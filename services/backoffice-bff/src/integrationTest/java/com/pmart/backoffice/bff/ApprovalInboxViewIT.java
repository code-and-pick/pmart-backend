package com.pmart.backoffice.bff;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
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
 * 승인함 화면(BR-40): approval-service 목록을 운영자 본인 토큰으로 읽어 묶는다. approval-service 는 WireMock — 정상·장애(빈 목록
 * + unavailableSources)·거절(403 그대로).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock(
    @ConfigureWireMock(name = "approval", baseUrlProperties = "pmart.bff.approval-base-url"))
class ApprovalInboxViewIT {

  static final String OPERATOR =
      TestJwt.operator("approver-1", List.of("PRODUCT_APPROVER"), List.of());

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;

  @InjectWireMock("approval")
  WireMockServer approval;

  RestClient http;

  @BeforeEach
  void setUp() {
    approval.resetAll();
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1/backoffice")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
  }

  record Res(int status, JsonNode body) {}

  Res inbox(String query, String token) {
    var e =
        http.get()
            .uri("/views/approval-inbox" + query)
            .headers(
                h -> {
                  if (token != null) {
                    h.setBearerAuth(token);
                  }
                })
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  void approvalReturns(int status, String body) {
    approval.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/approvals"))
            .willReturn(
                aResponse()
                    .withStatus(status)
                    .withHeader("Content-Type", "application/json")
                    .withBody(body)));
  }

  @Test
  void itemsComeFromApprovalServiceWithOperatorToken() {
    approvalReturns(
        200,
        """
        {"items":[{"approvalId":"AP-1","changeRequestId":"CR-1","type":"PRODUCT",
          "targetService":"product-service","targetRef":"productImportBatch:PB-1","summary":"상품 일괄 등록 3건",
          "requesterId":"md-1","requestState":"SUBMITTED","reviewState":"PENDING","executionState":"NOT_STARTED",
          "deliveryState":"NOT_SENT","caseId":null,"submittedAt":"2026-11-03T10:00:00Z","expiresAt":null,
          "viewerCanDecide":false,"viewerDecideBlockedReason":"NO_ROLE"}],
         "page":{"nextCursor":"c2","size":1}}
        """);

    Res res = inbox("?approvalType=PRODUCT&reviewState=PENDING&size=20", OPERATOR);

    assertThat(res.status()).isEqualTo(200);
    JsonNode item = res.body().path("items").path(0);
    assertThat(item.path("dossierId").asString()).isEqualTo("AP-1");
    assertThat(item.path("approvalId").asString()).isEqualTo("AP-1");
    assertThat(item.path("approvalType").asString()).isEqualTo("PRODUCT");
    assertThat(item.path("targetRef").asString()).isEqualTo("productImportBatch:PB-1");
    assertThat(item.path("summary").asString()).isEqualTo("상품 일괄 등록 3건");
    assertThat(item.path("requestedBy").asString()).isEqualTo("md-1");
    assertThat(item.path("source").path("confidence").asString()).isEqualTo("DIRECT");
    assertThat(item.path("viewerCanDecide").asBoolean(true)).as("AP-3 판정 그대로").isFalse();
    assertThat(item.path("viewerDecideBlockedReason").asString()).isEqualTo("NO_ROLE");
    assertThat(res.body().path("page").path("nextCursor").asString()).isEqualTo("c2");
    assertThat(res.body().path("unavailableSources")).isEmpty();
    // 운영자 본인 토큰과 필터를 그대로 넘긴다
    approval.verify(
        getRequestedFor(urlPathEqualTo("/api/v1/approvals"))
            .withHeader("Authorization", equalTo("Bearer " + OPERATOR))
            .withQueryParam("type", equalTo("PRODUCT"))
            .withQueryParam("reviewState", equalTo("PENDING"))
            .withQueryParam("size", equalTo("20"))
            .withQueryParam("decidableByMe", absent()));
  }

  @Test
  void decidableByMeIsPassedToApprovalService() {
    approvalReturns(
        200,
        """
        {"items":[{"approvalId":"AP-9","type":"PRICE","viewerCanDecide":true}],
         "page":{"nextCursor":null,"size":1}}
        """);

    Res res = inbox("?decidableByMe=true", OPERATOR);

    assertThat(res.status()).isEqualTo(200);
    JsonNode item = res.body().path("items").path(0);
    assertThat(item.path("viewerCanDecide").asBoolean(false)).isTrue();
    assertThat(item.path("viewerDecideBlockedReason").isNull()).isTrue();
    approval.verify(
        getRequestedFor(urlPathEqualTo("/api/v1/approvals"))
            .withQueryParam("decidableByMe", equalTo("true")));
  }

  @Test
  void approvalServiceDownGivesEmptyListMarkedUnavailable() {
    approvalReturns(503, "{}");

    Res res = inbox("", OPERATOR);

    assertThat(res.status()).isEqualTo(200);
    assertThat(res.body().path("items")).isEmpty();
    assertThat(res.body().path("unavailableSources").path(0).asString())
        .isEqualTo("approval-service");
    assertThat(res.body().path("sources").path(0).path("confidence").asString())
        .isEqualTo("UNKNOWN");
  }

  @Test
  void approvalServiceRejectionIsForbidden() {
    approvalReturns(403, "{\"code\":\"FORBIDDEN\"}");
    assertThat(inbox("", TestJwt.customer("CU-1")).status()).isEqualTo(403);
    assertThat(inbox("", null).status()).isEqualTo(401);
  }
}
