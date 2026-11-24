package com.pmart.cdc.outbound.api;

import com.pmart.cdc.outbound.api.generated.InternalApi;
import com.pmart.cdc.outbound.api.generated.model.IntakeTransfer202Response;
import com.pmart.cdc.outbound.api.generated.model.TransferIntake;
import com.pmart.cdc.outbound.api.generated.model.TransferIntakeAllOfProgress;
import com.pmart.cdc.outbound.api.generated.model.TransferIntakeInput;
import com.pmart.cdc.outbound.api.generated.model.TransferIntakeInputLinesInner;
import com.pmart.cdc.outbound.application.TransferService;
import com.pmart.cdc.outbound.application.TransferService.Intake;
import com.pmart.cdc.outbound.application.TransferService.IntakeLine;
import com.pmart.cdc.outbound.infrastructure.OutboundStore.TransferLineRow;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 InternalApi — TRF 인수·조회(0.3.0). 출발 검증·매니페스트·출발 준비는 아직 인터페이스 default(501). */
@RestController
public class InternalController implements InternalApi {

  private final TransferService transfers;

  public InternalController(TransferService transfers) {
    this.transfers = transfers;
  }

  @Override
  public ResponseEntity<IntakeTransfer202Response> intakeTransfer(
      String idempotencyKey,
      String xSourceSystem,
      TransferIntakeInput body,
      @Nullable String traceparent) {
    var r =
        transfers.intake(
            idempotencyKey,
            new Intake(
                body.getTrfId(),
                body.getTrfVersion(),
                body.getCdcId(),
                body.getMfcId(),
                body.getApprovalRef(),
                body.getDeadlineMeaning().getValue(),
                body.getDueAt().toInstant(),
                body.getLines().stream()
                    .map(l -> new IntakeLine(l.getSku(), l.getApprovedQty()))
                    .toList()));
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(Dto.command(r));
  }

  @Override
  public ResponseEntity<TransferIntake> getTransferIntake(String trfId) {
    var v = transfers.get(trfId);
    var t = v.transfer();
    var out = new TransferIntake();
    out.setTrfId(t.trfId());
    out.setTrfVersion(t.trfVersion());
    out.setCdcId(t.cdcId());
    out.setMfcId(t.mfcId());
    out.setApprovalRef(t.approvalRef());
    out.setDeadlineMeaning(TransferIntake.DeadlineMeaningEnum.fromValue(t.deadlineMeaning()));
    out.setDueAt(Dto.time(t.dueAt()));
    out.setLines(
        v.lines().stream()
            .map(
                l -> {
                  var x = new TransferIntakeInputLinesInner();
                  x.setSku(l.sku());
                  x.setApprovedQty(l.approvedQty());
                  return x;
                })
            .toList());
    out.setState(TransferIntake.StateEnum.fromValue(t.state()));
    out.setVersion(t.version());
    out.setAllocationId(t.allocationId());
    out.setWaveId(t.waveId());
    out.setProgress(v.lines().stream().map(InternalController::progress).toList());
    return ResponseEntity.ok(out);
  }

  static TransferIntakeAllOfProgress progress(TransferLineRow l) {
    var p = new TransferIntakeAllOfProgress();
    p.setSku(l.sku());
    p.setApprovedQty(l.approvedQty());
    p.setAllocatedQty(l.allocatedQty());
    p.setPickedQty(l.pickedQty());
    p.setSortedQty(0);
    p.setInspectedQty(0);
    p.setLoadedQty(0);
    p.setDepartedQty(0);
    return p;
  }
}
