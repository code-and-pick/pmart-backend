package com.pmart.delivery.execution.infrastructure;

import com.pmart.common.messaging.EventConsumers;
import com.pmart.common.messaging.PmartEventConsumer;
import com.pmart.delivery.execution.application.ExecutionEventHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 사건 구독. 바인딩 토픽은 설정 저장소 {@code delivery-execution-service.yml}: {@code deliveryEvents-in-0} =
 * delivery.delivery.v1(dispatch 배정 사건 — 이 서비스가 내는 사건도 같은 토픽이라 종류로 거른다), {@code
 * fulfillmentEvents-in-0} = mfc.fulfillment.v1(포장 목록·인계). 다루지 않는 사건은 inbox 에 남기지 않고 지나간다.
 *
 * <p>처음 켜기 전에 두 소비 그룹 시작 위치를 2026-11-10T00:30Z 로 맞춘다(dispatch 와 같은 기준 — kickoff §6).
 */
@Configuration(proxyBeanMethods = false)
class ExecutionEventConsumers {

  @Bean
  PmartEventConsumer deliveryEvents(EventConsumers consumers, ExecutionEventHandler handler) {
    return consumers.of(
        e -> ExecutionEventHandler.DELIVERY_TYPES.contains(e.eventType()), handler::onDelivery);
  }

  /**
   * case 오배송 확인(platform.case.v1, 바인딩 {@code caseEvents-in-0}, ADR-0065 2026-11-16) — 라이더 귀책이면 원래
   * 수행료 미지급. 처음 켜기 전 소비 그룹 시작 위치 latest.
   */
  @Bean
  PmartEventConsumer caseEvents(EventConsumers consumers, ExecutionEventHandler handler) {
    return consumers.of(e -> "MisdeliveryConfirmed".equals(e.eventType()), handler::onCase);
  }

  @Bean
  PmartEventConsumer fulfillmentEvents(EventConsumers consumers, ExecutionEventHandler handler) {
    return consumers.of(
        e -> ExecutionEventHandler.FULFILLMENT_TYPES.contains(e.eventType()),
        handler::onFulfillment);
  }
}
