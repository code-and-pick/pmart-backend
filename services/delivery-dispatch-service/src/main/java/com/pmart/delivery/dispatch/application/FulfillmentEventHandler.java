package com.pmart.delivery.dispatch.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.delivery.dispatch.infrastructure.DispatchStore;
import com.pmart.delivery.dispatch.infrastructure.DispatchStore.DeliveryRow;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * MFC 사건(mfc.fulfillment.v1) → 배송. inbox 트랜잭션 안에서 부른다(외부 호출 없음).
 *
 * <ul>
 *   <li>출고당 배송 하나. **배차는 점원 요청({@code DispatchRequested})으로만 시작한다**(사용자 2026-11-13 "자동요청빼", BR-51 —
 *       mfc-fulfillment 0.4.11 은 READY 자동 요청을 내지 않는다). 요청이 오면 DISPATCHING +
 *       DeliveryCreated(shipmentId — MFC 배정 투영) + 배차 시도(SEARCHING, 바로 판단).
 *   <li>{@code ShipmentReady} 가 요청보다 먼저 오면 배송을 CREATED 로만 둔다(출하 대기·포장 목록 기록, 사건·배차 없음). 점원이 READY
 *       뒤에 요청하면 그때 배차.
 *   <li>{@code PackingFinished} — 포장 목록(결품으로 줄어든 내용) 갱신.
 *   <li>{@code WorkStopResult} — 그 배송이 아는 포장이 모두 STOPPED 면 픽업 전 취소(cancel-vs-handover — 하나라도 인계·인계
 *       중이면 그대로). 포장을 아직 모르면 판정하지 않는다(경고).
 *   <li>{@code PackageHandedOver} — 인계된 포장 기록, 배송의 모든 포장이 인계되면 PICKED_UP(D-6).
 * </ul>
 */
@Component
public class FulfillmentEventHandler {

  public static final Set<String> TYPES =
      Set.of(
          "DispatchRequested",
          "ShipmentReady",
          "PackingFinished",
          "WorkStopResult",
          "PackageHandedOver");

  private static final Logger log = LoggerFactory.getLogger(FulfillmentEventHandler.class);
  private static final Set<String> CLOSED =
      Set.of("PICKED_UP", "CANCELLED_BEFORE_PICKUP", "CLOSED", "BLOCKED");

  private final DispatchStore store;
  private final DispatchEvents events;
  private final DeliveryTransitions transitions;
  private final ObjectMapper mapper;
  private final Clock clock;

  public FulfillmentEventHandler(
      DispatchStore store,
      DispatchEvents events,
      DeliveryTransitions transitions,
      ObjectMapper mapper,
      Clock clock) {
    this.store = store;
    this.events = events;
    this.transitions = transitions;
    this.mapper = mapper;
    this.clock = clock;
  }

  public void on(EventEnvelope e) {
    JsonNode p = e.payload();
    Instant now = clock.instant();
    switch (e.eventType()) {
      case "DispatchRequested" ->
          ensure(
              p.path("shipmentId").asString(),
              p.path("orderId").asString(),
              p.path("mfcId").asString(),
              p.path("trigger").asString(null),
              instant(p.path("estimatedReadyAt")),
              null,
              null,
              null,
              true,
              now);
      case "ShipmentReady" ->
          ensure(
              p.path("shipmentId").asString(),
              p.path("orderId").asString(),
              p.path("mfcId").asString(),
              null,
              null,
              instant(p.path("readyAt")),
              packages(p.path("packages")),
              p.path("deliveryMode").asString(null),
              false,
              now);
      case "PackingFinished" -> {
        String shipmentId = p.path("shipmentId").asString();
        store
            .lockByShipment(shipmentId)
            .ifPresent(
                d -> store.updatePackages(d.deliveryId(), packages(p.path("packages")), now));
      }
      case "WorkStopResult" -> onStop(p, now);
      case "PackageHandedOver" -> onHandover(p, now);
      default -> {}
    }
  }

