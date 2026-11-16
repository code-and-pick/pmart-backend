package com.pmart.cases.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 접수 건 메모(V8 — 계약 0.10.0). 덧붙이기만, 가림은 글만 지운다. */
@Repository
public class CaseNoteStore {

  public record NoteRow(
      String noteId,
      String caseId,
      @Nullable String text,
      String authorId,
      Instant createdAt,
      @Nullable Instant maskedAt,
      @Nullable String maskedBy,
      @Nullable String maskReason) {}

  private static final String SELECT =
      "SELECT note_id, case_id, text, author_id, created_at, masked_at, masked_by, mask_reason FROM case_note";

  private final JdbcTemplate jdbc;

  public CaseNoteStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(NoteRow n) {
    jdbc.update(
        "INSERT INTO case_note (note_id, case_id, text, author_id, created_at) VALUES (?,?,?,?,?)",
        n.noteId(),
        n.caseId(),
        n.text(),
        n.authorId(),
        Timestamp.from(n.createdAt()));
  }

  public Optional<NoteRow> lock(String caseId, String noteId) {
    return jdbc
        .query(SELECT + " WHERE case_id = ? AND note_id = ? FOR UPDATE", this::row, caseId, noteId)
        .stream()
        .findFirst();
  }

  public Optional<NoteRow> find(String noteId) {
    return jdbc.query(SELECT + " WHERE note_id = ?", this::row, noteId).stream().findFirst();
  }

  public List<NoteRow> forCase(String caseId) {
    return jdbc.query(
        SELECT + " WHERE case_id = ? ORDER BY created_at, note_id", this::row, caseId);
  }

  /** 가림 — 글은 지우고 누가·언제·왜는 남긴다. */
  public void mask(String noteId, String by, String reason, Instant at) {
    jdbc.update(
        "UPDATE case_note SET text = NULL, masked_at = ?, masked_by = ?, mask_reason = ?"
            + " WHERE note_id = ?",
        Timestamp.from(at),
        by,
        reason,
        noteId);
  }

  private NoteRow row(ResultSet rs, int i) throws SQLException {
    Timestamp masked = rs.getTimestamp(6);
    return new NoteRow(
        rs.getString(1),
        rs.getString(2),
        rs.getString(3),
        rs.getString(4),
        Objects.requireNonNull(rs.getTimestamp(5)).toInstant(),
        masked == null ? null : masked.toInstant(),
        rs.getString(7),
        rs.getString(8));
  }
}
