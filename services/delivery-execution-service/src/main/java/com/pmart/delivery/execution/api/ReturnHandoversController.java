package com.pmart.delivery.execution.api;

import com.pmart.delivery.execution.api.generated.ReturnHandoversApi;
import com.pmart.delivery.execution.api.generated.model.ListReturnHandovers200Response;
import com.pmart.delivery.execution.api.generated.model.ReturnHandover;
import com.pmart.delivery.execution.application.ReturnHandoverCodes;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** MFC 반납 인증번호 — 센터 직원 화면(0.27.0, 2026-11-18). 규칙은 {@link ReturnHandoverCodes}. */
@RestController
public class ReturnHandoversController implements ReturnHandoversApi {

  private final ReturnHandoverCodes codes;

  public ReturnHandoversController(ReturnHandoverCodes codes) {
    this.codes = codes;
  }

  @Override
  public ResponseEntity<ListReturnHandovers200Response> listReturnHandovers(
      @Nullable String mfcId) {
    return ResponseEntity.ok(
        new ListReturnHandovers200Response()
            .items(codes.list(mfcId).stream().map(ReturnHandoversController::view).toList()));
  }

  @Override
  public ResponseEntity<ReturnHandover> reissueReturnHandoverCode(
      String deliveryId, String idempotencyKey, String xSourceSystem) {
    return ResponseEntity.ok(view(codes.reissue(deliveryId)));
  }

  private static ReturnHandover view(ReturnHandoverCodes.Handover h) {
    return new ReturnHandover()
        .deliveryId(h.delivery().deliveryId())
        .deliveryNumber(h.delivery().deliveryNumber())
        .purpose(h.delivery().purpose())
        .state(h.delivery().state())
        .riderId(h.delivery().riderId())
        .code(h.code())
        .failedAttempts(h.failedAttempts())
        .locked(h.locked())
        .issuedAt(h.issuedAt().atOffset(ZoneOffset.UTC));
  }
}
