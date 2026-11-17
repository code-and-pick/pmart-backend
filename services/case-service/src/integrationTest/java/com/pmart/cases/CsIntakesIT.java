package com.pmart.cases;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.util.HashMap;
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
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * CS 접수(0.12.0 — 2026-11-16 사용자 결정 BO-11): 상담원이 전화·채팅·메일 연락을 남긴다. 본인 확인 방법 필수, 대리는 표시만, 회원만, 주문은 그
 * 고객 것인지 직원 주문 조회로 확인, 새 건은 고객 앱에 안 보이는 문의(답변 알림 없음), 기존 건 연결은 이력만.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.cases.order-service-base-url=${order.url}",
      "pmart.messaging.outbox.delete-after-insert=false",
      // InquiriesIT 와 설정이 같으면 컨텍스트가 공유되는데 컨테이너는 클래스마다라 끊긴다 — 따로 둔다
      "pmart.test.context=cs-intakes"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock({@ConfigureWireMock(name = "order", baseUrlProperties = "order.url")})
class CsIntakesIT extends PostgresKafkaContainerSupport {

  static final String AGENT = TestJwt.operator("cs-in-1", List.of("CS_AGENT"), List.of());
  static final String ADMIN = TestJwt.operator("adm-in-1", List.of("OPERATOR_ADMIN"), List.of());
  static final String RIDER_OPS = TestJwt.operator("ro-in-1", List.of("RIDER_OPS"), List.of());

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;

  @InjectWireMock("order")
  WireMockServer order;

  String me;
  String orderId;

