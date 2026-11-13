package com.pmart.mfc.capacity;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.EventEnvelopeFixtures;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.mfc.capacity.application.HrLinkService;
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
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * 작업자 명단 ↔ 인사·권한(실제 Kafka — 사용자 2026-11-13: 결정 3개 "추천대로" + "센터 관리자는 작업자와 별개"): 인사 등록만으로는 작업자를 만들지
 * 않고 그 센터 MFC_WORKER 권한(GrantApplied)이 붙을 때 만든다. 권한이 먼저 와도 등록 때 만든다. 정지·복귀·퇴사 반영, 늦게 온 옛 사건 버림, 권한
 * 기간(시작 전·끝 — 센터 이동)은 주기 작업이 맞춘다, 센터 관리자가 멈춘 작업자는 자동으로 풀지 않는다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.capacity.units-per-hour=3",
      "pmart.capacity.hr-link.sweep-interval=PT1H" // 시험에서 직접 부른다
    })
class HrLinkIT extends PostgresKafkaContainerSupport {

  static final String MFC = "MFC-HR-1";
  static final String MFC_B = "MFC-HR-2";

  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired JdbcTemplate jdbc;
  @Autowired WorkerStore workers;
  @Autowired QualificationStore quals;
  @Autowired HrLinkService links;

  void employee(String type, String op, long version, Map<String, Object> payload) {
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

  void grant(String op, String role, List<String> centers, Instant from, @Nullable Instant to) {
    String grantId = "APR-" + UUID.randomUUID() + ":1";
    Map<String, Object> effective = new LinkedHashMap<>();
    effective.put("from", from.toString());
    effective.put("to", to == null ? null : to.toString());
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("grantId", grantId);
    p.put("approvalRef", Map.of("approvalId", "APR-1", "approverId", "RULE:X@v1"));
    p.put("operatorId", op);
    p.put("roles", List.of(role));
    p.put("centerScope", centers);
    p.put("effective", effective);
    kafka
        .send(
            "platform.auth.v1",
            grantId,
            EventEnvelopeFixtures.envelope("GrantApplied")
                .from("employee-auth-service")
                .aggregate("Grant", grantId, 1)
                .payload(p)
                .buildJson())
        .join();
  }

  static Map<String, Object> registered(String type) {
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
        Map.of("type", "CENTER", "code", MFC),
        "startDate",
        "2026-11-13");
  }

  static Map<String, Object> at() {
    return Map.of("effectiveAt", Instant.now().toString());
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

  String state(String mfc, String op) {
    return workers.find(mfc, op).map(WorkerStore.WorkerRow::state).orElse(null);
  }

  Long hrVersion(String op) {
    List<Long> r =
        jdbc.queryForList(
            "SELECT hr_version FROM hr_employee WHERE operator_id = ?", Long.class, op);
    return r.isEmpty() ? null : r.getFirst();
  }

  int grantRows(String op) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM mfc_worker_grant WHERE operator_id = ?", Integer.class, op);
  }

  /** 두 토픽의 앞 사건이 다 처리됐음을 확인하는 표지 — 표지 운영자의 작업자가 생기면 그 앞 사건은 끝난 것. */
  void drain() throws InterruptedException {
    String m = "OP-MARK-" + UUID.randomUUID();
    employee("EmployeeRegistered", m, 1, registered("REGULAR"));
    await(() -> hrVersion(m), v -> v != null);
    grant(m, "MFC_WORKER", List.of(MFC), Instant.now().minusSeconds(60), null);
    await(() -> state(MFC, m), "ACTIVE"::equals);
  }

  @Test
  void centerManagerIsNotAWorker() throws Exception {
    String op = "OP-SUP-" + UUID.randomUUID();
    employee("EmployeeRegistered", op, 1, registered("REGULAR"));
    grant(op, "MFC_SUPERVISOR", List.of(MFC), Instant.now().minusSeconds(60), null);
    drain();
    assertThat(hrVersion(op)).as("인사 사본은 남는다").isEqualTo(1L);
    assertThat(workers.find(MFC, op)).as("등록·관리자 권한만으로는 작업자가 아니다").isEmpty();
    assertThat(grantRows(op)).isZero();
  }

