package com.pmart.cases;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.pmart.common.test.EventEnvelopeFixtures;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 사건함(0.4.0, 2026-11-16 "사건함 공통 결정"): 도움요청 사건 → RIDER_HELP(재전달 하나), 전달 실패 → DELIVERY_INCIDENT, 위치
 * 끊김 두 번 → 사건 하나 + 재발 이력, 상담원은 보기만, RIDER_OPS 가 집고(남의 것 못 집음) 닫기, 오배송 확인 → MISDELIVERY +
 * MisdeliveryConfirmed, 사건 CaseOpened·CaseAssigned·CaseClosed.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.messaging.outbox.delete-after-insert=false",
      "pmart.cases.delivery-dispatch-base-url=${dispatch.url}",
      "pmart.test.context=case-inbox"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock({@ConfigureWireMock(name = "dispatch", baseUrlProperties = "dispatch.url")})
class CaseInboxIT extends PostgresKafkaContainerSupport {

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired KafkaTemplate<String, String> kafka;

  @InjectWireMock("dispatch")
  WireMockServer dispatch;

  final String ops = TestJwt.operator("op-ops-1", List.of("RIDER_OPS"), List.of());
  final String ops2 = TestJwt.operator("op-ops-2", List.of("RIDER_OPS"), List.of());
  final String cs = TestJwt.operator("op-cs-1", List.of("CS_AGENT"), List.of());
  final String admin = TestJwt.operator("op-admin", List.of("OPERATOR_ADMIN"), List.of());
  final String md = TestJwt.operator("op-md", List.of("MD"), List.of());

  record Res(int status, JsonNode body) {}

