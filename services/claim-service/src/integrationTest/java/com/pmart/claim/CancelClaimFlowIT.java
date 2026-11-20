package com.pmart.claim;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.pmart.claim.application.RefundService;
import com.pmart.common.test.EventEnvelopeFixtures;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * 고객 전체 취소: 접수(order 원장 심사) → ClaimOpened·CancellationRequested → OrderCancelled 사건으로 취소 확정·환불 의도 →
 * payment 취소(멱등키 고정) → RefundResult 로 환불 완료·클레임 종결. 인계됨이면 REJECTED, payment 미전달이면 재실행·원거래 조회로 잇는다.
 * order·payment·auth 는 한 WireMock.
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
      "pmart.claim.refund-sweep-interval=PT1H" // 시험이 재개를 직접 부른다
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock(@ConfigureWireMock(name = "deps", baseUrlProperties = "deps.url"))
class CancelClaimFlowIT extends PostgresKafkaContainerSupport {

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired RefundService refunds;

  @InjectWireMock("deps")
  WireMockServer deps;

  RestClient http;

  record Res(int status, JsonNode body) {}

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

  /** order 원장: 상품 19,000(10개) + 봉투 100 + 배달팁 0 = 승인 19,100. */
  void order(String orderId, String accountId, String status) {
    order(orderId, accountId, status, 0);
  }

