package com.pmart.delivery.tracking.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.delivery.tracking.infrastructure.DispatchClient;
import com.pmart.delivery.tracking.infrastructure.LocationStore;
import com.pmart.delivery.tracking.infrastructure.LocationStore.EtaRow;
import com.pmart.delivery.tracking.infrastructure.LocationStore.Latest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 라이더 ETA(TR-1, 2026-11-16 — 사용자 "진행해"). 진행 중 배송을 {@code pmart.tracking.eta.interval} 마다 다시 계산해,
 * 직전에 낸 도착 예정과 {@code min-change} 넘게 다르면(처음은 항상) {@code DeliveryEtaUpdated}(delivery.tracking.v1)를
 * 낸다.
 *
 * <ul>
 *   <li>픽업 전: 라이더 → 픽업지(카카오 길찾기) = etaPickup, 그 뒤 + 픽업지 → 도착지(배차가 잰 도로 시간) = etaDrop. basis {@code
 *       RIDER_TO_PICKUP+PICKUP_TO_DROP}. 픽업지 머무는 시간은 더하지 않는다(정한 값 없음).
 *   <li>픽업 뒤: 라이더 → 도착지 = etaDrop. basis {@code RIDER_TO_DROP}. 묶음 배송도 각 도착지로 바로 가는 시간(방문 순서 반영 안
 *       함).
 *   <li>라이더 위치가 {@code stale-after}(3분)보다 오래됐거나 시계가 어긋난 점이면 계산하지 않는다(끊김은 RiderLocationStale). 길찾기
 *       실패·사용량 초과면 그 회차는 건너뛴다(직선거리로 대신하지 않는다 — ADR-0058).
 *   <li>값이 없으면 꺼져 있다(카카오 사용량).
 * </ul>
 */
@Component
public class EtaService {

  public static final String TOPIC = RiderWatch.TOPIC;

  private static final Logger log = LoggerFactory.getLogger(EtaService.class);

  private static final Set<String> USABLE = Set.of("GOOD", "LOW_ACCURACY");

  private final LocationStore store;
  private final DispatchClient dispatch;
  private final RoutePlanner routes;
  private final EventPublisher events;
  private final EventEnvelopeCodec codec;
  private final TrackingProperties props;
  private final TransactionTemplate tx;
  private final Clock clock;
  private boolean warnedOff;

  public EtaService(
      LocationStore store,
      DispatchClient dispatch,
      RoutePlanner routes,
      EventPublisher events,
      EventEnvelopeCodec codec,
      TrackingProperties props,
      TransactionTemplate tx,
      Clock clock) {
    this.store = store;
    this.dispatch = dispatch;
    this.routes = routes;
    this.events = events;
    this.codec = codec;
    this.props = props;
    this.tx = tx;
    this.clock = clock;
  }

  @Scheduled(
      initialDelayString = "${pmart.tracking.eta-check-interval:PT15S}",
      fixedDelayString = "${pmart.tracking.eta-check-interval:PT15S}")
  public void tick() {
    TrackingProperties.Eta eta = props.eta();
    if (!eta.enabled()) {
      if (!warnedOff) {
        log.info("라이더 ETA 꺼짐 — pmart.tracking.eta.interval·min-change 가 없음(TR-1)");
        warnedOff = true;
      }
      return;
    }
    Instant now = clock.instant();
    Map<String, List<EtaRow>> byRider =
        store.etaCandidates(now.minus(eta.interval()), 200).stream()
            .collect(
                Collectors.groupingBy(EtaRow::riderId, LinkedHashMap::new, Collectors.toList()));
    for (var e : byRider.entrySet()) {
      try {
        rider(e.getKey(), e.getValue(), eta.minChange(), now);
      } catch (RuntimeException ex) {
        log.warn("라이더 {} ETA 계산 건너뜀: {}", e.getKey(), ex.toString());
        e.getValue().forEach(r -> store.etaChecked(r.deliveryId(), now));
      }
    }
  }

