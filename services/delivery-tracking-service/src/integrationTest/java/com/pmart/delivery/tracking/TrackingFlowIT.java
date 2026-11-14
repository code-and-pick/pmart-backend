package com.pmart.delivery.tracking;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.pmart.common.test.EventEnvelopeFixtures;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import com.pmart.delivery.tracking.application.RiderWatch;
import java.sql.Timestamp;
import java.time.Duration;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
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
      "spring.data.redis.url=",
      "pmart.messaging.outbox.delete-after-insert=false",
      "pmart.tracking.stale-check-interval=PT1H", // 시험이 직접 부른다
      "pmart.tracking.purge-interval=PT1H"
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
  @Autowired JdbcTemplate jdbc;
  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired RiderWatch watch;

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

  /** 사용자 2026-11-14: 진행 중 배송이 있는 라이더 위치가 3분 넘게 안 오면 RiderLocationStale 한 번, 완료 뒤에는 안 냄. */
  @Test
  void staleLocationRaisedOncePerGapWhileDelivering() {
    String rider = "RD-st-" + UUID.randomUUID();
    String delivery = "DLV-st-" + UUID.randomUUID();
    deliveryEvent(
        "DeliveryAssigned",
        delivery,
        Map.of(
            "deliveryId",
            delivery,
            "riderId",
            rider,
            "assignmentVersion",
            1,
            "acceptedAt",
            Instant.now().toString()));
    awaitCount(
        "SELECT count(*) FROM rider_active_delivery WHERE delivery_id = '"
            + delivery
            + "' AND state = 'ACTIVE'");

    watch.checkStale();
    assertThat(staleCount(rider)).isZero(); // 막 배정 — 아직 3분 안
    jdbc.update(
        "UPDATE rider_active_delivery SET since = ? WHERE delivery_id = ?",
        Timestamp.from(Instant.now().minus(Duration.ofMinutes(5))),
        delivery);
    watch.checkStale();
    watch.checkStale();
    assertThat(staleCount(rider)).isEqualTo(1); // 끊김 한 번에 한 번
    JsonNode stale =
        json(
            jdbc.queryForObject(
                "SELECT payload::text FROM outbox_event WHERE aggregateid = ? AND type = 'RiderLocationStale'",
                String.class,
                rider));
    assertThat(stale.path("payload").path("deliveryId").asString()).isEqualTo(delivery);
    assertThat(
            jdbc.queryForObject(
                "SELECT topic FROM outbox_event WHERE aggregateid = ? AND type = 'RiderLocationStale'",
                String.class,
                rider))
        .isEqualTo("delivery.tracking.v1");

    // 위치가 다시 오면 끊김 아님, 그 뒤 다시 끊기면 또 한 번
    upload(rider, "k-st-" + rider, List.of(point(37.5, 127.03, 10, Instant.now())));
    watch.checkStale();
    assertThat(staleCount(rider)).isEqualTo(1);
    jdbc.update(
        "UPDATE rider_signal SET last_received_at = ? WHERE rider_id = ?",
        Timestamp.from(Instant.now().minus(Duration.ofMinutes(4))),
        rider);
    watch.checkStale();
    assertThat(staleCount(rider)).isEqualTo(2);
    // 사건 버전 = 알림 횟수(갱신하며 읽음 — 겹치지 않음)
    assertThat(
            jdbc.queryForList(
                "SELECT (payload->>'aggregateVersion')::int FROM outbox_event WHERE aggregateid = ?"
                    + " AND type = 'RiderLocationStale' ORDER BY created_at",
                Integer.class,
                rider))
        .containsExactly(1, 2);

    // 전달 완료 뒤에는 끊겨도 안 냄, 늦게 온 옛 배정 사건이 다시 열지 않는다
    deliveryEvent(
        "DeliveryCompleted",
        delivery,
        Map.of(
            "deliveryId",
            delivery,
            "orderId",
            "ORD-1",
            "riderId",
            rider,
            "lines",
            List.of(Map.of("orderLineId", "L-1", "qty", 1)),
            "method",
            "FACE",
            "evidenceRef",
            Map.of("evidenceId", "EVP-1", "kind", "PHOTO"),
            "deliveredAt",
            Instant.now().toString()));
    awaitCount(
        "SELECT count(*) FROM rider_active_delivery WHERE delivery_id = '"
            + delivery
            + "' AND state = 'CLOSED'");
    deliveryEvent(
        "DeliveryAssigned",
        delivery,
        Map.of(
            "deliveryId",
            delivery,
            "riderId",
            rider,
            "assignmentVersion",
            1,
            "acceptedAt",
            Instant.now().toString()));
    jdbc.update(
        "UPDATE rider_signal SET last_received_at = ? WHERE rider_id = ?",
        Timestamp.from(Instant.now().minus(Duration.ofMinutes(10))),
        rider);
    sleepMs(1500);
    watch.checkStale();
    assertThat(staleCount(rider)).isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM rider_active_delivery WHERE delivery_id = ?",
                String.class,
                delivery))
        .isEqualTo("CLOSED");
  }

  /**
   * PC 1 PR #15 검토·사용자 2026-11-14: 감시 시작은 사건에 적힌 배정 시각(읽은 시각이 아님), 배정 뒤 5시간 지나면 끝 사건이 없어도 감시
   * 끝("5시간이 지나면 감시 종료"), 배정 해제 뒤 늦게 온 옛 배정 사건은 다시 열지 않고 더 높은 버전 배정만 다시 연다.
   */
  @Test
  void watchStartsAtAssignmentTimeEndsAfterFiveHoursAndIgnoresLateOldAssignment() {
    // 6시간 전 배정 사건을 지금 읽음(옛 사건 재처리) — 지금 배정된 것처럼 되지 않고, 끊김 알림 없이 끝난다
    String rider = "RD-ex-" + UUID.randomUUID();
    String old = "DLV-ex-" + UUID.randomUUID();
    deliveryEvent(
        "DeliveryAssigned",
        old,
        Map.of(
            "deliveryId",
            old,
            "riderId",
            rider,
            "assignmentVersion",
            1,
            "acceptedAt",
            Instant.now().minus(Duration.ofHours(6)).toString()));
    awaitCount("SELECT count(*) FROM rider_active_delivery WHERE delivery_id = '" + old + "'");
    watch.checkStale();
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM rider_active_delivery WHERE delivery_id = ?", String.class, old))
        .isEqualTo("EXPIRED");
    assertThat(staleCount(rider)).isZero();

    // 배정 → 해제(RELEASED, 버전 2) → 늦게 온 옛 배정(버전 1)은 무시
    String rider2 = "RD-rl-" + UUID.randomUUID();
    String d2 = "DLV-rl-" + UUID.randomUUID();
    deliveryEvent(
        "DeliveryAssigned",
        d2,
        Map.of(
            "deliveryId",
            d2,
            "riderId",
            rider2,
            "assignmentVersion",
            1,
            "acceptedAt",
            Instant.now().toString()));
    awaitCount(
        "SELECT count(*) FROM rider_active_delivery WHERE delivery_id = '"
            + d2
            + "' AND state = 'ACTIVE'");
    deliveryEvent(
        "DeliveryAssignmentChanged",
        d2,
        Map.of(
            "deliveryId",
            d2,
            "oldRiderId",
            rider2,
            "assignmentVersion",
            2,
            "reason",
            "RIDER_RESTRICTED",
            "changedAt",
            Instant.now().toString()));
    awaitCount(
        "SELECT count(*) FROM rider_active_delivery WHERE delivery_id = '"
            + d2
            + "' AND state = 'RELEASED'");
    deliveryEvent(
        "DeliveryAssigned",
        d2,
        Map.of(
            "deliveryId",
            d2,
            "riderId",
            rider2,
            "assignmentVersion",
            1,
            "acceptedAt",
            Instant.now().toString()));
    sleepMs(1500);
    Map<String, Object> released =
        jdbc.queryForMap(
            "SELECT state, assignment_version FROM rider_active_delivery WHERE delivery_id = ?",
            d2);
    assertThat(released.get("state")).isEqualTo("RELEASED");
    assertThat(released.get("assignment_version")).isEqualTo(2);
    jdbc.update(
        "INSERT INTO rider_signal (rider_id, last_received_at) VALUES (?, ?)",
        rider2,
        Timestamp.from(Instant.now().minus(Duration.ofMinutes(10))));
    watch.checkStale();
    assertThat(staleCount(rider2)).isZero();

    // 더 높은 버전 새 배정은 다시 연다(시작 = 사건의 배정 시각)
    Instant acceptedAt =
        Instant.now()
            .minus(Duration.ofMinutes(5))
            .truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    deliveryEvent(
        "DeliveryAssigned",
        d2,
        Map.of(
            "deliveryId",
            d2,
            "riderId",
            rider2,
            "assignmentVersion",
            3,
            "acceptedAt",
            acceptedAt.toString()));
    awaitCount(
        "SELECT count(*) FROM rider_active_delivery WHERE delivery_id = '"
            + d2
            + "' AND state = 'ACTIVE'");
    assertThat(
            jdbc.queryForObject(
                    "SELECT since FROM rider_active_delivery WHERE delivery_id = ?",
                    Timestamp.class,
                    d2)
                .toInstant())
        .isEqualTo(acceptedAt);
    watch.checkStale();
    assertThat(staleCount(rider2)).isEqualTo(1);
  }

  /** 사용자 2026-11-14 "보존기간은 30일": 서버 수신 30일 지난 위치 점은 지운다. */
  @Test
  void locationPointsOlderThan30DaysArePurged() {
    String rider = "RD-pg-" + UUID.randomUUID();
    upload(rider, "k-pg-" + rider, List.of(point(37.5, 127.03, 10, Instant.now())));
    jdbc.update(
        "INSERT INTO rider_location_point (rider_id, lat, lng, recorded_at, received_at, quality) VALUES (?, 37.4, 127.0, ?, ?, 'GOOD')",
        rider,
        Timestamp.from(Instant.now().minus(Duration.ofDays(31))),
        Timestamp.from(Instant.now().minus(Duration.ofDays(31))));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM rider_location_point WHERE rider_id = ?",
                Integer.class,
                rider))
        .isEqualTo(2);
    watch.purge();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM rider_location_point WHERE rider_id = ?",
                Integer.class,
                rider))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM rider_latest_location WHERE rider_id = ?",
                Integer.class,
                rider))
        .isEqualTo(1);
  }

  private void deliveryEvent(String type, String deliveryId, Map<String, Object> payload) {
    String json =
        EventEnvelopeFixtures.envelope(type)
            .from(
                type.equals("DeliveryCompleted")
                    ? "delivery-execution-service"
                    : "delivery-dispatch-service")
            .aggregate("Delivery", deliveryId, System.nanoTime() % 100000)
            .payload(payload)
            .buildJson();
    kafka.send("delivery.delivery.v1", deliveryId, json).join();
  }

  private int staleCount(String rider) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM outbox_event WHERE aggregateid = ? AND type = 'RiderLocationStale'",
        Integer.class,
        rider);
  }

  private void awaitCount(String sql) {
    long until = System.currentTimeMillis() + 30_000;
    while (System.currentTimeMillis() < until) {
      Integer n = jdbc.queryForObject(sql, Integer.class);
      if (n != null && n > 0) {
        return;
      }
      sleepMs(200);
    }
    throw new AssertionError("기다린 상태가 오지 않음: " + sql);
  }

  private static void sleepMs(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
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
