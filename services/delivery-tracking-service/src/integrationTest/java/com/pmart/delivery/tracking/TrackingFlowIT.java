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
      "pmart.tracking.purge-interval=PT1H",
      "pmart.tracking.eta-check-interval=PT1H", // 시험이 직접 부른다(TR-1)
      "pmart.tracking.eta.interval=PT0S",
      "pmart.tracking.eta.min-change=PT1M",
      "pmart.tracking.stream-poll-interval=PT0.3S"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock({
  @ConfigureWireMock(name = "kakao", baseUrlProperties = "pmart.tracking.kakao-mobility.base-url"),
  @ConfigureWireMock(name = "dispatch", baseUrlProperties = "pmart.tracking.dispatch-base-url"),
  @ConfigureWireMock(name = "order", baseUrlProperties = "pmart.tracking.order-base-url"),
  @ConfigureWireMock(name = "rider", baseUrlProperties = "pmart.tracking.rider-base-url")
})
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
  @Autowired com.pmart.delivery.tracking.application.ReceiptCheck receiptCheck;

  @InjectWireMock("kakao")
  WireMockServer kakao;

  @InjectWireMock("dispatch")
  WireMockServer dispatchApi;

  @InjectWireMock("order")
  WireMockServer orderApi;

  @InjectWireMock("rider")
  WireMockServer riderApi;

  @Autowired com.pmart.delivery.tracking.application.EtaService eta;

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

  /**
   * 고객에게 받았는지 묻기(2026-11-17 — 사용자 "도착예정 시간보다 20분이 지나면"): ETA 가 없으면 약속 시간대 끝 + 20분, 픽업 뒤·전달 전만, 한
   * 번만.
   */
  @Test
  void receiptCheckAskedOnceTwentyMinutesAfterExpected() {
    String late = "DLV-rc-" + UUID.randomUUID();
    String early = "DLV-rc-" + UUID.randomUUID();
    String store = "DLV-rc-" + UUID.randomUUID();
    java.time.ZonedDateTime seoul = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Seoul"));
    String pastEnd = seoul.minusMinutes(30).toLocalTime().withSecond(0).withNano(0).toString();
    String soonEnd = seoul.plusMinutes(30).toLocalTime().withSecond(0).withNano(0).toString();
    java.sql.Date pastDay = java.sql.Date.valueOf(seoul.minusMinutes(30).toLocalDate());
    java.sql.Date soonDay = java.sql.Date.valueOf(seoul.plusMinutes(30).toLocalDate());
    jdbc.update(
        "INSERT INTO delivery_progress (delivery_id, order_id, mfc_id, stage, arrival_date, arrival_end,"
            + " created_at, updated_at) VALUES (?, 'ORD-rc-1', 'MFC-1', 'TO_CUSTOMER', ?, ?, now(), now()),"
            + " (?, 'ORD-rc-2', 'MFC-1', 'AT_CUSTOMER', ?, ?, now(), now()),"
            + " (?, 'ORD-rc-3', 'MFC-1', 'AT_STORE', ?, ?, now(), now())",
        late,
        pastDay,
        pastEnd,
        early,
        soonDay,
        soonEnd,
        store,
        pastDay,
        pastEnd);
    receiptCheck.run();
    receiptCheck.run(); // 한 번만
    Integer asked =
        jdbc.queryForObject(
            "SELECT count(*) FROM outbox_event WHERE type = 'DeliveryReceiptCheckRequested' AND"
                + " aggregateid = ?",
            Integer.class,
            late);
    assertThat(asked).isEqualTo(1);
    String payload =
        jdbc.queryForObject(
            "SELECT payload::text FROM outbox_event WHERE type = 'DeliveryReceiptCheckRequested' AND"
                + " aggregateid = ?",
            String.class,
            late);
    assertThat(payload).contains("ARRIVAL_WINDOW").contains("ORD-rc-1");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE type = 'DeliveryReceiptCheckRequested' AND"
                    + " aggregateid IN (?, ?)",
                Integer.class,
                early,
                store))
        .isZero(); // 아직 20분 안 지남 / 픽업 전
  }

  /**
   * 라이더 관제(2026-11-17, 사용자 "추천대로 전부"): RIDER_OPS·OPERATOR_ADMIN 만, 운행 중 라이더(rider-service 가 고름)의 현재
   * 위치·끊김만 — 위치 없는 라이더도 나오고(stale), 운행 중이 아닌 라이더는 위치가 있어도 안 나온다.
   */
  @Test
  void controlBoardShowsOnDutyRidersCurrentLocationOnlyToRiderOps() {
    String r1 = "RD-ctl-" + UUID.randomUUID();
    String r2 = "RD-ctl-" + UUID.randomUUID();
    String off = "RD-ctl-" + UUID.randomUUID();
    Instant now = Instant.now();
    assertThat(
            upload(r1, "k-" + r1, List.of(point(35.1585, 129.0620, 10, now.minusSeconds(5))))
                .getStatusCode()
                .value())
        .isEqualTo(202);
    assertThat(
            upload(off, "k-" + off, List.of(point(35.1586, 129.0621, 10, now.minusSeconds(5))))
                .getStatusCode()
                .value())
        .isEqualTo(202);
    riderApi.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.get(
                urlPathEqualTo("/api/v1/internal/riders/on-duty"))
            .withQueryParam("mfcId", equalTo("MFC-CTL"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        mapper.writeValueAsString(
                            java.util.Map.of(
                                "items",
                                List.of(
                                    java.util.Map.of(
                                        "riderId", r1,
                                        "nameMasked", "김*수",
                                        "vehicleType", "MOTORCYCLE",
                                        "operating",
                                            java.util.Map.of(
                                                "status", "ON_DUTY", "newDispatchEnabled", true),
                                        "restriction", "NORMAL"),
                                    java.util.Map.of(
                                        "riderId",
                                        r2,
                                        "vehicleType",
                                        "BICYCLE",
                                        "operating",
                                        java.util.Map.of(
                                            "status", "ON_DUTY", "newDispatchEnabled", false),
                                        "restriction",
                                        "NORMAL")))))));
    String ops = TestJwt.operator("ro-ctl", List.of("RIDER_OPS"), List.of());
    ResponseEntity<String> res = control(ops, "MFC-CTL");
    assertThat(res.getStatusCode().value()).as(res.getBody()).isEqualTo(200);
    assertThat(res.getHeaders().getCacheControl()).contains("no-store");
    JsonNode items = json(res.getBody()).path("items");
    assertThat(items.size()).isEqualTo(2);
    JsonNode a = items.get(0).path("riderId").asString().equals(r1) ? items.get(0) : items.get(1);
    JsonNode b = a == items.get(0) ? items.get(1) : items.get(0);
    assertThat(a.path("nameMasked").asString()).isEqualTo("김*수");
    assertThat(a.path("location").path("lat").asDouble()).isEqualTo(35.1585);
    assertThat(a.path("stale").asBoolean()).isFalse();
    assertThat(b.path("location").isNull() || b.path("location").isMissingNode()).isTrue();
    assertThat(b.path("stale").asBoolean()).isTrue();
    assertThat(b.path("newDispatchEnabled").asBoolean()).isFalse();
    assertThat(res.getBody()).doesNotContain(off); // 운행 중이 아니면 위치가 있어도 안 보임
    // 관리자도 됨, 상담원·센터 관리자·라이더는 403
    assertThat(
            control(TestJwt.operator("adm", List.of("OPERATOR_ADMIN"), List.of()), "MFC-CTL")
                .getStatusCode()
                .value())
        .isEqualTo(200);
    assertThat(
            control(TestJwt.operator("cs", List.of("CS_AGENT"), List.of()), "MFC-CTL")
                .getStatusCode()
                .value())
        .isEqualTo(403);
    assertThat(
            control(
                    TestJwt.operator("sup", List.of("MFC_SUPERVISOR"), List.of("MFC-CTL")),
                    "MFC-CTL")
                .getStatusCode()
                .value())
        .isEqualTo(403);
    assertThat(control(TestJwt.rider(r1), "MFC-CTL").getStatusCode().value()).isEqualTo(403);
  }

  private ResponseEntity<String> control(String token, String mfcId) {
    return http.get()
        .uri("/rider-locations?mfcId={m}", mfcId)
        .header("Authorization", "Bearer " + token)
        .retrieve()
        .toEntity(String.class);
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

  /**
   * DD-2(2026-11-16): 정한 시각에 가장 가까운 위치 점(오배송 회수지 — 원래 라이더가 완료를 누른 곳), 앞뒤 3분 밖이면 404. 회수 반납
   * (ReturnDelivered)은 회수 라이더 위치 감시를 끝낸다.
   */
  @Test
  void locationAtGivesNearestPointAndReturnDeliveredEndsWatch() {
    String rider = "RD-at-" + UUID.randomUUID();
    Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    upload(
        rider,
        "k-" + rider,
        List.of(
            point(37.5000, 127.0300, 10, now.minusSeconds(300)),
            point(37.5010, 127.0365, 10, now.minusSeconds(120)),
            point(37.5020, 127.0400, 10, now.minusSeconds(30))));
    JsonNode at =
        json(
            http.get()
                .uri(
                    "/internal/riders/{id}/location-at?at={at}",
                    rider,
                    now.minusSeconds(110).toString())
                .header("X-Source-System", "delivery-dispatch-service")
                .retrieve()
                .body(String.class));
    assertThat(at.path("lat").asDouble()).isEqualTo(37.501);
    assertThat(at.path("recordedAt").asString())
        .startsWith(now.minusSeconds(120).toString().substring(0, 19));
    assertThat(
            http.get()
                .uri(
                    "/internal/riders/{id}/location-at?at={at}",
                    rider,
                    now.minusSeconds(1200).toString())
                .retrieve()
                .toEntity(String.class)
                .getStatusCode()
                .value())
        .isEqualTo(404);

    String deliveryId = "DLV-at-" + UUID.randomUUID();
    deliveryEvent(
        "DeliveryAssigned",
        deliveryId,
        Map.of(
            "deliveryId",
            deliveryId,
            "riderId",
            rider,
            "assignmentVersion",
            1,
            "acceptedAt",
            now.toString()));
    awaitCount(
        "SELECT count(*) FROM rider_active_delivery WHERE delivery_id = '"
            + deliveryId
            + "' AND state = 'ACTIVE'");
    deliveryEvent(
        "ReturnDelivered",
        deliveryId,
        Map.of(
            "returnPickupId",
            deliveryId,
            "deliveryId",
            deliveryId,
            "deliveredAt",
            now.toString(),
            "lines",
            List.of(Map.of("orderLineId", "L-1", "qty", 1))));
    awaitCount(
        "SELECT count(*) FROM rider_active_delivery WHERE delivery_id = '"
            + deliveryId
            + "' AND state = 'CLOSED'");
  }

  /**
   * TR-1: 진행 중 배송의 ETA — 픽업 전 라이더 → 픽업지 + 픽업지 → 도착지, 픽업 뒤 라이더 → 도착지. 처음은 내고, 1분 넘게 달라질 때만 다시 낸다.
   */
  @Test
  void etaIsPublishedBeforeAndAfterPickupOnlyWhenItMoves() {
    String rider = "RD-eta-" + UUID.randomUUID();
    String delivery = "DLV-eta-" + UUID.randomUUID();
    upload(
        rider, "k-" + rider, List.of(point(37.5000, 127.0300, 10, Instant.now().minusSeconds(5))));
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
    dispatchApi.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.get(
                urlPathEqualTo("/api/v1/internal/deliveries/" + delivery + "/route-points"))
            .willReturn(
                json(
                    Map.of(
                        "deliveryId",
                        delivery,
                        "pickup",
                        Map.of("lat", 37.5010, "lng", 127.0310),
                        "drop",
                        Map.of("lat", 37.5100, "lng", 127.0400),
                        "pickupToDropS",
                        600))));
    kakao.stubFor(
        post(urlPathEqualTo("/v1/destinations/directions"))
            .willReturn(json(Map.of("routes", List.of(route("0", 0, 300, 120))))));
    Instant before = Instant.now();
    eta.tick();
    List<Map<String, Object>> events = etaEvents(delivery);
    assertThat(events).hasSize(1);
    JsonNode first = json((String) events.get(0).get("payload")).path("payload");
    assertThat(first.path("basis").asString()).isEqualTo("RIDER_TO_PICKUP+PICKUP_TO_DROP");
    assertThat(Instant.parse(first.path("etaPickup").asString()))
        .isBetween(before.plusSeconds(119), Instant.now().plusSeconds(121));
    assertThat(Instant.parse(first.path("etaDrop").asString()))
        .isBetween(before.plusSeconds(719), Instant.now().plusSeconds(721));
    // 그대로면 다시 내지 않는다
    eta.tick();
    assertThat(etaEvents(delivery)).hasSize(1);
    // 픽업 → 라이더 → 도착지(2분) — 10분 넘게 당겨져 다시 낸다
    deliveryEvent(
        "PickupCompleted",
        delivery,
        Map.of("deliveryId", delivery, "riderId", rider, "pickedUpAt", Instant.now().toString()));
    awaitCount(
        "SELECT count(*) FROM rider_active_delivery WHERE delivery_id = '"
            + delivery
            + "' AND picked_up_at IS NOT NULL");
    eta.tick();
    List<Map<String, Object>> after = etaEvents(delivery);
    assertThat(after).hasSize(2);
    JsonNode second = json((String) after.get(1).get("payload")).path("payload");
    assertThat(second.path("basis").asString()).isEqualTo("RIDER_TO_DROP");
    assertThat(second.has("etaPickup")).isFalse();
    assertThat(after.get(1).get("topic")).isEqualTo("delivery.tracking.v1");
    // 좌표는 한 번만 읽는다
    dispatchApi.verify(
        1,
        com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(
            urlPathEqualTo("/api/v1/internal/deliveries/" + delivery + "/route-points")));
  }

  /**
   * TR-3(2026-11-17): 고객 본인 배송만 dropEta, 직원은 pickupEta 도, 라이더 403·남 404. 단계는 배송 사건으로 앞으로만, SSE 는 지금
   * 상태 → 단계 바뀜 → 끝(STREAM_END) 뒤 닫힘.
   */
  @Test
  void etaAndStreamForOwnerAndStaff() throws Exception {
    String rider = "RD-tr3-" + UUID.randomUUID();
    String delivery = "DLV-tr3-" + UUID.randomUUID();
    String orderId = "ORD-tr3-" + UUID.randomUUID();
    String me = "ACC-tr3-" + UUID.randomUUID().toString().substring(0, 8);
    String customer = TestJwt.customer(me);
    String staff = TestJwt.operator("OP-RO", List.of("RIDER_OPS"), List.of());
    String otherCenter = TestJwt.operator("OP-W", List.of("MFC_SUPERVISOR"), List.of("MFC-OTHER"));
    String sameCenter = TestJwt.operator("OP-S", List.of("MFC_SUPERVISOR"), List.of("MFC-TR3"));
    orderApi.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.get(
                urlPathEqualTo("/api/v1/internal/orders/" + orderId))
            .willReturn(json(Map.of("orderId", orderId, "accountId", me))));
    deliveryEvent(
        "DeliveryCreated",
        delivery,
        Map.of(
            "deliveryId",
            delivery,
            "orderId",
            orderId,
            "mfcId",
            "MFC-TR3",
            "type",
            "FORWARD",
            "arrivalWindow",
            Map.of("date", "2026-11-17", "start", "18:00", "end", "19:00"),
            "createdAt",
            Instant.now().toString()));
    awaitCount("SELECT count(*) FROM delivery_progress WHERE delivery_id = '" + delivery + "'");

    JsonNode first = json(etaGet(customer, delivery).getBody());
    assertThat(first.path("stage").asString()).isEqualTo("UNASSIGNED");
    assertThat(first.path("dropEta").path("status").asString()).isEqualTo("ETA_PENDING");
    assertThat(first.path("pickupEta").isObject()).isFalse(); // 고객에게 픽업 예정 없음(null)
    assertThat(first.path("arrivalWindow").path("end").asString()).isEqualTo("19:00");
    assertThat(etaGet(TestJwt.customer("ACC-someone-else"), delivery).getStatusCode().value())
        .isEqualTo(404);
    // 라이더(2026-11-17 §16-4): 배정 전에는 자기 배송이 아니다 — 404, 라이더 아닌 토큰 종류만 403
    assertThat(etaGet(TestJwt.rider(rider), delivery).getStatusCode().value()).isEqualTo(404);
    assertThat(etaGet(otherCenter, delivery).getStatusCode().value()).isEqualTo(404);
    assertThat(etaGet(sameCenter, delivery).getStatusCode().value()).isEqualTo(200);
    assertThat(etaGet(customer, "DLV-none").getStatusCode().value()).isEqualTo(404);

    // 배정 + 위치 + ETA 계산 -> 고객은 도착 예정만, 직원은 픽업 예정도
    upload(
        rider, "k-" + rider, List.of(point(37.5000, 127.0300, 10, Instant.now().minusSeconds(5))));
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
        "SELECT count(*) FROM delivery_progress WHERE delivery_id = '"
            + delivery
            + "' AND stage = 'TO_STORE'");
    awaitCount(
        "SELECT count(*) FROM rider_active_delivery WHERE delivery_id = '"
            + delivery
            + "' AND state = 'ACTIVE'");
    dispatchApi.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.get(
                urlPathEqualTo("/api/v1/internal/deliveries/" + delivery + "/route-points"))
            .willReturn(
                json(
                    Map.of(
                        "deliveryId",
                        delivery,
                        "pickup",
                        Map.of("lat", 37.5010, "lng", 127.0310),
                        "drop",
                        Map.of("lat", 37.5100, "lng", 127.0400),
                        "pickupToDropS",
                        600))));
    kakao.stubFor(
        post(urlPathEqualTo("/v1/destinations/directions"))
            .willReturn(json(Map.of("routes", List.of(route("0", 0, 300, 120))))));
    eta.tick();
    JsonNode mine = json(etaGet(customer, delivery).getBody());
    assertThat(mine.path("dropEta").path("status").asString()).isEqualTo("AVAILABLE");
    assertThat(mine.path("dropEta").path("remainingSeconds").asInt()).isBetween(600, 721);
    assertThat(mine.path("pickupEta").isObject()).isFalse();
    assertThat(mine.toString()).doesNotContain("37.50");
    JsonNode staffView = json(etaGet(staff, delivery).getBody());
    assertThat(staffView.path("pickupEta").path("status").asString()).isEqualTo("AVAILABLE");
    // 배정된 라이더는 직원과 같은 범위(픽업 예정 포함), 다른 라이더는 404
    JsonNode riderView = json(etaGet(TestJwt.rider(rider), delivery).getBody());
    assertThat(riderView.path("pickupEta").path("status").asString()).isEqualTo("AVAILABLE");
    assertThat(riderView.path("dropEta").path("status").asString()).isEqualTo("AVAILABLE");
    assertThat(etaGet(TestJwt.rider("RD-other"), delivery).getStatusCode().value()).isEqualTo(404);
    // 라이더 위치(2026-11-17 사용자 "픽업 뒤 정확한 위치"): 픽업 전 고객 없음, 직원은 배정 뒤
    assertThat(mine.path("riderLocation").isObject()).isFalse();
    assertThat(staffView.path("riderLocation").path("lat").asDouble()).isEqualTo(37.5);

    // SSE: 지금 상태 -> 픽업(STAGE_CHANGED) -> 늦게 온 가게 도착은 무시 -> 완료(STREAM_END) 뒤 닫힘
    java.util.concurrent.BlockingQueue<String> lines = sse(customer, delivery);
    assertThat(awaitLine(lines, "event: ")).isEqualTo("event: ETA_UPDATED");
    deliveryEvent(
        "PickupCompleted",
        delivery,
        Map.of("deliveryId", delivery, "riderId", rider, "pickedUpAt", Instant.now().toString()));
    assertThat(nextEvent(lines)).isEqualTo("event: STAGE_CHANGED");
    assertThat(awaitLine(lines, "data: ")).contains("\"stage\":\"TO_CUSTOMER\"");
    JsonNode picked = json(etaGet(customer, delivery).getBody());
    assertThat(picked.path("riderLocation").path("lat").asDouble()).isEqualTo(37.5);
    assertThat(picked.path("riderLocation").path("lng").asDouble()).isEqualTo(127.03);
    assertThat(picked.path("riderLocation").path("staleSeconds").asInt()).isGreaterThanOrEqualTo(0);
    deliveryEvent(
        "RiderArrivedAtStore",
        delivery,
        Map.of("deliveryId", delivery, "riderId", rider, "arrivedAt", Instant.now().toString()));
    deliveryEvent(
        "DeliveryCompleted",
        delivery,
        Map.of("deliveryId", delivery, "riderId", rider, "completedAt", Instant.now().toString()));
    assertThat(nextEvent(lines)).isEqualTo("event: STAGE_CHANGED");
    assertThat(nextEvent(lines)).isEqualTo("event: STREAM_END");
    assertThat(awaitLine(lines, "<<EOF>>")).isEqualTo("<<EOF>>");
    JsonNode done = json(etaGet(customer, delivery).getBody());
    assertThat(done.path("stage").asString()).isEqualTo("DONE");
    assertThat(done.path("outcome").asString()).isEqualTo("COMPLETED");
    assertThat(done.path("dropEta").path("status").asString()).isEqualTo("FINAL");
    assertThat(done.path("riderLocation").isObject()).isFalse(); // 끝나면 위치 없음
    // 남의 구독은 스트림을 열기 전에 보통 응답(404)
    assertThat(sseStatus(TestJwt.customer("ACC-someone-else"), delivery)).isEqualTo(404);
  }

  private ResponseEntity<String> etaGet(String token, String delivery) {
    return http.get()
        .uri("/deliveries/" + delivery + "/eta")
        .header("Authorization", "Bearer " + token)
        .retrieve()
        .toEntity(String.class);
  }

  private java.net.http.HttpResponse<java.util.stream.Stream<String>> sseOpen(
      String token, String delivery) throws Exception {
    return java.net.http.HttpClient.newHttpClient()
        .send(
            java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create(
                        "http://localhost:" + port + "/api/v1/deliveries/" + delivery + "/stream"))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "text/event-stream")
                .build(),
            java.net.http.HttpResponse.BodyHandlers.ofLines());
  }

  private int sseStatus(String token, String delivery) throws Exception {
    var res = sseOpen(token, delivery);
    res.body().close();
    return res.statusCode();
  }

  private java.util.concurrent.BlockingQueue<String> sse(String token, String delivery)
      throws Exception {
    var res = sseOpen(token, delivery);
    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(res.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
    var q = new java.util.concurrent.LinkedBlockingQueue<String>();
    Thread t =
        new Thread(
            () -> {
              try {
                res.body().forEach(q::add);
              } catch (RuntimeException ignored) {
                // 끊김
              }
              q.add("<<EOF>>");
            });
    t.setDaemon(true);
    t.start();
    return q;
  }

  /** 다음 이벤트 종류(HEARTBEAT 는 건너뜀). */
  private static String nextEvent(java.util.concurrent.BlockingQueue<String> q)
      throws InterruptedException {
    while (true) {
      String line = awaitLine(q, "event: ");
      if (!line.equals("event: HEARTBEAT")) {
        return line;
      }
    }
  }

  private static String awaitLine(java.util.concurrent.BlockingQueue<String> q, String prefix)
      throws InterruptedException {
    long until = System.currentTimeMillis() + 20_000;
    while (System.currentTimeMillis() < until) {
      String line = q.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS);
      if (line != null && (line.startsWith(prefix) || "<<EOF>>".equals(line))) {
        return line;
      }
    }
    throw new AssertionError("SSE 줄이 오지 않음: " + prefix);
  }

  private List<Map<String, Object>> etaEvents(String delivery) {
    return jdbc.queryForList(
        "SELECT payload::text AS payload, topic FROM outbox_event WHERE aggregateid = ? AND type ="
            + " 'DeliveryEtaUpdated' ORDER BY created_at, id",
        delivery);
  }

  private void deliveryEvent(String type, String deliveryId, Map<String, Object> payload) {
    String json =
        EventEnvelopeFixtures.envelope(type)
            .from(
                type.equals("DeliveryCompleted") || type.equals("ReturnDelivered")
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
