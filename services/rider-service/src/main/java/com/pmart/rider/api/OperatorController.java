package com.pmart.rider.api;

import com.pmart.rider.api.generated.OperatorApi;
import com.pmart.rider.api.generated.model.AccountRestriction;
import com.pmart.rider.api.generated.model.ListRiderRestrictionReviews200Response;
import com.pmart.rider.api.generated.model.ListRiderRestrictions200Response;
import com.pmart.rider.api.generated.model.RestrictionReview;
import com.pmart.rider.api.generated.model.RestrictionReviewComplaintsInner;
import com.pmart.rider.api.generated.model.RestrictionReviewDecision;
import com.pmart.rider.api.generated.model.RiderRestrictionChange;
import com.pmart.rider.api.generated.model.RiderRestrictionRecord;
import com.pmart.rider.application.ComplaintService;
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
  private final ComplaintService complaints;
  private final ObjectMapper json;

  public OperatorController(RiderService riders, ComplaintService complaints, ObjectMapper json) {
    this.riders = riders;
    this.complaints = complaints;
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

  @Override
  public ResponseEntity<ListRiderRestrictionReviews200Response> listRiderRestrictionReviews(
      String state, @Nullable String riderId) {
    return ResponseEntity.ok(
        new ListRiderRestrictionReviews200Response()
            .items(
                complaints.reviews(state, riderId).stream()
                    .map(OperatorController::review)
                    .toList()));
  }

  @Override
  public ResponseEntity<RestrictionReview> decideRiderRestrictionReview(
      String reviewId,
      String idempotencyKey,
      String xSourceSystem,
      RestrictionReviewDecision body) {
    return ResponseEntity.ok(
        review(
            complaints.decide(
                reviewId,
                idempotencyKey,
                xSourceSystem,
                body.getDecision().getValue(),
                body.getNote())));
  }

  private static RestrictionReview review(ComplaintService.Review v) {
    var r = v.review();
    return new RestrictionReview()
        .reviewId(r.reviewId())
        .riderId(r.riderId())
        .complaintType(RestrictionReview.ComplaintTypeEnum.fromValue(r.complaintType()))
        .state(RestrictionReview.StateEnum.fromValue(r.state()))
        .complaints(
            v.complaints().stream()
                .map(
                    c ->
                        new RestrictionReviewComplaintsInner()
                            .complaintId(c.complaintId())
                            .deliveryId(c.deliveryId())
                            .recordedAt(RiderViews.time(c.recordedAt())))
                .toList())
        .openedAt(RiderViews.time(r.openedAt()))
        .decidedBy(r.decidedBy())
        .decidedAt(RiderViews.time(r.decidedAt()))
        .note(r.decisionNote());
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
