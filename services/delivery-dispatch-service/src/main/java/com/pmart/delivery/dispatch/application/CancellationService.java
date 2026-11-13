package com.pmart.delivery.dispatch.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.delivery.dispatch.infrastructure.DispatchStore;
import com.pmart.delivery.dispatch.infrastructure.DispatchStore.DeliveryRow;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * 배차 취소(원문 §5.4 — 배차취소는 라이더 배정 해제, 주문취소와 다르다).
 *
 * <ul>
 *   <li>픽업 전(ASSIGNED): 배정을 풀고 상품은 점포에 남는다. reassign(기본 true)이면 다시 배차 대기 + 새 시도(REASSIGN, 취소한 라이더는
 *       뺀다) + {@code DeliveryAssignmentChanged}(newRiderId null — MFC 는 이전 라이더 인계를 막는다).
 *       reassign=false 면 {@code DeliveryCancelledBeforePickup}.
 *   <li>픽업 뒤(PICKED_UP): 422 PICKED_UP_CASE_REQUIRED — 지원센터가 상품 위치·회수·재인계를 확인한다(기록은 남긴다).
 *   <li>요청자: 배정된 라이더 본인(RIDER), 그 센터 운영자·OPERATOR_ADMIN(OPERATOR — 사유 OPERATOR 면 approvalRef 필수),
 *       서비스(사가).
 *   <li>같은 키·같은 본문은 같은 결과(RequestIdempotency), X-Expected-Version 은 배송 버전.
 * </ul>
 */
@Service
public class CancellationService {

  public record Cancel(
      String requestedBy,
      String reason,
      @Nullable String reasonDetail,
      boolean reassign,
      @Nullable String approvalRef) {}

  public record Result(
      String requestId,
      String deliveryId,
      String state,
      @Nullable String reassignAttemptId,
      int version,
      Instant occurredAt) {}

  private final DispatchStore store;
  private final DeliveryTransitions transitions;
  private final DispatchEvents events;
  private final RequestIdempotency idempotency;
  private final Clock clock;

  public CancellationService(
      DispatchStore store,
      DeliveryTransitions transitions,
      DispatchEvents events,
      RequestIdempotency idempotency,
      Clock clock) {
    this.store = store;
    this.transitions = transitions;
    this.events = events;
    this.idempotency = idempotency;
    this.clock = clock;
  }

  public Result cancel(
      String deliveryId,
      String requestId,
      String sourceSystem,
      @Nullable Integer expectedVersion,
      Cancel c) {
    PmartPrincipal p =
        PmartPrincipal.current().orElseThrow(() -> OfferService.forbidden("인증이 필요합니다."));
    if ("OPERATOR".equals(c.reason()) && (c.approvalRef() == null || c.approvalRef().isBlank())) {
      throw new PmartException(
          ErrorCode.APPROVAL_REQUIRED, "운영자 사유 배차 취소는 승인 번호(approvalRef)가 필요합니다.");
    }
    return idempotency
        .execute(
            new RequestKey(sourceSystem, requestId, "createAssignmentCancellation", p.subject()),
            Map.of(
                "deliveryId",
                deliveryId,
                "cancel",
                c,
                "expectedVersion",
                String.valueOf(expectedVersion)),
            Result.class,
            () -> {
              Instant now = clock.instant();
              DeliveryRow d =
                  store
                      .lockDelivery(deliveryId)
                      .orElseThrow(
                          () -> PmartException.notFound("DELIVERY_NOT_FOUND", "배송이 없습니다."));
              authorize(p, d, c);
              if (expectedVersion != null && expectedVersion != d.version()) {
                throw PmartException.versionConflict("배송이 바뀌었습니다(현재 버전 " + d.version() + ").");
              }
              if ("PICKED_UP".equals(d.state())) {
                if (!store.insertCancellation(
                    requestId,
                    deliveryId,
                    c.requestedBy(),
                    p.subject(),
                    c.reason(),
                    c.reasonDetail(),
                    c.reassign(),
                    c.approvalRef(),
                    "REJECTED_PICKED_UP",
                    d.riderId(),
                    null,
                    now)) {
                  throw PmartException.idempotencyConflict(
                      "같은 Idempotency-Key 로 다른 요청이 이미 처리되었습니다. 새 키를 사용하세요.");
                }
                return new Result(
                    requestId, deliveryId, "REJECTED_PICKED_UP", null, d.version(), now);
              }
              if (!"ASSIGNED".equals(d.state())) {
                // 배정 전 배송: 운영자(MFC 백오피스)만 — 재배차 없이 취소, 또는 배차 실패(시스템 장애) 다시 배차(사용자 2026-11-13 M2)
                boolean staff = p.type() != PmartPrincipal.PrincipalType.RIDER;
                boolean waiting =
                    List.of("CREATED", "DISPATCHING", "DISPATCH_FAILED").contains(d.state());
                if (!staff || !waiting) {
                  throw PmartException.invalidState("배정된 배송만 배차 취소할 수 있습니다(지금 " + d.state() + ").");
                }
                String restart = null;
                if (!c.reassign()) {
                  transitions.cancelBeforePickup(d, c.reason(), now);
                } else if ("DISPATCH_FAILED".equals(d.state())) {
                  store.setState(deliveryId, "DISPATCHING", null, now);
                  restart = "ATT-" + UUID.randomUUID();
                  store.insertAttempt(restart, deliveryId, "REASSIGN", now);
                } else {
                  throw PmartException.invalidState(
                      "이미 배차 중입니다 — 재배차 없이 취소(reassign=false)만 할 수 있습니다.");
                }
                if (!store.insertCancellation(
                    requestId,
                    deliveryId,
                    c.requestedBy(),
                    p.subject(),
                    c.reason(),
                    c.reasonDetail(),
                    c.reassign(),
                    c.approvalRef(),
                    "CANCELLED_BEFORE_PICKUP",
                    null,
                    restart,
                    now)) {
                  throw PmartException.idempotencyConflict(
                      "같은 Idempotency-Key 로 다른 요청이 이미 처리되었습니다. 새 키를 사용하세요.");
                }
                return new Result(
                    requestId,
                    deliveryId,
                    "CANCELLED_BEFORE_PICKUP",
                    restart,
                    d.version() + 1,
                    now);
              }
              EventEnvelope.Actor actor = actor(p);
              String attemptId = null;
              if (c.reassign()) {
                int version = d.assignmentVersion() + 1;
                store.unassign(deliveryId, version, now);
                attemptId = "ATT-" + UUID.randomUUID();
                store.insertAttempt(attemptId, deliveryId, "REASSIGN", d.riderId(), now);
                events.assignmentChanged(d, null, version, "CANCELLED_" + c.reason(), actor, now);
              } else {
                transitions.cancelBeforePickup(d, c.reason(), now);
              }
              if (!store.insertCancellation(
                  requestId,
                  deliveryId,
                  c.requestedBy(),
                  p.subject(),
                  c.reason(),
                  c.reasonDetail(),
                  c.reassign(),
                  c.approvalRef(),
                  "CANCELLED_BEFORE_PICKUP",
                  d.riderId(),
                  attemptId,
                  now)) {
                throw PmartException.idempotencyConflict(
                    "같은 Idempotency-Key 로 다른 요청이 이미 처리되었습니다. 새 키를 사용하세요.");
              }
              return new Result(
                  requestId,
                  deliveryId,
                  "CANCELLED_BEFORE_PICKUP",
                  attemptId,
                  d.version() + 1,
                  now);
            })
        .value();
  }

