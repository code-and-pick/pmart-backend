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
 * 고객 찾기·상세(0.22.0 — BF-2, customer 0.11.0 CU-3·CU-4, 2026-11-20): 원천은 직원 토큰 그대로, 하나만 넣어야 함, 주문번호 →
 * customerIds, 주 원천 403 그대로·장애는 빈 목록 + unavailableSources, 상세 머리 404·칸 실패는 그 칸만 null, 제목·요약 글 없음.
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
  @ConfigureWireMock(name = "customer", baseUrlProperties = "pmart.bff.customer-base-url"),
  @ConfigureWireMock(name = "order", baseUrlProperties = "pmart.bff.order-base-url"),
  @ConfigureWireMock(name = "case", baseUrlProperties = "pmart.bff.case-base-url"),
  @ConfigureWireMock(name = "hr", baseUrlProperties = "pmart.bff.hr-base-url"),
  @ConfigureWireMock(name = "claim", baseUrlProperties = "pmart.bff.claim-base-url"),
  @ConfigureWireMock(name = "execution", baseUrlProperties = "pmart.bff.execution-base-url"),
  @ConfigureWireMock(name = "audit", baseUrlProperties = "pmart.bff.audit-base-url")
})
class CustomerViewsIT {

  static final String CS = TestJwt.operator("op-cs", List.of("CS_AGENT"), List.of());
  static final String ADMIN = TestJwt.operator("op-admin", List.of("OPERATOR_ADMIN"), List.of());

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;

  @InjectWireMock("customer")
  WireMockServer customer;

  @InjectWireMock("order")
  WireMockServer order;

  @InjectWireMock("case")
  WireMockServer cases;

  @InjectWireMock("hr")
  WireMockServer hr;

  @InjectWireMock("claim")
  WireMockServer claim;

  @InjectWireMock("execution")
  WireMockServer execution;

  @InjectWireMock("audit")
  WireMockServer audit;

  RestClient http;

  @BeforeEach
  void setUp() {
    for (WireMockServer w : List.of(customer, order, cases, hr, claim, execution, audit)) {
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
    return get(CS, path);
  }

  Res get(String token, String path) {
    var e =
        http.get().uri(path).headers(h -> h.setBearerAuth(token)).retrieve().toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  static ResponseDefinitionBuilder json(int s, String b) {
    return aResponse().withStatus(s).withHeader("Content-Type", "application/json").withBody(b);
  }

  static final String KIM =
      """
      {"items":[{"customerId":"CU-1","customerNumber":"482913","nameMasked":"김*수","phoneMasked":"010-****-5678","status":"ACTIVE"}]}""";

  @Test
  void searchByCustomerNumberPassesTokenAndMaskedValues() {
    customer.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/customers"))
            .withQueryParam("customerNumber", equalTo("482913"))
            .willReturn(json(200, KIM)));

    Res r = get("/views/customer-search?customerNumber=482913");

    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    JsonNode c = r.body().path("items").get(0);
    assertThat(c.path("customerId").asString()).isEqualTo("CU-1");
    assertThat(c.path("customerNumber").asString()).isEqualTo("482913");
    assertThat(c.path("nameMasked").asString()).isEqualTo("김*수");
    assertThat(c.path("phoneMasked").asString()).isEqualTo("010-****-5678");
    assertThat(r.body().path("unavailableSources")).isEmpty();
    customer.verify(
        getRequestedFor(urlPathEqualTo("/api/v1/customers"))
            .withHeader("Authorization", equalTo("Bearer " + CS)));
  }

  @Test
  void searchByOrderNumberGoesThroughOrderThenCustomerIds() {
    order.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/orders/lookups"))
            .withQueryParam("orderNumber", equalTo("74939255"))
            .willReturn(
                json(200, "{\"items\":[{\"orderId\":\"ORD-1\",\"customerId\":\"CU-1\"}]}")));
    customer.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/customers"))
            .withQueryParam("customerIds", equalTo("CU-1"))
            .willReturn(json(200, KIM)));

    Res r = get("/views/customer-search?orderNumber=74939255");

    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    assertThat(r.body().path("items").get(0).path("customerId").asString()).isEqualTo("CU-1");

