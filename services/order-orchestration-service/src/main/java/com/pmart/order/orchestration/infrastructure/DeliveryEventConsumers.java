package com.pmart.order.orchestration.infrastructure;

import com.pmart.common.messaging.EventConsumers;
import com.pmart.common.messaging.PmartEventConsumer;
import com.pmart.order.orchestration.application.RedeliveryPaymentService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * delivery-dispatch 배송 사건 구독(delivery.delivery.v1) — {@code RedeliveryPaymentRequired}(오배송 다시 배송
 * 9,000원, ADR-0065)만 쓰고 나머지는 지나간다. 바인딩 deliveryEvents-in-0(설정 저장소, 처음 켤 때 시작 위치 latest).
 */
@Configuration(proxyBeanMethods = false)
class DeliveryEventConsumers {

  @Bean
  PmartEventConsumer deliveryEvents(EventConsumers consumers, RedeliveryPaymentService payments) {
    return consumers.of(payments::onDeliveryEvent);
  }
}
