package com.pmart.mfc.capacity.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.mfc.capacity.domain.HrLinkRules;
import com.pmart.mfc.capacity.domain.WorkerRules;
import com.pmart.mfc.capacity.infrastructure.HrLinkStore;
import com.pmart.mfc.capacity.infrastructure.HrLinkStore.Employee;
import com.pmart.mfc.capacity.infrastructure.HrLinkStore.Grant;
import com.pmart.mfc.capacity.infrastructure.QualificationStore;
import com.pmart.mfc.capacity.infrastructure.WorkerStore;
import com.pmart.mfc.capacity.infrastructure.WorkerStore.WorkerRow;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * 작업자 명단 ↔ 인사·권한(규칙 {@link HrLinkRules}). 사건 처리는 inbox 트랜잭션 안(같은 사건 두 번 반영 없음).
 *
 * <ul>
 *   <li>인사 직원 사건(platform.employee.v1) — 인사 사본(hr_employee: 사번·고용 형태·재직 상태)만 갱신하고, 그 운영자의 작업자들을
 *       맞춘다. <b>등록만으로는 작업자를 만들지 않는다</b>(센터 관리자 ≠ 작업자, 사용자 2026-11-13).
 *   <li>권한 반영(employee-auth {@code GrantApplied}, platform.auth.v1) 중 역할 MFC_WORKER — 센터마다 권한 기간을
 *       남기고, 인사 사본이 있으면 그 센터 작업자를 만든다(없으면 등록 사건이 올 때 만든다 — 두 토픽 사이 순서는 보장되지 않는다).
 *   <li>권한 기간 경계(시작 전·끝)는 사건이 없어 주기 작업({@link #sweep()})이 맞춘다 — 유효한 권한이 없으면 SUSPENDED, 다시 생기면
 *       ACTIVE.
 *   <li>상태를 올리는(ACTIVE) 것은 자동 주체가 내린 경우만 — 센터 관리자가 멈춘 작업자는 그대로. LEFT 는 되돌리지 않는다.
 * </ul>
 */
@Service
public class HrLinkService {

  private static final Logger log = LoggerFactory.getLogger(HrLinkService.class);
  public static final List<String> EMPLOYEE_TYPES =
      List.of("EmployeeRegistered", "EmployeeSuspended", "EmployeeReinstated", "EmployeeLeft");

  private final HrLinkStore links;
  private final WorkerStore workers;
  private final QualificationStore quals;
  private final TransactionTemplate tx;
  private final Clock clock;

  public HrLinkService(
      HrLinkStore links,
      WorkerStore workers,
      QualificationStore quals,
      PlatformTransactionManager txManager,
      Clock clock) {
    this.links = links;
    this.workers = workers;
    this.quals = quals;
    this.tx = new TransactionTemplate(txManager);
    this.clock = clock;
  }

  /** 구독 필터: MFC_WORKER 권한 반영. */
  public static boolean workerGrant(EventEnvelope e) {
    if (!"GrantApplied".equals(e.eventType())) {
      return false;
    }
    for (JsonNode r : e.payload().path("roles")) {
      if (HrLinkRules.WORKER_ROLE.equals(r.asText(null))) {
        return true;
      }
    }
    return false;
  }

  // ------------------------------------------------------------------ 인사 직원 사건

  public void onEmployee(EventEnvelope e) {
    JsonNode p = e.payload();
    String operatorId = required(p, "operatorId");
    Instant now = clock.instant();
    Employee before = links.lockEmployee(operatorId).orElse(null);
    if (before != null && e.aggregateVersion() <= before.hrVersion()) {
      log.info("늦게 온 인사 사건 버림: {} {} v{}", e.eventType(), operatorId, e.aggregateVersion());
      return;
    }
    String status =
        switch (e.eventType()) {
          case "EmployeeSuspended" -> "SUSPENDED";
          case "EmployeeLeft" -> "LEFT";
          default -> "ACTIVE";
        };
    if ("EmployeeRegistered".equals(e.eventType())) {
      String type = p.path("employmentType").asText("REGULAR");
      if (!WorkerRules.EMPLOYMENT_TYPES.contains(type)) {
        throw new IllegalArgumentException("모르는 고용 형태: " + type); // 계약 위반 — DLT
      }
      links.upsertRegistered(
          operatorId,
          p.path("employeeNo").asText(operatorId),
          type,
          status,
          e.aggregateVersion(),
          now);
    } else {
      links.upsertStatus(operatorId, status, e.aggregateVersion(), now);
    }
    Employee hr = links.lockEmployee(operatorId).orElseThrow();
    // 이 운영자의 작업자가 있는 센터 + 권한이 있는 센터(등록보다 권한이 먼저 온 경우 여기서 만든다)
    Set<String> centers = new LinkedHashSet<>();
    workers.lockAllCenters(operatorId).forEach(w -> centers.add(w.mfcId()));
    centers.addAll(links.grantCenters(operatorId));
    for (String mfcId : centers) {
      if (hr.employmentType() != null && "EmployeeRegistered".equals(e.eventType())) {
        workers
            .find(mfcId, operatorId)
            .ifPresent(w -> quals.setEmploymentType(mfcId, operatorId, hr.employmentType()));
      }
      reconcile(mfcId, operatorId, hr, HrLinkRules.HR_ACTOR, now);
    }
  }

  // ------------------------------------------------------------------ 권한 반영

  public void onGrant(EventEnvelope e) {
    JsonNode p = e.payload();
    String operatorId = required(p, "operatorId");
    String grantId = required(p, "grantId");
    Instant from = Instant.parse(required(p.path("effective"), "from"));
    String toText = p.path("effective").path("to").asText(null);
    Instant to = toText == null || toText.isBlank() ? null : Instant.parse(toText);
    Instant now = clock.instant();
    Employee hr = links.lockEmployee(operatorId).orElse(null);
    for (JsonNode c : p.path("centerScope")) {
      String mfcId = c.asText(null);
      if (!HrLinkRules.isMfc(mfcId)) {
        continue;
      }
      links.insertGrant(grantId, mfcId, operatorId, from, to, now);
      if (hr == null) {
        log.info("MFC_WORKER 권한 기록 — 인사 등록을 기다림: {} {}", mfcId, operatorId);
        continue;
      }
      reconcile(mfcId, operatorId, hr, HrLinkRules.GRANT_ACTOR, now);
    }
  }

  // ------------------------------------------------------------------ 권한 기간 경계

  /** 권한 기간 시작·끝을 맞춘다(사건이 없다). 짝마다 따로 트랜잭션. */
  @Scheduled(fixedDelayString = "${pmart.capacity.hr-link.sweep-interval:PT1M}")
  public void sweep() {
    for (String[] pair : links.grantPairs()) {
      try {
        tx.executeWithoutResult(
            s -> {
              Employee hr = links.lockEmployee(pair[1]).orElse(null);
              if (hr != null) {
                reconcile(pair[0], pair[1], hr, HrLinkRules.GRANT_ACTOR, clock.instant());
              }
            });
      } catch (RuntimeException ex) {
        log.warn("권한 기간 맞추기 실패: {} {} — {}", pair[0], pair[1], ex.toString());
      }
    }
  }

  // ------------------------------------------------------------------ 맞추기

  /** 한 센터의 작업자를 인사·권한에 맞춘다: 없으면(권한이 있으면) 만들고, 있으면 상태를 맞춘다. */
  void reconcile(String mfcId, String operatorId, Employee hr, String actor, Instant now) {
    List<Grant> grants = links.grants(mfcId, operatorId);
    boolean linked = !grants.isEmpty();
    boolean valid = grants.stream().anyMatch(g -> g.validAt(now));
    String desired = HrLinkRules.desiredState(hr.status(), linked, valid);
    WorkerRow w = workers.lock(mfcId, operatorId).orElse(null);
    if (w == null) {
      boolean pending = grants.stream().anyMatch(g -> !g.endedAt(now));
      if (pending && hr.employmentType() != null && !"LEFT".equals(hr.status())) {
        create(mfcId, hr, desired, now);
      }
      return;
    }
    String to =
        HrLinkRules.transition(w.state(), desired, workers.lastStateChangedBy(mfcId, operatorId));
    if (to != null) {
      workers.setState(mfcId, operatorId, w.state(), to, reason(hr, valid), actor, now);
      log.info("작업자 상태 자동 반영: {} {} {} → {} ({})", mfcId, operatorId, w.state(), to, actor);
    }
  }

  private void create(String mfcId, Employee hr, String state, Instant now) {
    String op = hr.operatorId();
    String type = java.util.Objects.requireNonNull(hr.employmentType());
    String name = hr.employeeNo() != null ? hr.employeeNo() : op; // 인사 사건에 이름 없음 — 사번
    String by = HrLinkRules.GRANT_ACTOR;
    if (!workers.insert(mfcId, op, name, by, now)) {
      return; // 같은 때 다른 사건이 만들었다
    }
    Set<String> qual = HrLinkRules.initialQualification(type);
    workers.insertDuty(mfcId, op, 1, qual, Set.of(), "MFC_WORKER 권한", by, now);
    quals.setEmploymentType(mfcId, op, type);
    quals.insert(mfcId, op, 1, qual, null, "MFC_WORKER 권한", by, now);
    workers.setState(mfcId, op, null, state, "MFC_WORKER 권한", by, now);
    log.info("MFC_WORKER 권한 → 작업자 생성: {} {} ({}, {})", mfcId, op, type, state);
  }

  private static String reason(Employee hr, boolean grantValid) {
    return switch (hr.status()) {
      case "LEFT" -> "인사 퇴사";
      case "SUSPENDED" -> "인사 정지";
      default -> grantValid ? "인사 재직·권한 유효" : "MFC_WORKER 권한 기간 밖";
    };
  }

  private static String required(JsonNode p, String field) {
    @Nullable String v = p.path(field).asText(null);
    if (v == null || v.isBlank()) {
      throw new IllegalArgumentException(field + " 없음"); // 계약 위반 — 바인더가 DLT 로
    }
    return v;
  }
}
