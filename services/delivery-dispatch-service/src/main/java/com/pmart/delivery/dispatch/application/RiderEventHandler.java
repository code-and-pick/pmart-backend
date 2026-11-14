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
 *   <li><b>픽업한 배송은 끝까지 전달</b>한다 — 그대로 둔다. 포장을 하나라도 넘겨받은 배송(묶음 여러 개 중 일부만 인계돼 아직 ASSIGNED)도 픽업한 것으로
 *       본다(PC 1 PR #13 검토).
 *   <li>응답 전 제안은 잠근 뒤 상태를 다시 본다 — 만료 처리가 먼저 커밋한 제안은 EXPIRED 그대로.
 *   <li>그 라이더에게 간 응답 전 제안은 LATE(INELIGIBLE)로 닫고 시도를 다시 찾게 한다. 새 제안은 rider 적격 조회가 막는다.
 *   <li>제한 사본({@code restricted_rider}, 기한 to)을 남긴다 — 수락(제안 응답·배달리스트)·제안 후보가 라이더 잠금 아래 다시 본다. rider
 *       조회는 트랜잭션 밖이라, 제한 직전에 조회한 수락이 이 처리 뒤에 들어와도 배정되지 않는다.
 *   <li>풀기(restriction NORMAL)는 사본만 지운다 — 적격 조회가 다시 넣는다.
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
    if (riderId.isBlank() || restriction.isBlank()) {
      return;
    }
    Instant now = clock.instant();
    store.lockRider(riderId);
    if ("NORMAL".equals(restriction)) {
      store.clearRestricted(riderId);
      log.info("라이더 {} 계정 제한 풀림", riderId);
      return;
    }
    // 사본을 먼저 남긴다 — 이 처리 뒤 들어오는 수락(rider 조회가 제한 전이었어도)은 같은 잠금 아래 이것을 본다
    String to = e.payload().path("to").asString(null);
    store.markRestricted(
        riderId, restriction, to == null || to.isBlank() ? null : Instant.parse(to), now);
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
        // 잠근 뒤 다시 본다 — 그 사이 만료 처리가 커밋했으면 EXPIRED 그대로(만료 처리가 시도를 이미 다시 찾게 했다)
        boolean stillSent =
            store.lockOffer(o.offerId()).map(x -> "SENT".equals(x.state())).orElse(false);
        if (!stillSent
            || !store.decideOffer(
                o.offerId(), "LATE", now, null, AcceptRules.LateReason.INELIGIBLE.name())) {
          continue;
        }
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
      // 픽업한 배송, 그리고 포장을 하나라도 넘겨받은 배송(모두 넘겨받기 전이라 아직 ASSIGNED)은 끝까지 전달
      if ("PICKED_UP".equals(d.state()) || d.handoverStarted()) {
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
