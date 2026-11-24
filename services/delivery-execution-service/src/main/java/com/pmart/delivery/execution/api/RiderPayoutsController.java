package com.pmart.delivery.execution.api;

import com.pmart.delivery.execution.api.generated.RiderPayoutsApi;
import com.pmart.delivery.execution.api.generated.model.ListRiderPayouts200Response;
import com.pmart.delivery.execution.api.generated.model.RecordRiderPayoutResultRequest;
import com.pmart.delivery.execution.api.generated.model.RiderPayout;
import com.pmart.delivery.execution.api.generated.model.RiderPayoutLastResult;
import com.pmart.delivery.execution.application.PayoutResults;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 수행료 지급 결과(2026-11-18) — 규칙은 {@link PayoutResults}. */
@RestController
public class RiderPayoutsController implements RiderPayoutsApi {

  private final PayoutResults payouts;

  public RiderPayoutsController(PayoutResults payouts) {
    this.payouts = payouts;
  }

  @Override
  public ResponseEntity<ListRiderPayouts200Response> listRiderPayouts(
      @Nullable LocalDate payoutDate,
      @Nullable LocalDate from,
      @Nullable LocalDate to,
      @Nullable String riderId,
      @Nullable String state) {
    return ResponseEntity.ok(
        new ListRiderPayouts200Response()
            .items(
                payouts.list(payoutDate, from, to, blank(riderId), blank(state)).stream()
                    .map(RiderPayoutsController::view)
                    .toList()));
  }

  @Override
  public ResponseEntity<RiderPayout> recordRiderPayoutResult(
      String riderId,
      LocalDate payoutDate,
      String idempotencyKey,
      String xSourceSystem,
      RecordRiderPayoutResultRequest body) {
    return ResponseEntity.ok(
        view(
            payouts.recordByOperator(
                riderId,
                payoutDate,
                body.getResult().getValue(),
                body.getReference(),
                body.getFailureReason(),
                idempotencyKey)));
  }

  @Override
  public ResponseEntity<RiderPayout> recordRiderPayoutResultInternal(
      String riderId,
      LocalDate payoutDate,
      String idempotencyKey,
      String xSourceSystem,
      RecordRiderPayoutResultRequest body) {
    return ResponseEntity.ok(
        view(
            payouts.recordByService(
                riderId,
                payoutDate,
                body.getResult().getValue(),
                body.getReference(),
                body.getFailureReason(),
                idempotencyKey)));
  }

  @Override
  public ResponseEntity<
          com.pmart.delivery.execution.api.generated.model.ListRiderPayoutRollovers200Response>
      listRiderPayoutRollovers(@Nullable String riderId) {
    return ResponseEntity.ok(
        new com.pmart.delivery.execution.api.generated.model.ListRiderPayoutRollovers200Response()
            .items(
                payouts.rollovers(blank(riderId)).stream()
                    .map(
                        o ->
                            new com.pmart.delivery.execution.api.generated.model
                                    .ListRiderPayoutRollovers200ResponseItemsInner()
                                .rolloverId(o.rolloverId())
                                .riderId(o.riderId())
                                .fromDate(o.fromDate())
                                .toDate(o.toDate())
                                .lineCount(o.lineCount())
                                .total((int) o.total())
                                .failureReason(o.failureReason())
                                .rolledAt(o.rolledAt().atOffset(java.time.ZoneOffset.UTC)))
                    .toList()));
  }

  private static RiderPayout view(PayoutResults.Batch b) {
    RiderPayout v =
        new RiderPayout()
            .riderId(b.riderId())
            .payoutDate(b.payoutDate())
            .count(b.count())
            .total((int) b.total())
            .state(RiderPayout.StateEnum.fromValue(b.state()))
            .compensationCount(b.compensationCount())
            .deductionTotal((int) b.deductionTotal())
            .expectedDeduction((int) b.expectedDeduction())
            .netTotal((int) b.netTotal())
            .rolledTo(b.rolledTo())
            .rolledInCount(b.rolledInCount());
    if (b.lastResult() != null) {
      var l = b.lastResult();
      v.lastResult(
          new RiderPayoutLastResult()
              .result(RiderPayoutLastResult.ResultEnum.fromValue(l.result()))
              .reference(l.reference())
              .failureReason(l.failureReason())
              .recordedBy(l.recordedBy())
              .recordedAt(l.recordedAt().atOffset(ZoneOffset.UTC)));
    }
    return v;
  }

  private static @Nullable String blank(@Nullable String s) {
    return s == null || s.isBlank() ? null : s;
  }
}
