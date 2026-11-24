package com.pmart.cases;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 문의 만족도(0.15.0 — 2026-11-17 사용자 "답변 뒤 1~5점 + 한 줄", 업무 원문 Commerce §14.2): 답변 받은 고객 문의에 남긴 고객이 한 번,
 * 상담원에게는 안 보이고 본사 관리자만 모아 본다(분자·분모, 분모 0 이면 평균 없음 — BO-28).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.messaging.outbox.delete-after-insert=false",
      "pmart.test.context=inquiry-satisfaction"
    })
@Import(TestJwtConfiguration.class)
class InquirySatisfactionIT extends PostgresKafkaContainerSupport {

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;

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
                  h.set("X-Source-System", "customer-app");
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

  String ask(String token) {
    Res r = send("POST", token, "/inquiries", Map.of("category", "DELIVERY", "body", "늦어요"));
    assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
    return r.body().path("inquiryId").asString();
  }

  void answer(String agent, String inquiryId) {
    Res r = send("POST", agent, "/inquiries/" + inquiryId + "/replies", Map.of("body", "확인했어요"));
    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
  }

  JsonNode agentRow(JsonNode byAgent, String agentId) {
    for (JsonNode i : byAgent.path("items")) {
      if (agentId.equals(i.path("agentId").asString())) {
        return i;
      }
    }
    throw new AssertionError("직원 줄 없음: " + agentId + " " + byAgent);
  }

