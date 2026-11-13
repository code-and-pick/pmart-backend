package com.pmart.delivery.dispatch.application;

import com.pmart.common.web.PmartException;
import com.pmart.delivery.dispatch.domain.AcceptRules;
import com.pmart.delivery.dispatch.domain.AssignmentProblem;
import com.pmart.delivery.dispatch.domain.FeeTable;
import com.pmart.delivery.dispatch.domain.Geo;
import com.pmart.delivery.dispatch.domain.OperatingHours;
import com.pmart.delivery.dispatch.domain.SearchRules;
import com.pmart.delivery.dispatch.infrastructure.DispatchStore;
import com.pmart.delivery.dispatch.infrastructure.DispatchStore.AttemptRow;
import com.pmart.delivery.dispatch.infrastructure.DispatchStore.DeliveryRow;
import com.pmart.delivery.dispatch.infrastructure.DispatchStore.OfferRow;
import com.pmart.delivery.dispatch.infrastructure.Upstreams;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * AI 추천 배차 판단 주기(원문 §2.1 ①~④, ADR-0058 — Python AI 서버 전까지 OR-Tools).
 *
 * <ol>
 *   <li>응답 없이 만료된 제안 → EXPIRED(상세를 열었으면 거절 횟수에 넣는다 — §4.4), 그 시도는 다시 탐색.
 *   <li>판단할 차례가 된 시도를 MFC 별로 모은다. 배차 가능 시간(08~24시) 밖이면 다음 시작까지 미룬다.
 *   <li>배송마다 좌표 준비(한 번): MFC 좌표(center-master), 배송지(order → serviceability 정확 일치), MFC → 고객 도로
 *       거리(tracking 카카오모빌리티). 실패하면 사유를 남기고 30초 뒤 다시(직선거리로 대신하지 않는다).
 *   <li>후보: 적격 라이더(rider) 중 AI 추천 모드·살아 있는 제안 없음·맡은 배송 한도 밑, 위치(tracking)가 최신이고 그 시도의 반경 단계 안(MFC
 *       중심 직선 — 사용자 2026-11-13), 이번 회차에 이미 제안받지 않음.
 *   <li>라이더 → MFC 도로 소요(카카오모빌리티)로 {@link AssignmentProblem} → {@link AssignmentSolver}(OR-Tools
 *       CP-SAT).
 *   <li>짝지어진 배송은 제안(1분) + DispatchOfferSent. 못 지은 배송은 {@link SearchRules}(단계 머물기·다음 단계·재요청·실패).
 * </ol>
 *
 * 일반 배차모드 라이더는 제안을 받지 않고 배달리스트에서 고른다({@link OfferService}). 상태 변경은 짧은 트랜잭션마다 행 잠금 아래에서 다시 확인한다.
 */
@Service
public class DispatchEngine {

  private static final Logger log = LoggerFactory.getLogger(DispatchEngine.class);
  private static final Duration RETRY_LATER = Duration.ofSeconds(30);
  private static final Set<String> USABLE_QUALITY = Set.of("GOOD", "LOW_ACCURACY");

  private final DispatchStore store;
  private final Upstreams upstreams;
  private final AssignmentSolver solver;
  private final DeliveryTransitions transitions;
  private final DispatchEvents events;
  private final DispatchProperties props;
  private final TransactionTemplate tx;
  private final Clock clock;

  public DispatchEngine(
      DispatchStore store,
      Upstreams upstreams,
      AssignmentSolver solver,
      DeliveryTransitions transitions,
      DispatchEvents events,
      DispatchProperties props,
      TransactionTemplate tx,
      Clock clock) {
    this.store = store;
    this.upstreams = upstreams;
    this.solver = solver;
    this.transitions = transitions;
    this.events = events;
    this.props = props;
    this.tx = tx;
    this.clock = clock;
  }

  @Scheduled(
      fixedDelayString = "${pmart.dispatch.tick-interval:PT5S}",
      initialDelayString = "${pmart.dispatch.tick-initial-delay:PT10S}")
  public void scheduledTick() {
    try {
      tick();
    } catch (RuntimeException e) {
      log.error("배차 판단 주기 실패 — 다음 주기에 다시", e);
    }
  }

