package com.pmart.delivery.capacity.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.delivery.capacity.infrastructure.SlotHoldStore;
import com.pmart.delivery.capacity.infrastructure.SlotHoldStore.ConfirmedSlot;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * CP-1 1부(2026-11-16, 결정 2026-11-15 사용자 "추천대로" — 이미 잡힌 주문은 유지하고 지연될 수 있다고 안내, 고객이 원하면 취소·시간대 변경,
 * 자동으로 옮기거나 취소하지 않는다). mfc-capacity {@code CapacityReduced}(작업 창마다 넘친 주문)를 받아, 영향 주문을 고객이 고른 **도착
 * 시간대**별로 묶어 {@code SlotCapacityReduced}(delivery.slot.v1)로 낸다 — notification 이 고객에게 안내한다. 도착 시간대
 * 점유가 없는 주문(바로 배송 등)은 MFC 작업 창 그대로 한 번에(window.meaning WORK). 슬롯 정원·점유는 바꾸지 않는다(새 주문은 mfc-capacity
 * 수용 점유에서 이미 막힌다).
 */
@Component
public class CapacityReductionHandler {

  public static final String TOPIC = "delivery.slot.v1";
  private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
  private static final Logger log = LoggerFactory.getLogger(CapacityReductionHandler.class);

  private final SlotHoldStore store;
  private final EventPublisher events;
  private final EventEnvelopeCodec codec;
  private final Clock clock;

  public CapacityReductionHandler(
      SlotHoldStore store, EventPublisher events, EventEnvelopeCodec codec, Clock clock) {
    this.store = store;
    this.events = events;
    this.codec = codec;
    this.clock = clock;
  }

  /** inbox 트랜잭션 안. */
  public void on(EventEnvelope e) {
    JsonNode p = e.payload();
    String mfcId = p.path("mfcId").asString("");
    Set<String> affected = new LinkedHashSet<>();
    p.path("affectedOrders").forEach(o -> affected.add(o.asString("")));
    affected.remove("");
    if (mfcId.isBlank() || affected.isEmpty()) {
      return;
    }
    Map<String, List<ConfirmedSlot>> byArrival = new LinkedHashMap<>();
    for (ConfirmedSlot s : store.confirmedFor(affected)) {
      byArrival.computeIfAbsent(s.date() + ":" + s.startMinute(), k -> new ArrayList<>()).add(s);
      affected.remove(s.orderId());
    }
    long version = clock.instant().getEpochSecond();
    for (List<ConfirmedSlot> group : byArrival.values()) {
      ConfirmedSlot first = group.get(0);
      Instant start = first.date().atStartOfDay(SEOUL).plusMinutes(first.startMinute()).toInstant();
      Instant end = first.date().atStartOfDay(SEOUL).plusMinutes(first.endMinute()).toInstant();
      publish(
          e,
          mfcId,
          mfcId + ":" + first.date() + ":" + first.startMinute(),
          window(start, end, "ARRIVAL"),
          group.stream().map(ConfirmedSlot::orderId).toList(),
          version);
    }
    if (!affected.isEmpty()) {
      JsonNode w = p.path("window");
      publish(
          e,
          mfcId,
          mfcId + ":work:" + w.path("start").asString(""),
          window(
              Instant.parse(w.path("start").asString()),
              Instant.parse(w.path("end").asString()),
              "WORK"),
          List.copyOf(affected),
          version);
    }
    log.info(
        "MFC {} 작업 여력 축소 → 도착 시간대 {}곳·시간대 없음 {}건 안내 사건", mfcId, byArrival.size(), affected.size());
  }

  private static Map<String, Object> window(Instant start, Instant end, String meaning) {
    Map<String, Object> w = new LinkedHashMap<>();
    w.put("start", start.toString());
    w.put("end", end.toString());
    w.put("meaning", meaning);
    return w;
  }

  private void publish(
      EventEnvelope source,
      String mfcId,
      String aggregateId,
      Map<String, Object> window,
      List<String> orders,
      long version) {
    JsonNode p = source.payload();
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("mfcId", mfcId);
    m.put("window", window);
    if (p.path("oldCapacity").isNumber()) {
      m.put("previousCapacity", Math.max(0, p.path("oldCapacity").asInt()));
    }
    if (p.path("newCapacity").isNumber()) {
      m.put("newCapacity", Math.max(0, p.path("newCapacity").asInt()));
    }
    m.put("affectedOrders", orders);
    m.put("reason", "MFC_CAPACITY_REDUCED");
    m.put("sourceEventId", source.eventId().toString());
    events.publish(
        codec.create(
            events.sourceSystem(),
            "SlotCapacityReduced",
            1,
            "Slot",
            aggregateId,
            version,
            null,
            EventEnvelope.EventSource.REAL,
            m),
        TOPIC);
  }
}
