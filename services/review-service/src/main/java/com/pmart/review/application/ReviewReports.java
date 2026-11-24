package com.pmart.review.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.review.domain.ReviewRules;
import com.pmart.review.infrastructure.ReviewReportStore;
import com.pmart.review.infrastructure.ReviewStore;
import com.pmart.review.infrastructure.ReviewStore.ReviewRow;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 리뷰 신고(계약 0.7.0, 2026-11-23 고객앱 BR-90 — 사용자 결정: 회원만·한 리뷰에 한 번·신고만으로 숨기지 않음·자기 리뷰 불가·결과 안 알림, 처리
 * CS_AGENT·OPERATOR_ADMIN, 숨기면 별점·리뷰 수에서 뺌). 숨김 = 리뷰 state HIDDEN + {@code ReviewDeleted}(reason
 * OPERATOR_HIDDEN) — 평점 사본(product-content·storefront)이 지울 때와 같이 뺀다. 신고 글(detail)은 로그에 남기지 않는다.
 */
@Service
public class ReviewReports {

  private static final Logger log = LoggerFactory.getLogger(ReviewReports.class);
  private static final String AGGREGATE = "Review";

  public record Report(String reportId) {}

  public record GroupPage(List<ReviewReportStore.Group> items, @Nullable String nextCursor) {}

  /** 0.8.0 운영자 상세 — 리뷰·붙은 사진·신고 한 건 한 건(신고자 없음). */
  public record Detail(
      ReviewRow review, List<ReviewStore.PhotoRow> photos, List<ReviewReportStore.Item> items) {

    public int openCount() {
      return (int) items.stream().filter(i -> "OPEN".equals(i.state())).count();
    }
  }

  private final ReviewStore reviews;
  private final ReviewReportStore reports;
  private final RequestIdempotency idempotency;
  private final EventPublisher events;
  private final Clock clock;

  public ReviewReports(
      ReviewStore reviews,
      ReviewReportStore reports,
      RequestIdempotency idempotency,
      EventPublisher events,
      Clock clock) {
    this.reviews = reviews;
    this.reports = reports;
    this.idempotency = idempotency;
    this.events = events;
    this.clock = clock;
  }

