package com.pmart.approval.domain;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;

/**
 * 승인 최소 범위의 업무 규칙(ADR-0004·0014, approval-service.yaml).
 *
 * <ul>
 *   <li>제출은 DRAFT 에서만. 결정은 심사 중(REVIEW_PENDING)에서만.
 *   <li>발의자 = 승인자이면 FORBIDDEN(자기승인 분리).
 *   <li>승인자 역할은 종류별 설정값. 미설정이면 CONFIG_REQUIRED — 누구나 승인 가능으로 대체하지 않는다.
 *   <li>이 단계가 지원하는 결정은 APPROVE·REJECT. PARTIALLY_APPROVE·REQUEST_EVIDENCE 는 UNSUPPORTED_ACTION.
 * </ul>
 */
public final class ApprovalRules {

  public static final Set<String> SUPPORTED_DECISIONS = Set.of("APPROVE", "REJECT");

  private ApprovalRules() {}

  public static void requireDraft(String requestState) {
    if (!"DRAFT".equals(requestState)) {
      throw PmartException.invalidState("제출은 DRAFT 상태에서만 할 수 있습니다(현재 " + requestState + ").");
    }
  }

  public static void requireUnderReview(String reviewState) {
    throwIfBlocked(reviewBlock(reviewState), reviewState, "");
  }

  public static void requireSupported(String decision) {
    if (!SUPPORTED_DECISIONS.contains(decision)) {
      throw new PmartException(
          ErrorCode.UNSUPPORTED_ACTION, decision + " 결정은 아직 지원하지 않습니다(최소 범위: APPROVE, REJECT).");
    }
  }

  /**
   * @param approverRoles 결정자 토큰의 역할
   * @param approverRolesByType 종류 → 승인 역할(설정값)
   */
  public static void requireApprover(
      String type,
      String requesterId,
      String approverId,
      List<String> approverRoles,
      Map<String, String> approverRolesByType) {
    throwIfBlocked(
        approverBlock(type, requesterId, approverId, approverRoles, approverRolesByType), "", type);
  }

  /**
   * 자기 권한 승인 금지(권한 빈틈 정리 2026-11-17): GRANT 의 대상(targetRef = 권한을 받을 직원)이 승인자 본인이면 결정할 수 없다. 요청자 ≠
   * 승인자만으로는 운영 관리자가 자기에게 온 권한 요청을 승인하는 것을 막지 못한다.
   */
  public static void requireNotOwnGrant(String type, String targetRef, String approverId) {
    throwIfBlocked(ownGrantBlock(type, targetRef, approverId), "", type);
  }

  // ---------------------------------------------------------------- 결정할 수 있는지(AP-3)

  /** 결정 API 가 막는 이유. 검사 순서 = 결정 API 의 순서(심사 중 → 자기 요청 → 역할 설정 → 역할 → 자기 권한 요청). */
  public enum DecideBlock {
    NOT_PENDING,
    SELF_APPROVAL,
    CONFIG_REQUIRED,
    NO_ROLE,
    GRANT_TO_SELF
  }

  /**
   * 이 사람이 지금 이 승인을 결정할 수 있는지(AP-3). 결정 API({@link #requireCanDecide})와 같은 검사 — 예외 대신 첫 번째로 막힌 이유를
   * 돌려준다. 결정 종류 지원 여부·기대 버전은 보지 않는다.
   *
   * @return 결정할 수 있으면 null
   */
  public static @Nullable DecideBlock decideBlock(
      String reviewState,
      String type,
      String requesterId,
      String targetRef,
      String viewerId,
      List<String> viewerRoles,
      Map<String, String> approverRolesByType) {
    DecideBlock b = reviewBlock(reviewState);
    if (b == null) {
      b = approverBlock(type, requesterId, viewerId, viewerRoles, approverRolesByType);
    }
    if (b == null) {
      b = ownGrantBlock(type, targetRef, viewerId);
    }
    return b;
  }

