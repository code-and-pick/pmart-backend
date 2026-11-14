package com.pmart.rider;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.EventEnvelopeFixtures;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.kafka.core.KafkaTemplate;
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
  @Autowired KafkaTemplate<String, String> kafka;

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

  /** RS-1(2026-11-14): 운영자 계정 제한 → 신규배차 꺼짐·적격 목록에서 빠짐·RiderRestricted, 풀기·기한 지남·권한. */
  @Test
  void operatorRestrictsAndLiftsRider() {
    String rider = "RD-it-" + UUID.randomUUID();
    assertThat(signup(rider, phone(), "k-" + rider).getStatusCode().value()).isEqualTo(201);
    http.put()
        .uri("/riders/me/state")
        .headers(h -> riderHeaders(h, rider, "s-" + rider))
        .body(Map.of("status", "ON_DUTY", "newDispatchEnabled", true, "mfcId", "MFC-IT-1"))
        .retrieve()
        .toEntity(String.class);
    assertThat(eligibleIds("MFC-IT-1")).contains(rider);

    // OPERATOR_ADMIN 이 아니면 403(센터 관리자도), 라이더 토큰도 403
    assertThat(
            restrict(
                    rider,
                    "x1-" + rider,
                    "MFC_SUPERVISOR",
                    Map.of("restriction", "SUSPENDED", "reason", "r"))
                .getStatusCode()
                .value())
        .isEqualTo(403);
    assertThat(
            http.get()
                .uri("/riders/{id}/restrictions", rider)
                .header("Authorization", "Bearer " + TestJwt.rider(rider))
                .retrieve()
                .toEntity(String.class)
                .getStatusCode()
                .value())
        .isEqualTo(403);
    // 지난 기한 400, 제한 없는데 풀기 409
    assertThat(
            restrict(
                    rider,
                    "x2-" + rider,
                    "OPERATOR_ADMIN",
                    Map.of(
                        "restriction", "SUSPENDED", "reason", "r", "until", "2020-01-01T00:00:00Z"))
                .getStatusCode()
                .value())
        .isEqualTo(400);
    assertThat(
            restrict(
                    rider,
                    "x3-" + rider,
                    "OPERATOR_ADMIN",
                    Map.of("restriction", "NORMAL", "reason", "r"))
                .getStatusCode()
                .value())
        .isEqualTo(409);

    Map<String, Object> body =
        Map.of(
            "restriction",
            "DISPATCH_RESTRICTED",
            "reason",
            "고객 신고 3건 확인",
            "evidenceRefs",
            List.of("CASE-1", "CASE-2"),
            "approvalRef",
            "APR-1");
    ResponseEntity<String> r = restrict(rider, "r-" + rider, "OPERATOR_ADMIN", body);
    assertThat(r.getStatusCode().value()).isEqualTo(200);
    JsonNode rec = json(r.getBody());
    assertThat(rec.path("restriction").asString()).isEqualTo("DISPATCH_RESTRICTED");
    assertThat(rec.path("previous").asString()).isEqualTo("NORMAL");
    assertThat(rec.path("changedBy").asString()).isEqualTo("OP-1");
    assertThat(rec.path("evidenceRefs")).hasSize(2);
    // 같은 키·같은 본문 = 같은 결과(사건 하나)
    assertThat(
            json(restrict(rider, "r-" + rider, "OPERATOR_ADMIN", body).getBody())
                .path("version")
                .asInt())
        .isEqualTo(rec.path("version").asInt());

    assertThat(eligibleIds("MFC-IT-1")).doesNotContain(rider);
    JsonNode me =
        json(
            http.get()
                .uri("/riders/me")
                .header("Authorization", "Bearer " + TestJwt.rider(rider))
                .retrieve()
                .body(String.class));
    assertThat(me.path("restriction").path("state").asString()).isEqualTo("DISPATCH_RESTRICTED");
    assertThat(me.path("operating").path("status").asString()).isEqualTo("ON_DUTY"); // 운행은 그대로
    assertThat(me.path("operating").path("newDispatchEnabled").asBoolean()).isFalse();

    List<String> restricted =
        jdbc.queryForList(
            "SELECT payload::text FROM outbox_event WHERE aggregateid = ? AND type = 'RiderRestricted'",
            String.class,
            rider);
    assertThat(restricted).hasSize(1);
    JsonNode ev = json(restricted.get(0)).path("payload");
    assertThat(ev.path("restriction").asString()).isEqualTo("DISPATCH_RESTRICTED");
    assertThat(ev.path("basis").asString()).isEqualTo("고객 신고 3건 확인");
    assertThat(ev.path("evidenceRefs").get(0).path("evidenceId").asString()).isEqualTo("CASE-1");
    assertThat(ev.path("approvalRef").path("approvalId").asString()).isEqualTo("APR-1");
    assertThat(json(restricted.get(0)).path("actor").path("type").asString()).isEqualTo("OPERATOR");

    // 풀기 → RiderRestricted(NORMAL), 신규배차는 라이더가 다시 켠다
    ResponseEntity<String> lift =
        restrict(
            rider,
            "l-" + rider,
            "OPERATOR_ADMIN",
            Map.of("restriction", "NORMAL", "reason", "소명 확인"));
    assertThat(lift.getStatusCode().value()).isEqualTo(200);
    assertThat(json(lift.getBody()).path("previous").asString()).isEqualTo("DISPATCH_RESTRICTED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE aggregateid = ? AND type = 'RiderRestricted'",
                Integer.class,
                rider))
        .isEqualTo(2);
    JsonNode history =
        json(
            http.get()
                .uri("/riders/{id}/restrictions", rider)
                .header(
                    "Authorization",
                    "Bearer " + TestJwt.operator("OP-1", List.of("OPERATOR_ADMIN"), List.of()))
                .retrieve()
                .body(String.class));
    assertThat(history.path("current").asString()).isEqualTo("NORMAL");
    assertThat(history.path("items")).hasSize(2);
    assertThat(history.path("items").get(0).path("restriction").asString()).isEqualTo("NORMAL");

    // 기한이 지난 제한은 없는 것으로 본다(운행 시작·적격)
    jdbc.update(
        "UPDATE rider SET restriction = 'SUSPENDED', restriction_until = now() - interval '1 minute'"
            + " WHERE rider_id = ?",
        rider);
    ResponseEntity<String> on =
        http.put()
            .uri("/riders/me/state")
            .headers(h -> riderHeaders(h, rider, "s2-" + rider))
            .body(Map.of("status", "ON_DUTY", "newDispatchEnabled", true, "mfcId", "MFC-IT-1"))
            .retrieve()
            .toEntity(String.class);
    assertThat(on.getStatusCode().value()).isEqualTo(200);
    assertThat(eligibleIds("MFC-IT-1")).contains(rider);
  }

  /** RS-3(사용자 2026-11-14): 같은 종류 컴플레인 7일 안 서로 다른 배송 3건 이상 → 검토 대상, 운영자가 닫음. 자동 제재 없음. */
  @Test
  void repeatedComplaintsOpenAReviewForTheOperator() {
    String rider = "RD-it-" + UUID.randomUUID();
    assertThat(signup(rider, phone(), "k-" + rider).getStatusCode().value()).isEqualTo(201);
    Instant now = Instant.now();
    complaint("C1-" + rider, rider, "D1", "REPORT", now.minus(Duration.ofDays(6)));
    complaint("C2-" + rider, rider, "D1", "REPORT", now.minus(Duration.ofDays(5))); // 같은 배송
    complaint("C3-" + rider, rider, "D2", "REPORT", now.minus(Duration.ofDays(3)));
    complaint("C4-" + rider, rider, "D3", "MISDELIVERY", now.minus(Duration.ofDays(2))); // 다른 종류
    awaitComplaints(rider, 4);
    assertThat(reviews(rider, "OPEN")).isEmpty(); // 신고는 배송 2곳뿐

    complaint("C5-" + rider, rider, "D4", "REPORT", now);
    complaint("C5-" + rider, rider, "D4", "REPORT", now); // 같은 컴플레인 다시 와도 한 번
    awaitComplaints(rider, 5);
    List<JsonNode> open = reviews(rider, "OPEN");
    assertThat(open).hasSize(1);
    JsonNode review = open.get(0);
    assertThat(review.path("complaintType").asString()).isEqualTo("REPORT");
    assertThat(review.path("complaints").valueStream().map(c -> c.path("complaintId").asString()))
        .containsExactlyInAnyOrder("C1-" + rider, "C2-" + rider, "C3-" + rider, "C5-" + rider);
    // 자동 제재 없음
    assertThat(
            jdbc.queryForObject(
                "SELECT restriction FROM rider WHERE rider_id = ?", String.class, rider))
        .isEqualTo("NORMAL");

    // 열린 동안 온 같은 종류는 거기에 묶인다
    complaint("C6-" + rider, rider, "D5", "REPORT", now);
    awaitComplaints(rider, 6);
    assertThat(reviews(rider, "OPEN").get(0).path("complaints")).hasSize(5);

    String reviewId = review.path("reviewId").asString();
    // 제한을 걸지 않고 RESTRICTED 로 닫기 409, 센터 관리자 403
    assertThat(
            decide(reviewId, "d1-" + reviewId, "OPERATOR_ADMIN", "RESTRICTED")
                .getStatusCode()
                .value())
        .isEqualTo(409);
    assertThat(
            decide(reviewId, "d2-" + reviewId, "MFC_SUPERVISOR", "DISMISSED")
                .getStatusCode()
                .value())
        .isEqualTo(403);
    ResponseEntity<String> dismissed =
        decide(reviewId, "d3-" + reviewId, "OPERATOR_ADMIN", "DISMISSED");
    assertThat(dismissed.getStatusCode().value()).isEqualTo(200);
    assertThat(json(dismissed.getBody()).path("state").asString()).isEqualTo("DISMISSED");
    assertThat(json(dismissed.getBody()).path("decidedBy").asString()).isEqualTo("OP-1");

    // 닫힌 검토의 컴플레인은 다시 세지 않는다 — 새 1건으로는 대상 아님
    complaint("C7-" + rider, rider, "D6", "REPORT", now);
    awaitComplaints(rider, 7);
    assertThat(reviews(rider, "OPEN")).isEmpty();
    assertThat(reviews(rider, "DISMISSED")).hasSize(1);
  }

  private void complaint(String id, String rider, String delivery, String type, Instant at) {
    String json =
        EventEnvelopeFixtures.envelope("RiderComplaintRecorded")
            .from("case-service")
            .aggregate("Case", "CASE-" + id, 1)
            .payload(
                Map.of(
                    "complaintId",
                    id,
                    "caseId",
                    "CASE-" + id,
                    "riderId",
                    rider,
                    "deliveryId",
                    delivery + "-" + rider,
                    "complaintType",
                    type,
                    "recordedAt",
                    at.toString()))
            .buildJson();
    kafka.send("platform.case.v1", "CASE-" + id, json).join();
  }

  private void awaitComplaints(String rider, int n) {
    long until = System.currentTimeMillis() + 30_000;
    while (System.currentTimeMillis() < until) {
      Integer c =
          jdbc.queryForObject(
              "SELECT count(*) FROM rider_complaint WHERE rider_id = ?", Integer.class, rider);
      if (c != null && c >= n) {
        // 같은 컴플레인 중복 사건까지 처리되게 잠깐 더
        sleep(500);
        return;
      }
      sleep(200);
    }
    throw new AssertionError("컴플레인 " + n + "건이 오지 않음");
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private List<JsonNode> reviews(String rider, String state) {
    JsonNode n =
        json(
            http.get()
                .uri("/riders/restriction-reviews?state={s}&riderId={r}", state, rider)
                .header(
                    "Authorization",
                    "Bearer " + TestJwt.operator("OP-1", List.of("OPERATOR_ADMIN"), List.of()))
                .retrieve()
                .body(String.class));
    return n.path("items").valueStream().toList();
  }

  private ResponseEntity<String> decide(String reviewId, String key, String role, String decision) {
    return http.post()
        .uri("/riders/restriction-reviews/{id}/decisions", reviewId)
        .header("Authorization", "Bearer " + TestJwt.operator("OP-1", List.of(role), List.of()))
        .header("Idempotency-Key", key)
        .header("X-Source-System", "backoffice-web")
        .body(Map.of("decision", decision, "note", "확인함"))
        .retrieve()
        .toEntity(String.class);
  }

  private ResponseEntity<String> restrict(
      String rider, String key, String role, Map<String, Object> body) {
    return http.post()
        .uri("/riders/{id}/restrictions", rider)
        .header("Authorization", "Bearer " + TestJwt.operator("OP-1", List.of(role), List.of()))
        .header("Idempotency-Key", key)
        .header("X-Source-System", "backoffice-web")
        .body(body)
        .retrieve()
        .toEntity(String.class);
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
