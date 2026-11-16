package com.pmart.cases.infrastructure;

import com.pmart.cases.application.CaseInboxService;
import com.pmart.common.messaging.EventConsumers;
import com.pmart.common.messaging.PmartEventConsumer;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 사건함 구독(2026-11-16, 설정 저장소 {@code case-service.yml} 의 바인딩). 처음 켤 때 소비 그룹 시작 위치는 latest(지난 도움요청은
 * delivery-execution 의 임시 처리함에 남아 있다).
 *
 * <ul>
 *   <li>{@code deliveryEvents-in-0} = delivery.delivery.v1 — {@code DeliveryIncidentRaised} 만(R-4).
 *       같은 토픽의 다른 배송 사건은 버린다.
 *   <li>{@code trackingEvents-in-0} = delivery.tracking.v1 — {@code RiderLocationStale} 만(R-6).
 *   <li>{@code sagaEvents-in-0} = order.saga.v1, {@code paymentEvents-in-0} = order.payment.v1 —
 *       사가·결제 이상(R-3, 2026-11-16). 처음 켤 때 latest(지난 이상은 처리 대기 BO-12 에 이미 보인다).
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
class CaseEventConsumers {

  @Bean
  PmartEventConsumer deliveryEvents(EventConsumers consumers, CaseInboxService cases) {
    return consumers.of(
        e -> "DeliveryIncidentRaised".equals(e.eventType()), cases::onDeliveryIncident);
  }

  @Bean
  PmartEventConsumer trackingEvents(EventConsumers consumers, CaseInboxService cases) {
    return consumers.of(e -> "RiderLocationStale".equals(e.eventType()), cases::onLocationStale);
  }

  /**
   * R-3: order.saga.v1 — {@code ReconciliationRequired}·{@code CompensationExecuted}(성공 아닌 것만 사건).
   */
  @Bean
  PmartEventConsumer sagaEvents(EventConsumers consumers, CaseInboxService cases) {
    return consumers.of(e -> SAGA_TYPES.contains(e.eventType()), cases::onOrderException);
  }

  /** R-3: order.payment.v1 — {@code PaymentMismatch}·{@code PaymentResultUnknown}(상신된 것만 사건). */
  @Bean
  PmartEventConsumer paymentEvents(EventConsumers consumers, CaseInboxService cases) {
    return consumers.of(e -> PAYMENT_TYPES.contains(e.eventType()), cases::onOrderException);
  }

  private static final Set<String> SAGA_TYPES =
      Set.of("ReconciliationRequired", "CompensationExecuted");
  private static final Set<String> PAYMENT_TYPES =
      Set.of("PaymentMismatch", "PaymentResultUnknown");
}
