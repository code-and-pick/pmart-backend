package com.pmart.mfc.inbound.infrastructure;

import com.pmart.common.messaging.EventConsumers;
import com.pmart.common.messaging.PmartEventConsumer;
import com.pmart.mfc.inbound.application.ReplenishmentTrigger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * mfc-inventory 사건 구독(mfc.inventory.v1) — 재고 변동 때 자동 보충 점검 표시. 바인딩 inventoryEvents-in-0(설정 저장소,
 * ADR-0035).
 */
@Configuration(proxyBeanMethods = false)
class InventoryEventConsumers {

  /**
   * mfc-quality 사건(mfc.quality.v1, 바인딩 qualityEvents-in-0) — ReturnInspected 의 재판매 분 적치 작업(IV-10).
   */
  @Bean
  PmartEventConsumer qualityEvents(
      EventConsumers consumers, com.pmart.mfc.inbound.application.ReturnReceiptService returns) {
    return consumers.of(returns::onQualityEvent);
  }

  /**
   * mfc-capacity 사건(mfc.capacity.v1, 바인딩 capacityEvents-in-0) — 이동 지시(작업자 쪽 4단계)·입고 작업(0.9.0, BR-52
   * R2) 담당 투영. taskId 가 어느 쪽에도 없으면 아무것도 안 바뀐다.
   */
  @Bean
  PmartEventConsumer capacityEvents(
      EventConsumers consumers,
      com.pmart.mfc.inbound.application.MoveOrderService moves,
      com.pmart.mfc.inbound.application.InboundTaskService tasks) {
    return consumers.of(
        e -> {
          moves.onCapacityEvent(e);
          tasks.onCapacityEvent(e);
        });
  }

  @Bean
  PmartEventConsumer inventoryEvents(EventConsumers consumers, ReplenishmentTrigger trigger) {
    return consumers.of(trigger::onInventoryEvent);
  }
}
