package com.pmart.promotion;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.EventEnvelopeFixtures;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 쿠폰·기간 할인(ADR-0082): 행사 만들기 → 제출(해시 잠금·승인 요청 본문) → ApprovalDecided(PROMOTION) → 발효·반영 회신, SKU·카테고리
 * 기간 할인(가장 큰 1개), 쿠폰 내려받기(1인 한도·총 수량), 지정 발급, 계산·점유·확정·복원, 조기 종료. 실제 PostgreSQL·Kafka.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.messaging.outbox.delete-after-insert=false",
      "pmart.it.context=PromotionsIT",
      "pmart.promotion.display-sync-interval=PT0.5S",
      "pmart.promotion.display-sync-initial-delay=PT1S",
      "pmart.promotion.compensation.dispatch-unavailable-promotion-id=PRM-COMP-IT",
      "pmart.promotion.compensation.retry-interval=PT1S",
      "pmart.promotion.compensation.retry-initial-delay=PT1S",
      // 0.3.0 — 테마 받기·받을 고객 발급은 시험이 직접 부른다(주기 작업과 겹치지 않게)
      "pmart.promotion.theme-sync-initial-delay=PT1H",
      "pmart.promotion.recipient-grant-initial-delay=PT1H"
    })
@Import(TestJwtConfiguration.class)
class PromotionsIT extends PostgresKafkaContainerSupport {

  static final String MKT = TestJwt.operator("mkt-1", List.of("MARKETING"), List.of());
  static final String CS = TestJwt.operator("cs-1", List.of("CS_AGENT"), List.of());
  static final String MD = TestJwt.operator("md-1", List.of("MD"), List.of());

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired com.pmart.promotion.application.SignupCoupons signupCoupons;
  @Autowired com.pmart.promotion.application.ThemeSync themeSync;
  @Autowired com.pmart.promotion.application.RecipientGrants recipientGrants;
  RestClient http;

  /** storefront {@code /internal/storefront/themes} 대역(0.3.0) — 시험이 응답 본문을 바꾼다. */
  static final java.util.concurrent.atomic.AtomicReference<String> STOREFRONT_THEMES =
      new java.util.concurrent.atomic.AtomicReference<>("{\"layoutVersion\":0,\"themes\":[]}");

  static final com.sun.net.httpserver.HttpServer STOREFRONT = startStorefront();

  static com.sun.net.httpserver.HttpServer startStorefront() {
    try {
      var server =
          com.sun.net.httpserver.HttpServer.create(
              new java.net.InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/api/v1/internal/storefront/themes",
          ex -> {
            byte[] b = STOREFRONT_THEMES.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
          });
      server.start();
      return server;
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @org.springframework.test.context.DynamicPropertySource
  static void storefront(org.springframework.test.context.DynamicPropertyRegistry r) {
    r.add(
        "pmart.promotion.storefront-base-url",
        () -> "http://127.0.0.1:" + STOREFRONT.getAddress().getPort());
  }

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .requestFactory(new JdkClientHttpRequestFactory())
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
  }

  record Res(int status, JsonNode body) {}

  Res send(String method, String token, String path, Object body) {
    return send(method, token, path, body, UUID.randomUUID().toString());
  }

  Res send(String method, String token, String path, Object body, String key) {
    var spec =
        http.method(org.springframework.http.HttpMethod.valueOf(method))
            .uri(path)
            .headers(
                h -> {
                  if (token != null) {
                    h.setBearerAuth(token);
                  }
                  h.set("Idempotency-Key", key);
                  h.set("X-Source-System", "it");
                });
    if (body != null) {
      spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
    }
    return spec.exchange(
        (q, r) -> {
          byte[] b = r.getBody().readAllBytes();
          return new Res(
              r.getStatusCode().value(), mapper.readTree(b.length == 0 ? "{}" : new String(b)));
        });
  }

  static <T> T await(Supplier<T> s, Predicate<T> ok) throws InterruptedException {
    T v = s.get();
    for (int i = 0; i < 80 && !ok.test(v); i++) {
      Thread.sleep(250);
      v = s.get();
    }
    return v;
  }

  Map<String, Object> priceDiscount(String name, Map<String, Object> discount, List<?> targets) {
    Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("kind", "PRICE_DISCOUNT");
    m.put("name", name);
    m.put("startsAt", now.minus(1, ChronoUnit.MINUTES).toString());
    m.put("endsAt", now.plus(1, ChronoUnit.DAYS).toString());
    m.put("discount", discount);
    m.put("targets", targets);
    return m;
  }

  Map<String, Object> coupon(
      String name, Map<String, Object> discount, Map<String, Object> policy) {
    Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("kind", "COUPON");
    m.put("name", name);
    m.put("startsAt", now.minus(1, ChronoUnit.MINUTES).toString());
    m.put("endsAt", now.plus(1, ChronoUnit.DAYS).toString());
    m.put("discount", discount);
    m.put("coupon", policy);
    return m;
  }

  /** 만들기 → 제출 → 승인(ACTIVATE) → APPROVED 까지. 행사 ID. */
  String approved(Map<String, Object> input) throws InterruptedException {
    Res created = send("POST", MKT, "/promotions", input);
    assertThat(created.status()).as(created.body().toString()).isEqualTo(201);
    String id = created.body().path("promotionId").asString();
    assertThat(created.body().path("problems").size()).as(created.body().toString()).isZero();
    Res sub = send("POST", MKT, "/promotions/" + id + "/submissions", null);
    assertThat(sub.status()).as(sub.body().toString()).isEqualTo(200);
    JsonNode req = sub.body().path("approvalRequest");
    assertThat(req.path("type").asString()).isEqualTo("PROMOTION");
    assertThat(req.path("targetService").asString()).isEqualTo("promotion-service");
    decide(
        "AP-" + UUID.randomUUID(),
        id,
        "APPROVED",
        "ACTIVATE",
        sub.body().path("draftHash").asString());
    Res got =
        await(
            () -> send("GET", MKT, "/promotions/" + id, null),
            r -> "APPROVED".equals(r.body().path("state").asString()));
    assertThat(got.body().path("state").asString()).isEqualTo("APPROVED");
    assertThat(got.body().path("phase").asString()).isEqualTo("RUNNING");
    return id;
  }

  void decide(String approvalId, String promotionId, String decision, String action, String hash) {
    Map<String, Object> attrs = new LinkedHashMap<>();
    attrs.put("promotionId", promotionId);
    attrs.put("action", action);
    attrs.put("draftHash", hash);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("approvalId", approvalId);
    payload.put("changeRequestId", "CR-" + approvalId);
    payload.put("type", "PROMOTION");
    payload.put("decision", decision);
    payload.put(
        "targetRef",
        Map.of(
            "targetType",
            "Promotion",
            "targetId",
            promotionId,
            "targetService",
            "promotion-service"));
    payload.put(
        "lines", List.of(Map.of("lineId", promotionId, "decision", decision, "attributes", attrs)));
    payload.put("approverId", "mkt-2");
    payload.put("dossierVersion", 2);
    kafka
        .send(
            "platform.approval.v1",
            approvalId,
            EventEnvelopeFixtures.envelope("ApprovalDecided")
                .from("approval-service")
                .aggregate("Approval", approvalId, 2)
                .payload(payload)
                .buildJson())
        .join();
  }

  void productPublished(String productId, String sku, List<String> categoryIds) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("productId", productId);
    payload.put("baseProductId", "BP-" + productId);
    payload.put("version", 1);
    payload.put("categoryIds", categoryIds);
    payload.put("skus", List.of(Map.of("sku", sku, "unitsPerSku", 1, "weightG", 100)));
    kafka
        .send(
            "commerce.product.v1",
            productId,
            EventEnvelopeFixtures.envelope("ProductVersionPublished")
                .from("product-service")
                .aggregate("Product", productId, 1)
                .payload(payload)
                .buildJson())
        .join();
  }

