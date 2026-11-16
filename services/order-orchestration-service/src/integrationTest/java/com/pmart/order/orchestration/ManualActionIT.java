package com.pmart.order.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.PostgresKafkaContainerSupport;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 수동 개입(BO-12, 0.2.0 — 2026-11-16): 멈춘 사가 목록(업무 거절 ESCALATED → MARK_RESOLVED 만), 처리 완료 표시 뒤 목록에서
 * 빠짐·같은 키 재전송 같은 결과, 결제 사가가 아니면 다시 확인 422, 승인이 필요한 조치 422, 조치 기록 조회.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.orchestration.sweep-interval=PT1H",
      "pmart.test.context=manual-action"
    })
class ManualActionIT extends PostgresKafkaContainerSupport {

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;

  record Res(int status, JsonNode body) {}

  Res call(String method, String path, @Nullable Object body, String key) {
    var spec =
        RestClient.builder()
            .requestFactory(new JdkClientHttpRequestFactory())
            .build()
            .method(HttpMethod.valueOf(method))
            .uri("http://localhost:" + port + "/api/v1" + path)
            .headers(
                h -> {
                  h.set("Idempotency-Key", key);
                  h.set("X-Source-System", "backoffice-bff");
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

  String escalatedShortage() {
    String id = "SSG-MA-" + UUID.randomUUID();
    jdbc.update(
        "INSERT INTO shortage_saga (saga_id, event_id, order_id, fulfillment_id, order_line_id, short_qty,"
            + " state, current_step, last_error_step, last_error_code, last_error_message, started_at, updated_at)"
            + " VALUES (?, ?, ?, 'FUL-1', 'L1', 1, 'ESCALATED', 'RESOLVE_WORK', 'RESOLVE_WORK', 'WORK_ALREADY_HANDED_OVER',"
            + " '이미 인계됨', now() - interval '1 hour', now() - interval '1 hour')",
        id,
        UUID.randomUUID().toString(),
        "ORD-MA-" + id.substring(7, 15));
    return id;
  }

  /** 주문을 찾기 전에 멈춘 오배송 재발송 사가(2026-11-16). */
  String escalatedReshipment() {
    String id = "SAGA-R-MA-" + UUID.randomUUID();
    jdbc.update(
        "INSERT INTO reshipment_saga (saga_id, case_id, delivery_id, state, current_step,"
            + " last_error_step, last_error_code, last_error_message, started_at, updated_at)"
            + " VALUES (?, ?, 'DLV-MA-1', 'ESCALATED', 'RESOLVE_ORDER', 'RESOLVE_ORDER',"
            + " 'DELIVERY_NOT_FOUND', '배송을 찾지 못했습니다', now() - interval '2 hour', now() -"
            + " interval '2 hour')",
        id,
        "CASE-MA-" + id.substring(10, 18));
    return id;
  }

  @Test
  void escalatedReshipmentIsListedWithCaseAndCanBeResolved() {
    String sagaId = escalatedReshipment();
    JsonNode item =
        find(call("GET", "/internal/stuck-sagas?size=200", null, "x").body().path("items"), sagaId);
    assertThat(item).isNotNull();
    assertThat(item.path("sagaType").asString()).isEqualTo("RESHIPMENT");
    assertThat(item.path("orderId").asString()).isEmpty(); // 주문을 찾기 전
    assertThat(item.path("lastErrorCode").asString()).isEqualTo("DELIVERY_NOT_FOUND");
    assertThat(item.path("lastErrorMessage").asString())
        .startsWith("오배송 사건 CASE-MA-")
        .contains("배송 DLV-MA-1")
        .endsWith("배송을 찾지 못했습니다");
    assertThat(item.path("allowedActions").toString()).isEqualTo("[\"MARK_RESOLVED\"]");
    Res r =
        call(
            "POST",
            "/internal/sagas/" + sagaId + "/manual-actions",
            Map.of("action", "MARK_RESOLVED", "reason", "상담원이 새 주문으로 다시 보냄", "operatorId", "op-cs"),
            "k-rs");
    assertThat(r.status()).as(r.body().toString()).isEqualTo(202);
    assertThat(
            find(
                call("GET", "/internal/stuck-sagas?size=200", null, "x").body().path("items"),
                sagaId))
        .isNull();
  }

  JsonNode find(JsonNode items, String sagaId) {
    for (JsonNode i : items) {
      if (sagaId.equals(i.path("sagaId").asString())) {
        return i;
      }
    }
    return null;
  }

  @Test
  void stuckSagasCanBeMarkedResolvedButNotRequeried() {
    String sagaId = escalatedShortage();
    Res list = call("GET", "/internal/stuck-sagas?size=200", null, "x");
    assertThat(list.status()).isEqualTo(200);
    JsonNode item = find(list.body().path("items"), sagaId);
    assertThat(item).isNotNull();
    assertThat(item.path("sagaType").asString()).isEqualTo("SHORTAGE");
    assertThat(item.path("state").asString()).isEqualTo("ESCALATED");
    assertThat(item.path("lastErrorCode").asString()).isEqualTo("WORK_ALREADY_HANDED_OVER");
    assertThat(item.path("allowedActions").toString()).isEqualTo("[\"MARK_RESOLVED\"]");

    String path = "/internal/sagas/" + sagaId + "/manual-actions";
    assertThat(
            call(
                    "POST",
                    path,
                    Map.of("action", "REQUERY_RESULT", "reason", "x", "operatorId", "op-1"),
                    "k1")
                .body()
                .path("code")
                .asString())
        .isEqualTo("NOT_REQUERYABLE");
    assertThat(
            call(
                    "POST",
                    path,
                    Map.of("action", "EXECUTE_COMPENSATION", "reason", "x", "operatorId", "op-1"),
                    "k2")
                .body()
                .path("code")
                .asString())
        .isEqualTo("UNSUPPORTED_ACTION");

    Map<String, Object> resolve =
        Map.of(
            "action",
            "MARK_RESOLVED",
            "reason",
            "MFC 실물 확인 뒤 고객 부분 환불 처리함",
            "operatorId",
            "op-admin");
    Res r = call("POST", path, resolve, "k-resolve");
    assertThat(r.status()).as(r.body().toString()).isEqualTo(202);
    assertThat(r.body().path("status").asString()).isEqualTo("APPLIED");
    assertThat(r.body().path("requestId").asString()).isEqualTo("k-resolve");
    assertThat(call("POST", path, resolve, "k-resolve").status()).as("같은 키 — 같은 결과").isEqualTo(202);
    assertThat(
            find(
                call("GET", "/internal/stuck-sagas?size=200", null, "x").body().path("items"),
                sagaId))
        .as("처리 완료 — 목록에서 빠짐")
        .isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM shortage_saga WHERE saga_id = ?", String.class, sagaId))
        .as("사가 상태는 그대로")
        .isEqualTo("ESCALATED");
    assertThat(call("POST", path, resolve, "k-again").body().path("code").asString())
        .as("이미 처리 완료")
        .isEqualTo("NOT_RESOLVABLE");

    Res got = call("GET", path + "/k-resolve", null, "x");
    assertThat(got.status()).isEqualTo(200);
    assertThat(got.body().path("action").asString()).isEqualTo("MARK_RESOLVED");
    assertThat(got.body().path("operatorId").asString()).isEqualTo("op-admin");
    assertThat(got.body().path("state").asString()).isEqualTo("SUCCEEDED");
  }
}
