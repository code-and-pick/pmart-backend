package com.pmart.claim;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.pmart.claim.application.ReturnFeeService;
import com.pmart.common.test.EventEnvelopeFixtures;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 반품 — 라이더 인계 뒤 회수(CL-1, 2026-11-17): 고객센터 접수(역할·인계 수량 심사·면제 사유) → 회수비 9,000원 결제(payment 결제
 * 시도·PaymentApproved) → 회수 요청(ReturnPickupRequested) → delivery 회수 배송 사건 → MFC
 * 반입(ReturnItemReceived)·검수 (ReturnInspected) → 검수 수량 원단가 부분 환불 → RefundResult 로 CLOSED. 회수 실패 두
 * 번이면 RECOVERY_FAILED, 상담원 재요청(회수비 다시 받지 않음). 회수비 기한이 지나면 취소, 그 뒤 승인은 보상 취소. order·payment·auth 는 한
 * WireMock.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.messaging.outbox.delete-after-insert=false",
      "pmart.security.service-token.client-secret=it-secret",
      "pmart.security.service-token.token-uri=${deps.url}/api/v1/internal/service-tokens",
      "pmart.claim.order-base-url=${deps.url}",
      "pmart.claim.payment-base-url=${deps.url}",
      "pmart.claim.refund-sweep-interval=PT1H", // 시험이 재개를 직접 부른다
      // 컨테이너는 클래스마다 다시 뜬다 — 취소 시험과 같은 설정이면 캐시된 컨텍스트(지난 컨테이너 주소)를 다시 쓰므로 따로
      "pmart.test.context=return-flow"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock(@ConfigureWireMock(name = "deps", baseUrlProperties = "deps.url"))
class ReturnClaimFlowIT extends PostgresKafkaContainerSupport {

  static final String SKU = "E-00000001";

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired ReturnFeeService fees;

  @InjectWireMock("deps")
  WireMockServer deps;

  RestClient http;

  record Res(int status, JsonNode body) {}

