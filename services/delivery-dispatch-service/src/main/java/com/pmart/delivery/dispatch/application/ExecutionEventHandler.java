package com.pmart.delivery.dispatch.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.delivery.dispatch.infrastructure.DispatchStore;
import java.time.Clock;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * delivery-execution 사건(delivery.delivery.v1) → 배차 종료. {@code DeliveryCompleted}(전달 완료)·{@code
 * DeliveryFailed}(운영자가 확정한 전달 실패 — 2026-11-13)면 배송을 CLOSED 로 — 라이더 "진행 중" 수(ASSIGNED·PICKED_UP)에서
 * 빠져 다음 배차를 받는다(PC 1 검토 H2, 사용자 2026-11-13 "배달 완료 전까지 진행 중"). 같은 토픽의 이 서비스 사건은 종류로 거른다. inbox 트랜잭션
 * 안.
 */
@Component
public class ExecutionEventHandler {

  public static final Set<String> TYPES = Set.of("DeliveryCompleted", "DeliveryFailed");

  private static final Logger log = LoggerFactory.getLogger(ExecutionEventHandler.class);
  private static final Set<String> OPEN = Set.of("ASSIGNED", "PICKED_UP");

  private final DispatchStore store;
  private final Clock clock;

  public ExecutionEventHandler(DispatchStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  public void on(EventEnvelope e) {
    String deliveryId = e.payload().path("deliveryId").asString();
    store
        .lockDelivery(deliveryId)
        .ifPresent(
            d -> {
              if (!OPEN.contains(d.state())) {
                log.info("배송 {} {} — 배차 상태 {} 그대로", deliveryId, e.eventType(), d.state());
                return;
              }
              // 인계 사건보다 완료가 먼저 와도(토픽이 달라 순서 보장 없음) 전달은 실물 사실이라 닫는다
              store.setState(deliveryId, "CLOSED", null, clock.instant());
              log.info("배송 {} 배차 종료 — {}(라이더 {})", deliveryId, e.eventType(), d.riderId());
            });
  }
}
