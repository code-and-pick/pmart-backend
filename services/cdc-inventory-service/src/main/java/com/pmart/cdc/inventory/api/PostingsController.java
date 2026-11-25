package com.pmart.cdc.inventory.api;

import com.pmart.cdc.inventory.api.generated.PostingsApi;
import com.pmart.cdc.inventory.api.generated.model.PostDepartureRequest;
import com.pmart.cdc.inventory.api.generated.model.PostInitialBalanceRequest;
import com.pmart.cdc.inventory.api.generated.model.PostMoveRequest;
import com.pmart.cdc.inventory.api.generated.model.ReleaseAllocation200Response;
import com.pmart.cdc.inventory.api.generated.model.SetSafetyStocksRequest;
import com.pmart.cdc.inventory.application.DepartureService;
import com.pmart.cdc.inventory.application.DepartureService.Depart;
import com.pmart.cdc.inventory.application.DepartureService.DepartLine;
import com.pmart.cdc.inventory.application.MoveService;
import com.pmart.cdc.inventory.application.MoveService.Move;
import com.pmart.cdc.inventory.application.MoveService.MoveLine;
import com.pmart.cdc.inventory.application.StockSetupService;
import com.pmart.cdc.inventory.application.StockSetupService.InitialBalance;
import com.pmart.cdc.inventory.application.StockSetupService.InitialLine;
import com.pmart.cdc.inventory.application.StockSetupService.SafetyLine;
import com.pmart.cdc.inventory.application.StockSetupService.SafetyStocks;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 PostingsApi — 초기 보유·안전량·TOUT(0.2.0)·내부 이동(0.3.0) 구현. 수령 확인·품질·손실·조정은 아직 인터페이스 default(501). 업무
 * 규칙은 application/domain 에 있다.
 */
@RestController
public class PostingsController implements PostingsApi {

  private final StockSetupService setup;
  private final DepartureService departures;
  private final MoveService moves;
  private final com.pmart.cdc.inventory.application.ReceiptService receipts;

  public PostingsController(
      StockSetupService setup,
      DepartureService departures,
      MoveService moves,
      com.pmart.cdc.inventory.application.ReceiptService receipts) {
    this.receipts = receipts;
    this.setup = setup;
    this.departures = departures;
    this.moves = moves;
  }

