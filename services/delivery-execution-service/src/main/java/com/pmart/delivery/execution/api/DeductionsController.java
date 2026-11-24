package com.pmart.delivery.execution.api;

import com.pmart.delivery.execution.api.generated.DeductionsApi;
import com.pmart.delivery.execution.api.generated.model.DecideRiderDeductionRequest;
import com.pmart.delivery.execution.api.generated.model.ObjectMyDeductionRequest;
import com.pmart.delivery.execution.api.generated.model.RiderDeduction;
import com.pmart.delivery.execution.api.generated.model.RiderDeductionApplicationsInner;
import com.pmart.delivery.execution.api.generated.model.RiderDeductionDecision;
import com.pmart.delivery.execution.api.generated.model.RiderDeductionList;
import com.pmart.delivery.execution.api.generated.model.RiderDeductionObjection;
import com.pmart.delivery.execution.api.generated.model.RiderDeductionState;
import com.pmart.delivery.execution.application.RiderDeductions;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 오배송 정산 차감(2026-11-24, ADR-0086 — DE-9) — 규칙은 {@link RiderDeductions}. */
@RestController
public class DeductionsController implements DeductionsApi {

  private final RiderDeductions deductions;

  public DeductionsController(RiderDeductions deductions) {
    this.deductions = deductions;
  }

  @Override
  public ResponseEntity<RiderDeductionList> listRiderDeductions(
      @Nullable String riderId, @Nullable RiderDeductionState state, @Nullable String caseId) {
    return ResponseEntity.ok(
        view(
            deductions.forStaff(
                blank(riderId), state == null ? null : state.getValue(), blank(caseId))));
  }

  @Override
  public ResponseEntity<RiderDeduction> decideRiderDeduction(
      String deductionId,
      String idempotencyKey,
      String xSourceSystem,
      DecideRiderDeductionRequest req) {
    return ResponseEntity.ok(
        view(
            deductions.decide(
                deductionId, idempotencyKey, req.getDecision().getValue(), req.getNote())));
  }

  @Override
  public ResponseEntity<RiderDeductionList> listMyDeductions() {
    return ResponseEntity.ok(view(deductions.mine()));
  }

  @Override
  public ResponseEntity<RiderDeduction> objectMyDeduction(
      String deductionId,
      String idempotencyKey,
      String xSourceSystem,
      ObjectMyDeductionRequest req) {
    return ResponseEntity.ok(view(deductions.object(deductionId, idempotencyKey, req.getNote())));
  }

  private static RiderDeductionList view(RiderDeductions.Listing l) {
    return new RiderDeductionList()
        .items(l.items().stream().map(DeductionsController::view).toList())
        .remainingTotal(l.remainingTotal() == null ? null : l.remainingTotal().intValue())
        .pendingTotal(l.pendingTotal() == null ? null : l.pendingTotal().intValue());
  }

  private static RiderDeduction view(RiderDeductions.Row d) {
    return new RiderDeduction()
        .deductionId(d.deductionId())
        .caseId(d.caseId())
        .deliveryId(d.deliveryId())
        .deliveryNumber(d.deliveryNumber())
        .recoveryDeliveryId(d.recoveryDeliveryId())
        .orderId(d.orderId())
        .riderId(d.riderId())
        .amount(d.amount())
        .remaining(d.remaining())
        .state(RiderDeductionState.fromValue(d.state()))
        .objectionDeadline(at(d.objectionDeadline()))
        .objection(
            d.objectedAt() == null
                ? null
                : new RiderDeductionObjection()
                    .note(d.objectionNote())
                    .objectedAt(at(d.objectedAt())))
        .decision(
            d.decision() == null
                ? null
                : new RiderDeductionDecision()
                    .result(RiderDeductionDecision.ResultEnum.fromValue(d.decision()))
                    .note(d.decisionNote())
                    .decidedBy(d.decidedBy())
                    .decidedAt(at(d.decidedAt())))
        .confirmedAt(at(d.confirmedAt()))
        .applications(
            d.applications().stream()
                .map(
                    a ->
                        new RiderDeductionApplicationsInner()
                            .payoutDate(a.payoutDate())
                            .amount(a.amount()))
                .toList())
        .createdAt(at(d.createdAt()));
  }

  private static @Nullable OffsetDateTime at(@Nullable Instant i) {
    return i == null ? null : i.atOffset(ZoneOffset.UTC);
  }

  private static @Nullable String blank(@Nullable String s) {
    return s == null || s.isBlank() ? null : s;
  }
}
