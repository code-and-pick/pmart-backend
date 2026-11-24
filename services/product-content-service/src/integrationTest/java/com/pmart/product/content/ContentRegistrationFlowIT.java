package com.pmart.product.content;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.pmart.common.test.EventEnvelopeFixtures;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 콘텐츠 등록 흐름(임시 백오피스): seed-v1 묶음 업로드(이미지 → SeaweedFS) → 재업로드 멱등 → ProductVersionPublished(sources)
 * 로 상품 연결 → 제출 준비 → ApprovalDecided(DISPLAY) → 게시본 조회·이미지 본문·ProductContentPublished(Outbox). 실제
 * PostgreSQL·Kafka·SeaweedFS, 시험 토큰. 이미지는 합성 바이트만 쓴다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.messaging.outbox.delete-after-insert=false",
      "pmart.security.service-token.token-uri=${pmart.content.inventory-base-url}/api/v1/internal/service-tokens"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock(
    @ConfigureWireMock(
        name = "deps",
        baseUrlProperties = {"pmart.content.inventory-base-url", "pmart.content.pricing-base-url"}))
class ContentRegistrationFlowIT extends ContentContainerSupport {

  static final String SERVICE_TOKEN = TestJwt.service("product-content-service");

  @InjectWireMock("deps")
  WireMockServer deps;

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;
  @Autowired KafkaTemplate<String, String> kafka;

  static final String MD = TestJwt.operator("md-1", List.of("MD"), List.of());
  static final String CUSTOMER = TestJwt.customer("c-1");

  static S3Client s3;

  RestClient http;

