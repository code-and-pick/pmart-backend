package com.pmart.order.orchestration.domain;

import java.util.List;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * S3 취소 사가 규칙(docs/business/flows/cancel-vs-handover.md). 2026-10-31 범위는 피킹 전 전체 취소 — 배차(b)는 배송 기능이
 * 없어 단계에 없다.
 *
 * <ul>
 *   <li>취소 접수 시각은 인계보다 우선한다는 근거가 아니다. 현장(mfc-fulfillment) 판정이 STOPPED 인 수량만 출고 전 취소한다.
 *   <li>인계 확인 중(HANDOVER_IN_PROGRESS)·판정 대기(PENDING)는 기다리고, 인계됨(HANDED_OVER)이 있으면 자동 진행을 멈춘다(회수 S6
 *       는 아직 없음 — 상신).
 *   <li>재고·여력·슬롯은 현장 중지가 확정된 뒤에만 해제한다(검증 없이 판매가능 복구 금지).
 * </ul>
 */
public final class CancellationRules {

  public static final String STOP_WORK = "STOP_WORK";
  public static final String RELEASE_RESOURCES = "RELEASE_RESOURCES";
  public static final String APPLY_ORDER_CANCEL = "APPLY_ORDER_CANCEL";
  public static final List<String> STEPS =
      List.of(STOP_WORK, RELEASE_RESOURCES, APPLY_ORDER_CANCEL);

  /** S1 에서 확정해 두었던 점유 — 취소 때 해제 대상(해제 순서: 늦게 잡은 것부터). */
  public static final List<String> RELEASABLE =
      List.of("SLOT_HOLD", "CAPACITY_HOLD", "INVENTORY_RESERVATION");

  private CancellationRules() {}

  /** 현장 판정. */
  public enum StopVerdict {
    /** 요청 수량 전부 중지 확정. */
    STOPPED,
    /** 판정 대기·인계 확인 중 — 재조회. */
    WAIT,
    /** 일부라도 인계됨 — 출고 전 취소 불가(회수 경로). */
    HANDED_OVER
  }

  /** 계약 StopRequestResult → 판정. 상태가 RESOLVED 이고 모든 포장이 STOPPED(포장 없음 포함)일 때만 STOPPED. */
  public static StopVerdict verdict(JsonNode result) {
    boolean waiting = !"RESOLVED".equals(result.path("state").asString(""));
    for (JsonNode p : result.path("packages")) {
      String v = p.path("verdict").asString("");
      if ("HANDED_OVER".equals(v)) {
        return StopVerdict.HANDED_OVER;
      }
      if (!"STOPPED".equals(v)) {
        waiting = true;
      }
    }
    return waiting ? StopVerdict.WAIT : StopVerdict.STOPPED;
  }

  /** 단계 명령 Idempotency-Key — 재개·재전송이 정본에 한 번만 반영된다. */
  public static String requestId(String sagaId, String step) {
    return sagaId + ":" + step;
  }

  public static String releaseRequestId(String sagaId, String resourceType) {
    return sagaId + ":RELEASE:" + resourceType;
  }

  /** 정본별 취소 해제 사유(계약 enum). */
  public static String releaseReason(String resourceType) {
    return switch (resourceType) {
      case "INVENTORY_RESERVATION" -> "CANCEL_BEFORE_PICK";
      case "CAPACITY_HOLD", "SLOT_HOLD" -> "ORDER_CANCELLED";
      default -> throw new IllegalArgumentException("해제할 수 없는 자원: " + resourceType);
    };
  }

  /** 정본 조회 상태가 해제 확인인가. */
  public static boolean released(@Nullable String state) {
    return "RELEASED".equals(state) || "EXPIRED".equals(state);
  }

  /** 해제 거절 중 "그 점유가 정본에 아예 없음" — 풀 것이 없으므로 해제된 것으로 본다. */
  public static final String SLOT_HOLD_NOT_FOUND = "SLOT_HOLD_NOT_FOUND";

  /**
   * 해제 거절 코드가 "정본에 점유 기록 없음"인가(2026-11-25). 슬롯 정원은 delivery-capacity 의 slot_hold 행을 세므로 행이 없으면 차지한
   * 정원도 없다. 2026-11-10 PC 3 → PC 5 이전 때 옛 slot_hold 가 지워져 그 전 주문의 취소가 여기서 ESCALATED 로 멈췄다. 재고 예약·작업
   * 여력은 그대로 거절이면 상신(정본 유실이 확인된 것은 슬롯뿐).
   */
  public static boolean absent(String resourceType, String rejectCode) {
    return "SLOT_HOLD".equals(resourceType) && SLOT_HOLD_NOT_FOUND.equals(rejectCode);
  }

  public static @Nullable String next(String step) {
    int i = STEPS.indexOf(step);
    return i < 0 || i + 1 >= STEPS.size() ? null : STEPS.get(i + 1);
  }
}
