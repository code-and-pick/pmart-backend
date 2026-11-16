package com.pmart.cases.application;

import com.pmart.cases.domain.CaseRules;
import com.pmart.cases.infrastructure.CaseNoteStore;
import com.pmart.cases.infrastructure.CaseNoteStore.NoteRow;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 접수 건 메모(2026-11-16 사용자 "고친 안" — 계약 0.10.0, 업무 원문 BO-27 "전임의 늦은 보고는 버리지 않고 원사건으로").
 *
 * <ul>
 *   <li>남기기: 그 건을 볼 수 있는 본사 직원(관리자·지원센터·상담원). 센터 관리자는 아직 안 됨. 처리 끝난 건에도 — 상태는 그대로.
 *   <li>고치기·지우기 없음. 관리자 가림(사유 필수) — 글만 지우고 누가·언제·왜는 남긴다(ADR-0062).
 *   <li>이벤트 없음, 글은 로그에 남기지 않는다.
 * </ul>
 */
@Service
public class CaseNoteService {

  /** 메모를 남길 수 있는 본사 역할. */
  static final List<String> WRITERS =
      List.of(CaseRules.ADMIN, CaseRules.RIDER_OPS, CaseRules.CS_AGENT);

  private static final Logger log = LoggerFactory.getLogger(CaseNoteService.class);

  private final CaseNoteStore store;
  private final CaseInboxService inbox;
  private final RequestIdempotency idempotency;
  private final Clock clock;

  public CaseNoteService(
      CaseNoteStore store, CaseInboxService inbox, RequestIdempotency idempotency, Clock clock) {
    this.store = store;
    this.inbox = inbox;
    this.idempotency = idempotency;
    this.clock = clock;
  }

  public NoteRow add(String requestId, String sourceSystem, String caseId, String text) {
    PmartPrincipal p = operator();
    inbox.visibleCase(caseId); // 못 보면 404
    if (WRITERS.stream().noneMatch(p::hasRole)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "메모는 본사 직원(관리자·지원센터·상담원)만 남깁니다.");
    }
    if (text == null || text.isBlank()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "text(메모)가 필요합니다.");
    }
    String id =
        idempotency
            .execute(
                new RequestKey(sourceSystem, requestId, "createCaseNote:" + caseId, p.subject()),
                text,
                String.class,
                () -> {
                  String noteId = "NOTE-" + UUID.randomUUID();
                  store.insert(
                      new NoteRow(
                          noteId, caseId, text.strip(), p.subject(), now(), null, null, null));
                  log.info("메모 {} 사건 {} by {}", noteId, caseId, p.subject());
                  return noteId;
                })
            .value();
    return store.find(id).orElseThrow();
  }

  public List<NoteRow> list(String caseId) {
    operator();
    inbox.visibleCase(caseId);
    return store.forCase(caseId);
  }

  public NoteRow mask(
      String requestId, String sourceSystem, String caseId, String noteId, String reason) {
    PmartPrincipal p = operator();
    inbox.visibleCase(caseId);
    if (!p.hasRole(CaseRules.ADMIN)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "메모 가림은 관리자만 합니다.");
    }
    if (reason == null || reason.isBlank()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "reason(가림 사유)이 필요합니다.");
    }
    idempotency.execute(
        new RequestKey(sourceSystem, requestId, "maskCaseNote:" + noteId, p.subject()),
        reason,
        String.class,
        () -> {
          NoteRow n =
              store
                  .lock(caseId, noteId)
                  .orElseThrow(
                      () -> PmartException.notFound("NOTE_NOT_FOUND", "메모를 찾을 수 없습니다: " + noteId));
          if (n.maskedAt() == null) {
            store.mask(noteId, p.subject(), reason.strip(), now());
            log.info("메모 가림 {} by {}", noteId, p.subject());
          }
          return noteId;
        });
    return store.find(noteId).orElseThrow();
  }

  private static PmartPrincipal operator() {
    return PmartPrincipal.current()
        .filter(x -> x.type() == PmartPrincipal.PrincipalType.OPERATOR)
        .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "직원만 메모를 다룹니다."));
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }
}
