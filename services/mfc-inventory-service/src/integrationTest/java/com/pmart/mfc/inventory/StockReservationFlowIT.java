package com.pmart.mfc.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.EventEnvelopeFixtures;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 재고 원장·예약(S1 a, ADR-0040): 수령 전기(receiptId 멱등, E 없음) → 검수·적치(executionId 멱등, 적치 뒤 E) → 가용 → 예약
 * all-or-nothing·동시 경합(TC-09)·LOT 조각(FEFO·최소 잔여일) → 같은 키 재생 → 확정·해제 → 만료 작업(InventoryHoldExpired
 * Outbox). 실제 PostgreSQL·Kafka, 시험 서비스 토큰.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.messaging.outbox.delete-after-insert=false",
      "pmart.inventory.hold-expiry-interval=PT1S",
      "pmart.inventory.dummy-sku-size=true"
    })
@Import(TestJwtConfiguration.class)
class StockReservationFlowIT extends PostgresKafkaContainerSupport {

  static final String INBOUND = TestJwt.service("mfc-inbound-service");
  static final String ORCH = TestJwt.service("order-orchestration-service");

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;
  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired com.pmart.mfc.inventory.application.BlockedResecurer blockedResecurer;
  @Autowired com.pmart.mfc.inventory.application.SnapshotPublisher snapshots;
  @Autowired com.pmart.mfc.inventory.application.UnshippableLotScan unshippableScan;

