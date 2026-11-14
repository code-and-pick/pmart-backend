package com.pmart.delivery.dispatch.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.delivery.dispatch.domain.AcceptRules;
import com.pmart.delivery.dispatch.infrastructure.DispatchStore;
import com.pmart.delivery.dispatch.infrastructure.DispatchStore.DeliveryRow;
import com.pmart.delivery.dispatch.infrastructure.DispatchStore.OfferRow;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * rider 계정 제한(RS-1, delivery.rider.v1 {@code RiderRestricted}) → 그 라이더의 배차를 거둔다. 사용자 2026-11-14:
 *
 * <ul>
 *   <li>모든 제한 단계(신규배차 제한·일시 중지·계약 종료)에서 <b>픽업 전 배정은 풀고 다시 배차</b> — 배정 해제 + 새 시도 REASSIGN(그 라이더는 뺀다)
 *       + {@code DeliveryAssignmentChanged}(newRiderId null, reason {@code RIDER_RESTRICTED}). MFC
 *       는 이 사건으로 그 라이더의 인계를 막는다(D-4).
 *   <li><b>픽업한 배송은 끝까지 전달</b>한다 — 그대로 둔다.
 *   <li>그 라이더에게 간 응답 전 제안은 LATE(INELIGIBLE)로 닫고 시도를 다시 찾게 한다. 새 제안은 rider 적격 조회가 막는다.
 *   <li>풀기(restriction NORMAL)는 할 일이 없다 — 적격 조회가 다시 넣는다.
 * </ul>
 *
 * <p>잠금 순서는 수락과 같다(라이더 → 배송 ID 순 → 제안). inbox 트랜잭션 안. 다시 와도 이미 풀린 배정은 지나간다.
 */
@Component
public class RiderEventHandler {

  public static final Set<String> TYPES = Set.of("RiderRestricted");
  public static final String REASON = "RIDER_RESTRICTED";

  private static final Logger log = LoggerFactory.getLogger(RiderEventHandler.class);

  private final DispatchStore store;
  private final DispatchEvents events;
  private final Clock clock;

  public RiderEventHandler(DispatchStore store, DispatchEvents events, Clock clock) {
    this.store = store;
    this.events = events;
    this.clock = clock;
  }

  public void on(EventEnvelope e) {
    String riderId = e.payload().path("riderId").asString("");
    String restriction = e.payload().path("restriction").asString("");
    if (riderId.isBlank() || restriction.isBlank() || "NORMAL".equals(restriction)) {
      return;
    }
    Instant now = clock.instant();
    store.lockRider(riderId);
    List<DeliveryRow> deliveries = new ArrayList<>();
    for (String id : store.deliveryIdsTouchingRider(riderId)) {
      store.lockDelivery(id).ifPresent(deliveries::add);
    }
    // 응답 전 제안 닫기(묶음이면 같은 라이더에게 간 묶음 전체)
    Set<String> attempts = new LinkedHashSet<>();
    int closed = 0;
    for (DeliveryRow d : deliveries) {
      for (OfferRow o : store.offersOfDelivery(d.deliveryId())) {
        if (!riderId.equals(o.riderId()) || !"SENT".equals(o.state())) {
          continue;
        }
        store.lockOffer(o.offerId());
        store.decideOffer(o.offerId(), "LATE", now, null, AcceptRules.LateReason.INELIGIBLE.name());
        attempts.add(o.attemptId());
        closed++;
      }
    }
    for (String attemptId : attempts) {
      if (store.liveOffersInAttempt(attemptId, now) == 0) {
        store.searchAgain(attemptId, now);
      }
    }
    // 픽업 전 배정 풀고 다시 배차, 픽업한 배송은 그대로
    int released = 0;
    int kept = 0;
    for (DeliveryRow d : deliveries) {
      if (!riderId.equals(d.riderId())) {
        continue;
      }
      if ("PICKED_UP".equals(d.state())) {
        kept++;
        continue;
      }
      if (!"ASSIGNED".equals(d.state())) {
        continue;
      }
      int version = d.assignmentVersion() + 1;
      store.unassign(d.deliveryId(), version, now);
      store.insertAttempt("ATT-" + UUID.randomUUID(), d.deliveryId(), "REASSIGN", riderId, now);
      events.assignmentChanged(d, null, version, REASON, null, now);
      released++;
    }
    log.info(
        "라이더 {} 계정 제한 {} — 제안 {}건 닫음, 픽업 전 배정 {}건 풀고 재배차, 픽업한 {}건은 전달까지",
        riderId,
        restriction,
        closed,
        released,
        kept);
  }
}
