package com.pmart.mfc.capacity.domain;

import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * 작업자 명단 ↔ 인사·권한 연결 규칙. 순수 계산.
 *
 * <ul>
 *   <li>사용자 결정 2026-11-13 "추천대로"(pc-split §1-2): 인사에서 작업자 자동 생성, 퇴사·정지 자동 반영, 고용 형태 정본 = 인사.
 *   <li>사용자 2026-11-13(PR #3 검토) "센터 관리자는 작업자와 별개": 인사 등록만으로는 만들지 않는다 — 그 센터 {@code MFC_WORKER} 권한이
 *       반영될 때(employee-auth {@code GrantApplied}) 만든다. 인사 등록 사건에는 역할이 없다(ADR-0056 결정 4). 센터 이동도
 *       권한으로 따라간다(BO-01: A 종료·B 시작 권한을 따로).
 * </ul>
 */
public final class HrLinkRules {

  /** 인사 사건으로 바꾼 상태 변경의 주체(mfc_worker_state_change.changed_by). */
  public static final String HR_ACTOR = "hr-service";

  /** 권한(생성·기간)으로 바꾼 상태 변경·등록의 주체. */
  public static final String GRANT_ACTOR = "employee-auth-service";

  /** 자동으로 상태를 바꾼 주체 — 이 주체가 내린 상태만 자동으로 다시 올린다(센터 관리자가 멈춘 것은 그대로). */
  public static final Set<String> AUTO_ACTORS = Set.of(HR_ACTOR, GRANT_ACTOR);

  /** 작업자를 만드는 역할. */
  public static final String WORKER_ROLE = "MFC_WORKER";

  /** MFC 센터 코드 접두사 — 권한 센터 중 MFC 만(같은 역할이 다른 센터에 붙는 일은 없지만 지킨다). */
  public static final String MFC_PREFIX = "MFC-";

  public static boolean isMfc(@Nullable String centerId) {
    return centerId != null && centerId.startsWith(MFC_PREFIX);
  }

  /**
   * 자동 생성 작업자의 자격. 정규직·알바는 비운다 — 업무는 센터 관리자가 정한다(사용자 결정 2026-11-08 "정규 = 관리자가 업무"). 일일크루는 등록 기본 자격
   * 그대로(PICK·PACK, 그날 업무 = 유효 자격 — 시스템).
   */
  public static Set<String> initialQualification(String employmentType) {
    return "DAILY".equals(employmentType) ? WorkerRules.DAILY_DEFAULT_QUALIFICATION : Set.of();
  }

  /**
   * 자동으로 맞출 상태. 퇴사 → LEFT, 정지 → SUSPENDED, 권한으로 만든 작업자인데 지금 유효한 권한이 없으면 SUSPENDED(기간 전·끝), 아니면
   * ACTIVE.
   *
   * @param hrStatus 인사 상태(인사 사건을 받은 적 없으면 null)
   * @param grantLinked 그 센터 MFC_WORKER 권한 기록이 있는가(센터 관리자가 직접 등록만 한 작업자는 false)
   */
  public static String desiredState(
      @Nullable String hrStatus, boolean grantLinked, boolean grantValidNow) {
    if ("LEFT".equals(hrStatus)) {
      return "LEFT";
    }
    if ("SUSPENDED".equals(hrStatus) || (grantLinked && !grantValidNow)) {
      return "SUSPENDED";
    }
    return "ACTIVE";
  }

  /**
   * 지금 상태에서 자동으로 옮길 상태, 그대로면 null. 내리는 것(정지·퇴사)은 늘, 올리는 것(ACTIVE)은 자동 주체가 내린 경우만. LEFT 는 되돌리지 않는다.
   */
  public static @Nullable String transition(
      String current, String desired, @Nullable String lastChangedBy) {
    if ("LEFT".equals(current) || current.equals(desired)) {
      return null;
    }
    if ("ACTIVE".equals(desired) && !AUTO_ACTORS.contains(lastChangedBy)) {
      return null;
    }
    return desired;
  }

  private HrLinkRules() {}
}
