package com.pmart.delivery.execution.api;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.delivery.execution.api.generated.ExecutionApi;
import com.pmart.delivery.execution.api.generated.model.CompletionRequest;
import com.pmart.delivery.execution.api.generated.model.CompletionRequestAllOfEvidence;
import com.pmart.delivery.execution.api.generated.model.CreateHelpRequestRequest;
import com.pmart.delivery.execution.api.generated.model.DecideFailureRequest;
import com.pmart.delivery.execution.api.generated.model.DeliveryExecution;
import com.pmart.delivery.execution.api.generated.model.DropInfo;
import com.pmart.delivery.execution.api.generated.model.EvidencePhoto;
import com.pmart.delivery.execution.api.generated.model.HelpMessage;
import com.pmart.delivery.execution.api.generated.model.HelpRequest;
import com.pmart.delivery.execution.api.generated.model.ListHelpRequestMessages200Response;
import com.pmart.delivery.execution.api.generated.model.PostHelpRequestMessageRequest;
import com.pmart.delivery.execution.api.generated.model.ReportFailureRequest;
import com.pmart.delivery.execution.api.generated.model.ReportPickupRequest;
import com.pmart.delivery.execution.api.generated.model.ReportStoreArrival200Response;
import com.pmart.delivery.execution.api.generated.model.TransitionRequest;
import com.pmart.delivery.execution.application.DropInfoService;
import com.pmart.delivery.execution.application.ExecutionService;
import com.pmart.delivery.execution.application.FailureDecisionService;
import com.pmart.delivery.execution.application.HelpRequestService;
import com.pmart.delivery.execution.infrastructure.ExecutionStore;
import com.pmart.delivery.execution.infrastructure.OrderClient;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

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
  private final DropInfoService dropInfos;
  private final ObjectMapper mapper;

  public ExecutionController(
      ExecutionService service,
      FailureDecisionService failures,
      HelpRequestService helps,
      ExecutionViews views,
      DropInfoService dropInfos,
      ObjectMapper mapper) {
    this.service = service;
    this.failures = failures;
    this.helps = helps;
    this.views = views;
    this.dropInfos = dropInfos;
    this.mapper = mapper;
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
    // 등록 = 접수(운영자 확정 전) — ACCEPTED. resultId = 함께 열린 지원센터 대화(도움요청) ID(2026-11-17 §13)
    return ResponseEntity.ok(
        ExecutionViews.applied(applied)
            .status(ReportStoreArrival200Response.StatusEnum.ACCEPTED)
            .resultId(idempotencyKey)
            .nextAction("WAIT_OPERATOR_DECISION"));
  }

  /** 전달 재개 요청(2026-11-17 라이더 앱 요청 §13-3). */
  @Override
  public ResponseEntity<ReportStoreArrival200Response> requestFailureResume(
      String deliveryId,
      String idempotencyKey,
      String xSourceSystem,
      @Nullable Integer xExpectedVersion,
      com.pmart.delivery.execution.api.generated.model.@Nullable RequestFailureResumeRequest body) {
    var applied =
        service.requestResume(
            deliveryId,
            idempotencyKey,
            xSourceSystem,
            xExpectedVersion,
            body == null ? null : body.getNote());
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

  /** 배송별 도움요청 목록(0.7.0, 라이더 앱 요청 §10). */
  @Override
  public ResponseEntity<
          com.pmart.delivery.execution.api.generated.model.ListHelpRequests200Response>
      listHelpRequests(String deliveryId) {
    return ResponseEntity.ok(
        new com.pmart.delivery.execution.api.generated.model.ListHelpRequests200Response()
            .items(
                helps.list(deliveryId).stream()
                    .map(v -> StaffController.helpRequest(v, mapper))
                    .toList()));
  }

  @Override
  public ResponseEntity<HelpRequest> getHelpRequest(String deliveryId, String requestId) {
    HelpRequestService.HelpView v = helps.get(deliveryId, requestId);
    HelpRequest out = StaffController.helpRequest(v, mapper);
    if (v.staff()) {
      // DE-2: 직원에게 대화의 마지막 메시지 시각(직원별 읽음은 화면이 기억)
      var t = helps.messageTimes(deliveryId, requestId);
      out.lastRiderMessageAt(ExecutionViews.time(t.lastRider()))
          .lastSupportMessageAt(ExecutionViews.time(t.lastSupport()));
    }
    return ResponseEntity.ok(out);
  }

  /** 도움요청 대화(0.6.0, 라이더 앱 요청 §11-3). 라이더에게는 상담원 ID 를 보이지 않는다. */
  @Override
  public ResponseEntity<ListHelpRequestMessages200Response> listHelpRequestMessages(
      String deliveryId, String requestId, @Nullable Long after) {
    HelpRequestService.Thread t = helps.messages(deliveryId, requestId, after == null ? 0 : after);
    return ResponseEntity.ok(
        new ListHelpRequestMessages200Response()
            .items(t.messages().stream().map(m -> message(m, t.staff(), mapper)).toList())
            .helpState(
                ListHelpRequestMessages200Response.HelpStateEnum.fromValue(t.help().state())));
  }

  @Override
  public ResponseEntity<HelpMessage> postHelpRequestMessage(
      String deliveryId,
      String requestId,
      String idempotencyKey,
      String xSourceSystem,
      PostHelpRequestMessageRequest req) {
    HelpRequestService.Thread t =
        helps.postMessage(
            deliveryId,
            requestId,
            idempotencyKey,
            xSourceSystem,
            req.getBody(),
            req.getPhotoRefs() == null ? List.of() : req.getPhotoRefs());
    return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED)
        .body(message(t.messages().get(0), t.staff(), mapper));
  }

  static HelpMessage message(ExecutionStore.HelpMessageRow m, boolean staff, ObjectMapper mapper) {
    return new HelpMessage()
        .masked(m.maskedAt() != null)
        .maskedAt(ExecutionViews.time(m.maskedAt()))
        .maskedBy(staff ? m.maskedBy() : null)
        .maskReason(staff ? m.maskReason() : null)
        .messageId(m.messageId())
        .seq(m.seq())
        .senderType(HelpMessage.SenderTypeEnum.fromValue(m.senderType()))
        .senderId(staff || "RIDER".equals(m.senderType()) ? m.senderId() : null)
        .body(m.body())
        .photoRefs(List.of(mapper.readValue(m.photoRefsJson(), String[].class)))
        .createdAt(ExecutionViews.time(m.createdAt()));
  }

  @Override
  public ResponseEntity<DeliveryExecution> getDeliveryExecution(String deliveryId) {
    ExecutionService.View v = service.view(deliveryId);
    DeliveryExecution out = views.delivery(v);
    var summary =
        v.delivery().orderId() == null ? null : dropInfos.orderSummary(v.delivery().orderId());
    if (summary instanceof tools.jackson.databind.node.ObjectNode o) {
      o.remove("orderId"); // 배송 조회에 이미 있다
    }
    if (summary != null) {
      out.orderSummary(
          mapper.convertValue(
              summary,
              com.pmart.delivery.execution.api.generated.model.DeliveryExecutionOrderSummary
                  .class));
    }
    return ResponseEntity.ok(out);
  }

  /** 고객용 전달 완료 증빙(2026-11-17). */
  @Override
  public ResponseEntity<
          com.pmart.delivery.execution.api.generated.model.GetDeliveryProof200Response>
      getDeliveryProof(String deliveryId) {
    ExecutionService.DeliveryProof d = service.deliveryProof(deliveryId);
    return ResponseEntity.ok()
        .cacheControl(org.springframework.http.CacheControl.noStore())
        .body(
            new com.pmart.delivery.execution.api.generated.model.GetDeliveryProof200Response()
                .deliveryId(d.deliveryId())
                .orderId(d.orderId())
                .completedAt(ExecutionViews.time(d.completedAt()))
                .placement(d.placement())
                .placementDetail(d.placementDetail())
                .photoRefs(d.photoRefs()));
  }

  /** 증빙 사진 보기(2026-11-16) — 앱·브라우저가 남기지 않게 no-store·private. */
  @Override
  public ResponseEntity<org.springframework.core.io.Resource> getEvidencePhoto(
      String deliveryId, String photoRef) {
    ExecutionService.PhotoFile f = service.photoFile(deliveryId, photoRef);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore().cachePrivate())
        .contentType(org.springframework.http.MediaType.parseMediaType(f.contentType()))
        .body(new org.springframework.core.io.ByteArrayResource(f.bytes()));
  }

  /** 라이더용 전달지(BR-24) — 개인정보라 응답을 저장하지 않게 no-store. */
  @Override
  public ResponseEntity<DropInfo> getDeliveryDropInfo(String deliveryId) {
    OrderClient.DropInfo d = dropInfos.dropInfo(deliveryId);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            new DropInfo()
                .deliveryId(deliveryId)
                .roadAddress(d.roadAddress())
                .detailAddress(d.detailAddress())
                .postalCode(d.postalCode())
                .receiverName(d.receiverName())
                .receiverPhone(d.receiverPhone())
                .entranceMethod(
                    d.entranceMethod() == null
                        ? null
                        : DropInfo.EntranceMethodEnum.fromValue(d.entranceMethod()))
                .entranceNote(d.entranceNote())
                .deliveryNote(d.deliveryNote())
                .lat(d.lat() == null ? null : BigDecimal.valueOf(d.lat()))
                .lng(d.lng() == null ? null : BigDecimal.valueOf(d.lng())));
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
