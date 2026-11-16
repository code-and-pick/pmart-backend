package com.pmart.order.orchestration.infrastructure;

import com.pmart.common.messaging.EventConsumers;
import com.pmart.common.messaging.PmartEventConsumer;
import com.pmart.order.orchestration.application.CheckoutSagaService;
import com.pmart.order.orchestration.application.RedeliveryPaymentService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * payment-service 결제 사건 구독(order.payment.v1). 승인·실패·불명·불일치·보상 취소 결과가 오면 해당 사가를 깨운다. 사건이 늦거나 없어도
 * sweep 이 결제 시도를 조회해 같은 결과에 이른다.
 *
 * <p>Spring Cloud Stream 함수 바인딩(ADR-0035): 토픽·함수 이름은 설정 저장소 {@code <서비스>.yml}, 그룹·재시도·DLQ 는
 * common-messaging 기본값.
 */
@Configuration(proxyBeanMethods = false)
class PaymentEventConsumers {

  /** order.payment.v1 — 바인딩 paymentEvents-in-0(설정 저장소). */
  @Bean
  PmartEventConsumer paymentEvents(
      EventConsumers consumers, CheckoutSagaService sagas, RedeliveryPaymentService redelivery) {
    return consumers.of(
        e -> {
          sagas.onPaymentEvent(e);
          redelivery.onPaymentEvent(e); // 오배송 다시 배송 9,000원(ADR-0065) — 자기 결제 시도만
        });
  }
}
