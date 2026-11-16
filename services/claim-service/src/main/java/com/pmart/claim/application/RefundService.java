package com.pmart.claim.application;

import com.pmart.claim.domain.ClaimRules;
import com.pmart.claim.infrastructure.ClaimStore;
import com.pmart.claim.infrastructure.ClaimStore.ClaimRow;
import com.pmart.claim.infrastructure.ClaimStore.RefundRow;
import com.pmart.claim.infrastructure.Upstreams;
import com.pmart.common.messaging.EventEnvelope;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * 환불 의도·실행·결과(OMS 10 — 접수 → 실행 대기 → PG 결과 확인 → 완료/실패/확인 중). PG 실행은 payment-service, 멱등키는 의도마다 고정
 * ({@code REFUND-<refundIntentId>}) — 재전송·재실행이 새 금액 취소를 만들지 않는다.
 *
 * <ul>
 *   <li>PENDING_EXECUTION: payment 취소 명령 전·미전달. 응답이 오면 PG_PENDING(결과 사건·원거래 조회 대기).
 *   <li>RefundResult(order.payment.v1) 또는 원거래 조회 SUCCEEDED → COMPLETED + 클레임 CLOSED. FAILED →
 *       FAILED(운영자 확인), 불명 → UNKNOWN(payment 가 원거래 조회로 해소, 이 서비스는 같은 요청을 다시 조회).
 *   <li>환불 금액은 원 승인액 전액(전체 취소). 금액 구성 합이 승인액과 다르면 실행하지 않고 FAILED(AMOUNT_MISMATCH).
 * </ul>
 */
@Service
public class RefundService {

  private static final Logger log = LoggerFactory.getLogger(RefundService.class);

  private final ClaimStore store;
  private final Upstreams upstreams;
  private final ClaimEvents events;
  private final TransactionTemplate tx;
  private final ExecutorService executor;
  private final ClaimProperties props;
  private final Clock clock;

  public RefundService(
      ClaimStore store,
      Upstreams upstreams,
      ClaimEvents events,
      TransactionTemplate tx,
      ExecutorService refundExecutor,
      ClaimProperties props,
      Clock clock) {
    this.store = store;
    this.upstreams = upstreams;
    this.events = events;
    this.tx = tx;
    this.executor = refundExecutor;
    this.props = props;
    this.clock = clock;
  }

  /** 전체 취소 환불 의도(호출자 트랜잭션 안). RefundIntentCreated 도 함께. */
  RefundRow createFullRefund(ClaimRow c, Instant now) {
    ClaimRules.RefundAmount a =
        ClaimRules.fullRefund(c.approvedAmount(), c.itemAmount(), c.bagFee(), c.deliveryTip());
    // 앞서 결품 부분 환불이 있었으면 남은 금액만(payment FULL 취소 = 남은 잔액, ADR-0046)
    long[] prior = store.refundedForOrder(c.orderId());
    if (a != null && prior[0] > 0) {
      a =
          new ClaimRules.RefundAmount(
              a.amount() - prior[0], a.itemAmount() - prior[1], a.bagFee(), a.deliveryTip());
      log.info("클레임 {} 전체 환불 — 앞선 환불 {}원을 빼고 {}원", c.claimId(), prior[0], a.amount());
    }
    String id = "RFI-" + UUID.randomUUID();
    RefundRow r =
        new RefundRow(
            id,
            c.claimId(),
            c.orderId(),
            c.paymentAttemptId(),
            "FULL",
            "CANCEL_BEFORE_SHIP",
            a == null ? c.approvedAmount() : a.amount(),
            a == null ? c.itemAmount() : a.itemAmount(),
            c.bagFee(),
            c.deliveryTip(),
            a == null ? "FAILED" : "PENDING_EXECUTION",
            "REFUND-" + id,
            null,
            null,
            a == null ? "AMOUNT_MISMATCH" : null,
            0,
            null,
            null,
            now,
            now);
    store.insertRefund(r);
    if (a == null) {
      log.error(
          "클레임 {} 환불 금액 구성({}+{}+{})이 승인액 {}과 다르다 — 자동 환불하지 않는다",
          c.claimId(),
          c.itemAmount(),
          c.bagFee(),
          c.deliveryTip(),
          c.approvedAmount());
    } else {
      events.refundIntentCreated(r);
    }
    return r;
  }

  /**
   * 결품 부분 환불 의도(ADR-0045, 호출자 트랜잭션 안). 금액 = 원주문 단가 × 수량 합, 봉투값·배달팁 없음(부분 취소). RefundIntentCreated 도
   * 함께.
   */
  /**
   * @param itemAmount 상품값(단가 × 수량)
   * @param amount 실제 환불액(공제 뒤 — ADR-0065)
   */
  RefundRow createShortageRefund(ClaimRow c, long itemAmount, long amount, Instant now) {
    String id = "RFI-" + UUID.randomUUID();
    RefundRow r =
        new RefundRow(
            id,
            c.claimId(),
            c.orderId(),
            c.paymentAttemptId(),
            "PARTIAL",
            "OUT_OF_STOCK",
            amount,
            itemAmount,
            0,
            0,
            "PENDING_EXECUTION",
            "REFUND-" + id,
            null,
            null,
            null,
            0,
            null,
            null,
            now,
            now);
    store.insertRefund(r);
    events.refundIntentCreated(r);
    return r;
  }

  void executeAsync(String refundIntentId) {
    executor.execute(
        () -> {
          try {
            execute(refundIntentId);
          } catch (RuntimeException e) {
            log.warn("환불 {} 실행 중 오류 — 재시도 대기: {}", refundIntentId, e.toString());
          }
        });
  }

