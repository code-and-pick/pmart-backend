package com.pmart.delivery.tracking;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 위치 배치(정렬·품질·마지막 위치)와 거리 행렬(카카오모빌리티 WireMock — 다중 출발지·다중 목적지, 401/500 은 오류 그대로, 직선거리 대체 없음). */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.tracking.kakao-mobility.rest-api-key=it-key",
      "pmart.tracking.low-accuracy-m=50",
      "spring.data.redis.url="
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock(
    @ConfigureWireMock(
        name = "kakao",
        baseUrlProperties = "pmart.tracking.kakao-mobility.base-url"))
class TrackingFlowIT extends PostgresKafkaContainerSupport {

  /** Redis health indicator 가 포함되므로 실제 Redis 컨테이너(ServiceBootIT 와 같음). */
  @Container
  @ServiceConnection(name = "redis")
  static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;

  @InjectWireMock("kakao")
  WireMockServer kakao;

  RestClient http;

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    kakao.resetAll();
  }

  @Test
  void uploadKeepsAllPointsAndLatestIsNewestGoodPoint() {
    String rider = "RD-it-" + UUID.randomUUID();
    Instant now = Instant.now();
    ResponseEntity<String> r =
        upload(
            rider,
            "k-" + rider,
            List.of(
                point(37.5010, 127.0390, 10, now.minusSeconds(10)),
                point(37.5000, 127.0380, 10, now.minusSeconds(30)), // 순서 섞임 — 정렬해 저장
                point(37.6000, 127.1000, 500, now.minusSeconds(5)))); // 정확도 불량도 저장·표시
    assertThat(r.getStatusCode().value()).isEqualTo(202);
    assertThat(json(r.getBody()).path("appliedQty").asInt()).isEqualTo(3);
    // 같은 키 재전송 = 같은 결과
    assertThat(
            json(upload(
                        rider,
                        "k-" + rider,
                        List.of(
                            point(37.5010, 127.0390, 10, now.minusSeconds(10)),
                            point(37.5000, 127.0380, 10, now.minusSeconds(30)),
                            point(37.6000, 127.1000, 500, now.minusSeconds(5))))
                    .getBody())
                .path("appliedQty")
                .asInt())
        .isEqualTo(3);

    JsonNode latest =
        json(
            http.get()
                .uri("/riders/me/locations/latest")
                .header("Authorization", "Bearer " + TestJwt.rider(rider))
                .retrieve()
                .body(String.class));
    assertThat(latest.path("quality").asString()).isEqualTo("LOW_ACCURACY");
    assertThat(latest.path("lat").asDouble()).isEqualTo(37.6);

    // 이미 받은 것보다 이른 점 → TIME_SKEW, 마지막 위치는 그대로
    upload(rider, "k2-" + rider, List.of(point(37.4000, 127.0000, 5, now.minusSeconds(60))));
    JsonNode batch =
        json(
            http.get()
                .uri("/internal/rider-locations?riderIds={a},RD-none", rider)
                .header("X-Source-System", "delivery-dispatch-service")
                .retrieve()
                .body(String.class));
    assertThat(batch.path("items")).hasSize(1);
    assertThat(batch.path("items").get(0).path("lat").asDouble()).isEqualTo(37.6);
  }

  @Test
  void matrixUsesKakaoAndNeverFallsBackToStraightLine() {
    kakao.stubFor(
        post(urlPathEqualTo("/v1/origins/directions"))
            .withHeader("Authorization", equalTo("KakaoAK it-key"))
            .willReturn(
                json(
                    Map.of(
                        "routes",
                        List.of(route("0", 0, 872, 117), route("1", 104, 0, 0)))))); // 길 없음(반경 밖 등)
    JsonNode m =
        json(
            http.post()
                .uri("/internal/distance-matrix")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .header("X-Source-System", "delivery-dispatch-service")
                .body(
                    matrixBody(
                        List.of(ll(37.4979, 127.0276), ll(37.6, 127.2)),
                        List.of(ll(37.5006, 127.0360))))
                .retrieve()
                .body(String.class));
    assertThat(m.path("provider").asString()).isEqualTo("KAKAO_MOBILITY");
    assertThat(m.path("fallback").asBoolean()).isFalse();
    assertThat(m.path("rows").get(0).path("elements").get(0).path("distanceM").asInt())
        .isEqualTo(872);
    assertThat(m.path("rows").get(0).path("elements").get(0).path("durationSeconds").asInt())
        .isEqualTo(117);
    assertThat(m.path("rows").get(1).path("elements").get(0).path("status").asString())
        .isEqualTo("NO_ROUTE");
    kakao.verify(
        postRequestedFor(urlPathEqualTo("/v1/origins/directions"))
            .withRequestBody(matchingJsonPath("$.destination.x", equalTo("127.036")))
            .withRequestBody(matchingJsonPath("$.origins[1].key", equalTo("1"))));

    kakao.stubFor(
        post(urlPathEqualTo("/v1/destinations/directions"))
            .willReturn(
                json(Map.of("routes", List.of(route("1", 0, 2311, 609), route("0", 0, 500, 90))))));
    JsonNode m2 =
        json(
            http.post()
                .uri("/internal/distance-matrix")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .header("X-Source-System", "delivery-dispatch-service")
                .body(
                    matrixBody(
                        List.of(ll(37.4979, 127.0276)),
                        List.of(ll(37.5006, 127.0360), ll(37.49, 127.02))))
                .retrieve()
                .body(String.class));
    assertThat(m2.path("rows").get(0).path("elements").get(0).path("distanceM").asInt())
        .isEqualTo(500);
    assertThat(m2.path("rows").get(0).path("elements").get(1).path("distanceM").asInt())
        .isEqualTo(2311);

    kakao.stubFor(
        post(urlPathEqualTo("/v1/destinations/directions"))
            .willReturn(aResponse().withStatus(401)));
    assertThat(matrixStatus()).isEqualTo(422);
    kakao.stubFor(
        post(urlPathEqualTo("/v1/destinations/directions"))
            .willReturn(aResponse().withStatus(500)));
    assertThat(matrixStatus()).isEqualTo(503);
    kakao.stubFor(
        post(urlPathEqualTo("/v1/destinations/directions"))
            .willReturn(aResponse().withStatus(429)));
    assertThat(matrixStatus()).isEqualTo(429);
    // 실제 일 사용량 초과 응답은 400 code -10 — 429 로(dispatch quota-backoff), 다른 400 은 그대로 503
    kakao.stubFor(
        post(urlPathEqualTo("/v1/destinations/directions"))
            .willReturn(
                aResponse()
                    .withStatus(400)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"code\":-10,\"msg\":\"API limit has been exceeded\"}")));
    assertThat(matrixStatus()).isEqualTo(429);
    kakao.stubFor(
        post(urlPathEqualTo("/v1/destinations/directions"))
            .willReturn(
                aResponse()
                    .withStatus(400)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"code\":-2,\"msg\":\"bad request\"}")));
    assertThat(matrixStatus()).isEqualTo(503);
  }

  private int matrixStatus() {
    return http.post()
        .uri("/internal/distance-matrix")
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
        .header("X-Source-System", "delivery-dispatch-service")
        .body(matrixBody(List.of(ll(37.49, 127.02)), List.of(ll(37.50, 127.03))))
        .retrieve()
        .toEntity(String.class)
        .getStatusCode()
        .value();
  }

  private static Map<String, Object> matrixBody(
      List<Map<String, Object>> origins, List<Map<String, Object>> destinations) {
    return Map.of("origins", origins, "destinations", destinations);
  }

  private static Map<String, Object> ll(double lat, double lng) {
    return Map.of("lat", lat, "lng", lng);
  }

  private ResponseEntity<String> upload(
      String rider, String key, List<Map<String, Object>> points) {
    return http.post()
        .uri("/riders/me/locations")
        .header("Authorization", "Bearer " + TestJwt.rider(rider))
        .header("Idempotency-Key", key)
        .header("X-Source-System", "rider-app")
        .body(Map.of("points", points))
        .retrieve()
        .toEntity(String.class);
  }

  private static Map<String, Object> point(double lat, double lng, double acc, Instant at) {
    return Map.of("lat", lat, "lng", lng, "accuracyM", acc, "recordedAt", at.toString());
  }

  private static Map<String, Object> route(String key, int code, int distance, int duration) {
    return Map.of(
        "key",
        key,
        "result_code",
        code,
        "result_msg",
        code == 0 ? "길찾기 성공" : "길 없음",
        "summary",
        Map.of("distance", distance, "duration", duration));
  }

  private com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(Object body) {
    return aResponse()
        .withHeader("Content-Type", "application/json")
        .withBody(mapper.writeValueAsString(body));
  }

  private JsonNode json(String body) {
    return mapper.readTree(body == null ? "{}" : body);
  }
}