  /** 결정 API 의 권한·상태 검사. {@link #decideBlock} 이 막으면 그 이유의 오류(지금까지와 같은 코드·문구)를 던진다. */
  public static void requireCanDecide(
      String reviewState,
      String type,
      String requesterId,
      String targetRef,
      String approverId,
      List<String> approverRoles,
      Map<String, String> approverRolesByType) {
    throwIfBlocked(
        decideBlock(
            reviewState,
            type,
            requesterId,
            targetRef,
            approverId,
            approverRoles,
            approverRolesByType),
        reviewState,
        type);
  }

  /**
   * 이 사람이 결정할 수 있는 종류(설정된 승인 역할을 가진 종류). 승인함 {@code decidableByMe} 거르기를 SQL 로 할 때 쓴다 — {@link
   * #approverBlock} 의 설정·역할 검사와 같다.
   */
  public static Set<String> decidableTypes(
      List<String> viewerRoles, Map<String, String> approverRolesByType) {
    Set<String> out = new TreeSet<>();
    approverRolesByType.forEach(
        (type, role) -> {
          if (role != null && !role.isBlank() && holdsAny(viewerRoles, role)) {
            out.add(type);
          }
        });
    return out;
  }

  /**
   * 설정 승인 역할 — 하나("PRICE_APPROVER") 또는 쉼표로 여럿("MARKETING,OPERATOR_ADMIN", 2026-11-20 ADR-0082
   * PROMOTION) 중 하나라도 가졌는가.
   */
  static boolean holdsAny(List<String> roles, String configured) {
    for (String r : configured.split(",")) {
      if (!r.isBlank() && roles.contains(r.strip())) {
        return true;
      }
    }
    return false;
  }

  private static @Nullable DecideBlock reviewBlock(String reviewState) {
    return "REVIEW_PENDING".equals(reviewState) ? null : DecideBlock.NOT_PENDING;
  }

  private static @Nullable DecideBlock approverBlock(
      String type,
      String requesterId,
      String approverId,
      List<String> approverRoles,
      Map<String, String> approverRolesByType) {
    if (requesterId.equals(approverId)) {
      return DecideBlock.SELF_APPROVAL;
    }
    String required = approverRolesByType.get(type);
    if (required == null || required.isBlank()) {
      return DecideBlock.CONFIG_REQUIRED;
    }
    if (!holdsAny(approverRoles, required)) {
      return DecideBlock.NO_ROLE;
    }
    return null;
  }

  private static @Nullable DecideBlock ownGrantBlock(
      String type, String targetRef, String approverId) {
    return "GRANT".equals(type) && targetRef.equals(approverId) ? DecideBlock.GRANT_TO_SELF : null;
  }

  private static void throwIfBlocked(@Nullable DecideBlock block, String reviewState, String type) {
    if (block == null) {
      return;
    }
    throw switch (block) {
      case NOT_PENDING ->
          PmartException.invalidState("심사 중인 승인만 결정할 수 있습니다(현재 " + reviewState + ").");
      case SELF_APPROVAL -> forbidden("발의자는 자기 요청을 승인할 수 없습니다.");
      case CONFIG_REQUIRED ->
          PmartException.configRequired("pmart.approval.approver-roles." + type);
      case NO_ROLE -> forbidden(type + " 승인 권한이 없습니다.");
      case GRANT_TO_SELF -> forbidden("자기 권한 요청은 승인·반려할 수 없습니다.");
    };
  }

  /** ApprovalDecided.decision (APPROVED / REJECTED). */
  public static String decidedState(String decision) {
    return "APPROVE".equals(decision) ? "APPROVED" : "REJECTED";
  }

