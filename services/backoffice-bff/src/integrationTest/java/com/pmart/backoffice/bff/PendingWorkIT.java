package com.pmart.backoffice.bff;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 처리 대기(BO-12, 0.2.0 — 2026-11-16): OPERATOR_ADMIN 만, 멈춘 사가 + 이행 확인 필요를 오래된 것부터(order 는 운영자 토큰 전달),
 * 원천 장애면 unavailableSources, 명령 saga-resolve → orchestration manual-actions(운영자 ID·사유), 대상 거절 코드
 * 그대로, 모르는 명령 422.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
      "pmart.bff.mfc-pending-lists=false" // 0.8.0 부터 기본 켬 — 이 시험은 사가·이행 확인만(꺼짐 스위치 확인)
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock({
  @ConfigureWireMock(name = "orch", baseUrlProperties = "pmart.bff.orchestration-base-url"),
  @ConfigureWireMock(name = "order", baseUrlProperties = "pmart.bff.order-base-url")
})
class PendingWorkIT {

  static final String ADMIN = TestJwt.operator("op-admin", List.of("OPERATOR_ADMIN"), List.of());
  static final String CS = TestJwt.operator("op-cs", List.of("CS_AGENT"), List.of());

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;

  @InjectWireMock("orch")
  WireMockServer orch;

  @InjectWireMock("order")
  WireMockServer order;

  RestClient http;

  @BeforeEach
  void setUp() {
    orch.resetAll();
    order.resetAll();
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1/backoffice")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
  }

  record Res(int status, JsonNode body) {}

