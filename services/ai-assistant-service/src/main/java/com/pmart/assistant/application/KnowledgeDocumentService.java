package com.pmart.assistant.application;

import com.pmart.assistant.domain.KnowledgeDocumentRules;
import com.pmart.assistant.domain.RequestHashing;
import com.pmart.assistant.domain.SourceSystemPolicy;
import com.pmart.assistant.infrastructure.KnowledgeStore;
import com.pmart.assistant.infrastructure.KnowledgeStore.ApplicationRow;
import com.pmart.assistant.infrastructure.KnowledgeStore.Document;
import com.pmart.assistant.infrastructure.KnowledgeStore.DocumentSummary;
import com.pmart.assistant.infrastructure.KnowledgeStore.DraftRow;
import com.pmart.assistant.infrastructure.KnowledgeStore.ReindexRow;
import com.pmart.assistant.infrastructure.KnowledgeStore.VersionRow;
import com.pmart.assistant.infrastructure.SnapshotStore;
import com.pmart.assistant.infrastructure.SnapshotStore.SourceFile;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 운영자 RAG 문서 보기·초안(ADR-0092). 반영은 승인으로만({@link KnowledgeApprovals}) — 이 서비스는 문서 판을 바꾸지 않는다.
 *
 * <p>보기: 운영자 누구나(X-Source-System 검사). 초안: {@code pmart.assistant.knowledge.editor-roles}. 초안 응답에 approval-service
 * 변경 요청 본문({@link ApprovalRequest})을 실어 화면이 그대로 보낸다.
 */
@Service
public class KnowledgeDocumentService {

  /** 문서의 색인 상태: 지금 판이 활성 스냅숏에 들어갔는지. */
  public enum IndexState {
    INDEXED,
    PENDING,
    FAILED,
    NOT_INDEXED
  }

  public record DocumentView(DocumentSummary summary, IndexState indexState) {}

  public record DocumentDetail(
      DocumentView view, String content, int shownVersion, List<VersionRow> versions) {}

  /** approval-service {@code POST /change-requests} 본문(RAG_DOCUMENT). */
  public record ApprovalRequest(Map<String, Object> body) {}

  public record DraftView(
      DraftRow draft,
      @Nullable Document current,
      ApprovalRequest approvalRequest) {}

  public record Overview(List<DocumentView> documents, ReindexRow reindex, boolean dbSource) {}

  private final KnowledgeStore store;
  private final SnapshotStore snapshots;
  private final KnowledgeProperties props;
  private final Clock clock;

  public KnowledgeDocumentService(
      KnowledgeStore store, SnapshotStore snapshots, KnowledgeProperties props, Clock clock) {
    this.store = store;
    this.snapshots = snapshots;
    this.props = props;
    this.clock = clock;
  }

  // ---- 보기 ----

  public Overview list(@Nullable PmartPrincipal principal, @Nullable String source, boolean includeRetired) {
    requireOperatorAndSource(principal, source);
    Map<String, String> indexed = indexedHashes();
    ReindexRow reindex = store.reindex();
    List<DocumentView> docs =
        store.listDocuments(includeRetired).stream()
            .map(d -> new DocumentView(d, indexState(d, indexed, reindex)))
            .toList();
    return new Overview(docs, reindex, props.getKnowledge().dbSource());
  }

  public DocumentDetail get(
      @Nullable PmartPrincipal principal,
      @Nullable String source,
      String documentId,
      @Nullable Integer version) {
    requireOperatorAndSource(principal, source);
    Document doc = store.findDocument(documentId).orElseThrow(KnowledgeDocumentService::notFound);
    String content = doc.content();
    int shown = doc.summary().version();
    if (version != null && version != shown) {
      content = store.versionContent(documentId, version).orElseThrow(KnowledgeDocumentService::notFound);
      shown = version;
    }
    DocumentView view =
        new DocumentView(doc.summary(), indexState(doc.summary(), indexedHashes(), store.reindex()));
    return new DocumentDetail(view, content, shown, store.versions(documentId));
  }

  public List<ApplicationRow> applications(
      @Nullable PmartPrincipal principal, @Nullable String source, int size) {
    requireOperatorAndSource(principal, source);
    return store.recentApplications(Math.max(1, Math.min(size, 100)));
  }

  // ---- 초안 ----

