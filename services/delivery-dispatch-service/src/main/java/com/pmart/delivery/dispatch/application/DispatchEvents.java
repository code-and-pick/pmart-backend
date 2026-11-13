package com.pmart.delivery.dispatch.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.delivery.dispatch.infrastructure.DispatchStore.DeliveryRow;
import com.pmart.delivery.dispatch.infrastructure.DispatchStore.OfferRow;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * 배송 사건 발행(토픽 delivery.delivery.v1, 집계 Delivery, 호출자 트랜잭션 안 Outbox). mfc-fulfillment 가 이 항목 이름 그대로
 * 읽는다(kickoff §4) — 주소 원문·좌표는 싣지 않는다.
 */
@Component
public class DispatchEvents {

  private final EventPublisher events;

  public DispatchEvents(EventPublisher events) {
    this.events = events;
  }

  public void created(DeliveryRow d, List<String> packageIds) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("deliveryId", d.deliveryId());
    p.put("orderId", d.orderId());
    p.put("shipmentId", d.shipmentId());
    p.put("type", d.type());
    p.put("mfcId", d.mfcId());
    p.put("dropAddressRef", "order:" + d.orderId()); // 원주문 주소 참조(주소 원문 없음)
    if (d.deliveryMode() != null) {
      p.put("deliveryMode", d.deliveryMode());
    }
    if (!packageIds.isEmpty()) {
      p.put("packageIds", packageIds);
    }
    p.put("createdAt", d.createdAt().toString());
    publish("DeliveryCreated", d, d.version(), system(), p);
  }

  public void offerSent(DeliveryRow d, OfferRow o) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("offerId", o.offerId());
    p.put("attemptId", o.attemptId());
    p.put("deliveryId", d.deliveryId());
    p.put("riderId", o.riderId());
    p.put("expiresAt", o.expiresAt().toString());
    p.put("fee", o.fee());
    p.put("sentAt", o.sentAt().toString());
    publish("DispatchOfferSent", d, d.version(), system(), p);
  }

  public void assigned(
      DeliveryRow before, String riderId, String offerId, int assignmentVersion, Instant at) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("deliveryId", before.deliveryId());
    p.put("orderId", before.orderId());
    p.put("riderId", riderId);
    p.put("offerId", offerId);
    p.put("assignmentVersion", assignmentVersion);
    p.put("acceptedAt", at.toString());
    publish(
        "DeliveryAssigned",
        before,
        before.version() + 1L,
        new EventEnvelope.Actor(EventEnvelope.ActorType.RIDER, riderId),
        p);
  }

  public void cancelledBeforePickup(DeliveryRow before, String reason, Instant at) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("deliveryId", before.deliveryId());
    p.put("orderId", before.orderId());
    if (before.riderId() != null) {
      p.put("riderId", before.riderId());
    }
    p.put("assignmentVersion", before.assignmentVersion());
    p.put("reason", reason);
    p.put("cancelledAt", at.toString());
    publish("DeliveryCancelledBeforePickup", before, before.version() + 1L, system(), p);
  }

  /** 재배정·배정 해제(newRiderId null = 해제). MFC 는 이것으로 이전 라이더 인계를 막는다(D-4·kickoff §4). */
  public void assignmentChanged(
      DeliveryRow before,
      @Nullable String newRiderId,
      int assignmentVersion,
      String reason,
      EventEnvelope.@Nullable Actor actor,
      Instant at) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("deliveryId", before.deliveryId());
    p.put("oldRiderId", before.riderId());
    p.put("newRiderId", newRiderId);
    p.put("assignmentVersion", assignmentVersion);
    p.put("reason", reason);
    p.put("changedAt", at.toString());
    publish(
        "DeliveryAssignmentChanged",
        before,
        before.version() + 1L,
        actor == null ? system() : actor,
        p);
  }

  /** 배차 지연 임계 초과(원문 §3.6 — MFC 관리자·고객센터 안내). */
  public void delayed(DeliveryRow d, int delayedMinutes, int thresholdMinutes, Instant at) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("deliveryId", d.deliveryId());
    p.put("orderId", d.orderId());
    p.put("delayedMinutes", delayedMinutes);
    p.put("threshold", thresholdMinutes);
    p.put("detectedAt", at.toString());
    publish("DispatchDelayed", d, d.version(), system(), p);
  }

  public void failed(DeliveryRow before, String reason, int attempts, Instant at) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("deliveryId", before.deliveryId());
    p.put("orderId", before.orderId());
    p.put("reason", reason);
    p.put("attempts", attempts);
    p.put("failedAt", at.toString());
    publish("DispatchFailed", before, before.version() + 1L, system(), p);
  }

  private void publish(
      String type,
      DeliveryRow d,
      long version,
      EventEnvelope.@Nullable Actor actor,
      Map<String, Object> payload) {
    events.publish(type, 1, "Delivery", d.deliveryId(), version, actor, payload);
  }

  private EventEnvelope.Actor system() {
    return new EventEnvelope.Actor(EventEnvelope.ActorType.SYSTEM, events.sourceSystem());
  }
}
