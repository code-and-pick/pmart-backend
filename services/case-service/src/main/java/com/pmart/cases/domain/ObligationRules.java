package com.pmart.cases.domain;

import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * 사건 의무 규칙(2026-11-16 사용자 "고친 안으로 진행해 줘" — 업무 원문 §2 Obligation·§5.3, 계약 0.8.0).
 *
 * <ul>
 *   <li>종류: 담당 팀이 있는 8종만(MFC·SCM 쪽 재고 회수·폐기·공급사 청구는 담당 팀이 없어 막는다 — 이관 "A안"과 같은 기준).
 *   <li>담당 팀: DELIVERY_OPS(지원센터 RIDER_OPS)·ORDER_OPS(관리자)·CS(상담원 CS_AGENT). 담당 팀이 그 사건을 볼 수 있어야 한다.
 *   <li>등록: 사건을 다룰 수 있는 사람({@link CaseRules#canWork}). 해소(FULFILLED): 담당 팀·관리자. 면제·실패(일을 하지 않고 끝냄):
 *       관리자만, 사유 필수.
 * </ul>
 */
public final class ObligationRules {

  private ObligationRules() {}

  public static final String CS = "CS";

  public static final Set<String> KINDS =
      Set.of(
          "REFUND",
          "RETURN_PICKUP",
          "REDELIVERY",
          "RECONCILIATION",
          "CUSTOMER_CONTACT",
          "EVIDENCE_COLLECTION",
          "COMPENSATION",
          "OTHER");

  public static final Set<String> OWNERS =
      Set.of(CaseRules.GROUP_DELIVERY_OPS, CaseRules.GROUP_ORDER_OPS, CS);

  public static final Set<String> OPEN_STATES = Set.of("OPEN", "PARTIALLY_RESOLVED");

  public static final String FULFILLED = "FULFILLED";

  /** 시도 기록 — 상태는 그대로(담당 팀이 "고객 연락 3번 안 됨" 같은 것을 남기고 관리자가 본다). */
  public static final String ATTEMPTED = "ATTEMPTED";

  /** 담당 팀이 그 사건을 볼 수 있는가 — 지원센터는 배송 쪽, 상담원은 배송 쪽·상담원 팀 사건(R-8)만 본다. */
  public static boolean ownerCanSee(String owner, String caseKind) {
    if (CaseRules.GROUP_ORDER_OPS.equals(owner)) {
      return true;
    }
    if (CS.equals(owner)) {
      return CaseRules.CS_AGENT_KINDS.contains(caseKind);
    }
    return CaseRules.DELIVERY_KINDS.contains(caseKind);
  }

  /** 비우면 사건 팀(사건 팀이 셋 밖이면 관리자 팀). */
  public static String defaultOwner(@Nullable String caseGroup) {
    return caseGroup != null && OWNERS.contains(caseGroup) ? caseGroup : CaseRules.GROUP_ORDER_OPS;
  }

  /** 해소할 수 있는가. 면제·실패는 관리자만, 한 만큼(FULFILLED)·시도 기록(ATTEMPTED)은 담당 팀·관리자. */
  public static boolean canResolve(Set<String> roles, String owner, String outcome) {
    if (roles.contains(CaseRules.ADMIN)) {
      return true;
    }
    if (!FULFILLED.equals(outcome) && !ATTEMPTED.equals(outcome)) {
      return false;
    }
    return switch (owner) {
      case CaseRules.GROUP_DELIVERY_OPS -> roles.contains(CaseRules.RIDER_OPS);
      case CS -> roles.contains(CaseRules.CS_AGENT);
      default -> false;
    };
  }

  /** 해소 뒤 상태 — 수량·금액 중 있는 것을 모두 채우면 RESOLVED. 둘 다 없는 의무는 한 번에 RESOLVED. */
  public static String stateAfter(
      @Nullable Integer qty, @Nullable Long amount, int resolvedQty, long resolvedAmount) {
    boolean qtyDone = qty == null || resolvedQty >= qty;
    boolean amountDone = amount == null || resolvedAmount >= amount;
    return qtyDone && amountDone ? "RESOLVED" : "PARTIALLY_RESOLVED";
  }
}
