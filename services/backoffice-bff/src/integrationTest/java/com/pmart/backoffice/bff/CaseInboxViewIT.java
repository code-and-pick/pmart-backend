package com.pmart.backoffice.bff;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
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
 * 사건함 화면 조합(0.5.0 — 2026-11-16): case 목록을 직원 토큰으로(필터 이름 옮김), 사건마다 연결 배송·주문의 지금 상태를 같은 ID 한 번만, 연결
 * 원천 실패·403 은 그 칸만 UNKNOWN + unavailableSources, 404 는 없음(원천 정상), case 403 은 그대로, case 장애는 빈 목록 +
 * unavailableSources.
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
  @ConfigureWireMock(name = "case", baseUrlProperties = "pmart.bff.case-base-url"),
  @ConfigureWireMock(name = "execution", baseUrlProperties = "pmart.bff.execution-base-url"),
  @ConfigureWireMock(name = "order", baseUrlProperties = "pmart.bff.order-base-url"),
  @ConfigureWireMock(name = "rider", baseUrlProperties = "pmart.bff.rider-base-url"),
  @ConfigureWireMock(name = "hr", baseUrlProperties = "pmart.bff.hr-base-url")
})
class CaseInboxViewIT {

  static final String RIDER_OPS = TestJwt.operator("op-ro", List.of("RIDER_OPS"), List.of());

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;

  @InjectWireMock("case")
  WireMockServer cases;

  @InjectWireMock("execution")
  WireMockServer execution;

  @InjectWireMock("order")
  WireMockServer order;

  @InjectWireMock("rider")
  WireMockServer rider;

  @InjectWireMock("hr")
  WireMockServer hr;

  RestClient http;

