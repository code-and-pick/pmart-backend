package com.pmart.review;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.pmart.common.test.EventEnvelopeFixtures;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import com.pmart.review.application.ReviewPhotoStorage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 리뷰 1차(0.2.0, ADR-0063): 배송 완료 → 자격, 사진 올리고 작성(ReviewPublished), 남의 품목·중복·전부 반품·기한 지남 거절, 수정, 삭제 뒤
 * 재작성 금지(ReviewDeleted), 공개 목록 요약, 사진은 공개 리뷰에 붙은 것만.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.messaging.outbox.delete-after-insert=false",
      "pmart.review.order-base-url=${order.url}",
      "pmart.review.customer-base-url=${customer.url}",
      "pmart.review.center-master-base-url=${center.url}",
      "pmart.review.content-base-url=${content.url}",
      "pmart.test.context=reviews"
    })
@Import({TestJwtConfiguration.class, ReviewsIT.MemoryPhotos.class})
@EnableWireMock({
  @ConfigureWireMock(name = "order", baseUrlProperties = "order.url"),
  @ConfigureWireMock(name = "customer", baseUrlProperties = "customer.url"),
  @ConfigureWireMock(name = "center", baseUrlProperties = "center.url"),
  @ConfigureWireMock(name = "content", baseUrlProperties = "content.url")
})
class ReviewsIT extends PostgresKafkaContainerSupport {

