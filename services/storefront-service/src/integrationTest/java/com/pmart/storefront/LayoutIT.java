package com.pmart.storefront;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.EventEnvelopeFixtures;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import com.pmart.storefront.application.BannerImageStorage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 진열 편성(ADR-0075 ② — BO-05): 배너 이미지 올리기 → 초안 → 할인 문구 422 → 고쳐 제출(해시 잠금·승인 요청 본문) → approval 의
 * ApprovalDecided(DISPLAY·storefront-service) → 새 진열 버전, 홈에 배너·테마 영역·전체 상품, 반영 회신 APPLIED. 해시가 다른
 * 승인은 게시하지 않는다. 이미지 저장소는 메모리, 그 밖은 실제 PostgreSQL·Kafka.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.storefront.content-base-url=http://localhost:1",
      "pmart.messaging.outbox.delete-after-insert=false",
      "spring.cloud.function.definition=catalogEvents;handlingEvents;reviewEvents;orderEvents;approvalEvents;promotionEvents",
      "spring.cloud.stream.bindings.orderEvents-in-0.destination=order.order.v1",
      "spring.cloud.stream.bindings.approvalEvents-in-0.destination=platform.approval.v1",
      "pmart.storefront.ranking.sales-window-days=30",
      "pmart.storefront.ranking.rating-prior-count=20",
      "pmart.storefront.ranking.rating-prior-mean=4.0",
      "pmart.it.context=LayoutIT"
    })
@Import({TestJwtConfiguration.class, LayoutIT.MemoryImages.class})
// 같은 DB 를 쓰는 시험 — 기본 진열(버전 0)에서 시작하는 draftSubmit… 이 먼저(이름 순)
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.MethodName.class)
class LayoutIT extends PostgresKafkaContainerSupport {

  @TestConfiguration
  static class MemoryImages {
    static final Map<String, byte[]> STORED = new ConcurrentHashMap<>();

    @Bean
    @Primary
    BannerImageStorage memoryBannerImages() {
      return new BannerImageStorage() {
        @Override
        public void put(String key, byte[] bytes, String contentType) {
          STORED.put(key, bytes);
        }

        @Override
        public InputStream get(String key) {
          byte[] b = STORED.get(key);
          if (b == null) {
            throw com.pmart.common.web.PmartException.notFound("IMAGE_NOT_FOUND", "없음");
          }
          return new ByteArrayInputStream(b);
        }

        @Override
        public boolean delete(String key) {
          return STORED.remove(key) != null;
        }
      };
    }
  }

  static final String MD = TestJwt.operator("md-1", List.of("MD"), List.of());
  static final String APPROVER =
      TestJwt.operator("approver-1", List.of("DISPLAY_APPROVER"), List.of());
  static final String STAFF = TestJwt.operator("staff-1", List.of("MFC_WORKER"), List.of());

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired KafkaTemplate<String, String> kafka;

