package com.pmart.cases.application;

import com.pmart.cases.domain.CsIntakeRules;
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
 * 문의 대화 — 라이더 문의(0.21.0, 2026-11-17 라이더 앱 요청 §19 — 사용자 "문의에 대화 붙이기")와 R-16(ADR-0086, 2026-11-24)부터
 * 고객 문의도. 메시지는 답변 표 그대로 — 답변(AGENT → SUPPORT), 남긴 사람이 이어 쓴 것(RIDER·CUSTOMER), 시스템 안내(SYSTEM — 오배송
 * 선택 등). 직원은 기존 답변(`/replies`)으로 답한다.
 */
@Service
public class InquiryChatService {

  private static final Logger log = LoggerFactory.getLogger(InquiryChatService.class);
  static final int MAX_PAGE = 200;

  private final InquiryService inquiries;
  private final InquiryStore store;
  private final RequestIdempotency idempotency;
  private final Clock clock;
  private final ProfanityGuard profanity;
  private final CustomerRestrictions restrictions;

  public InquiryChatService(
      InquiryService inquiries,
      InquiryStore store,
      RequestIdempotency idempotency,
      Clock clock,
      ProfanityGuard profanity,
      CustomerRestrictions restrictions) {
    this.restrictions = restrictions;
    this.profanity = profanity;
    this.inquiries = inquiries;
    this.store = store;
    this.idempotency = idempotency;
    this.clock = clock;
  }

  public record Page(List<MessageRow> items, String status) {}

  /** 볼 권한은 문의 상세와 같다(남의 것 404). R-16 부터 고객 문의도. */
  public Page list(String inquiryId, @Nullable Long after) {
    InquiryRow row = inquiries.get(inquiryId).inquiry();
    return new Page(store.messages(inquiryId, after == null ? 0 : after, MAX_PAGE), row.status());
  }

  /** 남긴 라이더 본인만. 닫힌 문의 409 INQUIRY_CLOSED. 상태 → RECEIVED. */
  public MessageRow post(String requestId, String sourceSystem, String inquiryId, String body) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "토큰이 필요합니다."));
    boolean rider = p.type() == PmartPrincipal.PrincipalType.RIDER;
    if (!rider && p.type() != PmartPrincipal.PrincipalType.CUSTOMER) {
      throw new PmartException(
          ErrorCode.FORBIDDEN, "문의 대화는 남긴 고객·라이더 본인만 이어 씁니다. 직원은 답변(/replies)으로.");
    }
    String requesterType = rider ? InquiryRules.RIDER : InquiryRules.CUSTOMER;
    if (!rider) {
      restrictions.checkCanWrite(p.subject()); // CU-7 이용제한·정지 고객은 쓰지 못함(새 문의와 같게)
    }
    // 0.37.0: 막기 단어(욕설)는 받지 않는다 — 422, 정지 없음
    profanity.rejectBlocked(body);
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
                          .filter(r -> requesterType.equals(r.requesterType()))
                          .filter(r -> !CsIntakeRules.ORIGIN_STAFF_INTAKE.equals(r.origin()))
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
                  java.time.Instant now = clock.instant();
                  store.addRequesterMessage(inquiryId, id, requesterType, p.subject(), body, now);
                  // 0.37.0: 정지 단어 → 라이더 RiderProfanityDetected(PC 5)·고객
                  // CustomerProfanityDetected(CU-7)
                  profanity.reportSuspendable(
                      requesterType,
                      p.subject(),
                      inquiryId,
                      row.caseId(),
                      "INQUIRY_MESSAGE",
                      row.version() + 1L,
                      now,
                      body);
                  log.info("inquiry message id={} inquiry={} by={}", id, inquiryId, requesterType);
                  return id;
                })
            .value();
    return store.message(messageId).orElseThrow();
  }

  private static PmartException notFound(String inquiryId) {
    return PmartException.notFound("INQUIRY_NOT_FOUND", "문의를 찾을 수 없습니다: " + inquiryId);
  }
}