  /** 한 주기. 시험은 직접 부른다. */
  public void tick() {
    Instant now = clock.instant();
    expireOffers(now);
    Map<String, List<DispatchStore.Due>> byMfc = new LinkedHashMap<>();
    for (DispatchStore.Due d : store.dueAttempts(now, 50)) {
      byMfc.computeIfAbsent(d.mfcId(), k -> new ArrayList<>()).add(d);
    }
    for (var e : byMfc.entrySet()) {
      try {
        runMfc(e.getKey(), e.getValue(), now);
      } catch (RuntimeException ex) {
        log.error("MFC {} 배차 판단 실패 — 30초 뒤 다시", e.getKey(), ex);
        e.getValue()
            .forEach(
                d ->
                    tx.executeWithoutResult(
                        s -> store.postpone(d.attemptId(), now.plus(RETRY_LATER))));
      }
    }
  }

  // ---------- 1. 만료 ----------

  void expireOffers(Instant now) {
    tx.executeWithoutResult(
        s -> {
          for (OfferRow o : store.lockExpired(now, 200)) {
            store.decideOffer(o.offerId(), "EXPIRED", now, null, null);
            store.markAttempt(o.attemptId(), false, false, true);
            if (AcceptRules.expiryCountsAsRejection(o.viewedAt())) {
              store.addRejection(o.riderId(), now);
            }
            if (store.liveOffersInAttempt(o.attemptId(), now) == 0) {
              store.searchAgain(o.attemptId(), now);
            }
          }
        });
  }

  // ---------- 2~6. 한 MFC ----------

  private record Ready(DeliveryRow delivery, AttemptRow attempt, double radiusKm) {}

  private void runMfc(String mfcId, List<DispatchStore.Due> dues, Instant now) {
    OperatingHours hours = props.hours();
    if (!hours.isOpen(now)) {
      Instant open = hours.nextOpen(now);
      dues.forEach(d -> tx.executeWithoutResult(s -> store.postpone(d.attemptId(), open)));
      return;
    }
    List<Ready> ready = new ArrayList<>();
    for (DispatchStore.Due due : dues) {
      Optional<DeliveryRow> d = store.delivery(due.deliveryId());
      Optional<AttemptRow> a = store.openAttempt(due.deliveryId());
      if (d.isEmpty() || a.isEmpty() || !"SEARCHING".equals(a.get().state())) {
        continue;
      }
      DeliveryRow row = d.get();
      if (!row.geoReady()) {
        row = prepareGeo(row, a.get(), now).orElse(null);
        if (row == null) {
          continue;
        }
      }
      ready.add(new Ready(row, a.get(), SearchRules.radiusKm(props.radiiKm(), a.get().stage())));
    }
    if (ready.isEmpty()) {
      return;
    }
    Geo mfc = new Geo(ready.get(0).delivery().mfcLat(), ready.get(0).delivery().mfcLng());

    // 후보 라이더(AI 추천 모드만 제안을 받는다 — 일반 모드는 배달리스트)
    List<Upstreams.Rider> eligible =
        upstreams.eligibleRiders(mfcId, props.maxRadiusKm()).stream()
            .filter(r -> r.acceptsNewDispatch() && "AI_RECOMMENDED".equals(r.dispatchMode()))
            .toList();
    Set<String> busy = store.ridersWithLiveOffer(now);
    List<String> ids =
        eligible.stream().map(Upstreams.Rider::riderId).filter(id -> !busy.contains(id)).toList();
    Set<String> full = new HashSet<>(store.ridersAtOrAbove(ids, props.maxActiveDeliveries()));
    full.addAll(
        store.ridersAtOrAbove(
            eligible.stream()
                .filter(Upstreams.Rider::oneAtATime)
                .map(Upstreams.Rider::riderId)
                .toList(),
            1));
    Map<String, Upstreams.Rider> byId = new HashMap<>();
    eligible.forEach(r -> byId.put(r.riderId(), r));
    List<String> free = ids.stream().filter(id -> !full.contains(id)).toList();

    Map<String, Geo> located = new HashMap<>();
    Map<String, Double> straight = new HashMap<>();
    for (Upstreams.Location l : upstreams.locations(free)) {
      boolean fresh = !l.receivedAt().plus(props.locationMaxAge()).isBefore(now);
      if (fresh && USABLE_QUALITY.contains(l.quality()) && free.contains(l.riderId())) {
        located.put(l.riderId(), l.at());
        straight.put(l.riderId(), l.at().metersTo(mfc));
      }
    }

    // 시도마다 반경 안·이번 회차 미제안 라이더
    List<Set<String>> allowed = new ArrayList<>();
    Set<String> union = new HashSet<>();
    for (Ready r : ready) {
      Set<String> offered = store.offeredInRound(r.attempt().attemptId(), r.attempt().round());
      Set<String> ok = new HashSet<>();
      for (var e : straight.entrySet()) {
        if (e.getValue() <= r.radiusKm() * 1000.0 && !offered.contains(e.getKey())) {
          ok.add(e.getKey());
        }
      }
      allowed.add(ok);
      union.addAll(ok);
    }
    List<String> riders =
        union.stream()
            .sorted(Comparator.comparingDouble(straight::get))
            .limit(30) // 거리 행렬 한 번에 30개까지
            .toList();

    int[][] pickup = new int[riders.size()][ready.size()];
    Upstreams.Leg[] legs = new Upstreams.Leg[riders.size()];
    if (!riders.isEmpty()) {
      Upstreams.Leg[][] m =
          upstreams.matrix(riders.stream().map(located::get).toList(), List.of(mfc));
      for (int i = 0; i < riders.size(); i++) {
        legs[i] = m[i][0];
      }
    }
    int[] readyIn = new int[ready.size()];
    for (int d = 0; d < ready.size(); d++) {
      Instant at = ready.get(d).delivery().readyOrEstimate();
      readyIn[d] = at == null ? 0 : (int) Math.max(0, Duration.between(now, at).toSeconds());
      for (int r = 0; r < riders.size(); r++) {
        pickup[r][d] =
            legs[r].ok() && allowed.get(d).contains(riders.get(r)) ? legs[r].durationS() : -1;
      }
    }
    AssignmentProblem problem = new AssignmentProblem(pickup, readyIn, props.lateWeight());
    AssignmentProblem.Solution sol =
        riders.isEmpty() ? problem.greedy() : solver.solve(problem, props.solverTimeLimit());

    for (int d = 0; d < ready.size(); d++) {
      Ready r = ready.get(d);
      int idx = sol.riderForDelivery()[d];
      if (idx >= 0) {
        String riderId = riders.get(idx);
        offer(r, byId.get(riderId), legs[idx], sol.solver(), now);
      } else {
        noCandidate(r.attempt().attemptId(), r.delivery().deliveryId(), now);
      }
    }
  }

