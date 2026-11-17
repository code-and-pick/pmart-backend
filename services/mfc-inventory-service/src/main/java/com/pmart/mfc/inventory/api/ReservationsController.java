package com.pmart.mfc.inventory.api;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.inventory.api.generated.ReservationsApi;
import com.pmart.mfc.inventory.api.generated.model.ConfirmReservationRequest;
import com.pmart.mfc.inventory.api.generated.model.ReleaseReservationRequest;
import com.pmart.mfc.inventory.api.generated.model.Reservation;
import com.pmart.mfc.inventory.api.generated.model.ReservationPartState;
import com.pmart.mfc.inventory.api.generated.model.ReservationPartsInner;
import com.pmart.mfc.inventory.api.generated.model.ReserveInventory201Response;
import com.pmart.mfc.inventory.api.generated.model.ReserveInventoryRequest;
import com.pmart.mfc.inventory.application.ReservationService;
import com.pmart.mfc.inventory.application.ReservationService.ReserveCommand;
import com.pmart.mfc.inventory.application.ReservationService.ReserveLine;
import com.pmart.mfc.inventory.domain.StockRules.Line;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 ReservationsApi — 재고 예약·확정·해제·조회(서비스 간). 업무 규칙은 application/domain 에 있고 여기서는 변환만 한다. */
@RestController
public class ReservationsController implements ReservationsApi {

  private final ReservationService service;
  private final com.pmart.mfc.inventory.application.ShortageService shortages;
  private final com.pmart.mfc.inventory.application.LocationAllocationService allocations;

  public ReservationsController(
      ReservationService service,
      com.pmart.mfc.inventory.application.ShortageService shortages,
      com.pmart.mfc.inventory.application.LocationAllocationService allocations) {
    this.service = service;
    this.shortages = shortages;
    this.allocations = allocations;
  }

  @Override
  public ResponseEntity<com.pmart.mfc.inventory.api.generated.model.PickPlan>
      allocateReservationLocations(
          String reservationId, String idempotencyKey, String xSourceSystem) {
    allocations.allocate(reservationId);
    return ResponseEntity.ok(pickPlanDto(allocations.plan(reservationId)));
  }

  @Override
  public ResponseEntity<ReserveInventory201Response> reserveInventory(
      String idempotencyKey,
      String xSourceSystem,
      ReserveInventoryRequest body,
      @Nullable String traceparent) {
    List<ReserveLine> lines = new ArrayList<>();
    for (var l : body.getLines()) {
      if (l.getLotConstraint() != null) {
        throw new PmartException(
            ErrorCode.VALIDATION_FAILED, "lotConstraint 는 아직 지원하지 않습니다(FEFO·최소 잔여일로 배정).");
      }
      lines.add(new ReserveLine(l.getOrderLineId(), l.getSku(), l.getQty()));
    }
    var r =
        service.reserve(
            idempotencyKey,
            xSourceSystem,
            new ReserveCommand(
                body.getOrderId(),
                body.getMfcId(),
                body.getTtlMinutes(),
                body.getPromisedDate(),
                lines));
    return ResponseEntity.status(HttpStatus.CREATED).body(Commands.dto(r));
  }

