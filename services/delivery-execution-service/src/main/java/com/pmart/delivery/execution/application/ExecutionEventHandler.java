package com.pmart.delivery.execution.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.delivery.execution.domain.ExecutionRules;
import com.pmart.delivery.execution.infrastructure.ExecutionStore;
import com.pmart.delivery.execution.infrastructure.ExecutionStore.DeliveryRow;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 구독 사건 → 배송 수행. inbox 트랜잭션 안에서 부른다(외부 호출 없음).
 *
 * <ul>
 *   <li>delivery.delivery.v1(dispatch): {@code DeliveryCreated} → UNASSIGNED, {@code
 *       DeliveryAssigned} → ASSIGNED, {@code DeliveryAssignmentChanged} → 다른 라이더 ASSIGNED / 해제면
 *       UNASSIGNED(픽업 전만), {@code DeliveryCancelledBeforePickup} → CANCELLED(픽업 전만). 배정 버전이 낮은 사건은
 *       버린다.
 *   <li>mfc.fulfillment.v1: {@code ShipmentReady}·{@code PackingFinished} → 출고 포장 목록, {@code
 *       PackageHandedOver} → 인계 사실(같은 handoverId 한 번). 그 뒤 픽업 판정({@link PickupJudgement}).
 * </ul>
 */
@Component
public class ExecutionEventHandler {

  public static final Set<String> DELIVERY_TYPES =
      Set.of(
          "DeliveryCreated",
          "DeliveryAssigned",
          "DeliveryAssignmentChanged",
          "DeliveryCancelledBeforePickup");

  public static final Set<String> FULFILLMENT_TYPES =
      Set.of("ShipmentReady", "PackingFinished", "PackageHandedOver");

  private static final Logger log = LoggerFactory.getLogger(ExecutionEventHandler.class);

  private final ExecutionStore store;
  private final PickupJudgement pickup;
  private final ObjectMapper mapper;
  private final Clock clock;

  public ExecutionEventHandler(
      ExecutionStore store, PickupJudgement pickup, ObjectMapper mapper, Clock clock) {
    this.store = store;
    this.pickup = pickup;
    this.mapper = mapper;
    this.clock = clock;
  }

  public void onDelivery(EventEnvelope e) {
    JsonNode p = e.payload();
    Instant now = clock.instant();
    String deliveryId = p.path("deliveryId").asString();
    store.ensureDelivery(
        deliveryId,
        p.path("type").asString(null),
        p.path("orderId").asString(null),
        p.path("shipmentId").asString(null),
        p.path("mfcId").asString(null),
        now);
    DeliveryRow d = store.lockDelivery(deliveryId).orElseThrow();
    switch (e.eventType()) {
      case "DeliveryCreated" -> {
        List<String> pkgs = strings(p.path("packageIds"));
        if (d.shipmentId() != null
            && !pkgs.isEmpty()
            && store.shipmentPackages(d.shipmentId()).isEmpty()) {
          store.putShipmentPackages(d.shipmentId(), mapper.writeValueAsString(pkgs), now);
        }
      }
      case "DeliveryAssigned" -> {
        int v = p.path("assignmentVersion").asInt();
        if (v <= d.assignmentVersion() || !ExecutionRules.BEFORE_PICKUP.contains(d.state())) {
          log.info(
              "배송 {} 배정 사건 무시 — 배정 버전 {} (지금 {} {})",
              deliveryId,
              v,
              d.assignmentVersion(),
              d.state());
          return;
        }
        Instant at = instant(p.path("acceptedAt"), e.occurredAt());
        store.assign(deliveryId, p.path("riderId").asString(), v, at, now);
        store.history(
            deliveryId, "ASSIGNED", at, "SYSTEM_EVENT", p.path("offerId").asString(null), now);
      }
      case "DeliveryAssignmentChanged" -> {
        int v = p.path("assignmentVersion").asInt();
        if (v <= d.assignmentVersion()) {
          return;
        }
        if (!ExecutionRules.BEFORE_PICKUP.contains(d.state())) {
          log.warn("배송 {} 픽업 뒤 배정 변경 사건 — 수행 상태 {} 유지(지원센터 처리)", deliveryId, d.state());
          return;
        }
        Instant at = instant(p.path("changedAt"), e.occurredAt());
        String newRider = p.path("newRiderId").asString(null);
        if (newRider == null || newRider.isBlank()) {
          store.unassign(deliveryId, v, now);
          store.history(
              deliveryId, "UNASSIGNED", at, "SYSTEM_EVENT", p.path("reason").asString(null), now);
        } else {
          store.assign(deliveryId, newRider, v, at, now);
          store.history(
              deliveryId, "ASSIGNED", at, "SYSTEM_EVENT", p.path("reason").asString(null), now);
        }
      }
      case "DeliveryCancelledBeforePickup" -> {
        if (!ExecutionRules.BEFORE_PICKUP.contains(d.state())) {
          log.warn("배송 {} 픽업 전 취소 사건 — 이미 {}, 그대로 둔다", deliveryId, d.state());
          return;
        }
        store.setState(deliveryId, "CANCELLED", now);
        store.history(
            deliveryId,
            "CANCELLED",
            instant(p.path("cancelledAt"), e.occurredAt()),
            "SYSTEM_EVENT",
            p.path("reason").asString(null),
            now);
        return;
      }
      default -> {
        return;
      }
    }
    pickup.judge(deliveryId, now);
  }

  public void onFulfillment(EventEnvelope e) {
    JsonNode p = e.payload();
    Instant now = clock.instant();
    switch (e.eventType()) {
      case "ShipmentReady", "PackingFinished" -> {
        String shipmentId = p.path("shipmentId").asString();
        List<String> ids = new ArrayList<>();
        for (JsonNode n : p.path("packages")) {
          ids.add(n.path("packageId").asString());
        }
        if (ids.isEmpty()) {
          return;
        }
        store.putShipmentPackages(shipmentId, mapper.writeValueAsString(ids), now);
        for (String deliveryId : store.deliveriesOfShipment(shipmentId)) {
          pickup.judge(deliveryId, now);
        }
      }
      case "PackageHandedOver" -> {
        String deliveryId = p.path("deliveryId").asString(null);
        if (deliveryId == null || deliveryId.isBlank()) {
          return;
        }
        Map<String, Integer> lines = new LinkedHashMap<>();
        for (JsonNode l : p.path("lines")) {
          lines.merge(l.path("orderLineId").asString(), l.path("qty").asInt(), Integer::sum);
        }
        boolean fresh =
            store.recordHandover(
                p.path("handoverId").asString(),
                deliveryId,
                p.path("packageId").asString(),
                p.path("riderId").asString(null),
                mapper.writeValueAsString(lines),
                instant(p.path("handedAt"), e.occurredAt()),
                now);
        if (fresh) {
          pickup.judge(deliveryId, now);
        }
      }
      default -> {}
    }
  }

  private static List<String> strings(JsonNode arr) {
    List<String> out = new ArrayList<>();
    for (JsonNode n : arr) {
      out.add(n.asString());
    }
    return out;
  }

  private static Instant instant(JsonNode n, @Nullable Instant fallback) {
    if (n.isString()) {
      return Instant.parse(n.asString());
    }
    return fallback == null ? Instant.now() : fallback;
  }
}