  @BeforeEach
  void setUp() {
    order.resetAll();
    me = "CU-" + UUID.randomUUID().toString().substring(0, 8);
    orderId = "ORD-" + UUID.randomUUID().toString().substring(0, 8);
    order.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/orders/lookups/" + orderId))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"orderId\":\"" + orderId + "\",\"customerId\":\"" + me + "\"}")));
    order.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/orders/lookups/ORD-NONE"))
            .willReturn(aResponse().withStatus(404)));
  }

  record Res(int status, JsonNode body) {}

  Res send(String method, String token, String path, @Nullable Object body) {
    return send(method, token, path, body, UUID.randomUUID().toString());
  }

  Res send(String method, String token, String path, @Nullable Object body, String key) {
    var spec =
        RestClient.builder()
            .requestFactory(new JdkClientHttpRequestFactory())
            .build()
            .method(HttpMethod.valueOf(method))
            .uri("http://localhost:" + port + "/api/v1" + path)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", key);
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

  Map<String, Object> phoneCall() {
    Map<String, Object> m = new HashMap<>();
    m.put("channel", "PHONE");
    m.put("category", "DELIVERY");
    m.put("customerRef", me);
    m.put("subjectRef", orderId);
    m.put("summary", "배송이 늦는다는 전화");
    m.put("identityCheck", Map.of("method", "ORDER_DETAILS_MATCH"));
    m.put("callerRelation", "SELF");
    return m;
  }

  @Test
  void phoneIntakeOpensStaffInquiryHiddenFromCustomerWithoutNotification() {
    String key = UUID.randomUUID().toString();
    Res r = send("POST", AGENT, "/cs-intakes", phoneCall(), key);
    assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
    String intakeId = r.body().path("intakeId").asString();
    String inquiryId = r.body().path("inquiryId").asString();
    assertThat(intakeId).startsWith("CSI-");
    assertThat(inquiryId).startsWith("INQ-");
    assertThat(r.body().path("linked").asBoolean()).isFalse();
    assertThat(r.body().path("identityCheck").path("method").asString())
        .isEqualTo("ORDER_DETAILS_MATCH");
    assertThat(r.body().path("callerRelation").asString()).isEqualTo("SELF");
    assertThat(r.body().path("consentRecorded").asBoolean()).isFalse();
    assertThat(r.body().path("receivedBy").asString()).isEqualTo("cs-in-1");
    // 직원 토큰으로 직원 주문 조회
    order.verify(
        getRequestedFor(urlPathEqualTo("/api/v1/orders/lookups/" + orderId))
            .withHeader("Authorization", equalTo("Bearer " + AGENT)));

    // 같은 키 재전송은 같은 접수
    Res again = send("POST", AGENT, "/cs-intakes", phoneCall(), key);
    assertThat(again.body().path("intakeId").asString()).isEqualTo(intakeId);

    // 상담원은 문의로 본다 — 경로·본인/대리가 붙는다
    Res inq = send("GET", AGENT, "/inquiries/" + inquiryId, null);
    assertThat(inq.status()).isEqualTo(200);
    assertThat(inq.body().path("origin").asString()).isEqualTo("STAFF_INTAKE");
    assertThat(inq.body().path("channel").asString()).isEqualTo("PHONE");
    assertThat(inq.body().path("callerRelation").asString()).isEqualTo("SELF");
    assertThat(inq.body().path("orderId").asString()).isEqualTo(orderId);
    Res byOrder = send("GET", AGENT, "/inquiries?orderId=" + orderId, null);
    assertThat(byOrder.body().path("items").findValuesAsString("inquiryId")).contains(inquiryId);

    // 고객 앱에는 안 보인다
    String meTok = TestJwt.customer(me);
    assertThat(send("GET", meTok, "/inquiries/" + inquiryId, null).status()).isEqualTo(404);
    assertThat(send("GET", meTok, "/inquiries", null).body().path("items")).isEmpty();

    // 답하면 ANSWERED, 고객 알림(InquiryAnswered) 없음
    Res replied =
        send("POST", AGENT, "/inquiries/" + inquiryId + "/replies", Map.of("body", "통화로 안내함"));
    assertThat(replied.status()).as(replied.body().toString()).isEqualTo(200);
    assertThat(replied.body().path("status").asString()).isEqualTo("ANSWERED");
    Integer events =
        jdbc.queryForObject(
            "SELECT count(*) FROM outbox_event WHERE type = 'InquiryAnswered' AND payload::text LIKE ?",
            Integer.class,
            "%" + inquiryId + "%");
    assertThat(events).isZero();

    // 조회·목록
    assertThat(send("GET", ADMIN, "/cs-intakes/" + intakeId, null).status()).isEqualTo(200);
    Res byCustomer = send("GET", AGENT, "/cs-intakes?customerRef=" + me, null);
    assertThat(byCustomer.body().path("items").findValuesAsString("intakeId"))
        .containsExactly(intakeId);
  }

  /** 0.19.2: 같은 키 재전송은 order 가 내려가 있어도 저장된 결과를 재생, 새 요청은 503. */
  @Test
  void replayDoesNotNeedOrderServiceButNewRequestDoes() {
    String key = UUID.randomUUID().toString();
    Res first = send("POST", AGENT, "/cs-intakes", phoneCall(), key);
    assertThat(first.status()).as(first.body().toString()).isEqualTo(201);
    order.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/orders/lookups/" + orderId))
            .willReturn(aResponse().withStatus(503)));
    Res replay = send("POST", AGENT, "/cs-intakes", phoneCall(), key);
    assertThat(replay.status()).as(replay.body().toString()).isBetween(200, 201);
    assertThat(replay.body().path("intakeId").asString())
        .isEqualTo(first.body().path("intakeId").asString());
    Res fresh = send("POST", AGENT, "/cs-intakes", phoneCall(), UUID.randomUUID().toString());
    assertThat(fresh.status()).as("새 요청은 주문 확인이 필요").isEqualTo(503);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM cs_intake WHERE customer_id = ?", Integer.class, me))
        .as("재전송·실패로 더 남지 않음")
        .isEqualTo(1);
  }

  @Test
  void proxyCallWithConsentLinksToExistingCase() {
    Res first = send("POST", AGENT, "/cs-intakes", phoneCall());
    String caseId = first.body().path("caseId").asString();

    Map<String, Object> proxy = phoneCall();
    proxy.put("channel", "CHAT");
    proxy.put("callerRelation", "PROXY");
    proxy.put("identityCheck", Map.of("method", "OTHER", "note", "가족이라며 주문 내용을 말함"));
    proxy.put(
        "consent", Map.of("consentType", "OOS_SUBSTITUTE", "given", true, "detail", "우유 1개 대체 동의"));
    proxy.put("linkCaseId", caseId);
    Res r = send("POST", ADMIN, "/cs-intakes", proxy);
    assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
    assertThat(r.body().path("linked").asBoolean()).isTrue();
    assertThat(r.body().path("caseId").asString()).isEqualTo(caseId);
    assertThat(r.body().path("inquiryId").isNull()).isTrue();
    assertThat(r.body().path("callerRelation").asString()).isEqualTo("PROXY");
    assertThat(r.body().path("identityCheck").path("note").asString()).contains("가족");
    assertThat(r.body().path("consentRecorded").asBoolean()).isTrue();
    assertThat(r.body().path("consent").path("given").asBoolean()).isTrue();
    assertThat(r.body().path("consent").path("recordedAt").isMissingNode()).isFalse();

    // 건으로 모아 본다 — 최근 것부터
    Res byCase = send("GET", AGENT, "/cs-intakes?caseId=" + caseId, null);
    assertThat(byCase.body().path("items").findValuesAsString("intakeId"))
        .containsExactly(
            r.body().path("intakeId").asString(), first.body().path("intakeId").asString());
    Integer linked =
        jdbc.queryForObject(
            "SELECT count(*) FROM case_history WHERE case_id = ? AND action = 'CS_INTAKE_LINKED'",
            Integer.class,
            caseId);
    assertThat(linked).isEqualTo(1);

    // 없는 건 연결 404
    proxy.put("linkCaseId", "CASE-NONE");
    assertThat(send("POST", AGENT, "/cs-intakes", proxy).status()).isEqualTo(404);
  }

  @Test
  void rejectsWrongInputsRolesAndOrders() {
    // 역할
    assertThat(send("POST", RIDER_OPS, "/cs-intakes", phoneCall()).status()).isEqualTo(403);
    assertThat(send("POST", TestJwt.customer(me), "/cs-intakes", phoneCall()).status())
        .isEqualTo(403);
    assertThat(send("GET", RIDER_OPS, "/cs-intakes?customerRef=" + me, null).status())
        .isEqualTo(403);

    // 입력
    Map<String, Object> app = phoneCall();
    app.put("channel", "APP");
    assertThat(send("POST", AGENT, "/cs-intakes", app).status()).isEqualTo(400);
    Map<String, Object> rider = phoneCall();
    rider.put("category", "RIDER");
    assertThat(send("POST", AGENT, "/cs-intakes", rider).status()).isEqualTo(400);
    Map<String, Object> noOrder = phoneCall();
    noOrder.remove("subjectRef");
    assertThat(send("POST", AGENT, "/cs-intakes", noOrder).status())
        .as("주문 내용 대조인데 주문이 없음")
        .isEqualTo(400);
    Map<String, Object> other = phoneCall();
    other.put("identityCheck", Map.of("method", "OTHER"));
    assertThat(send("POST", AGENT, "/cs-intakes", other).status()).isEqualTo(400);
    Map<String, Object> appChatByPhone = phoneCall();
    appChatByPhone.put("identityCheck", Map.of("method", "APP_SIGNED_IN_CHAT"));
    assertThat(send("POST", AGENT, "/cs-intakes", appChatByPhone).status()).isEqualTo(400);
    Map<String, Object> noIdentity = phoneCall();
    noIdentity.remove("identityCheck");
    assertThat(send("POST", AGENT, "/cs-intakes", noIdentity).status()).isEqualTo(400);
    Map<String, Object> noCustomer = phoneCall();
    noCustomer.remove("customerRef");
    assertThat(send("POST", AGENT, "/cs-intakes", noCustomer).status()).isEqualTo(400);
    assertThat(send("GET", AGENT, "/cs-intakes", null).status()).as("조건 없음").isEqualTo(400);

    // 주문
    Map<String, Object> missing = phoneCall();
    missing.put("subjectRef", "ORD-NONE");
    Res nf = send("POST", AGENT, "/cs-intakes", missing);
    assertThat(nf.status()).isEqualTo(422);
    assertThat(nf.body().path("code").asString()).isEqualTo("ORDER_NOT_FOUND");
    Map<String, Object> mismatch = phoneCall();
    mismatch.put("customerRef", "CU-someone-else");
    Res mm = send("POST", AGENT, "/cs-intakes", mismatch);
    assertThat(mm.status()).isEqualTo(422);
    assertThat(mm.body().path("code").asString()).isEqualTo("CUSTOMER_ORDER_MISMATCH");

    // 주문 없는 계정 문의는 주문 조회 없이
    Map<String, Object> account = phoneCall();
    account.remove("subjectRef");
    account.put("category", "ACCOUNT");
    account.put("identityCheck", Map.of("method", "OTHER", "note", "가입 이메일 대조"));
    assertThat(send("POST", AGENT, "/cs-intakes", account).status()).isEqualTo(201);
    // 아무 것도 저장되지 않은 실패들 — 이 고객 접수는 계정 문의 하나
    assertThat(
            send("GET", AGENT, "/cs-intakes?customerRef=" + me, null).body().path("items").size())
        .isEqualTo(1);
  }
}
