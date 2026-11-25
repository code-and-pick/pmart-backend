package com.pmart.cdc.outbound.infrastructure;

import com.pmart.cdc.outbound.application.ShortfallService;
import com.pmart.common.messaging.EventConsumers;
import com.pmart.common.messaging.PmartEventConsumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * cdc-inventory 사건 구독(토픽 cdc.inventory.v1, 0.17.0 — cdc-outbound 첫 구독). {@code AllocationShortfall}
 * 만 반영하고 같은 토픽의 다른 사건(AllocationResult·TransferDeparted 등)은 inbox 에만 남긴다. 반영과 inbox 기록이 한
 * 트랜잭션(ADR-0009·0035).
 *
 * <p>바인딩 inventoryEvents-in-0 → cdc.inventory.v1(설정 저장소 {@code cdc-outbound-service.yml} — 정본 PC
 * 1).
 */
@Configuration(proxyBeanMethods = false)
class InventoryEventConsumers {

  @Bean
  PmartEventConsumer inventoryEvents(EventConsumers consumers, ShortfallService shortfalls) {
    return consumers.of(e -> "AllocationShortfall".equals(e.eventType()), shortfalls::apply);
  }
}
