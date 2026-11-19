package com.pmart.cases.infrastructure;

import com.pmart.cases.domain.InquiryRules;
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
      int version,
      @Nullable String channel,
      @Nullable String callerRelation,
      String requesterType,
      @Nullable String deliveryId,
      @Nullable String deliveryIssue,
      /** 화면 문의 번호 Q-MMDD-일련(ADR-0070, V20 — DB 트리거가 매김). 넣을 때는 null. */
      @Nullable String inquiryNumber) {}

  public record ReplyRow(
      String replyId, String authorType, String authorId, String body, Instant createdAt) {}

  private static final String COLS =
      "inquiry_id, case_id, customer_id, category, order_id, title, body, origin, status,"
          + " created_at, answered_at, version, requester_type, delivery_id, delivery_issue";

  /** 상담원 접수(0.12.0)면 연락 경로·본인/대리를 같이 — cs_intake 는 이 서비스 표. */
  private static final String SELECT =
      "SELECT q.inquiry_id, q.case_id, q.customer_id, q.category, q.order_id, q.title, q.body,"
          + " q.origin, q.status, q.created_at, q.answered_at, q.version, q.requester_type,"
          + " q.delivery_id, q.delivery_issue, q.inquiry_number, i.channel,"
          + " i.caller_relation FROM cs_inquiry q LEFT JOIN cs_intake i ON i.inquiry_id ="
          + " q.inquiry_id";

  private final JdbcTemplate jdbc;

  public InquiryStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(InquiryRow r) {
    jdbc.update(
        "INSERT INTO case_record (case_id, case_type, state, subject_ref, created_at)"
            + " VALUES (?, ?, 'OPEN', ?, ?)",
        r.caseId(),
        InquiryRules.caseType(r.requesterType()),
        r.orderId(),
        Timestamp.from(r.createdAt()));
    jdbc.update(
        "INSERT INTO cs_inquiry (" + COLS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
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
        0,
        r.requesterType(),
        r.deliveryId(),
        r.deliveryIssue());
  }

  public Optional<InquiryRow> find(String inquiryId) {
    return jdbc.query(SELECT + " WHERE q.inquiry_id = ?", this::row, inquiryId).stream()
        .findFirst();
  }

  public Optional<InquiryRow> lock(String inquiryId) {
    return jdbc
        .query(SELECT + " WHERE q.inquiry_id = ? FOR UPDATE OF q", this::row, inquiryId)
        .stream()
        .findFirst();
  }

  /**
   * 최근 접수 순 한 쪽. customerId 가 있으면 그 고객 것만 — 이때 상담원 접수(STAFF_INTAKE)는 뺀다(고객 앱에 안 보임, 0.12.0). cursor
   * = 앞 쪽 마지막 문의 ID(그 행의 접수 시각 다음부터).
   *
   * @return size+1 개까지 — 호출 쪽이 다음 쪽 여부를 판단
   */
  public List<InquiryRow> page(
      @Nullable String customerId,
      @Nullable String requesterType,
      @Nullable String status,
      @Nullable String orderId,
      @Nullable String deliveryIssue,
      @Nullable String cursor,
      int size) {
    StringBuilder sql = new StringBuilder(SELECT + " WHERE 1=1");
    List<Object> args = new ArrayList<>();
    if (customerId != null) {
      sql.append(" AND q.customer_id = ? AND q.origin <> 'STAFF_INTAKE'");
      args.add(customerId);
    }
    if (requesterType != null) {
      sql.append(" AND q.requester_type = ?");
      args.add(requesterType);
    }
    if (status != null) {
      sql.append(" AND q.status = ?");
      args.add(status);
    }
    if (orderId != null) {
      sql.append(" AND q.order_id = ?");
      args.add(orderId);
    }
    if (deliveryIssue != null) {
      sql.append(" AND q.delivery_issue = ?");
      args.add(deliveryIssue);
    }
    if (cursor != null) {
      sql.append(
          " AND (q.created_at, q.inquiry_id) < (SELECT created_at, inquiry_id FROM cs_inquiry"
              + " WHERE inquiry_id = ?)");
      args.add(cursor);
    }
    sql.append(" ORDER BY q.created_at DESC, q.inquiry_id DESC LIMIT ?");
    args.add(size + 1);
    return jdbc.query(sql.toString(), this::row, args.toArray());
  }

  public List<ReplyRow> replies(String inquiryId) {
    return jdbc.query(
        "SELECT reply_id, author_type, author_id, body, created_at FROM cs_inquiry_reply"
            + " WHERE inquiry_id = ? ORDER BY seq",
        (rs, i) ->
            new ReplyRow(
                rs.getString(1),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getTimestamp(5).toInstant()),
        inquiryId);
  }

  /** 대화 메시지 한 줄(0.21.0) — 답변 표 그대로, seq 순. */
  public record MessageRow(
      String messageId,
      long seq,
      String authorType,
      String authorId,
      String body,
      Instant createdAt) {}

  public List<MessageRow> messages(String inquiryId, long after, int limit) {
    return jdbc.query(
        "SELECT reply_id, seq, author_type, author_id, body, created_at FROM cs_inquiry_reply"
            + " WHERE inquiry_id = ? AND seq > ? ORDER BY seq LIMIT ?",
        (rs, i) ->
            new MessageRow(
                rs.getString(1),
                rs.getLong(2),
                rs.getString(3),
                rs.getString(4),
                rs.getString(5),
                rs.getTimestamp(6).toInstant()),
        inquiryId,
        after,
        limit);
  }

  public java.util.Optional<MessageRow> message(String messageId) {
    return jdbc
        .query(
            "SELECT reply_id, seq, author_type, author_id, body, created_at FROM cs_inquiry_reply"
                + " WHERE reply_id = ?",
            (rs, i) ->
                new MessageRow(
                    rs.getString(1),
                    rs.getLong(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getString(5),
                    rs.getTimestamp(6).toInstant()),
            messageId)
        .stream()
        .findFirst();
  }

  /** 라이더가 이어 쓴 메시지(0.21.0) — 상태는 RECEIVED(지원센터 답 기다림)로. 닫힘 확인은 호출 쪽(행을 잠근 뒤). */
  public void addRequesterMessage(
      String inquiryId, String messageId, String authorId, String body, Instant at) {
    jdbc.update(
        "INSERT INTO cs_inquiry_reply (reply_id, inquiry_id, author_type, author_id, body,"
            + " created_at) VALUES (?,?, 'RIDER', ?,?,?)",
        messageId,
        inquiryId,
        authorId,
        body,
        Timestamp.from(at));
    jdbc.update(
        "UPDATE cs_inquiry SET status = 'RECEIVED', version = version + 1 WHERE inquiry_id = ?",
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
        rs.getInt("version"),
        rs.getString("channel"),
        rs.getString("caller_relation"),
        rs.getString("requester_type"),
        rs.getString("delivery_id"),
        rs.getString("delivery_issue"),
        rs.getString("inquiry_number"));
  }
}
