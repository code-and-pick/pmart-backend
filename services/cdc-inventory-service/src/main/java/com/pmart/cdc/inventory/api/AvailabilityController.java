package com.pmart.cdc.inventory.api;

import com.pmart.cdc.inventory.api.generated.AvailabilityApi;
import com.pmart.cdc.inventory.api.generated.model.Availability;
import com.pmart.cdc.inventory.api.generated.model.AvailabilityLotsInner;
import com.pmart.cdc.inventory.api.generated.model.AvailabilityLotsInnerLocationsInner;
import com.pmart.cdc.inventory.api.generated.model.AvailabilitySafety;
import com.pmart.cdc.inventory.api.generated.model.GetSnapshot200Response;
import com.pmart.cdc.inventory.api.generated.model.ListRecountHolds200Response;
import com.pmart.cdc.inventory.api.generated.model.LotIdentification;
import com.pmart.cdc.inventory.api.generated.model.QualityState;
import com.pmart.cdc.inventory.api.generated.model.RecountHold;
import com.pmart.cdc.inventory.api.generated.model.ReleaseRecountHoldRequest;
import com.pmart.cdc.inventory.application.InventoryQueries;
import com.pmart.cdc.inventory.application.InventoryQueries.AvailabilityView;
import com.pmart.cdc.inventory.application.RecountHoldService;
import com.pmart.cdc.inventory.infrastructure.RecountHoldStore.HoldRow;
import com.pmart.common.web.PmartException;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 AvailabilityApi — 공급가능량 조회, 재고 스냅샷(0.4.0, LOT 마다 위치별 현물·예약). */
@RestController
public class AvailabilityController implements AvailabilityApi {

  private final InventoryQueries queries;
  private final RecountHoldService holds;
  private final com.pmart.cdc.inventory.infrastructure.ProductNames names;

  public AvailabilityController(
      InventoryQueries queries,
      RecountHoldService holds,
      com.pmart.cdc.inventory.infrastructure.ProductNames names) {
    this.queries = queries;
    this.holds = holds;
    this.names = names;
  }

  @Override
  public ResponseEntity<ListRecountHolds200Response> listRecountHolds(
      String cdcId, @Nullable String state) {
    var out = new ListRecountHolds200Response();
    var items = holds.list(cdcId, state).stream().map(AvailabilityController::hold).toList();
    var n = names.of(items.stream().map(RecountHold::getSku).toList());
    items.forEach(x -> x.setProductName(n.get(x.getSku())));
    out.setItems(items);
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<RecountHold> releaseRecountHold(
      String cdcId,
      String holdId,
      String idempotencyKey,
      String xSourceSystem,
      ReleaseRecountHoldRequest body) {
    var x =
        hold(holds.release(idempotencyKey, xSourceSystem, cdcId, holdId, body.getReason()).value());
    x.setProductName(names.of(List.of(x.getSku())).get(x.getSku()));
    return ResponseEntity.ok(x);
  }

  private static RecountHold hold(HoldRow h) {
    var x = new RecountHold();
    x.setHoldId(h.holdId());
    x.setSku(h.sku());
    x.setLot(h.lot());
    x.setLocationCode(h.locationCode());
    x.setState(RecountHold.StateEnum.fromValue(h.state()));
    x.setShortQty(h.shortQty());
    x.setAllocationId(h.allocationId());
    x.setSourceRef(h.sourceRef());
    x.setCreatedAt(Dto.time(h.createdAt()));
    x.setReleasedAt(Dto.time(h.releasedAt()));
    x.setReleasedBy(h.releasedBy());
    x.setReleaseReason(h.releaseReason());
    return x;
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

  /** DC 백오피스 재고 현황(2026-11-25) — 내부 스냅샷과 같은 내용, 자기 센터 운영자만. */
  @Override
  public ResponseEntity<GetSnapshot200Response> getCenterStockSnapshot(
      String cdcId, @Nullable String sku, Boolean withPlaces) {
    com.pmart.cdc.inventory.application.Operator.requireCenter(cdcId);
    var snap = queries.snapshot(cdcId, sku);
    var out = new GetSnapshot200Response();
    out.setSnapshotId(snap.snapshotId());
    out.setCdcId(snap.cdcId());
    out.setAsOf(Dto.asOf(snap.asOf()));
    boolean places = Boolean.TRUE.equals(withPlaces);
    var items = snap.items().stream().map(v -> dto(v, places)).toList();
    var n = names.of(items.stream().map(Availability::getSku).toList()); // 0.17.0 CI-1
    items.forEach(x -> x.setProductName(n.get(x.getSku())));
    out.setItems(items);
    return ResponseEntity.ok(out);
  }

  /** DC 백오피스 칸별 재고(0.9.0 M-6) — 스냅샷을 칸 기준으로 펼침, 칸 → SKU → LOT 순. */
  @Override
  public ResponseEntity<
          com.pmart.cdc.inventory.api.generated.model.ListCenterLocationStocks200Response>
      listCenterLocationStocks(
          String cdcId, @Nullable String locationPrefix, @Nullable String sku, Integer limit) {
    com.pmart.cdc.inventory.application.Operator.requireCenter(cdcId);
    int max = limit == null ? 500 : Math.min(2000, Math.max(1, limit));
    String prefix =
        locationPrefix == null || locationPrefix.isBlank() ? null : locationPrefix.strip();
    var snap = queries.snapshot(cdcId, sku);
    var rows =
        new java.util.ArrayList<
            com.pmart.cdc.inventory.api.generated.model
                .ListCenterLocationStocks200ResponseItemsInner>();
    for (var v : snap.items()) {
      for (var l : v.lots()) {
        for (var pl : l.places()) {
          if (prefix != null && !pl.locationCode().startsWith(prefix)) {
            continue;
          }
          rows.add(
              new com.pmart.cdc.inventory.api.generated.model
                      .ListCenterLocationStocks200ResponseItemsInner()
                  .locationCode(pl.locationCode())
                  .sku(v.sku())
                  .lot(l.lot())
                  .qualityState(pl.qualityState())
                  .onHand(pl.onHand())
                  .reserved(pl.reserved())
                  .expiresAt(l.expiresAt()));
        }
      }
    }
    rows.sort(
        java.util.Comparator.comparing(
                com.pmart.cdc.inventory.api.generated.model
                        .ListCenterLocationStocks200ResponseItemsInner
                    ::getLocationCode)
            .thenComparing(
                com.pmart.cdc.inventory.api.generated.model
                        .ListCenterLocationStocks200ResponseItemsInner
                    ::getSku)
            .thenComparing(
                com.pmart.cdc.inventory.api.generated.model
                        .ListCenterLocationStocks200ResponseItemsInner
                    ::getLot));
    boolean cut = rows.size() > max;
    var out = new com.pmart.cdc.inventory.api.generated.model.ListCenterLocationStocks200Response();
    out.setCdcId(cdcId);
    out.setAsOf(Dto.asOf(snap.asOf()));
    out.setTruncated(cut);
    var page = new java.util.ArrayList<>(cut ? rows.subList(0, max) : rows);
    var n =
        names.of(
            page.stream()
                .map(
                    com.pmart.cdc.inventory.api.generated.model
                            .ListCenterLocationStocks200ResponseItemsInner
                        ::getSku)
                .toList()); // 0.17.0 CI-1
    page.forEach(x -> x.setProductName(n.get(x.getSku())));
    out.setItems(page);
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
