package com.pmart.delivery.dispatch.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.delivery.dispatch.domain.AcceptRules;
import com.pmart.delivery.dispatch.domain.FeeTable;
import com.pmart.delivery.dispatch.domain.Geo;
import com.pmart.delivery.dispatch.domain.SearchRules;
import com.pmart.delivery.dispatch.infrastructure.DispatchStore;
import com.pmart.delivery.dispatch.infrastructure.DispatchStore.DeliveryRow;
import com.pmart.delivery.dispatch.infrastructure.DispatchStore.OfferRow;
import com.pmart.delivery.dispatch.infrastructure.Upstreams;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 라이더 쪽 배차 — AI 추천 제안 목록·조회·응답, 일반 배차모드 배달리스트·수락(원문 §2.2·§2.4·§3.3·§4.3·§4.4).
 *
 * <ul>
 *   <li>수락은 배송 행 잠금 아래 {@link AcceptRules} — 한 명만 배정, 나머지는 LATE(이미 배정·만료·주문 취소·자격/용량). 늦은 수락도 결과로
 *       돌려주어 같은 키 재전송에 같은 답.
 *   <li>라이더 상태(운행·신규배차·제한·하나씩 배달)는 rider-service 조회 사본, 맡은 배송 수는 이 서비스 기록(트랜잭션 안).
 *   <li>거절은 연속 거절 +1(5회면 안내), 시도는 다시 탐색. 제안 조회는 처음 연 시각을 남긴다(만료 때 거절 횟수 판정).
 * </ul>
 */
@Service
public class OfferService {

  public record OfferResult(
      String offerId,
      String outcome,
      @Nullable String lateReason,
      String deliveryId,
      @Nullable String assignmentId,
      @Nullable Integer consecutiveRejections,
      @Nullable Boolean rejectionNotice,
      Instant decidedAt) {}

  public record MyOffers(
      List<OfferView> items,
      Instant serverTime,
      @Nullable Integer pollAfterSec,
      @Nullable String nextCursor) {}

  public record OfferView(OfferRow offer, DeliveryRow delivery) {}

  public record ListItem(DeliveryRow delivery, double straightKm, int fee, Instant readyAt) {}

  public record DeliveryList(
      List<ListItem> items, Instant serverTime, @Nullable Integer pollAfterSec) {}

  private final DispatchStore store;
  private final Upstreams upstreams;
  private final DeliveryTransitions transitions;
  private final RequestIdempotency idempotency;
  private final DispatchProperties props;
  private final Clock clock;

  public OfferService(
      DispatchStore store,
      Upstreams upstreams,
      DeliveryTransitions transitions,
      RequestIdempotency idempotency,
      DispatchProperties props,
      Clock clock) {
    this.store = store;
    this.upstreams = upstreams;
    this.transitions = transitions;
    this.idempotency = idempotency;
    this.props = props;
    this.clock = clock;
  }

  // ---------- AI 추천 제안 ----------

  public MyOffers myOffers(@Nullable String state, int size, @Nullable String cursor) {
    String riderId = requireRider();
    Instant now = clock.instant();
    int limit = Math.max(1, Math.min(size, 100));
    int offset = parseCursor(cursor);
    List<OfferRow> rows = store.riderOffers(riderId, state, now, limit + 1, offset);
    List<OfferView> items = new ArrayList<>();
    for (OfferRow o : rows.subList(0, Math.min(limit, rows.size()))) {
      store.delivery(o.deliveryId()).ifPresent(d -> items.add(new OfferView(o, d)));
    }
    Integer poll =
        upstreams.rider(riderId).filter(Upstreams.Rider::acceptsNewDispatch).isPresent()
            ? props.pollAfterSec()
            : null;
    return new MyOffers(
        items, now, poll, rows.size() > limit ? String.valueOf(offset + limit) : null);
  }

  @Transactional
  public OfferView offer(String offerId) {
    String riderId = requireRider();
    OfferRow o = ownOffer(offerId, riderId);
    Instant now = clock.instant();
    // 묶음은 한 화면이라 묶음 전체를 확인한 것으로 본다(원문 §4.4 거절 횟수 판정)
    for (OfferRow x : group(o)) {
      store.markViewed(x.offerId(), now);
    }
    OfferRow fresh = store.offer(offerId).orElse(o);
    return new OfferView(fresh, store.delivery(o.deliveryId()).orElseThrow());
  }

