package com.pmart.cases.application;

import com.pmart.cases.domain.InquiryRules;
import com.pmart.cases.infrastructure.InquiryStore;
import com.pmart.cases.infrastructure.InquiryStore.InquiryRow;
import com.pmart.cases.infrastructure.InquiryStore.MessageRow;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 라이더 문의 대화(0.21.0, 2026-11-17 라이더 앱 요청 §19 — 사용자 "문의에 대화 붙이기"). 배송 밖 문의를 채팅처럼 이어 쓴다. 메시지는 답변 표 그대로
 * — 지원센터 답변(AGENT → SUPPORT)과 라이더가 이어 쓴 것(RIDER). 지원센터는 기존 답변(`/replies`)으로 답한다.
 */
@Service
public class InquiryChatService {

  private static final Logger log = LoggerFactory.getLogger(InquiryChatService.class);
  static final int MAX_PAGE = 200;

  private final InquiryService inquiries;
  private final InquiryStore store;
  private final RequestIdempotency idempotency;
  private final Clock clock;

  public InquiryChatService(
      InquiryService inquiries, InquiryStore store, RequestIdempotency idempotency, Clock clock) {
    this.inquiries = inquiries;
    this.store = store;
    this.idempotency = idempotency;
    this.clock = clock;
  }

  public record Page(List<MessageRow> items, String status) {}

  /** 볼 권한은 문의 상세와 같다(남의 것 404). 고객 문의는 대화가 없다 — 404. */
  public Page list(String inquiryId, @Nullable Long after) {
    InquiryRow row = riderInquiry(inquiries.get(inquiryId).inquiry());
    return new Page(store.messages(inquiryId, after == null ? 0 : after, MAX_PAGE), row.status());
  }

  /** 남긴 라이더 본인만. 닫힌 문의 409 INQUIRY_CLOSED. 상태 → RECEIVED. */
  public MessageRow post(String requestId, String sourceSystem, String inquiryId, String body) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "토큰이 필요합니다."));
    if (p.type() != PmartPrincipal.PrincipalType.RIDER) {
      throw new PmartException(ErrorCode.FORBIDDEN, "문의 대화는 라이더 본인만 이어 씁니다. 지원센터는 답변(/replies)으로.");
    }
    String messageId =
        idempotency
            .execute(
                new RequestKey(sourceSystem, requestId, "postInquiryMessage", p.subject()),
                new Object[] {inquiryId, body},
                String.class,
                () -> {
                  InquiryRow row =
                      store
                          .lock(inquiryId)
                          .filter(r -> r.customerId().equals(p.subject()))
                          .map(InquiryChatService::riderInquiry)
                          .orElseThrow(() -> notFound(inquiryId));
                  if (InquiryRules.replyRejection(row.status()) != null) {
                    throw new PmartException(
                        "INQUIRY_CLOSED",
                        HttpStatus.CONFLICT,
                        "닫힌 문의입니다. 새 문의를 남겨 주세요.",
                        false,
                        List.of());
                  }
                  String id = "IMS-" + UUID.randomUUID();
                  store.addRequesterMessage(inquiryId, id, p.subject(), body, clock.instant());
                  log.info("rider inquiry message id={} inquiry={}", id, inquiryId);
                  return id;
                })
            .value();
    return store.message(messageId).orElseThrow();
  }

  private static InquiryRow riderInquiry(InquiryRow row) {
    if (!InquiryRules.RIDER.equals(row.requesterType())) {
      throw notFound(row.inquiryId());
    }
    return row;
  }

  private static PmartException notFound(String inquiryId) {
    return PmartException.notFound("INQUIRY_NOT_FOUND", "문의를 찾을 수 없습니다: " + inquiryId);
  }
}
