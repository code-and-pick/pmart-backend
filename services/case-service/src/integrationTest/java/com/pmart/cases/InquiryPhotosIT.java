package com.pmart.cases;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.cases.application.InquiryPhotoService;
import com.pmart.cases.application.InquiryPhotoStorage;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 문의 사진(0.14.0 — 2026-11-17 사용자 "리뷰 방식 그대로", 업무 원문 Commerce §14.1): 남긴 사람만 올리고 붙이고, 본문은 본인·그 문의를 볼
 * 수 있는 직원만, 붙지 않은 사진은 정리. 저장소는 메모리.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.messaging.outbox.delete-after-insert=false",
      "pmart.test.context=inquiry-photos"
    })
@Import({TestJwtConfiguration.class, InquiryPhotosIT.MemoryPhotos.class})
class InquiryPhotosIT extends PostgresKafkaContainerSupport {

  static final Map<String, byte[]> STORED = new ConcurrentHashMap<>();
  static final AtomicBoolean FAIL_PUT = new AtomicBoolean(false);
  static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x01, 0x02};
  static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00};

  @TestConfiguration
  static class MemoryPhotos {
    @Bean
    @Primary
    InquiryPhotoStorage memoryInquiryPhotoStorage() {
      return new InquiryPhotoStorage() {
        @Override
        public void put(String key, byte[] bytes, String contentType) {
          if (FAIL_PUT.get()) {
            throw com.pmart.common.web.PmartException.dependencyUnavailable(
                "시험: 저장소 실패", new IllegalStateException("down"));
          }
          STORED.put(key, bytes);
        }

        @Override
        public InputStream get(String key) {
          return new ByteArrayInputStream(STORED.get(key));
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
  @Autowired InquiryPhotoService photoService;

  record Res(int status, JsonNode body) {}

  record Raw(int status, byte[] body) {}

  RestClient client() {
    return RestClient.builder().requestFactory(new JdkClientHttpRequestFactory()).build();
  }

  Res send(String method, String token, String path, @Nullable Object body) {
    var spec =
        client()
            .method(HttpMethod.valueOf(method))
            .uri("http://localhost:" + port + "/api/v1" + path)
            .headers(
                h -> {
                  h.setBearerAuth(token);
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
    return new Res(status, b.length == 0 ? mapper.createObjectNode() : mapper.readTree(b));
  }

  Res upload(String token, byte[] bytes) {
    return upload(token, bytes, UUID.randomUUID().toString());
  }

  Res upload(String token, byte[] bytes, String key) {
    var form = new LinkedMultiValueMap<String, Object>();
    form.add(
        "file",
        new ByteArrayResource(bytes) {
          @Override
          public String getFilename() {
            return "p.jpg";
          }
        });
    return client()
        .post()
        .uri("http://localhost:" + port + "/api/v1/inquiry-photos")
        .headers(
            h -> {
              h.setBearerAuth(token);
              h.set("Idempotency-Key", key);
              h.set("X-Source-System", "customer-app");
            })
        .contentType(MediaType.MULTIPART_FORM_DATA)
        .body(form)
        .exchange((req, res) -> read(res.getStatusCode().value(), res.getBody().readAllBytes()));
  }

  Raw photo(String token, String photoId) {
    return client()
        .get()
        .uri("http://localhost:" + port + "/api/v1/inquiry-photos/" + photoId)
        .headers(h -> h.setBearerAuth(token))
        .exchange((req, res) -> new Raw(res.getStatusCode().value(), res.getBody().readAllBytes()));
  }

  String uploaded(String token, byte[] bytes) {
    Res r = upload(token, bytes);
    assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
    return r.body().path("photoId").asString();
  }

  @Test
  void customerAttachesPhotosAndOnlyOwnerAndAgentsSeeThem() {
    String me = "CU-" + UUID.randomUUID();
    String meTok = TestJwt.customer(me);
    String otherTok = TestJwt.customer("CU-" + UUID.randomUUID());
    String agent = TestJwt.operator("cs-p1", List.of("CS_AGENT"), List.of());
    String admin = TestJwt.operator("admin-p1", List.of("OPERATOR_ADMIN"), List.of());
    String riderOps = TestJwt.operator("ro-p1", List.of("RIDER_OPS"), List.of());
    String md = TestJwt.operator("md-p1", List.of("MD"), List.of());

    // 올리기 — 형식·역할
    Res bad = upload(meTok, "hello".getBytes());
    assertThat(bad.status()).isEqualTo(422);
    assertThat(bad.body().path("code").asString()).isEqualTo("PHOTO_INVALID");
    assertThat(upload(agent, JPEG).status()).as("직원은 못 올림").isEqualTo(403);
    String p1 = uploaded(meTok, JPEG);
    assertThat(p1).startsWith("IPH-");

    // 안 붙은 사진은 올린 본인만
    Raw own = photo(meTok, p1);
    assertThat(own.status()).isEqualTo(200);
    assertThat(own.body()).isEqualTo(JPEG);
    assertThat(photo(otherTok, p1).status()).isEqualTo(404);
    assertThat(photo(admin, p1).status()).isEqualTo(404);

    // 문의 남기며 붙이기
    Res created =
        send(
            "POST",
            meTok,
            "/inquiries",
            Map.of("category", "DELIVERY", "body", "상자가 젖었어요", "photoIds", List.of(p1)));
    assertThat(created.status()).as(created.body().toString()).isEqualTo(201);
    String inq = created.body().path("inquiryId").asString();
    assertThat(created.body().path("photos").findValuesAsString("photoId")).containsExactly(p1);
    assertThat(created.body().path("photos").get(0).path("url").asString())
        .isEqualTo("/api/v1/inquiry-photos/" + p1);

    // 이미 붙은 사진·남의 사진은 422 — 문의도 남지 않는다
    int before = count("SELECT count(*) FROM cs_inquiry WHERE customer_id = ?", me);
    Res reused =
        send(
            "POST",
            meTok,
            "/inquiries",
            Map.of("category", "OTHER", "body", "다시", "photoIds", List.of(p1)));
    assertThat(reused.status()).isEqualTo(422);
    assertThat(reused.body().path("code").asString()).isEqualTo("PHOTO_NOT_FOUND");
    assertThat(count("SELECT count(*) FROM cs_inquiry WHERE customer_id = ?", me))
        .isEqualTo(before);
    String othersPhoto = uploaded(otherTok, PNG);
    assertThat(
            send(
                    "POST",
                    meTok,
                    "/inquiries",
                    Map.of("category", "OTHER", "body", "남의", "photoIds", List.of(othersPhoto)))
                .status())
        .isEqualTo(422);

    // 보완 — 남긴 본인만, 합쳐 5장
    String p2 = uploaded(meTok, PNG);
    assertThat(
            send("POST", otherTok, "/inquiries/" + inq + "/photos", Map.of("photoIds", List.of(p2)))
                .status())
        .as("남의 문의")
        .isEqualTo(404);
    Res added =
        send("POST", meTok, "/inquiries/" + inq + "/photos", Map.of("photoIds", List.of(p2)));
    assertThat(added.status()).as(added.body().toString()).isEqualTo(200);
    assertThat(added.body().path("photos").findValuesAsString("photoId")).containsExactly(p1, p2);
    assertThat(added.body().path("version").asInt()).as("상태·버전 그대로").isEqualTo(0);
    List<String> four =
        List.of(
            uploaded(meTok, JPEG),
            uploaded(meTok, JPEG),
            uploaded(meTok, JPEG),
            uploaded(meTok, JPEG));
    assertThat(
            send("POST", meTok, "/inquiries/" + inq + "/photos", Map.of("photoIds", four)).status())
        .as("2 + 4 > 5")
        .isEqualTo(400);

    // 붙은 사진 본문 — 본인·고객 문의를 보는 직원만
    assertThat(photo(meTok, p2).status()).isEqualTo(200);
    assertThat(photo(agent, p2).status()).isEqualTo(200);
    assertThat(photo(admin, p2).status()).isEqualTo(200);
    assertThat(photo(riderOps, p2).status()).as("라이더 문의만 보는 지원센터").isEqualTo(404);
    assertThat(photo(md, p2).status()).as("문의를 볼 역할 없음").isEqualTo(403);
    assertThat(photo(otherTok, p2).status()).isEqualTo(404);
    assertThat(send("GET", agent, "/inquiries/" + inq, null).body().path("photos").size())
        .isEqualTo(2);

    // 닫힌 문의에는 못 붙인다
    jdbc.update("UPDATE cs_inquiry SET status = 'CLOSED' WHERE inquiry_id = ?", inq);
    Res closed =
        send(
            "POST",
            meTok,
            "/inquiries/" + inq + "/photos",
            Map.of("photoIds", List.of(four.get(0))));
    assertThat(closed.status()).isEqualTo(409);
    assertThat(closed.body().path("code").asString()).isEqualTo("INQUIRY_CLOSED");
  }

  @Test
  void riderPhotosAreForSupportCenterAndOrphansAreSwept() {
    String rider = "RD-" + UUID.randomUUID().toString().substring(0, 8);
    String riderTok = TestJwt.rider(rider);
    String riderOps = TestJwt.operator("ro-p2", List.of("RIDER_OPS"), List.of());
    String agent = TestJwt.operator("cs-p2", List.of("CS_AGENT"), List.of());

    // 본문 올리기가 실패하면 행도 남지 않는다(행 먼저 넣고 같은 트랜잭션에서 되돌림)
    FAIL_PUT.set(true);
    try {
      assertThat(upload(riderTok, PNG).status()).isEqualTo(503);
    } finally {
      FAIL_PUT.set(false);
    }
    assertThat(count("SELECT count(*) FROM inquiry_photo WHERE owner_id = ?", rider)).isZero();

    String rp = uploaded(riderTok, PNG);
    Res created =
        send(
            "POST",
            riderTok,
            "/inquiries",
            Map.of("category", "APP_ERROR", "body", "앱이 멈춰요", "photoIds", List.of(rp)));
    assertThat(created.status()).as(created.body().toString()).isEqualTo(201);
    assertThat(photo(riderOps, rp).status()).isEqualTo(200);
    assertThat(photo(agent, rp).status()).as("고객 문의만 보는 상담원").isEqualTo(404);

    // 하루 지난 안 붙은 사진은 정리, 붙은 사진·새 사진은 그대로
    String old = uploaded(riderTok, JPEG);
    String fresh = uploaded(riderTok, JPEG);
    jdbc.update(
        "UPDATE inquiry_photo SET uploaded_at = now() - interval '2 days' WHERE photo_id IN (?, ?)",
        old,
        rp);
    String oldKey =
        jdbc.queryForObject(
            "SELECT object_key FROM inquiry_photo WHERE photo_id = ?", String.class, old);
    assertThat(photoService.sweepOrphans()).isGreaterThanOrEqualTo(1);
    assertThat(count("SELECT count(*) FROM inquiry_photo WHERE photo_id = ?", old)).isZero();
    assertThat(STORED).doesNotContainKey(oldKey);
    String rpKey =
        jdbc.queryForObject(
            "SELECT object_key FROM inquiry_photo WHERE photo_id = ?", String.class, rp);
    assertThat(STORED).as("붙은 사진 본문은 그대로").containsKey(rpKey);
    assertThat(count("SELECT count(*) FROM inquiry_photo WHERE photo_id IN (?, ?)", rp, fresh))
        .isEqualTo(2);

    // 같은 키 재전송인데 그사이 정리됐으면 404(0.19.1 — 전에는 500)
    String key = UUID.randomUUID().toString();
    Res first = upload(riderTok, JPEG, key);
    assertThat(first.status()).isEqualTo(201);
    jdbc.update(
        "DELETE FROM inquiry_photo WHERE photo_id = ?", first.body().path("photoId").asString());
    Res replay = upload(riderTok, JPEG, key);
    assertThat(replay.status()).isEqualTo(404);
    assertThat(replay.body().path("code").asString()).isEqualTo("PHOTO_NOT_FOUND");
  }

  int count(String sql, Object... args) {
    Integer n = jdbc.queryForObject(sql, Integer.class, args);
    return n == null ? 0 : n;
  }
}
