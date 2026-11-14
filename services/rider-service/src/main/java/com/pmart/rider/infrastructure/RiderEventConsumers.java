package com.pmart.rider.infrastructure;

import com.pmart.common.messaging.EventConsumers;
import com.pmart.common.messaging.PmartEventConsumer;
import com.pmart.rider.application.ComplaintService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * case 사건 구독(토픽 platform.case.v1, 바인딩 {@code caseEvents-in-0} — 설정 저장소 {@code rider-service.yml}).
 * 라이더 컴플레인({@code RiderComplaintRecorded}, RS-3)만 받고 다른 사건 생명주기 사건은 버린다.
 */
@Configuration(proxyBeanMethods = false)
class RiderEventConsumers {

  @Bean
  PmartEventConsumer caseEvents(EventConsumers consumers, ComplaintService complaints) {
    return consumers.of(e -> ComplaintService.TYPES.contains(e.eventType()), complaints::on);
  }
}