  /** 제안 응답. 묶음(bundleId)은 묶음 전체를 한 번에 판정한다 — 모두 배정되거나 전부 LATE, 거절도 전체(연속 거절 1회). */
  public OfferResult respond(
      String offerId,
      String requestId,
      String sourceSystem,
      String response,
      @Nullable String rejectReason) {
    String riderId = requireRider();
    ownOffer(offerId, riderId);
    Optional<Upstreams.Rider> rider =
        "ACCEPT".equals(response) ? upstreams.rider(riderId) : Optional.empty();
    return idempotency
        .execute(
            new RequestKey(sourceSystem, requestId, "respondToOffer", riderId),
            Map.of(
                "offerId",
                offerId,
                "response",
                response,
                "rejectReason",
                String.valueOf(rejectReason)),
            OfferResult.class,
            () -> {
              Instant now = clock.instant();
              // 같은 라이더의 수락은 줄을 세워 앞 수락의 건수를 본 뒤 판정한다(M1). 라이더 → 배송 순서로 잠근다
              store.lockRider(riderId);
              OfferRow peek = store.offer(offerId).orElseThrow();
              List<OfferRow> peekGroup = group(peek);
              // 배송 행을 ID 순으로 잠근다(같은 묶음을 두 요청이 동시에 잡아도 교착 없음)
              Map<String, DeliveryRow> deliveries = new HashMap<>();
              peekGroup.stream()
                  .map(OfferRow::deliveryId)
                  .sorted()
                  .forEach(id -> deliveries.put(id, store.lockDelivery(id).orElseThrow()));
              List<OfferRow> offers =
                  peekGroup.stream().map(x -> store.lockOffer(x.offerId()).orElseThrow()).toList();
              OfferRow me =
                  offers.stream()
                      .filter(x -> x.offerId().equals(offerId))
                      .findFirst()
                      .orElseThrow();
              if ("ACCEPTED".equals(me.state())) {
                return result(
                    me, "ASSIGNED", null, deliveries.get(me.deliveryId()).assignmentId(), now);
              }
              OfferRow closed =
                  offers.stream().filter(x -> !"SENT".equals(x.state())).findFirst().orElse(null);
              if (closed != null) {
                String late =
                    switch (closed.state()) {
                      case "SUPERSEDED", "LATE" ->
                          Optional.ofNullable(closed.lateReason()).orElse("ALREADY_ASSIGNED");
                      default -> "EXPIRED";
                    };
                lateGroup(offers, late, now);
                return result(me, "LATE", late, null, now);
              }
              if (!now.isBefore(me.expiresAt())) {
                return expiredGroup(offers, me, now);
              }
              if ("REJECT".equals(response)) {
                for (OfferRow x : offers) {
                  store.decideOffer(x.offerId(), "REJECTED", now, rejectReason, null);
                  store.markAttempt(x.attemptId(), false, true, false);
                }
                int n = store.addRejection(riderId, now);
                searchAgain(offers, now);
                return new OfferResult(
                    offerId,
                    "REJECTED",
                    null,
                    me.deliveryId(),
                    null,
                    n,
                    AcceptRules.rejectionNotice(n, props.rejectionNotice()),
                    now);
              }
              // 묶음 전체를 맡을 수 있어야 한다 — 지금 맡은 수 + (묶음 − 1) 로 한도를 본다
              // rider 조회는 트랜잭션 밖 — 그 뒤 처리된 계정 제한은 잠금 아래 사본으로 다시 본다(RS-1)
              boolean restricted = store.restricted(riderId, now);
              AcceptRules.RiderSnapshot snap =
                  rider
                      .map(
                          r ->
                              new AcceptRules.RiderSnapshot(
                                  r.acceptsNewDispatch() && !restricted,
                                  r.oneAtATime(),
                                  store.activeDeliveries(riderId) + offers.size() - 1))
                      .orElse(null);
              for (OfferRow x : offers) {
                AcceptRules.Verdict v =
                    AcceptRules.judge(
                        deliveries.get(x.deliveryId()).state(),
                        x.expiresAt(),
                        now,
                        snap,
                        props.maxActiveDeliveries());
                if (!v.ok()) {
                  if (v.late() == AcceptRules.LateReason.EXPIRED) {
                    return expiredGroup(offers, me, now);
                  }
                  lateGroup(offers, v.late().name(), now);
                  return result(me, "LATE", v.late().name(), null, now);
                }
              }
              String myAssignment = null;
              for (OfferRow x : offers) {
                String assignmentId =
                    transitions.assign(
                        deliveries.get(x.deliveryId()),
                        riderId,
                        x.offerId(),
                        x.bundleId(),
                        x.stopSeq(),
                        now);
                if (x.offerId().equals(offerId)) {
                  myAssignment = assignmentId;
                }
              }
              return result(me, "ASSIGNED", null, myAssignment, now);
            })
        .value();
  }

