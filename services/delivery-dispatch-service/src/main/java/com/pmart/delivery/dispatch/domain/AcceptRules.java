package com.pmart.delivery.dispatch.domain;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * 수락 판정(원문 §3.3·§4.3 — 여러 명이 동시에 수락해도 한 명만, 늦은 수락은 시간 만료 / 이미 배정 / 주문 취소 / 자격·용량 불충족). 배송 행 잠금 아래에서
 * 부른다.
 */
public final class AcceptRules {

  public enum LateReason {
    EXPIRED,
    ALREADY_ASSIGNED,
    ORDER_CANCELLED,
    INELIGIBLE
  }

  /** null 이면 배정해도 된다. */
  public record Verdict(@Nullable LateReason late) {
    public boolean ok() {
      return late == null;
    }
  }

  /** 라이더 상태(rider-service 조회 사본)와 지금 맡은 배송 수(이 서비스 기록). */
  public record RiderSnapshot(
      boolean acceptsNewDispatch, boolean oneAtATime, int activeDeliveries, boolean holdsSingle) {

    public RiderSnapshot(boolean acceptsNewDispatch, boolean oneAtATime, int activeDeliveries) {
      this(acceptsNewDispatch, oneAtATime, activeDeliveries, false);
    }
  }

  private AcceptRules() {}

  /**
   * @param deliveryState 배송 상태(DISPATCHING 이어야)
   * @param offerExpiresAt 제안 수락이면 만료 시각, 배달리스트 수락이면 null
   * @param maxActive 동시에 맡을 수 있는 배송 수(원문 §2.4·§5.3 최대 3)
   */
  public static Verdict judge(
      String deliveryState,
      @Nullable Instant offerExpiresAt,
      Instant now,
      @Nullable RiderSnapshot rider,
      int maxActive) {
    return judge(deliveryState, offerExpiresAt, now, rider, maxActive, false);
  }

  /**
   * @param single 한집배달(SINGLE) — 2026-11-18 사용자 "한집은 단독": 한집을 맡은 라이더는 다른 배송을 못 받고, 다른 배송을 맡은 라이더는
   *     한집을 못 받는다(알뜰배달끼리만 3건까지 함께).
   */
  public static Verdict judge(
      String deliveryState,
      @Nullable Instant offerExpiresAt,
      Instant now,
      @Nullable RiderSnapshot rider,
      int maxActive,
      boolean single) {
    switch (deliveryState) {
      case "DISPATCHING" -> {}
      case "CANCELLED_BEFORE_PICKUP", "BLOCKED" -> {
        return new Verdict(LateReason.ORDER_CANCELLED);
      }
      default -> {
        return new Verdict(LateReason.ALREADY_ASSIGNED);
      }
    }
    if (offerExpiresAt != null && !now.isBefore(offerExpiresAt)) {
      return new Verdict(LateReason.EXPIRED);
    }
    if (rider == null || !rider.acceptsNewDispatch()) {
      return new Verdict(LateReason.INELIGIBLE);
    }
    int limit = rider.oneAtATime() ? 1 : maxActive;
    if (rider.activeDeliveries() >= limit) {
      return new Verdict(LateReason.INELIGIBLE);
    }
    if (rider.holdsSingle() || (single && rider.activeDeliveries() > 0)) {
      return new Verdict(LateReason.INELIGIBLE); // 한집은 단독(2026-11-18)
    }
    return new Verdict(null);
  }

  /** 미응답 만료가 거절 횟수에 들어가는가 — 상세 화면을 확인했으면 들어간다(원문 §4.4). */
  public static boolean expiryCountsAsRejection(@Nullable Instant viewedAt) {
    return viewedAt != null;
  }

  /** 연속 거절 안내(원문 §4.4 5회). */
  public static boolean rejectionNotice(int consecutive, int threshold) {
    return consecutive >= threshold;
  }
}
