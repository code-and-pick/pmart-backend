package com.pmart.cdc.inventory;

import static org.assertj.core.api.Assertions.assertThat;

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
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks"
    })
@Import(TestJwtConfiguration.class)
class StockLedgerFlowIT extends PostgresKafkaContainerSupport {

  static final String CENTER = "CDC01";
  static final String OPERATOR = TestJwt.operator("op-1", List.of("CDC_MANAGER"), List.of(CENTER));
  static final String SERVICE = TestJwt.service("cdc-outbound-service");
  static final String LOC1 = "CDC01-C-5-01-01-1-1";
  static final String LOC2 = "CDC01-C-5-01-02-1-1";
  static final String LOC3 = "CDC01-C-5-01-03-1-1";

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;
  @Autowired KafkaTemplate<String, String> kafka;

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
}