  @BeforeAll
  static void s3Client() {
    s3 =
        S3Client.builder()
            .region(Region.US_EAST_1)
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create(S3_KEY, S3_SECRET)))
            .forcePathStyle(true)
            .endpointOverride(URI.create(s3Endpoint()))
            .build();
  }

  @AfterAll
  static void closeS3() {
    s3.close();
  }

  @BeforeEach
  void client() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    deps.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/service-tokens"))
            .willReturn(
                okJson(
                    Map.of(
                        "access_token",
                        SERVICE_TOKEN,
                        "token_type",
                        "Bearer",
                        "expires_in",
                        300))));
  }

  /** mfc-inventory 가용(MFC-1). */
  void stock(String sku, int available, boolean handlingEnabled) {
    deps.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/availability"))
            .withQueryParam("mfcId", equalTo("MFC-1"))
            .willReturn(
                okJson(
                    Map.of(
                        "mfcId",
                        "MFC-1",
                        "items",
                        List.of(
                            Map.of(
                                "sku",
                                sku,
                                "available",
                                available,
                                "handlingEnabled",
                                handlingEnabled,
                                "version",
                                1))))));
  }

  com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder okJson(Object body) {
    return aResponse()
        .withHeader("Content-Type", "application/json")
        .withBody(mapper.writeValueAsString(body));
  }

  record Res(int status, JsonNode body) {}

  Res post(String path, Object body) {
    var spec =
        http.post()
            .uri(path)
            .headers(
                h -> {
                  h.setBearerAuth(MD);
                  h.set("Idempotency-Key", UUID.randomUUID().toString());
                  h.set("X-Source-System", "backoffice-web");
                });
    if (body instanceof LinkedMultiValueMap<?, ?> form) {
      spec = spec.contentType(MediaType.MULTIPART_FORM_DATA).body(form);
    } else if (body != null) {
      spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
    }
    var e = spec.retrieve().toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  /** token 이 null 이면 비회원(토큰 없음). */
  Res get(String path, String token) {
    var e =
        http.get()
            .uri(path)
            .headers(
                h -> {
                  if (token != null) {
                    h.setBearerAuth(token);
                  }
                })
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  Res get(String path) {
    return get(path, MD);
  }

  record Bytes(int status, byte[] body, String contentType) {}

  Bytes image(String url, String token) {
    var e =
        RestClient.builder()
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build()
            .get()
            .uri("http://localhost:" + port + url)
            .headers(
                h -> {
                  if (token != null) {
                    h.setBearerAuth(token);
                  }
                  h.set("Accept", "*/*");
                })
            .retrieve()
            .toEntity(byte[].class);
    return new Bytes(
        e.getStatusCode().value(), e.getBody(), String.valueOf(e.getHeaders().getContentType()));
  }

  Res await(String path, Predicate<JsonNode> done) throws InterruptedException {
    Instant until = Instant.now().plus(Duration.ofSeconds(30));
    Res r = get(path);
    while (!done.test(r.body()) && Instant.now().isBefore(until)) {
      Thread.sleep(300);
      r = get(path);
    }
    return r;
  }

  /** review 사건(commerce.review.v1) 흉내. */
  void review(String type, String reviewId, String productId, int rating, int version) {
    String json =
        EventEnvelopeFixtures.envelope(type)
            .from("review-service")
            .aggregate("Review", reviewId, version)
            .payload(
                Map.of(
                    "reviewId", reviewId,
                    "orderLineId", "OL-" + reviewId,
                    "productId", productId,
                    "rating", rating))
            .buildJson();
    kafka.send("commerce.review.v1", reviewId, json).join();
  }

  /** 평점 집계(0.2.1, 2026-11-19): 등록·수정(새 버전)·옛 버전 무시·삭제, 비회원 조회. */
  @Test
  void ratingSummaryCountsPublishedReviews() throws Exception {
    String productId = "PRD-RS-" + UUID.randomUUID().toString().substring(0, 8);
    Res empty = get("/product-contents/" + productId + "/rating-summary", null);
    assertThat(empty.status()).isEqualTo(200);
    assertThat(empty.body().path("reviewCount").asInt()).isZero();
    assertThat(
            empty.body().path("averageRating").isNull()
                || empty.body().path("averageRating").isMissingNode())
        .as("리뷰 없으면 0점이 아니라 없음")
        .isTrue();

    String r1 = "RV-1-" + productId;
    String r2 = "RV-2-" + productId;
    String r3 = "RV-3-" + productId;
    review("ReviewPublished", r1, productId, 5, 1);
    review("ReviewPublished", r2, productId, 4, 1);
    review("ReviewPublished", r3, productId, 1, 1);
    review("ReviewPublished", r2, productId, 2, 2); // 수정 — 별점 4 → 2
    review("ReviewPublished", r2, productId, 4, 1); // 늦게 온 옛 버전 — 무시
    review("ReviewDeleted", r3, productId, 1, 2); // 삭제
    JsonNode s = null;
    for (int i = 0; i < 100; i++) {
      s = get("/product-contents/" + productId + "/rating-summary", null).body();
      if (s.path("reviewCount").asInt() == 2 && s.path("ratingCounts").path("2").asInt() == 1) {
        break;
      }
      Thread.sleep(200);
    }
    assertThat(s.path("reviewCount").asInt()).isEqualTo(2);
    assertThat(s.path("averageRating").asDouble()).isEqualTo(3.5); // (5 + 2) / 2
    assertThat(s.path("ratingCounts").path("5").asInt()).isEqualTo(1);
    assertThat(s.path("ratingCounts").path("4").asInt()).isZero();
    assertThat(s.path("ratingCounts").path("1").asInt()).isZero();
  }

  /** product-service 가 내는 ProductVersionPublished 를 흉내 낸다(sources 로 초안 연결). */
  void productPublished(String productId, int version, String sku, String sourceKey) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("productId", productId);
    payload.put("baseProductId", "BP-" + productId);
    payload.put(
        "skus",
        List.of(
            Map.of(
                "sku",
                sku,
                "unitType",
                "E",
                "unitsPerSku",
                1,
                "weightG",
                180,
                "state",
                "ACTIVE",
                "netContent",
                Map.of("amount", 180, "unit", "G")))); // 내용량(2026-11-19 BR-19)
    payload.put("version", version);
    payload.put("saleState", "ON_SALE");
    payload.put("primaryCategoryId", "p03-c02");
    payload.put("categoryPaths", List.of("p03/p03-c02"));
    payload.put("effectiveAt", Instant.now().toString());
    if (sourceKey != null) {
      payload.put("sources", List.of(Map.of("system", "bmart-capture", "key", sourceKey)));
    }
    String json =
        EventEnvelopeFixtures.envelope("ProductVersionPublished")
            .from("product-service")
            .aggregate("Product", productId, version)
            .payload(payload)
            .buildJson();
    kafka.send("commerce.product.v1", productId, json).join();
  }

  /** 반영 회신(RC-1, 2026-11-19) — outbox 의 ApplicationReceiptRecorded payload, 없으면 null. */
  tools.jackson.databind.JsonNode receipt(String approvalId) throws InterruptedException {
    for (int i = 0; i < 50; i++) {
      java.util.List<String> rows =
          jdbc.queryForList(
              "SELECT payload::text FROM outbox_event WHERE type = 'ApplicationReceiptRecorded'"
                  + " AND aggregateid = ? AND topic = 'platform.approval-receipt.v1'",
              String.class,
              approvalId);
      if (!rows.isEmpty()) {
        return mapper.readTree(rows.get(0)).path("payload");
      }
      Thread.sleep(100);
    }
    return null;
  }

  /** approval-service 가 내는 ApprovalDecided 를 흉내 낸다(제출 준비가 돌려준 본문의 행 그대로, 필요하면 해시를 바꿔). */
  String decide(JsonNode approvalRequest, String decision, boolean tamperHash) {
    String approvalId = "AP-" + UUID.randomUUID();
    List<Map<String, Object>> lines = new ArrayList<>();
    for (JsonNode l : approvalRequest.path("lines")) {
      Map<String, Object> attrs = mapper.convertValue(l.path("attributes"), Map.class);
      if (tamperHash) {
        attrs.put("draftHash", "0".repeat(64));
      }
      Map<String, Object> line = new LinkedHashMap<>();
      line.put("lineId", l.path("lineKey").asString());
      line.put("decision", decision);
      line.put("targetRef", l.path("targetRef").asString());
      line.put("attributes", attrs);
      lines.add(line);
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("approvalId", approvalId);
    payload.put("changeRequestId", "CR-" + UUID.randomUUID());
    payload.put("type", approvalRequest.path("type").asString());
    payload.put("decision", decision);
    payload.put(
        "targetRef",
        Map.of(
            "targetType",
            "Display",
            "targetId",
            approvalRequest.path("targetRef").asString(),
            "targetService",
            approvalRequest.path("targetService").asString()));
    payload.put("lines", lines);
    payload.put("approverId", "lead-1");
    payload.put("dossierVersion", 2);
    payload.put("decidedAt", Instant.now().toString());
    String json =
        EventEnvelopeFixtures.envelope("ApprovalDecided")
            .from("approval-service")
            .aggregate("Approval", approvalId, 2)
            .payload(payload)
            .buildJson();
    kafka.send("platform.approval.v1", approvalId, json).join();
    return approvalId;
  }

  /** 합성 JPEG(시그니처 + 표식 바이트). 실제 이미지가 아니다. */
  static byte[] jpeg(String marker) {
    byte[] m = marker.getBytes(StandardCharsets.UTF_8);
    byte[] out = new byte[4 + m.length];
    out[0] = (byte) 0xFF;
    out[1] = (byte) 0xD8;
    out[2] = (byte) 0xFF;
    out[3] = (byte) 0xE0;
    System.arraycopy(m, 0, out, 4, m.length);
    return out;
  }

  byte[] seedZip(Map<String, Map<String, Object>> products, Map<String, Map<String, byte[]>> images)
      throws Exception {
    var out = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(out)) {
      zip.putNextEntry(new ZipEntry("seed-v1/manifest.json"));
      zip.write("{\"format\":\"seed-v1\"}".getBytes(StandardCharsets.UTF_8));
      for (var e : products.entrySet()) {
        String dir = "seed-v1/products/" + e.getKey() + "/";
        zip.putNextEntry(new ZipEntry(dir + "product.json"));
        zip.write(mapper.writeValueAsBytes(e.getValue()));
        for (var img : images.getOrDefault(e.getKey(), Map.of()).entrySet()) {
          zip.putNextEntry(new ZipEntry(dir + img.getKey()));
          zip.write(img.getValue());
        }
      }
    }
    return out.toByteArray();
  }

  static Map<String, Object> seedProduct(String id, String name) {
    Map<String, Object> content = new LinkedHashMap<>();
    content.put("name", name);
    content.put("options", List.of(Map.of("label", "1개", "capacityLabel", "180g")));
    content.put("description", "OCR 초안 설명");
    content.put("storageGuide", "냉동 보관");
    content.put("returnGuide", null);
    content.put("notices", List.of("원산지: 국내산", "용량: 180g"));
    content.put("rating", null);
    // 0.6.0 표시사항 값(2026-11-23) — 100g 기준 영양(모르는 칸은 없음)·알레르기 함유/같은 시설
    content.put(
        "nutrition", Map.of("basisUnit", "G", "energyKcal", 206, "sodiumMg", 277, "proteinG", 13));
    content.put(
        "allergens",
        Map.of(
            "contains",
            List.of("닭고기"),
            "mayContain",
            List.of("땅콩"),
            "text",
            "닭고기 함유 / 땅콩을 사용한 제품과 같은 제조시설에서 제조"));
    return Map.of(
        "format",
        "seed-v1",
        "product",
        Map.of("seedProductId", id, "internalName", name),
        "content",
        content,
        "source",
        Map.of("system", "bmart-capture"));
  }

  LinkedMultiValueMap<String, Object> file(byte[] bytes, String name) {
    var form = new LinkedMultiValueMap<String, Object>();
    form.add(
        "file",
        new ByteArrayResource(bytes) {
          @Override
          public String getFilename() {
            return name;
          }
        });
    return form;
  }

  int objectCount() {
    return s3.listObjectsV2Paginator(b -> b.bucket(BUCKET)).contents().stream()
        .mapToInt(o -> 1)
        .sum();
  }

  int count(String sql, Object... args) {
    Integer n = jdbc.queryForObject(sql, Integer.class, args);
    return n == null ? 0 : n;
  }

  @Test
  void seedBatchLinksPublishesAndServesImages() throws Exception {
    String run = UUID.randomUUID().toString().substring(0, 8);
    String a = "bm-a-" + run;
    String b = "bm-b-" + run;
    byte[] main = jpeg("main-" + run);
    byte[] d1 = jpeg("desc1-" + run);
    byte[] d2 = jpeg("desc2-" + run);
    byte[] info = jpeg("info-" + run);
    byte[] zip =
        seedZip(
            Map.of(a, seedProduct(a, "[냉동]호밍스 부대찌개 180g"), b, seedProduct(b, "대표 사진 없는 상품")),
            Map.of(
                a,
                Map.of(
                    "images/main.jpg",
                    main,
                    "images/description_01.jpg",
                    d1,
                    "images/description_02.jpg",
                    d2,
                    "images/info.jpg",
                    info),
                b,
                Map.of("images/description_01.jpg", d1))); // 같은 내용 → 저장소에 다시 올리지 않음
    int objectsBefore = objectCount();

    // 1) 업로드: 초안 2건, 이미지 5장 중 저장소에는 서로 다른 4개만
    Res batch = post("/content-import-batches", file(zip, "seed-v1.zip"));
    assertThat(batch.status()).isEqualTo(201);
    assertThat(batch.body().path("createdCount").asInt()).isEqualTo(2);
    assertThat(batch.body().path("imageCount").asInt()).isEqualTo(5);
    assertThat(batch.body().path("uploadedImageCount").asInt()).isEqualTo(4);
    assertThat(objectCount() - objectsBefore).isEqualTo(4);
    JsonNode draftA = null;
    JsonNode draftB = null;
    for (JsonNode item : batch.body().path("items")) {
      if (a.equals(item.path("sourceKey").asString())) {
        draftA = item.path("draft");
      } else {
        draftB = item.path("draft");
      }
    }
    String draftAId = draftA.path("draftId").asString();
    String draftBId = draftB.path("draftId").asString();
    assertThat(draftA.path("images")).hasSize(4);
    assertThat(draftA.path("images").path(0).path("role").asString()).isEqualTo("MAIN");
    assertThat(draftA.path("input").path("notices").path(0).asString()).isEqualTo("원산지: 국내산");
    assertThat(draftA.path("input").path("nutrition").path("energyKcal").asInt()).isEqualTo(206);
    assertThat(draftA.path("input").path("allergens").path("mayContain").path(0).asString())
        .isEqualTo("땅콩");
    assertThat(draftA.path("submittable").asBoolean()).isFalse();
    assertThat(draftA.path("problems").toString()).contains("PRODUCT_NOT_LINKED");
    assertThat(draftB.path("problems").toString()).contains("MAIN_IMAGE_REQUIRED");
    String objectKey =
        jdbc.queryForObject(
            "SELECT object_key FROM content_image WHERE draft_id = ? AND role = 'MAIN'",
            String.class,
            draftAId);
    assertThat(objectKey).startsWith("products/draft-" + draftAId + "/main/").endsWith(".jpg");
    assertThat(s3.headObject(h -> h.bucket(BUCKET).key(objectKey)).contentLength())
        .isEqualTo(main.length);

    // 2) 상품 승인 전에는 제출할 수 없다
    Res early = post("/content-drafts/" + draftAId + "/submissions", null);
    assertThat(early.status()).isEqualTo(422);
    assertThat(early.body().path("code").asString()).isEqualTo("PRODUCT_NOT_LINKED");

    // 3) 같은 묶음을 다시 올려도 초안·이미지·저장소 객체가 늘지 않는다
    int draftsBefore = count("SELECT COUNT(*) FROM content_draft WHERE source_key IN (?, ?)", a, b);
    int imagesBefore =
        count("SELECT COUNT(*) FROM content_image WHERE draft_id IN (?, ?)", draftAId, draftBId);
    Res again = post("/content-import-batches", file(zip, "seed-v1.zip"));
    assertThat(again.status()).isEqualTo(201);
    assertThat(again.body().path("unchangedCount").asInt()).isEqualTo(2);
    assertThat(again.body().path("uploadedImageCount").asInt()).isZero();
    assertThat(count("SELECT COUNT(*) FROM content_draft WHERE source_key IN (?, ?)", a, b))
        .isEqualTo(draftsBefore)
        .isEqualTo(2);
    assertThat(
            count(
                "SELECT COUNT(*) FROM content_image WHERE draft_id IN (?, ?)", draftAId, draftBId))
        .isEqualTo(imagesBefore)
        .isEqualTo(5);
    assertThat(objectCount() - objectsBefore).isEqualTo(4);

    // 4) 상품 승인(ProductVersionPublished, sources) → 원천 키로 연결
    String productId = "PRD-" + UUID.randomUUID();
    String sku = String.format("E-%08d", Math.abs(run.hashCode() % 100_000_000));
    productPublished(productId, 1, sku, a);
    JsonNode linked =
        await("/content-drafts/" + draftAId, x -> productId.equals(x.path("productId").asString()))
            .body();
    assertThat(linked.path("productId").asString()).isEqualTo(productId);
    assertThat(linked.path("submittable").asBoolean()).isTrue();
    // BR-44: 아직 게시본이 없으니 신규
    assertThat(linked.path("registration").asString()).isEqualTo("NEW");
    assertThat(linked.path("publishedContentVersion").isNull()).isTrue();

    // 5) 배치 제출 준비 → DISPLAY 승인 본문, 대표 사진 없는 b 는 빠진다
    String againBatch = again.body().path("batchId").asString();
    Res submission = post("/content-import-batches/" + againBatch + "/submissions", null);
    assertThat(submission.status()).isEqualTo(200);
    assertThat(submission.body().path("submittedDraftIds").path(0).asString()).isEqualTo(draftAId);
    assertThat(submission.body().path("excludedDraftIds").path(0).asString()).isEqualTo(draftBId);
    JsonNode body = submission.body().path("approvalRequest");
    assertThat(body.path("type").asString()).isEqualTo("DISPLAY");
    assertThat(body.path("targetService").asString()).isEqualTo("product-content-service");
    assertThat(body.path("targetRef").asString()).isEqualTo("contentImportBatch:" + againBatch);
    assertThat(body.path("lines").path(0).path("attributes").path("draftHash").asString())
        .hasSize(64);
    assertThat(body.path("lines").path(0).path("attributes").path("registration").asString())
        .isEqualTo("NEW");
    // BR-41: 승인 대기 동안 배치 조회가 제출 결과(같은 승인 요청 본문)를 다시 준다
    JsonNode pending =
        get("/content-import-batches/" + againBatch).body().path("pendingSubmission");
    assertThat(pending.path("approvalRequest")).isEqualTo(body);
    assertThat(pending.path("submittedDraftIds").path(0).asString()).isEqualTo(draftAId);
    assertThat(get("/product-contents/" + productId, CUSTOMER).status()).isEqualTo(404);

    // 6) 승인 → 게시
    decide(body, "APPROVED", false);
    JsonNode published =
        await("/content-drafts/" + draftAId, x -> "PUBLISHED".equals(x.path("state").asString()))
            .body();
    assertThat(published.path("state").asString()).isEqualTo("PUBLISHED");
    assertThat(published.path("contentVersion").asInt()).isEqualTo(1);
    assertThat(
            await(
                    "/content-import-batches/" + againBatch,
                    x -> "PUBLISHED".equals(x.path("state").asString()))
                .body()
                .path("state")
                .asString())
        .isEqualTo("PUBLISHED");

    Res content = get("/product-contents/" + productId, CUSTOMER);
    assertThat(content.status()).isEqualTo(200);
    JsonNode c = content.body();
    assertThat(c.path("name").asString()).isEqualTo("[냉동]호밍스 부대찌개 180g");
    // 0.6.0 표시사항 값은 승인 게시본에 그대로 — 모르는 칸(당류·지방)은 null
    assertThat(c.path("nutrition").path("basisUnit").asString()).isEqualTo("G");
    assertThat(c.path("nutrition").path("proteinG").asDouble()).isEqualTo(13.0);
    assertThat(
            c.path("nutrition").path("sugarsG").isNull()
                || c.path("nutrition").path("sugarsG").isMissingNode())
        .isTrue();
    assertThat(c.path("allergens").path("contains").path(0).asString()).isEqualTo("닭고기");
    assertThat(c.path("contentVersion").asInt()).isEqualTo(1);
    assertThat(c.path("productVersion").asInt()).isEqualTo(1);
    // BR-44: 게시된 상품에 새 초안을 만들면 기존 게시 콘텐츠 갱신(UPDATE, 지금 게시 버전 1)
    JsonNode update =
        post("/content-drafts", Map.of("productId", productId, "name", "[냉동]호밍스 부대찌개 180g(새 설명)"))
            .body();
    assertThat(update.path("registration").asString()).isEqualTo("UPDATE");
    assertThat(update.path("publishedContentVersion").asInt()).isEqualTo(1);
    assertThat(update.path("publishedDraftId").asString()).isEqualTo(draftAId);
    assertThat(update.path("publishedAt").asString()).isNotBlank();
    // 게시된 초안 자신은 대상이 아니다
    assertThat(
            published.path("registration").isNull()
                || published.path("registration").isMissingNode())
        .isTrue();
    assertThat(c.path("images")).hasSize(4);
    assertThat(c.path("images").path(1).path("role").asString()).isEqualTo("DESCRIPTION");
    assertThat(c.path("categoryPath").toString()).isEqualTo("[\"p03\",\"p03-c02\"]");
    assertThat(c.path("options").path(0).path("sku").asString()).isEqualTo(sku);
    assertThat(c.path("options").path(0).path("capacityLabel").asString()).isEqualTo("180g");
    // 가격(BR-36, 2026-11-19): pricing 이 답하지 않으면(여기서는 아직 스텁 없음 — 404) 가격 없이 + 출처 UNAVAILABLE(0원 아님).
    // 가용은 mfcId 를 줄 때만(없으면 채우지 않는다)
    JsonNode opt = c.path("options").path(0);
    assertThat(opt.path("price").isNull() || opt.path("price").isMissingNode()).isTrue();
    assertThat(c.path("sources").toString()).contains("pricing-service").contains("UNAVAILABLE");
    deps.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/prices"))
            .willReturn(
                okJson(
                    Map.of(
                        "items",
                        List.of(
                            Map.of(
                                "sku",
                                sku,
                                "basePrice",
                                3980,
                                "priceVersion",
                                2,
                                "effectiveFrom",
                                "2026-11-19T00:00:00Z")),
                        "notSet",
                        List.of()))));
    JsonNode priced = get("/product-contents/" + productId, null).body();
    assertThat(priced.path("options").path(0).path("price").path("basePrice").asLong())
        .isEqualTo(3980L);
    assertThat(priced.path("options").path(0).path("price").path("priceVersion").asInt())
        .isEqualTo(2);
    // 단위 가격(BR-19): 3980원 × 100 ÷ 180g = 2211.1 → 2211원
    assertThat(priced.path("options").path(0).path("netContent").path("amount").asInt())
        .isEqualTo(180);
    JsonNode unitPrice = priced.path("options").path(0).path("unitPrice");
    assertThat(unitPrice.path("basis").asInt()).isEqualTo(100);
    assertThat(unitPrice.path("unit").asString()).isEqualTo("G");
    assertThat(unitPrice.path("price").asLong()).isEqualTo(2211L);
    assertThat(opt.path("unitPrice").isNull() || opt.path("unitPrice").isMissingNode())
        .as("가격이 없으면 단위 가격도 없다")
        .isTrue();
    deps.verify(
        getRequestedFor(urlPathEqualTo("/api/v1/prices")).withQueryParam("skus", equalTo(sku)));
    assertThat(opt.path("availability").isNull() || opt.path("availability").isMissingNode())
        .isTrue();
    // BR-63(0.3.0): 리뷰가 없으면 rating 은 개수 0·평균 없음
    assertThat(c.path("rating").path("reviewCount").asInt(-1)).isZero();
    assertThat(
            c.path("rating").path("averageRating").isNull()
                || c.path("rating").path("averageRating").isMissingNode())
        .isTrue();
    String mainUrl = c.path("mainImage").path("url").asString();
    assertThat(mainUrl).startsWith("/api/v1/content-images/IMG-");

    Res cards = get("/product-contents?productIds=" + productId + ",PRD-none", CUSTOMER);
    assertThat(cards.body().path("items")).hasSize(1);
    assertThat(cards.body().path("missing").path(0).asString()).isEqualTo("PRD-none");
    assertThat(cards.body().path("items").path(0).path("nutrition").path("energyKcal").asInt())
        .isEqualTo(206);
    // 0.7.0 view=availability — productId·options[sku, availability] 만(이름·가격·평점 없음)
    JsonNode slim =
        get("/product-contents?productIds=" + productId + ",PRD-none&view=availability", null)
            .body();
    assertThat(slim.path("items").path(0).path("productId").asString()).isEqualTo(productId);
    assertThat(slim.path("items").path(0).path("options").path(0).path("sku").asString())
        .isEqualTo(sku);
    assertThat(
            slim.path("items").path(0).path("name").isNull()
                || slim.path("items").path(0).path("name").isMissingNode())
        .isTrue();
    assertThat(
            slim.path("items").path(0).path("rating").isNull()
                || slim.path("items").path(0).path("rating").isMissingNode())
        .isTrue();
    assertThat(slim.path("missing").path(0).asString()).isEqualTo("PRD-none");
    assertThat(
            cards
                .body()
                .path("items")
                .path(0)
                .path("allergens")
                .path("mayContain")
                .path(0)
                .asString())
        .isEqualTo("땅콩");
    // 0.5.0(2026-11-22 products-v8): include=search 일 때만 원산지·설명 — 원산지 구분은 상세 notices 의 "원산지:" 줄
    JsonNode noSearch = cards.body().path("items").path(0).path("search");
    assertThat(noSearch.isNull() || noSearch.isMissingNode()).isTrue();
    JsonNode search =
        get("/product-contents?productIds=" + productId + "&include=search", null)
            .body()
            .path("items")
            .path(0)
            .path("search");
    java.util.List<String> notices = new java.util.ArrayList<>();
    c.path("notices").forEach(n -> notices.add(n.asString()));
    var origin = com.pmart.product.content.domain.OriginRules.of(notices);
    assertThat(search.path("originText").asString(null))
        .isEqualTo(
            origin.map(com.pmart.product.content.domain.OriginRules.Origin::text).orElse(null));
    assertThat(search.path("originType").asString(null))
        .isEqualTo(origin.map(o -> o.type() == null ? null : o.type().name()).orElse(null));
    assertThat(search.path("description").asString(null))
        .isEqualTo(c.path("description").asString(null));
    // BR-63(0.3.0): 리뷰 사건 → 상세·카드 rating 이 평점 집계와 같다
    review("ReviewPublished", "RV-A-" + productId, productId, 5, 1);
    review("ReviewPublished", "RV-B-" + productId, productId, 2, 1);
    JsonNode rated = null;
    for (int i = 0; i < 100; i++) {
      rated = get("/product-contents/" + productId, null).body().path("rating");
      if (rated.path("reviewCount").asInt() == 2) {
        break;
      }
      Thread.sleep(200);
    }
    assertThat(rated.path("reviewCount").asInt()).isEqualTo(2);
    assertThat(rated.path("averageRating").asDouble()).isEqualTo(3.5);
    assertThat(rated.path("ratingCounts").path("5").asInt()).isEqualTo(1);
    JsonNode cardRating =
        get("/product-contents?productIds=" + productId, null)
            .body()
            .path("items")
            .path(0)
            .path("rating");
    assertThat(cardRating.path("reviewCount").asInt()).isEqualTo(2);
    assertThat(cardRating.path("averageRating").asDouble()).isEqualTo(3.5);

    // 담당 센터(mfcId) 가용 사본 — mfc-inventory 내부 API(서비스 토큰)
    stock(sku, 7, true);
    JsonNode av =
        get("/product-contents/" + productId + "?mfcId=MFC-1", null)
            .body()
            .path("options")
            .path(0)
            .path("availability");
    assertThat(av.path("carried").asBoolean()).isTrue();
    assertThat(av.path("state").asString()).isEqualTo("AVAILABLE");
    deps.verify(
        getRequestedFor(urlPathEqualTo("/api/v1/internal/availability"))
            .withQueryParam("mfcId", equalTo("MFC-1"))
            .withQueryParam("sku", equalTo(sku))
            .withoutHeader("Authorization") // 서비스 간 인증 없음(ADR-0026)
            .withHeader("X-Source-System", equalTo("product-content-service")));
    assertThat(
            get("/product-contents?productIds=" + productId + "&mfcId=MFC-1", null)
                .body()
                .path("items")
                .path(0)
                .path("options")
                .path(0)
                .path("availability")
                .path("state")
                .asString())
        .isEqualTo("AVAILABLE");
    stock(sku, 0, true);
    assertThat(
            get("/product-contents/" + productId + "?mfcId=MFC-1", CUSTOMER)
                .body()
                .path("options")
                .path(0)
                .path("availability")
                .path("state")
                .asString())
        .isEqualTo("SOLD_OUT");
    stock(sku, 5, false);
    av =
        get("/product-contents/" + productId + "?mfcId=MFC-1", CUSTOMER)
            .body()
            .path("options")
            .path(0)
            .path("availability");
    assertThat(av.path("carried").asBoolean(true)).as("센터 취급 안 함").isFalse();
    // 재고 조회 실패 → 화면은 그대로, 가용 불명(0·품절 아님), 출처 UNAVAILABLE
    deps.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/availability"))
            .willReturn(aResponse().withStatus(503)));
    JsonNode failed = get("/product-contents/" + productId + "?mfcId=MFC-1", CUSTOMER).body();
    assertThat(failed.path("name").asString()).isEqualTo("[냉동]호밍스 부대찌개 180g");
    assertThat(failed.path("options").path(0).path("availability").path("state").asString())
        .isEqualTo("UNKNOWN");
    assertThat(failed.path("sources").toString()).contains("mfc-inventory-service", "UNAVAILABLE");

    // 7) 이미지 본문: 고객은 게시된 이미지만, 운영자는 초안 이미지도
    Bytes served = image(mainUrl, CUSTOMER);
    assertThat(served.status()).isEqualTo(200);
    assertThat(served.body()).isEqualTo(main);
    assertThat(served.contentType()).isEqualTo("image/jpeg");
    // 0.3.0 축소본: 풀 수 없는 본문(시험 조각)은 원본 그대로, 허용 밖 폭은 400
    assertThat(image(mainUrl + "?w=720", null).body()).isEqualTo(main);
    assertThat(image(mainUrl + "?w=1000", null).status()).isEqualTo(400);
    // 2026-11-20 축소본 미리 만들기: 올린 이미지 내용은 뒤에서 차례가 돌아 기록된다(시험 조각은 풀 수 없어 만든 것은 없음)
    String mainSha =
        jdbc.queryForObject(
            "SELECT sha256 FROM content_image WHERE image_id = ?",
            String.class,
            mainUrl.substring(mainUrl.lastIndexOf('/') + 1));
    long prepDeadline = System.currentTimeMillis() + 60_000;
    Integer prepared = 0;
    while (System.currentTimeMillis() < prepDeadline) {
      prepared =
          jdbc.queryForObject(
              "SELECT count(*) FROM content_image_variant WHERE sha256 = ?",
              Integer.class,
              mainSha);
      if (prepared != null && prepared > 0) {
        break;
      }
      Thread.sleep(500);
    }
    assertThat(prepared).as("축소본 미리 만들기 기록").isEqualTo(1);
    String draftBImage = draftB.path("images").path(0).path("url").asString();
    assertThat(image(draftBImage, CUSTOMER).status()).isEqualTo(404);
    // 비회원(토큰 없음)도 게시본만: 상세·카드·게시 이미지 200, 초안 이미지 404
    assertThat(get("/product-contents/" + productId, null).status()).isEqualTo(200);
    assertThat(get("/product-contents?productIds=" + productId, null).body().path("items"))
        .hasSize(1);
    assertThat(image(mainUrl, null).body()).isEqualTo(main);
    assertThat(image(draftBImage, null).status()).isEqualTo(404);
    assertThat(image(draftBImage, MD).body()).isEqualTo(d1);

    // 8) 발행: ProductContentPublished 가 commerce.product.v1 로 Outbox 에
    assertThat(
            count(
                "SELECT COUNT(*) FROM outbox_event WHERE type = 'ProductContentPublished'"
                    + " AND aggregateid = ? AND topic = 'commerce.product.v1'",
                productId))
        .isEqualTo(1);

    // 9) 게시 후 같은 묶음을 다시 올려도 새 초안이 생기지 않는다(바뀐 것 없음)
    Res third = post("/content-import-batches", file(zip, "seed-v1.zip"));
    assertThat(third.body().path("unchangedCount").asInt()).isEqualTo(2);
    assertThat(count("SELECT COUNT(*) FROM content_draft WHERE source_key IN (?, ?)", a, b))
        .isEqualTo(2);
  }

  @Test
  void singleDraftImageRulesScopeMismatchAndRejection() throws Exception {
    String productId = "PRD-" + UUID.randomUUID();
    productPublished(productId, 3, "E-99999999", null);
    // 투영이 생길 때까지 기다린다(명시 productId 는 수신한 상품이어야 한다)
    Instant until = Instant.now().plus(Duration.ofSeconds(30));
    while (count("SELECT COUNT(*) FROM product_projection WHERE product_id = ?", productId) == 0
        && Instant.now().isBefore(until)) {
      Thread.sleep(200);
    }
    assertThat(
            post("/content-drafts", Map.of("name", "x", "productId", "PRD-unknown"))
                .body()
                .path("code")
                .asString())
        .isEqualTo("PRODUCT_NOT_FOUND");

    Res created =
        post(
            "/content-drafts",
            Map.of("productId", productId, "name", "삼다수 500mL", "notices", List.of("용량: 500mL")));
    // 0.6.0 표시사항 값 검사 — 법정 이름이 아닌 알레르기(계란 → 알류로 적어야 함)·음수 영양값은 400
    assertThat(
            post(
                    "/content-drafts",
                    Map.of(
                        "productId",
                        productId,
                        "name",
                        "삼다수 500mL",
                        "allergens",
                        Map.of("contains", List.of("계란"))))
                .status())
        .isEqualTo(400);
    assertThat(
            post(
                    "/content-drafts",
                    Map.of(
                        "productId",
                        productId,
                        "name",
                        "삼다수 500mL",
                        "nutrition",
                        Map.of("basisUnit", "ML", "sodiumMg", -1)))
                .status())
        .isEqualTo(400);
    assertThat(created.status()).isEqualTo(201);
    String draftId = created.body().path("draftId").asString();
    assertThat(created.body().path("problems").toString()).contains("MAIN_IMAGE_REQUIRED");

    // PCT-1(0.4.0): 초안 목록(최근 바뀐 순·state·productId·커서)과 상태별 수
    Res listDraft2 = post("/content-drafts", Map.of("productId", productId, "name", "삼다수 2L"));
    String draft2 = listDraft2.body().path("draftId").asString();
    Res listed = get("/content-drafts?productId=" + productId + "&state=DRAFT&size=1");
    assertThat(listed.status()).as(listed.body().toString()).isEqualTo(200);
    assertThat(listed.body().path("items").findValuesAsString("draftId")).containsExactly(draft2);
    assertThat(listed.body().path("items").path(0).path("name").asString()).isEqualTo("삼다수 2L");
    String next = listed.body().path("page").path("nextCursor").asString();
    assertThat(next).isNotBlank();
    assertThat(
            get("/content-drafts?productId=" + productId + "&state=DRAFT&size=1&cursor=" + next)
                .body()
                .path("items")
                .findValuesAsString("draftId"))
        .containsExactly(draftId);
    assertThat(
            get("/content-drafts?productId=" + productId + "&state=PUBLISHED").body().path("items"))
        .isEmpty();
    assertThat(get("/content-drafts?state=NOPE").status()).isEqualTo(400);
    assertThat(get("/content-drafts?cursor=bad!").status()).isEqualTo(400);
    assertThat(get("/content-drafts", CUSTOMER).status()).isEqualTo(403);
    Res counts = get("/content-drafts/counts?productId=" + productId);
    assertThat(counts.status()).isEqualTo(200);
    Map<String, Long> byState = new java.util.LinkedHashMap<>();
    counts
        .body()
        .path("counts")
        .forEach(c -> byState.put(c.path("key").asString(), c.path("count").asLong()));
    assertThat(byState)
        .containsExactly(
            Map.entry("DRAFT", 2L),
            Map.entry("SUBMITTED", 0L),
            Map.entry("PUBLISHED", 0L),
            Map.entry("REJECTED", 0L));

    // 이미지 규칙: 형식·MAIN 자리
    var notImage = file("not an image".getBytes(StandardCharsets.UTF_8), "a.txt");
    notImage.add("role", "MAIN");
    assertThat(
            post("/content-drafts/" + draftId + "/images", notImage).body().path("code").asString())
        .isEqualTo("IMAGE_INVALID");
    var secondMain = file(jpeg("m2"), "m.jpg");
    secondMain.add("role", "MAIN");
    secondMain.add("sortOrder", "1");
    assertThat(post("/content-drafts/" + draftId + "/images", secondMain).status()).isEqualTo(422);

    var mainForm = file(jpeg("water-" + productId), "main.jpg");
    mainForm.add("role", "MAIN");
    Res uploaded = post("/content-drafts/" + draftId + "/images", mainForm);
    assertThat(uploaded.status()).isEqualTo(201);
    assertThat(uploaded.body().path("sha256").asString()).hasSize(64);
    assertThat(
            jdbc.queryForObject(
                "SELECT object_key FROM content_image WHERE image_id = ?",
                String.class,
                uploaded.body().path("imageId").asString()))
        .startsWith("products/" + productId + "/main/");
    // 0.8.0 사진 규격 — 경고만(가로·세로를 못 읽은 합성 JPEG), 제출은 막지 않는다
    JsonNode warned = get("/content-drafts/" + draftId).body();
    assertThat(warned.path("warnings").toString()).contains("IMAGE_SIZE_UNKNOWN", "images[MAIN:0]");
    assertThat(warned.path("submittable").asBoolean()).isTrue();
    JsonNode specs = get("/content-drafts/image-specs").body();
    assertThat(specs.path("items").path(0).path("role").asString()).isEqualTo("MAIN");
    assertThat(specs.path("items").path(0).path("ratioLabel").asString()).isEqualTo("1:1");
    assertThat(specs.path("items").path(0).path("recommendedWidth").asInt()).isEqualTo(1000);
    assertThat(specs.path("maxBytes").asLong()).isEqualTo(20L * 1024 * 1024);

    // 잠근 해시와 다른 승인은 반영하지 않는다
    Res sub = post("/content-drafts/" + draftId + "/submissions", null);
    assertThat(sub.status()).isEqualTo(200);
    assertThat(sub.body().path("approvalRequest").path("targetRef").asString())
        .isEqualTo("contentDraft:" + draftId);
    assertThat(
            post("/content-drafts/" + draftId + "/images", mainForm).body().path("code").asString())
        .isEqualTo("DRAFT_NOT_EDITABLE");
    String mismatchApproval = decide(sub.body().path("approvalRequest"), "APPROVED", true);
    JsonNode mismatch =
        await("/content-drafts/" + draftId, x -> "REJECTED".equals(x.path("state").asString()))
            .body();
    assertThat(mismatch.path("problems").toString()).contains("APPROVAL_SCOPE_MISMATCH");
    // 반영 회신(RC-1): 승인했지만 반영 못 함 → REJECTED, 행 사유 SCOPE_MISMATCH
    JsonNode mismatchReceipt = receipt(mismatchApproval);
    assertThat(mismatchReceipt).as("반영 회신").isNotNull();
    assertThat(mismatchReceipt.path("receiverService").asString())
        .isEqualTo("product-content-service");
    assertThat(mismatchReceipt.path("result").asString()).isEqualTo("REJECTED");
    assertThat(mismatchReceipt.path("lines").path(0).path("reasonCode").asString())
        .isEqualTo("SCOPE_MISMATCH");
    assertThat(get("/product-contents/" + productId, CUSTOMER).status()).isEqualTo(404);

    // 반려된 초안도 게시하지 않는다
    Res second = post("/content-drafts", Map.of("productId", productId, "name", "삼다수"));
    String secondId = second.body().path("draftId").asString();
    var m = file(jpeg("water2-" + productId), "main.jpg");
    m.add("role", "MAIN");
    post("/content-drafts/" + secondId + "/images", m);
    decide(
        post("/content-drafts/" + secondId + "/submissions", null).body().path("approvalRequest"),
        "REJECTED",
        false);
    JsonNode rejected =
        await("/content-drafts/" + secondId, x -> "REJECTED".equals(x.path("state").asString()))
            .body();
    assertThat(rejected.path("problems").toString()).contains("APPROVAL_REJECTED");
    assertThat(get("/product-contents/" + productId, CUSTOMER).status()).isEqualTo(404);
    assertThat(get("/content-drafts/" + secondId, CUSTOMER).status()).isEqualTo(403);
  }
}
