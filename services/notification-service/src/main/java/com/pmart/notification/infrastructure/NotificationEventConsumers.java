package com.pmart.notification.infrastructure;

import com.pmart.common.messaging.EventConsumers;
import com.pmart.common.messaging.PmartEventConsumer;
import com.pmart.notification.application.NotificationService;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 고객 알림 구독(설정 저장소 {@code notification-service.yml} 의 바인딩, 2026-11-16). 처음 켤 때 소비 그룹 시작 위치는
 * latest(지난 주문에 알림을 몰아 쌓지 않게). 고르는 사건 밖은 버린다.
 */
@Configuration(proxyBeanMethods = false)
class NotificationEventConsumers {

  private static final Set<String> ORDER =
      Set.of(
          "OrderConfirmed",
          "OrderPreparationStarted",
          "OrderCancelled",
          "OrderLineQuantityChanged",
          "ReservationTimeChanged");
  private static final Set<String> DELIVERY =
      Set.of(
          "PickupCompleted",
          "DeliveryCompleted",
          "RedeliveryPaymentRequired",
          "HelpRequestReplied",
          "DispatchOfferSent", // 라이더 배차 제안 푸시(2026-11-17 — 알림함에는 안 넣음)
          "RiderEarningRecorded", // 지원센터 수행료 조정(2026-11-17) — adjustment 있을 때만 알림
          // 고객 반품 회수(2026-11-17 — purpose CLAIM_RETURN 만 알린다)
          "DeliveryCreated",
          "ReturnPickedUp",
          "ReturnPickupFailed");

  /**
   * platform.case.v1 — 고객 문의 답변, 라이더 문의 답변·도움요청 처리 끝(라이더 알림함 §11-5), 오배송 확인(리뷰 요청 보류 — 2026-11-18).
   */
  private static final Set<String> CASE =
      Set.of("InquiryAnswered", "RiderInquiryAnswered", "CaseClosed", "MisdeliveryConfirmed");

  @Bean
  PmartEventConsumer orderEvents(EventConsumers consumers, NotificationService n) {
    return consumers.of(e -> ORDER.contains(e.eventType()), n::onOrderEvent);
  }

  @Bean
  PmartEventConsumer deliveryEvents(EventConsumers consumers, NotificationService n) {
    return consumers.of(e -> DELIVERY.contains(e.eventType()), n::onDeliveryEvent);
  }

  /** delivery.slot.v1 — 배송 지연 가능 안내(CP-1, 2026-11-16). */
  @Bean
  PmartEventConsumer slotEvents(EventConsumers consumers, NotificationService n) {
    return consumers.of(e -> "SlotCapacityReduced".equals(e.eventType()), n::onSlotEvent);
  }

  @Bean
  PmartEventConsumer claimEvents(EventConsumers consumers, NotificationService n) {
    return consumers.of(e -> "RefundIntentCreated".equals(e.eventType()), n::onClaimEvent);
  }

  @Bean
  PmartEventConsumer paymentEvents(EventConsumers consumers, NotificationService n) {
    return consumers.of(e -> "RefundResult".equals(e.eventType()), n::onPaymentEvent);
  }

  @Bean
  PmartEventConsumer caseEvents(EventConsumers consumers, NotificationService n) {
    return consumers.of(e -> CASE.contains(e.eventType()), n::onCaseEvent);
  }

  /** delivery.rider.v1 — 계정 제한·해제(라이더 알림함, 2026-11-16 §11-5). */
  @Bean
  PmartEventConsumer riderEvents(EventConsumers consumers, NotificationService n) {
    return consumers.of(
        e -> "RiderRestricted".equals(e.eventType()) || "DeliveryListUpdated".equals(e.eventType()),
        n::onRiderEvent);
  }

  /**
   * delivery.tracking.v1 — 고객에게 받았는지 묻기(2026-11-17, DeliveryReceiptCheckRequested). 시작 위치 latest.
   */
  @Bean
  PmartEventConsumer trackingEvents(EventConsumers consumers, NotificationService n) {
    return consumers.of(
        e -> "DeliveryReceiptCheckRequested".equals(e.eventType()), n::onTrackingEvent);
  }
}
