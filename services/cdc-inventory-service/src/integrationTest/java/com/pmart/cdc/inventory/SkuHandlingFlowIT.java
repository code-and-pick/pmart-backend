package com.pmart.cdc.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.EventEnvelopeFixtures;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
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
 * 상품 SKU 투영과 CDC 취급 ON/OFF: product-service 이벤트(SkuIssued·ProductVersionPublished)를 Kafka 로 흘림 → 투영
 * → 취급 목록은 OFF(레코드 없음) → 자기 센터 운영자가 켬 → 멱등 재생 → 다른 센터 운영자 403 → 오래된 버전 무시. 실제 PostgreSQL·Kafka, 시험
 * 토큰.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks"
    })
@Import(TestJwtConfiguration.class)
class SkuHandlingFlowIT extends PostgresKafkaContainerSupport {

  static final String TOPIC = "commerce.product.v1";
  static final String CENTER = "CDC01";
  static final String OTHER = "CDC02";
  static final String OPERATOR =
      TestJwt.operator("op-1", List.of("CDC_SUPERVISOR"), List.of(CENTER));
  static final String OTHER_OPERATOR =
      TestJwt.operator("op-2", List.of("CDC_SUPERVISOR"), List.of(OTHER));
  static final String SERVICE = TestJwt.service("cdc-outbound-service");

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;
  @Autowired KafkaTemplate<String, String> kafka;

  RestClient http;

  @BeforeEach
  void client() throws Exception {
    StockLedgerFlowIT.ensureTopic(TOPIC); // 먼저 도는 시험이면 토픽이 아직 없을 수 있다
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
                  h.set("X-Source-System", "backoffice-web");
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

  Res await(String token, String path, Predicate<Res> done) throws InterruptedException {
    Instant until = Instant.now().plus(Duration.ofSeconds(60));
    Res r = get(token, path);
    while (!done.test(r) && Instant.now().isBefore(until)) {
      Thread.sleep(300);
      r = get(token, path);
    }
    return r;
  }

  static String newSku(String unit) {
    return unit + "-" + String.format("%08d", ThreadLocalRandom.current().nextInt(1, 99_999_999));
  }

  void skuIssued(String sku, String productId, int version) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("sku", sku);
    payload.put("baseProductId", "BP-" + productId);
    payload.put("productId", productId);
    payload.put("version", version);
    payload.put("issuedAt", Instant.now().toString());
    payload.put("approvalRef", Map.of("approvalId", "AP-1"));
    send(productId, "SkuIssued", "Sku", sku, 1, payload);
  }

  static Map<String, Object> skuSnapshot(String sku, int weightG, String state) {
    Map<String, Object> s = new LinkedHashMap<>();
    s.put("sku", sku);
    s.put("unitType", sku.substring(0, 1));
    s.put("unitsPerSku", 1);
    s.put("weightG", weightG);
    s.put("volumeMl", 500);
    s.put("storageType", "CHILLED");
    s.put("alcohol", false);
    s.put("state", state);
    s.put("barcodes", List.of("08801234567890"));
    s.put("logistics", Map.of("unitsPerBox", 12, "boxesPerPallet", 40, "ti", 8, "hi", 5));
    return s;
  }