  RestClient http;
  String mfc;

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    mfc = "MFC-IT-" + UUID.randomUUID().toString().substring(0, 8);
    // 위치는 mfc-location 사건(MfcLocationStateChanged)으로 들어온다
    location("RCV-DOCK", "RECEIVING", "AMBIENT", "ACTIVE", 1);
    location("A-01-01", "STORAGE", "AMBIENT", "ACTIVE", 1);
    location("C-01-01", "STORAGE", "CHILLED", "ACTIVE", 1);
    awaitLocations(3);
  }

  void location(String code, String zone, String temp, String state, int version) {
    String id = "LOC-" + mfc + "-" + code;
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("locationId", id);
    payload.put("mfcId", mfc);
    payload.put("locationCode", code);
    payload.put("zone", zone);
    payload.put("temperatureZone", temp);
    payload.put("state", state);
    payload.put("version", version);
    String json =
        EventEnvelopeFixtures.envelope("MfcLocationStateChanged")
            .from("mfc-location-service")
            .aggregate("Location", id, version)
            .payload(payload)
            .buildJson();
    kafka.send("mfc.location.v1", id, json).join();
  }

  /** 치수·고회전이 있는 위치(재고 이동 용량·규칙 시험). */
  void shelf(String code, String zone, int w, int d, int h, boolean highTurnover) {
    shelf(code, zone, w, d, h, highTurnover, null);
  }

  void shelf(
      String code, String zone, int w, int d, int h, boolean highTurnover, String categoryId) {
    String id = "LOC-" + mfc + "-" + code;
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("locationId", id);
    payload.put("mfcId", mfc);
    payload.put("locationCode", code);
    payload.put("zone", zone);
    payload.put("temperatureZone", "AMBIENT");
    payload.put("state", "ACTIVE");
    payload.put("version", 1);
    payload.put("highTurnover", highTurnover);
    payload.put("widthMm", w);
    payload.put("depthMm", d);
    payload.put("heightMm", h);
    payload.put("categoryId", categoryId);
    String json =
        EventEnvelopeFixtures.envelope("MfcLocationStateChanged")
            .from("mfc-location-service")
            .aggregate("Location", id, 1)
            .payload(payload)
            .buildJson();
    kafka.send("mfc.location.v1", id, json).join();
  }

  void awaitLocations(int count) {
    awaitSql("SELECT count(*) FROM location_projection WHERE mfc_id = '" + mfc + "'", count);
  }

  void awaitSql(String sql, int expected) {
    Instant until = Instant.now().plus(Duration.ofSeconds(30));
    while (Instant.now().isBefore(until)) {
      Integer n = jdbc.queryForObject(sql, Integer.class);
      if (n != null && n == expected) {
        return;
      }
      try {
        Thread.sleep(200);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
    assertThat(jdbc.queryForObject(sql, Integer.class)).isEqualTo(expected);
  }

  record Res(int status, JsonNode body) {}

  Res post(String token, String source, String path, String key, Object body) {
    var e =
        http.post()
            .uri(path)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", key);
                  h.set("X-Source-System", source);
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

  static String newSku() {
    return "E-" + String.format("%08d", ThreadLocalRandom.current().nextInt(1, 99_999_999));
  }

  /** 센터 취급 ON(이 서비스 표 — 시험 준비). */
  void handlingOn(String sku) {
    jdbc.update(
        "INSERT INTO center_sku_handling (center_id, sku, handling_enabled, changed_by, updated_at,"
            + " row_version) VALUES (?,?,true,'it',now(),1)",
        mfc,
        sku);
  }

  /** SKU 투영(상품 이벤트 대신 시험 준비) — 보관 유형. */
  void projection(String sku, String storageType) {
    jdbc.update(
        "INSERT INTO sku_projection (sku, unit_type, storage_type, state, product_version, updated_at)"
            + " VALUES (?, 'E', ?, 'ACTIVE', 1, now()) ON CONFLICT (sku) DO NOTHING",
        sku,
        storageType);
  }

  static Map<String, Object> line(
      String sku, int normal, int quarantined, String lotRef, String expiry) {
    Map<String, Object> line = new LinkedHashMap<>();
    line.put("sku", sku);
    line.put("normalQty", normal);
    line.put("quarantinedQty", quarantined);
    line.put("pendingQty", 0);
    line.put("toLocationCode", "RCV-DOCK");
    if (lotRef != null) {
      line.put("sourceLotRef", lotRef);
    }
    if (expiry != null) {
      line.put("expiryDate", expiry);
    }
    return line;
  }

  Map<String, Object> receipt(String receiptId, String sku, int normal, int quarantined) {
    return receiptOf(receiptId, List.of(line(sku, normal, quarantined, null, null)));
  }

  Map<String, Object> receiptOf(String receiptId, List<Map<String, Object>> lines) {
    return Map.of(
        "receiptId",
        receiptId,
        "trfId",
        "INITIAL-AP-1",
        "departureId",
        "INITIAL",
        "mfcId",
        mfc,
        "lines",
        lines);
  }

  Res postReceipt(String key, Map<String, Object> body) {
    return post(INBOUND, "mfc-inbound-service", "/internal/receipts", key, body);
  }

  Res execute(String type, String fromLoc, String toLoc, List<Map<String, Object>> lines) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("executionType", type);
    body.put("mfcId", mfc);
    body.put("taskId", "TSK-" + UUID.randomUUID());
    body.put("executionId", "EXE-" + UUID.randomUUID());
    body.put("fromLocation", fromLoc);
    if (toLoc != null) {
      body.put("toLocation", toLoc);
    }
    body.put("lines", lines);
    return post(
        INBOUND,
        "mfc-inbound-service",
        "/internal/task-executions",
        UUID.randomUUID().toString(),
        body);
  }

  /** 수령 → 수령 결과의 lotId 로 정상분 검수(NORMAL) → 선반 적치. 적치 뒤에만 판매 가능(E). */
  void stocked(String receiptId, List<Map<String, Object>> lines) {
    for (Map<String, Object> l : lines) {
      projection((String) l.get("sku"), "AMBIENT");
    }
    Res r = postReceipt(UUID.randomUUID().toString(), receiptOf(receiptId, lines));
    assertThat(r.status()).isEqualTo(201);
    JsonNode result = get(INBOUND, "/internal/receipts/" + receiptId).body();
    for (JsonNode l : result.path("lines")) {
      int normal = l.path("normalQty").asInt();
      if (normal == 0) {
        continue;
      }
      var el =
          List.<Map<String, Object>>of(
              Map.of(
                  "sku",
                  l.path("sku").asString(),
                  "lotId",
                  l.path("lotId").asString(),
                  "qty",
                  normal,
                  "resultQuality",
                  "NORMAL"));
      assertThat(execute("INSPECT", "RCV-DOCK", null, el).status()).isEqualTo(201);
      var pl =
          List.<Map<String, Object>>of(
              Map.of(
                  "sku",
                  l.path("sku").asString(),
                  "lotId",
                  l.path("lotId").asString(),
                  "qty",
                  normal));
      String shelf =
          "CHILLED"
                  .equals(
                      jdbc.queryForObject(
                          "SELECT storage_type FROM sku_projection WHERE sku = ?",
                          String.class,
                          l.path("sku").asString()))
              ? "C-01-01"
              : "A-01-01";
      assertThat(execute("PUTAWAY", "RCV-DOCK", shelf, pl).status()).isEqualTo(201);
    }
  }

  void stocked(String sku, int normal) {
    stocked("RCV-" + UUID.randomUUID(), List.of(line(sku, normal, 0, null, null)));
  }

  Map<String, Object> reserveBody(String orderId, Map<String, Integer> lines) {
    List<Map<String, Object>> ls = new ArrayList<>();
    lines.forEach((s, q) -> ls.add(Map.of("sku", s, "qty", q)));
    return Map.of("orderId", orderId, "mfcId", mfc, "lines", ls);
  }

  Res reserve(String key, Map<String, Object> body) {
    return post(ORCH, "order-orchestration-service", "/internal/reservations", key, body);
  }

  JsonNode availability(String sku) {
    Res r = get(ORCH, "/internal/availability?mfcId=" + mfc + "&sku=" + sku);
    assertThat(r.status()).isEqualTo(200);
    return r.body().path("items").path(0);
  }

  @Test
  void receiptIsPostedOnceAndFeedsAvailability() {
    String sku = newSku();
    String receiptId = "RCV-" + UUID.randomUUID();

    // 정상 5, 격리 1 → H6 Q1 E5
    Res first = postReceipt(UUID.randomUUID().toString(), receipt(receiptId, sku, 5, 1));
    assertThat(first.status()).isEqualTo(201);
    assertThat(first.body().path("status").asString()).isEqualTo("APPLIED");
    String commitId = first.body().path("resultId").asString();
    assertThat(commitId).startsWith("CMT-");
    assertThat(first.body().path("appliedQty").asInt()).isEqualTo(6);

    // 같은 receiptId·같은 내용 → 다른 키여도 같은 commitId, 이중 계상 없음
    Res again = postReceipt(UUID.randomUUID().toString(), receipt(receiptId, sku, 5, 1));
    assertThat(again.status()).isEqualTo(201);
    assertThat(again.body().path("resultId").asString()).isEqualTo(commitId);
    // 같은 키·같은 본문 → 저장 결과 재생
    String key = UUID.randomUUID().toString();
    Res k1 = postReceipt(key, receipt("RCV-" + UUID.randomUUID(), sku, 1, 0));
    // 다른 receiptId 를 같은 키로 → 409 IDEMPOTENCY_CONFLICT
    assertThat(postReceipt(key, receipt("RCV-" + UUID.randomUUID(), sku, 1, 0)).status())
        .isEqualTo(409);
    assertThat(k1.status()).isEqualTo(201);
    // 같은 receiptId·다른 내용 → 409 RECEIPT_ALREADY_POSTED
    Res conflict = postReceipt(UUID.randomUUID().toString(), receipt(receiptId, sku, 4, 1));
    assertThat(conflict.status()).isEqualTo(409);
    assertThat(conflict.body().path("code").asString()).isEqualTo("RECEIPT_ALREADY_POSTED");

    // 수령만으로는 판매 가능(E) 0 — 검수·적치 전(IV-02)
    JsonNode a = availability(sku);
    assertThat(a.path("onHand").asInt()).isEqualTo(7);
    assertThat(a.path("quarantined").asInt()).isEqualTo(1);
    assertThat(a.path("sellable").asInt()).isZero();
    assertThat(a.path("available").asInt()).isZero();
    assertThat(a.path("handlingEnabled").asBoolean()).isFalse();

    // 수령 결과: 줄별 inventory lotId(상류 표기·기한 없음 → 추적 미확인 LOT)
    Res result = get(INBOUND, "/internal/receipts/" + receiptId);
    assertThat(result.status()).isEqualTo(200);
    String lotId = result.body().path("lines").path(0).path("lotId").asString();
    assertThat(lotId).startsWith("LOT-");
    assertThat(get(INBOUND, "/internal/receipts/RCV-none").status()).isEqualTo(404);

    // 검수 정상 5 → 아직 E 0(적치 대기), 적치 → E 5
    var inspectLines =
        List.<Map<String, Object>>of(
            Map.of("sku", sku, "lotId", lotId, "qty", 5, "resultQuality", "NORMAL"));
    Res inspected = execute("INSPECT", "RCV-DOCK", null, inspectLines);
    assertThat(inspected.status()).isEqualTo(201);
    assertThat(availability(sku).path("sellable").asInt()).isZero();
    // 검수 대기 잔량보다 많이 → 422(위치 잔량 부족)
    var tooMany =
        List.<Map<String, Object>>of(
            Map.of("sku", sku, "lotId", lotId, "qty", 1, "resultQuality", "NORMAL"));
    assertThat(execute("INSPECT", "RCV-DOCK", null, tooMany).status()).isEqualTo(422);
    var putLines = List.<Map<String, Object>>of(Map.of("sku", sku, "lotId", lotId, "qty", 5));
    // 보관 유형을 모르면 적치 CONFIG_REQUIRED, 온도대가 다르면 409, 수령 구역에 적치하면 409
    Res noType = execute("PUTAWAY", "RCV-DOCK", "A-01-01", putLines);
    assertThat(noType.status()).isEqualTo(422);
    assertThat(noType.body().path("code").asString()).isEqualTo("CONFIG_REQUIRED");
    projection(sku, "AMBIENT");
    Res wrongTemp = execute("PUTAWAY", "RCV-DOCK", "C-01-01", putLines);
    assertThat(wrongTemp.status()).isEqualTo(409);
    assertThat(wrongTemp.body().path("code").asString()).isEqualTo("TEMPERATURE_ZONE_MISMATCH");
    assertThat(execute("PUTAWAY", "RCV-DOCK", "RCV-DOCK", putLines).body().path("code").asString())
        .isEqualTo("LOCATION_ZONE_MISMATCH");
    Res put = execute("PUTAWAY", "RCV-DOCK", "A-01-01", putLines);
    assertThat(put.status()).isEqualTo(201);
    a = availability(sku);
    assertThat(a.path("onHand").asInt()).isEqualTo(7);
    assertThat(a.path("sellable").asInt()).isEqualTo(5);
    assertThat(a.path("available").asInt()).isEqualTo(5);
    // 적치 원장: 수령 위치 −5, 선반 +5(E+5)
    JsonNode putCommit =
        get(INBOUND, "/internal/commits/" + put.body().path("resultId").asString()).body();
    assertThat(putCommit.path("commitType").asString()).isEqualTo("TASK_EXECUTION");
    assertThat(putCommit.path("entries").toString()).contains("A-01-01").contains("STORED");
    // 다른 SKU 의 lotId → 400, 예약 없는 PICK → 400(6b: 예약·주문 줄 필수), MOVE 는 아직 501
    var wrongLot = List.<Map<String, Object>>of(Map.of("sku", newSku(), "lotId", lotId, "qty", 1));
    assertThat(execute("PUTAWAY", "RCV-DOCK", "A-01-01", wrongLot).status()).isEqualTo(400);
    assertThat(execute("PICK", "A-01-01", "WORK-1", putLines).status()).isEqualTo(400);
    // MOVE 는 취소 복귀만(ADR-0046) — 등록 안 된 목적지는 422
    assertThat(execute("MOVE", "A-01-01", "WORK-1", putLines).status()).isEqualTo(422);
    // 원장 행이 없는 SKU 는 0
    assertThat(availability(newSku()).path("available").asInt()).isZero();

    // 전기 조회
    Res commit = get(INBOUND, "/internal/commits/" + commitId);
    assertThat(commit.status()).isEqualTo(200);
    assertThat(commit.body().path("commitType").asString()).isEqualTo("TIN");
    assertThat(commit.body().path("sourceRef").asString()).isEqualTo(receiptId);
    assertThat(commit.body().path("entries")).hasSize(3); // 검수 대기 H+5, 격리 H+1·Q+1

    // 수령 위치: 미등록 422, 선반 구역 409, 폐쇄 409
    Map<String, Object> badLine = line(sku, 1, 0, null, null);
    badLine.put("toLocationCode", "NOWHERE");
    Res unknown =
        postReceipt(
            UUID.randomUUID().toString(), receiptOf("RCV-" + UUID.randomUUID(), List.of(badLine)));
    assertThat(unknown.status()).isEqualTo(422);
    assertThat(unknown.body().path("code").asString()).isEqualTo("LOCATION_NOT_REGISTERED");
    badLine.put("toLocationCode", "A-01-01");
    assertThat(
            postReceipt(
                    UUID.randomUUID().toString(),
                    receiptOf("RCV-" + UUID.randomUUID(), List.of(badLine)))
                .body()
                .path("code")
                .asString())
        .isEqualTo("LOCATION_ZONE_MISMATCH");
    location("RCV-DOCK", "RECEIVING", "AMBIENT", "CLOSED", 2);
    awaitSql(
        "SELECT count(*) FROM location_projection WHERE mfc_id = '"
            + mfc
            + "' AND state = 'CLOSED'",
        1);
    location("RCV-DOCK", "RECEIVING", "AMBIENT", "ACTIVE", 1); // 오래된 버전은 무시
    Res closed =
        postReceipt(UUID.randomUUID().toString(), receipt("RCV-" + UUID.randomUUID(), sku, 1, 0));
    assertThat(closed.status()).isEqualTo(409);
    assertThat(closed.body().path("code").asString()).isEqualTo("LOCATION_CLOSED");

    // 운영자·고객 토큰은 /internal 403
    assertThat(
            post(
                    TestJwt.operator("op", List.of(), List.of(mfc)),
                    "backoffice-web",
                    "/internal/receipts",
                    UUID.randomUUID().toString(),
                    receipt("RCV-" + UUID.randomUUID(), sku, 1, 0))
                .status())
        .isEqualTo(403);
  }

  @Test
  void reserveIsAllOrNothingAndReplayable() {
    String a = newSku();
    String b = newSku();
    handlingOn(a);
    stocked(a, 5);

    // 취급 OFF SKU 포함 → 422 SKU_NOT_HANDLED
    Res off = reserve(UUID.randomUUID().toString(), reserveBody("ORD-1", Map.of(a, 1, b, 1)));
    assertThat(off.status()).isEqualTo(422);
    assertThat(off.body().path("code").asString()).isEqualTo("SKU_NOT_HANDLED");
    assertThat(off.body().path("details").path(0).path("field").asString()).isEqualTo(b);

    // 취급 ON 이지만 재고 0 인 b 포함 → 전체 거절, a 도 확보 0
    handlingOn(b);
    Res shortB = reserve(UUID.randomUUID().toString(), reserveBody("ORD-2", Map.of(a, 1, b, 1)));
    assertThat(shortB.status()).isEqualTo(422);
    assertThat(shortB.body().path("code").asString()).isEqualTo("INSUFFICIENT_STOCK");
    assertThat(shortB.body().path("details").path(0).path("field").asString()).isEqualTo(b);
    assertThat(shortB.body().path("details").path(0).path("reason").asString())
        .isEqualTo("requestedQty=1;availableQty=0");
    assertThat(availability(a).path("reserved").asInt()).isZero();

    // TC-09 별도 시험: 같은 SKU 두 행 3+3 = 6 > A5 → 전체 거절
    List<Map<String, Object>> dup = List.of(Map.of("sku", a, "qty", 3), Map.of("sku", a, "qty", 3));
    Res dupRes =
        reserve(
            UUID.randomUUID().toString(), Map.of("orderId", "ORD-3", "mfcId", mfc, "lines", dup));
    assertThat(dupRes.status()).isEqualTo(422);
    assertThat(dupRes.body().path("details").path(0).path("reason").asString())
        .isEqualTo("requestedQty=6;availableQty=5");

    // 성공 → R4/A1, 같은 키·같은 본문 재전송 → 같은 결과, R 변화 0
    String key = UUID.randomUUID().toString();
    Res ok = reserve(key, reserveBody("ORD-4", Map.of(a, 4)));
    assertThat(ok.status()).isEqualTo(201);
    String reservationId = ok.body().path("resultId").asString();
    assertThat(reservationId).startsWith("RSV-");
    Res replay = reserve(key, reserveBody("ORD-4", Map.of(a, 4)));
    assertThat(replay.status()).isEqualTo(201);
    assertThat(replay.body()).isEqualTo(ok.body());
    assertThat(availability(a).path("reserved").asInt()).isEqualTo(4);
    assertThat(availability(a).path("available").asInt()).isEqualTo(1);
    // 같은 키·다른 본문 → 409
    assertThat(reserve(key, reserveBody("ORD-4", Map.of(a, 1))).status()).isEqualTo(409);

    // 조회
    Res rv = get(ORCH, "/internal/reservations/" + reservationId);
    assertThat(rv.status()).isEqualTo(200);
    assertThat(rv.body().path("state").asString()).isEqualTo("HELD");
    assertThat(rv.body().path("orderId").asString()).isEqualTo("ORD-4");
    assertThat(rv.body().path("parts").path(0).path("qty").asInt()).isEqualTo(4);
    assertThat(rv.body().path("parts").path(0).path("lotId").asString()).startsWith("LOT-");
    assertThat(rv.body().path("parts").path(0).path("state").asString()).isEqualTo("HELD");
    assertThat(rv.body().path("expiresAt").isNull()).isFalse();
    assertThat(get(ORCH, "/internal/reservations/RSV-none").status()).isEqualTo(404);

    // 원결과 조회: 예약 요청 → APPLIED + commitId, 거절된 요청 → 404
    Res req = get(ORCH, "/internal/requests/" + key);
    assertThat(req.status()).isEqualTo(200);
    assertThat(req.body().path("applyStatus").asString()).isEqualTo("APPLIED");
    assertThat(req.body().path("commitId").asString()).startsWith("CMT-");
    assertThat(req.body().path("appliedQty").asInt()).isEqualTo(4);
    assertThat(get(ORCH, "/internal/requests/" + UUID.randomUUID()).status()).isEqualTo(404);

    // 확정: 다른 주문 → 409, 낡은 버전 → 409, 정상 → CONFIRMED(만료 시각 지움), 재확정 → 변화 없는 성공
    String confirmPath = "/internal/reservations/" + reservationId + "/confirmations";
    assertThat(
            post(
                    ORCH,
                    "order-orchestration-service",
                    confirmPath,
                    UUID.randomUUID().toString(),
                    Map.of("orderId", "ORD-X"))
                .status())
        .isEqualTo(409);
    Res confirmed =
        post(
            ORCH,
            "order-orchestration-service",
            confirmPath,
            UUID.randomUUID().toString(),
            Map.of("orderId", "ORD-4", "paymentAttemptId", "PAY-1"));
    assertThat(confirmed.status()).isEqualTo(200);
    assertThat(confirmed.body().path("version").asInt()).isEqualTo(2);
    assertThat(confirmed.body().path("appliedQty").asInt()).isEqualTo(4);
    Res reconfirm =
        post(
            ORCH,
            "order-orchestration-service",
            confirmPath,
            UUID.randomUUID().toString(),
            Map.of("orderId", "ORD-4"));
    assertThat(reconfirm.status()).isEqualTo(200);
    assertThat(reconfirm.body().path("appliedQty").asInt()).isZero();
    JsonNode afterConfirm = get(ORCH, "/internal/reservations/" + reservationId).body();
    assertThat(afterConfirm.path("state").asString()).isEqualTo("CONFIRMED");
    assertThat(afterConfirm.path("expiresAt").isNull()).isTrue();
    assertThat(afterConfirm.path("parts").path(0).path("state").asString()).isEqualTo("CONFIRMED");
    assertThat(availability(a).path("reserved").asInt()).isEqualTo(4);

    // 부분 해제 → 422, 전량 해제 → A 복구, 재해제 → 변화 없는 성공, 해제 후 확정 → 409
    String releasePath = "/internal/reservations/" + reservationId + "/releases";
    Res partial =
        post(
            ORCH,
            "order-orchestration-service",
            releasePath,
            UUID.randomUUID().toString(),
            Map.of("reason", "CANCEL_BEFORE_PICK", "lines", List.of(Map.of("sku", a, "qty", 1))));
    assertThat(partial.status()).isEqualTo(422);
    assertThat(partial.body().path("code").asString()).isEqualTo("PARTIAL_RELEASE_NOT_SUPPORTED");
    Res released =
        post(
            ORCH,
            "order-orchestration-service",
            releasePath,
            UUID.randomUUID().toString(),
            Map.of("reason", "CANCEL_BEFORE_PICK"));
    assertThat(released.status()).isEqualTo(200);
    assertThat(released.body().path("appliedQty").asInt()).isEqualTo(4);
    assertThat(availability(a).path("reserved").asInt()).isZero();
    assertThat(availability(a).path("available").asInt()).isEqualTo(5);
    Res rerelease =
        post(
            ORCH,
            "order-orchestration-service",
            releasePath,
            UUID.randomUUID().toString(),
            Map.of("reason", "SAGA_COMPENSATION"));
    assertThat(rerelease.status()).isEqualTo(200);
    assertThat(rerelease.body().path("appliedQty").asInt()).isZero();
    // 해제가 실제로 바뀐 한 번만 InventoryReleased(2026-11-08)
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE type = 'InventoryReleased' AND aggregateid = ?"
                    + " AND payload #>> '{payload,reason}' = 'CANCEL_BEFORE_PICK'",
                Integer.class,
                reservationId))
        .isEqualTo(1);
    assertThat(availability(a).path("available").asInt()).isEqualTo(5);
    Res confirmReleased =
        post(
            ORCH,
            "order-orchestration-service",
            confirmPath,
            UUID.randomUUID().toString(),
            Map.of("orderId", "ORD-4", "paymentAttemptId", "PAY-2"));
    assertThat(confirmReleased.status()).isEqualTo(409);
    assertThat(confirmReleased.body().path("code").asString()).isEqualTo("INVALID_STATE");
  }

  @Test
  void reservationSplitsLinesAcrossLotsFefoAndRespectsMinimumRemainingDays() {
    // 냉장 우유(최소 잔여일 2일): 기한 +1일 3개, +3일 2개, +10일 5개 / 배송 약속일 = 오늘
    String milk = newSku();
    handlingOn(milk);
    projection(milk, "CHILLED");
    java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
    stocked(
        "RCV-" + UUID.randomUUID(),
        List.of(
            line(milk, 3, 0, "M-OLD", today.plusDays(1).toString()),
            line(milk, 2, 0, "M-MID", today.plusDays(3).toString()),
            line(milk, 5, 0, "M-NEW", today.plusDays(10).toString())));
    assertThat(availability(milk).path("available").asInt()).isEqualTo(10);

    // 4개 → 기한 +1일 LOT 은 최소 잔여일 미달로 제외, +3일 2개 + +10일 2개(줄당 LOT 수 제한 없음)
    Res ok =
        reserve(
            UUID.randomUUID().toString(),
            Map.of(
                "orderId",
                "ORD-FEFO",
                "mfcId",
                mfc,
                "lines",
                List.of(Map.of("orderLineId", "L1", "sku", milk, "qty", 4))));
    assertThat(ok.status()).isEqualTo(201);
    JsonNode parts =
        get(ORCH, "/internal/reservations/" + ok.body().path("resultId").asString())
            .body()
            .path("parts");
    assertThat(parts).hasSize(2);
    assertThat(parts.path(0).path("qty").asInt() + parts.path(1).path("qty").asInt()).isEqualTo(4);
    assertThat(parts.path(0).path("orderLineId").asString()).isEqualTo("L1");
    String midLot = parts.path(0).path("lotId").asString();
    assertThat(parts.path(0).path("qty").asInt()).isEqualTo(2);
    assertThat(parts.path(1).path("lotId").asString()).isNotEqualTo(midLot);

    // 센터 가용은 6(기한 임박 3 포함)이지만 적격은 3 → 4개 요청은 NO_ELIGIBLE_LOT
    assertThat(availability(milk).path("available").asInt()).isEqualTo(6);
    Res noLot = reserve(UUID.randomUUID().toString(), reserveBody("ORD-FEFO2", Map.of(milk, 4)));
    assertThat(noLot.status()).isEqualTo(422);
    assertThat(noLot.body().path("details").path(0).path("reason").asString())
        .startsWith("NO_ELIGIBLE_LOT")
        .contains("requestedQty=4;eligibleQty=3");
  }

  /** 상품별 소비기한 보장(ADR-0041) — 상품 이벤트 대신 투영 열을 직접 채운다(시험 준비). */
  /** S4(2026-11-16): 배송 약속일 바꾸기 — 잡힌 LOT 이 새 날짜에도 적격이면 바꾸고, 미달이면 422 LOT_EXPIRY_NOT_MET·예약 그대로. */
  @Test
  void promisedDateChangeKeepsReservationWhenLotWouldExpire() {
    String milk = newSku();
    handlingOn(milk);
    projection(milk, "CHILLED");
    java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
    stocked(
        "RCV-" + UUID.randomUUID(),
        List.of(line(milk, 2, 0, "M-EDGE", today.plusDays(2).toString())));
    Res ok = reserve(UUID.randomUUID().toString(), reserveBody("ORD-S4", Map.of(milk, 1)));
    assertThat(ok.status()).isEqualTo(201);
    String rid = ok.body().path("resultId").asString();
    // 오늘 그대로 → 같은 날짜 성공
    Res same =
        post(
            ORCH,
            "order-orchestration-service",
            "/internal/reservations/" + rid + "/promised-date-changes",
            UUID.randomUUID().toString(),
            Map.of("orderId", "ORD-S4", "promisedDate", today.toString()));
    assertThat(same.status()).isEqualTo(200);
    // 내일 → 기한 +2일 − 내일 = 1일 < 최소 2일 → 422, 예약 그대로
    Res bad =
        post(
            ORCH,
            "order-orchestration-service",
            "/internal/reservations/" + rid + "/promised-date-changes",
            UUID.randomUUID().toString(),
            Map.of("orderId", "ORD-S4", "promisedDate", today.plusDays(1).toString()));
    assertThat(bad.status()).isEqualTo(422);
    assertThat(bad.body().path("code").asString()).isEqualTo("LOT_EXPIRY_NOT_MET");
    assertThat(bad.body().path("details").path(0).path("field").asString()).isEqualTo(milk);
    assertThat(get(ORCH, "/internal/reservations/" + rid).body().path("state").asString())
        .isIn("HELD", "CONFIRMED");
    // 넉넉한 LOT 이면 내일로 바뀐다
    String bread = newSku();
    handlingOn(bread);
    projection(bread, "CHILLED");
    stocked(
        "RCV-" + UUID.randomUUID(),
        List.of(line(bread, 2, 0, "B-OK", today.plusDays(10).toString())));
    String rid2 =
        reserve(UUID.randomUUID().toString(), reserveBody("ORD-S4B", Map.of(bread, 1)))
            .body()
            .path("resultId")
            .asString();
    Res moved =
        post(
            ORCH,
            "order-orchestration-service",
            "/internal/reservations/" + rid2 + "/promised-date-changes",
            UUID.randomUUID().toString(),
            Map.of("orderId", "ORD-S4B", "promisedDate", today.plusDays(1).toString()));
    assertThat(moved.status()).isEqualTo(200);
    assertThat(moved.body().path("status").asString()).isEqualTo("APPLIED");
  }

  void guarantee(String sku, Integer minRemainingDays, Integer maxDaysSinceManufacture) {
    jdbc.update(
        "UPDATE sku_projection SET shelf_min_remaining_days = ?, shelf_max_days_since_mfg = ?"
            + " WHERE sku = ?",
        minRemainingDays,
        maxDaysSinceManufacture,
        sku);
  }

  /** 수령 → 검수(제조 LOT·소비기한·제조일 확인, LOT 분리) → 선반 적치. */
  void stockedWithManufacture(String sku, int qty, String lotRef, String expiry, String mfg) {
    String receiptId = "RCV-" + UUID.randomUUID();
    assertThat(postReceipt(UUID.randomUUID().toString(), receipt(receiptId, sku, qty, 0)).status())
        .isEqualTo(201);
    String pending =
        get(INBOUND, "/internal/receipts/" + receiptId)
            .body()
            .path("lines")
            .path(0)
            .path("lotId")
            .asString();
    Map<String, Object> l = new LinkedHashMap<>();
    l.put("sku", sku);
    l.put("lotId", pending);
    l.put("qty", qty);
    l.put("resultQuality", "NORMAL");
    l.put("sourceLotRef", lotRef);
    if (expiry != null) {
      l.put("expiryDate", expiry);
    }
    if (mfg != null) {
      l.put("manufactureDate", mfg);
    }
    Res inspected = execute("INSPECT", "RCV-DOCK", null, List.of(l));
    assertThat(inspected.status()).isEqualTo(201);
    String lot = null;
    for (JsonNode e :
        get(INBOUND, "/internal/commits/" + inspected.body().path("resultId").asString())
            .body()
            .path("entries")) {
      if (e.path("delta").asInt() > 0) {
        lot = e.path("lotId").asString();
      }
    }
    assertThat(
            execute(
                    "PUTAWAY",
                    "RCV-DOCK",
                    "C-01-01",
                    List.of(Map.of("sku", sku, "lotId", lot, "qty", qty)))
                .status())
        .isEqualTo(201);
  }

  @Test
  void productShelfLifeGuaranteeDecidesEligibleLots() {
    java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
    // 1) 냉장이지만 고시 "주문일 기준 7일 이상 남은 상품" → 기본 2일 대신 7일
    String yogurt = newSku();
    handlingOn(yogurt);
    projection(yogurt, "CHILLED");
    guarantee(yogurt, 7, null);
    stockedWithManufacture(yogurt, 3, "Y-SHORT", today.plusDays(5).toString(), null);
    stockedWithManufacture(yogurt, 2, "Y-LONG", today.plusDays(9).toString(), null);
    Res noLot = reserve(UUID.randomUUID().toString(), reserveBody("ORD-SL1", Map.of(yogurt, 3)));
    assertThat(noLot.status()).isEqualTo(422);
    assertThat(noLot.body().path("details").path(0).path("reason").asString())
        .contains("requestedQty=3;eligibleQty=2");
    assertThat(
            reserve(UUID.randomUUID().toString(), reserveBody("ORD-SL2", Map.of(yogurt, 2)))
                .status())
        .isEqualTo(201);

    // 2) 고시 "주문일 기준 2일 이내 제조된 상품"(빵): 제조일 확인된 LOT 만, 오래된 제조분 제외
    String bread = newSku();
    handlingOn(bread);
    projection(bread, "CHILLED");
    guarantee(bread, null, 2);
    stockedWithManufacture(
        bread, 4, "B-OLD", today.plusDays(10).toString(), today.minusDays(3).toString());
    stockedWithManufacture(
        bread, 1, "B-NEW", today.plusDays(10).toString(), today.minusDays(1).toString());
    stockedWithManufacture(bread, 5, "B-UNKNOWN", today.plusDays(10).toString(), null);
    Res bad = reserve(UUID.randomUUID().toString(), reserveBody("ORD-SL3", Map.of(bread, 2)));
    assertThat(bad.status()).isEqualTo(422);
    assertThat(bad.body().path("details").path(0).path("reason").asString())
        .contains("requestedQty=2;eligibleQty=1");
    Res one = reserve(UUID.randomUUID().toString(), reserveBody("ORD-SL4", Map.of(bread, 1)));
    assertThat(one.status()).isEqualTo(201);
    // 센터 재고 상세가 LOT 제조일을 보여 준다
    String op = TestJwt.operator("op", List.of(), List.of(mfc));
    JsonNode lots = get(op, "/mfcs/" + mfc + "/stock?sku=" + bread).body().path("lots");
    assertThat(lots.toString()).contains(today.minusDays(1).toString());
  }

  /** PICK·PACK 실행(fulfillment 가 부르는 모양). */
  Res pickPack(
      String type, String reservationId, String from, String to, List<Map<String, Object>> lines) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("executionType", type);
    body.put("mfcId", mfc);
    body.put("taskId", "TSK-" + UUID.randomUUID());
    body.put("executionId", "EXE-" + UUID.randomUUID());
    body.put("reservationId", reservationId);
    body.put("fromLocation", from);
    if (to != null) {
      body.put("toLocation", to);
    }
    body.put("lines", lines);
    return post(
        INBOUND,
        "mfc-fulfillment-service",
        "/internal/task-executions",
        UUID.randomUUID().toString(),
        body);
  }

  Res move(String path, Map<String, Object> body) {
    return post(INBOUND, "mfc-inbound-service", path, UUID.randomUUID().toString(), body);
  }

  Map<String, Object> moveBody(String id, String sku, String lot, String from, String to, int qty) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("moveId", id);
    m.put("mfcId", mfc);
    m.put("sku", sku);
    m.put("lotId", lot);
    m.put("fromLocation", from);
    m.put("toLocation", to);
    m.put("qty", qty);
    return m;
  }

  int unit(String sku, String location, String work) {
    return jdbc.queryForObject(
        "SELECT COALESCE(SUM(qty), 0) FROM stock_unit WHERE mfc_id = ? AND sku = ? AND location_code = ?"
            + " AND work_state = ?",
        Integer.class,
        mfc,
        sku,
        location,
        work);
  }

  /**
   * 재고 위치 이동(IV-03, 2026-11-08 사용자 결정): 두 단계(칸 → 카트 → 칸), 이동 중은 판매 가능에서 빠짐, 칸당 한 상품·한 상품 한 자리(고회전
   * 제외)·용량(더미 크기).
   */
  @Test
  void stockMovesThroughACartAndFollowsShelfRules() {
    shelf("B-01-01", "STORAGE", 1000, 600, 400, false); // 큰 상품도 16개
    shelf("B-01-02", "STORAGE", 1000, 600, 400, false);
    shelf("H-01-01", "PICKING", 1000, 600, 400, true); // 고회전
    shelf("T-01-01", "PICKING", 10, 10, 10, true); // 아무것도 안 들어감
    shelf("CRT-A-01", "CART", 0, 0, 0, false);
    shelf("CRT-A-02", "CART", 0, 0, 0, false);
    shelf("K-01-01", "STORAGE", 1000, 600, 400, false, "CAT-SNACK"); // 카테고리 칸
    awaitLocations(9);
    String sku = newSku();
    handlingOn(sku);
    stocked(sku, 10); // A-01-01
    String lot =
        jdbc.queryForObject(
            "SELECT lot_id FROM stock_unit WHERE mfc_id = ? AND sku = ? LIMIT 1",
            String.class,
            mfc,
            sku);

    // 예약 4 가 걸려 있으면 가용 6 까지만
    String rid = confirmedReservation("ORD-MV", sku, 4);
    Res over =
        move("/internal/move-allocations", moveBody("MV-1", sku, lot, "A-01-01", "B-01-01", 10));
    assertThat(over.status()).isEqualTo(409);
    assertThat(over.body().path("code").asString()).isEqualTo("MOVE_QTY_EXCEEDS_AVAILABLE");
    assertThat(
            post(
                    ORCH,
                    "order-orchestration-service",
                    "/internal/reservations/" + rid + "/releases",
                    UUID.randomUUID().toString(),
                    Map.of("reason", "CANCEL_BEFORE_PICK"))
                .status())
        .isIn(200, 202);

    // 전부 옮기기 배정 → 출발 칸 보류, E 0(H 10 그대로)
    Res created =
        move("/internal/move-allocations", moveBody("MV-1", sku, lot, "A-01-01", "B-01-01", 10));
    assertThat(created.status()).as(created.body().toString()).isEqualTo(201);
    assertThat(created.body().path("state").asString()).isEqualTo("ACTIVE");
    assertThat(created.body().path("sizeClass").asString()).isIn("S", "M", "L");
    JsonNode av = availability(sku);
    assertThat(av.path("onHand").asInt()).isEqualTo(10);
    assertThat(av.path("sellable").asInt()).isZero();
    // 같은 moveId 같은 내용 → 그대로, 다른 내용 → 409
    assertThat(
            move("/internal/move-allocations", moveBody("MV-1", sku, lot, "A-01-01", "B-01-01", 10))
                .status())
        .isEqualTo(201);
    assertThat(
            move("/internal/move-allocations", moveBody("MV-1", sku, lot, "A-01-01", "B-01-01", 9))
                .body()
                .path("code")
                .asString())
        .isEqualTo("MOVE_ID_CONFLICT");

    // 집어 올리기 6 → 카트 MOVING 6, 출발 4, E 그대로 0
    String exe = "EXE-" + UUID.randomUUID();
    Map<String, Object> pick = Map.of("executionId", exe, "cartLocation", "CRT-A-01", "qty", 6);
    Res picked = move("/internal/move-allocations/MV-1/pickups", pick);
    assertThat(picked.status()).as(picked.body().toString()).isEqualTo(200);
    assertThat(picked.body().path("onCartQty").asInt()).isEqualTo(6);
    assertThat(
            move("/internal/move-allocations/MV-1/pickups", pick).body().path("pickedQty").asInt())
        .isEqualTo(6); // 같은 실행 ID 는 한 번
    assertThat(unit(sku, "CRT-A-01", "MOVING")).isEqualTo(6);
    assertThat(unit(sku, "A-01-01", "STORED")).isEqualTo(4);
    assertThat(availability(sku).path("sellable").asInt()).isZero();
    // 다른 카트·남은 배정 초과는 거절
    assertThat(
            move(
                    "/internal/move-allocations/MV-1/pickups",
                    Map.of(
                        "executionId",
                        "EXE-" + UUID.randomUUID(),
                        "cartLocation",
                        "CRT-A-02",
                        "qty",
                        1))
                .body()
                .path("code")
                .asString())
        .isEqualTo("CART_MISMATCH");
    assertThat(
            move(
                    "/internal/move-allocations/MV-1/pickups",
                    Map.of(
                        "executionId",
                        "EXE-" + UUID.randomUUID(),
                        "cartLocation",
                        "CRT-A-01",
                        "qty",
                        5))
                .body()
                .path("code")
                .asString())
        .isEqualTo("MOVE_QTY_EXCEEDED");

    // 내려놓기 6 → 도착 STORED 6, E 6
    Res dropped =
        move(
            "/internal/move-allocations/MV-1/drops",
            Map.of("executionId", "EXE-" + UUID.randomUUID(), "toLocation", "B-01-01", "qty", 6));
    assertThat(dropped.status()).as(dropped.body().toString()).isEqualTo(200);
    assertThat(unit(sku, "B-01-01", "STORED")).isEqualTo(6);
    assertThat(availability(sku).path("sellable").asInt()).isEqualTo(6);

    // 나머지 4 를 집어 카트에 둔 채 취소 → 출발로 되돌려 놓아야 끝남
    move(
        "/internal/move-allocations/MV-1/pickups",
        Map.of("executionId", "EXE-" + UUID.randomUUID(), "cartLocation", "CRT-A-01", "qty", 2));
    Res cancelled = move("/internal/move-allocations/MV-1/cancellations", Map.of());
    assertThat(cancelled.body().path("state").asString()).isEqualTo("CANCEL_PENDING");
    assertThat(cancelled.body().path("qty").asInt()).isEqualTo(8); // 집은 양으로 줄어듦
    assertThat(availability(sku).path("sellable").asInt()).isEqualTo(8); // 집지 않은 2 복구
    Res back =
        move(
            "/internal/move-allocations/MV-1/drops",
            Map.of("executionId", "EXE-" + UUID.randomUUID(), "toLocation", "A-01-01", "qty", 2));
    assertThat(back.body().path("state").asString())
        .as(back.body().toString())
        .isEqualTo("CANCELLED");
    assertThat(availability(sku).path("sellable").asInt()).isEqualTo(10);
    assertThat(availability(sku).path("onHand").asInt()).isEqualTo(10);

    // 규칙: 다른 상품이 있는 칸, 이미 다른 칸에 있는 상품, 나눠 두기, 용량, 고회전 예외
    String other = newSku();
    handlingOn(other);
    stocked(other, 5); // A-01-01
    String otherLot =
        jdbc.queryForObject(
            "SELECT lot_id FROM stock_unit WHERE mfc_id = ? AND sku = ? LIMIT 1",
            String.class,
            mfc,
            other);
    // 칸에 여러 상품 가능(마트 진열대처럼) — 셋째 상품 전부를 sku 가 있는 B-01-01 로
    String third = newSku();
    handlingOn(third);
    stocked(third, 3);
    String thirdLot =
        jdbc.queryForObject(
            "SELECT lot_id FROM stock_unit WHERE mfc_id = ? AND sku = ? LIMIT 1",
            String.class,
            mfc,
            third);
    Res mixed =
        move(
            "/internal/move-allocations",
            moveBody("MV-2", third, thirdLot, "A-01-01", "B-01-01", 3));
    assertThat(mixed.status()).as(mixed.body().toString()).isEqualTo(201);
    // 카테고리 칸: 다른 카테고리 상품·카테고리 모르는 상품은 거절
    jdbc.update(
        "INSERT INTO sku_category (sku, category_paths, product_version, updated_at)"
            + " VALUES (?, 'CAT-FOOD/CAT-DRINK', 1, now())",
        other);
    assertThat(
            move(
                    "/internal/move-allocations",
                    moveBody("MV-7", other, otherLot, "A-01-01", "K-01-01", 2))
                .body()
                .path("code")
                .asString())
        .isEqualTo("CATEGORY_MISMATCH");
    assertThat(
            move("/internal/move-allocations", moveBody("MV-8", sku, lot, "A-01-01", "K-01-01", 1))
                .body()
                .path("code")
                .asString())
        .isEqualTo("SKU_CATEGORY_UNKNOWN");
    assertThat(
            move("/internal/move-allocations", moveBody("MV-3", sku, lot, "A-01-01", "B-01-02", 4))
                .body()
                .path("code")
                .asString())
        .isEqualTo("SKU_ALREADY_SHELVED"); // sku 는 이미 B-01-01 에 있다
    assertThat(
            move(
                    "/internal/move-allocations",
                    moveBody("MV-4", other, otherLot, "A-01-01", "B-01-02", 2))
                .body()
                .path("code")
                .asString())
        .isEqualTo("SKU_SPLIT_NOT_ALLOWED");
    assertThat(
            move(
                    "/internal/move-allocations",
                    moveBody("MV-5", other, otherLot, "A-01-01", "T-01-01", 1))
                .body()
                .path("code")
                .asString())
        .isEqualTo("LOCATION_CAPACITY_EXCEEDED");
    Res ht =
        move(
            "/internal/move-allocations",
            moveBody("MV-6", other, otherLot, "A-01-01", "H-01-01", 2));
    assertThat(ht.status()).as(ht.body().toString()).isEqualTo(201); // 고회전 선반은 나눠 둘 수 있다

    // 출하 부적격 LOT(상온 최소 잔여 30일인데 5일 남음): 카테고리 칸 거절, 회수 목록에 나옴, 기타 칸으로는 이동
    String near = newSku();
    handlingOn(near);
    stocked(
        "RCV-" + UUID.randomUUID(),
        List.of(line(near, 3, 0, "REF-NEAR", java.time.LocalDate.now().plusDays(5).toString())));
    jdbc.update(
        "INSERT INTO sku_category (sku, category_paths, product_version, updated_at)"
            + " VALUES (?, 'CAT-FOOD/CAT-SNACK', 1, now())",
        near);
    String nearLot =
        jdbc.queryForObject(
            "SELECT lot_id FROM stock_unit WHERE mfc_id = ? AND sku = ? LIMIT 1",
            String.class,
            mfc,
            near);
    assertThat(
            move(
                    "/internal/move-allocations",
                    moveBody("MV-9", near, nearLot, "A-01-01", "K-01-01", 3))
                .body()
                .path("code")
                .asString())
        .isEqualTo("LOT_NOT_SHIPPABLE");
    String op = TestJwt.operator("sv-1", List.of("MFC_SUPERVISOR"), List.of(mfc));
    JsonNode recall = get(op, "/mfcs/" + mfc + "/unshippable-lots").body().path("items");
    assertThat(recall.findValuesAsString("lotId")).contains(nearLot);
    assertThat(recall.findValuesAsString("lotId")).doesNotContain(lot); // 소비기한 없는 정상 LOT 은 아님
    // 정기 알림(남은 일 16): 기타 칸(A-01-01)에 있으면 알림 아님 → 카테고리 칸이면 OPEN(처음 본 시각 유지) → 기타 칸으로 거두면 CLEARED
    assertThat(unshippableScan.scan(mfc).added()).isZero();
    jdbc.update(
        "UPDATE location_projection SET category_id = 'CAT-FOOD' WHERE mfc_id = ? AND location_code = 'A-01-01'",
        mfc);
    var firstScan = unshippableScan.scan(mfc);
    assertThat(firstScan.added()).isEqualTo(1);
    assertThat(unshippableScan.scan(mfc).added()).isZero(); // 같은 칸·LOT 은 한 건
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM unshippable_lot_alert WHERE mfc_id = ? AND lot_id = ?"
                    + " AND state = 'OPEN' AND location_code = 'A-01-01'",
                Integer.class,
                mfc,
                nearLot))
        .isEqualTo(1);
    jdbc.update(
        "UPDATE location_projection SET category_id = NULL WHERE mfc_id = ? AND location_code = 'A-01-01'",
        mfc);
    Res misc =
        move(
            "/internal/move-allocations",
            moveBody("MV-10", near, nearLot, "A-01-01", "B-01-02", 3));
    assertThat(misc.status()).as(misc.body().toString()).isEqualTo(201); // 기타 칸(카테고리 없음)은 받는다
    var after = unshippableScan.scan(mfc);
    assertThat(after.cleared()).isEqualTo(1);
    assertThat(after.open()).isZero();
  }

  Res put(String token, String path, Object body) {
    var e =
        http.put()
            .uri(path)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", UUID.randomUUID().toString());
                  h.set("X-Source-System", "backoffice-web");
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapper.writeValueAsString(body))
            .retrieve()
            .toEntity(String.class);
    return new Res(e.getStatusCode().value(), read(e.getBody()));
  }

  /**
   * 자동 선반 보충(IV-04, 사용자 결정: 시스템이 만든다): 고회전 선반 기준 → 점검이 보충 이동을 만들고, 진행 중이면 더 안 만들고, 가용이 없으면 기다린다.
   */
  @Test
  void replenishmentFillsHighTurnoverShelvesFromStorageAndWaitsWhenStockIsReserved() {
    shelf("H-02-01", "PICKING", 1000, 600, 400, true);
    shelf("B-02-01", "STORAGE", 1000, 600, 400, false);
    shelf("CRT-A-09", "CART", 0, 0, 0, false);
    awaitLocations(6);
    String sku = newSku();
    handlingOn(sku);
    stocked(sku, 20); // A-01-01(보관)
    String sup = TestJwt.operator("sv-r", List.of("MFC_SUPERVISOR"), List.of(mfc));
    String wrk = TestJwt.operator("wk-r", List.of("MFC_WORKER"), List.of(mfc));
    String path = "/mfcs/" + mfc + "/replenishment-policies";
    Map<String, Object> policy =
        Map.of("locationCode", "H-02-01", "sku", sku, "minQty", 2, "targetQty", 10, "unitQty", 2);
    assertThat(put(wrk, path, policy).status()).isEqualTo(403);
    Res notHt =
        put(
            sup,
            path,
            Map.of(
                "locationCode", "B-02-01", "sku", sku, "minQty", 2, "targetQty", 10, "unitQty", 2));
    assertThat(notHt.body().path("code").asString()).isEqualTo("REPLENISH_ONLY_HIGH_TURNOVER");
    assertThat(put(sup, path, policy).status()).isEqualTo(200);

    // 점검: 현재 0 ≤ 최소 2 → 목표 10 만큼 보충 이동(시스템, kind REPLENISH)
    Map<String, Object> scope = Map.of("mfcId", mfc);
    Res first =
        post(
            INBOUND,
            "mfc-inbound-service",
            "/internal/replenishment-evaluations",
            UUID.randomUUID().toString(),
            scope);
    assertThat(first.status()).as(first.body().toString()).isEqualTo(200);
    assertThat(first.body().path("created").asInt()).isEqualTo(1);
    JsonNode mv = first.body().path("moves").path(0);
    assertThat(mv.path("kind").asString()).isEqualTo("REPLENISH");
    assertThat(mv.path("toLocation").asString()).isEqualTo("H-02-01");
    assertThat(mv.path("qty").asInt()).isEqualTo(10);
    // 다시 점검해도 진행 중 보충(U)이 있어 더 만들지 않는다
    Res again =
        post(
            INBOUND,
            "mfc-inbound-service",
            "/internal/replenishment-evaluations",
            UUID.randomUUID().toString(),
            scope);
    assertThat(again.body().path("created").asInt()).isZero();
    assertThat(again.body().path("moves")).hasSize(1);

    // 실행(카트 경유) 뒤 현재 10 > 최소 → 점검이 아무것도 안 만든다
    String moveId = mv.path("moveId").asString();
    move(
        "/internal/move-allocations/" + moveId + "/pickups",
        Map.of("executionId", "EXE-" + UUID.randomUUID(), "cartLocation", "CRT-A-09", "qty", 10));
    Res dropped =
        move(
            "/internal/move-allocations/" + moveId + "/drops",
            Map.of("executionId", "EXE-" + UUID.randomUUID(), "toLocation", "H-02-01", "qty", 10));
    assertThat(dropped.body().path("state").asString()).isEqualTo("COMPLETED");
    Res after =
        post(
            INBOUND,
            "mfc-inbound-service",
            "/internal/replenishment-evaluations",
            UUID.randomUUID().toString(),
            scope);
    assertThat(after.body().path("created").asInt()).isZero();
    assertThat(after.body().path("moves")).isEmpty();

    // 예약이 재고를 다 잡고 있으면(A 0) 보충하지 않고 기다린다(사용자 결정 — 예외 없음)
    String busy = newSku();
    handlingOn(busy);
    stocked(busy, 4);
    confirmedReservation("ORD-RPL", busy, 4);
    assertThat(
            put(
                    sup,
                    path,
                    Map.of(
                        "locationCode",
                        "H-02-01",
                        "sku",
                        busy,
                        "minQty",
                        1,
                        "targetQty",
                        4,
                        "unitQty",
                        1))
                .status())
        .isEqualTo(200);
    Res waiting =
        post(
            INBOUND,
            "mfc-inbound-service",
            "/internal/replenishment-evaluations",
            UUID.randomUUID().toString(),
            scope);
    assertThat(waiting.body().path("created").asInt()).isZero();
    assertThat(waiting.body().path("skipped").findValuesAsString("reason"))
        .contains("WAITING_FOR_STOCK");
  }

  /** 입고 적치도 진열 규칙(2026-11-08): 같은 상품은 한 자리(고회전 제외), 카테고리 칸은 그 카테고리 상품만. */
  @Test
  void putawayFollowsShelfRules() {
    shelf("B-09-01", "STORAGE", 0, 0, 0, false);
    shelf("H-09-01", "PICKING", 0, 0, 0, true);
    shelf("K-09-01", "STORAGE", 0, 0, 0, false, "CAT-DRINK");
    awaitLocations(6);
    String sku = newSku();
    handlingOn(sku);
    projection(sku, "AMBIENT");
    String receiptId = "RCV-" + UUID.randomUUID();
    assertThat(
            postReceipt(
                    UUID.randomUUID().toString(),
                    receiptOf(receiptId, List.of(line(sku, 9, 0, null, null))))
                .status())
        .isEqualTo(201);
    String lot =
        get(INBOUND, "/internal/receipts/" + receiptId)
            .body()
            .path("lines")
            .path(0)
            .path("lotId")
            .asString();
    assertThat(
            execute(
                    "INSPECT",
                    "RCV-DOCK",
                    null,
                    List.of(Map.of("sku", sku, "lotId", lot, "qty", 9, "resultQuality", "NORMAL")))
                .status())
        .isEqualTo(201);
    assertThat(
            execute(
                    "PUTAWAY",
                    "RCV-DOCK",
                    "A-01-01",
                    List.of(Map.of("sku", sku, "lotId", lot, "qty", 3)))
                .status())
        .isEqualTo(201);
    Res second =
        execute(
            "PUTAWAY", "RCV-DOCK", "B-09-01", List.of(Map.of("sku", sku, "lotId", lot, "qty", 2)));
    assertThat(second.status()).isEqualTo(409);
    assertThat(second.body().path("code").asString()).isEqualTo("SKU_ALREADY_SHELVED");
    Res category =
        execute(
            "PUTAWAY", "RCV-DOCK", "K-09-01", List.of(Map.of("sku", sku, "lotId", lot, "qty", 2)));
    assertThat(category.body().path("code").asString()).isEqualTo("SKU_CATEGORY_UNKNOWN");
    assertThat(
            execute(
                    "PUTAWAY",
                    "RCV-DOCK",
                    "H-09-01",
                    List.of(Map.of("sku", sku, "lotId", lot, "qty", 2)))
                .status())
        .isEqualTo(201); // 고회전 칸은 두 번째 자리 가능
    assertThat(
            execute(
                    "PUTAWAY",
                    "RCV-DOCK",
                    "A-01-01",
                    List.of(Map.of("sku", sku, "lotId", lot, "qty", 4)))
                .status())
        .isEqualTo(201); // 원래 자리에 더 넣기
  }

  /** 수령 → 검수 → 칸별 적치(같은 LOT 을 여러 칸에). 반환 = lotId. */
  String stockedAt(String sku, String lotRef, String expiry, Map<String, Integer> byShelf) {
    projection(sku, "AMBIENT");
    int total = byShelf.values().stream().mapToInt(Integer::intValue).sum();
    String receiptId = "RCV-" + UUID.randomUUID();
    assertThat(
            postReceipt(
                    UUID.randomUUID().toString(),
                    receiptOf(receiptId, List.of(line(sku, total, 0, lotRef, expiry))))
                .status())
        .isEqualTo(201);
    String lot =
        get(INBOUND, "/internal/receipts/" + receiptId)
            .body()
            .path("lines")
            .path(0)
            .path("lotId")
            .asString();
    assertThat(
            execute(
                    "INSPECT",
                    "RCV-DOCK",
                    null,
                    List.of(
                        Map.of("sku", sku, "lotId", lot, "qty", total, "resultQuality", "NORMAL")))
                .status())
        .isEqualTo(201);
    byShelf.forEach(
        (shelf, q) ->
            assertThat(
                    execute(
                            "PUTAWAY",
                            "RCV-DOCK",
                            shelf,
                            List.of(Map.of("sku", sku, "lotId", lot, "qty", q)))
                        .status())
                .isEqualTo(201));
    return lot;
  }

  int partQty(String reservationId, String state) {
    return jdbc.queryForObject(
        "SELECT COALESCE(SUM(qty), 0) FROM reservation_part WHERE reservation_id = ? AND state = ?",
        Integer.class,
        reservationId,
        state);
  }

  /**
   * 사용자 결정(2026-11-08): 격리로 모자라면 그 칸에 배정된 주문부터 보류하고(예약 순서가 아니라), 같은 SKU 다른 적격 LOT 으로 다시 잡은
   * 뒤(ADR-0042 결정 1) 남은 것만 보류.
   */
  @Test
  void quarantineBlocksOrdersAllocatedAtThatShelfFirstAndResecuresFromAnotherLot() {
    location("PAK-A-01", "PACKING", "AMBIENT", "ACTIVE", 1);
    shelf("A-01-02", "STORAGE", 0, 0, 0, true); // 같은 LOT 을 두 칸에 — 고회전 칸이라 가능(한 상품 한 자리 예외)
    awaitLocations(5);
    String sku = newSku();
    handlingOn(sku);
    String lotA =
        stockedAt(
            sku, "REF-A", "2027-06-30", new LinkedHashMap<>(Map.of("A-01-01", 5, "A-01-02", 5)));
    String lotB = stockedAt(sku, "REF-B", "2027-12-31", Map.of("A-01-01", 3));

    // X(먼저) 5 → A-01-01 배정, Y(나중) 5 → A-01-02 배정 (FEFO 로 둘 다 LOT A)
    JsonNode x = confirmedAndAllocated("ORD-SX", sku, 5);
    JsonNode y = confirmedAndAllocated("ORD-SY", sku, 5);
    assertThat(x.path("parts").path(0).path("lotId").asString()).isEqualTo(lotA);
    assertThat(x.path("parts").path(0).path("allocations").path(0).path("locationCode").asString())
        .isEqualTo("A-01-01");
    assertThat(y.path("parts").path(0).path("allocations").path(0).path("locationCode").asString())
        .isEqualTo("A-01-02");
    String rx = x.path("reservationId").asString();
    String ry = y.path("reservationId").asString();

    // A-01-01 의 LOT A 5개 격리 → 그 칸에 배정된 X 가 보류 대상(나중 예약 Y 가 아니라)
    Res q = quality("QUARANTINE", "QD-SHELF", sku, lotA, 5);
    assertThat(q.status()).as(q.body().toString()).isEqualTo(201);
    assertThat(partQty(ry, "BLOCKED")).isZero();
    assertThat(partQty(ry, "CONFIRMED")).isEqualTo(5);
    // X: LOT B(빈 3개)로 다시 잡고, 남은 2 만 보류
    assertThat(partQty(rx, "BLOCKED")).isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT COALESCE(SUM(qty), 0) FROM reservation_part WHERE reservation_id = ?"
                    + " AND state = 'CONFIRMED' AND lot_id = ?",
                Integer.class,
                rx,
                lotB))
        .isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE type = 'ReservationBlocked'"
                    + " AND aggregateid = ? AND payload::text LIKE '%\"blockedQty\": 2%'",
                Integer.class, rx))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE type = 'ReservationBlocked' AND aggregateid = ?",
                Integer.class,
                ry))
        .isZero();
    // 총량: H13 Q5 E8 R8(Y5 + X 의 LOT B 3) A0
    JsonNode av = availability(sku);
    assertThat(av.path("quarantined").asInt()).isEqualTo(5);
    assertThat(av.path("sellable").asInt()).isEqualTo(8);
    assertThat(av.path("reserved").asInt()).isEqualTo(8);
    assertThat(av.path("available").asInt()).isZero();
  }

  /** 결제 중(HELD) 예약이 격리로 모자라게 되면 확정을 거절한다 — 사가가 결제를 보상 취소한다. */
  @Test
  void heldReservationThatLostStockIsNotConfirmed() {
    String sku = newSku();
    handlingOn(sku);
    stocked(sku, 5);
    Res held =
        reserve(
            UUID.randomUUID().toString(),
            Map.of(
                "orderId",
                "ORD-HS",
                "mfcId",
                mfc,
                "lines",
                List.of(Map.of("orderLineId", "L1", "sku", sku, "qty", 4))));
    String rid = held.body().path("resultId").asString();
    String lot =
        get(ORCH, "/internal/reservations/" + rid + "/pick-plan")
            .body()
            .path("parts")
            .path(0)
            .path("lotId")
            .asString();
    // 격리 3 → E2, R4 → 2 보류(다른 LOT 없음), 남은 HELD 2
    assertThat(quality("QUARANTINE", "QD-HS", sku, lot, 3).status()).isEqualTo(201);
    assertThat(partQty(rid, "BLOCKED")).isEqualTo(2);
    assertThat(partQty(rid, "HELD")).isEqualTo(2);
    Res confirm =
        post(
            ORCH,
            "order-orchestration-service",
            "/internal/reservations/" + rid + "/confirmations",
            UUID.randomUUID().toString(),
            Map.of("orderId", "ORD-HS", "paymentAttemptId", "PAY-HS"));
    assertThat(confirm.status()).isEqualTo(409);
    assertThat(confirm.body().path("code").asString()).isEqualTo("RESERVATION_SHORT");
  }

  /** 확정 예약 + 위치 배정(V11) — 배정 결과(PickPlan)를 돌려준다. */
  JsonNode confirmedAndAllocated(String orderId, String sku, int qty) {
    String rid =
        reserve(
                UUID.randomUUID().toString(),
                Map.of(
                    "orderId",
                    orderId,
                    "mfcId",
                    mfc,
                    "lines",
                    List.of(Map.of("orderLineId", "L1", "sku", sku, "qty", qty))))
            .body()
            .path("resultId")
            .asString();
    assertThat(
            post(
                    ORCH,
                    "order-orchestration-service",
                    "/internal/reservations/" + rid + "/confirmations",
                    UUID.randomUUID().toString(),
                    Map.of("orderId", orderId, "paymentAttemptId", "PAY-" + orderId))
                .status())
        .isEqualTo(200);
    Res a =
        post(
            INBOUND,
            "mfc-fulfillment-service",
            "/internal/reservations/" + rid + "/location-allocations",
            UUID.randomUUID().toString(),
            Map.of());
    assertThat(a.status()).as(a.body().toString()).isEqualTo(200);
    return a.body();
  }

  JsonNode snapshotLine(String sku, String location) {
    for (JsonNode n :
        get(ORCH, "/internal/snapshots?mfcId=" + mfc + "&sku=" + sku).body().path("items")) {
      if (n.path("locationCode").asString().equals(location)
          && n.path("workState").asString().equals("STORED")) {
        return n;
      }
    }
    return mapper.createObjectNode();
  }

  @Test
  void reservationsAreAllocatedToShelvesAndPickingConsumesOrMovesTheAllocation() {
    location("PAK-A-01", "PACKING", "AMBIENT", "ACTIVE", 1);
    shelf("A-01-02", "STORAGE", 0, 0, 0, true); // 같은 LOT 을 두 칸에 — 고회전 칸이라 가능(한 상품 한 자리 예외)
    awaitLocations(5);
    String sku = newSku();
    handlingOn(sku);
    projection(sku, "AMBIENT");
    // 같은 LOT 10개: A-01-01 에 7, A-01-02 에 3
    String receiptId = "RCV-" + UUID.randomUUID();
    assertThat(
            postReceipt(
                    UUID.randomUUID().toString(),
                    receiptOf(receiptId, List.of(line(sku, 10, 0, null, null))))
                .status())
        .isEqualTo(201);
    String lot =
        get(INBOUND, "/internal/receipts/" + receiptId)
            .body()
            .path("lines")
            .path(0)
            .path("lotId")
            .asString();
    assertThat(
            execute(
                    "INSPECT",
                    "RCV-DOCK",
                    null,
                    List.of(Map.of("sku", sku, "lotId", lot, "qty", 10, "resultQuality", "NORMAL")))
                .status())
        .isEqualTo(201);
    assertThat(
            execute(
                    "PUTAWAY",
                    "RCV-DOCK",
                    "A-01-01",
                    List.of(Map.of("sku", sku, "lotId", lot, "qty", 7)))
                .status())
        .isEqualTo(201);
    assertThat(
            execute(
                    "PUTAWAY",
                    "RCV-DOCK",
                    "A-01-02",
                    List.of(Map.of("sku", sku, "lotId", lot, "qty", 3)))
                .status())
        .isEqualTo(201);

    // 주문 A 5개: 한 칸(A-01-01, 7)에서 다 집을 수 있다 → 한 칸 배정
    JsonNode a = confirmedAndAllocated("ORD-ALC-A", sku, 5);
    JsonNode pa = a.path("parts").path(0);
    assertThat(pa.path("allocations").size()).isEqualTo(1);
    assertThat(pa.path("allocations").path(0).path("locationCode").asString()).isEqualTo("A-01-01");
    assertThat(pa.path("allocations").path(0).path("qty").asInt()).isEqualTo(5);
    assertThat(pa.path("unallocatedQty").asInt()).isZero();
    assertThat(pa.path("locations").path(0).path("allocatedQty").asInt()).isEqualTo(5);
    String ridA = a.path("reservationId").asString();
    // 다시 불러도 그대로(멱등)
    Res again =
        post(
            INBOUND,
            "mfc-fulfillment-service",
            "/internal/reservations/" + ridA + "/location-allocations",
            UUID.randomUUID().toString(),
            Map.of());
    assertThat(again.body().path("parts").path(0).path("allocations").size()).isEqualTo(1);
    assertThat(again.body().path("parts").path(0).path("allocations").path(0).path("qty").asInt())
        .isEqualTo(5);

    // 주문 B 4개: 남은 가용 A-01-01 2·A-01-02 3 — 한 칸으로 안 되니 많은 칸부터(A-01-02 3, A-01-01 1)
    JsonNode b = confirmedAndAllocated("ORD-ALC-B", sku, 4);
    JsonNode pb = b.path("parts").path(0);
    assertThat(pb.path("allocations").path(0).path("locationCode").asString()).isEqualTo("A-01-02");
    assertThat(pb.path("allocations").path(0).path("qty").asInt()).isEqualTo(3);
    assertThat(pb.path("allocations").path(1).path("locationCode").asString()).isEqualTo("A-01-01");
    assertThat(pb.path("allocations").path(1).path("qty").asInt()).isEqualTo(1);
    String ridB = b.path("reservationId").asString();

    // 위치별 가용: A-01-01 7 − 배정 6 = 1, A-01-02 3 − 3 = 0
    JsonNode s1 = snapshotLine(sku, "A-01-01");
    assertThat(s1.path("allocatedQty").asInt()).isEqualTo(6);
    assertThat(s1.path("pickableQty").asInt()).isEqualTo(1);
    assertThat(snapshotLine(sku, "A-01-02").path("pickableQty").asInt()).isZero();

    // B 가 A-01-01 에서 2개(배정 1 + 남는 1) → 허용, A-01-02 배정은 2 로 줄어든다
    Map<String, Object> two = Map.of("orderLineId", "L1", "sku", sku, "lotId", lot, "qty", 2);
    assertThat(pickPack("PICK", ridB, "A-01-01", "PAK-A-01", List.of(two)).status()).isEqualTo(201);
    JsonNode pb2 =
        get(ORCH, "/internal/reservations/" + ridB + "/pick-plan").body().path("parts").path(0);
    assertThat(pb2.path("allocations").size()).isEqualTo(1);
    assertThat(pb2.path("allocations").path(0).path("locationCode").asString())
        .isEqualTo("A-01-02");
    assertThat(pb2.path("allocations").path(0).path("qty").asInt()).isEqualTo(2);
    // A-01-01 에 남은 5 는 A 몫 — B 가 더 집으면 거절
    Map<String, Object> one = Map.of("orderLineId", "L1", "sku", sku, "lotId", lot, "qty", 1);
    Res steal = pickPack("PICK", ridB, "A-01-01", "PAK-A-01", List.of(one));
    assertThat(steal.status()).isEqualTo(409);
    assertThat(steal.body().path("code").asString()).isEqualTo("LOCATION_ALLOCATED_TO_OTHER");

    // A 는 배정대로 5개 → 배정 소진
    Map<String, Object> five = Map.of("orderLineId", "L1", "sku", sku, "lotId", lot, "qty", 5);
    assertThat(pickPack("PICK", ridA, "A-01-01", "PAK-A-01", List.of(five)).status())
        .isEqualTo(201);
    JsonNode pa2 =
        get(ORCH, "/internal/reservations/" + ridA + "/pick-plan").body().path("parts").path(0);
    assertThat(pa2.path("allocations").size()).isZero();
    assertThat(pa2.path("unallocatedQty").asInt()).isZero();
    JsonNode s2 = snapshotLine(sku, "A-01-02");
    assertThat(s2.path("qty").asInt()).isEqualTo(3);
    assertThat(s2.path("allocatedQty").asInt()).isEqualTo(2);
    assertThat(s2.path("pickableQty").asInt()).isEqualTo(1);
  }

  @Test
  void pickAndPackMoveReservedUnitsWithoutChangingTotals() {
    location("PAK-A-01", "PACKING", "AMBIENT", "ACTIVE", 1);
    awaitLocations(4);
    String sku = newSku();
    handlingOn(sku);
    stocked(sku, 10);
    Res held =
        reserve(
            UUID.randomUUID().toString(),
            Map.of(
                "orderId",
                "ORD-PICK",
                "mfcId",
                mfc,
                "lines",
                List.of(Map.of("orderLineId", "L1", "sku", sku, "qty", 4))));
    String reservationId = held.body().path("resultId").asString();

    // 확정 전에는 집품 못 한다
    JsonNode plan = get(ORCH, "/internal/reservations/" + reservationId + "/pick-plan").body();
    JsonNode part = plan.path("parts").path(0);
    String lot = part.path("lotId").asString();
    assertThat(part.path("qty").asInt()).isEqualTo(4);
    assertThat(part.path("locations").path(0).path("locationCode").asString()).isEqualTo("A-01-01");
    assertThat(part.path("locations").path(0).path("qty").asInt()).isEqualTo(10);
    Map<String, Object> line = Map.of("orderLineId", "L1", "sku", sku, "lotId", lot, "qty", 3);
    assertThat(pickPack("PICK", reservationId, "A-01-01", "PAK-A-01", List.of(line)).status())
        .isEqualTo(409);
    assertThat(
            post(
                    ORCH,
                    "order-orchestration-service",
                    "/internal/reservations/" + reservationId + "/confirmations",
                    UUID.randomUUID().toString(),
                    Map.of("orderId", "ORD-PICK", "paymentAttemptId", "PAY-P"))
                .status())
        .isEqualTo(200);

    // 포장 구역이 아닌 도착지, 예약에 없는 LOT, 남은 양 초과는 거절
    assertThat(pickPack("PICK", reservationId, "A-01-01", "C-01-01", List.of(line)).status())
        .isEqualTo(409);
    Res wrongLot =
        pickPack(
            "PICK",
            reservationId,
            "A-01-01",
            "PAK-A-01",
            List.of(Map.of("orderLineId", "L1", "sku", sku, "lotId", "LOT-NONE", "qty", 1)));
    assertThat(wrongLot.status()).isIn(400, 422);
    Res tooMany =
        pickPack(
            "PICK",
            reservationId,
            "A-01-01",
            "PAK-A-01",
            List.of(Map.of("orderLineId", "L1", "sku", sku, "lotId", lot, "qty", 5)));
    assertThat(tooMany.status()).isEqualTo(422);
    assertThat(tooMany.body().path("code").asString()).isEqualTo("PICK_QTY_EXCEEDED");

    // 3 집품 → 총량 불변(H10/E10/R4/A6), 선반 7·포장대 PICKED 3
    assertThat(pickPack("PICK", reservationId, "A-01-01", "PAK-A-01", List.of(line)).status())
        .isEqualTo(201);
    JsonNode av = availability(sku);
    assertThat(av.path("onHand").asInt()).isEqualTo(10);
    assertThat(av.path("sellable").asInt()).isEqualTo(10);
    assertThat(av.path("reserved").asInt()).isEqualTo(4);
    assertThat(av.path("available").asInt()).isEqualTo(6);
    JsonNode after = get(ORCH, "/internal/reservations/" + reservationId + "/pick-plan").body();
    assertThat(after.path("parts").path(0).path("pickedQty").asInt()).isEqualTo(3);
    assertThat(after.path("parts").path(0).path("locations").path(0).path("qty").asInt())
        .isEqualTo(7);

    // 포장은 집품한 만큼만, 같은 포장대에서 PICKED → PACKED
    Map<String, Object> pack = Map.of("orderLineId", "L1", "sku", sku, "lotId", lot, "qty", 4);
    assertThat(pickPack("PACK", reservationId, "PAK-A-01", null, List.of(pack)).status())
        .isEqualTo(422);
    assertThat(pickPack("PACK", reservationId, "PAK-A-01", null, List.of(line)).status())
        .isEqualTo(201);
    assertThat(
            jdbc.queryForObject(
                "SELECT qty FROM stock_unit WHERE mfc_id = ? AND sku = ? AND location_code ="
                    + " 'PAK-A-01' AND work_state = 'PACKED'",
                Integer.class,
                mfc,
                sku))
        .isEqualTo(3);
    assertThat(availability(sku).path("available").asInt()).isEqualTo(6);

    // 집품 뒤에는 단순 해제 불가(IV-08 전)
    Res release =
        post(
            ORCH,
            "order-orchestration-service",
            "/internal/reservations/" + reservationId + "/releases",
            UUID.randomUUID().toString(),
            Map.of("reason", "CANCEL_BEFORE_PICK"));
    assertThat(release.status()).isEqualTo(409);
    assertThat(release.body().path("code").asString()).isEqualTo("RESERVATION_PICKED");

    // 집품 뒤 취소(IV-08, ADR-0046): 포장한 3개를 위치와 함께 빠짐없이 — 모자라면 409
    String cr = "/internal/reservations/" + reservationId + "/cancel-returns";
    Map<String, Object> partial =
        Map.of(
            "reason",
            "CANCEL_AFTER_PICK",
            "returns",
            List.of(
                Map.of(
                    "orderLineId",
                    "L1",
                    "sku",
                    sku,
                    "lotId",
                    lot,
                    "locationCode",
                    "PAK-A-01",
                    "workState",
                    "PACKED",
                    "qty",
                    2)));
    Res mismatch =
        post(INBOUND, "mfc-fulfillment-service", cr, UUID.randomUUID().toString(), partial);
    assertThat(mismatch.status()).isEqualTo(409);
    assertThat(mismatch.body().path("code").asString()).isEqualTo("RETURN_MISMATCH");
    Map<String, Object> all =
        Map.of(
            "reason",
            "CANCEL_AFTER_PICK",
            "returns",
            List.of(
                Map.of(
                    "orderLineId",
                    "L1",
                    "sku",
                    sku,
                    "lotId",
                    lot,
                    "locationCode",
                    "PAK-A-01",
                    "workState",
                    "PACKED",
                    "qty",
                    3)));
    Res cancelled = post(INBOUND, "mfc-fulfillment-service", cr, UUID.randomUUID().toString(), all);
    assertThat(cancelled.status()).as(cancelled.body().toString()).isEqualTo(200);
    assertThat(cancelled.body().path("appliedQty").asInt()).isEqualTo(4); // 집품 3 + 안 집은 1
    // 포장분은 취소 복귀 대기(판매 제외), 예약 0 — H10/E7/R0/A7
    JsonNode afterCancel = availability(sku);
    assertThat(afterCancel.path("onHand").asInt()).isEqualTo(10);
    assertThat(afterCancel.path("sellable").asInt()).isEqualTo(7);
    assertThat(afterCancel.path("reserved").asInt()).isZero();
    assertThat(afterCancel.path("available").asInt()).isEqualTo(7);
    assertThat(get(ORCH, "/internal/reservations/" + reservationId).body().path("state").asString())
        .isEqualTo("RELEASED");
    // 다시 와도 변화 없음
    assertThat(
            post(INBOUND, "mfc-fulfillment-service", cr, UUID.randomUUID().toString(), all)
                .status())
        .isEqualTo(200);

    // 복귀 칸 규칙(#59): 추천 첫 칸 = 원래 칸(A-01-01), 다른 선반으로는 같은 상품 한 자리 규칙으로 409
    shelf("B-09-01", "STORAGE", 1000, 600, 400, false);
    awaitSql(
        "SELECT count(*) FROM location_projection WHERE mfc_id = '"
            + mfc
            + "' AND location_code = 'B-09-01'",
        1);
    JsonNode suggest =
        get(
                INBOUND,
                "/internal/placement-suggestions?mfcId="
                    + mfc
                    + "&sku="
                    + sku
                    + "&lotId="
                    + lot
                    + "&qty=3")
            .body();
    assertThat(suggest.path("locationCodes").path(0).asString())
        .as(suggest.toString())
        .isEqualTo("A-01-01");
    Res elsewhere =
        execute(
            "MOVE",
            "PAK-A-01",
            "B-09-01",
            List.of(Map.of("sku", sku, "lotId", lot, "qty", 3, "resultQuality", "NORMAL")));
    assertThat(elsewhere.status()).isEqualTo(409);
    assertThat(elsewhere.body().path("code").asString()).isEqualTo("SKU_ALREADY_SHELVED");

    // 선반 복귀(MOVE) → 판매 가능 회복 E10/A10
    Res back =
        execute(
            "MOVE",
            "PAK-A-01",
            "A-01-01",
            List.of(Map.of("sku", sku, "lotId", lot, "qty", 3, "resultQuality", "NORMAL")));
    assertThat(back.status()).as(back.body().toString()).isEqualTo(201);
    assertThat(availability(sku).path("sellable").asInt()).isEqualTo(10);
    assertThat(availability(sku).path("available").asInt()).isEqualTo(10);
  }

  Res handover(String packageId, String authId, int fence, String reservationId, Object line) {
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("packageId", packageId);
    b.put("handoverId", "HOV-" + packageId);
    b.put("handoverAuthorizationId", authId);
    b.put("fenceVersion", fence);
    b.put("mfcId", mfc);
    b.put("orderId", "ORD-HOV");
    b.put("reservationId", reservationId);
    b.put("shipmentId", "ORD-HOV-S1");
    b.put("deliveryId", "DLV-1");
    b.put("riderId", "R-1");
    b.put("locationCode", "PAK-A-01");
    b.put("lines", List.of(line));
    return post(
        INBOUND,
        "mfc-fulfillment-service",
        "/internal/package-handovers",
        UUID.randomUUID().toString(),
        b);
  }

  /** 라이더 인계(IV-08 인계, ADR-0047): H10/E10/R4/A6 에서 포장 3+1 인계 → H6/E6/R0/A6, 같은 포장은 한 번. */
  @Test
  void handoverPostsPackedUnitsOutOncePerPackage() {
    location("PAK-A-01", "PACKING", "AMBIENT", "ACTIVE", 1);
    awaitLocations(4);
    String sku = newSku();
    handlingOn(sku);
    stocked(sku, 10);
    String rid = confirmedReservation("ORD-HOV", sku, 4);
    String lot =
        get(ORCH, "/internal/reservations/" + rid + "/pick-plan")
            .body()
            .path("parts")
            .path(0)
            .path("lotId")
            .asString();
    Map<String, Object> four = Map.of("orderLineId", "L1", "sku", sku, "lotId", lot, "qty", 4);
    Map<String, Object> three = Map.of("orderLineId", "L1", "sku", sku, "lotId", lot, "qty", 3);
    Map<String, Object> one = Map.of("orderLineId", "L1", "sku", sku, "lotId", lot, "qty", 1);
    assertThat(pickPack("PICK", rid, "A-01-01", "PAK-A-01", List.of(four)).status()).isEqualTo(201);
    assertThat(pickPack("PACK", rid, "PAK-A-01", null, List.of(three)).status()).isEqualTo(201);

    // 포장 1(3개) 인계 → H7/E7/R1/A6
    Res first = handover("PKG-1", "HAU-1", 1, rid, three);
    assertThat(first.status()).as(first.body().toString()).isEqualTo(201);
    String commitId = first.body().path("resultId").asString();
    JsonNode av = availability(sku);
    assertThat(av.path("onHand").asInt()).isEqualTo(7);
    assertThat(av.path("sellable").asInt()).isEqualTo(7);
    assertThat(av.path("reserved").asInt()).isEqualTo(1);
    assertThat(av.path("available").asInt()).isEqualTo(6);
    // 같은 포장·같은 fence 는 다른 요청 ID 로도 한 번(처음 commitId), 다른 fence 는 409
    Res again = handover("PKG-1", "HAU-1", 1, rid, three);
    assertThat(again.status()).isEqualTo(201);
    assertThat(again.body().path("resultId").asString()).isEqualTo(commitId);
    assertThat(availability(sku).path("onHand").asInt()).isEqualTo(7);
    Res otherFence = handover("PKG-1", "HAU-9", 2, rid, three);
    assertThat(otherFence.status()).isEqualTo(409);
    assertThat(otherFence.body().path("code").asString()).isEqualTo("FENCE_VERSION_MISMATCH");
    // 아직 포장 안 한 양은 인계 못 한다
    Res notPacked = handover("PKG-2", "HAU-2", 1, rid, one);
    assertThat(notPacked.status()).isEqualTo(409);
    assertThat(notPacked.body().path("code").asString()).isEqualTo("HANDOVER_QTY_EXCEEDED");

    // 나머지 1 포장·인계 → H6/E6/R0/A6, 예약 소진
    assertThat(pickPack("PACK", rid, "PAK-A-01", null, List.of(one)).status()).isEqualTo(201);
    assertThat(handover("PKG-2", "HAU-2", 1, rid, one).status()).isEqualTo(201);
    JsonNode done = availability(sku);
    assertThat(done.path("onHand").asInt()).isEqualTo(6);
    assertThat(done.path("sellable").asInt()).isEqualTo(6);
    assertThat(done.path("reserved").asInt()).isZero();
    assertThat(done.path("available").asInt()).isEqualTo(6);
    assertThat(get(ORCH, "/internal/reservations/" + rid).body().path("state").asString())
        .isEqualTo("CONSUMED");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE type = 'PackageHandedOverPosted'"
                    + " AND aggregateid IN ('PKG-1', 'PKG-2')",
                Integer.class))
        .isEqualTo(2);
  }

  static final String QUALITY = TestJwt.service("mfc-quality-service");

  Res quality(String type, String approvalRef, String sku, String lot, int qty) {
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("postingType", type);
    b.put("mfcId", mfc);
    b.put("qualityDecisionId", "QD-" + UUID.randomUUID());
    b.put("approvalRef", approvalRef);
    b.put(
        "lines", List.of(Map.of("sku", sku, "lotId", lot, "locationCode", "A-01-01", "qty", qty)));
    return post(
        QUALITY,
        "mfc-quality-service",
        "/internal/quality-postings",
        UUID.randomUUID().toString(),
        b);
  }

  /** 품질 격리 → 넘치는 예약만 나중 예약부터 보류(BLOCKED) → 해제(E+) → 폐기(H−, Q−). */
  @Test
  void qualityQuarantineBlocksNewestReservationsThenReleaseAndDispose() {
    String sku = newSku();
    handlingOn(sku);
    stocked(sku, 10);
    String older = confirmedReservation("ORD-Q1", sku, 3);
    String newer = confirmedReservation("ORD-Q2", sku, 4);
    String lot =
        get(ORCH, "/internal/reservations/" + older + "/pick-plan")
            .body()
            .path("parts")
            .path(0)
            .path("lotId")
            .asString();
    assertThat(availability(sku).path("available").asInt()).isEqualTo(3);

    // 근거 없으면 거절
    Res noRef = quality("QUARANTINE", " ", sku, lot, 1);
    assertThat(noRef.body().path("code").asString()).isEqualTo("APPROVAL_REQUIRED");

    // 격리 6 → E4, R7 이 넘치는 3 은 나중 예약(ORD-Q2)에서 보류 → H10 Q6 E4 R4 A0
    Res q = quality("QUARANTINE", "QD-1", sku, lot, 6);
    assertThat(q.status()).as(q.body().toString()).isEqualTo(201);
    JsonNode av = availability(sku);
    assertThat(av.path("onHand").asInt()).isEqualTo(10);
    assertThat(av.path("quarantined").asInt()).isEqualTo(6);
    assertThat(av.path("sellable").asInt()).isEqualTo(4);
    assertThat(av.path("reserved").asInt()).isEqualTo(4);
    assertThat(av.path("available").asInt()).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COALESCE(SUM(qty), 0) FROM reservation_part WHERE reservation_id = ?"
                    + " AND state = 'BLOCKED' AND blocked_reason = 'QUALITY_HOLD'",
                Integer.class,
                newer))
        .isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM reservation_part WHERE reservation_id = ? AND state = 'BLOCKED'",
                Integer.class,
                older))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE type = 'ReservationBlocked'"
                    + " AND aggregateid = ?",
                Integer.class,
                newer))
        .isEqualTo(1);

    // 해제 2 → Q4 E6 A2 (보류된 예약은 그대로 — 자동 재배분 없음)
    assertThat(quality("RELEASE", "QD-2", sku, lot, 2).status()).isEqualTo(201);
    av = availability(sku);
    assertThat(av.path("quarantined").asInt()).isEqualTo(4);
    assertThat(av.path("sellable").asInt()).isEqualTo(6);
    assertThat(av.path("available").asInt()).isEqualTo(2);

    // 스냅샷: 단위 행(정상 6·격리 4) 두 줄을 한 줄씩 페이지로
    JsonNode p1 = get(ORCH, "/internal/snapshots?mfcId=" + mfc + "&sku=" + sku + "&size=1").body();
    assertThat(p1.path("items").size()).isEqualTo(1);
    String cursor = p1.path("page").path("nextCursor").asString();
    assertThat(cursor).isNotBlank();
    JsonNode p2 =
        get(ORCH, "/internal/snapshots?mfcId=" + mfc + "&sku=" + sku + "&size=1&cursor=" + cursor)
            .body();
    assertThat(p2.path("items").size()).isEqualTo(1);
    assertThat(p2.path("page").path("nextCursor").asString("")).isEmpty();
    assertThat(
            get(ORCH, "/internal/snapshots?mfcId=" + mfc + "&locationCode=NOPE")
                .body()
                .path("items")
                .size())
        .isZero();
    assertThat(
            get(ORCH, "/internal/snapshots?mfcId=" + mfc + "&locationCode=A-01-01")
                .body()
                .path("items")
                .size())
        .isGreaterThan(0);
    assertThat(
            List.of(
                p1.path("items").path(0).path("qualityState").asString(),
                p2.path("items").path(0).path("qualityState").asString()))
        .containsExactlyInAnyOrder("NORMAL", "QUARANTINED");
    // 폐기 4 → H6 Q0, 격리보다 많이 폐기는 거절
    assertThat(quality("DISPOSE", "DSP-1", sku, lot, 5).status()).isEqualTo(422);
    assertThat(quality("DISPOSE", "DSP-1", sku, lot, 4).status()).isEqualTo(201);
    av = availability(sku);
    assertThat(av.path("onHand").asInt()).isEqualTo(6);
    assertThat(av.path("quarantined").asInt()).isZero();
    assertThat(av.path("sellable").asInt()).isEqualTo(6);
  }

  Res count(String token, String path, Object body) {
    return post(token, "mfc-pda", "/mfcs/" + mfc + path, UUID.randomUUID().toString(), body);
  }

  /**
   * 순환 실사(IV-11): 잠금 위치만, 장부 숨김 → 첫 계수 차이 → 다른 작업자 재계수 → 시작·계수자 아닌 센터 관리자 승인(근거) → 조정 + 조사 보류 해소.
   */
  @Test
  void cycleCountNeedsRecountByAnotherWorkerAndSupervisorApproval() {
    String w1 = TestJwt.operator("cw1", List.of("MFC_WORKER"), List.of(mfc));
    String w2 = TestJwt.operator("cw2", List.of("MFC_WORKER"), List.of(mfc));
    String s1 = TestJwt.operator("cs1", List.of("MFC_SUPERVISOR"), List.of(mfc));
    String sku = newSku();
    handlingOn(sku);
    stocked(sku, 10);
    String rid = confirmedReservation("ORD-CNT", sku, 4);
    String lot =
        get(ORCH, "/internal/reservations/" + rid + "/pick-plan")
            .body()
            .path("parts")
            .path(0)
            .path("lotId")
            .asString();
    // 결품 보고 2 → 조사 보류(E−2), 같은 LOT 에서 재확보
    assertThat(shortage("RPT-CNT", rid, sku, lot, 2, "SHORT").status()).isEqualTo(201);
    assertThat(availability(sku).path("sellable").asInt()).isEqualTo(8);

    // 잠기지 않은 위치는 실사 못 한다
    Res notLocked = count(w1, "/count-sessions", Map.of("locationCodes", List.of("A-01-01")));
    assertThat(notLocked.status()).isEqualTo(409);
    assertThat(notLocked.body().path("code").asString()).isEqualTo("LOCATION_NOT_LOCKED");
    location("A-01-01", "STORAGE", "AMBIENT", "COUNT_LOCKED", 2);
    awaitSql(
        "SELECT count(*) FROM location_projection WHERE mfc_id = '"
            + mfc
            + "' AND location_code = 'A-01-01' AND state = 'COUNT_LOCKED'",
        1);

    // (0.16.0, BR-52 R19-1) 계수표 상품명·바코드 — SKU 사본(상품 사건 대신 열을 직접 채움, 선행 0 보존)
    jdbc.update(
        "UPDATE sku_projection SET product_name = ?, barcodes = ? WHERE sku = ?",
        "실사 우유",
        String.join("\n", "08801115114154", "8801115114154"),
        sku);
    Res created = count(w1, "/count-sessions", Map.of("locationCodes", List.of("A-01-01")));
    assertThat(created.status()).as(created.body().toString()).isEqualTo(201);
    String sid = created.body().path("sessionId").asString();
    String base = "/count-sessions/" + sid;
    assertThat(created.body().path("state").asString()).isEqualTo("COUNTING");
    assertThat(created.body().path("sheet").path(0).path("lotId").asString()).isEqualTo(lot);
    assertThat(created.body().path("sheet").path(0).path("productName").asString())
        .isEqualTo("실사 우유");
    assertThat(created.body().path("sheet").path(0).path("barcodes").path(0).asString())
        .isEqualTo("08801115114154");
    assertThat(created.body().path("sheet").path(0).path("barcodes")).hasSize(2);
    assertThat(created.body().path("sheet").path(0).has("qty")).isFalse();
    assertThat(created.body().path("differences").isNull()).isTrue();
    // 같은 위치 두 번째 실사는 막는다
    assertThat(
            count(w2, "/count-sessions", Map.of("locationCodes", List.of("A-01-01")))
                .body()
                .path("code")
                .asString())
        .isEqualTo("COUNT_IN_PROGRESS");

    Map<String, Object> seven =
        Map.of(
            "lines",
            List.of(Map.of("locationCode", "A-01-01", "sku", sku, "lotId", lot, "countedQty", 7)));
    Res first = count(w1, base + "/counts", seven);
    assertThat(first.status()).as(first.body().toString()).isEqualTo(200);
    assertThat(first.body().path("state").asString()).isEqualTo("RECOUNT_REQUIRED");
    assertThat(first.body().path("differences").isNull()).isTrue();
    // 재계수는 다른 작업자
    assertThat(count(w1, base + "/counts", seven).body().path("code").asString())
        .isEqualTo("SAME_COUNTER");
    Res second = count(w2, base + "/counts", seven);
    assertThat(second.body().path("state").asString()).isEqualTo("PENDING_APPROVAL");
    JsonNode diff = second.body().path("differences").path(0);
    assertThat(diff.path("bookQty").asInt()).isEqualTo(10);
    assertThat(diff.path("heldQty").asInt()).isEqualTo(2);
    assertThat(diff.path("countedQty").asInt()).isEqualTo(7);
    assertThat(diff.path("deltaQty").asInt()).isEqualTo(-3);

    // 작업자·계수자는 승인 못 하고, 근거 없는 승인도 안 된다
    assertThat(count(w2, base + "/approvals", Map.of("action", "APPROVE")).status()).isEqualTo(403);
    String w1s = TestJwt.operator("cw1", List.of("MFC_SUPERVISOR"), List.of(mfc));
    assertThat(
            count(w1s, base + "/approvals", Map.of("action", "APPROVE", "approvalRef", "A-1"))
                .body()
                .path("code")
                .asString())
        .isEqualTo("SELF_APPROVAL");
    assertThat(
            count(s1, base + "/approvals", Map.of("action", "APPROVE"))
                .body()
                .path("code")
                .asString())
        .isEqualTo("APPROVAL_REQUIRED");
    Res ok = count(s1, base + "/approvals", Map.of("action", "APPROVE", "approvalRef", "ADJ-1"));
    assertThat(ok.status()).as(ok.body().toString()).isEqualTo(200);
    assertThat(ok.body().path("state").asString()).isEqualTo("ADJUSTED");
    // H10 → 7, 보류 해소(없는 2 + 찾지 못한 1 손실) → E7, R4 그대로
    JsonNode av = availability(sku);
    assertThat(av.path("onHand").asInt()).isEqualTo(7);
    assertThat(av.path("sellable").asInt()).isEqualTo(7);
    assertThat(av.path("reserved").asInt()).isEqualTo(4);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_hold WHERE source_ref = 'RPT-CNT' AND state = 'RESOLVED'"
                    + " AND resolved_ref = ?",
                Integer.class,
                sid))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE type = 'StockAdjusted' AND aggregateid = ?",
                Integer.class,
                sid))
        .isEqualTo(1);
    // 같은 승인 다시 → 한 번만
    assertThat(
            count(s1, base + "/approvals", Map.of("action", "APPROVE", "approvalRef", "ADJ-1"))
                .body()
                .path("state")
                .asString())
        .isEqualTo("ADJUSTED");
    assertThat(availability(sku).path("onHand").asInt()).isEqualTo(7);

    // 다음 실사가 장부와 같으면(보류 없음) 승인 없이 끝
    String sid2 =
        count(w1, "/count-sessions", Map.of("locationCodes", List.of("A-01-01")))
            .body()
            .path("sessionId")
            .asString();
    assertThat(
            count(w1, "/count-sessions/" + sid2 + "/counts", seven).body().path("state").asString())
        .isEqualTo("MATCHED");
  }

  /**
   * 실사 승인 보류 비교(2026-11-08 검토 #12): 실사 시작 뒤 생긴 결품 조사 보류는 이 실사가 본 적이 없다 — 승인이 그 보류를 같이 풀면 안 되므로 단위
   * 수량이 같아도 보류가 바뀌었으면 BOOK_CHANGED, 보류는 열린 채로 남는다.
   */
  @Test
  void countApprovalRejectsHoldCreatedAfterCountStarted() {
    String w1 = TestJwt.operator("hw1", List.of("MFC_WORKER"), List.of(mfc));
    String w2 = TestJwt.operator("hw2", List.of("MFC_WORKER"), List.of(mfc));
    String s1 = TestJwt.operator("hs1", List.of("MFC_SUPERVISOR"), List.of(mfc));
    String sku = newSku();
    handlingOn(sku);
    stocked(sku, 10);
    String rid = confirmedReservation("ORD-CNTH", sku, 4);
    // 작업 실행(적치) 반영은 InventoryExecutionApplied 로 알린다(mfc-inbound 보충 즉시 점검, 남은 일 15)
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE type = 'InventoryExecutionApplied'"
                    + " AND topic = 'mfc.inventory.v1' AND payload #>> '{payload,mfcId}' = ?"
                    + " AND payload #>> '{payload,executionType}' = 'PUTAWAY'"
                    + " AND payload #>> '{payload,result}' = 'SUCCESS'",
                Integer.class,
                mfc))
        .isEqualTo(1);
    String lot =
        get(ORCH, "/internal/reservations/" + rid + "/pick-plan")
            .body()
            .path("parts")
            .path(0)
            .path("lotId")
            .asString();
    location("A-01-01", "STORAGE", "AMBIENT", "COUNT_LOCKED", 2);
    awaitSql(
        "SELECT count(*) FROM location_projection WHERE mfc_id = '"
            + mfc
            + "' AND location_code = 'A-01-01' AND state = 'COUNT_LOCKED'",
        1);
    Res created = count(w1, "/count-sessions", Map.of("locationCodes", List.of("A-01-01")));
    assertThat(created.status()).as(created.body().toString()).isEqualTo(201);
    String base = "/count-sessions/" + created.body().path("sessionId").asString();
    Map<String, Object> nine =
        Map.of(
            "lines",
            List.of(Map.of("locationCode", "A-01-01", "sku", sku, "lotId", lot, "countedQty", 9)));
    assertThat(count(w1, base + "/counts", nine).body().path("state").asString())
        .isEqualTo("RECOUNT_REQUIRED");
    Res second = count(w2, base + "/counts", nine);
    assertThat(second.body().path("state").asString()).isEqualTo("PENDING_APPROVAL");
    assertThat(second.body().path("differences").path(0).path("heldQty").asInt()).isZero();

    // 승인 전에 결품 보고 → 조사 보류 1 (단위 수량 H 는 그대로 10)
    Res report = shortage("RPT-CNTH", rid, sku, lot, 1, "SHORT");
    assertThat(report.status()).as(report.body().toString()).isEqualTo(201);
    assertThat(availability(sku).path("onHand").asInt()).isEqualTo(10);

    Res approve =
        count(s1, base + "/approvals", Map.of("action", "APPROVE", "approvalRef", "ADJ-H1"));
    assertThat(approve.status()).as(approve.body().toString()).isEqualTo(409);
    assertThat(approve.body().path("code").asString()).isEqualTo("BOOK_CHANGED");
    // 아무것도 바뀌지 않음: 보류는 열린 채, 재고 그대로, 조정 사건 없음
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_hold WHERE source_ref = 'RPT-CNTH' AND state = 'OPEN'",
                Integer.class))
        .isEqualTo(1);
    JsonNode av = availability(sku);
    assertThat(av.path("onHand").asInt()).isEqualTo(10);
    assertThat(av.path("sellable").asInt()).isEqualTo(9);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE type = 'StockAdjusted' AND aggregateid = ?",
                Integer.class,
                created.body().path("sessionId").asString()))
        .isZero();
  }

  /** InventorySnapshotPublished(남은 일 17): 센터 × SKU H·Q·E·R·A, 센터마다 버전 +1, 같은 트랜잭션 outbox. */
  @Test
  void snapshotPublishesCenterSkuBalancesWithIncreasingVersion() {
    String sku = newSku();
    handlingOn(sku);
    stocked(sku, 10);
    confirmedReservation("ORD-SNP", sku, 4);
    var first = snapshots.publish(mfc, "EVENT");
    assertThat(first.version()).isEqualTo(1);
    assertThat(first.parts()).isEqualTo(1);
    String payload =
        jdbc.queryForObject(
            "SELECT payload::text FROM outbox_event WHERE type = 'InventorySnapshotPublished'"
                + " AND topic = 'mfc.inventory.v1' AND payload #>> '{payload,snapshotId}' = ?",
            String.class,
            first.snapshotId());
    JsonNode p = mapper.readTree(payload).path("payload");
    assertThat(p.path("centerType").asString()).isEqualTo("MFC");
    assertThat(p.path("centerId").asString()).isEqualTo(mfc);
    assertThat(p.path("trigger").asString()).isEqualTo("EVENT");
    JsonNode line = null;
    for (JsonNode l : p.path("lines")) {
      if (sku.equals(l.path("sku").asString())) {
        line = l;
      }
    }
    assertThat(line).as(p.toString()).isNotNull();
    assertThat(line.path("onHandQty").asInt()).isEqualTo(10);
    assertThat(line.path("saleableQty").asInt()).isEqualTo(10);
    assertThat(line.path("reservedQty").asInt()).isEqualTo(4);
    assertThat(line.path("heldQty").asInt()).isZero();
    assertThat(line.path("availableQty").asInt()).isEqualTo(6);
    assertThat(line.has("safetyQty")).isFalse(); // 안전량 정책 미결 — 0 으로 채우지 않음
    assertThat(snapshots.publish(mfc, "PERIODIC").version()).isEqualTo(2);
  }

  /**
   * 보류분 자동 재확보(#5 안 C): 결품 보고로 BLOCKED 가 남은 확정 예약 → 재고가 들어오면(입고 적치) 주기 작업이 같은 SKU 적격 LOT 으로 다시 잡고
   * ReservationBlockCleared(남은 보류 0). 피킹이 시작된(위치 배정이 불린) 예약은 건드리지 않는다.
   */
  @Test
  void blockedPartIsResecuredWhenStockReturnsBeforePicking() {
    String sku = newSku();
    handlingOn(sku);
    stocked(sku, 3);
    String rid = confirmedReservation("ORD-BRS", sku, 3);
    String lot =
        get(ORCH, "/internal/reservations/" + rid + "/pick-plan")
            .body()
            .path("parts")
            .path(0)
            .path("lotId")
            .asString();
    // 파손 1 → 격리, 다른 LOT 없음 → BLOCKED 1
    assertThat(shortage("RPT-BRS", rid, sku, lot, 1, "DAMAGED").status()).isEqualTo(201);
    assertThat(blockedQty(rid)).isEqualTo(1);
    blockedResecurer.run(); // 아직 재고 없음 — 그대로(run 은 모든 센터를 본다)
    assertThat(blockedQty(rid)).isEqualTo(1);

    stocked(sku, 2); // 재고가 돌아옴
    blockedResecurer.run();
    assertThat(blockedQty(rid)).isZero();
    String cleared =
        jdbc.queryForObject(
            "SELECT payload #>> '{payload,remainingBlockedQty}' FROM outbox_event"
                + " WHERE type = 'ReservationBlockCleared' AND aggregateid = ?",
            String.class,
            rid);
    assertThat(cleared).isEqualTo("0");

    // 피킹 시작 뒤에는 자동 재확보 안 함
    String rid2 = confirmedReservation("ORD-BRS2", sku, 1);
    String lot2 =
        get(ORCH, "/internal/reservations/" + rid2 + "/pick-plan")
            .body()
            .path("parts")
            .path(0)
            .path("lotId")
            .asString();
    assertThat(
            post(
                    ORCH,
                    "mfc-fulfillment-service",
                    "/internal/reservations/" + rid2 + "/location-allocations",
                    UUID.randomUUID().toString(),
                    Map.of())
                .status())
        .isLessThan(300);
    assertThat(shortage("RPT-BRS2", rid2, sku, lot2, 1, "DAMAGED").status()).isEqualTo(201);
    int before = blockedQty(rid2);
    stocked(sku, 2);
    blockedResecurer.run();
    assertThat(blockedQty(rid2)).isEqualTo(before);
  }

  int blockedQty(String rid) {
    Integer n =
        jdbc.queryForObject(
            "SELECT COALESCE(SUM(qty), 0) FROM reservation_part WHERE reservation_id = ?"
                + " AND state = 'BLOCKED'",
            Integer.class,
            rid);
    return n == null ? 0 : n;
  }

  Res shortage(
      String reportId, String reservationId, String sku, String lot, int qty, String reason) {
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("reportId", reportId);
    b.put("mfcId", mfc);
    b.put("reservationId", reservationId);
    b.put("orderLineId", "L1");
    b.put("sku", sku);
    b.put("lotId", lot);
    b.put("locationCode", "A-01-01");
    b.put("qty", qty);
    b.put("reason", reason);
    return post(
        INBOUND,
        "mfc-fulfillment-service",
        "/internal/shortage-reports",
        UUID.randomUUID().toString(),
        b);
  }

  String confirmedReservation(String orderId, String sku, int qty) {
    Res held =
        reserve(
            UUID.randomUUID().toString(),
            Map.of(
                "orderId",
                orderId,
                "mfcId",
                mfc,
                "lines",
                List.of(Map.of("orderLineId", "L1", "sku", sku, "qty", qty))));
    assertThat(held.status()).as(held.body().toString()).isEqualTo(201);
    String id = held.body().path("resultId").asString();
    assertThat(
            post(
                    ORCH,
                    "order-orchestration-service",
                    "/internal/reservations/" + id + "/confirmations",
                    UUID.randomUUID().toString(),
                    Map.of("orderId", orderId, "paymentAttemptId", "PAY-" + orderId))
                .status())
        .isEqualTo(200);
    return id;
  }

  @Test
  void shortageHoldsMissingUnitsAndResecuresSameSkuEligibleLot() {
    java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
    String sku = newSku();
    handlingOn(sku);
    stocked(
        "RCV-" + UUID.randomUUID(),
        List.of(
            line(sku, 3, 0, "LOT-X", today.plusDays(60).toString()),
            line(sku, 5, 0, "LOT-Y", today.plusDays(90).toString())));
    String rid = confirmedReservation("ORD-SH1", sku, 2);
    JsonNode part =
        get(ORCH, "/internal/reservations/" + rid + "/pick-plan").body().path("parts").path(0);
    String lotX = part.path("lotId").asString();

    // 남은 예약보다 많이 보고 → 422
    assertThat(shortage("SR-0", rid, sku, lotX, 3, "SHORT").status()).isEqualTo(422);

    // 1개 못 찾음(SHORT) → 분실 조사 보류(H 그대로, E−1), 같은 SKU 적격 LOT 에서 1개 재확보 → 이행부족 0
    Res r = shortage("SR-1", rid, sku, lotX, 1, "SHORT");
    assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
    assertThat(r.body().path("heldQty").asInt()).isEqualTo(1);
    assertThat(r.body().path("resecuredQty").asInt()).isEqualTo(1);
    assertThat(r.body().path("blockedQty").asInt()).isZero();
    JsonNode av = availability(sku);
    assertThat(av.path("onHand").asInt()).isEqualTo(8);
    assertThat(av.path("quarantined").asInt()).isZero();
    assertThat(av.path("sellable").asInt()).isEqualTo(7);
    assertThat(av.path("reserved").asInt()).isEqualTo(2);
    // 보류분은 피킹 지시 위치 잔량에서 빠진다
    JsonNode plan = get(ORCH, "/internal/reservations/" + rid + "/pick-plan").body();
    int lotXAtShelf = 0;
    int confirmed = 0;
    for (JsonNode p : plan.path("parts")) {
      if ("CONFIRMED".equals(p.path("state").asString())) {
        confirmed += p.path("qty").asInt();
      }
      if (lotX.equals(p.path("lotId").asString()) && p.path("locations").size() > 0) {
        lotXAtShelf = p.path("locations").path(0).path("qty").asInt();
      }
    }
    assertThat(confirmed).isEqualTo(2);
    assertThat(lotXAtShelf).isEqualTo(2);
    // 같은 보고 다시 → 같은 결과, 다른 내용 → 409
    assertThat(shortage("SR-1", rid, sku, lotX, 1, "SHORT").body().path("commitId").asString())
        .isEqualTo(r.body().path("commitId").asString());
    assertThat(shortage("SR-1", rid, sku, lotX, 1, "DAMAGED").status()).isEqualTo(409);
  }

  @Test
  void damagedWithNothingToResecureBecomesBlockedShortfall() {
    java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
    String sku = newSku();
    handlingOn(sku);
    stocked(
        "RCV-" + UUID.randomUUID(),
        List.of(line(sku, 2, 0, "LOT-D", today.plusDays(60).toString())));
    String rid = confirmedReservation("ORD-SH2", sku, 2);
    String lot =
        get(ORCH, "/internal/reservations/" + rid + "/pick-plan")
            .body()
            .path("parts")
            .path(0)
            .path("lotId")
            .asString();
    Res r = shortage("SR-2", rid, sku, lot, 2, "DAMAGED");
    assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
    assertThat(r.body().path("quarantinedQty").asInt()).isEqualTo(2);
    assertThat(r.body().path("resecuredQty").asInt()).isZero();
    assertThat(r.body().path("blockedQty").asInt()).isEqualTo(2);
    // 파손은 실제 있는 이상품 — 격리(Q), E·R 에서 빠짐, H 그대로
    JsonNode av = availability(sku);
    assertThat(av.path("onHand").asInt()).isEqualTo(2);
    assertThat(av.path("quarantined").asInt()).isEqualTo(2);
    assertThat(av.path("sellable").asInt()).isZero();
    assertThat(av.path("reserved").asInt()).isZero();
    JsonNode parts = get(ORCH, "/internal/reservations/" + rid).body().path("parts");
    assertThat(parts.toString()).contains("\"BLOCKED\"");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE type = 'ReservationBlocked' AND aggregateid = ?",
                Integer.class,
                rid))
        .isEqualTo(1);

    // 주문에서 결품 수량을 취소하면 이행부족 해제(ADR-0045) — 현물·R 변화 없음, 더 많이는 409
    String path = "/internal/reservations/" + rid + "/shortfall-releases";
    Map<String, Object> rel =
        Map.of("orderLineId", "L1", "qty", 2, "reason", "OUT_OF_STOCK_CANCELLED");
    String key = UUID.randomUUID().toString();
    Res released = post(ORCH, "order-orchestration-service", path, key, rel);
    assertThat(released.status()).as(released.body().toString()).isEqualTo(200);
    assertThat(post(ORCH, "order-orchestration-service", path, key, rel).status()).isEqualTo(200);
    assertThat(get(ORCH, "/internal/reservations/" + rid).body().path("parts").toString())
        .doesNotContain("\"BLOCKED\"");
    assertThat(availability(sku).path("quarantined").asInt()).isEqualTo(2);
    assertThat(
            post(
                    ORCH,
                    "order-orchestration-service",
                    path,
                    UUID.randomUUID().toString(),
                    Map.of("orderLineId", "L1", "qty", 1, "reason", "OUT_OF_STOCK_CANCELLED"))
                .status())
        .isEqualTo(409);
  }

  @Test
  void inspectionSplitsTheReceivedLotByManufacturerLotAndExpiry() {
    // 수령은 상품·수량만(LOT 미확정) → 품질검사에서 제조 LOT·소비기한별로 나누고 격리분을 가른다
    String sku = newSku();
    projection(sku, "AMBIENT");
    String receiptId = "RCV-" + UUID.randomUUID();
    assertThat(postReceipt(UUID.randomUUID().toString(), receipt(receiptId, sku, 6, 0)).status())
        .isEqualTo(201);
    String pending =
        get(INBOUND, "/internal/receipts/" + receiptId)
            .body()
            .path("lines")
            .path(0)
            .path("lotId")
            .asString();
    java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
    Map<String, Object> a = new LinkedHashMap<>();
    a.put("sku", sku);
    a.put("lotId", pending);
    a.put("qty", 3);
    a.put("resultQuality", "NORMAL");
    a.put("sourceLotRef", "MFG-A");
    a.put("expiryDate", today.plusDays(60).toString());
    Map<String, Object> b = new LinkedHashMap<>(a);
    b.put("qty", 2);
    b.put("sourceLotRef", "MFG-B");
    b.put("expiryDate", today.plusDays(90).toString());
    Map<String, Object> c = new LinkedHashMap<>();
    c.put("sku", sku);
    c.put("lotId", pending);
    c.put("qty", 1);
    c.put("resultQuality", "QUARANTINED");
    Res inspected = execute("INSPECT", "RCV-DOCK", null, List.of(a, b, c));
    assertThat(inspected.status()).isEqualTo(201);
    JsonNode entries =
        get(INBOUND, "/internal/commits/" + inspected.body().path("resultId").asString())
            .body()
            .path("entries");
    java.util.Set<String> normalLots = new java.util.TreeSet<>();
    for (JsonNode e : entries) {
      if ("H".equals(e.path("symbol").asString())
          && e.path("delta").asInt() > 0
          && "NORMAL".equals(e.path("qualityState").asString())) {
        normalLots.add(e.path("lotId").asString());
      }
    }
    assertThat(normalLots).hasSize(2).doesNotContain(pending);
    JsonNode av = availability(sku);
    assertThat(av.path("onHand").asInt()).isEqualTo(6);
    assertThat(av.path("quarantined").asInt()).isEqualTo(1);
    assertThat(av.path("sellable").asInt()).isZero();
    // 같은 제조 LOT·기한은 다음 수령에서도 같은 LOT
    String receipt2 = "RCV-" + UUID.randomUUID();
    postReceipt(UUID.randomUUID().toString(), receipt(receipt2, sku, 1, 0));
    String pending2 =
        get(INBOUND, "/internal/receipts/" + receipt2)
            .body()
            .path("lines")
            .path(0)
            .path("lotId")
            .asString();
    Map<String, Object> again = new LinkedHashMap<>(a);
    again.put("lotId", pending2);
    again.put("qty", 1);
    Res second = execute("INSPECT", "RCV-DOCK", null, List.of(again));
    JsonNode e2 =
        get(INBOUND, "/internal/commits/" + second.body().path("resultId").asString())
            .body()
            .path("entries");
    String reused = null;
    for (JsonNode e : e2) {
      if (e.path("delta").asInt() > 0) {
        reused = e.path("lotId").asString();
      }
    }
    assertThat(normalLots).contains(reused);
  }

  @Test
  void correctionMovesLotAndLocationWithoutChangingTotalsButNotReservedQuantity() {
    // 이관처럼 추적 미확인 LOT 5개(선반) + 예약 2 → 3개만 정정 가능(R ≤ E)
    String sku = newSku();
    handlingOn(sku);
    stocked(sku, 5);
    assertThat(
            reserve(UUID.randomUUID().toString(), reserveBody("ORD-COR", Map.of(sku, 2))).status())
        .isEqualTo(201);
    String op = TestJwt.operator("op", List.of(), List.of(mfc));
    JsonNode stock = get(op, "/mfcs/" + mfc + "/stock?sku=" + sku).body();
    assertThat(stock.path("lots")).hasSize(1);
    String lot = stock.path("lots").path(0).path("lotId").asString();
    assertThat(stock.path("lots").path(0).path("tracking").asString()).isEqualTo("UNTRACKED");
    assertThat(stock.path("lots").path(0).path("available").asInt()).isEqualTo(3);
    String expiry = java.time.LocalDate.now().plusDays(40).toString();
    Map<String, Object> line = new LinkedHashMap<>();
    line.put("sku", sku);
    line.put("lotId", lot);
    line.put("fromLocation", "A-01-01");
    line.put("qualityState", "NORMAL");
    line.put("workState", "STORED");
    line.put("qty", 4);
    line.put("toSourceLotRef", "SEED-LOT-1");
    line.put("toExpiryDate", expiry);
    Map<String, Object> body =
        Map.of("reason", "시드 LOT 정정", "approvalRef", "seed-v2", "lines", List.of(line));
    String path = "/mfcs/" + mfc + "/stock-corrections";
    Res tooMuch = post(op, "backoffice-web", path, UUID.randomUUID().toString(), body);
    assertThat(tooMuch.status()).isEqualTo(409);
    assertThat(tooMuch.body().path("code").asString()).isEqualTo("INVARIANT_VIOLATION");
    line.put("qty", 3);
    Res ok = post(op, "backoffice-web", path, UUID.randomUUID().toString(), body);
    assertThat(ok.status()).as(ok.body().toString()).isEqualTo(201);
    JsonNode after = get(op, "/mfcs/" + mfc + "/stock?sku=" + sku).body();
    assertThat(after.path("lots")).hasSize(2);
    JsonNode av = availability(sku);
    assertThat(av.path("onHand").asInt()).isEqualTo(5);
    assertThat(av.path("sellable").asInt()).isEqualTo(5);
    assertThat(av.path("reserved").asInt()).isEqualTo(2);
    assertThat(
            get(INBOUND, "/internal/commits/" + ok.body().path("resultId").asString())
                .body()
                .path("commitType")
                .asString())
        .isEqualTo("CORRECTION");
    // 다른 센터 운영자·근거 없음
    String other = TestJwt.operator("op2", List.of(), List.of("MFC-OTHER"));
    assertThat(post(other, "backoffice-web", path, UUID.randomUUID().toString(), body).status())
        .isEqualTo(403);
    assertThat(
            post(
                    op,
                    "backoffice-web",
                    path,
                    UUID.randomUUID().toString(),
                    Map.of("reason", "x", "approvalRef", "", "lines", List.of(line)))
                .status())
        .isEqualTo(422);
  }

  @Test
  void concurrentReservationsOnLastUnitsOnlyOneSucceeds() throws Exception {
    // TC-09: H5/E5/R0/A5 에 주문 A4·B4 동시 → 하나만 성공(R4/A1)
    String sku = newSku();
    handlingOn(sku);
    stocked(sku, 5);
    int rounds = 2;
    ExecutorService pool = Executors.newFixedThreadPool(rounds);
    try {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<Res>> futures = new ArrayList<>();
      for (int i = 0; i < rounds; i++) {
        String order = "ORD-C" + i;
        Callable<Res> c =
            () -> {
              start.await();
              return reserve(UUID.randomUUID().toString(), reserveBody(order, Map.of(sku, 4)));
            };
        futures.add(pool.submit(c));
      }
      start.countDown();
      List<Integer> statuses = new ArrayList<>();
      for (Future<Res> f : futures) {
        statuses.add(f.get().status());
      }
      assertThat(statuses).containsExactlyInAnyOrder(201, 422);
    } finally {
      pool.shutdownNow();
    }
    JsonNode a = availability(sku);
    assertThat(a.path("reserved").asInt()).isEqualTo(4);
    assertThat(a.path("available").asInt()).isEqualTo(1);
  }

  @Test
  void expiredHoldIsSweptAndPublished() throws Exception {
    String sku = newSku();
    handlingOn(sku);
    stocked(sku, 3);
    Res ok = reserve(UUID.randomUUID().toString(), reserveBody("ORD-EXP", Map.of(sku, 2)));
    assertThat(ok.status()).isEqualTo(201);
    String reservationId = ok.body().path("resultId").asString();
    assertThat(availability(sku).path("available").asInt()).isEqualTo(1);

    // 만료 시각을 과거로(TTL 최소 1분이라 시험에서 앞당김) → 만료 작업이 EXPIRED, R−q
    jdbc.update(
        "UPDATE reservation SET expires_at = ? WHERE reservation_id = ?",
        Timestamp.from(Instant.now().minusSeconds(1)),
        reservationId);
    Instant until = Instant.now().plus(Duration.ofSeconds(30));
    JsonNode rv = get(ORCH, "/internal/reservations/" + reservationId).body();
    while (!"EXPIRED".equals(rv.path("state").asString()) && Instant.now().isBefore(until)) {
      Thread.sleep(300);
      rv = get(ORCH, "/internal/reservations/" + reservationId).body();
    }
    assertThat(rv.path("state").asString()).isEqualTo("EXPIRED");
    assertThat(rv.path("version").asInt()).isEqualTo(2);
    assertThat(availability(sku).path("reserved").asInt()).isZero();
    assertThat(availability(sku).path("available").asInt()).isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE topic = 'mfc.inventory.v1'"
                    + " AND type = 'InventoryHoldExpired' AND aggregateid = ?",
                Integer.class,
                reservationId))
        .isEqualTo(1);

    // 만료된 예약 확정 → 409, 해제 → 변화 없는 성공
    Res confirm =
        post(
            ORCH,
            "order-orchestration-service",
            "/internal/reservations/" + reservationId + "/confirmations",
            UUID.randomUUID().toString(),
            Map.of("orderId", "ORD-EXP"));
    assertThat(confirm.status()).isEqualTo(409);
    Res release =
        post(
            ORCH,
            "order-orchestration-service",
            "/internal/reservations/" + reservationId + "/releases",
            UUID.randomUUID().toString(),
            Map.of("reason", "PAYMENT_FAILED"));
    assertThat(release.status()).isEqualTo(200);
    assertThat(release.body().path("appliedQty").asInt()).isZero();
    assertThat(availability(sku).path("available").asInt()).isEqualTo(3);
  }
}
