package com.pmart.delivery.dispatch.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.delivery.dispatch.infrastructure.DispatchStore;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * delivery-execution 사건(delivery.delivery.v1) → 배차 종료. {@code DeliveryCompleted}(전달 완료)·{@code
 * DeliveryFailed}(운영자가 확정한 전달 실패 — 2026-11-13)면 배송을 CLOSED 로 — 라이더 "진행 중" 수(ASSIGNED·PICKED_UP)에서
 * 빠져 다음 배차를 받는다(PC 1 검토 H2, 사용자 2026-11-13 "배달 완료 전까지 진행 중"). 같은 토픽의 이 서비스 사건은 종류로 거른다. inbox 트랜잭션
 * 안.
 *
 * <p>오배송 회수(DD-2, 2026-11-16): {@code ReturnPickedUp} → 회수 배송 PICKED_UP(픽업 뒤라 배차 취소는 지원센터 경로),
 * {@code ReturnDelivered} → CLOSED. 전달 완료 시각은 {@code completed_at} 에 남긴다 — 나중에 오배송이 확인되면 그 시각의 원래
 * 라이더 위치가 회수지다.
 */
@Component
public class ExecutionEventHandler {

  public static final Set<String> TYPES =
      Set.of("DeliveryCompleted", "DeliveryFailed", "ReturnPickedUp", "ReturnDelivered");

  private static final Logger log = LoggerFactory.getLogger(ExecutionEventHandler.class);
  private static final Set<String> OPEN = Set.of("ASSIGNED", "PICKED_UP");

  private final DispatchStore store;
  private final Clock clock;

  public ExecutionEventHandler(DispatchStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  public void on(EventEnvelope e) {
    JsonNode p = e.payload();
    String deliveryId = p.path("deliveryId").asString();
    Instant now = clock.instant();
    store
        .lockDelivery(deliveryId)
        .ifPresent(
            d -> {
              if ("DeliveryCompleted".equals(e.eventType())) {
                store.setCompletedAt(deliveryId, instant(p.path("deliveredAt"), now));
              }
              if (!OPEN.contains(d.state())) {
                log.info("배송 {} {} — 배차 상태 {} 그대로", deliveryId, e.eventType(), d.state());
                return;
              }
              if ("ReturnPickedUp".equals(e.eventType())) {
                if ("ASSIGNED".equals(d.state())) {
                  store.recordHandover(deliveryId, d.handedOverJson(), true, now);
                  log.info("회수 배송 {} 회수 — PICKED_UP(라이더 {})", deliveryId, d.riderId());
                }
                return;
              }
              // 인계 사건보다 완료가 먼저 와도(토픽이 달라 순서 보장 없음) 전달은 실물 사실이라 닫는다
              store.setState(deliveryId, "CLOSED", null, now);
              log.info("배송 {} 배차 종료 — {}(라이더 {})", deliveryId, e.eventType(), d.riderId());
            });
  }

  private static Instant instant(JsonNode n, Instant fallback) {
    try {
      return n.isString() ? Instant.parse(n.asString()) : fallback;
    } catch (java.time.format.DateTimeParseException ex) {
      return fallback;
    }
  }
}
