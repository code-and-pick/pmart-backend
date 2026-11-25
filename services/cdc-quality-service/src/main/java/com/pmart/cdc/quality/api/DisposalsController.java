package com.pmart.cdc.quality.api;

import com.pmart.cdc.quality.api.generated.DisposalsApi;
import com.pmart.cdc.quality.api.generated.model.DecisionInput;
import com.pmart.cdc.quality.api.generated.model.DisposalApproval;
import com.pmart.cdc.quality.api.generated.model.DisposalApprovalExecutionsInner;
import com.pmart.cdc.quality.api.generated.model.DisposalApprovalLinesInner;
import com.pmart.cdc.quality.api.generated.model.DisposalState;
import com.pmart.cdc.quality.api.generated.model.ExecuteDisposalRequest;
import com.pmart.cdc.quality.api.generated.model.ListDisposals200Response;
import com.pmart.cdc.quality.api.generated.model.RequestDisposalRequest;
import com.pmart.cdc.quality.application.DisposalService;
import com.pmart.cdc.quality.application.DisposalService.DisposalView;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 DisposalsApi — 폐기 요청·결정·실행(0.2.0). 업무 규칙은 {@link DisposalService}. */
@RestController
public class DisposalsController implements DisposalsApi {

  private final DisposalService disposals;

  public DisposalsController(DisposalService disposals) {
    this.disposals = disposals;
  }

  @Override
  public ResponseEntity<DisposalApproval> requestDisposal(
      String cdcId, String idempotencyKey, String xSourceSystem, RequestDisposalRequest body) {
    String id =
        disposals.request(
            idempotencyKey,
            xSourceSystem,
            cdcId,
            body.getHoldId(),
            body.getReason().getValue(),
            body.getNote(),
            HoldsController.exacts(body.getLines()));
    return ResponseEntity.status(HttpStatus.CREATED).body(dto(disposals.get(cdcId, id)));
  }

  @Override
  public ResponseEntity<ListDisposals200Response> listDisposals(
      String cdcId,
      @Nullable DisposalState state,
      @Nullable Integer size,
      @Nullable String cursor) {
    int n = size == null ? 50 : size;
    List<DisposalView> items =
        disposals.list(cdcId, state == null ? null : state.getValue(), cursor, n);
    var out = new ListDisposals200Response();
    out.setItems(items.stream().map(DisposalsController::dto).toList());
    out.setNextCursor(items.size() < n ? null : items.get(items.size() - 1).disposalApprovalId());
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<DisposalApproval> getDisposalApproval(
      String cdcId, String disposalApprovalId) {
    return ResponseEntity.ok(dto(disposals.get(cdcId, disposalApprovalId)));
  }

  @Override
  public ResponseEntity<DisposalApproval> decideDisposal(
      String cdcId,
      String disposalApprovalId,
      String idempotencyKey,
      String xSourceSystem,
      DecisionInput body) {
    disposals.decide(
        idempotencyKey,
        xSourceSystem,
        cdcId,
        disposalApprovalId,
        body.getDecision().getValue(),
        body.getReason(),
        body.getApprovalRef());
    return ResponseEntity.ok(dto(disposals.get(cdcId, disposalApprovalId)));
  }

  @Override
  public ResponseEntity<DisposalApproval> executeDisposal(
      String cdcId,
      String disposalApprovalId,
      String idempotencyKey,
      String xSourceSystem,
      ExecuteDisposalRequest body) {
    disposals.execute(
        idempotencyKey,
        xSourceSystem,
        cdcId,
        disposalApprovalId,
        body.getEvidenceRef(),
        body.getNote(),
        HoldsController.exacts(body.getLines()));
    return ResponseEntity.ok(dto(disposals.get(cdcId, disposalApprovalId)));
  }

  static DisposalApproval dto(DisposalView v) {
    var d = new DisposalApproval();
    d.setDisposalApprovalId(v.disposalApprovalId());
    d.setCdcId(v.cdcId());
    d.setHoldId(v.holdId());
    d.setReason(v.reason());
    d.setNote(v.note());
    d.setState(DisposalState.fromValue(v.state()));
    d.setRequestedBy(v.requestedBy());
    d.setDecidedBy(v.decidedBy());
    d.setDecisionReason(v.decisionReason());
    d.setApprovalRef(v.approvalRef());
    d.setLines(
        v.lines().stream()
            .map(
                l -> {
                  var x = new DisposalApprovalLinesInner();
                  x.setSku(l.sku());
                  x.setLot(l.lot());
                  x.setLocationCode(l.locationCode());
                  x.setApprovedQty(l.approvedQty());
                  x.setExecutedQty(l.executedQty());
                  return x;
                })
            .toList());
    d.setExecutions(
        v.executions().stream()
            .map(
                e -> {
                  var x = new DisposalApprovalExecutionsInner();
                  x.setExecutionId(e.executionId());
                  x.setState(DisposalApprovalExecutionsInner.StateEnum.fromValue(e.state()));
                  x.setEvidenceRef(e.evidenceRef());
                  x.setExecutedBy(e.executedBy());
                  x.setCommitId(e.commitId());
                  x.setRejectReason(e.rejectReason());
                  x.setLines(e.lines().stream().map(HoldsController::line).toList());
                  x.setCreatedAt(HoldsController.time(e.createdAt()));
                  return x;
                })
            .toList());
    d.setCreatedAt(HoldsController.time(v.createdAt()));
    d.setDecidedAt(HoldsController.time(v.decidedAt()));
    return d;
  }
}
