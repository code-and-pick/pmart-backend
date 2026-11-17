package com.pmart.delivery.dispatch.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.delivery.dispatch.infrastructure.DispatchStore;
import com.pmart.delivery.dispatch.infrastructure.DispatchStore.DeliveryRow;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 고객 반품 회수 배차(CL-1 배송 쪽, 2026-11-17 — 사용자 결정: 바로 배차, 수행료 일반 표(고객 집 → MFC), 실패마다 알림·다시 시도는 반품 쪽).
 *
 * <p>claim {@code ReturnPickupRequested}(order.claim.v1 — 반품 회수 승인) 한 건 → 회수 배송 하나(type RETURN,
 * purpose CLAIM_RETURN, 배송 ID 는 사건 ID 로 정해 같은 사건은 하나) + 배차 시도(제외 라이더 없음 — 원래 기사도 근처면 받을 수 있다) +
 * DeliveryCreated. 회수지(고객 집) 좌표·고객 집 → MFC 도로 거리는 판단 주기의 좌표 준비({@link DispatchEngine})가 채운다.
 *
 * <p>원래 배송이 아직 전달 완료 전이면(배달 중·실패) 만들지 않는다 — 사용자 2026-11-17 "배달 중이나 전달 완료 전에는 반품 불가"(claim 이 접수 때 막는
 * 것이 정본, 여기서는 경고만). 배송 하나 = 회수 요청 하나(pickupRequestId — 없으면 사건 ID).
 *
 * <p>같은 클레임에 아직 끝나지 않은 회수 배송이 있으면 새로 만들지 않고 경고만 — 한 번 실패하면 배송이 {@code ReturnPickupFailed} 로 끝나고, 다시
 * 보낼지는 반품 쪽이 새 회수 요청으로 정한다(2번 실패하면 상담원 재연락 — claim 결정 2026-11-17).
 */
@Component
public class ClaimReturnHandler {

  public static final Set<String> TYPES = Set.of("ReturnPickupRequested");

  private static final Logger log = LoggerFactory.getLogger(ClaimReturnHandler.class);

  private final DispatchStore store;
  private final DispatchEvents events;
  private final ObjectMapper mapper;
  private final Clock clock;

  public ClaimReturnHandler(
      DispatchStore store, DispatchEvents events, ObjectMapper mapper, Clock clock) {
    this.store = store;
    this.events = events;
    this.mapper = mapper;
    this.clock = clock;
  }

  public void on(EventEnvelope e) {
    JsonNode p = e.payload();
    String claimId = p.path("claimId").asString("");
    String orderId = p.path("orderId").asString("");
    String originId = p.path("deliveryId").asString("");
    JsonNode lines = p.path("lines");
    if (claimId.isBlank() || orderId.isBlank() || !lines.isArray() || lines.isEmpty()) {
      log.warn("반품 회수 요청 {} — claimId·orderId·lines 없음, 회수 배차 안 함", e.eventId());
      return;
    }
    store.lockClaim(claimId);
    Optional<String> open = store.openClaimReturn(claimId);
    if (open.isPresent()) {
      log.warn(
          "반품 회수 요청 {} — 클레임 {} 의 회수 배송 {} 이 아직 진행 중, 새로 만들지 않음", e.eventId(), claimId, open.get());
      return;
    }
    DeliveryRow origin = originId.isBlank() ? null : store.delivery(originId).orElse(null);
    if (origin != null && origin.completedAt() == null) {
      // 사용자 2026-11-17 "배달 중이나 전달 완료 전에는 반품 불가"
      log.warn(
          "반품 회수 요청 {} — 클레임 {} 의 원래 배송 {} 이 전달 완료 전({}), 반품 불가 — 회수 배차 안 함",
          e.eventId(),
          claimId,
          originId,
          origin.state());
      return;
    }
    String mfcId = p.path("mfcId").asString("");
    if (mfcId.isBlank() && origin != null) {
      mfcId = origin.mfcId();
    }
    if (mfcId.isBlank()) {
      log.warn(
          "반품 회수 요청 {} — 클레임 {} 의 MFC 를 알 수 없음(사건 mfcId·원래 배송 없음), 회수 배차 안 함",
          e.eventId(),
          claimId);
      return;
    }
    Instant now = clock.instant();
    String pickupRequestId = p.path("pickupRequestId").asString("");
    String requestKey = pickupRequestId.isBlank() ? String.valueOf(e.eventId()) : pickupRequestId;
    String deliveryId = deliveryId(requestKey);
    if (!store.insertClaimReturn(
        deliveryId,
        orderId,
        mfcId,
        claimId,
        originId.isBlank() ? null : originId,
        mapper.writeValueAsString(lines),
        requestKey,
        pickupRequestId.isBlank() ? null : pickupRequestId,
        now)) {
      log.info("반품 회수 요청 {} — 회수 배송 {} 이미 있음", requestKey, deliveryId);
      return;
    }
    store.insertAttempt("ATT-" + UUID.randomUUID(), deliveryId, "AUTO", now);
    events.created(store.delivery(deliveryId).orElseThrow(), List.of());
    log.info("클레임 {} → 반품 회수 배송 {} 배차 시작(주문 {}, MFC {})", claimId, deliveryId, orderId, mfcId);
  }

  /** 같은 회수 요청(pickupRequestId, 없으면 사건 ID)은 같은 배송 ID — 다시 받아도 하나. */
  static String deliveryId(String requestKey) {
    return "DLV-"
        + UUID.nameUUIDFromBytes(("claim-return:" + requestKey).getBytes(StandardCharsets.UTF_8));
  }
}
