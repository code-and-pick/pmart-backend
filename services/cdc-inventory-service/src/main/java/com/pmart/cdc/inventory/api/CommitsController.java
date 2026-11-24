package com.pmart.cdc.inventory.api;

import com.pmart.cdc.inventory.api.generated.CommitsApi;
import com.pmart.cdc.inventory.api.generated.model.Commit;
import com.pmart.cdc.inventory.api.generated.model.CommitDeltasInner;
import com.pmart.cdc.inventory.application.InventoryQueries;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 CommitsApi — 원결과 조회(UNKNOWN_RESULT 해소 경로). */
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
    out.setPostingKind(Commit.PostingKindEnum.fromValue(c.postingKind()));
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
}