  private List<OfferRow> group(OfferRow o) {
    return o.bundleId() == null ? List.of(o) : store.bundleOffers(o.bundleId());
  }

  /** 묶음의 남은 SENT 제안을 LATE 로 닫고 그 시도들은 다시 탐색. */
  private void lateGroup(List<OfferRow> offers, String reason, Instant now) {
    for (OfferRow x : offers) {
      if ("SENT".equals(x.state())) {
        store.decideOffer(x.offerId(), "LATE", now, null, reason);
      }
    }
    searchAgain(offers, now);
  }

  private OfferResult expiredGroup(List<OfferRow> offers, OfferRow me, Instant now) {
    boolean counted = false;
    for (OfferRow x : offers) {
      if (!"SENT".equals(x.state())) {
        continue;
      }
      store.decideOffer(x.offerId(), "EXPIRED", now, null, null);
      store.markAttempt(x.attemptId(), false, false, true);
      if (!counted && AcceptRules.expiryCountsAsRejection(x.viewedAt())) {
        store.addRejection(x.riderId(), now);
        counted = true;
      }
    }
    searchAgain(offers, now);
    return result(me, "LATE", "EXPIRED", null, now);
  }

  private void searchAgain(List<OfferRow> offers, Instant now) {
    for (OfferRow x : offers) {
      if (store.liveOffersInAttempt(x.attemptId(), now) == 0) {
        store.searchAgain(x.attemptId(), now);
      }
    }
  }

  // ---------- 일반 배차모드 배달리스트 ----------

  public DeliveryList deliveryList() {
    String riderId = requireRider();
    Instant now = clock.instant();
    Optional<Upstreams.Rider> rider = upstreams.rider(riderId);
    if (rider.isEmpty() || !rider.get().acceptsNewDispatch() || store.restricted(riderId, now)) {
      return new DeliveryList(List.of(), now, null);
    }
    Optional<Upstreams.Location> loc = upstreams.location(riderId).filter(l -> fresh(l, now));
    if (loc.isEmpty()) {
      return new DeliveryList(List.of(), now, props.pollAfterSec());
    }
    // 최근 반복 배차취소 → 새 배송이 늦게 보인다(배차 알림 감소 — 제재 아님, 사용자 2026-11-13)
    int cancels =
        store
            .recentRiderCancellations(List.of(riderId), now.minus(props.cancellationWindow()))
            .getOrDefault(riderId, 0);
    Instant visibleIfWaitingSince =
        now.minus(props.listDelayPerCancellation().multipliedBy(cancels));
    List<ListItem> items = new ArrayList<>();
    for (DispatchStore.ListedDelivery x : store.dispatchingWithStage()) {
      if (x.waitingSince().isAfter(visibleIfWaitingSince)) {
        continue;
      }
      if (riderId.equals(x.excludedRiderId())) {
        continue; // 방금 배차취소한 배송은 다른 라이더에게(원문 §5.4, L5)
      }
      DeliveryRow d = x.delivery();
      double km = loc.get().at().metersTo(new Geo(d.mfcLat(), d.mfcLng())) / 1000.0;
      if (km <= SearchRules.radiusKm(props.radiiKm(), x.stage())) {
        Instant ready = d.readyOrEstimate() == null ? d.createdAt() : d.readyOrEstimate();
        items.add(
            new ListItem(
                d, km, FeeTable.estimate(rider.get().vehicleType(), d.dropDistanceM()), ready));
      }
    }
    items.sort(Comparator.comparingDouble(ListItem::straightKm));
    return new DeliveryList(items, now, props.pollAfterSec());
  }

