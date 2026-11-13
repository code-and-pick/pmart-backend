package com.pmart.mfc.capacity.infrastructure;

import com.pmart.common.messaging.EventConsumers;
import com.pmart.common.messaging.PmartEventConsumer;
import com.pmart.mfc.capacity.application.HrLinkService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 작업자 명단 ↔ 인사·권한 구독(토픽은 설정 저장소). 그룹·재시도·DLQ·inbox 는 common-messaging 기본값(ADR-0035).
 *
 * <ul>
 *   <li>employeeEvents-in-0 = platform.employee.v1 — hr-service 직원 사건 4개만(GrantRequested 등은 inbox
 *       없이 버림).
 *   <li>authEvents-in-0 = platform.auth.v1 — employee-auth {@code GrantApplied} 중 역할 MFC_WORKER 만.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
class HrEventConsumers {

  @Bean
  PmartEventConsumer employeeEvents(EventConsumers consumers, HrLinkService links) {
    return consumers.of(
        e -> HrLinkService.EMPLOYEE_TYPES.contains(e.eventType()), links::onEmployee);
  }

  @Bean
  PmartEventConsumer authEvents(EventConsumers consumers, HrLinkService links) {
    return consumers.of(HrLinkService::workerGrant, links::onGrant);
  }
}
