package com.pmart.delivery.dispatch.infrastructure;

import com.pmart.common.messaging.EventConsumers;
import com.pmart.common.messaging.PmartEventConsumer;
import com.pmart.delivery.dispatch.application.ExecutionEventHandler;
import com.pmart.delivery.dispatch.application.FulfillmentEventHandler;
import com.pmart.delivery.dispatch.application.RiderEventHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * mfc-fulfillment 사건 구독(토픽 mfc.fulfillment.v1, DD-1). 바인딩 {@code fulfillmentEvents-in-0} 의 토픽은 설정
 * 저장소 {@code delivery-dispatch-service.yml}. 이 서비스가 다루지 않는 사건(작업 접수·피킹 등)은 inbox 에 남기지 않고 지나간다.
 *
 * <p>처음 켜기 전에 소비 그룹 시작 위치를 2026-11-10T00:30Z 로 맞춘다(kickoff §6 — 그 전 E2E 시험 출고는 건너뛴다).
 */
@Configuration(proxyBeanMethods = false)
class FulfillmentEventConsumers {

  @Bean
  PmartEventConsumer fulfillmentEvents(EventConsumers consumers, FulfillmentEventHandler handler) {
    return consumers.of(e -> FulfillmentEventHandler.TYPES.contains(e.eventType()), handler::on);
  }

  /**
   * order 주문 취소(토픽 order.order.v1, 바인딩 {@code orderEvents-in-0}) — scope FULL 이면 배송 취소·이후 배차 요청
   * 차단(PC 1 재검토 ①: 피킹 전 중지는 전체·부분 구분 없이 오므로 주문 전체 여부는 이 사건으로). 다른 주문 사건은 버린다.
   */
  @Bean
  PmartEventConsumer orderEvents(EventConsumers consumers, FulfillmentEventHandler handler) {
    return consumers.of(e -> "OrderCancelled".equals(e.eventType()), handler::onOrderCancelled);
  }

  /**
   * delivery-execution 전달 완료(토픽 delivery.delivery.v1, 바인딩 {@code executionEvents-in-0}) → 배송
   * CLOSED(H2). 이 서비스가 내는 사건도 같은 토픽이라 종류로 거른다.
   */
  @Bean
  PmartEventConsumer executionEvents(EventConsumers consumers, ExecutionEventHandler handler) {
    return consumers.of(e -> ExecutionEventHandler.TYPES.contains(e.eventType()), handler::on);
  }

  /**
   * rider 계정 제한(토픽 delivery.rider.v1, 바인딩 {@code riderEvents-in-0}, RS-1 2026-11-14) → 픽업 전 배정 풀고
   * 재배차·남은 제안 닫기. 운행 상태 사건(RiderStateChanged)은 적격 조회로 보므로 버린다.
   */
  @Bean
  PmartEventConsumer riderEvents(EventConsumers consumers, RiderEventHandler handler) {
    return consumers.of(e -> RiderEventHandler.TYPES.contains(e.eventType()), handler::on);
  }
}
