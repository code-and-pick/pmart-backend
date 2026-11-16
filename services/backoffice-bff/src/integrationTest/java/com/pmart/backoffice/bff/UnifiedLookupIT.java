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
import java.util.ArrayList;
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
 * 통합 조회(0.3.0 — 2026-11-16, BO-11): 주문 하나로 주문·배송·배차·사건을 원천마다 따로(직원 토큰 전달), 원천 장애는
 * unavailableSources, 개인정보 필드는 싣지 않음, 배송·사건 번호로 찾으면 그 주문으로 이음, 고객 ID 는 주문 목록, 권한 없는 역할 403, 원천 없는
 * 종류 422.
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
  @ConfigureWireMock(name = "order", baseUrlProperties = "pmart.bff.order-base-url"),
  @ConfigureWireMock(name = "execution", baseUrlProperties = "pmart.bff.execution-base-url"),
  @ConfigureWireMock(name = "dispatch", baseUrlProperties = "pmart.bff.dispatch-base-url"),
  @ConfigureWireMock(name = "claim", baseUrlProperties = "pmart.bff.claim-base-url"),
  @ConfigureWireMock(name = "case", baseUrlProperties = "pmart.bff.case-base-url")
})
class UnifiedLookupIT {

  static final String CS = TestJwt.operator("op-cs", List.of("CS_AGENT"), List.of());
  static final String OPS = TestJwt.operator("op-ops", List.of("RIDER_OPS"), List.of());
  static final String MD = TestJwt.operator("op-md", List.of("MD"), List.of());

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;

  @InjectWireMock("order")
  WireMockServer order;

  @InjectWireMock("execution")
  WireMockServer execution;

  @InjectWireMock("dispatch")
  WireMockServer dispatch;

  @InjectWireMock("claim")
  WireMockServer claim;

  @InjectWireMock("case")
  WireMockServer cases;

  RestClient http;

