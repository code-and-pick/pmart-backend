package com.pmart.delivery.tracking.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.delivery.tracking.infrastructure.LocationStore;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * 위치 끊김 감지·위치 보존(2026-11-14, 사용자 결정).
 *
 * <ul>
 *   <li><b>진행 중 배송</b>: delivery.delivery.v1 — dispatch 배정·배정 변경·픽업 전 취소, execution 완료·실패. 배정부터
 *       완료·실패 확정 전까지(픽업 전 포함 — 가게로 가는 길도 본다).
 *   <li><b>끊김</b>: 진행 중 배송이 있는 라이더의 마지막 수신(품질과 무관)이 {@code stale-after}(3분, "배달 중 3분") 넘게 없으면
 *       {@code RiderLocationStale}(delivery.tracking.v1) — 끊김 한 번에 한 번. 다시 점이 오면 다음 끊김에 또 낸다. 기준 =
 *       마지막 수신과 배정 시각(사건에 적힌 acceptedAt·changedAt) 중 늦은 것. 판정은 알림만 — 배정·상태는 바꾸지 않는다(dispatch·지원센터가
 *       본다).
 *   <li><b>감시 끝</b>: 완료·실패·픽업 전 취소 사건, 또는 배정 뒤 {@code watch-max-age}(5시간, 사용자 "5시간이 지나면 감시 종료"). 배정
 *       해제는 RELEASED 로 남겨 늦게 온 옛 배정 사건이 다시 열지 않게 한다.
 *   <li><b>보존</b>: 위치 점은 서버 수신 뒤 {@code retention}(30일, 사용자 "보존기간은 30일") 지나면 지운다. 그동안 새 위치가 없는 라이더의
 *       마지막 위치·닫힌 배송 기록도.
 * </ul>
 */
@Component
public class RiderWatch {

  public static final Set<String> TYPES =
      Set.of(
          "DeliveryAssigned",
          "DeliveryAssignmentChanged",
          "DeliveryCancelledBeforePickup",
          "DeliveryCompleted",
          "DeliveryFailed",
          "ReturnPickupFailed", // 고객 반품 회수 실패(CL-1, 2026-11-17) — 감시 끝
          "ReturnDelivered", // 회수 배송 반납(DD-2, 2026-11-16) — 회수 라이더 감시 끝
          "PickupCompleted", // 픽업(TR-1 ETA — 이제 라이더 → 도착지)
          "ReturnPickedUp"); // 회수지·잘못 둔 곳에서 받음(TR-1)
  public static final String TOPIC = "delivery.tracking.v1";

  private static final Logger log = LoggerFactory.getLogger(RiderWatch.class);

  private final LocationStore store;
  private final EventPublisher events;
  private final EventEnvelopeCodec codec;
  private final TrackingProperties props;
  private final TransactionTemplate tx;
  private final Clock clock;

  public RiderWatch(
      LocationStore store,
      EventPublisher events,
      EventEnvelopeCodec codec,
      TrackingProperties props,
      TransactionTemplate tx,
      Clock clock) {
    this.store = store;
    this.events = events;
    this.codec = codec;
    this.props = props;
    this.tx = tx;
    this.clock = clock;
  }

  /** inbox 트랜잭션 안. */
  public void on(EventEnvelope e) {
    JsonNode p = e.payload();
    String deliveryId = p.path("deliveryId").asString("");
    if (deliveryId.isBlank()) {
      return;
    }
    Instant now = clock.instant();
    switch (e.eventType()) {
      case "DeliveryAssigned" -> {
        String rider = p.path("riderId").asString("");
        if (!rider.isBlank()) {
          store.openDelivery(
              deliveryId,
              rider,
              p.path("assignmentVersion").asInt(0),
              eventTime(p, "acceptedAt", e.occurredAt(), now),
              now);
        }
      }
      case "DeliveryAssignmentChanged" -> {
        int version = p.path("assignmentVersion").asInt(0);
        String rider = p.path("newRiderId").asString("");
        if (rider.isBlank()) {
          store.releaseDelivery(deliveryId, version, now);
        } else {
          store.openDelivery(
              deliveryId, rider, version, eventTime(p, "changedAt", e.occurredAt(), now), now);
        }
      }
      case "PickupCompleted", "ReturnPickedUp" ->
          store.markPickedUp(deliveryId, eventTime(p, "pickedUpAt", e.occurredAt(), now));
      default -> store.closeDelivery(deliveryId, now); // 픽업 전 취소·완료·실패·회수 반납
    }
  }

  /**
   * 감시 시작 = 사건에 적힌 배정 시각(소비한 시각이 아니다 — 옛 사건을 다시 읽어도 지금 배정된 것처럼 되지 않게, PC 1 PR #15 검토). 없으면 사건 시각,
   * 서버보다 앞서면 지금.
   */
  private static Instant eventTime(JsonNode p, String field, Instant occurredAt, Instant now) {
    Instant at = occurredAt;
    String s = p.path(field).asString("");
    if (!s.isBlank()) {
      try {
        at = Instant.parse(s);
      } catch (java.time.format.DateTimeParseException ignored) {
        // 사건 시각으로
      }
    }
    return at == null || at.isAfter(now) ? now : at;
  }

  @Scheduled(
      initialDelayString = "${pmart.tracking.stale-check-interval:PT30S}",
      fixedDelayString = "${pmart.tracking.stale-check-interval:PT30S}")
  public void checkStale() {
    Instant now = clock.instant();
    // 배정 뒤 watch-max-age(5시간) 지난 감시는 끝 사건이 없어도 끝낸다 — 끊김 판정보다 먼저(사용자 2026-11-14)
    int expired = tx.execute(s -> store.expireWatches(now.minus(props.watchMaxAge()), now));
    if (expired > 0) {
      log.info("배정 뒤 {} 지난 위치 감시 {}건 끝냄(끝 사건 없음)", props.watchMaxAge(), expired);
    }
    for (LocationStore.Watch w : store.staleCandidates(now.minus(props.staleAfter()), 200)) {
      tx.executeWithoutResult(
          s -> {
            Instant base = w.base();
            // 알림 횟수를 올리면서 읽는다 — 사건 버전(aggregateVersion)이 겹치지 않게(PC 1 PR #15 검토)
            Integer count = store.markAlerted(w.riderId(), base);
            if (count == null) {
              return;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("riderId", w.riderId());
            payload.put("deliveryId", w.deliveryId());
            payload.put("lastSeenAt", base.toString());
            payload.put("detectedAt", now.toString());
            events.publish(
                codec.create(
                    events.sourceSystem(),
                    "RiderLocationStale",
                    1,
                    "Rider",
                    w.riderId(),
                    count.longValue(),
                    null,
                    EventEnvelope.EventSource.REAL,
                    payload),
                TOPIC);
            log.warn(
                "라이더 {} 위치 끊김 — 마지막 {} (배송 {}, 기준 {})",
                w.riderId(),
                base,
                w.deliveryId(),
                props.staleAfter());
          });
    }
  }

  @Scheduled(
      initialDelayString = "${pmart.tracking.purge-interval:PT1H}",
      fixedDelayString = "${pmart.tracking.purge-interval:PT1H}")
  public void purge() {
    Instant before = clock.instant().minus(props.retention());
    int total = 0;
    int n;
    do {
      n = tx.execute(s -> store.purgePoints(before, 5000));
      total += n;
    } while (n == 5000);
    int other = tx.execute(s -> store.purgeStale(before));
    if (total + other > 0) {
      log.info("위치 보존 기간({}) 지난 기록 지움 — 점 {}건, 그 밖 {}건", props.retention(), total, other);
    }
  }
}
