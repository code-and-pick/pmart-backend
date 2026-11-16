package com.pmart.order.orchestration.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * 오배송 고객 잘못 다시 배송 9,000원 결제 대기(ADR-0065 결정 3, 2026-11-16 사용자 "9,000원 결제 대기 30분, 넘으면 회수 후 취소·환불").
 */
public final class RedeliveryPaymentRules {

  public static final String PREPARE_PAYMENT = "PREPARE_PAYMENT";
  public static final String AWAIT_PAYMENT = "AWAIT_PAYMENT";
  public static final String RELEASE_DELIVERY = "RELEASE_DELIVERY";
  public static final String CONVERT_TO_RECOVERY = "CONVERT_TO_RECOVERY";
  public static final String CANCEL_LATE_PAYMENT = "CANCEL_LATE_PAYMENT";
  public static final String DONE = "DONE";

  /** 결제 대기 시간 — ADR-0065 정한 값. */
  public static final Duration WAIT = Duration.ofMinutes(30);

  /** 고객 부담(원) — 업무 원문 §9.2 회수재배송 9000원. */
  public static final long FEE = ReshipmentRules.CUSTOMER_FAULT_FEE;

  public static final String ORDER_NAME = "오배송 다시 배송비";

  /** 결과가 아직 안 난 결제 상태(payment PaymentState) — 기한이 지나도 결과를 기다린다. */
  private static final Set<String> IN_FLIGHT = Set.of("PENDING", "UNKNOWN", "MISMATCH");

  /** 새 결제 시도가 필요한 상태(실패·취소). CREATED·PENDING 은 그대로 쓴다. */
  private static final Set<String> DEAD = Set.of("FAILED", "CANCELLED", "PARTIALLY_CANCELLED");

  private RedeliveryPaymentRules() {}

  /** 결제 주문 참조 — 원래 주문 결제와 별개(같은 주문에 승인된 결제가 있어도 새 시도를 막지 않게). */
  public static String chargeRef(String caseId) {
    return "RDL-" + caseId;
  }

  public static String requestId(String sagaId, String step, int attemptNo) {
    return sagaId + ":" + step + ":" + attemptNo;
  }

  public static boolean inFlight(@Nullable String paymentState) {
    return paymentState != null && IN_FLIGHT.contains(paymentState);
  }

  public static boolean dead(@Nullable String paymentState) {
    return paymentState != null && DEAD.contains(paymentState);
  }

  public static boolean expired(Instant deadline, Instant now) {
    return !now.isBefore(deadline);
  }

  /** 고객에게 보이는 상태. */
  public static String status(@Nullable String outcome) {
    return outcome == null ? "WAITING_PAYMENT" : outcome;
  }
}
