package com.pmart.mfc.fulfillment.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.mfc.fulfillment.application.TaskService.Shortage;
import com.pmart.mfc.fulfillment.infrastructure.TaskStore.PackageRow;
import com.pmart.mfc.fulfillment.infrastructure.TaskStore.TaskInfo;
import com.pmart.mfc.fulfillment.infrastructure.WorkOrderStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 6b 사건 발행(토픽 mfc.fulfillment.v1, 호출자 트랜잭션 안 Outbox). 주문 단위
 * 사건(PreparationStarted·LineShortageReported)은 집계 키 orderId — WorkAccepted·WorkStopResult 와 같은 파티션.
 * 작업은 taskId, 출고는 shipmentId.
 */
@Component
public class FulfillmentEvents {

  private final EventPublisher events;
  private final EventEnvelopeCodec codec;
  private final WorkOrderStore works;
  private final ObjectMapper mapper;

  public FulfillmentEvents(
      EventPublisher events, EventEnvelopeCodec codec, WorkOrderStore works, ObjectMapper mapper) {
    this.events = events;
    this.codec = codec;
    this.works = works;
    this.mapper = mapper;
  }

  public void taskProgressed(
      TaskInfo t,
      String state,
      @Nullable String previousState,
      int version,
      @Nullable String workerId,
      @Nullable Integer qty,
      Instant at) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("taskId", t.taskId());
    p.put("mfcId", t.mfcId());
    p.put("type", t.taskType());
    p.put("state", state);
    if (previousState != null) {
      p.put("previousState", previousState);
    }
    if (qty != null) {
      p.put("qty", qty);
    }
    if (workerId != null) {
      p.put("workerId", workerId);
    }
    if (t.assignmentVersion() > 0) {
      p.put("assignmentVersion", t.assignmentVersion());
    }
    p.put("orderId", t.orderId());
    p.put("progressedAt", at.toString());
    publish("TaskProgressed", "Task", t.taskId(), version, workerId, p);
  }

  public void preparationStarted(TaskInfo t, Instant at) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("orderId", t.orderId());
    p.put("fulfillmentId", t.fulfillmentId());
    p.put("mfcId", t.mfcId());
    p.put("startedAt", at.toString());
    publish("PreparationStarted", "Fulfillment", t.orderId(), orderVersion(t), t.startedBy(), p);
  }

  public void lineShortage(
      TaskInfo t, String orderLineId, String sku, @Nullable String lotId, Shortage s, Instant at) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("orderId", t.orderId());
    p.put("fulfillmentId", t.fulfillmentId());
    p.put("orderLineId", orderLineId);
    p.put("sku", sku);
    if (lotId != null) {
      p.put("lot", lotId);
    }
    p.put("shortQty", s.qty());
    p.put("reason", s.reason());
    if (s.note() != null) {
      p.put("note", s.note());
    }
    p.put("alreadyHandedOverQty", 0);
    // 결품 사가(orchestration, ADR-0045)가 고객 사전 선택·예약으로 다음 단계를 정한다
    p.put("mfcId", t.mfcId());
    p.put("reservationId", t.reservationId());
    p.put("shipmentId", t.shipmentId());
    if (t.outOfStockPreference() != null) {
      p.put("outOfStockPreference", t.outOfStockPreference());
    }
    p.put("reportedAt", at.toString());
    reshipment(t.fulfillmentId(), p);
    publish("LineShortageReported", "Fulfillment", t.orderId(), orderVersion(t), t.startedBy(), p);
  }

  /** 오배송 재발송 작업 지시의 출고면 purpose·caseId 를 더한다(2026-11-16 — 배차가 재발송 배송으로 표시). */
  private void reshipment(@Nullable String fulfillmentId, Map<String, Object> p) {
    if (fulfillmentId == null) {
      return;
    }
    works
        .find(fulfillmentId)
        .filter(WorkOrderStore.WorkOrderRow::reshipment)
        .ifPresent(
            w -> {
              p.put("purpose", "MISDELIVERY_RESHIPMENT");
              p.put("caseId", w.sourceRef());
            });
  }

  /** DispatchRequested(D-5) — 출고 집계, 같은 트랜잭션. */
  public void dispatchRequested(TaskInfo pack, DispatchRequestService.Request r) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("orderId", pack.orderId());
    p.put("fulfillmentId", pack.fulfillmentId());
    p.put("shipmentId", r.shipmentId());
    p.put("mfcId", pack.mfcId());
    p.put("trigger", r.trigger());
    if (r.estimatedPackMinutes() != null) {
      p.put("estimatedPackMinutes", r.estimatedPackMinutes());
    }
    p.put("estimatedReadyAt", r.estimatedReadyAt().toString());
    p.put("requestedAt", r.requestedAt().toString());
    if (r.requestedBy() != null) {
      p.put("requestedBy", r.requestedBy());
    }
    reshipment(pack.fulfillmentId(), p);
    publish("DispatchRequested", "Shipment", r.shipmentId(), 1, r.requestedBy(), p);
  }

  public void packingFinishedAndReady(
      TaskInfo pack, List<PackageRow> sealed, int shipmentVersion, Instant at) {
    List<Map<String, Object>> pkgs = new ArrayList<>();
    for (PackageRow r : sealed) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("packageId", r.packageId());
      m.put("bagCount", r.bagCount());
      if (r.weightG() != null) {
        m.put("weightG", r.weightG());
      }
      if (r.volumeCm3() != null) {
        m.put("volumeCm3", r.volumeCm3());
      }
      m.put("alcohol", r.containsAlcohol());
      m.put("lines", mapper.readTree(r.contents()));
      pkgs.add(m);
    }
    Map<String, Object> finished = new LinkedHashMap<>();
    finished.put("orderId", pack.orderId());
    finished.put("fulfillmentId", pack.fulfillmentId());
    finished.put("shipmentId", pack.shipmentId());
    finished.put("mfcId", pack.mfcId());
    finished.put("packages", pkgs);
    finished.put("finishedAt", at.toString());
    reshipment(pack.fulfillmentId(), finished);
    publish(
        "PackingFinished",
        "Shipment",
        pack.shipmentId(),
        shipmentVersion,
        pack.startedBy(),
        finished);
    Map<String, Object> ready = new LinkedHashMap<>();
    ready.put("orderId", pack.orderId());
    ready.put("fulfillmentId", pack.fulfillmentId());
    ready.put("shipmentId", pack.shipmentId());
    ready.put("mfcId", pack.mfcId());
    ready.put("readyAt", at.toString());
    ready.put("packages", pkgs);
    reshipment(pack.fulfillmentId(), ready);
    publish(
        "ShipmentReady", "Shipment", pack.shipmentId(), shipmentVersion, pack.startedBy(), ready);
  }

  /** 포장 인계 확정(PackageHandedOver, 집계 키 orderId). handoverId 가 정확히 한 번 키. */
  public void packageHandedOver(
      com.pmart.mfc.fulfillment.infrastructure.HandoverStore.PackageFence p,
      com.pmart.mfc.fulfillment.infrastructure.HandoverStore.Handover h,
      String commitId,
      List<Map<String, Object>> lines,
      Instant at) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("orderId", p.orderId());
    m.put("fulfillmentId", p.fulfillmentId());
    m.put("shipmentId", p.shipmentId());
    m.put("deliveryId", h.deliveryId());
    m.put("packageId", p.packageId());
    m.put("mfcId", p.mfcId());
    m.put("riderId", h.riderId());
    m.put("handoverId", h.handoverId());
    m.put("inventoryCommitId", commitId);
    m.put("lines", lines);
    m.put("handedAt", at.toString());
    EventEnvelope.Actor actor = new EventEnvelope.Actor(EventEnvelope.ActorType.RIDER, h.riderId());
    events.publish(
        codec.create(
            events.sourceSystem(),
            "PackageHandedOver",
            1,
            "Package",
            p.packageId(),
            p.version() + 1,
            actor,
            EventEnvelope.EventSource.REAL,
            m),
        WorkStopService.TOPIC);
  }

  /** 재포장으로 이전 포장 라벨 무효(PackageInvalidated, 집계 Package = 이전 포장). */
  public void packageInvalidated(
      com.pmart.mfc.fulfillment.infrastructure.HandoverStore.PackageFence p,
      String newPackageId,
      String reason,
      String actorId,
      Instant at) {
    packageInvalidated(p, List.of(newPackageId), reason, actorId, at);
  }

  /** 재구성(C-12 1부) — 새 포장이 여럿일 수 있다(newPackageId = 첫 번째, newPackageIds = 전부). */
  public void packageInvalidated(
      com.pmart.mfc.fulfillment.infrastructure.HandoverStore.PackageFence p,
      List<String> newPackageIds,
      String reason,
      String actorId,
      Instant at) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("packageId", p.packageId());
    m.put("newPackageId", newPackageIds.get(0));
    if (newPackageIds.size() > 1) {
      m.put("newPackageIds", newPackageIds);
    }
    m.put("shipmentId", p.shipmentId());
    m.put("orderId", p.orderId());
    m.put("mfcId", p.mfcId());
    m.put("reason", reason);
    m.put("invalidatedAt", at.toString());
    events.publish(
        codec.create(
            events.sourceSystem(),
            "PackageInvalidated",
            1,
            "Package",
            p.packageId(),
            p.version() + 1,
            new EventEnvelope.Actor(EventEnvelope.ActorType.OPERATOR, actorId),
            EventEnvelope.EventSource.REAL,
            m),
        WorkStopService.TOPIC);
  }

  private int orderVersion(TaskInfo t) {
    return works.find(t.fulfillmentId()).map(WorkOrderStore.WorkOrderRow::version).orElse(1);
  }

  private void publish(
      String type,
      String aggregateType,
      String aggregateId,
      long version,
      @Nullable String operator,
      Map<String, Object> payload) {
    EventEnvelope.Actor actor =
        operator == null
            ? new EventEnvelope.Actor(EventEnvelope.ActorType.SYSTEM, events.sourceSystem())
            : new EventEnvelope.Actor(EventEnvelope.ActorType.OPERATOR, operator);
    events.publish(
        codec.create(
            events.sourceSystem(),
            type,
            1,
            aggregateType,
            aggregateId,
            version,
            actor,
            EventEnvelope.EventSource.REAL,
            payload),
        WorkStopService.TOPIC);
  }
}