  @Override
  public ResponseEntity<Reservation> getReservation(String reservationId) {
    var v = service.get(reservationId);
    var r = v.row();
    var out = new Reservation();
    out.setReservationId(r.reservationId());
    out.setOrderId(r.orderId());
    out.setMfcId(r.mfcId());
    out.setVersion(r.version());
    out.setState(ReservationPartState.fromValue(r.state().name()));
    out.setExpiresAt(r.expiresAt() == null ? null : SkuHandlingsController.time(r.expiresAt()));
    List<ReservationPartsInner> parts = new ArrayList<>();
    for (var part : v.parts()) {
      var p = new ReservationPartsInner();
      p.setPartId(part.partId());
      p.setOrderLineId(part.orderLineId());
      p.setSku(part.sku());
      p.setLotId(part.lotId());
      p.setKind(ReservationPartsInner.KindEnum.fromValue(part.kind()));
      p.setQty(part.qty());
      p.setState(ReservationPartState.fromValue(part.state().name()));
      p.setBlockedReason(part.blockedReason());
      parts.add(p);
    }
    out.setParts(parts);
    var asOf = new com.pmart.mfc.inventory.api.generated.model.GetAvailability200ResponseAsOf();
    asOf.setAsOf(SkuHandlingsController.time(Instant.now()));
    asOf.setSourceVersion(r.version());
    out.setAsOf(asOf);
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<ReserveInventory201Response> cancelReturn(
      String reservationId,
      String idempotencyKey,
      String xSourceSystem,
      com.pmart.mfc.inventory.api.generated.model.CancelReturnRequest body) {
    var r =
        service.cancelReturn(
            idempotencyKey,
            xSourceSystem,
            new ReservationService.CancelReturn(
                reservationId,
                body.getReason(),
                body.getReturns().stream()
                    .map(
                        l ->
                            new ReservationService.ReturnLine(
                                l.getOrderLineId(),
                                l.getSku(),
                                l.getLotId(),
                                l.getLocationCode(),
                                l.getWorkState().getValue(),
                                l.getQty()))
                    .toList()));
    return ResponseEntity.ok(Commands.dto(r));
  }

  @Override
  public ResponseEntity<ReserveInventory201Response> releaseShortfall(
      String reservationId,
      String idempotencyKey,
      String xSourceSystem,
      com.pmart.mfc.inventory.api.generated.model.ReleaseShortfallRequest body) {
    var r =
        shortages.releaseShortfall(
            idempotencyKey,
            xSourceSystem,
            new com.pmart.mfc.inventory.application.ShortageService.ShortfallRelease(
                reservationId, body.getOrderLineId(), body.getQty(), body.getReason().getValue()));
    return ResponseEntity.ok(Commands.dto(r));
  }

  @Override
  public ResponseEntity<com.pmart.mfc.inventory.api.generated.model.PickPlan> getPickPlan(
      String reservationId) {
    return ResponseEntity.ok(pickPlanDto(allocations.plan(reservationId)));
  }

  private static com.pmart.mfc.inventory.api.generated.model.PickPlan pickPlanDto(
      com.pmart.mfc.inventory.application.LocationAllocationService.Plan plan) {
    var r = plan.reservation();
    var out = new com.pmart.mfc.inventory.api.generated.model.PickPlan();
    out.setReservationId(r.reservationId());
    out.setOrderId(r.orderId());
    out.setMfcId(r.mfcId());
    out.setState(ReservationPartState.fromValue(r.state().name()));
    var parts = new ArrayList<com.pmart.mfc.inventory.api.generated.model.PickPlanPartsInner>();
    for (var ap : plan.parts()) {
      var pp = ap.base();
      var p = new com.pmart.mfc.inventory.api.generated.model.PickPlanPartsInner();
      p.setPartId(pp.part().partId());
      p.setOrderLineId(pp.part().orderLineId());
      p.setSku(pp.part().sku());
      p.setLotId(pp.part().lotId());
      p.setLotNo(pp.lotNo());
      p.setExpiryDate(pp.expiryDate());
      p.setState(ReservationPartState.fromValue(pp.part().state().name()));
      p.setQty(pp.part().qty());
      p.setPickedQty(pp.part().pickedQty());
      p.setPackedQty(pp.part().packedQty());
      var locs =
          new ArrayList<
              com.pmart.mfc.inventory.api.generated.model.PickPlanPartsInnerLocationsInner>();
      for (var e : ap.locations()) {
        var l = new com.pmart.mfc.inventory.api.generated.model.PickPlanPartsInnerLocationsInner();
        l.setLocationCode(e.locationCode());
        l.setQty(e.qty());
        l.setAllocatedQty(e.allocatedQty());
        locs.add(l);
      }
      p.setLocations(locs);
      var allocs =
          new ArrayList<
              com.pmart.mfc.inventory.api.generated.model.PickPlanPartsInnerAllocationsInner>();
      for (var e : ap.allocations()) {
        var a =
            new com.pmart.mfc.inventory.api.generated.model.PickPlanPartsInnerAllocationsInner();
        a.setLocationCode(e.getKey());
        a.setQty(e.getValue());
        allocs.add(a);
      }
      p.setAllocations(allocs);
      p.setUnallocatedQty(ap.unallocatedQty());
      parts.add(p);
    }
    out.setParts(parts);
    return out;
  }

  @Override
  public ResponseEntity<ReserveInventory201Response> confirmReservation(
      String reservationId,
      String idempotencyKey,
      String xSourceSystem,
      ConfirmReservationRequest body,
      @Nullable Integer xExpectedVersion) {
    var r =
        service.confirm(
            idempotencyKey,
            xSourceSystem,
            reservationId,
            body.getOrderId(),
            body.getPaymentAttemptId(),
            xExpectedVersion);
    return ResponseEntity.ok(Commands.dto(r));
  }

  /** S4 예약 시간 변경 — 배송 약속일(2026-11-16). */
  @Override
  public ResponseEntity<ReserveInventory201Response> changeReservationPromisedDate(
      String reservationId,
      String idempotencyKey,
      String xSourceSystem,
      com.pmart.mfc.inventory.api.generated.model.ChangeReservationPromisedDateRequest body) {
    return ResponseEntity.ok(
        Commands.dto(
            service.changePromisedDate(
                idempotencyKey,
                xSourceSystem,
                reservationId,
                body.getOrderId(),
                body.getPromisedDate())));
  }

  @Override
  public ResponseEntity<ReserveInventory201Response> releaseReservation(
      String reservationId,
      String idempotencyKey,
      String xSourceSystem,
      ReleaseReservationRequest body,
      @Nullable Integer xExpectedVersion) {
    List<Line> lines =
        body.getLines() == null
            ? null
            : body.getLines().stream().map(l -> new Line(l.getSku(), l.getQty())).toList();
    var r =
        service.release(
            idempotencyKey,
            xSourceSystem,
            reservationId,
            body.getReason().getValue(),
            body.getSourceRef(),
            lines,
            xExpectedVersion);
    return ResponseEntity.ok(Commands.dto(r));
  }
}
