package com.pmart.order.orchestration.infrastructure;

import com.pmart.common.messaging.EventConsumers;
import com.pmart.common.messaging.PmartEventConsumer;
import com.pmart.order.orchestration.application.RedeliveryPaymentService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 배송 사건 구독(delivery.delivery.v1) — {@code DeliveryFailed}(사고·파손 → 새 상품 재발송, 2026-11-18)와 {@code
 * RedeliveryPaymentRequired}(오배송 다시 배송 9,000원, ADR-0065)만 쓰고 나머지는 지나간다. 바인딩 deliveryEvents-in-0(설정
 * 저장소, 처음 켤 때 시작 위치 latest).
 */
@Configuration(proxyBeanMethods = false)
class DeliveryEventConsumers {

  @Bean
  PmartEventConsumer deliveryEvents(
      EventConsumers consumers,
      RedeliveryPaymentService payments,
      com.pmart.order.orchestration.application.ReshipmentSagaService reshipments) {
    return consumers.of(
        e -> {
          payments.onDeliveryEvent(e);
          reshipments.onDeliveryEvent(e); // 2026-11-18 — 사고·파손 전달 실패 → 새 상품 재발송
        });
  }
}
