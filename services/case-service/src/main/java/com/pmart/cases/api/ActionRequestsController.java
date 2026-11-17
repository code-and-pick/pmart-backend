package com.pmart.cases.api;

import com.pmart.cases.api.generated.ActionRequestsApi;
import com.pmart.cases.api.generated.model.ActionRequest;
import com.pmart.cases.api.generated.model.ActionRequestBody;
import com.pmart.cases.api.generated.model.EvidenceReference;
import com.pmart.cases.api.generated.model.RecordActionRequestResultRequest;
import com.pmart.cases.application.ActionRequestService;
import com.pmart.cases.infrastructure.ActionRequestStore.ActionRow;
import java.net.URI;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 ActionRequestsApi — 행위 요청 기록(0.17.0, 기록만). 규칙은 {@link ActionRequestService}. */
@RestController
public class ActionRequestsController implements ActionRequestsApi {

  private final ActionRequestService service;

  public ActionRequestsController(ActionRequestService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<ActionRequest> createActionRequest(
      String caseId,
      String idempotencyKey,
      String xSourceSystem,
      ActionRequestBody body,
      @Nullable Integer xExpectedVersion) {
    ActionRow r =
        service.create(
            idempotencyKey,
            xSourceSystem,
            caseId,
            new ActionRequestService.Create(
                body.getActionType().getValue(),
                body.getTargetService(),
                body.getCommand(),
                body.getTargetRef(),
                body.getIdempotencyKey(),
                body.getApprovalRef(),
                body.getPayloadSummary()),
            xExpectedVersion);
    return ResponseEntity.created(
            URI.create("/api/v1/cases/" + caseId + "/action-requests/" + r.actionRequestId()))
        .body(toModel(r));
  }

  @Override
  public ResponseEntity<ActionRequest> getActionRequest(String caseId, String actionRequestId) {
    return ResponseEntity.ok(toModel(service.get(caseId, actionRequestId)));
  }

  @Override
  public ResponseEntity<ActionRequest> recordActionRequestResult(
      String caseId,
      String actionRequestId,
      String idempotencyKey,
      String xSourceSystem,
      RecordActionRequestResultRequest body) {
    return ResponseEntity.ok(
        toModel(
            service.recordResult(
                idempotencyKey,
                xSourceSystem,
                caseId,
                actionRequestId,
                new ActionRequestService.Result(
                    body.getState().getValue(),
                    body.getResultSystem(),
                    body.getResultRef(),
                    body.getResultKind() == null ? null : body.getResultKind().getValue(),
                    body.getNote()))));
  }

  static ActionRequest toModel(ActionRow r) {
    var m = new ActionRequest();
    m.setActionRequestId(r.actionRequestId());
    m.setCaseId(r.caseId());
    m.setActionType(ActionRequest.ActionTypeEnum.fromValue(r.actionType()));
    m.setTargetService(r.targetService());
    m.setCommand(r.command());
    m.setTargetRef(r.targetRef());
    m.setIdempotencyKey(r.idempotencyKey());
    m.setApprovalRef(r.approvalRef());
    m.setPayloadSummary(r.payloadSummary());
    m.setState(ActionRequest.StateEnum.fromValue(r.state()));
    if (r.resultSystem() != null && r.resultRef() != null) {
      var e = new EvidenceReference();
      e.setSystem(r.resultSystem());
      e.setRef(r.resultRef());
      if (r.resultKind() != null) {
        e.setKind(EvidenceReference.KindEnum.fromValue(r.resultKind()));
      }
      if (r.resultAt() != null) {
        e.setAsOf(r.resultAt().atOffset(ZoneOffset.UTC));
      }
      m.setResultRef(e);
    }
    m.setResultNote(r.resultNote());
    m.setResultBy(r.resultBy());
    m.setRequestedBy(r.requestedBy());
    m.setRequestedAt(r.requestedAt().atOffset(ZoneOffset.UTC));
    m.setResultAt(r.resultAt() == null ? null : r.resultAt().atOffset(ZoneOffset.UTC));
    m.setVersion(r.version());
    return m;
  }
}