  @Test
  void workerGrantCreatesWorkerThatFollowsHrStatus() throws Exception {
    String op = "OP-HR-" + UUID.randomUUID();
    employee("EmployeeRegistered", op, 1, registered("REGULAR"));
    await(() -> hrVersion(op), v -> v != null);
    assertThat(workers.find(MFC, op)).isEmpty();

    grant(op, "MFC_WORKER", List.of(MFC), Instant.now().minusSeconds(60), null);
    assertThat(await(() -> state(MFC, op), "ACTIVE"::equals)).isEqualTo("ACTIVE");
    var w = workers.find(MFC, op).orElseThrow();
    assertThat(w.displayName()).as("이름은 인사 사건에 없다 — 사번").isEqualTo("P2026-00042");
    assertThat(w.registeredBy()).isEqualTo("employee-auth-service");
    assertThat(quals.employmentType(MFC, op)).isEqualTo("REGULAR");
    assertThat(quals.current(MFC, op).orElseThrow().taskTypes()).as("업무는 센터 관리자가").isEmpty();

    employee(
        "EmployeeSuspended",
        op,
        2,
        Map.of("reason", "SUSPENDED", "effectiveAt", at().get("effectiveAt")));
    assertThat(await(() -> state(MFC, op), "SUSPENDED"::equals)).isEqualTo("SUSPENDED");
    employee("EmployeeReinstated", op, 3, at());
    assertThat(await(() -> state(MFC, op), "ACTIVE"::equals)).isEqualTo("ACTIVE");
    // 늦게 온 옛 정지(v2, 새 eventId) — 버린다
    employee(
        "EmployeeSuspended",
        op,
        2,
        Map.of("reason", "SUSPENDED", "effectiveAt", at().get("effectiveAt")));
    employee("EmployeeLeft", op, 4, at());
    assertThat(await(() -> state(MFC, op), "LEFT"::equals)).isEqualTo("LEFT");
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
  void grantBeforeRegistrationWaitsForHr() throws Exception {
    String op = "OP-HR-" + UUID.randomUUID();
    grant(op, "MFC_WORKER", List.of(MFC), Instant.now().minusSeconds(60), null);
    await(() -> grantRows(op), n -> n == 1);
    assertThat(workers.find(MFC, op)).as("인사 등록 전 — 고용 형태를 모른다").isEmpty();

    employee("EmployeeRegistered", op, 1, registered("DAILY"));
    assertThat(await(() -> state(MFC, op), "ACTIVE"::equals)).isEqualTo("ACTIVE");
    assertThat(quals.employmentType(MFC, op)).isEqualTo("DAILY");
    assertThat(quals.current(MFC, op).orElseThrow().taskTypes())
        .containsExactlyInAnyOrder("PICK", "PACK");
  }

  @Test
  void centerMoveFollowsGrantPeriods() throws Exception {
    String op = "OP-MOVE-" + UUID.randomUUID();
    employee("EmployeeRegistered", op, 1, registered("REGULAR"));
    await(() -> hrVersion(op), v -> v != null);
    Instant now = Instant.now();
    // A 센터 권한은 곧 끝나고, B 센터 권한은 곧 시작(BO-01: 센터 이동 = A 종료·B 시작)
    grant(op, "MFC_WORKER", List.of(MFC), now.minusSeconds(60), now.plusSeconds(3));
    grant(op, "MFC_WORKER", List.of(MFC_B), now.plusSeconds(3), null);
    assertThat(await(() -> state(MFC, op), "ACTIVE"::equals)).isEqualTo("ACTIVE");
    assertThat(await(() -> state(MFC_B, op), "SUSPENDED"::equals))
        .as("권한 시작 전 — 만들어 두되 배정 안 됨")
        .isEqualTo("SUSPENDED");

    Thread.sleep(Duration.between(Instant.now(), now.plusSeconds(4)).toMillis());
    links.sweep();
    assertThat(state(MFC, op)).as("예전 센터 — 권한 끝").isEqualTo("SUSPENDED");
    assertThat(state(MFC_B, op)).as("새 센터 — 권한 시작").isEqualTo("ACTIVE");
  }

  @Test
  void existingWorkerTakesHrEmploymentTypeAndSupervisorSuspensionStays() throws Exception {
    String op = "OP-HR-" + UUID.randomUUID();
    Instant now = Instant.now();
    // 센터 관리자가 먼저 직접 등록(정규직, PICK) — 권한 기록 없음
    workers.insert(MFC, op, "홍길동", "sup-1", now);
    workers.insertDuty(MFC, op, 1, Set.of("PICK"), Set.of(), "등록", "sup-1", now);
    quals.insert(MFC, op, 1, Set.of("PICK"), null, "등록", "sup-1", now);
    workers.setState(MFC, op, null, "ACTIVE", "등록", "sup-1", now);

    employee("EmployeeRegistered", op, 1, registered("PART_TIME"));
    assertThat(await(() -> quals.employmentType(MFC, op), "PART_TIME"::equals))
        .as("고용 형태 정본 = 인사")
        .isEqualTo("PART_TIME");
    var w = workers.find(MFC, op).orElseThrow();
    assertThat(w.displayName()).isEqualTo("홍길동");
    assertThat(w.state()).as("권한 기록이 없는 직접 등록 작업자 — 권한으로 멈추지 않음").isEqualTo("ACTIVE");

    // 센터 관리자가 멈춤 → 인사 복귀가 와도 그대로
    workers.setState(MFC, op, "ACTIVE", "SUSPENDED", "현장 사정", "sup-1", Instant.now());
    employee("EmployeeReinstated", op, 2, at());
    assertThat(await(() -> hrVersion(op), v -> v != null && v >= 2)).isEqualTo(2L);
    assertThat(state(MFC, op)).isEqualTo("SUSPENDED");
  }
}
