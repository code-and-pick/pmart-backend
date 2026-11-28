package com.pmart.assistant.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** 운영자 RAG 문서 정본 저장(ADR-0092, V3): 지금 판·판 이력·초안·반영 기록·재색인 요청. 이 서비스 소유 표만 다룬다. 본문은 로그에 남기지 않는다. */
@Repository
public class KnowledgeStore {

  /** 목록용(본문 없음). */
  public record DocumentSummary(
      String documentId,
      String domain,
      String title,
      int version,
      String contentSha256,
      boolean retired,
      boolean readOnly,
      Instant updatedAt,
      String updatedBy) {}

  public record Document(DocumentSummary summary, String content) {}

  public record VersionRow(
      int version,
      String op,
      String title,
      String contentSha256,
      @Nullable String approvalId,
      @Nullable String draftId,
      String createdBy,
      Instant createdAt) {}

  public record DraftRow(
      String draftId,
      String documentId,
      String op,
      int baseVersion,
      @Nullable String title,
      @Nullable String content,
      @Nullable String contentSha256,
      String authorSubject,
      String requestId,
      String state,
      @Nullable String approvalId,
      @Nullable String resultReason,
      Instant createdAt,
      Instant updatedAt) {}

  public record ApplicationRow(
      String approvalId,
      @Nullable String draftId,
      @Nullable String documentId,
      String result,
      @Nullable String reason,
      @Nullable Integer version,
      @Nullable String approverId,
      Instant at) {}

  public record ReindexRow(
      @Nullable Instant requestedAt,
      @Nullable Instant startedAt,
      @Nullable Instant finishedAt,
      @Nullable String lastResult,
      @Nullable String lastSnapshotId,
      @Nullable String lastError) {}

  /** 색인 입력(지금 판, 내리지 않은 것). */
  public record ActiveDoc(String documentId, String domain, String content, String sha256) {}

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;

  public KnowledgeStore(JdbcTemplate jdbc, TransactionTemplate tx) {
    this.jdbc = jdbc;
    this.tx = tx;
  }

  public <T> T inTransaction(java.util.function.Supplier<T> work) {
    return tx.execute(s -> work.get());
  }

  // ---- 문서 ----

  public int countDocuments() {
    Integer n = jdbc.queryForObject("SELECT count(*) FROM knowledge_document", Integer.class);
    return n == null ? 0 : n;
  }

  /** 가져오기 — 이미 있는 문서는 건드리지 않는다. 넣었으면 true. */
  public boolean importIfMissing(
      String documentId,
      String domain,
      String title,
      String content,
      String sha256,
      boolean readOnly,
      Instant at) {
    Boolean inserted =
        tx.execute(
            s -> {
              int n =
                  jdbc.update(
                      """
                      INSERT INTO knowledge_document (document_id, domain_area, title, version, content,
                          content_sha256, retired, read_only, updated_at, updated_by)
                      VALUES (?, ?, ?, 1, ?, ?, false, ?, ?, 'IMPORT')
                      ON CONFLICT (document_id) DO NOTHING
                      """,
                      documentId,
                      domain,
                      title,
                      content,
                      sha256,
                      readOnly,
                      Timestamp.from(at));
              if (n == 0) {
                return false;
              }
              insertVersion(
                  documentId, 1, "IMPORT", title, content, sha256, null, null, "IMPORT", at);
              return true;
            });
    return Boolean.TRUE.equals(inserted);
  }

  public List<DocumentSummary> listDocuments(boolean includeRetired) {
    return jdbc.query(
        "SELECT * FROM knowledge_document"
            + (includeRetired ? "" : " WHERE NOT retired")
            + " ORDER BY document_id",
        (rs, i) -> summary(rs));
  }

  public Optional<Document> findDocument(String documentId) {
    return jdbc
        .query(
            "SELECT * FROM knowledge_document WHERE document_id = ?",
            (rs, i) -> new Document(summary(rs), rs.getString("content")),
            documentId)
        .stream()
        .findFirst();
  }

  /** 반영 때 문서 줄 잠금(같은 문서를 두 승인이 동시에 바꾸지 않게). 트랜잭션 안에서만. */
  public Optional<DocumentSummary> lockDocument(String documentId) {
    return jdbc
        .query(
            "SELECT * FROM knowledge_document WHERE document_id = ? FOR UPDATE",
            (rs, i) -> summary(rs),
            documentId)
        .stream()
        .findFirst();
  }

  public List<VersionRow> versions(String documentId) {
    return jdbc.query(
        """
        SELECT version, op, title, content_sha256, approval_id, draft_id, created_by, created_at
        FROM knowledge_document_version WHERE document_id = ? ORDER BY version DESC
        """,
        (rs, i) ->
            new VersionRow(
                rs.getInt(1),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getString(5),
                rs.getString(6),
                rs.getString(7),
                rs.getTimestamp(8).toInstant()),
        documentId);
  }