  /** 고객 신고 — 바로 기록(APPLIED). */
  public Report report(
      String reviewId,
      String reason,
      @Nullable String detail,
      String requestId,
      String sourceSystem) {
    PmartPrincipal c = customer();
    String text = detail == null || detail.isBlank() ? null : detail.strip();
    if ("OTHER".equals(reason) && text == null) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "기타 사유는 내용을 적어 주세요.");
    }
    String saved = "OTHER".equals(reason) ? text : null;
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("reviewId", reviewId);
    body.put("reason", reason);
    body.put("detail", saved);
    return idempotency
        .execute(
            new RequestKey(sourceSystem, requestId, "reportReview:" + reviewId, c.subject()),
            body,
            Report.class,
            () -> {
              ReviewRow r =
                  reviews
                      .lockReview(reviewId)
                      .filter(x -> ReviewRules.PUBLISHED.equals(x.state()))
                      .orElseThrow(() -> PmartException.notFound("REVIEW_NOT_FOUND", "리뷰가 없습니다."));
              if (r.accountId().equals(c.subject())) {
                throw new PmartException(
                    "REPORT_OWN_REVIEW",
                    ErrorCode.DOMAIN_REJECTED.status(),
                    "내가 쓴 리뷰는 신고할 수 없습니다.",
                    false,
                    List.of());
              }
              if (reports.exists(reviewId, c.subject())) {
                throw new PmartException(
                    "ALREADY_REPORTED", HttpStatus.CONFLICT, "이미 신고한 리뷰입니다.", false, List.of());
              }
              String id = "RPT-" + UUID.randomUUID();
              reports.insert(id, reviewId, c.subject(), reason, saved, now());
              log.info("리뷰 {} 신고 {} ({})", reviewId, id, reason);
              return new Report(id);
            })
        .value();
  }

  /** 운영자 신고 묶음 목록(CS_AGENT·OPERATOR_ADMIN). */
  public GroupPage groups(String state, int size, @Nullable String cursor) {
    staff();
    int offset = offset(cursor);
    List<ReviewReportStore.Group> rows = reports.groups(state, offset, size + 1);
    boolean more = rows.size() > size;
    return new GroupPage(
        List.copyOf(rows.subList(0, Math.min(size, rows.size()))),
        more ? String.valueOf(offset + size) : null);
  }

  /** 운영자 상세(0.8.0) — 신고가 없는 리뷰·없는 리뷰는 404 REVIEW_NOT_FOUND. */
  public Detail detail(String reviewId) {
    staff();
    ReviewRow r =
        reviews
            .review(reviewId)
            .orElseThrow(() -> PmartException.notFound("REVIEW_NOT_FOUND", "리뷰가 없습니다."));
    List<ReviewReportStore.Item> items = reports.items(reviewId);
    if (items.isEmpty()) {
      throw PmartException.notFound("REVIEW_NOT_FOUND", "신고된 리뷰가 아닙니다.");
    }
    return new Detail(r, reviews.photosOf(reviewId), items);
  }

  /** 상담원·운영 관리자인지(0.8.0 숨긴 리뷰 사진 보기). */
  public static boolean isStaff() {
    return PmartPrincipal.current()
        .map(p -> p.hasRole("CS_AGENT") || p.hasRole("OPERATOR_ADMIN"))
        .orElse(false);
  }

  /** 운영자 처리 — HIDE 면 숨김 + ReviewDeleted, KEEP 은 신고만 닫음. */
  public String resolve(
      String reviewId,
      String action,
      @Nullable String note,
      String requestId,
      String sourceSystem) {
    PmartPrincipal s = staff();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("reviewId", reviewId);
    body.put("action", action);
    body.put("note", note);
    return idempotency
        .execute(
            new RequestKey(
                sourceSystem, requestId, "resolveReviewReports:" + reviewId, s.subject()),
            body,
            String.class,
            () -> {
              ReviewRow r =
                  reviews
                      .lockReview(reviewId)
                      .orElseThrow(() -> PmartException.notFound("REVIEW_NOT_FOUND", "리뷰가 없습니다."));
              if (reports.openCount(reviewId) == 0) {
                throw new PmartException(
                    "NO_OPEN_REPORTS", HttpStatus.CONFLICT, "처리할 신고가 없습니다.", false, List.of());
              }
              Instant now = now();
              if ("HIDE".equals(action) && ReviewRules.PUBLISHED.equals(r.state())) {
                int version = r.version() + 1;
                reviews.markHidden(reviewId, version, now);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("reviewId", r.reviewId());
                m.put("orderLineId", r.orderLineId());
                m.put("productId", r.productId());
                m.put("rating", r.rating());
                m.put("deletedAt", now.toString());
                m.put("reason", "OPERATOR_HIDDEN");
                events.publish(
                    "ReviewDeleted",
                    1,
                    AGGREGATE,
                    r.reviewId(),
                    version,
                    new EventEnvelope.Actor(EventEnvelope.ActorType.OPERATOR, s.subject()),
                    m);
              }
              int closed = reports.resolve(reviewId, action, s.subject(), note, now);
              log.info("리뷰 {} 신고 {}건 처리 {}", reviewId, closed, action);
              return reviewId;
            })
        .value();
  }

  private static PmartPrincipal customer() {
    return PmartPrincipal.current()
        .filter(p -> p.type() == PmartPrincipal.PrincipalType.CUSTOMER)
        .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "회원만 신고할 수 있습니다."));
  }

  private static PmartPrincipal staff() {
    PmartPrincipal p = PmartPrincipal.current().orElse(null);
    if (p == null || !(p.hasRole("CS_AGENT") || p.hasRole("OPERATOR_ADMIN"))) {
      throw new PmartException(ErrorCode.FORBIDDEN, "상담원·운영 관리자만 처리할 수 있습니다.");
    }
    return p;
  }

  private static int offset(@Nullable String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return 0;
    }
    try {
      return Math.max(0, Integer.parseInt(cursor));
    } catch (NumberFormatException e) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "잘못된 커서입니다.");
    }
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }
}
