package com.pmart.review.api;

import com.pmart.review.api.generated.ReviewReportsApi;
import com.pmart.review.api.generated.model.ListReviewReports200Response;
import com.pmart.review.api.generated.model.ReportReason;
import com.pmart.review.api.generated.model.ReportReview201Response;
import com.pmart.review.api.generated.model.ResolveReviewReportsRequest;
import com.pmart.review.api.generated.model.ReviewReportDetail;
import com.pmart.review.api.generated.model.ReviewReportDetailPhotosInner;
import com.pmart.review.api.generated.model.ReviewReportGroup;
import com.pmart.review.api.generated.model.ReviewReportItem;
import com.pmart.review.application.ReviewReports;
import com.pmart.review.domain.ReviewRules;
import com.pmart.review.infrastructure.ReviewReportStore;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 ReviewReportsApi — 운영자 리뷰 신고 확인(0.7.0 BR-90, CS_AGENT·OPERATOR_ADMIN). */
@RestController
public class ReviewReportsController implements ReviewReportsApi {

  private final ReviewReports reports;

  public ReviewReportsController(ReviewReports reports) {
    this.reports = reports;
  }

  @Override
  public ResponseEntity<ListReviewReports200Response> listReviewReports(
      String state, Integer size, @Nullable String cursor) {
    var p = reports.groups(state == null ? "OPEN" : state, size == null ? 20 : size, cursor);
    return ResponseEntity.ok(
        new ListReviewReports200Response()
            .items(p.items().stream().map(ReviewReportsController::group).toList())
            .nextCursor(p.nextCursor()));
  }

  @Override
  public ResponseEntity<ReviewReportDetail> getReviewReportDetail(String reviewId) {
    ReviewReports.Detail d = reports.detail(reviewId);
    var r = d.review();
    return ResponseEntity.ok(
        new ReviewReportDetail()
            .reviewId(r.reviewId())
            .productId(r.productId())
            .sku(r.sku())
            .rating(r.rating())
            .text(r.body())
            .authorMasked(r.authorName())
            .reviewCreatedAt(time(r.createdAt()))
            .reviewUpdatedAt(r.updatedAt() == null ? null : time(r.updatedAt()))
            .reviewHidden(ReviewRules.HIDDEN.equals(r.state()))
            .reviewDeleted(ReviewRules.DELETED.equals(r.state()))
            .photos(
                d.photos().stream()
                    .map(
                        p ->
                            new ReviewReportDetailPhotosInner()
                                .photoId(p.photoId())
                                .url(ReviewViews.photoUrl(p.photoId())))
                    .toList())
            .openReportCount(d.openCount())
            .reports(d.items().stream().map(ReviewReportsController::item).toList()));
  }

  private static ReviewReportItem item(ReviewReportStore.Item i) {
    return new ReviewReportItem()
        .reportId(i.reportId())
        .reason(ReportReason.fromValue(i.reason()))
        .detail(i.detail())
        .reportedAt(time(i.reportedAt()))
        .state(ReviewReportItem.StateEnum.fromValue(i.state()))
        .resolution(
            i.resolution() == null
                ? null
                : ReviewReportItem.ResolutionEnum.fromValue(i.resolution()))
        .resolvedAt(i.resolvedAt() == null ? null : time(i.resolvedAt()))
        .resolvedBy(i.resolvedBy())
        .resolutionNote(i.resolutionNote());
  }

  @Override
  public ResponseEntity<ReportReview201Response> resolveReviewReports(
      String reviewId,
      String idempotencyKey,
      String xSourceSystem,
      ResolveReviewReportsRequest body) {
    String id =
        reports.resolve(
            reviewId, body.getAction().getValue(), body.getNote(), idempotencyKey, xSourceSystem);
    return ResponseEntity.ok(
        new ReportReview201Response()
            .status(ReportReview201Response.StatusEnum.APPLIED)
            .requestId(idempotencyKey)
            .resultId(id));
  }

  private static ReviewReportGroup group(ReviewReportStore.Group g) {
    return new ReviewReportGroup()
        .reviewId(g.reviewId())
        .productId(g.productId())
        .sku(g.sku())
        .rating(g.rating())
        .text(g.body())
        .authorMasked(g.authorName())
        .reviewCreatedAt(time(g.reviewCreatedAt()))
        .reviewHidden(ReviewRules.HIDDEN.equals(g.reviewState()))
        .reviewDeleted(ReviewRules.DELETED.equals(g.reviewState()))
        .state(
            g.resolution() == null
                ? ReviewReportGroup.StateEnum.OPEN
                : ReviewReportGroup.StateEnum.RESOLVED)
        .reportCount(g.count())
        .reasonCounts(g.reasonCounts())
        .otherDetails(g.otherDetails())
        .firstReportedAt(time(g.firstReportedAt()))
        .lastReportedAt(time(g.lastReportedAt()))
        .resolution(
            g.resolution() == null
                ? null
                : ReviewReportGroup.ResolutionEnum.fromValue(g.resolution()))
        .resolvedAt(g.resolvedAt() == null ? null : time(g.resolvedAt()))
        .resolvedBy(g.resolvedBy());
  }

  private static OffsetDateTime time(Instant i) {
    return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
  }
}