  Res call(String method, String path, String token, @Nullable Object body) {
    var spec =
        http.method(HttpMethod.valueOf(method))
            .uri(path)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", "k-1");
                  h.set("X-Source-System", "backoffice-web");
                });
    var e =
        body == null
            ? spec.retrieve().toEntity(String.class)
            : spec.contentType(MediaType.APPLICATION_JSON)
                .body(mapper.writeValueAsString(body))
                .retrieve()
                .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(int s, String b) {
    return aResponse().withStatus(s).withHeader("Content-Type", "application/json").withBody(b);
  }

  void stubSources() {
    orch.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/stuck-sagas"))
            .willReturn(
                json(
                    200,
                    """
                    {"items":[{"sagaId":"SSG-1","sagaType":"SHORTAGE","orderId":"ORD-2","state":"ESCALATED",
                     "currentStep":"RESOLVE_WORK","lastErrorStep":"RESOLVE_WORK","lastErrorCode":"WORK_ALREADY_HANDED_OVER",
                     "lastErrorMessage":"이미 인계됨","updatedAt":"2026-11-16T01:00:00Z","allowedActions":["MARK_RESOLVED"]}],
                     "page":{"size":1}}""")));
    order.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/fulfillment-issues"))
            .willReturn(
                json(
                    200,
                    """
                    {"items":[{"issueId":"FI-1","orderId":"ORD-1","mfcId":"MFC-1","sku":"E-1","blockedQty":2,
                     "reason":"QUALITY_HOLD","state":"OPEN","blockedAt":"2026-11-16T00:00:00Z"}]}""")));
  }

  @Test
  void adminSeesStuckSagasAndFulfillmentIssuesOldestFirst() {
    stubSources();
    assertThat(call("GET", "/views/pending-work", CS, null).status()).isEqualTo(403);

    Res r = call("GET", "/views/pending-work", ADMIN, null);
    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    JsonNode items = r.body().path("items");
    assertThat(items).hasSize(2);
    assertThat(items.get(0).path("kind").asString()).isEqualTo("FULFILLMENT_ISSUE");
    assertThat(items.get(0).path("allowedActions").toString()).isEqualTo("[\"ACKNOWLEDGE\"]");
    assertThat(items.get(1).path("kind").asString()).isEqualTo("STUCK_SAGA");
    assertThat(items.get(1).path("refId").asString()).isEqualTo("SSG-1");
    assertThat(items.get(1).path("reasonCode").asString()).isEqualTo("WORK_ALREADY_HANDED_OVER");
    assertThat(items.get(1).path("allowedActions").toString()).isEqualTo("[\"MARK_RESOLVED\"]");
    assertThat(r.body().path("unavailableSources")).isEmpty();
    assertThat(r.body().path("sources").toString())
        .as("MFC 목록은 설정을 켜기 전엔 부르지 않음(F-4 전)")
        .doesNotContain("mfc-fulfillment-service");
    order.verify(
        getRequestedFor(urlPathEqualTo("/api/v1/fulfillment-issues"))
            .withHeader("Authorization", equalTo("Bearer " + ADMIN))
            .withQueryParam("state", equalTo("OPEN")));
  }

  @Test
  void sourceDownIsReportedNotGuessed() {
    stubSources();
    orch.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/stuck-sagas")).willReturn(json(503, "{}")));
    Res r = call("GET", "/views/pending-work", ADMIN, null);
    assertThat(r.status()).isEqualTo(200);
    assertThat(r.body().path("items")).hasSize(1);
    assertThat(r.body().path("unavailableSources").toString())
        .contains("order-orchestration-service");
  }

  @Test
  void resolveCommandIsForwardedWithOperatorAndReason() {
    orch.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/sagas/SSG-1/manual-actions"))
            .willReturn(
                json(
                    202, "{\"status\":\"APPLIED\",\"requestId\":\"k-1\",\"resultId\":\"SSG-1\"}")));
    Map<String, Object> body =
        Map.of("targetRef", "SSG-1", "reason", "MFC 실물 확인 뒤 부분 환불 처리함", "payload", Map.of());
    assertThat(
            call("POST", "/commands/order-orchestration-service/saga-resolve", CS, body).status())
        .isEqualTo(403);
    Res r = call("POST", "/commands/order-orchestration-service/saga-resolve", ADMIN, body);
    assertThat(r.status()).as(r.body().toString()).isEqualTo(202);
    assertThat(r.body().path("status").asString()).isEqualTo("APPLIED");
    orch.verify(
        postRequestedFor(urlPathEqualTo("/api/v1/internal/sagas/SSG-1/manual-actions"))
            .withHeader("Idempotency-Key", equalTo("k-1"))
            .withHeader("X-Source-System", equalTo("backoffice-bff"))
            .withRequestBody(
                equalToJson(
                    "{\"action\":\"MARK_RESOLVED\",\"reason\":\"MFC 실물 확인 뒤 부분 환불 처리함\",\"operatorId\":\"op-admin\"}")));

    orch.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/sagas/SSG-2/manual-actions"))
            .willReturn(
                json(422, "{\"code\":\"NOT_REQUERYABLE\",\"detail\":\"결제 확인 대기 상태가 아닙니다.\"}")));
    Res rej =
        call(
            "POST",
            "/commands/order-orchestration-service/saga-requery",
            ADMIN,
            Map.of("targetRef", "SSG-2", "payload", Map.of())); // 다시 확인은 사유 선택
    assertThat(rej.status()).isEqualTo(422);
    assertThat(rej.body().path("code").asString()).isEqualTo("NOT_REQUERYABLE");

    assertThat(
            call(
                    "POST",
                    "/commands/approval-service/decide",
                    ADMIN,
                    Map.of("targetRef", "x", "reason", "x", "payload", Map.of()))
                .body()
                .path("code")
                .asString())
        .isEqualTo("UNSUPPORTED_ACTION");
    assertThat(
            call(
                    "POST",
                    "/commands/order-orchestration-service/saga-resolve",
                    ADMIN,
                    Map.of("targetRef", "SSG-1", "payload", Map.of()))
                .status())
        .as("사유 없음")
        .isEqualTo(400);
  }
}
