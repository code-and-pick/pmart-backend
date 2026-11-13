package com.pmart.mfc.capacity;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.EventEnvelopeFixtures;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.mfc.capacity.infrastructure.QualificationStore;
import com.pmart.mfc.capacity.infrastructure.WorkerStore;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * 작업자 명단 ↔ 인사(hr-service 직원 사건 platform.employee.v1, 실제 Kafka — 사용자 결정 2026-11-13): MFC 소속 등록 → 작업자
 * 자동 생성(자격·업무 없음, 표시명 = 사번), MFC 아닌 소속은 무시, 정지·복귀·퇴사 반영, 늦게 온 옛 사건 버림, 이미 있는 작업자는 고용 형태만 인사 값으로, 센터
 * 관리자가 멈춘 작업자는 인사 복귀로 풀지 않음.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.capacity.units-per-hour=3"
    })
class HrLinkIT extends PostgresKafkaContainerSupport {

  static final String MFC = "MFC-HR-1";

  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired JdbcTemplate jdbc;
  @Autowired WorkerStore workers;
  @Autowired QualificationStore quals;

  void send(String type, String op, long version, Map<String, Object> payload) {
    Map<String, Object> p = new LinkedHashMap<>(payload);
    p.put("operatorId", op);
    kafka
        .send(
            "platform.employee.v1",
            op,
            EventEnvelopeFixtures.envelope(type)
                .from("hr-service")
                .aggregate("Employee", op, version)
                .payload(p)
                .buildJson())
        .join();
  }

  static Map<String, Object> registered(String type, String center) {
    return Map.of(
        "employeeNo",
        "P2026-00042",
        "keycloakUserId",
        UUID.randomUUID().toString(),
        "mailAddress",
        "x@pmart.com",
        "employmentType",
        type,
        "affiliation",
        Map.of("type", "CENTER", "code", center),
        "startDate",
        "2026-11-13");
  }

  static Map<String, Object> suspended() {
    return Map.of("reason", "SUSPENDED", "effectiveAt", Instant.now().toString());
  }

  static <T> T await(Supplier<T> s, Predicate<T> ok) throws InterruptedException {
    Instant until = Instant.now().plus(Duration.ofSeconds(30));
    T v = s.get();
    while (!ok.test(v) && Instant.now().isBefore(until)) {
      Thread.sleep(200);
      v = s.get();
    }
    return v;
  }

  String state(String op) {
    return workers.find(MFC, op).map(WorkerStore.WorkerRow::state).orElse(null);
  }

  Long hrVersion(String op) {
    return workers.hrVersion(MFC, op);
  }

  @Test
  void registeredRegularWorkerIsCreatedWithoutDutyAndFollowsHrStatus() throws Exception {
    String op = "OP-HR-" + UUID.randomUUID();
    send("EmployeeRegistered", op, 1, registered("REGULAR", MFC));
    assertThat(await(() -> state(op), "ACTIVE"::equals)).isEqualTo("ACTIVE");
    var w = workers.find(MFC, op).orElseThrow();
    assertThat(w.displayName()).as("이름은 인사 사건에 없다 — 사번").isEqualTo("P2026-00042");
    assertThat(w.registeredBy()).isEqualTo("hr-service");
    assertThat(quals.employmentType(MFC, op)).isEqualTo("REGULAR");
    assertThat(quals.current(MFC, op).orElseThrow().taskTypes()).as("업무는 센터 관리자가").isEmpty();
    assertThat(workers.duty(MFC, op, 1).taskTypes()).isEmpty();

    send("EmployeeSuspended", op, 2, suspended());
    assertThat(await(() -> state(op), "SUSPENDED"::equals)).isEqualTo("SUSPENDED");

    send("EmployeeReinstated", op, 3, Map.of("effectiveAt", Instant.now().toString()));
    assertThat(await(() -> state(op), "ACTIVE"::equals)).isEqualTo("ACTIVE");

    // 늦게 온 옛 정지(v2, 새 eventId) — 버린다
    send("EmployeeSuspended", op, 2, suspended());
    assertThat(await(() -> hrVersion(op), v -> v != null && v >= 3)).isEqualTo(3L);
    send("EmployeeLeft", op, 4, Map.of("effectiveAt", Instant.now().toString()));
    assertThat(await(() -> state(op), "LEFT"::equals)).isEqualTo("LEFT");
    List<String> history =
        jdbc.queryForList(
            "SELECT to_state FROM mfc_worker_state_change WHERE mfc_id = ? AND worker_id = ?"
                + " ORDER BY changed_at, change_id",
            String.class,
            MFC,
            op);
    assertThat(history).containsExactly("ACTIVE", "SUSPENDED", "ACTIVE", "LEFT");
  }

  @Test
  void dailyCrewGetsDefaultQualification() throws Exception {
    String op = "OP-HR-" + UUID.randomUUID();
    send("EmployeeRegistered", op, 1, registered("DAILY", MFC));
    assertThat(await(() -> state(op), "ACTIVE"::equals)).isEqualTo("ACTIVE");
    assertThat(quals.employmentType(MFC, op)).isEqualTo("DAILY");
    assertThat(quals.current(MFC, op).orElseThrow().taskTypes())
        .containsExactlyInAnyOrder("PICK", "PACK");
  }

  @Test
  void nonMfcAffiliationIsIgnored() throws Exception {
    String op = "OP-HR-" + UUID.randomUUID();
    send("EmployeeRegistered", op, 1, registered("REGULAR", "CDC-HR-1"));
    // 같은 키(파티션) 뒤 사건이 처리될 때까지 기다린 뒤 확인
    send("EmployeeSuspended", op, 2, suspended());
    String marker = "OP-HR-" + UUID.randomUUID();
    send("EmployeeRegistered", marker, 1, registered("REGULAR", MFC));
    await(() -> state(marker), "ACTIVE"::equals);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM mfc_worker WHERE worker_id = ?", Integer.class, op))
        .isZero();
  }

  @Test
  void existingWorkerTakesHrEmploymentTypeAndSupervisorSuspensionStays() throws Exception {
    String op = "OP-HR-" + UUID.randomUUID();
    Instant now = Instant.now();
    // 센터 관리자가 먼저 등록(정규직, PICK)
    workers.insert(MFC, op, "홍길동", "sup-1", now);
    workers.insertDuty(MFC, op, 1, Set.of("PICK"), Set.of(), "등록", "sup-1", now);
    quals.insert(MFC, op, 1, Set.of("PICK"), null, "등록", "sup-1", now);
    workers.setState(MFC, op, null, "ACTIVE", "등록", "sup-1", now);

    send("EmployeeRegistered", op, 1, registered("PART_TIME", MFC));
    assertThat(await(() -> hrVersion(op), v -> v != null)).isEqualTo(1L);
    assertThat(quals.employmentType(MFC, op)).as("고용 형태 정본 = 인사").isEqualTo("PART_TIME");
    var w = workers.find(MFC, op).orElseThrow();
    assertThat(w.displayName()).isEqualTo("홍길동");
    assertThat(workers.duty(MFC, op, w.dutyVersion()).taskTypes()).containsExactly("PICK");

    // 센터 관리자가 멈춤 → 인사 복귀가 와도 그대로
    workers.setState(MFC, op, "ACTIVE", "SUSPENDED", "현장 사정", "sup-1", Instant.now());
    send("EmployeeReinstated", op, 2, Map.of("effectiveAt", Instant.now().toString()));
    assertThat(await(() -> hrVersion(op), v -> v != null && v >= 2)).isEqualTo(2L);
    assertThat(state(op)).isEqualTo("SUSPENDED");
  }
}