  public OfferResult acceptFromList(String deliveryId, String requestId, String sourceSystem) {
    String riderId = requireRider();
    DeliveryRow peek =
        store
            .delivery(deliveryId)
            .orElseThrow(() -> PmartException.notFound("DELIVERY_NOT_FOUND", "배송이 없습니다."));
    Optional<Upstreams.Rider> rider = upstreams.rider(riderId);
    Optional<Upstreams.Location> loc = upstreams.location(riderId);
    return idempotency
        .execute(
            new RequestKey(sourceSystem, requestId, "acceptFromDeliveryList", riderId),
            Map.of("deliveryId", deliveryId),
            OfferResult.class,
            () -> {
              Instant now = clock.instant();
              store.lockRider(riderId); // M1
              DeliveryRow d = store.lockDelivery(peek.deliveryId()).orElseThrow();
              var attempt = store.openAttempt(d.deliveryId());
              AcceptRules.Verdict v =
                  AcceptRules.judge(
                      d.state(),
                      null,
                      now,
                      snapshot(rider, riderId, now),
                      props.maxActiveDeliveries());
              String late = v.ok() ? null : v.late().name();
              double radius =
                  attempt
                      .map(a -> SearchRules.radiusKm(props.radiiKm(), a.stage()))
                      .orElse(props.maxRadiusKm());
              if (late == null
                  && (attempt.isEmpty()
                      || riderId.equals(attempt.get().excludedRiderId())
                      || d.mfcLat() == null
                      || loc.isEmpty()
                      || !fresh(loc.get(), now)
                      || loc.get().at().metersTo(new Geo(d.mfcLat(), d.mfcLng()))
                          > radius * 1000.0)) {
                late = AcceptRules.LateReason.INELIGIBLE.name();
              }
              // 배차 이력(원문 §3.3) — 배달리스트 수락도 제안 한 줄로 남긴다
              String vehicle = rider.map(Upstreams.Rider::vehicleType).orElse(null);
              OfferRow o =
                  new OfferRow(
                      "OFR-" + UUID.randomUUID(),
                      attempt
                          .map(DispatchStore.AttemptRow::attemptId)
                          .orElse(
                              store.attempts(d.deliveryId()).isEmpty()
                                  ? null
                                  : store.attempts(d.deliveryId()).getLast().attemptId()),
                      d.deliveryId(),
                      riderId,
                      attempt.map(DispatchStore.AttemptRow::round).orElse(0),
                      "DELIVERY_LIST",
                      radius,
                      late == null ? "SENT" : "LATE",
                      now,
                      now,
                      now,
                      late == null ? null : now,
                      null,
                      late,
                      null,
                      null,
                      d.dropDistanceM() == null || vehicle == null
                          ? 0
                          : FeeTable.estimate(vehicle, d.dropDistanceM()),
                      vehicle,
                      null,
                      1,
                      1);
              if (o.attemptId() == null) {
                throw PmartException.notFound("DELIVERY_NOT_FOUND", "배차 대기 배송이 아닙니다.");
              }
              store.insertOffer(o);
              if (late != null) {
                return result(o, "LATE", late, null, now);
              }
              String assignmentId = transitions.assign(d, riderId, o.offerId(), null, 1, now);
              return result(o, "ASSIGNED", null, assignmentId, now);
            })
        .value();
  }

  // ---------- 공통 ----------

  /** 라이더 잠금 아래에서 부른다 — 트랜잭션 밖 rider 조회 뒤 처리된 계정 제한(사본)도 본다(RS-1). */
  private AcceptRules.@Nullable RiderSnapshot snapshot(
      Optional<Upstreams.Rider> rider, String riderId, Instant now) {
    boolean restricted = store.restricted(riderId, now);
    return rider
        .map(
            r ->
                new AcceptRules.RiderSnapshot(
                    r.acceptsNewDispatch() && !restricted,
                    r.oneAtATime(),
                    store.activeDeliveries(riderId)))
        .orElse(null);
  }

  private static OfferResult result(
      OfferRow o,
      String outcome,
      @Nullable String late,
      @Nullable String assignmentId,
      Instant now) {
    return new OfferResult(
        o.offerId(), outcome, late, o.deliveryId(), assignmentId, null, null, now);
  }

  /** 최근 위치이고 품질을 쓸 수 있을 때만(AI 추천 후보와 같은 기준 — L5). */
  private boolean fresh(Upstreams.Location l, Instant now) {
    return !l.receivedAt().plus(props.locationMaxAge()).isBefore(now)
        && DispatchEngine.USABLE_QUALITY.contains(l.quality());
  }

  private OfferRow ownOffer(String offerId, String riderId) {
    OfferRow o =
        store
            .offer(offerId)
            .orElseThrow(() -> PmartException.notFound("OFFER_NOT_FOUND", "제안이 없습니다."));
    if (!o.riderId().equals(riderId)) {
      throw forbidden("본인 제안이 아닙니다.");
    }
    return o;
  }

  static String requireRider() {
    return PmartPrincipal.current()
        .filter(p -> p.type() == PmartPrincipal.PrincipalType.RIDER)
        .map(PmartPrincipal::subject)
        .orElseThrow(() -> forbidden("라이더만 쓸 수 있습니다."));
  }

  static PmartException forbidden(String message) {
    return new PmartException(
        ErrorCode.FORBIDDEN.name(), HttpStatus.FORBIDDEN, message, false, List.of());
  }

  private static int parseCursor(@Nullable String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return 0;
    }
    try {
      return Math.max(0, Integer.parseInt(cursor));
    } catch (NumberFormatException e) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "cursor 형식이 잘못되었습니다.");
    }
  }
}