  /** MFC 수령 확인(0.11.0) — 사건 유실 복구용. 같은 receiptId 한 번. */
  @Override
  public ResponseEntity<com.pmart.cdc.inventory.api.generated.model.AcknowledgeReceipt200Response>
      acknowledgeReceipt(
          String idempotencyKey,
          String xSourceSystem,
          com.pmart.cdc.inventory.api.generated.model.AcknowledgeReceiptRequest body,
          @Nullable String traceparent) {
    if (body.getCorrectionId() != null) {
      throw new com.pmart.common.web.PmartException(
          com.pmart.common.web.ErrorCode.UNSUPPORTED_ACTION, "수령 정정(correctionId)은 아직입니다.");
    }
    var lines =
        body.getLines().stream()
            .map(
                l ->
                    new com.pmart.cdc.inventory.application.ReceiptService.ReceiptLine(
                        l.getSku(),
                        l.getLot(),
                        l.getNormalQty(),
                        l.getQuarantinedQty(),
                        l.getPendingQty() == null ? 0 : l.getPendingQty()))
            .toList();
    var r =
        receipts.acknowledge(
            xSourceSystem,
            new com.pmart.cdc.inventory.application.ReceiptService.Receipt(
                body.getReceiptId(),
                body.getTrfId(),
                body.getDepartureId(),
                body.getMfcId(),
                body.getMfcCommitId(),
                lines));
    var out = new com.pmart.cdc.inventory.api.generated.model.AcknowledgeReceipt200Response();
    out.setStatus(
        com.pmart.cdc.inventory.api.generated.model.AcknowledgeReceipt200Response.StatusEnum
            .APPLIED);
    out.setRequestId(r.receiptId());
    out.setResultId(r.commitId());
    out.setAppliedQty(r.appliedQty());
    out.setOccurredAt(Dto.time(r.at()));
    if (r.transitState() != null) {
      out.setTransitState(
          com.pmart.cdc.inventory.api.generated.model.TransitState.fromValue(r.transitState()));
    }
    out.setUnreconciledQty(r.unreconciledQty());
    out.setConflicts(
        r.conflicts().stream()
            .map(
                c -> {
                  var x =
                      new com.pmart.cdc.inventory.api.generated.model
                          .AcknowledgeReceipt200ResponseAllOfConflictsInner();
                  x.setSku(c.sku());
                  x.setLot(c.lot());
                  x.setQty(c.qty());
                  x.setReason(c.reason());
                  return x;
                })
            .toList());
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<ReleaseAllocation200Response> postMove(
      String idempotencyKey,
      String xSourceSystem,
      PostMoveRequest body,
      @Nullable String traceparent) {
    List<MoveLine> lines =
        body.getLines().stream()
            .map(
                l ->
                    new MoveLine(
                        l.getSku(),
                        l.getLot(),
                        l.getFromLocationCode(),
                        l.getToLocationCode(),
                        l.getQty(),
                        l.getQualityState() == null ? null : l.getQualityState().getValue(),
                        l.getAllocationId()))
            .toList();
    var cmd =
        new Move(
            body.getCdcId(),
            body.getMoveKind().getValue(),
            body.getSourceRef(),
            body.getSpaceReservationId(),
            lines);
    return ResponseEntity.ok(Dto.command(moves.move(idempotencyKey, xSourceSystem, cmd)));
  }

  @Override
  public ResponseEntity<ReleaseAllocation200Response> postInitialBalance(
      String idempotencyKey,
      String xSourceSystem,
      PostInitialBalanceRequest body,
      @Nullable String traceparent) {
    List<InitialLine> lines =
        body.getLines().stream()
            .map(
                l ->
                    new InitialLine(
                        l.getSku(),
                        l.getLot(),
                        l.getLotIdentification() == null
                            ? null
                            : l.getLotIdentification().getValue(),
                        l.getExpiresAt(),
                        l.getLocationCode(),
                        l.getQty(),
                        l.getQualityState().getValue()))
            .toList();
    return ResponseEntity.ok(
        Dto.command(
            setup.initialBalance(
                idempotencyKey,
                xSourceSystem,
                new InitialBalance(body.getCdcId(), body.getApprovalRef(), lines))));
  }

  @Override
  public ResponseEntity<ReleaseAllocation200Response> setSafetyStocks(
      String idempotencyKey,
      String xSourceSystem,
      SetSafetyStocksRequest body,
      @Nullable String traceparent) {
    List<SafetyLine> lines =
        body.getLines().stream().map(l -> new SafetyLine(l.getSku(), l.getSafetyQty())).toList();
    return ResponseEntity.ok(
        Dto.command(
            setup.safetyStocks(
                idempotencyKey,
                xSourceSystem,
                new SafetyStocks(body.getCdcId(), body.getApprovalRef(), lines))));
  }

  @Override
  public ResponseEntity<ReleaseAllocation200Response> postDeparture(
      String idempotencyKey,
      String xSourceSystem,
      PostDepartureRequest body,
      @Nullable String traceparent) {
    List<DepartLine> lines =
        body.getLines().stream()
            .map(
                l ->
                    new DepartLine(
                        l.getDepartureLineId(),
                        l.getSku(),
                        l.getLot(),
                        l.getLocationCode(),
                        l.getQty(),
                        l.getLpns() == null ? List.of() : List.copyOf(l.getLpns())))
            .toList();
    var cmd =
        new Depart(
            body.getCdcId(),
            body.getDepartureId(),
            body.getDepartureSource() == null ? "REAL" : body.getDepartureSource().getValue(),
            body.getToMfcId(),
            body.getTrfId(),
            body.getTrfVersion(),
            body.getAllocationId(),
            body.getShipmentManifestId(),
            body.getDepartedAt().toInstant(),
            lines);
    return ResponseEntity.ok(Dto.command(departures.depart(idempotencyKey, xSourceSystem, cmd)));
  }
}
