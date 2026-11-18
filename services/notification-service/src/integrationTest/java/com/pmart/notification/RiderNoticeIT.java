package com.pmart.notification;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 라이더 공지(2026-11-18 — 사용자 "대상 고르기 + 알림함·푸시"): 받는 라이더마다 알림함 한 줄, 같은 요청은 한 번, 라이더 운영·관리자만. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock({
  @ConfigureWireMock(name = "rider", baseUrlProperties = "pmart.notification.rider-base-url")
})
class RiderNoticeIT extends PostgresKafkaContainerSupport {

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;

  @InjectWireMock("rider")
  WireMockServer rider;

  ResponseEntity<String> send(String token, String key, Map<String, Object> body) {
    return RestClient.builder()
        .requestFactory(new JdkClientHttpRequestFactory())
        .defaultStatusHandler(s -> s.isError(), (q, r) -> {})
        .build()
        .post()
        .uri("http://localhost:" + port + "/api/v1/rider-notices")
        .header("Authorization", "Bearer " + token)
        .header("Idempotency-Key", key)
        .header("X-Source-System", "rider-backoffice-web")
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .retrieve()
        .toEntity(String.class);
  }

  @Test
  void noticeGoesToEachRiderInboxOnce() throws Exception {
    String r1 = "RD-nt-" + UUID.randomUUID();
    String r2 = "RD-nt-" + UUID.randomUUID();
    rider.stubFor(
        get(urlPathEqualTo("/api/v1/internal/riders/recipients"))
            .withQueryParam("mfcId", equalTo("MFC-NT"))
            .willReturn(okJson(mapper.writeValueAsString(Map.of("riderIds", List.of(r1, r2))))));
    String ops = TestJwt.operator("OP-RO", List.of("RIDER_OPS"), List.of());
    Map<String, Object> body =
        Map.of(
            "title", "내일 센터 휴무",
            "body", "11월 19일은 부산진구지점이 쉽니다.",
            "target", Map.of("type", "MFC", "mfcId", "MFC-NT"));
    String key = UUID.randomUUID().toString();
    ResponseEntity<String> ok = send(ops, key, body);
    assertThat(ok.getStatusCode().value()).as(ok.getBody()).isEqualTo(200);
    JsonNode n = mapper.readTree(ok.getBody());
    assertThat(n.path("recipientCount").asInt()).isEqualTo(2);
    assertThat(n.path("sentBy").asString()).isEqualTo("OP-RO");
    // 같은 요청 → 같은 공지, 알림함은 그대로 두 줄
    assertThat(mapper.readTree(send(ops, key, body).getBody()).path("noticeId").asString())
        .isEqualTo(n.path("noticeId").asString());
    for (String r : List.of(r1, r2)) {
      String inbox =
          RestClient.builder()
              .requestFactory(new JdkClientHttpRequestFactory())
              .build()
              .get()
              .uri("http://localhost:" + port + "/api/v1/me/notifications")
              .header("Authorization", "Bearer " + TestJwt.rider(r))
              .retrieve()
              .body(String.class);
      JsonNode items = mapper.readTree(inbox).path("items");
      long notices =
          items
              .valueStream()
              .filter(i -> "RIDER_NOTICE".equals(i.path("templateKey").asString()))
              .count();
      assertThat(notices).as(r).isEqualTo(1);
    }
    // 상담원·센터 운영자는 403, 빈 제목 400
    assertThat(
            send(TestJwt.operator("OP-CS", List.of("CS_AGENT"), List.of()), "k2", body)
                .getStatusCode()
                .value())
        .isEqualTo(403);
    assertThat(
            send(ops, "k3", Map.of("title", " ", "body", "x", "target", Map.of("type", "ALL")))
                .getStatusCode()
                .value())
        .isEqualTo(400);
    // 목록
    String list =
        RestClient.builder()
            .requestFactory(new JdkClientHttpRequestFactory())
            .build()
            .get()
            .uri("http://localhost:" + port + "/api/v1/rider-notices")
            .header("Authorization", "Bearer " + ops)
            .retrieve()
            .body(String.class);
    assertThat(list).contains(n.path("noticeId").asString());
  }
}
