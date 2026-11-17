package com.pmart.riderauth;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.pmart.common.test.EventEnvelopeFixtures;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.riderauth.application.RiderAuthProperties;
import com.pmart.riderauth.application.RiderSessionCuts;
import com.pmart.riderauth.infrastructure.GoogleIdTokenVerifier;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 라이더 Google 로그인 E2E: 시험 RSA 키로 만든 Google 형식 ID 토큰(운영과 같은 iss·aud·만료 검증) → 신규 RD-… ·재로그인 같은 주체·같은 키
 * 재전송·결과 조회 → 라이더 토큰(JWKS 로 검증, typ=RIDER) → 현재 세션·갱신 회전·재사용 감지·로그아웃 → aud 가 다른 토큰(고객 웹
 * 클라이언트·Android 클라이언트) 거절과 누적 제한. 실제 PostgreSQL.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "pmart.rider-auth.signing.ephemeral=true",
      "pmart.rider-auth.issuer=http://rider-auth.test",
      "pmart.rider-auth.google.client-ids=" + RiderSocialLoginIT.RIDER_WEB,
      "pmart.rider-auth.max-failures=3",
      "pmart.rider-auth.session-cut-interval=PT1H", // 시험이 check() 를 직접 부른다
      "pmart.messaging.outbox.delete-after-insert=false"
    })
@Import(RiderSocialLoginIT.FakeGoogle.class)
@EnableWireMock(
    @ConfigureWireMock(
        name = "dispatch",
        baseUrlProperties = {"pmart.rider-auth.dispatch-base-url"}))
class RiderSocialLoginIT extends PostgresKafkaContainerSupport {

  static final String RIDER_WEB = "rider-web.apps.googleusercontent.com";
  static final String CUSTOMER_WEB = "customer-web.apps.googleusercontent.com";
  static final String RIDER_ANDROID = "rider-android.apps.googleusercontent.com";
  static final RSAKey GOOGLE_KEY = generate();

