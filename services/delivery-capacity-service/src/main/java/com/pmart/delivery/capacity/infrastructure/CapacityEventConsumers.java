package com.pmart.delivery.capacity.infrastructure;

import com.pmart.common.messaging.EventConsumers;
import com.pmart.common.messaging.PmartEventConsumer;
import com.pmart.delivery.capacity.application.CapacityReductionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * mfc-capacity 사건 구독(mfc.capacity.v1, 바인딩 {@code capacityEvents-in-0} — CP-1 2026-11-16). {@code
 * CapacityReduced} 만 쓰고 나머지는 버린다. 처음 켤 때 소비 그룹 시작 위치 latest(지난 축소로 안내를 몰아 내지 않게).
 */
@Configuration(proxyBeanMethods = false)
class CapacityEventConsumers {

  @Bean
  PmartEventConsumer capacityEvents(EventConsumers consumers, CapacityReductionHandler handler) {
    return consumers.of(e -> "CapacityReduced".equals(e.eventType()), handler::on);
  }
}