  public Optional<String> versionContent(String documentId, int version) {
    return jdbc
        .query(
            "SELECT content FROM knowledge_document_version WHERE document_id = ? AND version = ?",
            (rs, i) -> rs.getString(1),
            documentId,
            version)
        .stream()
        .findFirst();
  }

  public List<ActiveDoc> activeDocuments() {
    return jdbc.query(
        """
        SELECT document_id, domain_area, content, content_sha256 FROM knowledge_document
        WHERE NOT retired ORDER BY document_id
        """,
        (rs, i) ->
            new ActiveDoc(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)));
  }

  /** 새 판 저장(트랜잭션 안, {@link #lockDocument} 뒤). 새 문서면 version 1 로 넣는다. */
  public void writeVersion(
      String documentId,
      String domain,
      String op,
      int newVersion,
      String title,
      String content,
      String sha256,
      boolean retired,
      String approvalId,
      String draftId,
      String by,
      Instant at) {
    if (newVersion == 1) {
      jdbc.update(
          """
          INSERT INTO knowledge_document (document_id, domain_area, title, version, content, content_sha256,
              retired, read_only, updated_at, updated_by, last_approval_id)
          VALUES (?, ?, ?, 1, ?, ?, ?, false, ?, ?, ?)
          """,
          documentId,
          domain,
          title,
          content,
          sha256,
          retired,
          Timestamp.from(at),
          by,
          approvalId);
    } else {
      jdbc.update(
          """
          UPDATE knowledge_document SET title = ?, version = ?, content = ?, content_sha256 = ?, retired = ?,
              updated_at = ?, updated_by = ?, last_approval_id = ?
          WHERE document_id = ?
          """,
          title,
          newVersion,
          content,
          sha256,
          retired,
          Timestamp.from(at),
          by,
          approvalId,
          documentId);
    }
    insertVersion(documentId, newVersion, op, title, content, sha256, approvalId, draftId, by, at);
  }

  private void insertVersion(
      String documentId,
      int version,
      String op,
      String title,
      String content,
      String sha256,
      @Nullable String approvalId,
      @Nullable String draftId,
      String by,
      Instant at) {
    jdbc.update(
        """
        INSERT INTO knowledge_document_version (document_id, version, op, title, content, content_sha256,
            approval_id, draft_id, created_by, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        documentId,
        version,
        op,
        title,
        content,
        sha256,
        approvalId,
        draftId,
        by,
        Timestamp.from(at));
  }

  // ---- 초안 ----

  /** 만들기(같은 작성자·요청 ID 는 처음 것을 돌려준다). */
  public DraftRow createDraft(DraftRow d) {
    jdbc.update(
        """
        INSERT INTO knowledge_draft (draft_id, document_id, op, base_version, title, content, content_sha256,
            author_subject, request_id, state, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, ?)
        ON CONFLICT (author_subject, request_id) DO NOTHING
        """,
        d.draftId(),
        d.documentId(),
        d.op(),
        d.baseVersion(),
        d.title(),
        d.content(),
        d.contentSha256(),
        d.authorSubject(),
        d.requestId(),
        Timestamp.from(d.createdAt()),
        Timestamp.from(d.updatedAt()));
    return jdbc.queryForObject(
        "SELECT * FROM knowledge_draft WHERE author_subject = ? AND request_id = ?",
        (rs, i) -> draft(rs),
        d.authorSubject(),
        d.requestId());
  }

  public Optional<DraftRow> findDraft(String draftId) {
    return jdbc
        .query("SELECT * FROM knowledge_draft WHERE draft_id = ?", (rs, i) -> draft(rs), draftId)
        .stream()
        .findFirst();
  }

  public List<DraftRow> openDraftsOf(String author) {
    return jdbc.query(
        "SELECT * FROM knowledge_draft WHERE author_subject = ? AND state = 'OPEN' ORDER BY updated_at DESC",
        (rs, i) -> draft(rs),
        author);
  }

  /** 열린 초안 본문 고치기(작성자만). 고쳤으면 true. */
  public boolean updateDraft(
      String draftId, String author, String title, String content, String sha256, Instant at) {
    return jdbc.update(
            """
            UPDATE knowledge_draft SET title = ?, content = ?, content_sha256 = ?, updated_at = ?
            WHERE draft_id = ? AND author_subject = ? AND state = 'OPEN'
            """,
            title,
            content,
            sha256,
            Timestamp.from(at),
            draftId,
            author)
        == 1;
  }

  /** 버리기(작성자·열린 초안만). 바꿨으면 true. */
  public boolean discardDraft(String draftId, String author, Instant at) {
    return jdbc.update(
            """
            UPDATE knowledge_draft SET state = 'DISCARDED', updated_at = ?
            WHERE draft_id = ? AND author_subject = ? AND state = 'OPEN'
            """,
            Timestamp.from(at),
            draftId,
            author)
        == 1;
  }

  /** 반영 결과로 초안을 닫는다(트랜잭션 안). 열린 초안만. */
  public void closeDraft(
      String draftId, String state, String approvalId, @Nullable String reason, Instant at) {
    jdbc.update(
        """
        UPDATE knowledge_draft SET state = ?, approval_id = ?, result_reason = ?, updated_at = ?
        WHERE draft_id = ? AND state = 'OPEN'
        """,
        state,
        approvalId,
        reason,
        Timestamp.from(at),
        draftId);
  }

  // ---- 반영 기록 ----

  public Optional<ApplicationRow> findApplication(String approvalId) {
    return jdbc
        .query(
            "SELECT * FROM knowledge_application WHERE approval_id = ?",
            (rs, i) -> application(rs),
            approvalId)
        .stream()
        .findFirst();
  }

  public List<ApplicationRow> recentApplications(int size) {
    return jdbc.query(
        "SELECT * FROM knowledge_application ORDER BY at DESC LIMIT ?",
        (rs, i) -> application(rs),
        size);
  }

  public void insertApplication(ApplicationRow a) {
    jdbc.update(
        """
        INSERT INTO knowledge_application (approval_id, draft_id, document_id, result, reason, version,
            approver_id, at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """,
        a.approvalId(),
        a.draftId(),
        a.documentId(),
        a.result(),
        a.reason(),
        a.version(),
        a.approverId(),
        Timestamp.from(a.at()));
  }

  // ---- 재색인 요청 ----

  public void requestReindex(Instant at) {
    jdbc.update("UPDATE knowledge_reindex SET requested_at = ? WHERE id = 1", Timestamp.from(at));
  }

  public ReindexRow reindex() {
    return jdbc.queryForObject(
        "SELECT requested_at, started_at, finished_at, last_result, last_snapshot_id, last_error FROM knowledge_reindex WHERE id = 1",
        (rs, i) ->
            new ReindexRow(
                instant(rs, 1),
                instant(rs, 2),
                instant(rs, 3),
                rs.getString(4),
                rs.getString(5),
                rs.getString(6)));
  }

  /** 요청이 시작보다 새로우면 시작 시각을 남기고 true(한 주기에 한 번). */
  public boolean claimReindex(Instant at) {
    return jdbc.update(
            """
            UPDATE knowledge_reindex SET started_at = ?
            WHERE id = 1 AND requested_at IS NOT NULL
              AND (started_at IS NULL OR requested_at > started_at)
            """,
            Timestamp.from(at))
        == 1;
  }

  public void finishReindex(
      String result, @Nullable String snapshotId, @Nullable String error, Instant at) {
    jdbc.update(
        """
        UPDATE knowledge_reindex SET finished_at = ?, last_result = ?, last_snapshot_id = ?, last_error = ?
        WHERE id = 1
        """,
        Timestamp.from(at),
        result,
        snapshotId,
        error == null ? null : (error.length() > 500 ? error.substring(0, 500) : error));
  }

  /** 실패한 재색인을 다음 주기에 다시 하도록 시작 시각을 되돌린다. */
  public void releaseReindex() {
    jdbc.update("UPDATE knowledge_reindex SET started_at = NULL WHERE id = 1");
  }

  // ---- 변환 ----

  private static DocumentSummary summary(ResultSet rs) throws SQLException {
    return new DocumentSummary(
        rs.getString("document_id"),
        rs.getString("domain_area"),
        rs.getString("title"),
        rs.getInt("version"),
        rs.getString("content_sha256"),
        rs.getBoolean("retired"),
        rs.getBoolean("read_only"),
        rs.getTimestamp("updated_at").toInstant(),
        rs.getString("updated_by"));
  }

  private static DraftRow draft(ResultSet rs) throws SQLException {
    return new DraftRow(
        rs.getString("draft_id"),
        rs.getString("document_id"),
        rs.getString("op"),
        rs.getInt("base_version"),
        rs.getString("title"),
        rs.getString("content"),
        rs.getString("content_sha256"),
        rs.getString("author_subject"),
        rs.getString("request_id"),
        rs.getString("state"),
        rs.getString("approval_id"),
        rs.getString("result_reason"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }

  private static ApplicationRow application(ResultSet rs) throws SQLException {
    int v = rs.getInt("version");
    Integer version = rs.wasNull() ? null : v;
    return new ApplicationRow(
        rs.getString("approval_id"),
        rs.getString("draft_id"),
        rs.getString("document_id"),
        rs.getString("result"),
        rs.getString("reason"),
        version,
        rs.getString("approver_id"),
        rs.getTimestamp("at").toInstant());
  }

  private static @Nullable Instant instant(ResultSet rs, int col) throws SQLException {
    Timestamp t = rs.getTimestamp(col);
    return t == null ? null : t.toInstant();
  }
}
