package com.pmart.delivery.dispatch.application;

import com.pmart.delivery.dispatch.infrastructure.DispatchStore;
import com.pmart.delivery.dispatch.infrastructure.DispatchStore.AttemptRow;
import com.pmart.delivery.dispatch.infrastructure.DispatchStore.DeliveryRow;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** 배송 상태 바꾸기(배정·픽업 전 취소·배차 실패) — 호출자 트랜잭션·배송 행 잠금 아래, 바뀐 사실과 사건을 함께 쓴다. */
@Component
public class DeliveryTransitions {

  private final DispatchStore store;
  private final DispatchEvents events;

  public DeliveryTransitions(DispatchStore store, DispatchEvents events) {
    this.store = store;
    this.events = events;
  }

  /**
   * 유효 수락 1건 확정(원문 §3.3) — 알뜰배달 묶음이면 묶음 ID·방문 순서도. 나머지 제안은 SUPERSEDED(이미 배정). @return assignmentId
   */
  public String assign(
      DeliveryRow d,
      String riderId,
      String offerId,
      @Nullable String bundleId,
      int stopSeq,
      Instant now) {
    String assignmentId = "ASG-" + UUID.randomUUID();
    int version = d.assignmentVersion() + 1;
    store.decideOffer(offerId, "ACCEPTED", now, null, null);
    store.assign(d.deliveryId(), riderId, assignmentId, version, offerId, now);
    store.setBundle(d.deliveryId(), bundleId, stopSeq);
    store.supersedeOpenOffers(d.deliveryId(), now, "ALREADY_ASSIGNED");
    store
        .openAttempt(d.deliveryId())
        .ifPresent(a -> store.finishAttempt(a.attemptId(), "ASSIGNED", null, now));
    store.resetRejections(riderId, now);
    events.assigned(d, riderId, offerId, version, now);
    return assignmentId;
  }

  /** 픽업 전 취소 — 남은 제안·시도를 닫고 DeliveryCancelledBeforePickup(MFC 는 이것으로 배정을 푼다). */
  public void cancelBeforePickup(DeliveryRow d, String reason, Instant now) {
    store.supersedeOpenOffers(d.deliveryId(), now, "ORDER_CANCELLED");
    store
        .openAttempt(d.deliveryId())
        .ifPresent(a -> store.finishAttempt(a.attemptId(), "CANCELLED", null, now));
    store.setState(d.deliveryId(), "CANCELLED_BEFORE_PICKUP", reason, now);
    events.cancelledBeforePickup(d, reason, now);
  }

  /** 취소된 주문 — 신규 배차 차단(원문 §3.2). 배정 전이라 MFC 에 알릴 배정이 없다. */
  public void block(DeliveryRow d, Instant now) {
    store.supersedeOpenOffers(d.deliveryId(), now, "ORDER_CANCELLED");
    store
        .openAttempt(d.deliveryId())
        .ifPresent(a -> store.finishAttempt(a.attemptId(), "CANCELLED", null, now));
    store.setState(d.deliveryId(), "BLOCKED", "ORDER_CANCELLED", now);
  }

  /**
   * 라이더를 못 구한 배차 실패 — DispatchFailed 로 알리고 배송은 배차 대기 그대로, redispatchAt 에 새 시도(RETRY)로 다시 배차 요청(사용자
   * 2026-11-13 "배차 실패하면 자동으로 시스템 상에서 배차요청이 다시 가도록"). 재배차 없이 취소는 MFC 백오피스 관리자가 직접(배차 취소 API).
   *
   * @return 새 시도 ID
   */
  public String failAndRedispatch(
      DeliveryRow d, AttemptRow a, String reason, Instant redispatchAt, Instant now) {
    store.supersedeOpenOffers(d.deliveryId(), now, "EXPIRED");
    store.finishAttempt(a.attemptId(), "FAILED", reason, now);
    events.failed(d, reason, a.retryCount() + 1, now);
    String next = "ATT-" + UUID.randomUUID();
    store.insertAttempt(next, d.deliveryId(), "RETRY", now);
    store.updateAttempt(next, "SEARCHING", 1, redispatchAt, 0, 0, redispatchAt, null, null);
    return next;
  }

  /** 배차 실패(원문 §3.6 — 기사 없음·반복 거절·응답 만료·시스템 장애 구분). 다시 배차하지 않는다(시스템 장애 — 운영자 확인). */
  public void fail(DeliveryRow d, AttemptRow a, String reason, Instant now) {
    store.supersedeOpenOffers(d.deliveryId(), now, "EXPIRED");
    store.finishAttempt(a.attemptId(), "FAILED", reason, now);
    store.setState(d.deliveryId(), "DISPATCH_FAILED", null, now);
    events.failed(d, reason, a.retryCount() + 1, now);
  }
}
