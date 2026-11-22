package com.pmart.cases;

import static org.assertj.core.api.Assertions.assertThat;

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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 고객 상담 메모(0.34.0 R-13, 사용자 2026-11-23 "상담메모는 상담사가 쓸 수 있겠지. 수정은 적은 사람만 가능하고 삭제도 적은 사람만 가능함"). */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks"
    })
@Import(TestJwtConfiguration.class)
class CustomerNotesIT extends PostgresKafkaContainerSupport {

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

  final String agent = TestJwt.operator("cs-note-1", List.of("CS_AGENT"), List.of());
  final String otherAgent = TestJwt.operator("cs-note-2", List.of("CS_AGENT"), List.of());
  final String admin = TestJwt.operator("admin-note", List.of("OPERATOR_ADMIN"), List.of());
  final String riderOps = TestJwt.operator("ro-note", List.of("RIDER_OPS"), List.of());

  record Res(int status, JsonNode body) {}

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
                  h.set("X-Source-System", "backoffice");
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

  Res send(String method, String token, String path, @Nullable Object body) {
    return send(method, token, path, body, UUID.randomUUID().toString());
  }

  String note(String token, String customerId, String text) {
    Res r = send("POST", token, "/customer-notes", Map.of("customerId", customerId, "text", text));
    assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
    return r.body().path("noteId").asString();
  }

  List<String> ids(String token, String customerId) {
    Res r = send("GET", token, "/customer-notes?customerId=" + customerId, null);
    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    return r.body().path("items").findValuesAsString("noteId");
  }

  @Test
  void agentWritesAgentsAndAdminsReadNewestFirst() {
    String customer = "CU-" + UUID.randomUUID();
    String first = note(agent, customer, "환불 문의 두 번째 — 지난주 통화함");
    String second = note(otherAgent, customer, "다음 상담 때 배송지 다시 확인");
    note(agent, "CU-" + UUID.randomUUID(), "다른 고객");

    assertThat(ids(agent, customer)).containsExactly(second, first);
    assertThat(ids(admin, customer)).containsExactly(second, first);
    JsonNode top = send("GET", agent, "/customer-notes?customerId=" + customer, null).body();
    assertThat(top.path("items").get(0).path("authorId").asString()).isEqualTo("cs-note-2");
    assertThat(top.path("items").get(0).path("editedAt").isNull()).isTrue();
    assertThat(top.path("items").get(0).path("deleted").asBoolean()).isFalse();

    // 쪽 넘김
    JsonNode p1 =
        send("GET", agent, "/customer-notes?customerId=" + customer + "&size=1", null).body();
    assertThat(p1.path("items").findValuesAsString("noteId")).containsExactly(second);
    String cursor = p1.path("page").path("nextCursor").asString();
    assertThat(
            send(
                    "GET",
                    agent,
                    "/customer-notes?customerId=" + customer + "&size=1&cursor=" + cursor,
                    null)
                .body()
                .path("items")
                .findValuesAsString("noteId"))
        .containsExactly(first);

    // 쓰기는 상담원만, 보기는 상담원·관리자만
    Map<String, Object> body = Map.of("customerId", customer, "text", "x");
    assertThat(send("POST", admin, "/customer-notes", body).status()).isEqualTo(403);
    assertThat(send("POST", riderOps, "/customer-notes", body).status()).isEqualTo(403);
    assertThat(send("GET", riderOps, "/customer-notes?customerId=" + customer, null).status())
        .isEqualTo(403);
    assertThat(
            send("POST", agent, "/customer-notes", Map.of("customerId", customer, "text", " "))
                .status())
        .isEqualTo(400);

    // 같은 키 재전송은 같은 메모
    String key = UUID.randomUUID().toString();
    Res a =
        send("POST", agent, "/customer-notes", Map.of("customerId", customer, "text", "y"), key);
    Res b =
        send("POST", agent, "/customer-notes", Map.of("customerId", customer, "text", "y"), key);
    assertThat(b.body().path("noteId").asString()).isEqualTo(a.body().path("noteId").asString());
  }

  @Test
  void onlyAuthorRevisesAndDeletes() {
    String customer = "CU-" + UUID.randomUUID();
    String id = note(agent, customer, "처음 글");

    // 다른 상담원·관리자는 고치기·지우기 403
    for (String t : List.of(otherAgent, admin)) {
      assertThat(
              send("POST", t, "/customer-notes/" + id + "/revisions", Map.of("text", "남이 고침"))
                  .status())
          .isEqualTo(403);
      assertThat(send("POST", t, "/customer-notes/" + id + "/deletions", null).status())
          .isEqualTo(403);
    }

    Res revised =
        send("POST", agent, "/customer-notes/" + id + "/revisions", Map.of("text", "고친 글"));
    assertThat(revised.status()).as(revised.body().toString()).isEqualTo(200);
    assertThat(revised.body().path("text").asString()).isEqualTo("고친 글");
    assertThat(revised.body().path("editedAt").isNull()).isFalse();

    Res deleted = send("POST", agent, "/customer-notes/" + id + "/deletions", null);
    assertThat(deleted.status()).as(deleted.body().toString()).isEqualTo(200);
    assertThat(deleted.body().path("deleted").asBoolean()).isTrue();
    assertThat(deleted.body().path("text").isNull()).isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT text FROM customer_note WHERE note_id = ?", String.class, id))
        .as("글은 DB 에서 지움")
        .isNull();
    assertThat(ids(agent, customer)).as("지운 메모는 목록에서 빠짐").isEmpty();
    assertThat(send("POST", agent, "/customer-notes/" + id + "/deletions", null).status())
        .as("이미 지웠으면 그대로")
        .isEqualTo(200);
    assertThat(
            send("POST", agent, "/customer-notes/" + id + "/revisions", Map.of("text", "되살림"))
                .status())
        .as("지운 메모는 못 고침")
        .isEqualTo(404);
    assertThat(
            send("POST", agent, "/customer-notes/CNOTE-none/revisions", Map.of("text", "x"))
                .status())
        .isEqualTo(404);
  }
}
