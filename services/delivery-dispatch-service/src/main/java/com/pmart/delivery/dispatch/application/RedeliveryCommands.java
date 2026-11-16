package com.pmart.delivery.dispatch.application;

import com.pmart.common.web.PmartException;
import com.pmart.delivery.dispatch.infrastructure.DispatchStore;
import com.pmart.delivery.dispatch.infrastructure.DispatchStore.DeliveryRow;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 오배송 다시 배송 결제 대기 끝내기(ADR-0065 4단계, orchestration 이 부른다) — 결제됨이면 배차를 풀고, 30분·고객 거절이면 다시 배송을 픽업 전
 * 취소하고 MFC 회수 배송을 만든다. 둘 다 같은 결과를 다시 돌려준다(멱등).
 */
@Service
public class RedeliveryCommands {

  private static final Logger log = LoggerFactory.getLogger(RedeliveryCommands.class);

  /** 결제 뒤 배송 약속 — 재발송·다시 배송과 같은 50분(PC 5 정한 값, DispatchEngine.reshipmentPromise). */
  static final Duration PROMISE_AFTER_RELEASE = Duration.ofMinutes(50);

  private final DispatchStore store;
  private final DeliveryTransitions transitions;
  private final RecoveryHandler recovery;
  private final TransactionTemplate tx;
  private final Clock clock;

  public RedeliveryCommands(
      DispatchStore store,
      DeliveryTransitions transitions,
      RecoveryHandler recovery,
      TransactionTemplate tx,
      Clock clock) {
    this.store = store;
    this.transitions = transitions;
    this.recovery = recovery;
    this.tx = tx;
    this.clock = clock;
  }

  /** 결제됨 → 배차 풀기. @return 다시 배송 ID */
  public String release(String deliveryId, String caseId) {
    return Objects.requireNonNull(
        tx.execute(
            s -> {
              DeliveryRow d = awaiting(deliveryId, caseId);
              Instant now = clock.instant();
              if (d.releasedAt() != null) {
                return deliveryId; // 이미 풀림
              }
              if (!"DISPATCHING".equals(d.state())) {
                throw PmartException.invalidState("결제를 기다리는 다시 배송이 아닙니다(지금 " + d.state() + ").");
              }
              store.markReleased(deliveryId, now);
              store.setPromise(deliveryId, now.plus(PROMISE_AFTER_RELEASE));
              store.openAttempt(deliveryId).ifPresent(a -> store.postpone(a.attemptId(), now));
              log.info("다시 배송 {} 결제됨 — 배차 시작(사건 {})", deliveryId, caseId);
              return deliveryId;
            }));
  }

  /** 결제 안 됨(TIMED_OUT·DECLINED) → 다시 배송 픽업 전 취소 + MFC 회수 배송. @return 회수 배송 ID */
  public String cancel(String deliveryId, String caseId, String reason) {
    return Objects.requireNonNull(
        tx.execute(
            s -> {
              DeliveryRow d = awaiting(deliveryId, caseId);
              Instant now = clock.instant();
              boolean cancelled = "CANCELLED_BEFORE_PICKUP".equals(d.state());
              if (!cancelled && (d.releasedAt() != null || !"DISPATCHING".equals(d.state()))) {
                throw PmartException.invalidState("결제를 기다리는 다시 배송이 아닙니다(지금 " + d.state() + ").");
              }
              DeliveryRow origin =
                  d.originDeliveryId() == null
                      ? null
                      : store.lockDelivery(d.originDeliveryId()).orElse(null);
              if (origin == null) {
                throw PmartException.invalidState("원래 배송이 없어 회수 배송을 만들 수 없습니다.");
              }
              if (!cancelled) {
                transitions.cancelBeforePickup(d, "MISDELIVERY_REDELIVERY_" + reason, now);
              }
              String recoveryId = recovery.startRecovery(caseId, origin, now);
              log.info("다시 배송 {} 결제 안 됨({}) → 취소, 회수 배송 {}", deliveryId, reason, recoveryId);
              return recoveryId;
            }));
  }

  /** 잠근 다시 배송 — 없거나 다른 사건이면 404, 결제가 필요 없는 다시 배송이면 409. */
  private DeliveryRow awaiting(String deliveryId, String caseId) {
    DeliveryRow d = store.lockDelivery(deliveryId).orElse(null);
    if (d == null || !d.redelivery() || !caseId.equals(d.caseId())) {
      throw PmartException.notFound("DELIVERY_NOT_FOUND", "다시 배송을 찾을 수 없습니다.");
    }
    if (!d.paymentRequired()) {
      throw PmartException.invalidState("결제가 필요 없는 다시 배송입니다.");
    }
    return d;
  }
}
