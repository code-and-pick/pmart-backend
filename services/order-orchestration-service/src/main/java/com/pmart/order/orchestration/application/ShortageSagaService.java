package com.pmart.order.orchestration.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.order.orchestration.domain.ShortageRules;
import com.pmart.order.orchestration.infrastructure.Participants;
import com.pmart.order.orchestration.infrastructure.Participants.Ok;
import com.pmart.order.orchestration.infrastructure.Participants.Rejected;
import com.pmart.order.orchestration.infrastructure.Participants.Result;
import com.pmart.order.orchestration.infrastructure.ShortageStore;
import com.pmart.order.orchestration.infrastructure.ShortageStore.ShortageRow;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * 결품 사가(IV-09 2부, ADR-0045). MFC 가 재확보하지 못한 결품(LineShortageReported, mfc.fulfillment.v1)마다 하나 —
 * {@link ShortageRules}. 단계마다 하위 명령 키 {@code sagaId:단계}(재전송 안전). 업무 거절은 상신(ESCALATED, 수동 — BO-12
 * 미구현), 결과 모름·미전달은 같은 키로 sweep 이 다시.
 */
@Service
public class ShortageSagaService {

  private static final Logger log = LoggerFactory.getLogger(ShortageSagaService.class);

  private final ShortageStore store;
  private final Participants participants;
  private final TransactionTemplate tx;
  private final ExecutorService executor;
  private final OrchestrationProperties props;
  private final Clock clock;

  public ShortageSagaService(
      ShortageStore store,
      Participants participants,
      TransactionTemplate tx,
      ExecutorService sagaExecutor,
      OrchestrationProperties props,
      Clock clock) {
    this.store = store;
    this.participants = participants;
    this.tx = tx;
    this.executor = sagaExecutor;
    this.props = props;
    this.clock = clock;
  }

