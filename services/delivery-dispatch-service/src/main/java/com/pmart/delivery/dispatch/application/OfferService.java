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
    store.markViewed(offerId, clock.instant());
    OfferRow fresh = store.offer(offerId).orElse(o);
    return new OfferView(fresh, store.delivery(o.deliveryId()).orElseThrow());
  }

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
              OfferRow peek = store.offer(offerId).orElseThrow();
              DeliveryRow d = store.lockDelivery(peek.deliveryId()).orElseThrow();
              OfferRow o = store.lockOffer(offerId).orElseThrow();
              if ("ACCEPTED".equals(o.state())) {
                return result(o, "ASSIGNED", null, d.assignmentId(), now);
              }
              if (!"SENT".equals(o.state())) {
                String late =
                    "SUPERSEDED".equals(o.state())
                        ? (Optional.ofNullable(o.lateReason()).orElse("ALREADY_ASSIGNED"))
                        : "EXPIRED";
                return result(o, "LATE", late, null, now);
              }
              if ("REJECT".equals(response)) {
                if (!now.isBefore(o.expiresAt())) {
                  return expired(o, now);
                }
                store.decideOffer(offerId, "REJECTED", now, rejectReason, null);
                store.markAttempt(o.attemptId(), false, true, false);
                int n = store.addRejection(riderId, now);
                if (store.liveOffersInAttempt(o.attemptId(), now) == 0) {
                  store.searchAgain(o.attemptId(), now);
                }
                return new OfferResult(
                    offerId,
                    "REJECTED",
                    null,
                    o.deliveryId(),
                    null,
                    n,
                    AcceptRules.rejectionNotice(n, props.rejectionNotice()),
                    now);
              }
              AcceptRules.Verdict v =
                  AcceptRules.judge(
                      d.state(),
                      o.expiresAt(),
                      now,
                      snapshot(rider, riderId),
                      props.maxActiveDeliveries());
              if (!v.ok()) {
                if (v.late() == AcceptRules.LateReason.EXPIRED) {
                  return expired(o, now);
                }
                store.decideOffer(offerId, "LATE", now, null, v.late().name());
                return result(o, "LATE", v.late().name(), null, now);
              }
              String assignmentId = transitions.assign(d, riderId, offerId, now);
              return result(o, "ASSIGNED", null, assignmentId, now);
            })
        .value();
  }

  // ---------- 일반 배차모드 배달리스트 ----------

  public DeliveryList deliveryList() {
    String riderId = requireRider();
    Instant now = clock.instant();
    Optional<Upstreams.Rider> rider = upstreams.rider(riderId);
    if (rider.isEmpty() || !rider.get().acceptsNewDispatch()) {
      return new DeliveryList(List.of(), now, null);
    }
    Optional<Upstreams.Location> loc = upstreams.location(riderId).filter(l -> fresh(l, now));
    if (loc.isEmpty()) {
      return new DeliveryList(List.of(), now, props.pollAfterSec());
    }
    List<ListItem> items = new ArrayList<>();
    for (DispatchStore.ListedDelivery x : store.dispatchingWithStage()) {
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
              DeliveryRow d = store.lockDelivery(peek.deliveryId()).orElseThrow();
              var attempt = store.openAttempt(d.deliveryId());
              AcceptRules.Verdict v =
                  AcceptRules.judge(
                      d.state(), null, now, snapshot(rider, riderId), props.maxActiveDeliveries());
              String late = v.ok() ? null : v.late().name();
              double radius =
                  attempt
                      .map(a -> SearchRules.radiusKm(props.radiiKm(), a.stage()))
                      .orElse(props.maxRadiusKm());
              if (late == null
                  && (attempt.isEmpty()
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
                      vehicle);
              if (o.attemptId() == null) {
                throw PmartException.notFound("DELIVERY_NOT_FOUND", "배차 대기 배송이 아닙니다.");
              }
              store.insertOffer(o);
              if (late != null) {
                return result(o, "LATE", late, null, now);
              }
              String assignmentId = transitions.assign(d, riderId, o.offerId(), now);
              return result(o, "ASSIGNED", null, assignmentId, now);
            })
        .value();
  }

  // ---------- 공통 ----------

  private OfferResult expired(OfferRow o, Instant now) {
    store.decideOffer(o.offerId(), "EXPIRED", now, null, null);
    store.markAttempt(o.attemptId(), false, false, true);
    if (AcceptRules.expiryCountsAsRejection(o.viewedAt())) {
      store.addRejection(o.riderId(), now);
    }
    if (store.liveOffersInAttempt(o.attemptId(), now) == 0) {
      store.searchAgain(o.attemptId(), now);
    }
    return result(o, "LATE", "EXPIRED", null, now);
  }

  private AcceptRules.@Nullable RiderSnapshot snapshot(
      Optional<Upstreams.Rider> rider, String riderId) {
    return rider
        .map(
            r ->
                new AcceptRules.RiderSnapshot(
                    r.acceptsNewDispatch(), r.oneAtATime(), store.activeDeliveries(riderId)))
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

  private boolean fresh(Upstreams.Location l, Instant now) {
    return !l.receivedAt().plus(props.locationMaxAge()).isBefore(now);
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
