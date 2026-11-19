package com.pmart.cases.api;

import com.pmart.cases.api.generated.InquiriesApi;
import com.pmart.cases.api.generated.model.AddInquiryPhotosRequest;
import com.pmart.cases.api.generated.model.CloseInquiryRequest;
import com.pmart.cases.api.generated.model.CreateInquiryRequest;
import com.pmart.cases.api.generated.model.Inquiry;
import com.pmart.cases.api.generated.model.InquiryAllOfReplies;
import com.pmart.cases.api.generated.model.InquiryCategory;
import com.pmart.cases.api.generated.model.InquiryDeliveryIssue;
import com.pmart.cases.api.generated.model.InquiryRequesterType;
import com.pmart.cases.api.generated.model.InquirySatisfaction;
import com.pmart.cases.api.generated.model.InquirySatisfactionSummary;
import com.pmart.cases.api.generated.model.InquiryStatus;
import com.pmart.cases.api.generated.model.InquirySummary;
import com.pmart.cases.api.generated.model.ListCases200ResponsePage;
import com.pmart.cases.api.generated.model.ListInquiries200Response;
import com.pmart.cases.api.generated.model.RateInquiryRequest;
import com.pmart.cases.api.generated.model.ReplyInquiryRequest;
import com.pmart.cases.application.InquiryCloseService;
import com.pmart.cases.application.InquiryPhotoService;
import com.pmart.cases.application.InquirySatisfactionService;
import com.pmart.cases.application.InquiryService;
import com.pmart.cases.domain.InquiryRules;
import com.pmart.cases.infrastructure.InquiryStore.InquiryRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 고객센터 문의(0.2.0). 요청·응답 변환만 — 규칙은 {@link InquiryService}. */
@RestController
public class InquiriesController implements InquiriesApi {

  private final InquiryService service;
  private final InquiryPhotoService photos;
  private final InquirySatisfactionService satisfaction;
  private final InquiryCloseService closes;
  private final com.pmart.cases.application.InquiryChatService chat;

  public InquiriesController(
      InquiryService service,
      InquiryPhotoService photos,
      InquirySatisfactionService satisfaction,
      InquiryCloseService closes,
      com.pmart.cases.application.InquiryChatService chat) {
    this.service = service;
    this.photos = photos;
    this.satisfaction = satisfaction;
    this.closes = closes;
    this.chat = chat;
  }

