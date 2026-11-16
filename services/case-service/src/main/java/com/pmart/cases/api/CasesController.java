package com.pmart.cases.api;

import com.pmart.cases.api.generated.CasesApi;
import com.pmart.cases.api.generated.model.AssignCaseRequest;
import com.pmart.cases.api.generated.model.AssigneeGroup;
import com.pmart.cases.api.generated.model.CaseAllOfClosure;
import com.pmart.cases.api.generated.model.CaseAllOfHistory;
import com.pmart.cases.api.generated.model.CaseKind;
import com.pmart.cases.api.generated.model.CaseNote;
import com.pmart.cases.api.generated.model.CaseState;
import com.pmart.cases.api.generated.model.CaseSummary;
import com.pmart.cases.api.generated.model.CaseSummaryRefsInner;
import com.pmart.cases.api.generated.model.CloseCaseRequest;
import com.pmart.cases.api.generated.model.CreateCaseNoteRequest;
import com.pmart.cases.api.generated.model.CreateCaseRequest;
import com.pmart.cases.api.generated.model.EvidenceReference;
import com.pmart.cases.api.generated.model.ListCaseNotes200Response;
import com.pmart.cases.api.generated.model.ListCases200Response;
import com.pmart.cases.api.generated.model.ListCases200ResponsePage;
import com.pmart.cases.api.generated.model.MaskCaseNoteRequest;
import com.pmart.cases.api.generated.model.ModelCase;
import com.pmart.cases.api.generated.model.Obligation;
import com.pmart.cases.api.generated.model.Priority;
import com.pmart.cases.api.generated.model.ReopenCaseRequest;
import com.pmart.cases.api.generated.model.TransferCaseRequest;
import com.pmart.cases.application.CaseInboxService;
import com.pmart.cases.application.CaseNoteService;
import com.pmart.cases.application.ObligationService;
import com.pmart.cases.domain.CaseRules;
import com.pmart.cases.domain.ObligationRules;
import com.pmart.cases.infrastructure.CaseNoteStore;
import com.pmart.cases.infrastructure.CaseStore.CaseRow;
import com.pmart.common.web.PmartException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 CasesApi — 사건함(0.4.0, 2026-11-16): 목록·상세·오배송 열기·집기·닫기. 규칙은 {@link CaseInboxService}. 아직 없는 하위
 * 기능(이관·재개·의무·행위 요청)은 인터페이스 default 501.
 */
@RestController
public class CasesController implements CasesApi {

  private final CaseInboxService cases;
  private final ObligationService obligations;
  private final CaseNoteService notes;

  public CasesController(
      CaseInboxService cases, ObligationService obligations, CaseNoteService notes) {
    this.cases = cases;
    this.obligations = obligations;
    this.notes = notes;
  }

  @Override
  public ResponseEntity<CaseNote> createCaseNote(
      String caseId, String idempotencyKey, String xSourceSystem, CreateCaseNoteRequest body) {
    CaseNoteStore.NoteRow n = notes.add(idempotencyKey, xSourceSystem, caseId, body.getText());
    return ResponseEntity.created(
            java.net.URI.create("/api/v1/cases/" + caseId + "/notes/" + n.noteId()))
        .body(note(n));
  }

  @Override
  public ResponseEntity<ListCaseNotes200Response> listCaseNotes(String caseId) {
    return ResponseEntity.ok(
        new ListCaseNotes200Response()
            .items(notes.list(caseId).stream().map(CasesController::note).toList()));
  }

  @Override
  public ResponseEntity<CaseNote> maskCaseNote(
      String caseId,
      String noteId,
      String idempotencyKey,
      String xSourceSystem,
      MaskCaseNoteRequest body) {
    return ResponseEntity.ok(
        note(notes.mask(idempotencyKey, xSourceSystem, caseId, noteId, body.getReason())));
  }

