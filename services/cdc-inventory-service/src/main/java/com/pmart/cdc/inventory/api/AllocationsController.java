package com.pmart.cdc.inventory.api;

import com.pmart.cdc.inventory.api.generated.AllocationsApi;
import com.pmart.cdc.inventory.api.generated.model.AddToAllocation200Response;
import com.pmart.cdc.inventory.api.generated.model.AddToAllocation200ResponseLinesInner;
import com.pmart.cdc.inventory.api.generated.model.AddToAllocation200ResponseLinesInnerLotsInner;
import com.pmart.cdc.inventory.api.generated.model.AddToAllocationRequest;
import com.pmart.cdc.inventory.api.generated.model.Allocation;
import com.pmart.cdc.inventory.api.generated.model.AllocationLinesInner;
import com.pmart.cdc.inventory.api.generated.model.CreateAllocation200Response;
import com.pmart.cdc.inventory.api.generated.model.CreateAllocationRequest;
import com.pmart.cdc.inventory.api.generated.model.ReleaseAllocation200Response;
import com.pmart.cdc.inventory.api.generated.model.ReleaseAllocationRequest;
import com.pmart.cdc.inventory.application.AllocationService;
import com.pmart.cdc.inventory.application.AllocationService.Add;
import com.pmart.cdc.inventory.application.AllocationService.AddLine;
import com.pmart.cdc.inventory.application.AllocationService.Allocate;
import com.pmart.cdc.inventory.application.AllocationService.AllocateLine;
import com.pmart.cdc.inventory.application.AllocationService.AllocationView;
import com.pmart.cdc.inventory.application.AllocationService.LineView;
import com.pmart.cdc.inventory.application.AllocationService.Release;
import com.pmart.cdc.inventory.application.AllocationService.ReleaseLine;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 AllocationsApi — 승인 TRF 할당·조회·해제. 업무 규칙은 application/domain 에 있다. */
@RestController
public class AllocationsController implements AllocationsApi {

  private final AllocationService service;

  public AllocationsController(AllocationService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<CreateAllocation200Response> createAllocation(
      String idempotencyKey,
      String xSourceSystem,
      CreateAllocationRequest body,
      @Nullable String traceparent) {
    List<AllocateLine> lines =
        body.getLines().stream()
            .map(l -> new AllocateLine(l.getSku(), l.getRequestedQty(), l.getMinRemainingDays()))
            .toList();
    var r =
        service
            .allocate(
                idempotencyKey,
                xSourceSystem,
                new Allocate(body.getCdcId(), body.getTrfId(), body.getTrfVersion(), lines))
            .value();
    AllocationView v = r.allocation();
    var out = new CreateAllocation200Response();
    out.setStatus(CreateAllocation200Response.StatusEnum.APPLIED);
    out.setRequestId(r.requestId());
    out.setResultId(v.allocationId());
    out.setAppliedQty(v.lines().stream().mapToInt(LineView::allocatedQty).sum());
    out.setOccurredAt(Dto.time(v.allocatedAt()));
    out.setAllocationId(v.allocationId());
    out.setTrfId(v.trfId());
    out.setTrfVersion(v.trfVersion());
    out.setState(CreateAllocation200Response.StateEnum.fromValue(v.state()));
    out.setVersion(v.version());
    out.setCommitId(v.commitId());
    out.setAllocatedAt(Dto.time(v.allocatedAt()));
    out.setLines(lines(v));
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<Allocation> getAllocation(String allocationId) {
    AllocationView v = service.get(allocationId);
    var out = new Allocation();
    out.setAllocationId(v.allocationId());
    out.setTrfId(v.trfId());
    out.setTrfVersion(v.trfVersion());
    out.setState(Allocation.StateEnum.fromValue(v.state()));
    out.setVersion(v.version());
    out.setCommitId(v.commitId());
    out.setAllocatedAt(Dto.time(v.allocatedAt()));
    out.setLines(lines(v));
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<ReleaseAllocation200Response> releaseAllocation(
      String allocationId,
      String idempotencyKey,
      String xSourceSystem,
      ReleaseAllocationRequest body,
      @Nullable Integer xExpectedVersion) {
    List<ReleaseLine> lines =
        body.getLines() == null
            ? List.of()
            : body.getLines().stream()
                .map(l -> new ReleaseLine(l.getSku(), l.getLot(), l.getLocationCode(), l.getQty()))
                .toList();
    var cmd =
        new Release(
            allocationId,
            body.getReason().getValue(),
            body.getSourceRef(),
            xExpectedVersion,
            lines,
            Boolean.TRUE.equals(body.getRecountHold()));
    return ResponseEntity.ok(Dto.command(service.release(idempotencyKey, xSourceSystem, cmd)));
  }

  @Override
  public ResponseEntity<AddToAllocation200Response> addToAllocation(
      String allocationId,
      String idempotencyKey,
      String xSourceSystem,
      AddToAllocationRequest body) {
    var cmd =
        new Add(
            allocationId,
            body.getReason().getValue(),
            body.getSourceRef(),
            body.getLines().stream()
                .map(l -> new AddLine(l.getSku(), l.getQty(), l.getMinRemainingDays()))
                .toList());
    var r = service.add(idempotencyKey, xSourceSystem, cmd).value();
    var out = new AddToAllocation200Response();
    out.setStatus(AddToAllocation200Response.StatusEnum.APPLIED);
    out.setRequestId(r.requestId());
    out.setResultId(r.commitId());
    out.setAddedQty(r.addedQty());
    out.setOccurredAt(Dto.time(r.at()));
    out.setLines(
        r.lines().stream()
            .map(
                l -> {
                  var d = new AddToAllocation200ResponseLinesInner();
                  d.setSku(l.sku());
                  d.setRequestedQty(l.requestedQty());
                  d.setAddedQty(l.addedQty());
                  d.setLots(
                      l.lots().stream()
                          .map(
                              x -> {
                                var lot = new AddToAllocation200ResponseLinesInnerLotsInner();
                                lot.setLot(x.lot());
                                lot.setLocationCode(x.locationCode());
                                lot.setQty(x.qty());
                                lot.setExpiresAt(x.expiresAt());
                                return lot;
                              })
                          .toList());
                  return d;
                })
            .toList());
    return ResponseEntity.ok(out);
  }

  private static List<AllocationLinesInner> lines(AllocationView v) {
    return v.lines().stream()
        .map(
            l -> {
              var d = new AllocationLinesInner();
              d.setSku(l.sku());
              d.setRequestedQty(l.requestedQty());
              d.setAllocatedQty(l.allocatedQty());
              d.setShortQty(l.shortQty());
              d.setReleasedQty(l.releasedQty());
              d.setDepartedQty(l.departedQty());
              d.setLots(
                  l.lots().stream()
                      .map(
                          x -> {
                            var lot = new AddToAllocation200ResponseLinesInnerLotsInner();
                            lot.setLot(x.lot());
                            lot.setLocationCode(x.locationCode());
                            lot.setQty(x.qty());
                            lot.setExpiresAt(x.expiresAt());
                            return lot;
                          })
                      .toList());
              return d;
            })
        .toList();
  }
}
