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

/** 계약 ReconciliationApi — Transit 조회(q·r·l·t). 일일 수불 대사는 아직 인터페이스 default(501). */
@RestController
public class ReconciliationController implements ReconciliationApi {

  private final InventoryQueries queries;

  public ReconciliationController(InventoryQueries queries) {
    this.queries = queries;
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
                  int r = l.normalQty() + l.quarantinedQty();
                  x.setSku(l.sku());
                  x.setLot(l.lot());
                  x.setQ(l.q());
                  x.setR(r);
                  x.setNormalQty(l.normalQty());
                  x.setQuarantinedQty(l.quarantinedQty());
                  x.setL(l.lossQty());
                  x.setT(l.q() - r - l.lossQty());
                  return x;
                })
            .toList());
    return out;
  }
}
