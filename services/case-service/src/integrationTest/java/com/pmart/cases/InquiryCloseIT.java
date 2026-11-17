package com.pmart.cases;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.util.HashMap;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 문의 닫기(0.19.0 — 2026-11-17 사용자 "그 표대로 진행해, 다시 열기는 빼고", 업무 원문 Commerce §14.2-5): 본인 또는 답변 역할 직원(사유
 * 필수, 고객에게는 안 보임), 닫을 때 만족도(답변 있을 때만), 다시 열기 없음, 접수 건 행도 닫힘.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.messaging.outbox.delete-after-insert=false",
      "pmart.test.context=inquiry-close"
    })
@Import(TestJwtConfiguration.class)
class InquiryCloseIT extends PostgresKafkaContainerSupport {

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;

  final String agent = TestJwt.operator("cs-c1", List.of("CS_AGENT"), List.of());
  final String admin = TestJwt.operator("admin-c1", List.of("OPERATOR_ADMIN"), List.of());
  final String ops = TestJwt.operator("ro-c1", List.of("RIDER_OPS"), List.of());

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

  String ask(String token, String category) {
    Res r = send("POST", token, "/inquiries", Map.of("category", category, "body", "문의"));
    assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
    return r.body().path("inquiryId").asString();
  }

  void answer(String token, String inquiryId) {
    Res r = send("POST", token, "/inquiries/" + inquiryId + "/replies", Map.of("body", "답변"));
    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
  }

  Res close(String token, String inquiryId, Map<String, Object> body) {
    return send("POST", token, "/inquiries/" + inquiryId + "/closures", body);
  }

  String caseState(String inquiryId) {
    return jdbc.queryForObject(
        "SELECT c.state FROM case_record c JOIN cs_inquiry q ON q.case_id = c.case_id"
            + " WHERE q.inquiry_id = ?",
        String.class,
        inquiryId);
  }

  @Test
  void customerClosesWithSatisfactionAndNothingReopens() {
    String me = "CU-" + UUID.randomUUID();
    String meTok = TestJwt.customer(me);
    String other = TestJwt.customer("CU-" + UUID.randomUUID());
    String inq = ask(meTok, "DELIVERY");
    answer(agent, inq);

    assertThat(close(other, inq, Map.of()).status()).as("남의 문의").isEqualTo(404);
    assertThat(close(admin, inq, Map.of("reason", "정리")).status()).as("관리자는 보기만").isEqualTo(403);
    assertThat(close(meTok, inq, Map.of("score", 6)).status()).isEqualTo(400);

    int version = send("GET", meTok, "/inquiries/" + inq, null).body().path("version").asInt();
    Res closed = close(meTok, inq, Map.of("score", 5, "comment", "해결됐어요"));
    assertThat(closed.status()).as(closed.body().toString()).isEqualTo(200);
    assertThat(closed.body().path("status").asString()).isEqualTo("CLOSED");
    assertThat(closed.body().path("closedByType").asString()).isEqualTo("CUSTOMER");
    assertThat(closed.body().path("closedAt").asString()).isNotBlank();
    assertThat(closed.body().path("version").asInt()).isEqualTo(version + 1);
    assertThat(closed.body().path("satisfaction").path("score").asInt()).isEqualTo(5);
    assertThat(caseState(inq)).as("접수 건 행도 닫힘").isEqualTo("CLOSED");

    Res again = close(meTok, inq, Map.of());
    assertThat(again.status()).isEqualTo(409);
    assertThat(again.body().path("code").asString()).isEqualTo("INQUIRY_CLOSED");
    Res reply = send("POST", agent, "/inquiries/" + inq + "/replies", Map.of("body", "추가"));
    assertThat(reply.status()).as("닫힌 문의에 답변 없음").isEqualTo(409);
    assertThat(send("GET", meTok, "/inquiries/" + inq, null).status())
        .as("닫혀도 계속 볼 수 있음")
        .isEqualTo(200);

    // 답변 전에도 닫을 수 있다(만족도는 못 남김)
    String early = ask(meTok, "OTHER");
    assertThat(close(meTok, early, Map.of("score", 4)).status())
        .as("답변 없는 문의엔 만족도 없음")
        .isEqualTo(400);
    assertThat(close(meTok, early, Map.of("reason", "고객이 쓴 사유는 남기지 않음")).status()).isEqualTo(200);
    assertThat(send("GET", agent, "/inquiries/" + early, null).body().path("closeReason").isNull())
        .isTrue();

    // 답변 받고 만족도 없이 닫은 뒤에 따로 남기기
    String later = ask(meTok, "ORDER");
    answer(agent, later);
    assertThat(close(meTok, later, Map.of()).status()).isEqualTo(200);
    assertThat(
            send("POST", meTok, "/inquiries/" + later + "/satisfaction", Map.of("score", 3))
                .status())
        .as("닫은 뒤 만족도는 그대로 됨")
        .isEqualTo(200);
  }

