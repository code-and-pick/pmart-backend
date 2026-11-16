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

  public static final String GROUP_DELIVERY_OPS = "DELIVERY_OPS";
  public static final String GROUP_ORDER_OPS = "ORDER_OPS";

  public static final Set<String> DELIVERY_KINDS =
      Set.of(RIDER_HELP, DELIVERY_INCIDENT, RIDER_LOCATION_STALE, MISDELIVERY);

  /** 사가·결제 이상(R-3) — OPERATOR_ADMIN 만. */
  public static final Set<String> ORDER_KINDS = Set.of(RECONCILIATION, PAYMENT_UNKNOWN);

  public static final String ADMIN = "OPERATOR_ADMIN";
  public static final String RIDER_OPS = "RIDER_OPS";
  public static final String CS_AGENT = "CS_AGENT";

  public static final List<String> CREATE_MISDELIVERY_ROLES = List.of(CS_AGENT, RIDER_OPS, ADMIN);

  /**
   * 종류별 처리 기한 기본값(설정 {@code pmart.cases.sla.<종류>} 로 바꾼다 — 값 확정 2026-11-16 사용자 "그대로 할게", 사가·결제 이상
   * 1시간은 같은 날 "한시간으로").
   */
  public static final Map<String, Duration> DEFAULT_SLA =
      Map.of(
          RIDER_HELP, Duration.ofMinutes(10),
          DELIVERY_INCIDENT, Duration.ofMinutes(30),
          RIDER_LOCATION_STALE, Duration.ofMinutes(10),
          MISDELIVERY, Duration.ofMinutes(30),
          RECONCILIATION, Duration.ofHours(1),
          PAYMENT_UNKNOWN, Duration.ofHours(1));

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
    return Set.of();
  }

  /** 집기·닫기 할 수 있는가(그 종류 팀). */
  public static boolean canWork(Set<String> roles, String kind) {
    return roles.contains(ADMIN) || (DELIVERY_KINDS.contains(kind) && roles.contains(RIDER_OPS));
  }

  /** 사건함에 나오는 종류(고객 문의 CS_INQUIRY 는 /inquiries). */
  public static final Set<String> ALL_INBOX_KINDS =
      Set.of(
          RIDER_HELP,
          DELIVERY_INCIDENT,
          RIDER_LOCATION_STALE,
          MISDELIVERY,
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
