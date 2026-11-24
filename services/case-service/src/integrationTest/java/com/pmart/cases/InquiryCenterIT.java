package com.pmart.cases;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 0.40.0 R-17(웹 요청, 사용자 "b 로 하고 라이더는 운행 센터로"): 직원 문의 목록 centerId — 고객 문의 = 주문 이행 센터, 라이더 문의 = 접수 때
 * 운행 센터, 센터 없는 문의는 늘 함께. 지난 문의는 order 원장으로 채운다. order·rider 는 WireMock.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.cases.order-service-base-url=${order.url}",
      "pmart.cases.rider-service-base-url=${rider.url}",
      "pmart.messaging.outbox.delete-after-insert=false",
      "pmart.test.context=inquiry-center"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock({
  @ConfigureWireMock(name = "order", baseUrlProperties = "order.url"),
  @ConfigureWireMock(name = "rider", baseUrlProperties = "rider.url")
})
class InquiryCenterIT extends PostgresKafkaContainerSupport {

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired com.pmart.cases.application.InquiryCenterBackfill backfill;

  @InjectWireMock("order")
  WireMockServer order;

  @InjectWireMock("rider")
  WireMockServer rider;

  record Res(int status, JsonNode body) {}

  Res send(String method, String token, String path, @Nullable Object body) {
    var spec =
        RestClient.builder()
            .requestFactory(new JdkClientHttpRequestFactory())
            .build()
            .method(HttpMethod.valueOf(method))
            .uri("http://localhost:" + port + "/api/v1" + path)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", UUID.randomUUID().toString());
                  h.set("X-Source-System", "customer-app");
                });
    var e =
        body == null
            ? spec
            : spec.contentType(MediaType.APPLICATION_JSON).body(mapper.writeValueAsString(body));
    return e.exchange(
        (req, res) -> {
          byte[] b = res.getBody().readAllBytes();
          return new Res(
              res.getStatusCode().value(),
              b.length == 0 ? mapper.createObjectNode() : mapper.readTree(b));
        });
  }

  void orderOf(String orderId, @Nullable String mfcId) {
    order.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/orders/" + orderId))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"orderId\":\""
                            + orderId
                            + "\",\"status\":\"CONFIRMED\""
                            + (mfcId == null ? "" : ",\"mfcId\":\"" + mfcId + "\"")
                            + "}")));
  }

  String ask(String token, @Nullable String orderId, String category) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("category", category);
    body.put("body", "문의");
    if (orderId != null) {
      body.put("orderId", orderId);
    }
    Res r = send("POST", token, "/inquiries", body);
    assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
    return r.body().path("inquiryId").asString();
  }

  Map<String, JsonNode> list(String token, String query) {
    Res r = send("GET", token, "/inquiries?size=100" + query, null);
    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    Map<String, JsonNode> out = new HashMap<>();
    r.body().path("items").forEach(i -> out.put(i.path("inquiryId").asString(), i));
    return out;
  }

  @Test
  void staffFiltersByCenterAndCenterlessInquiriesAlwaysShow() {
    String suffix = UUID.randomUUID().toString().substring(0, 6);
    String c1 = "MFC-C1-" + suffix;
    String c2 = "MFC-C2-" + suffix;
    String me = TestJwt.customer("CU-" + suffix);
    String admin = TestJwt.operator("admin-c", List.of("OPERATOR_ADMIN"), List.of());

    orderOf("ORD-C1-" + suffix, c1);
    orderOf("ORD-C2-" + suffix, c2);
    String atC1 = ask(me, "ORD-C1-" + suffix, "DELIVERY");
    String atC2 = ask(me, "ORD-C2-" + suffix, "DELIVERY");
    String general = ask(me, null, "ACCOUNT");
    // 라이더 문의 — 접수 때 운행 센터
    rider.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/riders/me"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"riderId\":\"RD-C\",\"operating\":{\"mfcId\":\"" + c1 + "\"}}")));
    String riderInq = ask(TestJwt.rider("RD-C-" + suffix), null, "SETTLEMENT");

    Map<String, JsonNode> byC1 = list(admin, "&centerId=" + c1);
    assertThat(byC1).containsKeys(atC1, general, riderInq).doesNotContainKey(atC2);
    assertThat(byC1.get(atC1).path("centerId").asString()).isEqualTo(c1);
    assertThat(byC1.get(riderInq).path("centerId").asString()).isEqualTo(c1);
    assertThat(byC1.get(general).path("centerId").isString()).as("센터 없음 — 늘 함께").isFalse();
    assertThat(list(admin, "&centerId=" + c2))
        .containsKeys(atC2, general)
        .doesNotContainKeys(atC1, riderInq);
    // 고객에게는 센터 값을 주지 않고, 고객 토큰의 centerId 는 무시
    Map<String, JsonNode> mine = list(me, "&centerId=" + c2);
    assertThat(mine).containsKeys(atC1, atC2, general);
    assertThat(mine.get(atC1).path("centerId").isString()).isFalse();
  }

  @Test
  void pastInquiriesGetOrderCenterFromLedger() {
    String suffix = UUID.randomUUID().toString().substring(0, 6);
    String me = TestJwt.customer("CU-B-" + suffix);
    String admin = TestJwt.operator("admin-b", List.of("OPERATOR_ADMIN"), List.of());
    String orderId = "ORD-B-" + suffix;
    String center = "MFC-B-" + suffix;
    orderOf(orderId, null); // 받을 때 센터를 못 읽음(0.40.0 전 문의와 같다)
    String id = ask(me, orderId, "DELIVERY");
    assertThat(list(admin, "&centerId=OTHER-" + suffix).get(id).path("centerId").isString())
        .as("채우기 전 — 센터 없음이라 늘 함께")
        .isFalse();

    order.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/orders/" + orderId))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"orderId\":\"" + orderId + "\",\"mfcId\":\"" + center + "\"}")));
    assertThat(backfill.run()).isGreaterThanOrEqualTo(1);
    assertThat(list(admin, "&centerId=" + center).get(id).path("centerId").asString())
        .isEqualTo(center);
    assertThat(list(admin, "&centerId=OTHER-" + suffix)).doesNotContainKey(id);
  }
}
