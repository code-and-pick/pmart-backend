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
          "OrderLineQuantityChanged");
  private static final Set<String> DELIVERY =
      Set.of("PickupCompleted", "DeliveryCompleted", "RedeliveryPaymentRequired");

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
    return consumers.of(e -> "InquiryAnswered".equals(e.eventType()), n::onCaseEvent);
  }
}