  /** ApprovalDecided.targetRef.targetType — 정본 aggregate 종류. */
  public static String targetType(String type) {
    return switch (type) {
      case "PRODUCT" -> "Product";
      case "PRICE" -> "Price";
      case "DISPLAY" -> "Display";
      case "CENTER_MASTER" -> "Center";
      case "SERVICEABILITY" -> "ServiceabilityVersion";
      case "INITIAL_STOCK" -> "InitialStockLoad";
      case "GRANT" -> "EmployeeGrant";
      case "MFC_STOCK_ADJUSTMENT" -> "MfcStockAdjustment"; // AP-1, 원객체는 targetRef 의 kind·ref
      case "MFC_OPERATION_RESTRICTION" -> "OperationRestriction";
      case "SEARCH_SYNONYM" -> "SynonymSet"; // ADR-0073, 원객체 = 동의어 집합
      case "PROMOTION" -> "Promotion"; // ADR-0082, 원객체 = 행사(기간 할인·쿠폰)
      case "RAG_DOCUMENT" -> "KnowledgeDocument"; // ADR-0092, 원객체 = 운영자 RAG 문서
      default -> type;
    };
  }

  /** 회신 결과(ApplicationReceiptRecorded.result) — 실행 축 값과 같은 이름. */
  public static final Set<String> RECEIPT_RESULTS =
      Set.of("ACCEPTED", "APPLIED", "PARTIAL", "REJECTED", "UNKNOWN_RESULT");

  /**
   * 회신 → 실행 축(ADR-0039). 승인된(APPROVED·PARTIALLY_APPROVED) 승인만, 그 승인의 정본(targetService)이 보낸 회신만 실행
   * 축을 바꾼다. 다른 수신자의 회신은 기록만 한다(전달 축의 근거). 반려·심사 중 승인에 온 회신도 기록만 한다.
   *
   * @return 새 실행 축 값, 바꾸지 않으면 null
   */
  public static @Nullable String executionAfterReceipt(
      String reviewState, String targetService, String receiverService, String result) {
    if (!"APPROVED".equals(reviewState) && !"PARTIALLY_APPROVED".equals(reviewState)) {
      return null;
    }
    if (!targetService.equals(receiverService) || !RECEIPT_RESULTS.contains(result)) {
      return null;
    }
    return result;
  }

  /**
   * 직원 권한 요청 행이 자동 승인 규칙에 맞는가(ADR-0056 결정 6 — "소속 센터 현장 역할만"). 규칙의 역할이어야 하고, {@code
   * sameCenterAsAffiliation} 이면 소속이 센터이고 권한 센터가 정확히 그 센터 하나여야 한다. 하나라도 어긋나면 승인자 심사.
   */
  public static boolean autoApprovable(
      List<String> ruleRoles,
      boolean sameCenterAsAffiliation,
      String role,
      List<String> centers,
      @Nullable String affiliationType,
      @Nullable String affiliationCode) {
    if (!ruleRoles.contains(role)) {
      return false;
    }
    if (!sameCenterAsAffiliation) {
      return true;
    }
    return "CENTER".equals(affiliationType)
        && affiliationCode != null
        && centers.size() == 1
        && affiliationCode.equals(centers.getFirst());
  }

  /**
   * 변경 요청을 만들 수 있는 역할인가(설정 {@code requester-roles.<종류>}). 설정이 없으면 막지 않는다.
   *
   * @throws PmartException FORBIDDEN — 설정 역할이 하나도 없을 때
   */
  public static void requireRequesterRole(
      String type, List<String> operatorRoles, @Nullable List<String> requesterRoles) {
    if (requesterRoles == null || requesterRoles.isEmpty()) {
      return;
    }
    for (String r : requesterRoles) {
      if (operatorRoles.contains(r)) {
        return;
      }
    }
    throw forbidden(type + " 변경 요청은 " + requesterRoles + " 역할만 만들 수 있습니다.");
  }

  public static PmartException forbidden(String message) {
    return new PmartException(
        ErrorCode.FORBIDDEN.name(), HttpStatus.FORBIDDEN, message, false, List.of());
  }
}
