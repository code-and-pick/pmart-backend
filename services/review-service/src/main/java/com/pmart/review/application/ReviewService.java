package com.pmart.review.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.review.domain.ReviewRules;
import com.pmart.review.infrastructure.JpegResizer;
import com.pmart.review.infrastructure.ReviewStore;
import com.pmart.review.infrastructure.ReviewStore.EligibilityCounts;
import com.pmart.review.infrastructure.ReviewStore.EligibilityRow;
import com.pmart.review.infrastructure.ReviewStore.EligibilityView;
import com.pmart.review.infrastructure.ReviewStore.PhotoRow;
import com.pmart.review.infrastructure.ReviewStore.ReviewRow;
import com.pmart.review.infrastructure.Upstreams;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 리뷰(2026-11-16, 계약 0.2.0 — ADR-0063). 규칙은 {@link ReviewRules}.
 *
 * <ul>
 *   <li>자격: {@code DeliveryCompleted}(delivery.delivery.v1)의 품목마다. 계정·SKU·상품·상품명은 order 내부 조회 사본.
 *   <li>작성·수정·삭제는 본인(고객 토큰)만. 발행 {@code ReviewPublished}(작성·수정 — 별점이 바뀔 수 있다)·{@code ReviewDeleted}
 *       (commerce.review.v1) — product-content 평점 집계가 받는다.
 *   <li>리뷰 내용·별명은 로그에 남기지 않는다.
 * </ul>
 */
@Service
public class ReviewService {

  private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

  private final com.pmart.review.infrastructure.ReviewReportStore reportStore;
  private static final String AGGREGATE = "Review";
  private static final String ANONYMOUS_AUTHOR = "PMart 고객";

  /** 리뷰 한 건과 사진·계약 상태. deliveredAt 은 내부 조회에만. */
  /** helpedByMe = 회원 토큰으로 볼 때 내가 도움돼요를 눌렀는지(0.6.0 BR-76), 비회원·모름 null. */
  public record ReviewView(
      ReviewRow row,
      List<PhotoRow> photos,
      String state,
      @Nullable Instant deliveredAt,
      @Nullable Boolean helpedByMe,
      @Nullable Boolean reportedByMe,
      @Nullable Boolean mine) {

    public ReviewView(
        ReviewRow row, List<PhotoRow> photos, String state, @Nullable Instant deliveredAt) {
      this(row, photos, state, deliveredAt, null, null, null);
    }

    public ReviewView(
        ReviewRow row,
        List<PhotoRow> photos,
        String state,
        @Nullable Instant deliveredAt,
        @Nullable Boolean helpedByMe) {
      this(row, photos, state, deliveredAt, helpedByMe, null, null);
    }
  }

  /** 도움돼요 결과(바뀐 수·눌림). */
  public record Helpful(String reviewId, int helpfulCount, boolean helpedByMe) {}

  /** 자격 한 쪽과 개수(0.5.0 BR-67 — total·writable·written). */
  /**
   * @param orderWritable 쪽에 나온 주문마다 지금 쓸 수 있는 품목 수(0.6.1 BR-86 — state·쪽과 상관없이 주문 기준)
   */
  public record EligibilityPage(
      List<EligibilityItem> items,
      @Nullable String nextCursor,
      EligibilityCounts counts,
      java.util.Map<String, Integer> orderWritable) {}

  /** 자격 한 줄. display 는 리뷰관리 표시 값(0.5.0 BR-67 — 주문 시각·센터 이름·지금 상품 사진·옵션 라벨, 모르면 null). */
  public record EligibilityItem(
      EligibilityRow row,
      String state,
      @Nullable String reviewId,
      EligibilityDisplay.Display display) {}

  public record ReviewPage(
      List<ReviewView> items, @Nullable String nextCursor, @Nullable Double average, int count) {}

  /** 사진 본문 — 원본이거나 축소본(JPEG). */
  public record Photo(
      PhotoRow row, InputStream body, String contentType, long length, String etag) {}

  public record CreateCommand(
      String orderLineId, int rating, @Nullable String text, List<String> photoIds) {}

  public record UpdateCommand(
      @Nullable Integer rating, @Nullable String text, @Nullable List<String> photoIds) {}

