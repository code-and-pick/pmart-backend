package com.pmart.cases.api;

import com.pmart.cases.api.generated.InquiriesApi;
import com.pmart.cases.api.generated.model.CreateInquiryRequest;
import com.pmart.cases.api.generated.model.Inquiry;
import com.pmart.cases.api.generated.model.InquiryAllOfReplies;
import com.pmart.cases.api.generated.model.InquiryCategory;
import com.pmart.cases.api.generated.model.InquiryStatus;
import com.pmart.cases.api.generated.model.InquirySummary;
import com.pmart.cases.api.generated.model.ListCases200ResponsePage;
import com.pmart.cases.api.generated.model.ListInquiries200Response;
import com.pmart.cases.api.generated.model.ReplyInquiryRequest;
import com.pmart.cases.application.InquiryService;
import com.pmart.cases.infrastructure.InquiryStore.InquiryRow;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 고객센터 문의(0.2.0). 요청·응답 변환만 — 규칙은 {@link InquiryService}. */
@RestController
public class InquiriesController implements InquiriesApi {

  private final InquiryService service;

  public InquiriesController(InquiryService service) {
    this.service = service;
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
                req.getOrigin() == null ? null : req.getOrigin().getValue()));
    return ResponseEntity.status(HttpStatus.CREATED).body(detail(view));
  }

  @Override
  public ResponseEntity<ListInquiries200Response> listInquiries(
      Integer size,
      @Nullable String cursor,
      @Nullable InquiryStatus status,
      @Nullable String orderId) {
    var page = service.list(status == null ? null : status.getValue(), orderId, cursor, size);
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
    return ResponseEntity.ok(detail(service.get(inquiryId)));
  }

  @Override
  public ResponseEntity<Inquiry> replyInquiry(
      String inquiryId,
      String idempotencyKey,
      String xSourceSystem,
      ReplyInquiryRequest req,
      @Nullable Integer xExpectedVersion) {
    return ResponseEntity.ok(
        detail(
            service.reply(
                idempotencyKey, xSourceSystem, inquiryId, req.getBody(), xExpectedVersion)));
  }

  private static InquirySummary summary(InquiryRow r) {
    var s = new InquirySummary();
    s.setInquiryId(r.inquiryId());
    s.setCategory(InquiryCategory.fromValue(r.category()));
    s.setOrderId(r.orderId());
    s.setTitle(r.title());
    s.setStatus(InquiryStatus.fromValue(r.status()));
    s.setOrigin(r.origin());
    s.setCreatedAt(r.createdAt().atOffset(ZoneOffset.UTC));
    s.setAnsweredAt(r.answeredAt() == null ? null : r.answeredAt().atOffset(ZoneOffset.UTC));
    s.setVersion(r.version());
    return s;
  }

  private static Inquiry detail(InquiryService.View v) {
    InquiryRow r = v.inquiry();
    var d = new Inquiry();
    d.setInquiryId(r.inquiryId());
    d.setCaseId(r.caseId());
    d.setCustomerId(r.customerId());
    d.setCategory(InquiryCategory.fromValue(r.category()));
    d.setOrderId(r.orderId());
    d.setTitle(r.title());
    d.setBody(r.body());
    d.setStatus(InquiryStatus.fromValue(r.status()));
    d.setOrigin(r.origin());
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
    return d;
  }
}