  @BeforeEach
  void setUp() {
    for (WireMockServer w : List.of(cases, execution, order, rider, hr)) {
      w.resetAll();
    }
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1/backoffice")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    cases.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/cases"))
            .willReturn(
                json(
                    200,
                    """
                    {"items":[
                      {"caseId":"CASE-1","kind":"RIDER_HELP","state":"OPEN","priority":"P1","subjectType":"DELIVERY","subjectRef":"DLV-1",
                       "summary":"라이더 도움요청","overdue":true,"slaDueAt":"2026-11-16T01:10:00Z","openObligationCount":0,"myLastSeenAt":"2026-11-17T01:02:00Z",
                       "refs":[{"refType":"deliveryId","refId":"DLV-1"},{"refType":"orderId","refId":"ORD-1"},{"refType":"incidentId","refId":"DLV-1:R1"},{"refType":"requestId","refId":"R1"}]},
                      {"caseId":"CASE-2","kind":"MISDELIVERY","state":"ASSIGNED","priority":"P2","subjectType":"DELIVERY","subjectRef":"DLV-1",
                       "refs":[{"refType":"orderId","refId":"ORD-404"},{"refType":"recoveryDeliveryId","refId":"DLV-R"},{"refType":"reshipmentDeliveryId","refId":"DLV-S"}]}],
                     "page":{"nextCursor":"n2"}}""")));
    execution.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/deliveries/DLV-1"))
            .willReturn(
                json(200, "{\"deliveryId\":\"DLV-1\",\"state\":\"PICKED_UP\",\"version\":5}")));
    execution.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/deliveries/DLV-R"))
            .willReturn(
                json(
                    200,
                    "{\"deliveryId\":\"DLV-R\",\"state\":\"RETURN_IN_PROGRESS\",\"version\":2}")));
    execution.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/deliveries/DLV-S"))
            .willReturn(
                json(200, "{\"deliveryId\":\"DLV-S\",\"state\":\"ASSIGNED\",\"version\":1}")));
    execution.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/deliveries/DLV-1/help-requests/R1"))
            .willReturn(
                json(
                    200,
                    "{\"requestId\":\"R1\",\"state\":\"IN_PROGRESS\",\"lastRiderMessageAt\":\"2026-11-17T01:05:00Z\",\"lastSupportMessageAt\":\"2026-11-17T01:00:00Z\"}")));
    order.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/orders/lookups/ORD-1"))
            .willReturn(json(403, "{\"code\":\"FORBIDDEN\"}")));
    order.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/orders/lookups/ORD-404"))
            .willReturn(json(404, "{\"code\":\"NOT_FOUND\"}")));
  }

  record Res(int status, JsonNode body) {}

  Res inbox(String query) {
    var e =
        http.get()
            .uri("/views/case-inbox" + (query.isEmpty() ? "" : "?" + query))
            .headers(h -> h.setBearerAuth(RIDER_OPS))
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  static ResponseDefinitionBuilder json(int s, String b) {
    return aResponse().withStatus(s).withHeader("Content-Type", "application/json").withBody(b);
  }

  @Test
  void casesCarryLinkedDeliveryAndOrderStatePerSource() {
    Res r = inbox("assignee=op-x&unassigned=false&overdue=true&size=50");
    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    JsonNode items = r.body().path("items");
    assertThat(items.findValuesAsString("caseId")).containsExactly("CASE-1", "CASE-2");

    JsonNode c1 = items.get(0);
    assertThat(c1.path("kind").asString()).isEqualTo("RIDER_HELP");
    assertThat(c1.path("overdue").asBoolean()).isTrue();
    assertThat(c1.path("myLastSeenAt").asString())
        .as("부른 직원이 본 시각(0.10.0)")
        .isEqualTo("2026-11-17T01:02:00Z");
    assertThat(items.get(1).path("myLastSeenAt").isNull()).isTrue();
    assertThat(c1.path("linkedRefs")).hasSize(4);
    JsonNode dl = c1.path("linked").get(0);
    assertThat(dl.path("kind").asString()).isEqualTo("DELIVERY");
    assertThat(dl.path("state").asString()).isEqualTo("PICKED_UP");
    assertThat(dl.path("version").asLong()).isEqualTo(5);
    assertThat(dl.path("source").path("confidence").asString()).isEqualTo("DIRECT");
    JsonNode od = c1.path("linked").get(1);
    assertThat(od.path("kind").asString()).isEqualTo("ORDER");
    assertThat(od.path("state").isNull()).as("주문을 못 보는 역할 — 그 칸만 모름").isTrue();
    assertThat(od.path("source").path("confidence").asString()).isEqualTo("UNKNOWN");
    JsonNode help = c1.path("linked").get(2);
    assertThat(help.path("kind").asString()).as("도움요청 대화(0.6.0, DE-2)").isEqualTo("HELP_REQUEST");
    assertThat(help.path("id").asString()).isEqualTo("R1");
    assertThat(help.path("state").asString()).isEqualTo("IN_PROGRESS");
    assertThat(help.path("lastRiderMessageAt").asString()).isEqualTo("2026-11-17T01:05:00Z");
    assertThat(help.path("lastSupportMessageAt").asString()).isEqualTo("2026-11-17T01:00:00Z");
    assertThat(items.get(1).path("linked").findValuesAsString("kind"))
        .as("오배송 건엔 도움요청 없음")
        .doesNotContain("HELP_REQUEST");

    JsonNode c2 = items.get(1);
    assertThat(c2.path("linked").get(1).path("id").asString())
        .as("오배송 회수 배송도 배송으로(0.5.1)")
        .isEqualTo("DLV-R");
    assertThat(c2.path("linked").get(1).path("state").asString()).isEqualTo("RETURN_IN_PROGRESS");
    assertThat(c2.path("linked").get(2).path("id").asString())
        .as("오배송 재발송 배송도 배송으로(0.5.2)")
        .isEqualTo("DLV-S");
    assertThat(c2.path("linked").get(2).path("state").asString()).isEqualTo("ASSIGNED");
    JsonNode notFound = c2.path("linked").get(3);
    assertThat(notFound.path("id").asString()).isEqualTo("ORD-404");
    assertThat(notFound.path("state").isNull()).isTrue();
    assertThat(notFound.path("source").path("confidence").asString())
        .as("404 는 원천의 답(없음)")
        .isEqualTo("DIRECT");

    assertThat(r.body().path("unavailableSources").toString())
        .contains("order-service")
        .doesNotContain("delivery-execution-service")
        .doesNotContain("\"case-service\"");
    assertThat(r.body().path("page").path("nextCursor").asString()).isEqualTo("n2");

    // 같은 배송은 한 번만, case 에는 직원 토큰·옮긴 필터
    execution.verify(1, getRequestedFor(urlPathEqualTo("/api/v1/deliveries/DLV-1")));
    cases.verify(
        getRequestedFor(urlPathEqualTo("/api/v1/cases"))
            .withHeader("Authorization", equalTo("Bearer " + RIDER_OPS))
            .withQueryParam("assigneeId", equalTo("op-x"))
            .withQueryParam("overdue", equalTo("true"))
            .withQueryParam("size", equalTo("50")));
  }

  /** 0.17.0(ADR-0070·HR-4): 맡은 직원 "이름 · 사번" — hr 를 화면 응답 하나에 한 번. */
  @Test
  void assigneeGetsNameAndEmployeeNo() {
    cases.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/cases"))
            .atPriority(1)
            .willReturn(
                json(
                    200,
                    """
                    {"items":[{"caseId":"CASE-A","caseNumber":"C-1119-0042","kind":"DISPATCH_FAILED","state":"ASSIGNED","priority":"P2",
                     "assigneeId":"op-x","refs":[]},
                     {"caseId":"CASE-B","kind":"DISPATCH_FAILED","state":"OPEN","priority":"P2","refs":[]}],
                     "page":{"nextCursor":null}}""")));
    hr.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/hr/employee-labels"))
            .willReturn(
                json(
                    200,
                    "{\"items\":[{\"operatorId\":\"op-x\",\"name\":\"김지원\",\"employeeNo\":\"P2026-00003\"}]}")));

    Res r = inbox("");

    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    assertThat(r.body().path("items").get(0).path("caseNumber").asString())
        .as("화면 접수 번호(0.19.0, case 0.25.0)")
        .isEqualTo("C-1119-0042");
    JsonNode a = r.body().path("items").get(0).path("assignee");
    assertThat(a.path("operatorId").asString()).isEqualTo("op-x");
    assertThat(a.path("name").asString()).isEqualTo("김지원");
    assertThat(a.path("employeeNo").asString()).isEqualTo("P2026-00003");
    assertThat(r.body().path("items").get(1).path("assignee").isNull()).as("안 맡은 건").isTrue();
    assertThat(r.body().path("unavailableSources").toString()).doesNotContain("hr-service");
    hr.verify(1, getRequestedFor(urlPathEqualTo("/api/v1/hr/employee-labels")));
  }

  @Test
  void caseForbiddenPassesThroughAndCaseDownIsReported() {
    cases.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/cases"))
            .willReturn(json(403, "{\"code\":\"FORBIDDEN\"}")));
    assertThat(inbox("").status()).isEqualTo(403);

    cases.stubFor(WireMock.get(urlPathEqualTo("/api/v1/cases")).willReturn(json(503, "{}")));
    Res r = inbox("");
    assertThat(r.status()).isEqualTo(200);
    assertThat(r.body().path("items")).isEmpty();
    assertThat(r.body().path("unavailableSources").toString()).contains("case-service");
  }

  /** 0.9.3: 토큰 만료(401)는 401 로, 장애 난 연결 원천은 남은 건에서 부르지 않음. */
  @Test
  void expiredTokenIs401AndBrokenLinkedSourceIsCalledOnce() {
    cases.stubFor(WireMock.get(urlPathEqualTo("/api/v1/cases/x")).willReturn(json(200, "{}")));
    cases.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/cases"))
            .withQueryParam("state", equalTo("EXPIRED"))
            .willReturn(json(401, "{\"code\":\"UNAUTHENTICATED\"}")));
    assertThat(inbox("state=EXPIRED").status()).as("다시 로그인 안내가 되게").isEqualTo(401);

    execution.stubFor(
        WireMock.get(urlPathMatching("/api/v1/deliveries/.+"))
            .willReturn(json(503, "{\"code\":\"DEPENDENCY_UNAVAILABLE\"}")));
    Res r = inbox("");
    assertThat(r.status()).isEqualTo(200);
    assertThat(r.body().path("unavailableSources").toString())
        .contains("delivery-execution-service");
    execution.verify(0, getRequestedFor(urlPathEqualTo("/api/v1/deliveries/DLV-R")));
    execution.verify(0, getRequestedFor(urlPathEqualTo("/api/v1/deliveries/DLV-S")));
    assertThat(
            r.body()
                .path("items")
                .get(1)
                .path("linked")
                .get(1)
                .path("source")
                .path("confidence")
                .asString())
        .isEqualTo("UNKNOWN");
  }

  /** 0.14.0(ADR-0070): 연결 배송·주문의 화면 번호와 사건이 가리키는 라이더의 번호·가린 이름(rider 한 번), rider 실패면 번호만 null. */
  @Test
  void linkedEntriesCarryDisplayNumbersAndRidersAreLookedUpOnce() {
    cases.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/cases"))
            .willReturn(
                json(
                    200,
                    """
                    {"items":[
                      {"caseId":"CASE-N","kind":"DELIVERY_INCIDENT","state":"OPEN","priority":"P2","subjectType":"DELIVERY","subjectRef":"DLV-N",
                       "refs":[{"refType":"deliveryId","refId":"DLV-N"},{"refType":"riderId","refId":"RD-7"}]},
                      {"caseId":"CASE-M","kind":"RIDER_HELP","state":"OPEN","priority":"P1","subjectType":"DELIVERY","subjectRef":"DLV-N",
                       "refs":[{"refType":"riderId","refId":"RD-7"}]}],
                     "page":{"nextCursor":null}}""")));
    execution.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/deliveries/DLV-N"))
            .willReturn(
                json(
                    200,
                    """
                    {"deliveryId":"DLV-N","deliveryNumber":"74939255-2","state":"ASSIGNED","version":3,
                     "orderSummary":{"orderNumber":"74939255","items":[]}}""")));
    rider.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/riders"))
            .willReturn(
                json(
                    200,
                    """
                    {"items":[{"riderId":"RD-7","riderNumber":"482913","nameMasked":"김*수"}],
                     "page":0,"size":50,"hasNext":false}""")));

    Res r = inbox("");
    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    JsonNode c = r.body().path("items").get(0);
    JsonNode dl = c.path("linked").get(0);
    assertThat(dl.path("displayNumber").asString()).isEqualTo("74939255-2");
    assertThat(dl.path("orderNumber").asString()).isEqualTo("74939255");
    assertThat(c.path("riders").get(0).path("riderId").asString()).isEqualTo("RD-7");
    assertThat(c.path("riders").get(0).path("riderNumber").asString()).isEqualTo("482913");
    assertThat(c.path("riders").get(0).path("nameMasked").asString()).isEqualTo("김*수");
    assertThat(c.has("riderIds")).as("내부 칸은 내보내지 않음").isFalse();
    rider.verify(
        1,
        getRequestedFor(urlPathEqualTo("/api/v1/riders"))
            .withQueryParam("riderIds", equalTo("RD-7"))
            .withHeader("Authorization", equalTo("Bearer " + RIDER_OPS)));

    rider.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/riders"))
            .willReturn(json(403, "{\"code\":\"FORBIDDEN\"}")));
    Res denied = inbox("");
    assertThat(denied.status()).isEqualTo(200);
    JsonNode rd = denied.body().path("items").get(0).path("riders").get(0);
    assertThat(rd.path("riderId").asString()).isEqualTo("RD-7");
    assertThat(rd.path("riderNumber").isNull()).as("rider 를 못 읽으면 번호만 null").isTrue();
    assertThat(denied.body().path("unavailableSources").toString())
        .as("0.15.0 — 403 은 볼 권한 없음이지 장애가 아님")
        .doesNotContain("rider-service");
  }
}