  private final ReviewStore store;
  private final Upstreams upstreams;
  private final ReviewPhotoStorage photos;
  private final EventPublisher events;
  private final RequestIdempotency idempotency;
  private final Clock clock;

  /** 사진 축소본(2026-11-20, 계약 0.4.0). */
  private final ReviewPhotoVariants variants;

  private final ReviewPhotoPreparer preparer;

  /** 리뷰관리 표시 값(2026-11-20 BR-67). */
  private final EligibilityDisplay display;

  public ReviewService(
      ReviewStore store,
      Upstreams upstreams,
      ReviewPhotoStorage photos,
      EventPublisher events,
      RequestIdempotency idempotency,
      Clock clock,
      ReviewPhotoVariants variants,
      ReviewPhotoPreparer preparer,
      EligibilityDisplay display,
      com.pmart.review.infrastructure.ReviewReportStore reportStore) {
    this.reportStore = reportStore;
    this.display = display;
    this.variants = variants;
    this.preparer = preparer;
    this.store = store;
    this.upstreams = upstreams;
    this.photos = photos;
    this.events = events;
    this.idempotency = idempotency;
    this.clock = clock;
  }

  // ---------------------------------------------------------------- 자격(inbox 트랜잭션 안)

  /** 배송 완료 → 품목마다 자격. 주문을 못 찾으면(시험 배송 등) 건너뛴다. order 장애는 예외 → 재시도·DLT. */
  public void onDeliveryCompleted(EventEnvelope e) {
    JsonNode p = e.payload();
    String orderId = text(p, "orderId");
    String deliveredAt = text(p, "deliveredAt");
    if (orderId == null || deliveredAt == null) {
      throw new IllegalArgumentException("orderId·deliveredAt 없음"); // 계약 위반 → DLT
    }
    Upstreams.Order order = upstreams.order(orderId);
    if (order == null) {
      log.warn("배송 완료 {} — 주문 {} 이 order 에 없어 리뷰 자격을 만들지 않음", text(p, "deliveryId"), orderId);
      return;
    }
    Instant at = Instant.parse(deliveredAt);
    Instant now = now();
    int n = 0;
    for (JsonNode l : p.path("lines")) {
      String lineId = text(l, "orderLineId");
      int qty = l.path("qty").asInt(0);
      if (lineId == null || qty <= 0) {
        continue;
      }
      Upstreams.OrderLine ol = order.line(lineId);
      if (ol == null) {
        log.warn("배송 완료 품목 {} 이 주문 {} 에 없음 — 건너뜀", lineId, orderId);
        continue;
      }
      store.upsertEligibility(
          new EligibilityRow(
              lineId,
              orderId,
              order.accountId(),
              ol.sku(),
              ol.productId(),
              ol.productName(),
              qty,
              at,
              text(p, "deliveryId"),
              order.confirmedAt(),
              order.mfcId()),
          now);
      n++;
    }
    log.info("배송 완료 {} → 리뷰 자격 {}건", text(p, "deliveryId"), n);
  }

  public EligibilityPage eligibilities(
      @Nullable String orderId, @Nullable String state, @Nullable String cursor, int size) {
    PmartPrincipal c = customer();
    int offset = offset(cursor);
    Instant now = now();
    List<EligibilityView> rows =
        store.eligibilities(c.subject(), blankToNull(orderId), state, now, offset, size + 1);
    boolean more = rows.size() > size;
    List<EligibilityView> page = rows.subList(0, Math.min(size, rows.size()));
    List<EligibilityDisplay.Display> shown =
        display.of(page.stream().map(EligibilityView::e).toList());
    List<EligibilityItem> items = new ArrayList<>();
    for (int i = 0; i < page.size(); i++) {
      EligibilityView v = page.get(i);
      items.add(
          new EligibilityItem(
              v.e(),
              ReviewRules.eligibilityState(
                  v.reviewState(), v.reviewCreatedAt(), v.e().deliveredAt(), now),
              v.reviewId(),
              shown.get(i)));
    }
    EligibilityCounts counts =
        store.eligibilityCounts(c.subject(), blankToNull(orderId), state, now);
    java.util.Map<String, Integer> orderWritable =
        store.writableByOrder(
            c.subject(), page.stream().map(v -> v.e().orderId()).distinct().toList(), now);
    return new EligibilityPage(
        items, more ? String.valueOf(offset + size) : null, counts, orderWritable);
  }

