package com.pmart.cases.domain;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * 사건함 규칙(2026-11-16, 사용자 "사건함 공통 결정" — 추천대로).
 *
 * <ul>
 *   <li>종류(지금 여는 것): RIDER_HELP(라이더 도움요청, R-4)·DELIVERY_INCIDENT(전달 실패 등록,
 *       R-4)·RIDER_LOCATION_STALE(위치 끊김, R-6)·MISDELIVERY(오배송 확인, R-7). 모두 배송 쪽 — 팀 DELIVERY_OPS(역할
 *       RIDER_OPS, 지원센터 겸함).
 *   <li>보기: OPERATOR_ADMIN 모두, RIDER_OPS·CS_AGENT 배송 쪽. 집기·닫기: 그 팀(RIDER_OPS)·OPERATOR_ADMIN. 오배송
 *       열기: CS_AGENT·RIDER_OPS· OPERATOR_ADMIN.
 *   <li>우선순위: 사고(ACCIDENT)·안전 위협 P1, 도움요청·위치 끊김 P2, 그 밖 P3.
 *   <li>처리 기한: 종류별 기본값(설정으로 바꿈) — 표시만, 알림 없음.
 *   <li>배차 실패(2026-11-16 사용자 — 중간안·지원센터·10분): DISPATCH_FAILED — 배송 쪽(DELIVERY_OPS). 시스템 장애는 첫 실패,
 *       라이더 없음 등 자동 재배차 실패는 같은 배송 두 번째 실패부터. 배송마다 열린 사건 하나, 그 뒤 실패는 이력.
 *   <li>사가·결제 이상(R-3, 2026-11-16 사용자 — 기한 1시간·"이상만"·멈춘 사가는 지금 안 함): RECONCILIATION(대사 필요·결제 불일치·보상
 *       실패)·PAYMENT_UNKNOWN(결제 결과 불명 상신). 팀 ORDER_OPS = OPERATOR_ADMIN 만 보고 집고 닫는다. 사건은 알림·기록이고 조치는
 *       처리 대기(BO-12) 버튼·각 메뉴에서 한다.
 * </ul>
 */
public final class CaseRules {

  private CaseRules() {}

  public static final String RIDER_HELP = "RIDER_HELP";
  public static final String DELIVERY_INCIDENT = "DELIVERY_INCIDENT";
  public static final String RIDER_LOCATION_STALE = "RIDER_LOCATION_STALE";
  public static final String MISDELIVERY = "MISDELIVERY";
  public static final String CS_INQUIRY = "CS_INQUIRY";
  public static final String RECONCILIATION = "RECONCILIATION";
  public static final String PAYMENT_UNKNOWN = "PAYMENT_UNKNOWN";
  public static final String DISPATCH_FAILED = "DISPATCH_FAILED";

  public static final String GROUP_DELIVERY_OPS = "DELIVERY_OPS";
  public static final String GROUP_ORDER_OPS = "ORDER_OPS";

  public static final Set<String> DELIVERY_KINDS =
      Set.of(RIDER_HELP, DELIVERY_INCIDENT, RIDER_LOCATION_STALE, MISDELIVERY, DISPATCH_FAILED);

  /** 사가·결제 이상(R-3) — OPERATOR_ADMIN 만. */
  public static final Set<String> ORDER_KINDS = Set.of(RECONCILIATION, PAYMENT_UNKNOWN);

  public static final String ADMIN = "OPERATOR_ADMIN";
  public static final String RIDER_OPS = "RIDER_OPS";
  public static final String CS_AGENT = "CS_AGENT";

  /** 센터 관리자 — 자기 센터(토큰 centers)의 배차 실패 사건만 보기(2026-11-16 사용자 "추천대로", 배송 원문 §3.6). */
  public static final String MFC_SUPERVISOR = "MFC_SUPERVISOR";

  public static final Set<String> MFC_SUPERVISOR_KINDS = Set.of(DISPATCH_FAILED);

  public static final List<String> CREATE_MISDELIVERY_ROLES = List.of(CS_AGENT, RIDER_OPS, ADMIN);

  /**
   * 종류별 처리 기한 기본값(설정 {@code pmart.cases.sla.<종류>} 로 바꾼다 — 값 확정 2026-11-16 사용자 "그대로 할게", 사가·결제 이상
   * 1시간은 같은 날 "한시간으로", 배차 실패 10분은 같은 날 "1번으로").
   */
  public static final Map<String, Duration> DEFAULT_SLA =
      Map.of(
          RIDER_HELP, Duration.ofMinutes(10),
          DELIVERY_INCIDENT, Duration.ofMinutes(30),
          RIDER_LOCATION_STALE, Duration.ofMinutes(10),
          MISDELIVERY, Duration.ofMinutes(30),
          RECONCILIATION, Duration.ofHours(1),
          PAYMENT_UNKNOWN, Duration.ofHours(1),
          DISPATCH_FAILED, Duration.ofMinutes(10));

  public static String group(String kind) {
    if (DELIVERY_KINDS.contains(kind)) {
      return GROUP_DELIVERY_OPS;
    }
    return ORDER_KINDS.contains(kind) ? GROUP_ORDER_OPS : "CS";
  }

  /** 볼 수 있는 종류. 빈 집합 = 하나도 없음. */
  public static Set<String> visibleKinds(Set<String> roles) {
    if (roles.contains(ADMIN)) {
      return ALL_INBOX_KINDS;
    }
    if (roles.contains(RIDER_OPS) || roles.contains(CS_AGENT)) {
      return DELIVERY_KINDS;
    }
    if (roles.contains(MFC_SUPERVISOR)) {
      return MFC_SUPERVISOR_KINDS;
    }
    return Set.of();
  }

