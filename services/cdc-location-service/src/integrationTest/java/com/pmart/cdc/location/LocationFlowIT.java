package com.pmart.cdc.location;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 위치 마스터 흐름(0.2.0): 칠곡 문법 위반 일괄 등록 거절(전부 또는 전무) → 일괄 등록·ACTIVE → 재생 → 이미 있는 코드 409 → 운영자 단건
 * 등록(DRAFT)·다른 센터 403 → 조회(구간·점유 모름) → 거르기·쪽 나누기 → 상태 전환(BLOCK·LocStateChanged, 버전 충돌, RETIRE 규칙).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "pmart.messaging.outbox.delete-after-insert=false",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks"
    })
@Import(TestJwtConfiguration.class)
class LocationFlowIT extends PostgresKafkaContainerSupport {

  static final String CENTER = "CDC01";
  static final String OPERATOR = TestJwt.operator("op-1", List.of("CDC_MANAGER"), List.of(CENTER));
  static final String OTHER = TestJwt.operator("op-2", List.of("CDC_MANAGER"), List.of("CDC02"));
  static final String SERVICE = TestJwt.service("backoffice-bff");

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;

  RestClient http;

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    jdbc.update("DELETE FROM location_state_history");
    jdbc.update("DELETE FROM dock_reservation");
    jdbc.update("DELETE FROM aisle_pick_method");
    jdbc.update("DELETE FROM location");
  }

  record Res(int status, JsonNode body) {}

  Res post(String token, String path, @Nullable String key, Object body, Integer expectedVersion) {
    var e =
        http.post()
            .uri(path)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  if (key != null) {
                    h.set("Idempotency-Key", key);
                  }
                  h.set("X-Source-System", "backoffice-web");
                  if (expectedVersion != null) {
                    h.set("X-Expected-Version", String.valueOf(expectedVersion));
                  }
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toEntity(String.class);
    return new Res(e.getStatusCode().value(), read(e.getBody()));
  }

  Res get(String token, String path) {
    var e =
        http.get().uri(path).headers(h -> h.setBearerAuth(token)).retrieve().toEntity(String.class);
    return new Res(e.getStatusCode().value(), read(e.getBody()));
  }

  JsonNode read(String body) {
    return mapper.readTree(body == null || body.isBlank() ? "{}" : body);
  }

  static String key() {
    return UUID.randomUUID().toString();
  }

  static Map<String, Object> loc(String code, String temp, String type) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("locationCode", code);
    m.put("centerId", CENTER);
    m.put("temperatureZone", temp);
    m.put("locationType", type);
    m.put("capacity", Map.of("unit", "PLT", "maxQty", 1, "maxWeightG", 1_000_000));
    return m;
  }

  /** 피킹 선반 칸(R-13) — 최소·목표량은 설정값이라 비운다. */
  static Map<String, Object> shelf(String code) {
    Map<String, Object> m = loc(code, "AMBIENT", "PICK_FACE");
    m.put("capacity", Map.of("unit", "EACH"));
    return m;
  }

  /** 상온 C 5층 1번 통로 베이 01~02 × 단 1~4 × 슬롯 1~2 = 16칸(RESERVE) + 출고대기 1 + 지하 도크 1. */
  static List<Map<String, Object>> layout() {
    List<Map<String, Object>> items = new ArrayList<>();
    for (int bay = 1; bay <= 2; bay++) {
      for (int level = 1; level <= 4; level++) {
        for (int slot = 1; slot <= 2; slot++) {
          items.add(
              loc(
                  String.format("CDC01-A-5-01-%02d-%d-%d", bay, level, slot),
                  "AMBIENT",
                  "RESERVE"));
        }
      }
    }
    items.add(loc("CDC01-A-5-91-01-0-1", "AMBIENT", "STAGING"));
    items.add(loc("CDC01-G-6-90-03-0-1", "CHILLED", "DOCK"));
    return items;
  }

  int outbox(String code) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM outbox_event WHERE type = 'LocStateChanged' AND aggregateid = ?"
                + " AND topic = 'cdc.location.v1'",
            Integer.class,
            code);
    return n == null ? 0 : n;
  }

  /**
   * 통로별 피킹 방식(0.5.0, 묶음 피킹 설계 §7 결정 3): 6층 B 선반 61 = 분배·62 = 라인, 팔레트 랙 통로는 거절, 일괄 조회에 실리고 해제하면
   * 비워진다(기본값으로 채우지 않음).
   */
  @Test
  void aislePickMethodsReachLookup() {
    List<Map<String, Object>> items = new ArrayList<>();
    for (String c : List.of("CDC01-G-6-61-01-1-1", "CDC01-G-6-62-01-1-1")) {
      Map<String, Object> m = loc(c, "CHILLED", "PICK_FACE");
      m.put("capacity", Map.of("unit", "EACH"));
      items.add(m);
    }
    items.add(loc("CDC01-G-6-01-01-1-1", "CHILLED", "RESERVE"));
    assertThat(
            post(
                    SERVICE,
                    "/internal/location-batches",
                    key(),
                    Map.of(
                        "centerId",
                        CENTER,
                        "approvalRef",
                        "BO-06-2",
                        "activate",
                        true,
                        "items",
                        items),
                    null)
                .status())
        .isEqualTo(200);
    String path = "/cdcs/CDC01/aisle-pick-methods";
    Map<String, Object> set =
        Map.of(
            "reason",
            "6층 야채 통로",
            "items",
            List.of(
                Map.of("area", "G", "floor", "6", "aisle", "61", "pickMethod", "DISTRIBUTION"),
                Map.of("area", "G", "floor", "6", "aisle", "62", "pickMethod", "LINE")));
    String k = key();
    Res ok = post(OPERATOR, path, k, set, null);
    assertThat(ok.status()).as(ok.body().toString()).isEqualTo(200);
    assertThat(ok.body().path("setCount").asInt()).isEqualTo(2);
    assertThat(post(OPERATOR, path, k, set, null).status()).isEqualTo(200); // 같은 키 재생
    assertThat(post(OTHER, path, key(), set, null).status()).isEqualTo(403);
    Res rack =
        post(
            OPERATOR,
            path,
            key(),
            Map.of(
                "items",
                List.of(Map.of("area", "G", "floor", "6", "aisle", "01", "pickMethod", "FC"))),
            null);
    assertThat(rack.status()).isEqualTo(400);
    assertThat(rack.body().toString()).contains("NOT_PICK_FACE_AISLE");
    Res dup =
        post(
            OPERATOR,
            path,
            key(),
            Map.of(
                "items",
                List.of(
                    Map.of("area", "G", "floor", "6", "aisle", "61", "pickMethod", "FC"),
                    Map.of("area", "G", "floor", "6", "aisle", "61", "pickMethod", "LINE"))),
            null);
    assertThat(dup.body().toString()).contains("DUPLICATE_AISLE");

    Map<String, Object> lookup =
        Map.of(
            "centerId",
            CENTER,
            "locationCodes",
            List.of("CDC01-G-6-61-01-1-1", "CDC01-G-6-62-01-1-1", "CDC01-G-6-01-01-1-1"));
    JsonNode lk = post(SERVICE, "/internal/location-lookups", key(), lookup, null).body();
    Map<String, String> methods = new LinkedHashMap<>();
    for (JsonNode i : lk.path("items")) {
      methods.put(
          i.path("locationCode").asString(),
          i.path("pickMethod").isMissingNode() || i.path("pickMethod").isNull()
              ? null
              : i.path("pickMethod").asString());
    }
    assertThat(methods)
        .containsEntry("CDC01-G-6-61-01-1-1", "DISTRIBUTION")
        .containsEntry("CDC01-G-6-62-01-1-1", "LINE")
        .containsEntry("CDC01-G-6-01-01-1-1", null);

    // 해제 → 조회에서 비워짐, 목록엔 61 만
    Map<String, Object> clear = new LinkedHashMap<>();
    clear.put("area", "G");
    clear.put("floor", "6");
    clear.put("aisle", "62");
    clear.put("pickMethod", null);
    Res cl = post(OPERATOR, path, key(), Map.of("items", List.of(clear)), null);
    assertThat(cl.status()).as(cl.body().toString()).isEqualTo(200);
    assertThat(cl.body().path("clearedCount").asInt()).isEqualTo(1);
    JsonNode after = post(SERVICE, "/internal/location-lookups", key(), lookup, null).body();
    assertThat(after.toString()).doesNotContain("LINE").contains("DISTRIBUTION");
    JsonNode list = get(OPERATOR, path + "?floor=6").body();
    assertThat(list.path("items").size()).isEqualTo(1);
    assertThat(list.path("items").get(0).path("pickMethod").asString()).isEqualTo("DISTRIBUTION");
    assertThat(list.path("items").get(0).path("updatedBy").asString()).isEqualTo("op-1");
  }

  @Test
  void batchCreateQueryAndStateChanges() {
    // 1) 한 줄이라도 어기면 전부 거절(냉동 A 글자에 AMBIENT, 5단)
    List<Map<String, Object>> bad = new ArrayList<>(layout());
    bad.add(loc("CDC01-H-5-01-01-3-1", "AMBIENT", "RESERVE"));
    bad.add(loc("CDC01-A-5-02-01-5-1", "AMBIENT", "RESERVE"));
    Res rejected =
        post(
            SERVICE,
            "/internal/location-batches",
            key(),
            Map.of("centerId", CENTER, "approvalRef", "BO-06-1", "activate", true, "items", bad),
            null);
    assertThat(rejected.status()).isEqualTo(400);
    assertThat(rejected.body().toString())
        .contains("TEMPERATURE_ZONE_MISMATCH")
        .contains("RACK_LEVEL");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM location", Integer.class)).isZero();

    // 2) 일괄 등록 ACTIVE 18칸, 같은 키 재생
    Map<String, Object> batch =
        Map.of("centerId", CENTER, "approvalRef", "BO-06-1", "activate", true, "items", layout());
    String batchKey = key();
    Res ok = post(SERVICE, "/internal/location-batches", batchKey, batch, null);
    assertThat(ok.status()).as(ok.body().toString()).isEqualTo(200);
    assertThat(ok.body().path("appliedQty").asInt()).isEqualTo(18);
    assertThat(post(SERVICE, "/internal/location-batches", batchKey, batch, null).status())
        .isEqualTo(200);
    Res dup = post(SERVICE, "/internal/location-batches", key(), batch, null);
    assertThat(dup.status()).isEqualTo(409);
    assertThat(dup.body().path("code").asString()).isEqualTo("LOCATION_CODE_EXISTS");

    // 2-1) 내부 일괄 조회(0.4.0) — 유형·상태, 없는 코드는 missing
    Res lk =
        post(
            SERVICE,
            "/internal/location-lookups",
            key(),
            Map.of(
                "centerId",
                CENTER,
                "locationCodes",
                List.of("CDC01-A-5-01-01-1-1", "CDC01-A-5-91-01-0-1", "CDC01-A-5-99-01-0-1")),
            null);
    assert lk.status() == 200 : lk.body();
    assertThat(lk.body().path("items").size()).isEqualTo(2);
    assertThat(lk.body().toString()).contains("STAGING").contains("RESERVE");
    assertThat(lk.body().path("missing").get(0).asString()).isEqualTo("CDC01-A-5-99-01-0-1");
    // POST 라 공통 헤더 검사가 키를 요구한다(0.4.1 계약 — LAN #462 배포에서 확인된 것)
    assertThat(
            post(
                    SERVICE,
                    "/internal/location-lookups",
                    null,
                    Map.of("centerId", CENTER, "locationCodes", List.of("CDC01-A-5-01-01-1-1")),
                    null)
                .status())
        .isEqualTo(400);

    // 3) 운영자 단건 등록(DRAFT), 다른 센터 운영자 403
    Res one = post(OPERATOR, "/cdcs/CDC01/locations", key(), shelf("CDC01-E-7-61-01-3-1"), null);
    assertThat(one.status()).as(one.body().toString()).isEqualTo(201);
    assertThat(one.body().path("state").asString()).isEqualTo("DRAFT");
    assertThat(
            post(
                    OTHER,
                    "/cdcs/CDC01/locations",
                    key(),
                    loc("CDC01-E-7-01-01-4-1", "AMBIENT", "RESERVE"),
                    null)
                .status())
        .isEqualTo(403);

    Map<String, Object> wrongCenter = loc("CDC01-E-7-01-01-4-2", "AMBIENT", "RESERVE");
    wrongCenter.put("centerId", "CDC02");
    assertThat(post(OPERATOR, "/cdcs/CDC01/locations", key(), wrongCenter, null).status())
        .isEqualTo(400);

    // 4) 조회 — 구간 나눔, 점유는 모름(null), 신선도 UNAVAILABLE
    JsonNode g = get(OPERATOR, "/cdcs/CDC01/locations/CDC01-A-5-01-02-1-2").body();
    assertThat(g.path("state").asString()).isEqualTo("ACTIVE");
    assertThat(g.path("version").asInt()).isEqualTo(2);
    assertThat(g.path("locationType").asString()).isEqualTo("RESERVE");
    assertThat(g.path("parts").path("floor").asString()).isEqualTo("5");
    assertThat(g.path("parts").path("level").asInt()).isEqualTo(1);
    assertThat(g.path("occupiedQty").isNull()).isTrue();
    assertThat(g.path("asOf").path("freshness").asString()).isEqualTo("UNAVAILABLE");
    assertThat(get(OTHER, "/cdcs/CDC01/locations/CDC01-A-5-01-02-1-2").status()).isEqualTo(403);

    // 5) 거르기·쪽 나누기
    JsonNode staging =
        get(OPERATOR, "/cdcs/CDC01/locations?locationType=STAGING").body().path("items");
    assertThat(staging.size()).isEqualTo(1);
    JsonNode page1 = get(OPERATOR, "/cdcs/CDC01/locations?area=A&floor=5&size=10").body();
    assertThat(page1.path("items").size()).isEqualTo(10);
    String cursor = page1.path("page").path("nextCursor").asString();
    JsonNode page2 =
        get(OPERATOR, "/cdcs/CDC01/locations?area=A&floor=5&size=10&cursor=" + cursor).body();
    assertThat(page2.path("items").size()).isEqualTo(7); // 랙 16 + 출고대기 1 = 17
    assertThat(page2.path("page").path("nextCursor").isNull()).isTrue();

    // 6) 상태 전환 BLOCK → 사건, 옛 버전은 409, ACTIVE 폐기 불가, DRAFT 폐기는 승인 필요
    String code = "CDC01-A-5-01-01-3-1";
    Res block =
        post(
            OPERATOR,
            "/cdcs/CDC01/locations/" + code + "/state-changes",
            key(),
            Map.of("action", "BLOCK", "reason", "랙 점검"),
            2);
    assertThat(block.status()).as(block.body().toString()).isEqualTo(202);
    assertThat(block.body().path("version").asInt()).isEqualTo(3);
    assertThat(outbox(code)).isEqualTo(1);
    Res stale =
        post(
            OPERATOR,
            "/cdcs/CDC01/locations/" + code + "/state-changes",
            key(),
            Map.of("action", "UNBLOCK", "reason", "끝"),
            2);
    assertThat(stale.status()).isEqualTo(409);
    assertThat(stale.body().path("code").asString()).isEqualTo("VERSION_CONFLICT");
    Res retireActive =
        post(
            OPERATOR,
            "/cdcs/CDC01/locations/CDC01-A-5-01-02-3-1/state-changes",
            key(),
            Map.of("action", "RETIRE", "reason", "철거", "approvalRef", "AP-1"),
            null);
    assertThat(retireActive.body().path("code").asString()).isEqualTo("INVALID_STATE");
    Res noApproval =
        post(
            OPERATOR,
            "/cdcs/CDC01/locations/CDC01-E-7-61-01-3-1/state-changes",
            key(),
            Map.of("action", "RETIRE", "reason", "잘못 등록"),
            null);
    assertThat(noApproval.status()).isEqualTo(422);
    Res retire =
        post(
            OPERATOR,
            "/cdcs/CDC01/locations/CDC01-E-7-61-01-3-1/state-changes",
            key(),
            Map.of("action", "RETIRE", "reason", "잘못 등록", "approvalRef", "AP-2"),
            null);
    assertThat(retire.status()).isEqualTo(202);
    assertThat(
            get(OPERATOR, "/cdcs/CDC01/locations/CDC01-E-7-61-01-3-1")
                .body()
                .path("state")
                .asString())
        .isEqualTo("RETIRED");
  }

  /** 0.7.0 공간 예약(현물 + 미완료 예약 + 신규 ≤ maxQty)·해제·소비, 피킹 선반 최소·목표 설정·목록. */
  @Test
  void spaceReservationAndPickFaceSettings() {
    String a = "CDC01-A-5-61-20-1-1";
    String b = "CDC01-A-5-61-20-1-2";
    Map<String, Object> la = shelf(a);
    la.put("capacity", Map.of("unit", "EACH", "maxQty", 80));
    Map<String, Object> lb = shelf(b); // maxQty 없음
    Res made =
        post(
            SERVICE,
            "/internal/location-batches",
            key(),
            Map.of(
                "centerId",
                CENTER,
                "approvalRef",
                "BO-06-SP",
                "activate",
                true,
                "items",
                List.of(la, lb)),
            null);
    assertThat(made.status()).as(made.body().toString()).isEqualTo(200);
    String path = "/internal/space-reservations";
    // 현물 50 + 30 = 80 → 됨, 같은 칸 다음 예약 1 → 넘침
    Res r1 =
        post(
            SERVICE,
            path,
            key(),
            Map.of(
                "centerId",
                CENTER,
                "sourceRef",
                "PT-1",
                "lines",
                List.of(
                    Map.of("locationCode", a, "sku", "E-00000001", "qty", 30, "occupiedQty", 50))),
            null);
    assertThat(r1.status()).as(r1.body().toString()).isEqualTo(200);
    String rid = r1.body().path("reservationId").asString();
    assertThat(r1.body().path("state").asString()).isEqualTo("RESERVED");
    Res over =
        post(
            SERVICE,
            path,
            key(),
            Map.of(
                "centerId",
                CENTER,
                "sourceRef",
                "PT-2",
                "lines",
                List.of(
                    Map.of("locationCode", a, "sku", "E-00000001", "qty", 1, "occupiedQty", 50))),
            null);
    assertThat(over.status()).isEqualTo(409);
    assertThat(over.body().path("code").asString()).isEqualTo("CAPACITY_EXCEEDED");
    // maxQty 미등록 칸은 422
    Res cfg =
        post(
            SERVICE,
            path,
            key(),
            Map.of(
                "centerId",
                CENTER,
                "sourceRef",
                "PT-3",
                "lines",
                List.of(
                    Map.of("locationCode", b, "sku", "E-00000001", "qty", 1, "occupiedQty", 0))),
            null);
    assertThat(cfg.status()).isEqualTo(422);
    // 소비 20(재고 이동 반영분) → PARTIALLY_CONSUMED, 나머지 해제 → CONSUMED, 넘게 해제 409
    Res c =
        post(
            SERVICE,
            path + "/" + rid + "/releases",
            key(),
            Map.of(
                "action",
                "CONSUME",
                "commitId",
                "CMT-1",
                "lines",
                List.of(Map.of("locationCode", a, "qty", 20))),
            null);
    assertThat(c.body().path("state").asString())
        .as(c.body().toString())
        .isEqualTo("PARTIALLY_CONSUMED");
    assertThat(
            post(
                    SERVICE,
                    path + "/" + rid + "/releases",
                    key(),
                    Map.of(
                        "action",
                        "RELEASE",
                        "lines",
                        List.of(Map.of("locationCode", a, "qty", 11))),
                    null)
                .status())
        .isEqualTo(409);
    Res rel =
        post(SERVICE, path + "/" + rid + "/releases", key(), Map.of("action", "RELEASE"), null);
    assertThat(rel.body().path("state").asString()).isEqualTo("CONSUMED");
    assertThat(rel.body().path("lines").get(0).path("releasedQty").asInt()).isEqualTo(10);
    // 이제 여유가 다시 생김(현물 70 가정 + 10)
    assertThat(
            post(
                    SERVICE,
                    path,
                    key(),
                    Map.of(
                        "centerId",
                        CENTER,
                        "sourceRef",
                        "PT-4",
                        "lines",
                        List.of(
                            Map.of(
                                "locationCode",
                                a,
                                "sku",
                                "E-00000001",
                                "qty",
                                10,
                                "occupiedQty",
                                70))),
                    null)
                .status())
        .isEqualTo(200);
    // 피킹 선반 최소·목표
    Res bad =
        post(
            SERVICE,
            "/internal/pick-face-settings",
            key(),
            Map.of(
                "centerId",
                CENTER,
                "items",
                List.of(Map.of("locationCode", a, "pickFaceMinQty", 30, "pickFaceTargetQty", 20))),
            null);
    assertThat(bad.status()).isEqualTo(400);
    Res set =
        post(
            SERVICE,
            "/internal/pick-face-settings",
            key(),
            Map.of(
                "centerId",
                CENTER,
                "items",
                List.of(Map.of("locationCode", a, "pickFaceMinQty", 24, "pickFaceTargetQty", 80))),
            null);
    assertThat(set.status()).as(set.body().toString()).isEqualTo(200);
    JsonNode pf = get(SERVICE, "/internal/pick-faces?centerId=" + CENTER).body();
    assertThat(pf.path("items").findValuesAsString("locationCode")).contains(a).doesNotContain(b);
  }

  Map<String, Object> dock(String trip, String temp, String start, String end) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("centerId", CENTER);
    m.put("tripId", trip);
    m.put("vehicleId", "VH-" + trip);
    m.put("purpose", "OUTBOUND_LOADING");
    m.put("temperatureZone", temp);
    m.put("routeId", "RT-04");
    m.put("plateNo", "12가0001");
    m.put("window", Map.of("start", start, "end", end));
    return m;
  }

  /**
   * 0.8.0 도크 예약: 온도대 같은 빈 도크를 코드 순으로, 겹치면 다음 칸, 다 차면 409, 칸 없으면 NO_DOCK, 같은 운행 다시 = 그대로, 해제 뒤 다시.
   */
  @Test
  void dockReservations() {
    Res made =
        post(
            SERVICE,
            "/internal/location-batches",
            key(),
            Map.of(
                "centerId",
                CENTER,
                "approvalRef",
                "BO-06-DK",
                "activate",
                true,
                "items",
                List.of(
                    loc("CDC01-G-5-90-01-0-1", "CHILLED", "DOCK"),
                    loc("CDC01-G-5-90-02-0-1", "CHILLED", "DOCK"),
                    loc("CDC01-A-6-90-01-0-1", "AMBIENT", "DOCK"))),
            null);
    assertThat(made.status()).as(made.body().toString()).isEqualTo(200);
    String p = "/internal/dock-reservations";
    String s = "2026-12-01T15:00:00Z";
    String e = "2026-12-01T15:30:00Z";
    Res r1 = post(SERVICE, p, key(), dock("TO-1", "CHILLED", s, e), null);
    assertThat(r1.status()).as(r1.body().toString()).isEqualTo(200);
    assertThat(r1.body().path("dockCode").asString()).isEqualTo("CDC01-G-5-90-01-0-1");
    assertThat(r1.body().path("floor").asString()).isEqualTo("5");
    assertThat(r1.body().path("status").asString()).isEqualTo("APPLIED");
    assertThat(
            post(SERVICE, p, key(), dock("TO-2", "CHILLED", s, e), null)
                .body()
                .path("dockCode")
                .asString())
        .isEqualTo("CDC01-G-5-90-02-0-1");
    Res full = post(SERVICE, p, key(), dock("TO-3", "CHILLED", "2026-12-01T15:10:00Z", e), null);
    assertThat(full.status()).isEqualTo(409);
    assertThat(full.body().path("code").asString()).isEqualTo("CAPACITY_EXCEEDED");
    assertThat(
            post(SERVICE, p, key(), dock("TO-9", "FROZEN", s, e), null)
                .body()
                .path("code")
                .asString())
        .isEqualTo("NO_DOCK");
    // 같은 운행·같은 창 = 같은 예약, 끝 = 다음 시작은 겹치지 않음
    assertThat(
            post(SERVICE, p, key(), dock("TO-1", "CHILLED", s, e), null)
                .body()
                .path("dockReservationId")
                .asString())
        .isEqualTo(r1.body().path("dockReservationId").asString());
    assertThat(
            post(SERVICE, p, key(), dock("TO-4", "CHILLED", e, "2026-12-01T16:00:00Z"), null)
                .body()
                .path("dockCode")
                .asString())
        .isEqualTo("CDC01-G-5-90-01-0-1");
    // 해제(멱등) 뒤 그 칸을 다시 잡음
    String id1 = r1.body().path("dockReservationId").asString();
    assertThat(
            post(SERVICE, p + "/" + id1 + "/releases", key(), Map.of("reason", "CANCELLED"), null)
                .body()
                .path("state")
                .asString())
        .isEqualTo("RELEASED");
    assertThat(
            post(SERVICE, p + "/" + id1 + "/releases", key(), Map.of("reason", "CANCELLED"), null)
                .status())
        .isEqualTo(200);
    assertThat(
            post(SERVICE, p, key(), dock("TO-3", "CHILLED", "2026-12-01T15:10:00Z", e), null)
                .body()
                .path("dockCode")
                .asString())
        .isEqualTo("CDC01-G-5-90-01-0-1");
    // 운영자 보기(DK-1) — 서울 2026-12-02(00:00~ 이 창이 걸침)
    JsonNode board =
        get(OPERATOR, "/cdcs/" + CENTER + "/dock-reservations?date=2026-12-02&state=RESERVED")
            .body();
    assertThat(board.path("docks").size()).isEqualTo(3);
    assertThat(board.path("items").findValuesAsString("tripId"))
        .containsExactly("TO-3", "TO-4", "TO-2");
    assertThat(board.path("items").get(0).path("routeId").asString()).isEqualTo("RT-04");
    assertThat(get(OTHER, "/cdcs/" + CENTER + "/dock-reservations").status()).isEqualTo(403);
  }
}
