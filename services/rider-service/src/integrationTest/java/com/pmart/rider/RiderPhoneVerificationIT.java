package com.pmart.rider;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 라이더 휴대폰 번호 확인(2026-11-19 — 사용자 "옥토모", 라이더 앱 요청 R2·R3): 시작 → 문자 안 옴 → 옴 → 가입(필수 설정), 같은 번호 다른 계정은
 * IDENTITY_ALREADY_REGISTERED(로그인 수단·가린 이메일·가입일). OCTOMO·rider-auth 는 WireMock.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.rider.phone-verification.api-key=test-key",
      "pmart.rider.phone-verification.send-to=1666-3538",
      "pmart.rider.phone-verification.ttl=PT10M",
      "pmart.rider.phone-verification.within-minutes=10",
      "pmart.rider.phone-verification.max-attempts=5",
      "pmart.rider.phone-verification.max-starts-per-day=10",
      "pmart.rider.phone-verification.required=true"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock({
  @ConfigureWireMock(
      name = "octomo",
      baseUrlProperties = "pmart.rider.phone-verification.octomo-base-url"),
  @ConfigureWireMock(name = "riderauth", baseUrlProperties = "pmart.rider.rider-auth-base-url")
})
class RiderPhoneVerificationIT extends PostgresKafkaContainerSupport {

  static final String EXISTS = "/octomo/v1/public/message/exists";

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;

  @InjectWireMock("octomo")
  WireMockServer octomo;

  @InjectWireMock("riderauth")
  WireMockServer riderAuth;