  @Test
  void staffCloseNeedsAnswerRoleAndReasonHiddenFromCustomer() {
    String me = "CU-" + UUID.randomUUID();
    String meTok = TestJwt.customer(me);
    String inq = ask(meTok, "PAYMENT");

    assertThat(close(agent, inq, Map.of()).status()).as("직원은 사유 필수").isEqualTo(400);
    Map<String, Object> withScore = new HashMap<>();
    withScore.put("reason", "중복 문의");
    withScore.put("score", 5);
    assertThat(close(agent, inq, withScore).status()).as("직원은 만족도 못 남김").isEqualTo(400);
    assertThat(close(ops, inq, Map.of("reason", "중복 문의")).status())
        .as("고객 문의는 상담원이")
        .isEqualTo(403);

    assertThat(close(agent, "INQ-" + "x".repeat(70), Map.of("reason", "정리")).status())
        .as("경로 ID 64자 넘음 — 500 이 아니라 400(0.19.1)")
        .isEqualTo(400);
    String longReasonInq = ask(meTok, "OTHER");
    assertThat(close(agent, longReasonInq, Map.of("reason", "가".repeat(500))).status())
        .as("사유 500자도 닫힘(이력 메모에 사유를 붙이지 않음 — 0.19.1)")
        .isEqualTo(200);

    Res closed = close(agent, inq, Map.of("reason", "중복 문의 — 다른 문의로 안내"));
    assertThat(closed.status()).as(closed.body().toString()).isEqualTo(200);
    assertThat(closed.body().path("closedByType").asString()).isEqualTo("STAFF");
    assertThat(closed.body().path("closeReason").asString()).isEqualTo("중복 문의 — 다른 문의로 안내");
    JsonNode mine = send("GET", meTok, "/inquiries/" + inq, null).body();
    assertThat(mine.path("closedByType").asString()).isEqualTo("STAFF");
    assertThat(mine.path("closeReason").isNull() || mine.path("closeReason").isMissingNode())
        .as("사유는 직원에게만")
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM case_history h JOIN cs_inquiry q ON q.case_id = h.case_id"
                    + " WHERE q.inquiry_id = ? AND h.action = 'INQUIRY_CLOSED'",
                Integer.class,
                inq))
        .isEqualTo(1);

    // 라이더 문의는 지원센터가
    String rider = "RD-" + UUID.randomUUID().toString().substring(0, 8);
    String riderTok = TestJwt.rider(rider);
    String rq = ask(riderTok, "APP_ERROR");
    assertThat(close(agent, rq, Map.of("reason", "정리")).status()).isEqualTo(403);
    assertThat(close(ops, rq, Map.of("reason", "앱 업데이트로 해결")).status()).isEqualTo(200);

    // 상담원이 남긴 문의(STAFF_INTAKE)는 고객이 못 닫고 상담원이 닫는다
    Res intake =
        send(
            "POST",
            agent,
            "/cs-intakes",
            Map.of(
                "channel", "PHONE",
                "category", "OTHER",
                "customerRef", me,
                "summary", "전화 문의",
                "identityCheck", Map.of("method", "OTHER", "note", "가입 이메일 대조"),
                "callerRelation", "SELF"));
    assertThat(intake.status()).as(intake.body().toString()).isEqualTo(201);
    String staffInq = intake.body().path("inquiryId").asString();
    assertThat(close(meTok, staffInq, Map.of()).status()).isEqualTo(404);
    assertThat(close(agent, staffInq, Map.of("reason", "통화로 해결")).status()).isEqualTo(200);
  }
}
