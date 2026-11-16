package com.pmart.order.orchestration.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.web.PmartException;
import com.pmart.order.orchestration.domain.RedeliveryPaymentRules;
import com.pmart.order.orchestration.domain.ReshipmentRules;
import com.pmart.order.orchestration.infrastructure.Participants;
import com.pmart.order.orchestration.infrastructure.Participants.Ok;
import com.pmart.order.orchestration.infrastructure.Participants.Rejected;
import com.pmart.order.orchestration.infrastructure.Participants.Result;
import com.pmart.order.orchestration.infrastructure.RedeliveryPaymentStore;
import com.pmart.order.orchestration.infrastructure.RedeliveryPaymentStore.Row;
import com.pmart.order.orchestration.infrastructure.ReshipmentStore;
import java.nio.charset.StandardCharsets;
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
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * 오배송 고객 잘못 다시 배송 9,000원 결제 대기(ADR-0065 결정 3, 4단계). dispatch {@code RedeliveryPaymentRequired} 로 시작
 * → 결제 시도 생성(주문 참조 {@code RDL-<caseId>}, customerRef = 주문 accountId, 만료 = 기한) → 고객 앱 결제 → 승인이면
 * dispatch 다시 배송 풀기. 기한(30분)까지 승인이 없거나 고객이 거절하면 dispatch 에 회수로 바꾸기 + 재발송 사가 CUSTOMER_REFUND(회수 후
 * 상품값 − 9,000원). 기한 뒤 늦은 승인은 보상 취소. 결제가 진행 중(PENDING·UNKNOWN·MISMATCH)이면 기한이 지나도 결과를 기다린다.
 */
@Service
public class RedeliveryPaymentService {

  private static final Logger log = LoggerFactory.getLogger(RedeliveryPaymentService.class);

  private final RedeliveryPaymentStore store;
  private final ReshipmentStore reshipments;
  private final ReshipmentSagaService reshipmentSagas;
  private final Participants participants;
  private final TransactionTemplate tx;
  private final ExecutorService executor;
  private final OrchestrationProperties props;
  private final Clock clock;

  public RedeliveryPaymentService(
      RedeliveryPaymentStore store,
      ReshipmentStore reshipments,
      ReshipmentSagaService reshipmentSagas,
      Participants participants,
      TransactionTemplate tx,
      ExecutorService sagaExecutor,
      OrchestrationProperties props,
      Clock clock) {
    this.store = store;
    this.reshipments = reshipments;
    this.reshipmentSagas = reshipmentSagas;
    this.participants = participants;
    this.tx = tx;
    this.executor = sagaExecutor;
    this.props = props;
    this.clock = clock;
  }

  // ---------- 사건 ----------

  /** delivery.delivery.v1 — RedeliveryPaymentRequired 로 시작(inbox 트랜잭션 안). 다른 사건은 지나간다. */
  public void onDeliveryEvent(EventEnvelope e) {
    if (!"RedeliveryPaymentRequired".equals(e.eventType())) {
      return;
    }
    JsonNode p = e.payload();
    String caseId = p.path("caseId").asString("");
    String deliveryId = p.path("deliveryId").asString("");
    String orderId = p.path("orderId").asString("");
    if (caseId.isBlank() || deliveryId.isBlank() || orderId.isBlank()) {
      log.warn("다시 배송 결제 사건 {} 에 caseId·deliveryId·orderId 가 없음", e.eventId());
      return;
    }
    Instant now = clock.instant();
    Instant requiredAt = instant(p.path("requiredAt").asString(null), now);
    long fee = p.path("fee").isNumber() ? p.path("fee").asLong() : RedeliveryPaymentRules.FEE;
    String sagaId = sagaId(caseId);
    if (!store.insert(
        sagaId,
        caseId,
        deliveryId,
        p.path("originDeliveryId").asString(null),
        orderId,
        fee,
        requiredAt.plus(RedeliveryPaymentRules.WAIT),
        now)) {
      return;
    }
    log.info("다시 배송 결제 대기 {} 시작 — 사건 {} 배송 {} {}원", sagaId, caseId, deliveryId, fee);
    afterCommit(sagaId);
  }

