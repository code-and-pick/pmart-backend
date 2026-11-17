package com.pmart.delivery.execution.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.delivery.execution.infrastructure.ExecutionStore;
import com.pmart.delivery.execution.infrastructure.ExecutionStore.DeliveryRow;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 배송 수행 사건 발행(토픽 delivery.delivery.v1, 집계 Delivery, 호출자 트랜잭션 안 Outbox). 주소·좌표·사진 원본은 싣지 않는다.
 * aggregateVersion 은 이 서비스의 수행 버전 — 같은 Delivery 의 배차 사건과 따로 오른다(발행 서비스마다, events.md §7 머리말
 * 2026-11-14).
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
    if (before.purpose() != null) {
      p.put("purpose", before.purpose()); // 오배송 다시 배송이면 order 가 수량을 다시 세지 않는다(ADR-0065)
    }
    publish("DeliveryCompleted", before, rider(before), p);
  }

  /**
   * 회수지에서 회수품을 받음(DD-2 오배송 회수 — ReturnPickedUp). lines = 원래 배송이 전달한 줄·수량(회수 대상), 증빙은 사진 참조 묶음 ID 만.
   */
  public void returnPickedUp(
      DeliveryRow before, List<Map<String, Object>> lines, String evidenceId, Instant at) {
    Map<String, Object> p = returnPayload(before, lines, evidenceId, at);
    p.put("riderId", before.riderId());
    p.put("pickedUpAt", at.toString());
    publish("ReturnPickedUp", before, rider(before), p);
  }

  /** MFC 반납(DD-2 — mfc-inbound 점원 수령 확인 전까지 MFC 도착 = 반납, 사용자 2026-11-16 "추천대로"). 배송 끝. */
  public void returnDelivered(
      DeliveryRow before, List<Map<String, Object>> lines, String evidenceId, Instant at) {
    Map<String, Object> p = returnPayload(before, lines, evidenceId, at);
    p.put("deliveredAt", at.toString());
    publish("ReturnDelivered", before, rider(before), p);
  }

  private static Map<String, Object> returnPayload(
      DeliveryRow d, List<Map<String, Object>> lines, String evidenceId, Instant at) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("returnPickupId", d.deliveryId()); // 회수 배송 하나에 회수 하나
    p.put("deliveryId", d.deliveryId());
    if (d.orderId() != null) {
      p.put("orderId", d.orderId());
    }
    if (d.mfcId() != null) {
      p.put("mfcId", d.mfcId());
    }
    p.put("purpose", d.purpose());
    if (d.claimId() != null) {
      p.put("claimId", d.claimId()); // 고객 반품 회수(CL-1)
    }
    if (d.caseId() != null) {
      p.put("caseId", d.caseId());
    }
    if (d.originDeliveryId() != null) {
      p.put("originDeliveryId", d.originDeliveryId());
    }
    p.put("lines", lines);
    p.put(
        "evidenceRef",
        Map.of("evidenceId", evidenceId, "kind", "PHOTO", "capturedAt", at.toString()));
    return p;
  }

  /**
   * 고객 반품 회수 실패 확정(CL-1, 2026-11-17 — 사용자 "실패마다 알림, 다시 시도는 반품 쪽"). 회수 배송 끝, 상품은 고객에게 그대로. 연락 번호·내용은
   * 싣지 않고 횟수만.
   */
  public void returnPickupFailed(
      DeliveryRow before,
      String reason,
      boolean customerReason,
      int contactAttempts,
      @org.jspecify.annotations.Nullable String reportedAt,
      String operatorId,
      Instant at) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("deliveryId", before.deliveryId());
    p.put("claimId", before.claimId());
    if (before.orderId() != null) {
      p.put("orderId", before.orderId());
    }
    if (before.mfcId() != null) {
      p.put("mfcId", before.mfcId());
    }
    if (before.riderId() != null) {
      p.put("riderId", before.riderId());
    }
    p.put("reason", reason);
    p.put("customerReason", customerReason);
    p.put("contactAttempts", contactAttempts);
    if (reportedAt != null) {
      p.put("reportedAt", reportedAt);
    }
    p.put("failedAt", at.toString());
    publish(
        "ReturnPickupFailed",
        before,
        new EventEnvelope.Actor(EventEnvelope.ActorType.OPERATOR, operatorId),
        p);
  }

  /** 운영자가 실패를 확정(2026-11-13) — 배송 종료. 회수 배차는 새 배송(claim·case 판단). */
  public void failed(
      DeliveryRow before,
      String reason,
      String custody,
      @org.jspecify.annotations.Nullable String goodsLocation,
      String returnBy,
      String operatorId,
      Instant at) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("deliveryId", before.deliveryId());
    if (before.orderId() != null) {
      p.put("orderId", before.orderId());
    }
    if (before.riderId() != null) {
      p.put("riderId", before.riderId());
    }
    p.put("reason", reason);
    p.put("custody", custody);
    if (goodsLocation != null) {
      p.put("goodsLocation", goodsLocation);
    }
    if ("RIDER".equals(custody)) {
      p.put("returnBy", returnBy); // 2026-11-17 — 다른 라이더 회수면 OTHER_RIDER
    }
    p.put("failedAt", at.toString());
    publish(
        "DeliveryFailed",
        before,
        new EventEnvelope.Actor(EventEnvelope.ActorType.OPERATOR, operatorId),
        p);
  }

  /**
   * 지원센터 답장(2026-11-16 — 라이더 앱 요청 §11-5): 라이더 알림함용. 메시지 내용은 싣지 않는다. version = 지금 배송 수행 버전 그대로(대화는
   * 배송 버전을 올리지 않는다 — 라이더 앱의 X-Expected-Version 이 흔들리지 않게).
   */
  public void helpReplied(
      String deliveryId,
      String requestId,
      String riderId,
      long seq,
      java.time.Instant at,
      int version,
      String staffId) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("deliveryId", deliveryId);
    p.put("requestId", requestId);
    p.put("riderId", riderId);
    p.put("messageSeq", seq);
    p.put("repliedAt", at.toString());
    events.publish(
        "HelpRequestReplied",
        1,
        "Delivery",
        deliveryId,
        version,
        new EventEnvelope.Actor(EventEnvelope.ActorType.OPERATOR, staffId),
        p);
  }

  /** 라이더 도움요청 → case-service 가 받아 지원센터 사건으로(구현 전). version = 이 사건의 수행 버전(bumpVersion). */
  public void helpRequested(
      DeliveryRow d, ExecutionStore.HelpRow h, List<String> evidenceRefs, int version) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("incidentId", h.incidentId());
    p.put("deliveryId", d.deliveryId());
    p.put("riderId", h.riderId());
    p.put("type", "HELP_REQUEST");
    p.put("category", h.category());
    p.put("stage", h.stage());
    if (d.orderId() != null) {
      p.put("orderId", d.orderId());
    }
    if (d.mfcId() != null) {
      p.put("mfcId", d.mfcId());
    }
    if (h.detail() != null) {
      p.put("detail", h.detail());
    }
    p.put("evidenceRefs", evidenceRefs);
    p.put("raisedAt", h.createdAt().toString());
    events.publish(
        "DeliveryIncidentRaised",
        1,
        "Delivery",
        d.deliveryId(),
        version,
        new EventEnvelope.Actor(EventEnvelope.ActorType.RIDER, h.riderId()),
        p);
  }

  /** 수행료 근거(환불과 독립). version = 이 사건의 수행 버전(bumpVersion). */
  public void earningRecorded(ExecutionStore.EarningRow e, int version, Instant at) {
    earningRecorded(e, version, at, null);
  }

  /** adjustment = 지원센터 조정(2026-11-17 — adjustmentId·decision·fault, 사유 없음). 있으면 라이더 알림함이 알린다. */
  public void earningRecorded(
      ExecutionStore.EarningRow e,
      int version,
      Instant at,
      @org.jspecify.annotations.Nullable Map<String, Object> adjustment) {
    Map<String, Object> basis = new LinkedHashMap<>();
    if (e.baseFee() != null) {
      basis.put("baseFee", e.baseFee());
    }
    if (e.total() != null) {
      basis.put("totalFee", e.total());
    }
    if (e.vehicleType() != null) {
      basis.put("vehicleType", e.vehicleType());
    }
    if (e.distanceM() != null) {
      basis.put("distanceM", e.distanceM());
    }
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("deliveryId", e.deliveryId());
    p.put("riderId", e.riderId());
    p.put("earningId", e.earningId());
    p.put("feeBasis", basis);
    p.put("rateVersion", e.rateTableVersion());
    p.put("status", e.settlementState());
    p.put("calculable", e.calculable());
    if (e.payoutDate() != null) {
      p.put("payoutDate", e.payoutDate().toString());
    }
    p.put("recordedAt", at.toString());
    if (adjustment != null) {
      p.put("adjustment", adjustment);
    }
    events.publish(
        "RiderEarningRecorded",
        1,
        "Delivery",
        e.deliveryId(),
        version,
        new EventEnvelope.Actor(EventEnvelope.ActorType.SYSTEM, events.sourceSystem()),
        p);
  }

  /** 운영자 상태 정정(2026-11-17). basis = 사유(직원이 적은 글 — 개인정보를 적지 않게 화면 안내). */
  public void statusCorrected(
      DeliveryRow before,
      String from,
      String to,
      String operatorId,
      String basis,
      java.util.List<String> evidenceRefs,
      Instant at) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("deliveryId", before.deliveryId());
    p.put("from", from);
    p.put("to", to);
    p.put("operatorId", operatorId);
    p.put("basis", basis);
    p.put(
        "evidenceRefs",
        evidenceRefs.stream().map(r -> Map.of("evidenceId", r, "kind", "PHOTO")).toList());
    p.put("correctedAt", at.toString());
    publish(
        "DeliveryStatusCorrected",
        before,
        new EventEnvelope.Actor(EventEnvelope.ActorType.OPERATOR, operatorId),
        p);
  }

  private void publish(
      String type, DeliveryRow before, EventEnvelope.Actor actor, Map<String, Object> payload) {
    events.publish(type, 1, "Delivery", before.deliveryId(), before.version() + 1L, actor, payload);
  }

  private static EventEnvelope.Actor rider(DeliveryRow d) {
    return new EventEnvelope.Actor(EventEnvelope.ActorType.RIDER, String.valueOf(d.riderId()));
  }
}
