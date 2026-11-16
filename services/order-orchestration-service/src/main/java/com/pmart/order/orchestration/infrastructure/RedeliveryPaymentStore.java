package com.pmart.order.orchestration.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 오배송 다시 배송 9,000원 결제 대기 사가(V11 redelivery_payment). */
@Repository
public class RedeliveryPaymentStore {

  public record Row(
      String sagaId,
      String caseId,
      String deliveryId,
      @Nullable String originDeliveryId,
      String orderId,
      @Nullable String accountId,
      long amount,
      String state,
      String currentStep,
      @Nullable String outcome,
      @Nullable String paymentAttemptId,
      int attemptNo,
      Instant deadlineAt,
      @Nullable String lastErrorCode,
      Instant createdAt,
      Instant updatedAt) {}

  private static final String COLS =
      "saga_id, case_id, delivery_id, origin_delivery_id, order_id, account_id, amount, state,"
          + " current_step, outcome, payment_attempt_id, attempt_no, deadline_at, last_error_code,"
          + " created_at, updated_at";

  private final JdbcTemplate jdbc;

  public RedeliveryPaymentStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 같은 사건이면 false(사건 다시 받음). */
  public boolean insert(
      String sagaId,
      String caseId,
      String deliveryId,
      @Nullable String originDeliveryId,
      String orderId,
      long amount,
      Instant deadline,
      Instant now) {
    return jdbc.update(
            "INSERT INTO redelivery_payment (saga_id, case_id, delivery_id, origin_delivery_id,"
                + " order_id, amount, state, current_step, deadline_at, created_at, updated_at)"
                + " VALUES (?,?,?,?,?,?,'RUNNING','PREPARE_PAYMENT',?,?,?) ON CONFLICT DO NOTHING",
            sagaId,
            caseId,
            deliveryId,
            originDeliveryId,
            orderId,
            amount,
            Timestamp.from(deadline),
            Timestamp.from(now),
            Timestamp.from(now))
        == 1;
  }

  public Optional<Row> find(String sagaId) {
    return jdbc
        .query("SELECT " + COLS + " FROM redelivery_payment WHERE saga_id = ?", this::row, sagaId)
        .stream()
        .findFirst();
  }

  public Optional<Row> lock(String sagaId) {
    return jdbc
        .query(
            "SELECT " + COLS + " FROM redelivery_payment WHERE saga_id = ? FOR UPDATE",
            this::row,
            sagaId)
        .stream()
        .findFirst();
  }

  public Optional<Row> byCase(String caseId) {
    return jdbc
        .query("SELECT " + COLS + " FROM redelivery_payment WHERE case_id = ?", this::row, caseId)
        .stream()
        .findFirst();
  }

  public Optional<String> byPaymentAttempt(String paymentAttemptId) {
    return jdbc
        .queryForList(
            "SELECT saga_id FROM redelivery_payment WHERE payment_attempt_id = ?",
            String.class,
            paymentAttemptId)
        .stream()
        .findFirst();
  }

  /** 고객 본인 것 — 최근 순 20건. */
  public List<Row> byAccount(String accountId, @Nullable String orderId) {
    return orderId == null
        ? jdbc.query(
            "SELECT "
                + COLS
                + " FROM redelivery_payment WHERE account_id = ? ORDER BY created_at"
                + " DESC LIMIT 20",
            this::row,
            accountId)
        : jdbc.query(
            "SELECT "
                + COLS
                + " FROM redelivery_payment WHERE account_id = ? AND order_id = ?"
                + " ORDER BY created_at DESC LIMIT 20",
            this::row,
            accountId,
            orderId);
  }

  /** 진행 중이고 마지막 갱신이 before 보다 이른 것(sweep) + 기한이 지난 결제 대기. */
  public List<String> due(Instant before, Instant now) {
    return jdbc.queryForList(
        "SELECT saga_id FROM redelivery_payment WHERE state = 'RUNNING' AND (updated_at < ? OR"
            + " (current_step = 'AWAIT_PAYMENT' AND deadline_at <= ?)) ORDER BY updated_at LIMIT"
            + " 100",
        String.class,
        Timestamp.from(before),
        Timestamp.from(now));
  }

  public void attempt(
      String sagaId, String accountId, String paymentAttemptId, int attemptNo, Instant now) {
    jdbc.update(
        "UPDATE redelivery_payment SET account_id = ?, payment_attempt_id = ?, attempt_no = ?,"
            + " current_step = 'AWAIT_PAYMENT', last_error_step = NULL, last_error_code = NULL,"
            + " last_error_message = NULL, updated_at = ? WHERE saga_id = ?",
        accountId,
        paymentAttemptId,
        attemptNo,
        Timestamp.from(now),
        sagaId);
  }

  public void step(String sagaId, String step, @Nullable String outcome, Instant now) {
    jdbc.update(
        "UPDATE redelivery_payment SET current_step = ?, outcome = COALESCE(?, outcome),"
            + " last_error_step = NULL, last_error_code = NULL, last_error_message = NULL,"
            + " updated_at = ? WHERE saga_id = ?",
        step,
        outcome,
        Timestamp.from(now),
        sagaId);
  }

  /** 고객 거절 표시(결제 대기 중일 때만 — 다음 진행에서 회수로 바꾼다). */
  public void decline(String sagaId, Instant now) {
    jdbc.update(
        "UPDATE redelivery_payment SET outcome = 'DECLINED', updated_at = ? WHERE saga_id = ? AND"
            + " outcome IS NULL",
        Timestamp.from(now),
        sagaId);
  }

  public void finish(String sagaId, Instant now) {
    jdbc.update(
        "UPDATE redelivery_payment SET state = 'COMPLETED', current_step = 'DONE', finished_at ="
            + " ?, updated_at = ? WHERE saga_id = ?",
        Timestamp.from(now),
        Timestamp.from(now),
        sagaId);
  }

  /** 늦은 승인 보상 취소를 위해 다시 연다. */
  public void reopen(String sagaId, String step, Instant now) {
    jdbc.update(
        "UPDATE redelivery_payment SET state = 'RUNNING', current_step = ?, updated_at = ? WHERE"
            + " saga_id = ?",
        step,
        Timestamp.from(now),
        sagaId);
  }

  /** 실패 기록(진행 중 유지 — sweep 이 같은 키로 다시). escalate 면 사람 처리 대기. */
  public void error(
      String sagaId, String step, String code, String message, boolean escalate, Instant now) {
    jdbc.update(
        "UPDATE redelivery_payment SET state = CASE WHEN ? THEN 'ESCALATED' ELSE state END,"
            + " last_error_step = ?, last_error_code = ?, last_error_message = ?, updated_at = ?"
            + " WHERE saga_id = ?",
        escalate,
        step,
        code,
        message.length() > 500 ? message.substring(0, 500) : message,
        Timestamp.from(now),
        sagaId);
  }

  private Row row(ResultSet rs, int n) throws SQLException {
    return new Row(
        rs.getString("saga_id"),
        rs.getString("case_id"),
        rs.getString("delivery_id"),
        rs.getString("origin_delivery_id"),
        rs.getString("order_id"),
        rs.getString("account_id"),
        rs.getLong("amount"),
        rs.getString("state"),
        rs.getString("current_step"),
        rs.getString("outcome"),
        rs.getString("payment_attempt_id"),
        rs.getInt("attempt_no"),
        rs.getTimestamp("deadline_at").toInstant(),
        rs.getString("last_error_code"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }
}
