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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 고객센터 문의(0.2.0, 사용자 결정 2026-11-09 C2·C4): 회원 고객만 남기고, 본인 주문만 고르고, 상담원(CS_AGENT)이 답한다.
 * order-service 는 WireMock.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.cases.order-service-base-url=${order.url}",
      "pmart.messaging.outbox.delete-after-insert=false"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock({@ConfigureWireMock(name = "order", baseUrlProperties = "order.url")})
class InquiriesIT extends PostgresKafkaContainerSupport {

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

  @InjectWireMock("order")
  WireMockServer order;

  record Res(int status, JsonNode body) {}

  Res send(String method, String token, String path, @Nullable Object body, @Nullable Integer v) {
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
                  h.set("X-Source-System", "customer-app");
                  if (v != null) {
                    h.set("X-Expected-Version", String.valueOf(v));
                  }
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

  @Test
  void customerAsksAgentAnswersAndOnlyOwnerSeesIt() {
    String me = "CU-" + UUID.randomUUID().toString().substring(0, 8);
    String other = "CU-" + UUID.randomUUID().toString().substring(0, 8);
    String meTok = TestJwt.customer(me);
    String agent = TestJwt.operator("cs-1", List.of("CS_AGENT"), List.of());
    String md = TestJwt.operator("md-1", List.of("MD"), List.of());
    String admin = TestJwt.operator("admin-1", List.of("OPERATOR_ADMIN"), List.of());

    // 일반 문의(주문 없이)
    Res created =
        send(
            "POST",
            meTok,
            "/inquiries",
            Map.of("category", "ACCOUNT", "title", "로그인", "body", "로그인이 안 돼요"),
            null);
    assertThat(created.status()).as(created.body().toString()).isEqualTo(201);
    String id = created.body().path("inquiryId").asString();
    assertThat(created.body().path("status").asString()).isEqualTo("RECEIVED");
    assertThat(created.body().path("origin").asString()).isEqualTo("INQUIRY_PAGE");
    assertThat(created.body().path("caseId").asString()).startsWith("CASE-");

    // 본인만 보고, 남은 404, 상담원 아닌 운영자는 403
    assertThat(send("GET", meTok, "/inquiries/" + id, null, null).status()).isEqualTo(200);
    assertThat(send("GET", TestJwt.customer(other), "/inquiries/" + id, null, null).status())
        .isEqualTo(404);
    assertThat(send("GET", md, "/inquiries/" + id, null, null).status()).isEqualTo(403);
    assertThat(send("GET", md, "/inquiries", null, null).status()).isEqualTo(403);
    // 본사 관리자는 보기만(2026-11-16 사용자 (가)) — 목록·상세 200, 답변 403
    assertThat(send("GET", admin, "/inquiries/" + id, null, null).status()).isEqualTo(200);
    Res adminList = send("GET", admin, "/inquiries", null, null);
    assertThat(adminList.status()).isEqualTo(200);
    assertThat(adminList.body().path("items").findValuesAsString("inquiryId")).contains(id);
    assertThat(
            send("POST", admin, "/inquiries/" + id + "/replies", Map.of("body", "x"), null)
                .status())
        .isEqualTo(403);
    Res mine = send("GET", meTok, "/inquiries", null, null);
    assertThat(mine.body().path("items").findValuesAsString("inquiryId")).containsExactly(id);
    Res others = send("GET", TestJwt.customer(other), "/inquiries", null, null);
    assertThat(others.body().path("items")).isEmpty();

    // 상담원 답변 → ANSWERED, 고객이 답변을 본다. 고객은 답변 불가
    assertThat(
            send("POST", meTok, "/inquiries/" + id + "/replies", Map.of("body", "x"), null)
                .status())
        .isEqualTo(403);
    Res replied =
        send("POST", agent, "/inquiries/" + id + "/replies", Map.of("body", "앱을 다시 설치해 보세요"), 0);
    assertThat(replied.status()).as(replied.body().toString()).isEqualTo(200);
    assertThat(replied.body().path("status").asString()).isEqualTo("ANSWERED");
    // 고객 알림용 사건(답변 내용 없음, N-2)
    String payload =
        jdbc.queryForObject(
            "SELECT payload::text FROM outbox_event WHERE type = 'InquiryAnswered' AND payload::text LIKE ?",
            String.class,
            "%" + id + "%");
    assertThat(payload).contains("\"customerId\"").doesNotContain("앱을 다시 설치해 보세요");
    Res seen = send("GET", meTok, "/inquiries/" + id, null, null);
    assertThat(seen.body().path("replies").path(0).path("body").asString())
        .isEqualTo("앱을 다시 설치해 보세요");
    // 낡은 버전으로 답하면 409
    assertThat(
            send("POST", agent, "/inquiries/" + id + "/replies", Map.of("body", "y"), 0).status())
        .isEqualTo(409);
    // 상담원 목록: 답변됨 필터
    Res answered = send("GET", agent, "/inquiries?status=ANSWERED", null, null);
    assertThat(answered.body().path("items").findValuesAsString("inquiryId")).contains(id);
  }

  @Test
  void orderInquiryChecksOwnershipWithOrderService() {
    String me = "CU-" + UUID.randomUUID().toString().substring(0, 8);
    String meTok = TestJwt.customer(me);
    order.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/orders/ORD-MINE"))
            .willReturn(aResponse().withStatus(200).withBody("{}")));
    order.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/orders/ORD-OTHER"))
            .willReturn(aResponse().withStatus(404)));