  static final Map<String, byte[]> STORED = new ConcurrentHashMap<>();
  static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x01, 0x02};

  @TestConfiguration
  static class MemoryPhotos {
    @Bean
    @Primary
    ReviewPhotoStorage memoryReviewPhotoStorage() {
      return new ReviewPhotoStorage() {
        @Override
        public void put(String key, byte[] bytes, String contentType) {
          STORED.put(key, bytes);
        }

        @Override
        public InputStream get(String key) {
          byte[] b = STORED.get(key);
          if (b == null) { // 실제 저장소와 같게(ReviewPhotoStorage 계약 — 없으면 404 PHOTO_NOT_FOUND)
            throw com.pmart.common.web.PmartException.notFound("PHOTO_NOT_FOUND", "사진이 없습니다.");
          }
          return new ByteArrayInputStream(b);
        }

        @Override
        public boolean delete(String key) {
          STORED.remove(key);
          return true;
        }
      };
    }
  }

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired KafkaTemplate<String, String> kafka;

  @InjectWireMock("order")
  WireMockServer order;

  @InjectWireMock("customer")
  WireMockServer customer;

  @InjectWireMock("center")
  WireMockServer center;

  @InjectWireMock("content")
  WireMockServer content;

  static final String CONFIRMED_AT = "2026-11-10T01:00:00Z";

  record Res(int status, JsonNode body) {}

  Res send(String method, @Nullable String token, String path, @Nullable Object body) {
    var spec =
        RestClient.builder()
            .requestFactory(new JdkClientHttpRequestFactory())
            .build()
            .method(HttpMethod.valueOf(method))
            .uri("http://localhost:" + port + "/api/v1" + path)
            .headers(
                h -> {
                  if (token != null) {
                    h.setBearerAuth(token);
                  }
                  h.set("Idempotency-Key", UUID.randomUUID().toString());
                  h.set("X-Source-System", "customer-app");
                });
    var e =
        body == null
            ? spec
            : spec.contentType(MediaType.APPLICATION_JSON).body(mapper.writeValueAsString(body));
    return e.exchange(
        (req, res) -> read(res.getStatusCode().value(), res.getBody().readAllBytes()));
  }

  Res read(int status, byte[] b) {
    JsonNode n;
    try {
      n = b.length == 0 ? mapper.createObjectNode() : mapper.readTree(b);
    } catch (RuntimeException ex) {
      n = mapper.createObjectNode().put("raw", b.length);
    }
    return new Res(status, n);
  }

  Res upload(String token, byte[] bytes) {
    LinkedMultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
    form.add(
        "file",
        new ByteArrayResource(bytes) {
          @Override
          public String getFilename() {
            return "p.jpg";
          }
        });
    return RestClient.builder()
        .requestFactory(new JdkClientHttpRequestFactory())
        .build()
        .post()
        .uri("http://localhost:" + port + "/api/v1/review-photos")
        .headers(
            h -> {
              h.setBearerAuth(token);
              h.set("Idempotency-Key", UUID.randomUUID().toString());
              h.set("X-Source-System", "customer-app");
            })
        .contentType(MediaType.MULTIPART_FORM_DATA)
        .body(form)
        .exchange((req, res) -> read(res.getStatusCode().value(), res.getBody().readAllBytes()));
  }

  /** 머리말만 있는 20000×20000 PNG(몇십 바이트, 다 풀면 1.6GB). */
  static byte[] pngBomb() {
    java.nio.ByteBuffer ihdr = java.nio.ByteBuffer.allocate(17);
    ihdr.put("IHDR".getBytes()).putInt(20000).putInt(20000).put((byte) 8).put((byte) 2);
    ihdr.put((byte) 0).put((byte) 0).put((byte) 0);
    java.util.zip.CRC32 crc = new java.util.zip.CRC32();
    crc.update(ihdr.array());
    return java.nio.ByteBuffer.allocate(8 + 4 + 17 + 4)
        .put(new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10})
        .putInt(13)
        .put(ihdr.array())
        .putInt((int) crc.getValue())
        .array();
  }

  /** order 내부 주문(품목 두 개). returned = 첫 품목 반품 수량. */
  void stubOrder(String orderId, String account, String productId, int returned) {
    order.stubFor(
        get(urlPathEqualTo("/api/v1/internal/orders/" + orderId))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {"orderId":"%1$s","accountId":"%2$s","mfcId":"MFC-%1$s","confirmedAt":"%5$s","lines":[
                          {"orderLineId":"%1$s-L1","sku":"E-00000001","productId":"%3$s","productName":"사과","deliveredQty":0,"returnedQty":%4$d},
                          {"orderLineId":"%1$s-L2","sku":"E-00000002","productId":"%3$s","productName":"배","deliveredQty":0,"returnedQty":0}
                        ]}"""
                            .formatted(orderId, account, productId, returned, CONFIRMED_AT))));
    customer.stubFor(
        get(urlPathMatching("/api/v1/internal/accounts/.*/review-author"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"accountId\":\"x\",\"displayName\":\"장***\"}")));
  }

  void delivered(String orderId, Instant at, String... lineIds) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("deliveryId", "DLV-" + orderId);
    p.put("orderId", orderId);
    p.put("riderId", "RD-1");
    p.put(
        "lines",
        java.util.Arrays.stream(lineIds).map(l -> Map.of("orderLineId", l, "qty", 1)).toList());
    p.put("method", "CONTACTLESS");
    p.put("evidenceRef", "ev-1");
    p.put("deliveredAt", at.toString());
    kafka
        .send(
            "delivery.delivery.v1",
            "DLV-" + orderId,
            EventEnvelopeFixtures.envelope("DeliveryCompleted")
                .from("delivery-execution-service")
                .aggregate("Delivery", "DLV-" + orderId, 1)
                .payload(p)
                .buildJson())
        .join();
  }

  static <T> T await(Supplier<T> s, Predicate<T> ok) throws InterruptedException {
    Instant until = Instant.now().plus(Duration.ofSeconds(30));
    T v = s.get();
    while (!ok.test(v) && Instant.now().isBefore(until)) {
      Thread.sleep(200);
      v = s.get();
    }
    return v;
  }

  int count(String sql, Object... args) {
    return jdbc.queryForObject(sql, Integer.class, args);
  }

  String newId() {
    return UUID.randomUUID().toString().substring(0, 8);
  }

  @Test
  void deliveredLineCanBeReviewedOnceWithPhotos() throws Exception {
    String orderId = "ORD-RV-" + newId();
    String me = "ACC-rv-" + newId();
    String productId = "PRD-" + newId();
    String token = TestJwt.customer(me);
    stubOrder(orderId, me, productId, 0);
    delivered(orderId, Instant.now().minus(Duration.ofDays(1)), orderId + "-L1", orderId + "-L2");

    Res el =
        await(
            () -> send("GET", token, "/me/review-eligibilities?orderId=" + orderId, null),
            r -> r.body().path("items").size() == 2);
    assertThat(el.body().path("items").get(0).path("state").asString()).isEqualTo("REV_ELIGIBLE");
    assertThat(el.body().path("items").get(0).path("productName").asString()).isIn("사과", "배");

    // 사진: 형식이 틀리면 422, 맞으면 201
    assertThat(upload(token, "GIF89a".getBytes()).status()).isEqualTo(422);
    // 0.4.1: 화소 수 상한(작은 파일로 거대한 그림) — 422
    Res bomb = upload(token, pngBomb());
    assertThat(bomb.status()).isEqualTo(422);
    assertThat(bomb.body().path("code").asString()).isEqualTo("PHOTO_INVALID");
    Res photo = upload(token, JPEG);
    assertThat(photo.status()).isEqualTo(201);
    String photoId = photo.body().path("photoId").asString();
    // 붙기 전 사진은 공개되지 않는다
    assertThat(send("GET", null, "/review-photos/" + photoId, null).status()).isEqualTo(404);

    // 남의 사진은 못 붙인다
    String other = TestJwt.customer("ACC-other-" + newId());
    String otherPhoto = upload(other, JPEG).body().path("photoId").asString();
    Res bad =
        send(
            "POST",
            token,
            "/reviews",
            Map.of("orderLineId", orderId + "-L1", "rating", 5, "photoIds", List.of(otherPhoto)));
    assertThat(bad.status()).isEqualTo(422);
    assertThat(bad.body().path("code").asString()).isEqualTo("PHOTO_NOT_FOUND");

    Res created =
        send(
            "POST",
            token,
            "/reviews",
            Map.of(
                "orderLineId",
                orderId + "-L1",
                "rating",
                5,
                "text",
                "  신선해요  ",
                "photoIds",
                List.of(photoId)));
    assertThat(created.status()).isEqualTo(201);
    String reviewId = created.body().path("reviewId").asString();
    assertThat(created.body().path("state").asString()).isEqualTo("PUBLISHED");
    assertThat(created.body().path("text").asString()).isEqualTo("신선해요");
    assertThat(created.body().path("authorMasked").asString()).isEqualTo("장***");
    assertThat(created.body().path("photos").get(0).path("url").asString())
        .isEqualTo("/api/v1/review-photos/" + photoId);
    assertThat(
            count(
                "SELECT count(*) FROM outbox_event WHERE aggregateid = ? AND type = 'ReviewPublished'",
                reviewId))
        .isEqualTo(1);

    // 공개: 사진 본문·단건·목록 요약
    assertThat(send("GET", null, "/review-photos/" + photoId, null).status()).isEqualTo(200);
    // 0.4.0 축소본: 풀 수 없는 시험 조각은 원본 그대로, 허용 밖 폭은 400
    Res small = send("GET", null, "/review-photos/" + photoId + "?w=240", null);
    assertThat(small.status()).isEqualTo(200);
    assertThat(small.body().path("raw").asInt()).isEqualTo(JPEG.length);
    assertThat(send("GET", null, "/review-photos/" + photoId + "?w=1000", null).status())
        .isEqualTo(400);
    assertThat(send("GET", null, "/reviews/" + reviewId, null).status()).isEqualTo(200);
    Res list = send("GET", null, "/products/" + productId + "/reviews?withPhotoOnly=true", null);
    assertThat(list.body().path("items").size()).isEqualTo(1);
    assertThat(list.body().path("summary").path("reviewCount").asInt()).isEqualTo(1);
    assertThat(list.body().path("summary").path("averageRating").asDouble()).isEqualTo(5.0);

    // 같은 품목 두 번째 → ALREADY_REVIEWED, 남의 품목 → NOT_ELIGIBLE
    assertThat(
            send("POST", token, "/reviews", Map.of("orderLineId", orderId + "-L1", "rating", 4))
                .body()
                .path("code")
                .asString())
        .isEqualTo("ALREADY_REVIEWED");
    assertThat(
            send("POST", other, "/reviews", Map.of("orderLineId", orderId + "-L2", "rating", 4))
                .body()
                .path("code")
                .asString())
        .isEqualTo("NOT_ELIGIBLE");

    // 수정(별점 3, 사진 빼기) → 버전 2, ReviewPublished 한 번 더, 떨어진 사진은 비공개
    Res updated =
        send("PATCH", token, "/reviews/" + reviewId, Map.of("rating", 3, "photoIds", List.of()));
    assertThat(updated.status()).isEqualTo(200);
    assertThat(updated.body().path("rating").asInt()).isEqualTo(3);
    assertThat(updated.body().path("version").asInt()).isEqualTo(2);
    assertThat(updated.body().path("photos").size()).isZero();
    assertThat(send("GET", null, "/review-photos/" + photoId, null).status()).isEqualTo(404);
    // 남이 고치려 하면 404
    assertThat(send("PATCH", other, "/reviews/" + reviewId, Map.of("rating", 1)).status())
        .isEqualTo(404);
    // 토큰 없이 고치기는 403
    assertThat(send("PATCH", null, "/reviews/" + reviewId, Map.of("rating", 1)).status())
        .isIn(401, 403);

    // 삭제 → 204, 다시 삭제도 204, 공개 404, 재작성 금지, ReviewDeleted 한 번
    assertThat(send("DELETE", token, "/reviews/" + reviewId, null).status()).isEqualTo(204);
    assertThat(send("DELETE", token, "/reviews/" + reviewId, null).status()).isEqualTo(204);
    assertThat(send("GET", null, "/reviews/" + reviewId, null).status()).isEqualTo(404);
    assertThat(
            send("POST", token, "/reviews", Map.of("orderLineId", orderId + "-L1", "rating", 5))
                .body()
                .path("code")
                .asString())
        .isEqualTo("REWRITE_AFTER_DELETE_FORBIDDEN");
    assertThat(
            count(
                "SELECT count(*) FROM outbox_event WHERE aggregateid = ? AND type = 'ReviewDeleted'",
                reviewId))
        .isEqualTo(1);
    Res el2 =
        send("GET", token, "/me/review-eligibilities?orderId=" + orderId + "&state=DELETED", null);
    assertThat(el2.body().path("items").size()).isEqualTo(1);
    assertThat(el2.body().path("items").get(0).path("reviewId").asString()).isEqualTo(reviewId);
  }

  /** BR-76(0.6.0): 추천순(도움돼요 많은 순)·옵션(SKU) 거르기·거른 수, 도움돼요 누르기·취소(회원만, 멱등, 내 리뷰 422, 삭제 409). */
  @Test
  void recommendedSortSkuFilterAndHelpful() throws Exception {
    String orderId = "ORD-HP-" + newId();
    String me = "ACC-hp-" + newId();
    String productId = "PRD-" + newId();
    String token = TestJwt.customer(me);
    String other = TestJwt.customer("ACC-hp-other-" + newId());
    stubOrder(orderId, me, productId, 0);
    delivered(orderId, Instant.now().minus(Duration.ofDays(1)), orderId + "-L1", orderId + "-L2");
    await(
        () -> send("GET", token, "/me/review-eligibilities?orderId=" + orderId, null),
        r -> r.body().path("items").size() == 2);
    String first =
        send("POST", token, "/reviews", Map.of("orderLineId", orderId + "-L1", "rating", 5))
            .body()
            .path("reviewId")
            .asString();
    String second =
        send("POST", token, "/reviews", Map.of("orderLineId", orderId + "-L2", "rating", 3))
            .body()
            .path("reviewId")
            .asString();

    // 도움돼요: 내 리뷰 422, 다른 회원 누르기(두 번 눌러도 1), 비회원 401·403
    Res own = send("PUT", token, "/reviews/" + second + "/helpful", null);
    assertThat(own.status()).isEqualTo(422);
    assertThat(own.body().path("code").asString()).isEqualTo("HELPFUL_OWN_REVIEW");
    Res mark = send("PUT", other, "/reviews/" + second + "/helpful", null);
    assertThat(mark.status()).as(mark.body().toString()).isEqualTo(200);
    assertThat(mark.body().path("helpfulCount").asInt()).isEqualTo(1);
    assertThat(mark.body().path("helpedByMe").asBoolean()).isTrue();
    assertThat(
            send("PUT", other, "/reviews/" + second + "/helpful", null)
                .body()
                .path("helpfulCount")
                .asInt())
        .isEqualTo(1);
    assertThat(send("PUT", null, "/reviews/" + second + "/helpful", null).status()).isIn(401, 403);

    // 추천순: 도움돼요 많은 second 먼저(최근 등록은 second 지만 별점순이 아님을 보려고 first 를 1위로 만들지 않는다)
    Res rec = send("GET", other, "/products/" + productId + "/reviews?sort=RECOMMENDED", null);
    assertThat(rec.body().path("items").path(0).path("reviewId").asString()).isEqualTo(second);
    assertThat(rec.body().path("items").path(0).path("helpfulCount").asInt()).isEqualTo(1);
    assertThat(rec.body().path("items").path(0).path("helpedByMe").asBoolean()).isTrue();
    assertThat(rec.body().path("items").path(1).path("helpedByMe").asBoolean()).isFalse();
    Res anon = send("GET", null, "/products/" + productId + "/reviews?sort=RECOMMENDED", null);
    assertThat(anon.body().path("items").path(0).path("helpedByMe").isNull()).isTrue();
    // 옵션 거르기 — 거른 수
    Res bySku = send("GET", null, "/products/" + productId + "/reviews?sku=E-00000001", null);
    assertThat(bySku.body().path("items").size()).isEqualTo(1);
    assertThat(bySku.body().path("items").path(0).path("reviewId").asString()).isEqualTo(first);
    assertThat(bySku.body().path("summary").path("reviewCount").asInt()).isEqualTo(1);
    assertThat(bySku.body().path("summary").path("averageRating").asDouble()).isEqualTo(4.0);

    // 취소(두 번 해도 0), 삭제된 리뷰 누르기 409
    Res unmark = send("DELETE", other, "/reviews/" + second + "/helpful", null);
    assertThat(unmark.body().path("helpfulCount").asInt()).isZero();
    assertThat(unmark.body().path("helpedByMe").asBoolean()).isFalse();
    assertThat(
            send("DELETE", other, "/reviews/" + second + "/helpful", null)
                .body()
                .path("helpfulCount")
                .asInt())
        .isZero();
    assertThat(send("DELETE", token, "/reviews/" + first, null).status()).isEqualTo(204);
    Res gone = send("PUT", other, "/reviews/" + first + "/helpful", null);
    assertThat(gone.status()).isEqualTo(409);
    assertThat(gone.body().path("code").asString()).isEqualTo("REVIEW_NOT_PUBLISHED");
  }

  /**
   * BR-90(0.7.0): 신고 — 회원만·한 번·내 리뷰 422·기타는 글 필수, 목록 카드 reportedByMe·mine, 운영자 목록·처리(CS_AGENT), 숨기면
   * 공개 목록·단건에서 빠지고 ReviewDeleted(OPERATOR_HIDDEN), 작성자에게는 LOCKED, 다른 역할 403.
   */
  @Test
  void reportAndOperatorHide() throws Exception {
    String orderId = "ORD-RP-" + newId();
    String me = "ACC-rp-" + newId();
    String productId = "PRD-" + newId();
    String token = TestJwt.customer(me);
    String other = TestJwt.customer("ACC-rp-other-" + newId());
    String third = TestJwt.customer("ACC-rp-third-" + newId());
    String cs = TestJwt.operator("OP-cs-" + newId(), List.of("CS_AGENT"), List.of());
    String picker = TestJwt.operator("OP-mfc-" + newId(), List.of("MFC_WORKER"), List.of());
    stubOrder(orderId, me, productId, 0);
    delivered(orderId, Instant.now().minus(Duration.ofDays(1)), orderId + "-L1");
    await(
        () -> send("GET", token, "/me/review-eligibilities?orderId=" + orderId, null),
        r -> r.body().path("items").size() == 1);
    String photoId = upload(token, JPEG).body().path("photoId").asString();
    String reviewId =
        send(
                "POST",
                token,
                "/reviews",
                Map.of("orderLineId", orderId + "-L1", "rating", 1, "photoIds", List.of(photoId)))
            .body()
            .path("reviewId")
            .asString();

    // 고객 신고: 내 리뷰 422, 기타 글 없음 400, 신고 201 APPLIED, 두 번째 409, 비회원 401·403
    Res own = send("POST", token, "/reviews/" + reviewId + "/reports", Map.of("reason", "AD"));
    assertThat(own.status()).isEqualTo(422);
    assertThat(own.body().path("code").asString()).isEqualTo("REPORT_OWN_REVIEW");
    assertThat(
            send("POST", other, "/reviews/" + reviewId + "/reports", Map.of("reason", "OTHER"))
                .status())
        .isEqualTo(400);
    Res ok =
        send(
            "POST",
            other,
            "/reviews/" + reviewId + "/reports",
            Map.of("reason", "OTHER", "detail", "주문과 상관없는 글"));
    assertThat(ok.status()).as(ok.body().toString()).isEqualTo(201);
    assertThat(ok.body().path("status").asString()).isEqualTo("APPLIED");
    Res again = send("POST", other, "/reviews/" + reviewId + "/reports", Map.of("reason", "AD"));
    assertThat(again.status()).isEqualTo(409);
    assertThat(again.body().path("code").asString()).isEqualTo("ALREADY_REPORTED");
    assertThat(
            send("POST", third, "/reviews/" + reviewId + "/reports", Map.of("reason", "ABUSE"))
                .status())
        .isEqualTo(201);
    assertThat(
            send("POST", null, "/reviews/" + reviewId + "/reports", Map.of("reason", "AD"))
                .status())
        .isIn(401, 403);

    // 목록 카드: 신고한 회원 reportedByMe true·mine false, 작성자 mine true, 비회원 null
    JsonNode seenByOther =
        send("GET", other, "/products/" + productId + "/reviews", null)
            .body()
            .path("items")
            .path(0);
    assertThat(seenByOther.path("reportedByMe").asBoolean()).isTrue();
    assertThat(seenByOther.path("mine").asBoolean()).isFalse();
    assertThat(
            send("GET", token, "/products/" + productId + "/reviews", null)
                .body()
                .path("items")
                .path(0)
                .path("mine")
                .asBoolean())
        .isTrue();

    // 운영자: 다른 역할 403, 목록에 2건(사유별·기타 글), 숨김 처리
    assertThat(send("GET", picker, "/review-reports", null).status()).isEqualTo(403);
    JsonNode group = null;
    for (JsonNode g : send("GET", cs, "/review-reports?size=100", null).body().path("items")) {
      if (reviewId.equals(g.path("reviewId").asString())) {
        group = g;
      }
    }
    assertThat(group).isNotNull();
    assertThat(group.path("reportCount").asInt()).isEqualTo(2);
    assertThat(group.path("reasonCounts").path("ABUSE").asInt()).isEqualTo(1);
    assertThat(group.path("otherDetails").path(0).asString()).isEqualTo("주문과 상관없는 글");
    assertThat(group.path("state").asString()).isEqualTo("OPEN");
    // 0.8.0 상세: 다른 역할 403, 없는 리뷰 404, 신고 한 건 한 건(최근 순)·신고자 없음
    assertThat(send("GET", picker, "/review-reports/" + reviewId, null).status()).isEqualTo(403);
    assertThat(send("GET", cs, "/review-reports/RV-none-" + newId(), null).status()).isEqualTo(404);
    JsonNode open = send("GET", cs, "/review-reports/" + reviewId, null).body();
    assertThat(open.path("openReportCount").asInt()).isEqualTo(2);
    assertThat(open.path("reports").size()).isEqualTo(2);
    assertThat(open.path("reports").path(0).path("reason").asString()).isEqualTo("ABUSE");
    assertThat(open.path("reports").path(1).path("detail").asString()).isEqualTo("주문과 상관없는 글");
    assertThat(open.path("photos").path(0).path("url").asString())
        .isEqualTo("/api/v1/review-photos/" + photoId);
    assertThat(open.toString()).doesNotContain("ACC-rp-other-").doesNotContain("ACC-rp-third-");
    assertThat(open.path("reports").path(0).has("reporterAccountId")).isFalse();
    Res hide =
        send("POST", cs, "/review-reports/" + reviewId + "/resolutions", Map.of("action", "HIDE"));
    assertThat(hide.status()).as(hide.body().toString()).isEqualTo(200);
    assertThat(
            send(
                    "POST",
                    cs,
                    "/review-reports/" + reviewId + "/resolutions",
                    Map.of("action", "KEEP"))
                .body()
                .path("code")
                .asString())
        .isEqualTo("NO_OPEN_REPORTS");

    // 숨김: 공개 목록·단건에서 빠짐, 작성자는 LOCKED 로, ReviewDeleted 한 번
    assertThat(send("GET", null, "/products/" + productId + "/reviews", null).body().path("items"))
        .isEmpty();
    assertThat(send("GET", other, "/reviews/" + reviewId, null).status()).isEqualTo(404);
    Res mine = send("GET", token, "/reviews/" + reviewId, null);
    assertThat(mine.status()).isEqualTo(200);
    assertThat(mine.body().path("state").asString()).isEqualTo("LOCKED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE aggregateid = ? AND type = 'ReviewDeleted'",
                Integer.class,
                reviewId))
        .isEqualTo(1);
    assertThat(
            send("GET", cs, "/review-reports?state=RESOLVED&size=100", null)
                .body()
                .findValuesAsString("reviewId"))
        .contains(reviewId);

    // 0.8.0: 숨긴 뒤 상세 — 처리 결과, 사진은 상담원 토큰만(비회원·다른 역할 404)
    JsonNode done = send("GET", cs, "/review-reports/" + reviewId, null).body();
    assertThat(done.path("reviewHidden").asBoolean()).isTrue();
    assertThat(done.path("openReportCount").asInt()).isZero();
    assertThat(done.path("reports").findValuesAsString("resolution")).containsOnly("HIDE");
    assertThat(done.path("reports").findValuesAsString("state")).containsOnly("RESOLVED");
    assertThat(send("GET", null, "/review-photos/" + photoId, null).status()).isEqualTo(404);
    assertThat(send("GET", picker, "/review-photos/" + photoId, null).status()).isEqualTo(404);
    assertThat(send("GET", cs, "/review-photos/" + photoId, null).status()).isEqualTo(200);
  }

  /** CU-7(2026-11-23): customer review-author 가 이용제한(RESTRICTED)이면 리뷰 작성 403 ACCOUNT_RESTRICTED. */
  @Test
  void restrictedCustomerCannotWriteReview() throws Exception {
    String orderId = "ORD-RX-" + newId();
    String me = "ACC-rx-" + newId();
    String token = TestJwt.customer(me);
    stubOrder(orderId, me, "PRD-" + newId(), 0);
    delivered(orderId, Instant.now().minus(Duration.ofDays(1)), orderId + "-L1");
    await(
        () -> send("GET", token, "/me/review-eligibilities?orderId=" + orderId, null),
        r -> r.body().path("items").size() == 1);
    customer.stubFor(
        get(urlPathMatching("/api/v1/internal/accounts/" + me + "/review-author"))
            .atPriority(1)
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"accountId\":\"x\",\"displayName\":\"장***\",\"restriction\":\"RESTRICTED\","
                            + "\"restrictedUntil\":\"2099-01-01T00:00:00Z\"}")));
    Res r = send("POST", token, "/reviews", Map.of("orderLineId", orderId + "-L1", "rating", 5));
    assertThat(r.status()).isEqualTo(403);
    assertThat(r.body().path("code").asString()).isEqualTo("ACCOUNT_RESTRICTED");
  }

  @Test
  void fullyReturnedAndExpiredLinesCannotBeReviewed() throws Exception {
    String orderId = "ORD-RV-" + newId();
    String me = "ACC-rv-" + newId();
    String token = TestJwt.customer(me);
    stubOrder(orderId, me, "PRD-" + newId(), 1); // L1 은 받은 1개가 반품됨
    delivered(orderId, Instant.now().minus(Duration.ofDays(1)), orderId + "-L1", orderId + "-L2");
    await(
        () -> count("SELECT count(*) FROM review_eligibility WHERE order_id = ?", orderId),
        n -> n == 2);

    Res returned =
        send("POST", token, "/reviews", Map.of("orderLineId", orderId + "-L1", "rating", 2));
    assertThat(returned.status()).isEqualTo(422);
    assertThat(returned.body().path("code").asString()).isEqualTo("NOT_ELIGIBLE");

    // 수령 15일 전으로 돌리면 기한 지남
    jdbc.update(
        "UPDATE review_eligibility SET delivered_at = ? WHERE order_line_id = ?",
        Timestamp.from(Instant.now().minus(Duration.ofDays(15))),
        orderId + "-L2");
    Res expired =
        send("POST", token, "/reviews", Map.of("orderLineId", orderId + "-L2", "rating", 4));
    assertThat(expired.body().path("code").asString()).isEqualTo("ELIGIBILITY_EXPIRED");
    Res el =
        send("GET", token, "/me/review-eligibilities?orderId=" + orderId + "&state=EXPIRED", null);
    assertThat(el.body().path("items").size()).isEqualTo(1);
  }

  /**
   * 내부 조회 — 주문의 리뷰 작성 여부(0.3.0, 2026-11-18 리뷰 요청 알림): 토큰 없이, 쓰기 전 0·한 품목 쓰면 reviewed(삭제해도 그대로), 남의
   * 계정·없는 주문은 모두 0, 사용자 토큰은 403.
   */
  @Test
  void orderReviewStatusForNotification() throws Exception {
    String orderId = "ORD-RV-" + newId();
    String me = "ACC-rv-" + newId();
    String token = TestJwt.customer(me);
    stubOrder(orderId, me, "PRD-" + newId(), 0);
    delivered(orderId, Instant.now().minus(Duration.ofHours(1)), orderId + "-L1", orderId + "-L2");
    await(
        () -> count("SELECT count(*) FROM review_eligibility WHERE order_id = ?", orderId),
        n -> n == 2);
    String path = "/internal/orders/" + orderId + "/review-status?accountId=" + me;

    Res before = send("GET", null, path, null);
    assertThat(before.status()).isEqualTo(200);
    assertThat(before.body().path("eligibleLineCount").asInt()).isEqualTo(2);
    assertThat(before.body().path("reviewedLineCount").asInt()).isZero();
    assertThat(before.body().path("openLineCount").asInt()).isEqualTo(2);
    assertThat(before.body().path("reviewed").asBoolean()).isFalse();

    String reviewId =
        send("POST", token, "/reviews", Map.of("orderLineId", orderId + "-L1", "rating", 4))
            .body()
            .path("reviewId")
            .asString();
    Res after = send("GET", null, path, null);
    assertThat(after.body().path("reviewedLineCount").asInt()).isEqualTo(1);
    assertThat(after.body().path("openLineCount").asInt()).isEqualTo(1);
    assertThat(after.body().path("reviewed").asBoolean()).isTrue();

    // 삭제해도 쓴 것으로 센다(삭제 뒤 재작성 불가)
    assertThat(send("DELETE", token, "/reviews/" + reviewId, null).status()).isEqualTo(204);
    assertThat(send("GET", null, path, null).body().path("reviewed").asBoolean()).isTrue();

    // 남의 계정·없는 주문은 0
    Res other =
        send(
            "GET",
            null,
            "/internal/orders/" + orderId + "/review-status?accountId=ACC-x-" + newId(),
            null);
    assertThat(other.status()).isEqualTo(200);
    assertThat(other.body().path("eligibleLineCount").asInt()).isZero();
    assertThat(
            send("GET", null, "/internal/orders/ORD-none/review-status?accountId=" + me, null)
                .body()
                .path("eligibleLineCount")
                .asInt())
        .isZero();
    // 사용자 토큰으로 내부 경로는 403
    assertThat(send("GET", token, path, null).status()).isEqualTo(403);
  }

  void stubCenter(String mfcId, @Nullable String name) {
    center.stubFor(
        get(urlPathEqualTo("/api/v1/internal/centers/" + mfcId))
            .willReturn(
                name == null
                    ? aResponse().withStatus(404)
                    : aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"centerId\":\"" + mfcId + "\",\"name\":\"" + name + "\"}")));
  }

  void stubCards(String productId, String body) {
    content.stubFor(
        get(urlPathEqualTo("/api/v1/product-contents"))
            .withQueryParam("productIds", equalTo(productId))
            .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body)));
  }

  /**
   * 리뷰관리 표시 값(0.5.0, 2026-11-20 BR-67): total(조건에 맞는 전체 — 쪽 크기 아님)·counts(쓸 수 있는 것·쓴 것), 품목마다 주문
   * 시각·센터 이름(center-master)·지금 상품 사진·옵션 라벨(product-content 카드 일괄 조회). V4 전 행은 order 를 다시 읽어 채운다.
   */
  @Test
  void eligibilityListCarriesTotalsAndDisplayValues() throws Exception {
    String orderId = "ORD-RV-" + newId();
    String me = "ACC-rv-" + newId();
    String productId = "PRD-" + newId();
    String token = TestJwt.customer(me);
    stubOrder(orderId, me, productId, 0);
    stubCenter("MFC-" + orderId, "강남센터");
    stubCards(
        productId,
        """
        {"items":[{"productId":"%s","name":"사과",
          "mainImage":{"imageId":"IMG-1","url":"/api/v1/content-images/IMG-1","role":"MAIN"},
          "options":[{"sku":"E-00000001","label":"1팩(6입)"},{"sku":"E-00000002"}]}],
         "missing":[]}"""
            .formatted(productId));
    delivered(orderId, Instant.now().minus(Duration.ofDays(1)), orderId + "-L1", orderId + "-L2");
    await(
        () -> count("SELECT count(*) FROM review_eligibility WHERE order_id = ?", orderId),
        n -> n == 2);
    assertThat(
            count(
                "SELECT count(*) FROM review_eligibility WHERE order_id = ? AND mfc_id = ?"
                    + " AND ordered_at IS NOT NULL",
                orderId,
                "MFC-" + orderId))
        .as("자격을 만들 때 주문 확정 시각·센터 사본")
        .isEqualTo(2);
    assertThat(
            send("POST", token, "/reviews", Map.of("orderLineId", orderId + "-L2", "rating", 5))
                .status())
        .isEqualTo(201);

    // 쪽 크기 1 이어도 total 은 전체(2), counts 는 탭 머리(쓸 수 있는 1·쓴 1)
    Res page = send("GET", token, "/me/review-eligibilities?orderId=" + orderId + "&size=1", null);
    assertThat(page.status()).isEqualTo(200);
    assertThat(page.body().path("items").size()).isEqualTo(1);
    assertThat(page.body().path("total").asInt()).isEqualTo(2);
    assertThat(page.body().path("counts").path("writable").asInt()).isEqualTo(1);
    assertThat(page.body().path("counts").path("written").asInt()).isEqualTo(1);
    // 0.6.1 BR-86 — 쪽 크기 1 이어도 그 주문의 쓸 수 있는 품목 수(1)
    assertThat(page.body().path("items").path(0).path("orderWritableCount").asInt(-1)).isEqualTo(1);

    Res writable =
        send(
            "GET",
            token,
            "/me/review-eligibilities?orderId=" + orderId + "&state=REV_ELIGIBLE",
            null);
    assertThat(writable.body().path("total").asInt()).as("total 은 state 조건을 따른다").isEqualTo(1);
    assertThat(writable.body().path("counts").path("written").asInt())
        .as("counts 는 state 를 보지 않는다")
        .isEqualTo(1);
    JsonNode l1 = writable.body().path("items").get(0);
    assertThat(l1.path("orderLineId").asString()).isEqualTo(orderId + "-L1");
    assertThat(Instant.parse(l1.path("orderedAt").asString()))
        .isEqualTo(Instant.parse(CONFIRMED_AT));
    assertThat(l1.path("mfcName").asString()).isEqualTo("강남센터");
    assertThat(l1.path("imageUrl").asString()).isEqualTo("/api/v1/content-images/IMG-1");
    assertThat(l1.path("optionLabel").asString()).isEqualTo("1팩(6입)");

    Res all = send("GET", token, "/me/review-eligibilities?orderId=" + orderId, null);
    JsonNode l2 = null;
    for (JsonNode i : all.body().path("items")) {
      if (i.path("orderLineId").asString().endsWith("-L2")) {
        l2 = i;
      }
    }
    assertThat(l2).isNotNull();
    assertThat(l2.hasNonNull("optionLabel")).as("옵션 이름 없는 SKU 는 null").isFalse();
    assertThat(l2.path("imageUrl").asString()).isEqualTo("/api/v1/content-images/IMG-1");

    // V4 전 행(사본 없음) → 목록 조회가 order 를 다시 읽어 채운다
    jdbc.update(
        "UPDATE review_eligibility SET ordered_at = NULL, mfc_id = NULL WHERE order_id = ?",
        orderId);
    Res refilled = send("GET", token, "/me/review-eligibilities?orderId=" + orderId, null);
    assertThat(refilled.body().path("items").get(0).path("mfcName").asString()).isEqualTo("강남센터");
    assertThat(
            count(
                "SELECT count(*) FROM review_eligibility WHERE order_id = ? AND mfc_id IS NOT NULL",
                orderId))
        .isEqualTo(2);
  }

  /** 센터 없음(404)·게시 안 된 상품(missing)이면 그 값만 null — 지어내지 않고 목록은 그대로. 자격이 없으면 total·counts 0. */
  @Test
  void eligibilityDisplayValuesAreNullWhenUnknown() throws Exception {
    String orderId = "ORD-RV-" + newId();
    String me = "ACC-rv-" + newId();
    String productId = "PRD-" + newId();
    String token = TestJwt.customer(me);
    stubOrder(orderId, me, productId, 0);
    stubCenter("MFC-" + orderId, null);
    stubCards(productId, "{\"items\":[],\"missing\":[\"" + productId + "\"]}");
    delivered(orderId, Instant.now().minus(Duration.ofDays(1)), orderId + "-L1");
    await(
        () -> count("SELECT count(*) FROM review_eligibility WHERE order_id = ?", orderId),
        n -> n == 1);

    Res res = send("GET", token, "/me/review-eligibilities?orderId=" + orderId, null);
    assertThat(res.status()).isEqualTo(200);
    assertThat(res.body().path("total").asInt()).isEqualTo(1);
    JsonNode item = res.body().path("items").get(0);
    assertThat(item.path("orderedAt").isString()).isTrue();
    assertThat(item.hasNonNull("mfcName")).isFalse();
    assertThat(item.hasNonNull("imageUrl")).isFalse();
    assertThat(item.hasNonNull("optionLabel")).isFalse();

    Res none =
        send("GET", TestJwt.customer("ACC-none-" + newId()), "/me/review-eligibilities", null);
    assertThat(none.status()).isEqualTo(200);
    assertThat(none.body().path("total").asInt()).isZero();
    assertThat(none.body().path("counts").path("writable").asInt()).isZero();
    assertThat(none.body().path("counts").path("written").asInt()).isZero();
  }
}
