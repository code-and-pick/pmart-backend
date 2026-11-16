package com.pmart.mfc.fulfillment;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import com.pmart.mfc.fulfillment.application.WorkOrderService;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 작업 접수(슬라이스 6a): 접수 → 출고·PICK/PACK, 예약 주문 준비 시각(센터 설정 N, 없으면 CONFIG_REQUIRED), 예약 불일치 거절(재시도 대상) →
 * 새 작업 버전 접수, 이미 중지된 수량 빼기·전체 중지 거절, 접수 뒤 중지 → 작업 취소, 예약 조회 불가 → 503(기록 없음). 재고 정본은 WireMock.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.messaging.outbox.delete-after-insert=false",
      "pmart.fulfillment.mfc-inventory-base-url=${inv.url}",
      "pmart.fulfillment.pick-lead-time.[MFC-T1]=PT90M",
      "pmart.fulfillment.ready-sweep-interval=PT1H" // 시험이 직접 부른다
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock(@ConfigureWireMock(name = "inv", baseUrlProperties = "inv.url"))
class WorkOrderIT extends PostgresKafkaContainerSupport {

  static final String SVC = TestJwt.service("order-orchestration-service");

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired WorkOrderService works;

  @InjectWireMock("inv")
  WireMockServer inv;

  RestClient http;

