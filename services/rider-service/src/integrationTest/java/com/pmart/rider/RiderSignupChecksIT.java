package com.pmart.rider;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 가입 전 확인·거주지 주소·정산 계좌(2026-11-17 — 라이더 앱 요청 §12·§14·§15·§16-1, 사용자 결정: 서버가 주소 변환으로 확인, 계좌 중복 불가,
 * 은행 = 금융결제원 코드). serviceability 는 WireMock.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.messaging.outbox.delete-after-insert=false"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock(
    @ConfigureWireMock(
        name = "serviceability",
        baseUrlProperties = {"pmart.rider.serviceability-base-url"}))
class RiderSignupChecksIT extends PostgresKafkaContainerSupport {

  static final String GEOCODE = "/api/v1/internal/address-geocodes";
  static final String REAL = "부산 부산진구 동천로 109";

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;

  @InjectWireMock("serviceability")
  WireMockServer serviceability;

  RestClient http;

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    serviceability.resetAll();
    // 실제 주소 하나만 찾힌다
    serviceability.stubFor(
        WireMock.post(urlPathEqualTo(GEOCODE)).willReturn(okJson("{\"results\":[]}")));
    serviceability.stubFor(
        WireMock.post(urlPathEqualTo(GEOCODE))
            .atPriority(1)
            .withRequestBody(matchingJsonPath("$.query", equalTo(REAL)))
            .willReturn(
                okJson(
                    "{\"results\":[{\"lat\":35.158,\"lng\":129.062,"
                        + "\"jibunAddress\":\"부산 부산진구 부전동 1\"}]}")));
  }

  @Test
  void phoneAndAccountChecksBeforeSignupAndRealAddressOnly() {
    String a = "RD-ck1-" + UUID.randomUUID();
    String b = "RD-ck2-" + UUID.randomUUID();
    String phone = phone();
    String account = String.valueOf(1_000_000_000_000L + Math.floorMod(a.hashCode(), 999_999_999));

    // 은행 목록(금융결제원 코드)
    JsonNode banks = json(get(a, "/riders/bank-codes"));
    assertThat(banks.path("items").findValuesAsString("code")).contains("004", "090");

    // 가입 전 확인: 아직 아무도 없음
    assertThat(available(a, "/riders/phone-checks", Map.of("phone", phone))).isTrue();
    assertThat(post(a, "/riders/phone-checks", Map.of("phone", "02-123")).getStatusCode().value())
        .isEqualTo(400);
    assertThat(available(a, "/riders/account-checks", bank("004", account))).isTrue();
    assertThat(post(a, "/riders/account-checks", bank("999", account)).getStatusCode().value())
        .isEqualTo(400); // 목록 밖 은행

    // 없는 주소 → 422, 은행 이름(옛 앱) → 400
    assertThat(json(signup(a, phone, "없는로 1", "004", account)).path("code").asString())
        .isEqualTo("INVALID_ADDRESS");
    assertThat(signup(a, phone, REAL, "국민은행", account).getStatusCode().value()).isEqualTo(400);
    // 실제 주소 + 코드 → 201
    assertThat(signup(a, phone, REAL, "004", account).getStatusCode().value()).isEqualTo(201);

    // 다른 라이더: 번호·계좌 사용 중, 본인 계좌는 사용 가능
    assertThat(available(b, "/riders/phone-checks", Map.of("phone", phone))).isFalse();
    assertThat(available(b, "/riders/account-checks", bank("004", account))).isFalse();
    assertThat(available(a, "/riders/account-checks", bank("004", account))).isTrue();
    ResponseEntity<String> dup = signup(b, phone(), REAL, "004", account);
    assertThat(dup.getStatusCode().value()).isEqualTo(409);
    assertThat(json(dup).path("code").asString()).isEqualTo("DUPLICATE_ACCOUNT");
    assertThat(signup(b, phone(), null, "088", account).getStatusCode().value())
        .isEqualTo(201); // 다른 은행이면 다른 계좌

    // 프로필 변경도 같은 규칙: 없는 주소 422, 남의 계좌 409
    assertThat(
            json(patch(b, Map.of("residence", Map.of("roadAddress", "없는로 2"))))
                .path("code")
                .asString())
        .isEqualTo("INVALID_ADDRESS");
    Map<String, Object> other = new LinkedHashMap<>(bank("004", account));
    other.put("holderName", "김라이더");
    assertThat(json(patch(b, Map.of("bankAccount", other))).path("code").asString())
        .isEqualTo("DUPLICATE_ACCOUNT");

    // 주소 검색 장애 → 503
    serviceability.resetAll();
    serviceability.stubFor(
        WireMock.post(urlPathEqualTo(GEOCODE)).willReturn(aResponse().withStatus(503)));
    assertThat(patch(b, Map.of("residence", Map.of("roadAddress", REAL))).getStatusCode().value())
        .isEqualTo(503);
  }

  /** 직원 상세 배달 지역 구역 이름(0.26.0 — PC 2): 지금 발효 권역의 이름, 권역에 없거나 못 읽으면 null. */
  @Test
  void staffDetailShowsZoneNameFromCurrentServiceability() {
    String rider = "RD-" + UUID.randomUUID();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("phone", phone());
    body.put("name", "김라이더");
    body.put("vehicleType", "BICYCLE");
    body.put(
        "deliveryAreas",
        List.of(
            Map.of("mfcId", "MFC-IT-1", "zoneId", "ZONE-A"),
            Map.of("mfcId", "MFC-IT-2", "zoneId", "ZONE-GONE")));
    body.put("consents", List.of(Map.of("consentType", "TERMS", "version", "2026-11")));
    ResponseEntity<String> created =
        http.post()
            .uri("/riders/signups")
            .headers(
                h -> {
                  h.setBearerAuth(TestJwt.rider(rider));
                  h.set("Idempotency-Key", "k-" + UUID.randomUUID());
                  h.set("X-Source-System", "rider-app");
                })
            .body(body)
            .retrieve()
            .toEntity(String.class);
    assertThat(created.getStatusCode().value()).as(created.getBody()).isEqualTo(201);
    serviceability.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/serviceability-versions/current"))
            .willReturn(
                okJson(
                    "{\"version\":3,\"zones\":[{\"zoneId\":\"ZONE-A\",\"mfcId\":\"MFC-IT-1\","
                        + "\"name\":\"부산진구 서면\"}]}")));
    String ops = TestJwt.operator("OP-RO", List.of("RIDER_OPS"), List.of());
    JsonNode areas = json(staffDetail(rider, ops)).path("deliveryAreas");
    assertThat(areas.get(0).path("zoneName").asString()).isEqualTo("부산진구 서면");
    assertThat(areas.get(1).path("zoneName").isNull()).isTrue();

    // 권역 서비스 장애 — 상세는 그대로, 이름만 null
    serviceability.resetAll();
    serviceability.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/serviceability-versions/current"))
            .willReturn(aResponse().withStatus(503)));
    ResponseEntity<String> down = staffDetail(rider, ops);
    assertThat(down.getStatusCode().value()).isEqualTo(200);
    assertThat(json(down).path("deliveryAreas").get(0).path("zoneName").isNull()).isTrue();
  }

  private ResponseEntity<String> staffDetail(String rider, String token) {
    return http.get()
        .uri("/riders/{id}", rider)
        .header("Authorization", "Bearer " + token)
        .retrieve()
        .toEntity(String.class);
  }

  private static ResponseDefinitionBuilder okJson(String body) {
    return aResponse()
        .withStatus(200)
        .withHeader("Content-Type", "application/json")
        .withBody(body);
  }

  private static Map<String, Object> bank(String code, String account) {
    return Map.of("bankCode", code, "accountNumber", account);
  }

  private boolean available(String rider, String uri, Object body) {
    ResponseEntity<String> r = post(rider, uri, body);
    assertThat(r.getStatusCode().value()).as(uri + " " + r.getBody()).isEqualTo(200);
    return json(r).path("available").asBoolean();
  }

  private ResponseEntity<String> signup(
      String rider, String phone, @Nullable String road, String bankCode, String account) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("phone", phone);
    body.put("name", "김라이더");
    body.put("vehicleType", "BICYCLE");
    body.put("deliveryAreas", List.of(Map.of("mfcId", "MFC-IT-1")));
    body.put("consents", List.of(Map.of("consentType", "TERMS", "version", "2026-11")));
    body.put(
        "bankAccount",
        Map.of("bankCode", bankCode, "accountNumber", account, "holderName", "김라이더"));
    if (road != null) {
      body.put("residence", Map.of("roadAddress", road, "detail", "101호"));
    }
    return http.post()
        .uri("/riders/signups")
        .headers(
            h -> {
              h.setBearerAuth(TestJwt.rider(rider));
              h.set("Idempotency-Key", "k-" + UUID.randomUUID());
              h.set("X-Source-System", "rider-app");
            })
        .body(body)
        .retrieve()
        .toEntity(String.class);
  }

  private ResponseEntity<String> patch(String rider, Map<String, Object> body) {
    return http.patch()
        .uri("/riders/me")
        .headers(
            h -> {
              h.setBearerAuth(TestJwt.rider(rider));
              h.set("Idempotency-Key", "p-" + UUID.randomUUID());
              h.set("X-Source-System", "rider-app");
            })
        .body(body)
        .retrieve()
        .toEntity(String.class);
  }

  /** 확인 경로도 변경 요청 공통 헤더(면허 확인과 같다). */
  private ResponseEntity<String> post(String rider, String uri, Object body) {
    return http.post()
        .uri(uri)
        .headers(
            h -> {
              h.setBearerAuth(TestJwt.rider(rider));
              h.set("Idempotency-Key", "c-" + UUID.randomUUID());
              h.set("X-Source-System", "rider-app");
            })
        .body(body)
        .retrieve()
        .toEntity(String.class);
  }

  private ResponseEntity<String> get(String rider, String uri) {
    return http.get()
        .uri(uri)
        .header("Authorization", "Bearer " + TestJwt.rider(rider))
        .retrieve()
        .toEntity(String.class);
  }

  private static String phone() {
    return "010"
        + String.format(
            "%08d", Math.abs(UUID.randomUUID().getLeastSignificantBits() % 100_000_000L));
  }

  private JsonNode json(ResponseEntity<String> r) {
    return mapper.readTree(r.getBody() == null ? "{}" : r.getBody());
  }
}