  public DraftView createDraft(
      @Nullable PmartPrincipal principal,
      @Nullable String source,
      String requestId,
      String documentId,
      String op,
      @Nullable Integer baseVersion,
      @Nullable String content) {
    PmartPrincipal p = requireEditor(principal, source);
    List<KnowledgeDocumentRules.Problem> problems = KnowledgeDocumentRules.check(documentId, op, content);
    if (!problems.isEmpty()) {
      throw invalid(problems);
    }
    var current = store.findDocument(documentId);
    int now = current.map(d -> d.summary().version()).orElse(0);
    if ("RETIRE".equals(op) && (current.isEmpty() || current.get().summary().retired())) {
      throw new PmartException(
          ErrorCode.INVALID_STATE.name(), HttpStatus.CONFLICT, "내릴 문서가 없거나 이미 내렸습니다.", false, List.of());
    }
    if (baseVersion != null && baseVersion != now) {
      throw stale(now);
    }
    Instant at = clock.instant();
    String sha = content == null ? null : sha(content);
    DraftRow made =
        store.createDraft(
            new DraftRow(
                "KDR-" + UUID.randomUUID(),
                documentId,
                op,
                now,
                content == null ? current.map(d -> d.summary().title()).orElse(null) : KnowledgeDocumentRules.title(content),
                content,
                sha,
                p.subject(),
                requestId,
                "OPEN",
                null,
                null,
                at,
                at));
    if (!made.documentId().equals(documentId) || !made.op().equals(op)
        || !java.util.Objects.equals(made.contentSha256(), sha)) {
      throw new PmartException(
          ErrorCode.IDEMPOTENCY_CONFLICT.name(),
          HttpStatus.CONFLICT,
          "같은 Idempotency-Key 로 다른 초안을 만들 수 없습니다.",
          false,
          List.of());
    }
    return view(made);
  }

  public DraftView updateDraft(
      @Nullable PmartPrincipal principal, @Nullable String source, String draftId, String content) {
    PmartPrincipal p = requireEditor(principal, source);
    DraftRow d = store.findDraft(draftId).orElseThrow(KnowledgeDocumentService::draftNotFound);
    if (!d.authorSubject().equals(p.subject())) {
      throw draftNotFound();
    }
    if (!"OPEN".equals(d.state())) {
      throw new PmartException(
          ErrorCode.INVALID_STATE.name(), HttpStatus.CONFLICT, "닫힌 초안은 고칠 수 없습니다: " + d.state(), false, List.of());
    }
    List<KnowledgeDocumentRules.Problem> problems =
        KnowledgeDocumentRules.check(d.documentId(), d.op(), content);
    if (!problems.isEmpty()) {
      throw invalid(problems);
    }
    store.updateDraft(draftId, p.subject(), KnowledgeDocumentRules.title(content), content, sha(content), clock.instant());
    return view(store.findDraft(draftId).orElseThrow());
  }

  /** 초안 보기 — 승인자도 본다(운영자 누구나). 지금 판을 같이 줘서 화면이 비교한다. */
  public DraftView getDraft(@Nullable PmartPrincipal principal, @Nullable String source, String draftId) {
    requireOperatorAndSource(principal, source);
    return view(store.findDraft(draftId).orElseThrow(KnowledgeDocumentService::draftNotFound));
  }

  public List<DraftView> myDrafts(@Nullable PmartPrincipal principal, @Nullable String source) {
    PmartPrincipal p = requireOperatorAndSource(principal, source);
    return store.openDraftsOf(p.subject()).stream().map(this::view).toList();
  }

  public void discardDraft(@Nullable PmartPrincipal principal, @Nullable String source, String draftId) {
    PmartPrincipal p = requireOperatorAndSource(principal, source);
    store.discardDraft(draftId, p.subject(), clock.instant()); // 없거나 남의 것·닫힌 것이어도 204(멱등)
  }

  private DraftView view(DraftRow d) {
    return new DraftView(d, store.findDocument(d.documentId()).orElse(null), approvalRequest(d));
  }

