package com.pmart.delivery.execution.api;

import com.pmart.delivery.execution.api.generated.FailureReversalsApi;
import com.pmart.delivery.execution.api.generated.model.ReportStoreArrival200Response;
import com.pmart.delivery.execution.api.generated.model.ReverseDeliveryFailureRequest;
import com.pmart.delivery.execution.application.FailureReversalService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 전달 실패 확정 되돌리기(2026-11-19) — 규칙은 {@link FailureReversalService}. */
@RestController
public class FailureReversalsController implements FailureReversalsApi {

  private final FailureReversalService reversals;

  public FailureReversalsController(FailureReversalService reversals) {
    this.reversals = reversals;
  }

  @Override
  public ResponseEntity<ReportStoreArrival200Response> reverseDeliveryFailure(
      String deliveryId,
      String idempotencyKey,
      String xSourceSystem,
      ReverseDeliveryFailureRequest body) {
    return ResponseEntity.ok(
        ExecutionViews.applied(
            reversals.reverse(deliveryId, idempotencyKey, xSourceSystem, body.getReason())));
  }
}