  RestClient http;

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    octomo.resetAll();
    riderAuth.resetAll();
  }

  @Test
  void verifyPhoneThenSignupAndSameNumberPointsToExistingAccount() throws Exception {
    String rider = "RD-pv-" + UUID.randomUUID();
    String phone = phone();
    // 확인 없이 가입은 422(설정 required)
    assertThat(signup(rider, phone, null).getStatusCode().value()).isEqualTo(422);
    JsonNode started = json(call("/riders/me/phone-verifications", rider, Map.of("phone", phone)));
    String id = started.path("verificationId").asString();
    String text = started.path("text").asString();
    assertThat(started.path("sendTo").asString()).isEqualTo("1666-3538");
    assertThat(text).matches("[0-9]{6}");
    // 아직 안 옴 → PENDING·남은 4
    octomo.stubFor(post(urlPathEqualTo(EXISTS)).willReturn(okJson(obj("exists", "false"))));
    JsonNode miss =
        json(
            call(
                "/riders/me/phone-verifications/" + id + "/confirmations",
                rider,
                Map.of("phone", phone)));
    assertThat(miss.path("state").asString()).isEqualTo("PENDING");
    assertThat(miss.path("remainingAttempts").asInt()).isEqualTo(4);
    // 옴 → VERIFIED(본문·번호 그대로 물었는지)
    octomo.resetAll();
    octomo.stubFor(
        post(urlPathEqualTo(EXISTS))
            .withHeader("Authorization", equalTo("Octomo test-key"))
            .withRequestBody(WireMock.matchingJsonPath("$.text", equalTo(text)))
            .withRequestBody(WireMock.matchingJsonPath("$.mobileNum", equalTo(phone)))
            .willReturn(okJson(obj("exists", "true"))));
    JsonNode ok =
        json(
            call(
                "/riders/me/phone-verifications/" + id + "/confirmations",
                rider,
                Map.of("phone", phone)));
    assertThat(ok.path("state").asString()).isEqualTo("VERIFIED");
    ResponseEntity<String> joined = signup(rider, phone, id);
    assertThat(joined.getStatusCode().value()).as(joined.getBody()).isEqualTo(201);
    assertThat(
            jdbc.queryForObject(
                "SELECT phone_verified FROM rider WHERE rider_id = ?", Boolean.class, rider))
        .isTrue();

    // 다른 주체로 같은 번호 → 그 계정 안내(로그인 수단·가린 이메일·가입일)
    riderAuth.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/riders/" + rider + "/identity"))
            .willReturn(
                okJson(
                    mapper.writeValueAsString(
                        Map.of(
                            "riderId", rider,
                            "provider", "GOOGLE",
                            "emailMasked", "a***@g***",
                            "linkedAt", "2026-11-19T00:00:00Z")))));
    String other = "RD-pv2-" + UUID.randomUUID();
    ResponseEntity<String> dup =
        call("/riders/me/phone-verifications", other, Map.of("phone", phone));
    assertThat(dup.getStatusCode().value()).isEqualTo(409);
    assertThat(json(dup).path("code").asString()).isEqualTo("IDENTITY_ALREADY_REGISTERED");
    assertThat(dup.getBody()).contains("GOOGLE").contains("a***@g***").contains("joinedAt");
  }

  /** 0.28.0(2026-11-25 사용자 "이미 가입했는데 전화번호 인증 안 한 계정은 인증하라고 팝업"): 가입 뒤 번호 확인 붙이기. */
  @Test
  void signedUpRiderAttachesPhoneVerification() throws Exception {
    String rider = "RD-pva-" + UUID.randomUUID();
    String phone = phone();
    assertThat(signup(rider, phone, verified(rider, phone)).getStatusCode().value()).isEqualTo(201);
    // 확인이 생기기 전 가입자처럼
    jdbc.update("UPDATE rider SET phone_verified = false WHERE rider_id = ?", rider);
    assertThat(json(me(rider)).path("phoneVerified").asBoolean()).isFalse();

    // 번호만·확인 ID 만 → 400
    assertThat(patch(rider, Map.of("phone", phone)).getStatusCode().value()).isEqualTo(400);
    assertThat(patch(rider, Map.of("phoneVerificationId", "RPV-x")).getStatusCode().value())
        .isEqualTo(400);
    // 확인이 끝나지 않은 ID → 422
    String pending =
        json(call("/riders/me/phone-verifications", rider, Map.of("phone", phone)))
            .path("verificationId")
            .asString();
    ResponseEntity<String> notYet =
        patch(rider, Map.of("phone", phone, "phoneVerificationId", pending));
    assertThat(notYet.getStatusCode().value()).isEqualTo(422);
    assertThat(json(notYet).path("code").asString()).isEqualTo("PHONE_NOT_VERIFIED");

    // 새 번호로 확인 → 붙이면 번호·확인 갱신
    String phone2 = phone();
    String id2 = verified(rider, phone2);
    ResponseEntity<String> ok = patch(rider, Map.of("phone", phone2, "phoneVerificationId", id2));
    assertThat(ok.getStatusCode().value()).as(ok.getBody()).isEqualTo(200);
    JsonNode after = json(me(rider));
    assertThat(after.path("phoneVerified").asBoolean()).isTrue();
    assertThat(after.path("phoneMasked").asString()).endsWith(phone2.substring(7));

    // 다른 라이더 번호는 붙일 수 없다(확인 ID 가 있어도)
    String other = "RD-pvb-" + UUID.randomUUID();
    String otherPhone = phone();
    assertThat(signup(other, otherPhone, verified(other, otherPhone)).getStatusCode().value())
        .isEqualTo(201);
    ResponseEntity<String> taken =
        patch(rider, Map.of("phone", otherPhone, "phoneVerificationId", id2));
    assertThat(taken.getStatusCode().value()).isEqualTo(409);
    assertThat(json(taken).path("code").asString()).isEqualTo("IDENTITY_ALREADY_REGISTERED");
  }

  String verified(String rider, String phone) throws Exception {
    octomo.resetAll();
    octomo.stubFor(post(urlPathEqualTo(EXISTS)).willReturn(okJson(obj("exists", "true"))));
    String id =
        json(call("/riders/me/phone-verifications", rider, Map.of("phone", phone)))
            .path("verificationId")
            .asString();
    assertThat(
            json(call(
                    "/riders/me/phone-verifications/" + id + "/confirmations",
                    rider,
                    Map.of("phone", phone)))
                .path("state")
                .asString())
        .isEqualTo("VERIFIED");
    return id;
  }

  ResponseEntity<String> me(String rider) {
    return http.get()
        .uri("/riders/me")
        .headers(h -> h.setBearerAuth(TestJwt.rider(rider)))
        .retrieve()
        .toEntity(String.class);
  }

  ResponseEntity<String> patch(String rider, Map<String, Object> body) {
    return http.patch()
        .uri("/riders/me")
        .headers(
            h -> {
              h.setBearerAuth(TestJwt.rider(rider));
              h.set("Idempotency-Key", UUID.randomUUID().toString());
              h.set("X-Source-System", "rider-app");
            })
        .body(body)
        .retrieve()
        .toEntity(String.class);
  }

  static String obj(String k, String v) {
    return "{" + Q + k + Q + ":" + v + "}";
  }

  static final String Q = String.valueOf('"');

  ResponseEntity<String> call(String uri, String rider, Map<String, Object> body) {
    return http.post()
        .uri(uri)
        .headers(
            h -> {
              h.setBearerAuth(TestJwt.rider(rider));
              h.set("Idempotency-Key", UUID.randomUUID().toString());
              h.set("X-Source-System", "rider-app");
            })
        .body(body)
        .retrieve()
        .toEntity(String.class);
  }

  ResponseEntity<String> signup(String rider, String phone, String verificationId) {
    Map<String, Object> b = new java.util.LinkedHashMap<>();
    b.put("phone", phone);
    b.put("name", "시험 라이더");
    b.put("vehicleType", "BICYCLE");
    b.put("deliveryAreas", List.of(Map.of("mfcId", "MFC-IT-1")));
    b.put("consents", List.of(Map.of("consentType", "TERMS", "version", "2026-11")));
    if (verificationId != null) {
      b.put("phoneVerificationId", verificationId);
    }
    return call("/riders/signups", rider, b);
  }

  JsonNode json(ResponseEntity<String> r) throws Exception {
    return mapper.readTree(r.getBody());
  }

  static String phone() {
    return "010"
        + String.format(
            "%08d", Math.abs(UUID.randomUUID().getLeastSignificantBits() % 100_000_000L));
  }
}
