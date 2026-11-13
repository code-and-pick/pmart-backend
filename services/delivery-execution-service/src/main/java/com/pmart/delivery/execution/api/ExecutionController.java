package com.pmart.delivery.execution.api;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.delivery.execution.api.generated.ExecutionApi;
import com.pmart.delivery.execution.api.generated.model.CompletionRequest;
import com.pmart.delivery.execution.api.generated.model.CompletionRequestAllOfEvidence;
import com.pmart.delivery.execution.api.generated.model.CreateHelpRequestRequest;
import com.pmart.delivery.execution.api.generated.model.DecideFailureRequest;
import com.pmart.delivery.execution.api.generated.model.DeliveryExecution;
import com.pmart.delivery.execution.api.generated.model.EvidencePhoto;
import com.pmart.delivery.execution.api.generated.model.ExecutionState;
import com.pmart.delivery.execution.api.generated.model.GetHelpRequest200Response;
import com.pmart.delivery.execution.api.generated.model.ReportFailureRequest;
import com.pmart.delivery.execution.api.generated.model.ReportPickupRequest;
import com.pmart.delivery.execution.api.generated.model.ReportStoreArrival200Response;
import com.pmart.delivery.execution.api.generated.model.TransitionRequest;
import com.pmart.delivery.execution.application.ExecutionService;
import com.pmart.delivery.execution.application.FailureDecisionService;
import com.pmart.delivery.execution.application.HelpRequestService;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 계약 ExecutionApi — 수행 조회, 가게 도착·픽업 완료·고객 도착·증빙 사진·전달 완료(0.2.0), 전달 실패 등록·운영자 결정·도움요청(0.3.0). 요청·응답
 * 변환만 하고 규칙은 {@link ExecutionService}.
 */
@RestController
public class ExecutionController implements ExecutionApi {

  private final ExecutionService service;
  private final FailureDecisionService failures;
  private final HelpRequestService helps;
  private final ExecutionViews views;

  public ExecutionController(
      ExecutionService service,
      FailureDecisionService failures,
      HelpRequestService helps,
      ExecutionViews views) {
    this.service = service;
    this.failures = failures;
    this.helps = helps;
    this.views = views;
  }

  @Override
  public ResponseEntity<ReportStoreArrival200Response> reportFailure(
      String deliveryId,
      String idempotencyKey,
      String xSourceSystem,
      ReportFailureRequest body,
      @Nullable Integer xExpectedVersion) {
    List<Map<String, Object>> contacts = new ArrayList<>();
    if (body.getContactAttempts() != null) {
      for (var c : body.getContactAttempts()) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("attemptedAt", c.getAttemptedAt().toInstant().toString());
        if (c.getMethod() != null) {
          m.put("method", c.getMethod().getValue());
        }
        m.put("result", c.getResult().getValue());
        contacts.add(m);
      }
    }
    var applied =
        service.reportFailure(
            deliveryId,
            idempotencyKey,
            xSourceSystem,
            xExpectedVersion,
            new ExecutionService.Failure(
                body.getReason().getValue(),
                body.getDetail(),
                body.getEvidenceRefs(),
                body.getGoodsLocation() == null ? null : body.getGoodsLocation().getValue(),
                contacts));
    // 등록 = 접수(운영자 확정 전) — ACCEPTED
    return ResponseEntity.ok(
        ExecutionViews.applied(applied)
            .status(ReportStoreArrival200Response.StatusEnum.ACCEPTED)
            .nextAction("WAIT_OPERATOR_DECISION"));
  }

  @Override
  public ResponseEntity<ReportStoreArrival200Response> decideFailure(
      String deliveryId,
      String idempotencyKey,
      String xSourceSystem,
      DecideFailureRequest body,
      @Nullable Integer xExpectedVersion) {
    return ResponseEntity.ok(
        ExecutionViews.applied(
            failures.decide(
                deliveryId,
                idempotencyKey,
                xSourceSystem,
                xExpectedVersion,
                new FailureDecisionService.Decision(
                    body.getDecision().getValue(),
                    body.getNote(),
                    body.getCustody() == null ? null : body.getCustody().getValue()))));
  }

  @Override
  public ResponseEntity<ReportStoreArrival200Response> createHelpRequest(
      String deliveryId,
      String idempotencyKey,
      String xSourceSystem,
      CreateHelpRequestRequest body) {
    var c =
        helps.create(
            deliveryId,
            idempotencyKey,
            xSourceSystem,
            body.getCategory().getValue(),
            body.getDetail(),
            body.getEvidenceRefs() == null ? List.of() : body.getEvidenceRefs());
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(
            new ReportStoreArrival200Response()
                .status(ReportStoreArrival200Response.StatusEnum.ACCEPTED)
                .requestId(c.requestId())
                .resultId(c.requestId())
                .version(c.version())
                .retryable(false)
                .nextAction("SUPPORT_CENTER")
                .occurredAt(ExecutionViews.time(c.createdAt())));
  }

  @Override
  public ResponseEntity<GetHelpRequest200Response> getHelpRequest(
      String deliveryId, String requestId) {
    var h = helps.get(deliveryId, requestId);
    return ResponseEntity.ok(
        new GetHelpRequest200Response()
            .requestId(h.requestId())
            .caseId(h.caseId())
            .state(GetHelpRequest200Response.StateEnum.fromValue(h.state()))
            .category(h.category())
            .stage(ExecutionState.fromValue(h.stage()))
            .createdAt(ExecutionViews.time(h.createdAt())));
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
