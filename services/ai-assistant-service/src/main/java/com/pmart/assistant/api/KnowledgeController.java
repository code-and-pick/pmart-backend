package com.pmart.assistant.api;

import com.pmart.assistant.api.generated.KnowledgeApi;
import com.pmart.assistant.api.generated.model.KnowledgeApplication;
import com.pmart.assistant.api.generated.model.KnowledgeApplicationList;
import com.pmart.assistant.api.generated.model.KnowledgeDocumentDetail;
import com.pmart.assistant.api.generated.model.KnowledgeDocumentList;
import com.pmart.assistant.api.generated.model.KnowledgeDocumentSummary;
import com.pmart.assistant.api.generated.model.KnowledgeDraft;
import com.pmart.assistant.api.generated.model.KnowledgeDraftCreate;
import com.pmart.assistant.api.generated.model.KnowledgeDraftCurrent;
import com.pmart.assistant.api.generated.model.KnowledgeDraftList;
import com.pmart.assistant.api.generated.model.KnowledgeDraftUpdate;
import com.pmart.assistant.api.generated.model.KnowledgeIndexState;
import com.pmart.assistant.api.generated.model.KnowledgeReindex;
import com.pmart.assistant.api.generated.model.KnowledgeVersion;
import com.pmart.assistant.application.KnowledgeDocumentService;
import com.pmart.assistant.application.KnowledgeDocumentService.DocumentView;
import com.pmart.assistant.application.KnowledgeDocumentService.DraftView;
import com.pmart.assistant.infrastructure.KnowledgeStore.ApplicationRow;
import com.pmart.assistant.infrastructure.KnowledgeStore.ReindexRow;
import com.pmart.assistant.infrastructure.KnowledgeStore.VersionRow;
import com.pmart.common.security.PmartPrincipal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 KnowledgeApi 구현(ADR-0092). 변환만 — 권한·형식·판 규칙은 {@link KnowledgeDocumentService}. */
@RestController
public class KnowledgeController implements KnowledgeApi {

  private final KnowledgeDocumentService service;