  /** order.payment.v1 — 이 사가의 결제 시도면 깨운다. 끝난 사가에 늦은 승인이면 보상 취소로 다시 연다. */
  public void onPaymentEvent(EventEnvelope e) {
    String attemptId = e.payload().path("paymentAttemptId").asString(null);
    if (attemptId == null) {
      return;
    }
    store
        .byPaymentAttempt(attemptId)
        .ifPresent(
            sagaId -> {
              Row r = store.find(sagaId).orElseThrow();
              if ("PaymentApproved".equals(e.eventType())
                  && "COMPLETED".equals(r.state())
                  && !"PAID".equals(r.outcome())) {
                store.reopen(sagaId, RedeliveryPaymentRules.CANCEL_LATE_PAYMENT, clock.instant());
              }
              afterCommit(sagaId);
            });
  }

  // ---------- 고객 ----------

  public List<Row> mine(String accountId, @Nullable String orderId) {
    return store.byAccount(accountId, orderId);
  }

  /** 지금 쓸 결제 시도(앞 시도가 실패·취소면 새로). 남의 것 404, 결제 대기가 아니면 409. */
  public Row retry(String sagaId, String accountId) {
    return tx.execute(
        s -> {
          Row r = mineLocked(sagaId, accountId);
          Instant now = clock.instant();
          if (!RedeliveryPaymentRules.AWAIT_PAYMENT.equals(r.currentStep())
              || r.outcome() != null
              || RedeliveryPaymentRules.expired(r.deadlineAt(), now)) {
            throw PmartException.invalidState("결제를 기다리는 건이 아닙니다.");
          }
          String state = paymentState(r);
          if (RedeliveryPaymentRules.dead(state)) {
            startPayment(r, r.accountId(), now);
          }
          return store.find(sagaId).orElseThrow();
        });
  }

  /** 결제하지 않고 회수 후 환불. 결제됐으면 409 ALREADY_PAID. */
  public Row decline(String sagaId, String accountId) {
    Row out =
        tx.execute(
            s -> {
              Row r = mineLocked(sagaId, accountId);
              if ("PAID".equals(r.outcome())) {
                throw new PmartException(
                    "ALREADY_PAID", HttpStatus.CONFLICT, "이미 결제되어 다시 배송 중입니다.", false, List.of());
              }
              if (r.outcome() == null) {
                store.decline(sagaId, clock.instant());
                log.info("다시 배송 결제 대기 {} — 고객이 회수 후 환불 고름", sagaId);
              }
              return store.find(sagaId).orElseThrow();
            });
    kick(sagaId);
    return out;
  }

  private Row mineLocked(String sagaId, String accountId) {
    Row r = store.lock(sagaId).orElse(null);
    if (r == null || !accountId.equals(r.accountId())) {
      throw PmartException.notFound("REDELIVERY_PAYMENT_NOT_FOUND", "결제 대기 건을 찾을 수 없습니다.");
    }
    return r;
  }

  // ---------- 진행 ----------