  private void ensure(
      String shipmentId,
      String orderId,
      String mfcId,
      @Nullable String trigger,
      @Nullable Instant estimatedReadyAt,
      @Nullable Instant readyAt,
      @Nullable String packagesJson,
      @Nullable String deliveryMode,
      boolean requested,
      Instant now) {
    store.lockShipmentKey(shipmentId);
    var existing = store.lockByShipment(shipmentId);
    if (existing.isPresent()) {
      DeliveryRow d = existing.get();
      if (trigger != null || estimatedReadyAt != null) {
        store.updateRequest(d.deliveryId(), trigger, estimatedReadyAt, now);
      }
      if (readyAt != null || packagesJson != null) {
        store.updateReady(
            d.deliveryId(),
            readyAt,
            packagesJson == null ? d.packagesJson() : packagesJson,
            deliveryMode,
            now);
      }
      if (requested && "CREATED".equals(d.state())) {
        store.setState(d.deliveryId(), "DISPATCHING", null, now);
        startDispatch(d.deliveryId(), now);
      }
      return;
    }
    String deliveryId = "DLV-" + UUID.randomUUID();
    String pkgs = packagesJson == null ? "[]" : packagesJson;
    store.insertDelivery(
        deliveryId,
        orderId,
        shipmentId,
        mfcId,
        trigger,
        estimatedReadyAt,
        readyAt,
        pkgs,
        deliveryMode,
        requested ? "DISPATCHING" : "CREATED",
        now);
    if (requested) {
      startDispatch(deliveryId, now);
    } else {
      log.info("배송 {} 기록 — 출고 {} 출하 대기, 점원 배차 요청 대기", deliveryId, shipmentId);
    }
  }

  /** 점원 요청 → 배차 시작: 시도 + DeliveryCreated. */
  private void startDispatch(String deliveryId, Instant now) {
    store.insertAttempt("ATT-" + UUID.randomUUID(), deliveryId, "AUTO", now);
    DeliveryRow d = store.delivery(deliveryId).orElseThrow();
    events.created(d, packageIds(d.packagesJson()));
    log.info(
        "배송 {} 배차 시작 — 출고 {} 주문 {} MFC {}", deliveryId, d.shipmentId(), d.orderId(), d.mfcId());
  }

  private void onStop(JsonNode p, Instant now) {
    Map<String, String> verdicts = new LinkedHashMap<>();
    for (JsonNode pp : p.path("perPackage")) {
      verdicts.put(pp.path("packageId").asString(), pp.path("state").asString(""));
    }
    for (DeliveryRow d : store.lockByOrder(p.path("orderId").asString())) {
      if (CLOSED.contains(d.state())) {
        continue;
      }
      List<String> known = packageIds(d.packagesJson());
      if (known.isEmpty()) {
        log.warn(
            "배송 {} 작업 중지 판정({}) — 포장을 아직 몰라 배차를 그대로 둔다(운영자 확인)",
            d.deliveryId(),
            p.path("stopRequestId").asString(""));
        continue;
      }
      boolean allStopped = known.stream().allMatch(id -> "STOPPED".equals(verdicts.get(id)));
      if (allStopped && "CREATED".equals(d.state())) {
        store.setState(
            d.deliveryId(), "CANCELLED_BEFORE_PICKUP", "ORDER_CANCELLED", now); // 배차 전 — 알릴 배정 없음
      } else if (allStopped) {
        transitions.cancelBeforePickup(d, "ORDER_CANCELLED", now);
        log.info("배송 {} 픽업 전 취소 — 모든 포장 STOPPED", d.deliveryId());
      } else {
        log.info("배송 {} 작업 중지 판정에 인계·인계 중 포장 있음 — 배차 유지", d.deliveryId());
      }
    }
  }

  private void onHandover(JsonNode p, Instant now) {
    String deliveryId = p.path("deliveryId").asString(null);
    if (deliveryId == null) {
      return;
    }
    store
        .lockDelivery(deliveryId)
        .ifPresent(
            d -> {
              Set<String> handed = new LinkedHashSet<>(stringList(d.handedOverJson()));
              handed.add(p.path("packageId").asString());
              List<String> known = packageIds(d.packagesJson());
              boolean all = known.isEmpty() || handed.containsAll(known);
              boolean pickedUp = all && "ASSIGNED".equals(d.state());
              store.recordHandover(
                  deliveryId, mapper.writeValueAsString(new ArrayList<>(handed)), pickedUp, now);
            });
  }

  /** 포장 목록 — 라이더 표시·과적 판단에 필요한 칸만(내용물 줄은 저장하지 않는다). */
  private String packages(JsonNode arr) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (JsonNode n : arr) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("packageId", n.path("packageId").asString());
      m.put("bagCount", n.path("bagCount").asInt(0));
      if (n.path("weightG").isNumber()) {
        m.put("weightG", n.path("weightG").asInt());
      }
      m.put("alcohol", n.path("alcohol").asBoolean(false));
      out.add(m);
    }
    return mapper.writeValueAsString(out);
  }

  List<String> packageIds(String packagesJson) {
    List<String> ids = new ArrayList<>();
    for (JsonNode n : mapper.readTree(packagesJson)) {
      ids.add(n.path("packageId").asString());
    }
    return ids;
  }

  private List<String> stringList(String json) {
    List<String> out = new ArrayList<>();
    for (JsonNode n : mapper.readTree(json)) {
      out.add(n.asString());
    }
    return out;
  }

  private static @Nullable Instant instant(JsonNode n) {
    return n.isString() ? Instant.parse(n.asString()) : null;
  }
}
