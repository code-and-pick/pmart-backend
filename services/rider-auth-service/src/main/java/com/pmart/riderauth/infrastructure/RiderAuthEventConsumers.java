package com.pmart.riderauth.infrastructure;

import com.pmart.common.messaging.EventConsumers;
import com.pmart.common.messaging.PmartEventConsumer;
import com.pmart.riderauth.application.RiderSessionCuts;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 라이더 사건 구독(토픽 delivery.rider.v1, 바인딩 {@code riderEvents-in-0} — 설정 저장소 {@code
 * rider-auth-service.yml}, 2026-11-15). 계정 제한({@code RiderRestricted})만 받아 정지·계약 종료 라이더 로그인 끊기에 쓴다.
 * 운행 상태 사건(RiderStateChanged)은 버린다.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class RiderAuthEventConsumers {

  @Bean
  PmartEventConsumer riderEvents(
      EventConsumers consumers,
      RiderSessionCuts cuts,
      com.pmart.riderauth.application.RiderWithdrawals withdrawals) {
    return consumers.of(
        e ->
            RiderSessionCuts.TYPES.contains(e.eventType())
                || com.pmart.riderauth.application.RiderWithdrawals.TYPES.contains(e.eventType()),
        e -> {
          if (com.pmart.riderauth.application.RiderWithdrawals.TYPES.contains(e.eventType())) {
            withdrawals.on(e); // 탈퇴(2026-11-17)
          } else {
            cuts.on(e);
          }
        });
  }
}