  Res send(String method, String token, String path, @Nullable Object body) {
    var spec =
        RestClient.builder()
            .requestFactory(new JdkClientHttpRequestFactory())
            .build()
            .method(HttpMethod.valueOf(method))
            .uri("http://localhost:" + port + "/api/v1" + path)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", UUID.randomUUID().toString());
                  h.set("X-Source-System", "backoffice-web");
                });
    var e =
        body == null
            ? spec
            : spec.contentType(MediaType.APPLICATION_JSON).body(mapper.writeValueAsString(body));
    return e.exchange(
        (req, res) -> {
          byte[] b = res.getBody().readAllBytes();
          return new Res(
              res.getStatusCode().value(),
              b.length == 0 ? mapper.createObjectNode() : mapper.readTree(b));
        });
  }

  void publish(String topic, String type, String key, Map<String, Object> payload, String from) {
    kafka
        .send(
            topic,
            key,
            EventEnvelopeFixtures.envelope(type)
                .from(from)
                .aggregate("Delivery", key, 1)
                .payload(payload)
                .buildJson())
        .join();
  }

  static <T> T await(Supplier<T> s, java.util.function.Predicate<T> ok)
      throws InterruptedException {
    Instant until = Instant.now().plus(Duration.ofSeconds(30));
    T v = s.get();
    while (!ok.test(v) && Instant.now().isBefore(until)) {
      Thread.sleep(200);
      v = s.get();
    }
    return v;
  }

  int count(String sql, Object... args) {
    return jdbc.queryForObject(sql, Integer.class, args);
  }

  int outbox(String caseId, String type) {
    return count(
        "SELECT count(*) FROM outbox_event WHERE aggregateid = ? AND type = ?", caseId, type);
  }

  @Test
  void helpRequestBecomesCaseThatOpsClaimAndClose() throws Exception {
    String deliveryId = "DLV-CI-" + UUID.randomUUID().toString().substring(0, 8);
    String incidentId = deliveryId + ":REQ-1";
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("incidentId", incidentId);
    p.put("deliveryId", deliveryId);
    p.put("riderId", "RD-1");
    p.put("type", "HELP_REQUEST");
    p.put("category", "ACCIDENT");
    p.put("stage", "PICKED_UP");
    p.put("orderId", "ORD-1");
    p.put("mfcId", "MFC-1");
    publish(
        "delivery.delivery.v1",
        "DeliveryIncidentRaised",
        deliveryId,
        p,
        "delivery-execution-service");
    publish(
        "delivery.delivery.v1",
        "DeliveryIncidentRaised",
        deliveryId,
        p,
        "delivery-execution-service");

    int n =
        await(
            () -> count("SELECT count(*) FROM case_record WHERE subject_ref = ?", deliveryId),
            c -> c > 0);
    Thread.sleep(1000);
    assertThat(count("SELECT count(*) FROM case_record WHERE subject_ref = ?", deliveryId))
        .as("같은 도움요청 두 번 — 사건 하나")
        .isEqualTo(1);
    assertThat(n).isEqualTo(1);

    Res list = send("GET", ops, "/cases?subjectRef=" + deliveryId + "&unassigned=true", null);
    assertThat(list.status()).isEqualTo(200);
    JsonNode item = list.body().path("items").get(0);
    String caseId = item.path("caseId").asString();
    assertThat(item.path("kind").asString()).isEqualTo("RIDER_HELP");
    assertThat(item.path("priority").asString()).as("사고 — 긴급").isEqualTo("P1");
    assertThat(item.path("assigneeGroup").asString()).isEqualTo("DELIVERY_OPS");
    assertThat(item.path("centerId").asString()).isEqualTo("MFC-1");
    assertThat(item.path("slaDueAt").isNull()).isFalse();
    assertThat(item.path("overdue").asBoolean()).isFalse();
    assertThat(item.path("refs").toString())
        .contains(incidentId, "ORD-1", "RD-1", "\"requestId\"", "REQ-1");
    assertThat(outbox(caseId, "CaseOpened")).isEqualTo(1);

    // 상담원은 보기만, 역할 없는 직원은 목록 403
    assertThat(send("GET", cs, "/cases/" + caseId, null).status()).isEqualTo(200);
    assertThat(
            send("POST", cs, "/cases/" + caseId + "/assignments", Map.of("assigneeId", "op-cs-1"))
                .status())
        .isEqualTo(403);
    assertThat(send("GET", md, "/cases", null).status()).isEqualTo(403);

    // 집기: 자기 자신만, 이미 집은 사건은 다른 직원이 못 집음
    assertThat(
            send("POST", ops, "/cases/" + caseId + "/assignments", Map.of("assigneeId", "op-ops-2"))
                .status())
        .isEqualTo(403);
    Res claimed =
        send("POST", ops, "/cases/" + caseId + "/assignments", Map.of("assigneeId", "op-ops-1"));
    assertThat(claimed.status()).as(claimed.body().toString()).isEqualTo(200);
    assertThat(claimed.body().path("state").asString()).isEqualTo("ASSIGNED");
    assertThat(claimed.body().path("assigneeId").asString()).isEqualTo("op-ops-1");
    assertThat(
            send(
                    "POST",
                    ops2,
                    "/cases/" + caseId + "/assignments",
                    Map.of("assigneeId", "op-ops-2"))
                .status())
        .isEqualTo(409);
    assertThat(outbox(caseId, "CaseAssigned")).isEqualTo(1);
    assertThat(
            send("GET", ops, "/cases?subjectRef=" + deliveryId + "&unassigned=true", null)
                .body()
                .path("items"))
        .isEmpty();

    // 닫기: 다른 직원 403, 맡은 직원 200, 이력
    Map<String, Object> close =
        Map.of("resolution", "RESOLVED", "summary", "라이더 통화 — 안전 확인, 다른 라이더로 회수");
    assertThat(send("POST", ops2, "/cases/" + caseId + "/closures", close).status()).isEqualTo(403);
    Res closed = send("POST", ops, "/cases/" + caseId + "/closures", close);
    assertThat(closed.status()).as(closed.body().toString()).isEqualTo(200);
    assertThat(closed.body().path("state").asString()).isEqualTo("CLOSED");
    assertThat(closed.body().path("closure").path("resolution").asString()).isEqualTo("RESOLVED");
    assertThat(closed.body().path("history")).hasSize(3);
    assertThat(outbox(caseId, "CaseClosed")).isEqualTo(1);
    assertThat(send("GET", ops, "/cases?subjectRef=" + deliveryId, null).body().path("items"))
        .as("기본 목록은 열린 것만")
        .isEmpty();
    assertThat(
            send("GET", ops, "/cases?subjectRef=" + deliveryId + "&state=CLOSED", null)
                .body()
                .path("items"))
        .hasSize(1);
  }

  @Test
  void deliveryFailureAndLocationStaleAndMisdelivery() throws Exception {
    String deliveryId = "DLV-CF-" + UUID.randomUUID().toString().substring(0, 8);
    publish(
        "delivery.delivery.v1",
        "DeliveryIncidentRaised",
        deliveryId,
        Map.of(
            "incidentId",
            deliveryId + ":F1",
            "deliveryId",
            deliveryId,
            "type",
            "HELP_REQUEST",
            "category",
            "DELIVERY_FAILURE"),
        "delivery-execution-service");
    assertThat(
            await(
                () ->
                    jdbc.queryForList(
                        "SELECT case_type FROM case_record WHERE subject_ref = ?",
                        String.class,
                        deliveryId),
                l -> !l.isEmpty()))
        .containsExactly("DELIVERY_INCIDENT");

    // 위치 끊김 두 번 → 사건 하나 + 재발 이력
    Map<String, Object> stale =
        Map.of(
            "riderId",
            "RD-STALE",
            "deliveryId",
            deliveryId,
            "lastSeenAt",
            Instant.now().minusSeconds(240).toString(),
            "detectedAt",
            Instant.now().toString());
    publish(
        "delivery.tracking.v1",
        "RiderLocationStale",
        "RD-STALE",
        stale,
        "delivery-tracking-service");
    publish(
        "delivery.tracking.v1",
        "RiderLocationStale",
        "RD-STALE",
        stale,
        "delivery-tracking-service");
    await(
        () ->
            count(
                "SELECT count(*) FROM case_history h JOIN case_record c ON c.case_id = h.case_id"
                    + " WHERE c.case_type = 'RIDER_LOCATION_STALE' AND c.subject_ref = ? AND h.action = 'RECURRED'",
                deliveryId),
        c -> c > 0);
    assertThat(
            count(
                "SELECT count(*) FROM case_record WHERE case_type = 'RIDER_LOCATION_STALE' AND subject_ref = ?",
                deliveryId))
        .isEqualTo(1);

    // 오배송 확인 — 상담원이 연다(배정 라이더는 dispatch), 같은 배송 두 번째는 409
    dispatch.stubFor(
        get(urlPathMatching("/api/v1/internal/deliveries/.+/assignment"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"riderId\":\"RD-9\",\"deliveryId\":\"" + deliveryId + "\"}")));
    Map<String, Object> mis =
        Map.of(
            "kind", "MISDELIVERY",
            "subjectType", "DELIVERY",
            "subjectRef", deliveryId,
            "summary", "고객 통화 — 옆 동으로 전달됨(사진 확인)",
            "evidence",
                List.of(Map.of("system", "delivery-execution", "ref", "PHOTO-1", "kind", "PHOTO")));
    Res created = send("POST", cs, "/cases", mis);
    assertThat(created.status()).as(created.body().toString()).isEqualTo(201);
    String caseId = created.body().path("caseId").asString();
    assertThat(created.body().path("kind").asString()).isEqualTo("MISDELIVERY");
    assertThat(outbox(caseId, "MisdeliveryConfirmed")).isEqualTo(1);
    String payload =
        jdbc.queryForObject(
            "SELECT payload::text FROM outbox_event WHERE aggregateid = ? AND type = 'MisdeliveryConfirmed'",
            String.class,
            caseId);
    assertThat(payload).contains("RD-9", "PHOTO-1", "op-cs-1").doesNotContain("옆 동");
    assertThat(send("POST", cs, "/cases", mis).status()).isEqualTo(409);
    assertThat(send("POST", md, "/cases", mis).status()).isEqualTo(403);
    Map<String, Object> other = new LinkedHashMap<>(mis);
    other.put("kind", "RIDER_HELP");
    assertThat(send("POST", admin, "/cases", other).status()).isEqualTo(422);

    // 관리자는 다른 사람에게 배정
    Res a =
        send("POST", admin, "/cases/" + caseId + "/assignments", Map.of("assigneeId", "op-ops-2"));
    assertThat(a.status()).isEqualTo(200);
    assertThat(a.body().path("assigneeId").asString()).isEqualTo("op-ops-2");

    // ADR-0065(2026-11-16): 귀책·고객 선택 — 사건 refs·MisdeliveryConfirmed 에 싣고, 맞지 않는 조합은 422
    String d2 = "DLV-F-" + UUID.randomUUID();
    Map<String, Object> rider = new LinkedHashMap<>(mis);
    rider.put("subjectRef", d2);
    rider.put("misdelivery", Map.of("fault", "RIDER", "resolution", "REFUND"));
    Res r2 = send("POST", cs, "/cases", rider);
    assertThat(r2.status()).as(r2.body().toString()).isEqualTo(201);
    String c2 = r2.body().path("caseId").asString();
    assertThat(r2.body().toString()).contains("\"fault\"", "RIDER", "REFUND");
    String p2 =
        jdbc.queryForObject(
            "SELECT payload::text FROM outbox_event WHERE aggregateid = ? AND type = 'MisdeliveryConfirmed'",
            String.class,
            c2);
    assertThat(p2).contains("\"fault\": \"RIDER\"", "\"resolution\": \"REFUND\"");
    String d3 = "DLV-F-" + UUID.randomUUID();
    Map<String, Object> sys = new LinkedHashMap<>(mis);
    sys.put("subjectRef", d3);
    sys.put("misdelivery", Map.of("fault", "SYSTEM")); // 기본 RESHIP
    Res r3 = send("POST", cs, "/cases", sys);
    assertThat(r3.status()).isEqualTo(201);
    assertThat(
            jdbc.queryForObject(
                "SELECT payload::text FROM outbox_event WHERE aggregateid = ? AND type = 'MisdeliveryConfirmed'",
                String.class,
                r3.body().path("caseId").asString()))
        .contains("\"resolution\": \"RESHIP\"");
    String d4 = "DLV-F-" + UUID.randomUUID();
    Map<String, Object> cust = new LinkedHashMap<>(mis);
    cust.put("subjectRef", d4);
    cust.put("misdelivery", Map.of("fault", "CUSTOMER")); // 기본 REDELIVER — 실제 주소 없으면 422
    Res noDest = send("POST", cs, "/cases", cust);
    assertThat(noDest.status()).isEqualTo(422);
    assertThat(noDest.body().path("code").asString()).isEqualTo("REDELIVERY_DESTINATION_REQUIRED");
    cust.put("misdelivery", Map.of("fault", "CUSTOMER", "resolution", "RESHIP"));
    Res wrong = send("POST", cs, "/cases", cust);
    assertThat(wrong.body().path("code").asString()).isEqualTo("INVALID_MISDELIVERY_RESOLUTION");
    cust.put(
        "misdelivery",
        Map.of("fault", "CUSTOMER", "resolution", "REDELIVER", "redeliveryDestinationId", "RDD-1"));
    Res ok = send("POST", cs, "/cases", cust);
    assertThat(ok.status()).as(ok.body().toString()).isEqualTo(201);
    assertThat(
            jdbc.queryForObject(
                "SELECT payload::text FROM outbox_event WHERE aggregateid = ? AND type = 'MisdeliveryConfirmed'",
                String.class,
                ok.body().path("caseId").asString()))
        .contains("\"fault\": \"CUSTOMER\"", "\"redeliveryDestinationId\": \"RDD-1\"");
  }

  void publishOrder(
      String topic,
      String type,
      String aggregateType,
      String key,
      Map<String, Object> payload,
      String from) {
    kafka
        .send(
            topic,
            key,
            EventEnvelopeFixtures.envelope(type)
                .from(from)
                .aggregate(aggregateType, key, 1)
                .payload(payload)
                .buildJson())
        .join();
  }

  List<String> kindsOf(String subjectRef) {
    return jdbc.queryForList(
        "SELECT case_type FROM case_record WHERE subject_ref = ? ORDER BY case_type",
        String.class,
        subjectRef);
  }

  /**
   * R-3(2026-11-16 사용자 — 기한 1시간·"이상만"·멈춘 사가는 안 함): 정상 보상·조회 중 결제 불명은 사건이 아니고, 보상 실패·대사 필요·결제 불일치 →
   * RECONCILIATION, 상신된 결제 불명 → PAYMENT_UNKNOWN. 같은 주문 재발은 이력만(결제 불명 반복은 이력도 없음). 관리자만 보고 집는다.
   */
  @Test
  void sagaAndPaymentExceptionsOpenAdminOnlyCases() throws Exception {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    String orderId = "ORD-R3-" + suffix;
    String sagaId = "SAGA-R3-" + suffix;
    String saga = "order-orchestration-service";

    // 정상 보상 → 사건 없음, 이어서 보상 실패 → RECONCILIATION(같은 파티션이라 앞 사건이 먼저 처리됨)
    Map<String, Object> comp = new LinkedHashMap<>();
    comp.put("sagaId", sagaId);
    comp.put("orderId", orderId);
    comp.put("resourceType", "INVENTORY_RESERVATION");
    comp.put("resourceId", "RSV-1");
    comp.put("result", "SUCCESS");
    comp.put("executedAt", Instant.now().toString());
    publishOrder("order.saga.v1", "CompensationExecuted", "Saga", sagaId, comp, saga);
    Map<String, Object> failed = new LinkedHashMap<>(comp);
    failed.put("result", "FAILED");
    publishOrder("order.saga.v1", "CompensationExecuted", "Saga", sagaId, failed, saga);
    assertThat(await(() -> kindsOf(orderId), l -> !l.isEmpty())).containsExactly("RECONCILIATION");

    // 같은 주문의 대사 필요 → 사건 그대로 + 재발 이력
    publishOrder(
        "order.saga.v1",
        "ReconciliationRequired",
        "Saga",
        sagaId,
        Map.of(
            "sagaId",
            sagaId,
            "orderId",
            orderId,
            "mismatchType",
            "AMOUNT",
            "details",
            Map.of(),
            "detectedAt",
            Instant.now().toString()),
        saga);
    await(
        () ->
            count(
                "SELECT count(*) FROM case_history h JOIN case_record c ON c.case_id = h.case_id"
                    + " WHERE c.subject_ref = ? AND h.action = 'RECURRED'",
                orderId),
        c -> c > 0);
    assertThat(kindsOf(orderId)).containsExactly("RECONCILIATION");

    // 결제 불명: 조회 중(escalated=false)은 사건 아님, 상신 → PAYMENT_UNKNOWN, 다시 와도 이력 없음
    String payOrder = "ORD-R3P-" + suffix;
    String attempt = "PAY-R3-" + suffix;
    Map<String, Object> unknown = new LinkedHashMap<>();
    unknown.put("paymentAttemptId", attempt);
    unknown.put("orderRef", payOrder);
    unknown.put("inquiryCount", 3);
    unknown.put("nextInquiryAt", Instant.now().plusSeconds(60).toString());
    unknown.put("escalated", false);
    publishOrder(
        "order.payment.v1", "PaymentResultUnknown", "Payment", attempt, unknown, "payment-service");
    Map<String, Object> escalated = new LinkedHashMap<>(unknown);
    escalated.put("inquiryCount", 5);
    escalated.put("escalated", true);
    publishOrder(
        "order.payment.v1",
        "PaymentResultUnknown",
        "Payment",
        attempt,
        escalated,
        "payment-service");
    publishOrder(
        "order.payment.v1",
        "PaymentResultUnknown",
        "Payment",
        attempt,
        escalated,
        "payment-service");
    // 다른 주문의 결제 불일치 → RECONCILIATION(같은 토픽·키 뒤에 넣어 앞 사건이 다 처리된 뒤를 기다린다)
    String mismatchOrder = "ORD-R3M-" + suffix;
    publishOrder(
        "order.payment.v1",
        "PaymentMismatch",
        "Payment",
        attempt,
        Map.of(
            "paymentAttemptId",
            attempt + "-M",
            "orderRef",
            mismatchOrder,
            "mismatchType",
            "AMOUNT",
            "expectedAmount",
            10000,
            "approvedAmount",
            9000,
            "detectedAt",
            Instant.now().toString()),
        "payment-service");
    assertThat(await(() -> kindsOf(mismatchOrder), l -> !l.isEmpty()))
        .containsExactly("RECONCILIATION");
    assertThat(kindsOf(payOrder)).as("상신 두 번 — 사건 하나").containsExactly("PAYMENT_UNKNOWN");
    assertThat(
            count(
                "SELECT count(*) FROM case_history h JOIN case_record c ON c.case_id = h.case_id"
                    + " WHERE c.subject_ref = ? AND h.action = 'RECURRED'",
                payOrder))
        .as("결제 불명 반복은 이력도 없음")
        .isZero();

    // 관리자만 본다 — 배송 팀·상담원 목록엔 없고 단건은 404
    Res adminList = send("GET", admin, "/cases?subjectRef=" + orderId, null);
    assertThat(adminList.status()).isEqualTo(200);
    JsonNode item = adminList.body().path("items").get(0);
    String caseId = item.path("caseId").asString();
    assertThat(item.path("kind").asString()).isEqualTo("RECONCILIATION");
    assertThat(item.path("assigneeGroup").asString()).isEqualTo("ORDER_OPS");
    assertThat(item.path("refs").toString()).contains(orderId, sagaId, "RSV-1");
    Instant due = Instant.parse(item.path("slaDueAt").asString());
    assertThat(due)
        .isBetween(Instant.now().plusSeconds(50 * 60), Instant.now().plusSeconds(61 * 60));
    assertThat(outbox(caseId, "CaseOpened")).isEqualTo(1);
    assertThat(send("GET", ops, "/cases?subjectRef=" + orderId, null).body().path("items"))
        .isEmpty();
    assertThat(send("GET", cs, "/cases?kind=PAYMENT_UNKNOWN", null).body().path("items")).isEmpty();
    assertThat(send("GET", ops, "/cases/" + caseId, null).status()).isEqualTo(404);
    assertThat(
            send("POST", ops, "/cases/" + caseId + "/assignments", Map.of("assigneeId", "op-ops-1"))
                .status())
        .isEqualTo(404);

    Res claimed =
        send("POST", admin, "/cases/" + caseId + "/assignments", Map.of("assigneeId", "op-admin"));
    assertThat(claimed.status()).as(claimed.body().toString()).isEqualTo(200);
    assertThat(claimed.body().path("history").toString()).contains("RECURRED");
  }

  Map<String, Object> dispatchFailed(String deliveryId, String reason) {
    return Map.of(
        "deliveryId",
        deliveryId,
        "orderId",
        "ORD-" + deliveryId,
        "reason",
        reason,
        "attempts",
        1,
        "failedAt",
        Instant.now().toString());
  }

  /**
   * 배차 실패(2026-11-16 사용자 — 중간안·지원센터·10분): 라이더 없음은 첫 실패에 사건 없음 → 두 번째에 DISPATCH_FAILED → 세 번째는 이력,
   * 시스템 장애는 첫 실패에 사건. 배송 쪽이라 RIDER_OPS 가 집고 상담원은 본다.
   */
  @Test
  void dispatchFailureOpensOnSystemFailureOrSecondRetryFailure() throws Exception {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    String retrying = "DLV-DF-" + suffix;
    String broken = "DLV-DS-" + suffix;
    String dispatch = "delivery-dispatch-service";

    publish(
        "delivery.delivery.v1",
        "DispatchFailed",
        retrying,
        dispatchFailed(retrying, "NO_RIDER"),
        dispatch);
    await(
        () ->
            count(
                "SELECT count(*) FROM dispatch_failure_watch WHERE delivery_id = ? AND failures = 1",
                retrying),
        c -> c > 0);
    assertThat(count("SELECT count(*) FROM case_record WHERE subject_ref = ?", retrying))
        .as("라이더 없음 첫 실패 — 아직 사건 아님")
        .isZero();

    publish(
        "delivery.delivery.v1",
        "DispatchFailed",
        retrying,
        dispatchFailed(retrying, "EXPIRED"),
        dispatch);
    publish(
        "delivery.delivery.v1",
        "DispatchFailed",
        retrying,
        dispatchFailed(retrying, "NO_RIDER"),
        dispatch);
    await(
        () ->
            count(
                "SELECT count(*) FROM case_history h JOIN case_record c ON c.case_id = h.case_id"
                    + " WHERE c.subject_ref = ? AND h.action = 'RECURRED'",
                retrying),
        c -> c > 0);
    assertThat(
            jdbc.queryForList(
                "SELECT case_type FROM case_record WHERE subject_ref = ?", String.class, retrying))
        .containsExactly("DISPATCH_FAILED");

    publish(
        "delivery.delivery.v1",
        "DispatchFailed",
        broken,
        dispatchFailed(broken, "SYSTEM_FAILURE"),
        dispatch);
    assertThat(
            await(
                () ->
                    jdbc.queryForList(
                        "SELECT case_type FROM case_record WHERE subject_ref = ?",
                        String.class,
                        broken),
                l -> !l.isEmpty()))
        .as("시스템 장애 — 첫 실패에 사건")
        .containsExactly("DISPATCH_FAILED");

    Res list = send("GET", ops, "/cases?subjectRef=" + retrying, null);
    assertThat(list.status()).isEqualTo(200);
    JsonNode item = list.body().path("items").get(0);
    String caseId = item.path("caseId").asString();
    assertThat(item.path("kind").asString()).isEqualTo("DISPATCH_FAILED");
    assertThat(item.path("assigneeGroup").asString()).isEqualTo("DELIVERY_OPS");
    assertThat(item.path("summary").asString()).contains("2번째", "응답 만료");
    assertThat(item.path("refs").toString()).contains(retrying, "ORD-" + retrying);
    Instant due = Instant.parse(item.path("slaDueAt").asString());
    assertThat(due)
        .isBetween(Instant.now().plusSeconds(8 * 60), Instant.now().plusSeconds(11 * 60));
    assertThat(outbox(caseId, "CaseOpened")).isEqualTo(1);
    assertThat(send("GET", cs, "/cases/" + caseId, null).status()).as("상담원은 본다").isEqualTo(200);
    Res claimed =
        send("POST", ops, "/cases/" + caseId + "/assignments", Map.of("assigneeId", "op-ops-1"));
    assertThat(claimed.status()).as(claimed.body().toString()).isEqualTo(200);
    assertThat(claimed.body().path("history").toString()).contains("RECURRED", "3번째");

    // 닫힌 사건은 닫힌 시각 최근 순(0.5.0) — 먼저 연 retrying 을 먼저 닫고 broken 을 나중에 닫으면 broken 이 앞
    Map<String, Object> close = Map.of("resolution", "RESOLVED", "summary", "배차 확인");
    assertThat(send("POST", ops, "/cases/" + caseId + "/closures", close).status()).isEqualTo(200);
    String brokenCase =
        send("GET", ops, "/cases?subjectRef=" + broken, null)
            .body()
            .path("items")
            .get(0)
            .path("caseId")
            .asString();
    Thread.sleep(5);
    assertThat(send("POST", ops, "/cases/" + brokenCase + "/closures", close).status())
        .isEqualTo(200);
    Res closedList = send("GET", ops, "/cases?kind=DISPATCH_FAILED&state=CLOSED", null);
    assertThat(closedList.body().path("items").findValuesAsString("caseId"))
        .containsSubsequence(brokenCase, caseId);
  }

  /**
   * 센터 관리자(2026-11-16 사용자 "추천대로", 배송 원문 §3.6): 자기 센터(토큰 centers)의 배차 실패 사건만 보기 — 다른 센터·센터 없는 사건·다른
   * 종류는 안 보이고(목록에 없음·단건 404), 집기는 없다(403).
   */
  @Test
  void mfcSupervisorSeesOwnCenterDispatchFailuresOnly() throws Exception {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    String mine = "DLV-SM-" + suffix;
    String other = "DLV-SO-" + suffix;
    String noCenter = "DLV-SN-" + suffix;
    String dispatchSvc = "delivery-dispatch-service";
    Map<String, Object> pm = new LinkedHashMap<>(dispatchFailed(mine, "SYSTEM_FAILURE"));
    pm.put("mfcId", "MFC-SUP-" + suffix);
    Map<String, Object> po = new LinkedHashMap<>(dispatchFailed(other, "SYSTEM_FAILURE"));
    po.put("mfcId", "MFC-OTHER-" + suffix);
    publish("delivery.delivery.v1", "DispatchFailed", mine, pm, dispatchSvc);
    publish("delivery.delivery.v1", "DispatchFailed", other, po, dispatchSvc);
    publish(
        "delivery.delivery.v1",
        "DispatchFailed",
        noCenter,
        dispatchFailed(noCenter, "SYSTEM_FAILURE"),
        dispatchSvc);
    for (String d : List.of(mine, other, noCenter)) {
      await(() -> count("SELECT count(*) FROM case_record WHERE subject_ref = ?", d), c -> c > 0);
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT center_id FROM case_record WHERE subject_ref = ?", String.class, mine))
        .as("사건에 실린 mfcId 가 사건 센터")
        .isEqualTo("MFC-SUP-" + suffix);

    String sup =
        TestJwt.operator("op-sup", List.of("MFC_SUPERVISOR"), List.of("MFC-SUP-" + suffix));
    Res list = send("GET", sup, "/cases?kind=DISPATCH_FAILED", null);
    assertThat(list.status()).isEqualTo(200);
    assertThat(list.body().path("items").findValuesAsString("subjectRef"))
        .contains(mine)
        .doesNotContain(other, noCenter);
    String mineCase = list.body().path("items").get(0).path("caseId").asString();
    assertThat(send("GET", sup, "/cases/" + mineCase, null).status()).isEqualTo(200);
    String otherCase =
        jdbc.queryForObject(
            "SELECT case_id FROM case_record WHERE subject_ref = ?", String.class, other);
    assertThat(send("GET", sup, "/cases/" + otherCase, null).status())
        .as("다른 센터 — 있는지도 알리지 않음")
        .isEqualTo(404);
    assertThat(
            send("POST", sup, "/cases/" + mineCase + "/assignments", Map.of("assigneeId", "op-sup"))
                .status())
        .as("보기만")
        .isEqualTo(403);
    assertThat(send("GET", sup, "/cases?kind=RIDER_HELP", null).body().path("items"))
        .as("다른 종류는 안 보임")
        .isEmpty();
    String supNoCenter = TestJwt.operator("op-sup2", List.of("MFC_SUPERVISOR"), List.of());
    assertThat(send("GET", supNoCenter, "/cases", null).body().path("items")).isEmpty();
  }

  /**
   * 재개(0.6.0 — 2026-11-16 사용자 "1번 + 이전 사건 연결"): 닫힌 뒤 같은 대상으로 새 사건이 열리면 previousCaseId 로 잇고, 열린 사건이
   * 있는 동안 옛 사건 재개는 409 CASE_ALREADY_OPEN. 재개는 닫기와 같은 사람(팀·관리자), 사유 필수, 담당 비움·기한 새로,
   * 이력·CaseReopened.
   */
  @Test
  void reopenLinksPreviousAndClearsAssignee() {
    String deliveryId = "DLV-RO-" + UUID.randomUUID().toString().substring(0, 8);
    dispatch.stubFor(
        get(urlPathMatching("/api/v1/internal/deliveries/.+/assignment"))
            .willReturn(aResponse().withStatus(404)));
    Map<String, Object> mis =
        Map.of(
            "kind", "MISDELIVERY",
            "subjectType", "DELIVERY",
            "subjectRef", deliveryId,
            "summary", "고객 통화로 확인");
    Map<String, Object> close = Map.of("resolution", "RESOLVED", "summary", "회수 완료");
    Map<String, Object> reason = Map.of("reason", "회수 물건이 다른 집 것으로 확인됨");

    String first = send("POST", cs, "/cases", mis).body().path("caseId").asString();
    assertThat(
            send("POST", ops, "/cases/" + first + "/assignments", Map.of("assigneeId", "op-ops-1"))
                .status())
        .isEqualTo(200);
    assertThat(send("POST", ops, "/cases/" + first + "/reopenings", reason).status())
        .as("닫히지 않은 사건")
        .isEqualTo(409);
    assertThat(send("POST", ops, "/cases/" + first + "/closures", close).status()).isEqualTo(200);

    // 같은 배송으로 새 사건 — 이전 사건 연결, 그동안 옛 사건 재개는 409
    Res second = send("POST", cs, "/cases", mis);
    assertThat(second.status()).isEqualTo(201);
    String secondId = second.body().path("caseId").asString();
    assertThat(second.body().path("refs").toString()).contains("previousCaseId", first);
    Res dup = send("POST", ops, "/cases/" + first + "/reopenings", reason);
    assertThat(dup.status()).isEqualTo(409);
    assertThat(dup.body().path("code").asString()).isEqualTo("CASE_ALREADY_OPEN");
    assertThat(send("POST", admin, "/cases/" + secondId + "/closures", close).status())
        .isEqualTo(200);

    // 권한·입력
    assertThat(send("POST", cs, "/cases/" + first + "/reopenings", reason).status())
        .as("상담원은 보기만")
        .isEqualTo(403);
    assertThat(send("POST", md, "/cases/" + first + "/reopenings", reason).status()).isIn(403, 404);
    assertThat(send("POST", ops, "/cases/" + first + "/reopenings", Map.of("reason", " ")).status())
        .isEqualTo(400);

    // 재개 — 담당 비움, 기한 지금부터, 마지막 종결 내용은 남김
    Res r = send("POST", ops2, "/cases/" + first + "/reopenings", reason);
    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    assertThat(r.body().path("state").asString()).isEqualTo("REOPENED");
    assertThat(r.body().path("assigneeId").isNull() || r.body().path("assigneeId").isMissingNode())
        .isTrue();
    Instant due = Instant.parse(r.body().path("slaDueAt").asString());
    assertThat(due).isAfter(Instant.now().plusSeconds(20 * 60));
    assertThat(r.body().path("history").toString()).contains("REOPENED");
    assertThat(outbox(first, "CaseReopened")).isEqualTo(1);
    assertThat(
            send("GET", ops, "/cases?subjectRef=" + deliveryId, null)
                .body()
                .path("items")
                .findValuesAsString("caseId"))
        .as("다시 열린 사건으로 목록에")
        .containsExactly(first);

    // 다시 열린 동안 같은 배송 새 오배송은 409(열린 사건 하나)
    assertThat(send("POST", cs, "/cases", mis).status()).isEqualTo(409);
  }

  /**
   * 이관(0.7.0 — 2026-11-16 사용자 "A안"): 배송 쪽 사건만 지원센터 팀 ↔ 관리자 팀. 관리자 팀으로 간 사건은 지원센터가 보기만(집기·닫기 403),
   * 담당 비움·OPEN, 이력·CaseTransferred. 그 밖 팀·센터 이관 422, 같은 팀·닫힌 사건 409, 남이 맡은 것은 관리자만.
   */
  @Test
  void deliveryCaseTransfersBetweenSupportAndAdminTeams() {
    String deliveryId = "DLV-TR-" + UUID.randomUUID().toString().substring(0, 8);
    dispatch.stubFor(
        get(urlPathMatching("/api/v1/internal/deliveries/.+/assignment"))
            .willReturn(aResponse().withStatus(404)));
    String caseId =
        send(
                "POST",
                cs,
                "/cases",
                Map.of(
                    "kind", "MISDELIVERY",
                    "subjectType", "DELIVERY",
                    "subjectRef", deliveryId,
                    "summary", "환불이 얽힌 오배송"))
            .body()
            .path("caseId")
            .asString();
    assertThat(
            send("POST", ops, "/cases/" + caseId + "/assignments", Map.of("assigneeId", "op-ops-1"))
                .status())
        .isEqualTo(200);
    String path = "/cases/" + caseId + "/transfers";
    Map<String, Object> toAdmin = Map.of("toGroup", "ORDER_OPS", "reason", "환불 판단 필요");

    assertThat(send("POST", ops2, path, toAdmin).status()).as("남이 맡은 사건").isEqualTo(403);
    assertThat(send("POST", cs, path, toAdmin).status()).as("상담원은 보기만").isEqualTo(403);
    assertThat(send("POST", ops, path, Map.of("toGroup", "FINANCE", "reason", "x")).status())
        .isEqualTo(422);
    assertThat(
            send(
                    "POST",
                    ops,
                    path,
                    Map.of("toGroup", "ORDER_OPS", "toCenterId", "MFC-1", "reason", "x"))
                .status())
        .isEqualTo(422);
    assertThat(send("POST", ops, path, Map.of("toGroup", "ORDER_OPS", "reason", " ")).status())
        .isEqualTo(400);
    assertThat(send("POST", ops, path, Map.of("toGroup", "DELIVERY_OPS", "reason", "x")).status())
        .as("같은 팀")
        .isEqualTo(409);

    Res moved = send("POST", ops, path, toAdmin);
    assertThat(moved.status()).as(moved.body().toString()).isEqualTo(200);
    assertThat(moved.body().path("assigneeGroup").asString()).isEqualTo("ORDER_OPS");
    assertThat(moved.body().path("state").asString()).isEqualTo("OPEN");
    assertThat(
            moved.body().path("assigneeId").isNull()
                || moved.body().path("assigneeId").isMissingNode())
        .isTrue();
    assertThat(moved.body().path("history").toString()).contains("TRANSFERRED", "ORDER_OPS");
    assertThat(outbox(caseId, "CaseTransferred")).isEqualTo(1);

    // 지원센터는 보기만, 관리자 팀 목록에 나온다
    assertThat(send("GET", ops, "/cases/" + caseId, null).status()).isEqualTo(200);
    assertThat(
            send("POST", ops, "/cases/" + caseId + "/assignments", Map.of("assigneeId", "op-ops-1"))
                .status())
        .isEqualTo(403);
    assertThat(
            send(
                    "POST",
                    ops,
                    "/cases/" + caseId + "/closures",
                    Map.of("resolution", "RESOLVED", "summary", "x"))
                .status())
        .isEqualTo(403);
    assertThat(
            send("GET", admin, "/cases?assigneeGroup=ORDER_OPS&subjectRef=" + deliveryId, null)
                .body()
                .path("items")
                .findValuesAsString("caseId"))
        .containsExactly(caseId);

    // 관리자가 되돌리면 지원센터가 다시 집는다
    assertThat(
            send("POST", admin, path, Map.of("toGroup", "DELIVERY_OPS", "reason", "환불 처리 끝"))
                .status())
        .isEqualTo(200);
    assertThat(
            send(
                    "POST",
                    ops2,
                    "/cases/" + caseId + "/assignments",
                    Map.of("assigneeId", "op-ops-2"))
                .status())
        .isEqualTo(200);
    assertThat(
            send(
                    "POST",
                    ops2,
                    "/cases/" + caseId + "/closures",
                    Map.of("resolution", "RESOLVED", "summary", "회수 완료"))
                .status())
        .isEqualTo(200);
    assertThat(send("POST", admin, path, toAdmin).status()).as("닫힌 사건").isEqualTo(409);
  }

  int obligationEvents(String obligationId, String type) {
    return count(
        "SELECT count(*) FROM outbox_event WHERE aggregateid = ? AND type = ?", obligationId, type);
  }

  /**
   * 의무(0.8.0 — 2026-11-16 사용자 "고친 안으로 진행해 줘"): 등록은 사건 팀, 8종만, 해소는 담당 팀(상담원은 고객센터 의무)·관리자, 시도 기록은 상태
   * 그대로, 면제·실패는 관리자만(사유), 부분 해소·초과 409, 열린 의무가 있으면 닫기 409, 닫힌 사건엔 등록 409, 팀 목록·열린 의무 수.
   */
  @Test
  void obligationsBlockClosingUntilResolvedByOwningTeam() {
    String deliveryId = "DLV-OB-" + UUID.randomUUID().toString().substring(0, 8);
    dispatch.stubFor(
        get(urlPathMatching("/api/v1/internal/deliveries/.+/assignment"))
            .willReturn(aResponse().withStatus(404)));
    String caseId =
        send(
                "POST",
                cs,
                "/cases",
                Map.of(
                    "kind", "MISDELIVERY",
                    "subjectType", "DELIVERY",
                    "subjectRef", deliveryId,
                    "summary", "옆집 전달"))
            .body()
            .path("caseId")
            .asString();
    String obl = "/cases/" + caseId + "/obligations";

    // 등록
    assertThat(send("POST", cs, obl, Map.of("kind", "CUSTOMER_CONTACT", "owner", "CS")).status())
        .as("상담원은 사건을 다루지 않음")
        .isEqualTo(403);
    Res disposal = send("POST", ops, obl, Map.of("kind", "DISPOSAL"));
    assertThat(disposal.status()).isEqualTo(422);
    assertThat(disposal.body().path("code").asString()).isEqualTo("UNSUPPORTED_KIND");
    Res pickup = send("POST", ops, obl, Map.of("kind", "RETURN_PICKUP", "qty", 2));
    assertThat(pickup.status()).as(pickup.body().toString()).isEqualTo(201);
    assertThat(pickup.body().path("owner").asString()).as("비우면 사건 팀").isEqualTo("DELIVERY_OPS");
    String pickupId = pickup.body().path("obligationId").asString();
    String contactId =
        send("POST", ops, obl, Map.of("kind", "CUSTOMER_CONTACT", "owner", "CS"))
            .body()
            .path("obligationId")
            .asString();
    String refundId =
        send("POST", ops, obl, Map.of("kind", "REFUND", "owner", "ORDER_OPS", "amount", 12000))
            .body()
            .path("obligationId")
            .asString();
    assertThat(obligationEvents(pickupId, "ObligationCreated")).isEqualTo(1);

    // 열린 의무 — 닫기 409, 목록·상세에 수
    Map<String, Object> close = Map.of("resolution", "RESOLVED", "summary", "회수 완료");
    Res blocked = send("POST", ops, "/cases/" + caseId + "/closures", close);
    assertThat(blocked.status()).isEqualTo(409);
    assertThat(blocked.body().path("code").asString()).isEqualTo("OPEN_OBLIGATIONS");
    assertThat(
            send("GET", ops, "/cases/" + caseId, null).body().path("openObligationCount").asInt())
        .isEqualTo(3);
    assertThat(
            send("GET", ops, "/cases?subjectRef=" + deliveryId, null)
                .body()
                .path("items")
                .get(0)
                .path("openObligationCount")
                .asInt())
        .isEqualTo(3);
    assertThat(
            send("GET", cs, "/obligations?owner=CS&caseId=" + caseId, null)
                .body()
                .path("items")
                .findValuesAsString("obligationId"))
        .containsExactly(contactId);

    // 상담원: 고객센터 의무만, 시도 기록은 상태 그대로, 면제는 관리자만
    Map<String, Object> callLog = Map.of("system", "cs-phone", "ref", "CALL-1", "kind", "LOG");
    Res tried =
        send(
            "POST",
            cs,
            "/obligations/" + contactId + "/resolutions",
            Map.of("outcome", "ATTEMPTED", "evidence", callLog, "note", "부재중 2회"));
    assertThat(tried.status()).as(tried.body().toString()).isEqualTo(200);
    assertThat(tried.body().path("state").asString()).isEqualTo("OPEN");
    assertThat(tried.body().path("resolutions").get(0).path("note").asString()).isEqualTo("부재중 2회");
    assertThat(obligationEvents(contactId, "ObligationResolved")).as("시도는 끝난 게 아님").isZero();
    assertThat(
            send(
                    "POST",
                    cs,
                    "/obligations/" + contactId + "/resolutions",
                    Map.of("outcome", "WAIVED", "evidence", callLog, "note", "연락 불가"))
                .status())
        .isEqualTo(403);
    assertThat(
            send(
                    "POST",
                    cs,
                    "/obligations/" + pickupId + "/resolutions",
                    Map.of("outcome", "FULFILLED", "resolvedQty", 1, "evidence", callLog))
                .status())
        .as("지원센터 의무")
        .isEqualTo(403);
    Res contacted =
        send(
            "POST",
            cs,
            "/obligations/" + contactId + "/resolutions",
            Map.of("outcome", "FULFILLED", "evidence", callLog));
    assertThat(contacted.body().path("state").asString()).isEqualTo("RESOLVED");
    assertThat(obligationEvents(contactId, "ObligationResolved")).isEqualTo(1);

    // 지원센터: 부분 해소, 초과 409, 수량 없음 400
    Map<String, Object> handover = Map.of("system", "delivery-execution-service", "ref", "HO-1");
    String pick = "/obligations/" + pickupId + "/resolutions";
    assertThat(
            send("POST", ops, pick, Map.of("outcome", "FULFILLED", "evidence", handover)).status())
        .isEqualTo(400);
    assertThat(
            send(
                    "POST",
                    ops,
                    pick,
                    Map.of("outcome", "FULFILLED", "resolvedQty", 1, "evidence", handover))
                .body()
                .path("state")
                .asString())
        .isEqualTo("PARTIALLY_RESOLVED");
    Res over =
        send(
            "POST",
            ops,
            pick,
            Map.of("outcome", "FULFILLED", "resolvedQty", 5, "evidence", handover));
    assertThat(over.status()).isEqualTo(409);
    assertThat(over.body().path("code").asString()).isEqualTo("QTY_EXCEEDS_OBLIGATION");
    assertThat(
            send(
                    "POST",
                    ops,
                    pick,
                    Map.of("outcome", "FULFILLED", "resolvedQty", 1, "evidence", handover))
                .body()
                .path("state")
                .asString())
        .isEqualTo("RESOLVED");

    // 관리자 팀 환불 의무: 지원센터 403, 관리자 면제는 사유 필수
    String refund = "/obligations/" + refundId + "/resolutions";
    Map<String, Object> doc = Map.of("system", "claim-service", "ref", "CLM-1", "kind", "DOCUMENT");
    assertThat(
            send(
                    "POST",
                    ops,
                    refund,
                    Map.of("outcome", "FULFILLED", "resolvedAmount", 12000, "evidence", doc))
                .status())
        .isEqualTo(403);
    assertThat(send("POST", admin, refund, Map.of("outcome", "WAIVED", "evidence", doc)).status())
        .as("사유 없음")
        .isEqualTo(400);
    assertThat(
            send(
                    "POST",
                    admin,
                    refund,
                    Map.of("outcome", "WAIVED", "evidence", doc, "note", "고객이 환불 대신 재배송 선택"))
                .body()
                .path("state")
                .asString())
        .isEqualTo("WAIVED");
    assertThat(
            send("POST", admin, refund, Map.of("outcome", "FAILED", "evidence", doc, "note", "x"))
                .status())
        .as("이미 끝남")
        .isEqualTo(409);

    // 모두 끝 — 닫힘, 닫힌 사건엔 등록 409, 상세에 의무 3
    assertThat(send("POST", ops, "/cases/" + caseId + "/closures", close).status()).isEqualTo(200);
    assertThat(send("POST", ops, obl, Map.of("kind", "OTHER")).status()).isEqualTo(409);
    JsonNode detail = send("GET", ops, "/cases/" + caseId, null).body();
    assertThat(detail.path("obligations")).hasSize(3);
    assertThat(detail.path("history").toString())
        .contains("OBLIGATION_CREATED", "OBLIGATION_RESOLVED");
    assertThat(send("GET", md, "/obligations/" + pickupId, null).status()).isEqualTo(404);
  }

  /**
   * 오배송 회수 진행(2026-11-16 — DD-2 연결): 회수 배송(purpose MISDELIVERY_RECOVERY) 생성·회수함·매장 반납을 그 오배송 사건에 —
   * refs recoveryDeliveryId 와 이력, 상태 그대로(자동 종결 없음). 일반 배송·고객 반품 회수는 버린다.
   */
  @Test
  void misdeliveryRecoveryProgressIsRecordedOnTheCase() throws Exception {
    String deliveryId = "DLV-RC-" + UUID.randomUUID().toString().substring(0, 8);
    String recovery = "RCV-" + deliveryId;
    dispatch.stubFor(
        get(urlPathMatching("/api/v1/internal/deliveries/.+/assignment"))
            .willReturn(aResponse().withStatus(404)));
    String caseId =
        send(
                "POST",
                cs,
                "/cases",
                Map.of(
                    "kind", "MISDELIVERY",
                    "subjectType", "DELIVERY",
                    "subjectRef", deliveryId,
                    "summary", "옆 동 전달"))
            .body()
            .path("caseId")
            .asString();
    String dispatchSvc = "delivery-dispatch-service";
    String execution = "delivery-execution-service";
    List<Map<String, Object>> lines = List.of(Map.of("orderLineId", "OL-1", "qty", 1));

    // 버릴 것: 일반 배송 생성(caseId 가 실려 있어도 purpose 없음), 고객 반품 회수 반납
    Map<String, Object> plain = new LinkedHashMap<>();
    plain.put("deliveryId", "DLV-PLAIN-" + deliveryId);
    plain.put("orderId", "ORD-1");
    plain.put("shipmentId", "SHP-1");
    plain.put("type", "FORWARD");
    plain.put("mfcId", "MFC-1");
    plain.put("dropAddressRef", "ADDR-REF-1");
    plain.put("caseId", caseId);
    publish("delivery.delivery.v1", "DeliveryCreated", "DLV-PLAIN", plain, dispatchSvc);
    publish(
        "delivery.delivery.v1",
        "ReturnDelivered",
        recovery,
        Map.of(
            "returnPickupId", "RP-0",
            "lines", lines,
            "deliveredAt", "2026-11-16T03:00:00Z",
            "purpose", "CLAIM_RETURN",
            "caseId", caseId),
        execution);

    // 회수 배송 생성 → 회수함 → 매장 반납
    Map<String, Object> created = new LinkedHashMap<>(plain);
    created.put("deliveryId", recovery);
    created.put("type", "RETURN");
    created.put("purpose", "MISDELIVERY_RECOVERY");
    created.put("originDeliveryId", deliveryId);
    publish("delivery.delivery.v1", "DeliveryCreated", recovery, created, dispatchSvc);
    publish(
        "delivery.delivery.v1",
        "ReturnPickedUp",
        recovery,
        Map.of(
            "returnPickupId", "RP-1",
            "deliveryId", recovery,
            "lines", lines,
            "pickedUpAt", "2026-11-16T03:10:00Z",
            "purpose", "MISDELIVERY_RECOVERY",
            "caseId", caseId,
            "originDeliveryId", deliveryId),
        execution);
    publish(
        "delivery.delivery.v1",
        "ReturnDelivered",
        recovery,
        Map.of(
            "returnPickupId",
            "RP-1",
            "deliveryId",
            recovery,
            "mfcId",
            "MFC-1",
            "lines",
            lines,
            "deliveredAt",
            "2026-11-16T03:30:00Z",
            "purpose",
            "MISDELIVERY_RECOVERY",
            "caseId",
            caseId,
            "originDeliveryId",
            deliveryId),
        execution);

    await(
        () ->
            count(
                "SELECT count(*) FROM case_history WHERE case_id = ? AND action = 'RECOVERY_RETURNED'",
                caseId),
        n -> n > 0);
    JsonNode detail = send("GET", ops, "/cases/" + caseId, null).body();
    assertThat(detail.path("state").asString()).as("상태 그대로 — 자동 종결 없음").isEqualTo("OPEN");
    assertThat(detail.path("refs").toString()).contains("recoveryDeliveryId", recovery);
    assertThat(detail.path("refs").toString()).doesNotContain("DLV-PLAIN");
    assertThat(detail.path("history").findValuesAsString("action"))
        .containsSubsequence(
            "OPENED", "RECOVERY_CREATED", "RECOVERY_PICKED_UP", "RECOVERY_RETURNED");
    assertThat(
            count(
                "SELECT count(*) FROM case_history WHERE case_id = ? AND action LIKE 'RECOVERY_%'",
                caseId))
        .as("고객 반품 회수·일반 배송은 버림")
        .isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "SELECT jsonb_array_length(refs) FROM case_record WHERE case_id = ?",
                Integer.class,
                caseId))
        .as("회수 배송 번호는 한 번만")
        .isEqualTo(2);
  }

  String misdeliveryCase(String prefix) {
    return send(
            "POST",
            cs,
            "/cases",
            Map.of(
                "kind", "MISDELIVERY",
                "subjectType", "DELIVERY",
                "subjectRef", prefix + "-" + UUID.randomUUID().toString().substring(0, 8),
                "summary", "교대 시험"))
        .body()
        .path("caseId")
        .asString();
  }

  void claim(String token, String caseId, String who) {
    assertThat(
            send("POST", token, "/cases/" + caseId + "/assignments", Map.of("assigneeId", who))
                .status())
        .isEqualTo(200);
  }

  Map<String, Object> item(String caseId, String next) {
    return Map.of("caseId", caseId, "nextAction", next);
  }

  /**
   * 교대 인수인계(0.9.0 — 2026-11-16 사용자 "진행해 줘", BO-27): 전임이 맡은 건을 다음 조치와 함께 작성(담당 그대로) → 후임 본인이 건별 수락 —
   * 받은 건은 담당 이동·HANDED_OVER, 뺀 건은 EXCLUDED(미인수), 그사이 바뀐 건 SKIPPED_CHANGED, 다룰 수 없는 후임은
   * SKIPPED_NOT_ALLOWED. 취소하면 그 건을 다시 넣을 수 있다.
   */
  @Test
  void shiftHandoverIsAcceptedPerCaseBySuccessor() {
    dispatch.stubFor(
        get(urlPathMatching("/api/v1/internal/deliveries/.+/assignment"))
            .willReturn(aResponse().withStatus(404)));
    String a = misdeliveryCase("DLV-HA");
    String b = misdeliveryCase("DLV-HB");
    String d = misdeliveryCase("DLV-HD");
    String other = misdeliveryCase("DLV-HO");
    claim(ops, a, "op-ops-1");
    claim(ops, b, "op-ops-1");
    claim(ops, d, "op-ops-1");
    claim(ops2, other, "op-ops-2");

    // 작성 검사
    assertThat(
            send(
                    "POST",
                    ops,
                    "/shift-handovers",
                    Map.of(
                        "group", "DELIVERY_OPS",
                        "toOperatorId", "op-ops-2",
                        "items", List.of(Map.of("caseId", a, "nextAction", " "))))
                .status())
        .as("다음 조치 없음")
        .isEqualTo(400);
    Res notMine =
        send(
            "POST",
            ops,
            "/shift-handovers",
            Map.of(
                "group", "DELIVERY_OPS",
                "toOperatorId", "op-ops-2",
                "items", List.of(item(other, "x"))));
    assertThat(notMine.status()).isEqualTo(422);
    assertThat(notMine.body().path("code").asString()).isEqualTo("NOT_HANDOVERABLE");
    assertThat(
            send(
                    "POST",
                    ops,
                    "/shift-handovers",
                    Map.of(
                        "group", "ORDER_OPS",
                        "toOperatorId", "op-ops-2",
                        "items", List.of(item(a, "x"))))
                .status())
        .as("다른 업무")
        .isEqualTo(422);
    assertThat(
            send(
                    "POST",
                    ops2,
                    "/shift-handovers",
                    Map.of(
                        "group", "DELIVERY_OPS",
                        "fromOperatorId", "op-ops-1",
                        "toOperatorId", "op-ops-2",
                        "items", List.of(item(a, "x"))))
                .status())
        .as("남의 인계서는 관리자만")
        .isEqualTo(403);

    // 작성 — 담당은 그대로
    Res created =
        send(
            "POST",
            ops,
            "/shift-handovers",
            Map.of(
                "group", "DELIVERY_OPS",
                "toOperatorId", "op-ops-2",
                "items",
                    List.of(
                        Map.of(
                            "caseId", a,
                            "nextAction", "고객 재연락 후 회수 확인",
                            "checkDueAt", "2026-11-16T09:00:00Z"),
                        item(b, "회수 확인"),
                        item(d, "사진 다시 확인")),
                "note", "야간 교대"));
    assertThat(created.status()).as(created.body().toString()).isEqualTo(201);
    String h = created.body().path("shiftHandoverId").asString();
    assertThat(created.body().path("state").asString()).isEqualTo("PENDING");
    assertThat(created.body().path("unreceived").asBoolean()).isTrue();
    assertThat(created.body().path("teamSnapshot").findValuesAsString("caseId")).contains(a, other);
    assertThat(send("GET", ops, "/cases/" + a, null).body().path("assigneeId").asString())
        .as("수락 전엔 전임")
        .isEqualTo("op-ops-1");
    Res dup =
        send(
            "POST",
            admin,
            "/shift-handovers",
            Map.of(
                "group", "DELIVERY_OPS",
                "fromOperatorId", "op-ops-1",
                "toOperatorId", "op-admin",
                "items", List.of(item(a, "x"))));
    assertThat(dup.status()).isEqualTo(409);
    assertThat(dup.body().path("code").asString()).isEqualTo("CASE_IN_HANDOVER");

    // 보기·미인수 목록
    assertThat(send("GET", cs, "/shift-handovers/" + h, null).status()).isEqualTo(404);
    assertThat(
            send("GET", admin, "/shift-handovers?unreceived=true", null)
                .body()
                .path("items")
                .findValuesAsString("shiftHandoverId"))
        .contains(h);

    // 그사이 b 는 전임이 끝냄
    assertThat(
            send(
                    "POST",
                    ops,
                    "/cases/" + b + "/closures",
                    Map.of("resolution", "RESOLVED", "summary", "회수 끝"))
                .status())
        .isEqualTo(200);

    // 수락 — 후임 본인만, 건별
    String acc = "/shift-handovers/" + h + "/acceptances";
    assertThat(send("POST", admin, acc, Map.of()).status()).as("관리자도 대신 못 함").isEqualTo(403);
    assertThat(send("POST", ops, acc, Map.of()).status()).as("전임").isEqualTo(403);
    Res done = send("POST", ops2, acc, Map.of("excludedCaseIds", List.of(d)));
    assertThat(done.status()).as(done.body().toString()).isEqualTo(200);
    assertThat(done.body().path("state").asString()).isEqualTo("COMPLETED");
    Map<String, String> outcomes = new LinkedHashMap<>();
    done.body()
        .path("items")
        .forEach(i -> outcomes.put(i.path("caseId").asString(), i.path("outcome").asString()));
    assertThat(outcomes)
        .containsEntry(a, "ACCEPTED")
        .containsEntry(b, "SKIPPED_CHANGED")
        .containsEntry(d, "EXCLUDED");
    assertThat(done.body().path("unreceived").asBoolean()).as("빠진 건 있음").isTrue();
    JsonNode caseA = send("GET", ops2, "/cases/" + a, null).body();
    assertThat(caseA.path("assigneeId").asString()).isEqualTo("op-ops-2");
    assertThat(caseA.path("history").toString())
        .contains("HANDOVER_REQUESTED", "HANDED_OVER", "고객 재연락 후 회수 확인");
    assertThat(send("GET", ops, "/cases/" + d, null).body().path("assigneeId").asString())
        .as("뺀 건은 전임 그대로")
        .isEqualTo("op-ops-1");
    assertThat(outbox(a, "CaseAssigned")).isEqualTo(2);
    assertThat(
            count(
                "SELECT count(*) FROM outbox_event WHERE aggregateid = ? AND type = 'ShiftHandoverCompleted'",
                h))
        .isEqualTo(1);
    assertThat(send("POST", ops2, acc, Map.of()).status()).as("이미 끝남").isEqualTo(409);

    // 취소하면 그 건을 다시 넣을 수 있다 / 다룰 수 없는 후임(상담원)은 건너뜀
    String h2 =
        send(
                "POST",
                ops,
                "/shift-handovers",
                Map.of(
                    "group", "DELIVERY_OPS",
                    "toOperatorId", "op-ops-2",
                    "items", List.of(item(d, "사진 다시 확인"))))
            .body()
            .path("shiftHandoverId")
            .asString();
    assertThat(send("POST", ops2, "/shift-handovers/" + h2 + "/cancellations", null).status())
        .as("후임은 취소 못 함")
        .isEqualTo(403);
    Res cancelled = send("POST", ops, "/shift-handovers/" + h2 + "/cancellations", null);
    assertThat(cancelled.body().path("state").asString()).isEqualTo("CANCELLED");
    String h3 =
        send(
                "POST",
                ops,
                "/shift-handovers",
                Map.of(
                    "group", "DELIVERY_OPS",
                    "toOperatorId", "op-cs-1",
                    "items", List.of(item(d, "상담원에게"))))
            .body()
            .path("shiftHandoverId")
            .asString();
    Res notAllowed = send("POST", cs, "/shift-handovers/" + h3 + "/acceptances", Map.of());
    assertThat(notAllowed.status()).isEqualTo(200);
    assertThat(notAllowed.body().path("items").get(0).path("outcome").asString())
        .isEqualTo("SKIPPED_NOT_ALLOWED");
    assertThat(send("GET", ops, "/cases/" + d, null).body().path("assigneeId").asString())
        .isEqualTo("op-ops-1");
  }

  /**
   * 접수 건 메모(0.10.0 — 2026-11-16 사용자 "고친 안"): 볼 수 있는 본사 직원(상담원 포함)이 덧붙이기만, 처리 끝난 건에도(상태 그대로), 관리자
   * 가림(사유 필수 — 글은 지우고 누가·언제·왜는 남김), 못 보는 직원 404.
   */
  @Test
  void notesAreAppendOnlyAndAdminCanMask() {
    dispatch.stubFor(
        get(urlPathMatching("/api/v1/internal/deliveries/.+/assignment"))
            .willReturn(aResponse().withStatus(404)));
    String caseId =
        send(
                "POST",
                cs,
                "/cases",
                Map.of(
                    "kind", "MISDELIVERY",
                    "subjectType", "DELIVERY",
                    "subjectRef", "DLV-NT-" + UUID.randomUUID().toString().substring(0, 8),
                    "summary", "메모 시험"))
            .body()
            .path("caseId")
            .asString();
    String notes = "/cases/" + caseId + "/notes";

    Res byCs = send("POST", cs, notes, Map.of("text", "고객과 통화 — 내일 오전 회수 가능"));
    assertThat(byCs.status()).as(byCs.body().toString()).isEqualTo(201);
    assertThat(byCs.body().path("authorId").asString()).isEqualTo("op-cs-1");
    assertThat(send("POST", ops, notes, Map.of("text", " ")).status()).isEqualTo(400);
    assertThat(send("POST", md, notes, Map.of("text", "x")).status()).as("못 보는 직원").isEqualTo(404);

    // 처리 끝난 건에도 — 상태 그대로
    assertThat(
            send(
                    "POST",
                    ops,
                    "/cases/" + caseId + "/closures",
                    Map.of("resolution", "RESOLVED", "summary", "회수 끝"))
                .status())
        .isEqualTo(200);
    Res late = send("POST", ops, notes, Map.of("text", "늦은 보고: 실제로는 두 개였음"));
    assertThat(late.status()).isEqualTo(201);
    assertThat(send("GET", ops, "/cases/" + caseId, null).body().path("state").asString())
        .isEqualTo("CLOSED");

    JsonNode list = send("GET", ops, notes, null).body().path("items");
    assertThat(list).hasSize(2);
    assertThat(list.get(0).path("text").asString()).startsWith("고객과 통화");

    // 가림 — 관리자만, 사유 필수, 글은 지움
    String noteId = byCs.body().path("noteId").asString();
    String mask = notes + "/" + noteId + "/maskings";
    assertThat(send("POST", ops, mask, Map.of("reason", "x")).status()).isEqualTo(403);
    assertThat(send("POST", admin, mask, Map.of("reason", " ")).status()).isEqualTo(400);
    Res masked = send("POST", admin, mask, Map.of("reason", "전화번호를 적음"));
    assertThat(masked.status()).as(masked.body().toString()).isEqualTo(200);
    assertThat(masked.body().path("masked").asBoolean()).isTrue();
    assertThat(masked.body().path("text").isNull()).isTrue();
    assertThat(masked.body().path("maskedBy").asString()).isEqualTo("op-admin");
    assertThat(masked.body().path("authorId").asString()).as("누가 남겼는지는 남음").isEqualTo("op-cs-1");
    assertThat(
            jdbc.queryForObject(
                "SELECT text FROM case_note WHERE note_id = ?", String.class, noteId))
        .as("DB 에서도 지움")
        .isNull();
    assertThat(
            send("POST", admin, mask, Map.of("reason", "다시")).body().path("maskReason").asString())
        .as("이미 가린 메모는 그대로")
        .isEqualTo("전화번호를 적음");
  }

  /**
   * 오배송 재발송(0.10.2 — 2026-11-16): 재발송 배송 생성(DeliveryCreated, purpose MISDELIVERY_RESHIPMENT,
   * caseId)을 그 오배송 사건에 — refs reshipmentDeliveryId·이력 RESHIPMENT_CREATED, 상태 그대로.
   */
  @Test
  void misdeliveryReshipmentIsLinkedToTheCase() throws Exception {
    dispatch.stubFor(
        get(urlPathMatching("/api/v1/internal/deliveries/.+/assignment"))
            .willReturn(aResponse().withStatus(404)));
    String deliveryId = "DLV-RS-" + UUID.randomUUID().toString().substring(0, 8);
    String caseId =
        send(
                "POST",
                cs,
                "/cases",
                Map.of(
                    "kind", "MISDELIVERY",
                    "subjectType", "DELIVERY",
                    "subjectRef", deliveryId,
                    "summary", "재발송 시험"))
            .body()
            .path("caseId")
            .asString();
    String reship = "RSH-" + deliveryId;
    Map<String, Object> created = new LinkedHashMap<>();
    created.put("deliveryId", reship);
    created.put("orderId", "ORD-1");
    created.put("shipmentId", "SHP-RS");
    created.put("type", "RESHIPMENT");
    created.put("mfcId", "MFC-1");
    created.put("dropAddressRef", "ADDR-REF-1");
    created.put("purpose", "MISDELIVERY_RESHIPMENT");
    created.put("caseId", caseId);
    publish(
        "delivery.delivery.v1", "DeliveryCreated", reship, created, "delivery-dispatch-service");

    await(
        () ->
            count(
                "SELECT count(*) FROM case_history WHERE case_id = ? AND action = 'RESHIPMENT_CREATED'",
                caseId),
        n -> n > 0);
    JsonNode detail = send("GET", ops, "/cases/" + caseId, null).body();
    assertThat(detail.path("state").asString()).isEqualTo("OPEN");
    assertThat(detail.path("refs").toString()).contains("reshipmentDeliveryId", reship);
    assertThat(detail.path("history").toString()).contains("재발송 배송 " + reship);
  }
}
