package com.pmart.delivery.tracking.domain;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * 배송 진행 단계(TR-3, 2026-11-17) — 프레임워크 없음. 사건마다 다음 단계를 정한다.
 *
 * <ul>
 *   <li>단계: UNASSIGNED → TO_STORE(배정) → AT_STORE(가게 도착) → TO_CUSTOMER(픽업·회수지에서 받음) → AT_CUSTOMER(고객
 *       도착) → DONE(완료·실패·픽업 전 취소·회수 반납).
 *   <li>앞으로만 간다 — 같은 토픽에 dispatch·execution 이 따로 내서 늦게 온 사건이 단계를 되돌리지 않게. 예외: 픽업 전 배정 해제(재배차)는
 *       UNASSIGNED, 픽업 전 재배정은 TO_STORE.
 *   <li>DONE 은 끝 — 그 뒤 사건은 버린다.
 * </ul>
 */
public final class ProgressRules {

  private ProgressRules() {}

  public static final String UNASSIGNED = "UNASSIGNED";
  public static final String TO_STORE = "TO_STORE";
  public static final String AT_STORE = "AT_STORE";
  public static final String TO_CUSTOMER = "TO_CUSTOMER";
  public static final String AT_CUSTOMER = "AT_CUSTOMER";
  public static final String DONE = "DONE";

  private static final List<String> ORDER =
      List.of(UNASSIGNED, TO_STORE, AT_STORE, TO_CUSTOMER, AT_CUSTOMER, DONE);

  /** 이 사건이 가리키는 단계와 결과(DONE 일 때). 진행과 상관없는 사건이면 null. */
  public record Target(String stage, @Nullable String outcome) {}

  public static @Nullable Target target(String eventType, boolean riderReleased) {
    return switch (eventType) {
      case "DeliveryAssigned" -> new Target(TO_STORE, null);
      case "DeliveryAssignmentChanged" ->
          riderReleased ? new Target(UNASSIGNED, null) : new Target(TO_STORE, null);
      case "RiderArrivedAtStore" -> new Target(AT_STORE, null);
      case "PickupCompleted", "ReturnPickedUp" -> new Target(TO_CUSTOMER, null);
      case "RiderArrivedAtCustomer" -> new Target(AT_CUSTOMER, null);
      case "DeliveryCompleted", "ReturnDelivered" -> new Target(DONE, "COMPLETED");
      case "DeliveryFailed", "ReturnPickupFailed" ->
          new Target(DONE, "FAILED"); // 반품 회수 실패(CL-1, 2026-11-17)
      case "DeliveryCancelledBeforePickup" -> new Target(DONE, "CANCELLED");
      default -> null;
    };
  }

  /** 지금 단계(없으면 null)에서 target 으로 바꿀지. */
  public static boolean shouldMove(@Nullable String current, Target target) {
    if (current == null) {
      return true;
    }
    if (DONE.equals(current) || current.equals(target.stage())) {
      return false;
    }
    int now = ORDER.indexOf(current);
    int next = ORDER.indexOf(target.stage());
    boolean beforePickup = now < ORDER.indexOf(TO_CUSTOMER);
    // 픽업 전 배정 해제·재배정은 되돌림을 허용
    if (beforePickup && (UNASSIGNED.equals(target.stage()) || TO_STORE.equals(target.stage()))) {
      return true;
    }
    return next > now;
  }
}
