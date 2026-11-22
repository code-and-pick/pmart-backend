package com.pmart.backoffice.bff;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
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
 * 처리 대기 MFC 묶음(0.4.0 — 2026-11-16 사용자 "A안", PC 3 요청 F-4): 설정을 켜면 실패로 멈춘 중지 요청·재고가 거절한 인계를 운영자 토큰으로
 * 읽어 RETRY 항목으로, 둘 중 하나라도 실패하면 MFC 항목 없이 unavailableSources. 재시도 명령은 MFC 내부 경로로 보내고 응답을
 * CommandResponse 로 옮긴다(사유 필수, 관리자만).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
      "pmart.bff.mfc-pending-lists=true"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock({
  @ConfigureWireMock(name = "orch", baseUrlProperties = "pmart.bff.orchestration-base-url"),
  @ConfigureWireMock(name = "order", baseUrlProperties = "pmart.bff.order-base-url"),
  @ConfigureWireMock(name = "mfc", baseUrlProperties = "pmart.bff.mfc-fulfillment-base-url"),
  @ConfigureWireMock(name = "hr", baseUrlProperties = "pmart.bff.hr-base-url")
})
class PendingWorkMfcIT {

  static final String ADMIN = TestJwt.operator("op-admin", List.of("OPERATOR_ADMIN"), List.of());
  static final String CS = TestJwt.operator("op-cs", List.of("CS_AGENT"), List.of());

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;

  @InjectWireMock("orch")
  WireMockServer orch;

  @InjectWireMock("order")
  WireMockServer order;

  @InjectWireMock("mfc")
  WireMockServer mfc;

  @InjectWireMock("hr")
  WireMockServer hr;

  RestClient http;

