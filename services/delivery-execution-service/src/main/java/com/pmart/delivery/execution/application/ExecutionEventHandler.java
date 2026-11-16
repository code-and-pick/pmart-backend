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
  private final EarningService earnings;
  private final ObjectMapper mapper;
  private final Clock clock;

  public ExecutionEventHandler(
      ExecutionStore store,
      PickupJudgement pickup,
      EarningService earnings,
      ObjectMapper mapper,
      Clock clock) {
    this.store = store;
    this.pickup = pickup;
    this.earnings = earnings;
    this.mapper = mapper;
    this.clock = clock;
  }

  /**
   * case 오배송 확인(platform.case.v1 MisdeliveryConfirmed, ADR-0065): 귀책 RIDER 면 원래 배송 수행료
   * 미지급(SYSTEM·CUSTOMER 는 지급). inbox 트랜잭션 안.
   */
  public void onCase(EventEnvelope e) {
    JsonNode p = e.payload();
    if (!"RIDER".equals(p.path("fault").asString(""))) {
      return;
    }
    String deliveryId = p.path("deliveryId").asString("");
    DeliveryRow d = store.delivery(deliveryId).orElse(null);
    if (d == null || d.riderId() == null) {
      log.warn("라이더 귀책 오배송 {} — 배송 {} 의 라이더를 몰라 수행료를 바꾸지 않음", e.eventId(), deliveryId);
      return;
    }
    earnings.withholdForMisdelivery(
        deliveryId, d.riderId(), p.path("caseId").asString(""), clock.instant());
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
        // 유형·목적(오배송 회수 — DD-2). 배정 사건이 먼저 와 FORWARD 로 만들어졌어도 여기서 바로잡는다
        store.describe(
            deliveryId,
            p.path("type").asString(null),
            p.path("purpose").asString(null),
            p.path("caseId").asString(null),
            p.path("originDeliveryId").asString(null));
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
        // 수락 당시 금액·조건(원문 §7.1) — 수행료 기준(2026-11-13)
        store.accepted(
            deliveryId,
            p.path("fee").isNumber() ? p.path("fee").asInt() : null,
            p.path("vehicleType").asString(null),
            p.path("distanceM").isNumber() ? p.path("distanceM").asInt() : null);
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
        if (d.riderId() != null && !byRider(e)) {
          earnings.notCalculable(d, "UNASSIGNED", now); // 운영자·주문 사유 배차 취소 — 기준 없음(본인 취소는 수행료 없음)
        }
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
        if (d.riderId() != null && !"UNASSIGNED".equals(d.state()) && !byRider(e)) {
          earnings.notCalculable(d, "CANCELLED_BEFORE_PICKUP", now);
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

  /** 라이더 본인이 낸 배차 취소인가(사건 행위자) — 본인 취소는 수행료를 만들지 않는다. */
  private static boolean byRider(EventEnvelope e) {
    return e.actor() != null && e.actor().type() == EventEnvelope.ActorType.RIDER;
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
