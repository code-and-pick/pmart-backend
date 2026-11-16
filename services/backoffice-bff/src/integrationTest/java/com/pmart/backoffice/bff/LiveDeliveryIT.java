package com.pmart.backoffice.bff;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 실시간 배송 현황(0.3.0 — 2026-11-16, 배송 원문 §10.1): 끝나지 않은 배송을 상태마다 읽어 오래된 것부터 합침(넘으면 truncated), 배송마다
 * 배차·열린 사건을 원천마다 따로, 배차 원천 장애는 그 칸만 UNKNOWN, 약속 시각은 arrivalWindow 끝(Asia/Seoul), state 를 주면 원천 커서,
 * 권한·입력 검사.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock({
  @ConfigureWireMock(name = "execution", baseUrlProperties = "pmart.bff.execution-base-url"),
  @ConfigureWireMock(name = "dispatch", baseUrlProperties = "pmart.bff.dispatch-base-url"),
  @ConfigureWireMock(name = "case", baseUrlProperties = "pmart.bff.case-base-url")
})
class LiveDeliveryIT {

  static final String CS = TestJwt.operator("op-cs", List.of("CS_AGENT"), List.of());
  static final String MD = TestJwt.operator("op-md", List.of("MD"), List.of());

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;

  @InjectWireMock("execution")
  WireMockServer execution;

  @InjectWireMock("dispatch")
  WireMockServer dispatch;

  @InjectWireMock("case")
  WireMockServer cases;

  RestClient http;

  @BeforeEach
  void setUp() {
    for (WireMockServer w : List.of(execution, dispatch, cases)) {
      w.resetAll();
    }
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1/backoffice")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    stubSources();
  }

  record Res(int status, JsonNode body) {}

  Res live(String token, String query) {
    var e =
        http.get()
            .uri("/views/live-delivery" + (query.isEmpty() ? "" : "?" + query))
            .headers(h -> h.setBearerAuth(token))
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  static ResponseDefinitionBuilder json(int s, String b) {
    return aResponse().withStatus(s).withHeader("Content-Type", "application/json").withBody(b);
  }

  void stubSources() {
    // 기본: 그 상태엔 배송 없음
    execution.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/deliveries"))
            .willReturn(json(200, "{\"items\":[],\"page\":{\"nextCursor\":null}}")));
    execution.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/deliveries"))
            .withQueryParam("state", equalTo("UNASSIGNED"))
            .willReturn(
                json(
                    200,
                    """
                    {"items":[{"deliveryId":"DLV-B","orderId":"ORD-B","mfcId":"MFC-1","type":"FORWARD",
                               "state":"UNASSIGNED","version":1,"createdAt":"2026-11-16T01:10:00Z"}],
                     "page":{"nextCursor":null}}""")));
    execution.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/deliveries"))
            .withQueryParam("state", equalTo("ASSIGNED"))
            .willReturn(
                json(
                    200,
                    """
                    {"items":[{"deliveryId":"DLV-A","orderId":"ORD-A","mfcId":"MFC-1","type":"FORWARD","riderId":"RD-1",
                               "state":"ASSIGNED","version":4,"createdAt":"2026-11-16T01:00:00Z","assignedAt":"2026-11-16T01:02:00Z"}],
                     "page":{"nextCursor":"c2"}}""")));
    dispatch.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/deliveries/DLV-A/dispatch"))
            .willReturn(
                json(
                    200,
                    """
                    {"deliveryId":"DLV-A","state":"ASSIGNED","version":3,
                     "arrivalWindow":{"date":"2026-11-16","start":"10:00","end":"10:30"},
                     "assignment":{"riderId":"RD-1"}}""")));
    dispatch.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/deliveries/DLV-B/dispatch"))
            .willReturn(json(503, "{\"code\":\"DEPENDENCY_UNAVAILABLE\"}")));
    cases.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/cases"))
            .willReturn(
                json(
                    200,
                    """
                    {"items":[{"caseId":"CASE-9","kind":"DISPATCH_FAILED","priority":"P2","overdue":true,
                               "subjectType":"DELIVERY","subjectRef":"DLV-B","refs":[{"refType":"deliveryId","refId":"DLV-B"}]}]}""")));
  }

  @Test
  void activeDeliveriesOldestFirstWithDispatchAndCasesPerSource() {
    Res r = live(CS, "mfcId=MFC-1&size=20");
    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    JsonNode items = r.body().path("items");
    assertThat(items.findValuesAsString("deliveryId")).containsExactly("DLV-A", "DLV-B");
    assertThat(r.body().path("truncated").asBoolean()).as("ASSIGNED 에 다음 쪽이 있음").isTrue();
    assertThat(r.body().path("page").path("nextCursor").isNull()).isTrue();

    JsonNode a = items.get(0);
    assertThat(a.path("delivery").path("state").asString()).isEqualTo("ASSIGNED");
    assertThat(a.path("delivery").path("source").path("system").asString())
        .isEqualTo("delivery-execution-service");
    assertThat(a.path("dispatch").path("state").asString()).isEqualTo("ASSIGNED");
    assertThat(a.path("riderId").asString()).isEqualTo("RD-1");
    assertThat(a.path("promisedAt").asString())
        .as("10:30 Asia/Seoul")
        .isEqualTo("2026-11-16T01:30:00Z");
    assertThat(a.path("openCases")).isEmpty();

    JsonNode b = items.get(1);
    assertThat(b.path("dispatch").path("state").isNull()).as("배차 원천 장애 — 상태 없음").isTrue();
    assertThat(b.path("dispatch").path("source").path("confidence").asString())
        .isEqualTo("UNKNOWN");
    assertThat(b.path("promisedAt").isNull()).isTrue();
    assertThat(b.path("openCases").findValuesAsString("caseId")).containsExactly("CASE-9");
    assertThat(r.body().path("unavailableSources").toString())
        .contains("delivery-dispatch-service")
        .doesNotContain("delivery-execution-service");

    // 끝나지 않은 상태마다 한 번씩, 센터·오래된 순·직원 토큰 그대로
    execution.verify(
        9,
        getRequestedFor(urlPathEqualTo("/api/v1/deliveries"))
            .withQueryParam("mfcId", equalTo("MFC-1"))
            .withQueryParam("sort", equalTo("OLDEST"))
            .withHeader("Authorization", equalTo("Bearer " + CS)));
    execution.verify(
        0,
        getRequestedFor(urlPathEqualTo("/api/v1/deliveries"))
            .withQueryParam("state", equalTo("COMPLETED")));
  }

  @Test
  void oneStateUsesSourceCursorAndAllCentersWhenMfcEmpty() {
    Res r = live(CS, "state=ASSIGNED&cursor=c1");
    assertThat(r.status()).isEqualTo(200);
    assertThat(r.body().path("items").findValuesAsString("deliveryId")).containsExactly("DLV-A");
    assertThat(r.body().path("page").path("nextCursor").asString()).isEqualTo("c2");
    assertThat(r.body().path("truncated").asBoolean()).isFalse();
    execution.verify(
        1,
        getRequestedFor(urlPathEqualTo("/api/v1/deliveries"))
            .withQueryParam("cursor", equalTo("c1"))
            .withQueryParam("mfcId", absent()));
  }

  @Test
  void rolesAndInputsAreChecked() {
    assertThat(live(MD, "").status()).isEqualTo(403);
    assertThat(live(CS, "state=NOPE").status()).isEqualTo(400);
    assertThat(live(CS, "cursor=c1").status()).as("상태 없이 커서").isEqualTo(400);
  }
}