    Res ok =
        send(
            "POST",
            meTok,
            "/inquiries",
            Map.of(
                "category",
                "DELIVERY",
                "orderId",
                "ORD-MINE",
                "body",
                "배송 완료인데 못 받았어요",
                "origin",
                "ASSISTANT_HANDOFF"),
            null);
    assertThat(ok.status()).as(ok.body().toString()).isEqualTo(201);
    assertThat(ok.body().path("orderId").asString()).isEqualTo("ORD-MINE");
    assertThat(ok.body().path("origin").asString()).isEqualTo("ASSISTANT_HANDOFF");
    // 고객 토큰을 그대로 넘겨 본인 주문인지 확인
    order.verify(
        getRequestedFor(urlPathEqualTo("/api/v1/orders/ORD-MINE"))
            .withHeader("Authorization", equalTo("Bearer " + meTok)));

    // 0.5.0 통합 조회 — 주문으로 거른다(상담원·관리자)
    String agent = TestJwt.operator("cs-2", List.of("CS_AGENT"), List.of());
    Res byOrder = send("GET", agent, "/inquiries?orderId=ORD-MINE", null, null);
    assertThat(byOrder.status()).isEqualTo(200);
    assertThat(byOrder.body().path("items").findValuesAsString("inquiryId"))
        .contains(ok.body().path("inquiryId").asString());
    assertThat(byOrder.body().path("items").findValuesAsString("orderId")).containsOnly("ORD-MINE");
    assertThat(send("GET", agent, "/inquiries?orderId=ORD-NONE", null, null).body().path("items"))
        .isEmpty();