  public KnowledgeController(KnowledgeDocumentService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<KnowledgeDocumentList> listKnowledgeDocuments(
      String xSourceSystem, @Nullable Boolean includeRetired) {
    var o = service.list(principal(), xSourceSystem, Boolean.TRUE.equals(includeRetired));
    return ResponseEntity.ok(
        new KnowledgeDocumentList()
            .items(o.documents().stream().map(KnowledgeController::summary).toList())
            .source(KnowledgeDocumentList.SourceEnum.fromValue(o.dbSource() ? "db" : "files"))
            .reindex(reindex(o.reindex())));
  }

  @Override
  public ResponseEntity<KnowledgeDocumentDetail> getKnowledgeDocument(
      String xSourceSystem, String documentId, @Nullable Integer version) {
    var d = service.get(principal(), xSourceSystem, documentId, version);
    return ResponseEntity.ok(
        new KnowledgeDocumentDetail()
            .document(summary(d.view()))
            .content(d.content())
            .shownVersion(d.shownVersion())
            .versions(d.versions().stream().map(KnowledgeController::version).toList()));
  }

  @Override
  public ResponseEntity<KnowledgeApplicationList> listKnowledgeApplications(
      String xSourceSystem, @Nullable Integer size) {
    return ResponseEntity.ok(
        new KnowledgeApplicationList()
            .items(
                service.applications(principal(), xSourceSystem, size == null ? 20 : size).stream()
                    .map(KnowledgeController::application)
                    .toList()));
  }

  @Override
  public ResponseEntity<KnowledgeDraftList> listMyKnowledgeDrafts(String xSourceSystem) {
    return ResponseEntity.ok(
        new KnowledgeDraftList()
            .items(
                service.myDrafts(principal(), xSourceSystem).stream()
                    .map(KnowledgeController::draft)
                    .toList()));
  }

  @Override
  public ResponseEntity<KnowledgeDraft> createKnowledgeDraft(
      String idempotencyKey, String xSourceSystem, KnowledgeDraftCreate body) {
    var v =
        service.createDraft(
            principal(),
            xSourceSystem,
            idempotencyKey,
            body.getDocumentId(),
            body.getOp().getValue(),
            body.getBaseVersion(),
            body.getContent());
    return ResponseEntity.status(HttpStatus.CREATED).body(draft(v));
  }

  @Override
  public ResponseEntity<KnowledgeDraft> getKnowledgeDraft(String xSourceSystem, String draftId) {
    return ResponseEntity.ok(draft(service.getDraft(principal(), xSourceSystem, draftId)));
  }

  @Override
  public ResponseEntity<KnowledgeDraft> updateKnowledgeDraft(
      String idempotencyKey, String xSourceSystem, String draftId, KnowledgeDraftUpdate body) {
    return ResponseEntity.ok(
        draft(service.updateDraft(principal(), xSourceSystem, draftId, body.getContent())));
  }

  @Override
  public ResponseEntity<Void> discardKnowledgeDraft(
      String idempotencyKey, String xSourceSystem, String draftId) {
    service.discardDraft(principal(), xSourceSystem, draftId);
    return ResponseEntity.noContent().build();
  }

  // ---- 변환 ----

  private static @Nullable PmartPrincipal principal() {
    return PmartPrincipal.current().orElse(null);
  }

  static KnowledgeDocumentSummary summary(DocumentView v) {
    var s = v.summary();
    return new KnowledgeDocumentSummary()
        .documentId(s.documentId())
        .domain(s.domain())
        .title(s.title())
        .version(s.version())
        .contentSha256(s.contentSha256())
        .retired(s.retired())
        .readOnly(s.readOnly())
        .indexState(KnowledgeIndexState.fromValue(v.indexState().name()))
        .updatedAt(odt(s.updatedAt()))
        .updatedBy(s.updatedBy());
  }

  static KnowledgeReindex reindex(ReindexRow r) {
    return new KnowledgeReindex()
        .requestedAt(odtOrNull(r.requestedAt()))
        .finishedAt(odtOrNull(r.finishedAt()))
        .lastResult(r.lastResult())
        .lastSnapshotId(r.lastSnapshotId());
  }

  static KnowledgeVersion version(VersionRow v) {
    return new KnowledgeVersion()
        .version(v.version())
        .op(KnowledgeVersion.OpEnum.fromValue(v.op()))
        .title(v.title())
        .contentSha256(v.contentSha256())
        .approvalId(v.approvalId())
        .draftId(v.draftId())
        .createdBy(v.createdBy())
        .createdAt(odt(v.createdAt()));
  }

  static KnowledgeApplication application(ApplicationRow a) {
    return new KnowledgeApplication()
        .approvalId(a.approvalId())
        .draftId(a.draftId())
        .documentId(a.documentId())
        .result(KnowledgeApplication.ResultEnum.fromValue(a.result()))
        .reason(a.reason())
        .version(a.version())
        .approverId(a.approverId())
        .at(odt(a.at()));
  }

  static KnowledgeDraft draft(DraftView v) {
    var d = v.draft();
    var c = v.current();
    return new KnowledgeDraft()
        .draftId(d.draftId())
        .documentId(d.documentId())
        .op(KnowledgeDraft.OpEnum.fromValue(d.op()))
        .baseVersion(d.baseVersion())
        .title(d.title())
        .content(d.content())
        .contentSha256(d.contentSha256())
        .authorId(d.authorSubject())
        .state(KnowledgeDraft.StateEnum.fromValue(d.state()))
        .approvalId(d.approvalId())
        .resultReason(d.resultReason())
        .createdAt(odt(d.createdAt()))
        .updatedAt(odt(d.updatedAt()))
        .current(
            c == null
                ? null
                : new KnowledgeDraftCurrent()
                    .version(c.summary().version())
                    .title(c.summary().title())
                    .content(c.content())
                    .retired(c.summary().retired()))
        .approvalRequest(v.approvalRequest().body());
  }

  private static OffsetDateTime odt(Instant i) {
    return i.atOffset(ZoneOffset.UTC);
  }

  private static @Nullable OffsetDateTime odtOrNull(@Nullable Instant i) {
    return i == null ? null : odt(i);
  }
}