  /** 자기 센터 사건만 보는가 — 센터 관리자만 가진 직원(관리자·지원센터·상담원 역할이 있으면 센터와 관계없이 그 역할 범위). 집기·닫기는 없다. */
  public static boolean centerScoped(Set<String> roles) {
    return roles.contains(MFC_SUPERVISOR)
        && !roles.contains(ADMIN)
        && !roles.contains(RIDER_OPS)
        && !roles.contains(CS_AGENT);
  }

  /**
   * 집기·닫기·재개·이관 할 수 있는가(지금 담당 팀). 관리자는 모두, 지원센터(RIDER_OPS)는 배송 쪽 사건이 지원센터 팀(DELIVERY_OPS)에 있을 때만 —
   * 관리자 팀(ORDER_OPS)으로 넘긴 배송 쪽 사건은 관리자만(0.7.0).
   */
  public static boolean canWork(Set<String> roles, String kind, @Nullable String group) {
    if (roles.contains(ADMIN)) {
      return true;
    }
    return DELIVERY_KINDS.contains(kind)
        && roles.contains(RIDER_OPS)
        && !GROUP_ORDER_OPS.equals(group);
  }

  /**
   * 교대 인계서를 쓰고 받을 수 있는 역할(0.13.1 — 2026-11-17 사용자 (나), 건을 맡을 수 있는 사람과 같게): 지원센터 팀(DELIVERY_OPS) =
   * 지원센터·관리자, 관리자 팀(ORDER_OPS) = 관리자만.
   */
  public static boolean canHandOver(Set<String> roles, String group) {
    if (roles.contains(ADMIN)) {
      return true;
    }
    return GROUP_DELIVERY_OPS.equals(group) && roles.contains(RIDER_OPS);
  }

  /** 이관할 수 있는 팀(0.7.0 — 사용자 "A안"): 사람이 있는 두 팀. */
  public static final Set<String> TRANSFER_GROUPS = Set.of(GROUP_DELIVERY_OPS, GROUP_ORDER_OPS);

  /** 이관할 수 있는 사건 — 배송 쪽만(사가·결제는 이미 관리자 팀). */
  public static boolean transferable(String kind) {
    return DELIVERY_KINDS.contains(kind);
  }

  /** 사건함에 나오는 종류(고객 문의 CS_INQUIRY·라이더 문의 RIDER_INQUIRY 는 /inquiries). */
  public static final Set<String> ALL_INBOX_KINDS =
      Set.of(
          RIDER_HELP,
          DELIVERY_INCIDENT,
          RIDER_LOCATION_STALE,
          MISDELIVERY,
          DISPATCH_FAILED,
          RECONCILIATION,
          PAYMENT_UNKNOWN);

  /**
   * 사가·결제 사건 중 사건함에 여는 것("이상만", 2026-11-16 사용자). 대사 필요·결제 불일치는 전부, 보상 실행은 성공이 아닌 것만(정상 보상은 주문 취소마다
   * 나온다), 결제 결과 불명은 상신된 것(escalated)만. 열지 않으면 null, 열면 종류.
   */
  public static @Nullable String orderExceptionKind(
      String eventType, @Nullable String result, boolean escalated) {
    return switch (eventType) {
      case "ReconciliationRequired", "PaymentMismatch" -> RECONCILIATION;
      case "CompensationExecuted" -> "SUCCESS".equals(result) ? null : RECONCILIATION;
      case "PaymentResultUnknown" -> escalated ? PAYMENT_UNKNOWN : null;
      default -> null;
    };
  }

  /**
   * 배차 실패 사건을 여는가(2026-11-16 사용자 — 중간안). 시스템 장애(자동 재배차 없음)는 첫 실패에, 그 밖(라이더 없음·반복 거절·응답 만료 — 10분마다
   * 자동 재배차)은 같은 배송의 두 번째 실패부터. {@code failures} = 이 배송의 배차 실패 횟수(이번 것 포함).
   */
  public static boolean dispatchFailureOpens(@Nullable String reason, int failures) {
    return "SYSTEM_FAILURE".equals(reason) || failures >= 2;
  }

  public static String dispatchReasonName(@Nullable String reason) {
    if (reason == null) {
      return "-";
    }
    return switch (reason) {
      case "NO_RIDER" -> "라이더 없음";
      case "REPEATED_REJECTION" -> "반복 거절";
      case "EXPIRED" -> "응답 만료";
      case "SYSTEM_FAILURE" -> "시스템 장애";
      default -> reason;
    };
  }

  /** 라이더 도움요청·전달 실패 사건 → 종류. */
  public static String incidentKind(@Nullable String type, @Nullable String category) {
    if ("DELIVERY_FAILURE".equals(category) || "DELIVERY_FAILURE".equals(type)) {
      return DELIVERY_INCIDENT;
    }
    return RIDER_HELP;
  }

  public static String incidentPriority(@Nullable String category) {
    if (category == null) {
      return "P2";
    }
    return switch (category) {
      case "ACCIDENT", "SAFETY_THREAT", "THREAT" -> "P1";
      case "DELIVERY_FAILURE" -> "P2";
      default -> "P2";
    };
  }

  public static final Set<String> RESOLUTIONS =
      Set.of(
          "RESOLVED", "NOT_REPRODUCIBLE", "DUPLICATE", "NO_ACTION_REQUIRED", "ESCALATED_EXTERNAL");
}