  /** 0.38.0 — 사용자 2026-11-24 "직원마다 평점, 고객 개인정보는 가리고, 관리자가 개인 성과 측정·관리 목적". */
  @Test
  void adminSeesSatisfactionPerAgentWithoutCustomerData() {
    String me = "CU-" + UUID.randomUUID();
    String meTok = TestJwt.customer(me);
    String a = "cs-a-" + UUID.randomUUID();
    String b = "cs-b-" + UUID.randomUUID();
    String aTok = TestJwt.operator(a, List.of("CS_AGENT"), List.of());
    String bTok = TestJwt.operator(b, List.of("CS_AGENT"), List.of());
    String admin = TestJwt.operator("admin-s2", List.of("OPERATOR_ADMIN"), List.of());
    Instant from = Instant.now().minus(1, ChronoUnit.MINUTES);

    // ① 둘이 답했으면 평가 직전 마지막 답변자(B) 몫
    String inq1 = ask(meTok);
    answer(aTok, inq1);
    answer(bTok, inq1);
    assertThat(
            send(
                    "POST",
                    meTok,
                    "/inquiries/" + inq1 + "/satisfaction",
                    Map.of("score", 4, "comment", "010-1234-5678 로 연락 주셨어요 hong@example.com"))
                .status())
        .isEqualTo(200);
    // ② 평가 뒤에 다른 직원이 또 답해도 평가 때 답변자(A) 몫
    String inq2 = ask(meTok);
    answer(aTok, inq2);
    assertThat(
            send("POST", meTok, "/inquiries/" + inq2 + "/satisfaction", Map.of("score", 2))
                .status())
        .isEqualTo(200);
    answer(bTok, inq2);
    // ③ 평가 없는 문의는 지금 마지막 답변자(A) 분모
    answer(aTok, ask(meTok));
    // ④ A 몫 하나 더(쪽 넘김)
    String inq4 = ask(meTok);
    answer(aTok, inq4);
    assertThat(
            send("POST", meTok, "/inquiries/" + inq4 + "/satisfaction", Map.of("score", 5))
                .status())
        .isEqualTo(200);

    String range =
        "answeredFrom=" + from + "&answeredTo=" + Instant.now().plus(1, ChronoUnit.MINUTES);
    Res by = send("GET", admin, "/inquiries/satisfaction-summary/by-agent?" + range, null);
    assertThat(by.status()).as(by.body().toString()).isEqualTo(200);
    JsonNode ra = agentRow(by.body(), a);
    assertThat(ra.path("answeredCount").asLong()).isEqualTo(3);
    assertThat(ra.path("ratedCount").asLong()).isEqualTo(2);
    assertThat(ra.path("scoreSum").asLong()).isEqualTo(7);
    assertThat(ra.path("averageScore").decimalValue()).isEqualByComparingTo("3.50");
    assertThat(ra.path("byScore").path("2").asLong()).isEqualTo(1);
    assertThat(ra.path("byScore").path("4").asLong()).isZero();
    JsonNode rb = agentRow(by.body(), b);
    assertThat(rb.path("answeredCount").asLong()).isEqualTo(1);
    assertThat(rb.path("ratedCount").asLong()).isEqualTo(1);
    assertThat(rb.path("byScore").path("4").asLong()).isEqualTo(1);

    // 직원별 평가 목록 — 고객 정보 없음, 의견 속 전화·메일 가림
    Res lb = send("GET", admin, "/inquiries/satisfaction-ratings?agentId=" + b + "&" + range, null);
    assertThat(lb.status()).as(lb.body().toString()).isEqualTo(200);
    assertThat(lb.body().path("items")).hasSize(1);
    JsonNode one = lb.body().path("items").get(0);
    assertThat(one.path("inquiryId").asString()).isEqualTo(inq1);
    assertThat(one.path("inquiryNumber").asString()).startsWith("Q-");
    assertThat(one.path("comment").asString()).isEqualTo("010-****-**** 로 연락 주셨어요 h***@***");
    assertThat(one.has("customerId")).isFalse();
    assertThat(lb.body().toString()).doesNotContain(me);

    Res p1 =
        send(
            "GET",
            admin,
            "/inquiries/satisfaction-ratings?agentId=" + a + "&size=1&" + range,
            null);
    assertThat(p1.body().path("items").get(0).path("inquiryId").asString()).isEqualTo(inq4);
    String next = p1.body().path("page").path("nextCursor").asString();
    Res p2 =
        send(
            "GET",
            admin,
            "/inquiries/satisfaction-ratings?agentId=" + a + "&size=1&cursor=" + next + "&" + range,
            null);
    assertThat(p2.body().path("items").get(0).path("inquiryId").asString()).isEqualTo(inq2);
    assertThat(p2.body().path("page").path("nextCursor").isString()).isFalse();

    // 관리자만 — 상담원 본인에게도 안 보인다
    assertThat(
            send("GET", aTok, "/inquiries/satisfaction-summary/by-agent?" + range, null).status())
        .isEqualTo(403);
    assertThat(
            send("GET", aTok, "/inquiries/satisfaction-ratings?agentId=" + a + "&" + range, null)
                .status())
        .isEqualTo(403);

    // 답변 쓴 사람 — 직원에게만
    JsonNode staffReplies = send("GET", admin, "/inquiries/" + inq1, null).body().path("replies");
    assertThat(staffReplies.get(0).path("authorId").asString()).isEqualTo(a);
    assertThat(staffReplies.get(1).path("authorId").asString()).isEqualTo(b);
    JsonNode mine = send("GET", meTok, "/inquiries/" + inq1, null).body().path("replies");
    assertThat(mine.get(0).path("authorId").isString()).as("고객에게 직원 ID 없음").isFalse();
  }