  JsonNode discounts(Map<String, Integer> prices) {
    List<Map<String, Object>> items =
        prices.entrySet().stream()
            .map(e -> Map.<String, Object>of("sku", e.getKey(), "unitPrice", e.getValue()))
            .toList();
    Res r = send("POST", null, "/internal/price-discounts", Map.of("items", items));
    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    return r.body().path("items");
  }

  void priceEffective(String sku, long price, int version) {
    kafka
        .send(
            "commerce.pricing.v1",
            sku,
            EventEnvelopeFixtures.envelope("PriceVersionEffective")
                .from("pricing-service")
                .aggregate("Price", sku, version)
                .payload(
                    Map.of(
                        "sku",
                        sku,
                        "price",
                        price,
                        "priceVersion",
                        version,
                        "effectiveAt",
                        Instant.now().toString()))
                .buildJson())
        .join();
  }

  /** 그 SKU 의 SkuDisplayDiscountChanged payload(seq 순). */
  List<JsonNode> displayEvents(String sku) {
    return jdbc
        .queryForList(
            "SELECT payload::text FROM outbox_event WHERE type = 'SkuDisplayDiscountChanged'"
                + " AND aggregateid = ? AND topic = 'commerce.sku-display-discount.v1'",
            String.class,
            sku)
        .stream()
        .map(t -> mapper.readTree(t).path("payload"))
        .sorted(java.util.Comparator.comparingInt(n -> n.path("seq").asInt()))
        .toList();
  }

  static String randomSku() {
    return "E-" + String.format("%08d", (int) (Math.random() * 90_000_000) + 10_000_000);
  }