  void published(String productId, int version, List<Map<String, Object>> skus) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("productId", productId);
    payload.put("baseProductId", "BP-" + productId);
    payload.put("skus", skus);
    payload.put("version", version);
    payload.put("effectiveAt", Instant.now().toString());
    payload.put("saleState", "ON_SALE");
    send(productId, "ProductVersionPublished", "Product", productId, version, payload);
  }

  void send(
      String key,
      String type,
      String aggregateType,
      String aggregateId,
      long version,
      Map<String, Object> payload) {
    String json =
        EventEnvelopeFixtures.envelope(type)
            .from("product-service")
            .aggregate(aggregateType, aggregateId, version)
            .payload(payload)
            .buildJson();
    kafka.send(TOPIC, key, json).join();
  }

  Res awaitProjection(String sku, Predicate<JsonNode> done) throws InterruptedException {
    return await(SERVICE, "/internal/skus/" + sku, r -> r.status() == 200 && done.test(r.body()));
  }

  static JsonNode item(JsonNode page, String sku) {
    for (JsonNode n : page.path("items")) {
      if (sku.equals(n.path("sku").asString())) {
        return n;
      }
    }
    return null;
  }

  static Map<String, Object> change(String sku, boolean enabled) {
    return Map.of("sku", sku, "enabled", enabled, "reason", "센터 판매 시작");
  }

  @Test
  void projectionThenOperatorTurnsHandlingOn() throws Exception {
    String productId = "PRD-" + UUID.randomUUID();
    String sku = newSku("E");
    String inactive = newSku("B");

    // 1) SkuIssued → 식별만 있는 투영, ProductVersionPublished → 단위·중량·상태 채움
    skuIssued(sku, productId, 1);
    skuIssued(inactive, productId, 1);
    published(
        productId,
        1,
        List.of(skuSnapshot(sku, 300, "ACTIVE"), skuSnapshot(inactive, 1800, "INACTIVE")));
    Res proj = awaitProjection(sku, b -> b.path("weightG").asInt() == 300);
    assertThat(proj.status()).isEqualTo(200);
    assertThat(proj.body().path("unitType").asString()).isEqualTo("E");
    assertThat(proj.body().path("storageType").asString()).isEqualTo("CHILLED");
    assertThat(proj.body().path("productVersion").asInt()).isEqualTo(1);
    // CDC 는 승인된 물류 규격도 투영한다(CDC-F-002)
    assertThat(proj.body().path("logistics").path("unitsPerBox").asInt()).isEqualTo(12);
    assertThat(proj.body().path("logistics").path("hi").asInt()).isEqualTo(5);
    assertThat(
            awaitProjection(inactive, b -> "INACTIVE".equals(b.path("state").asString())).status())
        .isEqualTo(200);
    // 이벤트로는 취급 레코드를 만들지 않는다
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM center_sku_handling WHERE sku IN (?, ?)",
                Integer.class,
                sku,
                inactive))
        .isZero();

    // 2) 취급 목록: 레코드 없음 = OFF, version 0
    Res list = get(OPERATOR, "/cdcs/" + CENTER + "/sku-handlings?size=200");
    assertThat(list.status()).isEqualTo(200);
    JsonNode row = item(list.body(), sku);
    assertThat(row).isNotNull();
    assertThat(row.path("handlingEnabled").asBoolean()).isFalse();
    assertThat(row.path("version").asInt()).isZero();
    assertThat(
            item(
                get(OPERATOR, "/cdcs/" + CENTER + "/sku-handlings?enabled=false&size=200").body(),
                sku))
        .isNotNull();
    assertThat(
            item(
                get(OPERATOR, "/cdcs/" + CENTER + "/sku-handlings?enabled=true&size=200").body(),
                sku))
        .isNull();

    // 3) 다른 센터 운영자는 조회·변경 403
    assertThat(get(OTHER_OPERATOR, "/cdcs/" + CENTER + "/sku-handlings").status()).isEqualTo(403);
    assertThat(
            post(
                    OTHER_OPERATOR,
                    "/cdcs/" + CENTER + "/sku-handlings",
                    UUID.randomUUID().toString(),
                    change(sku, true))
                .status())
        .isEqualTo(403);

    // 4) 자기 센터 운영자가 켬 → version 1, 이력 1건
    String key = UUID.randomUUID().toString();
    Res on = post(OPERATOR, "/cdcs/" + CENTER + "/sku-handlings", key, change(sku, true));
    assertThat(on.status()).isEqualTo(200);
    assertThat(on.body().path("status").asString()).isEqualTo("APPLIED");
    assertThat(on.body().path("changed").asBoolean()).isTrue();
    assertThat(on.body().path("handling").path("handlingEnabled").asBoolean()).isTrue();
    assertThat(on.body().path("handling").path("version").asInt()).isEqualTo(1);
    assertThat(on.body().path("handling").path("changedBy").asString()).isEqualTo("op-1");

    // 5) 같은 키·같은 본문 → 저장된 결과 재생(다시 처리하지 않음), 다른 본문 → 409
    Res replay = post(OPERATOR, "/cdcs/" + CENTER + "/sku-handlings", key, change(sku, true));
    assertThat(replay.status()).isEqualTo(200);
    assertThat(replay.body()).isEqualTo(on.body());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM center_sku_handling_history WHERE center_id = ? AND sku = ?",
                Integer.class,
                CENTER,
                sku))
        .isEqualTo(1);
    assertThat(
            post(OPERATOR, "/cdcs/" + CENTER + "/sku-handlings", key, change(sku, false)).status())
        .isEqualTo(409);

    // 6) 낡은 X-Expected-Version → 409, 같은 값으로 다시 켜기 → 변경 없음
    var stale =
        http.post()
            .uri("/cdcs/" + CENTER + "/sku-handlings")
            .headers(
                h -> {
                  h.setBearerAuth(OPERATOR);
                  h.set("Idempotency-Key", UUID.randomUUID().toString());
                  h.set("X-Source-System", "backoffice-web");
                  h.set("X-Expected-Version", "0");
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(change(sku, false))
            .retrieve()
            .toEntity(String.class);
    assertThat(stale.getStatusCode().value()).isEqualTo(409);
    Res same =
        post(
            OPERATOR,
            "/cdcs/" + CENTER + "/sku-handlings",
            UUID.randomUUID().toString(),
            change(sku, true));
    assertThat(same.body().path("changed").asBoolean()).isFalse();
    assertThat(same.body().path("handling").path("version").asInt()).isEqualTo(1);

    // 7) 비활성 SKU 켜기 → 422, 모르는 SKU → 404, 비활성 SKU 끄기(레코드 없음 = 이미 OFF) → 200
    Res inact =
        post(
            OPERATOR,
            "/cdcs/" + CENTER + "/sku-handlings",
            UUID.randomUUID().toString(),
            change(inactive, true));
    assertThat(inact.status()).isEqualTo(422);
    assertThat(inact.body().path("code").asString()).isEqualTo("SKU_INACTIVE");
    Res unknown =
        post(
            OPERATOR,
            "/cdcs/" + CENTER + "/sku-handlings",
            UUID.randomUUID().toString(),
            change(newSku("E"), true));
    assertThat(unknown.status()).isEqualTo(404);
    assertThat(unknown.body().path("code").asString()).isEqualTo("SKU_NOT_FOUND");
    assertThat(
            post(
                    OPERATOR,
                    "/cdcs/" + CENTER + "/sku-handlings",
                    UUID.randomUUID().toString(),
                    change(inactive, false))
                .status())
        .isEqualTo(200);

    // 8) 순서 뒤바뀜: v2 반영 후 늦게 온 v1 은 무시. 같은 키(같은 파티션) 표식 이벤트가 처리될 때까지 기다린 뒤 확인
    published(productId, 2, List.of(skuSnapshot(sku, 320, "ACTIVE")));
    assertThat(awaitProjection(sku, b -> b.path("productVersion").asInt() == 2).status())
        .isEqualTo(200);
    published(productId, 1, List.of(skuSnapshot(sku, 999, "INACTIVE")));
    String marker = newSku("E");
    skuIssued(marker, productId, 2);
    assertThat(awaitProjection(marker, b -> true).status()).isEqualTo(200);
    JsonNode after = get(SERVICE, "/internal/skus/" + sku).body();
    assertThat(after.path("productVersion").asInt()).isEqualTo(2);
    assertThat(after.path("weightG").asInt()).isEqualTo(320);
    assertThat(after.path("state").asString()).isEqualTo("ACTIVE");
  }

  @Test
  void batchIsAllOrNothing() throws Exception {
    String productId = "PRD-" + UUID.randomUUID();
    String a = newSku("E");
    String b = newSku("E");
    String inactive = newSku("B");
    published(
        productId,
        1,
        List.of(
            skuSnapshot(a, 100, "ACTIVE"),
            skuSnapshot(b, 100, "ACTIVE"),
            skuSnapshot(inactive, 100, "INACTIVE")));
    assertThat(awaitProjection(inactive, x -> true).status()).isEqualTo(200);
    String path = "/cdcs/" + CENTER + "/sku-handling-batches";

    List<Map<String, Object>> bad = new ArrayList<>();
    bad.add(Map.of("sku", a, "enabled", true));
    bad.add(Map.of("sku", inactive, "enabled", true));
    Res rejected = post(OPERATOR, path, UUID.randomUUID().toString(), Map.of("items", bad));
    assertThat(rejected.status()).isEqualTo(422);
    assertThat(rejected.body().path("code").asString()).isEqualTo("SKU_HANDLING_BATCH_REJECTED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM center_sku_handling WHERE center_id = ? AND sku = ?",
                Integer.class,
                CENTER,
                a))
        .isZero();

    Res dup =
        post(
            OPERATOR,
            path,
            UUID.randomUUID().toString(),
            Map.of(
                "items",
                List.of(Map.of("sku", a, "enabled", true), Map.of("sku", a, "enabled", false))));
    assertThat(dup.status()).isEqualTo(400);

    Res ok =
        post(
            OPERATOR,
            path,
            UUID.randomUUID().toString(),
            Map.of(
                "reason",
                "개점 일괄",
                "items",
                List.of(Map.of("sku", a, "enabled", true), Map.of("sku", b, "enabled", true))));
    assertThat(ok.status()).isEqualTo(200);
    assertThat(ok.body().path("changedCount").asInt()).isEqualTo(2);
    Res on = get(OPERATOR, "/cdcs/" + CENTER + "/sku-handlings?enabled=true&size=200");
    assertThat(item(on.body(), a)).isNotNull();
    assertThat(item(on.body(), b).path("reason").asString()).isEqualTo("개점 일괄");

    assertThat(
            post(OTHER_OPERATOR, path, UUID.randomUUID().toString(), Map.of("items", bad)).status())
        .isEqualTo(403);
  }
}
