package com.pmart.cases.application;

import com.pmart.cases.domain.CsIntakeRules;
import com.pmart.cases.domain.InquiryRules;
import com.pmart.cases.infrastructure.CaseStore;
import com.pmart.cases.infrastructure.InquiryClosureStore;
import com.pmart.cases.infrastructure.InquiryClosureStore.ClosureRow;
import com.pmart.cases.infrastructure.InquirySatisfactionStore;
import com.pmart.cases.infrastructure.InquirySatisfactionStore.SatisfactionRow;
import com.pmart.cases.infrastructure.InquiryStore;
import com.pmart.cases.infrastructure.InquiryStore.InquiryRow;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 문의 닫기(0.19.0 — 2026-11-17 사용자 "그 표대로 진행해, 다시 열기는 빼고", 업무 원문 Commerce §14.2-5).
 *
 * <ul>
 *   <li>닫는 사람: 문의를 남긴 고객·라이더 본인(상담 종료 확인), 또는 답변 역할 직원(고객 문의 = CS_AGENT, 라이더 문의 = RIDER_OPS — 관리자는
 *       보기만, 2026-11-16 결정 유지). 직원은 사유 필수(2~500자) — 사유는 직원에게만 보인다. 상담원이 남긴 문의(STAFF_INTAKE)는 직원만.
 *   <li>닫을 때 만족도(선택, 고객만): 답변이 있을 때만(없으면 400), 이미 남겼으면 409 ALREADY_RATED. 닫은 뒤 따로 남기기도 그대로 된다.
 *   <li>닫힘은 문의와 그 접수 건 행만 — 주문·클레임·다른 접수 건은 그대로(원문 "상담 종료 ≠ 거래 종결"). 자동 닫기·다시 열기 없음, 알림 없음.
 *   <li>사유·의견은 로그에 남기지 않는다.
 * </ul>
 */
@Service
public class InquiryCloseService {

  private static final Logger log = LoggerFactory.getLogger(InquiryCloseService.class);

  public static final String STAFF = "STAFF";

  private final InquiryStore inquiries;
  private final InquiryClosureStore closures;
  private final InquirySatisfactionStore satisfaction;
  private final CaseStore cases;
  private final InquiryService inquiryService;
  private final RequestIdempotency idempotency;
  private final Clock clock;

  public InquiryCloseService(
      InquiryStore inquiries,
      InquiryClosureStore closures,
      InquirySatisfactionStore satisfaction,
      CaseStore cases,
      InquiryService inquiryService,
      RequestIdempotency idempotency,
      Clock clock) {
    this.inquiries = inquiries;
    this.closures = closures;
    this.satisfaction = satisfaction;
    this.cases = cases;
    this.inquiryService = inquiryService;
    this.idempotency = idempotency;
    this.clock = clock;
  }