  @Test
  void periodDiscountsBySkuAndCategoryTakeTheLargestAndEndEarly() throws Exception {
    String skuA = randomSku();
    String skuB = randomSku();
    String cat = "p" + (int) (Math.random() * 900 + 100);
    productPublished("PRD-" + UUID.randomUUID(), skuB, List.of(cat + "-c01-l01"));
    await(
        () ->
            jdbc.queryForObject(
                "SELECT count(*) FROM sku_category WHERE sku = ?", Integer.class, skuB),
        n -> n != null && n == 3);

    // 권한: MD 는 만들 수 없다
    assertThat(
            send(
                    "POST",
                    MD,
                    "/promotions",
                    priceDiscount("x", Map.of("type", "RATE", "value", 10), List.of()))
                .status())
        .isEqualTo(403);
    // 초안은 고칠 곳이 있어도 저장, 제출은 막힌다
    Res draft =
        send(
            "POST",
            MKT,
            "/promotions",
            priceDiscount("빈 대상", Map.of("type", "RATE", "value", 10), List.of()));
    assertThat(draft.body().path("problems").toString()).contains("targets");
    Res blocked =
        send(
            "POST",
            MKT,
            "/promotions/" + draft.body().path("promotionId").asString() + "/submissions",
            null);
    assertThat(blocked.status()).isEqualTo(422);
    assertThat(blocked.body().path("code").asString()).isEqualTo("PROMOTION_INVALID");

    // SKU A 10%, 카테고리(상위) 500원, SKU B 직접 5%
    approved(
        priceDiscount(
            "A 10%",
            Map.of("type", "RATE", "value", 10), List.of(Map.of("type", "SKU", "id", skuA))));
    String catPromo =
        approved(
            priceDiscount(
                "카테고리 500원",
                Map.of("type", "AMOUNT", "value", 500),
                List.of(Map.of("type", "CATEGORY", "id", cat))));
    approved(
        priceDiscount(
            "B 5%",
            Map.of("type", "RATE", "value", 5), List.of(Map.of("type", "SKU", "id", skuB))));

    JsonNode items = discounts(Map.of(skuA, 9990, skuB, 3000));
    Map<String, JsonNode> bySku = new LinkedHashMap<>();
    items.forEach(i -> bySku.put(i.path("sku").asString(), i));
    assertThat(bySku.get(skuA).path("unitDiscount").asInt()).isEqualTo(999);
    assertThat(bySku.get(skuA).path("discountedUnitPrice").asInt()).isEqualTo(8991);
    // B: 카테고리 500원 > 5%(150원) — 가장 큰 1개
    assertThat(bySku.get(skuB).path("unitDiscount").asInt()).isEqualTo(500);
    assertThat(bySku.get(skuB).path("promotionId").asString()).isEqualTo(catPromo);
    assertThat(bySku.get(skuB).path("ratePercent").asInt()).isEqualTo(16);

    // 반영 회신
    Integer receipts =
        jdbc.queryForObject(
            "SELECT count(*) FROM outbox_event WHERE type = 'ApplicationReceiptRecorded' AND payload::text LIKE ?",
            Integer.class,
            "%" + catPromo + "%");
    assertThat(receipts).isPositive();

    // 조기 종료 → B 는 5% 로
    Res end =
        send(
            "POST", MKT, "/promotions/" + catPromo + "/end-submissions", Map.of("reason", "재고 소진"));
    assertThat(end.status()).as(end.body().toString()).isEqualTo(200);
    decide(
        "AP-" + UUID.randomUUID(),
        catPromo,
        "APPROVED",
        "END",
        end.body().path("draftHash").asString());
    await(
        () -> send("GET", MKT, "/promotions/" + catPromo, null),
        r -> "ENDED".equals(r.body().path("state").asString()));
    JsonNode after = discounts(Map.of(skuB, 3000));
    assertThat(after.path(0).path("unitDiscount").asInt()).isEqualTo(150);
  }

  @Test
  void displayDiscountFollowsPriceStartEndAndCategoryProducts() throws Exception {
    String skuA = randomSku();
    String skuC = randomSku();
    String cat = "q" + (int) (Math.random() * 900 + 100);
    priceEffective(skuA, 10_000, 1);
    priceEffective(skuC, 3_000, 1);
    await(
        () ->
            jdbc.queryForObject(
                "SELECT count(*) FROM sku_price WHERE sku IN (?, ?)", Integer.class, skuA, skuC),
        n -> n != null && n == 2);
    // 더 오래된 가격 버전은 무시
    priceEffective(skuA, 1, 0);

    // A: SKU 10% 와 정액 300원 → 큰 쪽(1,000원)
    String rate =
        approved(
            priceDiscount(
                "A 10%",
                Map.of("type", "RATE", "value", 10), List.of(Map.of("type", "SKU", "id", skuA))));
    approved(
        priceDiscount(
            "A 300원",
            Map.of("type", "AMOUNT", "value", 300),
            List.of(Map.of("type", "SKU", "id", skuA))));
    List<JsonNode> a = await(() -> displayEvents(skuA), l -> !l.isEmpty());
    assertThat(a).hasSize(1);
    assertThat(a.get(0).path("seq").asInt()).isEqualTo(1);
    assertThat(a.get(0).path("basePrice").asLong()).isEqualTo(10_000);
    assertThat(a.get(0).path("unitDiscount").asLong()).isEqualTo(1_000);
    assertThat(a.get(0).path("salePrice").asLong()).isEqualTo(9_000);
    assertThat(a.get(0).path("ratePercent").asInt()).isEqualTo(10);
    assertThat(a.get(0).path("promotionId").asString()).isEqualTo(rate);
    assertThat(a.get(0).path("priceVersion").asInt()).isEqualTo(1);
    // 바뀐 것이 없으면 다시 알리지 않는다
    Thread.sleep(1500);
    assertThat(displayEvents(skuA)).hasSize(1);

    // 가격이 바뀌면 새 기본가로(2,000원 → 10% = 200원 < 300원 → 정액 행사)
    priceEffective(skuA, 2_000, 2);
    List<JsonNode> a2 = await(() -> displayEvents(skuA), l -> l.size() >= 2);
    assertThat(a2.get(1).path("seq").asInt()).isEqualTo(2);
    assertThat(a2.get(1).path("priceVersion").asInt()).isEqualTo(2);
    assertThat(a2.get(1).path("unitDiscount").asLong()).isEqualTo(300);
    assertThat(a2.get(1).path("salePrice").asLong()).isEqualTo(1_700);

    // 카테고리 행사 뒤에 그 카테고리에 들어온 상품도 할인가로
    String catPromo =
        approved(
            priceDiscount(
                "카테고리 20%",
                Map.of("type", "RATE", "value", 20),
                List.of(Map.of("type", "CATEGORY", "id", cat))));
    Thread.sleep(1500);
    assertThat(displayEvents(skuC)).isEmpty();
    productPublished("PRD-" + UUID.randomUUID(), skuC, List.of(cat + "-c01"));
    List<JsonNode> c = await(() -> displayEvents(skuC), l -> !l.isEmpty());
    assertThat(c.get(0).path("salePrice").asLong()).isEqualTo(2_400);
    assertThat(c.get(0).path("promotionName").asString()).isEqualTo("카테고리 20%");

    // 조기 종료 → 할인 없음(unitDiscount 0, promotionId 없음)
    Res end =
        send("POST", MKT, "/promotions/" + catPromo + "/end-submissions", Map.of("reason", "끝"));
    decide(
        "AP-" + UUID.randomUUID(),
        catPromo,
        "APPROVED",
        "END",
        end.body().path("draftHash").asString());
    List<JsonNode> c2 = await(() -> displayEvents(skuC), l -> l.size() >= 2);
    assertThat(c2.get(1).path("seq").asInt()).isEqualTo(2);
    assertThat(c2.get(1).path("unitDiscount").asLong()).isZero();
    assertThat(c2.get(1).path("salePrice").asLong()).isEqualTo(3_000);
    assertThat(c2.get(1).path("promotionId").isNull()).isTrue();
    Thread.sleep(1500);
    assertThat(displayEvents(skuC)).hasSize(2);
  }