  @Test
  void customerRatesOnceAfterAnswerAndOnlyAdminSeesIt() {
    String me = "CU-" + UUID.randomUUID();
    String meTok = TestJwt.customer(me);
    String otherTok = TestJwt.customer("CU-" + UUID.randomUUID());
    String agent = TestJwt.operator("cs-s1", List.of("CS_AGENT"), List.of());
    String admin = TestJwt.operator("admin-s1", List.of("OPERATOR_ADMIN"), List.of());
    Instant from = Instant.now().minus(1, ChronoUnit.MINUTES);

    String inq = ask(meTok);
    String rate = "/inquiries/" + inq + "/satisfaction";
    Res early = send("POST", meTok, rate, Map.of("score", 5));
    assertThat(early.status()).isEqualTo(409);
    assertThat(early.body().path("code").asString()).isEqualTo("INQUIRY_NOT_ANSWERED");

    answer(agent, inq);
    String unrated = ask(meTok);
    answer(agent, unrated);

    assertThat(send("POST", otherTok, rate, Map.of("score", 5)).status())
        .as("남의 문의")
        .isEqualTo(404);
    assertThat(send("POST", agent, rate, Map.of("score", 5)).status()).as("직원").isEqualTo(403);
    assertThat(send("POST", meTok, rate, Map.of("score", 6)).status()).isEqualTo(400);
    assertThat(send("POST", meTok, rate, Map.of("score", 3, "comment", "가".repeat(201))).status())
        .isEqualTo(400);

    int version = send("GET", meTok, "/inquiries/" + inq, null).body().path("version").asInt();
    Res rated = send("POST", meTok, rate, Map.of("score", 4, "comment", "빨리 답해 줬어요"));
    assertThat(rated.status()).as(rated.body().toString()).isEqualTo(200);
    assertThat(rated.body().path("satisfaction").path("score").asInt()).isEqualTo(4);
    assertThat(rated.body().path("satisfaction").path("comment").asString()).isEqualTo("빨리 답해 줬어요");
    assertThat(rated.body().path("version").asInt()).as("상태·버전 그대로").isEqualTo(version);
    assertThat(rated.body().path("status").asString()).isEqualTo("ANSWERED");

    Res again = send("POST", meTok, rate, Map.of("score", 1));
    assertThat(again.status()).isEqualTo(409);
    assertThat(again.body().path("code").asString()).isEqualTo("ALREADY_RATED");

    // 상담원에게는 안 보이고, 본인·관리자에게만
    assertThat(send("GET", agent, "/inquiries/" + inq, null).body().path("satisfaction").isObject())
        .as("상담원 평가에 쓰지 않음")
        .isFalse();
    assertThat(
            send("GET", admin, "/inquiries/" + inq, null)
                .body()
                .path("satisfaction")
                .path("score")
                .asInt())
        .isEqualTo(4);
    assertThat(
            send("GET", meTok, "/inquiries/" + inq, null)
                .body()
                .path("satisfaction")
                .path("score")
                .asInt())
        .isEqualTo(4);

    // 모아 보기 — 관리자만, 분자·분모
    String to = Instant.now().plus(1, ChronoUnit.MINUTES).toString();
    String q = "/inquiries/satisfaction-summary?answeredFrom=" + from + "&answeredTo=" + to;
    assertThat(send("GET", agent, q, null).status()).isEqualTo(403);
    Res sum = send("GET", admin, q, null);
    assertThat(sum.status()).as(sum.body().toString()).isEqualTo(200);
    assertThat(sum.body().path("answeredCount").asLong()).isGreaterThanOrEqualTo(2);
    assertThat(sum.body().path("ratedCount").asLong())
        .isGreaterThanOrEqualTo(1)
        .isLessThan(sum.body().path("answeredCount").asLong());
    assertThat(sum.body().path("byScore").path("4").asLong()).isGreaterThanOrEqualTo(1);
    assertThat(sum.body().path("averageScore").isNumber()).isTrue();

    // 빈 기간은 평균 없음(N/A), 92일 넘으면 400
    Res empty =
        send(
            "GET",
            admin,
            "/inquiries/satisfaction-summary?answeredFrom=2020-01-01T00:00:00Z&answeredTo=2020-01-02T00:00:00Z",
            null);
    assertThat(empty.body().path("answeredCount").asLong()).isZero();
    assertThat(empty.body().path("averageScore").isNumber()).as("N/A").isFalse();
    assertThat(empty.body().path("byScore").path("5").asLong()).isZero();
    assertThat(
            send(
                    "GET",
                    admin,
                    "/inquiries/satisfaction-summary?answeredFrom=2020-01-01T00:00:00Z&answeredTo=2020-06-01T00:00:00Z",
                    null)
                .status())
        .isEqualTo(400);
  }
}