  /** cancelled = 출고 전 취소 누계(미이행 = 10 − cancelled). */
  void order(String orderId, String accountId, String status, int cancelled) {
    Map<String, Object> o = new LinkedHashMap<>();
    o.put("orderId", orderId);
    o.put("accountId", accountId);
    o.put("status", status);
    o.put("version", 1);
    o.put("mfcId", "MFC-1");
    o.put("paymentAttemptId", "PAY-" + orderId);
    o.put("approvedAmount", 19_100);
    o.put("itemAmount", 19_000);
    o.put("deliveryTip", 0);
    o.put("bagFee", 100);
    o.put("totalAmount", 19_100);
    o.put("firstPurchase", true);
    o.put("confirmedAt", "2026-10-31T01:00:00Z");
    o.put(
        "lines",
        List.of(
            Map.of(
                "orderLineId", orderId + "-L1",
                "sku", "E-00000001",
                "quantity", 10,
                "unitPrice", 1_900,
                "lineAmount", 19_000,
                "originalQty", 10,
                "unfulfilledQty", 10 - cancelled,
                "handedOverQty", 0,
                "cancelledBeforeShipQty", cancelled)));
    deps.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/orders/" + orderId))
            .willReturn(json(200, o)));
  }

  Res post(String token, String key, Map<String, Object> body) {
    var e =
        http.post()
            .uri("/claims")
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", key);
                  h.set("X-Source-System", "customer-app");
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  Res get(String token, String path) {
    var e =
        http.get().uri(path).headers(h -> h.setBearerAuth(token)).retrieve().toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  static Map<String, Object> cancel(String orderId, int qty) {
    return Map.of(
        "orderId",
        orderId,
        "type",
        "CANCEL",
        "reason",
        "CHANGE_OF_MIND",
        "channel",
        "CUSTOMER_APP",
        "lines",
        List.of(Map.of("orderLineId", orderId + "-L1", "qty", qty)));
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

  void orderCancelled(String orderId, String claimId) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("orderId", orderId);
    p.put("orderVersion", 2);
    p.put("scope", "FULL");
    p.put("lines", List.of(Map.of("orderLineId", orderId + "-L1", "qty", 10)));
    p.put("reason", "CANCEL_BEFORE_SHIP");
    p.put("claimId", claimId);
    send("order.order.v1", "OrderCancelled", "Order", orderId, p);
  }

  Res await(String token, String path, Predicate<JsonNode> done) throws InterruptedException {
    Res r = get(token, path);
    for (int i = 0; i < 150 && !done.test(r.body()); i++) {
      Thread.sleep(100);
      r = get(token, path);
    }
    return r;
  }

  void paymentAccepts(String orderId) {
    deps.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/payments/PAY-" + orderId + "/cancellations"))
            .willReturn(
                json(202, Map.of("status", "ACCEPTED", "requestId", "x", "resultId", "CXL-1"))));
  }

  int outbox(String type, String aggregateId) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM outbox_event WHERE type = ? AND aggregateid = ?",
        Integer.class,
        type,
        aggregateId);
  }

  /**
   * 고객센터 주문 취소(2026-11-18, 저녁 목록 3번 — 사용자 "그 추천대로"): 상담원·운영 관리자만(403), 본인 확인 필수(400), 채널
   * CS·OPERATOR(400), CONFIRMED 만 — 상품준비(PREPARING) 뒤 422 ORDER_NOT_CANCELLABLE(ADR-0060). 접수는 고객
   * 취소와 같은 흐름(STOP_PENDING· CancellationRequested channel CS), 클레임 계정 = 주문 계정(고객이 앱에서 본다), 접수 직원·본인
   * 확인·CS 사건이 남는다.
   */
  /** CL-5(2026-11-19): 배차 불가 직원 취소 — 상품준비 뒤라도 인계 전이면, 그 센터 관리자·상담원, 본인 확인 없음. */
  @Test
  void dispatchUnavailableCancelsPreparedOrderBeforeHandover() throws Exception {
    String account = "CU-" + UUID.randomUUID();
    String orderId = "ORD-" + UUID.randomUUID();
    order(orderId, account, "PREPARING");
    paymentAccepts(orderId);
    Map<String, Object> body = new LinkedHashMap<>(cancel(orderId, 10));
    body.put("channel", "OPERATOR");
    body.put("reason", "DISPATCH_UNAVAILABLE");
    body.put("reasonDetail", "배달 기사 74분 못 찾음");

    assertThat(
            post(
                    TestJwt.operator("STAFF-RO", List.of("RIDER_OPS"), List.of()),
                    UUID.randomUUID().toString(),
                    body)
                .status())
        .as("역할 없는 직원")
        .isEqualTo(403);
    assertThat(
            post(
                    TestJwt.operator("STAFF-SUP-2", List.of("MFC_SUPERVISOR"), List.of("MFC-2")),
                    UUID.randomUUID().toString(),
                    body)
                .status())
        .as("다른 센터 관리자")
        .isEqualTo(404);
    Map<String, Object> asCustomer = new LinkedHashMap<>(cancel(orderId, 10));
    asCustomer.put("reason", "DISPATCH_UNAVAILABLE");
    assertThat(post(TestJwt.customer(account), UUID.randomUUID().toString(), asCustomer).status())
        .as("고객은 이 사유로 접수 못 함")
        .isEqualTo(400);

    Res opened =
        post(
            TestJwt.operator("STAFF-SUP-1", List.of("MFC_SUPERVISOR"), List.of("MFC-1")),
            UUID.randomUUID().toString(),
            body);
    assertThat(opened.status()).as(opened.body().toString()).isEqualTo(201);
    String claimId = opened.body().path("claimId").asString();
    JsonNode rp =
        mapper
            .readTree(
                (String)
                    jdbc.queryForObject(
                        "SELECT payload::text FROM outbox_event"
                            + " WHERE type = 'CancellationRequested' AND aggregateid = ?",
                        String.class,
                        claimId))
            .path("payload");
    assertThat(rp.path("reason").asString()).isEqualTo("DISPATCH_UNAVAILABLE");

    // 인계 뒤(미이행 0) — 반품으로
    String handed = "ORD-" + UUID.randomUUID();
    order(handed, account, "PREPARING", 10);
    Map<String, Object> late = new LinkedHashMap<>(body);
    late.put("orderId", handed);
    late.put("lines", List.of(Map.of("orderLineId", handed + "-L1", "qty", 10)));
    Res lateRes =
        post(
            TestJwt.operator("STAFF-CS-9", List.of("CS_AGENT"), List.of()),
            UUID.randomUUID().toString(),
            late);
    assertThat(lateRes.status()).as(lateRes.body().toString()).isEqualTo(422);
  }

  /**
   * ADR-0076(2026-11-20, 사용자 "관리자 사유 취소만 넓힘" — MFC 시뮬레이터 R-29): 관리자 판단 취소 OPERATOR_DECISION — 배차
   * 불가와 같은 범위(상품준비 뒤라도 인계 전)·역할, 사유 메모 필수, 고객은 못 씀(고객 취소는 상품준비 전만 그대로).
   */
  @Test
  void operatorDecisionCancelsPreparedOrderWithMemo() throws Exception {
    String account = "CU-" + UUID.randomUUID();
    String orderId = "ORD-" + UUID.randomUUID();
    order(orderId, account, "PREPARING");
    paymentAccepts(orderId);
    Map<String, Object> body = new LinkedHashMap<>(cancel(orderId, 10));
    body.put("channel", "OPERATOR");
    body.put("reason", "OPERATOR_DECISION");
    String sup = TestJwt.operator("STAFF-SUP-1", List.of("MFC_SUPERVISOR"), List.of("MFC-1"));

    Map<String, Object> noMemo = new LinkedHashMap<>(body);
    noMemo.put("reasonDetail", "  ");
    assertThat(post(sup, UUID.randomUUID().toString(), noMemo).status())
        .as("사유 메모 없음")
        .isEqualTo(400);
    body.put("reasonDetail", "냉장 설비 고장으로 출고 불가");
    assertThat(
            post(
                    TestJwt.operator("STAFF-W", List.of("MFC_WORKER"), List.of("MFC-1")),
                    UUID.randomUUID().toString(),
                    body)
                .status())
        .as("센터 작업자")
        .isEqualTo(403);
    assertThat(
            post(
                    TestJwt.operator("STAFF-CS-1", List.of("CS_AGENT"), List.of()),
                    UUID.randomUUID().toString(),
                    body)
                .status())
        .as("고객센터 상담원은 관리자 판단 취소 못 함(배차 불가만)")
        .isEqualTo(403);
    assertThat(
            post(
                    TestJwt.operator("STAFF-SUP-2", List.of("MFC_SUPERVISOR"), List.of("MFC-2")),
                    UUID.randomUUID().toString(),
                    body)
                .status())
        .as("다른 센터 관리자")
        .isEqualTo(404);
    Map<String, Object> asCustomer = new LinkedHashMap<>(cancel(orderId, 10));
    asCustomer.put("reason", "OPERATOR_DECISION");
    asCustomer.put("reasonDetail", "그냥");
    assertThat(post(TestJwt.customer(account), UUID.randomUUID().toString(), asCustomer).status())
        .as("고객은 이 사유로 접수 못 함")
        .isEqualTo(400);

    Res opened = post(sup, UUID.randomUUID().toString(), body);
    assertThat(opened.status()).as(opened.body().toString()).isEqualTo(201);
    assertThat(opened.body().path("reason").asString()).isEqualTo("OPERATOR_DECISION");
    assertThat(opened.body().path("reasonDetail").asString()).isEqualTo("냉장 설비 고장으로 출고 불가");
    String claimId = opened.body().path("claimId").asString();
    JsonNode rp =
        mapper
            .readTree(
                (String)
                    jdbc.queryForObject(
                        "SELECT payload::text FROM outbox_event"
                            + " WHERE type = 'CancellationRequested' AND aggregateid = ?",
                        String.class,
                        claimId))
            .path("payload");
    assertThat(rp.path("reason").asString()).isEqualTo("OPERATOR_DECISION");

    // 인계 뒤(미이행 0) — 반품으로
    String handed = "ORD-" + UUID.randomUUID();
    order(handed, account, "PREPARING", 10);
    Map<String, Object> late = new LinkedHashMap<>(body);
    late.put("orderId", handed);
    late.put("lines", List.of(Map.of("orderLineId", handed + "-L1", "qty", 10)));
    Res lateRes =
        post(
            TestJwt.operator("OP-ADMIN-9", List.of("OPERATOR_ADMIN"), List.of()),
            UUID.randomUUID().toString(),
            late);
    assertThat(lateRes.status()).as(lateRes.body().toString()).isEqualTo(422);
  }

  @Test
  void customerCenterCancelsConfirmedOrderOnly() throws Exception {
    String account = "CU-" + UUID.randomUUID();
    String orderId = "ORD-" + UUID.randomUUID();
    order(orderId, account, "CONFIRMED");
    paymentAccepts(orderId);
    String cs = TestJwt.operator("STAFF-CS-9", List.of("CS_AGENT"), List.of());
    Map<String, Object> body = new LinkedHashMap<>(cancel(orderId, 10));
    body.put("channel", "CS");
    body.put("reason", "CHANGE_OF_MIND");
    body.put("reasonDetail", "고객 전화 — 다른 주소로 다시 주문");
    body.put("identityCheck", Map.of("method", "ORDER_DETAILS_MATCH"));
    body.put("caseRef", "CASE-IT-1");

    assertThat(
            post(
                    TestJwt.operator("STAFF-RO", List.of("RIDER_OPS"), List.of()),
                    UUID.randomUUID().toString(),
                    body)
                .status())
        .as("역할 없는 직원")
        .isEqualTo(403);
    Map<String, Object> noIdentity = new LinkedHashMap<>(body);
    noIdentity.remove("identityCheck");
    assertThat(post(cs, UUID.randomUUID().toString(), noIdentity).status()).isEqualTo(400);
    Map<String, Object> otherNoNote = new LinkedHashMap<>(body);
    otherNoNote.put("identityCheck", Map.of("method", "OTHER"));
    assertThat(post(cs, UUID.randomUUID().toString(), otherNoNote).status())
        .as("기타면 메모 필수")
        .isEqualTo(400);
    Map<String, Object> appChannel = new LinkedHashMap<>(body);
    appChannel.put("channel", "CUSTOMER_APP");
    assertThat(post(cs, UUID.randomUUID().toString(), appChannel).status()).isEqualTo(400);

    Res opened = post(cs, UUID.randomUUID().toString(), body);
    assertThat(opened.status()).as(opened.body().toString()).isEqualTo(201);
    String claimId = opened.body().path("claimId").asString();
    assertThat(opened.body().path("state").asString()).isEqualTo("STOP_PENDING");
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT account_id, channel, created_by, identity_method, case_ref FROM claim WHERE claim_id = ?",
            claimId);
    assertThat(row.get("account_id")).isEqualTo(account);
    assertThat(row.get("channel")).isEqualTo("CS");
    assertThat(row.get("created_by")).isEqualTo("STAFF-CS-9");
    assertThat(row.get("identity_method")).isEqualTo("ORDER_DETAILS_MATCH");
    assertThat(row.get("case_ref")).isEqualTo("CASE-IT-1");
    JsonNode rp =
        mapper
            .readTree(
                (String)
                    jdbc.queryForObject(
                        "SELECT payload::text FROM outbox_event"
                            + " WHERE type = 'CancellationRequested' AND aggregateid = ?",
                        String.class,
                        claimId))
            .path("payload");
    assertThat(rp.path("channel").asString()).isEqualTo("CS");
    assertThat(get(TestJwt.customer(account), "/claims/" + claimId).status())
        .as("고객이 앱에서 본다")
        .isEqualTo(200);
    Res dup = post(TestJwt.customer(account), UUID.randomUUID().toString(), cancel(orderId, 10));
    assertThat(dup.body().path("code").asString()).isEqualTo("DUPLICATE_CLAIM");

    // 상품준비 뒤는 고객센터도 못 한다(ADR-0060)
    String preparing = "ORD-" + UUID.randomUUID();
    order(preparing, account, "PREPARING");
    Map<String, Object> late = new LinkedHashMap<>(body);
    late.put("orderId", preparing);
    late.put("lines", List.of(Map.of("orderLineId", preparing + "-L1", "qty", 10)));
    Res rejected = post(cs, UUID.randomUUID().toString(), late);
    assertThat(rejected.status()).isEqualTo(422);
    assertThat(rejected.body().path("code").asString()).isEqualTo("ORDER_NOT_CANCELLABLE");
    assertThat(rejected.body().path("detail").asString()).contains("ADR-0060", "반품");
  }

  @Test
  void customerCancelsWholeOrderAndGetsRefund() throws Exception {
    String account = "CU-" + UUID.randomUUID();
    String me = TestJwt.customer(account);
    String orderId = "ORD-" + UUID.randomUUID();
    order(orderId, account, "CONFIRMED");
    paymentAccepts(orderId);

    // 남의 주문 404, 부분 취소 422, 반품 422(아직 없음)
    assertThat(
            post(TestJwt.customer("CU-OTHER"), UUID.randomUUID().toString(), cancel(orderId, 10))
                .status())
        .isEqualTo(404);
    Res partial = post(me, UUID.randomUUID().toString(), cancel(orderId, 5));
    assertThat(partial.status()).isEqualTo(422);
    assertThat(partial.body().path("code").asString()).isEqualTo("PARTIAL_CANCEL_NOT_ALLOWED");
    Map<String, Object> ret = new LinkedHashMap<>(cancel(orderId, 10));
    ret.put("type", "RETURN");
    Res returns = post(me, UUID.randomUUID().toString(), ret);
    assertThat(returns.status()).isEqualTo(422);
    assertThat(returns.body().path("code").asString()).isEqualTo("UNSUPPORTED_ACTION");

    // 접수 → STOP_PENDING, 같은 키 재생, 다른 키는 중복 접수
    String key = UUID.randomUUID().toString();
    Res opened = post(me, key, cancel(orderId, 10));
    assertThat(opened.status()).as(opened.body().toString()).isEqualTo(201);
    String claimId = opened.body().path("claimId").asString();
    assertThat(opened.body().path("state").asString()).isEqualTo("STOP_PENDING");
    String stopId = opened.body().path("stopRequestId").asString();
    assertThat(stopId).startsWith("STOP-");
    assertThat(opened.body().path("lines").path(0).path("decision").asString())
        .isEqualTo("PENDING");
    assertThat(post(me, key, cancel(orderId, 10)).body().path("claimId").asString())
        .isEqualTo(claimId);
    Res dup = post(me, UUID.randomUUID().toString(), cancel(orderId, 10));
    assertThat(dup.status()).isEqualTo(422);
    assertThat(dup.body().path("code").asString()).isEqualTo("DUPLICATE_CLAIM");

    assertThat(outbox("ClaimOpened", claimId)).isEqualTo(1);
    assertThat(outbox("CancellationRequested", claimId)).isEqualTo(1);
    Map<String, Object> req =
        jdbc.queryForMap(
            "SELECT topic, payload::text AS payload FROM outbox_event"
                + " WHERE type = 'CancellationRequested' AND aggregateid = ?",
            claimId);
    assertThat(req.get("topic")).isEqualTo("order.claim.v1");
    JsonNode rp = mapper.readTree((String) req.get("payload")).path("payload");
    assertThat(rp.path("stopRequestId").asString()).isEqualTo(stopId);
    assertThat(rp.path("lines").path(0).path("qty").asInt()).isEqualTo(10);

    // 조회: 본인 200, 남 404, 목록
    assertThat(get(me, "/claims/" + claimId).status()).isEqualTo(200);
    assertThat(get(TestJwt.customer("CU-OTHER"), "/claims/" + claimId).status()).isEqualTo(404);
    assertThat(get(me, "/claims").body().path("items").findValuesAsString("claimId"))
        .contains(claimId);

    // 주문 취소 확정 → IN_PROGRESS, 환불 의도(전액) → payment 취소(멱등키 고정)
    orderCancelled(orderId, claimId);
    Res inProgress =
        await(
            me,
            "/claims/" + claimId,
            b -> "PG_PENDING".equals(b.path("refunds").path(0).path("state").asString()));
    assertThat(inProgress.body().path("state").asString()).isEqualTo("IN_PROGRESS");
    assertThat(inProgress.body().path("lines").path(0).path("approvedQty").asInt()).isEqualTo(10);
    JsonNode refund = inProgress.body().path("refunds").path(0);
    assertThat(refund.path("amount").asInt()).isEqualTo(19_100);
    assertThat(refund.path("breakdown").path("bagFee").asInt()).isEqualTo(100);
    String intentId = refund.path("refundIntentId").asString();
    deps.verify(
        1,
        postRequestedFor(
                urlPathEqualTo("/api/v1/internal/payments/PAY-" + orderId + "/cancellations"))
            .withHeader("Idempotency-Key", WireMock.equalTo("REFUND-" + intentId))
            .withRequestBody(matchingJsonPath("$.amount", WireMock.equalTo("19100")))
            .withRequestBody(matchingJsonPath("$.scope", WireMock.equalTo("FULL")))
            .withRequestBody(matchingJsonPath("$.reason", WireMock.equalTo("CANCEL_BEFORE_SHIP"))));
    assertThat(outbox("RefundIntentCreated", claimId)).isEqualTo(1);

    // PG 결과 성공 → 환불 완료·클레임 종결
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("refundRequestId", "REFUND-" + intentId);
    result.put("refundIntentId", intentId);
    result.put("paymentAttemptId", "PAY-" + orderId);
    result.put("pgTransactionId", "tcxl_1");
    result.put("amount", 19_100);
    result.put("result", "SUCCESS");
    send("order.payment.v1", "RefundResult", "PaymentAttempt", "PAY-" + orderId, result);
    Res closed = await(me, "/claims/" + claimId, b -> "CLOSED".equals(b.path("state").asString()));
    assertThat(closed.body().path("state").asString()).isEqualTo("CLOSED");
    Res refundList = get(me, "/claims/" + claimId + "/refunds");
    assertThat(refundList.status()).isEqualTo(200);
    assertThat(refundList.body().path("items").path(0).path("state").asString())
        .isEqualTo("COMPLETED");
    assertThat(refundList.body().path("items").path(0).path("pgResult").asString())
        .isEqualTo("SUCCESS");
    assertThat(outbox("ClaimClosed", claimId)).isEqualTo(1);
    // 내 클레임 목록(0.6.0 BR-33): 환불 요약 COMPLETED·금액, 사유·채널·종결 시각
    JsonNode mine = get(me, "/claims").body().path("items");
    JsonNode row = null;
    for (JsonNode i : mine) {
      if (claimId.equals(i.path("claimId").asString())) {
        row = i;
      }
    }
    assertThat(row).as("목록에 이 클레임").isNotNull();
    assertThat(row.path("refund").path("state").asString()).isEqualTo("COMPLETED");
    assertThat(row.path("refund").path("refundedAmount").asLong()).isEqualTo(19_100L);
    assertThat(row.path("refund").path("requestedAmount").asLong()).isEqualTo(19_100L);
    assertThat(row.path("channel").asString()).isEqualTo("CUSTOMER_APP");
    assertThat(row.path("closedAt").isNull()).isFalse();

    // 종결 뒤에는 다시 취소 접수를 받지 않는다(주문이 CANCELLED)
    order(orderId, account, "CANCELLED");
    Res again = post(me, UUID.randomUUID().toString(), cancel(orderId, 10));
    assertThat(again.status()).isEqualTo(422);
    assertThat(again.body().path("code").asString()).isEqualTo("ORDER_NOT_CANCELLABLE");
  }

  /** 내 클레임 목록 커서(0.6.0 BR-33): 최근 순, size 만큼 + nextCursor, 마지막 쪽은 커서 없음, 남의 클레임 없음, 틀린 커서 400. */
  @Test
  void myClaimsPageWithCursor() {
    String acc = "CU-PAGE-" + UUID.randomUUID().toString().substring(0, 8);
    java.time.Instant base = java.time.Instant.parse("2026-11-19T00:00:00Z");
    for (int i = 0; i < 3; i++) {
      jdbc.update(
          "INSERT INTO claim (claim_id, order_id, account_id, type, state, reason, channel,"
              + " payment_attempt_id, approved_amount, item_amount, bag_fee, delivery_tip, opened_at,"
              + " updated_at) VALUES (?,?,?,'CANCEL','REJECTED','CHANGE_OF_MIND','CUSTOMER_APP','PAY-x',0,0,0,0,?,?)",
          acc + "-C" + i,
          "ORD-" + acc + "-" + i,
          acc,
          java.sql.Timestamp.from(base.plusSeconds(i)),
          java.sql.Timestamp.from(base.plusSeconds(i)));
    }
    String token = TestJwt.customer(acc);
    Res first = get(token, "/claims?size=2");
    assertThat(first.body().path("items").findValuesAsString("claimId"))
        .containsExactly(acc + "-C2", acc + "-C1");
    assertThat(first.body().path("items").path(0).path("refund").path("state").asString())
        .isEqualTo("NONE");
    String cursor = first.body().path("page").path("nextCursor").asString();
    assertThat(cursor).isNotBlank();
    Res second = get(token, "/claims?size=2&cursor=" + cursor);
    assertThat(second.body().path("items").findValuesAsString("claimId"))
        .containsExactly(acc + "-C0");
    assertThat(
            second.body().path("page").path("nextCursor").isNull()
                || second.body().path("page").path("nextCursor").isMissingNode())
        .isTrue();
    assertThat(get(token, "/claims?cursor=bad!").status()).isEqualTo(400);
  }

  @Test
  void paymentUnreachableThenResumeAndInquire() throws Exception {
    String account = "CU-" + UUID.randomUUID();
    String me = TestJwt.customer(account);
    String orderId = "ORD-" + UUID.randomUUID();
    order(orderId, account, "CONFIRMED");
    deps.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/payments/PAY-" + orderId + "/cancellations"))
            .willReturn(aResponse().withStatus(503)));

    String claimId =
        post(me, UUID.randomUUID().toString(), cancel(orderId, 10))
            .body()
            .path("claimId")
            .asString();
    orderCancelled(orderId, claimId);
    Res pending =
        await(
            me,
            "/claims/" + claimId,
            b ->
                b.path("refunds").size() == 1
                    && !b.path("refunds").path(0).path("requestedAt").isMissingNode()
                    && !b.path("refunds").path(0).path("requestedAt").isNull());
    JsonNode r = pending.body().path("refunds").path(0);
    assertThat(r.path("state").asString()).isEqualTo("PENDING_EXECUTION");
    String intentId = r.path("refundIntentId").asString();

    // payment 복구 → 재실행(같은 키) → PG 결과 대기 → 원거래 조회 SUCCEEDED → 완료
    paymentAccepts(orderId);
    refunds.resume(intentId);
    assertThat(
            get(me, "/claims/" + claimId).body().path("refunds").path(0).path("state").asString())
        .isEqualTo("PG_PENDING");
    deps.stubFor(
        WireMock.get(
                urlPathEqualTo(
                    "/api/v1/internal/payments/PAY-"
                        + orderId
                        + "/cancellations/REFUND-"
                        + intentId))
            .willReturn(
                json(200, Map.of("requestId", "REFUND-" + intentId, "state", "SUCCEEDED"))));
    refunds.resume(intentId);
    JsonNode done = get(me, "/claims/" + claimId).body();
    assertThat(done.path("refunds").path(0).path("state").asString()).isEqualTo("COMPLETED");
    assertThat(done.path("state").asString()).isEqualTo("CLOSED");
    // 미전달(503)은 공용 클라이언트 재시도까지 포함해 여러 번일 수 있다 — 모두 같은 멱등키(새 금액 취소 없음)
    deps.verify(
        WireMock.moreThanOrExactly(2),
        postRequestedFor(
                urlPathEqualTo("/api/v1/internal/payments/PAY-" + orderId + "/cancellations"))
            .withHeader("Idempotency-Key", WireMock.equalTo("REFUND-" + intentId)));
    var all =
        deps.findAll(
            postRequestedFor(
                urlPathEqualTo("/api/v1/internal/payments/PAY-" + orderId + "/cancellations")));
    assertThat(all).allMatch(q -> ("REFUND-" + intentId).equals(q.getHeader("Idempotency-Key")));
  }

  @Test
  void handedOverRejectsClaim() throws Exception {
    String account = "CU-" + UUID.randomUUID();
    String me = TestJwt.customer(account);
    String orderId = "ORD-" + UUID.randomUUID();
    order(orderId, account, "CONFIRMED");
    Res opened = post(me, UUID.randomUUID().toString(), cancel(orderId, 10));
    String claimId = opened.body().path("claimId").asString();

    Map<String, Object> p = new LinkedHashMap<>();
    p.put("orderId", orderId);
    p.put("stopRequestId", opened.body().path("stopRequestId").asString());
    p.put("perPackage", List.of(Map.of("packageId", "PKG-1", "state", "HANDED_OVER")));
    send("mfc.fulfillment.v1", "WorkStopResult", "Fulfillment", orderId, p);
    Res rejected =
        await(me, "/claims/" + claimId, b -> "REJECTED".equals(b.path("state").asString()));
    assertThat(rejected.body().path("state").asString()).isEqualTo("REJECTED");
    assertThat(rejected.body().path("lines").path(0).path("rejectReason").asString())
        .isEqualTo("HANDED_OVER");
    // 거절 뒤에는 새 취소 접수를 다시 받을 수 있다(중복 접수 제한은 진행 중인 것만)
    assertThat(post(me, UUID.randomUUID().toString(), cancel(orderId, 10)).status()).isEqualTo(201);
  }

  Res system(String key, Map<String, Object> body) {
    var e =
        http.post()
            .uri("/internal/claims/system-cancellations")
            .headers(
                h -> {
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

  /**
   * 배차 불가 자동 취소(0.9.1, 2026-11-20 — delivery-dispatch 가 약속 + 30분 라이더 없음): 시스템 취소 사유
   * DISPATCH_UNAVAILABLE — 포장까지 끝난(PREPARING) 주문도 접수, channel SYSTEM.
   */
  @Test
  void systemCancellationForDispatchUnavailable() throws Exception {
    String account = "CU-" + UUID.randomUUID();
    String orderId = "ORD-" + UUID.randomUUID();
    order(orderId, account, "PREPARING");
    paymentAccepts(orderId);
    Res r =
        system(
            "dispatch-no-rider:DLV-" + orderId,
            Map.of(
                "orderId",
                orderId,
                "reason",
                "DISPATCH_UNAVAILABLE",
                "reasonDetail",
                "배차 불가 자동 취소"));
    assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
    assertThat(r.body().path("reason").asString()).isEqualTo("DISPATCH_UNAVAILABLE");
    assertThat(
            jdbc.queryForObject(
                "SELECT channel FROM claim WHERE claim_id = ?",
                String.class,
                r.body().path("claimId").asString()))
        .isEqualTo("SYSTEM");
  }

  /** 슬라이스 6a D6-4: MFC 작업 거절 뒤 orchestration 의 자동 전체 취소 — 고객 취소와 같은 흐름, 진행 중 취소가 있으면 그것. */
  @Test
  void systemCancellationAfterFulfillmentRejected() throws Exception {
    String account = "CU-" + UUID.randomUUID();
    String orderId = "ORD-" + UUID.randomUUID();
    order(orderId, account, "CONFIRMED");
    paymentAccepts(orderId);
    Map<String, Object> body =
        Map.of(
            "orderId",
            orderId,
            "reason",
            "FULFILLMENT_REJECTED",
            "reasonDetail",
            "INVENTORY_MISMATCH v3",
            "sourceRef",
            "S2-1");

    Res r = system("S2-1:REQUEST_SYSTEM_CANCEL", body);
    assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
    String claimId = r.body().path("claimId").asString();
    assertThat(r.body().path("state").asString()).isEqualTo("STOP_PENDING");
    assertThat(r.body().path("reason").asString()).isEqualTo("FULFILLMENT_REJECTED");
    assertThat(r.body().path("lines").path(0).path("requestedQty").asInt()).isEqualTo(10);
    assertThat(
            jdbc.queryForObject(
                "SELECT channel || ' ' || account_id FROM claim WHERE claim_id = ?",
                String.class,
                claimId))
        .isEqualTo("SYSTEM " + account);
    JsonNode req =
        mapper.readTree(
            jdbc.queryForObject(
                "SELECT payload::text FROM outbox_event WHERE type = 'CancellationRequested'"
                    + " AND aggregateid = ?",
                String.class,
                claimId));
    assertThat(req.path("payload").path("reason").asString()).isEqualTo("FULFILLMENT_REJECTED");
    assertThat(req.path("payload").path("channel").asString()).isEqualTo("SYSTEM");

    // 같은 키 재전송 → 같은 클레임(201 재생), 다른 키 → 진행 중 클레임 200(새로 만들지 않음)
    assertThat(system("S2-1:REQUEST_SYSTEM_CANCEL", body).body().path("claimId").asString())
        .isEqualTo(claimId);
    Res again = system("S2-1:other", body);
    assertThat(again.status()).isEqualTo(200);
    assertThat(again.body().path("claimId").asString()).isEqualTo(claimId);
    assertThat(outbox("CancellationRequested", claimId)).isEqualTo(1);

    // 뒤 흐름은 고객 취소와 같다: OrderCancelled → 원 승인액 전액 환불 실행
    orderCancelled(orderId, claimId);
    Res done =
        await(
            TestJwt.service("order-orchestration-service"),
            "/claims/" + claimId,
            b -> "IN_PROGRESS".equals(b.path("state").asString()));
    assertThat(done.body().path("state").asString()).isEqualTo("IN_PROGRESS");
    assertThat(
            jdbc.queryForObject(
                "SELECT amount FROM refund_intent WHERE claim_id = ?", Long.class, claimId))
        .isEqualTo(19_100L);

    // 확정 아닌 주문 → 422, 없는 주문 → 404
    String cancelled = "ORD-" + UUID.randomUUID();
    order(cancelled, account, "CANCELLED");
    Map<String, Object> b2 = new LinkedHashMap<>(body);
    b2.put("orderId", cancelled);
    Res no = system("k-" + cancelled, b2);
    assertThat(no.status()).isEqualTo(422);
    assertThat(no.body().path("code").asString()).isEqualTo("ORDER_NOT_CANCELLABLE");
    Map<String, Object> b3 = new LinkedHashMap<>(body);
    b3.put("orderId", "ORD-NONE");
    deps.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/orders/ORD-NONE"))
            .willReturn(json(404, Map.of("code", "ORDER_NOT_FOUND"))));
    assertThat(system("k-none", b3).status()).isEqualTo(404);
  }

  Res shortageRefund(String key, Map<String, Object> body) {
    var e =
        http.post()
            .uri("/internal/claims/shortage-refunds")
            .headers(
                h -> {
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

  /** ADR-0065: 공제 있는 부분 환불(고객 잘못 오배송 회수 후 상품값 − 9,000원). 공제가 상품값 이상이면 환불 없이 클레임을 바로 닫는다. */
  @Test
  void shortageRefundWithFeeDeduction() throws Exception {
    String orderId = "ORD-" + UUID.randomUUID();
    order(orderId, "CU-" + UUID.randomUUID(), "COMPLETED", 10); // 10개 모두 출고 전 취소로 반영됨(다시 보냄 뒤 취소)
    paymentAccepts(orderId);
    Res r =
        shortageRefund(
            "FD-1",
            Map.of(
                "orderId",
                orderId,
                "lines",
                List.of(Map.of("orderLineId", orderId + "-L1", "qty", 6)),
                "feeDeduction",
                9000));
    assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
    // 1,900 × 6 = 11,400 − 9,000 = 2,400
    assertThat(
            jdbc.queryForObject(
                "SELECT amount || ' ' || item_amount FROM refund_intent WHERE claim_id = ?",
                String.class,
                r.body().path("claimId").asString()))
        .isEqualTo("2400 11400");
    Res zero =
        shortageRefund(
            "FD-2",
            Map.of(
                "orderId",
                orderId,
                "lines",
                List.of(Map.of("orderLineId", orderId + "-L1", "qty", 4)),
                "feeDeduction",
                9000));
    assertThat(zero.status()).isEqualTo(201);
    assertThat(zero.body().path("state").asString()).isEqualTo("CLOSED"); // 7,600 − 9,000 → 0원
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM refund_intent WHERE claim_id = ?",
                Integer.class,
                zero.body().path("claimId").asString()))
        .isZero();
  }

  /** IV-09 2부(ADR-0045): 결품 부분 환불 — 단가 × 수량, 봉투·배달팁 없음, 출고 전 취소 누계 안에서만, 두 번 환불 안 함. */
  @Test
  void shortageRefundIsUnitPriceTimesQtyWithinCancelledQty() throws Exception {
    String account = "CU-" + UUID.randomUUID();
    String orderId = "ORD-" + UUID.randomUUID();
    order(orderId, account, "PREPARING", 2); // 결품 2개가 이미 출고 전 취소(SHORTAGE)로 반영됨
    paymentAccepts(orderId);
    Map<String, Object> body =
        Map.of(
            "orderId",
            orderId,
            "lines",
            List.of(Map.of("orderLineId", orderId + "-L1", "qty", 1)),
            "sourceRef",
            "S4-1");
    Res r = shortageRefund("S4-1:REFUND", body);
    assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
    String claimId = r.body().path("claimId").asString();
    assertThat(r.body().path("reason").asString()).isEqualTo("OUT_OF_STOCK");
    assertThat(r.body().path("state").asString()).isEqualTo("IN_PROGRESS");
    // 환불 의도: 부분, 1,900원(단가 × 1), 봉투·배달팁 0 → payment 부분 취소
    assertThat(
            jdbc.queryForObject(
                "SELECT scope || ' ' || amount || ' ' || bag_fee || ' ' || delivery_tip FROM refund_intent"
                    + " WHERE claim_id = ?",
                String.class,
                claimId))
        .isEqualTo("PARTIAL 1900 0 0");
    long until = System.currentTimeMillis() + 15_000;
    while (System.currentTimeMillis() < until
        && deps.findAll(
                postRequestedFor(
                    urlPathEqualTo("/api/v1/internal/payments/PAY-" + orderId + "/cancellations")))
            .isEmpty()) {
      Thread.sleep(100);
    }
    deps.verify(
        postRequestedFor(
                urlPathEqualTo("/api/v1/internal/payments/PAY-" + orderId + "/cancellations"))
            .withRequestBody(
                com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath(
                    "$.amount", com.github.tomakehurst.wiremock.client.WireMock.equalTo("1900")))
            .withRequestBody(
                com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath(
                    "$.scope", com.github.tomakehurst.wiremock.client.WireMock.equalTo("PARTIAL")))
            // payment 계약의 취소 사유는 SHORTAGE(클레임 사유 OUT_OF_STOCK 그대로 보내면 400 — 2026-11-17 고침)
            .withRequestBody(
                com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath(
                    "$.reason",
                    com.github.tomakehurst.wiremock.client.WireMock.equalTo("SHORTAGE"))));
    // 같은 키 재생, 남은 1개만 더 환불 가능(2 → 422)
    assertThat(shortageRefund("S4-1:REFUND", body).body().path("claimId").asString())
        .isEqualTo(claimId);
    Map<String, Object> over =
        Map.of(
            "orderId", orderId, "lines", List.of(Map.of("orderLineId", orderId + "-L1", "qty", 2)));
    Res tooMany = shortageRefund("S4-2:REFUND", over);
    assertThat(tooMany.status()).isEqualTo(422);
    assertThat(tooMany.body().path("code").asString()).isEqualTo("QUANTITY_EXCEEDS_AVAILABLE");

    // IV-08(ADR-0046): 이어서 주문 전체 결품 → 결품 사가의 시스템 전체 취소(PREPARING 도 받음). 진행 중 부분 환불 클레임은
    // "진행 중인 취소" 가 아니다(새 클레임), CancellationRequested 사유 OUT_OF_STOCK, 환불은 앞선 1,900원을 뺀 잔액
    Res full =
        system(
            "S4-9:REQUEST_FULL_CANCEL",
            Map.of(
                "orderId",
                orderId,
                "reason",
                "OUT_OF_STOCK",
                "reasonDetail",
                "WHOLE_ORDER_OUT_OF_STOCK"));
    assertThat(full.status()).as(full.body().toString()).isEqualTo(201);
    String fullId = full.body().path("claimId").asString();
    assertThat(fullId).isNotEqualTo(claimId);
    assertThat(full.body().path("lines").path(0).path("requestedQty").asInt()).isEqualTo(8);
    JsonNode req =
        mapper.readTree(
            jdbc.queryForObject(
                "SELECT payload::text FROM outbox_event WHERE type = 'CancellationRequested'"
                    + " AND aggregateid = ?",
                String.class,
                fullId));
    assertThat(req.path("payload").path("reason").asString()).isEqualTo("OUT_OF_STOCK");
    orderCancelled(orderId, fullId);
    await(
        TestJwt.service("order-orchestration-service"),
        "/claims/" + fullId,
        b -> "IN_PROGRESS".equals(b.path("state").asString()));
    assertThat(
            jdbc.queryForObject(
                "SELECT scope || ' ' || amount || ' ' || item_amount || ' ' || bag_fee FROM refund_intent"
                    + " WHERE claim_id = ?",
                String.class,
                fullId))
        .isEqualTo("FULL 17200 17100 100");
  }

  // ---------------------------------------------------------------- CL-3 기간 환불 집계(0.5.0)

  void refundRow(
      String claimId, String sourceRef, String reason, long amount, String state, String at) {
    String id = "RFI-MT-" + UUID.randomUUID();
    jdbc.update(
        "INSERT INTO refund_intent (refund_intent_id, claim_id, order_id, payment_attempt_id, scope,"
            + " reason, amount, item_amount, bag_fee, delivery_tip, state, cancellation_request_id,"
            + " completed_at, created_at, updated_at, source_ref)"
            + " VALUES (?,?,?,?,'PARTIAL',?,?,?,0,0,?,?,?::timestamptz,now(),now(),?)",
        id,
        claimId,
        "ORD-" + claimId,
        "PAY-" + claimId,
        reason,
        amount,
        amount,
        state,
        "REFUND-" + id,
        at,
        sourceRef);
  }

  /**
   * CL-3: OPERATOR_ADMIN 만, 완료(COMPLETED) 환불의 완료 시각 한국 날짜(from·to 포함), 근거 묶음 다섯 줄.
   * /claims/metrics/refunds 와 /claims/{claimId}/refunds 가 둘 다 맞게 간다.
   */
  @Test
  void refundMetricsByKoreanCompletedDate() {
    String claimId = "CLM-MT-" + UUID.randomUUID();
    jdbc.update(
        "INSERT INTO claim (claim_id, order_id, account_id, type, state, reason, channel,"
            + " payment_attempt_id, approved_amount, item_amount, bag_fee, delivery_tip, opened_at,"
            + " updated_at) VALUES (?,?,?,'CANCEL','CLOSED','CHANGE_OF_MIND','SYSTEM',?,0,0,0,0,"
            + " now(), now())",
        claimId,
        "ORD-" + claimId,
        "ACC-MT",
        "PAY-" + claimId);
    // 2019-07-14 00:00 KST ~ 23:59:59 KST 안
    refundRow(claimId, null, "CANCEL_BEFORE_SHIP", 19_100, "COMPLETED", "2019-07-13T15:00:00Z");
    refundRow(claimId, "S1", "OUT_OF_STOCK", 1_900, "COMPLETED", "2019-07-14T14:59:59Z");
    refundRow(claimId, "INS-1", "RETURN", 5_000, "COMPLETED", "2019-07-14T03:00:00Z");
    refundRow(claimId, "RETURN_FEE", "RETURN_FEE", 9_000, "COMPLETED", "2019-07-14T04:00:00Z");
    refundRow(claimId, "M", "MYSTERY", 100, "COMPLETED", "2019-07-14T05:00:00Z");
    // 완료 아님 → 세지 않음
    refundRow(claimId, "INS-2", "RETURN", 7_000, "PG_PENDING", null);
    // 2019-07-13 23:59:59 KST — 전날
    refundRow(claimId, "X", "CANCEL_BEFORE_SHIP", 3_000, "COMPLETED", "2019-07-13T14:59:59Z");

    String admin = TestJwt.operator("ad-mt", List.of("OPERATOR_ADMIN"), List.of());
    Res day = get(admin, "/claims/metrics/refunds?from=2019-07-14&to=2019-07-14");
    assertThat(day.status()).isEqualTo(200);
    assertThat(day.body().path("totalAmount").asLong()).isEqualTo(35_100);
    assertThat(day.body().path("totalCount").asInt()).isEqualTo(5);
    JsonNode by = day.body().path("byReason");
    assertThat(by).hasSize(5);
    List<String> reasons = new java.util.ArrayList<>();
    by.forEach(b -> reasons.add(b.path("reason").asString()));
    assertThat(reasons).containsExactly("CANCEL", "SHORTAGE", "RETURN", "RETURN_FEE", "OTHER");
    assertThat(by.get(0).path("amount").asLong()).isEqualTo(19_100);
    assertThat(by.get(1).path("amount").asLong()).isEqualTo(1_900);
    assertThat(by.get(2).path("amount").asLong()).isEqualTo(5_000);
    assertThat(by.get(2).path("count").asInt()).isEqualTo(1);
    assertThat(by.get(3).path("amount").asLong()).isEqualTo(9_000);
    assertThat(by.get(4).path("amount").asLong()).isEqualTo(100);

    Res two = get(admin, "/claims/metrics/refunds?from=2019-07-13&to=2019-07-14");
    assertThat(two.body().path("byReason").get(0).path("amount").asLong()).isEqualTo(22_100);
    assertThat(two.body().path("byReason").get(0).path("count").asInt()).isEqualTo(2);
    // 빈 기간도 다섯 줄 0
    Res empty = get(admin, "/claims/metrics/refunds?from=2019-01-01&to=2019-01-01");
    assertThat(empty.body().path("totalAmount").asLong()).isZero();
    assertThat(empty.body().path("byReason")).hasSize(5);

    // 권한·기간
    String cs = TestJwt.operator("cs-mt", List.of("CS_AGENT"), List.of());
    assertThat(get(cs, "/claims/metrics/refunds?from=2019-07-14&to=2019-07-14").status())
        .isEqualTo(403);
    assertThat(
            get(TestJwt.customer("ACC-MT"), "/claims/metrics/refunds?from=2019-07-14&to=2019-07-14")
                .status())
        .isEqualTo(403);
    assertThat(get(admin, "/claims/metrics/refunds?from=2019-07-15&to=2019-07-14").status())
        .isEqualTo(400);
    assertThat(get(admin, "/claims/metrics/refunds?from=2019-01-01&to=2019-04-04").status())
        .isEqualTo(400); // 94일
    assertThat(get(admin, "/claims/metrics/refunds?from=2019-01-01&to=2019-04-03").status())
        .isEqualTo(200); // 93일
    assertThat(get(admin, "/claims/metrics/refunds?to=2019-04-03").status()).isEqualTo(400);

    // 경로가 겹치지 않는다: 클레임 환불 조회는 그대로
    Res list = get(admin, "/claims/" + claimId + "/refunds");
    assertThat(list.status()).isEqualTo(200);
    assertThat(list.body().path("claimId").asString()).isEqualTo(claimId);
    assertThat(list.body().path("items")).hasSize(7);
  }

  Res eligibility(String accountId) {
    var e =
        http.get()
            .uri("/internal/accounts/{id}/withdrawal-eligibility", accountId)
            .headers(h -> h.set("X-Source-System", "customer-service"))
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  static List<String> obligations(JsonNode body) {
    List<String> out = new java.util.ArrayList<>();
    body.path("openObligations")
        .forEach(o -> out.add(o.path("kind").asString() + ":" + o.path("ref").asString()));
    return out;
  }

  /**
   * 고객 탈퇴 가능 여부(0.9.0, ADR-0077): 클레임 없음 eligible → 접수(STOP_PENDING) CLAIM_IN_PROGRESS → 취소 확정·PG
   * 결과 대기 CLAIM_IN_PROGRESS + REFUND_IN_PROGRESS → 환불 완료·종결 eligible → (환불이 FAILED 였다면) 종결 상태여도
   * REFUND_IN_PROGRESS. 남의 클레임은 보이지 않는다.
   */
  @Test
  void withdrawalEligibilityFollowsClaimAndRefund() throws Exception {
    String account = "CU-" + UUID.randomUUID();
    String orderId = "ORD-" + UUID.randomUUID();
    String me = TestJwt.customer(account);

    Res none = eligibility(account);
    assertThat(none.status()).isEqualTo(200);
    assertThat(none.body().path("eligible").asBoolean()).isTrue();
    assertThat(none.body().path("openObligations")).isEmpty();
    assertThat(none.body().path("asOf").path("asOf").asString()).isNotEmpty();

    order(orderId, account, "CONFIRMED");
    paymentAccepts(orderId);
    Res created = post(me, UUID.randomUUID().toString(), cancel(orderId, 10));
    assertThat(created.status()).isEqualTo(201);
    String claimId = created.body().path("claimId").asString();

    Res open = eligibility(account);
    assertThat(open.body().path("eligible").asBoolean()).isFalse();
    assertThat(obligations(open.body())).containsExactly("CLAIM_IN_PROGRESS:" + claimId);
    assertThat(eligibility("CU-OTHER-" + UUID.randomUUID()).body().path("eligible").asBoolean())
        .isTrue();

    orderCancelled(orderId, claimId);
    Res pending =
        await(
            me,
            "/claims/" + claimId,
            b -> "PG_PENDING".equals(b.path("refunds").path(0).path("state").asString()));
    String intentId = pending.body().path("refunds").path(0).path("refundIntentId").asString();
    assertThat(obligations(eligibility(account).body()))
        .containsExactly("CLAIM_IN_PROGRESS:" + claimId, "REFUND_IN_PROGRESS:" + claimId);

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("refundRequestId", "REFUND-" + intentId);
    result.put("refundIntentId", intentId);
    result.put("paymentAttemptId", "PAY-" + orderId);
    result.put("pgTransactionId", "tcxl_w");
    result.put("amount", 19_100);
    result.put("result", "SUCCESS");
    send("order.payment.v1", "RefundResult", "PaymentAttempt", "PAY-" + orderId, result);
    await(me, "/claims/" + claimId, b -> "CLOSED".equals(b.path("state").asString()));
    Res done = eligibility(account);
    assertThat(done.body().path("eligible").asBoolean()).isTrue();
    assertThat(done.body().path("openObligations")).isEmpty();

    // 실패한 환불은 돈을 못 돌려준 것 — 클레임이 끝 상태여도 미결(OMS: 환불 실패·장기 미결이면 종결하지 않는다)
    jdbc.update("UPDATE refund_intent SET state = 'FAILED' WHERE refund_intent_id = ?", intentId);
    Res failed = eligibility(account);
    assertThat(failed.body().path("eligible").asBoolean()).isFalse();
    assertThat(obligations(failed.body())).containsExactly("REFUND_IN_PROGRESS:" + claimId);
  }
}
