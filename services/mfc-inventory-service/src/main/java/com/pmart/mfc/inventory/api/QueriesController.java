package com.pmart.mfc.inventory.api;

import com.pmart.mfc.inventory.api.generated.QueriesApi;
import com.pmart.mfc.inventory.api.generated.model.Availability;
import com.pmart.mfc.inventory.api.generated.model.CenterStock;
import com.pmart.mfc.inventory.api.generated.model.CenterStockLotsInner;
import com.pmart.mfc.inventory.api.generated.model.CenterStockUnitsInner;
import com.pmart.mfc.inventory.api.generated.model.Commit;
import com.pmart.mfc.inventory.api.generated.model.CommitEntriesInner;
import com.pmart.mfc.inventory.api.generated.model.GetAvailability200Response;
import com.pmart.mfc.inventory.api.generated.model.GetAvailability200ResponseAsOf;
import com.pmart.mfc.inventory.api.generated.model.GetRequestResult200Response;
import com.pmart.mfc.inventory.api.generated.model.InventoryApplyStatus;
import com.pmart.mfc.inventory.api.generated.model.QualityState;
import com.pmart.mfc.inventory.api.generated.model.WorkState;
import com.pmart.mfc.inventory.application.CorrectionService;
import com.pmart.mfc.inventory.application.StockQueryService;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 QueriesApi — 가용(A = E − R, 원장 행 없음 = 0, 취급 여부 함께), 전기 조회(commitId), 원결과 조회(requestId). 스냅샷은
 * 인터페이스 default 의 501.
 */
@RestController
public class QueriesController implements QueriesApi {

  private final StockQueryService queries;
  private final CorrectionService corrections;

  public QueriesController(StockQueryService queries, CorrectionService corrections) {
    this.queries = queries;
    this.corrections = corrections;
  }

