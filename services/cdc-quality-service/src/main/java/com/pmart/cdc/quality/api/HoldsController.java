package com.pmart.cdc.quality.api;

import com.pmart.cdc.quality.api.generated.HoldsApi;
import com.pmart.cdc.quality.api.generated.model.DecisionInput;
import com.pmart.cdc.quality.api.generated.model.ExactLine;
import com.pmart.cdc.quality.api.generated.model.Hold;
import com.pmart.cdc.quality.api.generated.model.HoldInput;
import com.pmart.cdc.quality.api.generated.model.HoldLinesInner;
import com.pmart.cdc.quality.api.generated.model.HoldRelease;
import com.pmart.cdc.quality.api.generated.model.HoldState;
import com.pmart.cdc.quality.api.generated.model.ListHolds200Response;
import com.pmart.cdc.quality.api.generated.model.RequestReleaseRequest;
import com.pmart.cdc.quality.application.HoldService;
import com.pmart.cdc.quality.application.HoldService.Exact;
import com.pmart.cdc.quality.application.HoldService.HoldView;
import com.pmart.cdc.quality.application.HoldService.ReleaseView;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 HoldsApi — 품질 보류·해제 요청·결정(0.2.0). 업무 규칙은 {@link HoldService}. */
@RestController
public class HoldsController implements HoldsApi {

  private final HoldService holds;

  public HoldsController(HoldService holds) {
    this.holds = holds;
  }

  @Override
  public ResponseEntity<Hold> createHold(
      String cdcId, String idempotencyKey, String xSourceSystem, HoldInput body) {
    String id =
        holds.create(
            idempotencyKey,
            xSourceSystem,
            cdcId,
            new HoldService.HoldCmd(
                body.getReason().getValue(),
                body.getSourceRef(),
                body.getEvidenceRef(),
                body.getNote(),
                body.getLines().stream()
                    .map(
                        l ->
                            new HoldService.Scope(
                                l.getSku(), l.getLot(), l.getLocationCode(), l.getQty()))
                    .toList()));
    return ResponseEntity.status(HttpStatus.CREATED).body(dto(holds.get(cdcId, id)));
  }

  @Override
  public ResponseEntity<ListHolds200Response> listHolds(
      String cdcId, @Nullable HoldState state, @Nullable Integer size, @Nullable String cursor) {
    int n = size == null ? 50 : size;
    List<HoldView> items = holds.list(cdcId, state == null ? null : state.getValue(), cursor, n);
    var out = new ListHolds200Response();
    out.setItems(items.stream().map(HoldsController::dto).toList());
    out.setNextCursor(items.size() < n ? null : items.get(items.size() - 1).holdId());
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<Hold> getHold(String cdcId, String holdId) {
    return ResponseEntity.ok(dto(holds.get(cdcId, holdId)));
  }

  @Override
  public ResponseEntity<HoldRelease> requestRelease(
      String cdcId,
      String holdId,
      String idempotencyKey,
      String xSourceSystem,
      RequestReleaseRequest body) {
    String id =
        holds.requestRelease(
            idempotencyKey, xSourceSystem, cdcId, holdId, body.getNote(), exacts(body.getLines()));
    return ResponseEntity.status(HttpStatus.CREATED).body(dto(holds.release(cdcId, holdId, id)));
  }

  @Override
  public ResponseEntity<HoldRelease> decideRelease(
      String cdcId,
      String holdId,
      String releaseId,
      String idempotencyKey,
      String xSourceSystem,
      DecisionInput body) {
    holds.decideRelease(
        idempotencyKey,
        xSourceSystem,
        cdcId,
        holdId,
        releaseId,
        body.getDecision().getValue(),
        body.getReason(),
        body.getApprovalRef());
    return ResponseEntity.ok(dto(holds.release(cdcId, holdId, releaseId)));
  }

  static List<Exact> exacts(List<ExactLine> lines) {
    return lines.stream()
        .map(l -> new Exact(l.getSku(), l.getLot(), l.getLocationCode(), l.getQty()))
        .toList();
  }

  static ExactLine line(Exact e) {
    var x = new ExactLine();
    x.setSku(e.sku());
    x.setLot(e.lot());
    x.setLocationCode(e.locationCode());
    x.setQty(e.qty());
    return x;
  }

  static @Nullable OffsetDateTime time(@Nullable Instant i) {
    return i == null ? null : i.atOffset(ZoneOffset.UTC);
  }

  static Hold dto(HoldView v) {
    var h = new Hold();
    h.setHoldId(v.holdId());
    h.setCdcId(v.cdcId());
    h.setReason(v.reason());
    h.setSourceRef(v.sourceRef());
    h.setEvidenceRef(v.evidenceRef());
    h.setNote(v.note());
    h.setState(HoldState.fromValue(v.state()));
    h.setRequestedBy(v.requestedBy());
    h.setRejectReason(v.rejectReason());
    h.setLines(
        v.lines().stream()
            .map(
                l -> {
                  var x = new HoldLinesInner();
                  x.setLineNo(l.lineNo());
                  x.setSku(l.sku());
                  x.setLot(l.lot());
                  x.setLocationCode(l.locationCode());
                  x.setRequestedQty(l.requestedQty());
                  x.setHeldQty(l.heldQty());
                  x.setReleasedQty(l.releasedQty());
                  x.setDisposedQty(l.disposedQty());
                  x.setCommitId(l.commitId());
                  x.setState(HoldLinesInner.StateEnum.fromValue(l.state()));
                  return x;
                })
            .toList());
    h.setReleases(v.releases().stream().map(HoldsController::dto).toList());
    h.setCreatedAt(time(v.createdAt()));
    h.setUpdatedAt(time(v.updatedAt()));
    return h;
  }

  static HoldRelease dto(ReleaseView r) {
    var x = new HoldRelease();
    x.setReleaseId(r.releaseId());
    x.setHoldId(r.holdId());
    x.setState(HoldRelease.StateEnum.fromValue(r.state()));
    x.setRequestedBy(r.requestedBy());
    x.setNote(r.note());
    x.setDecidedBy(r.decidedBy());
    x.setDecisionReason(r.decisionReason());
    x.setApprovalRef(r.approvalRef());
    x.setCommitId(r.commitId());
    x.setRejectReason(r.rejectReason());
    x.setLines(r.lines().stream().map(HoldsController::line).toList());
    x.setCreatedAt(time(r.createdAt()));
    x.setDecidedAt(time(r.decidedAt()));
    return x;
  }
}
