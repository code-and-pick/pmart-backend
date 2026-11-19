package com.pmart.delivery.tracking.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.delivery.tracking.infrastructure.DispatchClient;
import com.pmart.delivery.tracking.infrastructure.LocationStore;
import com.pmart.delivery.tracking.infrastructure.LocationStore.ApproachRow;
import com.pmart.delivery.tracking.infrastructure.LocationStore.Latest;
import com.pmart.delivery.tracking.infrastructure.ProgressStore;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * TR-4 곧 도착(2026-11-19 — 사용자 "응 해줘", 300m, 고객 앱 레퍼런스 10-3-4·원문 Delivery 10.3 "일정 거리 안에 도착하면 곧 도착합니다
 * 알림"). 픽업 뒤 감시 중인 배송에서 라이더 마지막 위치(정상 품질·끊기지 않음)가 전달지 좌표 {@code approach-distance-m} 안에 처음 들어오면
 * {@code RiderApproachingCustomer}(delivery.tracking.v1) 를 배송당 한 번. 거리는 직선(카카오 사용량을 쓰지 않음 — 알림 기준일
 * 뿐). 설정이 없으면 내지 않는다(CONFIG_REQUIRED 로그 한 번). 라이더 좌표는 싣지 않는다.
 */
@Component
public class ApproachWatch {

  static final String TOPIC = "delivery.tracking.v1";
  private static final Logger log = LoggerFactory.getLogger(ApproachWatch.class);
  private static final Set<String> USABLE = Set.of("GOOD", "LOW_ACCURACY");

  private final LocationStore store;
  private final ProgressStore progress;
  private final DispatchClient dispatch;
  private final EventPublisher events;
  private final EventEnvelopeCodec codec;
  private final TrackingProperties props;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final @Nullable Integer distanceM;
  private boolean warnedOff;

  public ApproachWatch(
      LocationStore store,
      ProgressStore progress,
      DispatchClient dispatch,
      EventPublisher events,
      EventEnvelopeCodec codec,
      TrackingProperties props,
      TransactionTemplate tx,
      Clock clock,
      @Value("${pmart.tracking.approach-distance-m:#{null}}") @Nullable Integer distanceM) {
    this.store = store;
    this.progress = progress;
    this.dispatch = dispatch;
    this.events = events;
    this.codec = codec;
    this.props = props;
    this.tx = tx;
    this.clock = clock;
    this.distanceM = distanceM;
  }

  /** 두 점 직선 거리(m, 하버사인). */
  static double meters(double lat1, double lng1, double lat2, double lng2) {
    double r = 6_371_000;
    double dLat = Math.toRadians(lat2 - lat1);
    double dLng = Math.toRadians(lng2 - lng1);
    double a =
        Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1))
                * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2)
                * Math.sin(dLng / 2);
    return 2 * r * Math.asin(Math.min(1, Math.sqrt(a)));
  }

  @Scheduled(
      fixedDelayString = "${pmart.tracking.approach-check-interval:PT10S}",
      initialDelayString = "${pmart.tracking.approach-check-initial-delay:PT20S}")
  public void tick() {
    if (distanceM == null || distanceM <= 0) {
      if (!warnedOff) {
        log.info("곧 도착 알림 꺼짐 — pmart.tracking.approach-distance-m 이 없음(TR-4 CONFIG_REQUIRED)");
        warnedOff = true;
      }
      return;
    }
    Instant now = clock.instant();
    for (ApproachRow r : store.approachCandidates(500)) {
      try {
        check(r, now);
      } catch (RuntimeException ex) {
        log.warn("배송 {} 곧 도착 판정 건너뜀: {}", r.deliveryId(), ex.toString());
      }
    }
  }

  private void check(ApproachRow r, Instant now) {
    Double lat = r.dropLat();
    Double lng = r.dropLng();
    if (lat == null || lng == null) {
      var p = dispatch.routePoints(r.deliveryId()).orElse(null);
      if (p == null) {
        return;
      }
      store.saveRoutePoints(
          r.deliveryId(),
          p.pickupLat(),
          p.pickupLng(),
          p.dropLat(),
          p.dropLng(),
          p.pickupToDropS());
      lat = p.dropLat();
      lng = p.dropLng();
    }
    Latest at = store.latest(r.riderId()).orElse(null);
    if (at == null
        || at.receivedAt().isBefore(now.minus(props.staleAfter()))
        || !USABLE.contains(at.quality())) {
      return;
    }
    int d = (int) Math.round(meters(at.lat(), at.lng(), lat, lng));
    if (d > distanceM) {
      return;
    }
    String[] op = progress.orderAndPurpose(r.deliveryId()).orElse(new String[] {null, null});
    tx.executeWithoutResult(
        s -> {
          if (!store.markApproached(r.deliveryId(), now)) {
            return;
          }
          Map<String, Object> p = new LinkedHashMap<>();
          p.put("deliveryId", r.deliveryId());
          if (op[0] != null) {
            p.put("orderId", op[0]);
          }
          p.put("purpose", op[1] == null ? "DELIVERY" : op[1]);
          p.put("distanceM", d);
          p.put("at", now.toString());
          events.publish(
              codec.create(
                  events.sourceSystem(),
                  "RiderApproachingCustomer",
                  1,
                  "Delivery",
                  r.deliveryId(),
                  1L,
                  null,
                  EventEnvelope.EventSource.REAL,
                  p),
              TOPIC);
          log.info("배송 {} 곧 도착 — 전달지까지 {}m", r.deliveryId(), d);
        });
  }
}
