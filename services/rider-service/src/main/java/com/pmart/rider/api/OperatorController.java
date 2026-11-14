package com.pmart.rider.api;

import com.pmart.rider.api.generated.OperatorApi;
import com.pmart.rider.api.generated.model.AccountRestriction;
import com.pmart.rider.api.generated.model.ListRiderRestrictions200Response;
import com.pmart.rider.api.generated.model.RiderRestrictionChange;
import com.pmart.rider.api.generated.model.RiderRestrictionRecord;
import com.pmart.rider.application.RiderService;
import com.pmart.rider.infrastructure.RiderStore;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** 계약 OperatorApi — 계정 제한 걸기·풀기·이력(RS-1, 2026-11-14). 권한 판정은 RiderService. */
@RestController
public class OperatorController implements OperatorApi {

  private final RiderService riders;
  private final ObjectMapper json;

  public OperatorController(RiderService riders, ObjectMapper json) {
    this.riders = riders;
    this.json = json;
  }

  @Override
  public ResponseEntity<RiderRestrictionRecord> changeRiderRestriction(
      String riderId,
      String idempotencyKey,
      String xSourceSystem,
      RiderRestrictionChange body,
      @Nullable Integer xExpectedVersion) {
    RiderStore.RestrictionRow row =
        riders.changeRestriction(
            riderId,
            idempotencyKey,
            xSourceSystem,
            xExpectedVersion,
            new RiderService.RestrictionChange(
                body.getRestriction().getValue(),
                body.getReason(),
                body.getUntil() == null ? null : body.getUntil().toInstant(),
                body.getEvidenceRefs() == null ? List.of() : List.copyOf(body.getEvidenceRefs()),
                body.getApprovalRef()));
    return ResponseEntity.ok(record(row));
  }

  @Override
  public ResponseEntity<ListRiderRestrictions200Response> listRiderRestrictions(String riderId) {
    RiderService.Restrictions r = riders.restrictions(riderId);
    return ResponseEntity.ok(
        new ListRiderRestrictions200Response()
            .current(AccountRestriction.fromValue(r.current().name()))
            .items(r.items().stream().map(this::record).toList()));
  }

  private RiderRestrictionRecord record(RiderStore.RestrictionRow r) {
    return new RiderRestrictionRecord()
        .riderId(r.riderId())
        .restriction(AccountRestriction.fromValue(r.restriction()))
        .previous(AccountRestriction.fromValue(r.previous()))
        .reason(r.reason())
        .until(RiderViews.time(r.until()))
        .evidenceRefs(json.readValue(r.evidenceRefsJson(), new TypeReference<List<String>>() {}))
        .approvalRef(r.approvalRef())
        .changedBy(r.changedBy())
        .changedAt(RiderViews.time(r.changedAt()))
        .version(r.riderVersion());
  }
}