  /** mfc.fulfillment.v1 — LineShortageReported 로 시작(inbox 트랜잭션 안). 다른 사건은 지나간다. 커밋 뒤 진행. */
  public void onFulfillmentEvent(EventEnvelope e) {
    if (!"LineShortageReported".equals(e.eventType())) {
      return;
    }
    JsonNode p = e.payload();
    Instant after = props.fulfillment().startOrdersConfirmedAfter();
    Instant reported = instant(p, "reportedAt");
    if (after == null || (reported != null && reported.isBefore(after))) {
      return; // 전환 시각 전 사건(새 구독 그룹이 토픽 처음부터 읽을 때)
    }
    int qty = p.path("shortQty").asInt(0);
    String fulfillmentId = p.path("fulfillmentId").asString(null);
    if (qty <= 0 || fulfillmentId == null) {
      log.warn("결품 사건 {} 에 수량·작업 지시가 없어 무시", e.eventId());
      return;
    }
    String preference = p.path("outOfStockPreference").asString(null);
    // 오배송 재발송 작업의 결품(2026-11-16, ADR-0052 결정 4): 그 품목만 부분 취소·환불 — 주문 전체 취소 판정(CHECK_ORDER)을 건너뛴다
    boolean reshipment = "MISDELIVERY_RESHIPMENT".equals(p.path("purpose").asString(""));
    String escalate = reshipment ? null : ShortageRules.escalationAtStart(preference);
    Instant now = clock.instant();
    String sagaId = "SAGA-O-" + UUID.randomUUID();
    boolean created =
        store.insert(
            new ShortageRow(
                sagaId,
                e.eventId().toString(),
                p.path("orderId").asString(),
                fulfillmentId,
                p.path("reservationId").asString(null),
                p.path("orderLineId").asString(),
                p.path("sku").asString(null),
                qty,
                p.path("reason").asString(null),
                preference,
                escalate == null ? "RUNNING" : "ESCALATED",
                reshipment ? ShortageRules.RESOLVE_WORK : ShortageRules.firstStep(preference),
                null,
                escalate == null ? null : ShortageRules.CHECK_ORDER,
                escalate,
                escalate == null ? null : "품절 처리 선택이 없어 CS 이관(case-service 미구현 — 수동)",
                now,
                now,
                escalate == null ? null : now));
    if (!created) {
      return;
    }
    log.info(
        "결품 사가 {} 시작 — 주문 {} 줄 {} {}개, 선택 {}{}",
        sagaId,
        p.path("orderId").asString(),
        p.path("orderLineId").asString(),
        qty,
        preference,
        escalate == null ? "" : " → 상신 " + escalate);
    if (escalate == null) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              kick(sagaId);
            }
          });
    }
  }

  void kick(String sagaId) {
    executor.execute(
        () -> {
          try {
            advance(sagaId);
          } catch (RuntimeException ex) {
            log.warn("결품 사가 {} 진행 중 오류 — 재개 대기: {}", sagaId, ex.toString());
          }
        });
  }

  @Scheduled(fixedDelayString = "${pmart.orchestration.sweep-interval:PT10S}")
  public void sweep() {
    for (String sagaId : store.due(clock.instant().minus(props.stuckAfter()))) {
      try {
        advance(sagaId);
      } catch (RuntimeException ex) {
        log.warn("결품 사가 {} 재개 실패: {}", sagaId, ex.toString());
      }
    }
  }

  public Optional<ShortageRow> find(String sagaId) {
    return store.find(sagaId);
  }

  public List<ShortageRow> byOrder(String orderId) {
    return store.byOrder(orderId);
  }

  /** 행 잠금 아래에서 끝까지(또는 기다릴 지점까지). */
  public void advance(String sagaId) {
    tx.executeWithoutResult(
        status -> {
          ShortageRow s = store.lock(sagaId).orElse(null);
          while (s != null && "RUNNING".equals(s.state())) {
            if (!step(s)) {
              return;
            }
            s = store.find(sagaId).orElse(null);
          }
        });
  }

  /** 한 단계. 다음 단계로 넘어가면 true. */
  private boolean step(ShortageRow s) {
    Instant now = clock.instant();
    String step = s.currentStep();
    String key = ShortageRules.requestId(s.sagaId(), step);
    if (ShortageRules.CHECK_ORDER.equals(step)) {
      JsonNode o = participants.orderLedger(s.orderId());
      if (o == null) {
        store.touch(s.sagaId(), now); // 조회 실패 — sweep 이 다시
        return false;
      }
      int remaining = 0;
      for (JsonNode l : o.path("lines")) {
        remaining += l.path("unfulfilledQty").asInt(0);
      }
      boolean whole = ShortageRules.wholeOrder(remaining, s.shortQty());
      store.step(
          s.sagaId(), whole ? ShortageRules.REQUEST_FULL_CANCEL : ShortageRules.next(step), now);
      if (whole) {
        log.info("결품 사가 {} — 주문 {} 남은 수량 전부 결품, 전체 취소로", s.sagaId(), s.orderId());
      }
      return true;
    }
    Result r =
        switch (step) {
          case ShortageRules.REQUEST_FULL_CANCEL ->
              participants.systemCancel(
                  key,
                  Map.of(
                      "orderId",
                      s.orderId(),
                      "reason",
                      "OUT_OF_STOCK",
                      "reasonDetail",
                      "WHOLE_ORDER_OUT_OF_STOCK"
                          + " 결품 "
                          + s.orderLineId()
                          + " "
                          + s.shortQty()
                          + "개 "
                          + s.reason(),
                      "sourceRef",
                      s.sagaId()));
          case ShortageRules.RESOLVE_WORK ->
              participants.resolveShortage(
                  s.fulfillmentId(),
                  key,
                  Map.of(
                      "orderLineId",
                      s.orderLineId(),
                      "qty",
                      s.shortQty(),
                      "resolution",
                      "CANCEL_ITEM"));
          case ShortageRules.RELEASE_SHORTFALL ->
              s.reservationId() == null
                  ? new Rejected("RESERVATION_UNKNOWN", "결품 사건에 예약 ID 가 없습니다")
                  : participants.releaseShortfall(
                      s.reservationId(),
                      key,
                      Map.of(
                          "orderLineId", s.orderLineId(),
                          "qty", s.shortQty(),
                          "reason", "OUT_OF_STOCK_CANCELLED"));
          case ShortageRules.APPLY_ORDER_CHANGE -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("reason", "SHORTAGE");
            body.put("sourceRef", s.sagaId());
            body.put(
                "changes", List.of(Map.of("orderLineId", s.orderLineId(), "qty", s.shortQty())));
            yield participants.applyOrderCancel(s.orderId(), key, body);
          }
          case ShortageRules.REQUEST_REFUND ->
              participants.shortageRefund(
                  key,
                  Map.of(
                      "orderId", s.orderId(),
                      "lines", List.of(Map.of("orderLineId", s.orderLineId(), "qty", s.shortQty())),
                      "reasonDetail", "결품 " + s.reason() + " " + s.sku(),
                      "sourceRef", s.sagaId()));
          default -> throw new IllegalStateException("알 수 없는 단계 " + step);
        };
    return switch (r) {
      case Ok ok -> {
        String next = ShortageRules.next(step);
        if (next == null) {
          String claimId = ok.body().path("claimId").asString(null);
          store.finish(s.sagaId(), "COMPLETED", claimId, null, null, null, now);
          log.info(
              "결품 사가 {} 완료 — 주문 {} {} 클레임 {}",
              s.sagaId(),
              s.orderId(),
              ShortageRules.REQUEST_FULL_CANCEL.equals(step) ? "전체 취소" : "부분 환불",
              claimId);
          yield false;
        }
        store.step(s.sagaId(), next, now);
        yield true;
      }
      case Rejected rej -> {
        store.finish(s.sagaId(), "ESCALATED", null, step, rej.code(), rej.message(), now);
        log.warn("결품 사가 {} 상신 — {} {} {}", s.sagaId(), step, rej.code(), rej.message());
        yield false;
      }
      case Participants.Unknown u -> {
        store.touch(s.sagaId(), now); // 실패로 단정하지 않는다 — 같은 키로 다시(sweep)
        yield false;
      }
    };
  }

  private static @Nullable Instant instant(JsonNode n, String field) {
    String v = n.path(field).asString(null);
    return v == null || v.isBlank() ? null : Instant.parse(v);
  }
}
