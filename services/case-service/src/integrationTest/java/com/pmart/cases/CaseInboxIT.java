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
}