  @Override
  public ResponseEntity<GetAvailability200Response> getAvailability(
      String mfcId, List<String> sku) {
    List<Availability> items =
        queries.availability(mfcId, sku.stream().distinct().toList()).stream()
            .map(
                v -> {
                  var a = new Availability();
                  a.setSku(v.sku());
                  a.setOnHand(v.balance().onHand());
                  a.setQuarantined(v.balance().quarantined());
                  a.setSellable(v.balance().sellable());
                  a.setReserved(v.balance().reserved());
                  a.setAvailable(v.balance().available());
                  a.setHandlingEnabled(v.handlingEnabled());
                  a.setVersion((int) v.balance().version());
                  return a;
                })
            .toList();
    var asOf = new GetAvailability200ResponseAsOf();
    asOf.setAsOf(SkuHandlingsController.time(Instant.now()));
    asOf.setFreshness(GetAvailability200ResponseAsOf.FreshnessEnum.CURRENT);
    var out = new GetAvailability200Response();
    out.setMfcId(mfcId);
    out.setAsOf(asOf);
    out.setItems(items);
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<Commit> getCommit(String commitId) {
    var v = queries.commit(commitId);
    var c = v.commit();
    var out = new Commit();
    out.setCommitId(c.commitId());
    out.setRequestId(c.requestId());
    out.setCommitType(Commit.CommitTypeEnum.fromValue(c.commitType()));
    out.setMfcId(c.mfcId());
    out.setSourceSystem(c.sourceSystem());
    out.setSourceRef(c.sourceRef());
    out.setAppliedQty(c.appliedQty());
    out.setOccurredAt(SkuHandlingsController.time(c.occurredAt()));
    out.setEntries(
        v.entries().stream()
            .map(
                e -> {
                  var d = new CommitEntriesInner();
                  d.setSku(e.sku());
                  d.setLotId(e.lotId());
                  d.setLocationCode(e.locationCode());
                  d.setQualityState(e.qualityState());
                  d.setWorkState(e.workState());
                  d.setSymbol(CommitEntriesInner.SymbolEnum.fromValue(e.symbol()));
                  d.setDelta(e.delta());
                  d.setBalanceVersion((int) e.balanceVersion());
                  return d;
                })
            .toList());
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<GetRequestResult200Response> getRequestResult(String requestId) {
    var v = queries.request(requestId);
    var out = new GetRequestResult200Response();
    out.setRequestId(v.requestId());
    out.setApplyStatus(InventoryApplyStatus.APPLIED);
    out.setCommitId(v.commit() == null ? null : v.commit().commitId());
    out.setAppliedQty(v.commit() == null ? 0 : v.commit().appliedQty());
    out.setOccurredAt(SkuHandlingsController.time(v.occurredAt()));
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<CenterStock> getCenterStock(String mfcId, String sku) {
    var v = corrections.stock(mfcId, sku);
    var out = new CenterStock();
    out.setMfcId(mfcId);
    out.setSku(sku);
    out.setLots(
        v.lots().stream()
            .map(
                b -> {
                  var l = new CenterStockLotsInner();
                  l.setLotId(b.lotId());
                  l.setLotNo(b.lotNo());
                  l.setTracking(CenterStockLotsInner.TrackingEnum.fromValue(b.tracking()));
                  l.setExpiryDate(b.expiryDate());
                  l.setManufactureDate(b.manufactureDate());
                  l.setOnHand(b.onHand());
                  l.setQuarantined(b.quarantined());
                  l.setSellable(b.sellable());
                  l.setReserved(b.reserved());
                  l.setAvailable(b.available());
                  return l;
                })
            .toList());
    out.setUnits(
        v.units().stream()
            .map(
                u -> {
                  var d = new CenterStockUnitsInner();
                  d.setLocationCode(u.key().locationCode());
                  d.setLotId(u.key().lotId());
                  d.setQualityState(QualityState.fromValue(u.key().quality().name()));
                  d.setWorkState(WorkState.fromValue(u.key().work().name()));
                  d.setQty(u.qty());
                  return d;
                })
            .toList());
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<com.pmart.mfc.inventory.api.generated.model.GetSnapshot200Response>
      getSnapshot(
          String mfcId,
          @Nullable String sku,
          @Nullable String locationCode,
          @Nullable Integer size,
          @Nullable String cursor) {
    int n = size == null ? 50 : size;
    var rows = queries.snapshotLines(mfcId, sku, locationCode, cursor, n + 1);
    boolean more = rows.size() > n;
    var page = more ? rows.subList(0, n) : rows;
    var out = new com.pmart.mfc.inventory.api.generated.model.GetSnapshot200Response();
    var asOf = new com.pmart.mfc.inventory.api.generated.model.GetAvailability200ResponseAsOf();
    asOf.setAsOf(SkuHandlingsController.time(java.time.Instant.now()));
    asOf.setFreshness(
        com.pmart.mfc.inventory.api.generated.model.GetAvailability200ResponseAsOf.FreshnessEnum
            .CURRENT);
    out.setAsOf(asOf);
    out.setItems(
        page.stream()
            .map(
                line -> {
                  var r = line.row();
                  var b = new com.pmart.mfc.inventory.api.generated.model.Balance();
                  var k = r.unit().key();
                  b.setMfcId(mfcId);
                  b.setLocationCode(k.locationCode());
                  b.setSku(k.sku());
                  b.setLotId(k.lotId());
                  b.setExpiryDate(r.expiryDate());
                  b.setQualityState(QualityState.fromValue(k.quality().name()));
                  b.setWorkState(WorkState.fromValue(k.work().name()));
                  b.setQty(r.unit().qty());
                  b.setVersion((int) r.unit().version());
                  b.setAllocatedQty(line.allocated());
                  b.setHeldQty(line.held());
                  b.setPickableQty(line.pickable());
                  return b;
                })
            .toList());
    var p = new com.pmart.mfc.inventory.api.generated.model.GetSnapshot200ResponsePage();
    p.setSize(page.size());
    if (more) {
      var k = page.get(page.size() - 1).row().unit().key();
      p.setNextCursor(
          k.sku() + "|" + k.lotId() + "|" + k.locationCode() + "|" + k.quality() + "|" + k.work());
    }
    out.setPage(p);
    return ResponseEntity.ok(out);
  }
}
