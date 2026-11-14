package com.pmart.order;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import com.pmart.order.domain.OrderRules;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 주문 초안: 정본 재확인(계정·배송지·판매 상태·상한·가격·중량) → 금액(배달팁·봉투·최소주문·첫 구매) → 차단 사유 → 10분 고정, 배송 방식 안내. 실제
 * PostgreSQL, customer·product·pricing·center-master·capacity·auth 는 WireMock 하나.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.security.service-token.client-secret=it-secret",
      "pmart.messaging.outbox.delete-after-insert=false",
      "pmart.security.service-token.token-uri=${pmart.order.customer-base-url}/api/v1/internal/service-tokens"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock(
    @ConfigureWireMock(
        name = "deps",
        baseUrlProperties = {
          "pmart.order.customer-base-url",
          "pmart.order.product-base-url",
          "pmart.order.pricing-base-url",
          "pmart.order.center-master-base-url",
          "pmart.order.capacity-base-url",
          "pmart.order.orchestration-base-url",
          "pmart.order.content-base-url"
        }))
class DraftFlowIT extends PostgresKafkaContainerSupport {

  static final String FIRST = "CU-FIRST";
  static final String REPEAT = "CU-REPEAT";
  static final String TOMORROW = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1).toString();

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired org.springframework.kafka.core.KafkaTemplate<String, String> kafka;

  @InjectWireMock("deps")
  WireMockServer deps;

  RestClient http;

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    deps.resetAll();
    stub(
        WireMock.post(urlPathEqualTo("/api/v1/internal/service-tokens")),
        Map.of(
            "access_token",
            TestJwt.service("order-service"),
            "token_type",
            "Bearer",
            "expires_in",
            300));
    for (String acc : List.of(FIRST, REPEAT)) {
      stub(
          WireMock.get(urlPathEqualTo("/api/v1/internal/accounts/" + acc + "/status")),
          Map.of("accountId", acc, "status", "ACTIVE", "purchasable", true));
      stub(
          WireMock.get(
              urlPathEqualTo("/api/v1/internal/accounts/" + acc + "/first-purchase-checks")),
          Map.of(
              "accountId", acc, "firstPurchaseCandidate", acc.equals(FIRST), "matchedBy", "NONE"));
    }
    shoppingContext("ADR-IN", "SERVICEABLE", "MFC-1");
    shoppingContext("ADR-OUT", "OUT_OF_SERVICE_AREA", null);
    // 상품명·옵션 라벨 사본(BR-35) — E-00000001 만 이름이 있다(그 밖은 404 → 이름 없음)
    stub(
        WireMock.get(urlPathEqualTo("/api/v1/internal/product-contents/PRD-E-00000001/snapshot")),
        Map.of(
            "productId", "PRD-E-00000001",
            "sku", "E-00000001",
            "name", "국산콩 두부 300g",
            "optionLabel", "300g",
            "contentVersion", 1));
    stub(
        WireMock.get(urlPathEqualTo("/api/v1/internal/skus")),
        Map.of(
            "items",
            List.of(
                sku("E-00000001", "ON_SALE", 320),
                sku("E-00000002", "TEMPORARILY_SOLD_OUT", 300),
                sku("E-00000003", "ON_SALE", 500),
                sku("E-00000009", "ON_SALE", 12_000)),
            "missing",
            List.of()));
    deps.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/purchase-limit-checks"))
            .atPriority(5)
            .willReturn(json(Map.of("passed", true, "violations", List.of()))));
    deps.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/purchase-limit-checks"))
            .atPriority(1)
            .withRequestBody(matchingJsonPath("$.items[?(@.quantity > 10)]"))
            .willReturn(
                json(
                    Map.of(
                        "passed",
                        false,
                        "violations",
                        List.of(
                            Map.of(
                                "baseProductId", "BP-1", "totalUnits", 11, "limitUnits", 10))))));
    quote(2, 1_900);
    quote(10, 1_900);
    deps.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/quotes"))
            .atPriority(1)
            .withRequestBody(matchingJsonPath("$.items[?(@.sku == 'E-00000003')]"))
            .willReturn(aResponse().withStatus(422)));
    // 고객 주문 운영 00:00~24:00 — 한집·알뜰 가능 여부는 심야(00~08)만 가른다
    deps.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/centers/MFC-1/calendar"))
            .willReturn(
                json(
                    Map.of(
                        "open",
                        true,
                        "calendarVersion",
                        1,
                        "ranges",
                        List.of(Map.of("start", "00:00", "end", "24:00"))))));
    deps.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/slots"))
            .withQueryParam("mfcId", equalTo("MFC-1"))
            .willReturn(
                json(
                    Map.of(
                        "slots",
                        List.of(
                            slot("10:00", "11:00", "AVAILABLE"),
                            slot("11:00", "12:00", "FULL"))))));
  }

  void shoppingContext(String addressId, String state, String mfcId) {
    shoppingContext(addressId, state, mfcId, "101동 1001호", "01012345678");
  }

  /** customer 배송지(주문 당시 사본 BR-37 의 원천). detail·phone 을 바꿔 "주소록 수정"을 흉내 낸다. */
  void shoppingContext(
      String addressId, String state, String mfcId, String detailAddress, String receiverPhone) {
    Map<String, Object> svc = new LinkedHashMap<>();
    svc.put("state", state);
    if (mfcId != null) {
      svc.put("mfcId", mfcId);
    }
    Map<String, Object> address = new LinkedHashMap<>();
    address.put("addressId", addressId);
    address.put("roadAddress", "부산광역시 부산진구 중앙대로 708");
    address.put("detailAddress", detailAddress);
    address.put("postalCode", "47296");
    address.put("receiverName", "홍길동");
    address.put("receiverPhone", receiverPhone);
    address.put("entranceMethod", "PASSWORD");
    address.put("entranceNote", "#1234");
    address.put("geo", Map.of("lat", 35.1577, "lng", 129.0599));
    address.put("serviceability", svc);
    for (String acc : List.of(FIRST, REPEAT)) {
      deps.stubFor(
          WireMock.get(urlPathEqualTo("/api/v1/internal/accounts/" + acc + "/shopping-context"))
              .withQueryParam("shoppingAddressId", equalTo(addressId))
              .willReturn(json(Map.of("accountId", acc, "address", address))));
    }
  }

  void quote(int qty, long unit) {
    deps.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/quotes"))
            .atPriority(3)
            .withRequestBody(
                matchingJsonPath("$.items[?(@.sku == 'E-00000001' && @.quantity == " + qty + ")]"))
            .willReturn(
                json(
                    Map.of(
                        "quoteId",
                        "Q-" + qty,
                        "priceVersion",
                        4,
                        "itemAmount",
                        qty * unit,
                        "lines",
                        List.of(
                            Map.of(
                                "sku",
                                "E-00000001",
                                "quantity",
                                qty,
                                "unitPrice",
                                unit,
                                "lineAmount",
                                qty * unit)),
                        "calculatedAt",
                        Instant.now().toString()))));
  }

  static Map<String, Object> sku(String sku, String state, int weight) {
    return Map.of(
        "sku",
        sku,
        "productId",
        "PRD-" + sku,
        "baseProductId",
        "BP-" + sku,
        "unitType",
        "E",
        "unitsPerSku",
        1,
        "weightG",
        weight,
        "saleState",
        state,
        "version",
        1);
  }

  static Map<String, Object> slot(String start, String end, String availability) {
    return Map.of(
        "window",
        Map.of("date", TOMORROW, "start", start, "end", end),
        "availability",
        availability);
  }

  void stub(MappingBuilder b, Object body) {
    deps.stubFor(b.willReturn(json(body)));
  }

  com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(Object body) {
    return aResponse()
        .withHeader("Content-Type", "application/json")
        .withBody(mapper.writeValueAsString(body));
  }

  record Res(int status, JsonNode body) {}

  Res create(String token, Map<String, Object> body) {
    var e =
        http.post()
            .uri("/order-drafts")
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", UUID.randomUUID().toString());
                  h.set("X-Source-System", "customer-app");
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  Res get(String token, String path) {
    var e =
        http.get().uri(path).headers(h -> h.setBearerAuth(token)).retrieve().toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  static Map<String, Object> draft(
      String intent, String sku, int qty, String address, String mode, Map<String, Object> window) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("purchaseIntentId", intent);
    m.put("items", List.of(Map.of("cartItemId", "CI-1", "sku", sku, "quantity", qty)));
    m.put("shoppingAddressId", address);
    m.put("deliveryMode", mode);
    if (window != null) {
      m.put("arrivalWindow", window);
    }
    m.put("outOfStockPreference", "CANCEL_OOS_ONLY");
    m.put("deliveryNote", "문 앞");
    return m;
  }

  static Map<String, Object> window(String start, String end) {
    return Map.of("date", TOMORROW, "start", start, "end", end);
  }

  @Test
  void draftQuoteBlockersAndOptions() {
    String first = TestJwt.customer(FIRST);
    String repeat = TestJwt.customer(REPEAT);

    // 첫 구매 회원, 예약 내일 10~11시, 두부 2개(3,800원) → 최소주문 미달 차단, 첫 구매 무료 예상
    String intent = "PI-" + UUID.randomUUID();
    Res d =
        create(
            first, draft(intent, "E-00000001", 2, "ADR-IN", "SCHEDULED", window("10:00", "11:00")));
    assertThat(d.status()).as(d.body().toString()).isEqualTo(201);
    JsonNode q = d.body().path("quote");
    assertThat(q.path("itemAmount").asInt()).isEqualTo(3_800);
    assertThat(q.path("deliveryTipBase").asInt()).isEqualTo(3_000);
    assertThat(q.path("deliveryTip").asInt()).isZero();
    assertThat(q.path("deliveryTipReason").asString()).isEqualTo("FIRST_PURCHASE");
    assertThat(q.path("firstPurchaseBenefit").asString()).isEqualTo("EXPECTED");
    assertThat(q.path("bagFee").asInt()).isEqualTo(100);
    assertThat(q.path("totalAmount").asInt()).isEqualTo(3_900);
    assertThat(q.path("minOrderSatisfied").asBoolean()).isFalse();
    assertThat(q.path("minOrderShortfall").asInt()).isEqualTo(11_200);
    assertThat(q.path("weightG").asInt()).isEqualTo(640);
    assertThat(q.path("plannedShipments").asInt()).isEqualTo(1);
    assertThat(q.path("priceVersion").asInt()).isEqualTo(4);
    assertThat(
            Duration.between(
                    Instant.now(),
                    OffsetDateTime.parse(q.path("quoteExpiresAt").asString()).toInstant())
                .toMinutes())
        .isBetween(8L, 10L);
    assertThat(d.body().path("blockers").path(0).path("code").asString())
        .isEqualTo("MIN_ORDER_AMOUNT");
    assertThat(d.body().path("blockers")).hasSize(1);
    assertThat(d.body().path("lines").path(0).path("unitPrice").asInt()).isEqualTo(1_900);
    assertThat(d.body().path("delivery").path("arrivalWindow").path("start").asString())
        .isEqualTo("10:00");
    assertThat(d.body().path("delivery").path("deliveryNote").asString()).isEqualTo("문 앞");
    assertThat(d.body().path("status").asString()).isEqualTo("DRAFT");
    assertThat(d.body().path("nextAction").asString()).isEqualTo("REVIEW");
    assertThat(d.body().path("mfcId").asString()).isEqualTo("MFC-1");
    String draftId = d.body().path("draftId").asString();
    // 같은 구매 의도 → 같은 초안
    assertThat(
            create(
                    first,
                    draft(intent, "E-00000001", 2, "ADR-IN", "SCHEDULED", window("10:00", "11:00")))
                .body()
                .path("draftId")
                .asString())
        .isEqualTo(draftId);
    assertThat(get(first, "/order-drafts/" + draftId).body().path("draftId").asString())
        .isEqualTo(draftId);
    assertThat(get(repeat, "/order-drafts/" + draftId).status()).as("남의 초안").isEqualTo(404);

    // 재구매 회원, 알뜰, 10개(19,000원) → 배달팁 3,000, 최소주문 충족. 한집·알뜰은 심야면 차단
    Res r =
        create(
            repeat, draft("PI-" + UUID.randomUUID(), "E-00000001", 10, "ADR-IN", "BUNDLED", null));
    assertThat(r.status()).isEqualTo(201);
    assertThat(r.body().path("quote").path("deliveryTip").asInt()).isEqualTo(3_000);
    assertThat(r.body().path("quote").path("deliveryTipReason").asString()).isEqualTo("STANDARD");
    assertThat(r.body().path("quote").path("firstPurchaseBenefit").asString())
        .isEqualTo("NOT_ELIGIBLE");
    assertThat(r.body().path("quote").path("totalAmount").asInt()).isEqualTo(22_100);
    if (OrderRules.nightTime(Instant.now())) {
      assertThat(r.body().path("blockers").path(0).path("detail").asString())
          .isEqualTo("NIGHT_TIME");
    } else {
      assertThat(r.body().path("blockers")).isEmpty();
    }

    // 예약: 시간 미선택·마감 슬롯·제공 안 하는 시간대(07시)
    assertThat(
            codes(
                create(
                    repeat,
                    draft(
                        "PI-" + UUID.randomUUID(), "E-00000001", 10, "ADR-IN", "SCHEDULED", null))))
        .contains("SLOT_REQUIRED");
    Res full =
        create(
            repeat,
            draft(
                "PI-" + UUID.randomUUID(),
                "E-00000001",
                10,
                "ADR-IN",
                "SCHEDULED",
                window("11:00", "12:00")));
    assertThat(full.body().path("blockers").path(0).path("detail").asString()).isEqualTo("FULL");
    Res early =
        create(
            repeat,
            draft(
                "PI-" + UUID.randomUUID(),
                "E-00000001",
                10,
                "ADR-IN",
                "SCHEDULED",
                window("07:00", "08:00")));
    assertThat(early.body().path("blockers").path(0).path("detail").asString())
        .isEqualTo("NOT_OFFERED");

    // 거절
    assertThat(
            code(
                create(
                    repeat,
                    draft("PI-" + UUID.randomUUID(), "E-00000002", 1, "ADR-IN", "SINGLE", null))))
        .isEqualTo("SKU_NOT_SALEABLE");
    assertThat(
            code(
                create(
                    repeat,
                    draft("PI-" + UUID.randomUUID(), "E-00000001", 11, "ADR-IN", "SINGLE", null))))
        .isEqualTo("QUANTITY_LIMIT_EXCEEDED");
    assertThat(
            code(
                create(
                    repeat,
                    draft("PI-" + UUID.randomUUID(), "E-00000001", 2, "ADR-OUT", "SINGLE", null))))
        .isEqualTo("OUT_OF_SERVICE_AREA");
    assertThat(
            code(
                create(
                    repeat,
                    draft("PI-" + UUID.randomUUID(), "E-00000003", 1, "ADR-IN", "SINGLE", null))))
        .isEqualTo("PRICE_NOT_SET");
    assertThat(
            code(
                create(
                    repeat,
                    draft("PI-" + UUID.randomUUID(), "E-00000009", 1, "ADR-IN", "SINGLE", null))))
        .isEqualTo("WEIGHT_UNSPLITTABLE");
    // 비회원·운영자는 주문 초안 불가
    String guest = TestJwt.token("GST-1", "GUEST", List.of(), List.of(), "it");
    assertThat(create(guest, draft("PI-x", "E-00000001", 2, "ADR-IN", "SINGLE", null)).status())
        .isEqualTo(403);

    // 배송 방식 안내(비회원): 예상시간 미설정, 기본 배달팁, 30,000 이상이면 무료, 가장 빠른 예약 슬롯
    Res o = get(guest, "/delivery-options?mfcId=MFC-1&itemAmount=31000");
    assertThat(o.status()).isEqualTo(200);
    JsonNode single = o.body().path("options").path(0);
    assertThat(single.path("mode").asString()).isEqualTo("SINGLE");
    assertThat(single.path("eta").path("state").asString()).isEqualTo("NOT_SET");
    assertThat(single.path("deliveryTip").path("base").asInt()).isEqualTo(4_000);
    assertThat(single.path("deliveryTip").path("applied").asInt()).isZero();
    assertThat(single.path("deliveryTip").path("reason").asString()).isEqualTo("OVER_30000");
    assertThat(single.path("availability").asString())
        .isEqualTo(OrderRules.nightTime(Instant.now()) ? "UNAVAILABLE" : "AVAILABLE");
    JsonNode scheduled = o.body().path("options").path(2);
    assertThat(scheduled.path("availability").asString()).isEqualTo("AVAILABLE");
    assertThat(scheduled.path("earliestSlot").path("start").asString()).isEqualTo("10:00");
    // 첫 구매 회원 + 금액 → 무료 예상, 금액 없으면 적용액 없음
    assertThat(
            get(first, "/delivery-options?mfcId=MFC-1&itemAmount=5000")
                .body()
                .path("options")
                .path(1)
                .path("deliveryTip")
                .path("reason")
                .asString())
        .isEqualTo("FIRST_PURCHASE");
    assertThat(
            get(repeat, "/delivery-options?mfcId=MFC-1")
                .body()
                .path("options")
                .path(1)
                .path("deliveryTip")
                .path("applied")
                .isNull())
        .isTrue();
  }

  Res checkout(String token, String draftId, String key, int quoteVersion) {
    var e =
        http.post()
            .uri("/order-drafts/{id}/checkouts", draftId)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", key);
                  h.set("X-Source-System", "customer-app");
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("acceptedQuoteVersion", quoteVersion, "paymentMethod", "TOSS_PAYMENTS"))
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  void saga(String sagaId, Map<String, Object> body) {
    deps.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/sagas/" + sagaId)).willReturn(json(body)));
  }

  static Map<String, Object> step(String step, String state) {
    return Map.of("step", step, "state", state);
  }

  /** 결제 진입: 견적·차단 사유 확인 → 사가 시작(202) → 사가 사본으로 진행·결제창 정보·실패 반영. 사가는 WireMock. */
  @Test
  void checkoutStartsSagaAndReflectsProgress() {
    String repeat = TestJwt.customer(REPEAT);
    // 재구매 회원, 예약 내일 10~11시, 10개(19,000원) → 차단 사유 없음
    Res d =
        create(
            repeat,
            draft(
                "PI-" + UUID.randomUUID(),
                "E-00000001",
                10,
                "ADR-IN",
                "SCHEDULED",
                window("10:00", "11:00")));
    assertThat(d.body().path("blockers")).as(d.body().toString()).isEmpty();
    String draftId = d.body().path("draftId").asString();
    int quoteVersion = d.body().path("quote").path("quoteVersion").asInt();

    // 초안 뒤 계정이 구매 불가로 바뀌면 결제 진입을 거절한다(사가를 시작하지 않음) — BR-37 질문 4
    stub(
        WireMock.get(urlPathEqualTo("/api/v1/internal/accounts/" + REPEAT + "/status")),
        Map.of("accountId", REPEAT, "status", "WITHDRAW_PENDING", "purchasable", false));
    Res notPurchasable = checkout(repeat, draftId, UUID.randomUUID().toString(), quoteVersion);
    assertThat(notPurchasable.status()).as(notPurchasable.body().toString()).isEqualTo(422);
    assertThat(notPurchasable.body().path("code").asString()).isEqualTo("ACCOUNT_NOT_PURCHASABLE");
    deps.verify(0, WireMock.postRequestedFor(urlPathEqualTo("/api/v1/internal/checkout-sagas")));
    stub(
        WireMock.get(urlPathEqualTo("/api/v1/internal/accounts/" + REPEAT + "/status")),
        Map.of("accountId", REPEAT, "status", "ACTIVE", "purchasable", true));

    String sagaId = "SAGA-" + UUID.randomUUID();
    deps.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/checkout-sagas"))
            .willReturn(
                aResponse()
                    .withStatus(202)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        mapper.writeValueAsString(
                            Map.of("status", "ACCEPTED", "requestId", "k", "resultId", sagaId)))));
    saga(
        sagaId,
        Map.of(
            "sagaId",
            sagaId,
            "state",
            "WAITING_RESULT",
            "currentStep",
            "AWAIT_PAYMENT_RESULT",
            "steps",
            List.of(
                step("RESERVE_INVENTORY", "SUCCEEDED"),
                step("HOLD_CAPACITY", "SUCCEEDED"),
                step("HOLD_SLOT", "SUCCEEDED"),
                step("START_PAYMENT", "SUCCEEDED")),
            "timers",
            Map.of("reservationExpiresAt", "2026-10-31T01:10:00Z"),
            "payment",
            Map.of(
                "paymentAttemptId",
                "PAY-1",
                "amount",
                22_100,
                "pgOrderId",
                "PAY-1",
                "clientKey",
                "test_ck_docs",
                "expiresAt",
                "2026-10-31T01:09:00Z")));

    // 견적 버전이 다르면 다시 견적
    assertThat(code(checkout(repeat, draftId, UUID.randomUUID().toString(), quoteVersion + 1)))
        .isEqualTo("RE_QUOTE_REQUIRED");

    String key = UUID.randomUUID().toString();
    Res c = checkout(repeat, draftId, key, quoteVersion);
    assertThat(c.status()).as(c.body().toString()).isEqualTo(202);
    String orderId = c.body().path("resultId").asString();
    assertThat(orderId).startsWith("ORD-");
    // 같은 키 재전송, 진행 중 다른 키 → 같은 주문(새 사가 없음)
    assertThat(checkout(repeat, draftId, key, quoteVersion).body().path("resultId").asString())
        .isEqualTo(orderId);
    assertThat(
            checkout(repeat, draftId, UUID.randomUUID().toString(), quoteVersion)
                .body()
                .path("resultId")
                .asString())
        .isEqualTo(orderId);
    deps.verify(
        1,
        WireMock.postRequestedFor(urlPathEqualTo("/api/v1/internal/checkout-sagas"))
            .withRequestBody(matchingJsonPath("$.orderId", equalTo(orderId)))
            .withRequestBody(matchingJsonPath("$.customerRef", equalTo(REPEAT)))
            .withRequestBody(matchingJsonPath("$.amount", equalTo("22100")))
            .withRequestBody(matchingJsonPath("$.arrivalWindow.start", equalTo("10:00")))
            .withRequestBody(matchingJsonPath("$.lines[0].quantity", equalTo("10")))
            // 결제창 주문명 = 상품명 사본(BR-35). 한 품목이라 "외 N건" 없음
            .withRequestBody(matchingJsonPath("$.orderName", equalTo("국산콩 두부 300g"))));

    JsonNode g = get(repeat, "/order-drafts/" + draftId).body();
    assertThat(g.path("status").asString()).isEqualTo("PAYMENT_PENDING");
    assertThat(g.path("nextAction").asString()).isEqualTo("PAY");
    assertThat(g.path("orderId").asString()).isEqualTo(orderId);
    assertThat(g.path("payment").path("clientKey").asString()).isEqualTo("test_ck_docs");
    assertThat(g.path("payment").path("pgOrderId").asString()).isEqualTo("PAY-1");
    assertThat(g.path("resources").path("inventoryReservation").asString()).isEqualTo("HELD");
    assertThat(g.path("resources").path("slotHold").asString()).isEqualTo("HELD");
    assertThat(g.path("resources").path("payment").asString()).isEqualTo("STARTED");

    // 사가가 재고 부족으로 보상 종료 → 초안은 다시 검토, 실패 사유 표시
    saga(
        sagaId,
        Map.of(
            "sagaId",
            sagaId,
            "state",
            "COMPENSATED",
            "currentStep",
            "RESERVE_INVENTORY",
            "steps",
            List.of(step("RESERVE_INVENTORY", "FAILED")),
            "lastError",
            Map.of("step", "RESERVE_INVENTORY", "code", "INSUFFICIENT_STOCK", "message", "재고 부족")));
    JsonNode failed = get(repeat, "/order-drafts/" + draftId).body();
    assertThat(failed.path("status").asString()).isEqualTo("DRAFT");
    assertThat(failed.path("nextAction").asString()).isEqualTo("REVIEW");
    assertThat(failed.path("checkoutFailure").path("code").asString())
        .isEqualTo("INSUFFICIENT_STOCK");
    assertThat(failed.path("resources").path("inventoryReservation").asString())
        .isEqualTo("FAILED");

    // 차단 사유가 남은 초안(최소주문 미달)은 결제 진입 불가
    Res blocked =
        create(
            repeat, draft("PI-" + UUID.randomUUID(), "E-00000001", 2, "ADR-IN", "BUNDLED", null));
    assertThat(
            code(
                checkout(
                    repeat,
                    blocked.body().path("draftId").asString(),
                    UUID.randomUUID().toString(),
                    blocked.body().path("quote").path("quoteVersion").asInt())))
        .isEqualTo("CHECKOUT_BLOCKED");
  }

  static String code(Res r) {
    return r.body().path("code").asString();
  }

  static List<String> codes(Res r) {
    return r.body().path("blockers").findValuesAsString("code");
  }

  String startSaga(String sagaId) {
    deps.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/checkout-sagas"))
            .willReturn(
                aResponse()
                    .withStatus(202)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        mapper.writeValueAsString(
                            Map.of("status", "ACCEPTED", "requestId", "k", "resultId", sagaId)))));
    return sagaId;
  }

  Res confirm(String token, String orderId, String key, long amount) {
    var e =
        http.post()
            .uri("/internal/orders/{id}/confirmation", orderId)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", key);
                  h.set("X-Source-System", "order-orchestration-service");
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                Map.of(
                    "paymentAttemptId",
                    "PAY-1",
                    "pgTransactionId",
                    "tgen_1",
                    "approvedAmount",
                    amount,
                    "reservationId",
                    "RSV-1",
                    "capacityHoldId",
                    "CH-1",
                    "slotHoldId",
                    "SH-1"))
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  /**
   * 주문 확정(orchestration → order): 승인 금액 대조 → 주문 원장 + OrderConfirmed(한 번) → 주문 조회(본인만) → 초안
   * CONFIRMED/DONE. 사가 상태 COMPLETED·RECONCILIATION_REQUIRED 의 초안 표시와 새 결제 진입 차단도 확인한다.
   */
  @Test
  void confirmOrderCreatesOrderAndSingleEvent() throws Exception {
    String first = TestJwt.customer(FIRST);
    String svc = TestJwt.service("order-orchestration-service");
    // 첫 구매 회원, 예약 내일 10~11시, 10개(19,000원) → 첫 구매 무료, 총액 19,100
    Res d =
        create(
            first,
            draft(
                "PI-" + UUID.randomUUID(),
                "E-00000001",
                10,
                "ADR-IN",
                "SCHEDULED",
                window("10:00", "11:00")));
    assertThat(d.body().path("blockers")).as(d.body().toString()).isEmpty();
    assertThat(d.body().path("quote").path("deliveryTipReason").asString())
        .isEqualTo("FIRST_PURCHASE");
    long total = d.body().path("quote").path("totalAmount").asLong();
    assertThat(total).isEqualTo(19_100);
    // 상품명·옵션 라벨 사본(BR-35)
    assertThat(d.body().path("lines").path(0).path("productName").asString())
        .isEqualTo("국산콩 두부 300g");
    assertThat(d.body().path("lines").path(0).path("optionLabel").asString()).isEqualTo("300g");
    // 주문 당시 배송지 사본(BR-37): 초안을 만들 때 customer 배송지를 복사해 고정한다
    JsonNode ship = d.body().path("delivery").path("shippingAddress");
    assertThat(ship.path("roadAddress").asString()).isEqualTo("부산광역시 부산진구 중앙대로 708");
    assertThat(ship.path("detailAddress").asString()).isEqualTo("101동 1001호");
    assertThat(ship.path("receiverName").asString()).isEqualTo("홍길동");
    assertThat(ship.path("receiverPhone").asString()).isEqualTo("01012345678");
    assertThat(ship.path("entranceMethod").asString()).isEqualTo("PASSWORD");
    assertThat(ship.path("geo").path("lat").asDouble()).isEqualTo(35.1577);
    assertThat(ship.path("capturedAt").asString()).isNotBlank();
    // 고객이 주소록을 고쳐도(상세·연락처) 이 초안과 주문은 사본을 쓴다
    shoppingContext("ADR-IN", "SERVICEABLE", "MFC-1", "202동 2002호", "01099998888");
    String draftId = d.body().path("draftId").asString();
    int quoteVersion = d.body().path("quote").path("quoteVersion").asInt();
    String sagaId = startSaga("SAGA-" + UUID.randomUUID());
    Res c = checkout(first, draftId, UUID.randomUUID().toString(), quoteVersion);
    assertThat(c.status()).as(c.body().toString()).isEqualTo(202);
    String orderId = c.body().path("resultId").asString();

    // 승인됐지만 대사 필요 → 결제 확인 중(WAIT), 새 결제 진입은 같은 주문 ID(사가를 새로 만들지 않음)
    saga(
        sagaId,
        Map.of(
            "sagaId",
            sagaId,
            "state",
            "RECONCILIATION_REQUIRED",
            "currentStep",
            "CONFIRM_ORDER",
            "steps",
            List.of(step("RESERVE_INVENTORY", "SUCCEEDED"), step("START_PAYMENT", "SUCCEEDED")),
            "payment",
            Map.of("paymentAttemptId", "PAY-1", "state", "APPROVED")));
    JsonNode recon = get(first, "/order-drafts/" + draftId).body();
    assertThat(recon.path("status").asString()).isEqualTo("PAYMENT_UNKNOWN");
    assertThat(recon.path("nextAction").asString()).isEqualTo("WAIT");
    assertThat(recon.path("resources").path("payment").asString()).isEqualTo("APPROVED");
    assertThat(
            checkout(first, draftId, UUID.randomUUID().toString(), quoteVersion)
                .body()
                .path("resultId")
                .asString())
        .isEqualTo(orderId);
    deps.verify(1, WireMock.postRequestedFor(urlPathEqualTo("/api/v1/internal/checkout-sagas")));

    // 확정 전에는 주문 조회 대상이 아니다
    assertThat(get(first, "/orders/" + orderId).status()).isEqualTo(404);
    // 고객 토큰으로 내부 확정 불가, 모르는 주문 404, 금액 불일치 422(확정 안 함)
    assertThat(confirm(first, orderId, UUID.randomUUID().toString(), total).status())
        .isEqualTo(403);
    assertThat(confirm(svc, "ORD-NOPE", UUID.randomUUID().toString(), total).status())
        .isEqualTo(404);
    Res mismatch = confirm(svc, orderId, UUID.randomUUID().toString(), total - 100);
    assertThat(mismatch.status()).isEqualTo(422);
    assertThat(code(mismatch)).isEqualTo("AMOUNT_MISMATCH");
    assertThat(get(first, "/orders/" + orderId).status()).isEqualTo(404);

    // 확정 → 같은 키 재전송·다른 키 모두 200, 사건은 한 번
    String key = sagaId + ":CONFIRM_ORDER";
    Res ok = confirm(svc, orderId, key, total);
    assertThat(ok.status()).as(ok.body().toString()).isEqualTo(200);
    assertThat(ok.body().path("status").asString()).isEqualTo("APPLIED");
    assertThat(ok.body().path("resultId").asString()).isEqualTo(orderId);
    assertThat(confirm(svc, orderId, key, total).status()).isEqualTo(200);
    Res other = confirm(svc, orderId, UUID.randomUUID().toString(), total);
    assertThat(other.status()).isEqualTo(200);
    assertThat(other.body().path("resultId").asString()).isEqualTo(orderId);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE type = 'OrderConfirmed' AND aggregateid = ?",
                Integer.class,
                orderId))
        .isEqualTo(1);
    Map<String, Object> outbox =
        jdbc.queryForMap(
            "SELECT topic, correlation_id, payload::text AS payload FROM outbox_event"
                + " WHERE type = 'OrderConfirmed' AND aggregateid = ?",
            orderId);
    assertThat(outbox.get("topic")).isEqualTo("order.order.v1");
    assertThat(outbox.get("correlation_id")).isEqualTo(sagaId);
    JsonNode p = mapper.readTree((String) outbox.get("payload")).path("payload");
    assertThat(p.path("accountId").asString()).isEqualTo(FIRST);
    assertThat(p.path("firstPurchase").asBoolean()).isTrue();
    assertThat(p.path("totalAmount").asLong()).isEqualTo(total);
    assertThat(p.path("lines").path(0).path("orderLineId").asString()).isEqualTo(orderId + "-L1");
    assertThat(p.path("lines").path(0).path("cartItemId").asString()).isEqualTo("CI-1");
    assertThat(p.path("lines").path(0).path("unitsPerSku").asInt()).isEqualTo(1);
    assertThat(p.path("slotHoldId").asString()).isEqualTo("SH-1");
    assertThat(p.path("arrivalWindow").path("start").asString()).endsWith("T01:00:00Z");
    // 이행 계획(슬라이스 6a D6-2): 주문당 출고 1개, 모든 행
    assertThat(p.path("shipments")).hasSize(1);
    assertThat(p.path("shipments").path(0).path("shipmentId").asString())
        .isEqualTo(orderId + "-S1");
    assertThat(p.path("shipments").path(0).path("lines").path(0).path("orderLineId").asString())
        .isEqualTo(orderId + "-L1");
    assertThat(p.path("shipments").path(0).path("lines").size()).isEqualTo(p.path("lines").size());
    assertThat(p.path("lines").path(0).path("productName").asString()).isEqualTo("국산콩 두부 300g");

    // 주문 조회: 본인 200, 남 404
    Res mine = get(first, "/orders/" + orderId);
    assertThat(mine.status()).as(mine.body().toString()).isEqualTo(200);
    JsonNode o = mine.body();
    assertThat(o.path("status").asString()).isEqualTo("CONFIRMED");
    assertThat(o.path("version").asInt()).isEqualTo(1);
    assertThat(o.path("deliveryMode").asString()).isEqualTo("SCHEDULED");
    assertThat(o.path("arrivalWindow").path("start").asString()).isEqualTo("10:00");
    assertThat(o.path("quote").path("totalAmount").asLong()).isEqualTo(total);
    assertThat(o.path("quote").path("firstPurchaseBenefit").asString()).isEqualTo("CONFIRMED");
    assertThat(o.path("lines").path(0).path("orderLineId").asString()).isEqualTo(orderId + "-L1");
    assertThat(o.path("lines").path(0).path("originalQty").asInt()).isEqualTo(10);
    assertThat(o.path("lines").path(0).path("productName").asString()).isEqualTo("국산콩 두부 300g");

    // MFC 준비 시작(PreparationStarted) → PREPARING·OrderPreparationStarted, 다시 와도 한 번(슬라이스 6b)
    String prep =
        com.pmart.common.test.EventEnvelopeFixtures.envelope("PreparationStarted")
            .from("mfc-fulfillment-service")
            .aggregate("Fulfillment", orderId, 1)
            .payload(
                Map.of(
                    "orderId",
                    orderId,
                    "fulfillmentId",
                    "FUL-1",
                    "startedAt",
                    "2026-11-07T01:00:00Z"))
            .buildJson();
    kafka.send("mfc.fulfillment.v1", orderId, prep).join();
    kafka.send("mfc.fulfillment.v1", orderId, prep).join();
    long until = System.currentTimeMillis() + 20_000;
    while (System.currentTimeMillis() < until
        && !"PREPARING".equals(get(first, "/orders/" + orderId).body().path("status").asString())) {
      Thread.sleep(200);
    }
    assertThat(get(first, "/orders/" + orderId).body().path("status").asString())
        .isEqualTo("PREPARING");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE type = 'OrderPreparationStarted'"
                    + " AND aggregateid = ?",
                Integer.class,
                orderId))
        .isEqualTo(1);
    assertThat(o.path("lines").path(0).path("optionLabel").asString()).isEqualTo("300g");
    assertThat(o.path("lines").path(0).path("unfulfilledQty").asInt()).isEqualTo(10);
    assertThat(o.path("lines").path(0).path("deliveredQty").asInt()).isZero();
    assertThat(o.path("payment").path("paymentAttemptId").asString()).isEqualTo("PAY-1");
    // 주문 당시 배송지(BR-37): 초안 이후 주소록을 고쳤어도 주문은 초안 때 사본 그대로
    JsonNode os = o.path("shippingAddress");
    assertThat(os.path("detailAddress").asString()).isEqualTo("101동 1001호");
    assertThat(os.path("receiverPhone").asString()).isEqualTo("01012345678");
    assertThat(os.path("entranceNote").asString()).isEqualTo("#1234");
    // 개인정보는 사건에 싣지 않는다(ADR-0009)
    assertThat(p.toString()).doesNotContain("01012345678").doesNotContain("홍길동");
    String repeat = TestJwt.customer(REPEAT);
    assertThat(get(repeat, "/orders/" + orderId).status()).isEqualTo(404);
    JsonNode list = get(first, "/orders?size=1").body();
    assertThat(list.path("items").path(0).path("orderId").asString()).isEqualTo(orderId);
    assertThat(list.path("items").path(0).path("itemCount").asInt()).isEqualTo(10);
    assertThat(get(repeat, "/orders").body().path("items").findValuesAsString("orderId"))
        .doesNotContain(orderId);

    // 초안: 확정 주문이 사가 사본보다 우선(CONFIRMED/DONE), 다시 결제 진입해도 같은 주문
    saga(sagaId, Map.of("sagaId", sagaId, "state", "COMPENSATED", "steps", List.of()));
    JsonNode done = get(first, "/order-drafts/" + draftId).body();
    assertThat(done.path("status").asString()).isEqualTo("CONFIRMED");
    assertThat(done.path("nextAction").asString()).isEqualTo("DONE");
    assertThat(done.path("orderId").asString()).isEqualTo(orderId);
    assertThat(done.path("resources").path("payment").asString()).isEqualTo("APPROVED");
    assertThat(
            checkout(first, draftId, UUID.randomUUID().toString(), quoteVersion)
                .body()
                .path("resultId")
                .asString())
        .isEqualTo(orderId);
    deps.verify(1, WireMock.postRequestedFor(urlPathEqualTo("/api/v1/internal/checkout-sagas")));

    // 사가 COMPLETED(확정 주문이 아직 안 보여도) → CONFIRMED/DONE, 새 결제 진입 없음
    Res d2 =
        create(
            repeat,
            draft(
                "PI-" + UUID.randomUUID(),
                "E-00000001",
                10,
                "ADR-IN",
                "SCHEDULED",
                window("10:00", "11:00")));
    String saga2 = startSaga("SAGA-" + UUID.randomUUID());
    String draft2 = d2.body().path("draftId").asString();
    int qv2 = d2.body().path("quote").path("quoteVersion").asInt();
    String order2 =
        checkout(repeat, draft2, UUID.randomUUID().toString(), qv2)
            .body()
            .path("resultId")
            .asString();
    saga(saga2, Map.of("sagaId", saga2, "state", "COMPLETED", "steps", List.of()));
    JsonNode completed = get(repeat, "/order-drafts/" + draft2).body();
    assertThat(completed.path("status").asString()).isEqualTo("CONFIRMED");
    assertThat(completed.path("nextAction").asString()).isEqualTo("DONE");
    assertThat(
            checkout(repeat, draft2, UUID.randomUUID().toString(), qv2)
                .body()
                .path("resultId")
                .asString())
        .isEqualTo(order2);
    deps.verify(2, WireMock.postRequestedFor(urlPathEqualTo("/api/v1/internal/checkout-sagas")));
  }

  Res changeQuantity(String token, String orderId, String key, Map<String, Object> body) {
    var e =
        http.post()
            .uri("/internal/orders/{id}/line-quantity-changes", orderId)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", key);
                  h.set("X-Source-System", "order-orchestration-service");
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  /**
   * 출고 전 취소(S3 d): 내부 주문 조회(서비스만) → 미이행보다 많은 수량 409·지원 안 하는 사유 422(바뀌지 않음) → 전량 취소 시 CANCELLED, 행
   * 사건 OrderLineQuantityChanged·주문 사건 OrderCancelled(claimId·accountId·firstPurchase) 한 번 → 같은 키
   * 재생, 다시 취소는 409.
   */
  @Test
  void cancelBeforeShipCancelsWholeOrder() {
    String repeat = TestJwt.customer(REPEAT);
    String svc = TestJwt.service("order-orchestration-service");
    Res d =
        create(
            repeat,
            draft(
                "PI-" + UUID.randomUUID(),
                "E-00000001",
                10,
                "ADR-IN",
                "SCHEDULED",
                window("10:00", "11:00")));
    long total = d.body().path("quote").path("totalAmount").asLong();
    startSaga("SAGA-" + UUID.randomUUID());
    String orderId =
        checkout(
                repeat,
                d.body().path("draftId").asString(),
                UUID.randomUUID().toString(),
                d.body().path("quote").path("quoteVersion").asInt())
            .body()
            .path("resultId")
            .asString();
    assertThat(confirm(svc, orderId, UUID.randomUUID().toString(), total).status()).isEqualTo(200);

    // 배차용 배송지(O-4): 서비스만, 방식·예약 창, 수령인·연락처는 없다
    assertThat(get(repeat, "/internal/orders/" + orderId + "/delivery-destination").status())
        .isEqualTo(403);
    Res dest = get(svc, "/internal/orders/" + orderId + "/delivery-destination");
    assertThat(dest.status()).as(dest.body().toString()).isEqualTo(200);
    assertThat(dest.body().path("deliveryMode").asString()).isEqualTo("SCHEDULED");
    assertThat(dest.body().path("confirmedAt").isString()).isTrue();
    assertThat(dest.body().path("arrivalWindow").path("start").asString()).isEqualTo("10:00");
    assertThat(dest.body().has("receiverPhone")).isFalse();
    assertThat(dest.body().has("receiverName")).isFalse();
    // 2026-11-14: 주문 당시 사본의 도로명주소가 실려야 한다(전에는 사본을 안 읽어 늘 null — 배차가 주소 없음으로 멈춤)
    assertThat(dest.body().path("roadAddress").asString()).isEqualTo("부산광역시 부산진구 중앙대로 708");
    assertThat(get(svc, "/internal/orders/ORD-NOPE/delivery-destination").status()).isEqualTo(404);

    // 라이더용 전달지(O-5, BR-24): 서비스만, 고객 전화번호 그대로(안심번호 없음 — 사용자 2026-11-14)
    assertThat(get(repeat, "/internal/orders/" + orderId + "/drop-info").status()).isEqualTo(403);
    Res drop = get(svc, "/internal/orders/" + orderId + "/drop-info");
    assertThat(drop.status()).as(drop.body().toString()).isEqualTo(200);
    assertThat(drop.body().path("roadAddress").asString()).isEqualTo("부산광역시 부산진구 중앙대로 708");
    assertThat(drop.body().path("receiverPhone").asString()).isNotBlank();
    assertThat(drop.body().path("receiverName").asString()).isNotBlank();
    assertThat(drop.body().path("detailAddress").isString()).isTrue();
    assertThat(get(svc, "/internal/orders/ORD-NOPE/drop-info").status()).isEqualTo(404);

    // 내부 주문 조회: 서비스만, 소유자·결제·행 수량
    assertThat(get(repeat, "/internal/orders/" + orderId).status()).isEqualTo(403);
    assertThat(get(svc, "/internal/orders/ORD-NOPE").status()).isEqualTo(404);
    Res internal = get(svc, "/internal/orders/" + orderId);
    assertThat(internal.status()).as(internal.body().toString()).isEqualTo(200);
    assertThat(internal.body().path("accountId").asString()).isEqualTo(REPEAT);
    assertThat(internal.body().path("paymentAttemptId").asString()).isEqualTo("PAY-1");
    assertThat(internal.body().path("approvedAmount").asLong()).isEqualTo(total);
    assertThat(internal.body().path("status").asString()).isEqualTo("CONFIRMED");
    String line = orderId + "-L1";
    assertThat(internal.body().path("lines").path(0).path("unfulfilledQty").asInt()).isEqualTo(10);

    // 미이행보다 많이 → 409, 지원 안 하는 사유 → 422, 고객 토큰 → 403 (모두 바뀌지 않음)
    Res over =
        changeQuantity(
            svc,
            orderId,
            UUID.randomUUID().toString(),
            Map.of(
                "reason",
                "CANCEL_BEFORE_SHIP",
                "changes",
                List.of(Map.of("orderLineId", line, "qty", 11))));
    assertThat(over.status()).isEqualTo(409);
    assertThat(code(over)).isEqualTo("INVALID_STATE");
    Res shortage =
        changeQuantity(
            svc,
            orderId,
            UUID.randomUUID().toString(),
            Map.of(
                "reason", "HANDOVER", "changes", List.of(Map.of("orderLineId", line, "qty", 1))));
    assertThat(shortage.status()).isEqualTo(422);
    assertThat(code(shortage)).isEqualTo("UNSUPPORTED_ACTION");
    // 결품(SHORTAGE, ADR-0045)은 받는다 — 같은 수량 규칙(미이행 초과 409)
    Res shortageOver =
        changeQuantity(
            svc,
            orderId,
            UUID.randomUUID().toString(),
            Map.of(
                "reason", "SHORTAGE", "changes", List.of(Map.of("orderLineId", line, "qty", 11))));
    assertThat(shortageOver.status()).isEqualTo(409);
    assertThat(
            changeQuantity(
                    repeat,
                    orderId,
                    UUID.randomUUID().toString(),
                    Map.of(
                        "reason",
                        "CANCEL_BEFORE_SHIP",
                        "changes",
                        List.of(Map.of("orderLineId", line, "qty", 10))))
                .status())
        .isEqualTo(403);
    assertThat(get(svc, "/internal/orders/" + orderId).body().path("version").asInt()).isEqualTo(1);

    // 전량 취소 → CANCELLED, 같은 키 재생
    Map<String, Object> cancel =
        Map.of(
            "reason",
            "CANCEL_BEFORE_SHIP",
            "sourceRef",
            "STOP-1",
            "claimId",
            "CLM-1",
            "changes",
            List.of(Map.of("orderLineId", line, "qty", 10)));
    String key = "SAGA-C:APPLY_ORDER_CANCEL";
    Res ok = changeQuantity(svc, orderId, key, cancel);
    assertThat(ok.status()).as(ok.body().toString()).isEqualTo(200);
    assertThat(ok.body().path("status").asString()).isEqualTo("APPLIED");
    assertThat(ok.body().path("version").asInt()).isEqualTo(2);
    assertThat(changeQuantity(svc, orderId, key, cancel).status()).isEqualTo(200);
    JsonNode o = get(repeat, "/orders/" + orderId).body();
    assertThat(o.path("status").asString()).isEqualTo("CANCELLED");
    assertThat(o.path("lines").path(0).path("unfulfilledQty").asInt()).isZero();
    assertThat(o.path("lines").path(0).path("cancelledBeforeShipQty").asInt()).isEqualTo(10);

    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE type = 'OrderLineQuantityChanged'"
                    + " AND aggregateid = ?",
                Integer.class,
                orderId))
        .isEqualTo(1);
    Map<String, Object> ev =
        jdbc.queryForMap(
            "SELECT topic, payload::text AS payload FROM outbox_event"
                + " WHERE type = 'OrderCancelled' AND aggregateid = ?",
            orderId);
    assertThat(ev.get("topic")).isEqualTo("order.order.v1");
    JsonNode p = mapper.readTree((String) ev.get("payload")).path("payload");
    assertThat(p.path("scope").asString()).isEqualTo("FULL");
    assertThat(p.path("claimId").asString()).isEqualTo("CLM-1");
    assertThat(p.path("accountId").asString()).isEqualTo(REPEAT);
    assertThat(p.path("lines").path(0).path("qty").asInt()).isEqualTo(10);

    // 다시 취소(다른 키) → 409
    Res again = changeQuantity(svc, orderId, UUID.randomUUID().toString(), cancel);
    assertThat(again.status()).isEqualTo(409);
  }

  /** 상품명 사본 조회가 실패해도 초안은 만든다 — 이름은 지어내지 않고 비운다(BR-35, 앱은 그때만 SKU 로). */
  @Test
  void productNameStaysEmptyWhenContentLookupFails() {
    deps.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/product-contents/PRD-E-00000001/snapshot"))
            .atPriority(1)
            .willReturn(aResponse().withStatus(500)));
    Res d =
        create(
            TestJwt.customer(REPEAT),
            draft(
                "PI-" + UUID.randomUUID(),
                "E-00000001",
                10,
                "ADR-IN",
                "SCHEDULED",
                window("10:00", "11:00")));
    assertThat(d.status()).as(d.body().toString()).isIn(200, 201);
    JsonNode line = d.body().path("lines").path(0);
    assertThat(line.path("sku").asString()).isEqualTo("E-00000001");
    assertThat(line.path("productName").isNull() || line.path("productName").isMissingNode())
        .as(line.toString())
        .isTrue();
  }
}