  private void offer(
      Ready r, Upstreams.Rider rider, Upstreams.Leg leg, String solverName, Instant now) {
    tx.executeWithoutResult(
        s -> {
          DeliveryRow d = store.lockDelivery(r.delivery().deliveryId()).orElse(null);
          AttemptRow a = store.lockAttempt(r.attempt().attemptId()).orElse(null);
          if (d == null
              || a == null
              || !"DISPATCHING".equals(d.state())
              || !"SEARCHING".equals(a.state())) {
            return;
          }
          OfferRow o =
              new OfferRow(
                  "OFR-" + UUID.randomUUID(),
                  a.attemptId(),
                  d.deliveryId(),
                  rider.riderId(),
                  a.round(),
                  "AI_RECOMMENDED",
                  r.radiusKm(),
                  "SENT",
                  now,
                  now.plus(props.offerTimeout()),
                  null,
                  null,
                  null,
                  null,
                  leg.distanceM(),
                  leg.durationS(),
                  FeeTable.estimate(rider.vehicleType(), d.dropDistanceM()),
                  rider.vehicleType());
          store.insertOffer(o);
          store.updateAttempt(
              a.attemptId(),
              "OFFERING",
              a.stage(),
              a.stageStartedAt(),
              a.retryCount(),
              a.round(),
              o.expiresAt(),
              null,
              null);
          store.markAttempt(a.attemptId(), true, false, false);
          events.offerSent(d, o);
          log.info(
              "제안 {} 배송 {} → 라이더 {} (단계 {}·반경 {}km, {})",
              o.offerId(),
              d.deliveryId(),
              rider.riderId(),
              a.stage(),
              r.radiusKm(),
              solverName);
        });
  }