  private static CaseNote note(CaseNoteStore.NoteRow n) {
    return new CaseNote()
        .noteId(n.noteId())
        .caseId(n.caseId())
        .text(n.text())
        .authorId(n.authorId())
        .createdAt(odt(n.createdAt()))
        .masked(n.maskedAt() != null)
        .maskedAt(odt(n.maskedAt()))
        .maskedBy(n.maskedBy())
        .maskReason(n.maskReason());
  }

  @Override
  public ResponseEntity<ListCases200Response> listCases(
      @Nullable CaseKind kind,
      @Nullable CaseState state,
      @Nullable String centerId,
      @Nullable String assigneeId,
      @Nullable Priority priority,
      @Nullable String subjectRef,
      @Nullable AssigneeGroup assigneeGroup,
      @Nullable Boolean unassigned,
      @Nullable Boolean overdue,
      Integer size,
      @Nullable String cursor) {
    CaseInboxService.Page page =
        cases.list(
            new CaseInboxService.ListQuery(
                kind == null ? null : kind.getValue(),
                state == null ? null : state.getValue(),
                blankToNull(centerId),
                blankToNull(assigneeId),
                priority == null ? null : priority.getValue(),
                blankToNull(subjectRef),
                assigneeGroup == null ? null : assigneeGroup.getValue(),
                Boolean.TRUE.equals(unassigned),
                Boolean.TRUE.equals(overdue)),
            cursor,
            size);
    Map<String, Integer> open =
        obligations.openCounts(page.items().stream().map(CaseRow::caseId).toList());
    return ResponseEntity.ok(
        new ListCases200Response()
            .items(
                page.items().stream()
                    .map(c -> summary(c, open.getOrDefault(c.caseId(), 0)))
                    .toList())
            .page(
                new ListCases200ResponsePage()
                    .nextCursor(page.nextCursor())
                    .size(page.items().size())));
  }

  @Override
  public ResponseEntity<ModelCase> getCase(String caseId) {
    return ResponseEntity.ok(detail(cases.get(caseId)));
  }

