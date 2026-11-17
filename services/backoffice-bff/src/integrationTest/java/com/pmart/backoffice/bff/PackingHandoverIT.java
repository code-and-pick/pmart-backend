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
 * 포장 인계 현황(0.11.0 — 2026-11-17, 배송 원문 §10.1·§3.1·§3.4·§3.7): MFC 센터 출고 목록(F-9) 한 번 + 출고마다 배송, 단계
 * 표시(bff 규칙), 일부 인계의 남은 포장, 원천 장애는 그 원천만, 센터 관리자 자기 센터 고정·작업자 403·본사 역할 mfcId 필수.
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
  @ConfigureWireMock(name = "mfc", baseUrlProperties = "pmart.bff.mfc-fulfillment-base-url"),
  @ConfigureWireMock(name = "execution", baseUrlProperties = "pmart.bff.execution-base-url")
})
class PackingHandoverIT {

  static final String CS = TestJwt.operator("op-cs", List.of("CS_AGENT"), List.of());
  static final String SUP = TestJwt.operator("op-sup", List.of("MFC_SUPERVISOR"), List.of("MFC-1"));

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;

  @InjectWireMock("mfc")
  WireMockServer mfc;

  @InjectWireMock("execution")
  WireMockServer execution;

  RestClient http;

  @BeforeEach
  void setUp() {
    mfc.resetAll();
    execution.resetAll();
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1/backoffice")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    mfc.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/shipments"))
            .willReturn(
                json(
                    200,
                    """
                    {"items":[
                      {"shipmentId":"SH-1","orderId":"ORD-1","orderNumber":"10000001","fulfillmentId":"FF-1","mfcId":"MFC-1",
                       "workOrderKind":"ORIGINAL","state":"PREPARING","createdAt":"2026-11-17T01:00:00Z",
                       "pick":{"state":"IN_PROGRESS","blockReason":null,"startedAt":"2026-11-17T01:01:00Z"},
                       "pack":{"state":"WAITING"},
                       "packageCounts":{"total":0,"sealed":0,"handedOver":0},"packages":[]},
                      {"shipmentId":"SH-2","orderId":"ORD-2","orderNumber":null,"fulfillmentId":"FF-2","mfcId":"MFC-1",
                       "workOrderKind":"RESHIPMENT","state":"PARTIALLY_HANDED_OVER","createdAt":"2026-11-17T01:05:00Z",
                       "readyAt":"2026-11-17T01:20:00Z","dispatchRequestedAt":"2026-11-17T01:06:00Z",
                       "pick":{"state":"COMPLETED"},"pack":{"state":"COMPLETED"},
                       "packageCounts":{"total":3,"sealed":2,"handedOver":1},
                       "packages":[
                         {"packageId":"PK-1","state":"HANDED_OVER","fenceState":"COMMITTED","handoverAuthorizedAt":"2026-11-17T01:30:00Z",
                          "handedOverAt":"2026-11-17T01:31:00Z","riderId":"RD-9"},
                         {"packageId":"PK-2","state":"SEALED","fenceState":"NONE"},
                         {"packageId":"PK-3","state":"SEALED","fenceState":"NONE"}]}],
                     "nextCursor":"SH-2","asOf":"2026-11-17T01:40:00Z"}""")));
    execution.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/deliveries"))
            .withQueryParam("orderId", equalTo("ORD-1"))
            .willReturn(json(200, "{\"items\":[],\"page\":{\"nextCursor\":null}}")));
    execution.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/deliveries"))
            .withQueryParam("orderId", equalTo("ORD-2"))
            .withQueryParam("type", equalTo("RESHIPMENT"))
            .willReturn(
                json(
                    200,
                    """
                    {"items":[{"deliveryId":"DLV-2","orderId":"ORD-2","type":"RESHIPMENT","state":"ARRIVED_AT_STORE",
                               "version":3,"riderId":"RD-9"}],"page":{"nextCursor":null}}""")));
  }

  record Res(int status, JsonNode body) {}

  Res view(String token, String query) {
    var e =
        http.get()
            .uri("/views/packing-handover" + (query.isEmpty() ? "" : "?" + query))
            .headers(h -> h.setBearerAuth(token))
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  static ResponseDefinitionBuilder json(int s, String b) {
    return aResponse().withStatus(s).withHeader("Content-Type", "application/json").withBody(b);
  }

  @Test
  void shipmentsWithStageAndPackagesAndDelivery() {
    Res r = view(CS, "mfcId=MFC-1&state=PREPARING&state=PARTIALLY_HANDED_OVER&size=10");
    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    mfc.verify(
        getRequestedFor(urlPathEqualTo("/api/v1/shipments"))
            .withQueryParam("mfcId", equalTo("MFC-1"))
            .withQueryParam("state", equalTo("PREPARING"))
            .withQueryParam("state", equalTo("PARTIALLY_HANDED_OVER"))
            .withQueryParam("size", equalTo("10")));
    JsonNode items = r.body().path("items");
    assertThat(items).hasSize(2);

    JsonNode a = items.get(0);
    assertThat(a.path("stage").asString()).isEqualTo("PICKING");
    assertThat(a.path("orderNumber").asString()).isEqualTo("10000001");
    assertThat(a.path("pick").path("state").asString()).isEqualTo("IN_PROGRESS");
    assertThat(a.path("deliveryId").isNull()).as("배송 없음").isTrue();
    assertThat(a.path("delivery").path("source").path("confidence").asString()).isEqualTo("DIRECT");

    JsonNode b = items.get(1);
    assertThat(b.path("stage").asString()).isEqualTo("PARTIALLY_HANDED_OVER");
    assertThat(b.path("shipment").path("state").asString()).isEqualTo("PARTIALLY_HANDED_OVER");
    assertThat(b.path("shipment").path("source").path("asOf").path("asOf").asString())
        .as("MFC 기준시각 그대로")
        .startsWith("2026-11-17T01:40");
    assertThat(b.path("packageCount").asInt()).isEqualTo(3);
    assertThat(b.path("handedOverQty").path("qty").asInt()).isEqualTo(1);
    assertThat(b.path("remainingPackages").asInt()).as("§3.7 남은 포장").isEqualTo(2);
    assertThat(b.path("packages").get(0).path("riderId").asString()).isEqualTo("RD-9");
    assertThat(b.path("packages").get(1).path("handedOverAt").isNull()).isTrue();
    assertThat(b.path("deliveryId").asString()).as("재발송은 RESHIPMENT 배송").isEqualTo("DLV-2");
    assertThat(b.path("delivery").path("state").asString()).isEqualTo("ARRIVED_AT_STORE");
    assertThat(b.path("riderId").asString()).isEqualTo("RD-9");

    assertThat(r.body().path("page").path("nextCursor").asString()).isEqualTo("SH-2");
    assertThat(r.body().path("unavailableSources")).isEmpty();
  }

  @Test
  void failingSourcesAreMarkedNotZeroed() {
    execution.resetAll();
    execution.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/deliveries"))
            .willReturn(json(500, "{\"code\":\"INTERNAL\"}")));
    Res r = view(SUP, "");
    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    assertThat(r.body().path("items")).hasSize(2);
    assertThat(
            r.body()
                .path("items")
                .get(1)
                .path("delivery")
                .path("source")
                .path("confidence")
                .asString())
        .isEqualTo("UNKNOWN");
    assertThat(r.body().path("unavailableSources").toString())
        .contains("delivery-execution-service");
    execution.verify(
        0,
        getRequestedFor(urlPathEqualTo("/api/v1/deliveries"))
            .withQueryParam("orderId", equalTo("ORD-2")));

    mfc.resetAll();
    mfc.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/shipments"))
            .willReturn(json(503, "{\"code\":\"DEPENDENCY_UNAVAILABLE\"}")));
    Res down = view(SUP, "");
    assertThat(down.status()).isEqualTo(200);
    assertThat(down.body().path("items")).isEmpty();
    assertThat(down.body().path("unavailableSources").toString())
        .contains("mfc-fulfillment-service");
  }

  @Test
  void rolesCentersAndInputs() {
    assertThat(view(SUP, "").status()).as("센터 관리자는 자기 센터").isEqualTo(200);
    mfc.verify(
        getRequestedFor(urlPathEqualTo("/api/v1/shipments"))
            .withQueryParam("mfcId", equalTo("MFC-1")));
    assertThat(view(SUP, "mfcId=MFC-2").status()).isEqualTo(403);
    assertThat(
            view(TestJwt.operator("op-w", List.of("MFC_WORKER"), List.of("MFC-1")), "mfcId=MFC-1")
                .status())
        .as("작업자는 PDA")
        .isEqualTo(403);
    assertThat(view(CS, "").status()).as("본사 역할은 mfcId 필수").isEqualTo(400);
    assertThat(view(CS, "mfcId=MFC-1&state=SHIPPED").status()).isEqualTo(400);
    assertThat(view(CS, "mfcId=MFC-1&size=51").status()).isEqualTo(400);
  }
}
