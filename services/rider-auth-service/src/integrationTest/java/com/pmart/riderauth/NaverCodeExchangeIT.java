package com.pmart.riderauth;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
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
 * 네이버 인가 코드 서버 교환(ADR-0078, BR-69): WireMock 이 네이버 토큰 엔드포인트를 대신한다. 성공(id_token 만)·같은 키 재전송(네이버 한
 * 번만)·invalid_grant 401·5xx/시간 초과 503·redirectUri 불일치 400. Secret 미설정 422 는 {@code
 * RiderSocialLoginIT}(네이버 설정 없는 문맥)와 단위 시험.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "pmart.rider-auth.signing.ephemeral=true",
      "pmart.rider-auth.issuer=http://rider-auth.test",
      "pmart.rider-auth.google.client-ids=rider-web.apps.googleusercontent.com",
      "pmart.rider-auth.naver.client-ids=" + NaverCodeExchangeIT.CLIENT_ID,
      "pmart.rider-auth.naver.client-secret=" + NaverCodeExchangeIT.SECRET,
      "pmart.rider-auth.naver.callback-url=" + NaverCodeExchangeIT.CALLBACK,
      "pmart.rider-auth.naver.read-timeout=PT1S",
      "pmart.rider-auth.session-cut-interval=PT1H"
    })
@EnableWireMock(
    @ConfigureWireMock(
        name = "naver",
        baseUrlProperties = {
          "pmart.rider-auth.naver.token-base-url",
          "pmart.rider-auth.naver.profile-base-url",
          "pmart.rider-auth.rider-base-url"
        }))
class NaverCodeExchangeIT extends PostgresKafkaContainerSupport {

  static final String CLIENT_ID = "naver-client-id";
  static final String SECRET = "naver-test-secret";
  static final String CALLBACK = "http://192.168.0.40:8080/api/v1/auth/oauth-callbacks/naver/rider";
  static final String VERIFIER = "v".repeat(43);

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;

  @InjectWireMock("naver")
  WireMockServer naver;

  RestClient http;

  record Res(int status, JsonNode body) {}

