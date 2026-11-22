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
 * 클레임·라이더 컴플레인 목록 + 화면 번호(0.18.0 — 2026-11-19, ADR-0070): 원천 목록은 직원 토큰 그대로, 번호는 같은 ID 한 번만, 번호 원천
 * 실패는 그 칸만 null + unavailableSources, 404 는 없음, 주 원천 403 은 그대로, 주 원천 장애는 빈 목록 + unavailableSources.
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
  @ConfigureWireMock(name = "claim", baseUrlProperties = "pmart.bff.claim-base-url"),
  @ConfigureWireMock(name = "case", baseUrlProperties = "pmart.bff.case-base-url"),
  @ConfigureWireMock(name = "execution", baseUrlProperties = "pmart.bff.execution-base-url"),
  @ConfigureWireMock(name = "order", baseUrlProperties = "pmart.bff.order-base-url"),
  @ConfigureWireMock(name = "rider", baseUrlProperties = "pmart.bff.rider-base-url"),
  @ConfigureWireMock(name = "hr", baseUrlProperties = "pmart.bff.hr-base-url")
})
class RecordListViewsIT {

  static final String CS = TestJwt.operator("op-cs", List.of("CS_AGENT"), List.of());

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;

  @InjectWireMock("claim")
  WireMockServer claim;

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
    for (WireMockServer w : List.of(claim, cases, execution, order, rider, hr)) {
      w.resetAll();
    }
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1/backoffice")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
  }

  record Res(int status, JsonNode body) {}

  Res get(String path) {
    var e =
        http.get().uri(path).headers(h -> h.setBearerAuth(CS)).retrieve().toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  static ResponseDefinitionBuilder json(int s, String b) {
    return aResponse().withStatus(s).withHeader("Content-Type", "application/json").withBody(b);
  }

  /**
   * 0.23.0(claim 0.10.0 CL-7·CL-4): 주문번호 → order 의 orderId 로 claim(번호는 다시 부르지 않음), 없는 주문번호는 claim 안
   * 부르고 빈 목록, orderId 와 같이 주면 400, customerId 그대로, 줄마다 claimSeq.
   */
  @Test
  void claimsFilterByOrderNumberOrCustomerAndCarrySeq() {
    order.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/orders/lookups"))
            .withQueryParam("orderNumber", equalTo("74939255"))
            .willReturn(
                json(200, "{\"items\":[{\"orderId\":\"ORD-1\",\"orderNumber\":\"74939255\"}]}")));
    claim.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/claims"))
            .willReturn(
                json(
                    200,
                    """
                    {"items":[{"claimId":"CL-1","claimSeq":2,"orderId":"ORD-1","type":"RETURN","state":"REQUESTED","openedAt":"2026-11-19T01:00:00Z"}],
                     "page":{"nextCursor":null}}""")));

    Res r = get("/views/claims?orderNumber=74939255");

    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    JsonNode c = r.body().path("items").get(0);
    assertThat(c.path("claimSeq").asInt()).isEqualTo(2);
    assertThat(c.path("orderNumber").asString()).isEqualTo("74939255");
    claim.verify(
        getRequestedFor(urlPathEqualTo("/api/v1/claims"))
            .withQueryParam("orderId", equalTo("ORD-1")));
    order.verify(0, getRequestedFor(urlPathEqualTo("/api/v1/orders/lookups/ORD-1")));

    order.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/orders/lookups"))
            .withQueryParam("orderNumber", equalTo("11111111"))
            .willReturn(json(200, "{\"items\":[]}")));
    claim.resetRequests();
    Res none = get("/views/claims?orderNumber=11111111");
    assertThat(none.status()).isEqualTo(200);
    assertThat(none.body().path("items")).isEmpty();
    claim.verify(0, getRequestedFor(urlPathEqualTo("/api/v1/claims")));

    assertThat(get("/views/claims?orderNumber=74939255&orderId=ORD-1").status())
        .as("둘 다 주면 400")
        .isEqualTo(400);

    order.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/orders/lookups/ORD-1"))
            .willReturn(json(200, "{\"orderId\":\"ORD-1\",\"orderNumber\":\"74939255\"}")));
    Res byCustomer = get("/views/claims?customerId=CU-1");
    assertThat(byCustomer.status()).isEqualTo(200);
    assertThat(byCustomer.body().path("items").get(0).path("claimSeq").asInt()).isEqualTo(2);
    claim.verify(
        getRequestedFor(urlPathEqualTo("/api/v1/claims"))
            .withQueryParam("customerId", equalTo("CU-1")));
    // 0.24.0 BF-4: 센터 → claim 0.13.0 CL-8 mfcId 그대로
    assertThat(get("/views/claims?centerId=MFC-MOKPO-01").status()).isEqualTo(200);
    claim.verify(
        getRequestedFor(urlPathEqualTo("/api/v1/claims"))
            .withQueryParam("mfcId", equalTo("MFC-MOKPO-01")));
  }

  @Test
  void claimsGetOrderNumbersOncePerOrder() {
    claim.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/claims"))
            .willReturn(
                json(
                    200,
                    """
                    {"items":[{"claimId":"CL-1","orderId":"ORD-1","type":"RETURN","state":"REQUESTED","openedAt":"2026-11-19T01:00:00Z",
                      "channel":"CS","refund":{"state":"IN_PROGRESS","requestedAmount":3000,"refundedAmount":0}},
                     {"claimId":"CL-2","orderId":"ORD-1","type":"CANCEL","state":"COMPLETED","openedAt":"2026-11-19T02:00:00Z"},
                     {"claimId":"CL-3","orderId":"ORD-404","type":"RETURN","state":"REQUESTED","openedAt":"2026-11-19T03:00:00Z"}],
                     "page":{"nextCursor":"n2"}}""")));
    order.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/orders/lookups/ORD-1"))
            .willReturn(json(200, "{\"orderId\":\"ORD-1\",\"orderNumber\":\"74939255\"}")));
    order.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/orders/lookups/ORD-404"))
            .willReturn(json(404, "{\"code\":\"NOT_FOUND\"}")));

    Res r = get("/views/claims?state=REQUESTED&size=10");

    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    JsonNode items = r.body().path("items");
    assertThat(items.size()).isEqualTo(3);
    assertThat(items.get(0).path("orderNumber").asString()).isEqualTo("74939255");
    assertThat(items.get(1).path("orderNumber").asString()).isEqualTo("74939255");
    assertThat(items.get(2).path("orderNumber").isNull()).as("404 — 번호 없음").isTrue();
    assertThat(items.get(0).path("type").asString()).isEqualTo("RETURN");
    assertThat(items.get(0).path("channel").asString()).as("claim 0.6.0 값 그대로").isEqualTo("CS");
    assertThat(items.get(0).path("refund").path("state").asString()).isEqualTo("IN_PROGRESS");
    assertThat(items.get(1).path("refund").isNull()).isTrue();
    assertThat(r.body().path("unavailableSources")).isEmpty();
    assertThat(r.body().path("page").path("nextCursor").asString()).isEqualTo("n2");
    order.verify(1, getRequestedFor(urlPathEqualTo("/api/v1/orders/lookups/ORD-1")));
    claim.verify(
        getRequestedFor(urlPathEqualTo("/api/v1/claims"))
            .withHeader("Authorization", equalTo("Bearer " + CS))
            .withQueryParam("state", equalTo("REQUESTED"))
            .withQueryParam("size", equalTo("10")));
  }

  @Test
  void orderOutageLeavesNumbersEmptyAndClaimRejectionPassesThrough() {
    claim.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/claims"))
            .willReturn(
                json(
                    200,
                    """
                    {"items":[{"claimId":"CL-1","orderId":"ORD-1"},{"claimId":"CL-2","orderId":"ORD-2"}],
                     "page":{"nextCursor":null}}""")));
    order.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/orders/lookups/ORD-1"))
            .willReturn(aResponse().withStatus(503)));

    Res r = get("/views/claims");

    assertThat(r.status()).isEqualTo(200);
    assertThat(r.body().path("items").get(0).path("orderNumber").isNull()).isTrue();
    assertThat(r.body().path("unavailableSources").toString()).contains("order-service");
    order.verify(0, getRequestedFor(urlPathEqualTo("/api/v1/orders/lookups/ORD-2")));

    claim.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/claims"))
            .willReturn(json(403, "{\"code\":\"FORBIDDEN\"}")));
    assertThat(get("/views/claims").status()).as("claim 403 그대로").isEqualTo(403);

    claim.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/claims")).willReturn(aResponse().withStatus(503)));
    Res down = get("/views/claims");
    assertThat(down.status()).isEqualTo(200);
    assertThat(down.body().path("items")).isEmpty();
    assertThat(down.body().path("unavailableSources").toString()).contains("claim-service");
  }

  @Test
  void riderComplaintsGetDeliveryOrderRiderAndStaffLabels() {
    cases.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/rider-complaints"))
            .willReturn(
                json(
                    200,
                    """
                    {"items":[{"complaintId":"RC-1","riderId":"RD-1","deliveryId":"DLV-1","orderId":"ORD-1",
                       "complaintType":"REPORT","source":"CS_AGENT","recordedBy":"op-cs","recordedAt":"2026-11-19T01:00:00Z"},
                      {"complaintId":"RC-2","riderId":"RD-1","deliveryId":"DLV-1","orderId":"ORD-1",
                       "complaintType":"DAMAGE","source":"CS_AGENT","recordedBy":"op-cs","recordedAt":"2026-11-19T02:00:00Z"}],
                     "page":{"nextCursor":null}}""")));
    execution.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/deliveries"))
            .withQueryParam("deliveryIds", equalTo("DLV-1"))
            .willReturn(
                json(
                    200,
                    """
                    {"items":[{"deliveryId":"DLV-1","deliveryNumber":"74939255-1","orderNumber":"74939255"}]}""")));
    rider.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/riders"))
            .willReturn(
                json(
                    200,
                    """
                    {"items":[{"riderId":"RD-1","riderNumber":"482913","nameMasked":"김*수"}]}""")));
    hr.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/hr/employee-labels"))
            .willReturn(
                json(
                    200,
                    """
                    {"items":[{"operatorId":"op-cs","name":"이상담","employeeNo":"P2026-00007"}]}""")));

    Res r = get("/views/rider-complaints?riderId=RD-1");

    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    JsonNode c = r.body().path("items").get(0);
    assertThat(c.path("deliveryNumber").asString()).isEqualTo("74939255-1");
    assertThat(c.path("orderNumber").asString()).isEqualTo("74939255");
    assertThat(c.path("rider").path("riderNumber").asString()).isEqualTo("482913");
    assertThat(c.path("rider").path("nameMasked").asString()).isEqualTo("김*수");
    assertThat(c.path("recordedByLabel").path("name").asString()).isEqualTo("이상담");
    assertThat(c.path("recordedByLabel").path("employeeNo").asString()).isEqualTo("P2026-00007");
    assertThat(c.path("recordedVia").asString()).isEqualTo("CS_AGENT");
    assertThat(c.has("note")).as("메모 없음").isFalse();
    assertThat(r.body().path("unavailableSources")).isEmpty();
    execution.verify(
        1,
        getRequestedFor(urlPathEqualTo("/api/v1/deliveries"))
            .withQueryParam("deliveryIds", equalTo("DLV-1")));
    rider.verify(1, getRequestedFor(urlPathEqualTo("/api/v1/riders")));
    hr.verify(1, getRequestedFor(urlPathEqualTo("/api/v1/hr/employee-labels")));
    cases.verify(
        getRequestedFor(urlPathEqualTo("/api/v1/rider-complaints"))
            .withHeader("Authorization", equalTo("Bearer " + CS))
            .withQueryParam("riderId", equalTo("RD-1")));
  }
}
