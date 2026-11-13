package com.pmart.rider;

import static org.assertj.core.api.Assertions.assertThat;

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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 라이더 가입(전화번호 중복만, 즉시 운행 승인) → 운행 시작·신규배차 ON(RiderStateChanged) → 적격 라이더 조회 → 설정 → 계정 제한 판정. 실제
 * PostgreSQL·Kafka, 시험 라이더 토큰.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.messaging.outbox.delete-after-insert=false"
    })
@Import(TestJwtConfiguration.class)
class RiderFlowIT extends PostgresKafkaContainerSupport {

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;

  RestClient http;

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
  }

  @Test
  void signupStartDutyAndEligibleListing() {
    String rider = "RD-it-" + UUID.randomUUID();
    String phone = phone();
    ResponseEntity<String> signup = signup(rider, phone, "k-" + rider);
    assertThat(signup.getStatusCode().value()).isEqualTo(201);
    JsonNode p = json(signup.getBody());
    assertThat(p.path("riderId").asString()).isEqualTo(rider);
    assertThat(p.path("phoneMasked").asString()).doesNotContain(phone.substring(3, 7));
    assertThat(p.path("operating").path("status").asString()).isEqualTo("OFF_DUTY");
    assertThat(p.path("settings").path("dispatchMode").asString()).isEqualTo("AI_RECOMMENDED");
    assertThat(p.path("restriction").path("state").asString()).isEqualTo("NORMAL");

    // 같은 키 재전송 = 같은 결과, 같은 주체 다른 키 = 409, 다른 주체 같은 전화 = 409
    assertThat(signup(rider, phone, "k-" + rider).getStatusCode().value()).isEqualTo(201);
    ResponseEntity<String> again = signup(rider, phone(), "k2-" + rider);
    assertThat(again.getStatusCode().value()).isEqualTo(409);
    assertThat(json(again.getBody()).path("code").asString()).isEqualTo("DUPLICATE_RIDER");
    assertThat(signup("RD-it-" + UUID.randomUUID(), phone, "k3-" + rider).getStatusCode().value())
        .isEqualTo(409);

    // 운행 시작 전에는 적격 목록에 없다
    assertThat(eligibleIds("MFC-IT-1")).doesNotContain(rider);

    ResponseEntity<String> start =
        http.put()
            .uri("/riders/me/state")
            .headers(h -> riderHeaders(h, rider, "s-" + rider))
            .header("X-Expected-Version", "0")
            .body(Map.of("status", "ON_DUTY", "newDispatchEnabled", true, "mfcId", "MFC-IT-1"))
            .retrieve()
            .toEntity(String.class);
    assertThat(start.getStatusCode().value()).isEqualTo(200);
    assertThat(json(start.getBody()).path("newDispatchEnabled").asBoolean()).isTrue();
    assertThat(json(start.getBody()).path("version").asInt()).isEqualTo(1);

    String payload =
        jdbc.queryForObject(
            "SELECT payload::text FROM outbox_event WHERE aggregateid = ? AND type ="
                + " 'RiderStateChanged'",
            String.class,
            rider);
    JsonNode env = json(payload);
    assertThat(env.path("payload").path("operatingState").asString()).isEqualTo("ON_DUTY");
    assertThat(env.path("payload").path("mfcId").asString()).isEqualTo("MFC-IT-1");
    assertThat(
            jdbc.queryForObject(
                "SELECT topic FROM outbox_event WHERE aggregateid = ?", String.class, rider))
        .isEqualTo("delivery.rider.v1");

    assertThat(eligibleIds("MFC-IT-1")).contains(rider);
    assertThat(eligibleIds("MFC-OTHER")).doesNotContain(rider);

    // 배달지역 밖 MFC 로 운행 시작 400, 옛 버전 409
    assertThat(
            http.put()
                .uri("/riders/me/state")
                .headers(h -> riderHeaders(h, rider, "s2-" + rider))
                .header("X-Expected-Version", "0")
                .body(Map.of("status", "ON_DUTY", "newDispatchEnabled", true))
                .retrieve()
                .toEntity(String.class)
                .getStatusCode()
                .value())
        .isEqualTo(409);

    // 하나씩 배달·일반 배차 모드
    ResponseEntity<String> settings =
        http.put()
            .uri("/riders/me/settings")
            .headers(h -> riderHeaders(h, rider, "set-" + rider))
            .body(Map.of("oneAtATime", true, "dispatchMode", "NORMAL"))
            .retrieve()
            .toEntity(String.class);
    assertThat(settings.getStatusCode().value()).isEqualTo(200);
    JsonNode internal =
        json(
            http.get()
                .uri("/internal/riders/{id}", rider)
                .header("X-Source-System", "delivery-dispatch-service")
                .retrieve()
                .body(String.class));
    assertThat(internal.path("oneAtATime").asBoolean()).isTrue();
    assertThat(internal.path("dispatchMode").asString()).isEqualTo("NORMAL");

    // 운행 종료 → 목록에서 빠짐, 신규배차는 꺼짐
    ResponseEntity<String> off =
        http.put()
            .uri("/riders/me/state")
            .headers(h -> riderHeaders(h, rider, "off-" + rider))
            .body(Map.of("status", "OFF_DUTY", "newDispatchEnabled", true))
            .retrieve()
            .toEntity(String.class);
    assertThat(json(off.getBody()).path("newDispatchEnabled").asBoolean()).isFalse();
    assertThat(eligibleIds("MFC-IT-1")).doesNotContain(rider);
  }

  @Test
  void suspendedRiderCannotStartAndOperatorTokenIsRejected() {
    String rider = "RD-it-" + UUID.randomUUID();
    assertThat(signup(rider, phone(), "k-" + rider).getStatusCode().value()).isEqualTo(201);
    jdbc.update("UPDATE rider SET restriction = 'SUSPENDED' WHERE rider_id = ?", rider);
    ResponseEntity<String> start =
        http.put()
            .uri("/riders/me/state")
            .headers(h -> riderHeaders(h, rider, "s-" + rider))
            .body(Map.of("status", "ON_DUTY", "newDispatchEnabled", false))
            .retrieve()
            .toEntity(String.class);
    assertThat(start.getStatusCode().value()).isEqualTo(403);

    ResponseEntity<String> operator =
        http.get()
            .uri("/riders/me")
            .header(
                "Authorization",
                "Bearer " + TestJwt.operator("OP-1", List.of("OPERATOR_ADMIN"), List.of()))
            .retrieve()
            .toEntity(String.class);
    assertThat(operator.getStatusCode().value()).isEqualTo(403);

    ResponseEntity<String> unknown =
        http.get()
            .uri("/riders/me")
            .header("Authorization", "Bearer " + TestJwt.rider("RD-none-" + UUID.randomUUID()))
            .retrieve()
            .toEntity(String.class);
    assertThat(unknown.getStatusCode().value()).isEqualTo(404);
  }

  private ResponseEntity<String> signup(String rider, String phone, String key) {
    return http.post()
        .uri("/riders/signups")
        .headers(h -> riderHeaders(h, rider, key))
        .body(
            Map.of(
                "phone",
                phone,
                "name",
                "시험 라이더",
                "vehicleType",
                "MOTORCYCLE",
                "deliveryAreas",
                List.of(Map.of("mfcId", "MFC-IT-1")),
                "consents",
                List.of(Map.of("consentType", "TERMS", "version", "2026-11"))))
        .retrieve()
        .toEntity(String.class);
  }

  private List<String> eligibleIds(String mfcId) {
    JsonNode n =
        json(
            http.get()
                .uri("/internal/riders/eligible?mfcId={m}&radiusKm=3", mfcId)
                .header("X-Source-System", "delivery-dispatch-service")
                .retrieve()
                .body(String.class));
    return n.path("items").valueStream().map(i -> i.path("riderId").asString()).toList();
  }

  private static void riderHeaders(
      org.springframework.http.HttpHeaders h, String rider, String key) {
    h.setBearerAuth(TestJwt.rider(rider));
    h.set("Idempotency-Key", key);
    h.set("X-Source-System", "rider-app");
  }

  private static String phone() {
    return "010"
        + String.format(
            "%08d", Math.abs(UUID.randomUUID().getLeastSignificantBits() % 100_000_000L));
  }

  private JsonNode json(String body) {
    return mapper.readTree(body == null ? "{}" : body);
  }
}