    Res notMine =
        send(
            "POST",
            meTok,
            "/inquiries",
            Map.of("category", "ORDER", "orderId", "ORD-OTHER", "body", "?"),
            null);
    assertThat(notMine.status()).isEqualTo(422);
    assertThat(notMine.body().path("code").asString()).isEqualTo("ORDER_NOT_FOUND");
  }

  /** 라이더 문의(0.13.0 — 라이더 앱 요청 §11-4, 사용자 "추천대로"): 지원센터(RIDER_OPS)가 답하고 상담원과 나뉜다. */
  @Test
  void riderAsksSupportCenterAnswers() {
    String rider = "RD-" + UUID.randomUUID().toString().substring(0, 8);
    String riderTok = TestJwt.rider(rider);
    String otherRider = TestJwt.rider("RD-" + UUID.randomUUID().toString().substring(0, 8));
    String ops = TestJwt.operator("ro-1", List.of("RIDER_OPS"), List.of());
    String agent = TestJwt.operator("cs-3", List.of("CS_AGENT"), List.of());
    String admin = TestJwt.operator("admin-2", List.of("OPERATOR_ADMIN"), List.of());

    // 라이더 분류만, 주문 없이
    assertThat(
            send("POST", riderTok, "/inquiries", Map.of("category", "ORDER", "body", "x"), null)
                .status())
        .isEqualTo(400);
    assertThat(
            send(
                    "POST",
                    riderTok,
                    "/inquiries",
                    Map.of("category", "OTHER", "orderId", "ORD-1", "body", "x"),
                    null)
                .status())
        .isEqualTo(400);
    // 고객은 라이더 분류를 못 쓴다
    assertThat(
            send(
                    "POST",
                    TestJwt.customer("CU-x"),
                    "/inquiries",
                    Map.of("category", "SETTLEMENT", "body", "x"),
                    null)
                .status())
        .isEqualTo(400);

    Res created =
        send(
            "POST",
            riderTok,
            "/inquiries",
            Map.of("category", "SETTLEMENT", "title", "정산", "body", "지난주 정산이 빠졌어요"),
            null);
    assertThat(created.status()).as(created.body().toString()).isEqualTo(201);
    String id = created.body().path("inquiryId").asString();
    assertThat(created.body().path("requesterType").asString()).isEqualTo("RIDER");
    assertThat(created.body().path("riderId").asString()).isEqualTo(rider);
    assertThat(
            created.body().path("customerId").isMissingNode()
                || created.body().path("customerId").isNull())
        .isTrue();
    String caseType =
        jdbc.queryForObject(
            "SELECT case_type FROM case_record WHERE case_id = ?",
            String.class,
            created.body().path("caseId").asString());
    assertThat(caseType).isEqualTo("RIDER_INQUIRY");

    // 본인만, 다른 라이더 404, 상담원은 못 봄(404)·목록에 없음, 지원센터·관리자는 봄
    assertThat(send("GET", riderTok, "/inquiries/" + id, null, null).status()).isEqualTo(200);
    assertThat(send("GET", otherRider, "/inquiries/" + id, null, null).status()).isEqualTo(404);
    assertThat(send("GET", agent, "/inquiries/" + id, null, null).status()).isEqualTo(404);
    assertThat(
            send("GET", agent, "/inquiries", null, null)
                .body()
                .path("items")
                .findValuesAsString("inquiryId"))
        .doesNotContain(id);
    assertThat(send("GET", agent, "/inquiries?requesterType=RIDER", null, null).status())
        .isEqualTo(403);
    assertThat(
            send("GET", ops, "/inquiries", null, null)
                .body()
                .path("items")
                .findValuesAsString("inquiryId"))
        .contains(id);
    // 지원센터는 주문으로 찾기(고객 문의) 403 — 통합 조회가 원천 불가로 표시하던 그대로
    assertThat(send("GET", ops, "/inquiries?orderId=ORD-1", null, null).status()).isEqualTo(403);
    assertThat(send("GET", admin, "/inquiries/" + id, null, null).status()).isEqualTo(200);
    assertThat(
            send("GET", riderTok, "/inquiries", null, null)
                .body()
                .path("items")
                .findValuesAsString("inquiryId"))
        .containsExactly(id);

    // 상담원 답변 403, 지원센터 답변 → ANSWERED, 고객 알림 사건 없음
    assertThat(
            send("POST", agent, "/inquiries/" + id + "/replies", Map.of("body", "x"), null)
                .status())
        .isEqualTo(403);
    Res replied =
        send("POST", ops, "/inquiries/" + id + "/replies", Map.of("body", "다음 정산에 넣었습니다"), 0);
    assertThat(replied.status()).as(replied.body().toString()).isEqualTo(200);
    assertThat(replied.body().path("status").asString()).isEqualTo("ANSWERED");
    Integer events =
        jdbc.queryForObject(
            "SELECT count(*) FROM outbox_event WHERE type = 'InquiryAnswered' AND payload::text LIKE ?",
            Integer.class,
            "%" + id + "%");
    assertThat(events).isZero();
    // 대신 라이더 알림용 RiderInquiryAnswered(답변 내용 없음)
    String riderEvent =
        jdbc.queryForObject(
            "SELECT payload::text FROM outbox_event WHERE type = 'RiderInquiryAnswered' AND payload::text LIKE ?",
            String.class,
            "%" + id + "%");
    assertThat(riderEvent).contains(rider).doesNotContain("다음 정산에 넣었습니다");
    Res seen = send("GET", riderTok, "/inquiries/" + id, null, null);
    assertThat(seen.body().path("replies").path(0).path("body").asString())
        .isEqualTo("다음 정산에 넣었습니다");
  }

  @Test
  void onlyLoggedInCustomersCanAsk() {
    String agent = TestJwt.operator("cs-2", List.of("CS_AGENT"), List.of());
    assertThat(
            send("POST", agent, "/inquiries", Map.of("category", "OTHER", "body", "x"), null)
                .status())
        .isEqualTo(403);
  }
}