  void afterCommit(String sagaId) {
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            kick(sagaId);
          }
        });
  }

  void kick(String sagaId) {
    executor.execute(
        () -> {
          try {
            advance(sagaId);
          } catch (RuntimeException ex) {
            log.warn("다시 배송 결제 대기 {} 진행 중 오류 — 재개 대기: {}", sagaId, ex.toString());
          }
        });
  }

  @Scheduled(fixedDelayString = "${pmart.orchestration.sweep-interval:PT10S}")
  public void sweep() {
    Instant now = clock.instant();
    for (String sagaId : store.due(now.minus(props.stuckAfter()), now)) {
      try {
        advance(sagaId);
      } catch (RuntimeException ex) {
        log.warn("다시 배송 결제 대기 {} 재개 실패: {}", sagaId, ex.toString());
      }
    }
  }

  public Optional<Row> byCase(String caseId) {
    return store.byCase(caseId);
  }

  /** 행 잠금 아래에서 기다릴 지점까지. */
  public void advance(String sagaId) {
    tx.executeWithoutResult(
        status -> {
          Row r = store.lock(sagaId).orElse(null);
          while (r != null && "RUNNING".equals(r.state())) {
            if (!step(r)) {
              return;
            }
            r = store.find(sagaId).orElse(null);
          }
        });
  }

  private boolean step(Row r) {
    Instant now = clock.instant();
    return switch (r.currentStep()) {
      case RedeliveryPaymentRules.PREPARE_PAYMENT -> prepare(r, now);
      case RedeliveryPaymentRules.AWAIT_PAYMENT -> await(r, now);
      case RedeliveryPaymentRules.RELEASE_DELIVERY -> release(r, now);
      case RedeliveryPaymentRules.CONVERT_TO_RECOVERY -> convert(r, now);
      case RedeliveryPaymentRules.CANCEL_LATE_PAYMENT -> cancelLate(r, now);
      default -> throw new IllegalStateException("알 수 없는 단계 " + r.currentStep());
    };
  }

  private boolean prepare(Row r, Instant now) {
    if (r.outcome() != null || RedeliveryPaymentRules.expired(r.deadlineAt(), now)) {
      // 결제 시도를 만들기 전에 거절·기한
      store.step(
          r.sagaId(),
          RedeliveryPaymentRules.CONVERT_TO_RECOVERY,
          r.outcome() == null ? "TIMED_OUT" : null,
          now);
      return true;
    }
    JsonNode order = participants.orderLedger(r.orderId());
    String account = order == null ? "" : order.path("accountId").asString("");
    if (account.isBlank()) {
      store.error(
          r.sagaId(), r.currentStep(), "ORDER_NOT_FOUND", "주문 소유자를 읽지 못함(다시 시도)", false, now);
      return false;
    }
    return startPayment(r, account, now);
  }

  /** 결제 시도 생성(시도 번호마다 키). 성공이면 AWAIT_PAYMENT. */
  private boolean startPayment(Row r, @Nullable String account, Instant now) {
    if (account == null) {
      return false;
    }
    int next = r.attemptNo() + 1;
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("orderId", RedeliveryPaymentRules.chargeRef(r.caseId()));
    body.put("sagaId", r.sagaId());
    body.put("amount", r.amount());
    body.put("currency", "KRW");
    body.put("method", "TOSS_PAYMENTS");
    body.put("customerRef", account);
    body.put("orderName", RedeliveryPaymentRules.ORDER_NAME);
    body.put("resourceExpiresAt", r.deadlineAt().toString());
    Result res =
        participants.startPayment(RedeliveryPaymentRules.requestId(r.sagaId(), "PAY", next), body);
    if (res instanceof Ok ok) {
      store.attempt(r.sagaId(), account, ok.resultId(), next, now);
      log.info("다시 배송 결제 대기 {} — 결제 시도 {}", r.sagaId(), ok.resultId());
      return true;
    }
    String code = res instanceof Rejected rj ? rj.code() : "UNKNOWN_RESULT";
    store.error(r.sagaId(), r.currentStep(), code, "결제 시도 생성 실패(다시 시도)", false, now);
    return false;
  }

  private boolean await(Row r, Instant now) {
    String state = paymentState(r);
    if ("APPROVED".equals(state)) {
      store.step(r.sagaId(), RedeliveryPaymentRules.RELEASE_DELIVERY, "PAID", now);
      return true;
    }
    if (RedeliveryPaymentRules.inFlight(state) || state == null) {
      return false; // 결과(또는 조회)를 기다린다
    }
    if ("DECLINED".equals(r.outcome())) {
      store.step(r.sagaId(), RedeliveryPaymentRules.CONVERT_TO_RECOVERY, null, now);
      return true;
    }
    if (RedeliveryPaymentRules.expired(r.deadlineAt(), now)) {
      store.step(r.sagaId(), RedeliveryPaymentRules.CONVERT_TO_RECOVERY, "TIMED_OUT", now);
      return true;
    }
    return false;
  }

  private boolean release(Row r, Instant now) {
    Result res =
        participants.releaseRedelivery(
            r.deliveryId(),
            RedeliveryPaymentRules.requestId(r.sagaId(), r.currentStep(), 0),
            r.caseId(),
            r.paymentAttemptId());
    if (res instanceof Ok) {
      store.finish(r.sagaId(), now);
      log.info("다시 배송 결제 대기 {} — 결제됨, 배송 {} 배차 풀림", r.sagaId(), r.deliveryId());
      return false;
    }
    escalateOrRetry(r, res, "결제됐는데 다시 배송 배차를 풀지 못함", now);
    return false;
  }

  private boolean convert(Row r, Instant now) {
    Result res =
        participants.cancelRedelivery(
            r.deliveryId(),
            RedeliveryPaymentRules.requestId(r.sagaId(), r.currentStep(), 0),
            r.caseId(),
            r.outcome() == null ? "TIMED_OUT" : r.outcome());
    if (!(res instanceof Ok)) {
      escalateOrRetry(r, res, "다시 배송을 회수로 바꾸지 못함", now);
      return false;
    }
    // 회수 후 상품값 − 9,000원 환불(재발송 사가 CUSTOMER_REFUND — 같은 사건이면 하나)
    String refundSaga = "SAGA-R-" + UUID.randomUUID();
    if (r.originDeliveryId() != null
        && reshipments.insert(
            refundSaga,
            r.caseId(),
            r.originDeliveryId(),
            r.orderId(),
            now,
            "CUSTOMER_REFUND",
            ReshipmentRules.RESOLVE_ORDER,
            now)) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              reshipmentSagas.kick(refundSaga);
            }
          });
    }
    store.finish(r.sagaId(), now);
    log.info(
        "다시 배송 결제 대기 {} — {}: 배송 {} 회수로, 환불 사가 {}",
        r.sagaId(),
        r.outcome(),
        r.deliveryId(),
        refundSaga);
    return false;
  }

  private boolean cancelLate(Row r, Instant now) {
    if (r.paymentAttemptId() == null) {
      store.finish(r.sagaId(), now);
      return false;
    }
    Result res =
        participants.compensationCancel(
            r.paymentAttemptId(),
            RedeliveryPaymentRules.requestId(r.sagaId(), r.currentStep(), r.attemptNo()),
            r.sagaId(),
            r.currentStep(),
            "REDELIVERY_PAYMENT_TOO_LATE");
    if (res instanceof Ok) {
      store.finish(r.sagaId(), now);
      log.info("다시 배송 결제 대기 {} — 기한 뒤 승인 {} 보상 취소", r.sagaId(), r.paymentAttemptId());
      return false;
    }
    escalateOrRetry(r, res, "기한 뒤 승인된 결제를 취소하지 못함", now);
    return false;
  }

  /** 업무 거절은 사람 처리 대기(처리 대기 목록), 불명·장애는 같은 키로 sweep 이 다시. */
  private void escalateOrRetry(Row r, Result res, String what, Instant now) {
    if (res instanceof Rejected rj) {
      store.error(r.sagaId(), r.currentStep(), rj.code(), what + " — " + rj.message(), true, now);
      log.warn("다시 배송 결제 대기 {} 멈춤 — {}: {}", r.sagaId(), what, rj.code());
    } else {
      store.error(r.sagaId(), r.currentStep(), "UNKNOWN_RESULT", what + "(다시 시도)", false, now);
    }
  }

  private @Nullable String paymentState(Row r) {
    return r.paymentAttemptId() == null ? null : participants.paymentState(r.paymentAttemptId());
  }

  static String sagaId(String caseId) {
    return "SAGA-RDP-"
        + UUID.nameUUIDFromBytes(("redelivery-payment:" + caseId).getBytes(StandardCharsets.UTF_8));
  }

  private static Instant instant(@Nullable String s, Instant fallback) {
    try {
      return s == null ? fallback : Instant.parse(s);
    } catch (RuntimeException e) {
      return fallback;
    }
  }
}
