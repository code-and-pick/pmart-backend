package com.pmart.delivery.execution.api;

import com.pmart.delivery.execution.api.generated.CompensationsApi;
import com.pmart.delivery.execution.api.generated.model.ListRiderCompensations200Response;
import com.pmart.delivery.execution.api.generated.model.RecordRiderCompensationRequest;
import com.pmart.delivery.execution.api.generated.model.RiderCompensation;
import com.pmart.delivery.execution.api.generated.model.SettlementState;
import com.pmart.delivery.execution.application.Compensations;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 이의 인정 보상금(2026-11-19) — 규칙은 {@link Compensations}. */
@RestController
public class CompensationsController implements CompensationsApi {

  private final Compensations compensations;

  public CompensationsController(Compensations compensations) {
    this.compensations = compensations;
  }

  @Override
  public ResponseEntity<RiderCompensation> recordRiderCompensation(
      String idempotencyKey, String xSourceSystem, RecordRiderCompensationRequest req) {
    return ResponseEntity.ok(
        view(
            compensations.record(
                idempotencyKey, req.getAppealId(), req.getAmount(), req.getReason())));
  }

  @Override
  public ResponseEntity<ListRiderCompensations200Response> listRiderCompensations(
      @Nullable String riderId, @Nullable String deliveryId, @Nullable String appealId) {
    return ResponseEntity.ok(
        new ListRiderCompensations200Response()
            .items(
                compensations.forStaff(blank(riderId), blank(deliveryId), blank(appealId)).stream()
                    .map(CompensationsController::view)
                    .toList()));
  }

  @Override
  public ResponseEntity<ListRiderCompensations200Response> listMyCompensations() {
    return ResponseEntity.ok(
        new ListRiderCompensations200Response()
            .items(compensations.mine().stream().map(CompensationsController::view).toList()));
  }

  private static RiderCompensation view(Compensations.Row c) {
    return new RiderCompensation()
        .compensationId(c.compensationId())
        .appealId(c.appealId())
        .deliveryId(c.deliveryId())
        .riderId(c.riderId())
        .amount(c.amount())
        .reason(c.reason())
        .settlementState(SettlementState.fromValue(c.settlementState()))
        .payoutDate(c.payoutDate())
        .recordedBy(c.recordedBy())
        .recordedAt(c.recordedAt().atOffset(ZoneOffset.UTC));
  }

  private static @Nullable String blank(@Nullable String s) {
    return s == null || s.isBlank() ? null : s;
  }
}
