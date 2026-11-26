package com.pmart.cdc.inventory.api;

import com.pmart.cdc.inventory.api.generated.CommitsApi;
import com.pmart.cdc.inventory.api.generated.model.Commit;
import com.pmart.cdc.inventory.api.generated.model.CommitDeltasInner;
import com.pmart.cdc.inventory.api.generated.model.ListStockMovements200Response;
import com.pmart.cdc.inventory.api.generated.model.PostingKind;
import com.pmart.cdc.inventory.api.generated.model.StockMovement;
import com.pmart.cdc.inventory.api.generated.model.StockMovementDeltasInner;
import com.pmart.cdc.inventory.application.InventoryQueries;
import java.time.OffsetDateTime;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 CommitsApi — 원결과 조회(UNKNOWN_RESULT 해소 경로)·재고 이동 이력(0.20.0). */
@RestController
public class CommitsController implements CommitsApi {

  private final InventoryQueries queries;

  public CommitsController(InventoryQueries queries) {
    this.queries = queries;
  }

  @Override
  public ResponseEntity<Commit> getCommit(String commitId) {
    var v = queries.commit(commitId);
    var c = v.commit();
    var out = new Commit();
    out.setCommitId(c.commitId());
    out.setRequestId(c.requestId());
    out.setPostingKind(PostingKind.fromValue(c.postingKind()));
    out.setActorId(c.actorId());
    out.setStatus(Commit.StatusEnum.fromValue(c.status()));
    out.setSourceSystem(c.sourceSystem());
    out.setSourceRef(c.sourceRef());
    out.setAppliedAt(Dto.time(c.appliedAt()));
    out.setDeltas(
        v.deltas().stream()
            .map(
                d -> {
                  var x = new CommitDeltasInner();
                  x.setSku(d.sku());
                  x.setLot(d.lot());
                  x.setLocationCode(d.locationCode());
                  x.setBucket(CommitDeltasInner.BucketEnum.fromValue(d.bucket()));
                  x.setDelta(d.delta());
                  return x;
                })
            .toList());
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<ListStockMovements200Response> listStockMovements(
      String cdcId,
      @Nullable String sku,
      @Nullable String lot,
      @Nullable String locationCode,
      @Nullable PostingKind kind,
      @Nullable OffsetDateTime from,
      @Nullable OffsetDateTime to,
      Integer page,
      Integer size) {
    var p =
        queries.stockMovements(
            cdcId,
            blank(sku),
            blank(lot),
            blank(locationCode),
            kind == null ? null : kind.getValue(),
            from == null ? null : from.toInstant(),
            to == null ? null : to.toInstant(),
            page == null ? 0 : page,
            size == null ? 50 : size);
    var out = new ListStockMovements200Response();
    out.setFrom(Dto.time(p.from()));
    out.setTo(Dto.time(p.to()));
    out.setItems(
        new java.util.ArrayList<>(
            p.items().stream()
                .map(
                    v -> {
                      var c = v.commit();
                      var m = new StockMovement();
                      m.setCommitId(c.commitId());
                      m.setPostingKind(PostingKind.fromValue(c.postingKind()));
                      m.setAppliedAt(Dto.time(c.appliedAt()));
                      m.setRequestId(c.requestId());
                      m.setSourceSystem(c.sourceSystem());
                      m.setSourceRef(c.sourceRef());
                      m.setActorId(c.actorId());
                      m.setDeltas(
                          new java.util.ArrayList<>(
                              v.deltas().stream()
                                  .map(
                                      d -> {
                                        var x = new StockMovementDeltasInner();
                                        x.setSku(d.sku());
                                        x.setLot(d.lot());
                                        x.setLocationCode(d.locationCode());
                                        x.setBucket(
                                            StockMovementDeltasInner.BucketEnum.fromValue(
                                                d.bucket()));
                                        x.setDelta(d.delta());
                                        return x;
                                      })
                                  .toList()));
                      return m;
                    })
                .toList()));
    out.setPage(p.page());
    out.setSize(p.size());
    out.setHasNext(p.hasNext());
    return ResponseEntity.ok(out);
  }

  private static @Nullable String blank(@Nullable String v) {
    return v == null || v.isBlank() ? null : v.strip();
  }
}