  @BeforeEach
  void setUp() {
    for (WireMockServer w : List.of(orch, order, mfc, hr)) {
      w.resetAll();
    }
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1/backoffice")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    orch.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/stuck-sagas"))
            .willReturn(json(200, "{\"items\":[]}")));
    order.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/fulfillment-issues"))
            .willReturn(json(200, "{\"items\":[]}")));
    mfc.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/stop-requests"))
            .willReturn(
                json(
                    200,
                    """
                    {"items":[{"stopRequestId":"STP-1","fulfillmentId":"FUL-1","orderId":"ORD-1","mfcId":"MFC-1",
                               "state":"PENDING","lastError":"RETURN_MISMATCH: 위치 잔량 부족",
                               "failedAt":"2026-11-16T02:00:00Z","requestedAt":"2026-11-16T01:50:00Z",
                               "lastRetry":{"operatorId":"op-admin","reason":"재고 보정 뒤 다시 시도","retriedAt":"2026-11-16T02:10:00Z"}}]}""")));
    mfc.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/package-handovers"))
            .willReturn(
                json(
                    200,
                    """
                    {"items":[{"handoverId":"HO-1","packageId":"PKG-1","orderId":"ORD-2","mfcId":"MFC-1",
                               "status":"REJECTED","rejectReason":"LOCATION_SHORTAGE: 포장 위치 수량 불일치",
                               "rejectedAt":"2026-11-16T01:00:00Z"}]}""")));
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

  static ResponseDefinitionBuilder json(int s, String b) {
    return aResponse().withStatus(s).withHeader("Content-Type", "application/json").withBody(b);
  }

  @Test
  void stopFailuresAndRejectedHandoversAreListedOldestFirst() {
    hr.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/hr/employee-labels"))
            .willReturn(
                WireMock.okJson(
                    "{\"items\":[{\"operatorId\":\"op-admin\",\"name\":\"관리자\",\"employeeNo\":\"P2026-00009\"}]}")));
    Res r = call("GET", "/views/pending-work", ADMIN, null);
    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    JsonNode items = r.body().path("items");
    assertThat(items.findValuesAsString("kind"))
        .containsExactly("MFC_HANDOVER_REJECTED", "MFC_STOP_FAILED");
    JsonNode h = items.get(0);
    assertThat(h.path("refId").asString()).isEqualTo("HO-1");
    assertThat(h.path("reasonCode").asString()).isEqualTo("LOCATION_SHORTAGE");
    assertThat(h.path("allowedActions").toString()).isEqualTo("[\"RETRY\"]");
    JsonNode s = items.get(1);
    assertThat(s.path("refId").asString()).isEqualTo("STP-1");
    assertThat(s.path("state").asString()).isEqualTo("PENDING");
    assertThat(s.path("since").asString()).isEqualTo("2026-11-16T02:00:00Z");
    assertThat(s.path("source").path("system").asString()).isEqualTo("mfc-fulfillment-service");
    assertThat(s.path("lastRetry").path("operatorId").asString())
        .as("최근 운영자 재시도(0.8.0)")
        .isEqualTo("op-admin");
    assertThat(s.path("lastRetry").path("retriedAt").asString()).isEqualTo("2026-11-16T02:10:00Z");
    assertThat(s.path("lastRetry").path("operator").path("name").asString())
        .as("재시도한 직원 이름 · 사번(HR-4)")
        .isEqualTo("관리자");
    assertThat(s.path("lastRetry").path("operator").path("employeeNo").asString())
        .isEqualTo("P2026-00009");
    assertThat(h.path("lastRetry").isNull()).isTrue();
    assertThat(r.body().path("unavailableSources")).isEmpty();
    mfc.verify(
        getRequestedFor(urlPathEqualTo("/api/v1/stop-requests"))
            .withHeader("Authorization", equalTo("Bearer " + ADMIN)));
  }

  /** 0.24.0 BF-4: 센터를 고르면 이행 확인·MFC 목록은 mfcId 로, 멈춘 사가는 못 거름 → centerUnfilteredSources. */
  @Test
  void centerIsPassedToSourcesThatCanFilter() {
    Res all = call("GET", "/views/pending-work", ADMIN, null);
    assertThat(all.body().path("centerUnfilteredSources")).as("센터 안 고르면 빈 배열").isEmpty();
    order.verify(
        getRequestedFor(urlPathEqualTo("/api/v1/fulfillment-issues"))
            .withQueryParam("mfcId", com.github.tomakehurst.wiremock.client.WireMock.absent()));

    Res r = call("GET", "/views/pending-work?centerId=MFC-MOKPO-01", ADMIN, null);
    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    assertThat(r.body().path("centerUnfilteredSources").toString())
        .isEqualTo("[\"order-orchestration-service\"]");
    for (String path : List.of("/api/v1/stop-requests", "/api/v1/package-handovers")) {
      mfc.verify(
          getRequestedFor(urlPathEqualTo(path)).withQueryParam("mfcId", equalTo("MFC-MOKPO-01")));
    }
    order.verify(
        getRequestedFor(urlPathEqualTo("/api/v1/fulfillment-issues"))
            .withQueryParam("mfcId", equalTo("MFC-MOKPO-01")));
    orch.verify(
        0,
        getRequestedFor(urlPathEqualTo("/api/v1/internal/stuck-sagas"))
            .withQueryParam(
                "mfcId", com.github.tomakehurst.wiremock.client.WireMock.matching(".*")));
  }

  @Test
  void halfReadMfcIsDroppedAndReportedUnavailable() {
    mfc.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/package-handovers")).willReturn(json(503, "{}")));
    Res r = call("GET", "/views/pending-work", ADMIN, null);
    assertThat(r.status()).isEqualTo(200);
    assertThat(r.body().path("items")).as("중지 목록만 읽힌 채로 내보내지 않음").isEmpty();
    assertThat(r.body().path("unavailableSources").toString()).contains("mfc-fulfillment-service");
  }

  /** 0.9.2: MFC 재시도 결과 모름(5xx·시간 초과)은 오류가 아니라 202 UNKNOWN_RESULT. */
  @Test
  void mfcRetryUnknownResultIsAcceptedNotError() {
    mfc.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/stop-requests/STP-9/retries"))
            .willReturn(json(500, "{}")));
    Res r =
        call(
            "POST",
            "/commands/mfc-fulfillment-service/stop-retry",
            ADMIN,
            Map.of("targetRef", "STP-9", "reason", "다시 시도", "payload", Map.of()));
    assertThat(r.status()).as(r.body().toString()).isEqualTo(202);
    assertThat(r.body().path("status").asString()).isEqualTo("UNKNOWN_RESULT");
    assertThat(r.body().path("nextAction").asString()).isEqualTo("QUERY_RESULT");
  }

  /**
   * 0.12.0 명령 결과 조회: 사가 수동 조치는 orchestration 결과로, 중지 재시도는 중지 요청 판정으로 status 를 옮긴다. 인계 재시도는 422, 원천
   * 404 는 404, 원천 5xx 는 UNKNOWN_RESULT, 관리자 아니면 403, targetRef 없으면 400.
   */
  @Test
  void commandResultReadsSourceAgain() {
    orch.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/sagas/SAGA-1/manual-actions/k-9"))
            .willReturn(
                json(
                    200,
                    """
                    {"requestId":"k-9","sagaId":"SAGA-1","action":"REQUERY_RESULT","operatorId":"op-admin",
                     "state":"SUCCEEDED","resultSummary":"결제 승인 확인","requestedAt":"2026-11-17T01:00:00Z",
                     "finishedAt":"2026-11-17T01:00:02Z"}""")));
    Res saga =
        call(
            "GET",
            "/commands/order-orchestration-service/saga-requery/k-9?targetRef=SAGA-1",
            ADMIN,
            null);
    assertThat(saga.status()).as(saga.body().toString()).isEqualTo(200);
    assertThat(saga.body().path("status").asString()).isEqualTo("APPLIED");
    assertThat(saga.body().path("requestId").asString()).isEqualTo("k-9");
    assertThat(saga.body().path("resultId").asString()).isEqualTo("SAGA-1");
    assertThat(saga.body().path("detail").path("resultSummary").asString()).isEqualTo("결제 승인 확인");
    assertThat(saga.body().path("source").path("system").asString())
        .isEqualTo("order-orchestration-service");
    assertThat(saga.body().path("source").path("confidence").asString()).isEqualTo("DIRECT");

    mfc.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/stop-requests/STP-1"))
            .willReturn(
                json(
                    200,
                    "{\"stopRequestId\":\"STP-1\",\"state\":\"PENDING\",\"lastError\":\"RETURN_MISMATCH\"}")));
    Res stop =
        call(
            "GET", "/commands/mfc-fulfillment-service/stop-retry/k-1?targetRef=STP-1", ADMIN, null);
    assertThat(stop.status()).as(stop.body().toString()).isEqualTo(200);
    assertThat(stop.body().path("status").asString()).isEqualTo("REJECTED");
    assertThat(stop.body().path("nextAction").asString()).isEqualTo("FIX_AND_RETRY");
    assertThat(stop.body().path("detail").path("lastError").asString())
        .isEqualTo("RETURN_MISMATCH");

    mfc.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/stop-requests/STP-5"))
            .willReturn(json(500, "{}")));
    Res unknown =
        call(
            "GET", "/commands/mfc-fulfillment-service/stop-retry/k-5?targetRef=STP-5", ADMIN, null);
    assertThat(unknown.status()).as(unknown.body().toString()).isEqualTo(200);
    assertThat(unknown.body().path("status").asString()).isEqualTo("UNKNOWN_RESULT");
    assertThat(unknown.body().path("source").path("confidence").asString()).isEqualTo("UNKNOWN");

    orch.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/sagas/SAGA-2/manual-actions/k-404"))
            .willReturn(json(404, "{\"code\":\"NOT_FOUND\"}")));
    assertThat(
            call(
                    "GET",
                    "/commands/order-orchestration-service/saga-resolve/k-404?targetRef=SAGA-2",
                    ADMIN,
                    null)
                .status())
        .isEqualTo(404);
    assertThat(
            call(
                    "GET",
                    "/commands/mfc-fulfillment-service/handover-retry/k-1?targetRef=HO-1",
                    ADMIN,
                    null)
                .status())
        .isEqualTo(422);
    assertThat(
            call("GET", "/commands/approval-service/anything/k-1?targetRef=X", ADMIN, null)
                .status())
        .isEqualTo(422);
    assertThat(
            call(
                    "GET",
                    "/commands/mfc-fulfillment-service/stop-retry/k-1?targetRef=STP-1",
                    CS,
                    null)
                .status())
        .isEqualTo(403);
    assertThat(
            call("GET", "/commands/mfc-fulfillment-service/stop-retry/k-1", ADMIN, null).status())
        .isEqualTo(400);
  }

  /** 0.9.1: MFC 목록을 커서로 다음 쪽까지 읽고, 5쪽을 넘으면 truncated. */
  @Test
  void mfcListsFollowCursorAndFlagTruncation() {
    mfc.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/stop-requests"))
            .willReturn(
                json(
                    200,
                    """
                    {"items":[{"stopRequestId":"STP-1","orderId":"ORD-1","mfcId":"MFC-1","state":"PENDING",
                               "lastError":"E: x","requestedAt":"2026-11-16T01:00:00Z"}],
                     "page":{"nextCursor":"STP-1"}}""")));
    mfc.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/stop-requests"))
            .withQueryParam("cursor", equalTo("STP-1"))
            .willReturn(
                json(
                    200,
                    """
                    {"items":[{"stopRequestId":"STP-2","orderId":"ORD-2","mfcId":"MFC-1","state":"PENDING",
                               "lastError":"E: y","requestedAt":"2026-11-16T01:30:00Z"}],
                     "page":{"nextCursor":null}}""")));
    Res r = call("GET", "/views/pending-work", ADMIN, null);
    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    assertThat(r.body().path("items").findValuesAsString("refId"))
        .as("두 쪽 모두")
        .contains("STP-1", "STP-2", "HO-1");
    assertThat(r.body().path("truncated").asBoolean()).isFalse();

    // 끝없이 다음 쪽이 있으면 5쪽에서 멈추고 truncated
    mfc.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/stop-requests"))
            .withQueryParam("cursor", equalTo("STP-1"))
            .willReturn(
                json(
                    200,
                    """
                    {"items":[],"page":{"nextCursor":"STP-1"}}""")));
    Res t = call("GET", "/views/pending-work", ADMIN, null);
    assertThat(t.body().path("truncated").asBoolean()).isTrue();
    mfc.verify(
        5 + 2,
        getRequestedFor(urlPathEqualTo("/api/v1/stop-requests"))
            .withQueryParam("size", equalTo("200")));
  }

  @Test
  void stopRetryIsForwardedAndMappedToCommandResponse() {
    mfc.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/stop-requests/STP-1/retries"))
            .willReturn(
                json(
                    200,
                    "{\"stopRequestId\":\"STP-1\",\"state\":\"PENDING\",\"lastError\":\"RETURN_MISMATCH: 다시 실패\"}")));
    Map<String, Object> body =
        Map.of("targetRef", "STP-1", "reason", "재고 보정 뒤 다시 시도", "payload", Map.of());
    assertThat(call("POST", "/commands/mfc-fulfillment-service/stop-retry", CS, body).status())
        .isEqualTo(403);
    Res r = call("POST", "/commands/mfc-fulfillment-service/stop-retry", ADMIN, body);
    assertThat(r.status()).as(r.body().toString()).isEqualTo(202);
    assertThat(r.body().path("status").asString()).as("다시 실패").isEqualTo("REJECTED");
    assertThat(r.body().path("nextAction").asString()).isEqualTo("FIX_AND_RETRY");
    assertThat(r.body().path("resultId").asString()).isEqualTo("STP-1");
    mfc.verify(
        postRequestedFor(urlPathEqualTo("/api/v1/internal/stop-requests/STP-1/retries"))
            .withHeader("Idempotency-Key", equalTo("k-1"))
            .withHeader("X-Source-System", equalTo("backoffice-bff"))
            .withRequestBody(matchingJsonPath("$.operatorId", equalTo("op-admin")))
            .withRequestBody(matchingJsonPath("$.reason", equalTo("재고 보정 뒤 다시 시도"))));

    mfc.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/stop-requests/STP-2/retries"))
            .willReturn(json(200, "{\"stopRequestId\":\"STP-2\",\"state\":\"RESOLVED\"}")));
    assertThat(
            call(
                    "POST",
                    "/commands/mfc-fulfillment-service/stop-retry",
                    ADMIN,
                    Map.of("targetRef", "STP-2", "reason", "보정함", "payload", Map.of()))
                .body()
                .path("status")
                .asString())
        .isEqualTo("APPLIED");

    assertThat(
            call(
                    "POST",
                    "/commands/mfc-fulfillment-service/stop-retry",
                    ADMIN,
                    Map.of("targetRef", "STP-1", "payload", Map.of()))
                .status())
        .as("사유 없음")
        .isEqualTo(400);
  }

  @Test
  void handoverRetryPassesStatusAndTargetErrors() {
    mfc.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/package-handovers/HO-1/retries"))
            .willReturn(
                json(
                    200,
                    "{\"status\":\"APPLIED\",\"handoverId\":\"HO-1\",\"inventoryCommitId\":\"IC-1\"}")));
    Res r =
        call(
            "POST",
            "/commands/mfc-fulfillment-service/handover-retry",
            ADMIN,
            Map.of("targetRef", "HO-1", "reason", "실물 확인·재고 정정 뒤", "payload", Map.of()));
    assertThat(r.status()).as(r.body().toString()).isEqualTo(202);
    assertThat(r.body().path("status").asString()).isEqualTo("APPLIED");
    assertThat(r.body().path("resultId").asString()).isEqualTo("HO-1");

    mfc.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/package-handovers/HO-404/retries"))
            .willReturn(json(404, "{\"code\":\"NOT_FOUND\",\"detail\":\"인계가 없습니다.\"}")));
    Res nf =
        call(
            "POST",
            "/commands/mfc-fulfillment-service/handover-retry",
            ADMIN,
            Map.of("targetRef", "HO-404", "reason", "x", "payload", Map.of()));
    assertThat(nf.status()).isEqualTo(404);
    assertThat(nf.body().path("code").asString()).isEqualTo("NOT_FOUND");

    assertThat(
            call(
                    "POST",
                    "/commands/mfc-fulfillment-service/repack",
                    ADMIN,
                    Map.of("targetRef", "x", "reason", "x", "payload", Map.of()))
                .body()
                .path("code")
                .asString())
        .isEqualTo("UNSUPPORTED_ACTION");
  }
}