  record Res(int status, JsonNode body) {}

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    inv.resetAll();
  }

  // ---------- 도우미 ----------

  void reservation(String orderId, String state, String partState) {
    inv.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/reservations/RSV-" + orderId))
            .willReturn(
                json(
                    200,
                    Map.of(
                        "reservationId",
                        "RSV-" + orderId,
                        "state",
                        state,
                        "parts",
                        List.of(
                            Map.of(
                                "partId", "P1", "sku", "E-00000001", "qty", 2, "state", partState),
                            Map.of(
                                "partId",
                                "P2",
                                "sku",
                                "E-00000002",
                                "qty",
                                1,
                                "state",
                                partState))))));
  }

  static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(
      int status, Object body) {
    return aResponse()
        .withStatus(status)
        .withHeader("Content-Type", "application/json")
        .withBody(new ObjectMapper().writeValueAsString(body));
  }

  Map<String, Object> confirmBody(String orderId, String mfcId, int workVersion, Instant slot) {
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("orderId", orderId);
    b.put("workVersion", workVersion);
    b.put("sagaId", "S2-" + orderId);
    b.put("mfcId", mfcId);
    b.put("reservationId", "RSV-" + orderId);
    b.put("deliveryMode", slot == null ? "SINGLE" : "SCHEDULED");
    b.put("outOfStockPreference", "CANCEL_ITEM");
    if (slot != null) {
      b.put(
          "arrivalWindow",
          Map.of("start", slot.toString(), "end", slot.plus(Duration.ofHours(1)).toString()));
    }
    b.put(
        "shipments",
        List.of(
            Map.of(
                "shipmentId",
                orderId + "-S1",
                "weightG",
                900,
                "lines",
                List.of(
                    Map.of(
                        "orderLineId",
                        orderId + "-L1",
                        "sku",
                        "E-00000001",
                        "qty",
                        2,
                        "productName",
                        "국산콩 두부 300g",
                        "unitsPerSku",
                        1,
                        "weightG",
                        600),
                    Map.of("orderLineId", orderId + "-L2", "sku", "E-00000002", "qty", 1)))));
    return b;
  }

  Res post(String path, String key, Object body) {
    var e =
        http.post()
            .uri(path)
            .headers(
                h -> {
                  h.setBearerAuth(SVC);
                  h.set("Idempotency-Key", key);
                  h.set("X-Source-System", "order-orchestration-service");
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  Res get(String path) {
    var e =
        http.get().uri(path).headers(h -> h.setBearerAuth(SVC)).retrieve().toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  JsonNode task(JsonNode workOrder, String type) {
    for (JsonNode t : workOrder.path("tasks")) {
      if (type.equals(t.path("taskType").asString())) {
        return t;
      }
    }
    throw new AssertionError("작업 없음 " + type + " " + workOrder);
  }

  int outbox(String type, String orderId) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM outbox_event WHERE type = ? AND aggregateid = ?",
        Integer.class,
        type,
        orderId);
  }

  static String order() {
    return "ORD-" + UUID.randomUUID().toString().substring(0, 8);
  }

  // ---------- 시험 ----------

  @Test
  void immediateOrderAcceptedWithReadyPickAndWaitingPack() {
    String o = order();
    reservation(o, "CONFIRMED", "CONFIRMED");
    Res r = post("/internal/work-orders", "k1-" + o, confirmBody(o, "MFC-T1", 1, null));
    assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
    assertThat(r.body().path("decision").asString()).isEqualTo("ACCEPTED");
    assertThat(r.body().path("workVersion").asInt()).isEqualTo(1);
    assertThat(r.body().path("shipmentIds").path(0).asString()).isEqualTo(o + "-S1");
    assertThat(r.body().path("taskIds")).hasSize(2);
    String fid = r.body().path("fulfillmentId").asString();

    // 같은 키 재전송·다른 키 같은 버전 → 같은 결정(새로 만들지 않음)
    Res same = post("/internal/work-orders", "k1-" + o, confirmBody(o, "MFC-T1", 1, null));
    assertThat(same.body().path("fulfillmentId").asString()).isEqualTo(fid);
    Res other = post("/internal/work-orders", "k2-" + o, confirmBody(o, "MFC-T1", 1, null));
    assertThat(other.status()).isEqualTo(201);
    assertThat(other.body().path("fulfillmentId").asString()).isEqualTo(fid);
    assertThat(other.body().path("taskIds")).hasSize(2);
    // 접수된 주문에 새 버전 → 409
    assertThat(post("/internal/work-orders", "k3-" + o, confirmBody(o, "MFC-T1", 2, null)).status())
        .isEqualTo(409);

    Res w = get("/internal/work-orders?orderId=" + o);
    assertThat(w.status()).isEqualTo(200);
    assertThat(w.body().path("acceptance").asString()).isEqualTo("ACCEPTED");
    assertThat(task(w.body(), "PICK").path("state").asString()).isEqualTo("READY");
    assertThat(task(w.body(), "PICK").path("plannedQty").asInt()).isEqualTo(3);
    assertThat(task(w.body(), "PACK").path("state").asString()).isEqualTo("WAITING");
    assertThat(w.body().path("shipments").path(0).path("state").asString()).isEqualTo("PREPARING");
    assertThat(get("/internal/work-orders/" + fid).body().path("orderId").asString()).isEqualTo(o);
    assertThat(
            jdbc.queryForObject(
                "SELECT product_name FROM shipment_line WHERE order_line_id = ?",
                String.class,
                o + "-L1"))
        .isEqualTo("국산콩 두부 300g");
    assertThat(outbox("WorkAccepted", o)).isEqualTo(1);
    assertThat(get("/internal/work-orders?orderId=NONE").status()).isEqualTo(404);
  }

  @Test
  void scheduledOrderPickReadyAtSlotStartMinusLeadTimeOrConfigRequired() {
    Instant slot = Instant.now().plus(Duration.ofHours(3)).truncatedTo(ChronoUnit.SECONDS);
    String a = order();
    reservation(a, "CONFIRMED", "CONFIRMED");
    assertThat(post("/internal/work-orders", "k-" + a, confirmBody(a, "MFC-T1", 1, slot)).status())
        .isEqualTo(201);
    JsonNode pa = task(get("/internal/work-orders?orderId=" + a).body(), "PICK");
    assertThat(pa.path("state").asString()).isEqualTo("WAITING");
    assertThat(Instant.parse(pa.path("readyAt").asString()))
        .isEqualTo(slot.minus(Duration.ofMinutes(90)));
    assertThat(pa.path("holdReason").isNull()).isTrue();

    // 설정이 없는 센터 → 작업은 접수, PICK 만 CONFIG_REQUIRED 대기
    String b = order();
    reservation(b, "CONFIRMED", "CONFIRMED");
    Res rb = post("/internal/work-orders", "k-" + b, confirmBody(b, "MFC-NOCONF", 1, slot));
    assertThat(rb.body().path("decision").asString()).isEqualTo("ACCEPTED");
    JsonNode pb = task(get("/internal/work-orders?orderId=" + b).body(), "PICK");
    assertThat(pb.path("state").asString()).isEqualTo("WAITING");
    assertThat(pb.path("holdReason").asString()).isEqualTo("CONFIG_REQUIRED");

    // 준비 시각이 지나면 sweep 이 READY 로
    jdbc.update(
        "UPDATE task SET ready_at = now() - interval '1 minute' WHERE task_id = ?",
        pa.path("taskId").asString());
    works.sweepReady();
    assertThat(
            task(get("/internal/work-orders?orderId=" + a).body(), "PICK").path("state").asString())
        .isEqualTo("READY");
    // 설정 없는 센터는 그대로 대기
    assertThat(
            task(get("/internal/work-orders?orderId=" + b).body(), "PICK")
                .path("holdReason")
                .asString())
        .isEqualTo("CONFIG_REQUIRED");
  }

  @Test
  void inventoryMismatchRejectedRetryableThenNewWorkVersionAccepted() {
    String o = order();
    reservation(o, "CONFIRMED", "BLOCKED");
    Res r1 = post("/internal/work-orders", "v1-" + o, confirmBody(o, "MFC-T1", 1, null));
    assertThat(r1.status()).isEqualTo(201);
    assertThat(r1.body().path("decision").asString()).isEqualTo("REJECTED");
    assertThat(r1.body().path("rejectReason").asString()).isEqualTo("INVENTORY_MISMATCH");
    assertThat(r1.body().path("retryable").asBoolean()).isTrue();
    assertThat(r1.body().path("taskIds")).isEmpty();
    assertThat(outbox("WorkRejected", o)).isEqualTo(1);
    assertThat(get("/internal/work-orders?orderId=" + o).body().path("tasks")).isEmpty();

    // 원래 예약이 다시 확정 → 새 작업 버전 접수
    reservation(o, "CONFIRMED", "CONFIRMED");
    Res r2 = post("/internal/work-orders", "v2-" + o, confirmBody(o, "MFC-T1", 2, null));
    assertThat(r2.body().path("decision").asString()).isEqualTo("ACCEPTED");
    assertThat(r2.body().path("fulfillmentId").asString())
        .isEqualTo(r1.body().path("fulfillmentId").asString());
    JsonNode w = get("/internal/work-orders?orderId=" + o).body();
    assertThat(w.path("workVersion").asInt()).isEqualTo(2);
    assertThat(w.path("tasks")).hasSize(2);
    // 옛 버전 재전달은 그 버전의 결정(거절)을 돌려준다
    Res again = post("/internal/work-orders", "v1b-" + o, confirmBody(o, "MFC-T1", 1, null));
    assertThat(again.body().path("decision").asString()).isEqualTo("REJECTED");
    assertThat(outbox("WorkAccepted", o)).isEqualTo(1);
  }

  /**
   * 오배송 재발송(2026-11-16, ADR-0052 결정 4): 접수된 주문에 kind RESHIPMENT 작업 지시를 하나 더 — 자기 예약으로, 근거 사건당 하나,
   * 원래 작업 중지 수량은 빼지 않는다, 사건에 purpose·caseId, 주문 조회는 원래 작업 지시 그대로.
   */
  @Test
  void reshipmentIsSecondWorkOrderForSameOrder() {
    String o = order();
    reservation(o, "CONFIRMED", "CONFIRMED");
    // 원래 주문: L1 1개 중지 뒤 접수(PICK 2)
    post(
        "/internal/stop-requests",
        "s-" + o,
        Map.of(
            "stopRequestId",
            "STOP-" + o,
            "orderId",
            o,
            "scope",
            "PARTIAL",
            "lines",
            List.of(Map.of("orderLineId", o + "-L1", "qty", 1))));
    Res first = post("/internal/work-orders", "k-" + o, confirmBody(o, "MFC-T1", 1, null));
    assertThat(first.body().path("decision").asString()).isEqualTo("ACCEPTED");
    String original = first.body().path("fulfillmentId").asString();

    String caseId = "CASE-" + UUID.randomUUID();
    String rsv = "RSV-RS-" + o;
    inv.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/reservations/" + rsv))
            .willReturn(
                json(
                    200,
                    Map.of(
                        "reservationId",
                        rsv,
                        "state",
                        "CONFIRMED",
                        "parts",
                        List.of(
                            Map.of(
                                "partId",
                                "P1",
                                "sku",
                                "E-00000001",
                                "qty",
                                2,
                                "state",
                                "CONFIRMED"),
                            Map.of(
                                "partId",
                                "P2",
                                "sku",
                                "E-00000002",
                                "qty",
                                1,
                                "state",
                                "CONFIRMED"))))));
    Map<String, Object> body = confirmBody(o, "MFC-T1", 1, null);
    body.put("reservationId", rsv);
    body.put("kind", "RESHIPMENT");
    body.put("reshipmentRef", caseId);
    body.put(
        "shipments",
        List.of(
            Map.of(
                "shipmentId",
                o + "-RS1",
                "lines",
                List.of(
                    Map.of("orderLineId", o + "-L1", "sku", "E-00000001", "qty", 2),
                    Map.of("orderLineId", o + "-L2", "sku", "E-00000002", "qty", 1)))));
    // reshipmentRef 없이 → 400
    Map<String, Object> noRef = new LinkedHashMap<>(body);
    noRef.remove("reshipmentRef");
    assertThat(post("/internal/work-orders", "rs0-" + o, noRef).status()).isEqualTo(400);

    Res r = post("/internal/work-orders", "rs-" + o, body);
    assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
    assertThat(r.body().path("decision").asString()).isEqualTo("ACCEPTED");
    String fid = r.body().path("fulfillmentId").asString();
    assertThat(fid).isNotEqualTo(original);
    assertThat(r.body().path("shipmentIds").path(0).asString()).isEqualTo(o + "-RS1");
    // 원래 주문의 중지 수량을 빼지 않는다 — L1 2 + L2 1
    assertThat(task(get("/internal/work-orders/" + fid).body(), "PICK").path("plannedQty").asInt())
        .isEqualTo(3);
    // 같은 근거 다시(다른 키) → 같은 작업 지시
    assertThat(
            post("/internal/work-orders", "rs2-" + o, body).body().path("fulfillmentId").asString())
        .isEqualTo(fid);
    // 주문 조회·원래 접수는 그대로
    assertThat(get("/internal/work-orders?orderId=" + o).body().path("fulfillmentId").asString())
        .isEqualTo(original);
    JsonNode accepted =
        mapper.readTree(
            jdbc.queryForObject(
                "SELECT payload::text FROM outbox_event WHERE type = 'WorkAccepted' AND"
                    + " aggregateid = ?",
                String.class,
                fid));
    assertThat(accepted.path("payload").path("purpose").asString())
        .isEqualTo("MISDELIVERY_RESHIPMENT");
    assertThat(accepted.path("payload").path("caseId").asString()).isEqualTo(caseId);
    assertThat(outbox("WorkAccepted", o)).isEqualTo(1); // 원래 주문 집계는 원래 접수 하나
    // 같은 근거로 다른 주문 → 409
    String other = order();
    Map<String, Object> wrong = new LinkedHashMap<>(body);
    wrong.put("orderId", other);
    assertThat(post("/internal/work-orders", "rs3-" + o, wrong).status()).isEqualTo(409);
  }

  @Test
  void stoppedQuantitiesSubtractedAndFullStopRejected() {
    String o = order();
    reservation(o, "CONFIRMED", "CONFIRMED");
    // 접수 전 부분 중지(L1 1개)
    assertThat(
            post(
                    "/internal/stop-requests",
                    "s-" + o,
                    Map.of(
                        "stopRequestId",
                        "STOP-" + o,
                        "orderId",
                        o,
                        "scope",
                        "PARTIAL",
                        "lines",
                        List.of(Map.of("orderLineId", o + "-L1", "qty", 1))))
                .status())
        .isEqualTo(200);
    assertThat(post("/internal/work-orders", "k-" + o, confirmBody(o, "MFC-T1", 1, null)).status())
        .isEqualTo(201);
    assertThat(
            task(get("/internal/work-orders?orderId=" + o).body(), "PICK")
                .path("plannedQty")
                .asInt())
        .isEqualTo(2); // L1 1 + L2 1

    String f = order();
    reservation(f, "CONFIRMED", "CONFIRMED");
    post(
        "/internal/stop-requests",
        "s-" + f,
        Map.of(
            "stopRequestId",
            "STOP-" + f,
            "orderId",
            f,
            "scope",
            "FULL",
            "lines",
            List.of(
                Map.of("orderLineId", f + "-L1", "qty", 2),
                Map.of("orderLineId", f + "-L2", "qty", 1))));
    Res r = post("/internal/work-orders", "k-" + f, confirmBody(f, "MFC-T1", 1, null));
    assertThat(r.body().path("decision").asString()).isEqualTo("REJECTED");
    assertThat(r.body().path("rejectReason").asString()).isEqualTo("ALREADY_STOPPED");
    assertThat(r.body().path("retryable").asBoolean()).isFalse();
  }

  @Test
  void stopAfterAcceptanceCancelsTasks() {
    String o = order();
    reservation(o, "CONFIRMED", "CONFIRMED");
    post("/internal/work-orders", "k-" + o, confirmBody(o, "MFC-T1", 1, null));
    Res s =
        post(
            "/internal/stop-requests",
            "s-" + o,
            Map.of(
                "stopRequestId",
                "STOP-" + o,
                "orderId",
                o,
                "scope",
                "FULL",
                "lines",
                List.of(
                    Map.of("orderLineId", o + "-L1", "qty", 2),
                    Map.of("orderLineId", o + "-L2", "qty", 1))));
    assertThat(s.status()).isEqualTo(200);
    assertThat(s.body().path("notStartedLines")).hasSize(2);
    JsonNode w = get("/internal/work-orders?orderId=" + o).body();
    assertThat(task(w, "PICK").path("state").asString()).isEqualTo("CANCELLED");
    assertThat(task(w, "PICK").path("cancelledQty").asInt()).isEqualTo(3);
    assertThat(task(w, "PICK").path("remainingQty").asInt()).isZero();
    assertThat(task(w, "PACK").path("state").asString()).isEqualTo("CANCELLED");
    assertThat(w.path("shipments").path(0).path("state").asString()).isEqualTo("CANCELLED");
  }

  @Test
  void inventoryUnavailableIs503AndNothingRecorded() {
    String o = order();
    inv.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/reservations/RSV-" + o))
            .willReturn(aResponse().withStatus(503)));
    Res r = post("/internal/work-orders", "k-" + o, confirmBody(o, "MFC-T1", 1, null));
    assertThat(r.status()).isEqualTo(503);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM work_order WHERE order_id = ?", Integer.class, o))
        .isZero();
    // 같은 키 재전송은 처음부터 다시 판단한다
    reservation(o, "CONFIRMED", "CONFIRMED");
    Res again = post("/internal/work-orders", "k-" + o, confirmBody(o, "MFC-T1", 1, null));
    assertThat(again.status()).isEqualTo(201);
    assertThat(again.body().path("decision").asString()).isEqualTo("ACCEPTED");
  }
}
