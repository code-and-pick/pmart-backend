package com.pmart.rider;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
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
 * 라이더 탈퇴(2026-11-17 — 라이더 앱 요청 §17, 사용자 결정: 확정 전 수행료·산정 불가·진행 중 배송·열린 도움요청·제한 검토만 막음, 즉시 가림·재가입
 * 허용). delivery-execution 은 WireMock.
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
        name = "execution",
        baseUrlProperties = {"pmart.rider.execution-base-url"}))
class RiderWithdrawalIT extends PostgresKafkaContainerSupport {

  static final String BLOCKERS = "/api/v1/internal/riders/%s/withdrawal-blockers";

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired com.pmart.rider.application.WithdrawalHoldPurger purger;

  @InjectWireMock("execution")
  WireMockServer execution;

  RestClient http;

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    execution.resetAll();
  }

  @Test
  void withdrawalBlockedThenCompletesAndClearsPersonalData() {
    String rider = "RD-wd-" + UUID.randomUUID();
    String phone = phone();
    assertThat(signup(rider, phone).getStatusCode().value()).isEqualTo(201);

    // 1) execution: 확정 전 수행료 → 409, 결과 REJECTED
    blockers(rider, "[\"UNCONFIRMED_EARNING\"]");
    ResponseEntity<String> blocked = withdraw(rider, "w-1");
    assertThat(blocked.getStatusCode().value()).isEqualTo(409);
    assertThat(json(blocked).path("code").asString()).isEqualTo("INVALID_STATE");
    assertThat(blocked.getBody()).contains("UNCONFIRMED_EARNING");
    JsonNode r1 = json(get(rider, "/riders/me/withdrawals/w-1"));
    assertThat(r1.path("state").asString()).isEqualTo("REJECTED");
    assertThat(r1.path("blockers").get(0).asString()).isEqualTo("UNCONFIRMED_EARNING");
    // 직원 탈퇴 목록(0.19.0 — 라이더 백오피스): 거절은 막은 것과 함께, 라이더 번호로 좁힌다, 라이더 토큰은 403
    String number =
        jdbc.queryForObject(
            "SELECT rider_number FROM rider WHERE rider_id = ?", String.class, rider);
    JsonNode rejected = staffWithdrawals("riderNumber=" + number);
    assertThat(rejected.path("items")).hasSize(1);
    assertThat(rejected.path("items").get(0).path("state").asString()).isEqualTo("REJECTED");
    assertThat(rejected.path("items").get(0).path("blockers").get(0).asString())
        .isEqualTo("UNCONFIRMED_EARNING");
    assertThat(rejected.path("items").get(0).path("riderNumber").asString()).isEqualTo(number);
    assertThat(get(rider, "/riders/withdrawals").getStatusCode().value()).isEqualTo(403);

    // 2) 제한 검토 중(이 서비스) — execution 은 비었어도 막는다
    blockers(rider, "[]");
    jdbc.update(
        "INSERT INTO rider_restriction_review (review_id, rider_id, complaint_type, state,"
            + " opened_at) VALUES (?, ?, 'REPORT', 'OPEN', now())",
        "RV-" + rider,
        rider);
    ResponseEntity<String> review = withdraw(rider, "w-1"); // 같은 키 — 다시 검사
    assertThat(review.getStatusCode().value()).isEqualTo(409);
    assertThat(review.getBody()).contains("RESTRICTION_REVIEW");
    jdbc.update(
        "UPDATE rider_restriction_review SET state = 'DISMISSED' WHERE rider_id = ?", rider);

    // 3) execution 을 못 부르면 503 — 탈퇴하지 않는다
    execution.resetAll();
    execution.stubFor(
        WireMock.get(urlPathEqualTo(String.format(BLOCKERS, rider)))
            .willReturn(aResponse().withStatus(503)));
    assertThat(withdraw(rider, "w-2").getStatusCode().value()).isEqualTo(503);
    assertThat(get(rider, "/riders/me").getStatusCode().value()).isEqualTo(200);

    // 4) 막는 것 없음 → 바로 완료, 개인정보 지움, RiderWithdrawn
    blockers(rider, "[]");
    ResponseEntity<String> done = withdraw(rider, "w-1");
    assertThat(done.getStatusCode().value()).isEqualTo(202);
    assertThat(json(done).path("status").asString()).isEqualTo("APPLIED");
    assertThat(json(done).path("nextAction").asString()).isEqualTo("LOGOUT");
    assertThat(json(get(rider, "/riders/me/withdrawals/w-1")).path("state").asString())
        .isEqualTo("COMPLETED");
    assertThat(withdraw(rider, "w-1").getStatusCode().value()).isEqualTo(202); // 같은 키 재전송
    JsonNode completed = staffWithdrawals("state=COMPLETED&riderNumber=" + number);
    assertThat(completed.path("items")).hasSize(1);
    assertThat(completed.path("items").get(0).path("completedAt").isNull()).isFalse();
    assertThat(completed.path("items").get(0).toString()).doesNotContain(phone);
    assertThat(staffWithdrawals("state=REJECTED&riderNumber=" + number).path("items")).isEmpty();
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT name, phone, bank_account_number, license_hash, operating_status,"
                + " withdrawn_at FROM rider WHERE rider_id = ?",
            rider);
    assertThat(row.get("name")).isNull();
    assertThat(row.get("phone")).isNull();
    assertThat(row.get("license_hash")).isNull();
    assertThat(row.get("operating_status")).isEqualTo("OFF_DUTY");
    assertThat(row.get("withdrawn_at")).isNotNull();
    String event =
        jdbc.queryForObject(
            "SELECT payload::text FROM outbox_event WHERE aggregateid = ? AND type ="
                + " 'RiderWithdrawn'",
            String.class,
            rider);
    assertThat(event).contains(rider).doesNotContain(phone);
    // 탈퇴 뒤 본인 조회 404, 같은 주체로 다시 가입은 409(다시 로그인하면 새 주체)
    assertThat(get(rider, "/riders/me").getStatusCode().value()).isEqualTo(404);
    ResponseEntity<String> again = signup(rider, phone, "again-" + rider);
    assertThat(again.getStatusCode().value()).isEqualTo(409);
    assertThat(json(again).path("code").asString()).isEqualTo("RIDER_WITHDRAWN");
    // 같은 전화로 새 주체 재가입은 30일 막힘(2026-11-19 사용자 "30일 동안 막기"), 기한이 지나면 된다
    String fresh = "RD-wd2-" + UUID.randomUUID();
    ResponseEntity<String> blockedJoin = signup(fresh, phone, "k-" + fresh, rider);
    assertThat(blockedJoin.getStatusCode().value()).isEqualTo(409);
    assertThat(json(blockedJoin).path("code").asString()).isEqualTo("RIDER_REJOIN_BLOCKED");
    assertThat(blockedJoin.getBody()).contains("30일 동안");
    // 부정 이용 방지 보관(처리방침 §3): 이름·전화를 막는 기간 동안만 따로 두고, 기한이 지나면 주기 작업이 지운다
    Map<String, Object> held =
        jdbc.queryForMap("SELECT name, phone FROM rider_withdrawal_hold WHERE rider_id = ?", rider);
    assertThat(held.get("phone")).isEqualTo(phone);
    assertThat(held.get("name")).isNotNull();
    purger.purge();
    assertThat(count("SELECT count(*) FROM rider_withdrawal_hold WHERE rider_id = ?", rider))
        .isEqualTo(1);
    jdbc.update(
        "UPDATE rider_withdrawal_hold SET held_until = now() - interval '1 minute' WHERE rider_id = ?",
        rider);
    jdbc.update("UPDATE rider_rejoin_block SET blocked_until = now() - interval '1 minute'");
    assertThat(signup(fresh, phone, "k2-" + fresh, rider).getStatusCode().value()).isEqualTo(201);
    purger.purge();
    assertThat(count("SELECT count(*) FROM rider_withdrawal_hold WHERE rider_id = ?", rider))
        .isZero();
    assertThat(count("SELECT count(*) FROM rider_rejoin_block WHERE blocked_until <= now()"))
        .isZero();
    // 직원 찾기에서도 빠진다
    String ops = TestJwt.operator("ro-1", List.of("RIDER_OPS"), List.of());
    JsonNode found =
        json(
            http.get()
                .uri("/riders?riderId={id}", rider)
                .header("Authorization", "Bearer " + ops)
                .retrieve()
                .toEntity(String.class));
    assertThat(found.path("items")).isEmpty();
  }

  private void blockers(String rider, String json) {
    execution.resetAll();
    execution.stubFor(
        WireMock.get(urlPathEqualTo(String.format(BLOCKERS, rider)))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"riderId\":\"" + rider + "\",\"blockers\":" + json + "}")));
  }

  private ResponseEntity<String> withdraw(String rider, String key) {
    return http.post()
        .uri("/riders/me/withdrawals")
        .headers(
            h -> {
              h.setBearerAuth(TestJwt.rider(rider));
              h.set("Idempotency-Key", key);
              h.set("X-Source-System", "rider-app");
            })
        .body(Map.of("reason", "그만둠"))
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

  private ResponseEntity<String> signup(String rider, String phone) {
    return signup(rider, phone, "k-" + rider, rider);
  }

  private ResponseEntity<String> signup(String rider, String phone, String key) {
    return signup(rider, phone, key, rider);
  }

  /** licenseOf 의 면허 번호로(같은 면허로 재가입 확인용). */
  private ResponseEntity<String> signup(String rider, String phone, String key, String licenseOf) {
    return http.post()
        .uri("/riders/signups")
        .headers(
            h -> {
              h.setBearerAuth(TestJwt.rider(rider));
              h.set("Idempotency-Key", key);
              h.set("X-Source-System", "rider-app");
            })
        .body(
            Map.of(
                "phone",
                phone,
                "name",
                "김라이더",
                "vehicleType",
                "MOTORCYCLE",
                "deliveryAreas",
                List.of(Map.of("mfcId", "MFC-IT-1")),
                "consents",
                List.of(Map.of("consentType", "TERMS", "version", "2026-11")),
                "licenseNumber",
                String.format(
                    "%012d", Math.floorMod(licenseOf.hashCode(), 1_000_000_000L) * 1000L + 7)))
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

  private long count(String sql, Object... args) {
    Long n = jdbc.queryForObject(sql, Long.class, args);
    return n == null ? 0 : n;
  }

  private JsonNode staffWithdrawals(String query) {
    String ops = TestJwt.operator("ro-1", List.of("RIDER_OPS"), List.of());
    return json(
        http.get()
            .uri("/riders/withdrawals?" + query)
            .header("Authorization", "Bearer " + ops)
            .retrieve()
            .toEntity(String.class));
  }
}