  void identityVerified(String accountId, String stage, String signupKey) {
    Map<String, Object> p = new java.util.HashMap<>();
    p.put("accountId", accountId);
    p.put("stage", stage);
    p.put("signupKey", signupKey);
    p.put("verifiedAt", Instant.now().toString());
    kafka
        .send(
            "commerce.customer.v1",
            accountId,
            EventEnvelopeFixtures.envelope("CustomerIdentityVerified")
                .from("customer-service")
                .aggregate("CustomerIdentity", accountId, System.currentTimeMillis())
                .payload(p)
                .buildJson())
        .join();
  }

  void orderCancelled(String orderId, String accountId, String cancelReason) {
    kafka
        .send(
            "order.order.v1",
            orderId,
            EventEnvelopeFixtures.envelope("OrderCancelled")
                .from("order-service")
                .aggregate("Order", orderId, 3)
                .payload(
                    Map.of(
                        "orderId",
                        orderId,
                        "scope",
                        "FULL",
                        "lines",
                        List.of(),
                        "reason",
                        "CANCELLED",
                        "cancelReason",
                        cancelReason,
                        "accountId",
                        accountId))
                .buildJson())
        .join();
  }

  int couponsOf(String accountId, String promotionId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM coupon WHERE account_id = ? AND promotion_id = ?",
            Integer.class,
            accountId,
            promotionId);
    return n == null ? 0 : n;
  }

  static String signupKey() {
    return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "");
  }

  /** ADR-0082 5단계: 가입 축하 쿠폰(같은 번호 키는 다시 안 줌)·배차 불가 보상 쿠폰(주문마다 한 번, 행사 전이면 대기). */
  @Test
  void signupAndDispatchUnavailableCompensationCoupons() throws Exception {
    String signup =
        approved(
            coupon(
                "가입 축하 3천원",
                Map.of("type", "AMOUNT", "value", 3000),
                Map.of(
                    "issueMode",
                    "SIGNUP",
                    "minOrderAmount",
                    0,
                    "perCustomerLimit",
                    1,
                    "useDays",
                    30)));
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    String a = "CU-SA-" + suffix;
    String rejoin = "CU-SR-" + suffix;
    String profile = "CU-SP-" + suffix;
    String noKey = "CU-SN-" + suffix;
    String other = "CU-SO-" + suffix;
    String keyA = signupKey();
    identityVerified(a, "SIGNUP", keyA);
    identityVerified(rejoin, "SIGNUP", keyA); // 같은 번호로 재가입 — 다시 안 줌
    identityVerified(profile, "PROFILE", signupKey()); // 가입이 아닌 /me 확인
    identityVerified(noKey, "SIGNUP", null); // 키 없음(비밀값 미설정)
    identityVerified(other, "SIGNUP", signupKey());
    // 한 파티션이 아닐 수 있어 각각 기다린다
    await(() -> couponsOf(a, signup), n -> n == 1);
    await(() -> couponsOf(other, signup), n -> n == 1);
    Thread.sleep(1500);
    assertThat(couponsOf(a, signup)).isEqualTo(1);
    assertThat(couponsOf(other, signup)).isEqualTo(1);
    assertThat(couponsOf(rejoin, signup)).isZero();
    assertThat(couponsOf(profile, signup)).isZero();
    assertThat(couponsOf(noKey, signup)).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT issue_channel FROM coupon WHERE account_id = ? AND promotion_id = ?",
                String.class,
                a,
                signup))
        .isEqualTo("SIGNUP");

    // 보상: 행사(PRM-COMP-IT)가 아직 없으면 대기
    String cust = "CU-COMP-" + suffix;
    orderCancelled("ORD-C0-" + suffix, cust, "DISPATCH_UNAVAILABLE");
    await(
        () ->
            jdbc.queryForObject(
                "SELECT count(*) FROM compensation_grant WHERE order_id = ? AND state = 'PENDING'",
                Integer.class,
                "ORD-C0-" + suffix),
        n -> n != null && n == 1);
    // 보상 행사(ADMIN 2,000원, 1인 한도 1)를 만들어 설정한 ID 로 — 대기분도 주기 작업이 준다
    String comp =
        approved(
            coupon(
                "배차 불가 보상 2천원",
                Map.of("type", "AMOUNT", "value", 2000),
                Map.of(
                    "issueMode",
                    "ADMIN",
                    "minOrderAmount",
                    0,
                    "perCustomerLimit",
                    1,
                    "useDays",
                    30)));
    jdbc.update("UPDATE promotion SET promotion_id = 'PRM-COMP-IT' WHERE promotion_id = ?", comp);
    orderCancelled("ORD-C1-" + suffix, cust, "DISPATCH_UNAVAILABLE");
    orderCancelled("ORD-C1-" + suffix, cust, "DISPATCH_UNAVAILABLE"); // 같은 주문 두 번 — 한 번만
    orderCancelled("ORD-C2-" + suffix, cust, "CUSTOMER_REQUEST"); // 다른 사유 — 없음
    await(() -> couponsOf(cust, "PRM-COMP-IT"), n -> n == 2);
    Thread.sleep(1500);
    assertThat(couponsOf(cust, "PRM-COMP-IT")).as("주문 두 건(대기 + 바로), 1인 한도로 막지 않음").isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM compensation_grant WHERE account_id = ? AND state = 'ISSUED'",
                Integer.class,
                cust))
        .isEqualTo(2);
    JsonNode mine =
        send("GET", TestJwt.customer(cust), "/me/coupons?status=AVAILABLE", null).body();
    assertThat(mine.path("items").size()).isEqualTo(2);
    // 쿠폰 발급 알림 사건(CouponIssued) — 보상은 주문·사유, 가입은 채널 SIGNUP
    List<String> issued =
        jdbc.queryForList(
            "SELECT payload::text FROM outbox_event WHERE type = 'CouponIssued' AND topic = 'commerce.coupon.v1'"
                + " AND (payload::text LIKE ? OR payload::text LIKE ?)",
            String.class,
            "%" + cust + "%",
            "%" + a + "%");
    assertThat(issued).hasSize(3);
    assertThat(
            issued.stream().filter(t -> t.contains("ORD-C1-" + suffix)).findFirst().orElseThrow())
        .contains("DISPATCH_UNAVAILABLE");
    assertThat(issued.stream().filter(t -> t.contains(a)).findFirst().orElseThrow())
        .contains("SIGNUP");
  }

  /** 처리방침 "지급 후 5년"(2026-11-21): 보관 기간(설정 P1826D) 지난 가입 쿠폰 지급 키만 지운다. */
  @Test
  void signupGrantKeysArePurgedAfterRetention() {
    String old = signupKey();
    String recent = signupKey();
    jdbc.update(
        "INSERT INTO signup_grant (signup_key, account_id, granted_at) VALUES (?, 'CU-OLD', now() - interval '6 years')",
        old);
    jdbc.update(
        "INSERT INTO signup_grant (signup_key, account_id, granted_at) VALUES (?, 'CU-NEW', now() - interval '4 years')",
        recent);
    signupCoupons.purgeExpiredKeys();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM signup_grant WHERE signup_key IN (?, ?)",
                Integer.class,
                old,
                recent))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM signup_grant WHERE signup_key = ?", Integer.class, recent))
        .isEqualTo(1);
  }

  @Test
  void couponDownloadLimitsHoldConfirmRestoreAndGrant() throws Exception {
    String a = "CU-A-" + UUID.randomUUID().toString().substring(0, 8);
    String b = "CU-B-" + UUID.randomUUID().toString().substring(0, 8);
    String ta = TestJwt.customer(a);
    String tb = TestJwt.customer(b);
    String promo =
        approved(
            coupon(
                "10% 최대 2천원",
                Map.of("type", "RATE", "value", 10, "maxAmount", 2000),
                Map.of(
                    "issueMode",
                    "DOWNLOAD",
                    "minOrderAmount",
                    15000,
                    "totalQuantity",
                    1,
                    "perCustomerLimit",
                    1,
                    "useDays",
                    7)));

    Res campaigns = send("GET", ta, "/coupon-campaigns", null);
    assertThat(campaigns.body().path("items").findValuesAsString("promotionId")).contains(promo);

    String key = UUID.randomUUID().toString();
    Res got = send("POST", ta, "/coupon-campaigns/" + promo + "/downloads", null, key);
    assertThat(got.status()).as(got.body().toString()).isEqualTo(201);
    String couponId = got.body().path("couponId").asString();
    assertThat(
            send("POST", ta, "/coupon-campaigns/" + promo + "/downloads", null, key)
                .body()
                .path("couponId")
                .asString())
        .as("같은 키는 같은 결과")
        .isEqualTo(couponId);
    assertThat(
            send("POST", ta, "/coupon-campaigns/" + promo + "/downloads", null)
                .body()
                .path("code")
                .asString())
        .isEqualTo("COUPON_LIMIT_REACHED");
    assertThat(
            send("POST", tb, "/coupon-campaigns/" + promo + "/downloads", null)
                .body()
                .path("code")
                .asString())
        .isEqualTo("COUPON_SOLD_OUT");
    assertThat(
            send("GET", ta, "/me/coupons?status=AVAILABLE", null)
                .body()
                .path("items")
                .findValuesAsString("couponId"))
        .containsExactly(couponId);

    // 계산: 최소 금액·최대 할인액
    Res under =
        send(
            "POST",
            null,
            "/internal/coupon-quotes",
            Map.of("couponId", couponId, "accountId", a, "itemAmount", 10000));
    assertThat(under.body().path("reason").asString()).isEqualTo("MIN_ORDER_NOT_MET");
    Res q =
        send(
            "POST",
            null,
            "/internal/coupon-quotes",
            Map.of("couponId", couponId, "accountId", a, "itemAmount", 30000));
    assertThat(q.body().path("discountAmount").asInt()).isEqualTo(2000);
    assertThat(
            send(
                    "POST",
                    null,
                    "/internal/coupon-quotes",
                    Map.of("couponId", couponId, "accountId", b, "itemAmount", 30000))
                .body()
                .path("reason")
                .asString())
        .isEqualTo("NOT_OWNER");

    // 점유 → 다른 주문은 못 씀 → 확정 → USED → 복원 → AVAILABLE
    Map<String, Object> hold =
        Map.of("couponId", couponId, "accountId", a, "itemAmount", 30000, "orderId", "ORD-1");
    Res h = send("POST", null, "/internal/coupon-holds", hold);
    assertThat(h.status()).as(h.body().toString()).isEqualTo(201);
    String holdId = h.body().path("holdId").asString();
    assertThat(h.body().path("discountAmount").asInt()).isEqualTo(2000);
    Res other =
        send(
            "POST",
            null,
            "/internal/coupon-holds",
            Map.of("couponId", couponId, "accountId", a, "itemAmount", 30000, "orderId", "ORD-2"));
    assertThat(other.status()).isEqualTo(422);
    assertThat(other.body().path("details").path(0).path("reason").asString()).isEqualTo("HELD");
    assertThat(send("POST", null, "/internal/coupon-holds", hold).body().path("holdId").asString())
        .as("같은 주문의 점유는 그대로")
        .isEqualTo(holdId);
    assertThat(
            send("POST", null, "/internal/coupon-holds/" + holdId + "/restorations", null).status())
        .as("확정 전 복원 불가")
        .isEqualTo(409);
    assertThat(
            send("POST", null, "/internal/coupon-holds/" + holdId + "/confirmations", null)
                .body()
                .path("state")
                .asString())
        .isEqualTo("CONFIRMED");
    assertThat(
            send("GET", ta, "/me/coupons?status=USED", null)
                .body()
                .path("items")
                .findValuesAsString("couponId"))
        .containsExactly(couponId);
    // 0.2.1 BR-85 — 사용 확정 시각(주문 확정 때)
    assertThat(
            send("GET", ta, "/me/coupons?status=USED", null)
                .body()
                .path("items")
                .path(0)
                .path("usedAt")
                .asString(""))
        .isNotBlank();
    assertThat(send("POST", null, "/internal/coupon-holds/" + holdId + "/releases", null).status())
        .isEqualTo(409);
    Res restored = send("POST", null, "/internal/coupon-holds/" + holdId + "/restorations", null);
    assertThat(restored.body().path("state").asString()).isEqualTo("RESTORED");
    assertThat(restored.body().path("couponUsable").asBoolean()).isTrue();
    assertThat(
            send("GET", ta, "/me/coupons?status=AVAILABLE", null)
                .body()
                .path("items")
                .findValuesAsString("couponId"))
        .containsExactly(couponId);
    // 돌아온 쿠폰은 usedAt 없음
    JsonNode back =
        send("GET", ta, "/me/coupons?status=AVAILABLE", null).body().path("items").path(0);
    assertThat(back.path("usedAt").isNull() || back.path("usedAt").isMissingNode()).isTrue();

    // 지정 발급(ADMIN): CS 가 a·a·b → 두 명에게 1장씩
    String admin =
        approved(
            coupon(
                "배차 지연 보상 2천원",
                Map.of("type", "AMOUNT", "value", 2000),
                Map.of(
                    "issueMode",
                    "ADMIN",
                    "minOrderAmount",
                    0,
                    "perCustomerLimit",
                    1,
                    "useDays",
                    30)));
    assertThat(
            send("POST", ta, "/coupon-campaigns/" + admin + "/downloads", null)
                .body()
                .path("code")
                .asString())
        .isEqualTo("UNSUPPORTED_ACTION");
    Res granted =
        send(
            "POST",
            CS,
            "/promotions/" + admin + "/coupon-grants",
            Map.of("accountIds", List.of(a, a, b), "reason", "배차 지연"));
    assertThat(granted.status()).as(granted.body().toString()).isEqualTo(200);
    assertThat(granted.body().path("results").findValuesAsString("result"))
        .containsExactly("ISSUED", "ISSUED");
    Res again =
        send(
            "POST",
            CS,
            "/promotions/" + admin + "/coupon-grants",
            Map.of("accountIds", List.of(a), "reason", "배차 지연"));
    assertThat(again.body().path("results").path(0).path("result").asString())
        .isEqualTo("SKIPPED_LIMIT");
    assertThat(
            send(
                    "POST",
                    MD,
                    "/promotions/" + admin + "/coupon-grants",
                    Map.of("accountIds", List.of(a), "reason", "x"))
                .status())
        .isEqualTo(403);
  }

  void storefrontThemes(int version, String themesJson) {
    STOREFRONT_THEMES.set("{\"layoutVersion\":" + version + ",\"themes\":" + themesJson + "}");
  }

  /** 0.3.0 — 사용자 "행사 대상에 테마도 추가", 세부 "추천안대로 진행". */
  @Test
  void themeTargetFollowsPublishedThemeProductsAndStopsWhenRemoved() throws Exception {
    String p1 = "PRD-" + UUID.randomUUID();
    String p2 = "PRD-" + UUID.randomUUID();
    String s1 = randomSku();
    String s2 = randomSku();
    productPublished(p1, s1, List.of());
    productPublished(p2, s2, List.of());
    await(
        () ->
            jdbc.queryForObject(
                "SELECT count(*) FROM sku_product WHERE sku IN (?, ?)", Integer.class, s1, s2),
        n -> n != null && n == 2);
    String sale = "T-SALE" + (int) (Math.random() * 100000);
    int v = (int) (Math.random() * 100000) + 10;
    storefrontThemes(
        v,
        "[{\"sectionId\":\""
            + sale
            + "\",\"kind\":\"THEME\",\"title\":\"겨울 특가\",\"productIds\":[\""
            + p1
            + "\"]},{\"sectionId\":\"T-RANK\",\"kind\":\"RANKING_AUTO\",\"title\":\"인기\",\"productIds\":[]}]");
    assertThat(themeSync.sync()).isTrue();
    assertThat(themeSync.sync()).as("같은 진열 버전이면 그대로").isFalse();

    Res picks = send("GET", MKT, "/promotions/theme-targets", null);
    assertThat(picks.status()).as(picks.body().toString()).isEqualTo(200);
    assertThat(picks.body().path("layoutVersion").asInt()).isEqualTo(v);
    Map<String, JsonNode> byId = new LinkedHashMap<>();
    picks.body().path("themes").forEach(t -> byId.put(t.path("sectionId").asString(), t));
    assertThat(byId.get(sale).path("productCount").asInt()).isEqualTo(1);
    assertThat(byId.get(sale).path("selectable").asBoolean()).isTrue();
    assertThat(byId.get("T-RANK").path("selectable").asBoolean()).isFalse();
    assertThat(send("GET", MD, "/promotions/theme-targets", null).status()).isEqualTo(403);

    // 게시본에 없는 테마·자동 랭킹 테마는 제출을 막는다
    Res bad =
        send(
            "POST",
            MKT,
            "/promotions",
            priceDiscount(
                "없는 테마",
                Map.of("type", "RATE", "value", 10),
                List.of(
                    Map.of("type", "THEME", "id", "T-NOPE"),
                    Map.of("type", "THEME", "id", "T-RANK"))));
    assertThat(bad.body().path("problems").toString())
        .contains("THEME_NOT_PUBLISHED", "THEME_AUTO_RANKING");

    String promo =
        approved(
            priceDiscount(
                "테마 10%",
                Map.of("type", "RATE", "value", 10), List.of(Map.of("type", "THEME", "id", sale))));
    JsonNode got = send("GET", MKT, "/promotions/" + promo, null).body();
    assertThat(got.path("targets").path(0).path("title").asString()).isEqualTo("겨울 특가");

    Map<String, Integer> prices = new LinkedHashMap<>();
    prices.put(s1, 1000);
    prices.put(s2, 2000);
    JsonNode d = discounts(prices);
    assertThat(d).hasSize(1);
    assertThat(d.path(0).path("sku").asString()).isEqualTo(s1);
    assertThat(d.path(0).path("unitDiscount").asInt()).isEqualTo(100);

    // 테마 상품이 바뀌면 다시 승인 없이 따라간다
    storefrontThemes(
        v + 1,
        "[{\"sectionId\":\""
            + sale
            + "\",\"kind\":\"THEME\",\"title\":\"겨울 특가\",\"productIds\":[\""
            + p2
            + "\"]}]");
    assertThat(themeSync.sync()).isTrue();
    d = discounts(prices);
    assertThat(d).hasSize(1);
    assertThat(d.path(0).path("sku").asString()).isEqualTo(s2);
    assertThat(d.path(0).path("unitDiscount").asInt()).isEqualTo(200);

    // 테마가 진열에서 빠지면 할인도 멈춘다
    storefrontThemes(v + 2, "[]");
    assertThat(themeSync.sync()).isTrue();
    assertThat(discounts(prices)).isEmpty();
    assertThat(
            send("GET", MKT, "/promotions/" + promo, null)
                .body()
                .path("targets")
                .path(0)
                .path("title")
                .isString())
        .isFalse();
  }

  /** 0.3.0 — 사용자 "직원 지정 발급 … 고객 id 를 넣는 칸", 세부 "추천안대로 진행". */
  @Test
  void adminCouponRecipientsAreIssuedWhenRunning() throws Exception {
    String a = "CU-" + UUID.randomUUID();
    String b = "CU-" + UUID.randomUUID();
    Map<String, Object> policy = new LinkedHashMap<>();
    policy.put("issueMode", "ADMIN");
    policy.put("minOrderAmount", 0);
    policy.put("perCustomerLimit", 1);
    policy.put("totalQuantity", 1);
    policy.put("useDays", 7);
    policy.put("recipients", Map.of("accountIds", List.of(a, b), "reason", "배송 지연 보상"));

    // 내려받기 쿠폰에는 받을 고객을 둘 수 없다
    Map<String, Object> download = new LinkedHashMap<>(policy);
    download.put("issueMode", "DOWNLOAD");
    Res wrong =
        send(
            "POST",
            MKT,
            "/promotions",
            coupon("내려받기", Map.of("type", "AMOUNT", "value", 1000), download));
    assertThat(wrong.body().path("problems").toString()).contains("RECIPIENTS_NOT_ADMIN");

    Res created =
        send(
            "POST",
            MKT,
            "/promotions",
            coupon("보상 쿠폰", Map.of("type", "AMOUNT", "value", 1000), policy));
    assertThat(created.status()).as(created.body().toString()).isEqualTo(201);
    String id = created.body().path("promotionId").asString();
    assertThat(created.body().path("coupon").path("recipients").path("accountIds")).hasSize(2);
    assertThat(created.body().path("recipientResults").path(0).path("result").asString())
        .isEqualTo("PENDING");
    // 승인 전에는 주지 않는다
    recipientGrants.run();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM coupon WHERE promotion_id = ?", Integer.class, id))
        .isZero();

    Res sub = send("POST", MKT, "/promotions/" + id + "/submissions", null);
    assertThat(sub.status()).as(sub.body().toString()).isEqualTo(200);
    assertThat(sub.body().at("/approvalRequest/summary").asString()).contains("받을 고객 2명");
    assertThat(sub.body().at("/approvalRequest/lines/0/attributes/recipientCount").asInt())
        .isEqualTo(2);
    decide(
        "AP-" + UUID.randomUUID(),
        id,
        "APPROVED",
        "ACTIVATE",
        sub.body().path("draftHash").asString());
    await(
        () -> send("GET", MKT, "/promotions/" + id, null),
        r -> "APPROVED".equals(r.body().path("state").asString()));

    assertThat(recipientGrants.run()).isGreaterThanOrEqualTo(2);
    JsonNode results = send("GET", MKT, "/promotions/" + id, null).body().path("recipientResults");
    assertThat(results.path(0).path("accountId").asString()).isEqualTo(a);
    assertThat(results.path(0).path("result").asString()).isEqualTo("ISSUED");
    assertThat(results.path(0).path("couponId").asString()).startsWith("CPN-");
    assertThat(results.path(1).path("result").asString()).as("총 수량 1").isEqualTo("SOLD_OUT");
    Map<String, Object> issued =
        jdbc.queryForMap(
            "SELECT issued_by, issue_channel, issue_reason FROM coupon WHERE promotion_id = ?", id);
    assertThat(issued.get("issued_by")).as("만든 직원").isEqualTo("mkt-1");
    assertThat(issued.get("issue_channel")).isEqualTo("ADMIN");
    assertThat(issued.get("issue_reason")).isEqualTo("배송 지연 보상");
    // 다시 돌아도 같은 고객에게 또 주지 않는다
    recipientGrants.run();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM coupon WHERE promotion_id = ?", Integer.class, id))
        .isEqualTo(1);
  }

  /** 0.3.1 — 쿠폰 발급 기록(고객 기준 PRM-1·행사 기준), 사용자 "쿠폰 발급 기록 조회"·"만들어". */
  @Test
  void couponRecordsByCustomerAndPromotion() throws Exception {
    String a = "CU-R-" + UUID.randomUUID().toString().substring(0, 8);
    String b = "CU-R-" + UUID.randomUUID().toString().substring(0, 8);
    Map<String, Object> policy = new LinkedHashMap<>();
    policy.put("minOrderAmount", 0);
    policy.put("perCustomerLimit", 1);
    policy.put("useDays", 7);
    Map<String, Object> dl = new LinkedHashMap<>(policy);
    dl.put("issueMode", "DOWNLOAD");
    Map<String, Object> adm = new LinkedHashMap<>(policy);
    adm.put("issueMode", "ADMIN");
    String download = approved(coupon("내려받기 천원", Map.of("type", "AMOUNT", "value", 1000), dl));
    String admin = approved(coupon("지정 이천원", Map.of("type", "AMOUNT", "value", 2000), adm));

    assertThat(
            send("POST", TestJwt.customer(a), "/coupon-campaigns/" + download + "/downloads", null)
                .status())
        .isEqualTo(201);
    Res grant =
        send(
            "POST",
            CS,
            "/promotions/" + admin + "/coupon-grants",
            Map.of("accountIds", List.of(a, b), "reason", "배송 지연 사과"));
    assertThat(grant.status()).as(grant.body().toString()).isEqualTo(200);

    // 고객 기준 — 두 행사, 받은 시각 최근 순
    Res mine = send("GET", CS, "/promotions/coupons?customerId=" + a, null);
    assertThat(mine.status()).as(mine.body().toString()).isEqualTo(200);
    JsonNode items = mine.body().path("items");
    assertThat(items).hasSize(2);
    JsonNode staff = items.get(0);
    assertThat(staff.path("promotionId").asString()).isEqualTo(admin);
    assertThat(staff.path("promotionName").asString()).isEqualTo("지정 이천원");
    assertThat(staff.path("source").asString()).isEqualTo("STAFF");
    assertThat(staff.path("issuedBy").asString()).isEqualTo("cs-1");
    assertThat(staff.path("issueReason").asString()).isEqualTo("배송 지연 사과");
    assertThat(staff.path("status").asString()).isEqualTo("AVAILABLE");
    assertThat(staff.path("discount").path("value").asInt()).isEqualTo(2000);
    JsonNode down = items.get(1);
    assertThat(down.path("source").asString()).isEqualTo("DOWNLOAD");
    assertThat(down.path("issuedBy").isString()).isFalse();

    // 거르기 — 받은 경로·상태(기한 지난 쿠폰은 EXPIRED)
    assertThat(
            send("GET", CS, "/promotions/coupons?customerId=" + a + "&source=DOWNLOAD", null)
                .body()
                .path("items"))
        .hasSize(1);
    jdbc.update(
        "UPDATE coupon SET valid_until = now() - interval '1 minute' WHERE promotion_id = ?"
            + " AND account_id = ?",
        download,
        a);
    JsonNode expired =
        send("GET", CS, "/promotions/coupons?customerId=" + a + "&status=EXPIRED", null)
            .body()
            .path("items");
    assertThat(expired).hasSize(1);
    assertThat(expired.get(0).path("promotionId").asString()).isEqualTo(download);

    // 행사 기준 — 그 행사에서 나간 쿠폰, 쪽 넘김
    Res p1 = send("GET", MKT, "/promotions/coupons?promotionId=" + admin + "&size=1", null);
    assertThat(p1.body().path("items")).hasSize(1);
    String next = p1.body().path("page").path("nextCursor").asString();
    Res p2 =
        send(
            "GET",
            MKT,
            "/promotions/coupons?promotionId=" + admin + "&size=1&cursor=" + next,
            null);
    assertThat(p2.body().path("items")).hasSize(1);
    assertThat(p2.body().path("items").get(0).path("couponId").asString())
        .isNotEqualTo(p1.body().path("items").get(0).path("couponId").asString());
    assertThat(p2.body().path("page").path("nextCursor").isString()).isFalse();

    // 거르기 없음 400, 권한 없음 403
    assertThat(send("GET", CS, "/promotions/coupons", null).status()).isEqualTo(400);
    assertThat(send("GET", MD, "/promotions/coupons?customerId=" + a, null).status())
        .isEqualTo(403);
    assertThat(
            send("GET", TestJwt.customer(a), "/promotions/coupons?customerId=" + a, null).status())
        .isEqualTo(403);
  }
}
