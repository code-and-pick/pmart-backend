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
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import com.pmart.order.orchestration.application.RedeliveryPaymentService;
import com.pmart.order.orchestration.application.ReshipmentSagaService;
import com.pmart.order.orchestration.infrastructure.RedeliveryPaymentStore.Row;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 오배송 고객 잘못 다시 배송 9,000원 결제 대기(ADR-0065 4단계): RedeliveryPaymentRequired → 결제 시도(주문 참조 RDL-<caseId>,
 * customerRef = 주문 accountId) → 승인이면 dispatch 배차 풀기. 기한(30분) 지남·고객 거절이면 dispatch 회수로 바꾸기 + 재발송 사가
 * CUSTOMER_REFUND. 기한 뒤 늦은 승인은 보상 취소. 고객 조회·거절은 본인 것만.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.orchestration.payment-base-url=${parts.url}",
      "pmart.orchestration.mfc-inventory-base-url=${parts.url}",
      "pmart.orchestration.mfc-fulfillment-base-url=${parts.url}",
      "pmart.orchestration.claim-base-url=${parts.url}",
      "pmart.orchestration.order-base-url=${parts.url}",
      "pmart.orchestration.delivery-dispatch-base-url=${parts.url}",
      "pmart.orchestration.sweep-interval=PT1H",
      "pmart.test.context=redelivery-payment"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock(@ConfigureWireMock(name = "parts", baseUrlProperties = "parts.url"))
class RedeliveryPaymentIT extends PostgresKafkaContainerSupport {

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired RedeliveryPaymentService payments;
  @Autowired ReshipmentSagaService reshipments;
  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

  @InjectWireMock("parts")
  WireMockServer parts;

  record Res(int status, JsonNode body) {}

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

