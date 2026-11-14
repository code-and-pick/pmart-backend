package com.pmart.rider.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.rider.domain.ComplaintReviewRules;
import com.pmart.rider.domain.ComplaintReviewRules.Complaint;
import com.pmart.rider.domain.Rider;
import com.pmart.rider.infrastructure.ComplaintStore;
import com.pmart.rider.infrastructure.ComplaintStore.ComplaintRow;
import com.pmart.rider.infrastructure.ComplaintStore.ReviewRow;
import com.pmart.rider.infrastructure.RiderStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * 컴플레인 누적 → 제한 검토 대상(RS-3, 사용자 2026-11-14). case {@code RiderComplaintRecorded}(platform.case.v1)를
 * 받아 종류마다 7일 안 서로 다른 배송 3건 이상이면 검토 대상을 연다({@link ComplaintReviewRules}). <b>자동 제재는 없다</b> — 운영자가 확인
 * 뒤 RS-1 API 로 제한하고, 이 검토는 RESTRICTED·DISMISSED 로 닫는다. 열린 검토가 있는 동안 온 같은 종류 컴플레인은 거기에 묶는다.
 */
@Service
public class ComplaintService {

  public static final Set<String> TYPES = Set.of("RiderComplaintRecorded");
  private static final Set<String> COMPLAINT_TYPES = Set.of("REPORT", "MISDELIVERY", "DAMAGE");

  /** 검토 대상 + 묶인 컴플레인. */
  public record Review(ReviewRow review, List<ComplaintRow> complaints) {}

  private static final Logger log = LoggerFactory.getLogger(ComplaintService.class);

  private final ComplaintStore store;
  private final RiderStore riders;
  private final RequestIdempotency idempotency;
  private final Clock clock;
  private final Duration window;
  private final int threshold;

  public ComplaintService(
      ComplaintStore store,
      RiderStore riders,
      RequestIdempotency idempotency,
      Clock clock,
      // 사용자 2026-11-14 "비슷한 종류의 컴플레인이 일주일 이내에 3건 이상"
      @Value("${pmart.rider.complaint-review.window:P7D}") Duration window,
      @Value("${pmart.rider.complaint-review.threshold:3}") int threshold) {
    this.store = store;
    this.riders = riders;
    this.idempotency = idempotency;
    this.clock = clock;
    this.window = window;
    this.threshold = threshold;
  }

  /** inbox 트랜잭션 안. 같은 complaintId 는 한 번만 센다. */
  public void on(EventEnvelope e) {
    JsonNode p = e.payload();
    String complaintId = p.path("complaintId").asString("");
    String riderId = p.path("riderId").asString("");
    String deliveryId = p.path("deliveryId").asString("");
    String type = p.path("complaintType").asString("");
    if (complaintId.isBlank()
        || riderId.isBlank()
        || deliveryId.isBlank()
        || !COMPLAINT_TYPES.contains(type)) {
      log.warn("컴플레인 사건 {} 값이 모자라 세지 않음(종류 {})", e.eventId(), type);
      return;
    }
    Instant at = Instant.parse(p.path("recordedAt").asString());
    Instant now = clock.instant();
    store.lockRiderComplaints(riderId);
    if (!store.insertComplaint(
        complaintId, riderId, deliveryId, type, p.path("caseId").asString(null), at, now)) {
      return;
    }
    var open = store.openReview(riderId, type);
    if (open.isPresent()) {
      store.attach(List.of(complaintId), open.get().reviewId());
      log.info("라이더 {} {} 컴플레인 — 열린 검토 {} 에 더함", riderId, type, open.get().reviewId());
      return;
    }
    List<Complaint> hit =
        ComplaintReviewRules.qualifying(
            store.unreviewed(riderId, type, at.minus(window), at.plus(window)),
            new Complaint(complaintId, deliveryId, at),
            window,
            threshold);
    if (hit.isEmpty()) {
      return;
    }
    String reviewId = "RRV-" + UUID.randomUUID();
    store.insertReview(reviewId, riderId, type, now);
    store.attach(hit.stream().map(Complaint::complaintId).toList(), reviewId);
    log.info("라이더 {} 제한 검토 대상 {} — {} {}건", riderId, reviewId, type, hit.size());
  }

  public List<Review> reviews(@Nullable String state, @Nullable String riderId) {
    RiderService.requireRestrictionAdmin();
    return store.reviews(state, riderId, 200).stream()
        .map(r -> new Review(r, store.complaintsOf(r.reviewId())))
        .toList();
  }

  /**
   * 검토 닫기 — RESTRICTED(먼저 RS-1 로 제한을 걸었어야 한다) 또는 DISMISSED. 닫힌 검토에 묶인 컴플레인은 다시 세지 않는다(새로 3건 쌓여야 다시
   * 대상).
   */
  @Transactional
  public Review decide(
      String reviewId,
      String requestId,
      String sourceSystem,
      String decision,
      @Nullable String note) {
    PmartPrincipal p = RiderService.requireRestrictionAdmin();
    if (!Set.of("RESTRICTED", "DISMISSED").contains(decision)) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "결정은 RESTRICTED 또는 DISMISSED 입니다.");
    }
    idempotency.execute(
        new RequestKey(sourceSystem, requestId, "decideRiderRestrictionReview", reviewId),
        Map.of("decision", decision, "note", String.valueOf(note)),
        String.class,
        () -> {
          ReviewRow r =
              store
                  .lockReview(reviewId)
                  .orElseThrow(
                      () -> PmartException.notFound("REVIEW_NOT_FOUND", "제한 검토 대상이 없습니다."));
          if (!"OPEN".equals(r.state())) {
            throw PmartException.invalidState("이미 처리된 검토입니다(" + r.state() + ").");
          }
          Instant now = clock.instant();
          if ("RESTRICTED".equals(decision)) {
            Rider.Restriction current =
                riders
                    .find(r.riderId())
                    .map(x -> x.restrictionAt(now))
                    .orElse(Rider.Restriction.NORMAL);
            if (current == Rider.Restriction.NORMAL) {
              throw PmartException.invalidState(
                  "라이더 계정이 제한되어 있지 않습니다 — 먼저 계정 제한(POST /riders/{riderId}/restrictions)을 거세요.");
            }
          }
          store.decide(reviewId, decision, p.subject(), note, now);
          return reviewId;
        });
    ReviewRow r = store.lockReview(reviewId).orElseThrow();
    return new Review(r, store.complaintsOf(reviewId));
  }
}
