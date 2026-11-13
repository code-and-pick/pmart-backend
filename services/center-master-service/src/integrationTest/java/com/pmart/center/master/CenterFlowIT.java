package com.pmart.center.master;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.EventEnvelopeFixtures;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 센터 승인(CENTER_MASTER, ApprovalDecided) → 센터 생성 → 영업 시간표 → 캘린더 조회(공개·내부) → 사건. 실제 PostgreSQL·Kafka.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.messaging.outbox.delete-after-insert=false"
    })
@Import(TestJwtConfiguration.class)
class CenterFlowIT extends PostgresKafkaContainerSupport {

  static final String OPS = TestJwt.operator("ops-1", List.of("OPS"), List.of());
  static final String SERVICE = TestJwt.service("delivery-capacity-service");

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired KafkaTemplate<String, String> kafka;

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

  Res get(String token, String path) {
    var e =
        http.get().uri(path).headers(h -> h.setBearerAuth(token)).retrieve().toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  String approve(List<Map<String, Object>> lineAttributes) {
    String approvalId = "AP-" + UUID.randomUUID();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("approvalId", approvalId);
    payload.put("type", "CENTER_MASTER");
    payload.put("decision", "APPROVED");
    payload.put(
        "targetRef",
        Map.of(
            "targetType",
            "Center",
            "targetId",
            "CENTER-X",
            "targetService",
            "center-master-service"));
    payload.put(
        "lines",
        lineAttributes.stream()
            .map(
                a ->
                    Map.of(
                        "lineId",
                        UUID.randomUUID().toString(),
                        "decision",
                        "APPROVED",
                        "attributes",
                        a))
            .toList());
    payload.put("approverId", "lead-1");
    kafka
        .send(
            "platform.approval.v1",
            approvalId,
            EventEnvelopeFixtures.envelope("ApprovalDecided")
                .from("approval-service")
                .aggregate("Approval", approvalId, 2)
                .payload(payload)
                .buildJson())
        .join();
    return approvalId;
  }

  String awaitApplied(String approvalId) throws InterruptedException {
    Instant until = Instant.now().plus(Duration.ofSeconds(30));
    while (Instant.now().isBefore(until)) {
      List<String> r =
          jdbc.queryForList(
              "SELECT result FROM approval_application WHERE approval_id = ?",
              String.class,
              approvalId);
      if (!r.isEmpty()) {
        return r.get(0);
      }
      Thread.sleep(200);
    }
    throw new AssertionError("승인 반영 안 됨: " + approvalId);
  }

  static Map<String, Object> weekly(String start, String end) {
    List<Map<String, Object>> days =
        List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN").stream()
            .map(
                d ->
                    Map.<String, Object>of(
                        "dayOfWeek", d, "ranges", List.of(Map.of("start", start, "end", end))))
            .toList();
    return Map.of("kind", "CUSTOMER_ORDER", "weekly", days, "holidays", List.of("2026-12-25"));
  }

  @Test
  void approvedCenterAndCalendar() throws Exception {
    String centerId = "MFC-IT-" + UUID.randomUUID().toString().substring(0, 8);
    // 시간표 없음·센터 없음
    assertThat(get(OPS, "/centers/" + centerId).status()).isEqualTo(404);

    String created =
        approve(
            List.of(
                Map.of(
                    "changeType",
                    "CREATE",
                    "centerId",
                    centerId,
                    "profile",
                    Map.of(
                        "type",
                        "MFC",
                        "name",
                        "시험 MFC",
                        "address",
                        "서울 서초구",
                        "coordinates",
                        Map.of("lat", 37.49, "lng", 127.01),
                        "temperatureZones",
                        List.of("AMBIENT", "CHILLED", "FROZEN"),
                        "floors",
                        1))));
    assertThat(awaitApplied(created)).startsWith("CREATE:" + centerId + ":v1");
    Res center = get(OPS, "/centers/" + centerId);
    assertThat(center.status()).isEqualTo(200);
    assertThat(center.body().path("name").asString()).isEqualTo("시험 MFC");
    assertThat(center.body().path("state").asString()).isEqualTo("ACTIVE");
    assertThat(center.body().path("floors").asInt()).isEqualTo(1);
    assertThat(center.body().path("temperatureZones")).hasSize(3);
    assertThat(get(OPS, "/centers?type=MFC&temperatureZone=FROZEN").body().path("items").toString())
        .contains(centerId);

    // 시간표 승인 전 → CONFIG_REQUIRED(상시 영업으로 보지 않음)
    String today = LocalDate.now(ZoneId.of("Asia/Seoul")).toString();
    Res noTable =
        get(OPS, "/centers/" + centerId + "/calendar?date=" + today + "&kind=CUSTOMER_ORDER");
    assertThat(noTable.status()).isEqualTo(422);
    assertThat(noTable.body().path("code").asString()).isEqualTo("CONFIG_REQUIRED");

    // 잘못된 시각은 반영하지 않고 사유만 남긴다
    String bad =
        approve(
            List.of(
                Map.of(
                    "changeType",
                    "UPDATE_CALENDAR",
                    "centerId",
                    centerId,
                    "calendar",
                    weekly("08:00", "24:30"))));
    assertThat(awaitApplied(bad)).startsWith("INVALID:");

    String cal =
        approve(
            List.of(
                Map.of(
                    "changeType",
                    "UPDATE_CALENDAR",
                    "centerId",
                    centerId,
                    "calendar",
                    weekly("08:00", "24:00"))));
    assertThat(awaitApplied(cal)).contains("UPDATE_CALENDAR:" + centerId + ":CUSTOMER_ORDER:v1");
    Res day = get(OPS, "/centers/" + centerId + "/calendar?date=" + today + "&kind=CUSTOMER_ORDER");
    assertThat(day.status()).isEqualTo(200);
    assertThat(day.body().path("open").asBoolean()).isTrue();
    assertThat(day.body().path("ranges").path(0).path("start").asString()).isEqualTo("08:00");
    assertThat(day.body().path("ranges").path(0).path("end").asString()).isEqualTo("24:00");
    Res holiday =
        get(OPS, "/centers/" + centerId + "/calendar?date=2026-12-25&kind=CUSTOMER_ORDER");
    assertThat(holiday.body().path("open").asBoolean()).isFalse();
    assertThat(holiday.body().path("isHoliday").asBoolean()).isTrue();
    // 다른 종류는 아직 미승인
    assertThat(
            get(OPS, "/centers/" + centerId + "/calendar?date=" + today + "&kind=STAFF").status())
        .isEqualTo(422);

    // 서비스 토큰 내부 조회, 사용자 토큰은 내부 경로 403
    Res internal =
        get(
            SERVICE,
            "/internal/centers/" + centerId + "/calendar?date=" + today + "&kind=CUSTOMER_ORDER");
    assertThat(internal.status()).isEqualTo(200);
    assertThat(internal.body().path("calendarVersion").asInt()).isEqualTo(1);
    assertThat(
            get(
                    OPS,
                    "/internal/centers/"
                        + centerId
                        + "/calendar?date="
                        + today
                        + "&kind=CUSTOMER_ORDER")
                .status())
        .isEqualTo(403);

    // 내부 센터 조회(delivery-dispatch, CM-1 2026-11-13) — 공개 조회와 같은 모양, 좌표 포함
    Res internalCenter = get(SERVICE, "/internal/centers/" + centerId);
    assertThat(internalCenter.status()).isEqualTo(200);
    assertThat(internalCenter.body().path("centerId").asString()).isEqualTo(centerId);
    assertThat(internalCenter.body().path("coordinates").path("lat").isNumber()).isTrue();
    assertThat(get(SERVICE, "/internal/centers/MFC-NONE-" + centerId).status()).isEqualTo(404);

    // 사건: platform.center.v1
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE topic = 'platform.center.v1' AND aggregateid = ?"
                    + " AND type IN ('CenterMasterChanged', 'CenterCalendarChanged')",
                Integer.class,
                centerId))
        .isEqualTo(2);
  }
}
