package com.pmart.cdc.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.pmart.common.test.EventEnvelopeFixtures;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.errors.TopicExistsException;
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
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 장부 흐름(0.2.0): 상품 투영 → 초기 보유(1회) → 취급 ON·안전량 → 가용 max(0, E−R−S) → FEFO 다LOT 부분 할당 → 일부 해제(늦은 LOT
 * 부터) → TOUT 출발 전기(현물 −q·예약 −q·Transit +q, 재전송은 같은 commitId) → 초과 출발 거절 → 잔여 해제 → 원결과 조회. 실제
 * PostgreSQL·Kafka.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "pmart.messaging.outbox.delete-after-insert=false",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.inventory.approval-ref.mode=VERIFY",
      "pmart.inventory.approval-ref.hq-loss-krw=100000",
      "pmart.inventory.approval-ref.hq-sku-qty=20"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock({
  @ConfigureWireMock(
      name = "location",
      baseUrlProperties = "pmart.inventory.cdc-location-base-url"),
  @ConfigureWireMock(
      name = "pricing",
      baseUrlProperties = "pmart.inventory.approval-ref.pricing-base-url"),
  @ConfigureWireMock(
      name = "approval",
      baseUrlProperties = "pmart.inventory.approval-ref.approval-base-url")
})
class StockLedgerFlowIT extends PostgresKafkaContainerSupport {

  static final String CENTER = "CDC01";
  static final String OPERATOR = TestJwt.operator("op-1", List.of("CDC_MANAGER"), List.of(CENTER));
  static final String SERVICE = TestJwt.service("cdc-outbound-service");
  static final String LOC1 = "CDC01-C-5-01-01-1-1";
  static final String LOC2 = "CDC01-C-5-01-02-1-1";
  static final String LOC3 = "CDC01-C-5-01-03-1-1";

  @LocalServerPort int port;

  @InjectWireMock("location")
  WireMockServer location;

  @InjectWireMock("pricing")
  WireMockServer pricing;

  @InjectWireMock("approval")
  WireMockServer approval;

  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;
  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired com.pmart.cdc.inventory.application.ReceiptService receipts;
  @Autowired com.pmart.common.messaging.EventEnvelopeCodec codec;
  @Autowired org.springframework.transaction.support.TransactionTemplate txTemplate;

  RestClient http;

