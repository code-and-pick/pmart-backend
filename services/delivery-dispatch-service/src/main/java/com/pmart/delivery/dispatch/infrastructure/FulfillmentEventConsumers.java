package com.pmart.delivery.dispatch.infrastructure;

import com.pmart.common.messaging.EventConsumers;
import com.pmart.common.messaging.PmartEventConsumer;
import com.pmart.delivery.dispatch.application.FulfillmentEventHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * mfc-fulfillment 사건 구독(토픽 mfc.fulfillment.v1, DD-1). 바인딩 {@code fulfillmentEvents-in-0} 의 토픽은 설정
 * 저장소 {@code delivery-dispatch-service.yml}. 이 서비스가 다루지 않는 사건(작업 접수·피킹 등)은 inbox 에 남기지 않고 지나간다.
 *
 * <p>처음 켜기 전에 소비 그룹 시작 위치를 2026-11-10T00:30Z 로 맞춘다(kickoff §6 — 그 전 E2E 시험 출고는 건너뛴다).
 */
@Configuration(proxyBeanMethods = false)
class FulfillmentEventConsumers {

  @Bean
  PmartEventConsumer fulfillmentEvents(EventConsumers consumers, FulfillmentEventHandler handler) {
    return consumers.of(e -> FulfillmentEventHandler.TYPES.contains(e.eventType()), handler::on);
  }
}
