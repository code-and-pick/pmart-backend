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
  private final tools.jackson.databind.ObjectMapper mapper;

  public DispatchEvents(EventPublisher events, tools.jackson.databind.ObjectMapper mapper) {
    this.events = events;
    this.mapper = mapper;
  }

  public void created(DeliveryRow d, List<String> packageIds) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("deliveryId", d.deliveryId());
    p.put("orderId", d.orderId());
    if (d.deliverySeq() != null) {
      p.put("deliverySeq", d.deliverySeq()); // 화면 배송 번호 = 주문번호-이 값(2026-11-18, ADR-0070)
    }
    p.put("shipmentId", d.shipmentId());
    p.put("type", d.type());
    p.put("mfcId", d.mfcId());
    if (d.redelivery()) {
      // 오배송 다시 배송(ADR-0065) — 도착지는 상담원이 기록한 실제 집(주소 원문 없음)
      p.put(
          "dropAddressRef", "order:" + d.orderId() + ":redelivery:" + d.redeliveryDestinationId());
      p.put("purpose", d.purpose());
      p.put("caseId", d.caseId());
      p.put("originDeliveryId", d.originDeliveryId());
    } else if (d.claimReturn()) {
      // 고객 반품 회수(CL-1) — 회수지 = 고객 집(주소 원문 없음), 도착지는 MFC. 회수할 줄은 execution 이 회수·반납 사건에 싣는다
      p.put("dropAddressRef", "mfc:" + d.mfcId());
      p.put("purpose", d.purpose());
      p.put("claimId", d.claimId());
      if (d.pickupRequestId() != null) {
        p.put("pickupRequestId", d.pickupRequestId()); // claim 이 배송 번호를 잇는다(DD-3)
      }
      if (d.originDeliveryId() != null) {
        p.put("originDeliveryId", d.originDeliveryId());
      }
      p.put("pickupAddressRef", "order:" + d.orderId());
      if (d.returnLinesJson() != null) {
        p.put("returnLines", mapper.readTree(d.returnLinesJson()));
      }
    } else if (d.failedReturn()) {
      // 전달 실패 반납(2026-11-17) — 상품은 이미 원래 라이더 손에, 도착지는 MFC
      p.put("dropAddressRef", "mfc:" + d.mfcId());
      p.put("purpose", d.purpose());
      p.put("originDeliveryId", d.originDeliveryId());
    } else if (d.recovery()) {
      // 오배송 회수(DD-2) — 도착지는 MFC, 회수지 좌표는 싣지 않는다
      p.put("dropAddressRef", "mfc:" + d.mfcId());
      p.put("purpose", d.purpose());
      p.put("caseId", d.caseId());
      p.put("originDeliveryId", d.originDeliveryId());
    } else {
      p.put("dropAddressRef", "order:" + d.orderId()); // 원주문 주소 참조(주소 원문 없음)
      if (d.purpose() != null) {
        p.put("purpose", d.purpose()); // 오배송 재발송(2026-11-16)
      }
      if (d.caseId() != null) {
        p.put("caseId", d.caseId());
      }
    }
    if (d.deliveryMode() != null) {
      p.put("deliveryMode", d.deliveryMode());
    }
    if (!packageIds.isEmpty()) {
      p.put("packageIds", packageIds);
    }
    p.put("createdAt", d.createdAt().toString());
    publish("DeliveryCreated", d, d.version(), system(), p);
  }

  /**
   * 오배송 다시 배송 9,000원 결제 필요(ADR-0065 — 잘못 둔 곳과 실제 집이 200m 이상). orchestration 이 고객 앱 결제를 요청하고, 결제되면
   * 배차를 풀고 30분 안에 안 되면 회수 후 환불로 바꾼다.
   */
  public void redeliveryPaymentRequired(DeliveryRow d, long fee, int version) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("deliveryId", d.deliveryId());
    p.put("orderId", d.orderId());
    p.put("caseId", d.caseId());
    p.put("originDeliveryId", d.originDeliveryId());
    p.put("straightDistanceM", d.straightDistanceM());
    p.put("fee", fee);
    p.put("requiredAt", d.updatedAt().toString());
    publish("RedeliveryPaymentRequired", d, version, system(), p);
  }

  /**
   * @param version 이 사건의 배송 버전(DispatchStore.bumpVersion — 직전 사건과 겹치지 않게, L3)
   */
  public void offerSent(DeliveryRow d, OfferRow o, int version) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("offerId", o.offerId());
    p.put("attemptId", o.attemptId());
    p.put("deliveryId", d.deliveryId());
    p.put("riderId", o.riderId());
    p.put("expiresAt", o.expiresAt().toString());
    p.put("fee", o.fee());
    p.put("sentAt", o.sentAt().toString());
    publish("DispatchOfferSent", d, version, system(), p);
  }

  /**
   * @param offer 수락한 제안 — 수락 당시 금액·수단을 실어 execution 수행료 기준으로(2026-11-13). 없으면 생략
   */
  public void assigned(
      DeliveryRow before,
      String riderId,
      String offerId,
      int assignmentVersion,
      Instant at,
      @Nullable OfferRow offer,
      @Nullable String orderSummaryJson) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("deliveryId", before.deliveryId());
    p.put("orderId", before.orderId());
    p.put("riderId", riderId);
    if (orderSummaryJson != null) {
      // 라이더 내 배달 내역(원문 §8.1 배달현황 — 주문번호·픽업지 마트 이름, 2026-11-17)
      var s = mapper.readTree(orderSummaryJson);
      if (s.path("orderNumber").isString()) {
        p.put("orderNumber", s.path("orderNumber").asString());
      }
      if (s.path("mfcName").isString()) {
        p.put("mfcName", s.path("mfcName").asString());
      }
      if (s.path("mfcPhone").isString()) {
        p.put("mfcPhone", s.path("mfcPhone").asString()); // 지점 전화(2026-11-17)
      }
    }
    p.put("offerId", offerId);
    p.put("assignmentVersion", assignmentVersion);
    p.put("acceptedAt", at.toString());
    if (offer != null) {
      p.put("fee", offer.fee());
      if (offer.vehicleType() != null) {
        p.put("vehicleType", offer.vehicleType());
      }
      if (offer.bundleId() != null) {
        // 묶음 전달 순서(DE-6, 2026-11-19) — execution 이 "다음 차례" 고객에게 가고 있어요를 낸다
        p.put("bundleId", offer.bundleId());
        p.put("stopSequence", offer.stopSeq());
      }
    }
    if (before.dropDistanceM() != null) {
      p.put("distanceM", before.dropDistanceM());
    }
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
  public void delayed(
      DeliveryRow d, int delayedMinutes, int thresholdMinutes, Instant at, int version) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("deliveryId", d.deliveryId());
    p.put("orderId", d.orderId());
    if (d.mfcId() != null) {
      p.put("mfcId", d.mfcId()); // DD-MFC — 센터 관리자 사건함
    }
    p.put("delayedMinutes", delayedMinutes);
    p.put("threshold", thresholdMinutes);
    p.put("detectedAt", at.toString());
    publish("DispatchDelayed", d, version, system(), p);
  }

  public void failed(DeliveryRow before, String reason, int attempts, Instant at) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("deliveryId", before.deliveryId());
    p.put("orderId", before.orderId());
    if (before.mfcId() != null) {
      p.put(
          "mfcId", before.mfcId()); // DD-MFC(2026-11-16) — case 가 센터 관리자(MFC_SUPERVISOR) 사건함에 보일 센터
    }
    p.put("reason", reason);
    p.put("attempts", attempts);
    p.put("failedAt", at.toString());
    publish("DispatchFailed", before, before.version() + 1L, system(), p);
  }

  /**
   * 반품 회수 요청 거절(2026-11-17 — 원래 배송이 전달 완료 전, 사용자 "전달 완료 전에는 반품 불가"). 회수 배송을 만들지 않았으므로 deliveryId 는
   * 그 요청으로 정해질 배송 ID(실제 행 없음). claim 이 회수 실패로 세어 멈추지 않게 한다.
   */
  public void returnPickupRejected(
      String deliveryId,
      String claimId,
      String orderId,
      @Nullable String mfcId,
      String reason,
      java.time.Instant at) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("deliveryId", deliveryId);
    p.put("claimId", claimId);
    p.put("orderId", orderId);
    if (mfcId != null) {
      p.put("mfcId", mfcId);
    }
    p.put("reason", reason);
    p.put("customerReason", false);
    p.put("contactAttempts", 0);
    p.put("failedAt", at.toString());
    events.publish("ReturnPickupFailed", 1, "Delivery", deliveryId, 1L, system(), p);
  }

  /** 일반배차모드 라이더에게 새 배달이 목록에 올라왔다(2026-11-18) — notification 이 푸시만 보낸다. 주소 없음. */
  public void deliveryListUpdated(
      String riderId, String mfcId, int newCount, int version, Instant at) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("riderId", riderId);
    p.put("mfcId", mfcId);
    p.put("newCount", newCount);
    p.put("notifiedAt", at.toString());
    events.publish("DeliveryListUpdated", 1, "Rider", riderId, version, system(), p);
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
