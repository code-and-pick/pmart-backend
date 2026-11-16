package com.pmart.cases.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 고객센터 문의 저장(JDBC). 사건 행은 문의와 같은 트랜잭션에서 만든다. */
@Repository
public class InquiryStore {

  public record InquiryRow(
      String inquiryId,
      String caseId,
      String customerId,
      String category,
      @Nullable String orderId,
      @Nullable String title,
      String body,
      String origin,
      String status,
      Instant createdAt,
      @Nullable Instant answeredAt,
      int version) {}

  public record ReplyRow(
      String replyId, String authorType, String authorId, String body, Instant createdAt) {}

  private static final String COLS =
      "inquiry_id, case_id, customer_id, category, order_id, title, body, origin, status,"
          + " created_at, answered_at, version";

  private final JdbcTemplate jdbc;

  public InquiryStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(InquiryRow r) {
    jdbc.update(
        "INSERT INTO case_record (case_id, case_type, state, subject_ref, created_at)"
            + " VALUES (?, 'CS_INQUIRY', 'OPEN', ?, ?)",
        r.caseId(),
        r.orderId(),
        Timestamp.from(r.createdAt()));
    jdbc.update(
        "INSERT INTO cs_inquiry (" + COLS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
        r.inquiryId(),
        r.caseId(),
        r.customerId(),
        r.category(),
        r.orderId(),
        r.title(),
        r.body(),
        r.origin(),
        r.status(),
        Timestamp.from(r.createdAt()),
        null,
        0);
  }

  public Optional<InquiryRow> find(String inquiryId) {
    return jdbc
        .query("SELECT " + COLS + " FROM cs_inquiry WHERE inquiry_id = ?", this::row, inquiryId)
        .stream()
        .findFirst();
  }

  public Optional<InquiryRow> lock(String inquiryId) {
    return jdbc
        .query(
            "SELECT " + COLS + " FROM cs_inquiry WHERE inquiry_id = ? FOR UPDATE",
            this::row,
            inquiryId)
        .stream()
        .findFirst();
  }

  /**
   * 최근 접수 순 한 쪽. customerId 가 있으면 그 고객 것만. cursor = 앞 쪽 마지막 문의 ID(그 행의 접수 시각 다음부터).
   *
   * @return size+1 개까지 — 호출 쪽이 다음 쪽 여부를 판단
   */
  public List<InquiryRow> page(
      @Nullable String customerId,
      @Nullable String status,
      @Nullable String orderId,
      @Nullable String cursor,
      int size) {
    StringBuilder sql = new StringBuilder("SELECT " + COLS + " FROM cs_inquiry WHERE 1=1");
    List<Object> args = new ArrayList<>();
    if (customerId != null) {
      sql.append(" AND customer_id = ?");
      args.add(customerId);
    }
    if (status != null) {
      sql.append(" AND status = ?");
      args.add(status);
    }
    if (orderId != null) {
      sql.append(" AND order_id = ?");
      args.add(orderId);
    }
    if (cursor != null) {
      sql.append(
          " AND (created_at, inquiry_id) < (SELECT created_at, inquiry_id FROM cs_inquiry"
              + " WHERE inquiry_id = ?)");
      args.add(cursor);
    }
    sql.append(" ORDER BY created_at DESC, inquiry_id DESC LIMIT ?");
    args.add(size + 1);
    return jdbc.query(sql.toString(), this::row, args.toArray());
  }

  public List<ReplyRow> replies(String inquiryId) {
    return jdbc.query(
        "SELECT reply_id, author_type, author_id, body, created_at FROM cs_inquiry_reply"
            + " WHERE inquiry_id = ? ORDER BY created_at, reply_id",
        (rs, i) ->
            new ReplyRow(
                rs.getString(1),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getTimestamp(5).toInstant()),
        inquiryId);
  }

  public void addReply(
      String inquiryId,
      String replyId,
      String authorId,
      String body,
      String newStatus,
      Instant at) {
    jdbc.update(
        "INSERT INTO cs_inquiry_reply (reply_id, inquiry_id, author_type, author_id, body,"
            + " created_at) VALUES (?,?, 'AGENT', ?,?,?)",
        replyId,
        inquiryId,
        authorId,
        body,
        Timestamp.from(at));
    jdbc.update(
        "UPDATE cs_inquiry SET status = ?, answered_at = COALESCE(answered_at, ?),"
            + " version = version + 1 WHERE inquiry_id = ?",
        newStatus,
        Timestamp.from(at),
        inquiryId);
  }

  private InquiryRow row(ResultSet rs, int i) throws SQLException {
    Timestamp answered = rs.getTimestamp("answered_at");
    return new InquiryRow(
        rs.getString("inquiry_id"),
        rs.getString("case_id"),
        rs.getString("customer_id"),
        rs.getString("category"),
        rs.getString("order_id"),
        rs.getString("title"),
        rs.getString("body"),
        rs.getString("origin"),
        rs.getString("status"),
        rs.getTimestamp("created_at").toInstant(),
        answered == null ? null : answered.toInstant(),
        rs.getInt("version"));
  }
}