  public InquiryService.View close(
      String requestId,
      String sourceSystem,
      String inquiryId,
      @Nullable String reason,
      @Nullable Integer score,
      @Nullable String comment) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "토큰이 필요합니다."));
    boolean staff = p.type() == PmartPrincipal.PrincipalType.OPERATOR;
    if (!staff
        && p.type() != PmartPrincipal.PrincipalType.CUSTOMER
        && p.type() != PmartPrincipal.PrincipalType.RIDER) {
      throw new PmartException(ErrorCode.FORBIDDEN, "문의를 닫을 수 없습니다.");
    }
    String memo = reason == null || reason.isBlank() ? null : reason.strip();
    if (staff) {
      if (memo == null || memo.length() < 2 || memo.length() > 500) {
        throw new PmartException(ErrorCode.VALIDATION_FAILED, "직원이 닫을 때는 사유(2~500자)가 필요합니다.");
      }
      if (score != null) {
        throw new PmartException(ErrorCode.VALIDATION_FAILED, "만족도는 고객만 남깁니다.");
      }
    } else {
      memo = null; // 고객·라이더가 보낸 사유는 남기지 않는다(직원 사유 칸)
    }
    String text = comment == null || comment.isBlank() ? null : comment.strip();
    if (score != null && (score < 1 || score > 5)) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "점수는 1~5 입니다.");
    }
    if (text != null && (score == null || text.length() > InquirySatisfactionService.MAX_COMMENT)) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "의견은 점수와 함께, 200자까지입니다.");
    }
    String note = memo;
    Map<String, Object> fingerprint = new LinkedHashMap<>();
    fingerprint.put("reason", note == null ? "" : note);
    fingerprint.put("score", score == null ? 0 : score);
    fingerprint.put("comment", text == null ? "" : text);
    idempotency.execute(
        new RequestKey(sourceSystem, requestId, "closeInquiry:" + inquiryId, p.subject()),
        fingerprint,
        String.class,
        () -> {
          InquiryRow row = inquiries.lock(inquiryId).orElseThrow(() -> notFound(inquiryId));
          String byType;
          if (staff) {
            if (!p.hasRole(InquiryRules.answerRole(row.requesterType()))) {
              throw new PmartException(
                  ErrorCode.FORBIDDEN,
                  InquiryRules.RIDER.equals(row.requesterType())
                      ? "라이더 문의는 지원센터(RIDER_OPS)가 닫습니다."
                      : "고객 문의는 상담원(CS_AGENT)이 닫습니다.");
            }
            byType = STAFF;
          } else {
            String mine =
                p.type() == PmartPrincipal.PrincipalType.RIDER
                    ? InquiryRules.RIDER
                    : InquiryRules.CUSTOMER;
            if (!row.customerId().equals(p.subject())
                || !mine.equals(row.requesterType())
                || CsIntakeRules.ORIGIN_STAFF_INTAKE.equals(row.origin())) {
              throw notFound(inquiryId);
            }
            byType = mine;
          }
          if (InquiryRules.CLOSED.equals(row.status())) {
            throw conflict("INQUIRY_CLOSED", "이미 닫힌 문의입니다.");
          }
          Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
          if (score != null) {
            if (!InquiryRules.CUSTOMER.equals(row.requesterType()) || row.answeredAt() == null) {
              throw new PmartException(
                  ErrorCode.VALIDATION_FAILED, "만족도는 답변을 받은 고객 문의에만 남길 수 있습니다.");
            }
            if (satisfaction.find(inquiryId).isPresent()) {
              throw conflict("ALREADY_RATED", "이미 만족도를 남겼습니다.");
            }
            satisfaction.insert(new SatisfactionRow(inquiryId, p.subject(), score, text, now));
          }
          closures.close(
              inquiryId, row.caseId(), new ClosureRow(inquiryId, p.subject(), byType, note, now));
          cases.history(
              row.caseId(),
              now,
              p.subject(),
              "INQUIRY_CLOSED",
              row.status(),
              InquiryRules.CLOSED,
              note == null ? byType : byType + ": " + note);
          log.info("inquiry {} closed by {}", inquiryId, byType);
          return inquiryId;
        });
    return inquiryService.get(inquiryId);
  }

  /** 상세에 붙일 닫기 기록 — 사유는 직원에게만(고객·라이더에게는 빈 값). 상세 접근은 이미 확인됨. */
  public Optional<ClosureRow> forViewer(String inquiryId) {
    boolean staff =
        PmartPrincipal.current()
            .map(x -> x.type() == PmartPrincipal.PrincipalType.OPERATOR)
            .orElse(false);
    return closures
        .find(inquiryId)
        .map(
            c ->
                staff
                    ? c
                    : new ClosureRow(c.inquiryId(), "", c.closedByType(), null, c.closedAt()));
  }

  private static PmartException notFound(String inquiryId) {
    return PmartException.notFound("INQUIRY_NOT_FOUND", "문의를 찾을 수 없습니다: " + inquiryId);
  }

  private static PmartException conflict(String code, String message) {
    return new PmartException(code, HttpStatus.CONFLICT, message, false, List.of());
  }
}