  @BeforeEach
  void setUp() {
    for (WireMockServer w : List.of(order, execution, dispatch, claim, cases)) {
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

  Res lookup(String token, String query) {
    var e =
        http.get()
            .uri("/views/unified-lookup?" + query)
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
    order.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/orders/lookups/ORD-1"))
            .willReturn(
                json(
                    200,
                    """
                    {"orderId":"ORD-1","customerId":"CU-1","status":"PREPARING","version":2,"mfcId":"MFC-1",
                     "deliveryMode":"SINGLE","confirmedAt":"2026-11-16T01:00:00Z",
                     "amounts":{"total":30830},"payment":{"paymentAttemptId":"pay-1","approvedAmount":30830},
                     "shippingAddress":{"recipientName":"홍*동","phone":"010-****-5678","detail":"***"},
                     "shippingAddressMasked":true,"deliveryNote":"가림","deliveryNoteMasked":true,
                     "lines":[{"sku":"A"},{"sku":"B"}],"fulfillmentIssues":[]}""")));
    order.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/orders/lookups"))
            .withQueryParam("customerId", equalTo("CU-1"))
            .willReturn(
                json(
                    200,
                    """
                    {"items":[{"orderId":"ORD-1","customerId":"CU-1","status":"PREPARING","totalAmount":30830,
                               "receiverPhoneMasked":"010-****-5678"},
                              {"orderId":"ORD-2","customerId":"CU-1","status":"COMPLETED","totalAmount":9000}],
                     "page":{"size":2}}""")));
    execution.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/deliveries"))
            .withQueryParam("orderId", equalTo("ORD-1"))
            .willReturn(
                json(
                    200,
                    """
                    {"items":[{"deliveryId":"DLV-1","type":"FORWARD","orderId":"ORD-1","mfcId":"MFC-1",
                               "riderId":null,"state":"CREATED","createdAt":"2026-11-16T01:05:00Z"}],
                     "page":{"size":1}}""")));
    execution.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/deliveries/DLV-1"))
            .willReturn(
                json(
                    200,
                    """
                    {"deliveryId":"DLV-1","type":"FORWARD","orderId":"ORD-1","state":"CREATED"}""")));
    dispatch.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/deliveries/DLV-1/dispatch"))
            .willReturn(
                json(
                    200,
                    """
                    {"deliveryId":"DLV-1","orderId":"ORD-1","state":"DISPATCH_FAILED",
                     "attempts":[{"trigger":"INITIAL","state":"FAILED","failureReason":"SYSTEM_FAILURE"}]}""")));
    claim.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/claims"))
            .willReturn(json(503, "{\"code\":\"DEPENDENCY_UNAVAILABLE\"}")));
    cases.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/cases"))
            .withQueryParam("subjectRef", equalTo("ORD-1"))
            .willReturn(json(200, "{\"items\":[]}")));
    String caseJson =
        """
        {"caseId":"CASE-1","kind":"DISPATCH_FAILED","state":"OPEN","priority":"P2",
         "summary":"배차 실패 1번째 — 시스템 장애","subjectType":"DELIVERY","subjectRef":"DLV-1",
         "assigneeGroup":"DELIVERY_OPS","refs":[{"refType":"deliveryId","refId":"DLV-1"}]}""";
    cases.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/cases"))
            .withQueryParam("subjectRef", equalTo("DLV-1"))
            .willReturn(json(200, "{\"items\":[" + caseJson + "]}")));
    cases.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/cases/CASE-1")).willReturn(json(200, caseJson)));
    // 닫힌 사건(state=CLOSED) — 주문에는 지난 사건 하나, 배송에는 없음(나중에 넣은 스텁이 먼저 맞는다)
    cases.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/cases"))
            .withQueryParam("subjectRef", equalTo("ORD-1"))
            .withQueryParam("state", equalTo("CLOSED"))
            .willReturn(
                json(
                    200,
                    """
                    {"items":[{"caseId":"CASE-0","kind":"RECONCILIATION","state":"CLOSED","priority":"P2",
                               "subjectType":"ORDER","subjectRef":"ORD-1"}]}""")));
    cases.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/cases"))
            .withQueryParam("subjectRef", equalTo("DLV-1"))
            .withQueryParam("state", equalTo("CLOSED"))
            .willReturn(json(200, "{\"items\":[]}")));
    // 고객 문의 — 다른 주문 것이 섞여 와도(예전 case) 거른다, 제목은 싣지 않는다. RIDER_OPS 는 403
    cases.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/inquiries"))
            .withQueryParam("orderId", equalTo("ORD-1"))
            .willReturn(
                json(
                    200,
                    """
                    {"items":[{"inquiryId":"INQ-1","category":"ORDER","orderId":"ORD-1","title":"홍길동 집 앞에 두세요",
                               "status":"ANSWERED","origin":"INQUIRY_PAGE","createdAt":"2026-11-16T02:00:00Z"},
                              {"inquiryId":"INQ-X","category":"ORDER","orderId":"ORD-9","status":"RECEIVED"}]}""")));
    cases.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/inquiries"))
            .withHeader("Authorization", equalTo("Bearer " + OPS))
            .willReturn(json(403, "{\"code\":\"FORBIDDEN\"}")));
  }

  static List<String> kinds(JsonNode body) {
    List<String> out = new ArrayList<>();
    body.path("matches")
        .forEach(m -> out.add(m.path("kind").asString() + ":" + m.path("id").asString()));
    return out;
  }

  @Test
  void orderComposesSourcesSeparatelyWithStaffTokenAndNoPii() {
    Res r = lookup(CS, "q=ORD-1&kind=ORDER");
    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    assertThat(kinds(r.body()))
        .containsExactly(
            "ORDER:ORD-1",
            "DELIVERY:DLV-1",
            "DISPATCH:DLV-1",
            "CASE:CASE-0",
            "CASE:CASE-1",
            "INQUIRY:INQ-1");
    assertThat(r.body().toString()).as("다른 주문 문의·고객이 쓴 제목은 없다").doesNotContain("INQ-X", "홍길동");
    assertThat(r.body().path("resolved").path("orderId").asString()).isEqualTo("ORD-1");
    // 클레임 원천 장애 — 0건으로 바꾸지 않고 unavailableSources
    assertThat(r.body().path("unavailableSources").toString()).contains("claim-service");
    assertThat(r.body().path("sources").toString()).contains("order-service", "UNAVAILABLE");

    JsonNode orderMatch = r.body().path("matches").get(0);
    assertThat(orderMatch.path("source").path("system").asString()).isEqualTo("order-service");
    assertThat(orderMatch.path("summary").path("lineCount").asInt()).isEqualTo(2);
    assertThat(orderMatch.path("summary").path("payment").path("paymentAttemptId").asString())
        .isEqualTo("pay-1");
    assertThat(r.body().toString())
        .as("개인정보 필드는 싣지 않는다(가린 값도)")
        .doesNotContain("shippingAddress", "deliveryNote", "010-", "홍*동");
    assertThat(r.body().path("matches").get(2).path("summary").path("state").asString())
        .isEqualTo("DISPATCH_FAILED");

    // 원천에는 호출한 직원의 토큰이 간다
    order.verify(
        getRequestedFor(urlPathEqualTo("/api/v1/orders/lookups/ORD-1"))
            .withHeader("Authorization", equalTo("Bearer " + CS)));
    cases.verify(
        getRequestedFor(urlPathEqualTo("/api/v1/cases"))
            .withHeader("Authorization", equalTo("Bearer " + CS)));
  }

  @Test
  void deliveryAndCaseIdsResolveToTheirOrder() {
    Res byDelivery = lookup(OPS, "q=DLV-1");
    assertThat(byDelivery.status()).isEqualTo(200);
    assertThat(byDelivery.body().path("resolved").path("kind").asString()).isEqualTo("DELIVERY");
    assertThat(byDelivery.body().path("resolved").path("orderId").asString()).isEqualTo("ORD-1");
    assertThat(kinds(byDelivery.body())).contains("ORDER:ORD-1", "DISPATCH:DLV-1", "CASE:CASE-1");
    assertThat(kinds(byDelivery.body())).noneMatch(k -> k.startsWith("INQUIRY:"));
    assertThat(byDelivery.body().path("unavailableSources").toString())
        .as("라이더 운영은 문의를 볼 수 없다 — 사건 원천과 따로 표시")
        .contains("case-service/inquiries")
        .doesNotContain("\"case-service\"");

    Res byCase = lookup(OPS, "q=CASE-1");
    assertThat(byCase.status()).isEqualTo(200);
    assertThat(byCase.body().path("resolved").path("orderId").asString()).isEqualTo("ORD-1");
    assertThat(kinds(byCase.body()))
        .as("사건이 맨 앞, 같은 사건은 한 번")
        .startsWith("CASE:CASE-1")
        .containsOnlyOnce("CASE:CASE-1")
        .contains("ORDER:ORD-1", "DELIVERY:DLV-1");
  }

  @Test
  void customerIdListsOrdersOnlyWithoutPhone() {
    Res r = lookup(CS, "q=CU-1");
    assertThat(r.status()).isEqualTo(200);
    assertThat(kinds(r.body())).containsExactly("ORDER:ORD-1", "ORDER:ORD-2");
    assertThat(r.body().path("resolved").path("orderId").isNull()).isTrue();
    assertThat(r.body().toString()).doesNotContain("receiverPhoneMasked", "010-");
  }

  @Test
  void wrongRoleIs403AndUnsupportedKindsAre422() {
    assertThat(lookup(MD, "q=ORD-1").status()).isEqualTo(403);
    Res trip = lookup(CS, "q=TRIP-1&kind=TRIP");
    assertThat(trip.status()).isEqualTo(422);
    assertThat(trip.body().path("code").asString()).isEqualTo("UNSUPPORTED_LOOKUP");
    assertThat(lookup(CS, "q=01012345678").status()).as("전화번호 등 모르는 값").isEqualTo(422);
    order.verify(0, getRequestedFor(urlPathEqualTo("/api/v1/orders/lookups/ORD-1")));
  }
}
