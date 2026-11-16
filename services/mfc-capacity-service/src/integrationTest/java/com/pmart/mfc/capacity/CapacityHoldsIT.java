package com.pmart.mfc.capacity;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import com.pmart.mfc.capacity.application.HoldExpirySweeper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 작업 여력 Hold(S1 c): 확보·확정·해제, 여력 초과, 마지막 1단위 동시 경합, 멱등 재생, TTL 만료 정리. 창당 3단위(시험값)로 시험하고 시험마다 다른 창을
 * 쓴다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.capacity.units-per-hour=3",
      "pmart.capacity.expiry-interval=PT1H"
    })
@Import({TestJwtConfiguration.class, CapacityHoldsIT.ClockConfig.class})
class CapacityHoldsIT extends PostgresKafkaContainerSupport {

  static final String SERVICE = TestJwt.service("order-orchestration-service");

  /** 시험이 앞으로 돌릴 수 있는 시계. */
  static final class MutableClock extends Clock {
    volatile Instant now = Instant.parse("2026-10-30T03:00:00Z");

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class ClockConfig {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock();
    }
  }

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired MutableClock clock;
  @Autowired HoldExpirySweeper sweeper;

  RestClient http;

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1/internal")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
  }

  record Res(int status, JsonNode body) {}

  Res post(String path, Object body) {
    return post(SERVICE, path, key(), body, null);
  }

  Res post(String token, String path, String key, Object body, @Nullable Integer expectedVersion) {
    var e =
        http.post()
            .uri(path)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", key);
                  h.set("X-Source-System", "order-orchestration-service");
                  if (expectedVersion != null) {
                    h.set("X-Expected-Version", expectedVersion.toString());
                  }
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapper.writeValueAsString(body))
            .retrieve()
            .toEntity(String.class);
    return res(e);
  }

  Res get(String path, Object... vars) {
    var e =
        http.get()
            .uri(path, vars)
            .headers(h -> h.setBearerAuth(SERVICE))
            .retrieve()
            .toEntity(String.class);
    return res(e);
  }

  Res res(ResponseEntity<String> e) {
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  /** 2026-11-02 의 hourKst:00 KST 1시간 창(UTC 표기). */
  static String window(int hourKst) {
    Instant start = Instant.parse("2026-11-01T15:00:00Z").plus(Duration.ofHours(hourKst));
    return start + "/" + start.plus(Duration.ofHours(1));
  }

  static Map<String, Object> holdBody(String orderId, String window) {
    return Map.of(
        "orderId",
        orderId,
        "mfcId",
        "MFC-1",
        "window",
        window,
        "demand",
        Map.of("lineCount", 2, "itemCount", 3, "weightG", 1200));
  }

  static String key() {
    return "k-" + UUID.randomUUID();
  }

  static String order() {
    return "O-" + UUID.randomUUID();
  }

  Res snapshot(String window) {
    return get("/capacity-snapshots?mfcId=MFC-1&window={w}", window);
  }

  Res confirm(String holdId, String orderId, @Nullable Integer expectedVersion) {
    return post(
        SERVICE,
        "/capacity-holds/" + holdId + "/confirmations",
        key(),
        Map.of("orderId", orderId),
        expectedVersion);
  }

  Res restriction(String token, String method, String pathAndQuery, @Nullable Object body) {
    var spec =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build()
            .method(org.springframework.http.HttpMethod.valueOf(method))
            .uri(pathAndQuery)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", key());
                  h.set("X-Source-System", "backoffice");
                });
    var e =
        (body == null
                ? spec
                : spec.contentType(MediaType.APPLICATION_JSON)
                    .body(mapper.writeValueAsString(body)))
            .retrieve()
            .toEntity(String.class);
    return res(e);
  }

  static String start(String window) {
    return window.substring(0, window.indexOf('/'));
  }

  /** 운영 제한(BO-08): 축소는 기존 Hold 유지·넘친 창 주문 표시·신규 판정에 반영, 접수 중지는 신규 Hold 거절, 해제하면 다시 받는다. */
  @Test
  void restrictionsStopIntakeAndReduceCapacityWithoutDroppingExistingHolds() {
    String sup = TestJwt.operator("sup", List.of("MFC_SUPERVISOR"), List.of("MFC-1"));
    String worker = TestJwt.operator("w", List.of("MFC_WORKER"), List.of("MFC-1"));
    String w20 = window(20);
    String w21 = window(21);
    String a = order();
    String b = order();
    assertThat(post("/capacity-holds", holdBody(a, w20)).status()).isEqualTo(201);
    assertThat(post("/capacity-holds", holdBody(b, w20)).status()).isEqualTo(201);

    Map<String, Object> reduce = new java.util.LinkedHashMap<>();
    reduce.put("mfcId", "MFC-1");
    reduce.put("restrictionType", "CAPACITY_REDUCE");
    reduce.put("approvalRef", "BO8-1");
    reduce.put("effectiveFrom", start(w20));
    reduce.put("effectiveTo", start(w21));
    reduce.put("reducedCapacityUnits", 1);
    assertThat(restriction(worker, "POST", "/operation-restrictions", reduce).status())
        .isEqualTo(403);
    Res r = restriction(sup, "POST", "/operation-restrictions", reduce);
    assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
    assertThat(r.body().path("appliedQty").asInt()).isEqualTo(2);
    String reduceId = r.body().path("resultId").asString();
    JsonNode got = restriction(sup, "GET", "/operation-restrictions/" + reduceId, null).body();
    assertThat(got.path("state").asString()).isEqualTo("ACTIVE");
    assertThat(got.path("affectedOrderIds").size()).isEqualTo(2);
    // 기존 Hold 는 그대로, 수용 1·남은 0, 신규는 여력 초과
    JsonNode snap = snapshot(w20).body();
    assertThat(snap.path("capacityUnits").asInt()).isEqualTo(1);
    assertThat(snap.path("heldUnits").asInt()).isEqualTo(2);
    assertThat(snap.path("availableUnits").asInt()).isZero();
    assertThat(snap.path("activeRestrictions").path(0).asString()).isEqualTo(reduceId);
    assertThat(post("/capacity-holds", holdBody(order(), w20)).body().path("code").asString())
        .isEqualTo("CAPACITY_EXCEEDED");
    // 범위 밖 창은 그대로
    assertThat(snapshot(w21).body().path("capacityUnits").asInt()).isEqualTo(3);

    // 접수 중지(끝 없음) — 근거 없으면 거절
    Map<String, Object> stop = new java.util.LinkedHashMap<>();
    stop.put("mfcId", "MFC-1");
    stop.put("restrictionType", "INTAKE_STOP");
    stop.put("approvalRef", " ");
    stop.put("effectiveFrom", start(w21));
    assertThat(
            restriction(sup, "POST", "/operation-restrictions", stop)
                .body()
                .path("code")
                .asString())
        .isEqualTo("APPROVAL_REQUIRED");
    stop.put("approvalRef", "BO8-2");
    String stopId =
        restriction(sup, "POST", "/operation-restrictions", stop)
            .body()
            .path("resultId")
            .asString();
    Res refused = post("/capacity-holds", holdBody(order(), w21));
    assertThat(refused.status()).isEqualTo(422);
    assertThat(refused.body().path("code").asString()).isEqualTo("OPERATION_RESTRICTED");
    assertThat(snapshot(w21).body().path("availableUnits").asInt()).isZero();
    assertThat(
            restriction(sup, "GET", "/operation-restrictions?mfcId=MFC-1", null)
                .body()
                .path("items")
                .size())
        .isGreaterThanOrEqualTo(2);

    // 해제하면 다시 받는다(축소는 아직 남음 — 하나 해제해도 나머지 유지)
    assertThat(
            restriction(
                    sup, "DELETE", "/operation-restrictions/" + stopId + "?approvalRef=BO8-3", null)
                .status())
        .isEqualTo(202);
    assertThat(post("/capacity-holds", holdBody(order(), w21)).status()).isEqualTo(201);
    assertThat(snapshot(w20).body().path("capacityUnits").asInt()).isEqualTo(1);
    restriction(sup, "DELETE", "/operation-restrictions/" + reduceId + "?approvalRef=BO8-4", null);
    assertThat(snapshot(w20).body().path("capacityUnits").asInt()).isEqualTo(3);
  }

  @Test
  void holdConfirmRelease() {
    String w = window(10);
    String orderId = order();
    String k = key();
    Res held = post(SERVICE, "/capacity-holds", k, holdBody(orderId, w), null);
    assertThat(held.status()).isEqualTo(201);
    assertThat(held.body().path("status").asString()).isEqualTo("APPLIED");
    String holdId = held.body().path("resultId").asString();
    assertThat(holdId).startsWith("CH-");

    // 같은 키·같은 본문 → 같은 결과(새 Hold 없음)
    Res replay = post(SERVICE, "/capacity-holds", k, holdBody(orderId, w), null);
    assertThat(replay.status()).isEqualTo(201);
    assertThat(replay.body().path("resultId").asString()).isEqualTo(holdId);
    // 같은 키·다른 본문 → IDEMPOTENCY_CONFLICT
    Res conflict = post(SERVICE, "/capacity-holds", k, holdBody(order(), w), null);
    assertThat(conflict.status()).isEqualTo(409);
    assertThat(conflict.body().path("code").asString()).isEqualTo("IDEMPOTENCY_CONFLICT");
    // 같은 주문·다른 키 → 주문당 활성 Hold 1개
    Res dup = post("/capacity-holds", holdBody(orderId, w));
    assertThat(dup.status()).isEqualTo(409);
    assertThat(dup.body().path("code").asString()).isEqualTo("HOLD_ALREADY_ACTIVE");

    Res snap = snapshot(w);
    assertThat(snap.status()).isEqualTo(200);
    assertThat(snap.body().path("capacityUnits").asInt()).isEqualTo(3);
    assertThat(snap.body().path("heldUnits").asInt()).isEqualTo(1);
    assertThat(snap.body().path("availableUnits").asInt()).isEqualTo(2);
    assertThat(snap.body().path("stages").path(0).path("stage").asString()).isEqualTo("WORKFORCE");

    Res got = get("/capacity-holds/" + holdId);
    assertThat(got.body().path("state").asString()).isEqualTo("HELD");
    assertThat(got.body().path("units").asInt()).isEqualTo(1);
    assertThat(got.body().path("window").asString()).isEqualTo(w);

    // 확정: 주문 불일치 400, 기대 버전 불일치 409, 정상 → CONFIRMED, 다시 → 멱등 성공
    assertThat(confirm(holdId, "X", null).status()).isEqualTo(400);
    assertThat(confirm(holdId, orderId, 7).body().path("code").asString())
        .isEqualTo("VERSION_CONFLICT");
    Res confirmed = confirm(holdId, orderId, 1);
    assertThat(confirmed.status()).isEqualTo(200);
    assertThat(confirmed.body().path("version").asInt()).isEqualTo(2);
    assertThat(confirm(holdId, orderId, null).status()).isEqualTo(200);
    assertThat(get("/capacity-holds/" + holdId).body().path("state").asString())
        .isEqualTo("CONFIRMED");
    assertThat(snapshot(w).body().path("confirmedUnits").asInt()).isEqualTo(1);

    // CONFIRMED 해제는 reason 필수
    Res noReason = post("/capacity-holds/" + holdId + "/releases", Map.of());
    assertThat(noReason.status()).isEqualTo(400);
    assertThat(noReason.body().path("code").asString()).isEqualTo("VALIDATION_FAILED");
    Res released =
        post(
            "/capacity-holds/" + holdId + "/releases",
            Map.of("reason", "ORDER_CANCELLED", "sourceRef", "saga-1"));
    assertThat(released.status()).isEqualTo(200);
    got = get("/capacity-holds/" + holdId);
    assertThat(got.body().path("state").asString()).isEqualTo("RELEASED");
    assertThat(got.body().path("releaseReason").asString()).isEqualTo("ORDER_CANCELLED");
    // 해제 후: 단위 반환, 재해제 멱등, 확정 불가
    assertThat(snapshot(w).body().path("availableUnits").asInt()).isEqualTo(3);
    assertThat(post("/capacity-holds/" + holdId + "/releases", Map.of()).status()).isEqualTo(200);
    Res late = confirm(holdId, orderId, null);
    assertThat(late.status()).isEqualTo(409);
    assertThat(late.body().path("code").asString()).isEqualTo("INVALID_STATE");
    // 해제된 주문은 다시 Hold 할 수 있다
    assertThat(post("/capacity-holds", holdBody(orderId, w)).status()).isEqualTo(201);

    assertThat(get("/capacity-holds/CH-none").status()).isEqualTo(404);
  }

  /** S4(2026-11-16): 확정 Hold 를 두고 다른 창 새 HELD(두 창 모두 단위) → 새 Hold 확정 때 기존 RELEASED(RESCHEDULED). */
  @Test
  void s4ReplacementHoldsNewWindowAndReleasesOldOnConfirm() {
    String w14 = window(14);
    String w15 = window(15);
    String orderId = order();
    String old = post("/capacity-holds", holdBody(orderId, w14)).body().path("resultId").asString();
    java.util.Map<String, Object> early = new java.util.HashMap<>(holdBody(orderId, w15));
    early.put("replacesHoldId", old);
    assertThat(post("/capacity-holds", early).status()).isEqualTo(409); // 확정 전
    assertThat(confirm(old, orderId, null).status()).isEqualTo(200);
    java.util.Map<String, Object> same = new java.util.HashMap<>(holdBody(orderId, w14));
    same.put("replacesHoldId", old);
    assertThat(post("/capacity-holds", same).body().path("code").asString())
        .isEqualTo("SAME_WINDOW");
    java.util.Map<String, Object> change = new java.util.HashMap<>(holdBody(orderId, w15));
    change.put("replacesHoldId", old);
    Res held = post("/capacity-holds", change);
    assertThat(held.status()).isEqualTo(201);
    String fresh = held.body().path("resultId").asString();
    assertThat(snapshot(w14).body().path("availableUnits").asInt()).isEqualTo(2);
    assertThat(snapshot(w15).body().path("availableUnits").asInt()).isEqualTo(2);
    assertThat(post("/capacity-holds", change).body().path("code").asString())
        .isEqualTo("HOLD_ALREADY_ACTIVE"); // 진행 중 교체
    assertThat(confirm(fresh, orderId, null).status()).isEqualTo(200);
    Res was = get("/capacity-holds/" + old);
    assertThat(was.body().path("state").asString()).isEqualTo("RELEASED");
    assertThat(was.body().path("releaseReason").asString()).isEqualTo("RESCHEDULED");
    assertThat(get("/capacity-holds/" + fresh).body().path("state").asString())
        .isEqualTo("CONFIRMED");
    assertThat(snapshot(w14).body().path("availableUnits").asInt()).isEqualTo(3);
  }

  @Test
  void capacityExceededAndValidation() {
    String w = window(11);
    for (int i = 0; i < 3; i++) {
      assertThat(post("/capacity-holds", holdBody(order(), w)).status()).isEqualTo(201);
    }
    Res full = post("/capacity-holds", holdBody(order(), w));
    assertThat(full.status()).isEqualTo(422);
    assertThat(full.body().path("code").asString()).isEqualTo("CAPACITY_EXCEEDED");
    assertThat(snapshot(w).body().path("availableUnits").asInt()).isZero();
    // 다른 창은 별개
    assertThat(post("/capacity-holds", holdBody(order(), window(12))).status()).isEqualTo(201);

    // 정시 1시간 창이 아니면 400
    Res bad =
        post("/capacity-holds", holdBody(order(), "2026-11-02T01:30:00Z/2026-11-02T02:30:00Z"));
    assertThat(bad.status()).isEqualTo(400);
    assertThat(snapshot("2026-11-02T01:00:00Z/2026-11-02T03:00:00Z").status()).isEqualTo(400);
    // 서비스 토큰이 아니면 403
    assertThat(
            post(TestJwt.customer("CU-1"), "/capacity-holds", key(), holdBody(order(), w), null)
                .status())
        .isEqualTo(403);
  }

  @Test
  void concurrentLastUnitOnlyOneWins() throws Exception {
    String w = window(13);
    for (int i = 0; i < 2; i++) {
      assertThat(post("/capacity-holds", holdBody(order(), w)).status()).isEqualTo(201);
    }
    var pool = Executors.newFixedThreadPool(4);
    List<Integer> statuses = new ArrayList<>();
    try {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<Integer>> results = new ArrayList<>();
      for (int i = 0; i < 4; i++) {
        Callable<Integer> c =
            () -> {
              start.await();
              return post("/capacity-holds", holdBody(order(), w)).status();
            };
        results.add(pool.submit(c));
      }
      start.countDown();
      for (Future<Integer> f : results) {
        statuses.add(f.get());
      }
    } finally {
      pool.shutdownNow();
    }
    assertThat(statuses).containsOnly(201, 422);
    assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
    Res snap = snapshot(w);
    assertThat(snap.body().path("heldUnits").asInt()).isEqualTo(3);
    assertThat(snap.body().path("availableUnits").asInt()).isZero();
  }

  @Test
  void expiredHoldsReturnUnits() {
    String w = window(14);
    Res a =
        post(
            "/capacity-holds",
            Map.of(
                "orderId",
                order(),
                "mfcId",
                "MFC-1",
                "window",
                w,
                "ttlMinutes",
                1,
                "demand",
                Map.of("lineCount", 1)));
    assertThat(a.status()).isEqualTo(201);
    String holdA = a.body().path("resultId").asString();
    String orderB = order();
    String holdB = post("/capacity-holds", holdBody(orderB, w)).body().path("resultId").asString();
    assertThat(snapshot(w).body().path("heldUnits").asInt()).isEqualTo(2);

    Instant base = clock.now;
    try {
      clock.now = base.plus(Duration.ofMinutes(2));
      // 정리 전이라도 만료 시각이 지난 HELD 는 점유로 세지 않는다
      assertThat(snapshot(w).body().path("heldUnits").asInt()).isEqualTo(1);
      assertThat(sweeper.expireDue()).isGreaterThanOrEqualTo(1);
      assertThat(get("/capacity-holds/" + holdA).body().path("state").asString())
          .isEqualTo("EXPIRED");
      assertThat(get("/capacity-holds/" + holdB).body().path("state").asString()).isEqualTo("HELD");
      // 만료 Hold 해제는 멱등 성공(상태 불변)
      assertThat(post("/capacity-holds/" + holdA + "/releases", Map.of()).status()).isEqualTo(200);
      assertThat(get("/capacity-holds/" + holdA).body().path("state").asString())
          .isEqualTo("EXPIRED");

      clock.now = base.plus(Duration.ofMinutes(11));
      // 만료 시각이 지난 HELD(정리 전) 확정 → INVALID_STATE
      Res late = confirm(holdB, orderB, null);
      assertThat(late.status()).isEqualTo(409);
      assertThat(late.body().path("code").asString()).isEqualTo("INVALID_STATE");
      // 같은 주문 재시도는 만료된 Hold 를 정리하고 새로 잡는다
      assertThat(post("/capacity-holds", holdBody(orderB, w)).status()).isEqualTo(201);
      assertThat(get("/capacity-holds/" + holdB).body().path("state").asString())
          .isEqualTo("EXPIRED");
      assertThat(snapshot(w).body().path("heldUnits").asInt()).isEqualTo(1);
    } finally {
      clock.now = base;
    }
  }
}
