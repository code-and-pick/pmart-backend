package com.pmart.cases.application;

import com.pmart.cases.domain.CsIntakeRules;
import com.pmart.cases.domain.InquiryRules;
import com.pmart.cases.infrastructure.InquirySatisfactionStore;
import com.pmart.cases.infrastructure.InquirySatisfactionStore.SatisfactionRow;
import com.pmart.cases.infrastructure.InquirySatisfactionStore.Summary;
import com.pmart.cases.infrastructure.InquiryStore;
import com.pmart.cases.infrastructure.InquiryStore.InquiryRow;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 문의 만족도(0.15.0 — 2026-11-17 사용자 "답변 뒤 1~5점 + 한 줄", 업무 원문 Commerce §14.2). 답변 받은 고객 문의에 남긴 고객이 한 번만
 * 남긴다. 상담 평가일 뿐 반품·환불 종결이나 라이더 평가가 아니고, **상담원 평가·제재에 쓰지 않는다** — 상담원에게는 보이지 않고 본사 관리자만 모아 본다.
 * 상태·version 은 바꾸지 않는다. 이벤트·알림 없음. 의견 글은 로그에 남기지 않는다.
 */
@Service
public class InquirySatisfactionService {

  /** 모아 보기 기간 상한(넓은 훑기 방지 — 주문 직원 검색 31일과 같은 뜻, 분기 단위까지). */
  static final Duration MAX_SUMMARY_RANGE = Duration.ofDays(92);

  public static final int MAX_COMMENT = 200;

  public record SummaryView(Instant from, Instant to, Summary summary) {}

  private final InquirySatisfactionStore store;
  private final InquiryStore inquiries;
  private final InquiryService inquiryService;
  private final RequestIdempotency idempotency;
  private final Clock clock;

  public InquirySatisfactionService(
      InquirySatisfactionStore store,
      InquiryStore inquiries,
      InquiryService inquiryService,
      RequestIdempotency idempotency,
      Clock clock) {
    this.store = store;
    this.inquiries = inquiries;
    this.inquiryService = inquiryService;
    this.idempotency = idempotency;
    this.clock = clock;
  }

  /** 남기기 — 남긴 고객 본인, 답변이 있는 문의, 한 번만(두 번째 409 ALREADY_RATED). */
  public InquiryService.View rate(
      String requestId,
      String sourceSystem,
      String inquiryId,
      int score,
      @Nullable String comment) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .filter(x -> x.type() == PmartPrincipal.PrincipalType.CUSTOMER)
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "만족도는 문의를 남긴 고객만 남깁니다."));
    if (score < 1 || score > 5) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "점수는 1~5 입니다.");
    }
    String text = comment == null || comment.isBlank() ? null : comment.strip();
    if (text != null && text.length() > MAX_COMMENT) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "의견은 200자까지입니다.");
    }
    idempotency.execute(
        new RequestKey(sourceSystem, requestId, "rateInquiry:" + inquiryId, p.subject()),
        Map.of("score", score, "comment", text == null ? "" : text),
        String.class,
        () -> {
          InquiryRow row =
              inquiries
                  .lock(inquiryId)
                  .filter(r -> mine(p, r))
                  .orElseThrow(
                      () ->
                          PmartException.notFound(
                              "INQUIRY_NOT_FOUND", "문의를 찾을 수 없습니다: " + inquiryId));
          if (row.answeredAt() == null) {
            throw conflict("INQUIRY_NOT_ANSWERED", "답변을 받은 뒤에 남길 수 있습니다.");
          }
          if (store.find(inquiryId).isPresent()) {
            throw conflict("ALREADY_RATED", "이미 만족도를 남겼습니다.");
          }
          store.insert(
              new SatisfactionRow(
                  inquiryId,
                  p.subject(),
                  score,
                  text,
                  clock.instant().truncatedTo(ChronoUnit.MICROS)));
          return inquiryId;
        });
    return inquiryService.get(inquiryId);
  }

  /** 문의 상세에 붙일 만족도 — 남긴 본인(상세 접근은 이미 확인됨)과 본사 관리자에게만. 상담원·지원센터에게는 빈 값. */
  public Optional<SatisfactionRow> forViewer(String inquiryId) {
    PmartPrincipal p = PmartPrincipal.current().orElse(null);
    if (p == null) {
      return Optional.empty();
    }
    boolean owner = p.type() == PmartPrincipal.PrincipalType.CUSTOMER;
    boolean admin =
        p.type() == PmartPrincipal.PrincipalType.OPERATOR && p.hasRole(InquiryRules.OPERATOR_ADMIN);
    return owner || admin ? store.find(inquiryId) : Optional.empty();
  }

  /** 모아 보기 — 본사 관리자만. 답변 시각 기준 [from, to), 92일까지. 평균은 남긴 건이 0 이면 비운다(N/A — BO-28). */
  public SummaryView summary(Instant from, Instant to) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "토큰이 필요합니다."));
    if (p.type() != PmartPrincipal.PrincipalType.OPERATOR
        || !p.hasRole(InquiryRules.OPERATOR_ADMIN)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "만족도 모아 보기는 본사 관리자만 봅니다.");
    }
    if (!from.isBefore(to) || Duration.between(from, to).compareTo(MAX_SUMMARY_RANGE) > 0) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED, "기간은 answeredFrom < answeredTo, 92일까지입니다.");
    }
    return new SummaryView(from, to, store.summary(from, to));
  }

  private static boolean mine(PmartPrincipal p, InquiryRow r) {
    return r.customerId().equals(p.subject())
        && InquiryRules.CUSTOMER.equals(r.requesterType())
        && !CsIntakeRules.ORIGIN_STAFF_INTAKE.equals(r.origin());
  }

  private static PmartException conflict(String code, String message) {
    return new PmartException(code, HttpStatus.CONFLICT, message, false, List.of());
  }
}
