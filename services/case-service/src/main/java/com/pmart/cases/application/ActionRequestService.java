package com.pmart.cases.application;

import com.pmart.cases.domain.ActionRequestRules;
import com.pmart.cases.domain.CaseRules;
import com.pmart.cases.infrastructure.ActionRequestStore;
import com.pmart.cases.infrastructure.ActionRequestStore.ActionRow;
import com.pmart.cases.infrastructure.CaseStore;
import com.pmart.cases.infrastructure.CaseStore.CaseRow;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 행위 요청 기록(0.17.0 — 2026-11-17 사용자 "기록만 먼저", 업무 원문 BackOffice §2 ActionRequest). case-service 는 다른
 * 서비스를 부르지 않는다 — 직원이 처리 대기·각 메뉴에서 한 요청과 그 결과(상태·결과 ID)를 접수 건에 남긴다.
 *
 * <ul>
 *   <li>남기기·결과: 그 접수 건을 다룰 수 있는 사람(집기·닫기와 같은 {@link CaseRules#canWork}). 닫힌 건에는 새 요청을 남기지 않는다.
 *   <li>보상 실행 기록은 승인 번호 필수(APPROVAL_REQUIRED), "강제·덮어쓰기" 명령은 받지 않음(UNSUPPORTED_ACTION).
 *   <li>APPLIED·REJECTED 뒤에는 결과를 더 남기지 않는다(다시 하려면 새 요청). 이벤트·알림 없음, 메모는 로그에 남기지 않는다.
 * </ul>
 */
@Service
public class ActionRequestService {

  private static final Logger log = LoggerFactory.getLogger(ActionRequestService.class);

  public record Create(
      String actionType,
      String targetService,
      String command,
      @Nullable String targetRef,
      @Nullable String idempotencyKey,
      @Nullable String approvalRef,
      @Nullable Map<String, Object> payloadSummary) {}

  public record Result(
      String state,
      @Nullable String resultSystem,
      @Nullable String resultRef,
      @Nullable String resultKind,
      @Nullable String note) {}

  private final ActionRequestStore store;
  private final CaseStore cases;
  private final CaseInboxService inbox;
  private final RequestIdempotency idempotency;
  private final Clock clock;

  public ActionRequestService(
      ActionRequestStore store,
      CaseStore cases,
      CaseInboxService inbox,
      RequestIdempotency idempotency,
      Clock clock) {
    this.store = store;
    this.cases = cases;
    this.inbox = inbox;
    this.idempotency = idempotency;
    this.clock = clock;
  }

  public ActionRow create(
      String requestId,
      String sourceSystem,
      String caseId,
      Create cmd,
      @Nullable Integer expectedCaseVersion) {
    PmartPrincipal p = operator();
    Set<String> roles = new HashSet<>(p.roles());
    String rejected =
        ActionRequestRules.rejection(cmd.actionType(), cmd.command(), cmd.approvalRef());
    if (rejected != null) {
      throw unprocessable(
          rejected,
          "APPROVAL_REQUIRED".equals(rejected)
              ? "승인된 보상 실행은 승인 번호(approvalRef)가 필요합니다."
              : "이 행위는 기록하지 않습니다(강제 성공·덮어쓰기 종류 또는 없는 종류).");
    }
    String id =
        idempotency
            .execute(
                new RequestKey(
                    sourceSystem, requestId, "createActionRequest:" + caseId, p.subject()),
                cmd,
                String.class,
                () -> {
                  CaseRow c = inbox.lockVisibleCase(caseId);
                  if (expectedCaseVersion != null && expectedCaseVersion != c.version()) {
                    throw PmartException.versionConflict(
                        "다른 변경이 먼저 반영되었습니다(현재 " + c.version() + ").");
                  }
                  if (!CaseRules.canWork(roles, c.kind(), c.assigneeGroup())) {
                    throw new PmartException(ErrorCode.FORBIDDEN, "이 접수 건에 행위 요청을 남길 권한이 없습니다.");
                  }
                  if ("CLOSED".equals(c.state())) {
                    throw PmartException.invalidState("처리 끝난 건입니다 — 다시 처리한 뒤 남기세요.");
                  }
                  Instant now = now();
                  String arId = "ACR-" + UUID.randomUUID();
                  store.insert(
                      new ActionRow(
                          arId,
                          caseId,
                          cmd.actionType(),
                          cmd.targetService().strip(),
                          cmd.command().strip(),
                          blankToNull(cmd.targetRef()),
                          blankToNull(cmd.idempotencyKey()),
                          blankToNull(cmd.approvalRef()),
                          cmd.payloadSummary(),
                          ActionRequestRules.REQUESTED,
                          null,
                          null,
                          null,
                          null,
                          p.subject(),
                          now,
                          null,
                          null,
                          0));
                  cases.history(
                      caseId,
                      now,
                      p.subject(),
                      "ACTION_REQUESTED",
                      null,
                      null,
                      arId
                          + " "
                          + cmd.actionType()
                          + " → "
                          + cmd.targetService().strip()
                          + "."
                          + cmd.command().strip());
                  log.info(
                      "action request {} case={} type={} target={}",
                      arId,
                      caseId,
                      cmd.actionType(),
                      cmd.targetService());
                  return arId;
                })
            .value();
    return store.find(caseId, id).orElseThrow();
  }

  /** 결과 남기기 — 상태·결과 ID. 끝난 요청 409 INVALID_STATE. */
  public ActionRow recordResult(
      String requestId, String sourceSystem, String caseId, String actionRequestId, Result r) {
    PmartPrincipal p = operator();
    Set<String> roles = new HashSet<>(p.roles());
    if (!ActionRequestRules.RESULT_STATES.contains(r.state())) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED,
          "state 는 SENT·ACCEPTED·APPLIED·REJECTED·UNKNOWN_RESULT 입니다.");
    }
    if (blankToNull(r.resultSystem()) == null ^ blankToNull(r.resultRef()) == null) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED, "결과 ID 는 resultSystem·resultRef 를 같이 보냅니다.");
    }
    idempotency.execute(
        new RequestKey(
            sourceSystem, requestId, "recordActionResult:" + actionRequestId, p.subject()),
        r,
        String.class,
        () -> {
          CaseRow c = inbox.lockVisibleCase(caseId);
          if (!CaseRules.canWork(roles, c.kind(), c.assigneeGroup())) {
            throw new PmartException(ErrorCode.FORBIDDEN, "이 접수 건의 행위 요청 결과를 남길 권한이 없습니다.");
          }
          ActionRow a =
              store.lock(caseId, actionRequestId).orElseThrow(() -> notFound(actionRequestId));
          if (!ActionRequestRules.canRecordResult(a.state())) {
            throw PmartException.invalidState("끝난 요청입니다(" + a.state() + ") — 다시 하려면 새 요청을 남기세요.");
          }
          Instant now = now();
          store.recordResult(
              actionRequestId,
              r.state(),
              blankToNull(r.resultSystem()),
              blankToNull(r.resultRef()),
              blankToNull(r.resultKind()),
              blankToNull(r.note()),
              p.subject(),
              now);
          cases.history(
              caseId,
              now,
              p.subject(),
              "ACTION_RESULT_RECORDED",
              a.state(),
              r.state(),
              actionRequestId
                  + (blankToNull(r.resultRef()) == null
                      ? ""
                      : " " + r.resultSystem().strip() + ":" + r.resultRef().strip()));
          return actionRequestId;
        });
    return store.find(caseId, actionRequestId).orElseThrow();
  }

  public ActionRow get(String caseId, String actionRequestId) {
    operator();
    inbox.visibleCase(caseId);
    return store.find(caseId, actionRequestId).orElseThrow(() -> notFound(actionRequestId));
  }

  /** 접수 건 상세에 붙일 것(상세 접근은 이미 확인됨). */
  public List<ActionRow> forCase(String caseId) {
    return store.forCase(caseId);
  }

  private static PmartPrincipal operator() {
    return PmartPrincipal.current()
        .filter(x -> x.type() == PmartPrincipal.PrincipalType.OPERATOR)
        .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "직원만 행위 요청을 남깁니다."));
  }

  private static PmartException notFound(String id) {
    return PmartException.notFound("ACTION_REQUEST_NOT_FOUND", "행위 요청을 찾을 수 없습니다: " + id);
  }

  private static PmartException unprocessable(String code, String message) {
    return new PmartException(code, HttpStatus.UNPROCESSABLE_CONTENT, message, false, List.of());
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  private static @Nullable String blankToNull(@Nullable String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }
}
