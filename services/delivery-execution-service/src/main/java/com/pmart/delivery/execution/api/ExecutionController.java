package com.pmart.delivery.execution.api;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.delivery.execution.api.generated.ExecutionApi;
import com.pmart.delivery.execution.api.generated.model.CompletionRequest;
import com.pmart.delivery.execution.api.generated.model.CompletionRequestAllOfEvidence;
import com.pmart.delivery.execution.api.generated.model.DeliveryExecution;
import com.pmart.delivery.execution.api.generated.model.EvidencePhoto;
import com.pmart.delivery.execution.api.generated.model.ReportPickupRequest;
import com.pmart.delivery.execution.api.generated.model.ReportStoreArrival200Response;
import com.pmart.delivery.execution.api.generated.model.TransitionRequest;
import com.pmart.delivery.execution.application.ExecutionService;
import java.io.IOException;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 계약 ExecutionApi — 수행 조회, 가게 도착·픽업 완료·고객 도착·증빙 사진·전달 완료(0.2.0). 실패·도움요청은 아직 501(인터페이스 default).
 * 요청·응답 변환만 하고 규칙은 {@link ExecutionService}.
 */
@RestController
public class ExecutionController implements ExecutionApi {

  private final ExecutionService service;
  private final ExecutionViews views;

  public ExecutionController(ExecutionService service, ExecutionViews views) {
    this.service = service;
    this.views = views;
  }

  @Override
  public ResponseEntity<DeliveryExecution> getDeliveryExecution(String deliveryId) {
    return ResponseEntity.ok(views.delivery(service.view(deliveryId)));
  }

  @Override
  public ResponseEntity<ReportStoreArrival200Response> reportStoreArrival(
      String deliveryId,
      String idempotencyKey,
      String xSourceSystem,
      @Nullable Integer xExpectedVersion,
      @Nullable TransitionRequest transitionRequest) {
    return ResponseEntity.ok(
        ExecutionViews.applied(
            service.storeArrival(deliveryId, idempotencyKey, xSourceSystem, xExpectedVersion)));
  }

  @Override
  public ResponseEntity<ReportStoreArrival200Response> reportPickup(
      String deliveryId,
      String idempotencyKey,
      String xSourceSystem,
      ReportPickupRequest body,
      @Nullable Integer xExpectedVersion) {
    return ResponseEntity.ok(
        ExecutionViews.applied(
            service.pickup(
                deliveryId,
                idempotencyKey,
                xSourceSystem,
                xExpectedVersion,
                body.getHandoverIds())));
  }

  @Override
  public ResponseEntity<ReportStoreArrival200Response> reportCustomerArrival(
      String deliveryId,
      String idempotencyKey,
      String xSourceSystem,
      @Nullable Integer xExpectedVersion,
      @Nullable TransitionRequest transitionRequest) {
    return ResponseEntity.ok(
        ExecutionViews.applied(
            service.customerArrival(deliveryId, idempotencyKey, xSourceSystem, xExpectedVersion)));
  }

  @Override
  public ResponseEntity<EvidencePhoto> uploadEvidencePhoto(
      String deliveryId, String idempotencyKey, String xSourceSystem, MultipartFile file) {
    byte[] bytes;
    try {
      bytes = file.getBytes();
    } catch (IOException e) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "사진을 읽을 수 없습니다.");
    }
    var p = service.uploadPhoto(deliveryId, idempotencyKey, xSourceSystem, bytes);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            new EvidencePhoto()
                .photoRef(p.photoRef())
                .contentType(EvidencePhoto.ContentTypeEnum.fromValue(p.contentType()))
                .sizeBytes(p.sizeBytes())
                .uploadedAt(ExecutionViews.time(p.uploadedAt())));
  }

  @Override
  public ResponseEntity<ReportStoreArrival200Response> reportCompletion(
      String deliveryId,
      String idempotencyKey,
      String xSourceSystem,
      CompletionRequest body,
      @Nullable Integer xExpectedVersion) {
    CompletionRequestAllOfEvidence e = body.getEvidence();
    return ResponseEntity.ok(
        ExecutionViews.applied(
            service.complete(
                deliveryId,
                idempotencyKey,
                xSourceSystem,
                xExpectedVersion,
                new ExecutionService.Completion(
                    body.getMethod().getValue(),
                    e.getPhotoRefs(),
                    e.getTakenAt().toInstant(),
                    e.getPlacement() == null ? null : e.getPlacement().getValue(),
                    e.getPlacementDetail(),
                    e.getSubstituteEvidenceCaseId()))));
  }
}