  /** payment 취소 명령. 실행 대기 상태일 때만 보낸다(같은 멱등키 — 재전송 안전). */
  public void execute(String refundIntentId) {
    tx.executeWithoutResult(
        status -> {
          RefundRow r = store.lockRefund(refundIntentId).orElse(null);
          if (r == null || !"PENDING_EXECUTION".equals(r.state())) {
            return;
          }
          Map<String, Object> body = new LinkedHashMap<>();
          body.put("refundIntentId", r.refundIntentId());
          body.put("claimId", r.claimId());
          body.put("orderId", r.orderId());
          body.put("amount", r.amount());
          body.put("scope", r.scope());
          body.put("reason", r.reason());
          Upstreams.Result res =
              upstreams.cancelPayment(r.paymentAttemptId(), r.cancellationRequestId(), body);
          Instant now = clock.instant();
          switch (res) {
            case Upstreams.Ok ok ->
                store.updateRefund(
                    r.refundIntentId(), "PG_PENDING", null, null, null, true, null, now);
            case Upstreams.Rejected rej -> {
              store.updateRefund(
                  r.refundIntentId(), "FAILED", "FAILED", null, rej.code(), true, null, now);
              log.warn("환불 {} 거절 {} {}", r.refundIntentId(), rej.code(), rej.message());
            }
            case Upstreams.Unknown u -> {
              // 실패로 단정하지 않는다 — 같은 키로 다시 보내 원결과를 받는다(sweep)
              store.updateRefund(
                  r.refundIntentId(), "PENDING_EXECUTION", null, null, u.code(), true, null, now);
            }
          }
        });
  }

  /** RefundResult(order.payment.v1) — inbox 트랜잭션 안. */
  public void onPaymentEvent(EventEnvelope e) {
    if (!"RefundResult".equals(e.eventType())) {
      return;
    }
    JsonNode p = e.payload();
    String id = p.path("refundIntentId").asString(null);
    if (id == null) {
      return;
    }
    apply(
        id,
        p.path("result").asString(""),
        p.path("pgTransactionId").asString(null),
        p.path("failureCode").asString(null));
  }

  /** 실행 대기·PG 결과 대기·불명이 오래된 환불: 재실행 또는 원거래 조회. */
  @Scheduled(fixedDelayString = "${pmart.claim.refund-sweep-interval:PT15S}")
  public void sweep() {
    Instant before = clock.instant().minus(props.refundRetryAfter());
    for (String id : store.dueRefunds(before)) {
      try {
        resume(id);
      } catch (RuntimeException e) {
        log.warn("환불 {} 재개 실패: {}", id, e.toString());
      }
    }
  }

  public void resume(String refundIntentId) {
    RefundRow r = tx.execute(status -> store.lockRefund(refundIntentId).orElse(null));
    if (r == null) {
      return;
    }
    if ("PENDING_EXECUTION".equals(r.state())) {
      execute(refundIntentId);
      return;
    }
    JsonNode c = upstreams.cancellation(r.paymentAttemptId(), r.cancellationRequestId());
    String state = c == null ? null : c.path("state").asString(null);
    if (state == null) {
      tx.executeWithoutResult(
          s ->
              store.updateRefund(
                  r.refundIntentId(),
                  r.state(),
                  null,
                  null,
                  r.failureCode(),
                  false,
                  null,
                  clock.instant()));
      return;
    }
    String result =
        switch (state) {
          case "SUCCEEDED" -> "SUCCESS";
          case "FAILED", "REJECTED" -> "FAILED";
          case "UNKNOWN" -> "UNKNOWN";
          default -> "PENDING";
        };
    apply(
        refundIntentId,
        result,
        c.path("pgCancelTransactionId").asString(null),
        c.path("pgResultCode").asString(null));
  }

  /** PG 결과 반영. SUCCESS 면 환불 완료 + 클레임 CLOSED + ClaimClosed. 이미 완료면 무시. */
  private void apply(
      String refundIntentId,
      String result,
      @Nullable String pgTransactionId,
      @Nullable String failureCode) {
    tx.executeWithoutResult(
        status -> {
          RefundRow r = store.lockRefund(refundIntentId).orElse(null);
          if (r == null || "COMPLETED".equals(r.state())) {
            return;
          }
          Instant now = clock.instant();
          switch (result) {
            case "SUCCESS" -> {
              store.updateRefund(
                  r.refundIntentId(),
                  "COMPLETED",
                  "SUCCESS",
                  pgTransactionId,
                  null,
                  false,
                  now,
                  now);
              ClaimRow c = store.lock(r.claimId()).orElseThrow();
              if (!"CLOSED".equals(c.state())) {
                store.updateState(c.claimId(), "CLOSED", null, null, now, now);
                events.claimClosed(c, "CANCELLED_REFUNDED", now);
              }
              log.info("환불 {} 완료 — 클레임 {} 종결", r.refundIntentId(), r.claimId());
            }
            case "FAILED" ->
                store.updateRefund(
                    r.refundIntentId(),
                    "FAILED",
                    "FAILED",
                    pgTransactionId,
                    failureCode,
                    false,
                    null,
                    now);
            case "UNKNOWN" ->
                store.updateRefund(
                    r.refundIntentId(),
                    "UNKNOWN",
                    "UNKNOWN",
                    pgTransactionId,
                    failureCode,
                    false,
                    null,
                    now);
            default ->
                store.updateRefund(
                    r.refundIntentId(), r.state(), null, null, r.failureCode(), false, null, now);
          }
        });
  }
}
