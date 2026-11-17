package com.pmart.notification;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.pmart.common.test.EventEnvelopeFixtures;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.ObjectMapper;

/**
 * 라이더 휴대폰 푸시(2026-11-17 — 사용자 "발송 코드만 먼저, 키는 나중"). 시험 키(그때 만든 RSA)로 OAuth·FCM(WireMock) — 라이더 알림함에
 * 알림이 생기면 그 라이더의 단말마다 FCM 으로 보내고, 더는 쓸 수 없는 토큰(UNREGISTERED)은 지운다. 고객 알림은 보내지 않는다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.notification.fcm.token-uri=${fcm.url}/token",
      "pmart.notification.fcm.send-base-url=${fcm.url}"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock({@ConfigureWireMock(name = "fcm", baseUrlProperties = "fcm.url")})
class RiderPushIT extends PostgresKafkaContainerSupport {

  @DynamicPropertySource
  static void fcmKey(DynamicPropertyRegistry r) throws Exception {
    KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
    g.initialize(2048);
    String pem =
        "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getMimeEncoder().encodeToString(g.generateKeyPair().getPrivate().getEncoded())
            + "\n-----END PRIVATE KEY-----\n";
    Map<String, String> key = new LinkedHashMap<>();
    key.put("type", "service_account");
    key.put("project_id", "pmart-test");
    key.put("client_email", "push@pmart-test.iam.gserviceaccount.com");
    key.put("private_key", pem);
    String json = new ObjectMapper().writeValueAsString(key);
    r.add("pmart.notification.fcm.service-account-json", () -> json);
  }

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired KafkaTemplate<String, String> kafka;

  @InjectWireMock("fcm")
  WireMockServer fcm;

  @Test
  void riderNoticeIsPushedAndDeadTokenRemoved() throws Exception {
    fcm.resetAll();
    fcm.stubFor(
        post(urlPathEqualTo("/token"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"access_token\":\"AT-1\",\"expires_in\":3600}")));
    fcm.stubFor(
        post(urlPathMatching("/v1/projects/pmart-test/messages:send"))
            .withRequestBody(matchingJsonPath("$.message.token", equalTo("tok-live")))
            .willReturn(aResponse().withStatus(200).withBody("{\"name\":\"m1\"}")));
    fcm.stubFor(
        post(urlPathMatching("/v1/projects/pmart-test/messages:send"))
            .withRequestBody(matchingJsonPath("$.message.token", equalTo("tok-dead")))
            .willReturn(
                aResponse()
                    .withStatus(404)
                    .withBody(
                        "{\"error\":{\"status\":\"NOT_FOUND\",\"details\":[{\"errorCode\":\"UNREGISTERED\"}]}}")));

    String rider = "RD-push-" + UUID.randomUUID().toString().substring(0, 8);
    String jwt = TestJwt.rider(rider);
    assertThat(register(jwt, "dev-live", "tok-live")).isEqualTo(200);
    assertThat(register(jwt, "dev-dead", "tok-dead")).isEqualTo(200);

    String deliveryId = "DLV-PUSH-" + UUID.randomUUID().toString().substring(0, 8);
    kafka
        .send(
            "delivery.delivery.v1",
            deliveryId,
            EventEnvelopeFixtures.envelope("HelpRequestReplied")
                .from("test")
                .aggregate("Delivery", deliveryId, 1)
                .payload(
                    Map.of(
                        "deliveryId",
                        deliveryId,
                        "requestId",
                        "REQ-1",
                        "riderId",
                        rider,
                        "messageSeq",
                        2,
                        "repliedAt",
                        Instant.now().toString()))
                .buildJson())
        .join();

    Instant until = Instant.now().plus(Duration.ofSeconds(30));
    while (Instant.now().isBefore(until) && devices(rider) > 1) {
      Thread.sleep(200);
    }
    // 살아 있는 단말에 보냄(Bearer 접근 토큰, 알림함과 같은 제목, data 에 목적지)
    fcm.verify(
        postRequestedFor(urlPathMatching("/v1/projects/pmart-test/messages:send"))
            .withHeader("Authorization", equalTo("Bearer AT-1"))
            .withRequestBody(matchingJsonPath("$.message.token", equalTo("tok-live")))
            .withRequestBody(matchingJsonPath("$.message.notification.title"))
            .withRequestBody(
                matchingJsonPath("$.message.data.destinationType", equalTo("HELP_REQUEST"))));
    // 죽은 토큰은 지움
    assertThat(devices(rider)).isEqualTo(1);
    fcm.verify(1, postRequestedFor(urlPathEqualTo("/token"))); // 접근 토큰은 캐시
  }

  /** 그 라이더의 등록 단말 수(GET /me/push-tokens). */
  int devices(String rider) {
    String body =
        RestClient.builder()
            .requestFactory(new JdkClientHttpRequestFactory())
            .build()
            .get()
            .uri("http://localhost:" + port + "/api/v1/me/push-tokens")
            .header("Authorization", "Bearer " + TestJwt.rider(rider))
            .retrieve()
            .body(String.class);
    return mapper.readTree(body == null ? "{}" : body).path("items").size();
  }

  int register(String jwt, String deviceId, String token) {
    return send(
        "PUT", jwt, "/me/push-tokens/" + deviceId, Map.of("token", token, "platform", "ANDROID"));
  }

  int send(String method, String token, String path, @Nullable Object body) {
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
                  h.set("X-Source-System", "rider-app");
                });
    var e =
        body == null
            ? spec
            : spec.contentType(MediaType.APPLICATION_JSON).body(mapper.writeValueAsString(body));
    return e.exchange((req, res) -> res.getStatusCode().value());
  }
}
