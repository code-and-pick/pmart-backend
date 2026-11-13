package com.pmart.mfc.capacity.infrastructure;

import com.pmart.common.messaging.EventConsumers;
import com.pmart.common.messaging.PmartEventConsumer;
import com.pmart.mfc.capacity.application.HrEmployeeHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * hr-service 직원 사건 구독(platform.employee.v1 — 바인딩 employeeEvents-in-0, 토픽은 설정 저장소). 같은 토픽의 다른 사건
 * (GrantRequested 등)은 inbox 에 남기지 않고 버린다. 그룹·재시도·DLQ·inbox 는 common-messaging 기본값(ADR-0035).
 */
@Configuration(proxyBeanMethods = false)
class HrEventConsumers {

  @Bean
  PmartEventConsumer employeeEvents(EventConsumers consumers, HrEmployeeHandler handler) {
    return consumers.of(e -> HrEmployeeHandler.TYPES.contains(e.eventType()), handler::on);
  }
}