  /** approval-service 변경 요청 본문 — 화면이 그대로 보낸다(동의어 승인과 같은 방식). */
  static ApprovalRequest approvalRequest(DraftRow d) {
    Map<String, Object> attrs = new LinkedHashMap<>();
    attrs.put("op", d.op());
    attrs.put("draftId", d.draftId());
    if (d.contentSha256() != null) {
      attrs.put("contentSha256", d.contentSha256());
    }
    Map<String, Object> line = new LinkedHashMap<>();
    line.put("lineKey", d.documentId());
    line.put("attributes", attrs);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", "RAG_DOCUMENT");
    body.put("targetService", "ai-assistant-service");
    body.put("targetRef", d.draftId());
    body.put("targetExpectedVersion", d.baseVersion());
    body.put("allowedActions", List.of("APPLY_KNOWLEDGE_DOCUMENT"));
    String what =
        "RETIRE".equals(d.op()) ? "내리기" : d.baseVersion() == 0 ? "새 문서" : "고치기(판 " + d.baseVersion() + " 기준)";
    String name = d.title() == null ? d.documentId() : d.title();
    String summary = "운영자 RAG 문서 " + what + ": " + name;
    body.put("summary", summary.length() > 500 ? summary.substring(0, 500) : summary);
    body.put("lines", List.of(line));
    return new ApprovalRequest(body);
  }

  // ---- 색인 상태 ----

  private Map<String, String> indexedHashes() {
    Map<String, String> out = new HashMap<>();
    snapshots
        .findActive()
        .ifPresent(
            s -> {
              for (SourceFile f : snapshots.sourceFiles(s.snapshotId())) {
                out.put(f.path(), f.sha256());
              }
            });
    return out;
  }

  static IndexState indexState(DocumentSummary d, Map<String, String> indexed, ReindexRow reindex) {
    String inSnapshot = indexed.get(d.documentId());
    if (d.retired()) {
      return inSnapshot == null ? IndexState.INDEXED : pendingOrFailed(d, reindex);
    }
    if (d.contentSha256().equals(inSnapshot)) {
      return IndexState.INDEXED;
    }
    if (inSnapshot == null && reindex.requestedAt() == null) {
      return IndexState.NOT_INDEXED; // 가져온 뒤 아직 한 번도 DB 로 색인하지 않음
    }
    return pendingOrFailed(d, reindex);
  }

  private static IndexState pendingOrFailed(DocumentSummary d, ReindexRow r) {
    boolean failedAfter =
        "FAILED".equals(r.lastResult())
            && r.finishedAt() != null
            && r.finishedAt().isAfter(d.updatedAt());
    return failedAfter ? IndexState.FAILED : IndexState.PENDING;
  }

  // ---- 검사·오류 ----

  static String sha(String content) {
    return RequestHashing.sha256(content.getBytes(StandardCharsets.UTF_8));
  }

  private PmartPrincipal requireEditor(@Nullable PmartPrincipal principal, @Nullable String source) {
    PmartPrincipal p = requireOperatorAndSource(principal, source);
    if (!hasAny(p.roles(), props.getKnowledge().getEditorRoles())) {
      throw new PmartException(ErrorCode.FORBIDDEN, "운영자 RAG 문서 초안은 " + props.getKnowledge().getEditorRoles() + " 만 만들 수 있습니다.");
    }
    return p;
  }

  static boolean hasAny(Collection<String> have, Collection<String> need) {
    return have.stream().anyMatch(need::contains);
  }

  static PmartPrincipal requireOperatorAndSource(@Nullable PmartPrincipal principal, @Nullable String source) {
    if (principal == null || principal.type() != PmartPrincipal.PrincipalType.OPERATOR) {
      throw new PmartException(ErrorCode.FORBIDDEN, "운영자만 사용할 수 있습니다.");
    }
    if (!SourceSystemPolicy.accepts(principal.sourceSystem(), source)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "X-Source-System 이 인증된 출처와 다릅니다.");
    }
    return principal;
  }

  private static PmartException invalid(List<KnowledgeDocumentRules.Problem> problems) {
    return new PmartException(
        ErrorCode.VALIDATION_FAILED,
        "문서 형식이 맞지 않습니다.",
        problems.stream().map(x -> new PmartException.Detail(x.field(), x.message())).toList());
  }

  private static PmartException stale(int now) {
    return new PmartException(
        "STALE_VERSION",
        HttpStatus.CONFLICT,
        "문서가 그사이 바뀌었습니다(지금 판 " + now + "). 다시 열어 고치세요.",
        false,
        List.of());
  }

  private static PmartException notFound() {
    return PmartException.notFound(ErrorCode.NOT_FOUND.name(), "문서를 찾을 수 없습니다.");
  }

  private static PmartException draftNotFound() {
    return PmartException.notFound(ErrorCode.NOT_FOUND.name(), "초안을 찾을 수 없습니다.");
  }
}
