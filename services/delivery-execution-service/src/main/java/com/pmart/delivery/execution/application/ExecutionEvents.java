package com.pmart.delivery.execution.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.delivery.execution.infrastructure.ExecutionStore.DeliveryRow;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 배송 수행 사건 발행(토픽 delivery.delivery.v1, 집계 Delivery, 호출자 트랜잭션 안 Outbox). 주소·좌표·사진 원본은 싣지 않는다.
 * aggregateVersion 은 이 서비스의 수행 버전(배차 버전과 다른 흐름).
 */
@Component
public class ExecutionEvents {

  private final EventPublisher events;

  public ExecutionEvents(EventPublisher events) {
    this.events = events;
  }

  public void arrivedAtStore(DeliveryRow before, Instant at) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("deliveryId", before.deliveryId());
    p.put("riderId", before.riderId());
    if (before.mfcId() != null) {
      p.put("mfcId", before.mfcId());
    }
    p.put("arrivedAt", at.toString());
    publish("RiderArrivedAtStore", before, rider(before), p);
  }

  public void pickupCompleted(
      DeliveryRow before, List<String> packageIds, List<String> handoverIds, Instant at) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("deliveryId", before.deliveryId());
    if (before.orderId() != null) {
      p.put("orderId", before.orderId());
    }
    if (before.riderId() != null) {
      p.put("riderId", before.riderId());
    }
    p.put("packageIds", packageIds);
    p.put("handoverIds", handoverIds);
    p.put("pickedUpAt", at.toString());
    publish(
        "PickupCompleted",
        before,
        new EventEnvelope.Actor(EventEnvelope.ActorType.SYSTEM, events.sourceSystem()),
        p);
  }

  public void arrivedAtCustomer(DeliveryRow before, Instant at) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("deliveryId", before.deliveryId());
    p.put("riderId", before.riderId());
    p.put("arrivedAt", at.toString());
    publish("RiderArrivedAtCustomer", before, rider(before), p);
  }

  public void completed(
      DeliveryRow before,
      List<Map<String, Object>> lines,
      String method,
      String evidenceId,
      Instant takenAt,
      Instant at) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("deliveryId", before.deliveryId());
    p.put("orderId", before.orderId());
    p.put("riderId", before.riderId());
    p.put("lines", lines);
    p.put("method", method);
    p.put(
        "evidenceRef",
        Map.of("evidenceId", evidenceId, "kind", "PHOTO", "capturedAt", takenAt.toString()));
    p.put("deliveredAt", at.toString());
    publish("DeliveryCompleted", before, rider(before), p);
  }

  private void publish(
      String type, DeliveryRow before, EventEnvelope.Actor actor, Map<String, Object> payload) {
    events.publish(type, 1, "Delivery", before.deliveryId(), before.version() + 1L, actor, payload);
  }

  private static EventEnvelope.Actor rider(DeliveryRow d) {
    return new EventEnvelope.Actor(EventEnvelope.ActorType.RIDER, String.valueOf(d.riderId()));
  }
}
