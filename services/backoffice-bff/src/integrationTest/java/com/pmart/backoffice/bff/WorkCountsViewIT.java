package com.pmart.backoffice.bff;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
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
 * 지금 일 건수(0.15.0, 2026-11-18 — 대시보드 D4): 사건·후속 처리·교대 인계·문의는 case 현황 그대로(정확), 처리 대기는 종류별, 승인 심사
 * 대기·끝나지 않은 배송은 쪽 넘김으로 세고 상한에 걸리면 complete=false. 원천 실패는 그 묶음 없이 unavailableSources, 관리자만.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
      "pmart.bff.mfc-pending-lists=false"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock({
  @ConfigureWireMock(name = "case", baseUrlProperties = "pmart.bff.case-base-url"),
  @ConfigureWireMock(name = "execution", baseUrlProperties = "pmart.bff.execution-base-url"),
  @ConfigureWireMock(name = "approval", baseUrlProperties = "pmart.bff.approval-base-url"),
  @ConfigureWireMock(name = "orch", baseUrlProperties = "pmart.bff.orchestration-base-url"),
  @ConfigureWireMock(name = "order", baseUrlProperties = "pmart.bff.order-base-url")
})
class WorkCountsViewIT {

  static final String ADMIN = TestJwt.operator("op-admin", List.of("OPERATOR_ADMIN"), List.of());
  static final String CS = TestJwt.operator("op-cs", List.of("CS_AGENT"), List.of());

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;

  @InjectWireMock("case")
  WireMockServer cases;

  @InjectWireMock("execution")
  WireMockServer execution;

  @InjectWireMock("approval")
  WireMockServer approval;

  @InjectWireMock("orch")
  WireMockServer orch;

  @InjectWireMock("order")
  WireMockServer order;

  RestClient http;

