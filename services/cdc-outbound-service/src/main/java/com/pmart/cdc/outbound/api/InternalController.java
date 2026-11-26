package com.pmart.cdc.outbound.api;

import com.pmart.cdc.outbound.api.generated.InternalApi;
import com.pmart.cdc.outbound.api.generated.model.DepartureValidation;
import com.pmart.cdc.outbound.api.generated.model.DepartureValidationMismatchesInner;
import com.pmart.cdc.outbound.api.generated.model.DepartureValidationTransfersInner;
import com.pmart.cdc.outbound.api.generated.model.IntakeTransfer202Response;
import com.pmart.cdc.outbound.api.generated.model.LpnComposition;
import com.pmart.cdc.outbound.api.generated.model.LpnCompositionContentsInner;
import com.pmart.cdc.outbound.api.generated.model.OutboundReadiness;
import com.pmart.cdc.outbound.api.generated.model.ShipmentManifest;
import com.pmart.cdc.outbound.api.generated.model.ShipmentManifestLinesInner;
import com.pmart.cdc.outbound.api.generated.model.TransferIntake;
import com.pmart.cdc.outbound.api.generated.model.TransferIntakeAllOfProgress;
import com.pmart.cdc.outbound.api.generated.model.TransferIntakeInput;
import com.pmart.cdc.outbound.api.generated.model.TransferIntakeInputLinesInner;
import com.pmart.cdc.outbound.api.generated.model.ValidateDepartureRequest;
import com.pmart.cdc.outbound.application.DepartureService;
import com.pmart.cdc.outbound.application.DepartureService.Composition;
import com.pmart.cdc.outbound.application.DepartureService.Validate;
import com.pmart.cdc.outbound.application.DepartureService.View;
import com.pmart.cdc.outbound.application.ReadinessService;
import com.pmart.cdc.outbound.application.TransferService;
import com.pmart.cdc.outbound.application.TransferService.Intake;
import com.pmart.cdc.outbound.application.TransferService.IntakeLine;
import com.pmart.cdc.outbound.infrastructure.OutboundStore.TransferLineRow;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 InternalApi — TRF 인수·조회(0.3.0), 출발 검증·매니페스트(0.4.0). 출발 준비 조회(0.6.0). */
@RestController
public class InternalController implements InternalApi {

  private final TransferService transfers;
  private final DepartureService departures;
  private final ReadinessService readiness;

  public InternalController(
      TransferService transfers, DepartureService departures, ReadinessService readiness) {
    this.transfers = transfers;
    this.departures = departures;
    this.readiness = readiness;
  }

  @Override
  public ResponseEntity<DepartureValidation> validateDeparture(
      String idempotencyKey,
      String xSourceSystem,
      ValidateDepartureRequest body,
      @Nullable String traceparent) {
    var v =
        departures.validate(
            new Validate(
                body.getDepartureId(),
                body.getTripId(),
                body.getVehicleId(),
                body.getDepartedAt().toInstant(),
                body.getSource().getValue(),
                body.getEvidenceRef(),
                body.getTrfCompositions().stream()
                    .map(
                        c ->
                            new Composition(
                                c.getTrfId(),
                                c.getMfcId(),
                                c.getLpns() == null
                                    ? List.of()
                                    : List.copyOf(new java.util.LinkedHashSet<>(c.getLpns())),
                                c.getSealNos() == null ? List.of() : List.copyOf(c.getSealNos())))
                    .toList()));
    return ResponseEntity.ok(dto(v));
  }

  @Override
  public ResponseEntity<DepartureValidation> getDepartureValidation(String departureId) {
    return ResponseEntity.ok(dto(departures.view(departureId)));
  }

  @Override
  public ResponseEntity<ShipmentManifest> getShipmentManifest(String trfId) {
    var m = departures.manifest(trfId);
    var out = new ShipmentManifest();
    out.setShipmentManifestId(m.manifestId());
    out.setTrfId(m.transfer().trfId());
    out.setTrfVersion(m.transfer().trfVersion());
    out.setDepartureId(m.departure().departureId());
    out.setTripId(m.departure().tripId());
    out.setVehicleId(m.departure().vehicleId());
    out.setCdcId(m.transfer().cdcId());
    out.setMfcId(m.transfer().mfcId());
    out.setDepartedAt(Dto.time(m.departure().departedAt()));
    out.setLpns(
        m.lpns().stream()
            .map(
                l -> {
                  var c = new LpnComposition();
                  c.setLpn(l.lpn());
                  c.setUnitType(LpnComposition.UnitTypeEnum.fromValue(l.unitType()));
                  c.setState(LpnComposition.StateEnum.fromValue(l.sealState()));
                  c.setParentLpn(l.parentLpn());
                  c.setSealNo(l.sealNo());
                  c.setTemperatureZone(
                      l.temperatureZone() == null
                          ? null
                          : LpnComposition.TemperatureZoneEnum.fromValue(l.temperatureZone()));
                  c.setWeightG(l.weightG());
                  c.setContents(
                      m.contents().getOrDefault(l.lpn(), List.of()).stream()
                          .map(
                              x -> {
                                var y = new LpnCompositionContentsInner();
                                y.setSku(x.sku());
                                y.setLot(x.lot());
                                y.setQty(x.qty());
                                return y;
                              })
                          .toList());
                  return c;
                })
            .toList());
    out.setLines(
        m.lines().stream()
            .map(
                l -> {
                  var x = new ShipmentManifestLinesInner();
                  x.setSku(l.sku());
                  x.setLot(l.lot());
                  x.setExpiresAt(l.expiresAt());
                  x.setQty(l.qty());
                  return x;
                })
            .toList());
    out.setIssuedAt(Dto.time(m.departure().validatedAt()));
    return ResponseEntity.ok(out);
  }

