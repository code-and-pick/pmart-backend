package com.pmart.cdc.inventory.api;

import com.pmart.cdc.inventory.api.generated.ReconciliationApi;
import com.pmart.cdc.inventory.api.generated.model.ListTransits200Response;
import com.pmart.cdc.inventory.api.generated.model.ListTransits200ResponsePage;
import com.pmart.cdc.inventory.api.generated.model.Transit;
import com.pmart.cdc.inventory.api.generated.model.TransitLinesInner;
import com.pmart.cdc.inventory.api.generated.model.TransitState;
import com.pmart.cdc.inventory.application.InventoryQueries;
import com.pmart.cdc.inventory.application.InventoryQueries.TransitView;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 ReconciliationApi — Transit 조회(q·r·l·t)·일일 대사(0.16.0, 조회할 때 계산). */
@RestController
public class ReconciliationController implements ReconciliationApi {

  private final InventoryQueries queries;
  private final com.pmart.cdc.inventory.application.ReconciliationService reconciliation;

  public ReconciliationController(
      InventoryQueries queries,
      com.pmart.cdc.inventory.application.ReconciliationService reconciliation) {
    this.queries = queries;
    this.reconciliation = reconciliation;
  }

  /** LOT 추적(0.18.0) — cdc-quality 리콜이 센터·출발분을 찾는다. */
  @Override
  public ResponseEntity<com.pmart.cdc.inventory.api.generated.model.GetLotTrace200Response>
      getLotTrace(String sku, String lot) {
    var t = reconciliation.lotTrace(sku, lot);
    var out = new com.pmart.cdc.inventory.api.generated.model.GetLotTrace200Response();
    out.setSku(t.sku());
    out.setLot(t.lot());
    out.setAsOf(Dto.asOf(t.asOf()));
    out.setStock(
        t.stock().stream()
            .map(
                x ->
                    new com.pmart.cdc.inventory.api.generated.model
                            .GetLotTrace200ResponseStockInner()
                        .cdcId(x.cdcId())
                        .locationCode(x.locationCode())
                        .qualityState(x.qualityState())
                        .onHand(x.onHand())
                        .reserved(x.reserved()))
            .toList());
    out.setTransits(
        t.transits().stream()
            .map(
                x ->
                    new com.pmart.cdc.inventory.api.generated.model
                            .GetLotTrace200ResponseTransitsInner()
                        .cdcId(x.cdcId())
                        .trfId(x.trfId())
                        .departureId(x.departureId())
                        .mfcId(x.mfcId())
                        .departedAt(Dto.time(x.departedAt()))
                        .state(TransitState.fromValue(x.state()))
                        .q(x.q())
                        .received(x.received())
                        .loss(x.loss())
                        .unreconciled(x.unreconciled()))
            .toList());
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<
          com.pmart.cdc.inventory.api.generated.model.GetDailyReconciliation200Response>
      getDailyReconciliation(String cdcId, java.time.LocalDate date, @Nullable String sku) {
    var r = reconciliation.daily(cdcId, date, sku);
    var out = new com.pmart.cdc.inventory.api.generated.model.GetDailyReconciliation200Response();
    out.setCdcId(r.cdcId());
    out.setDate(r.date());
    out.setState(
        com.pmart.cdc.inventory.api.generated.model.GetDailyReconciliation200Response.StateEnum
            .fromValue(r.state()));
    out.setAsOf(Dto.asOf(r.asOf()));
    out.setItems(
        r.items().stream()
            .map(
                l -> {
                  var x = new com.pmart.cdc.inventory.api.generated.model.DailyReconciliationLine();
                  x.setSku(l.sku());
                  x.setOpening(l.opening());
                  x.setInitialBalance(l.initialBalance());
                  x.setAdjustmentIn(l.adjustmentIn());
                  x.setDeparted(l.departed());
                  x.setDisposed(l.disposed());
                  x.setAdjustmentOut(l.adjustmentOut());
                  x.setMovedNet(l.movedNet());
                  x.setClosing(l.closing());
                  x.setComputedClosing(l.computedClosing());
                  x.setDiscrepancy(l.discrepancy());
                  return x;
                })
            .toList());
    out.setReservationChecks(
        r.reservationChecks().stream()
            .map(
                c -> {
                  var x =
                      new com.pmart.cdc.inventory.api.generated.model
                          .GetDailyReconciliation200ResponseReservationChecksInner();
                  x.setLocationCode(c.locationCode());
                  x.setSku(c.sku());
                  x.setLot(c.lot());
                  x.setReserved(c.reserved());
                  x.setAllocationRemaining(c.allocationRemaining());
                  return x;
                })
            .toList());
    out.setTransitChecks(
        r.transitChecks().stream()
            .map(
                c -> {
                  var x =
                      new com.pmart.cdc.inventory.api.generated.model
                          .GetDailyReconciliation200ResponseTransitChecksInner();
                  x.setSku(c.sku());
                  x.setLedgerTransit(c.ledgerTransit());
                  x.setUnreconciled(c.unreconciled());
                  return x;
                })
            .toList());
    out.setOpenTransits(
        r.openTransits().stream()
            .map(
                t -> {
                  var x =
                      new com.pmart.cdc.inventory.api.generated.model
                          .GetDailyReconciliation200ResponseOpenTransitsInner();
                  x.setTrfId(t.trfId());
                  x.setDepartureId(t.departureId());
                  x.setMfcId(t.mfcId());
                  x.setState(TransitState.fromValue(t.state()));
                  x.setDepartedAt(Dto.time(t.departedAt()));
                  x.setUnreconciledQty(t.unreconciledQty());
                  return x;
                })
            .toList());
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<ListTransits200Response> listTransits(
      @Nullable String trfId,
      @Nullable String departureId,
      @Nullable TransitState state,
      Integer size,
      @Nullable String cursor) {
    var page =
        queries.transits(
            trfId,
            departureId,
            state == null ? null : state.getValue(),
            cursor,
            size == null ? 50 : size);
    var out = new ListTransits200Response();
    out.setAsOf(Dto.asOf(Instant.now()));
    out.setItems(page.items().stream().map(ReconciliationController::dto).toList());
    var p = new ListTransits200ResponsePage();
    p.setNextCursor(page.nextCursor());
    p.setSize(page.items().size());
    out.setPage(p);
    return ResponseEntity.ok(out);
  }

  static Transit dto(TransitView v) {
    var t = v.transit();
    var out = new Transit();
    out.setTrfId(t.trfId());
    out.setDepartureId(t.departureId());
    out.setMfcId(t.toMfcId());
    out.setState(TransitState.fromValue(t.state()));
    out.setDepartedAt(Dto.time(t.departedAt()));
    out.setReconciledAt(Dto.time(t.reconciledAt()));
    out.setReceiptIds(List.of());
    out.setLines(
        v.lines().stream()
            .map(
                l -> {
                  var x = new TransitLinesInner();
                  // 0.16.1: 미판정(0.11.0 pending)도 받은 수량 — 미정산 t 에서 뺀다
                  int r = l.normalQty() + l.quarantinedQty() + l.pendingQty();
                  x.setSku(l.sku());
                  x.setLot(l.lot());
                  x.setQ(l.q());
                  x.setR(r);
                  x.setNormalQty(l.normalQty());
                  x.setQuarantinedQty(l.quarantinedQty());
                  x.setPendingQty(l.pendingQty());
                  x.setL(l.lossQty());
                  x.setT(l.q() - r - l.lossQty());
                  return x;
                })
            .toList());
    return out;
  }
}
