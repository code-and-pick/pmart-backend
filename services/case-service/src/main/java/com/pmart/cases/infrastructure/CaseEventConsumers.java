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
 *   <li>{@code deliveryEvents-in-0} = delivery.delivery.v1 — {@code
 *       DeliveryIncidentRaised}(R-4)·{@code DispatchFailed}(배차 실패, 2026-11-16 — delivery-dispatch 가
 *       같은 토픽에 낸다), 오배송 회수 진행 {@code DeliveryCreated·ReturnPickedUp·ReturnDelivered}(purpose
 *       MISDELIVERY_RECOVERY 만 — DD-2, 2026-11-16). 같은 토픽의 다른 배송 사건은 버린다.
 *   <li>{@code trackingEvents-in-0} = delivery.tracking.v1 — {@code RiderLocationStale} 만(R-6).
 *   <li>{@code sagaEvents-in-0} = order.saga.v1, {@code paymentEvents-in-0} = order.payment.v1 —
 *       사가·결제 이상(R-3, 2026-11-16). 처음 켤 때 latest(지난 이상은 처리 대기 BO-12 에 이미 보인다).
 *   <li>{@code claimEvents-in-0} = order.claim.v1 — {@code ReturnRecoveryFailed} 만(R-8,
 *       2026-11-17).
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
class CaseEventConsumers {

  @Bean
  PmartEventConsumer deliveryEvents(EventConsumers consumers, CaseInboxService cases) {
    return consumers.of(
        e -> DELIVERY_TYPES.contains(e.eventType()),
        e -> {
          if (RECOVERY_TYPES.contains(e.eventType())) {
            cases.onRecoveryProgress(e);
          } else if ("DispatchFailed".equals(e.eventType())) {
            cases.onDispatchFailed(e);
          } else {
            cases.onDeliveryIncident(e);
          }
        });
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

  /** R-8(2026-11-17): order.claim.v1 — {@code ReturnRecoveryFailed} 만(반품 회수 실패 → 상담원 재연락). */
  @Bean
  PmartEventConsumer claimEvents(EventConsumers consumers, CaseInboxService cases) {
    return consumers.of(
        e -> "ReturnRecoveryFailed".equals(e.eventType()), cases::onReturnRecoveryFailed);
  }

  private static final Set<String> DELIVERY_TYPES =
      Set.of(
          "DeliveryIncidentRaised",
          "DispatchFailed",
          "DeliveryCreated",
          "ReturnPickedUp",
          "ReturnDelivered");

  /** 오배송 회수 진행(DD-2, 2026-11-16) — purpose MISDELIVERY_RECOVERY 만 사건에 잇는다. */
  private static final Set<String> RECOVERY_TYPES =
      Set.of("DeliveryCreated", "ReturnPickedUp", "ReturnDelivered");

  private static final Set<String> SAGA_TYPES =
      Set.of("ReconciliationRequired", "CompensationExecuted");
  private static final Set<String> PAYMENT_TYPES =
      Set.of("PaymentMismatch", "PaymentResultUnknown");
}
