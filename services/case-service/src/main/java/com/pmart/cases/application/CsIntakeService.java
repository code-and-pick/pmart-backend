package com.pmart.cases.application;

import com.pmart.cases.domain.CaseRules;
import com.pmart.cases.domain.CsIntakeRules;
import com.pmart.cases.domain.InquiryRules;
import com.pmart.cases.infrastructure.CaseStore;
import com.pmart.cases.infrastructure.CaseStore.CaseRow;
import com.pmart.cases.infrastructure.CsIntakeStore;
import com.pmart.cases.infrastructure.CsIntakeStore.IntakeRow;
import com.pmart.cases.infrastructure.InquiryStore;
import com.pmart.cases.infrastructure.InquiryStore.InquiryRow;
import com.pmart.cases.infrastructure.OrderClient;
import com.pmart.cases.infrastructure.OrderClient.StaffOrder;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * CS 접수(계약 0.12.0 — 2026-11-16 사용자 결정 BO-11 "추천대로"). 상담원이 전화·채팅·메일로 받은 고객 연락을 남긴다.
 *
 * <ul>
 *   <li>접수 ≠ 명령 — 취소·환불을 하지 않는다. 동의는 기록만.
 *   <li>회원만(C2) — customerRef 필수. 주문을 주면 직원 주문 조회로 그 고객 주문인지 본다.
 *   <li>본인 확인은 방법만 기록, 대리는 표시만(대리 정책 없음 — 서버가 클레임을 막지 않는다).
 *   <li>새 건: 고객 문의(origin STAFF_INTAKE — 고객 앱에 안 보이고 답변 알림 없음). 기존 건 연결: 이력 CS_INTAKE_LINKED.
 *   <li>요약·메모는 로그에 남기지 않는다. 이벤트 없음.
 * </ul>
 */
@Service
public class CsIntakeService {

  private static final Logger log = LoggerFactory.getLogger(CsIntakeService.class);

  public record Create(
      String channel,
      String category,
      String customerRef,
      @Nullable String subjectRef,
      String summary,
      String identityMethod,
      @Nullable String identityNote,
      String callerRelation,
      @Nullable Consent consent,
      @Nullable String linkCaseId) {}

  public record Consent(
      String consentType, boolean given, @Nullable String detail, @Nullable Instant recordedAt) {}

  public record Page(List<IntakeRow> items, @Nullable String nextCursor) {}

  private final CsIntakeStore store;
  private final InquiryStore inquiries;
  private final CaseStore cases;
  private final CaseInboxService inbox;
  private final OrderClient orders;
  private final CaseProperties props;
  private final RequestIdempotency idempotency;
  private final Clock clock;

  public CsIntakeService(
      CsIntakeStore store,
      InquiryStore inquiries,
      CaseStore cases,
      CaseInboxService inbox,
      OrderClient orders,
      CaseProperties props,
      RequestIdempotency idempotency,
      Clock clock) {
    this.store = store;
    this.inquiries = inquiries;
    this.cases = cases;
    this.inbox = inbox;
    this.orders = orders;
    this.props = props;
    this.idempotency = idempotency;
    this.clock = clock;
  }