  // ---------------------------------------------------------------- 작성·수정·삭제

  public ReviewView create(String requestId, String sourceSystem, CreateCommand cmd) {
    PmartPrincipal c = customer();
    String text = normalize(cmd.text());
    List<String> photoIds = List.copyOf(cmd.photoIds());
    ReviewRules.inputProblem(cmd.rating(), true, text, photoIds)
        .ifPresent(
            m -> {
              throw new PmartException(ErrorCode.VALIDATION_FAILED, m);
            });
    String lineId = cmd.orderLineId().strip();
    EligibilityRow e =
        store
            .eligibility(lineId)
            .filter(x -> x.accountId().equals(c.subject()) && x.productId() != null)
            .orElseThrow(() -> rejected("NOT_ELIGIBLE", "리뷰를 쓸 수 있는 배송 완료 품목이 아닙니다."));
    // 반품 확인(트랜잭션 밖 — order 장애면 503, 지어내지 않음). 받은 수량 전부 반품이면 새로 못 쓴다(사용자 결정).
    Upstreams.Order order = upstreams.order(e.orderId());
    Upstreams.OrderLine ol = order == null ? null : order.line(lineId);
    if (ol != null && ReviewRules.fullyReturned(e.deliveredQty(), ol.returnedQty())) {
      throw rejected("NOT_ELIGIBLE", "받은 수량이 모두 반품된 품목은 리뷰를 쓸 수 없습니다.");
    }
    Upstreams.Author who = upstreams.author(c.subject());
    if (who != null && who.restrictedUntil() != null) { // 2026-11-23 CU-7 이용제한·정지 고객은 리뷰를 새로 못 쓴다
      throw new PmartException(
          "ACCOUNT_RESTRICTED",
          org.springframework.http.HttpStatus.FORBIDDEN,
          "운영 정책 위반으로 이용이 제한되어 리뷰를 쓸 수 없어요.",
          false,
          java.util.List.of(new PmartException.Detail("until", who.restrictedUntil())));
    }
    String author =
        Objects.requireNonNullElse(who == null ? null : who.displayName(), ANONYMOUS_AUTHOR);
    Map<String, Object> req = new LinkedHashMap<>();
    req.put("orderLineId", lineId);
    req.put("rating", cmd.rating());
    req.put("text", text);
    req.put("photoIds", photoIds);
    String reviewId =
        idempotency
            .execute(
                new RequestKey(sourceSystem, requestId, "createReview", c.subject()),
                req,
                String.class,
                () -> {
                  Instant now = now();
                  EligibilityRow locked =
                      store.lockEligibility(lineId).orElseThrow(); // 위에서 있음을 확인했다
                  var existing = store.reviewOfLine(lineId);
                  if (existing.isPresent()) {
                    throw ReviewRules.DELETED.equals(existing.get().state())
                        ? rejected("REWRITE_AFTER_DELETE_FORBIDDEN", "삭제한 리뷰는 다시 쓸 수 없습니다.")
                        : rejected("ALREADY_REVIEWED", "이미 리뷰를 쓴 품목입니다.");
                  }
                  if (!ReviewRules.withinWriteWindow(locked.deliveredAt(), now)) {
                    throw rejected("ELIGIBILITY_EXPIRED", "리뷰 작성 기간(배송 완료 후 14일)이 지났습니다.");
                  }
                  String id = "RV-" + UUID.randomUUID();
                  ReviewRow r =
                      new ReviewRow(
                          id,
                          lineId,
                          locked.orderId(),
                          c.subject(),
                          Objects.requireNonNull(locked.productId()),
                          locked.sku(),
                          author,
                          cmd.rating(),
                          text,
                          ReviewRules.PUBLISHED,
                          1,
                          now,
                          null,
                          null);
                  store.insertReview(r);
                  attach(id, c.subject(), photoIds);
                  publishPublished(r, now);
                  log.info("리뷰 {} 작성(품목 {}, 사진 {}장)", id, lineId, photoIds.size());
                  return id;
                })
            .value();
    return view(store.review(reviewId).orElseThrow(), false);
  }