  @BeforeEach
  void client() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
  }

  record Res(int status, JsonNode body) {}

  Res post(String token, String path, String key, Object body) {
    var e =
        http.post()
            .uri(path)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", key);
                  h.set("X-Source-System", "cdc-outbound-service");
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

  /** 이 시험이 먼저 돌면 상품 토픽이 아직 없을 수 있다 — 직접 만든다(이미 있으면 무시). */
  static void ensureTopic(String topic) throws Exception {
    try (Admin admin =
        Admin.create(
            Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka().getBootstrapServers()))) {
      admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get();
    } catch (java.util.concurrent.ExecutionException e) {
      if (!(e.getCause() instanceof TopicExistsException)) {
        throw e;
      }
    }
  }

  void productReady(String sku) throws Exception {
    ensureTopic("commerce.product.v1");
    String productId = "PRD-" + UUID.randomUUID();
    Map<String, Object> s = new LinkedHashMap<>();
    s.put("sku", sku);
    s.put("unitType", "E");
    s.put("unitsPerSku", 1);
    s.put("weightG", 500);
    s.put("volumeMl", 500);
    s.put("storageType", "AMBIENT");
    s.put("alcohol", false);
    s.put("state", "ACTIVE");
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("productId", productId);
    payload.put("baseProductId", "BP-" + productId);
    payload.put("skus", List.of(s));
    payload.put("version", 1);
    payload.put("effectiveAt", Instant.now().toString());
    payload.put("saleState", "ON_SALE");
    String json =
        EventEnvelopeFixtures.envelope("ProductVersionPublished")
            .from("product-service")
            .aggregate("Product", productId, 1)
            .payload(payload)
            .buildJson();
    kafka.send("commerce.product.v1", productId, json).join();
    Instant until = Instant.now().plus(Duration.ofSeconds(60));
    while (get(SERVICE, "/internal/skus/" + sku).status() != 200 && Instant.now().isBefore(until)) {
      Thread.sleep(300);
    }
    assertThat(get(SERVICE, "/internal/skus/" + sku).status()).isEqualTo(200);
  }

  static Map<String, Object> ib(
      String sku, String lot, String loc, int qty, LocalDate exp, String ident, String quality) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("sku", sku);
    m.put("lot", lot);
    m.put("lotIdentification", ident);
    m.put("expiresAt", exp.toString());
    m.put("locationCode", loc);
    m.put("qty", qty);
    m.put("qualityState", quality);
    return m;
  }

  Res availability(String sku) {
    return get(SERVICE, "/internal/availability?cdcId=" + CENTER + "&sku=" + sku);
  }

  int outbox(String type, String aggregateId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM outbox_event WHERE type = ? AND aggregateid = ? AND topic = ?",
            Integer.class,
            type,
            aggregateId,
            "cdc.inventory.v1");
    return n == null ? 0 : n;
  }

  @Test
  void initialBalanceAllocateReleaseDepart() throws Exception {
    String sku = "E-" + String.format("%08d", ThreadLocalRandom.current().nextInt(1, 99_999_999));
    productReady(sku);
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));

    // 1) 초기 보유: 적격 A1(10일)·A2(40일), 미확인 A3, 격리 A4
    Map<String, Object> init =
        Map.of(
            "cdcId",
            CENTER,
            "approvalRef",
            "APV-INIT-1",
            "lines",
            List.of(
                ib(sku, "A1", LOC1, 30, today.plusDays(10), "VERIFIED", "RELEASED"),
                ib(sku, "A2", LOC2, 100, today.plusDays(40), "VERIFIED", "RELEASED"),
                ib(sku, "A3", LOC3, 50, today.plusDays(5), "UNVERIFIED", "RELEASED"),
                ib(sku, "A4", LOC3, 10, today.plusDays(20), "VERIFIED", "QUARANTINED")));
    String initKey = key();
    Res r = post(SERVICE, "/internal/initial-balances", initKey, init);
    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    assertThat(r.body().path("appliedQty").asInt()).isEqualTo(190);
    String initCommit = r.body().path("resultId").asString();
    assertThat(
            post(SERVICE, "/internal/initial-balances", initKey, init)
                .body()
                .path("resultId")
                .asString())
        .isEqualTo(initCommit);
    Res again = post(SERVICE, "/internal/initial-balances", key(), init);
    assertThat(again.status()).isEqualTo(409);
    assertThat(again.body().path("code").asString()).isEqualTo("INITIAL_BALANCE_EXISTS");

    // 2) 취급 OFF → 422, 켠 뒤 안전량 없음 → CONFIG_REQUIRED, 안전량 20
    assertThat(availability(sku).body().path("code").asString()).isEqualTo("SKU_NOT_HANDLED");
    Res on =
        post(
            OPERATOR,
            "/cdcs/" + CENTER + "/sku-handlings",
            key(),
            Map.of("sku", sku, "enabled", true, "reason", "개시"));
    assertThat(on.status()).isEqualTo(200);
    assertThat(availability(sku).body().path("code").asString()).isEqualTo("CONFIG_REQUIRED");
    Res safety =
        post(
            SERVICE,
            "/internal/safety-stocks",
            key(),
            Map.of(
                "cdcId",
                CENTER,
                "approvalRef",
                "APV-S-1",
                "lines",
                List.of(Map.of("sku", sku, "safetyQty", 20))));
    assertThat(safety.status()).isEqualTo(200);

    JsonNode a = availability(sku).body();
    assertThat(a.path("onHand").asInt()).isEqualTo(190);
    assertThat(a.path("eligible").asInt()).isEqualTo(130);
    assertThat(a.path("held").asInt()).isEqualTo(10);
    assertThat(a.path("available").asInt()).isEqualTo(110); // 130 − 0 − 20
    assertThat(a.path("safety").path("value").asInt()).isEqualTo(20);

    // 3) 할당 150 → 110 (A1 30 → A2 80), 부족 40
    String trf = "TRF-" + UUID.randomUUID();
    Res alloc =
        post(
            SERVICE,
            "/internal/allocations",
            key(),
            Map.of(
                "cdcId",
                CENTER,
                "trfId",
                trf,
                "trfVersion",
                1,
                "lines",
                List.of(Map.of("sku", sku, "requestedQty", 150))));
    assertThat(alloc.status()).as(alloc.body().toString()).isEqualTo(200);
    assertThat(alloc.body().path("state").asString()).isEqualTo("PARTIAL");
    JsonNode line = alloc.body().path("lines").get(0);
    assertThat(line.path("allocatedQty").asInt()).isEqualTo(110);
    assertThat(line.path("shortQty").asInt()).isEqualTo(40);
    assertThat(line.path("lots").get(0).path("lot").asString()).isEqualTo("A1");
    assertThat(line.path("lots").get(0).path("qty").asInt()).isEqualTo(30);
    assertThat(line.path("lots").get(1).path("lot").asString()).isEqualTo("A2");
    assertThat(line.path("lots").get(1).path("qty").asInt()).isEqualTo(80);
    String allocationId = alloc.body().path("allocationId").asString();
    assertThat(outbox("AllocationResult", allocationId)).isEqualTo(1);
    assertThat(availability(sku).body().path("available").asInt()).isZero();

    // 같은 TRF·버전을 새 키로 → 409
    Res dupAlloc =
        post(
            SERVICE,
            "/internal/allocations",
            key(),
            Map.of(
                "cdcId",
                CENTER,
                "trfId",
                trf,
                "trfVersion",
                1,
                "lines",
                List.of(Map.of("sku", sku, "requestedQty", 1))));
    assertThat(dupAlloc.body().path("code").asString()).isEqualTo("ALLOCATION_EXISTS");

    // 4) 일부 해제 20 — 늦은 LOT(A2)부터
    Res rel =
        post(
            SERVICE,
            "/internal/allocations/" + allocationId + "/releases",
            key(),
            Map.of("reason", "TRANSFER_CHANGED", "lines", List.of(Map.of("sku", sku, "qty", 20))));
    assertThat(rel.status()).as(rel.body().toString()).isEqualTo(200);
    assertThat(availability(sku).body().path("available").asInt()).isEqualTo(20);

    // 5) 출발 전기 A1 30 + A2 50
    Map<String, Object> dep = new LinkedHashMap<>();
    dep.put("cdcId", CENTER);
    dep.put("departureId", "DEP-" + UUID.randomUUID());
    dep.put("departureSource", "SIMULATED");
    dep.put("toMfcId", "MFC-BJG-01");
    dep.put("trfId", trf);
    dep.put("trfVersion", 1);
    dep.put("allocationId", allocationId);
    dep.put("departedAt", Instant.now().toString());
    dep.put(
        "lines",
        List.of(
            Map.of(
                "departureLineId",
                trf + "-1",
                "sku",
                sku,
                "lot",
                "A1",
                "qty",
                30,
                "lpns",
                List.of("LPN-1")),
            Map.of("departureLineId", trf + "-2", "sku", sku, "lot", "A2", "qty", 50)));
    Res d = post(SERVICE, "/internal/departure-postings", key(), dep);
    assertThat(d.status()).as(d.body().toString()).isEqualTo(200);
    assertThat(d.body().path("appliedQty").asInt()).isEqualTo(80);
    String tout = d.body().path("resultId").asString();
    assertThat(outbox("TransferDeparted", trf)).isEqualTo(1);
    String payload =
        jdbc.queryForObject(
            "SELECT payload::text FROM outbox_event WHERE type = 'TransferDeparted' AND aggregateid = ?",
            String.class,
            trf);
    JsonNode env = read(payload);
    assertThat(env.path("payload").path("toMfc").asString()).isEqualTo("MFC-BJG-01");
    assertThat(env.path("payload").path("departedQty").asInt()).isEqualTo(80);
    assertThat(env.path("source").asString()).isEqualTo("SIMULATED");
    JsonNode first = env.path("payload").path("lines").get(0);
    assertThat(first.path("lpn").asString()).isEqualTo("LPN-1");
    assertThat(first.path("expiryDate").asString()).isEqualTo(today.plusDays(10).toString());

    // 새 키로 재전송 → 같은 commitId, 재차감 없음
    Res resend = post(SERVICE, "/internal/departure-postings", key(), dep);
    assertThat(resend.body().path("resultId").asString()).isEqualTo(tout);
    JsonNode after = availability(sku).body();
    assertThat(after.path("onHand").asInt()).isEqualTo(110);
    assertThat(after.path("transit").asInt()).isEqualTo(80);
    assertThat(after.path("reserved").asInt()).isEqualTo(10);

    JsonNode transits = get(SERVICE, "/internal/transits?trfId=" + trf).body();
    assertThat(transits.path("items").get(0).path("state").asString()).isEqualTo("TRANSIT_OPEN");
    assertThat(transits.path("items").get(0).path("mfcId").asString()).isEqualTo("MFC-BJG-01");
    assertThat(transits.path("items").get(0).path("lines").get(1).path("t").asInt()).isEqualTo(50);

    // 6) 다른 TRF 5 할당 → 6 출발은 거절
    String trf2 = "TRF-" + UUID.randomUUID();
    Res alloc2 =
        post(
            SERVICE,
            "/internal/allocations",
            key(),
            Map.of(
                "cdcId",
                CENTER,
                "trfId",
                trf2,
                "trfVersion",
                1,
                "lines",
                List.of(Map.of("sku", sku, "requestedQty", 5))));
    assertThat(alloc2.body().path("state").asString()).isEqualTo("ALLOCATED");
    Map<String, Object> dep2 = new LinkedHashMap<>(dep);
    dep2.put("trfId", trf2);
    dep2.put("allocationId", alloc2.body().path("allocationId").asString());
    dep2.put(
        "lines",
        List.of(Map.of("departureLineId", trf2 + "-1", "sku", sku, "lot", "A2", "qty", 6)));
    Res over = post(SERVICE, "/internal/departure-postings", key(), dep2);
    assertThat(over.status()).isEqualTo(409);
    assertThat(over.body().path("code").asString()).isEqualTo("DEPARTURE_EXCEEDS_ALLOCATED");

    // 7) TRF 1 잔여 해제 → DEPARTED
    Res residual =
        post(
            SERVICE,
            "/internal/allocations/" + allocationId + "/releases",
            key(),
            Map.of("reason", "RESIDUAL_CLOSED"));
    assertThat(residual.status()).isEqualTo(200);
    assertThat(residual.body().path("appliedQty").asInt()).isEqualTo(10);
    assertThat(
            get(SERVICE, "/internal/allocations/" + allocationId).body().path("state").asString())
        .isEqualTo("DEPARTED");

    // 8) 원결과
    JsonNode commit = get(SERVICE, "/internal/commits/" + tout).body();
    assertThat(commit.path("postingKind").asString()).isEqualTo("TOUT");
    int transitDelta = 0;
    for (JsonNode x : commit.path("deltas")) {
      if ("TRANSIT".equals(x.path("bucket").asString())) {
        transitDelta += x.path("delta").asInt();
      }
    }
    assertThat(transitDelta).isEqualTo(80);
    assertThat(
            get(SERVICE, "/internal/commits/" + initCommit).body().path("postingKind").asString())
        .isEqualTo("INITIAL_BALANCE");
  }

  static Map<String, Object> mv(
      String sku,
      String lot,
      String from,
      String to,
      int qty,
      String quality,
      String allocationId) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("sku", sku);
    m.put("lot", lot);
    m.put("fromLocationCode", from);
    m.put("toLocationCode", to);
    m.put("qty", qty);
    if (quality != null) {
      m.put("qualityState", quality);
    }
    if (allocationId != null) {
      m.put("allocationId", allocationId);
    }
    return m;
  }

  Res move(String kind, Map<String, Object> line) {
    return post(
        SERVICE,
        "/internal/move-postings",
        key(),
        Map.of(
            "cdcId",
            CENTER,
            "moveKind",
            kind,
            "sourceRef",
            "MOV-" + UUID.randomUUID(),
            "lines",
            List.of(line)));
  }

  /**
   * 내부 이동(0.3.0): 예약 안 된 현물만 옮기기(부족하면 409) → 할당 예약을 랙에서 피킹 선반으로 함께 옮기기(가용 그대로, 할당 위치가 선반으로) → 선반에서
   * 출발 전기 → 격리 현물 이동, 격리 + 할당은 400, 같은 위치 400 → 원결과 MOVE.
   */
  /** 같은 LOT 이 랙(01)·선반(61)에 있으면 선반부터(0.5.0). 위치 서비스가 안 되면 위치 코드 순(랙부터)으로 그래도 잡는다. */
  @Test
  void sameLotAllocatesPickFaceFirstAndFallsBackWithoutLocation() throws Exception {
    String sku = "E-" + String.format("%08d", ThreadLocalRandom.current().nextInt(1, 99_999_999));
    productReady(sku);
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
    String rack = "CDC01-C-5-01-05-1-1";
    String shelf = "CDC01-C-5-61-05-1-1";
    assertThat(
            post(
                    SERVICE,
                    "/internal/initial-balances",
                    key(),
                    Map.of(
                        "cdcId",
                        CENTER,
                        "approvalRef",
                        "APV-INIT-P",
                        "lines",
                        List.of(
                            ib(sku, "P1", rack, 100, today.plusDays(30), "VERIFIED", "RELEASED"),
                            ib(sku, "P1", shelf, 20, today.plusDays(30), "VERIFIED", "RELEASED"))))
                .status())
        .isEqualTo(200);
    post(
        OPERATOR,
        "/cdcs/" + CENTER + "/sku-handlings",
        key(),
        Map.of("sku", sku, "enabled", true, "reason", "개시"));
    post(
        SERVICE,
        "/internal/safety-stocks",
        key(),
        Map.of(
            "cdcId",
            CENTER,
            "approvalRef",
            "APV-S-P",
            "lines",
            List.of(Map.of("sku", sku, "safetyQty", 0))));

    location.stubFor(
        WireMock.post(WireMock.urlPathEqualTo("/api/v1/internal/location-lookups"))
            .willReturn(
                WireMock.okJson(
                    "{\"items\":[{\"locationCode\":\""
                        + rack
                        + "\",\"locationType\":\"RESERVE\",\"state\":\"ACTIVE\",\"temperatureZone\":\"AMBIENT\"},"
                        + "{\"locationCode\":\""
                        + shelf
                        + "\",\"locationType\":\"PICK_FACE\",\"state\":\"ACTIVE\",\"temperatureZone\":\"AMBIENT\"}],"
                        + "\"missing\":[]}")));
    JsonNode first = allocate(sku, 30);
    JsonNode lots = first.path("lines").get(0).path("lots");
    assertThat(lots.size()).as(first.toString()).isEqualTo(2);
    assertThat(lots.get(0).path("locationCode").asString()).isEqualTo(shelf);
    assertThat(lots.get(0).path("qty").asInt()).isEqualTo(20);
    assertThat(lots.get(1).path("locationCode").asString()).isEqualTo(rack);
    assertThat(lots.get(1).path("qty").asInt()).isEqualTo(10);
    location.verify(
        WireMock.postRequestedFor(WireMock.urlPathEqualTo("/api/v1/internal/location-lookups"))
            .withHeader("X-Source-System", WireMock.equalTo("cdc-inventory-service"))
            .withHeader("Idempotency-Key", WireMock.matching("cdc-inventory-location-lookup-.+"))
            .withRequestBody(WireMock.matchingJsonPath("$.centerId", WireMock.equalTo(CENTER))));

    // 위치 서비스 장애 — 할당은 막지 않고 위치 코드 순(랙 01 이 선반 61 보다 앞)
    location.resetAll();
    location.stubFor(
        WireMock.post(WireMock.urlPathEqualTo("/api/v1/internal/location-lookups"))
            .willReturn(WireMock.serverError()));
    JsonNode second = allocate(sku, 5);
    assertThat(second.path("state").asString()).isEqualTo("ALLOCATED");
    assertThat(second.path("lines").get(0).path("lots").get(0).path("locationCode").asString())
        .isEqualTo(rack);
    location.resetAll();
  }

  /**
   * 피킹 부족 재할당(0.7.0, CDC-F-018): 부족 해제(위치·재계수 대기) → 운영자 목록 → 추가 할당은 대기 칸을 뺀 다른 칸 → 다른 TRF 새 할당도 대기
   * 칸을 뺌 → 운영자가 풀면 다시 후보.
   */
  @Test
  void pickShortageRecountHoldAndAddition() throws Exception {
    String sku = "E-" + String.format("%08d", ThreadLocalRandom.current().nextInt(1, 99_999_999));
    productReady(sku);
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
    String a = "CDC01-C-5-01-07-1-1";
    String b = "CDC01-C-5-01-08-1-1";
    assertThat(
            post(
                    SERVICE,
                    "/internal/initial-balances",
                    key(),
                    Map.of(
                        "cdcId",
                        CENTER,
                        "approvalRef",
                        "APV-INIT-H",
                        "lines",
                        List.of(
                            ib(sku, "H1", a, 10, today.plusDays(30), "VERIFIED", "RELEASED"),
                            ib(sku, "H1", b, 50, today.plusDays(30), "VERIFIED", "RELEASED"))))
                .status())
        .isEqualTo(200);
    post(
        OPERATOR,
        "/cdcs/" + CENTER + "/sku-handlings",
        key(),
        Map.of("sku", sku, "enabled", true, "reason", "개시"));
    post(
        SERVICE,
        "/internal/safety-stocks",
        key(),
        Map.of(
            "cdcId",
            CENTER,
            "approvalRef",
            "APV-S-H",
            "lines",
            List.of(Map.of("sku", sku, "safetyQty", 0))));
    JsonNode first = allocate(sku, 10); // 위치 코드 순 — 칸 a 10
    String alc = first.path("allocationId").asString();
    assertThat(first.path("lines").get(0).path("lots").get(0).path("locationCode").asString())
        .isEqualTo(a);

    // 재계수 대기는 PICK_SHORTAGE·LOT·위치가 있어야
    Res bad =
        post(
            SERVICE,
            "/internal/allocations/" + alc + "/releases",
            key(),
            Map.of(
                "reason",
                "PICK_SHORTAGE",
                "recountHold",
                true,
                "lines",
                List.of(Map.of("sku", sku, "qty", 4))));
    assertThat(bad.status()).isEqualTo(400);
    Res rel =
        post(
            SERVICE,
            "/internal/allocations/" + alc + "/releases",
            key(),
            Map.of(
                "reason",
                "PICK_SHORTAGE",
                "sourceRef",
                "TASK-1",
                "recountHold",
                true,
                "lines",
                List.of(Map.of("sku", sku, "lot", "H1", "locationCode", a, "qty", 4))));
    assertThat(rel.status()).as(rel.body().toString()).isEqualTo(200);
    JsonNode held = get(OPERATOR, "/cdcs/" + CENTER + "/recount-holds?state=HELD").body();
    JsonNode mine = null;
    for (JsonNode h : held.path("items")) {
      if (sku.equals(h.path("sku").asString())) {
        mine = h;
      }
    }
    assertThat(mine).as(held.toString()).isNotNull();
    assertThat(mine.path("locationCode").asString()).isEqualTo(a);
    assertThat(mine.path("shortQty").asInt()).isEqualTo(4);

    // 추가 할당 4 — 대기 칸 a(남은 현물 4)를 빼고 b 에서
    String addKey = key();
    Map<String, Object> add =
        Map.of(
            "reason",
            "PICK_SHORTAGE_REALLOCATION",
            "sourceRef",
            "TASK-1",
            "lines",
            List.of(Map.of("sku", sku, "qty", 4)));
    Res added = post(SERVICE, "/internal/allocations/" + alc + "/additions", addKey, add);
    assertThat(added.status()).as(added.body().toString()).isEqualTo(200);
    assertThat(added.body().path("addedQty").asInt()).isEqualTo(4);
    JsonNode addedLot = added.body().path("lines").get(0).path("lots").get(0);
    assertThat(addedLot.path("locationCode").asString()).isEqualTo(b);
    assertThat(post(SERVICE, "/internal/allocations/" + alc + "/additions", addKey, add).status())
        .isEqualTo(200); // 같은 키 재생
    JsonNode now = get(SERVICE, "/internal/allocations/" + alc).body();
    int heldTotal = 0;
    for (JsonNode l : now.path("lines").get(0).path("lots")) {
      heldTotal += l.path("qty").asInt();
    }
    assertThat(heldTotal).as(now.toString()).isEqualTo(14); // a 처음 10(해제 4 포함 이력) + b 4

    // 다른 TRF 새 할당도 대기 칸 a 를 뺀다
    JsonNode other = allocate(sku, 3);
    assertThat(other.path("lines").get(0).path("lots").get(0).path("locationCode").asString())
        .isEqualTo(b);

    // 운영자가 풀면 a 가 다시 후보(위치 코드 순이라 a 먼저)
    Res free =
        post(
            OPERATOR,
            "/cdcs/" + CENTER + "/recount-holds/" + mine.path("holdId").asString() + "/releases",
            key(),
            Map.of("reason", "다시 세어 보니 맞음"));
    assertThat(free.status()).as(free.body().toString()).isEqualTo(200);
    assertThat(free.body().path("state").asString()).isEqualTo("RELEASED");
    assertThat(free.body().path("releasedBy").asString()).isEqualTo("op-1");
    JsonNode again = allocate(sku, 2);
    assertThat(again.path("lines").get(0).path("lots").get(0).path("locationCode").asString())
        .isEqualTo(a);
  }

  /**
   * 실사(0.8.0, CDC-F-029·030 — MFC 실사와 같게): 칸 K1 실사 시작(새 할당에서 빠짐·계수표에 장부 없음) → 겹침 409·출고대기 400 → 첫
   * 계수 8 (장부 10) RECOUNT_REQUIRED → 같은 사람 재계수 409 → 다른 사람 7 PENDING_APPROVAL → 시작자 결정 422 → 손실 3×5만
   * 원 ≥ 10만 원이라 본사 번호 없으면 422 APPROVAL_REQUIRED → 번호로 APPROVE(approval-service 한 번 사용) → CNT
   * −3·사건·K1 다시 후보. 일치 실사는 바로 MATCHED.
   */
  @Test
  void countSessionBlindRecountApprove() throws Exception {
    String sku = "E-" + String.format("%08d", ThreadLocalRandom.current().nextInt(1, 99_999_999));
    productReady(sku);
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
    String k1 = "CDC01-C-5-01-21-1-1";
    String k2 = "CDC01-C-5-01-22-1-1";
    String cnt1 = TestJwt.operator("cnt-1", List.of("CDC_WORKER"), List.of(CENTER));
    String cnt2 = TestJwt.operator("cnt-2", List.of("CDC_WORKER"), List.of(CENTER));
    String sup = TestJwt.operator("sup-9", List.of("CDC_MANAGER"), List.of(CENTER));
    assertThat(
            post(
                    SERVICE,
                    "/internal/initial-balances",
                    key(),
                    Map.of(
                        "cdcId",
                        CENTER,
                        "approvalRef",
                        "APV-INIT-C",
                        "lines",
                        List.of(
                            ib(sku, "C1", k1, 10, today.plusDays(30), "VERIFIED", "RELEASED"),
                            ib(sku, "C1", k2, 5, today.plusDays(30), "VERIFIED", "RELEASED"))))
                .status())
        .isEqualTo(200);
    post(
        OPERATOR,
        "/cdcs/" + CENTER + "/sku-handlings",
        key(),
        Map.of("sku", sku, "enabled", true, "reason", "개시"));
    post(
        SERVICE,
        "/internal/safety-stocks",
        key(),
        Map.of(
            "cdcId",
            CENTER,
            "approvalRef",
            "APV-S-C",
            "lines",
            List.of(Map.of("sku", sku, "safetyQty", 0))));
    String base = "/cdcs/" + CENTER + "/count-sessions";

    // 시작 — 계수표엔 장부 수량이 없다
    Res st =
        post(OPERATOR, base, key(), Map.of("locationCodes", List.of(k1), "reason", "부족 칸 재계수"));
    assertThat(st.status()).as(st.body().toString()).isEqualTo(201);
    String sid = st.body().path("sessionId").asString();
    // CS-1(0.12.0) 실사 목록 — 최신 순, state 거르기, 결정 전 lines 없음
    JsonNode list = get(OPERATOR, base + "?state=COUNTING&size=200").body();
    assertThat(list.path("items").findValuesAsString("sessionId")).contains(sid);
    assertThat(list.path("items").findValuesAsString("state")).containsOnly("COUNTING");
    assertThat(st.body().path("state").asString()).isEqualTo("COUNTING");
    assertThat(st.body().path("sheet").get(0).path("lot").asString()).isEqualTo("C1");
    assertThat(st.body().toString()).doesNotContain("bookQty");
    assertThat(
            post(OPERATOR, base, key(), Map.of("locationCodes", List.of(k1)))
                .body()
                .path("code")
                .asString())
        .isEqualTo("COUNT_IN_PROGRESS");
    assertThat(
            post(OPERATOR, base, key(), Map.of("locationCodes", List.of("CDC01-C-5-91-01-0-1")))
                .status())
        .isEqualTo(400);
    // 실사 중인 K1 은 새 할당 후보가 아니다 — K2 5 만
    JsonNode a = allocate(sku, 12);
    JsonNode lots = a.path("lines").get(0).path("lots");
    assertThat(lots.size()).as(a.toString()).isEqualTo(1);
    assertThat(lots.get(0).path("locationCode").asString()).isEqualTo(k2);

    // 첫 계수 8 → 재계수 필요, 같은 사람 409, 다른 사람 7 → 승인 대기
    List<Map<String, Object>> eight =
        List.of(Map.of("locationCode", k1, "sku", sku, "lot", "C1", "qty", 8));
    Res c1 = post(cnt1, base + "/" + sid + "/counts", key(), Map.of("lines", eight));
    assertThat(c1.status()).as(c1.body().toString()).isEqualTo(200);
    assertThat(c1.body().path("state").asString()).isEqualTo("RECOUNT_REQUIRED");
    Res unknownLot =
        post(
            cnt2,
            base + "/" + sid + "/recounts",
            key(),
            Map.of(
                "lines", List.of(Map.of("locationCode", k1, "sku", sku, "lot", "NOPE", "qty", 1))));
    assertThat(unknownLot.status()).isEqualTo(400);
    assertThat(
            post(cnt1, base + "/" + sid + "/recounts", key(), Map.of("lines", eight))
                .body()
                .path("code")
                .asString())
        .isEqualTo("SAME_COUNTER");
    Res c2 =
        post(
            cnt2,
            base + "/" + sid + "/recounts",
            key(),
            Map.of(
                "lines", List.of(Map.of("locationCode", k1, "sku", sku, "lot", "C1", "qty", 7))));
    assertThat(c2.body().path("state").asString())
        .as(c2.body().toString())
        .isEqualTo("PENDING_APPROVAL");

    // 결정 — 시작자 422, 본사 기준 이상이면 번호 필요
    Map<String, Object> approve = Map.of("decision", "APPROVE", "reason", "재계수 7 확정");
    assertThat(
            post(OPERATOR, base + "/" + sid + "/decisions", key(), approve)
                .body()
                .path("code")
                .asString())
        .isEqualTo("SELF_APPROVAL");
    pricing.stubFor(
        WireMock.get(WireMock.urlPathEqualTo("/api/v1/internal/prices"))
            .willReturn(
                WireMock.okJson(
                    "{\"items\":[{\"sku\":\""
                        + sku
                        + "\",\"basePrice\":50000,\"priceVersion\":1}]}")));
    Res need = post(sup, base + "/" + sid + "/decisions", key(), approve);
    assertThat(need.status()).as(need.body().toString()).isEqualTo(422);
    assertThat(need.body().toString())
        .contains("APPROVAL_REQUIRED")
        .contains("CDC_STOCK_ADJUSTMENT");
    approval.stubFor(
        WireMock.post(WireMock.urlPathEqualTo("/api/v1/internal/approval-ref-uses"))
            .willReturn(
                WireMock.okJson(
                    "{\"usable\":true,\"lines\":[{\"lineKey\":\""
                        + sku
                        + "\",\"requestedQty\":3}]}")));
    Map<String, Object> withRef = new LinkedHashMap<>(approve);
    withRef.put("approvalRef", "APV-9");
    Res ok = post(sup, base + "/" + sid + "/decisions", key(), withRef);
    assertThat(ok.status()).as(ok.body().toString()).isEqualTo(200);
    assertThat(ok.body().path("state").asString()).isEqualTo("APPROVED");
    assertThat(ok.body().path("approvalRef").asString()).isEqualTo("APV-9");
    JsonNode line = ok.body().path("lines").get(0);
    assertThat(line.path("bookQty").asInt()).isEqualTo(10);
    assertThat(line.path("firstQty").asInt()).isEqualTo(8);
    assertThat(line.path("finalQty").asInt()).isEqualTo(7);
    assertThat(line.path("deltaQty").asInt()).isEqualTo(-3);
    approval.verify(
        WireMock.postRequestedFor(WireMock.urlPathEqualTo("/api/v1/internal/approval-ref-uses"))
            .withRequestBody(
                WireMock.matchingJsonPath("$.type", WireMock.equalTo("CDC_STOCK_ADJUSTMENT")))
            .withRequestBody(
                WireMock.matchingJsonPath(
                    "$.targetRef", WireMock.equalTo(CENTER + ":COUNT_ADJUSTMENT:" + sid))));
    assertThat(outbox("InventoryAdjustmentPosted", sid)).isEqualTo(1);
    Integer onHand =
        jdbc.queryForObject(
            "SELECT on_hand FROM stock_balance WHERE cdc_id = ? AND sku = ? AND location_code = ?",
            Integer.class,
            CENTER,
            sku,
            k1);
    assertThat(onHand).isEqualTo(7);

    // 실사가 끝난 K1 은 다시 후보(K2 는 다 잡힘)
    JsonNode b = allocate(sku, 2);
    assertThat(b.path("lines").get(0).path("lots").get(0).path("locationCode").asString())
        .isEqualTo(k1);

    // 일치 실사는 바로 MATCHED(조정 없음)
    String sid2 =
        post(OPERATOR, base, key(), Map.of("locationCodes", List.of(k2)))
            .body()
            .path("sessionId")
            .asString();
    Res m =
        post(
            cnt1,
            base + "/" + sid2 + "/counts",
            key(),
            Map.of(
                "lines", List.of(Map.of("locationCode", k2, "sku", sku, "lot", "C1", "qty", 5))));
    assertThat(m.body().path("state").asString()).as(m.body().toString()).isEqualTo("MATCHED");
    assertThat(m.body().path("lines").get(0).path("deltaQty").asInt()).isZero();
  }

  /**
   * 실사 부족(0.10.0, 설계 §4): 칸 10 에 할당 A1 5(먼저)·A2 3(나중) → 실사 최종 4 승인 → 예약 8 > 현물 4 라 4 를 늦은 A2 3 부터,
   * 남은 1 은 A1 → 할당마다 AllocationShortfall(COUNT_LOSS). 손실 6×1천 원·6개는 본사 기준 아래라 센터 관리자 결정만.
   */
  @Test
  void countLossReleasesLatestAllocationsFirst() throws Exception {
    String sku = "E-" + String.format("%08d", ThreadLocalRandom.current().nextInt(1, 99_999_999));
    productReady(sku);
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
    String k3 = "CDC01-C-5-01-31-1-1";
    String cnt1 = TestJwt.operator("cnt-1", List.of("CDC_WORKER"), List.of(CENTER));
    String cnt2 = TestJwt.operator("cnt-2", List.of("CDC_WORKER"), List.of(CENTER));
    String sup = TestJwt.operator("sup-9", List.of("CDC_MANAGER"), List.of(CENTER));
    post(
        SERVICE,
        "/internal/initial-balances",
        key(),
        Map.of(
            "cdcId",
            CENTER,
            "approvalRef",
            "APV-INIT-L",
            "lines",
            List.of(ib(sku, "L1", k3, 10, today.plusDays(30), "VERIFIED", "RELEASED"))));
    post(
        OPERATOR,
        "/cdcs/" + CENTER + "/sku-handlings",
        key(),
        Map.of("sku", sku, "enabled", true, "reason", "개시"));
    post(
        SERVICE,
        "/internal/safety-stocks",
        key(),
        Map.of(
            "cdcId",
            CENTER,
            "approvalRef",
            "APV-S-L",
            "lines",
            List.of(Map.of("sku", sku, "safetyQty", 0))));
    String a1 = allocate(sku, 5).path("allocationId").asString();
    String a2 = allocate(sku, 3).path("allocationId").asString();
    pricing.stubFor(
        WireMock.get(WireMock.urlPathEqualTo("/api/v1/internal/prices"))
            .withQueryParam("skus", WireMock.equalTo(sku))
            .willReturn(
                WireMock.okJson(
                    "{\"items\":[{\"sku\":\""
                        + sku
                        + "\",\"basePrice\":1000,\"priceVersion\":1}]}")));
    String base = "/cdcs/" + CENTER + "/count-sessions";
    String sid =
        post(OPERATOR, base, key(), Map.of("locationCodes", List.of(k3)))
            .body()
            .path("sessionId")
            .asString();
    List<Map<String, Object>> four =
        List.of(Map.of("locationCode", k3, "sku", sku, "lot", "L1", "qty", 4));
    post(cnt1, base + "/" + sid + "/counts", key(), Map.of("lines", four));
    assertThat(
            post(cnt2, base + "/" + sid + "/recounts", key(), Map.of("lines", four))
                .body()
                .path("state")
                .asString())
        .isEqualTo("PENDING_APPROVAL");
    Res ok =
        post(
            sup,
            base + "/" + sid + "/decisions",
            key(),
            Map.of("decision", "APPROVE", "reason", "4 확정"));
    assertThat(ok.status()).as(ok.body().toString()).isEqualTo(200);
    assertThat(ok.body().path("state").asString()).isEqualTo("APPROVED");
    Map<String, Object> bal =
        jdbc.queryForMap(
            "SELECT on_hand, reserved FROM stock_balance WHERE cdc_id = ? AND sku = ? AND location_code = ?",
            CENTER,
            sku,
            k3);
    assertThat(bal.get("on_hand")).isEqualTo(4);
    assertThat(bal.get("reserved")).isEqualTo(4);
    assertThat(
            jdbc.queryForObject(
                "SELECT released_qty FROM allocation_lot WHERE allocation_id = ?",
                Integer.class,
                a2))
        .isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "SELECT released_qty FROM allocation_lot WHERE allocation_id = ?",
                Integer.class,
                a1))
        .isEqualTo(1);
    String ev2 =
        jdbc.queryForObject(
            "SELECT payload::text FROM outbox_event WHERE type = 'AllocationShortfall' AND aggregateid = ?",
            String.class,
            a2);
    assertThat(ev2).contains("\"qty\": 3").contains("COUNT_LOSS").contains(sid);
    assertThat(outbox("AllocationShortfall", a1)).isEqualTo(1);
  }

  /**
   * MFC 수령·운송 중 재고 대사(0.11.0, CDC-F-024): TOUT 보다 먼저 온 MfcReceiptPosted(4)는 기다렸다가 TOUT 때 처리 →
   * Transit 10 중 4 → 복구용 API 로 6(정상 5·미판정 1 — 미판정도 받은 수량) → t = 0 RECONCILED·TransitReconciled. 같은
   * receiptId 같은 본문은 그대로, 다른 본문 409, 출발 줄에 없는 LOT 은 그 줄만 보류(차감 0).
   */
  @Test
  void mfcReceiptReconcilesTransit() throws Exception {
    String sku = "E-" + String.format("%08d", ThreadLocalRandom.current().nextInt(1, 99_999_999));
    productReady(sku);
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
    String loc = "CDC01-C-5-01-41-1-1";
    post(
        SERVICE,
        "/internal/initial-balances",
        key(),
        Map.of(
            "cdcId",
            CENTER,
            "approvalRef",
            "APV-INIT-R",
            "lines",
            List.of(ib(sku, "R1", loc, 10, today.plusDays(30), "VERIFIED", "RELEASED"))));
    post(
        OPERATOR,
        "/cdcs/" + CENTER + "/sku-handlings",
        key(),
        Map.of("sku", sku, "enabled", true, "reason", "개시"));
    post(
        SERVICE,
        "/internal/safety-stocks",
        key(),
        Map.of(
            "cdcId",
            CENTER,
            "approvalRef",
            "APV-S-R",
            "lines",
            List.of(Map.of("sku", sku, "safetyQty", 0))));
    JsonNode a = allocate(sku, 10);
    String trf = a.path("trfId").asString();
    String alc = a.path("allocationId").asString();
    String depId = "DEP-" + UUID.randomUUID();

    // 1) TOUT 보다 먼저 온 수령 사건(4 = 정상 3·격리 1) — 기다린다
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("receiptId", "RCP-1-" + trf);
    p.put("postingId", "PST-1");
    p.put("commitId", "MFC-CMT-1");
    p.put("trfId", trf);
    p.put("departureId", depId);
    p.put("mfcId", "MFC-BJG-01");
    p.put(
        "lines",
        List.of(
            Map.of("sku", sku, "lot", "R1", "normalQty", 3, "quarantinedQty", 1, "pendingQty", 0)));
    p.put("receivedQty", 4);
    var env =
        codec.create(
            "mfc-inventory-service",
            "MfcReceiptPosted",
            1,
            "Receipt",
            "RCP-1-" + trf,
            1,
            null,
            com.pmart.common.messaging.EventEnvelope.EventSource.REAL,
            p);
    txTemplate.executeWithoutResult(s -> receipts.fromEvent(env));
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM transit_receipt WHERE receipt_id = ?",
                String.class,
                "RCP-1-" + trf))
        .isEqualTo("PENDING_DEPARTURE");

    // 2) TOUT 10 → 기다리던 4 반영
    Map<String, Object> dep = new LinkedHashMap<>();
    dep.put("cdcId", CENTER);
    dep.put("departureId", depId);
    dep.put("departureSource", "SIMULATED");
    dep.put("toMfcId", "MFC-BJG-01");
    dep.put("trfId", trf);
    dep.put("trfVersion", 1);
    dep.put("allocationId", alc);
    dep.put("departedAt", Instant.now().toString());
    dep.put(
        "lines",
        List.of(Map.of("departureLineId", trf + "-1", "sku", sku, "lot", "R1", "qty", 10)));
    Res d = post(SERVICE, "/internal/departure-postings", key(), dep);
    assertThat(d.status()).as(d.body().toString()).isEqualTo(200);
    Map<String, Object> line =
        jdbc.queryForMap(
            "SELECT normal_qty, quarantined_qty, pending_qty FROM transit_line WHERE trf_id = ?",
            trf);
    assertThat(line.get("normal_qty")).isEqualTo(3);
    assertThat(line.get("quarantined_qty")).isEqualTo(1);

    // 3) 복구용 API — 6(정상 5·미판정 1) → t = 0
    Map<String, Object> ack = new LinkedHashMap<>();
    ack.put("receiptId", "RCP-2-" + trf);
    ack.put("trfId", trf);
    ack.put("departureId", depId);
    ack.put("mfcId", "MFC-BJG-01");
    ack.put("mfcCommitId", "MFC-CMT-2");
    ack.put(
        "lines",
        List.of(
            Map.of("sku", sku, "lot", "R1", "normalQty", 5, "quarantinedQty", 0, "pendingQty", 1)));
    Res r2 = post(SERVICE, "/internal/receipt-acks", key(), ack);
    assertThat(r2.status()).as(r2.body().toString()).isEqualTo(200);
    assertThat(r2.body().path("appliedQty").asInt()).isEqualTo(6);
    assertThat(r2.body().path("transitState").asString()).isEqualTo("TRANSIT_RECONCILED");
    assertThat(r2.body().path("unreconciledQty").asInt()).isZero();
    assertThat(
            post(SERVICE, "/internal/receipt-acks", key(), ack).body().path("appliedQty").asInt())
        .isEqualTo(6); // 같은 receiptId 같은 본문 — 그대로(추가 차감 0)
    Map<String, Object> changed = new LinkedHashMap<>(ack);
    changed.put(
        "lines",
        List.of(
            Map.of("sku", sku, "lot", "R1", "normalQty", 6, "quarantinedQty", 0, "pendingQty", 0)));
    assertThat(
            post(SERVICE, "/internal/receipt-acks", key(), changed).body().path("code").asString())
        .isEqualTo("RECEIPT_CONFLICT");
    String ev =
        jdbc.queryForObject(
            "SELECT payload::text FROM outbox_event WHERE type = 'TransitReconciled' AND aggregateid = ?"
                + " AND payload::text LIKE '%RCP-2-%'",
            String.class, trf);
    assertThat(ev).contains("\"state\": \"RECONCILED\"").contains("\"receivedQty\": 10");

    // 4) 출발 줄에 없는 LOT — 그 줄만 보류
    Map<String, Object> odd = new LinkedHashMap<>(ack);
    odd.put("receiptId", "RCP-3-" + trf);
    odd.put(
        "lines",
        List.of(
            Map.of("sku", sku, "lot", "ZZ", "normalQty", 1, "quarantinedQty", 0, "pendingQty", 0)));
    Res r3 = post(SERVICE, "/internal/receipt-acks", key(), odd);
    assertThat(r3.status()).as(r3.body().toString()).isEqualTo(200);
    assertThat(r3.body().path("appliedQty").asInt()).isZero();
    assertThat(r3.body().path("conflicts").get(0).path("lot").asString()).isEqualTo("ZZ");
    // 5) 수령 정정(0.13.0) — API: RCP-2 정정 뒤 누계 정상 3·미판정 1(전 5·1) → δr −2 → 미정산 2 다시 엶
    Map<String, Object> fix = new LinkedHashMap<>(ack);
    fix.put("correctionId", "COR-1-" + trf);
    fix.put(
        "lines",
        List.of(
            Map.of("sku", sku, "lot", "R1", "normalQty", 3, "quarantinedQty", 0, "pendingQty", 1)));
    Res f1 = post(SERVICE, "/internal/receipt-acks", key(), fix);
    assertThat(f1.status()).as(f1.body().toString()).isEqualTo(200);
    assertThat(f1.body().path("appliedQty").asInt()).isEqualTo(-2);
    assertThat(f1.body().path("transitState").asString()).isEqualTo("RECEIPT_POSTED_AWAITING_ACK");
    assertThat(f1.body().path("unreconciledQty").asInt()).isEqualTo(2);
    assertThat(
            post(SERVICE, "/internal/receipt-acks", key(), fix).body().path("appliedQty").asInt())
        .isEqualTo(-2); // 같은 정정 — 그대로
    // 사건: RCP-1 정상 +2 → 다시 t = 0
    Map<String, Object> cp = new LinkedHashMap<>();
    cp.put("receiptId", "RCP-1-" + trf);
    cp.put("correctionId", "COR-2-" + trf);
    cp.put("deltaQty", 2);
    cp.put("reason", "재계수");
    cp.put("lines", List.of(Map.of("sku", sku, "lot", "R1", "deltaNormalQty", 2)));
    var cenv =
        codec.create(
            "mfc-inventory-service",
            "ReceiptCorrectionPosted",
            1,
            "Receipt",
            "RCP-1-" + trf,
            2,
            null,
            com.pmart.common.messaging.EventEnvelope.EventSource.REAL,
            cp);
    txTemplate.executeWithoutResult(s -> receipts.correctionFromEvent(cenv));
    assertThat(jdbc.queryForObject("SELECT state FROM transit WHERE trf_id = ?", String.class, trf))
        .isEqualTo("TRANSIT_RECONCILED");
    // 누계를 0 아래로 만드는 정정은 그 줄만 보류
    Map<String, Object> bad = new LinkedHashMap<>(fix);
    bad.put("correctionId", "COR-3-" + trf);
    bad.put("receiptId", "RCP-3-" + trf);
    Res f3 = post(SERVICE, "/internal/receipt-acks", key(), bad);
    assertThat(f3.status()).as(f3.body().toString()).isEqualTo(200);
    assertThat(f3.body().path("conflicts").size()).isEqualTo(1);

    // 출발 전이면 API 는 404
    Map<String, Object> early = new LinkedHashMap<>(ack);
    early.put("receiptId", "RCP-4");
    early.put("trfId", "TRF-NONE");
    assertThat(post(SERVICE, "/internal/receipt-acks", key(), early).body().path("code").asString())
        .isEqualTo("DEPARTURE_NOT_POSTED");
  }

  /** 운송 손실(0.14.0, CDC-F-027): MFC 입고 종결 전 409, 미정산 초과 409, 본사 기준 아래면 센터 결정만 → t = 0. */
  @Test
  void transitLossNeedsMfcClosure() throws Exception {
    String sku = "E-" + String.format("%08d", ThreadLocalRandom.current().nextInt(1, 99_999_999));
    productReady(sku);
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
    post(
        SERVICE,
        "/internal/initial-balances",
        key(),
        Map.of(
            "cdcId",
            CENTER,
            "approvalRef",
            "APV-INIT-L",
            "lines",
            List.of(
                ib(
                    sku,
                    "L1",
                    "CDC01-C-5-01-42-1-1",
                    10,
                    today.plusDays(30),
                    "VERIFIED",
                    "RELEASED"))));
    post(
        OPERATOR,
        "/cdcs/" + CENTER + "/sku-handlings",
        key(),
        Map.of("sku", sku, "enabled", true, "reason", "개시"));
    post(
        SERVICE,
        "/internal/safety-stocks",
        key(),
        Map.of(
            "cdcId",
            CENTER,
            "approvalRef",
            "APV-S-L",
            "lines",
            List.of(Map.of("sku", sku, "safetyQty", 0))));
    JsonNode a = allocate(sku, 10);
    String trf = a.path("trfId").asString();
    String depId = "DEP-" + UUID.randomUUID();
    Map<String, Object> dep = new LinkedHashMap<>();
    dep.put("cdcId", CENTER);
    dep.put("departureId", depId);
    dep.put("departureSource", "SIMULATED");
    dep.put("toMfcId", "MFC-BJG-01");
    dep.put("trfId", trf);
    dep.put("trfVersion", 1);
    dep.put("allocationId", a.path("allocationId").asString());
    dep.put("departedAt", Instant.now().toString());
    dep.put(
        "lines",
        List.of(Map.of("departureLineId", trf + "-1", "sku", sku, "lot", "L1", "qty", 10)));
    assertThat(post(SERVICE, "/internal/departure-postings", key(), dep).status()).isEqualTo(200);
    Map<String, Object> ack = new LinkedHashMap<>();
    ack.put("receiptId", "RCP-L-" + trf);
    ack.put("trfId", trf);
    ack.put("departureId", depId);
    ack.put("mfcId", "MFC-BJG-01");
    ack.put("mfcCommitId", "MFC-CMT-L");
    ack.put(
        "lines",
        List.of(
            Map.of("sku", sku, "lot", "L1", "normalQty", 6, "quarantinedQty", 0, "pendingQty", 0)));
    assertThat(
            post(SERVICE, "/internal/receipt-acks", key(), ack)
                .body()
                .path("unreconciledQty")
                .asInt())
        .isEqualTo(4);

    String base = "/cdcs/" + CENTER + "/transit-losses";
    Map<String, Object> loss = new LinkedHashMap<>();
    loss.put("trfId", trf);
    loss.put("departureId", depId);
    loss.put("reason", "운송 중 파손 확인");
    loss.put(
        "lines", List.of(Map.of("sku", sku, "lot", "L1", "lossQty", 4, "lossReason", "DAMAGE")));
    // 1) MFC 입고 종결 전 — 409
    Res open = post(OPERATOR, base, key(), loss);
    assertThat(open.status()).as(open.body().toString()).isEqualTo(409);
    assertThat(open.body().path("code").asString()).isEqualTo("MFC_RECEIPT_OPEN");
    // 2) 종결 기록(같은 본문 다시 = 그대로)
    Map<String, Object> close =
        Map.of(
            "trfId",
            trf,
            "departureId",
            depId,
            "mfcId",
            "MFC-BJG-01",
            "closedAt",
            Instant.now().toString());
    Res c1 = post(SERVICE, "/internal/transit-closures", key(), close);
    assertThat(c1.status()).as(c1.body().toString()).isEqualTo(200);
    assertThat(c1.body().path("unreconciledQty").asInt()).isEqualTo(4);
    assertThat(post(SERVICE, "/internal/transit-closures", key(), close).status()).isEqualTo(200);
    // 3) 미정산 초과 — 409
    Map<String, Object> over = new LinkedHashMap<>(loss);
    over.put("lines", List.of(Map.of("sku", sku, "lot", "L1", "lossQty", 5)));
    Res o = post(OPERATOR, base, key(), over);
    assertThat(o.status()).as(o.body().toString()).isEqualTo(409);
    assertThat(o.body().path("code").asString()).isEqualTo("LOSS_EXCEEDS_UNRECONCILED");
    // 4) 본사 기준 아래(4 × 1000원) — 센터 결정만 → t = 0
    pricing.stubFor(
        WireMock.get(WireMock.urlPathEqualTo("/api/v1/internal/prices"))
            .willReturn(
                WireMock.okJson(
                    "{\"items\":[{\"sku\":\""
                        + sku
                        + "\",\"basePrice\":1000,\"priceVersion\":1}]}")));
    String k = key();
    Res ok = post(OPERATOR, base, k, loss);
    assertThat(ok.status()).as(ok.body().toString()).isEqualTo(200);
    assertThat(ok.body().path("lossQty").asInt()).isEqualTo(4);
    assertThat(ok.body().path("unreconciledQty").asInt()).isZero();
    assertThat(ok.body().path("transitState").asString()).isEqualTo("TRANSIT_RECONCILED");
    Res again = post(OPERATOR, base, k, loss);
    assertThat(again.body().path("commitId").asString())
        .isEqualTo(ok.body().path("commitId").asString());
    assertThat(
            jdbc.queryForObject(
                "SELECT loss_qty FROM transit_line WHERE trf_id = ?", Integer.class, trf))
        .isEqualTo(4);
    // 5) 남은 미정산이 없으니 더 손실 불가
    Res none =
        post(
            OPERATOR,
            base,
            key(),
            Map.of(
                "trfId",
                trf,
                "departureId",
                depId,
                "reason",
                "또",
                "lines",
                List.of(Map.of("sku", sku, "lot", "L1", "lossQty", 1))));
    assertThat(none.body().path("code").asString()).isEqualTo("LOSS_EXCEEDS_UNRECONCILED");
  }

  JsonNode allocate(String sku, int qty) {
    Res r =
        post(
            SERVICE,
            "/internal/allocations",
            key(),
            Map.of(
                "cdcId",
                CENTER,
                "trfId",
                "TRF-" + UUID.randomUUID(),
                "trfVersion",
                1,
                "lines",
                List.of(Map.of("sku", sku, "requestedQty", qty))));
    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    return r.body();
  }

  @Test
  void replenishmentMoveCarriesReservationToShelf() throws Exception {
    String sku = "E-" + String.format("%08d", ThreadLocalRandom.current().nextInt(1, 99_999_999));
    productReady(sku);
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
    String rack = "CDC01-C-5-01-01-3-1";
    String shelf = "CDC01-C-5-61-01-1-1";
    Res init =
        post(
            SERVICE,
            "/internal/initial-balances",
            key(),
            Map.of(
                "cdcId",
                CENTER,
                "approvalRef",
                "APV-INIT-M",
                "lines",
                List.of(
                    ib(sku, "M1", rack, 100, today.plusDays(30), "VERIFIED", "RELEASED"),
                    ib(sku, "Q1", LOC2, 10, today.plusDays(30), "VERIFIED", "QUARANTINED"))));
    assertThat(init.status()).as(init.body().toString()).isEqualTo(200);
    post(
        OPERATOR,
        "/cdcs/" + CENTER + "/sku-handlings",
        key(),
        Map.of("sku", sku, "enabled", true, "reason", "개시"));
    post(
        SERVICE,
        "/internal/safety-stocks",
        key(),
        Map.of(
            "cdcId",
            CENTER,
            "approvalRef",
            "APV-S-M",
            "lines",
            List.of(Map.of("sku", sku, "safetyQty", 0))));
    String trf = "TRF-" + UUID.randomUUID();
    Res alloc =
        post(
            SERVICE,
            "/internal/allocations",
            key(),
            Map.of(
                "cdcId",
                CENTER,
                "trfId",
                trf,
                "trfVersion",
                1,
                "lines",
                List.of(Map.of("sku", sku, "requestedQty", 60))));
    String allocationId = alloc.body().path("allocationId").asString();
    assertThat(alloc.body().path("state").asString()).isEqualTo("ALLOCATED");

    // 1) 예약 안 된 현물은 40 — 50 은 거절, 30 은 옮김
    Res tooMuch = move("RELOCATION", mv(sku, "M1", rack, shelf, 50, null, null));
    assertThat(tooMuch.status()).isEqualTo(409);
    assertThat(tooMuch.body().path("code").asString()).isEqualTo("INSUFFICIENT_STOCK");
    assertThat(move("RELOCATION", mv(sku, "M1", rack, shelf, 30, null, null)).status())
        .isEqualTo(200);

    // 2) 보충 MOV — 할당 예약 60 을 선반으로. 예약보다 많으면 409
    assertThat(
            move("REPLENISHMENT_MOVE", mv(sku, "M1", rack, shelf, 61, null, allocationId)).status())
        .isEqualTo(409);
    Res rep = move("REPLENISHMENT_MOVE", mv(sku, "M1", rack, shelf, 60, null, allocationId));
    assertThat(rep.status()).as(rep.body().toString()).isEqualTo(200);
    JsonNode a = availability(sku).body();
    assertThat(a.path("onHand").asInt()).isEqualTo(110);
    assertThat(a.path("reserved").asInt()).isEqualTo(60);
    assertThat(a.path("available").asInt()).isEqualTo(40);
    JsonNode lots =
        get(SERVICE, "/internal/allocations/" + allocationId)
            .body()
            .path("lines")
            .get(0)
            .path("lots");
    assertThat(lots.size()).isEqualTo(1);
    assertThat(lots.get(0).path("locationCode").asString()).isEqualTo(shelf);
    assertThat(lots.get(0).path("qty").asInt()).isEqualTo(60);
    JsonNode commit =
        get(SERVICE, "/internal/commits/" + rep.body().path("resultId").asString()).body();
    assertThat(commit.path("postingKind").asString()).isEqualTo("MOVE");
    assertThat(commit.toString()).contains("\"bucket\":\"RESERVED\"");

    // 2-1) 스냅샷 — LOT 마다 위치별 현물·예약(선반에 옮긴 예약 60), 취급·안전량 표시
    JsonNode snap = get(SERVICE, "/internal/snapshots?cdcId=" + CENTER + "&sku=" + sku).body();
    JsonNode item = snap.path("items").get(0);
    assertThat(item.path("handlingActive").asBoolean()).isTrue();
    assertThat(item.path("safety").path("state").asString()).isEqualTo("VALUE");
    int shelfReserved = 0;
    int shelfOnHand = 0;
    for (JsonNode lot : item.path("lots")) {
      for (JsonNode loc : lot.path("locations")) {
        if (shelf.equals(loc.path("locationCode").asString())) {
          shelfReserved += loc.path("reserved").asInt();
          shelfOnHand += loc.path("onHand").asInt();
        }
      }
    }
    assertThat(shelfReserved).isEqualTo(60);
    assertThat(shelfOnHand).isEqualTo(90);
    assertThat(get(SERVICE, "/internal/snapshots?cdcId=" + CENTER + "&snapshotId=OLD").status())
        .isEqualTo(404);

    // 2-2) DC 백오피스 재고 현황(0.6.0, 2026-11-25) — 같은 내용, 자기 센터 운영자만, 기본은 위치 없이
    JsonNode staff = get(OPERATOR, "/cdcs/" + CENTER + "/stock-snapshot?sku=" + sku).body();
    assertThat(staff.path("items").get(0).path("onHand").asInt())
        .isEqualTo(item.path("onHand").asInt());
    assertThat(
            staff.path("items").get(0).path("lots").isMissingNode()
                || staff.path("items").get(0).path("lots").isEmpty()
                || staff.path("items").get(0).path("lots").get(0).path("locations").isMissingNode()
                || staff.path("items").get(0).path("lots").get(0).path("locations").isEmpty())
        .isTrue();
    assertThat(
            get(OPERATOR, "/cdcs/" + CENTER + "/stock-snapshot?sku=" + sku + "&withPlaces=true")
                .body()
                .toString())
        .contains(shelf);
    assertThat(
            get(
                    TestJwt.operator("op-2", List.of("CDC_SUPERVISOR"), List.of("CDC99")),
                    "/cdcs/" + CENTER + "/stock-snapshot")
                .status())
        .isEqualTo(403);
    // 2-3) 칸별 재고(0.9.0 M-6) — 선반 칸으로 거르면 그 칸 줄만, 현물·예약이 스냅샷과 같다
    JsonNode cells =
        get(
                OPERATOR,
                "/cdcs/" + CENTER + "/location-stocks?sku=" + sku + "&locationPrefix=" + shelf)
            .body();
    assertThat(cells.path("items").findValuesAsString("locationCode")).containsOnly(shelf);
    int cellOnHand = 0;
    int cellReserved = 0;
    for (JsonNode c : cells.path("items")) {
      cellOnHand += c.path("onHand").asInt();
      cellReserved += c.path("reserved").asInt();
    }
    assertThat(cellOnHand).isEqualTo(shelfOnHand);
    assertThat(cellReserved).isEqualTo(shelfReserved);
    assertThat(cells.path("truncated").asBoolean()).isFalse();

    // 3) 선반에서 출발 전기(위치를 비우면 할당이 잡은 위치 = 선반)
    Map<String, Object> dep = new LinkedHashMap<>();
    dep.put("cdcId", CENTER);
    dep.put("departureId", "DEP-" + UUID.randomUUID());
    dep.put("toMfcId", "MFC-BJG-01");
    dep.put("trfId", trf);
    dep.put("trfVersion", 1);
    dep.put("allocationId", allocationId);
    dep.put("departedAt", Instant.now().toString());
    dep.put(
        "lines",
        List.of(Map.of("departureLineId", trf + "-1", "sku", sku, "lot", "M1", "qty", 60)));
    Res d = post(SERVICE, "/internal/departure-postings", key(), dep);
    assertThat(d.status()).as(d.body().toString()).isEqualTo(200);
    JsonNode after = availability(sku).body();
    assertThat(after.path("onHand").asInt()).isEqualTo(50);
    assertThat(after.path("reserved").asInt()).isZero();
    assertThat(after.path("transit").asInt()).isEqualTo(60);

    // 4) 격리 현물 이동, 격리 + 할당 400, 같은 위치 400
    assertThat(
            move("RELOCATION", mv(sku, "Q1", LOC2, "CDC01-C-5-01-01-4-1", 10, "QUARANTINED", null))
                .status())
        .isEqualTo(200);
    assertThat(move("RELOCATION", mv(sku, "M1", shelf, rack, 1, "HELD", allocationId)).status())
        .isEqualTo(400);
    assertThat(move("RELOCATION", mv(sku, "M1", shelf, shelf, 1, null, null)).status())
        .isEqualTo(400);
  }
}