  public IntakeRow create(String requestId, String sourceSystem, Create cmd) {
    PmartPrincipal p = writer();
    String subjectRef = blankToNull(cmd.subjectRef());
    String rejected =
        CsIntakeRules.rejection(
            cmd.channel(), cmd.category(), subjectRef, cmd.identityMethod(), cmd.identityNote());
    if (rejected != null) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, rejected);
    }
    if (cmd.customerRef() == null || cmd.customerRef().isBlank()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "customerRef(고객 계정)가 필요합니다.");
    }
    String customerId = cmd.customerRef().strip();
    String linkCaseId = blankToNull(cmd.linkCaseId());
    if (linkCaseId != null) {
      linkable(linkCaseId); // 못 보면 404 — 트랜잭션 밖 선검사, 안에서 다시 본다
    }
    // 외부 호출은 트랜잭션 밖. 0.19.2: order 장애(연결 실패·결과 모름)면 바로 503 이 아니라 멱등 처리로 넘긴다 —
    // 같은 키로 이미 남긴 요청이면 저장된 결과를 재생하고, 새 요청이면 그때 503(아래 action 안에서 던짐 → 기록도 롤백)
    PmartException orderDown = null;
    if (subjectRef != null) {
      try {
        checkOrder(subjectRef, customerId);
      } catch (PmartException e) {
        if (!ErrorCode.DEPENDENCY_UNAVAILABLE.name().equals(e.code())
            && !ErrorCode.UNKNOWN_RESULT.name().equals(e.code())) {
          throw e;
        }
        orderDown = e;
      }
    }
    PmartException pendingOrderFailure = orderDown;
    String id =
        idempotency
            .execute(
                new RequestKey(sourceSystem, requestId, "createCsIntake", p.subject()),
                cmd,
                String.class,
                () -> {
                  if (pendingOrderFailure != null) {
                    throw pendingOrderFailure;
                  }
                  Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
                  String intakeId = "CSI-" + UUID.randomUUID();
                  String caseId;
                  String inquiryId = null;
                  if (linkCaseId != null) {
                    caseId = linkable(linkCaseId).caseId();
                    cases.history(
                        caseId,
                        now,
                        p.subject(),
                        CsIntakeRules.HISTORY_LINKED,
                        null,
                        null,
                        intakeId + " " + cmd.channel() + "/" + cmd.callerRelation());
                  } else {
                    caseId = "CASE-" + UUID.randomUUID();
                    inquiryId = "INQ-" + UUID.randomUUID();
                    inquiries.insert(
                        new InquiryRow(
                            inquiryId,
                            caseId,
                            customerId,
                            cmd.category(),
                            subjectRef,
                            null,
                            cmd.summary().strip(),
                            CsIntakeRules.ORIGIN_STAFF_INTAKE,
                            InquiryRules.RECEIVED,
                            now,
                            null,
                            0,
                            null,
                            null,
                            InquiryRules.CUSTOMER,
                            null,
                            null,
                            null));
                  }
                  Consent c = cmd.consent();
                  store.insert(
                      new IntakeRow(
                          intakeId,
                          caseId,
                          inquiryId,
                          linkCaseId != null,
                          cmd.channel(),
                          cmd.category(),
                          customerId,
                          subjectRef,
                          cmd.summary().strip(),
                          cmd.callerRelation(),
                          cmd.identityMethod(),
                          blankToNull(cmd.identityNote()),
                          c == null ? null : c.consentType(),
                          c == null ? null : c.given(),
                          c == null ? null : blankToNull(c.detail()),
                          c == null ? null : (c.recordedAt() == null ? now : c.recordedAt()),
                          p.subject(),
                          now));
                  log.info(
                      "cs intake {} case={} channel={} caller={} linked={}",
                      intakeId,
                      caseId,
                      cmd.channel(),
                      cmd.callerRelation(),
                      linkCaseId != null);
                  return intakeId;
                })
            .value();
    return store.find(id).orElseThrow();
  }

  public IntakeRow get(String intakeId) {
    writer();
    return store
        .find(intakeId)
        .orElseThrow(
            () -> PmartException.notFound("INTAKE_NOT_FOUND", "접수를 찾을 수 없습니다: " + intakeId));
  }

  public Page list(
      @Nullable String caseId,
      @Nullable String customerRef,
      @Nullable String subjectRef,
      int size,
      @Nullable String cursor) {
    writer();
    caseId = blankToNull(caseId);
    customerRef = blankToNull(customerRef);
    subjectRef = blankToNull(subjectRef);
    if (caseId == null && customerRef == null && subjectRef == null) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED, "caseId·customerRef·subjectRef 중 하나는 있어야 합니다.");
    }
    List<IntakeRow> rows = store.page(caseId, customerRef, subjectRef, blankToNull(cursor), size);
    boolean more = rows.size() > size;
    List<IntakeRow> items = more ? rows.subList(0, size) : rows;
    return new Page(List.copyOf(items), more ? items.get(items.size() - 1).intakeId() : null);
  }

  /** 연결할 수 있는 건 — 고객 문의 건(상담원·관리자는 모두 봄) 또는 지금 직원이 사건함에서 볼 수 있는 건. */
  private CaseRow linkable(String caseId) {
    CaseRow c =
        cases
            .find(caseId)
            .orElseThrow(
                () -> PmartException.notFound("CASE_NOT_FOUND", "접수 건을 찾을 수 없습니다: " + caseId));
    if (CaseRules.CS_INQUIRY.equals(c.kind())) {
      return c;
    }
    return inbox.visibleCase(caseId);
  }

  private void checkOrder(String orderId, String customerId) {
    if (props.orderServiceBaseUrl() == null) {
      throw PmartException.configRequired("pmart.cases.order-service-base-url");
    }
    StaffOrder o = orders.staffOrder(orderId);
    if (o instanceof StaffOrder.Forbidden) {
      throw new PmartException(ErrorCode.FORBIDDEN, "주문을 볼 권한이 없습니다.");
    }
    if (o instanceof StaffOrder.NotFound) {
      throw new PmartException(
          "ORDER_NOT_FOUND", HttpStatus.UNPROCESSABLE_CONTENT, "주문을 찾을 수 없습니다.", false, List.of());
    }
    if (o instanceof StaffOrder.Found f && !f.customerId().equals(customerId)) {
      throw new PmartException(
          "CUSTOMER_ORDER_MISMATCH",
          HttpStatus.UNPROCESSABLE_CONTENT,
          "그 고객의 주문이 아닙니다. 고객 계정과 주문 번호를 다시 확인하세요.",
          false,
          List.of());
    }
  }

  private static PmartPrincipal writer() {
    PmartPrincipal p =
        PmartPrincipal.current()
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "토큰이 필요합니다."));
    if (p.type() != PmartPrincipal.PrincipalType.OPERATOR
        || CsIntakeRules.WRITERS.stream().noneMatch(p::hasRole)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "CS 접수는 상담원(CS_AGENT)·본사 관리자만 다룹니다.");
    }
    return p;
  }

  private static @Nullable String blankToNull(@Nullable String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }
}
