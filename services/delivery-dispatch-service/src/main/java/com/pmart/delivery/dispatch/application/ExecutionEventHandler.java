package com.pmart.delivery.dispatch.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.delivery.dispatch.infrastructure.DispatchStore;
import com.pmart.delivery.dispatch.infrastructure.DispatchStore.DeliveryRow;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
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
      Set.of(
          "DeliveryCompleted",
          "DeliveryFailed",
          "ReturnPickedUp",
          "ReturnDelivered",
          "ReturnPickupFailed"); // 고객 반품 회수 실패(CL-1, 2026-11-17) — 배차 끝, 다시 시도는 반품 쪽이 새 요청으로

  private static final Logger log = LoggerFactory.getLogger(ExecutionEventHandler.class);
  private static final Set<String> OPEN = Set.of("ASSIGNED", "PICKED_UP");

  private final DispatchStore store;
  private final Clock clock;

  private final DispatchEvents events;

  public ExecutionEventHandler(DispatchStore store, DispatchEvents events, Clock clock) {
    this.store = store;
    this.events = events;
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
              if ("DeliveryFailed".equals(e.eventType())
                  && "RIDER".equals(p.path("custody").asString(""))) {
                failedReturn(d, "OTHER_RIDER".equals(p.path("returnBy").asString("")), now);
              }
            });
  }

  /**
   * 전달 실패 확정 + 상품이 라이더 손(custody RIDER) → 반납 배송(type RETURN, purpose FAILED_DELIVERY_RETURN)을 만들어
   * 원래 라이더에게 바로 배정한다(2026-11-17 사용자 "같은 라이더에 회수 배송 자동 배정" — 업무 원문 §5.4 "진행 중 상품을 다른 라이더에게 임의 인계
   * 불가"). 배정은 판단 주기({@link DispatchEngine})가 고객 집 → MFC 거리를 채운 뒤 제안 없이. 회수 배송·다시 배송의 실패는 만들지
   * 않는다(상품이 고객 쪽·이미 반납 흐름).
   */
  private void failedReturn(DeliveryRow d, boolean otherRider, Instant now) {
    if ("RETURN".equals(d.type()) || d.redelivery() || d.riderId() == null) {
      return;
    }
    String id =
        "DLV-"
            + java.util.UUID.nameUUIDFromBytes(
                ("failed-return:" + d.deliveryId())
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    if (!store.insertFailedReturn(id, d.orderId(), d.mfcId(), d.deliveryId(), otherRider, now)) {
      return;
    }
    // 다른 라이더(2026-11-17): 원래 라이더는 제안에서 뺀다 — 근처 라이더에게 일반 회수처럼
    store.insertAttempt(
        "ATT-" + java.util.UUID.randomUUID(), id, "AUTO", otherRider ? d.riderId() : null, now);
    events.created(store.delivery(id).orElseThrow(), List.of());
    log.info("전달 실패 {} → 반납 배송 {}(라이더 {} 에게 바로 배정 예정)", d.deliveryId(), id, d.riderId());
  }

  private static Instant instant(JsonNode n, Instant fallback) {
    try {
      return n.isString() ? Instant.parse(n.asString()) : fallback;
    } catch (java.time.format.DateTimeParseException ex) {
      return fallback;
    }
  }
}