  static RSAKey generate() {
    try {
      return new RSAKeyGenerator(2048).keyID("google-test").generate();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** Google 공개키 대신 시험 키로 서명을 확인하고, 그 밖의 검증(iss·aud·만료)은 운영 규칙 그대로. */
  @TestConfiguration
  static class FakeGoogle {
    @Bean
    @Primary
    GoogleIdTokenVerifier testGoogleVerifier(RiderAuthProperties props) throws Exception {
      NimbusJwtDecoder d = NimbusJwtDecoder.withPublicKey(GOOGLE_KEY.toRSAPublicKey()).build();
      d.setJwtValidator(GoogleIdTokenVerifier.validator(props.google()));
      return new GoogleIdTokenVerifier(d);
    }
  }

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired RiderSessionCuts cuts;

  @InjectWireMock("dispatch")
  WireMockServer dispatch;

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

  /** Android 앱이 serverClientId(웹 클라이언트)로 받은 ID 토큰 모양: aud = 웹 클라이언트, azp = Android 클라이언트. */
  static String idToken(String sub, String aud, String iss) {
    Instant now = Instant.now();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(iss)
            .subject(sub)
            .audience(List.of(aud))
            .claim("azp", RIDER_ANDROID)
            .issuedAt(now)
            .expiresAt(now.plusSeconds(600))
            .claim("email", sub + "@gmail.com")
            .claim("email_verified", true)
            .build();
    return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(GOOGLE_KEY)))
        .encode(
            JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).keyId("google-test").build(), claims))
        .getTokenValue();
  }

  static String google(String sub) {
    return idToken(sub, RIDER_WEB, "https://accounts.google.com");
  }

  Res login(String key, String device, String token) {
    return http.post()
        .uri("/rider-auth/social-logins")
        .headers(
            h -> {
              h.set("Idempotency-Key", key);
              h.set("X-Source-System", "rider-app");
              h.set("X-Device-Id", device);
            })
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("provider", "GOOGLE", "providerToken", token))
        .exchange((q, r) -> new Res(r.getStatusCode().value(), read(r.bodyTo(byte[].class))));
  }

  Res refresh(String refreshToken, String device) {
    return http.post()
        .uri("/rider-auth/tokens")
        .headers(
            h -> {
              h.set("Idempotency-Key", UUID.randomUUID().toString());
              h.set("X-Source-System", "rider-app");
              h.set("X-Device-Id", device);
            })
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("refreshToken", refreshToken))
        .exchange((q, r) -> new Res(r.getStatusCode().value(), read(r.bodyTo(byte[].class))));
  }

  Res currentSession(String accessToken) {
    return http.get()
        .uri("/rider-auth/sessions/current")
        .headers(h -> h.setBearerAuth(accessToken))
        .exchange((q, r) -> new Res(r.getStatusCode().value(), read(r.bodyTo(byte[].class))));
  }

  /**
   * 정지·계약 종료 라이더(2026-11-15 사용자 "추천대로"): 맡은 배송이 남아 있으면 기다리고(로그인도 됨), 없어지면 모든 세션을 끊고 SubjectBlocked
   * → 새 로그인 거절. 늦게 온 옛 사건은 무시, 풀리면 다시 로그인. 신규배차 제한은 끊지 않는다.
   */
  @Test
  void suspendedRiderIsLoggedOutOnlyAfterActiveDeliveriesEnd() {
    String sub = "g-" + UUID.randomUUID();
    String device = "dev-" + UUID.randomUUID();
    Res first = login("c1-" + sub, device, google(sub));
    assertThat(first.status()).isEqualTo(200);
    String riderId = first.body().get("riderId").asString();
    String refresh = first.body().get("tokens").get("refreshToken").asString();

    restricted(riderId, "SUSPENDED", 3);
    awaitCut(riderId, "PENDING");

    // 맡은 배송이 남음 → 기다린다, 로그인도 된다(픽업한 배송을 끝내야)
    activeDeliveries(riderId, "[\"DLV-1\"]");
    assertThat(cuts.check(riderId)).isFalse();
    assertThat(activeSessions(riderId)).isPositive();
    Res during = login("c2-" + sub, device, google(sub));
    assertThat(during.status()).isEqualTo(200);

    // 배송이 끝남 → 모든 세션을 끊고 차단 사건
    activeDeliveries(riderId, "[]");
    assertThat(cuts.check(riderId)).isTrue();
    assertThat(activeSessions(riderId)).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE aggregateid = ? AND type = 'SubjectBlocked'"
                    + " AND payload::text LIKE '%RIDER_RESTRICTED%'",
                Integer.class, riderId))
        .isEqualTo(1);
    assertThat(refresh(refresh, device).status()).isEqualTo(401);
    Res blocked = login("c3-" + sub, device, google(sub));
    assertThat(blocked.status()).isEqualTo(403);
    assertThat(blocked.body().path("code").asString()).isEqualTo("ACCOUNT_NOT_AVAILABLE");

    // 늦게 온 옛 사건(버전 2 풀기)은 무시 — 그대로 막힘
    restricted(riderId, "NORMAL", 2);
    restricted(riderId, "TERMINATED", 4); // 이미 끊김 → DONE 그대로
    awaitVersion(riderId, 4);
    assertThat(state(riderId)).isEqualTo("DONE");
    assertThat(login("c4-" + sub, device, google(sub)).status()).isEqualTo(403);

    // 풀림 → 다시 로그인
    restricted(riderId, "NORMAL", 5);
    awaitCut(riderId, "LIFTED");
    assertThat(login("c5-" + sub, device, google(sub)).status()).isEqualTo(200);

    // 신규배차 제한은 끊지 않는다
    restricted(riderId, "DISPATCH_RESTRICTED", 6);
    awaitVersion(riderId, 6);
    assertThat(state(riderId)).isEqualTo("LIFTED");
    assertThat(cuts.check(riderId)).isFalse();
    assertThat(activeSessions(riderId)).isPositive();
  }

  /**
   * 탈퇴(2026-11-17 — rider RiderWithdrawn, 사용자 "즉시 가림·재가입 허용"): 바로 모든 세션을 끊고
   * SubjectBlocked(ACCOUNT_DISABLED), 계정 DISABLED·Google 연결 지움 → 같은 Google 계정의 다음 로그인은 새 라이더 주체.
   */
  @Test
  void withdrawnRiderIsLoggedOutAndNextLoginIsNewRider() {
    String sub = "g-" + UUID.randomUUID();
    String device = "dev-" + UUID.randomUUID();
    Res first = login("w1-" + sub, device, google(sub));
    assertThat(first.status()).isEqualTo(200);
    String riderId = first.body().get("riderId").asString();
    String refresh = first.body().get("tokens").get("refreshToken").asString();

    String json =
        EventEnvelopeFixtures.envelope("RiderWithdrawn")
            .from("rider-service")
            .aggregate("Rider", riderId, 7)
            .payload(
                Map.of(
                    "riderId",
                    riderId,
                    "requestId",
                    "w-1",
                    "withdrawnAt",
                    Instant.now().toString()))
            .buildJson();
    kafka.send("delivery.rider.v1", riderId, json).join();
    await(
        "SELECT count(*) FROM rider_account WHERE rider_id = '"
            + riderId
            + "' AND state = 'DISABLED'");
    assertThat(activeSessions(riderId)).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE aggregateid = ? AND type = 'SubjectBlocked'"
                    + " AND payload::text LIKE '%ACCOUNT_DISABLED%'",
                Integer.class, riderId))
        .isEqualTo(1);
    assertThat(refresh(refresh, device).status()).isEqualTo(401);
    Res again = login("w2-" + sub, device, google(sub));
    assertThat(again.status()).isEqualTo(200);
    assertThat(again.body().get("riderId").asString()).isNotEqualTo(riderId); // 새 주체 — 다시 가입
    assertThat(again.body().get("newAccount").asBoolean()).isTrue();
  }

  void activeDeliveries(String riderId, String ids) {
    dispatch.resetAll();
    dispatch.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/riders/" + riderId + "/active-deliveries"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"riderId\":\"" + riderId + "\",\"deliveryIds\":" + ids + "}")));
  }

  void restricted(String riderId, String restriction, long version) {
    String json =
        EventEnvelopeFixtures.envelope("RiderRestricted")
            .from("rider-service")
            .aggregate("Rider", riderId, version)
            .payload(
                Map.of(
                    "riderId",
                    riderId,
                    "restriction",
                    restriction,
                    "from",
                    Instant.now().toString(),
                    "basis",
                    "IT"))
            .buildJson();
    kafka.send("delivery.rider.v1", riderId, json).join();
  }

  int activeSessions(String riderId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM rider_session WHERE rider_id = ? AND state = 'ACTIVE'",
            Integer.class,
            riderId);
    return n == null ? 0 : n;
  }

  String state(String riderId) {
    return jdbc.queryForObject(
        "SELECT state FROM rider_session_cut WHERE rider_id = ?", String.class, riderId);
  }

  void awaitCut(String riderId, String state) {
    await(
        "SELECT count(*) FROM rider_session_cut WHERE rider_id = '"
            + riderId
            + "' AND state = '"
            + state
            + "'");
  }

  void awaitVersion(String riderId, long version) {
    await(
        "SELECT count(*) FROM rider_session_cut WHERE rider_id = '"
            + riderId
            + "' AND rider_version = "
            + version);
  }

  void await(String countSql) {
    long until = System.currentTimeMillis() + 30_000;
    while (System.currentTimeMillis() < until) {
      Integer n = jdbc.queryForObject(countSql, Integer.class);
      if (n != null && n > 0) {
        return;
      }
      try {
        Thread.sleep(200);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
    throw new AssertionError("기다린 상태가 오지 않음: " + countSql);
  }

  JsonNode read(byte[] body) {
    return body == null || body.length == 0 ? mapper.createObjectNode() : mapper.readTree(body);
  }

  @SuppressWarnings("unchecked")
  Jwt decodeWithJwks(String accessToken) throws Exception {
    Map<String, Object> jwks =
        RestClient.create()
            .get()
            .uri("http://localhost:" + port + "/api/v1/internal/jwks")
            .retrieve()
            .body(Map.class);
    RSAKey key = (RSAKey) JWKSet.parse(jwks).getKeys().getFirst();
    return NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build().decode(accessToken);
  }

  @Test
  void googleLoginIssuesRiderTokenAndSessionLifecycle() throws Exception {
    String sub = "g-" + UUID.randomUUID();
    String device = "dev-" + UUID.randomUUID();

    // 처음 로그인: 새 라이더 주체
    Res first = login("k1-" + sub, device, google(sub));
    assertThat(first.status()).isEqualTo(200);
    String riderId = first.body().get("riderId").asString();
    assertThat(riderId).startsWith("RD-");
    assertThat(first.body().get("newAccount").asBoolean()).isTrue();
    assertThat(first.body().get("state").asString()).isEqualTo("AUTH_MEMBER");

    // 라이더 토큰: 이 서비스 JWKS 로 서명 확인, iss·sub·typ
    String access = first.body().get("tokens").get("accessToken").asString();
    Jwt jwt = decodeWithJwks(access);
    assertThat(jwt.getIssuer()).hasToString("http://rider-auth.test");
    assertThat(jwt.getSubject()).isEqualTo(riderId);
    assertThat(jwt.getClaimAsString("typ")).isEqualTo("RIDER");
    // PMart API 용 aud(ADR-0055 결정 6) — Google ID 토큰의 aud(라이더 웹 클라이언트 ID)를 옮기지 않는다
    assertThat(jwt.getAudience()).containsExactly("pmart-api");
    assertThat(jwt.getClaimAsString("sid"))
        .isEqualTo(first.body().get("tokens").get("sessionId").asString());

    // 같은 키 재전송: 같은 주체, 새 계정을 만들지 않음
    Res replay = login("k1-" + sub, device, google(sub));
    assertThat(replay.status()).isEqualTo(200);
    assertThat(replay.body().get("riderId").asString()).isEqualTo(riderId);
    assertThat(replay.body().get("authRequestId").asString())
        .isEqualTo(first.body().get("authRequestId").asString());
    // 같은 키, 다른 기기 → 409
    assertThat(login("k1-" + sub, "other-" + device, google(sub)).status()).isEqualTo(409);

    // 재로그인(새 키): 같은 주체, newAccount=false
    Res again = login("k2-" + sub, device, google(sub));
    assertThat(again.body().get("riderId").asString()).isEqualTo(riderId);
    assertThat(again.body().get("newAccount").asBoolean()).isFalse();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM rider_identity WHERE provider_subject = ?",
                Integer.class,
                sub))
        .isEqualTo(1);

    // 결과 조회: 같은 기기만, 토큰 없음
    String arId = first.body().get("authRequestId").asString();
    Res result =
        http.get()
            .uri("/rider-auth/social-logins/{id}", arId)
            .header("X-Device-Id", device)
            .exchange((q, r) -> new Res(r.getStatusCode().value(), read(r.bodyTo(byte[].class))));
    assertThat(result.status()).isEqualTo(200);
    assertThat(result.body().path("tokens").isMissingNode() || result.body().get("tokens").isNull())
        .as("결과 조회는 토큰을 다시 주지 않는다")
        .isTrue();
    int otherDevice =
        http.get()
            .uri("/rider-auth/social-logins/{id}", arId)
            .header("X-Device-Id", "other")
            .exchange((q, r) -> r.getStatusCode().value());
    assertThat(otherDevice).isEqualTo(403);

    // 현재 세션
    Res current = currentSession(access);
    assertThat(current.status()).isEqualTo(200);
    assertThat(current.body().get("riderId").asString()).isEqualTo(riderId);

    // 갱신(회전) → 같은 토큰 재사용은 TOKEN_REUSED 로 세션 폐기
    String refreshToken = first.body().get("tokens").get("refreshToken").asString();
    assertThat(refresh(refreshToken, "other-" + device).status()).isEqualTo(401); // 다른 기기
    Res rotated = refresh(refreshToken, device);
    assertThat(rotated.status()).isEqualTo(200);
    Res reused = refresh(refreshToken, device);
    assertThat(reused.status()).isEqualTo(401);
    assertThat(reused.body().get("code").asString()).isEqualTo("TOKEN_REUSED");
    assertThat(refresh(rotated.body().get("refreshToken").asString(), device).status())
        .isEqualTo(401); // 세션이 폐기됨
    assertThat(currentSession(access).status()).isEqualTo(401);
    // 재사용으로 끝낸 세션의 access token 도 게이트웨이에서 바로 막는다 — 세션 단위 SubjectBlocked(2026-11-13)
    assertThat(blockFor(claims(access).path("sid").asString())).contains("TOKEN_REUSED");

    // 다른 세션은 로그아웃으로 끝낸다
    String access2 = again.body().get("tokens").get("accessToken").asString();
    int logout =
        http.post()
            .uri("/rider-auth/logouts")
            .headers(
                h -> {
                  h.setBearerAuth(access2);
                  h.set("Idempotency-Key", UUID.randomUUID().toString());
                  h.set("X-Source-System", "rider-app");
                })
            .exchange((q, r) -> r.getStatusCode().value());
    assertThat(logout).isEqualTo(204);
    assertThat(currentSession(access2).status()).isEqualTo(401);
    assertThat(blockFor(claims(access2).path("sid").asString()))
        .contains("\"LOGOUT\"")
        .contains("RIDER")
        .contains("http://rider-auth.test")
        .contains(riderId);
  }

  /** JWT 본문(서명 확인 없이 — 이 시험은 sid 만 읽는다). */
  JsonNode claims(String jwt) {
    return mapper.readTree(java.util.Base64.getUrlDecoder().decode(jwt.split("\\.")[1]));
  }

  /** 그 세션의 SubjectBlocked outbox 행(payload). */
  String blockFor(String sid) {
    return jdbc.queryForObject(
        "SELECT payload::text FROM outbox_event WHERE topic = 'platform.subject-block.v1'"
            + " AND payload -> 'payload' ->> 'sessionId' = ?",
        String.class,
        sid);
  }

  @Test
  void rejectsTokensForOtherAudiencesAndRestrictsAfterRepeatedFailures() {
    String device = "dev-" + UUID.randomUUID();
    String sub = "g-" + UUID.randomUUID();
    // 고객용 웹 클라이언트로 받은 토큰(고객 앱 토큰), Android 클라이언트 ID 가 aud 인 토큰, 다른 iss → 모두 PROVIDER_REJECTED
    Res customerToken =
        login("r1-" + sub, device, idToken(sub, CUSTOMER_WEB, "https://accounts.google.com"));
    assertThat(customerToken.status()).isEqualTo(401);
    assertThat(customerToken.body().get("code").asString()).isEqualTo("PROVIDER_REJECTED");
    assertThat(
            login("r2-" + sub, device, idToken(sub, RIDER_ANDROID, "https://accounts.google.com"))
                .status())
        .isEqualTo(401);
    // 세 번째 실패로 제한(max-failures=3) → 423, 제한 중에는 올바른 토큰도 423
    Res third = login("r3-" + sub, device, idToken(sub, RIDER_WEB, "https://evil.example.com"));
    assertThat(third.status()).isEqualTo(423);
    assertThat(third.body().get("retryAfterSeconds").asInt()).isPositive();
    assertThat(login("r4-" + sub, device, google(sub)).status()).isEqualTo(423);
    // 다른 기기는 영향 없음, 실패 기록으로 라이더 주체가 생기지 않았음
    assertThat(login("r5-" + sub, "other-" + device, google(sub)).status()).isEqualTo(200);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM rider_identity WHERE provider_subject = ?",
                Integer.class,
                sub))
        .isEqualTo(1);
  }

  @Test
  void unsupportedProviderIsRejectedBeforeVerification() {
    Res kakao =
        http.post()
            .uri("/rider-auth/social-logins")
            .headers(
                h -> {
                  h.set("Idempotency-Key", UUID.randomUUID().toString());
                  h.set("X-Source-System", "rider-app");
                  h.set("X-Device-Id", "dev-x");
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("provider", "KAKAO", "providerToken", "x"))
            .exchange((q, r) -> new Res(r.getStatusCode().value(), read(r.bodyTo(byte[].class))));
    assertThat(kakao.status()).isEqualTo(400); // 계약 enum 밖(GOOGLE 만)
  }
}
