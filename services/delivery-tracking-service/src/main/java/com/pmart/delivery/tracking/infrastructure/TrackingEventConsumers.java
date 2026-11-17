package com.pmart.delivery.tracking.infrastructure;

import com.pmart.common.messaging.EventConsumers;
import com.pmart.common.messaging.PmartEventConsumer;
import com.pmart.delivery.tracking.application.RiderWatch;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 배송 사건 구독(토픽 delivery.delivery.v1, 바인딩 {@code deliveryEvents-in-0} — 설정 저장소 {@code
 * delivery-tracking-service.yml}, 2026-11-14). 배정·배정 변경·픽업 전 취소·완료·실패만 받아 진행 중 배송을 만든다(위치 끊김 감지 —
 * {@link RiderWatch}). 다른 사건은 버린다.
 */
@Configuration(proxyBeanMethods = false)
class TrackingEventConsumers {

  @Bean
  PmartEventConsumer deliveryEvents(
      EventConsumers consumers,
      RiderWatch watch,
      com.pmart.delivery.tracking.application.ProgressService progress) {
    // 같은 inbox 트랜잭션 — 라이더 감시(TR-2·TR-1)와 배송 진행 사본(TR-3)
    return consumers.of(
        e ->
            RiderWatch.TYPES.contains(e.eventType())
                || com.pmart.delivery.tracking.application.ProgressService.TYPES.contains(
                    e.eventType()),
        e -> {
          if (RiderWatch.TYPES.contains(e.eventType())) {
            watch.on(e);
          }
          if (com.pmart.delivery.tracking.application.ProgressService.TYPES.contains(
              e.eventType())) {
            progress.on(e);
          }
        });
  }
}