  static DepartureValidation dto(View v) {
    var d = v.departure();
    var out = new DepartureValidation();
    out.setDepartureId(d.departureId());
    out.setTripId(d.tripId());
    out.setVehicleId(d.vehicleId());
    out.setSource(DepartureValidation.SourceEnum.fromValue(d.source()));
    out.setValidatedAt(Dto.time(d.validatedAt()));
    out.setOverallResult(DepartureValidation.OverallResultEnum.fromValue(d.overallResult()));
    out.setTransfers(
        v.transfers().stream()
            .map(
                t -> {
                  var x = new DepartureValidationTransfersInner();
                  x.setTrfId(t.trfId());
                  x.setResult(DepartureValidationTransfersInner.ResultEnum.fromValue(t.result()));
                  x.setDepartedQty(t.departedQty());
                  if (t.postingStatus() != null && !"PENDING".equals(t.postingStatus())) {
                    x.setPostingStatus(
                        DepartureValidationTransfersInner.PostingStatusEnum.fromValue(
                            t.postingStatus()));
                  } else if ("PENDING".equals(t.postingStatus())) {
                    x.setPostingStatus(
                        DepartureValidationTransfersInner.PostingStatusEnum.ACCEPTED);
                  }
                  x.setCommitId(t.commitId());
                  x.setShipmentManifestId(t.manifestId());
                  if (t.residualStatus() != null) {
                    x.setResidualStatus(
                        DepartureValidationTransfersInner.ResidualStatusEnum.fromValue(
                            t.residualStatus()));
                  }
                  x.setResidualReason(t.residualReason());
                  return x;
                })
            .toList());
    out.setMismatches(
        v.mismatches().stream()
            .map(
                m -> {
                  var x = new DepartureValidationMismatchesInner();
                  x.setKind(DepartureValidationMismatchesInner.KindEnum.fromValue(m.kind()));
                  x.setLpn(m.lpn());
                  x.setTrfId(m.trfId());
                  x.setDetail(m.detail());
                  return x;
                })
            .toList());
    return out;
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
    out.setProgress(
        v.lines().stream()
            .map(l -> progress(l, v.stages().getOrDefault(l.sku(), new int[4])))
            .toList());
    out.setDepartureId(null);
    return ResponseEntity.ok(out);
  }

  static TransferIntakeAllOfProgress progress(TransferLineRow l, int[] st) {
    var p = new TransferIntakeAllOfProgress();
    p.setSku(l.sku());
    p.setApprovedQty(l.approvedQty());
    p.setAllocatedQty(l.allocatedQty());
    p.setPickedQty(l.pickedQty());
    p.setSortedQty(st[0]);
    p.setInspectedQty(st[1]);
    p.setLoadedQty(st[2]);
    p.setDepartedQty(st[3]);
    return p;
  }

  @Override
  public ResponseEntity<com.pmart.cdc.outbound.api.generated.model.GetTransportFeed200Response>
      getTransportFeed(
          String cdcId, java.time.OffsetDateTime dueFrom, java.time.OffsetDateTime dueTo) {
    var f = readiness.feed(cdcId, dueFrom.toInstant(), dueTo.toInstant());
    var out = new com.pmart.cdc.outbound.api.generated.model.GetTransportFeed200Response();
    out.setAsOf(Dto.time(f.asOf()));
    out.setTruncated(f.truncated());
    out.setItems(
        new java.util.ArrayList<>(
            f.items().stream()
                .map(
                    i ->
                        new com.pmart.cdc.outbound.api.generated.model
                                .GetTransportFeed200ResponseItemsInner()
                            .trfId(i.trfId())
                            .trfNumber(i.trfNumber())
                            .trfVersion(i.trfVersion())
                            .mfcId(i.mfcId())
                            .state(i.state())
                            .dueAt(Dto.time(i.dueAt()))
                            .approvedQty(i.approvedQty())
                            .allocatedQty(i.allocatedQty())
                            .tempClasses(
                                new java.util.ArrayList<>(
                                    i.tempClasses().stream()
                                        .map(
                                            com.pmart.cdc.outbound.api.generated.model
                                                    .GetTransportFeed200ResponseItemsInner
                                                    .TempClassesEnum
                                                ::fromValue)
                                        .toList()))
                            .boxCount(i.boxCount())
                            .rtCount(i.rtCount())
                            .weightG((int) Math.min(Integer.MAX_VALUE, i.weightG()))
                            .receivedAt(Dto.time(i.receivedAt())))
                .toList()));
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<OutboundReadiness> getOutboundReadiness(String trfId) {
    return ResponseEntity.ok(readinessDto(readiness.of(trfId)));
  }

  /** 출발 준비 응답(내부·직원 조회 같은 모양 — 0.16.0). */
  static OutboundReadiness readinessDto(
      com.pmart.cdc.outbound.application.ReadinessService.Readiness r) {
    var out = new OutboundReadiness();
    out.setTrfId(r.trfId());
    out.setAsOf(Dto.asOf(r.asOf()));
    out.setReadiness(OutboundReadiness.ReadinessEnum.fromValue(r.readiness()));
    out.setReadyLpnCount(r.readyLpnCount());
    out.setReadyQty(r.readyQty());
    out.setTotalWeightG((int) Math.min(Integer.MAX_VALUE, r.totalWeightG()));
    out.setTemperatureZones(
        r.temperatureZones().stream()
            .map(OutboundReadiness.TemperatureZonesEnum::fromValue)
            .toList());
    out.setEarliestReadyAt(Dto.time(r.earliestReadyAt()));
    out.setBlockers(r.blockers());
    return out;
  }
}
