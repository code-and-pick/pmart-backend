package com.pmart.rider.infrastructure;

import com.pmart.common.messaging.EventConsumers;
import com.pmart.common.messaging.PmartEventConsumer;
import com.pmart.rider.application.ComplaintService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * case 사건 구독(토픽 platform.case.v1, 바인딩 {@code caseEvents-in-0} — 설정 저장소 {@code rider-service.yml}).
 * 라이더 컴플레인({@code RiderComplaintRecorded}, RS-3)·문의 정지 단어({@code RiderProfanityDetected}, RS-8 — 7일
 * 정지)만 받고 다른 사건 생명주기 사건은 버린다.
 */
@Configuration(proxyBeanMethods = false)
class RiderEventConsumers {

  @Bean
  PmartEventConsumer caseEvents(
      EventConsumers consumers,
      ComplaintService complaints,
      com.pmart.rider.application.ProfanitySuspensions profanity) {
    return consumers.of(
        e ->
            ComplaintService.TYPES.contains(e.eventType())
                || com.pmart.rider.application.ProfanitySuspensions.TYPES.contains(e.eventType()),
        e -> {
          if (com.pmart.rider.application.ProfanitySuspensions.TYPES.contains(e.eventType())) {
            profanity.on(e);
          } else {
            complaints.on(e);
          }
        });
  }

  /**
   * 배송 사건(delivery.delivery.v1, 바인딩 {@code deliveryEvents-in-0} — 2026-11-23): 도움요청 대화 금칙어({@code
   * RiderHelpProfanityDetected})만 받고 나머지 배송 사건은 버린다.
   */
  @Bean
  PmartEventConsumer deliveryEvents(
      EventConsumers consumers, com.pmart.rider.application.ProfanitySuspensions profanity) {
    return consumers.of(
        e -> com.pmart.rider.application.ProfanitySuspensions.HELP_TYPES.contains(e.eventType()),
        profanity::on);
  }
}