  public ReviewView update(
      String reviewId,
      String requestId,
      String sourceSystem,
      UpdateCommand cmd,
      @Nullable Integer expectedVersion) {
    PmartPrincipal c = customer();
    String text = cmd.text() == null ? null : normalize(cmd.text());
    if (cmd.rating() == null && cmd.text() == null && cmd.photoIds() == null) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "바꿀 값이 없습니다.");
    }
    ReviewRules.inputProblem(cmd.rating(), false, text, cmd.photoIds())
        .ifPresent(
            m -> {
              throw new PmartException(ErrorCode.VALIDATION_FAILED, m);
            });
    Map<String, Object> req = new LinkedHashMap<>();
    req.put("reviewId", reviewId);
    req.put("rating", cmd.rating());
    req.put("text", cmd.text() == null ? null : text);
    req.put("textGiven", cmd.text() != null);
    req.put("photoIds", cmd.photoIds());
    req.put("expectedVersion", expectedVersion);
    idempotency.execute(
        new RequestKey(sourceSystem, requestId, "updateReview:" + reviewId, c.subject()),
        req,
        String.class,
        () -> {
          Instant now = now();
          ReviewRow r = own(store.lockReview(reviewId), c);
          if (ReviewRules.HIDDEN.equals(r.state())) { // 0.7.0 운영자가 숨긴 리뷰는 고칠 수 없다
            throw new PmartException(ErrorCode.INVALID_STATE, "운영 정책으로 숨겨진 리뷰는 고칠 수 없습니다.");
          }
          if (ReviewRules.DELETED.equals(r.state())) {
            throw PmartException.invalidState("삭제된 리뷰는 고칠 수 없습니다.");
          }
          if (!ReviewRules.editable(r.createdAt(), now)) {
            throw PmartException.invalidState("등록 후 3일이 지난 리뷰는 고칠 수 없습니다.");
          }
          if (expectedVersion != null && expectedVersion != r.version()) {
            throw PmartException.versionConflict("다른 변경이 먼저 반영되었습니다(현재 " + r.version() + ").");
          }
          int rating = cmd.rating() == null ? r.rating() : cmd.rating();
          String body = cmd.text() == null ? r.body() : text;
          int version = r.version() + 1;
          if (cmd.photoIds() != null) {
            store.detachPhotosExcept(reviewId, cmd.photoIds(), now);
            attach(reviewId, c.subject(), cmd.photoIds());
          }
          store.updateReview(reviewId, rating, body, version, now);
          publishPublished(
              new ReviewRow(
                  r.reviewId(),
                  r.orderLineId(),
                  r.orderId(),
                  r.accountId(),
                  r.productId(),
                  r.sku(),
                  r.authorName(),
                  rating,
                  body,
                  r.state(),
                  version,
                  r.createdAt(),
                  now,
                  null),
              now);
          log.info("리뷰 {} 수정(v{})", reviewId, version);
          return reviewId;
        });
    return view(store.review(reviewId).orElseThrow(), false);
  }

  /** 삭제(멱등 — 이미 삭제면 그대로 성공). 행은 남겨 재작성을 막는다. 사진은 증빙 보존을 위해 지우지 않는다(공개만 닫힘). */
  /**
   * 도움돼요 누르기·취소(0.6.0 BR-76) — 회원만, 멱등(이미 그렇게 돼 있으면 그대로). 누르기는 공개 리뷰만(삭제 409 REVIEW_NOT_PUBLISHED)·
   * 내 리뷰 422 HELPFUL_OWN_REVIEW. 취소는 상태와 상관없이 받는다. 리뷰 행을 잠가 수를 맞춘다.
   */
  public Helpful helpful(String reviewId, boolean mark, String requestId, String sourceSystem) {
    PmartPrincipal c = customer();
    return idempotency
        .execute(
            new RequestKey(
                sourceSystem,
                requestId,
                (mark ? "markHelpful:" : "unmarkHelpful:") + reviewId,
                c.subject()),
            Map.of("reviewId", reviewId),
            Helpful.class,
            () -> {
              ReviewRow r =
                  store
                      .lockReview(reviewId)
                      .orElseThrow(() -> PmartException.notFound("REVIEW_NOT_FOUND", "리뷰가 없습니다."));
              if (!mark) {
                return new Helpful(reviewId, store.unmarkHelpful(reviewId, c.subject()), false);
              }
              if (r.accountId().equals(c.subject())) {
                throw rejected("HELPFUL_OWN_REVIEW", "내가 쓴 리뷰에는 도움돼요를 누를 수 없습니다.");
              }
              if (!ReviewRules.PUBLISHED.equals(r.state())) {
                throw new PmartException(
                    "REVIEW_NOT_PUBLISHED", HttpStatus.CONFLICT, "공개된 리뷰가 아닙니다.", false, List.of());
              }
              return new Helpful(reviewId, store.markHelpful(reviewId, c.subject(), now()), true);
            })
        .value();
  }

  public void delete(String reviewId, String requestId, String sourceSystem) {
    PmartPrincipal c = customer();
    idempotency.execute(
        new RequestKey(sourceSystem, requestId, "deleteReview:" + reviewId, c.subject()),
        Map.of("reviewId", reviewId),
        String.class,
        () -> {
          ReviewRow r = own(store.lockReview(reviewId), c);
          if (ReviewRules.DELETED.equals(r.state())) {
            return reviewId;
          }
          Instant now = now();
          int version = r.version() + 1;
          store.markDeleted(reviewId, version, now);
          Map<String, Object> m = new LinkedHashMap<>();
          m.put("reviewId", r.reviewId());
          m.put("orderLineId", r.orderLineId());
          m.put("productId", r.productId());
          m.put("rating", r.rating());
          m.put("deletedAt", now.toString());
          m.put("reason", "AUTHOR_DELETED");
          events.publish(
              "ReviewDeleted",
              1,
              AGGREGATE,
              r.reviewId(),
              version,
              new EventEnvelope.Actor(EventEnvelope.ActorType.CUSTOMER, c.subject()),
              m);
          log.info("리뷰 {} 삭제", reviewId);
          return reviewId;
        });
  }

  // ---------------------------------------------------------------- 조회

  /** 공개 단건(삭제된 것은 404, 0.7.0 숨긴 것은 작성자 본인에게만 — LOCKED 로). */
  public ReviewView get(String reviewId) {
    String viewer =
        PmartPrincipal.current()
            .filter(x -> x.type() == PmartPrincipal.PrincipalType.CUSTOMER)
            .map(PmartPrincipal::subject)
            .orElse(null);
    ReviewRow r =
        store
            .review(reviewId)
            .filter(x -> !ReviewRules.DELETED.equals(x.state()))
            .filter(x -> !ReviewRules.HIDDEN.equals(x.state()) || x.accountId().equals(viewer))
            .orElseThrow(() -> PmartException.notFound("REVIEW_NOT_FOUND", "리뷰가 없습니다."));
    return view(r, false);
  }

  /**
   * 내부 조회 — 주문의 리뷰 작성 여부(0.3.0, 2026-11-18 사용자 "A해줘": notification 이 배송 완료 1시간 뒤 리뷰 요청 알림을 보낼지).
   * 자격이 없거나 남의 주문이면 모두 0. 반품은 보지 않는다(작성 때 order 로 확인).
   */
  public ReviewStore.OrderStatus orderReviewStatus(String orderId, String accountId) {
    return store.orderStatus(orderId, accountId, now().minus(ReviewRules.WRITE_WINDOW));
  }

  /** 내부 조회(삭제 포함, 자격 근거 시각). */
  public ReviewView internal(String reviewId) {
    ReviewRow r =
        store
            .review(reviewId)
            .orElseThrow(() -> PmartException.notFound("REVIEW_NOT_FOUND", "리뷰가 없습니다."));
    return view(r, true);
  }

  public ReviewPage productReviews(
      String productId,
      @Nullable String sort,
      boolean withPhotoOnly,
      @Nullable String sku,
      @Nullable String cursor,
      int size) {
    int offset = offset(cursor);
    List<ReviewRow> rows =
        store.productReviews(
            productId, sort == null ? "NEWEST" : sort, withPhotoOnly, sku, offset, size + 1);
    boolean more = rows.size() > size;
    List<ReviewRow> page = rows.subList(0, Math.min(size, rows.size()));
    Map<String, List<PhotoRow>> byReview =
        store.photosOf(page.stream().map(ReviewRow::reviewId).toList()).stream()
            .collect(Collectors.groupingBy(p -> Objects.requireNonNull(p.reviewId())));
    Instant now = now();
    // 회원 토큰이 있으면 내가 누른 리뷰(0.6.0 BR-76) — 공개 목록이라 토큰이 없을 수 있다(그때 null)
    String viewer =
        PmartPrincipal.current()
            .filter(x -> x.type() == PmartPrincipal.PrincipalType.CUSTOMER)
            .map(PmartPrincipal::subject)
            .orElse(null);
    java.util.Set<String> helped =
        viewer == null
            ? java.util.Set.of()
            : store.helpedBy(viewer, page.stream().map(ReviewRow::reviewId).toList());
    // 0.7.0 BR-90 — 내가 신고했는지·내 리뷰인지(회원 토큰일 때만)
    java.util.Set<String> reported =
        viewer == null
            ? java.util.Set.of()
            : reportStore.reportedBy(viewer, page.stream().map(ReviewRow::reviewId).toList());
    List<ReviewView> items =
        page.stream()
            .map(
                r ->
                    new ReviewView(
                        r,
                        byReview.getOrDefault(r.reviewId(), List.of()),
                        ReviewRules.reviewState(r.state(), r.createdAt(), now),
                        null,
                        viewer == null ? null : helped.contains(r.reviewId()),
                        viewer == null ? null : reported.contains(r.reviewId()),
                        viewer == null ? null : r.accountId().equals(viewer)))
            .toList();
    ReviewStore.Summary s = store.summary(productId);
    // reviewCount 는 거른 수(0.6.0), 평균은 상품 전체
    int count =
        (sku == null && !withPhotoOnly)
            ? s.count()
            : store.filteredCount(productId, withPhotoOnly, sku);
    return new ReviewPage(items, more ? String.valueOf(offset + size) : null, s.average(), count);
  }

  // ---------------------------------------------------------------- 사진

  public PhotoRow uploadPhoto(String requestId, String sourceSystem, byte[] bytes) {
    PmartPrincipal c = customer();
    ReviewRules.photoProblem(bytes)
        .ifPresent(
            m -> {
              throw new PmartException(
                  "PHOTO_INVALID", HttpStatus.UNPROCESSABLE_CONTENT, m, false, List.of());
            });
    String type = ReviewRules.photoType(bytes).orElseThrow();
    int[] size = JpegResizer.dimensions(bytes); // WEBP 등 JDK 가 못 읽는 형식은 null — 서버가 풀지도 않는다
    if (size != null && (long) size[0] * size[1] > JpegResizer.MAX_PIXELS) {
      // 2026-11-20 리뷰 지적 1: 작은 파일로 거대한 그림(압축 폭탄)을 올려 서비스를 메모리 부족으로 죽이지 못하게
      throw new PmartException(
          "PHOTO_INVALID",
          HttpStatus.UNPROCESSABLE_CONTENT,
          "사진 해상도가 너무 큽니다(4천만 화소 이하).",
          false,
          List.of());
    }
    String sha = sha256(bytes);
    String photoId =
        idempotency
            .execute(
                new RequestKey(sourceSystem, requestId, "uploadReviewPhoto", c.subject()),
                Map.of("sha256", sha, "size", bytes.length),
                String.class,
                () -> {
                  String id = "RPH-" + UUID.randomUUID();
                  String key = "review-photos/" + c.subject() + "/" + id;
                  photos.put(key, bytes, type); // 실패하면 행도 남지 않는다(같은 트랜잭션에서 예외)
                  store.insertPhoto(
                      new PhotoRow(id, c.subject(), key, type, bytes.length, sha, now(), null));
                  return id;
                })
            .value();
    preparer.afterUpload(); // 커밋 뒤(5초) 축소본을 뒤에서 만든다
    return store.photo(photoId).orElseThrow();
  }

  /**
   * 공개 리뷰에 붙은 사진만 본문을 준다. 0.8.0: 상담원·운영 관리자 토큰이면 운영자가 숨긴(HIDDEN) 리뷰 사진도 — 신고 상세에서 숨긴 뒤에도 확인하게.
   * width 가 있으면 축소본(0.4.0, 줄일 수 없는 형식이면 원본).
   */
  public Photo photo(String photoId, @Nullable Integer width) {
    if (width != null) {
      ReviewPhotoVariants.checkWidth(width);
    }
    PhotoRow p =
        store
            .photo(photoId)
            .filter(
                x ->
                    x.reviewId() != null
                        && store
                            .review(x.reviewId())
                            .map(
                                r ->
                                    ReviewRules.PUBLISHED.equals(r.state())
                                        || (ReviewRules.HIDDEN.equals(r.state())
                                            && ReviewReports.isStaff()))
                            .orElse(false))
            .orElseThrow(() -> PmartException.notFound("PHOTO_NOT_FOUND", "사진이 없습니다."));
    if (width != null) {
      ReviewPhotoVariants.Variant v = variants.variant(p, width);
      if (v != null) {
        return new Photo(p, v.body(), "image/jpeg", v.length(), v.etag());
      }
    }
    return new Photo(p, photos.get(p.objectKey()), p.contentType(), p.sizeBytes(), p.sha256());
  }

  /** 붙지 않은(또는 떨어진) 지 하루 지난 사진 정리. */
  public int sweepOrphanPhotos() {
    int n = 0;
    for (PhotoRow p : store.orphanPhotos(now().minus(ReviewRules.ORPHAN_PHOTO_TTL), 100)) {
      if (photos.delete(p.objectKey())) {
        variants.delete(p.photoId()); // 축소본도(있으면)
        store.deletePhoto(p.photoId());
        n++;
      }
    }
    return n;
  }

  // ---------------------------------------------------------------- 내부

  private void attach(String reviewId, String accountId, List<String> photoIds) {
    if (photoIds.isEmpty()) {
      return;
    }
    if (store.attachPhotos(reviewId, accountId, photoIds) != photoIds.size()) {
      throw rejected("PHOTO_NOT_FOUND", "내가 올린 사진이 아니거나 다른 리뷰에 쓰인 사진입니다."); // 트랜잭션째 되돌린다
    }
  }

  private void publishPublished(ReviewRow r, Instant now) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("reviewId", r.reviewId());
    m.put("orderLineId", r.orderLineId());
    m.put("orderId", r.orderId());
    m.put("productId", r.productId());
    m.put("accountId", r.accountId());
    m.put("rating", r.rating());
    m.put("publishedAt", now.toString());
    events.publish(
        "ReviewPublished",
        1,
        AGGREGATE,
        r.reviewId(),
        r.version(),
        new EventEnvelope.Actor(EventEnvelope.ActorType.CUSTOMER, r.accountId()),
        m);
  }

  private ReviewView view(ReviewRow r, boolean withDelivery) {
    Instant deliveredAt =
        withDelivery
            ? store.eligibility(r.orderLineId()).map(EligibilityRow::deliveredAt).orElse(null)
            : null;
    return new ReviewView(
        r,
        store.photosOf(r.reviewId()),
        ReviewRules.reviewState(r.state(), r.createdAt(), now()),
        deliveredAt);
  }

  private static ReviewRow own(java.util.Optional<ReviewRow> r, PmartPrincipal c) {
    // 남의 리뷰는 없는 것과 같게(404) — 존재를 알리지 않는다
    return r.filter(x -> x.accountId().equals(c.subject()))
        .orElseThrow(() -> PmartException.notFound("REVIEW_NOT_FOUND", "리뷰가 없습니다."));
  }

  private static PmartPrincipal customer() {
    return PmartPrincipal.current()
        .filter(x -> x.type() == PmartPrincipal.PrincipalType.CUSTOMER)
        .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "회원 고객만 할 수 있습니다."));
  }

  private static PmartException rejected(String code, String message) {
    return new PmartException(code, HttpStatus.UNPROCESSABLE_CONTENT, message, false, List.of());
  }

  private static @Nullable String normalize(@Nullable String text) {
    if (text == null) {
      return null;
    }
    String v = text.strip();
    return v.isEmpty() ? null : v;
  }

  private static @Nullable String blankToNull(@Nullable String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }

  private static @Nullable String text(JsonNode n, String field) {
    JsonNode v = n.get(field);
    return v == null || v.isNull() ? null : v.asString();
  }

  private static int offset(@Nullable String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return 0;
    }
    try {
      int n = Integer.parseInt(cursor);
      if (n < 0 || n > 100_000) {
        throw new NumberFormatException();
      }
      return n;
    } catch (NumberFormatException e) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "cursor 가 올바르지 않습니다.");
    }
  }

  private static String sha256(byte[] b) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }
}
