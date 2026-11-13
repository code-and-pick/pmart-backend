package com.pmart.mfc.capacity.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.mfc.capacity.domain.HrLinkRules;
import com.pmart.mfc.capacity.domain.WorkerRules;
import com.pmart.mfc.capacity.infrastructure.QualificationStore;
import com.pmart.mfc.capacity.infrastructure.WorkerStore;
import com.pmart.mfc.capacity.infrastructure.WorkerStore.WorkerRow;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * hr-service 직원 사건(platform.employee.v1)을 MFC 작업자 명단에 반영한다(사용자 결정 2026-11-13 "추천대로", pc-split §1-2,
 * 규칙 {@link HrLinkRules}). inbox 트랜잭션 안에서 불린다(같은 사건 두 번 반영 없음).
 *
 * <ul>
 *   <li>EmployeeRegistered — 소속이 MFC 센터면 그 센터에 작업자를 만든다(표시명 = 사번: 인사 사건에 이름이 없다 — 개인정보). 정규직·알바는
 *       자격·업무 없이(배정 안 됨 — 센터 관리자가 자격·업무를 정한다), 일일크루는 기본 자격. 이미 있으면 고용 형태만 인사 값으로.
 *   <li>EmployeeSuspended·EmployeeLeft — 그 운영자 ID 의 모든 센터 작업자를 SUSPENDED·LEFT(새 배정만 막는다 — 기존 배정은
 *       {@code workerEligibility} 로 드러난다).
 *   <li>EmployeeReinstated — 인사가 멈춘 작업자만 ACTIVE.
 *   <li>행마다 반영한 사건 버전(hr_version)보다 작거나 같은 사건은 버린다(늦게 온 옛 사건).
 * </ul>
 */
@Component
public class HrEmployeeHandler {

  private static final Logger log = LoggerFactory.getLogger(HrEmployeeHandler.class);
  public static final List<String> TYPES =
      List.of("EmployeeRegistered", "EmployeeSuspended", "EmployeeReinstated", "EmployeeLeft");

  private final WorkerStore workers;
  private final QualificationStore quals;
  private final Clock clock;

  public HrEmployeeHandler(WorkerStore workers, QualificationStore quals, Clock clock) {
    this.workers = workers;
    this.quals = quals;
    this.clock = clock;
  }

  public void on(EventEnvelope e) {
    JsonNode p = e.payload();
    String operatorId = p.path("operatorId").asText(null);
    if (operatorId == null || operatorId.isBlank()) {
      throw new IllegalArgumentException("operatorId 없음"); // 계약 위반 — 바인더가 DLT 로
    }
    Instant now = clock.instant();
    if ("EmployeeRegistered".equals(e.eventType())) {
      registered(e, operatorId, p, now);
      return;
    }
    for (WorkerRow w : workers.lockAllCenters(operatorId)) {
      if (stale(w, e)) {
        continue;
      }
      String to =
          HrLinkRules.nextState(
              e.eventType(), w.state(), workers.lastStateChangedBy(w.mfcId(), w.workerId()));
      if (to != null) {
        workers.setState(
            w.mfcId(), w.workerId(), w.state(), to, reason(e, p), HrLinkRules.HR_ACTOR, now);
        log.info("인사 사건 반영: {} {} {} → {}", e.eventType(), w.mfcId(), w.workerId(), to);
      }
      workers.setHrVersion(w.mfcId(), w.workerId(), e.aggregateVersion());
    }
  }

  private void registered(EventEnvelope e, String operatorId, JsonNode p, Instant now) {
    JsonNode a = p.path("affiliation");
    String mfcId = HrLinkRules.mfcOf(a.path("type").asText(null), a.path("code").asText(null));
    if (mfcId == null) {
      log.debug("MFC 소속 아님 — 작업자 안 만듦: operatorId={}", operatorId);
      return;
    }
    String type = p.path("employmentType").asText("REGULAR");
    if (!WorkerRules.EMPLOYMENT_TYPES.contains(type)) {
      throw new IllegalArgumentException("모르는 고용 형태: " + type); // 계약 위반 — DLT
    }
    String employeeNo = p.path("employeeNo").asText(operatorId);
    if (workers.insert(mfcId, operatorId, employeeNo, HrLinkRules.HR_ACTOR, now)) {
      Set<String> qual = HrLinkRules.initialQualification(type);
      workers.insertDuty(mfcId, operatorId, 1, qual, Set.of(), "인사 등록", HrLinkRules.HR_ACTOR, now);
      quals.setEmploymentType(mfcId, operatorId, type);
      quals.insert(mfcId, operatorId, 1, qual, null, "인사 등록", HrLinkRules.HR_ACTOR, now);
      workers.setState(mfcId, operatorId, null, "ACTIVE", "인사 등록", HrLinkRules.HR_ACTOR, now);
      workers.setHrVersion(mfcId, operatorId, e.aggregateVersion());
      log.info("인사 등록 → 작업자 생성: {} {} ({})", mfcId, operatorId, type);
      return;
    }
    WorkerRow w = workers.lock(mfcId, operatorId).orElseThrow();
    if (stale(w, e)) {
      return;
    }
    quals.setEmploymentType(mfcId, operatorId, type); // 고용 형태 정본 = 인사
    workers.setHrVersion(mfcId, operatorId, e.aggregateVersion());
    log.info("인사 등록 → 이미 있는 작업자 연결: {} {} ({})", mfcId, operatorId, type);
  }

  private boolean stale(WorkerRow w, EventEnvelope e) {
    @Nullable Long v = workers.hrVersion(w.mfcId(), w.workerId());
    if (v != null && e.aggregateVersion() <= v) {
      log.info(
          "늦게 온 인사 사건 버림: {} {} {} v{}",
          e.eventType(),
          w.mfcId(),
          w.workerId(),
          e.aggregateVersion());
      return true;
    }
    return false;
  }

  private static String reason(EventEnvelope e, JsonNode p) {
    return switch (e.eventType()) {
      case "EmployeeSuspended" -> "인사 정지(" + p.path("reason").asText("SUSPENDED") + ")";
      case "EmployeeLeft" -> "인사 퇴사";
      default -> "인사 복귀";
    };
  }
}
