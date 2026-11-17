package com.pmart.cases.domain;

import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * 행위 요청 기록 규칙(0.17.0 — 2026-11-17 사용자 "기록만 먼저"). case-service 는 대신 실행하지 않고 요청 사실·결과만 남긴다 — 프레임워크
 * 없음.
 */
public final class ActionRequestRules {

  public static final String COMPENSATION = "EXECUTE_APPROVED_COMPENSATION";

  public static final Set<String> TYPES =
      Set.of("REQUERY_RESULT", "RESEND_UNDELIVERED", COMPENSATION, "REQUEST_DOMAIN_REVIEW");

  public static final String REQUESTED = "REQUESTED";

  /** 결과로 남길 수 있는 상태. */
  public static final Set<String> RESULT_STATES =
      Set.of("SENT", "ACCEPTED", "APPLIED", "REJECTED", "UNKNOWN_RESULT");

  /** 끝난 상태 — 더 기록하지 않는다(다시 하려면 새 요청). */
  public static final Set<String> FINAL_STATES = Set.of("APPLIED", "REJECTED");

  private ActionRequestRules() {}

  /**
   * 받지 않는 요청 — 거절 코드(UNSUPPORTED_ACTION·APPROVAL_REQUIRED), 되면 null. 원문 "강제 성공·잔고 덮어쓰기" 같은 명령은 기록도
   * 받지 않는다.
   */
  public static @Nullable String rejection(
      String actionType, String command, @Nullable String approvalRef) {
    if (!TYPES.contains(actionType)) {
      return "UNSUPPORTED_ACTION";
    }
    String c = command.toLowerCase(Locale.ROOT);
    if (c.contains("force") || c.contains("overwrite") || c.contains("override")) {
      return "UNSUPPORTED_ACTION";
    }
    if (COMPENSATION.equals(actionType) && (approvalRef == null || approvalRef.isBlank())) {
      return "APPROVAL_REQUIRED";
    }
    return null;
  }

  /** 결과를 더 남길 수 있는가 — 끝난 요청은 안 된다. */
  public static boolean canRecordResult(String state) {
    return !FINAL_STATES.contains(state);
  }
}
