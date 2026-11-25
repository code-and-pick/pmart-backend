package com.pmart.cdc.inventory.infrastructure;

import com.pmart.cdc.inventory.application.ReceiptService;
import com.pmart.common.messaging.EventConsumers;
import com.pmart.common.messaging.PmartEventConsumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * mfc-inventory 사건 구독(토픽 mfc.inventory.v1, 0.11.0). {@code MfcReceiptPosted}(CDC 이관 TIN 확정)만 반영해 운송
 * 중 재고를 대사하고, 같은 토픽의 다른 사건은 inbox 에만 남긴다. 반영과 inbox 기록이 한 트랜잭션(ADR-0009·0035). 바인딩 mfcEvents-in-0 →
 * mfc.inventory.v1(설정 저장소 — 정본 PC 1). 발행은 mfc-inventory IV-8(PC 3).
 */
@Configuration(proxyBeanMethods = false)
class MfcEventConsumers {

  @Bean
  PmartEventConsumer mfcEvents(EventConsumers consumers, ReceiptService receipts) {
    return consumers.of(
        e ->
            "MfcReceiptPosted".equals(e.eventType())
                || "ReceiptCorrectionPosted".equals(e.eventType()),
        e -> {
          if ("ReceiptCorrectionPosted".equals(e.eventType())) {
            receipts.correctionFromEvent(e); // 0.13.0 수령 정정
          } else {
            receipts.fromEvent(e);
          }
        });
  }
}