  /** 다시 배송 하나: 주문 소유자 account, 결제 시도 attempt(상태 state), dispatch 풀기·회수로 바꾸기 성공. */
  void participants(String orderId, String account, String attempt, String state) {
    parts.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/orders/" + orderId))
            .willReturn(
                json(200, Map.of("orderId", orderId, "accountId", account, "lines", List.of()))));
    parts.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/payments"))
            .withRequestBody(matchingJsonPath("$.customerRef", WireMock.equalTo(account)))
            .willReturn(
                json(
                    201,
                    Map.of(
                        "paymentAttemptId",
                        attempt,
                        "state",
                        "CREATED",
                        "checkout",
                        Map.of("provider", "TOSS_PAYMENTS", "pgOrderId", attempt)))));
    paymentState(attempt, state);
    Map<String, Object> ok = Map.of("status", "APPLIED", "resultId", "x");
    parts.stubFor(
        WireMock.post(
                WireMock.urlPathMatching("/api/v1/internal/deliveries/.*/redelivery-releases"))
            .willReturn(json(200, ok)));
    parts.stubFor(
        WireMock.post(
                WireMock.urlPathMatching("/api/v1/internal/deliveries/.*/redelivery-cancellations"))
            .willReturn(json(200, ok)));
    parts.stubFor(
        WireMock.post(
                WireMock.urlPathMatching("/api/v1/internal/payments/.*/compensation-cancellations"))
            .willReturn(json(202, Map.of("status", "ACCEPTED", "resultId", "c"))));
  }

  void paymentState(String attempt, String state) {
    parts.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/payments/" + attempt))
            .willReturn(json(200, Map.of("paymentAttemptId", attempt, "state", state))));
  }

  void required(String caseId, String deliveryId, String orderId, Instant requiredAt) {
    String json =
        EventEnvelopeFixtures.envelope("RedeliveryPaymentRequired")
            .from("delivery-dispatch-service")
            .aggregate("Delivery", deliveryId, 2)
            .payload(
                Map.of(
                    "deliveryId",
                    deliveryId,
                    "orderId",
                    orderId,
                    "caseId",
                    caseId,
                    "originDeliveryId",
                    "DLV-ORIGIN-" + caseId,
                    "straightDistanceM",
                    850,
                    "fee",
                    9000,
                    "requiredAt",
                    requiredAt.toString()))
            .buildJson();
    kafka.send("delivery.delivery.v1", deliveryId, json).join();
  }

  Row await(String caseId, String state, @Nullable String step) throws InterruptedException {
    long until = System.currentTimeMillis() + 20_000;
    while (System.currentTimeMillis() < until) {
      var row = payments.byCase(caseId);
      if (row.isPresent()) {
        if (state.equals(row.get().state())
            && (step == null || step.equals(row.get().currentStep()))) {
          return row.get();
        }
        payments.advance(row.get().sagaId());
      }
      Thread.sleep(200);
    }
    throw new AssertionError("결제 대기 상태 " + state + "/" + step + " 안 됨: " + payments.byCase(caseId));
  }

  Res call(String method, String path, String token) {
    return RestClient.builder()
        .requestFactory(new JdkClientHttpRequestFactory())
        .build()
        .method(HttpMethod.valueOf(method))
        .uri("http://localhost:" + port + "/api/v1" + path)
        .headers(
            h -> {
              h.setBearerAuth(token);
              h.set("Idempotency-Key", UUID.randomUUID().toString());
              h.set("X-Source-System", "customer-app");
            })
        .exchange(
            (req, res) -> {
              byte[] b = res.getBody().readAllBytes();
              return new Res(
                  res.getStatusCode().value(),
                  b.length == 0 ? mapper.createObjectNode() : mapper.readTree(b));
            });
  }

  @Test
  void paidReleasesRedeliveryAndCustomerSeesOnlyOwn() throws Exception {
    String caseId = "CASE-" + UUID.randomUUID();
    String orderId = "ORD-" + UUID.randomUUID().toString().substring(0, 8);
    String deliveryId = "DLV-RD-" + UUID.randomUUID();
    String account = "ACC-" + UUID.randomUUID();
    String attempt = "pay-" + UUID.randomUUID().toString().replace("-", "");
    participants(orderId, account, attempt, "PENDING");
    required(caseId, deliveryId, orderId, Instant.now());
    required(caseId, deliveryId, orderId, Instant.now()); // 같은 사건 다시 → 하나
    Row r = await(caseId, "RUNNING", "AWAIT_PAYMENT");
    assertThat(r.paymentAttemptId()).isEqualTo(attempt);
    assertThat(r.accountId()).isEqualTo(account);
    parts.verify(
        1,
        postRequestedFor(urlPathEqualTo("/api/v1/internal/payments"))
            .withRequestBody(matchingJsonPath("$.orderId", WireMock.equalTo("RDL-" + caseId)))
            .withRequestBody(matchingJsonPath("$.amount", WireMock.equalTo("9000"))));

    // 고객: 본인 것만 보인다, 남은 404
    JsonNode list =
        call("GET", "/redelivery-payments?orderId=" + orderId, TestJwt.customer(account)).body();
    assertThat(list.path("items")).hasSize(1);
    assertThat(list.path("items").get(0).path("status").asString()).isEqualTo("WAITING_PAYMENT");
    assertThat(list.path("items").get(0).path("paymentAttemptId").asString()).isEqualTo(attempt);
    assertThat(list.path("items").get(0).path("amount").asInt()).isEqualTo(9000);
    assertThat(
            call("GET", "/redelivery-payments?orderId=" + orderId, TestJwt.customer("ACC-OTHER"))
                .body()
                .path("items"))
        .isEmpty();
    assertThat(
            call(
                    "POST",
                    "/redelivery-payments/" + r.sagaId() + "/declines",
                    TestJwt.customer("ACC-OTHER"))
                .status())
        .isEqualTo(404);
    // 결제 진행 중에는 다시 시도해도 같은 시도
    assertThat(
            call(
                    "POST",
                    "/redelivery-payments/" + r.sagaId() + "/payment-attempts",
                    TestJwt.customer(account))
                .body()
                .path("paymentAttemptId")
                .asString())
        .isEqualTo(attempt);

    // 승인 → 배차 풀기
    paymentState(attempt, "APPROVED");
    kafka
        .send(
            "order.payment.v1",
            attempt,
            EventEnvelopeFixtures.envelope("PaymentApproved")
                .from("payment-service")
                .aggregate("Payment", attempt, 2)
                .payload(
                    Map.of(
                        "paymentAttemptId",
                        attempt,
                        "orderId",
                        "RDL-" + caseId,
                        "approvedAmount",
                        9000,
                        "approvedAt",
                        Instant.now().toString()))
                .buildJson())
        .join();
    r = await(caseId, "COMPLETED", null);
    assertThat(r.outcome()).isEqualTo("PAID");
    parts.verify(
        postRequestedFor(
                urlPathEqualTo(
                    "/api/v1/internal/deliveries/" + deliveryId + "/redelivery-releases"))
            .withRequestBody(matchingJsonPath("$.caseId", WireMock.equalTo(caseId))));
    parts.verify(
        0,
        postRequestedFor(
            WireMock.urlPathMatching("/api/v1/internal/deliveries/.*/redelivery-cancellations")));
    assertThat(
            call(
                    "POST",
                    "/redelivery-payments/" + r.sagaId() + "/declines",
                    TestJwt.customer(account))
                .status())
        .isEqualTo(409); // 이미 결제됨
  }

  @Test
  void timeoutConvertsToRecoveryAndRefundThenLateApprovalIsCancelled() throws Exception {
    String caseId = "CASE-" + UUID.randomUUID();
    String orderId = "ORD-" + UUID.randomUUID().toString().substring(0, 8);
    String deliveryId = "DLV-RD-" + UUID.randomUUID();
    String account = "ACC-" + UUID.randomUUID();
    String attempt = "pay-" + UUID.randomUUID().toString().replace("-", "");
    participants(orderId, account, attempt, "CREATED");
    required(caseId, deliveryId, orderId, Instant.now());
    Row r = await(caseId, "RUNNING", "AWAIT_PAYMENT");
    // 결제 시도는 만들어졌고 고객이 결제하지 않은 채 30분이 지남
    jdbc.update(
        "UPDATE redelivery_payment SET deadline_at = now() - interval '1 minute' WHERE saga_id = ?",
        r.sagaId());
    r = await(caseId, "COMPLETED", null);
    assertThat(r.outcome()).isEqualTo("TIMED_OUT");
    parts.verify(
        postRequestedFor(
                urlPathEqualTo(
                    "/api/v1/internal/deliveries/" + deliveryId + "/redelivery-cancellations"))
            .withRequestBody(matchingJsonPath("$.reason", WireMock.equalTo("TIMED_OUT"))));
    var refund = reshipments.byCase(caseId).orElseThrow();
    assertThat(refund.mode()).isEqualTo("CUSTOMER_REFUND");
    assertThat(refund.deliveryId()).isEqualTo("DLV-ORIGIN-" + caseId);
    parts.verify(
        0,
        postRequestedFor(
            WireMock.urlPathMatching("/api/v1/internal/deliveries/.*/redelivery-releases")));

    // 기한 뒤 늦은 승인 → 보상 취소
    paymentState(attempt, "APPROVED");
    kafka
        .send(
            "order.payment.v1",
            attempt,
            EventEnvelopeFixtures.envelope("PaymentApproved")
                .from("payment-service")
                .aggregate("Payment", attempt, 2)
                .payload(
                    Map.of(
                        "paymentAttemptId",
                        attempt,
                        "orderId",
                        "RDL-" + caseId,
                        "approvedAmount",
                        9000,
                        "approvedAt",
                        Instant.now().toString()))
                .buildJson())
        .join();
    long until = System.currentTimeMillis() + 20_000;
    while (System.currentTimeMillis() < until
        && parts
            .findAll(
                postRequestedFor(
                    urlPathEqualTo(
                        "/api/v1/internal/payments/" + attempt + "/compensation-cancellations")))
            .isEmpty()) {
      // 사건은 같은 JVM 의 다른 시험 문맥이 받을 수 있다(같은 소비 그룹·DB) — 다시 연 행을 이 문맥에서 진행
      payments.advance(r.sagaId());
      Thread.sleep(200);
    }
    parts.verify(
        postRequestedFor(
                urlPathEqualTo(
                    "/api/v1/internal/payments/" + attempt + "/compensation-cancellations"))
            .withRequestBody(
                matchingJsonPath("$.reason", WireMock.equalTo("REDELIVERY_PAYMENT_TOO_LATE"))));
    assertThat(await(caseId, "COMPLETED", null).outcome()).isEqualTo("TIMED_OUT");
  }

  @Test
  void customerDeclineConvertsToRecovery() throws Exception {
    String caseId = "CASE-" + UUID.randomUUID();
    String orderId = "ORD-" + UUID.randomUUID().toString().substring(0, 8);
    String deliveryId = "DLV-RD-" + UUID.randomUUID();
    String account = "ACC-" + UUID.randomUUID();
    String attempt = "pay-" + UUID.randomUUID().toString().replace("-", "");
    participants(orderId, account, attempt, "CREATED");
    required(caseId, deliveryId, orderId, Instant.now());
    Row r = await(caseId, "RUNNING", "AWAIT_PAYMENT");
    Res declined =
        call("POST", "/redelivery-payments/" + r.sagaId() + "/declines", TestJwt.customer(account));
    assertThat(declined.status()).isEqualTo(200);
    assertThat(declined.body().path("status").asString()).isEqualTo("DECLINED");
    r = await(caseId, "COMPLETED", null);
    assertThat(r.outcome()).isEqualTo("DECLINED");
    parts.verify(
        postRequestedFor(
                urlPathEqualTo(
                    "/api/v1/internal/deliveries/" + deliveryId + "/redelivery-cancellations"))
            .withRequestBody(matchingJsonPath("$.reason", WireMock.equalTo("DECLINED"))));
    assertThat(reshipments.byCase(caseId).orElseThrow().mode()).isEqualTo("CUSTOMER_REFUND");
    // 결제 대기가 끝났으면 다시 시도 409
    assertThat(
            call(
                    "POST",
                    "/redelivery-payments/" + r.sagaId() + "/payment-attempts",
                    TestJwt.customer(account))
                .status())
        .isEqualTo(409);
  }
}
