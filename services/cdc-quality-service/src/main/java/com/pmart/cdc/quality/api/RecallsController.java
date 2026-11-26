package com.pmart.cdc.quality.api;

import com.pmart.cdc.quality.api.generated.RecallsApi;
import com.pmart.cdc.quality.api.generated.model.IssueRecallRequest;
import com.pmart.cdc.quality.api.generated.model.ListRecalls200Response;
import com.pmart.cdc.quality.api.generated.model.Recall;
import com.pmart.cdc.quality.api.generated.model.RecallCentersInner;
import com.pmart.cdc.quality.api.generated.model.RecallDeparturesInner;
import com.pmart.cdc.quality.application.RecallService;
import com.pmart.cdc.quality.application.RecallService.RecallView;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 RecallsApi — 리콜 발령·조회(0.3.0). 업무 규칙은 {@link RecallService}. */
@RestController
public class RecallsController implements RecallsApi {

  private final RecallService recalls;

  public RecallsController(RecallService recalls) {
    this.recalls = recalls;
  }

  @Override
  public ResponseEntity<Recall> issueRecall(
      String idempotencyKey, String xSourceSystem, IssueRecallRequest body) {
    String id =
        recalls.issue(
            idempotencyKey,
            xSourceSystem,
            new RecallService.IssueCmd(
                body.getSku(),
                body.getLots(),
                body.getReason().getValue(),
                body.getSeverity() == null ? null : body.getSeverity().getValue(),
                body.getApprovalRef(),
                body.getExternalRef(),
                body.getNote()));
    return ResponseEntity.status(HttpStatus.CREATED).body(dto(recalls.get(xSourceSystem, id)));
  }

  @Override
  public ResponseEntity<ListRecalls200Response> listRecalls(
      @Nullable String sku, @Nullable Integer size, @Nullable String cursor) {
    int n = size == null ? 50 : size;
    List<RecallView> items = recalls.list("cdc-backoffice", sku, cursor, n);
    var out = new ListRecalls200Response();
    out.setItems(items.stream().map(RecallsController::dto).toList());
    out.setNextCursor(items.size() < n ? null : items.get(items.size() - 1).recallId());
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<Recall> getRecall(String recallId) {
    return ResponseEntity.ok(dto(recalls.get("cdc-backoffice", recallId)));
  }

  static Recall dto(RecallView v) {
    var r = new Recall();
    r.setRecallId(v.recallId());
    r.setSku(v.sku());
    r.setLots(v.lots());
    r.setReason(v.reason());
    r.setSeverity(v.severity());
    r.setApprovalRef(v.approvalRef());
    r.setExternalRef(v.externalRef());
    r.setNote(v.note());
    r.setState(Recall.StateEnum.fromValue(v.state()));
    r.setIssuedBy(v.issuedBy());
    r.setCenters(
        v.centers().stream()
            .map(
                c ->
                    new RecallCentersInner()
                        .cdcId(c.cdcId())
                        .lot(c.lot())
                        .holdId(c.holdId())
                        .holdState(c.holdState())
                        .heldQty(c.heldQty()))
            .toList());
    r.setDepartures(
        v.departures().stream()
            .map(
                d ->
                    new RecallDeparturesInner()
                        .cdcId(d.cdcId())
                        .lot(d.lot())
                        .trfId(d.trfId())
                        .departureId(d.departureId())
                        .mfcId(d.mfcId())
                        .departedAt(HoldsController.time(d.departedAt()))
                        .q(d.q())
                        .received(d.received())
                        .unreconciled(d.unreconciled()))
            .toList());
    r.setTraceFreshness(
        v.traceCurrent()
            ? Recall.TraceFreshnessEnum.CURRENT
            : Recall.TraceFreshnessEnum.UNAVAILABLE);
    r.setCreatedAt(HoldsController.time(v.createdAt()));
    return r;
  }
}