  RestClient http;

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
    var spec =
        http.method(org.springframework.http.HttpMethod.valueOf(method))
            .uri(path)
            .headers(
                h -> {
                  if (token != null) {
                    h.setBearerAuth(token);
                  }
                  h.set("Idempotency-Key", UUID.randomUUID().toString());
                  h.set("X-Source-System", "backoffice-web");
                });
    if (body != null) {
      spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
    }
    return spec.exchange(
        (q, r) -> {
          byte[] b = r.getBody().readAllBytes();
          JsonNode n;
          try {
            n = mapper.readTree(b.length == 0 ? "{}" : new String(b));
          } catch (RuntimeException e) {
            n = mapper.createObjectNode().put("raw", b.length);
          }
          return new Res(r.getStatusCode().value(), n);
        });
  }

  Res upload(String token, byte[] bytes) {
    LinkedMultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
    form.add(
        "file",
        new ByteArrayResource(bytes) {
          @Override
          public String getFilename() {
            return "b.png";
          }
        });
    return http.post()
        .uri("/storefront/banner-images")
        .headers(
            h -> {
              h.setBearerAuth(token);
              h.set("Idempotency-Key", UUID.randomUUID().toString());
              h.set("X-Source-System", "backoffice-web");
            })
        .contentType(MediaType.MULTIPART_FORM_DATA)
        .body(form)
        .exchange(
            (q, r) ->
                new Res(r.getStatusCode().value(), mapper.readTree(r.getBody().readAllBytes())));
  }

  static byte[] png() throws Exception {
    java.awt.image.BufferedImage img =
        new java.awt.image.BufferedImage(40, 20, java.awt.image.BufferedImage.TYPE_INT_RGB);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    javax.imageio.ImageIO.write(img, "png", out);
    return out.toByteArray();
  }

  /** 압축이 잘 안 되는 PNG(무작위 점) — 크기 시험용. */
  static byte[] noisyPng(int w, int h) throws Exception {
    java.awt.image.BufferedImage img =
        new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
    java.util.Random r = new java.util.Random(7);
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        img.setRGB(x, y, r.nextInt(0xFFFFFF));
      }
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    javax.imageio.ImageIO.write(img, "png", out);
    return out.toByteArray();
  }

  void product(String id, String saleState) {
    jdbc.update(
        "INSERT INTO display_product (product_id, product_version, representative_sku, name, content_version,"
            + " content_published_at, sale_state, updated_at) VALUES (?, 1, ?, ?, 1, ?, ?, now())"
            + " ON CONFLICT (product_id) DO NOTHING",
        id,
        "E-" + id,
        "[시험] " + id,
        Timestamp.from(Instant.parse("2026-10-01T00:00:00Z")),
        saleState);
  }

  void approvalDecided(String approvalId, String draftId, String decision, String hash) {
    Map<String, Object> attrs = new LinkedHashMap<>();
    attrs.put("draftId", draftId);
    attrs.put("draftHash", hash);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("approvalId", approvalId);
    payload.put("changeRequestId", "CR-" + approvalId);
    payload.put("type", "DISPLAY");
    payload.put("decision", decision);
    payload.put(
        "targetRef",
        Map.of(
            "targetType", "Display", "targetId", draftId, "targetService", "storefront-service"));
    payload.put(
        "lines", List.of(Map.of("lineId", draftId, "decision", decision, "attributes", attrs)));
    payload.put("approverId", "approver-1");
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

  static <T> T await(Supplier<T> s, Predicate<T> ok) throws InterruptedException {
    T v = s.get();
    for (int i = 0; i < 80 && !ok.test(v); i++) {
      Thread.sleep(250);
      v = s.get();
    }
    return v;
  }

  Map<String, Object> body(String imageId, String themeTitle) {
    return Map.of(
        "banners",
        List.of(
            Map.of(
                "bannerId", "B1",
                "placement", "HOME_TOP",
                "imageId", imageId,
                "altText", "겨울 제철 채소 모음",
                "linkType", "SECTION",
                "linkRef", "T-WINTER",
                "sortOrder", 0)),
        "themes",
        List.of(
            Map.of(
                "sectionId",
                "T-WINTER",
                "kind",
                "SEASON",
                "title",
                themeTitle,
                "subtitle",
                "제철 채소",
                "productIds",
                List.of("PRD-L2", "PRD-L1", "PRD-L3"),
                "sortOrder",
                0)));
  }

  @Test
  void draftSubmitApprovePublishesBannersAndThemes() throws Exception {
    product("PRD-L1", "ON_SALE");
    product("PRD-L2", "ON_SALE");
    product("PRD-L3", "ON_SALE");
    assertThat(send("POST", STAFF, "/storefront/layout-drafts", Map.of()).status()).isEqualTo(403);

    Res img = upload(MD, png());
    assertThat(img.status()).as(img.body().toString()).isEqualTo(201);
    String imageId = img.body().path("imageId").asString();
    assertThat(img.body().path("width").asInt()).isEqualTo(40);
    assertThat(upload(MD, "GIF89a".getBytes()).status()).isEqualTo(422);
    // 1MB 넘는 배너(실제 배너 PNG 2.3MB 가 기본 multipart 1MB 에 413 이었음 — 설정 6MB)
    byte[] big = noisyPng(900, 700);
    assertThat(big.length).isGreaterThan(1_500_000);
    Res bigRes = upload(MD, big);
    assertThat(bigRes.status()).as(bigRes.body().toString()).isEqualTo(201);
    // 게시 전 이미지는 고객에게 안 보인다(운영자는 보임)
    assertThat(send("GET", null, "/storefront/banner-images/" + imageId, null).status())
        .isEqualTo(404);
    assertThat(send("GET", MD, "/storefront/banner-images/" + imageId, null).status())
        .isEqualTo(200);
    assertThat(send("GET", APPROVER, "/storefront/banner-images/" + imageId, null).status())
        .isEqualTo(200);

    // 근거 없는 약속 문구 → 제출 422, 고쳐서 다시(0.9.0 부터 할인·특가 문구는 허용)
    Res draft = send("POST", MD, "/storefront/layout-drafts", body(imageId, "겨울 채소 신선 보장"));
    assertThat(draft.status()).as(draft.body().toString()).isEqualTo(201);
    String draftId = draft.body().path("draftId").asString();
    assertThat(draft.body().path("state").asString()).isEqualTo("DRAFT");
    Res bad = send("POST", MD, "/storefront/layout-drafts/" + draftId + "/submissions", null);
    assertThat(bad.status()).isEqualTo(422);
    assertThat(bad.body().toString()).contains("PROMOTION_NOT_ALLOWED");
    Res fixed =
        http.put()
            .uri("/storefront/layout-drafts/" + draftId)
            .headers(
                h -> {
                  h.setBearerAuth(MD);
                  h.set("Idempotency-Key", UUID.randomUUID().toString());
                  h.set("X-Source-System", "backoffice-web");
                  h.set("X-Expected-Version", String.valueOf(draft.body().path("version").asInt()));
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(body(imageId, "겨울 제철 채소"))
            .exchange(
                (q, r) ->
                    new Res(
                        r.getStatusCode().value(), mapper.readTree(r.getBody().readAllBytes())));
    assertThat(fixed.status()).as(fixed.body().toString()).isEqualTo(200);
    Res submitted = send("POST", MD, "/storefront/layout-drafts/" + draftId + "/submissions", null);
    assertThat(submitted.status()).as(submitted.body().toString()).isEqualTo(200);
    String hash = submitted.body().path("draftHash").asString();
    JsonNode req = submitted.body().path("approvalRequest");
    assertThat(req.path("type").asString()).isEqualTo("DISPLAY");
    assertThat(req.path("targetService").asString()).isEqualTo("storefront-service");
    assertThat(req.path("targetRef").asString()).isEqualTo(draftId);
    assertThat(req.at("/lines/0/attributes/draftHash").asString()).isEqualTo(hash);

    // 전시 승인자: 승인 전에 초안·초안 이미지를 볼 수 있다(고치기·만들기는 안 됨, 0.7.1)
    Res seen = send("GET", APPROVER, "/storefront/layout-drafts/" + draftId, null);
    assertThat(seen.status()).as(seen.body().toString()).isEqualTo(200);
    assertThat(send("GET", APPROVER, "/storefront/layout-drafts", null).status()).isEqualTo(200);
    assertThat(send("POST", APPROVER, "/storefront/layout-drafts", Map.of()).status())
        .isEqualTo(403);
    assertThat(send("GET", STAFF, "/storefront/layout-drafts/" + draftId, null).status())
        .isEqualTo(403);

    // 승인 전: 홈은 기본 진열 그대로
    JsonNode before = send("GET", null, "/storefront/home", null).body();
    assertThat(before.path("layoutVersion").asInt()).isZero();
    assertThat(before.path("banners")).isEmpty();

    // 판매중지 상품은 승인 뒤에도 그 자리에서 빠진다
    jdbc.update(
        "UPDATE display_product SET sale_state = 'SALE_STOPPED' WHERE product_id = 'PRD-L3'");
    String approvalId = "APR-" + UUID.randomUUID();
    approvalDecided(approvalId, draftId, "APPROVED", hash);
    JsonNode home =
        await(
            () -> send("GET", null, "/storefront/home", null).body(),
            h -> h.path("layoutVersion").asInt() >= 1);
    int version = home.path("layoutVersion").asInt();
    assertThat(version).isGreaterThanOrEqualTo(1);
    assertThat(home.path("banners").path(0).path("bannerId").asString()).isEqualTo("B1");
    assertThat(home.path("banners").path(0).path("imageUrl").asString())
        .isEqualTo("/api/v1/storefront/banner-images/" + imageId);
    assertThat(home.path("banners").path(0).path("linkRef").asString()).isEqualTo("T-WINTER");
    JsonNode theme = home.path("sections").path(0);
    assertThat(theme.path("sectionId").asString()).isEqualTo("T-WINTER");
    assertThat(theme.path("kind").asString()).isEqualTo("SEASON");
    List<String> ids = new java.util.ArrayList<>();
    theme.path("items").forEach(i -> ids.add(i.path("productId").asString()));
    assertThat(ids).containsExactly("PRD-L2", "PRD-L1");
    assertThat(
            home.path("sections")
                .path(home.path("sections").size() - 1)
                .path("sectionId")
                .asString())
        .isEqualTo("S-ALL");
    // 영역 다음 묶음·버전 확인, 배너 목록, 공개 이미지
    assertThat(
            send(
                    "GET",
                    null,
                    "/storefront/sections/T-WINTER/items?size=1&layoutVersion=" + version,
                    null)
                .body()
                .path("items")
                .size())
        .isEqualTo(1);
    assertThat(
            send("GET", null, "/storefront/sections/T-WINTER/items?layoutVersion=0", null).status())
        .isEqualTo(409);
    assertThat(
            send("GET", null, "/storefront/banners?placement=HOME_TOP", null).body().path("items"))
        .hasSize(1);
    assertThat(send("GET", null, "/storefront/banner-images/" + imageId, null).status())
        .isEqualTo(200);
    // 초안 PUBLISHED·반영 회신 APPLIED
    JsonNode published = send("GET", MD, "/storefront/layout-drafts/" + draftId, null).body();
    assertThat(published.path("state").asString()).isEqualTo("PUBLISHED");
    assertThat(published.path("layoutVersion").asInt()).isEqualTo(version);
    String receipt =
        jdbc.queryForObject(
            "SELECT payload::text FROM outbox_event WHERE aggregateid = ? AND type = 'ApplicationReceiptRecorded'",
            String.class,
            approvalId);
    assertThat(mapper.readTree(receipt).at("/payload/result").asString()).isEqualTo("APPLIED");

    // 0.14.0 — promotion 이 받아 가는 게시 테마(판매중지 상품도 편성 그대로 — 할인 대상은 promotion 이 SKU 로 정한다)
    JsonNode themes = send("GET", null, "/internal/storefront/themes", null).body();
    assertThat(themes.path("layoutVersion").asInt()).isEqualTo(version);
    JsonNode winter = themes.path("themes").path(0);
    assertThat(winter.path("sectionId").asString()).isEqualTo("T-WINTER");
    assertThat(winter.path("kind").asString()).isEqualTo("SEASON");
    assertThat(winter.path("title").asString()).isNotBlank();
    assertThat(winter.path("productIds").toString()).contains("PRD-L1", "PRD-L2");

    // 해시가 다른 승인 → 게시 안 함, 초안 REJECTED·SCOPE_MISMATCH
    jdbc.update("UPDATE display_product SET sale_state = 'ON_SALE' WHERE product_id = 'PRD-L3'");
    Res second = send("POST", MD, "/storefront/layout-drafts?fromCurrent=true", Map.of());
    String secondId = second.body().path("draftId").asString();
    assertThat(second.body().path("themes").path(0).path("sectionId").asString())
        .isEqualTo("T-WINTER");
    assertThat(
            send("POST", MD, "/storefront/layout-drafts/" + secondId + "/submissions", null)
                .status())
        .isEqualTo(200);
    approvalDecided("APR-" + UUID.randomUUID(), secondId, "APPROVED", "0".repeat(64));
    JsonNode rejected =
        await(
            () -> send("GET", MD, "/storefront/layout-drafts/" + secondId, null).body(),
            d -> "REJECTED".equals(d.path("state").asString()));
    assertThat(rejected.path("rejectionReason").asString()).isEqualTo("SCOPE_MISMATCH");
    assertThat(send("GET", null, "/storefront/home", null).body().path("layoutVersion").asInt())
        .isEqualTo(version);
  }

  /** 0.9.0(2026-11-21): 팝업·로딩 화면 배너, 타이머 테마, MD 탭(탭에 넣은 테마는 HOME 에서 빠짐), 특가 문구 허용. */
  @Test
  void popupSplashTimerThemeAndCustomTab() throws Exception {
    product("PRD-M1", "ON_SALE");
    product("PRD-M2", "ON_SALE");
    String imageId = upload(MD, png()).body().path("imageId").asString();
    String ends =
        java.time.Instant.now()
            .plus(java.time.Duration.ofDays(2))
            .truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
            .toString();
    Map<String, Object> body =
        Map.of(
            "banners",
            List.of(
                Map.of(
                    "bannerId",
                    "B-POP",
                    "placement",
                    "POPUP",
                    "imageId",
                    imageId,
                    "altText",
                    "주말 특가 안내",
                    "linkType",
                    "SECTION",
                    "linkRef",
                    "T-DEAL",
                    "sortOrder",
                    0),
                Map.of(
                    "bannerId",
                    "B-SPL",
                    "placement",
                    "SPLASH",
                    "imageId",
                    imageId,
                    "altText",
                    "PMart",
                    "linkType",
                    "NONE",
                    "sortOrder",
                    0,
                    "endsAt",
                    ends),
                Map.of( // 0.11.0 BR-82 카테고리 화면 맨 위 — 홈에는 안 나온다
                    "bannerId",
                    "B-CAT",
                    "placement",
                    "CATEGORY",
                    "imageId",
                    imageId,
                    "altText",
                    "카테고리 배너",
                    "linkType",
                    "NONE",
                    "sortOrder",
                    0),
                Map.of(
                    "bannerId",
                    "B-TOP",
                    "placement",
                    "HOME_TOP",
                    "imageId",
                    imageId,
                    "altText",
                    "아침 한 상",
                    "linkType",
                    "SECTION",
                    "linkRef",
                    "T-TIMER",
                    "sortOrder",
                    0)),
            "themes",
            List.of(
                Map.of(
                    "sectionId",
                    "T-TIMER",
                    "kind",
                    "THEME",
                    "title",
                    "오늘만 이 가격",
                    "productIds",
                    List.of("PRD-M1"),
                    "sortOrder",
                    0,
                    "endsAt",
                    ends,
                    "showTimer",
                    true,
                    "timerStyle",
                    "BAND"),
                Map.of(
                    "sectionId",
                    "T-DEAL",
                    "kind",
                    "THEME",
                    "title",
                    "MD 특가",
                    "productIds",
                    List.of("PRD-M2"),
                    "sortOrder",
                    1,
                    "timerStyle",
                    "BAND")),
            "tabs",
            List.of(
                Map.of(
                    "tabKey",
                    "X-DEAL",
                    "title",
                    "특가",
                    "sectionIds",
                    List.of("T-DEAL"),
                    "sortOrder",
                    0,
                    "endsAt",
                    ends,
                    "showTimer",
                    true)));
    Res draft = send("POST", MD, "/storefront/layout-drafts", body);
    assertThat(draft.status()).as(draft.body().toString()).isEqualTo(201);
    String draftId = draft.body().path("draftId").asString();
    assertThat(draft.body().path("tabs").path(0).path("tabKey").asString()).isEqualTo("X-DEAL");
    // 0.12.0 BR-84: 타이머가 꺼진 테마의 모양은 null 로 저장된다
    assertThat(draft.body().path("themes").path(0).path("timerStyle").asString()).isEqualTo("BAND");
    assertThat(draft.body().path("themes").path(1).path("timerStyle").isNull()).isTrue();
    Res submitted = send("POST", MD, "/storefront/layout-drafts/" + draftId + "/submissions", null);
    assertThat(submitted.status()).as(submitted.body().toString()).isEqualTo(200);
    int before = send("GET", null, "/storefront/home", null).body().path("layoutVersion").asInt();
    approvalDecided(
        "APR-" + UUID.randomUUID(),
        draftId,
        "APPROVED",
        submitted.body().path("draftHash").asString());
    JsonNode home =
        await(
            () -> send("GET", null, "/storefront/home", null).body(),
            h ->
                h.path("layoutVersion").asInt() > before
                    && h.path("banners").findValuesAsString("bannerId").contains("B-TOP"));
    assertThat(home.path("layoutVersion").asInt()).as("새 버전 게시").isGreaterThan(before);
    // 홈 배너는 HOME_* 만, 팝업·로딩 화면은 자리로 따로
    assertThat(home.path("banners").findValuesAsString("bannerId")).containsExactly("B-TOP");
    assertThat(
            send("GET", null, "/storefront/banners", null)
                .body()
                .path("items")
                .findValuesAsString("bannerId"))
        .containsExactly("B-TOP");
    assertThat(
            send("GET", null, "/storefront/banners?placement=POPUP", null)
                .body()
                .path("items")
                .findValuesAsString("bannerId"))
        .containsExactly("B-POP");
    assertThat(
            send("GET", null, "/storefront/banners?placement=SPLASH", null)
                .body()
                .path("items")
                .findValuesAsString("bannerId"))
        .containsExactly("B-SPL");
    // 0.13.0 고객용 배너에 편성 기간 — 앱이 저장한 로딩 화면을 끝 뒤에 그리지 않게
    JsonNode splash =
        send("GET", null, "/storefront/banners?placement=SPLASH", null)
            .body()
            .path("items")
            .path(0);
    assertThat(java.time.Instant.parse(splash.path("endsAt").asString()))
        .isEqualTo(java.time.Instant.parse(ends));
    JsonNode popup =
        send("GET", null, "/storefront/banners?placement=POPUP", null).body().path("items").path(0);
    assertThat(popup.path("endsAt").isNull() || popup.path("endsAt").isMissingNode()).isTrue();
    assertThat(
            send("GET", null, "/storefront/banners?placement=CATEGORY", null)
                .body()
                .path("items")
                .findValuesAsString("bannerId"))
        .containsExactly("B-CAT");
    // 타이머 테마는 HOME 에, 탭에 넣은 테마는 HOME 에서 빠진다
    List<String> sections = home.path("sections").findValuesAsString("sectionId");
    assertThat(sections).contains("T-TIMER").doesNotContain("T-DEAL");
    JsonNode timer = home.path("sections").path(sections.indexOf("T-TIMER"));
    assertThat(timer.path("showTimer").asBoolean()).isTrue();
    assertThat(timer.path("timerStyle").asString()).as("BR-84 띠형").isEqualTo("BAND");
    assertThat(
            java.time.OffsetDateTime.parse(timer.path("endsAt").asString()).toInstant().toString())
        .isEqualTo(ends);
    assertThat(home.path("customTabs").path(0).path("tabKey").asString()).isEqualTo("X-DEAL");
    assertThat(home.path("customTabs").path(0).path("showTimer").asBoolean()).isTrue();
    JsonNode tab = send("GET", null, "/storefront/custom-tabs/X-DEAL", null).body();
    assertThat(tab.path("tab").path("title").asString()).isEqualTo("특가");
    assertThat(tab.path("sections").findValuesAsString("sectionId")).containsExactly("T-DEAL");
    assertThat(tab.path("sections").path(0).path("timerStyle").isNull()).as("타이머 없음").isTrue();
    assertThat(tab.path("sections").path(0).path("items").path(0).path("productId").asString())
        .isEqualTo("PRD-M2");
    assertThat(send("GET", null, "/storefront/custom-tabs/X-NONE", null).status()).isEqualTo(404);
  }

  /** 0.10.0(2026-11-21): 홈 순서 — 배너 묶음, 테마, 배너 묶음 순서 그대로, 넘김 간격. */
  @Test
  void homeOrderMixesBannerGroupsAndThemes() throws Exception {
    product("PRD-O1", "ON_SALE");
    String imageId = upload(MD, png()).body().path("imageId").asString();
    Map<String, Object> body =
        Map.of(
            "banners",
            List.of(
                Map.of(
                    "bannerId",
                    "B-G1A",
                    "placement",
                    "HOME_INLINE",
                    "groupId",
                    "G-ONE",
                    "imageId",
                    imageId,
                    "altText",
                    "첫 묶음 1",
                    "linkType",
                    "NONE",
                    "sortOrder",
                    0),
                Map.of(
                    "bannerId",
                    "B-G1B",
                    "placement",
                    "HOME_INLINE",
                    "groupId",
                    "G-ONE",
                    "imageId",
                    imageId,
                    "altText",
                    "첫 묶음 2",
                    "linkType",
                    "NONE",
                    "sortOrder",
                    1),
                Map.of(
                    "bannerId",
                    "B-G2",
                    "placement",
                    "HOME_INLINE",
                    "groupId",
                    "G-TWO",
                    "imageId",
                    imageId,
                    "altText",
                    "둘째 묶음",
                    "linkType",
                    "NONE",
                    "sortOrder",
                    0)),
            "themes",
            List.of(
                Map.of(
                    "sectionId",
                    "T-ORDER",
                    "kind",
                    "THEME",
                    "title",
                    "가운데 테마",
                    "productIds",
                    List.of("PRD-O1"),
                    "sortOrder",
                    0)),
            "homeOrder",
            List.of("G-ONE", "T-ORDER", "G-TWO"),
            "bannerIntervalSeconds",
            5);
    Res draft = send("POST", MD, "/storefront/layout-drafts", body);
    assertThat(draft.status()).as(draft.body().toString()).isEqualTo(201);
    assertThat(draft.body().path("homeOrder").size()).isEqualTo(3);
    String draftId = draft.body().path("draftId").asString();
    Res submitted = send("POST", MD, "/storefront/layout-drafts/" + draftId + "/submissions", null);
    assertThat(submitted.status()).as(submitted.body().toString()).isEqualTo(200);
    approvalDecided(
        "APR-" + UUID.randomUUID(),
        draftId,
        "APPROVED",
        submitted.body().path("draftHash").asString());
    JsonNode home =
        await(
            () -> send("GET", null, "/storefront/home", null).body(),
            h -> h.path("sections").findValuesAsString("sectionId").contains("G-TWO"));
    List<String> ids = home.path("sections").findValuesAsString("sectionId");
    assertThat(ids.subList(0, 3)).containsExactly("G-ONE", "T-ORDER", "G-TWO");
    assertThat(ids.get(ids.size() - 1)).isEqualTo("S-ALL");
    JsonNode first = home.path("sections").path(0);
    assertThat(first.path("kind").asString()).isEqualTo("BANNER");
    assertThat(first.path("banners").findValuesAsString("bannerId"))
        .containsExactly("B-G1A", "B-G1B");
    assertThat(first.path("intervalSeconds").asInt()).isEqualTo(5);
    assertThat(home.path("bannerIntervalSeconds").asInt()).isEqualTo(5);
    // 맨 위 배너 칸에는 섞이지 않는다
    assertThat(home.path("banners").findValuesAsString("bannerId")).doesNotContain("B-G1A", "B-G2");
  }
}