  @BeforeEach
  void setUp() {
    for (WireMockServer w : List.of(cases, execution, approval, orch, order)) {
      w.resetAll();
    }
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1/backoffice")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    cases.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/cases/metrics"))
            .willReturn(
                json(
                    200,
                    """
                    {"asOf":"2026-11-18T08:00:00Z",
                     "cases":[{"kind":"DISPATCH_FAILED","open":5,"unassigned":1,"overdue":2}],
                     "obligations":[{"owner":"CS","open":3,"overdue":1}],
                     "pendingHandovers":2,
                     "inquiries":[{"requesterType":"CUSTOMER","awaitingAnswer":4,"oldestReceivedAt":"2026-11-18T01:00:00Z"}]}""")));
    orch.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/stuck-sagas"))
            .willReturn(
                json(
                    200,
                    """
                    {"items":[{"sagaId":"SSG-1","sagaType":"SHORTAGE","orderId":"ORD-2","state":"ESCALATED",
                     "updatedAt":"2026-11-16T01:00:00Z","allowedActions":["MARK_RESOLVED"]},
                     {"sagaId":"SSG-2","sagaType":"SHORTAGE","orderId":"ORD-3","state":"ESCALATED",
                     "updatedAt":"2026-11-16T02:00:00Z","allowedActions":["MARK_RESOLVED"]}]}""")));
    order.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/fulfillment-issues"))
            .willReturn(json(200, "{\"items\":[]}")));
    // 승인: REVIEW_PENDING 2쪽(PRICE 2, PRODUCT 1), EVIDENCE_REQUIRED 0건
    approval.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/approvals"))
            .withQueryParam("reviewState", equalTo("REVIEW_PENDING"))
            .withQueryParam("cursor", absent())
            .willReturn(
                json(
                    200,
                    """
                    {"items":[{"approvalId":"AP-1","type":"PRICE"},{"approvalId":"AP-2","type":"PRODUCT"}],
                     "page":{"nextCursor":"c2","size":2}}""")));
    approval.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/approvals"))
            .withQueryParam("reviewState", equalTo("REVIEW_PENDING"))
            .withQueryParam("cursor", equalTo("c2"))
            .willReturn(
                json(
                    200,
                    """
                    {"items":[{"approvalId":"AP-3","type":"PRICE"}],"page":{"nextCursor":null,"size":1}}""")));
    approval.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/approvals"))
            .withQueryParam("reviewState", equalTo("EVIDENCE_REQUIRED"))
            .willReturn(json(200, "{\"items\":[],\"page\":{\"nextCursor\":null,\"size\":0}}")));
    // 승인(AP-3): 지금 보는 사람이 결정할 수 있는 것만 — PRICE 1건
    approval.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/approvals"))
            .withQueryParam("decidableByMe", equalTo("true"))
            .willReturn(
                json(
                    200,
                    """
                    {"items":[{"approvalId":"AP-1","type":"PRICE"}],"page":{"nextCursor":null,"size":1}}""")));
    // 배송: 기본은 0건, UNASSIGNED 3건(한 쪽)
    execution.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/deliveries"))
            .atPriority(10)
            .willReturn(json(200, "{\"items\":[],\"page\":{\"nextCursor\":null}}")));
    execution.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/deliveries"))
            .withQueryParam("state", equalTo("UNASSIGNED"))
            .atPriority(1)
            .willReturn(
                json(
                    200,
                    """
                    {"items":[{"deliveryId":"D1"},{"deliveryId":"D2"},{"deliveryId":"D3"}],"page":{"nextCursor":null}}""")));
  }

  record Res(int status, JsonNode body) {}

  Res counts(String token) {
    var e =
        http.get()
            .uri("/views/work-counts")
            .headers(h -> h.setBearerAuth(token))
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  static ResponseDefinitionBuilder json(int s, String b) {
    return aResponse().withStatus(s).withHeader("Content-Type", "application/json").withBody(b);
  }

  static JsonNode find(JsonNode body, String group, String key, String segment) {
    for (JsonNode c : body.path("counts")) {
      if (group.equals(c.path("group").asString())
          && key.equals(c.path("key").asString())
          && (segment == null
              ? c.path("segment").isNull()
              : segment.equals(c.path("segment").asString()))) {
        return c;
      }
    }
    return null;
  }

  @Test
  void adminSeesCountsFromEverySource() {
    Res r = counts(ADMIN);
    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    JsonNode b = r.body();
    assertThat(b.path("unavailableSources").size()).isZero();
    assertThat(find(b, "CASES", "OPEN", "DISPATCH_FAILED").path("value").asLong()).isEqualTo(5);
    assertThat(find(b, "CASES", "UNASSIGNED", "DISPATCH_FAILED").path("value").asLong())
        .isEqualTo(1);
    assertThat(find(b, "CASES", "OVERDUE", "DISPATCH_FAILED").path("complete").asBoolean())
        .isTrue();
    assertThat(find(b, "OBLIGATIONS", "OVERDUE", "CS").path("value").asLong()).isEqualTo(1);
    assertThat(find(b, "SHIFT_HANDOVERS", "PENDING", null).path("value").asLong()).isEqualTo(2);
    assertThat(find(b, "INQUIRIES", "AWAITING_ANSWER", "CUSTOMER").path("value").asLong())
        .isEqualTo(4);
    JsonNode stuck = find(b, "PENDING_WORK", "COUNT", "STUCK_SAGA");
    assertThat(stuck.path("value").asLong()).isEqualTo(2);
    assertThat(stuck.path("source").asString()).isEqualTo("order-orchestration-service");
    assertThat(find(b, "APPROVALS", "REVIEW_PENDING", "PRICE").path("value").asLong())
        .as("두 쪽을 넘겨 셈")
        .isEqualTo(2);
    assertThat(find(b, "APPROVALS", "REVIEW_PENDING", "PRODUCT").path("complete").asBoolean())
        .isTrue();
    JsonNode mine = find(b, "APPROVALS", "DECIDABLE_BY_ME", "PRICE");
    assertThat(mine.path("value").asLong()).as("AP-3 — 내가 결정할 수 있는 것만").isEqualTo(1);
    assertThat(mine.path("complete").asBoolean()).isTrue();
    assertThat(find(b, "APPROVALS", "DECIDABLE_BY_ME", "PRODUCT")).isNull();
    JsonNode unassigned = find(b, "DELIVERIES", "ACTIVE", "UNASSIGNED");
    assertThat(unassigned.path("value").asLong()).isEqualTo(3);
    assertThat(unassigned.path("complete").asBoolean()).isTrue();
    assertThat(find(b, "DELIVERIES", "ACTIVE", "PICKED_UP")).as("0건 상태는 줄 없음").isNull();
  }

  @Test
  void pagingCapMarksCountIncomplete() {
    execution.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/deliveries"))
            .withQueryParam("state", equalTo("ASSIGNED"))
            .atPriority(1)
            .willReturn(
                json(
                    200,
                    """
                    {"items":[{"deliveryId":"D9"}],"page":{"nextCursor":"more"}}""")));
    Res r = counts(ADMIN);
    assertThat(r.status()).isEqualTo(200);
    JsonNode assigned = find(r.body(), "DELIVERIES", "ACTIVE", "ASSIGNED");
    assertThat(assigned.path("value").asLong()).as("10쪽 × 1건까지 셈").isEqualTo(10);
    assertThat(assigned.path("complete").asBoolean()).as("상한 — 적어도 이만큼").isFalse();
  }

  @Test
  void failedSourceIsReportedNotZeroed() {
    approval.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/approvals"))
            .atPriority(1)
            .willReturn(json(503, "{\"code\":\"DEPENDENCY_UNAVAILABLE\"}")));
    Res r = counts(ADMIN);
    assertThat(r.status()).isEqualTo(200);
    assertThat(r.body().path("unavailableSources").toString()).contains("approval-service");
    assertThat(r.body().path("counts").toString()).doesNotContain("\"APPROVALS\"");
    assertThat(find(r.body(), "CASES", "OPEN", "DISPATCH_FAILED")).as("다른 묶음은 그대로").isNotNull();
  }

  @Test
  void onlyAdminSeesCounts() {
    assertThat(counts(CS).status()).isEqualTo(403);
  }
}