  @Override
  public ResponseEntity<com.pmart.cases.api.generated.model.ListInquiryMessages200Response>
      listInquiryMessages(String inquiryId, @Nullable Long after) {
    var page = chat.list(inquiryId, after);
    boolean staff =
        com.pmart.common.security.PmartPrincipal.current()
            .map(p -> p.type() == com.pmart.common.security.PmartPrincipal.PrincipalType.OPERATOR)
            .orElse(false);
    var out = new com.pmart.cases.api.generated.model.ListInquiryMessages200Response();
    out.setItems(page.items().stream().map(m -> message(m, staff)).toList());
    out.setStatus(InquiryStatus.fromValue(page.status()));
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<com.pmart.cases.api.generated.model.InquiryMessage> postInquiryMessage(
      String inquiryId,
      String idempotencyKey,
      String xSourceSystem,
      com.pmart.cases.api.generated.model.PostInquiryMessageRequest req) {
    return ResponseEntity.status(201)
        .body(message(chat.post(idempotencyKey, xSourceSystem, inquiryId, req.getBody()), false));
  }

  /** 답변 표 한 줄 → 대화 메시지. 상담원 ID 는 직원에게만. */
  private static com.pmart.cases.api.generated.model.InquiryMessage message(
      com.pmart.cases.infrastructure.InquiryStore.MessageRow m, boolean staff) {
    boolean rider = "RIDER".equals(m.authorType());
    var o = new com.pmart.cases.api.generated.model.InquiryMessage();
    o.setMessageId(m.messageId());
    o.setSeq(m.seq());
    o.setSenderType(
        rider
            ? com.pmart.cases.api.generated.model.InquiryMessage.SenderTypeEnum.RIDER
            : com.pmart.cases.api.generated.model.InquiryMessage.SenderTypeEnum.SUPPORT);
    o.setSenderId(rider || staff ? m.authorId() : null);
    o.setBody(m.body());
    o.setCreatedAt(m.createdAt().atOffset(ZoneOffset.UTC));
    return o;
  }

  @Override
  public ResponseEntity<Inquiry> closeInquiry(
      String inquiryId, String idempotencyKey, String xSourceSystem, CloseInquiryRequest req) {
    return ResponseEntity.ok(
        out(
            closes.close(
                idempotencyKey,
                xSourceSystem,
                inquiryId,
                req.getReason(),
                req.getScore(),
                req.getComment())));
  }

  @Override
  public ResponseEntity<Inquiry> addInquiryPhotos(
      String inquiryId, String idempotencyKey, String xSourceSystem, AddInquiryPhotosRequest req) {
    return ResponseEntity.ok(
        out(photos.add(idempotencyKey, xSourceSystem, inquiryId, req.getPhotoIds())));
  }

  @Override
  public ResponseEntity<Inquiry> createInquiry(
      String idempotencyKey, String xSourceSystem, CreateInquiryRequest req) {
    var view =
        service.create(
            idempotencyKey,
            xSourceSystem,
            new InquiryService.CreateCommand(
                req.getCategory().getValue(),
                req.getOrderId(),
                req.getTitle(),
                req.getBody(),
                req.getOrigin() == null ? null : req.getOrigin().getValue(),
                req.getPhotoIds(),
                req.getDeliveryId(),
                req.getDeliveryIssue() == null ? null : req.getDeliveryIssue().getValue()));
    return ResponseEntity.status(HttpStatus.CREATED).body(out(view));
  }

  @Override
  public ResponseEntity<ListInquiries200Response> listInquiries(
      Integer size,
      @Nullable String cursor,
      @Nullable InquiryStatus status,
      @Nullable String orderId,
      @Nullable InquiryRequesterType requesterType,
      @Nullable InquiryDeliveryIssue deliveryIssue) {
    var page =
        service.list(
            status == null ? null : status.getValue(),
            orderId,
            requesterType == null ? null : requesterType.getValue(),
            deliveryIssue == null ? null : deliveryIssue.getValue(),
            cursor,
            size);
    var out = new ListInquiries200Response();
    out.setItems(page.items().stream().map(InquiriesController::summary).toList());
    var p = new ListCases200ResponsePage();
    p.setNextCursor(page.nextCursor());
    p.setSize(page.items().size());
    out.setPage(p);
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<Inquiry> getInquiry(String inquiryId) {
    return ResponseEntity.ok(out(service.get(inquiryId)));
  }

  @Override
  public ResponseEntity<Inquiry> replyInquiry(
      String inquiryId,
      String idempotencyKey,
      String xSourceSystem,
      ReplyInquiryRequest req,
      @Nullable Integer xExpectedVersion) {
    return ResponseEntity.ok(
        out(
            service.reply(
                idempotencyKey, xSourceSystem, inquiryId, req.getBody(), xExpectedVersion)));
  }

  @Override
  public ResponseEntity<Inquiry> rateInquiry(
      String inquiryId, String idempotencyKey, String xSourceSystem, RateInquiryRequest req) {
    return ResponseEntity.ok(
        out(
            satisfaction.rate(
                idempotencyKey, xSourceSystem, inquiryId, req.getScore(), req.getComment())));
  }

  @Override
  public ResponseEntity<InquirySatisfactionSummary> getInquirySatisfactionSummary(
      OffsetDateTime answeredFrom, OffsetDateTime answeredTo) {
    var v = satisfaction.summary(answeredFrom.toInstant(), answeredTo.toInstant());
    var s = v.summary();
    var out = new InquirySatisfactionSummary();
    out.setAnsweredFrom(v.from().atOffset(ZoneOffset.UTC));
    out.setAnsweredTo(v.to().atOffset(ZoneOffset.UTC));
    out.setAnsweredCount(s.answered());
    out.setRatedCount(s.rated());
    out.setScoreSum(s.scoreSum());
    out.setAverageScore(
        s.rated() == 0
            ? null
            : BigDecimal.valueOf(s.scoreSum())
                .divide(BigDecimal.valueOf(s.rated()), 2, RoundingMode.HALF_UP));
    Map<String, Long> by = new LinkedHashMap<>();
    s.byScore().forEach((k, n) -> by.put(String.valueOf(k), n));
    out.setByScore(by);
    return ResponseEntity.ok(out);
  }

  /** 상세 + 만족도(본인·관리자에게만 — 0.15.0). */
  private Inquiry out(InquiryService.View v) {
    Inquiry d = detail(v);
    satisfaction
        .forViewer(v.inquiry().inquiryId())
        .ifPresent(
            r -> {
              var x = new InquirySatisfaction();
              x.setScore(r.score());
              x.setComment(r.comment());
              x.setRatedAt(r.ratedAt().atOffset(ZoneOffset.UTC));
              d.setSatisfaction(x);
            });
    closes
        .forViewer(v.inquiry().inquiryId())
        .ifPresent(
            c -> {
              d.setClosedAt(c.closedAt().atOffset(ZoneOffset.UTC));
              d.setClosedByType(Inquiry.ClosedByTypeEnum.fromValue(c.closedByType()));
              d.setCloseReason(c.reason());
            });
    return d;
  }

  private static InquirySummary summary(InquiryRow r) {
    var s = new InquirySummary();
    s.setInquiryId(r.inquiryId());
    s.setInquiryNumber(r.inquiryNumber());
    s.setCategory(InquiryCategory.fromValue(r.category()));
    s.setOrderId(r.orderId());
    s.setTitle(r.title());
    s.setStatus(InquiryStatus.fromValue(r.status()));
    s.setOrigin(r.origin());
    s.setChannel(r.channel());
    s.setCallerRelation(r.callerRelation());
    s.setRequesterType(InquiryRequesterType.fromValue(r.requesterType()));
    s.setDeliveryId(r.deliveryId());
    s.setDeliveryIssue(
        r.deliveryIssue() == null ? null : InquiryDeliveryIssue.fromValue(r.deliveryIssue()));
    s.setCreatedAt(r.createdAt().atOffset(ZoneOffset.UTC));
    s.setAnsweredAt(r.answeredAt() == null ? null : r.answeredAt().atOffset(ZoneOffset.UTC));
    s.setVersion(r.version());
    return s;
  }

  private static Inquiry detail(InquiryService.View v) {
    InquiryRow r = v.inquiry();
    var d = new Inquiry();
    d.setInquiryId(r.inquiryId());
    d.setInquiryNumber(r.inquiryNumber());
    d.setCaseId(r.caseId());
    if (InquiryRules.RIDER.equals(r.requesterType())) {
      d.setRiderId(r.customerId());
    } else {
      d.setCustomerId(r.customerId());
    }
    d.setRequesterType(InquiryRequesterType.fromValue(r.requesterType()));
    d.setDeliveryId(r.deliveryId());
    d.setDeliveryIssue(
        r.deliveryIssue() == null ? null : InquiryDeliveryIssue.fromValue(r.deliveryIssue()));
    d.setCategory(InquiryCategory.fromValue(r.category()));
    d.setOrderId(r.orderId());
    d.setTitle(r.title());
    d.setBody(r.body());
    d.setStatus(InquiryStatus.fromValue(r.status()));
    d.setOrigin(r.origin());
    d.setChannel(r.channel());
    d.setCallerRelation(r.callerRelation());
    d.setCreatedAt(r.createdAt().atOffset(ZoneOffset.UTC));
    d.setAnsweredAt(r.answeredAt() == null ? null : r.answeredAt().atOffset(ZoneOffset.UTC));
    d.setVersion(r.version());
    d.setReplies(
        v.replies().stream()
            .map(
                x -> {
                  var y = new InquiryAllOfReplies();
                  y.setReplyId(x.replyId());
                  y.setAuthorType(InquiryAllOfReplies.AuthorTypeEnum.fromValue(x.authorType()));
                  y.setBody(x.body());
                  y.setCreatedAt(x.createdAt().atOffset(ZoneOffset.UTC));
                  return y;
                })
            .toList());
    d.setPhotos(v.photos().stream().map(InquiryPhotosController::photo).toList());
    return d;
  }
}
