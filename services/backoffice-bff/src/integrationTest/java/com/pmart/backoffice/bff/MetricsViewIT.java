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
@EnableWireMock({
  @ConfigureWireMock(name = "case", baseUrlProperties = "pmart.bff.case-base-url"),
  @ConfigureWireMock(name = "mfc", baseUrlProperties = "pmart.bff.mfc-fulfillment-base-url"),
  @ConfigureWireMock(name = "dispatch", baseUrlProperties = "pmart.bff.dispatch-base-url")
})
class MetricsViewIT {

  static final String ADMIN = TestJwt.operator("op-admin", List.of("OPERATOR_ADMIN"), List.of());

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;

  @InjectWireMock("case")
  WireMockServer cases;

  @InjectWireMock("mfc")
  WireMockServer mfc;

  @InjectWireMock("dispatch")
  WireMockServer dispatch;

  RestClient http;

  @BeforeEach
  void setUp() {
    cases.resetAll();
    mfc.resetAll();
    dispatch.resetAll();
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
    Res r = metrics("metric=TRANSPORT_ON_TIME&from=2026-11-17&to=2026-11-18");
    assertThat(r.status()).isEqualTo(501);
    assertThat(r.body().path("code").asString()).isEqualTo("NOT_IMPLEMENTED");
    Res pay = metrics("metric=PAYMENT_UNKNOWN&from=2026-11-17&to=2026-11-18");
    assertThat(pay.status()).isEqualTo(501);
    assertThat(pay.body().path("detail").asString()).contains("처리 대기");
    cases.verify(0, getRequestedFor(urlPathEqualTo("/api/v1/cases/metrics/sla")));
  }

  /** 0.20.0(PC 3 F-10): 결품률 — 줄 단위, 날짜·센터 점 + 사유별 점, totals 는 전체 점만, 시간 단위 400, 원천 장애 503. */
  @Test
  void shortageRateUsesPickingLinesByDayCenterAndReason() {
    mfc.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/metrics/picking-lines"))
            .willReturn(
                json(
                    200,
                    """
                    {"asOf":"2026-11-19T03:00:00Z","items":[
                      {"date":"2026-11-18","mfcId":"MFC-1","pickedLines":40,"shortLines":4,"shortLinesByReason":{"SHORT":3,"DAMAGED":1,"BLOCKED":1,"SPOILED":1}},
                      {"date":"2026-11-18","mfcId":"MFC-2","pickedLines":10,"shortLines":0,"shortLinesByReason":{"SHORT":0,"DAMAGED":0,"BLOCKED":0}}]}""")));

    Res r = metrics("metric=SHORTAGE_RATE&from=2026-11-18&to=2026-11-18&centerId=MFC-1");

    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    assertThat(r.body().path("metric").asString()).isEqualTo("SHORTAGE_RATE");
    JsonNode points = r.body().path("points");
    assertThat(points.size())
        .as("MFC-1 전체 + 사유 4(변질 칸 있음), MFC-2 전체 + 사유 3(변질 칸 없음 — 0 으로 만들지 않음)")
        .isEqualTo(9);
    assertThat(points.get(4).path("segment").path("reason").asString()).isEqualTo("SPOILED");
    JsonNode all = points.get(0);
    assertThat(all.path("segment").path("mfcId").asString()).isEqualTo("MFC-1");
    assertThat(all.path("segment").path("reason").isNull()).isTrue();
    assertThat(all.path("numerator").asLong()).isEqualTo(4);
    assertThat(all.path("denominator").asLong()).isEqualTo(40);
    assertThat(all.path("ratio").asDouble()).isEqualTo(0.1);
    assertThat(points.get(1).path("segment").path("reason").asString()).isEqualTo("SHORT");
    assertThat(points.get(1).path("numerator").asLong()).isEqualTo(3);
    JsonNode total = r.body().path("totals").get(0);
    assertThat(total.path("numerator").asLong()).as("사유별은 더하지 않음").isEqualTo(4);
    assertThat(total.path("denominator").asLong()).isEqualTo(50);
    assertThat(r.body().path("currentOverdue").isNull()).isTrue();
    mfc.verify(
        getRequestedFor(urlPathEqualTo("/api/v1/metrics/picking-lines"))
            .withHeader(
                "Authorization",
                com.github.tomakehurst.wiremock.client.WireMock.equalTo("Bearer " + ADMIN))
            .withQueryParam(
                "mfcId", com.github.tomakehurst.wiremock.client.WireMock.equalTo("MFC-1")));

