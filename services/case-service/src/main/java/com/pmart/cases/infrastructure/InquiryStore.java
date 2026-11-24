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
      @Nullable String inquiryNumber,
      /** 마지막으로 답한 직원(0.36.0 — 답변 표 AGENT 의 가장 최근). 넣을 때는 null. */
      @Nullable String lastAnsweredBy,
      /** 0.40.0 문의 센터 — 고객 문의 = 주문 이행 센터, 라이더 문의 = 접수 때 운행 센터. 없으면 null(센터 없음). */
      @Nullable String centerId) {

    /** 센터 없이(0.40.0 전 모양). */
    public InquiryRow(
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
        @Nullable String inquiryNumber,
        @Nullable String lastAnsweredBy) {
      this(
          inquiryId,
          caseId,
          customerId,
          category,
          orderId,
          title,
          body,
          origin,
          status,
          createdAt,
          answeredAt,
          version,
          channel,
          callerRelation,
          requesterType,
          deliveryId,
          deliveryIssue,
          inquiryNumber,
          lastAnsweredBy,
          null);
    }

    /** 고객·라이더에게는 직원 ID·센터를 내보내지 않는다. */
    public InquiryRow withoutStaff() {
      return lastAnsweredBy == null && centerId == null
          ? this
          : new InquiryRow(
              inquiryId,
              caseId,
              customerId,
              category,
              orderId,
              title,
              body,
              origin,
              status,
              createdAt,
              answeredAt,
              version,
              channel,
              callerRelation,
              requesterType,
              deliveryId,
              deliveryIssue,
              inquiryNumber,
              null);
    }
  }

  public record ReplyRow(
      String replyId, String authorType, String authorId, String body, Instant createdAt) {}

  private static final String COLS =
      "inquiry_id, case_id, customer_id, category, order_id, title, body, origin, status,"
          + " created_at, answered_at, version, requester_type, delivery_id, delivery_issue,"
          + " center_id, center_checked";

  /** 상담원 접수(0.12.0)면 연락 경로·본인/대리를 같이 — cs_intake 는 이 서비스 표. */
  private static final String SELECT =
      "SELECT q.inquiry_id, q.case_id, q.customer_id, q.category, q.order_id, q.title, q.body,"
          + " q.origin, q.status, q.created_at, q.answered_at, q.version, q.requester_type,"
          + " q.delivery_id, q.delivery_issue, q.inquiry_number, q.center_id, i.channel,"
          + " i.caller_relation, (SELECT r.author_id FROM cs_inquiry_reply r WHERE r.inquiry_id ="
          + " q.inquiry_id AND r.author_type = 'AGENT' ORDER BY r.created_at DESC, r.seq DESC LIMIT 1)"
          + " AS last_answered_by FROM cs_inquiry q LEFT JOIN cs_intake i ON i.inquiry_id ="
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
        "INSERT INTO cs_inquiry (" + COLS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
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
        r.deliveryIssue(),
        r.centerId(),
        // 0.40.0: 채울 것이 없거나 이미 채움 — 주문 있는 고객 문의인데 센터를 못 받았으면 InquiryCenterBackfill 이 다시 본다
        r.centerId() != null || r.orderId() == null || !"CUSTOMER".equals(r.requesterType()));
  }

  /** 0.40.0 — 센터를 아직 안 채운 주문 있는 고객 문의의 주문(중복 없이). */
  public List<String> ordersWithoutCenter(int limit) {
    return jdbc.queryForList(
        "SELECT DISTINCT order_id FROM cs_inquiry WHERE NOT center_checked AND order_id IS NOT NULL"
            + " AND requester_type = 'CUSTOMER' LIMIT ?",
        String.class,
        limit);
  }

  /** 0.40.0 — 그 주문의 문의 센터 채우기(centerId null = 주문에 센터 없음·주문 없음 — 확인만 남김). */
  public int fillCenter(String orderId, @Nullable String centerId) {
    return jdbc.update(
        "UPDATE cs_inquiry SET center_id = COALESCE(center_id, ?), center_checked = true"
            + " WHERE order_id = ? AND NOT center_checked AND requester_type = 'CUSTOMER'",
        centerId,
        orderId);
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
      /** 직원이 고객으로 거를 때(0.29.0) 상담원 접수(STAFF_INTAKE)도 — 고객 본인 목록에서는 뺀다. */
      boolean includeStaffIntake,
      @Nullable String requesterType,
      @Nullable String status,
      @Nullable String orderId,
      @Nullable String deliveryIssue,
      /** 0.40.0 센터 거르기 — 그 센터 + 센터 없는 문의(늘 함께, 사용자 결정 b). */
      @Nullable String centerId,
      @Nullable String cursor,
      int size) {
    StringBuilder sql = new StringBuilder(SELECT + " WHERE 1=1");
    List<Object> args = new ArrayList<>();
    if (centerId != null) {
      sql.append(" AND (q.center_id = ? OR q.center_id IS NULL)");
      args.add(centerId);
    }
    if (customerId != null) {
      sql.append(
          includeStaffIntake
              ? " AND q.customer_id = ?"
              : " AND q.customer_id = ? AND q.origin <> 'STAFF_INTAKE'");
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
        // R-16: 상세 replies 는 지금처럼 답변(AGENT)·라이더 대화(RIDER)만 — 고객이 이어 쓴 것(CUSTOMER)·시스템 메시지(SYSTEM)는
        // 대화 목록(/messages)에서만(이미 나간 앱이 모르는 값을 받지 않게)
        "SELECT reply_id, author_type, author_id, body, created_at FROM cs_inquiry_reply"
            + " WHERE inquiry_id = ? AND author_type IN ('AGENT', 'RIDER') ORDER BY seq",
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

  /**
   * 남긴 사람이 이어 쓴 메시지 — 라이더(0.21.0 RIDER)·고객(R-16 CUSTOMER). 상태는 RECEIVED(답 기다림)로. 닫힘 확인은 호출 쪽(행을 잠근
   * 뒤).
   */
  public void addRequesterMessage(
      String inquiryId,
      String messageId,
      String authorType,
      String authorId,
      String body,
      Instant at) {
    jdbc.update(
        "INSERT INTO cs_inquiry_reply (reply_id, inquiry_id, author_type, author_id, body,"
            + " created_at) VALUES (?,?,?,?,?,?)",
        messageId,
        inquiryId,
        authorType,
        authorId,
        body,
        Timestamp.from(at));
    jdbc.update(
        "UPDATE cs_inquiry SET status = 'RECEIVED', version = version + 1 WHERE inquiry_id = ?",
        inquiryId);
  }

  /** R-16: 앱이 주문만 줬을 때 서버가 고른 배송 ID 를 문의에 남긴다. */
  public void setDeliveryId(String inquiryId, String deliveryId) {
    jdbc.update(
        "UPDATE cs_inquiry SET delivery_id = ? WHERE inquiry_id = ? AND delivery_id IS NULL",
        deliveryId,
        inquiryId);
  }

  /**
   * 시스템 메시지(R-16 — 오배송 선택 안내·결과). author_id 에 메시지
   * 종류(MISDELIVERY_CHOICE_REQUEST·MISDELIVERY_CHOSEN)를 둔다. 상태·답변 시각은 바꾸지 않는다(답변이 아님 — 마지막 답변 직원·만족도
   * 몫에도 안 셈).
   */
  public void addSystemMessage(
      String inquiryId, String messageId, String kind, String body, Instant at) {
    jdbc.update(
        "INSERT INTO cs_inquiry_reply (reply_id, inquiry_id, author_type, author_id, body,"
            + " created_at) VALUES (?,?, 'SYSTEM', ?,?,?)",
        messageId,
        inquiryId,
        kind,
        body,
        Timestamp.from(at));
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
        rs.getString("inquiry_number"),
        rs.getString("last_answered_by"),
        rs.getString("center_id"));
  }
}