  final String cs = TestJwt.operator("STAFF-CS-1", List.of("CS_AGENT"), List.of());
  final String admin = TestJwt.operator("STAFF-ADM-1", List.of("OPERATOR_ADMIN"), List.of());

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    deps.resetAll();
    deps.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/service-tokens"))
            .willReturn(
                json(
                    201,
                    Map.of(
                        "access_token",
                        TestJwt.service("claim-service"),
                        "token_type",
                        "Bearer",
                        "expires_in",
                        300))));
  }

  ResponseDefinitionBuilder json(int status, Object body) {
    return aResponse()
        .withStatus(status)
        .withHeader("Content-Type", "application/json")
        .withBody(mapper.writeValueAsString(body));
  }

  /** order 원장: 1,900원 × 10개, 인계 handedOver 개(라이더 픽업·전달). */
  void order(String orderId, String accountId, int handedOver) {
    order(orderId, accountId, handedOver, -1);
  }

  /** delivered = 전달 완료 수량(-1 이면 인계 수량과 같게 — 전달 끝). */
  void order(String orderId, String accountId, int handedOver, int delivered) {
    Map<String, Object> o = new LinkedHashMap<>();
    o.put("orderId", orderId);
    o.put("accountId", accountId);
    o.put("status", handedOver == 0 ? "PREPARING" : "COMPLETED");
    o.put("version", 3);
    o.put("mfcId", "MFC-1");
    o.put("paymentAttemptId", "PAY-" + orderId);
    o.put("approvedAmount", 19_100);
    o.put("itemAmount", 19_000);
    o.put("deliveryTip", 0);
    o.put("bagFee", 100);
    o.put("totalAmount", 19_100);
    o.put("confirmedAt", "2026-11-17T01:00:00Z");
    Map<String, Object> line = new LinkedHashMap<>();
    line.put("orderLineId", orderId + "-L1");
    line.put("sku", SKU);
    line.put("quantity", 10);
    line.put("unitPrice", 1_900);
    line.put("lineAmount", 19_000);
    line.put("discountShare", 0);
    line.put("originalQty", 10);
    line.put("unfulfilledQty", 10 - handedOver);
    line.put("handedOverQty", handedOver);
    line.put("deliveredQty", delivered < 0 ? handedOver : delivered); // 전달 완료(2026-11-17)
    line.put("cancelledBeforeShipQty", 0);
    o.put("lines", List.of(line));
    deps.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/orders/" + orderId))
            .willReturn(json(200, o)));
  }

  /** payment 결제 시도 생성: 시도 번호(키 끝 :FEE:<n>)마다 정한 ID. */
  void paymentStarts(String claimHint, int attemptNo, String attemptId) {
    deps.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/payments"))
            .withHeader("Idempotency-Key", WireMock.matching(".*:FEE:" + attemptNo))
            .willReturn(
                json(
                    201,
                    Map.of(
                        "paymentAttemptId",
                        attemptId,
                        "state",
                        "CREATED",
                        "orderId",
                        "RTF-" + claimHint,
                        "amount",
                        9000,
                        "checkout",
                        Map.of("provider", "TOSS_PAYMENTS")))));
  }

  void paymentState(String attemptId, String state) {
    deps.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/payments/" + attemptId))
            .willReturn(json(200, Map.of("paymentAttemptId", attemptId, "state", state))));
  }

  Res call(String method, String token, String path, Map<String, Object> body, String key) {
    var spec =
        "POST".equals(method)
            ? http.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body)
            : http.get().uri(path);
    var e =
        spec.headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", key);
                  h.set("X-Source-System", "backoffice-web");
                })
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  Res post(String token, String path, Map<String, Object> body) {
    return call("POST", token, path, body, UUID.randomUUID().toString());
  }

  Res get(String token, String path) {
    return call("GET", token, path, Map.of(), "-");
  }

  static Map<String, Object> returnBody(String orderId, int qty, String faultCode) {
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("orderId", orderId);
    b.put("type", "RETURN");
    b.put("reason", faultCode == null ? "CHANGE_OF_MIND" : "DAMAGED_IN_DELIVERY");
    b.put("channel", "CS");
    b.put("lines", List.of(Map.of("orderLineId", orderId + "-L1", "qty", qty)));
    b.put("identityCheck", Map.of("method", "ORDER_DETAILS_MATCH"));
    if (faultCode != null) {
      b.put("faultCode", faultCode);
    }
    return b;
  }

  void send(String topic, String type, String aggregateType, String id, Map<String, Object> p) {
    String json =
        EventEnvelopeFixtures.envelope(type)
            .from("it")
            .aggregate(aggregateType, id, 2)
            .payload(p)
            .buildJson();
    kafka.send(topic, id, json).join();
  }

  Res await(String token, String path, Predicate<JsonNode> done) throws InterruptedException {
    Res r = get(token, path);
    for (int i = 0; i < 150 && !done.test(r.body()); i++) {
      Thread.sleep(100);
      r = get(token, path);
    }
    return r;
  }

  static void until(BooleanSupplier done) throws InterruptedException {
    for (int i = 0; i < 150 && !done.getAsBoolean(); i++) {
      Thread.sleep(100);
    }
  }

  int outbox(String type, String aggregateId) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM outbox_event WHERE type = ? AND aggregateid = ?",
        Integer.class,
        type,
        aggregateId);
  }

  List<JsonNode> outboxPayloads(String type, String aggregateId) {
    List<JsonNode> out = new ArrayList<>();
    for (String raw :
        jdbc.queryForList(
            "SELECT payload::text FROM outbox_event WHERE type = ? AND aggregateid = ?"
                + " ORDER BY created_at",
            String.class,
            type,
            aggregateId)) {
      out.add(mapper.readTree(raw).path("payload"));
    }
    return out;
  }

  void deliveryFailed(String deliveryId, String claimId) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("deliveryId", deliveryId);
    if (claimId != null) {
      p.put("claimId", claimId);
    }
    p.put("reason", "CUSTOMER_ABSENT");
    p.put("contactAttempts", 2);
    p.put("failedAt", "2026-11-17T05:00:00Z");
    // delivery 가 실제로 내는 회수 실패 사건(DD-3, PC 5 — ReturnPickupFailed.v1)
    send("delivery.delivery.v1", "ReturnPickupFailed", "Delivery", deliveryId, p);
  }

  void deliveryCreated(String deliveryId, String claimId, String orderId, String pickupRequestId) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("deliveryId", deliveryId);
    p.put("orderId", orderId);
    p.put("shipmentId", "RTN-" + claimId);
    p.put("type", "RETURN");
    p.put("purpose", "CLAIM_RETURN");
    p.put("claimId", claimId);
    p.put("pickupRequestId", pickupRequestId);
    p.put("mfcId", "MFC-1");
    p.put("dropAddressRef", "mfc:MFC-1");
    send("delivery.delivery.v1", "DeliveryCreated", "Delivery", deliveryId, p);
  }

  /** 접수 심사·역할 → 회수비 결제 → 회수 → 반입·검수 → 원단가 환불 → 종결. 같은 검수가 다시 와도 환불은 한 번. */
  @Test
  void customerReasonReturnPaysFeeThenRecoveredInspectedAndRefunded() throws Exception {
    String account = "CU-" + UUID.randomUUID();
    String customer = TestJwt.customer(account);
    String orderId = "ORD-" + UUID.randomUUID();
    order(orderId, account, 10);

    // 고객 앱 반품 422, 역할 없는 직원 403, 상담원 취소 422, 본인 확인 없음 400
    assertThat(
            post(customer, "/claims", returnBody(orderId, 3, null)).body().path("code").asString())
        .isEqualTo("UNSUPPORTED_ACTION");
    assertThat(
            post(
                    TestJwt.operator("STAFF-RO", List.of("RIDER_OPS"), List.of()),
                    "/claims",
                    returnBody(orderId, 3, null))
                .status())
        .isEqualTo(403);
    Map<String, Object> cancel = new LinkedHashMap<>(returnBody(orderId, 3, null));
    cancel.put("type", "CANCEL");
    assertThat(post(cs, "/claims", cancel).body().path("code").asString())
        .isEqualTo("UNSUPPORTED_ACTION");
    Map<String, Object> noIdentity = new LinkedHashMap<>(returnBody(orderId, 3, null));
    noIdentity.remove("identityCheck");
    assertThat(post(cs, "/claims", noIdentity).status()).isEqualTo(400);
    Map<String, Object> other = new LinkedHashMap<>(returnBody(orderId, 3, null));
    other.put("identityCheck", Map.of("method", "OTHER"));
    assertThat(post(cs, "/claims", other).status()).isEqualTo(400);
    assertThat(post(cs, "/claims", returnBody(orderId, 11, null)).body().path("code").asString())
        .isEqualTo("QUANTITY_EXCEEDS_AVAILABLE");

    // 접수: 고객 사유 → 회수비 결제 대기, 결제 시도(주문 참조 RTF-<claim>, 9,000원, 고객 계정)
    paymentStarts("x", 1, "pay-fee-1-" + orderId.substring(4, 12));
    String attempt1 = "pay-fee-1-" + orderId.substring(4, 12);
    String key = UUID.randomUUID().toString();
    Res opened = call("POST", cs, "/claims", returnBody(orderId, 3, null), key);
    assertThat(opened.status()).as(opened.body().toString()).isEqualTo(201);
    String claimId = opened.body().path("claimId").asString();
    assertThat(opened.body().path("type").asString()).isEqualTo("RETURN");
    assertThat(opened.body().path("state").asString()).isEqualTo("FEE_PENDING");
    assertThat(opened.body().path("channel").asString()).isEqualTo("CS");
    assertThat(opened.body().path("fees").path("returnPickupFee").asInt()).isEqualTo(9_000);
    assertThat(opened.body().path("feePayment").path("state").asString()).isEqualTo("WAITING");
    // 같은 키 재생
    assertThat(
            call("POST", cs, "/claims", returnBody(orderId, 3, null), key)
                .body()
                .path("claimId")
                .asString())
        .isEqualTo(claimId);
    // 반품 가능 수량 = 인계 10 − 진행 3
    assertThat(post(cs, "/claims", returnBody(orderId, 8, null)).body().path("code").asString())
        .isEqualTo("QUANTITY_EXCEEDS_AVAILABLE");

    Res waiting =
        await(
            customer,
            "/claims/" + claimId,
            b -> attempt1.equals(b.path("feePayment").path("paymentAttemptId").asString(null)));
    assertThat(waiting.body().path("feePayment").path("amount").asInt()).isEqualTo(9_000);
    assertThat(waiting.body().path("feePayment").path("orderName").asString()).isEqualTo("반품 회수비");
    deps.verify(
        postRequestedFor(urlPathEqualTo("/api/v1/internal/payments"))
            .withHeader("Idempotency-Key", WireMock.equalTo(claimId + ":FEE:1"))
            .withRequestBody(matchingJsonPath("$.orderId", WireMock.equalTo("RTF-" + claimId)))
            .withRequestBody(matchingJsonPath("$.amount", WireMock.equalTo("9000")))
            .withRequestBody(matchingJsonPath("$.customerRef", WireMock.equalTo(account))));
    assertThat(outbox("ClaimOpened", claimId)).isEqualTo(1);
    until(() -> outbox("ReturnFeePaymentRequested", claimId) == 1);
    assertThat(
            outboxPayloads("ReturnFeePaymentRequested", claimId)
                .get(0)
                .path("paymentAttemptId")
                .asString())
        .isEqualTo(attempt1);
    assertThat(outbox("ReturnPickupRequested", claimId)).isZero(); // 결제 전에는 회수 요청 없음

    // 고객은 목록·상세로 본다(남의 것 404)
    assertThat(
            get(customer, "/claims?type=RETURN").body().path("items").findValuesAsString("claimId"))
        .contains(claimId);
    assertThat(get(TestJwt.customer("CU-OTHER"), "/claims/" + claimId).status()).isEqualTo(404);

    // 결제 승인 → IN_PROGRESS + 회수 요청 1번째
    send(
        "order.payment.v1",
        "PaymentApproved",
        "PaymentAttempt",
        attempt1,
        Map.of(
            "paymentAttemptId",
            attempt1,
            "pgTransactionId",
            "tx-1",
            "orderRef",
            "RTF-" + claimId,
            "amount",
            9000,
            "currency",
            "KRW",
            "approvedAt",
            "2026-11-17T02:00:00Z"));
    Res paid =
        await(
            customer, "/claims/" + claimId, b -> "IN_PROGRESS".equals(b.path("state").asString()));
    assertThat(paid.body().path("feePayment").path("state").asString()).isEqualTo("PAID");
    assertThat(paid.body().path("pickup").path("state").asString()).isEqualTo("REQUESTED");
    assertThat(paid.body().path("pickup").path("attempts").asInt()).isEqualTo(1);
    JsonNode req = outboxPayloads("ReturnPickupRequested", claimId).get(0);
    assertThat(req.path("attempt").asInt()).isEqualTo(1);
    assertThat(req.path("purpose").asString()).isEqualTo("CLAIM_RETURN");
    assertThat(req.path("pickupRequestId").asString()).isEqualTo(claimId + "-P1");
    assertThat(req.path("pickupAddressRef").asString()).isEqualTo("order:" + orderId);
    assertThat(req.path("mfcId").asString()).isEqualTo("MFC-1");
    assertThat(req.path("lines").path(0).path("qty").asInt()).isEqualTo(3);

    // 회수 배송 생성 → 수거 → 반납
    String deliveryId = "DLV-R-" + UUID.randomUUID();
    deliveryCreated(deliveryId, claimId, orderId, claimId + "-P1");
    Map<String, Object> picked = new LinkedHashMap<>();
    picked.put("returnPickupId", deliveryId);
    picked.put("claimId", claimId);
    picked.put("deliveryId", deliveryId);
    picked.put("purpose", "CLAIM_RETURN");
    picked.put("lines", List.of(Map.of("orderLineId", orderId + "-L1", "qty", 3)));
    picked.put("pickedUpAt", "2026-11-17T03:00:00Z");
    send("delivery.delivery.v1", "ReturnPickedUp", "Delivery", deliveryId, picked);
    Res pickedUp =
        await(
            customer,
            "/claims/" + claimId,
            b -> "PICKED_UP".equals(b.path("pickup").path("state").asString()));
    assertThat(pickedUp.body().path("pickup").path("deliveryId").asString()).isEqualTo(deliveryId);
    assertThat(pickedUp.body().path("lines").path(0).path("pickedUpQty").asInt()).isEqualTo(3);
    Map<String, Object> delivered = new LinkedHashMap<>(picked);
    delivered.remove("pickedUpAt");
    delivered.put("deliveredAt", "2026-11-17T03:30:00Z");
    send("delivery.delivery.v1", "ReturnDelivered", "Delivery", deliveryId, delivered);
    await(
        customer,
        "/claims/" + claimId,
        b -> "DELIVERED_TO_STORE".equals(b.path("pickup").path("state").asString()));

    // MFC 반입(격리) → 수령 수량
    Map<String, Object> received = new LinkedHashMap<>();
    received.put("returnReceiptId", "RRC-1-" + claimId);
    received.put("claimId", claimId);
    received.put("returnPickupId", deliveryId);
    received.put("orderId", orderId);
    received.put("mfcId", "MFC-1");
    received.put(
        "lines",
        List.of(Map.of("sku", SKU, "qty", 3, "lot", "LOT-1", "orderLineId", orderId + "-L1")));
    received.put("receivedAt", "2026-11-17T04:00:00Z");
    send("mfc.inbound.v1", "ReturnItemReceived", "ReturnReceipt", "RRC-1-" + claimId, received);
    Res got =
        await(
            customer,
            "/claims/" + claimId,
            b -> b.path("lines").path(0).path("receivedQty").asInt() == 3);
    assertThat(got.body().path("pickup").path("state").asString()).isEqualTo("RECEIVED");
    assertThat(got.body().path("refunds").size()).isZero(); // 수령은 환불이 아니다

    // 검수(재판매 2·폐기 1) → 3 × 1,900 = 5,700 부분 환불(RESALE·DISPOSE 모두, 감액 없음)
    deps.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/payments/PAY-" + orderId + "/cancellations"))
            .willReturn(
                json(202, Map.of("status", "ACCEPTED", "requestId", "x", "resultId", "C-1"))));
    Map<String, Object> inspected = new LinkedHashMap<>();
    inspected.put("inspectionId", "RIN-1-" + claimId);
    inspected.put("claimId", claimId);
    inspected.put("returnReceiptId", "RRC-1-" + claimId);
    inspected.put("mfcId", "MFC-1");
    inspected.put(
        "lines",
        List.of(
            Map.of("sku", SKU, "lot", "LOT-1", "qty", 2, "disposition", "RESALE"),
            Map.of("sku", SKU, "lot", "LOT-1", "qty", 1, "disposition", "DISPOSE")));
    inspected.put("inspectedAt", "2026-11-17T04:30:00Z");
    send("mfc.quality.v1", "ReturnInspected", "ReturnInspection", "RIN-1-" + claimId, inspected);
    Res refunding =
        await(
            customer,
            "/claims/" + claimId,
            b -> "PG_PENDING".equals(b.path("refunds").path(0).path("state").asString()));
    JsonNode refund = refunding.body().path("refunds").path(0);
    assertThat(refund.path("amount").asInt()).isEqualTo(5_700);
    assertThat(refund.path("breakdown").path("bagFee").asInt()).isZero();
    assertThat(refunding.body().path("lines").path(0).path("inspection").path("resaleQty").asInt())
        .isEqualTo(2);
    assertThat(refunding.body().path("lines").path(0).path("inspection").path("disposeQty").asInt())
        .isEqualTo(1);
    assertThat(refunding.body().path("pickup").path("state").asString()).isEqualTo("INSPECTED");
    assertThat(refunding.body().path("state").asString()).isEqualTo("IN_PROGRESS");
    String intentId = refund.path("refundIntentId").asString();
    deps.verify(
        1,
        postRequestedFor(
                urlPathEqualTo("/api/v1/internal/payments/PAY-" + orderId + "/cancellations"))
            .withHeader("Idempotency-Key", WireMock.equalTo("REFUND-" + intentId))
            .withRequestBody(matchingJsonPath("$.amount", WireMock.equalTo("5700")))
            .withRequestBody(matchingJsonPath("$.scope", WireMock.equalTo("PARTIAL")))
            .withRequestBody(matchingJsonPath("$.reason", WireMock.equalTo("RETURN"))));
    JsonNode intent = outboxPayloads("RefundIntentCreated", claimId).get(0);
    assertThat(intent.path("basis").path("lines").size()).isEqualTo(2);

    // 같은 검수가 다른 사건 ID 로 다시 와도 환불은 한 번(업무 키)
    send("mfc.quality.v1", "ReturnInspected", "ReturnInspection", "RIN-1-" + claimId, inspected);
    send("mfc.inbound.v1", "ReturnItemReceived", "ReturnReceipt", "RRC-1-" + claimId, received);
    Thread.sleep(1_500);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM refund_intent WHERE claim_id = ?", Integer.class, claimId))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT received_qty FROM claim_line WHERE claim_id = ?", Integer.class, claimId))
        .isEqualTo(3);

    // PG 성공 → 모든 행 검수·환불 → CLOSED(RETURN_REFUNDED)
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("refundRequestId", "REFUND-" + intentId);
    result.put("refundIntentId", intentId);
    result.put("paymentAttemptId", "PAY-" + orderId);
    result.put("pgTransactionId", "tcxl_r1");
    result.put("amount", 5_700);
    result.put("result", "SUCCESS");
    send("order.payment.v1", "RefundResult", "PaymentAttempt", "PAY-" + orderId, result);
    Res closed =
        await(customer, "/claims/" + claimId, b -> "CLOSED".equals(b.path("state").asString()));
    assertThat(closed.body().path("state").asString()).isEqualTo("CLOSED");
    assertThat(outboxPayloads("ClaimClosed", claimId).get(0).path("outcome").asString())
        .isEqualTo("RETURN_REFUNDED");
    // 완료된 반품도 수량을 점유한다(10 − 3 = 7 까지)
    assertThat(
            post(cs, "/claims", returnBody(orderId, 8, "MISDELIVERY"))
                .body()
                .path("code")
                .asString())
        .isEqualTo("QUANTITY_EXCEEDS_AVAILABLE");
  }

  /** 회사 잘못(면제) → 바로 회수 요청. 첫 실패 자동 재요청, 두 번째 실패 RECOVERY_FAILED → 상담원 재요청(회수비 다시 안 받음). */
  @Test
  void waivedReturnRetriesOnceThenRecoveryFailedAndCsRetries() throws Exception {
    String account = "CU-" + UUID.randomUUID();
    String customer = TestJwt.customer(account);
    String orderId = "ORD-" + UUID.randomUUID();
    order(orderId, account, 10);
    // 인계 전 주문은 반품 접수 안 됨
    String notYet = "ORD-" + UUID.randomUUID();
    order(notYet, account, 0);
    assertThat(
            post(admin, "/claims", returnBody(notYet, 1, "DEFECTIVE"))
                .body()
                .path("code")
                .asString())
        .isEqualTo("NOT_DELIVERED_YET");
    // 라이더가 들고 있는(배달 중 — 인계 10, 전달 0) 주문도 반품 접수 안 됨(사용자 2026-11-17 "전달 완료 전에는 반품 불가")
    String inDelivery = "ORD-" + UUID.randomUUID();
    order(inDelivery, account, 10, 0);
    assertThat(
            post(admin, "/claims", returnBody(inDelivery, 1, "DEFECTIVE"))
                .body()
                .path("code")
                .asString())
        .isEqualTo("NOT_DELIVERED_YET");

    Res opened = post(admin, "/claims", returnBody(orderId, 2, "DAMAGED_IN_DELIVERY"));
    assertThat(opened.status()).as(opened.body().toString()).isEqualTo(201);
    String claimId = opened.body().path("claimId").asString();
    assertThat(opened.body().path("state").asString()).isEqualTo("IN_PROGRESS");
    assertThat(opened.body().path("faultCode").asString()).isEqualTo("DAMAGED_IN_DELIVERY");
    assertThat(opened.body().path("fees").path("returnPickupFee").asInt()).isZero();
    assertThat(
            opened.body().path("feePayment").isMissingNode()
                || opened.body().path("feePayment").isNull())
        .isTrue();
    assertThat(outbox("ReturnPickupRequested", claimId)).isEqualTo(1);
    assertThat(
            outboxPayloads("ReturnPickupRequested", claimId).get(0).path("feeWaived").asBoolean())
        .isTrue();

    // 1번째 회수 배송 실패(고객 부재) → 자동으로 2번째 요청
    String d1 = "DLV-R1-" + UUID.randomUUID();
    deliveryCreated(d1, claimId, orderId, claimId + "-P1");
    until(
        () ->
            d1.equals(
                jdbc.queryForObject(
                    "SELECT COALESCE(delivery_id, '') FROM return_pickup WHERE pickup_request_id = ?",
                    String.class,
                    claimId + "-P1")));
    deliveryFailed(d1, null);
    until(() -> outbox("ReturnPickupRequested", claimId) == 2);
    assertThat(outboxPayloads("ReturnPickupRequested", claimId).get(1).path("attempt").asInt())
        .isEqualTo(2);
    Res retried = get(customer, "/claims/" + claimId);
    assertThat(retried.body().path("state").asString()).isEqualTo("IN_PROGRESS");
    assertThat(retried.body().path("pickup").path("failedAttempts").asInt()).isEqualTo(1);
    // 같은 실패가 다시 와도(사건 ID 다름) 더 요청하지 않는다
    deliveryFailed(d1, null);
    Thread.sleep(1_000);
    assertThat(outbox("ReturnPickupRequested", claimId)).isEqualTo(2);

    // 2번째 실패(배송 연결 전 — claimId 로) → RECOVERY_FAILED + 상담원 재연락 사건
    deliveryFailed("DLV-R2-" + UUID.randomUUID(), claimId);
    Res failed =
        await(
            customer,
            "/claims/" + claimId,
            b -> "RECOVERY_FAILED".equals(b.path("state").asString()));
    assertThat(failed.body().path("pickup").path("state").asString()).isEqualTo("FAILED");
    assertThat(failed.body().path("pickup").path("failedAttempts").asInt()).isEqualTo(2);
    JsonNode rf = outboxPayloads("ReturnRecoveryFailed", claimId).get(0);
    assertThat(rf.path("attempts").asInt()).isEqualTo(2);
    assertThat(rf.path("accountId").asString()).isEqualTo(account);
    assertThat(rf.path("lastFailureReason").asString()).isEqualTo("CUSTOMER_ABSENT");

    // 상담원 조치: 고객 403, 사유 없음 400, 다시 회수 요청 → IN_PROGRESS + 3번째 요청, 회수비 결제는 없다
    Map<String, Object> retry = Map.of("action", "RETRY_PICKUP", "note", "고객과 통화 — 내일 오전 재방문 동의");
    assertThat(post(customer, "/claims/" + claimId + "/recovery-actions", retry).status())
        .isEqualTo(403);
    assertThat(
            post(
                    cs,
                    "/claims/" + claimId + "/recovery-actions",
                    Map.of("action", "RETRY_PICKUP", "note", ""))
                .status())
        .isEqualTo(400);
    Res again = post(cs, "/claims/" + claimId + "/recovery-actions", retry);
    assertThat(again.status()).as(again.body().toString()).isEqualTo(200);
    assertThat(again.body().path("state").asString()).isEqualTo("IN_PROGRESS");
    assertThat(again.body().path("pickup").path("attempts").asInt()).isEqualTo(3);
    assertThat(outboxPayloads("ReturnPickupRequested", claimId).get(2).path("attempt").asInt())
        .isEqualTo(3);
    // 면제 반품 — 회수비 결제 시도는 한 번도 없다(재요청에도)
    deps.verify(
        0,
        postRequestedFor(urlPathEqualTo("/api/v1/internal/payments"))
            .withHeader("Idempotency-Key", WireMock.matching(claimId + ":FEE:.*")));
    assertThat(
            jdbc.queryForObject(
                "SELECT action || ' ' || actor FROM claim_action WHERE claim_id = ?",
                String.class,
                claimId))
        .isEqualTo("RETRY_PICKUP STAFF-CS-1");
    // 진행 중에는 취소 조치 409
    assertThat(
            post(
                    cs,
                    "/claims/" + claimId + "/recovery-actions",
                    Map.of("action", "CANCEL", "note", "고객 변심"))
                .status())
        .isEqualTo(409);
  }

  /** 회수비 실패 → 고객이 새 결제 시도, 기한까지 미결제 → 취소, 그 뒤 늦은 승인 → 보상 취소. */
  @Test
  void unpaidFeeCancelsReturnAndLateApprovalIsCompensated() throws Exception {
    String account = "CU-" + UUID.randomUUID();
    String customer = TestJwt.customer(account);
    String orderId = "ORD-" + UUID.randomUUID();
    order(orderId, account, 5);
    String a1 = "pay-a1-" + UUID.randomUUID().toString().substring(0, 8);
    String a2 = "pay-a2-" + UUID.randomUUID().toString().substring(0, 8);
    paymentStarts("x", 1, a1);
    paymentStarts("x", 2, a2);
    String claimId =
        post(cs, "/claims", returnBody(orderId, 1, null)).body().path("claimId").asString();
    await(
        customer,
        "/claims/" + claimId,
        b -> a1.equals(b.path("feePayment").path("paymentAttemptId").asString(null)));

    // 첫 시도 실패 → 고객이 새 결제 시도(남은 404). 아직 쓸 수 있는 시도면 그대로
    paymentState(a1, "FAILED");
    assertThat(
            post(
                    TestJwt.customer("CU-OTHER"),
                    "/claims/" + claimId + "/fee-payment-attempts",
                    Map.of())
                .status())
        .isEqualTo(404);
    Res retry = post(customer, "/claims/" + claimId + "/fee-payment-attempts", Map.of());
    assertThat(retry.status()).as(retry.body().toString()).isEqualTo(200);
    assertThat(retry.body().path("feePayment").path("paymentAttemptId").asString()).isEqualTo(a2);
    paymentState(a2, "CREATED");
    assertThat(
            post(customer, "/claims/" + claimId + "/fee-payment-attempts", Map.of())
                .body()
                .path("feePayment")
                .path("paymentAttemptId")
                .asString())
        .isEqualTo(a2);
    assertThat(outbox("ReturnFeePaymentRequested", claimId)).isEqualTo(1); // 결제 필요 알림은 처음 한 번

    // 기한 지남(결제창에 안 들어옴) → 미결제 취소
    jdbc.update(
        "UPDATE return_fee SET deadline_at = now() - interval '1 minute' WHERE claim_id = ?",
        claimId);
    fees.advance(claimId);
    Res cancelled = get(customer, "/claims/" + claimId);
    assertThat(cancelled.body().path("state").asString()).isEqualTo("CANCELLED");
    assertThat(cancelled.body().path("feePayment").path("state").asString()).isEqualTo("UNPAID");
    assertThat(outboxPayloads("ClaimClosed", claimId).get(0).path("outcome").asString())
        .isEqualTo("RETURN_FEE_UNPAID");
    assertThat(outbox("ReturnPickupRequested", claimId)).isZero();
    assertThat(post(customer, "/claims/" + claimId + "/fee-payment-attempts", Map.of()).status())
        .isEqualTo(409);
    // 취소된 반품은 수량을 점유하지 않는다(면제 반품으로 — 결제 호출 없이)
    assertThat(post(cs, "/claims", returnBody(orderId, 5, "WRONG_SUBSTITUTE")).status())
        .isEqualTo(201);

    // 기한 뒤 늦은 승인 → 보상 취소(RETURN_FEE_TOO_LATE)
    deps.stubFor(
        WireMock.post(
                urlPathEqualTo("/api/v1/internal/payments/" + a2 + "/compensation-cancellations"))
            .willReturn(
                json(202, Map.of("status", "ACCEPTED", "requestId", "y", "resultId", "CC-1"))));
    send(
        "order.payment.v1",
        "PaymentApproved",
        "PaymentAttempt",
        a2,
        Map.of(
            "paymentAttemptId",
            a2,
            "pgTransactionId",
            "tx-late",
            "orderRef",
            "RTF-" + claimId,
            "amount",
            9000,
            "currency",
            "KRW",
            "approvedAt",
            "2026-11-18T02:00:00Z"));
    until(
        () ->
            "LATE_CANCELLED"
                .equals(
                    jdbc.queryForObject(
                        "SELECT state FROM return_fee WHERE claim_id = ?", String.class, claimId)));
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM return_fee WHERE claim_id = ?", String.class, claimId))
        .isEqualTo("LATE_CANCELLED");
    deps.verify(
        postRequestedFor(
                urlPathEqualTo("/api/v1/internal/payments/" + a2 + "/compensation-cancellations"))
            .withRequestBody(
                matchingJsonPath("$.reason", WireMock.equalTo("RETURN_FEE_TOO_LATE"))));
    assertThat(get(customer, "/claims/" + claimId).body().path("state").asString())
        .isEqualTo("CANCELLED");
  }
}
