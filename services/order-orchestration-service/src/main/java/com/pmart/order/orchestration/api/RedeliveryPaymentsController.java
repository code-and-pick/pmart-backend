package com.pmart.order.orchestration.api;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.order.orchestration.api.generated.RedeliveryPaymentApi;
import com.pmart.order.orchestration.api.generated.model.ListRedeliveryPayments200Response;
import com.pmart.order.orchestration.api.generated.model.RedeliveryPayment;
import com.pmart.order.orchestration.application.RedeliveryPaymentService;
import com.pmart.order.orchestration.domain.RedeliveryPaymentRules;
import com.pmart.order.orchestration.infrastructure.RedeliveryPaymentStore.Row;
import java.time.ZoneOffset;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 고객 앱 — 오배송 다시 배송 9,000원 결제 대기(ADR-0065). 고객 토큰만, 본인(주문 accountId) 것만. */
@RestController
public class RedeliveryPaymentsController implements RedeliveryPaymentApi {

  private final RedeliveryPaymentService payments;
  private final RequestIdempotency idempotency;

  RedeliveryPaymentsController(RedeliveryPaymentService payments, RequestIdempotency idempotency) {
    this.payments = payments;
    this.idempotency = idempotency;
  }

  @Override
  public ResponseEntity<ListRedeliveryPayments200Response> listRedeliveryPayments(
      @Nullable String orderId) {
    String me = customer();
    return ResponseEntity.ok(
        new ListRedeliveryPayments200Response()
            .items(
                payments.mine(me, orderId).stream()
                    .map(RedeliveryPaymentsController::view)
                    .toList()));
  }

  @Override
  public ResponseEntity<RedeliveryPayment> retryRedeliveryPayment(
      String idempotencyKey, String xSourceSystem, String redeliveryPaymentId) {
    String me = customer();
    idempotency.execute(
        new RequestKey(xSourceSystem, idempotencyKey, "retryRedeliveryPayment", me),
        Map.of("redeliveryPaymentId", redeliveryPaymentId),
        String.class,
        () -> payments.retry(redeliveryPaymentId, me).sagaId());
    return ResponseEntity.ok(current(redeliveryPaymentId, me));
  }

  @Override
  public ResponseEntity<RedeliveryPayment> declineRedeliveryPayment(
      String idempotencyKey, String xSourceSystem, String redeliveryPaymentId) {
    String me = customer();
    idempotency.execute(
        new RequestKey(xSourceSystem, idempotencyKey, "declineRedeliveryPayment", me),
        Map.of("redeliveryPaymentId", redeliveryPaymentId),
        String.class,
        () -> payments.decline(redeliveryPaymentId, me).sagaId());
    return ResponseEntity.ok(current(redeliveryPaymentId, me));
  }

  private RedeliveryPayment current(String id, String me) {
    return payments.mine(me, null).stream()
        .filter(r -> r.sagaId().equals(id))
        .findFirst()
        .map(RedeliveryPaymentsController::view)
        .orElseThrow(
            () -> PmartException.notFound("REDELIVERY_PAYMENT_NOT_FOUND", "결제 대기 건을 찾을 수 없습니다."));
  }

  private static String customer() {
    PmartPrincipal p =
        PmartPrincipal.current()
            .filter(x -> x.type() == PmartPrincipal.PrincipalType.CUSTOMER)
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "고객만 볼 수 있습니다."));
    return p.subject();
  }

  static RedeliveryPayment view(Row r) {
    return new RedeliveryPayment()
        .redeliveryPaymentId(r.sagaId())
        .orderId(r.orderId())
        .caseId(r.caseId())
        .amount((int) r.amount())
        .orderName(RedeliveryPaymentRules.ORDER_NAME)
        .paymentAttemptId(r.paymentAttemptId())
        .status(RedeliveryPayment.StatusEnum.fromValue(RedeliveryPaymentRules.status(r.outcome())))
        .deadlineAt(r.deadlineAt().atOffset(ZoneOffset.UTC))
        .createdAt(r.createdAt().atOffset(ZoneOffset.UTC));
  }
}
