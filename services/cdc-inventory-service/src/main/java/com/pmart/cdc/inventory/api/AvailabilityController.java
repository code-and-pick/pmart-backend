package com.pmart.cdc.inventory.api;

import com.pmart.cdc.inventory.api.generated.AvailabilityApi;
import com.pmart.cdc.inventory.api.generated.model.Availability;
import com.pmart.cdc.inventory.api.generated.model.AvailabilityLotsInner;
import com.pmart.cdc.inventory.api.generated.model.AvailabilitySafety;
import com.pmart.cdc.inventory.api.generated.model.LotIdentification;
import com.pmart.cdc.inventory.application.InventoryQueries;
import com.pmart.cdc.inventory.application.InventoryQueries.AvailabilityView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 AvailabilityApi — 공급가능량 조회. 스냅샷 조회는 아직 인터페이스 default(501). */
@RestController
public class AvailabilityController implements AvailabilityApi {

  private final InventoryQueries queries;

  public AvailabilityController(InventoryQueries queries) {
    this.queries = queries;
  }

  @Override
  public ResponseEntity<Availability> getAvailability(String cdcId, String sku) {
    AvailabilityView v = queries.availability(cdcId, sku);
    var out = new Availability();
    out.setCdcId(v.cdcId());
    out.setSku(v.sku());
    out.setAsOf(Dto.asOf(v.asOf()));
    out.setOnHand(v.onHand());
    out.setEligible(v.eligible());
    out.setReserved(v.reserved());
    out.setHeld(v.held());
    var s = new AvailabilitySafety();
    s.setState(AvailabilitySafety.StateEnum.VALUE);
    s.setValue(v.safety().qty());
    s.setPolicyVersion(v.safety().policyVersion());
    s.setEffectiveFrom(Dto.time(v.safety().effectiveFrom()));
    out.setSafety(s);
    out.setAvailable(v.available());
    out.setTransit(v.transit());
    out.setHandlingActive(true);
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
                  return d;
                })
            .toList());
    return ResponseEntity.ok(out);
  }
}
