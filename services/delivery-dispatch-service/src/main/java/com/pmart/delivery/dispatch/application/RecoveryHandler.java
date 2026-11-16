package com.pmart.delivery.dispatch.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.delivery.dispatch.infrastructure.DispatchStore;
import com.pmart.delivery.dispatch.infrastructure.DispatchStore.DeliveryRow;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * 확인된 오배송 → 회수 배차(DD-2, ADR-0052 결정 4 "회수: 인근 다른 라이더를 자동 배차해 오배송지에서 회수하고 MFC 로 반납").
 *
 * <p>case {@code MisdeliveryConfirmed}(platform.case.v1 — 상담원·지원센터가 확인해 기록, 사용자 2026-11-15) → 회수
 * 배송(type RETURN, purpose MISDELIVERY_RECOVERY, 배송 ID 는 caseId 로 정해 같은 사건은 하나) + 배차 시도(원래 라이더는 뺀다)
 * + DeliveryCreated. 회수지 좌표·회수지 → MFC 도로 거리는 판단 주기의 좌표 준비({@link DispatchEngine})가 tracking·center
 * 를 불러 채운다(inbox 트랜잭션 안에서는 외부 호출 없음).
 *
 * <p>원래 배송이 이 서비스에 없거나 아직 픽업 전이면(실물이 고객 쪽에 없다) 만들지 않고 경고만 남긴다 — 사건함에서 사람이 본다. 사용자 2026-11-16
 * "추천대로": 회수지 = 원래 라이더가 완료를 누른 곳, 수행료 = 일반 표(회수지 → MFC), 회수 실패는 도움요청 → 지원센터, MFC 도착 = 반납.
 */
@Component
public class RecoveryHandler {

  public static final Set<String> TYPES = Set.of("MisdeliveryConfirmed");

  private static final Logger log = LoggerFactory.getLogger(RecoveryHandler.class);

  /** 실물이 라이더 손을 떠났을 수 있는 원래 배송 상태(완료 사건이 배차 쪽에 늦게 와 아직 PICKED_UP 일 수 있다). */
  private static final Set<String> DELIVERED = Set.of("PICKED_UP", "CLOSED");

  private final DispatchStore store;
  private final DispatchEvents events;
  private final Clock clock;

  public RecoveryHandler(DispatchStore store, DispatchEvents events, Clock clock) {
    this.store = store;
    this.events = events;
    this.clock = clock;
  }

  public void on(EventEnvelope e) {
    JsonNode p = e.payload();
    String caseId = p.path("caseId").asString("");
    String originId = p.path("deliveryId").asString("");
    if (caseId.isBlank() || originId.isBlank()) {
      log.warn("오배송 확인 사건 {} — caseId·deliveryId 없음, 회수 배차 안 함", e.eventId());
      return;
    }
    DeliveryRow origin = store.lockDelivery(originId).orElse(null);
    if (origin == null) {
      log.warn("오배송 확인 {} — 배송 {} 이 배차 기록에 없어 회수 배차 안 함(사건함에서 처리)", caseId, originId);
      return;
    }
    if (origin.recovery() || !DELIVERED.contains(origin.state())) {
      log.warn(
          "오배송 확인 {} — 배송 {} ({} {}) 은 회수 대상이 아님(픽업 전·회수 배송), 회수 배차 안 함",
          caseId,
          originId,
          origin.type(),
          origin.state());
      return;
    }
    Instant now = clock.instant();
    String deliveryId = recoveryDeliveryId(caseId);
    if (!store.insertRecovery(
        deliveryId, origin.orderId(), origin.mfcId(), caseId, originId, now)) {
      log.info("오배송 확인 {} — 회수 배송 {} 이미 있음", caseId, deliveryId);
      return;
    }
    // 원래 라이더는 뺀다(ADR-0052 "인근 다른 라이더") — 시도의 제외 라이더로
    store.insertAttempt("ATT-" + UUID.randomUUID(), deliveryId, "AUTO", origin.riderId(), now);
    events.created(store.delivery(deliveryId).orElseThrow(), List.of());
    log.info(
        "오배송 확인 {} → 회수 배송 {} 배차 시작(원래 배송 {}, MFC {}, 원래 라이더 제외)",
        caseId,
        deliveryId,
        originId,
        origin.mfcId());
  }

  /** 같은 사건은 같은 배송 ID — 다시 받아도 하나. */
  static String recoveryDeliveryId(String caseId) {
    return "DLV-"
        + UUID.nameUUIDFromBytes(
            ("misdelivery-recovery:" + caseId).getBytes(StandardCharsets.UTF_8));
  }
}
