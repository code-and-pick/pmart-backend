package com.pmart.cases.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 고객 상담 메모(V22 — 계약 0.34.0 R-13). 지우면 글만 NULL. 글은 로그 금지. */
@Repository
public class CustomerNoteStore {

  public record NoteRow(
      String noteId,
      String customerId,
      @Nullable String text,
      String authorId,
      Instant createdAt,
      @Nullable Instant editedAt,
      @Nullable Instant deletedAt) {}

  private static final String SELECT =
      "SELECT note_id, customer_id, text, author_id, created_at, edited_at, deleted_at FROM customer_note";

  private final JdbcTemplate jdbc;

  public CustomerNoteStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(NoteRow n) {
    jdbc.update(
        "INSERT INTO customer_note (note_id, customer_id, text, author_id, created_at) VALUES (?,?,?,?,?)",
        n.noteId(),
        n.customerId(),
        n.text(),
        n.authorId(),
        Timestamp.from(n.createdAt()));
  }

  public Optional<NoteRow> find(String noteId) {
    return jdbc.query(SELECT + " WHERE note_id = ?", this::row, noteId).stream().findFirst();
  }

  public Optional<NoteRow> lock(String noteId) {
    return jdbc.query(SELECT + " WHERE note_id = ? FOR UPDATE", this::row, noteId).stream()
        .findFirst();
  }

  /** 지우지 않은 메모 최근 순 한 쪽(size+1 개까지). cursor = 앞 쪽 마지막 메모 ID. */
  public List<NoteRow> page(String customerId, @Nullable String cursor, int size) {
    StringBuilder sql = new StringBuilder(SELECT + " WHERE customer_id = ? AND deleted_at IS NULL");
    List<Object> args = new ArrayList<>(List.of(customerId));
    if (cursor != null) {
      sql.append(
          " AND (created_at, note_id) < (SELECT created_at, note_id FROM customer_note WHERE note_id = ?)");
      args.add(cursor);
    }
    sql.append(" ORDER BY created_at DESC, note_id DESC LIMIT ?");
    args.add(size + 1);
    return jdbc.query(sql.toString(), this::row, args.toArray());
  }

  public void revise(String noteId, String text, Instant at) {
    jdbc.update(
        "UPDATE customer_note SET text = ?, edited_at = ? WHERE note_id = ?",
        text,
        Timestamp.from(at),
        noteId);
  }

  public void delete(String noteId, Instant at) {
    jdbc.update(
        "UPDATE customer_note SET text = NULL, deleted_at = ? WHERE note_id = ?",
        Timestamp.from(at),
        noteId);
  }

  private NoteRow row(ResultSet rs, int i) throws SQLException {
    Timestamp edited = rs.getTimestamp(6);
    Timestamp deleted = rs.getTimestamp(7);
    return new NoteRow(
        rs.getString(1),
        rs.getString(2),
        rs.getString(3),
        rs.getString(4),
        Objects.requireNonNull(rs.getTimestamp(5)).toInstant(),
        edited == null ? null : edited.toInstant(),
        deleted == null ? null : deleted.toInstant());
  }
}