  /**
   * 배차 취소 결과 조회 — 요청한 본인, 그 센터 운영자(MFC 백오피스)·OPERATOR_ADMIN, 고객센터(CS_AGENT), 서비스(사가). 지원센터 역할이 생기면
   * 더한다(사용자 2026-11-13 "백오피스에 권한이 있는 사람이나 고객센터 및 지원센터", PC 1 검토 L1). 볼 수 없으면 있는지도 알리지 않는다(404).
   */
  public DispatchStore.CancellationRow result(String deliveryId, String requestId) {
    PmartPrincipal p =
        PmartPrincipal.current().orElseThrow(() -> OfferService.forbidden("인증이 필요합니다."));
    DispatchStore.CancellationRow c =
        store
            .cancellation(deliveryId, requestId)
            .orElseThrow(
                () -> PmartException.notFound("CANCELLATION_NOT_FOUND", "배차 취소 요청이 없습니다."));
    String mfcId = store.delivery(deliveryId).map(DeliveryRow::mfcId).orElse("");
    boolean ok =
        switch (p.type()) {
          case SERVICE -> true;
          case OPERATOR ->
              p.hasRole("OPERATOR_ADMIN") || p.hasRole("CS_AGENT") || p.canAccessCenter(mfcId);
          default -> p.subject().equals(c.actor());
        };
    if (!ok) {
      throw PmartException.notFound("CANCELLATION_NOT_FOUND", "배차 취소 요청이 없습니다.");
    }
    return c;
  }

  private static void authorize(PmartPrincipal p, DeliveryRow d, Cancel c) {
    switch (p.type()) {
      case RIDER -> {
        if (!p.subject().equals(d.riderId()) || !"RIDER".equals(c.requestedBy())) {
          throw OfferService.forbidden("본인에게 배정된 배송만 배차 취소할 수 있습니다.");
        }
      }
      case OPERATOR -> {
        if (!p.canAccessCenter(d.mfcId()) && !p.hasRole("OPERATOR_ADMIN")) {
          throw OfferService.forbidden("이 센터 배송을 배차 취소할 수 없습니다.");
        }
        if ("RIDER".equals(c.requestedBy())) {
          throw new PmartException(
              ErrorCode.VALIDATION_FAILED, "운영자 요청은 requestedBy 가 RIDER 일 수 없습니다.");
        }
      }
      case SERVICE -> {}
      default -> throw OfferService.forbidden("배차 취소할 수 없습니다.");
    }
  }

  private static EventEnvelope.Actor actor(PmartPrincipal p) {
    return switch (p.type()) {
      case RIDER -> new EventEnvelope.Actor(EventEnvelope.ActorType.RIDER, p.subject());
      case OPERATOR -> new EventEnvelope.Actor(EventEnvelope.ActorType.OPERATOR, p.subject());
      default -> new EventEnvelope.Actor(EventEnvelope.ActorType.SYSTEM, p.subject());
    };
  }
}