  @Override
  public ResponseEntity<ModelCase> createCase(
      String idempotencyKey, String xSourceSystem, CreateCaseRequest body) {
    if (body.getKind() != CaseKind.MISDELIVERY) {
      throw new PmartException(
          "UNSUPPORTED_KIND",
          HttpStatus.UNPROCESSABLE_CONTENT,
          "직원이 직접 여는 사건은 지금 MISDELIVERY(오배송 확인)뿐입니다.",
          false,
          List.of());
    }
    List<String> evidence =
        body.getEvidence() == null
            ? List.of()
            : body.getEvidence().stream().map(EvidenceReference::getRef).toList();
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            detail(
                cases.createMisdelivery(
                    idempotencyKey,
                    xSourceSystem,
                    new CaseInboxService.MisdeliveryCommand(
                        body.getSubjectRef(), body.getSummary(), body.getCenterId(), evidence))));
  }

  @Override
  public ResponseEntity<ModelCase> assignCase(
      String caseId,
      String idempotencyKey,
      String xSourceSystem,
      AssignCaseRequest body,
      @Nullable Integer xExpectedVersion) {
    return ResponseEntity.ok(
        detail(
            cases.assign(
                idempotencyKey,
                xSourceSystem,
                caseId,
                body.getAssigneeId().strip(),
                xExpectedVersion)));
  }

  @Override
  public ResponseEntity<ModelCase> closeCase(
      String caseId,
      String idempotencyKey,
      String xSourceSystem,
      CloseCaseRequest body,
      @Nullable Integer xExpectedVersion) {
    return ResponseEntity.ok(
        detail(
            cases.close(
                idempotencyKey,
                xSourceSystem,
                caseId,
                body.getResolution().getValue(),
                body.getSummary(),
                xExpectedVersion)));
  }

  @Override
  public ResponseEntity<ModelCase> reopenCase(
      String caseId,
      String idempotencyKey,
      String xSourceSystem,
      ReopenCaseRequest body,
      @Nullable Integer xExpectedVersion) {
    return ResponseEntity.ok(
        detail(
            cases.reopen(
                idempotencyKey, xSourceSystem, caseId, body.getReason(), xExpectedVersion)));
  }

  @Override
  public ResponseEntity<ModelCase> transferCase(
      String caseId,
      String idempotencyKey,
      String xSourceSystem,
      TransferCaseRequest body,
      @Nullable Integer xExpectedVersion) {
    return ResponseEntity.ok(
        detail(
            cases.transfer(
                idempotencyKey,
                xSourceSystem,
                caseId,
                body.getToGroup().getValue(),
                body.getToCenterId(),
                body.getMergeIntoCaseId(),
                body.getReason(),
                xExpectedVersion)));
  }

  private CaseSummary summary(CaseRow c, int openObligations) {
    return new CaseSummary()
        .caseId(c.caseId())
        .kind(CaseKind.fromValue(c.kind()))
        .state(CaseState.fromValue(c.state()))
        .priority(c.priority() == null ? null : Priority.fromValue(c.priority()))
        .subjectRef(c.subjectRef())
        .subjectType(c.subjectType())
        .centerId(c.centerId())
        .assigneeId(c.assigneeId())
        .assigneeGroup(c.assigneeGroup())
        .openObligationCount(openObligations)
        .slaDueAt(odt(c.slaDueAt()))
        .overdue(cases.overdue(c))
        .summary(c.summary())
        .refs(refs(c))
        .openedAt(odt(c.openedAt()))
        .updatedAt(odt(c.updatedAt()));
  }

  private ModelCase detail(CaseInboxService.Detail d) {
    CaseRow c = d.row();
    List<Obligation> obls =
        obligations.forCase(c.caseId()).stream().map(ObligationsController::toModel).toList();
    ModelCase m =
        new ModelCase()
            .caseId(c.caseId())
            .kind(CaseKind.fromValue(c.kind()))
            .state(CaseState.fromValue(c.state()))
            .priority(c.priority() == null ? null : Priority.fromValue(c.priority()))
            .subjectRef(c.subjectRef())
            .subjectType(c.subjectType())
            .centerId(c.centerId())
            .assigneeId(c.assigneeId())
            .assigneeGroup(c.assigneeGroup())
            .openObligationCount(
                (int)
                    obls.stream()
                        .filter(
                            o ->
                                o.getState() != null
                                    && ObligationRules.OPEN_STATES.contains(
                                        o.getState().getValue()))
                        .count())
            .slaDueAt(odt(c.slaDueAt()))
            .overdue(d.overdue())
            .summary(c.summary())
            .refs(refs(c))
            .openedAt(odt(c.openedAt()))
            .updatedAt(odt(c.updatedAt()))
            .version(c.version())
            .sourceEventId(c.sourceEventId())
            .subCaseIds(List.of())
            .mergedFromCaseIds(List.of())
            .obligations(obls)
            .actionRequests(List.of())
            .evidence(List.of())
            .history(
                d.history().stream()
                    .map(
                        h ->
                            new CaseAllOfHistory()
                                .at(odt(h.at()))
                                .actorId(h.actorId())
                                .action(h.action())
                                .fromState(h.fromState())
                                .toState(h.toState())
                                .note(h.note()))
                    .toList());
    if (c.closedAt() != null) {
      m.closure(
          new CaseAllOfClosure()
              .resolution(c.resolution())
              .summary(c.closureSummary())
              .closedBy(c.closedBy())
              .closedAt(odt(c.closedAt())));
    }
    return m;
  }

  private static List<CaseSummaryRefsInner> refs(CaseRow c) {
    return c.refs().stream()
        .map(r -> new CaseSummaryRefsInner().refType(r.refType()).refId(r.refId()))
        .toList();
  }

  private static @Nullable OffsetDateTime odt(@Nullable Instant i) {
    return i == null ? null : i.atOffset(ZoneOffset.UTC);
  }

  private static @Nullable String blankToNull(@Nullable String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }

  /** 계약 종류 enum 과 규칙의 종류 이름이 같은지(컴파일 때 확인용 참조). */
  static final String MISDELIVERY = CaseRules.MISDELIVERY;
}
