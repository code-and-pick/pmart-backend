package com.pmart.order.orchestration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.pmart.common.test.EventEnvelopeFixtures;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwtConfiguration;
import com.pmart.order.orchestration.application.ReshipmentSagaService;
import com.pmart.order.orchestration.application.ShortageSagaService;
import com.pmart.order.orchestration.infrastructure.ReshipmentStore.ReshipmentRow;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.ObjectMapper;

/**
 * 오배송 재발송 사가(2026-11-16, ADR-0052 결정 4·5): MisdeliveryConfirmed → 배송으로 주문 찾기(dispatch) → 주문 원장의 보낼
 * 줄 → 원장 RESHIPMENT → 재고 예약(부족하면 가용까지 줄여 다시) → 예약 확정 → MFC 재발송 작업 지시(kind RESHIPMENT) → 못 보내는 줄
 * SHORTAGE → 부분 환불 → 완료. 같은 사건은 사가 하나, 전환 시각 전 사건은 시작 안 함. 재발송 작업의 결품은 주문 전체 취소 판정 없이 그 품목만. 참여 정본은
 * 한 WireMock.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.orchestration.mfc-inventory-base-url=${parts.url}",
      "pmart.orchestration.mfc-fulfillment-base-url=${parts.url}",
      "pmart.orchestration.claim-base-url=${parts.url}",
      "pmart.orchestration.order-base-url=${parts.url}",
      "pmart.orchestration.delivery-dispatch-base-url=${parts.url}",
      "pmart.orchestration.sweep-interval=PT1H",
      "pmart.orchestration.fulfillment.start-orders-confirmed-after=2026-11-01T00:00:00Z",
      "pmart.orchestration.reshipment.start-cases-confirmed-after=2026-11-16T00:00:00Z"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock(@ConfigureWireMock(name = "parts", baseUrlProperties = "parts.url"))
class ReshipmentSagaIT extends PostgresKafkaContainerSupport {

  @Autowired ObjectMapper mapper;
  @Autowired ReshipmentSagaService sagas;
  @Autowired ShortageSagaService shortages;
  @Autowired KafkaTemplate<String, String> kafka;

  @InjectWireMock("parts")
  WireMockServer parts;

  @BeforeEach
  void setUp() {
    parts.resetAll();
  }

  ResponseDefinitionBuilder json(int status, Object body) {
    return aResponse()
        .withStatus(status)
        .withHeader("Content-Type", "application/json")
        .withBody(mapper.writeValueAsString(body));
  }

  void confirmed(String caseId, String deliveryId, Instant at, String fault, String resolution) {
    String json =
        EventEnvelopeFixtures.envelope("MisdeliveryConfirmed")
            .from("case-service")
            .aggregate("Case", caseId, 1)
            .payload(
                Map.of(
                    "caseId",
                    caseId,
                    "deliveryId",
                    deliveryId,
                    "riderId",
                    "RD-1",
                    "confirmedBy",
                    "OP-CS",
                    "confirmedAt",
                    at.toString(),
                    "fault",
                    fault,
                    "resolution",
                    resolution))
            .buildJson();
    kafka.send("platform.case.v1", caseId, json).join();
  }

  /** ADR-0065: 라이더 잘못 + 고객 환불 선택 → 재고·작업 없이 전 품목 SHORTAGE·환불. 고객 잘못은 이 사가가 아니다. */
  @Test
  void riderFaultRefundSkipsReservationAndRefundsAllLines() throws Exception {
    String o = "ORD-" + UUID.randomUUID().toString().substring(0, 8);
    String deliveryId = "DLV-" + UUID.randomUUID();
    String caseId = "CASE-" + UUID.randomUUID();
    orderOf(deliveryId, o);
    participants(o);
    String customerCase = "CASE-" + UUID.randomUUID();
    confirmed(customerCase, deliveryId, Instant.now(), "CUSTOMER", "REDELIVER");
    confirmed(caseId, deliveryId, Instant.now(), "RIDER", "REFUND");
    ReshipmentRow s = await(caseId, "COMPLETED");
    assertThat(s.mode()).isEqualTo("REFUND");
    assertThat(s.claimId()).isEqualTo("CLM-RS-" + o);
    assertThat(sagas.byCase(customerCase)).isEmpty();
    parts.verify(0, postRequestedFor(urlPathEqualTo("/api/v1/internal/reservations")));
    parts.verify(0, postRequestedFor(urlPathEqualTo("/api/v1/internal/work-orders")));
    parts.verify(
        postRequestedFor(urlPathEqualTo("/api/v1/internal/orders/" + o + "/line-quantity-changes"))
            .withRequestBody(matchingJsonPath("$.reason", WireMock.equalTo("SHORTAGE")))
            .withRequestBody(
                matchingJsonPath("$.changes[1].orderLineId", WireMock.equalTo(o + "-L2"))));
    parts.verify(
        postRequestedFor(urlPathEqualTo("/api/v1/internal/claims/shortage-refunds"))
            .withRequestBody(matchingJsonPath("$.lines[0].qty", WireMock.equalTo("2")))
            .withRequestBody(matchingJsonPath("$.lines[1].qty", WireMock.equalTo("1"))));
  }

  /** ADR-0065: 고객 잘못 + 환불 → 재고·작업 없이 전 줄 SHORTAGE, 환불에 회수비 9,000원 공제. */
  @Test
  void customerFaultRefundDeductsRecoveryFee() throws Exception {
    String o = "ORD-" + UUID.randomUUID().toString().substring(0, 8);
    String deliveryId = "DLV-" + UUID.randomUUID();
    String caseId = "CASE-" + UUID.randomUUID();
    orderOf(deliveryId, o);
    participants(o);
    confirmed(caseId, deliveryId, Instant.now(), "CUSTOMER", "REFUND");
    ReshipmentRow s = await(caseId, "COMPLETED");
    assertThat(s.mode()).isEqualTo("CUSTOMER_REFUND");
    parts.verify(0, postRequestedFor(urlPathEqualTo("/api/v1/internal/reservations")));
    parts.verify(
        postRequestedFor(urlPathEqualTo("/api/v1/internal/claims/shortage-refunds"))
            .withRequestBody(matchingJsonPath("$.feeDeduction", WireMock.equalTo("9000")))
            .withRequestBody(matchingJsonPath("$.lines[1].qty", WireMock.equalTo("1"))));
  }

  void confirmed(String caseId, String deliveryId, Instant at) {
    String json =
        EventEnvelopeFixtures.envelope("MisdeliveryConfirmed")
            .from("case-service")
            .aggregate("Case", caseId, 1)
            .payload(
                Map.of(
                    "caseId",
                    caseId,
                    "deliveryId",
                    deliveryId,
                    "riderId",
                    "RD-1",
                    "confirmedBy",
                    "OP-CS",
                    "confirmedAt",
                    at.toString()))
            .buildJson();
    kafka.send("platform.case.v1", caseId, json).join();
  }

  /** 배송 → 주문, 주문 원장: L1(SKU A) 2개 전달, L2(SKU B) 1개 전달. */
  void orderOf(String deliveryId, String o) {
    parts.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/deliveries/" + deliveryId + "/assignment"))
            .willReturn(
                json(200, Map.of("deliveryId", deliveryId, "orderId", o, "riderId", "RD-1"))));
    parts.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/orders/" + o))
            .willReturn(
                json(
                    200,
                    Map.of(
                        "orderId",
                        o,
                        "status",
                        "COMPLETED",
                        "mfcId",
                        "MFC-T1",
                        "lines",
                        List.of(
                            line(o + "-L1", "E-0000000A", 2, "두부"),
                            line(o + "-L2", "E-0000000B", 1, "우유"))))));
  }

  static Map<String, Object> line(String id, String sku, int qty, String name) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("orderLineId", id);
    m.put("sku", sku);
    m.put("originalQty", qty);
    m.put("unfulfilledQty", 0);
    m.put("handedOverQty", qty);
    m.put("cancelledBeforeShipQty", 0);
    m.put("deliveredQty", qty);
    m.put("reshippedQty", 0);
    m.put("productName", name);
    return m;
  }

  /** 참여 정본: 두 줄 예약은 B 재고 0 으로 422, 한 줄(A)은 성공. */
  void participants(String o) {
    Map<String, Object> ok = Map.of("status", "APPLIED", "resultId", "x");
    parts.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/orders/" + o + "/line-quantity-changes"))
            .willReturn(json(200, ok)));
    parts.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/reservations"))
            .atPriority(1)
            .withRequestBody(matchingJsonPath("$.lines[1]"))
            .willReturn(
                json(
                    422,
                    Map.of(
                        "type",
                        "about:blank",
                        "title",
                        "재고 부족",
                        "status",
                        422,
                        "code",
                        "INSUFFICIENT_STOCK",
                        "traceId",
                        "t",
                        "detail",
                        "1개 SKU 의 가용 재고가 부족합니다.",
                        "details",
                        List.of(
                            Map.of(
                                "field",
                                "E-0000000B",
                                "reason",
                                "requestedQty=1;availableQty=0"))))));
    parts.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/reservations"))
            .atPriority(5)
            .willReturn(json(201, Map.of("status", "APPLIED", "resultId", "RSV-RS-" + o))));
    parts.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/reservations/RSV-RS-" + o))
            .willReturn(json(200, Map.of("reservationId", "RSV-RS-" + o, "state", "HELD"))));
    parts.stubFor(
        WireMock.post(
                urlPathEqualTo("/api/v1/internal/reservations/RSV-RS-" + o + "/confirmations"))
            .willReturn(json(200, ok)));
    parts.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/work-orders"))
            .willReturn(
                json(
                    201,
                    Map.of(
                        "fulfillmentId",
                        "FUL-RS-" + o,
                        "orderId",
                        o,
                        "workVersion",
                        1,
                        "decision",
                        "ACCEPTED"))));
    parts.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/claims/shortage-refunds"))
            .withRequestBody(matchingJsonPath("$.orderId", WireMock.equalTo(o)))
            .willReturn(json(201, Map.of("claimId", "CLM-RS-" + o, "state", "IN_PROGRESS"))));
  }

  ReshipmentRow await(String caseId, String state) throws InterruptedException {
    long until = System.currentTimeMillis() + 20_000;
    while (System.currentTimeMillis() < until) {
      var row = sagas.byCase(caseId);
      if (row.isPresent()) {
        if (state.equals(row.get().state())) {
          return row.get();
        }
        sagas.advance(row.get().sagaId());
      }
      Thread.sleep(200);
    }
    throw new AssertionError("사가 상태 " + state + " 안 됨: " + sagas.byCase(caseId));
  }

  @Test
  void resendsWhatIsInStockAndRefundsTheRest() throws Exception {
    String o = "ORD-" + UUID.randomUUID().toString().substring(0, 8);
    String deliveryId = "DLV-" + UUID.randomUUID();
    String caseId = "CASE-" + UUID.randomUUID();
    orderOf(deliveryId, o);
    participants(o);
    confirmed(caseId, deliveryId, Instant.now());
    confirmed(caseId, deliveryId, Instant.now()); // 같은 사건 다시 → 사가 하나
    ReshipmentRow s = await(caseId, "COMPLETED");
    assertThat(s.orderId()).isEqualTo(o);
    assertThat(s.claimId()).isEqualTo("CLM-RS-" + o);
    assertThat(s.fulfillmentId()).isEqualTo("FUL-RS-" + o);
    assertThat(s.shipmentId()).startsWith(o + "-RS-");
    assertThat(sagas.byOrder(o)).hasSize(1);

    // 원장 RESHIPMENT — 두 줄 모두(같은 수량을 재발송과 환불로 겹치지 않게, 못 보내는 줄은 이어서 SHORTAGE)
    parts.verify(
        postRequestedFor(urlPathEqualTo("/api/v1/internal/orders/" + o + "/line-quantity-changes"))
            .withHeader("Idempotency-Key", WireMock.equalTo(s.sagaId() + ":RECORD_RESHIPMENT"))
            .withRequestBody(matchingJsonPath("$.reason", WireMock.equalTo("RESHIPMENT")))
            .withRequestBody(matchingJsonPath("$.changes[1].qty", WireMock.equalTo("1"))));
    // 예약: 두 줄 → 422 → B 빼고 한 줄(키가 다르다)
    parts.verify(
        postRequestedFor(urlPathEqualTo("/api/v1/internal/reservations"))
            .withHeader("Idempotency-Key", WireMock.equalTo(s.sagaId() + ":RESERVE_INVENTORY:1"))
            .withRequestBody(matchingJsonPath("$.lines[0].sku", WireMock.equalTo("E-0000000A")))
            .withRequestBody(matchingJsonPath("$.lines[0].qty", WireMock.equalTo("2"))));
    // 확정은 결제 없이, 작업 지시는 재발송
    parts.verify(
        postRequestedFor(
                urlPathEqualTo("/api/v1/internal/reservations/RSV-RS-" + o + "/confirmations"))
            .withRequestBody(matchingJsonPath("$.orderId", WireMock.equalTo(o))));
    parts.verify(
        postRequestedFor(urlPathEqualTo("/api/v1/internal/work-orders"))
            .withRequestBody(matchingJsonPath("$.kind", WireMock.equalTo("RESHIPMENT")))
            .withRequestBody(matchingJsonPath("$.reshipmentRef", WireMock.equalTo(caseId)))
            .withRequestBody(matchingJsonPath("$.reservationId", WireMock.equalTo("RSV-RS-" + o)))
            .withRequestBody(
                matchingJsonPath("$.shipments[0].shipmentId", WireMock.equalTo(s.shipmentId())))
            .withRequestBody(
                matchingJsonPath("$.shipments[0].lines[0].productName", WireMock.equalTo("두부"))));
    parts.verify(
        0,
        postRequestedFor(urlPathEqualTo("/api/v1/internal/work-orders"))
            .withRequestBody(matchingJsonPath("$.shipments[0].lines[1]")));
    // 못 보내는 B 만 SHORTAGE·환불
    parts.verify(
        postRequestedFor(urlPathEqualTo("/api/v1/internal/orders/" + o + "/line-quantity-changes"))
            .withRequestBody(matchingJsonPath("$.reason", WireMock.equalTo("SHORTAGE")))
            .withRequestBody(
                matchingJsonPath("$.changes[0].orderLineId", WireMock.equalTo(o + "-L2"))));
    parts.verify(
        postRequestedFor(urlPathEqualTo("/api/v1/internal/claims/shortage-refunds"))
            .withRequestBody(
                matchingJsonPath("$.lines[0].orderLineId", WireMock.equalTo(o + "-L2")))
            .withRequestBody(matchingJsonPath("$.lines[0].qty", WireMock.equalTo("1"))));
  }

  @Test
  void caseBeforeSwitchTimeDoesNotStart() throws Exception {
    String caseId = "CASE-" + UUID.randomUUID();
    confirmed(caseId, "DLV-old", Instant.parse("2026-11-15T00:00:00Z"));
    String marker = "CASE-" + UUID.randomUUID();
    String o = "ORD-" + UUID.randomUUID().toString().substring(0, 8);
    orderOf("DLV-m", o);
    participants(o);
    confirmed(marker, "DLV-m", Instant.now()); // 같은 파티션 순서 표지 — 이것이 생기면 앞 사건은 처리됐다
    await(marker, "COMPLETED");
    assertThat(sagas.byCase(caseId)).isEmpty();
  }

  @Test
  void shortageInReshipmentWorkSkipsWholeOrderCheck() throws Exception {
    // 재발송 작업의 결품(purpose MISDELIVERY_RESHIPMENT) → 주문 원장 조회(전체 취소 판정) 없이 그 품목만
    String o = "ORD-" + UUID.randomUUID().toString().substring(0, 8);
    Map<String, Object> ok = Map.of("status", "APPLIED", "resultId", "x");
    parts.stubFor(
        WireMock.post(
                urlPathEqualTo(
                    "/api/v1/internal/work-orders/FUL-RS-" + o + "/shortage-resolutions"))
            .willReturn(json(200, ok)));
    parts.stubFor(
        WireMock.post(
                urlPathEqualTo("/api/v1/internal/reservations/RSV-RS-" + o + "/shortfall-releases"))
            .willReturn(json(200, ok)));
    parts.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/orders/" + o + "/line-quantity-changes"))
            .willReturn(json(200, ok)));
    parts.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/claims/shortage-refunds"))
            .willReturn(json(201, Map.of("claimId", "CLM-S-" + o))));
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("orderId", o);
    p.put("fulfillmentId", "FUL-RS-" + o);
    p.put("orderLineId", o + "-L1");
    p.put("sku", "E-0000000A");
    p.put("shortQty", 2);
    p.put("reason", "SHORT");
    p.put("mfcId", "MFC-T1");
    p.put("reservationId", "RSV-RS-" + o);
    p.put("purpose", "MISDELIVERY_RESHIPMENT");
    p.put("caseId", "CASE-X");
    p.put("reportedAt", Instant.now().toString());
    kafka
        .send(
            "mfc.fulfillment.v1",
            o,
            EventEnvelopeFixtures.envelope("LineShortageReported")
                .from("mfc-fulfillment-service")
                .aggregate("Fulfillment", o, 7)
                .payload(p)
                .buildJson())
        .join();
    long until = System.currentTimeMillis() + 20_000;
    String state = null;
    while (System.currentTimeMillis() < until) {
      var rows = shortages.byOrder(o);
      if (!rows.isEmpty()) {
        state = rows.get(0).state();
        if ("COMPLETED".equals(state)) {
          break;
        }
        shortages.advance(rows.get(0).sagaId());
      }
      Thread.sleep(200);
    }
    assertThat(state).isEqualTo("COMPLETED");
    parts.verify(0, WireMock.getRequestedFor(urlPathEqualTo("/api/v1/internal/orders/" + o)));
    parts.verify(
        0, postRequestedFor(urlPathEqualTo("/api/v1/internal/claims/system-cancellations")));
  }
}
