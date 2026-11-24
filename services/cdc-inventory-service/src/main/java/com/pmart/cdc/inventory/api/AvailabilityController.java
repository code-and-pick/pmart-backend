package com.pmart.cdc.inventory.api;

import com.pmart.cdc.inventory.api.generated.AvailabilityApi;
import com.pmart.cdc.inventory.api.generated.model.Availability;
import com.pmart.cdc.inventory.api.generated.model.AvailabilityLotsInner;
import com.pmart.cdc.inventory.api.generated.model.AvailabilityLotsInnerLocationsInner;
import com.pmart.cdc.inventory.api.generated.model.AvailabilitySafety;
import com.pmart.cdc.inventory.api.generated.model.GetSnapshot200Response;
import com.pmart.cdc.inventory.api.generated.model.LotIdentification;
import com.pmart.cdc.inventory.api.generated.model.QualityState;
import com.pmart.cdc.inventory.application.InventoryQueries;
import com.pmart.cdc.inventory.application.InventoryQueries.AvailabilityView;
import com.pmart.common.web.PmartException;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 AvailabilityApi — 공급가능량 조회, 재고 스냅샷(0.4.0, LOT 마다 위치별 현물·예약). */
@RestController
public class AvailabilityController implements AvailabilityApi {

  private final InventoryQueries queries;

  public AvailabilityController(InventoryQueries queries) {
    this.queries = queries;
  }

  @Override
  public ResponseEntity<Availability> getAvailability(String cdcId, String sku) {
    return ResponseEntity.ok(dto(queries.availability(cdcId, sku), false));
  }

  @Override
  public ResponseEntity<GetSnapshot200Response> getSnapshot(
      String cdcId, @Nullable String sku, @Nullable String snapshotId) {
    if (snapshotId != null) {
      throw PmartException.notFound("NOT_FOUND", "지난 스냅샷 " + snapshotId + " 은 보관하지 않습니다(지금 장부만).");
    }
    var snap = queries.snapshot(cdcId, sku);
    var out = new GetSnapshot200Response();
    out.setSnapshotId(snap.snapshotId());
    out.setCdcId(snap.cdcId());
    out.setAsOf(Dto.asOf(snap.asOf()));
    out.setItems(snap.items().stream().map(v -> dto(v, true)).toList());
    return ResponseEntity.ok(out);
  }

  static Availability dto(AvailabilityView v, boolean withPlaces) {
    var out = new Availability();
    out.setCdcId(v.cdcId());
    out.setSku(v.sku());
    out.setAsOf(Dto.asOf(v.asOf()));
    out.setOnHand(v.onHand());
    out.setEligible(v.eligible());
    out.setReserved(v.reserved());
    out.setHeld(v.held());
    var s = new AvailabilitySafety();
    if (v.safety() == null) {
      s.setState(AvailabilitySafety.StateEnum.NOT_SET);
    } else {
      s.setState(AvailabilitySafety.StateEnum.VALUE);
      s.setValue(v.safety().qty());
      s.setPolicyVersion(v.safety().policyVersion());
      s.setEffectiveFrom(Dto.time(v.safety().effectiveFrom()));
    }
    out.setSafety(s);
    out.setAvailable(v.available());
    out.setTransit(v.transit());
    out.setHandlingActive(v.handlingActive());
    out.setLots(
        v.lots().stream()
            .map(
                l -> {
                  var d = new AvailabilityLotsInner();
                  d.setLot(l.lot());
                  d.setExpiresAt(l.expiresAt());
                  d.setRemainingDays(l.remainingDays());
                  d.setIdentification(LotIdentification.fromValue(l.identification()));
                  d.setEligible(l.eligible());
                  d.setReserved(l.reserved());
                  d.setHeld(l.held());
                  if (withPlaces) {
                    d.setLocations(
                        l.places().stream()
                            .map(
                                p -> {
                                  var x = new AvailabilityLotsInnerLocationsInner();
                                  x.setLocationCode(p.locationCode());
                                  x.setQualityState(QualityState.fromValue(p.qualityState()));
                                  x.setOnHand(p.onHand());
                                  x.setReserved(p.reserved());
                                  return x;
                                })
                            .toList());
                  }
                  return d;
                })
            .toList());
    return out;
  }
}