    // 없는 주문번호 — customer 는 부르지 않고 빈 목록
    order.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/orders/lookups"))
            .withQueryParam("orderNumber", equalTo("11111111"))
            .willReturn(json(200, "{\"items\":[]}")));
    customer.resetRequests();
    Res none = get("/views/customer-search?orderNumber=11111111");
    assertThat(none.status()).isEqualTo(200);
    assertThat(none.body().path("items")).isEmpty();
    customer.verify(0, getRequestedFor(urlPathEqualTo("/api/v1/customers")));
  }

  @Test
  void searchNeedsExactlyOneValueAndPassesRejectionsAndMarksOutage() {
    assertThat(get("/views/customer-search").status()).isEqualTo(400);
    assertThat(get("/views/customer-search?customerNumber=482913&phone=01012345678").status())
        .isEqualTo(400);

    customer.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/customers"))
            .willReturn(json(403, "{\"code\":\"FORBIDDEN\"}")));
    assertThat(get("/views/customer-search?phone=01012345678").status())
        .as("customer 403 그대로")
        .isEqualTo(403);

    customer.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/customers")).willReturn(aResponse().withStatus(503)));
    Res down = get("/views/customer-search?phone=01012345678");
    assertThat(down.status()).isEqualTo(200);
    assertThat(down.body().path("items")).isEmpty();
    assertThat(down.body().path("unavailableSources").toString()).contains("customer-service");
  }

  @Test
  void detailHasMaskedHeadAndCellsWithoutFreeText() {
    customer.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/customers/CU-1"))
            .willReturn(
                json(
                    200,
                    """
                    {"customerId":"CU-1","customerNumber":"482913","nameMasked":"김*수","phoneMasked":"010-****-5678",
                     "emailMasked":"a***@k***","status":"ACTIVE","socialProvider":"KAKAO","phoneVerified":true,
                     "joinedAt":"2026-11-01T01:00:00Z"}""")));
    order.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/orders/lookups"))
            .withQueryParam("customerId", equalTo("CU-1"))
            .willReturn(
                json(
                    200,
                    """
                    {"items":[{"orderId":"ORD-1","orderNumber":"74939255","customerId":"CU-1","status":"DELIVERED",
                      "confirmedAt":"2026-11-19T01:00:00Z","mfcId":"MFC-1","deliveryMode":"SINGLE","totalAmount":15000,
                      "itemCount":3,"openFulfillmentIssues":0,"receiverPhoneMasked":"010-****-1234"}],
                     "page":{"page":0,"size":10,"hasNext":true}}""")));
    cases.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/inquiries"))
            .withQueryParam("customerId", equalTo("CU-1"))
            .willReturn(
                json(
                    200,
                    """
                    {"items":[{"inquiryId":"INQ-1","inquiryNumber":"Q-1119-0001","category":"DELIVERY","status":"OPEN",
                      "orderId":"ORD-1","title":"상자가 젖었어요","origin":"INQUIRY_PAGE","createdAt":"2026-11-19T02:00:00Z","version":1,
                      "lastAnsweredBy":"op-cs"}],
                     "page":{"nextCursor":null}}""")));
    cases.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/cs-intakes"))
            .withQueryParam("customerRef", equalTo("CU-1"))
            .willReturn(
                json(
                    200,
                    """
                    {"items":[{"intakeId":"IN-1","caseId":"CASE-1","channel":"PHONE","category":"DELIVERY",
                      "summary":"고객이 전화로 젖은 상자 문의","receivedBy":"op-cs","receivedAt":"2026-11-19T03:00:00Z",
                      "callerRelation":"SELF","linked":false,"customerRef":"CU-1"}],
                     "page":{"nextCursor":"c2"}}""")));
    hr.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/hr/employee-labels"))
            .willReturn(
                json(
                    200,
                    """
                    {"items":[{"operatorId":"op-cs","name":"이상담","employeeNo":"P2026-00007"}]}""")));
    claim.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/claims"))
            .withQueryParam("customerId", equalTo("CU-1"))
            .willReturn(
                json(
                    200,
                    """
                    {"items":[{"claimId":"CL-1","claimSeq":1,"orderId":"ORD-1","type":"RETURN","state":"REQUESTED",
                      "reason":"DAMAGED","channel":"CS","openedAt":"2026-11-19T04:00:00Z",
                      "refund":{"state":"IN_PROGRESS","requestedAmount":5000,"refundedAmount":0}},
                     {"claimId":"CL-2","claimSeq":1,"orderId":"ORD-9","type":"CANCEL","state":"COMPLETED",
                      "openedAt":"2026-11-18T04:00:00Z","closedAt":"2026-11-18T05:00:00Z"}],
                     "page":{"nextCursor":"c3"}}""")));
    order.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/orders/lookups/ORD-9"))
            .willReturn(json(200, "{\"orderId\":\"ORD-9\",\"orderNumber\":\"51234567\"}")));

    claim.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/claims/counts"))
            .withQueryParam("customerId", equalTo("CU-1"))
            .withQueryParam("by", equalTo("type"))
            .willReturn(
                json(
                    200,
                    "{\"counts\":[{\"key\":\"CANCEL\",\"count\":1,\"capped\":false},{\"key\":\"RETURN\",\"count\":3,\"capped\":false}]}")));
    execution.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/deliveries"))
            .withQueryParam("orderId", equalTo("ORD-1"))
            .willReturn(
                json(
                    200,
                    """
                    {"items":[{"deliveryId":"DLV-1","deliveryNumber":"D74939255","orderId":"ORD-1","orderNumber":"74939255",
                      "type":"FORWARD","mfcId":"MFC-1","state":"DELIVERED","riderId":"RD-1","createdAt":"2026-11-19T01:10:00Z",
                      "completedAt":"2026-11-19T02:00:00Z","completionMethod":"HANDED","dropAddress":"부산 어딘가"}]}""")));

    cases.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/customer-notes"))
            .willReturn(json(200, "{\"items\":[],\"page\":{\"nextCursor\":null}}")));

    Res r = get("/views/customer/CU-1");

    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    JsonNode head = r.body().path("customer");
    assertThat(head.path("customerNumber").asString()).isEqualTo("482913");
    assertThat(head.path("nameMasked").asString()).isEqualTo("김*수");
    assertThat(head.path("socialProvider").asString()).isEqualTo("KAKAO");
    assertThat(head.path("phoneVerified").asBoolean()).isTrue();

    JsonNode orders = r.body().path("orders");
    assertThat(orders.path("hasMore").asBoolean()).isTrue();
    assertThat(orders.path("items").get(0).path("orderNumber").asString()).isEqualTo("74939255");
    assertThat(orders.path("items").get(0).path("totalAmount").asLong()).isEqualTo(15000);
    assertThat(orders.path("items").get(0).has("receiverPhoneMasked")).as("연락처 없음").isFalse();

    JsonNode inq = r.body().path("inquiries");
    assertThat(inq.path("hasMore").asBoolean()).isFalse();
    assertThat(inq.path("items").get(0).path("inquiryNumber").asString()).isEqualTo("Q-1119-0001");
    assertThat(inq.path("items").get(0).has("title")).as("제목 없음").isFalse();
    assertThat(inq.path("items").get(0).path("lastAnsweredBy").asString()).isEqualTo("op-cs");
    assertThat(inq.path("items").get(0).path("lastAnsweredByLabel").path("name").asString())
        .as("담당 상담원 이름(0.27.0) — 접수 칸과 같은 hr 한 번")
        .isEqualTo("이상담");
    hr.verify(1, getRequestedFor(urlPathEqualTo("/api/v1/hr/employee-labels")));

    JsonNode in = r.body().path("csIntakes");
    assertThat(in.path("hasMore").asBoolean()).isTrue();
    assertThat(in.path("items").get(0).path("channel").asString()).isEqualTo("PHONE");
    assertThat(in.path("items").get(0).has("summary")).as("요약 글 없음").isFalse();
    assertThat(in.path("items").get(0).path("receivedByLabel").path("name").asString())
        .isEqualTo("이상담");
    JsonNode cl = r.body().path("claims");
    assertThat(cl.path("hasMore").asBoolean()).isTrue();
    assertThat(cl.path("items").get(0).path("claimSeq").asInt()).isEqualTo(1);
    assertThat(cl.path("items").get(0).path("orderNumber").asString())
        .as("주문 칸 값 — order 단건 안 부름")
        .isEqualTo("74939255");
    assertThat(cl.path("items").get(0).path("refundState").asString()).isEqualTo("IN_PROGRESS");
    assertThat(cl.path("items").get(0).path("reason").asString())
        .as("사유 코드(0.25.0)")
        .isEqualTo("DAMAGED");
    assertThat(cl.path("items").get(0).path("refundRequestedAmount").asLong()).isEqualTo(5000);
    assertThat(cl.path("items").get(0).path("refundedAmount").asLong()).isZero();
    // 0.25.0 BF-5: 클레임 종류별 수·배송 이력, 열람 기록은 관리자만(상담원은 부르지 않음)
    assertThat(r.body().path("claimCounts").path("counts").toString())
        .contains("RETURN")
        .contains("3");
    JsonNode dl = r.body().path("deliveries").path("items");
    assertThat(dl).hasSize(1);
    assertThat(dl.get(0).path("deliveryNumber").asString()).isEqualTo("D74939255");
    assertThat(dl.get(0).path("state").asString()).isEqualTo("DELIVERED");
    assertThat(dl.get(0).path("mfcId").asString()).isEqualTo("MFC-1");
    assertThat(dl.get(0).has("dropAddress")).as("주소 없음").isFalse();
    assertThat(r.body().path("piiAccessLogs").isNull()).isTrue();
    audit.verify(0, WireMock.anyRequestedFor(WireMock.anyUrl()));
    assertThat(cl.path("items").get(1).path("orderNumber").asString()).isEqualTo("51234567");
    assertThat(cl.path("items").get(1).path("refundState").isNull()).isTrue();
    order.verify(0, getRequestedFor(urlPathEqualTo("/api/v1/orders/lookups/ORD-1")));
    assertThat(r.body().path("unavailableSources")).isEmpty();
    for (WireMockServer w : List.of(customer, order, cases, claim)) {
      w.verify(
          WireMock.anyRequestedFor(WireMock.anyUrl())
              .withHeader("Authorization", equalTo("Bearer " + CS)));
    }
  }

  /** 0.25.0 BF-5: 관리자는 열람 기록(이름 · 사번), 배송 원천 장애면 배송 칸만 null. */
  @Test
  void adminSeesPiiAccessLogsAndDeliveryOutageIsOnlyThatCell() {
    customer.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/customers/CU-1"))
            .willReturn(json(200, "{\"customerId\":\"CU-1\",\"status\":\"ACTIVE\"}")));
    order.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/orders/lookups"))
            .willReturn(
                json(
                    200,
                    "{\"items\":[{\"orderId\":\"ORD-1\"},{\"orderId\":\"ORD-2\"}],\"page\":{\"hasNext\":false}}")));
    execution.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/deliveries")).willReturn(aResponse().withStatus(503)));
    audit.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/pii-access-logs"))
            .withQueryParam("subjectType", equalTo("CUSTOMER"))
            .withQueryParam("subjectRef", equalTo("CU-1"))
            .willReturn(
                json(
                    200,
                    """
                    {"items":[{"piiAccessLogId":"PA-1","occurredAt":"2026-11-23T01:00:00Z","actor":{"actorId":"op-cs","actorType":"OPERATOR"},
                      "subjectType":"CUSTOMER","subjectRef":"CU-1","accessType":"VIEW","fields":["NAME","PHONE"],"purpose":"CS_CALLBACK","screen":"customer-detail"}],
                     "page":{"nextCursor":"n1"}}""")));
    hr.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/hr/employee-labels"))
            .willReturn(
                json(
                    200,
                    "{\"items\":[{\"operatorId\":\"op-cs\",\"name\":\"이상담\",\"employeeNo\":\"P2026-00007\"}]}")));

    cases.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/customer-notes"))
            .withQueryParam("customerId", equalTo("CU-1"))
            .willReturn(
                json(
                    200,
                    """
                    {"items":[{"noteId":"CNOTE-2","customerId":"CU-1","text":"다음 통화 때 환불 확인","authorId":"op-cs",
                      "createdAt":"2026-11-23T02:00:00Z","editedAt":null,"deleted":false},
                     {"noteId":"CNOTE-1","customerId":"CU-1","text":"관리자 메모","authorId":"op-admin",
                      "createdAt":"2026-11-23T01:00:00Z","editedAt":"2026-11-23T01:30:00Z","deleted":false}],
                     "page":{"nextCursor":null}}""")));

    Res r = get(ADMIN, "/views/customer/CU-1");

    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    JsonNode logs = r.body().path("piiAccessLogs");
    assertThat(logs.path("hasMore").asBoolean()).isTrue();
    assertThat(logs.path("items").get(0).path("actorLabel").path("name").asString())
        .isEqualTo("이상담");
    assertThat(logs.path("items").get(0).path("fields").toString())
        .isEqualTo("[\"NAME\",\"PHONE\"]");
    audit.verify(
        getRequestedFor(urlPathEqualTo("/api/v1/pii-access-logs"))
            .withHeader("Authorization", equalTo("Bearer " + ADMIN)));
    // 0.26.0 상담 메모 — 쓴 직원 이름, mine = 부른 직원(관리자)이 쓴 것
    JsonNode notes = r.body().path("customerNotes").path("items");
    assertThat(notes.findValuesAsString("noteId")).containsExactly("CNOTE-2", "CNOTE-1");
    assertThat(notes.get(0).path("authorLabel").path("name").asString()).isEqualTo("이상담");
    assertThat(notes.get(0).path("mine").asBoolean()).isFalse();
    assertThat(notes.get(1).path("mine").asBoolean()).isTrue();
    assertThat(notes.get(1).path("editedAt").asString()).isEqualTo("2026-11-23T01:30:00Z");
    assertThat(r.body().path("customerNotes").path("hasMore").asBoolean()).isFalse();
    assertThat(r.body().path("deliveries").isNull()).as("배송 칸만 확인 불가").isTrue();
    assertThat(r.body().path("unavailableSources").toString())
        .contains("delivery-execution-service");
    execution.verify(
        0,
        getRequestedFor(urlPathEqualTo("/api/v1/deliveries"))
            .withQueryParam("orderId", equalTo("ORD-2")));
  }

  @Test
  void detailNotFoundAndCellOutageIsOnlyThatCell() {
    customer.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/customers/CU-404"))
            .willReturn(json(404, "{\"code\":\"CUSTOMER_NOT_FOUND\"}")));
    assertThat(get("/views/customer/CU-404").status()).isEqualTo(404);

    customer.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/customers/CU-1"))
            .willReturn(json(200, "{\"customerId\":\"CU-1\",\"status\":\"ACTIVE\"}")));
    order.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/orders/lookups"))
            .willReturn(json(200, "{\"items\":[],\"page\":{\"hasNext\":false}}")));
    cases.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/inquiries")).willReturn(aResponse().withStatus(503)));
    cases.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/cs-intakes"))
            .willReturn(json(200, "{\"items\":[],\"page\":{\"nextCursor\":null}}")));

    Res r = get("/views/customer/CU-1");

    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    assertThat(r.body().path("inquiries").isNull()).as("문의 칸만 확인 불가").isTrue();
    assertThat(r.body().path("claims").isNull()).as("클레임 칸 원천 없음(404) — 그 칸만 null").isTrue();
    assertThat(r.body().path("orders").path("items")).isEmpty();
    assertThat(r.body().path("csIntakes").path("items")).isEmpty();
    assertThat(r.body().path("unavailableSources").toString())
        .contains("case-service/inquiries")
        .doesNotContain("order-service");
    hr.verify(0, getRequestedFor(urlPathEqualTo("/api/v1/hr/employee-labels")));

    customer.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/customers/CU-1"))
            .willReturn(aResponse().withStatus(503)));
    assertThat(get("/views/customer/CU-1").status()).as("머리 장애 503").isEqualTo(503);
  }
}
