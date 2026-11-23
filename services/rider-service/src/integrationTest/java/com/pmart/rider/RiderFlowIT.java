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
    // 라이더 관제(2026-11-17): 센터의 운행 중 라이더 — 가린 이름만
    JsonNode duty = onDuty("MFC-IT-1");
    JsonNode me =
        duty.path("items")
            .valueStream()
            .filter(i -> rider.equals(i.path("riderId").asString()))
            .findFirst()
            .orElseThrow();
    assertThat(me.path("operating").path("status").asString()).isEqualTo("ON_DUTY");
    assertThat(me.path("nameMasked").asString()).contains("*");
    // 전화번호는 내보내지 않는다 — 응답 전체에서 "010" 을 찾으면 무작위 riderId(UUID) 에 걸려 흔들린다(2026-11-21)
    assertThat(me.has("phone")).isFalse();
    assertThat(me.has("phoneMasked")).isFalse();
    assertThat(duty.toString()).doesNotContain(phone);
    // 접속·대기(2026-11-18): 운행 시작·신규배차 켠 시각, 신규배차를 꺼도 운행 시작은 그대로
    String since = me.path("onDutySince").asString();
    assertThat(since).isNotBlank();
    assertThat(me.path("availableSince").asString()).isEqualTo(since);
    assertThat(
            http.put()
                .uri("/riders/me/state")
                .headers(h -> riderHeaders(h, rider, "pause-" + rider))
                .body(Map.of("status", "ON_DUTY", "newDispatchEnabled", false))
                .retrieve()
                .toEntity(String.class)
                .getStatusCode()
                .value())
        .isEqualTo(200);
    JsonNode paused =
        onDuty("MFC-IT-1")
            .path("items")
            .valueStream()
            .filter(i -> rider.equals(i.path("riderId").asString()))
            .findFirst()
            .orElseThrow();
    assertThat(paused.path("onDutySince").asString()).isEqualTo(since);
    assertThat(paused.path("availableSince").isNull()).isTrue();

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
    assertThat(onDuty("MFC-IT-1").toString()).doesNotContain(rider); // 운행 끝 → 관제에서 빠짐
    // 운행 구간: 신규배차 켬 구간 → 끈 구간, 둘 다 닫힘
    JsonNode segs =
        json(
            http.get()
                .uri(
                    "/internal/riders/{id}/duty-segments?from={f}&to={t}",
                    rider,
                    java.time.Instant.now().minusSeconds(3600).toString(),
                    java.time.Instant.now().plusSeconds(60).toString())
                .header("X-Source-System", "delivery-execution-service")
                .retrieve()
                .body(String.class));
    assertThat(segs.path("items").size()).isEqualTo(2);
    assertThat(segs.path("items").get(0).path("available").asBoolean()).isTrue();
    assertThat(segs.path("items").get(1).path("available").asBoolean()).isFalse();
    assertThat(segs.path("items").get(1).path("endedAt").isNull()).isFalse();
    // 공지 받는 라이더(2026-11-18): 배달지역 센터로 거름
    JsonNode rec =
        json(
            http.get()
                .uri("/internal/riders/recipients?mfcId=MFC-IT-1&size=1000")
                .header("X-Source-System", "notification-service")
                .retrieve()
                .body(String.class));
    assertThat(rec.path("riderIds").toString()).contains(rider);
    assertThat(
            json(http.get()
                    .uri("/internal/riders/recipients?mfcId=MFC-NONE")
                    .header("X-Source-System", "notification-service")
                    .retrieve()
                    .body(String.class))
                .path("riderIds"))
        .isEmpty();
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

  /** 소명·이의 신청(2026-11-18 사용자 "앱에서 신청 → 백오피스 결정"): 대상마다 한 번, 제한 인정이면 바로 풀림, 수행료 인정은 조정 필요. */
  @Test
  void riderAppealsAndOpsDecide() {
    String rider = "RD-ap-" + UUID.randomUUID();
    assertThat(signup(rider, phone(), "k-" + rider).getStatusCode().value()).isEqualTo(201);
    // 제한 없으면 409
    assertThat(
            appeal(rider, Map.of("targetType", "RESTRICTION", "body", "왜요"))
                .getStatusCode()
                .value())
        .isEqualTo(409);
    assertThat(
            restrict(
                    rider,
                    "ap1-" + rider,
                    "OPERATOR_ADMIN",
                    Map.of("restriction", "DISPATCH_RESTRICTED", "reason", "지연 반복"))
                .getStatusCode()
                .value())
        .isEqualTo(200);
    ResponseEntity<String> sent =
        appeal(rider, Map.of("targetType", "RESTRICTION", "body", "그날은 엘리베이터 고장이었어요"));
    assertThat(sent.getStatusCode().value()).as(sent.getBody()).isEqualTo(200);
    String appealId = json(sent.getBody()).path("appealId").asString();
    assertThat(json(sent.getBody()).path("restriction").asString())
        .isEqualTo("DISPATCH_RESTRICTED");
    assertThat(appeal(rider, Map.of("targetType", "RESTRICTION", "body", "다시")).getBody())
        .contains("APPEAL_EXISTS");
    ResponseEntity<String> earning =
        appeal(rider, Map.of("targetType", "EARNING", "targetRef", "DLV-ap-1", "body", "산정 불가 이의"));
    assertThat(earning.getStatusCode().value()).isEqualTo(200);
    String earningAppeal = json(earning.getBody()).path("appealId").asString();

    String ops = TestJwt.operator("OP-RO", List.of("RIDER_OPS"), List.of());
    JsonNode open =
        json(
            http.get()
                .uri("/rider-appeals?riderId={r}", rider)
                .header("Authorization", "Bearer " + ops)
                .retrieve()
                .body(String.class));
    assertThat(open.path("items")).hasSize(2);
    // 상담원 403, 설명 없는 결정 400
    assertThat(
            decideAppeal(
                    appealId,
                    TestJwt.operator("OP-CS", List.of("CS_AGENT"), List.of()),
                    Map.of("decision", "ACCEPTED", "note", "x"))
                .getStatusCode()
                .value())
        .isEqualTo(403);
    assertThat(
            decideAppeal(appealId, ops, Map.of("decision", "ACCEPTED", "note", " "))
                .getStatusCode()
                .value())
        .isEqualTo(400);
    ResponseEntity<String> ok =
        decideAppeal(appealId, ops, Map.of("decision", "ACCEPTED", "note", "확인 결과 고장 맞음"));
    assertThat(ok.getStatusCode().value()).as(ok.getBody()).isEqualTo(200);
    assertThat(json(ok.getBody()).path("state").asString()).isEqualTo("ACCEPTED");
    JsonNode me =
        json(
            http.get()
                .uri("/riders/me")
                .header("Authorization", "Bearer " + TestJwt.rider(rider))
                .retrieve()
                .body(String.class));
    assertThat(me.path("restriction").path("state").asString()).isEqualTo("NORMAL");
    assertThat(
            decideAppeal(appealId, ops, Map.of("decision", "UPHELD", "note", "번복"))
                .getStatusCode()
                .value())
        .isEqualTo(409);
    JsonNode earned =
        json(
            decideAppeal(earningAppeal, ops, Map.of("decision", "ACCEPTED", "note", "조정할게요"))
                .getBody());
    assertThat(earned.path("adjustmentRequired").asBoolean()).isTrue();
    // 내부 조회(execution 보상금 확인 — 2026-11-19)
    JsonNode internal =
        json(
            http.get()
                .uri("/internal/rider-appeals/{id}", earningAppeal)
                .retrieve()
                .body(String.class));
    assertThat(internal.path("state").asString()).isEqualTo("ACCEPTED");
    assertThat(internal.path("targetRef").asString()).isEqualTo("DLV-ap-1");
    // 라이더는 결과·설명을 보고 결정한 직원은 못 본다
    JsonNode mine =
        json(
            http.get()
                .uri("/riders/me/appeals")
                .header("Authorization", "Bearer " + TestJwt.rider(rider))
                .retrieve()
                .body(String.class));
    assertThat(mine.path("items")).hasSize(2);
    assertThat(mine.path("items").get(0).path("decisionNote").asString()).isNotBlank();
    assertThat(mine.path("items").get(0).path("decidedBy").isNull()).isTrue();
    List<String> decided =
        jdbc.queryForList(
            "SELECT payload::text FROM outbox_event WHERE aggregateid = ? AND type = 'RiderAppealDecided'",
            String.class,
            rider);
    assertThat(decided).hasSize(2);
    assertThat(decided.get(0)).doesNotContain("고장");
  }

  ResponseEntity<String> appeal(String rider, Map<String, Object> body) {
    return http.post()
        .uri("/riders/me/appeals")
        .headers(h -> riderHeaders(h, rider, UUID.randomUUID().toString()))
        .body(body)
        .retrieve()
        .toEntity(String.class);
  }

  ResponseEntity<String> decideAppeal(String appealId, String token, Map<String, Object> body) {
    return http.post()
        .uri("/rider-appeals/{id}/decisions", appealId)
        .header("Authorization", "Bearer " + token)
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .header("X-Source-System", "rider-backoffice-web")
        .body(body)
        .retrieve()
        .toEntity(String.class);
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
    // 직원 조치 이력(2026-11-18, 관리자만): 방금 건 제한이 보인다
    JsonNode acts =
        json(
            http.get()
                .uri("/rider-staff-actions?riderId={r}", rider)
                .header(
                    "Authorization",
                    "Bearer " + TestJwt.operator("OP-ADM", List.of("OPERATOR_ADMIN"), List.of()))
                .retrieve()
                .body(String.class));
    assertThat(acts.path("items").get(0).path("action").asString()).isEqualTo("RESTRICTION_SET");
    assertThat(acts.path("items").get(0).path("detail").asString()).endsWith("DISPATCH_RESTRICTED");
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

  /**
   * 도움요청 대화 금칙어(2026-11-23 — 사용자 "응"): execution RiderHelpProfanityDetected(delivery.delivery.v1) →
   * 같은 7일 정지.
   */
  @Test
  void helpChatProfanitySuspendsSevenDays() {
    String rider = "RD-hp-" + UUID.randomUUID();
    assertThat(signup(rider, phone(), "k-" + rider).getStatusCode().value()).isEqualTo(201);
    String json =
        EventEnvelopeFixtures.envelope("RiderHelpProfanityDetected")
            .from("delivery-execution-service")
            .aggregate("Delivery", "DLV-hp-" + rider, 1)
            .payload(
                Map.of(
                    "riderId",
                    rider,
                    "deliveryId",
                    "DLV-hp-" + rider,
                    "requestId",
                    "HR-hp",
                    "source",
                    "HELP_MESSAGE",
                    "detectedAt",
                    Instant.now().toString()))
            .buildJson();
    kafka.send("delivery.delivery.v1", "DLV-hp-" + rider, json).join();
    long deadline = System.currentTimeMillis() + 30_000;
    while (System.currentTimeMillis() < deadline
        && !"SUSPENDED"
            .equals(
                jdbc.queryForObject(
                    "SELECT restriction FROM rider WHERE rider_id = ?", String.class, rider))) {
      Thread.onSpinWait();
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT restriction_reason FROM rider WHERE rider_id = ?", String.class, rider))
        .contains("배송 도움요청 대화")
        .contains("DLV-hp-");
  }

  /**
   * RS-8(2026-11-23): 문의 정지 단어 → 7일 정지(SUSPENDED, 찾은 시각 + 7일), RiderRestricted(SYSTEM). 같은 사건 다시 와도
   * 한 번.
   */
  @Test
  void profanityDetectedSuspendsSevenDays() {
    String rider = "RD-pf-" + UUID.randomUUID();
    assertThat(signup(rider, phone(), "k-" + rider).getStatusCode().value()).isEqualTo(201);
    Instant detected = Instant.now().minusSeconds(60);
    String json =
        EventEnvelopeFixtures.envelope("RiderProfanityDetected")
            .from("case-service")
            .aggregate("Case", "CASE-pf-" + rider, 1)
            .payload(
                Map.of(
                    "riderId",
                    rider,
                    "inquiryId",
                    "INQ-pf",
                    "caseId",
                    "CASE-pf-" + rider,
                    "source",
                    "INQUIRY_MESSAGE",
                    "detectedAt",
                    detected.toString()))
            .buildJson();
    kafka.send("platform.case.v1", "CASE-pf-" + rider, json).join();
    kafka.send("platform.case.v1", "CASE-pf-" + rider, json).join(); // 같은 사건 다시
    long deadline = System.currentTimeMillis() + 30_000;
    while (System.currentTimeMillis() < deadline
        && !"SUSPENDED"
            .equals(
                jdbc.queryForObject(
                    "SELECT restriction FROM rider WHERE rider_id = ?", String.class, rider))) {
      Thread.onSpinWait();
    }
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT restriction, restriction_until, new_dispatch_enabled FROM rider WHERE rider_id = ?",
            rider);
    assertThat(row.get("restriction")).isEqualTo("SUSPENDED");
    assertThat(((java.sql.Timestamp) row.get("restriction_until")).toInstant())
        .isCloseTo(
            detected.plus(Duration.ofDays(7)),
            org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.SECONDS));
    assertThat(row.get("new_dispatch_enabled")).isEqualTo(false);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM rider_restriction_history WHERE rider_id = ? AND changed_by ="
                    + " 'system:case-profanity'",
                Integer.class,
                rider))
        .isEqualTo(1);
    String payload =
        jdbc.queryForObject(
            "SELECT payload::text FROM outbox_event WHERE aggregateid = ? AND type = 'RiderRestricted'",
            String.class,
            rider);
    assertThat(payload).contains("SUSPENDED").doesNotContain("INQ-pf-글");
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
    return signup(rider, phone, key, "MOTORCYCLE", license(rider));
  }

  private ResponseEntity<String> signup(
      String rider,
      String phone,
      String key,
      String vehicle,
      @org.jspecify.annotations.Nullable String license) {
    Map<String, Object> b = new java.util.LinkedHashMap<>();
    b.put("phone", phone);
    b.put("name", "시험 라이더");
    b.put("vehicleType", vehicle);
    b.put("deliveryAreas", List.of(Map.of("mfcId", "MFC-IT-1")));
    b.put("consents", List.of(Map.of("consentType", "TERMS", "version", "2026-11")));
    if (license != null) {
      b.put("licenseNumber", license);
    }
    return http.post()
        .uri("/riders/signups")
        .headers(h -> riderHeaders(h, rider, key))
        .body(b)
        .retrieve()
        .toEntity(String.class);
  }

  private ResponseEntity<String> patchMe(String rider, Map<String, Object> body, int version) {
    return http.patch()
        .uri("/riders/me")
        .headers(
            h -> {
              riderHeaders(h, rider, "p-" + UUID.randomUUID());
              h.set("X-Expected-Version", String.valueOf(version));
            })
        .body(body)
        .retrieve()
        .toEntity(String.class);
  }

  /** 라이더마다 고정된 시험 면허 번호(같은 키 재전송이 같은 본문이 되게). */
  private static String license(String seed) {
    String d = String.format("%012d", Math.floorMod(seed.hashCode(), 1_000_000_000L) * 1000L + 11);
    return d.substring(0, 2)
        + "-"
        + d.substring(2, 4)
        + "-"
        + d.substring(4, 10)
        + "-"
        + d.substring(10);
  }

  /**
   * 라이더 앱 요청 §6·§7(2026-11-16): 전기자전거·킥보드 수단, 오토바이·자동차·킥보드는 면허 필수(422 LICENSE_REQUIRED), 같은 면허 409
   * DUPLICATE_LICENSE, 중복 확인 경로, 프로필은 가린 번호만.
   */
  @Test
  void licenseRulesAndNewVehicleTypes() {
    String a = "RD-it-" + UUID.randomUUID();
    ResponseEntity<String> noLicense = signup(a, phone(), "k-" + a, "KICKBOARD", null);
    assertThat(noLicense.getStatusCode().value()).isEqualTo(422);
    assertThat(json(noLicense.getBody()).path("code").asString()).isEqualTo("LICENSE_REQUIRED");
    String lic = license("lic-" + a);
    ResponseEntity<String> ok = signup(a, phone(), "k2-" + a, "KICKBOARD", lic);
    assertThat(ok.getStatusCode().value()).isEqualTo(201);
    JsonNode p = json(ok.getBody());
    assertThat(p.path("vehicleType").asString()).isEqualTo("KICKBOARD");
    assertThat(p.path("licenseNumberMasked").asString())
        .isEqualTo(lic.substring(0, 2) + "-**-******-" + lic.substring(lic.length() - 2));
    assertThat(ok.getBody()).doesNotContain(lic.replace("-", "")).doesNotContain(lic);
    // 중복 확인 경로·같은 면허 409
    String b = "RD-it-" + UUID.randomUUID();
    JsonNode check =
        json(
            http.post()
                .uri("/riders/license-checks")
                .headers(h -> riderHeaders(h, b, "c-" + b))
                .body(Map.of("licenseNumber", lic.replace("-", "")))
                .retrieve()
                .body(String.class));
    assertThat(check.path("available").asBoolean()).isFalse();
    ResponseEntity<String> dup = signup(b, phone(), "k-" + b, "CAR", lic);
    assertThat(dup.getStatusCode().value()).isEqualTo(409);
    assertThat(json(dup.getBody()).path("code").asString()).isEqualTo("DUPLICATE_LICENSE");
    // 전기자전거는 면허 선택
    ResponseEntity<String> ebike = signup(b, phone(), "k2-" + b, "E_BIKE", null);
    assertThat(ebike.getStatusCode().value()).isEqualTo(201);
    assertThat(json(ebike.getBody()).path("licenseNumberMasked").isNull()).isTrue();
    // 가입 뒤 오토바이로 바꾸려면 면허 필요(사용자 2026-11-16 "받는다")
    int v = json(ebike.getBody()).path("version").asInt();
    ResponseEntity<String> noLic = patchMe(b, Map.of("vehicleType", "MOTORCYCLE"), v);
    assertThat(noLic.getStatusCode().value()).isEqualTo(422);
    assertThat(json(noLic.getBody()).path("code").asString()).isEqualTo("LICENSE_REQUIRED");
    ResponseEntity<String> dupLic =
        patchMe(b, Map.of("vehicleType", "MOTORCYCLE", "licenseNumber", lic), v);
    assertThat(json(dupLic.getBody()).path("code").asString()).isEqualTo("DUPLICATE_LICENSE");
    String own = license("own-" + b);
    ResponseEntity<String> withLic =
        patchMe(b, Map.of("vehicleType", "MOTORCYCLE", "licenseNumber", own), v);
    assertThat(withLic.getStatusCode().value()).isEqualTo(200);
    assertThat(json(withLic.getBody()).path("vehicleType").asString()).isEqualTo("MOTORCYCLE");
    assertThat(json(withLic.getBody()).path("licenseNumberMasked").asString())
        .endsWith(own.substring(own.length() - 2));
  }

  private JsonNode onDuty(String mfcId) {
    return json(
        http.get()
            .uri("/internal/riders/on-duty?mfcId={m}", mfcId)
            .header("X-Source-System", "delivery-tracking-service")
            .retrieve()
            .body(String.class));
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
