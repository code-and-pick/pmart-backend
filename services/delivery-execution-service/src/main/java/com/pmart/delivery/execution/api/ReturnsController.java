package com.pmart.delivery.execution.api;

import com.pmart.delivery.execution.api.generated.ReturnsApi;
import com.pmart.delivery.execution.api.generated.model.ReportReturnPickupRequest;
import com.pmart.delivery.execution.api.generated.model.ReportReturnStoreDeliveryRequest;
import com.pmart.delivery.execution.api.generated.model.ReportStoreArrival200Response;
import com.pmart.delivery.execution.application.ExecutionService;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 ReturnsApi — 오배송 회수 수행(0.5.0, DD-2 2026-11-16): 회수지에서 받음 → MFC 반납. 고객 반품 회수(CLAIM_RETURN)는 아직
 * 422. 업무 규칙은 {@link ExecutionService}.
 */
@RestController
public class ReturnsController implements ReturnsApi {

  private final ExecutionService service;
  private final com.pmart.delivery.execution.application.DropInfoService dropInfos;

  public ReturnsController(
      ExecutionService service,
      com.pmart.delivery.execution.application.DropInfoService dropInfos) {
    this.service = service;
    this.dropInfos = dropInfos;
  }

  @Override
  public ResponseEntity<com.pmart.delivery.execution.api.generated.model.RecoveryContact>
      getRecoveryContact(String deliveryId) {
    var c = dropInfos.recoveryContact(deliveryId);
    return ResponseEntity.ok()
        .cacheControl(org.springframework.http.CacheControl.noStore())
        .body(
            new com.pmart.delivery.execution.api.generated.model.RecoveryContact()
                .deliveryId(deliveryId)
                .purpose(
                    com.pmart.delivery.execution.api.generated.model.RecoveryContact.PurposeEnum
                        .fromValue(c.purpose()))
                .originDeliveryId(c.originDeliveryId())
                .receiverPhone(c.receiverPhone())
                .receiverName(c.receiverName())
                .roadAddress(c.roadAddress())
                .detailAddress(c.detailAddress())
                .entranceMethod(c.entranceMethod())
                .entranceNote(c.entranceNote()));
  }

  @Override
  public ResponseEntity<ReportStoreArrival200Response> reportReturnPickup(
      String deliveryId,
      String idempotencyKey,
      String xSourceSystem,
      ReportReturnPickupRequest body,
      @Nullable Integer xExpectedVersion) {
    return ResponseEntity.ok(
        ExecutionViews.applied(
            service.returnPickup(
                deliveryId,
                idempotencyKey,
                xSourceSystem,
                xExpectedVersion,
                body.getEvidenceRefs() == null ? List.of() : body.getEvidenceRefs())));
  }

  @Override
  public ResponseEntity<ReportStoreArrival200Response> reportReturnStoreDelivery(
      String deliveryId,
      String idempotencyKey,
      String xSourceSystem,
      ReportReturnStoreDeliveryRequest body,
      @Nullable Integer xExpectedVersion) {
    return ResponseEntity.ok(
        ExecutionViews.applied(
            service.returnToStore(
                deliveryId,
                idempotencyKey,
                xSourceSystem,
                xExpectedVersion,
                body == null || body.getEvidenceRefs() == null ? List.of() : body.getEvidenceRefs(),
                body == null ? null : body.getReceivedByStaffRef())));
  }
}