  private void noCandidate(String attemptId, String deliveryId, Instant now) {
    tx.executeWithoutResult(
        s -> {
          DeliveryRow d = store.lockDelivery(deliveryId).orElse(null);
          AttemptRow a = store.lockAttempt(attemptId).orElse(null);
          if (d == null || a == null || !"SEARCHING".equals(a.state())) {
            return;
          }
          switch (SearchRules.whenNoCandidate(
              a.stage(),
              a.stageStartedAt(),
              a.retryCount(),
              now,
              props.radiiKm(),
              props.offerTimeout(),
              props.maxRetries())) {
            case STAY -> store.postpone(a.attemptId(), now.plus(props.tickInterval()));
            case NEXT_STAGE ->
                store.updateAttempt(
                    a.attemptId(),
                    "SEARCHING",
                    a.stage() + 1,
                    now,
                    a.retryCount(),
                    a.round(),
                    now,
                    null,
                    null);
            case RETRY -> {
              Instant at = now.plus(props.retryInterval());
              store.updateAttempt(
                  a.attemptId(),
                  "SEARCHING",
                  1,
                  at,
                  a.retryCount() + 1,
                  a.round() + 1,
                  at,
                  null,
                  null);
              log.info(
                  "배송 {} 재요청 {}회 — {} 뒤", deliveryId, a.retryCount() + 1, props.retryInterval());
            }
            case FAIL -> {
              String reason = SearchRules.failureReason(a.hadRejection(), a.hadExpiry());
              transitions.fail(d, a, reason, now);
              log.warn("배송 {} 배차 실패 {}", deliveryId, reason);
            }
          }
        });
  }

  // ---------- 좌표 준비 ----------

  private Optional<DeliveryRow> prepareGeo(DeliveryRow d, AttemptRow a, Instant now) {
    try {
      Upstreams.Destination dest = upstreams.destination(d.orderId());
      if ("CANCELLED".equals(dest.status())) {
        tx.executeWithoutResult(
            s ->
                store
                    .lockDelivery(d.deliveryId())
                    .filter(x -> "DISPATCHING".equals(x.state()))
                    .ifPresent(x -> transitions.block(x, now)));
        log.info("배송 {} 주문 취소됨 — 신규 배차 차단", d.deliveryId());
        return Optional.empty();
      }
      if (dest.roadAddress() == null) {
        return failSystem(d, a, "NO_ADDRESS", now);
      }
      Optional<Geo> mfc = upstreams.centerCoordinates(d.mfcId());
      if (mfc.isEmpty()) {
        return later(d, a, "NO_MFC_COORDINATES", now);
      }
      Optional<Upstreams.Place> place = upstreams.geocode(dest.roadAddress());
      if (place.isEmpty()) {
        return failSystem(d, a, "ADDRESS_NOT_GEOCODED", now);
      }
      Upstreams.Leg leg = upstreams.matrix(List.of(mfc.get()), List.of(place.get().at()))[0][0];
      if (!leg.ok()) {
        return failSystem(d, a, "NO_ROUTE_TO_CUSTOMER", now);
      }
      tx.executeWithoutResult(
          s ->
              store.saveGeo(
                  d.deliveryId(),
                  mfc.get().lat(),
                  mfc.get().lng(),
                  place.get().at().lat(),
                  place.get().at().lng(),
                  place.get().areaLabel(),
                  leg.distanceM(),
                  leg.durationS(),
                  dest.deliveryMode(),
                  dest.arrivalDate(),
                  dest.arrivalStart(),
                  dest.arrivalEnd(),
                  now));
      return store.delivery(d.deliveryId());
    } catch (PmartException e) {
      log.warn("배송 {} 좌표·도로 거리 준비 실패({}) — 30초 뒤 다시", d.deliveryId(), e.code());
      return later(d, a, e.code(), now);
    }
  }

  private Optional<DeliveryRow> later(DeliveryRow d, AttemptRow a, String reason, Instant now) {
    tx.executeWithoutResult(
        s -> {
          store.saveGeoError(d.deliveryId(), reason, now);
          store.postpone(a.attemptId(), now.plus(RETRY_LATER));
        });
    return Optional.empty();
  }

  private Optional<DeliveryRow> failSystem(
      DeliveryRow d, AttemptRow a, String reason, Instant now) {
    tx.executeWithoutResult(
        s -> {
          DeliveryRow x = store.lockDelivery(d.deliveryId()).orElse(null);
          AttemptRow y = store.lockAttempt(a.attemptId()).orElse(null);
          if (x == null || y == null || !"SEARCHING".equals(y.state())) {
            return;
          }
          store.saveGeoError(d.deliveryId(), reason, now);
          transitions.fail(x, y, "SYSTEM_FAILURE", now);
        });
    log.error("배송 {} 배차 불가({}) — SYSTEM_FAILURE", d.deliveryId(), reason);
    return Optional.empty();
  }
}
