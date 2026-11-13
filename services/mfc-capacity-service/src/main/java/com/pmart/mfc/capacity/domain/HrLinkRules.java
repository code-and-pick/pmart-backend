package com.pmart.mfc.capacity.domain;

import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * 작업자 명단 ↔ 인사(hr-service) 연결 규칙(사용자 결정 2026-11-13 "추천대로", pc-split §1-2): 인사 등록 때 MFC 작업자 자동 생성,
 * 퇴사·정지 자동 반영, 고용 형태 정본 = 인사.
 */
public final class HrLinkRules {

  /** 인사 사건으로 바꾼 상태 변경의 주체(mfc_worker_state_change.changed_by). */
  public static final String HR_ACTOR = "hr-service";

  /** MFC 센터 코드 접두사 — 센터 코드 체계(MFC-…·CDC-…)에서 MFC 만 작업자를 만든다. */
  public static final String MFC_PREFIX = "MFC-";

  /**
   * 자동 생성 작업자의 자격. 정규직·알바는 비운다 — 업무는 센터 관리자가 정한다(사용자 결정 2026-11-08 "정규 = 관리자가 업무"). 일일크루는 등록 기본 자격
   * 그대로(PICK·PACK, 그날 업무 = 유효 자격 — 시스템).
   */
  public static Set<String> initialQualification(String employmentType) {
    return "DAILY".equals(employmentType) ? WorkerRules.DAILY_DEFAULT_QUALIFICATION : Set.of();
  }

  /** 소속이 MFC 센터면 그 센터 ID, 아니면 null(본사 부서·CDC 등은 MFC 작업자가 아니다). */
  public static @Nullable String mfcOf(@Nullable String affiliationType, @Nullable String code) {
    if (!"CENTER".equals(affiliationType) || code == null || !code.startsWith(MFC_PREFIX)) {
      return null;
    }
    return code;
  }

  /**
   * 상태 사건을 받은 뒤의 작업자 상태, 바꾸지 않으면 null.
   *
   * <ul>
   *   <li>정지(인사 정지·계약 종료) → SUSPENDED, 퇴사 → LEFT(센터 관리자가 직접 등록한 작업자도 — 인사가 정본).
   *   <li>복귀 → ACTIVE. 단 지금 상태를 인사가 만든 경우만 — 센터 관리자가 직접 멈춘 작업자는 그대로 둔다(MFC 안 사정은 MFC 가 정한다).
   *       퇴사(LEFT)는 되돌리지 않는다.
   * </ul>
   */
  public static @Nullable String nextState(
      String eventType, String current, @Nullable String lastChangedBy) {
    String to =
        switch (eventType) {
          case "EmployeeSuspended" -> "SUSPENDED";
          case "EmployeeLeft" -> "LEFT";
          case "EmployeeReinstated" ->
              "SUSPENDED".equals(current) && HR_ACTOR.equals(lastChangedBy) ? "ACTIVE" : null;
          default -> null;
        };
    if (to == null || to.equals(current) || "LEFT".equals(current)) {
      return null;
    }
    return to;
  }

  private HrLinkRules() {}
}
