package com.pmart.backoffice.bff;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
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
 * 지표 화면(0.13.0, 2026-11-18): CASE_SLA 는 case 기한 준수를 분자 = 기한 안에 닫힘, 분모 = 기한 있던 닫힌 건, unjudged = 기한
 * 없던 건(분모 0 이면 ratio null), 묶음별 합, 지금 기한 지난 건(보조 원천 — 실패하면 null + unavailableSources). case 4xx 는
 * 그대로, case 장애는 503(0건으로 보이지 않게), 그 밖 지표 501.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock({@ConfigureWireMock(name = "case", baseUrlProperties = "pmart.bff.case-base-url")})
class MetricsViewIT {

  static final String ADMIN = TestJwt.operator("op-admin", List.of("OPERATOR_ADMIN"), List.of());

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;

  @InjectWireMock("case")
  WireMockServer cases;

  RestClient http;

  @BeforeEach
  void setUp() {
    cases.resetAll();
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1/backoffice")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    cases.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/cases/metrics/sla"))
            .willReturn(
                json(
                    200,
                    """
                    {"asOf":"2026-11-18T03:00:00Z","timeZone":"Asia/Seoul","granularity":"DAY","rows":[
                      {"bucket":"2026-11-17","kind":"DISPATCH_FAILED","assigneeGroup":"DELIVERY_OPS","closed":4,"closedOnTime":3,"closedWithoutSla":0},
                      {"bucket":"2026-11-17","kind":"RECONCILIATION","assigneeGroup":"ORDER_OPS","closed":3,"closedOnTime":1,"closedWithoutSla":1},
                      {"bucket":"2026-11-18","kind":"RECONCILIATION","assigneeGroup":"ORDER_OPS","closed":2,"closedOnTime":0,"closedWithoutSla":2}]}""")));
    cases.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/cases/metrics"))
            .willReturn(
                json(
                    200,
                    """
                    {"asOf":"2026-11-18T03:00:00Z","cases":[{"kind":"DISPATCH_FAILED","open":5,"unassigned":1,"overdue":2}],
                     "obligations":[],"pendingHandovers":0,"inquiries":[]}""")));
  }

  record Res(int status, JsonNode body) {}

  Res metrics(String query) {
    var e =
        http.get()
            .uri("/views/metrics?" + query)
            .headers(h -> h.setBearerAuth(ADMIN))
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  static ResponseDefinitionBuilder json(int s, String b) {
    return aResponse().withStatus(s).withHeader("Content-Type", "application/json").withBody(b);
  }

  @Test
  void caseSlaKeepsUnjudgedOutOfDenominatorButVisible() {
    Res r = metrics("metric=CASE_SLA&from=2026-11-17&to=2026-11-18&centerId=MFC-1&granularity=DAY");
    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    cases.verify(
        getRequestedFor(urlPathEqualTo("/api/v1/cases/metrics/sla"))
            .withQueryParam("from", equalTo("2026-11-17"))
            .withQueryParam("to", equalTo("2026-11-18"))
            .withQueryParam("granularity", equalTo("DAY"))
            .withQueryParam("centerId", equalTo("MFC-1"))
            .withHeader("Authorization", equalTo("Bearer " + ADMIN)));
    JsonNode b = r.body();
    assertThat(b.path("metric").asString()).isEqualTo("CASE_SLA");
    assertThat(b.path("timeZone").asString()).isEqualTo("Asia/Seoul");
    JsonNode p = b.path("points");
    assertThat(p.size()).isEqualTo(3);
    assertThat(p.get(0).path("segment").path("kind").asString()).isEqualTo("DISPATCH_FAILED");
    assertThat(p.get(0).path("numerator").asInt()).isEqualTo(3);
    assertThat(p.get(0).path("denominator").asInt()).isEqualTo(4);
    assertThat(p.get(0).path("ratio").asDouble()).isEqualTo(0.75);
    assertThat(p.get(1).path("denominator").asInt()).as("기한 없던 1건은 분모에서 뺌").isEqualTo(2);
    assertThat(p.get(1).path("unjudged").asInt()).isEqualTo(1);
    assertThat(p.get(1).path("segment").path("assigneeGroup").asString()).isEqualTo("ORDER_OPS");
    assertThat(p.get(2).path("denominator").asInt()).isZero();
    assertThat(p.get(2).path("ratio").isNull()).as("분모 0 은 N/A — 100% 아님").isTrue();
    assertThat(p.get(0).path("source").path("asOf").path("asOf").asString())
        .isEqualTo("2026-11-18T03:00:00Z");

    JsonNode t = b.path("totals");
    assertThat(t.findValuesAsString("bucket")).containsExactly("2026-11-17", "2026-11-18");
    assertThat(t.get(0).path("numerator").asInt()).isEqualTo(4);
    assertThat(t.get(0).path("denominator").asInt()).isEqualTo(6);
    assertThat(t.get(0).path("unjudged").asInt()).isEqualTo(1);
    assertThat(t.get(1).path("ratio").isNull()).isTrue();
    assertThat(t.get(1).path("unjudged").asInt()).isEqualTo(2);

    assertThat(b.path("currentOverdue").get(0).path("overdue").asInt()).isEqualTo(2);
    assertThat(b.path("unavailableSources")).isEmpty();
  }

  @Test
  void snapshotFailureLeavesPeriodMetricAndMarksUnknown() {
    cases.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/cases/metrics")).willReturn(json(503, "{}")));
    Res r = metrics("metric=CASE_SLA&from=2026-11-17&to=2026-11-18");
    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    assertThat(r.body().path("points").size()).isEqualTo(3);
    assertThat(r.body().path("currentOverdue").isNull()).isTrue();
    assertThat(r.body().path("unavailableSources").toString()).contains("case-service");
  }

  @Test
  void sourceRejectionsAndOutagePassThrough() {
    cases.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/cases/metrics/sla"))
            .willReturn(
                json(
                    400, "{\"code\":\"VALIDATION_FAILED\",\"detail\":\"HOUR 는 7일까지 볼 수 있습니다.\"}")));
    Res bad = metrics("metric=CASE_SLA&from=2026-11-01&to=2026-11-18&granularity=HOUR");
    assertThat(bad.status()).isEqualTo(400);
    assertThat(bad.body().path("detail").asString()).contains("7일");

    cases.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/cases/metrics/sla")).willReturn(json(403, "{}")));
    assertThat(metrics("metric=CASE_SLA&from=2026-11-17&to=2026-11-18").status()).isEqualTo(403);

    cases.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/cases/metrics/sla")).willReturn(json(503, "{}")));
    Res down = metrics("metric=CASE_SLA&from=2026-11-17&to=2026-11-18");
    assertThat(down.status()).as("원천 장애는 0건이 아니라 503").isEqualTo(503);
  }

  @Test
  void otherMetricsAreNotImplemented() {
    Res r = metrics("metric=ON_TIME_DELIVERY&from=2026-11-17&to=2026-11-18");
    assertThat(r.status()).isEqualTo(501);
    assertThat(r.body().path("code").asString()).isEqualTo("NOT_IMPLEMENTED");
    Res pay = metrics("metric=PAYMENT_UNKNOWN&from=2026-11-17&to=2026-11-18");
    assertThat(pay.status()).isEqualTo(501);
    assertThat(pay.body().path("detail").asString()).contains("처리 대기");
    cases.verify(0, getRequestedFor(urlPathEqualTo("/api/v1/cases/metrics/sla")));
  }
}
