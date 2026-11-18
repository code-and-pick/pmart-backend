package com.pmart.backoffice.bff;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
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
  @ConfigureWireMock(name = "case", baseUrlProperties = "pmart.bff.case-base-url"),
  @ConfigureWireMock(name = "tracking", baseUrlProperties = "pmart.bff.tracking-base-url"),
  @ConfigureWireMock(name = "rider", baseUrlProperties = "pmart.bff.rider-base-url")
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

  @InjectWireMock("tracking")
  WireMockServer tracking;

  @InjectWireMock("rider")
  WireMockServer rider;

  RestClient http;

  @BeforeEach
  void setUp() {
    for (WireMockServer w : List.of(execution, dispatch, cases, tracking, rider)) {
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
                    {"items":[{"deliveryId":"DLV-A","deliveryNumber":"74939255-1","orderNumber":"74939255","orderId":"ORD-A","mfcId":"MFC-1","type":"FORWARD","riderId":"RD-1",
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
    // 도착 예정(0.7.0): A 는 산출됨, B 는 tracking 배포 전 배송(404)
    tracking.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/deliveries/DLV-A/eta"))
            .willReturn(
                json(
                    200,
                    """
                    {"deliveryId":"DLV-A","stage":"TO_STORE",
                     "dropEta":{"status":"AVAILABLE","etaAt":"2026-11-16T01:25:00Z","delayedBySeconds":-300}}""")));
    tracking.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/deliveries/DLV-B/eta"))
            .willReturn(json(404, "{\"code\":\"NOT_FOUND\"}")));
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
    assertThat(a.path("latestEta").asString())
        .as("tracking 도착 예정(0.7.0)")
        .isEqualTo("2026-11-16T01:25:00Z");
    assertThat(a.path("etaStatus").asString()).isEqualTo("AVAILABLE");
    assertThat(a.path("delayedBySeconds").asLong()).isEqualTo(-300);
    assertThat(a.path("etaSource").path("confidence").asString()).isEqualTo("DIRECT");

    JsonNode b = items.get(1);
    assertThat(b.path("dispatch").path("state").isNull()).as("배차 원천 장애 — 상태 없음").isTrue();
    assertThat(b.path("dispatch").path("source").path("confidence").asString())
        .isEqualTo("UNKNOWN");
    assertThat(b.path("promisedAt").isNull()).isTrue();
    assertThat(b.path("openCases").findValuesAsString("caseId")).containsExactly("CASE-9");
    assertThat(b.path("latestEta").isNull()).as("tracking 에 없는 배송 — 없음").isTrue();
    assertThat(b.path("etaSource").path("confidence").asString()).isEqualTo("DIRECT");
    assertThat(r.body().path("unavailableSources").toString())
        .contains("delivery-dispatch-service")
        .doesNotContain("delivery-execution-service")
        .doesNotContain("delivery-tracking-service");

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

  /** 0.9.2: 열린 사건을 커서로 다음 쪽까지 읽고, 도착 예정 원천이 한 번 실패하면 남은 배송은 부르지 않고 UNKNOWN, 배차 404 는 DIRECT(없음). */
  @Test
  void openCasesFollowCursorAndFailingSourceIsCalledOnce() {
    cases.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/cases"))
            .willReturn(json(200, "{\"items\":[],\"page\":{\"nextCursor\":\"c2\"}}")));
    cases.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/cases"))
            .withQueryParam("cursor", equalTo("c2"))
            .willReturn(
                json(
                    200,
                    """
                    {"items":[{"caseId":"CASE-9","kind":"DISPATCH_FAILED","priority":"P2",
                               "subjectType":"DELIVERY","subjectRef":"DLV-B"}],
                     "page":{"nextCursor":null}}""")));
    tracking.stubFor(
        WireMock.get(urlPathMatching("/api/v1/deliveries/.+/eta"))
            .willReturn(json(503, "{\"code\":\"DEPENDENCY_UNAVAILABLE\"}")));
    dispatch.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/deliveries/DLV-B/dispatch"))
            .willReturn(json(404, "{\"code\":\"NOT_FOUND\"}")));

    Res r = live(CS, "mfcId=MFC-1&size=20");
    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    JsonNode items = r.body().path("items");
    assertThat(items.get(1).path("openCases").findValuesAsString("caseId"))
        .as("두 번째 쪽 사건도 이어짐")
        .containsExactly("CASE-9");
    assertThat(items.get(0).path("openCases")).as("다 읽었으니 없음은 []").isEmpty();
    assertThat(r.body().path("casesTruncated").asBoolean()).isFalse();
    assertThat(items.get(0).path("etaSource").path("confidence").asString()).isEqualTo("UNKNOWN");
    assertThat(items.get(1).path("etaSource").path("confidence").asString()).isEqualTo("UNKNOWN");
    // 첫 배송(DLV-A)에서 실패하면 다음 배송(DLV-B)은 부르지 않는다(HTTP 클라이언트의 503 재전송은 같은 호출)
    tracking.verify(0, getRequestedFor(urlPathEqualTo("/api/v1/deliveries/DLV-B/eta")));
    assertThat(items.get(1).path("dispatch").path("source").path("confidence").asString())
        .as("배차 기록 없음(404)은 원천의 답")
        .isEqualTo("DIRECT");
    assertThat(r.body().path("unavailableSources").toString())
        .contains("delivery-tracking-service")
        .doesNotContain("delivery-dispatch-service");
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

  /** 센터 관리자(2026-11-16 사용자 "추천대로"): 자기 센터로 고정 — 비우면 자기 센터, 다른 센터 403, 센터 없으면 403, 여럿이면 400. */
  @Test
  void mfcSupervisorIsPinnedToOwnCenter() {
    String sup = TestJwt.operator("op-sup", List.of("MFC_SUPERVISOR"), List.of("MFC-1"));
    Res own = live(sup, "state=ASSIGNED");
    assertThat(own.status()).as(own.body().toString()).isEqualTo(200);
    assertThat(own.body().path("items").findValuesAsString("deliveryId")).containsExactly("DLV-A");
    execution.verify(
        getRequestedFor(urlPathEqualTo("/api/v1/deliveries"))
            .withQueryParam("mfcId", equalTo("MFC-1"))
            .withHeader("Authorization", equalTo("Bearer " + sup)));
    assertThat(live(sup, "mfcId=MFC-2").status()).as("다른 센터").isEqualTo(403);
    assertThat(live(TestJwt.operator("op-sup0", List.of("MFC_SUPERVISOR"), List.of()), "").status())
        .isEqualTo(403);
    assertThat(
            live(
                    TestJwt.operator(
                        "op-sup2", List.of("MFC_SUPERVISOR"), List.of("MFC-1", "MFC-3")),
                    "")
                .status())
        .isEqualTo(400);
  }

  /** 0.14.0(ADR-0070): 화면 번호 — 배송·주문 번호는 execution 값 그대로, 라이더 번호·가린 이름은 rider 를 한 번에. */
  @Test
  void displayNumbersComeFromSourcesAndRiderIsLookedUpOnce() {
    rider.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/riders"))
            .willReturn(
                json(
                    200,
                    """
                    {"items":[{"riderId":"RD-1","riderNumber":"482913","nameMasked":"김*수"}],
                     "page":0,"size":50,"hasNext":false}""")));
    Res r = live(CS, "mfcId=MFC-1&size=20");
    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    JsonNode a = r.body().path("items").get(0);
    assertThat(a.path("deliveryNumber").asString()).isEqualTo("74939255-1");
    assertThat(a.path("orderNumber").asString()).isEqualTo("74939255");
    assertThat(a.path("rider").path("riderId").asString()).isEqualTo("RD-1");
    assertThat(a.path("rider").path("riderNumber").asString()).isEqualTo("482913");
    assertThat(a.path("rider").path("nameMasked").asString()).isEqualTo("김*수");
    JsonNode b = r.body().path("items").get(1);
    assertThat(b.path("deliveryNumber").isNull()).as("번호 없는 배송 — null").isTrue();
    assertThat(b.path("rider").isNull()).as("배정 전 — 라이더 없음").isTrue();
    assertThat(r.body().path("unavailableSources").toString()).doesNotContain("rider-service");
    rider.verify(
        1,
        getRequestedFor(urlPathEqualTo("/api/v1/riders"))
            .withQueryParam("riderIds", equalTo("RD-1"))
            .withHeader("Authorization", equalTo("Bearer " + CS)));
  }

  /** 0.14.0: rider 가 실패해도 화면은 그대로 — 번호·이름만 null, unavailableSources 에 rider-service. */
  @Test
  void riderFailureLeavesNumbersNullButKeepsTheView() {
    rider.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/riders"))
            .willReturn(json(503, "{\"code\":\"DEPENDENCY_UNAVAILABLE\"}")));
    Res r = live(CS, "mfcId=MFC-1&size=20");
    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    JsonNode a = r.body().path("items").get(0);
    assertThat(a.path("deliveryNumber").asString()).isEqualTo("74939255-1");
    assertThat(a.path("rider").path("riderId").asString()).isEqualTo("RD-1");
    assertThat(a.path("rider").path("riderNumber").isNull()).isTrue();
    assertThat(a.path("rider").path("nameMasked").isNull()).isTrue();
    assertThat(r.body().path("unavailableSources").toString()).contains("rider-service");
  }

  /**
   * 0.15.0(#194 검토 후속): rider 403 — 이 직원은 라이더를 볼 수 없음(예: 센터 관리자) — 은 장애가 아니다. 번호·이름만 null,
   * unavailableSources 에 rider-service 를 넣지 않는다.
   */
  @Test
  void riderForbiddenIsNotReportedAsOutage() {
    rider.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/riders"))
            .willReturn(json(403, "{\"code\":\"FORBIDDEN\"}")));
    Res r = live(CS, "mfcId=MFC-1&size=20");
    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    JsonNode a = r.body().path("items").get(0);
    assertThat(a.path("rider").path("riderId").asString()).isEqualTo("RD-1");
    assertThat(a.path("rider").path("riderNumber").isNull()).isTrue();
    assertThat(r.body().path("unavailableSources").toString()).doesNotContain("rider-service");
  }
}