    assertThat(
            metrics("metric=SHORTAGE_RATE&from=2026-11-18&to=2026-11-18&granularity=HOUR").status())
        .as("날짜 단위만")
        .isEqualTo(400);
    mfc.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/metrics/picking-lines"))
            .willReturn(aResponse().withStatus(503)));
    assertThat(metrics("metric=SHORTAGE_RATE&from=2026-11-18&to=2026-11-18").status())
        .as("원천 장애는 0건이 아니라 503")
        .isEqualTo(503);
  }

  /** 0.21.0(PC 5 DD-4): 정시 배송률 — 분모 = due(정시 + 늦음), 판정 불가·약속 전은 따로, 날짜 합계, 원천 400 그대로·장애 503. */
  @Test
  void onTimeDeliveryKeepsPendingAndUnjudgedOutOfDenominator() {
    dispatch.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/metrics/on-time-deliveries"))
            .willReturn(
                json(
                    200,
                    """
                    {"asOf":"2026-11-19T03:00:00Z","items":[
                      {"date":"2026-11-18","mfcId":"MFC-1","due":10,"onTime":8,"late":2,"unjudged":1,"pending":0},
                      {"date":"2026-11-18","mfcId":"MFC-2","due":0,"onTime":0,"late":0,"unjudged":0,"pending":3}]}""")));

    Res r = metrics("metric=ON_TIME_DELIVERY&from=2026-11-18&to=2026-11-18");

    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    JsonNode a = r.body().path("points").get(0);
    assertThat(a.path("numerator").asLong()).isEqualTo(8);
    assertThat(a.path("denominator").asLong()).isEqualTo(10);
    assertThat(a.path("ratio").asDouble()).isEqualTo(0.8);
    assertThat(a.path("unjudged").asLong()).isEqualTo(1);
    assertThat(a.path("segment").path("mfcId").asString()).isEqualTo("MFC-1");
    JsonNode b = r.body().path("points").get(1);
    assertThat(b.path("ratio").isNull()).as("분모 0 — N/A").isTrue();
    assertThat(b.path("pending").asLong()).isEqualTo(3);
    JsonNode t = r.body().path("totals").get(0);
    assertThat(t.path("denominator").asLong()).isEqualTo(10);
    assertThat(t.path("pending").asLong()).isEqualTo(3);
    dispatch.verify(
        getRequestedFor(urlPathEqualTo("/api/v1/metrics/on-time-deliveries"))
            .withHeader(
                "Authorization",
                com.github.tomakehurst.wiremock.client.WireMock.equalTo("Bearer " + ADMIN)));

    dispatch.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/metrics/on-time-deliveries"))
            .willReturn(json(400, "{\"code\":\"VALIDATION_FAILED\",\"detail\":\"기간은 31일까지\"}")));
    Res bad = metrics("metric=ON_TIME_DELIVERY&from=2026-10-01&to=2026-11-18");
    assertThat(bad.status()).isEqualTo(400);
    assertThat(bad.body().path("detail").asString()).contains("31일");
    dispatch.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/metrics/on-time-deliveries"))
            .willReturn(aResponse().withStatus(503)));
    assertThat(metrics("metric=ON_TIME_DELIVERY&from=2026-11-18&to=2026-11-18").status())
        .isEqualTo(503);
  }
}