  private void rider(String riderId, List<EtaRow> rows, Duration minChange, Instant now) {
    Latest at = store.latest(riderId).orElse(null);
    if (at == null
        || at.receivedAt().isBefore(now.minus(props.staleAfter()))
        || !USABLE.contains(at.quality())) {
      rows.forEach(r -> store.etaChecked(r.deliveryId(), now)); // 위치 확인 중 — 내지 않는다
      return;
    }
    List<EtaRow> ready = new ArrayList<>();
    for (EtaRow r : rows) {
      EtaRow x = withPoints(r);
      if (x == null) {
        store.etaChecked(r.deliveryId(), now);
      } else {
        ready.add(x);
      }
    }
    if (ready.isEmpty()) {
      return;
    }
    List<RoutePlanner.Point> targets =
        ready.stream()
            .map(
                r ->
                    r.pickedUpAt() != null
                        ? new RoutePlanner.Point(r.dropLat(), r.dropLng())
                        : new RoutePlanner.Point(r.pickupLat(), r.pickupLng()))
            .toList();
    List<RoutePlanner.Leg> legs =
        routes.fromOne(new RoutePlanner.Point(at.lat(), at.lng()), targets);
    long staleness = Math.max(0, Duration.between(at.receivedAt(), now).toSeconds());
    for (int i = 0; i < ready.size(); i++) {
      EtaRow r = ready.get(i);
      RoutePlanner.Leg leg = legs.get(i);
      if (!leg.ok() || (r.pickedUpAt() == null && r.pickupToDropS() == null)) {
        store.etaChecked(r.deliveryId(), now);
        continue;
      }
      Instant etaPickup = r.pickedUpAt() == null ? now.plusSeconds(leg.durationSeconds()) : null;
      Instant etaDrop =
          etaPickup != null
              ? etaPickup.plusSeconds(r.pickupToDropS())
              : now.plusSeconds(leg.durationSeconds());
      if (r.etaDrop() != null
          && Duration.between(r.etaDrop(), etaDrop).abs().compareTo(minChange) <= 0) {
        store.etaChecked(r.deliveryId(), now);
        continue;
      }
      publish(r, etaPickup, etaDrop, staleness, now);
    }
  }

  /** 좌표가 없으면 배차에서 읽어 둔다. 아직 없으면 null. */
  private EtaRow withPoints(EtaRow r) {
    if (r.pickupLat() != null && r.dropLat() != null) {
      return r;
    }
    var p = dispatch.routePoints(r.deliveryId()).orElse(null);
    if (p == null) {
      return null;
    }
    store.saveRoutePoints(
        r.deliveryId(), p.pickupLat(), p.pickupLng(), p.dropLat(), p.dropLng(), p.pickupToDropS());
    return new EtaRow(
        r.deliveryId(),
        r.riderId(),
        r.pickedUpAt(),
        p.pickupLat(),
        p.pickupLng(),
        p.dropLat(),
        p.dropLng(),
        p.pickupToDropS(),
        r.etaDrop());
  }

  private void publish(
      EtaRow r, @Nullable Instant etaPickup, Instant etaDrop, long staleness, Instant now) {
    tx.executeWithoutResult(
        s -> {
          Integer version = store.etaPublished(r.deliveryId(), etaPickup, etaDrop, now);
          if (version == null) {
            return; // 그 사이 끝난 배송
          }
          Map<String, Object> p = new LinkedHashMap<>();
          p.put("deliveryId", r.deliveryId());
          if (etaPickup != null) {
            p.put("etaPickup", etaPickup.toString());
          }
          p.put("etaDrop", etaDrop.toString());
          p.put("basis", etaPickup != null ? "RIDER_TO_PICKUP+PICKUP_TO_DROP" : "RIDER_TO_DROP");
          p.put("staleness", staleness);
          p.put("computedAt", now.toString());
          events.publish(
              codec.create(
                  events.sourceSystem(),
                  "DeliveryEtaUpdated",
                  1,
                  "Delivery",
                  r.deliveryId(),
                  version.longValue(),
                  null,
                  EventEnvelope.EventSource.REAL,
                  p),
              TOPIC);
        });
  }
}