  @BeforeEach
  void setUp() {
    naver.resetAll();
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            // 시험 클라이언트가 503 을 스스로 재시도하지 않게(Apache 클라이언트 기본 재시도) JDK 클라이언트로 고정
            .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory())
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
  }

  Res exchange(String key, String code, String redirectUri, String state) {
    return exchange(key, code, redirectUri, state, null);
  }

  Res exchange(String key, String code, String redirectUri, String state, String purpose) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("authorizationCode", code);
    body.put("codeVerifier", VERIFIER);
    body.put("redirectUri", redirectUri);
    if (state != null) {
      body.put("state", state);
    }
    if (purpose != null) {
      body.put("purpose", purpose);
    }
    return http.post()
        .uri("/rider-auth/naver-token-exchanges")
        .headers(
            h -> {
              h.set("Idempotency-Key", key);
              h.set("X-Source-System", "rider-app");
            })
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .exchange(
            (q, r) -> {
              byte[] b = r.bodyTo(byte[].class);
              return new Res(
                  r.getStatusCode().value(),
                  b == null || b.length == 0 ? mapper.createObjectNode() : mapper.readTree(b));
            });
  }

  void naverReturns(int status, String json) {
    naver.stubFor(
        post(urlPathEqualTo("/oauth2/token"))
            .willReturn(
                aResponse()
                    .withStatus(status)
                    .withHeader("Content-Type", "application/json")
                    .withBody(json)));
  }

  @Test
  void exchangesCodeForIdTokenOnlyAndReplaysSameKeyWithoutCallingNaverAgain() {
    naverReturns(
        200,
        "{\"access_token\":\"at-1\",\"refresh_token\":\"rt-1\",\"token_type\":\"bearer\","
            + "\"expires_in\":3600,\"id_token\":\"naver.id.token\"}");
    String key = UUID.randomUUID().toString();

    Res first = exchange(key, "code-1", CALLBACK, "st-1");
    assertThat(first.status()).isEqualTo(200);
    assertThat(first.body().path("idToken").asString()).isEqualTo("naver.id.token");
    // access/refresh token 은 응답에 싣지 않는다
    assertThat(first.body().has("accessToken")).isFalse();
    assertThat(first.body().size()).isEqualTo(1);

    naver.verify(
        1,
        postRequestedFor(urlPathEqualTo("/oauth2/token"))
            .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
            .withHeader("Authorization", WireMock.absent())
            .withFormParam("grant_type", equalTo("authorization_code"))
            .withFormParam("client_id", equalTo(CLIENT_ID))
            .withFormParam("client_secret", equalTo(SECRET))
            .withFormParam("code", equalTo("code-1"))
            .withFormParam("code_verifier", equalTo(VERIFIER))
            .withFormParam("redirect_uri", equalTo(CALLBACK))
            .withFormParam("state", equalTo("st-1")));

    // 응답 유실 후 같은 키 재전송: 네이버를 다시 부르지 않고 처음 결과
    Res again = exchange(key, "code-1", CALLBACK, "st-1");
    assertThat(again.status()).isEqualTo(200);
    assertThat(again.body().path("idToken").asString()).isEqualTo("naver.id.token");
    naver.verify(1, postRequestedFor(urlPathEqualTo("/oauth2/token")));

    // 같은 키·다른 본문 → 409
    Res conflict = exchange(key, "code-2", CALLBACK, "st-1");
    assertThat(conflict.status()).isEqualTo(409);
    naver.verify(1, postRequestedFor(urlPathEqualTo("/oauth2/token")));

    // 기록에는 해시만, 비밀은 없다
    Integer secretRows =
        jdbc.queryForObject(
            "SELECT count(*) FROM request_record WHERE request_id = ? AND result::text LIKE ?",
            Integer.class,
            key,
            "%" + SECRET + "%");
    assertThat(secretRows).isZero();
  }

  @Test
  void invalidGrantIsProviderRejected() {
    naverReturns(400, "{\"error\":\"invalid_grant\",\"error_description\":\"bad code\"}");
    Res r = exchange(UUID.randomUUID().toString(), "used-code", CALLBACK, null);
    assertThat(r.status()).isEqualTo(401);
    assertThat(r.body().path("code").asString()).isEqualTo("PROVIDER_REJECTED");
  }

  @Test
  void errorInOkBodyIsProviderRejectedAndFailureIsNotStored() {
    naverReturns(200, "{\"error\":\"invalid_request\",\"error_description\":\"no\"}");
    String key = UUID.randomUUID().toString();
    assertThat(exchange(key, "c", CALLBACK, null).status()).isEqualTo(401);
    // 실패는 기록하지 않는다 — 같은 키로 다시 보내면 다시 교환한다
    naverReturns(200, "{\"id_token\":\"later.id.token\"}");
    Res retry = exchange(key, "c", CALLBACK, null);
    assertThat(retry.status()).isEqualTo(200);
    assertThat(retry.body().path("idToken").asString()).isEqualTo("later.id.token");
    naver.verify(2, postRequestedFor(urlPathEqualTo("/oauth2/token")));
  }

  @Test
  void naverServerErrorIsProviderUnavailable() {
    naverReturns(500, "{}");
    Res r = exchange(UUID.randomUUID().toString(), "c", CALLBACK, null);
    assertThat(r.status()).isEqualTo(503);
    assertThat(r.body().path("code").asString()).isEqualTo("PROVIDER_UNAVAILABLE");
    naver.verify(1, postRequestedFor(urlPathEqualTo("/oauth2/token")));
  }

  @Test
  void naverTimeoutIsProviderUnavailable() {
    naver.stubFor(
        post(urlPathEqualTo("/oauth2/token"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"id_token\":\"slow\"}")
                    .withFixedDelay(3000)));
    Res r = exchange(UUID.randomUUID().toString(), "c", CALLBACK, null);
    assertThat(r.status()).isEqualTo(503);
    assertThat(r.body().path("code").asString()).isEqualTo("PROVIDER_UNAVAILABLE");
    naver.verify(1, postRequestedFor(urlPathEqualTo("/oauth2/token")));
  }

  @Test
  void otherRedirectUriIsRejectedWithoutCallingNaver() {
    Res r =
        exchange(
            UUID.randomUUID().toString(),
            "c",
            "http://192.168.0.40:8080/api/v1/auth/oauth-callbacks/naver/customer",
            null);
    assertThat(r.status()).isEqualTo(400);
    assertThat(r.body().path("code").asString()).isEqualTo("VALIDATION_FAILED");
    naver.verify(0, postRequestedFor(urlPathEqualTo("/oauth2/token")));
  }

  // ---- 2026-11-22 RA-1: 탈퇴 재인증 끊기·가입 전 끊기·가린 이메일

  @Autowired com.pmart.riderauth.infrastructure.RiderAuthStore store;

  /** 서명 없는 모양만 id_token — 서버는 sub 를 키로만 읽는다. */
  static String idTokenFor(String sub) {
    java.util.Base64.Encoder b64 = java.util.Base64.getUrlEncoder().withoutPadding();
    return b64.encodeToString("{\"alg\":\"none\"}".getBytes())
        + "."
        + b64.encodeToString(("{\"sub\":\"" + sub + "\"}").getBytes())
        + ".sig";
  }

  void naverIssues(String sub, String email) {
    naverReturns(
        200,
        "{\"access_token\":\"at-ra1\",\"id_token\":\""
            + idTokenFor(sub)
            + "\",\"expires_in\":3600}");
    naver.stubFor(
        WireMock.get(urlPathEqualTo("/v1/nid/me"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"resultcode\":\"00\",\"message\":\"success\",\"response\":{\"id\":\"x\""
                            + (email == null ? "" : ",\"email\":\"" + email + "\"")
                            + "}}")));
    naver.stubFor(
        post(urlPathEqualTo("/oauth2.0/token"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"access_token\":\"at-ra1\",\"result\":\"success\"}")));
  }

  int unlinkCalls() {
    return naver
        .findAll(
            postRequestedFor(urlPathEqualTo("/oauth2.0/token"))
                .withFormParam("grant_type", equalTo("delete")))
        .size();
  }

  @Test
  void withdrawalPurposeUnlinksNaverAfterReadingProfile() {
    String sub = "nv-" + UUID.randomUUID();
    String rider = "RD-" + UUID.randomUUID();
    store.insertAccount(rider, java.time.Instant.now());
    store.insertIdentity("NAVER", sub, rider, java.time.Instant.now());
    naver.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/riders/" + rider))
            .willReturn(aResponse().withStatus(200).withBody("{}")));
    naverIssues(sub, null);

    Res r = exchange(UUID.randomUUID().toString(), "c-w", CALLBACK, null, "WITHDRAWAL");
    assertThat(r.status()).isEqualTo(200);
    naver.verify(1, WireMock.getRequestedFor(urlPathEqualTo("/v1/nid/me")));
    naver.verify(
        1,
        postRequestedFor(urlPathEqualTo("/oauth2.0/token"))
            .withFormParam("grant_type", equalTo("delete"))
            .withFormParam("client_id", equalTo(CLIENT_ID))
            .withFormParam("client_secret", equalTo(SECRET))
            .withFormParam("access_token", equalTo("at-ra1"))
            .withFormParam("service_provider", equalTo("NAVER")));
  }

  @Test
  void loginUnlinksOnlyWhenSignupNotFinished() {
    // 처음(연결 없음) → 끊음
    naverIssues("nv-" + UUID.randomUUID(), null);
    assertThat(exchange(UUID.randomUUID().toString(), "c1", CALLBACK, null).status())
        .isEqualTo(200);
    assertThat(unlinkCalls()).isEqualTo(1);

    // 연결은 있지만 rider-service 에 가입 전(404) → 끊음
    naver.resetRequests();
    String pendingSub = "nv-" + UUID.randomUUID();
    String pending = "RD-" + UUID.randomUUID();
    store.insertAccount(pending, java.time.Instant.now());
    store.insertIdentity("NAVER", pendingSub, pending, java.time.Instant.now());
    naver.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/riders/" + pending))
            .willReturn(aResponse().withStatus(404)));
    naverIssues(pendingSub, null);
    assertThat(exchange(UUID.randomUUID().toString(), "c2", CALLBACK, null).status())
        .isEqualTo(200);
    assertThat(unlinkCalls()).isEqualTo(1);

    // 가입한 라이더(200) → 끊지 않음
    naver.resetRequests();
    String memberSub = "nv-" + UUID.randomUUID();
    String member = "RD-" + UUID.randomUUID();
    store.insertAccount(member, java.time.Instant.now());
    store.insertIdentity("NAVER", memberSub, member, java.time.Instant.now());
    naver.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/riders/" + member))
            .willReturn(aResponse().withStatus(200).withBody("{}")));
    naverIssues(memberSub, null);
    assertThat(exchange(UUID.randomUUID().toString(), "c3", CALLBACK, null).status())
        .isEqualTo(200);
    assertThat(unlinkCalls()).isZero();

    // rider-service 장애(500) → 판단을 건너뛰고 끊지 않음, 교환은 200
    naver.resetRequests();
    String oddSub = "nv-" + UUID.randomUUID();
    String odd = "RD-" + UUID.randomUUID();
    store.insertAccount(odd, java.time.Instant.now());
    store.insertIdentity("NAVER", oddSub, odd, java.time.Instant.now());
    naver.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/riders/" + odd))
            .willReturn(aResponse().withStatus(500)));
    naverIssues(oddSub, null);
    assertThat(exchange(UUID.randomUUID().toString(), "c4", CALLBACK, null).status())
        .isEqualTo(200);
    assertThat(unlinkCalls()).isZero();
  }

  @Test
  void unlinkFailureDoesNotFailExchange() {
    naverIssues("nv-" + UUID.randomUUID(), null);
    naver.stubFor(post(urlPathEqualTo("/oauth2.0/token")).willReturn(aResponse().withStatus(500)));
    Res r = exchange(UUID.randomUUID().toString(), "c-f", CALLBACK, null, "WITHDRAWAL");
    assertThat(r.status()).isEqualTo(200);
    assertThat(unlinkCalls()).isEqualTo(1);
  }

  @Test
  void holdsOnlyMaskedEmailBySubjectHashForTenMinutes() {
    String sub = "nv-" + UUID.randomUUID();
    naverIssues(sub, "rider.kim@naver.com");
    assertThat(exchange(UUID.randomUUID().toString(), "c-e", CALLBACK, null).status())
        .isEqualTo(200);

    java.util.List<Map<String, Object>> rows =
        jdbc.queryForList("SELECT subject_hash, email_masked FROM naver_email_hold");
    assertThat(rows)
        .anySatisfy(
            row -> {
              assertThat(row.get("subject_hash").toString()).doesNotContain(sub).hasSize(64);
              assertThat(row.get("email_masked").toString())
                  .doesNotContain("rider.kim")
                  .doesNotContain("naver.com")
                  .contains("@");
            });
    String hash = com.pmart.riderauth.application.NaverCodeExchangeService.subjectHash(sub);
    assertThat(store.takeNaverEmail(hash, java.time.Instant.now())).isPresent();
    // 한 번 꺼내면 지워진다
    assertThat(store.takeNaverEmail(hash, java.time.Instant.now())).isEmpty();
    // 10분 지난 것은 꺼내지 않는다
    store.holdNaverEmail(
        hash, "a***@n***", java.time.Instant.now().minusSeconds(1), java.time.Instant.now());
    assertThat(store.takeNaverEmail(hash, java.time.Instant.now())).isEmpty();
  }
}
