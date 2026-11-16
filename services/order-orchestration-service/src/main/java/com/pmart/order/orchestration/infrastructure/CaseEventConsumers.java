package com.pmart.order.orchestration.infrastructure;

import com.pmart.common.messaging.EventConsumers;
import com.pmart.common.messaging.PmartEventConsumer;
import com.pmart.order.orchestration.application.ReshipmentSagaService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * case 사건 구독(platform.case.v1, 2026-11-16 — ADR-0052 결정 4). MisdeliveryConfirmed 로 오배송 재발송 사가를 시작하고
 * 나머지 사건함 사건은 지나간다. 바인딩 {@code caseEvents-in-0}(설정 저장소). 처음 켜기 전 소비 그룹 시작 위치 latest(+ {@code
 * pmart.orchestration.reshipment.start-cases-confirmed-after}).
 */
@Configuration(proxyBeanMethods = false)
class CaseEventConsumers {

  @Bean
  PmartEventConsumer caseEvents(EventConsumers consumers, ReshipmentSagaService sagas) {
    return consumers.of(sagas::onCaseEvent);
  }
}
