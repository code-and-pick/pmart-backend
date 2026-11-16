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

/** 오배송 재발송 사가(V8, JDBC). 줄 묶음은 JSON 문자열 그대로 둔다(규칙 쪽이 읽고 쓴다). */
@Repository
public class ReshipmentStore {

  public record ReshipmentRow(
      String sagaId,
      String caseId,
      String deliveryId,
      @Nullable String orderId,
      @Nullable String mfcId,
      @Nullable String shipmentId,
      String linesJson,
      String reservedJson,
      String shortJson,
      int reserveAttempt,
      @Nullable String reservationId,
      @Nullable String fulfillmentId,
      @Nullable String claimId,
      String state,
      String currentStep,
      @Nullable String lastErrorStep,
      @Nullable String lastErrorCode,
      @Nullable String lastErrorMessage,
      @Nullable Instant confirmedAt,
      Instant startedAt,
      Instant updatedAt,
      @Nullable Instant finishedAt) {}

  private final JdbcTemplate jdbc;

  public ReshipmentStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 같은 사건(caseId)이면 만들지 않는다. true = 새로 만듦. */
  public boolean insert(
      String sagaId,
      String caseId,
      String deliveryId,
      @Nullable String orderId,
      @Nullable Instant confirmedAt,
      String firstStep,
      Instant now) {
    return jdbc.update(
            "INSERT INTO reshipment_saga (saga_id, case_id, delivery_id, order_id, state,"
                + " current_step, confirmed_at, started_at, updated_at) VALUES"
                + " (?,?,?,?,'RUNNING',?,?,?,?) ON CONFLICT (case_id) DO NOTHING",
            sagaId,
            caseId,
            deliveryId,
            orderId,
            firstStep,
            confirmedAt == null ? null : Timestamp.from(confirmedAt),
            Timestamp.from(now),
            Timestamp.from(now))
        == 1;
  }

  public Optional<ReshipmentRow> find(String sagaId) {
    return jdbc
        .query("SELECT * FROM reshipment_saga WHERE saga_id = ?", (rs, i) -> row(rs), sagaId)
        .stream()
        .findFirst();
  }

  public Optional<ReshipmentRow> lock(String sagaId) {
    return jdbc
        .query(
            "SELECT * FROM reshipment_saga WHERE saga_id = ? FOR UPDATE SKIP LOCKED",
            (rs, i) -> row(rs),
            sagaId)
        .stream()
        .findFirst();
  }

  public Optional<ReshipmentRow> byCase(String caseId) {
    return jdbc
        .query("SELECT * FROM reshipment_saga WHERE case_id = ?", (rs, i) -> row(rs), caseId)
        .stream()
        .findFirst();
  }

  public List<ReshipmentRow> byOrder(String orderId) {
    return jdbc.query(
        "SELECT * FROM reshipment_saga WHERE order_id = ? ORDER BY started_at",
        (rs, i) -> row(rs),
        orderId);
  }

  public List<String> due(Instant stuckBefore) {
    return jdbc.queryForList(
        "SELECT saga_id FROM reshipment_saga WHERE state = 'RUNNING' AND updated_at < ?"
            + " ORDER BY updated_at LIMIT 100",
        String.class,
        Timestamp.from(stuckBefore));
  }

  /** 주문 찾음 — 주문·센터·출고·보낼 줄. */
  public void resolved(
      String sagaId,
      String orderId,
      String mfcId,
      String shipmentId,
      String linesJson,
      String nextStep,
      Instant at) {
    jdbc.update(
        "UPDATE reshipment_saga SET order_id = ?, mfc_id = ?, shipment_id = ?, lines = ?::jsonb,"
            + " current_step = ?, updated_at = ? WHERE saga_id = ?",
        orderId,
        mfcId,
        shipmentId,
        linesJson,
        nextStep,
        Timestamp.from(at),
        sagaId);
  }

  /** 예약 결과 — 예약된 줄·못 보내는 줄·시도 수(줄여 다시 예약할 때). */
  public void reservation(
      String sagaId,
      @Nullable String reservationId,
      String reservedJson,
      String shortJson,
      int attempt,
      String step,
      Instant at) {
    jdbc.update(
        "UPDATE reshipment_saga SET reservation_id = ?, reserved = ?::jsonb, short_lines ="
            + " ?::jsonb, reserve_attempt = ?, current_step = ?, updated_at = ? WHERE saga_id = ?",
        reservationId,
        reservedJson,
        shortJson,
        attempt,
        step,
        Timestamp.from(at),
        sagaId);
  }

  public void work(String sagaId, String fulfillmentId, String step, Instant at) {
    jdbc.update(
        "UPDATE reshipment_saga SET fulfillment_id = ?, current_step = ?, updated_at = ? WHERE"
            + " saga_id = ?",
        fulfillmentId,
        step,
        Timestamp.from(at),
        sagaId);
  }

  public void step(String sagaId, String step, Instant at) {
    jdbc.update(
        "UPDATE reshipment_saga SET current_step = ?, updated_at = ? WHERE saga_id = ?",
        step,
        Timestamp.from(at),
        sagaId);
  }

  public void touch(String sagaId, Instant at) {
    jdbc.update(
        "UPDATE reshipment_saga SET updated_at = ? WHERE saga_id = ?", Timestamp.from(at), sagaId);
  }

  public void finish(
      String sagaId,
      String state,
      @Nullable String claimId,
      @Nullable String errorStep,
      @Nullable String errorCode,
      @Nullable String errorMessage,
      Instant at) {
    jdbc.update(
        "UPDATE reshipment_saga SET state = ?, claim_id = COALESCE(?, claim_id), last_error_step"
            + " = ?, last_error_code = ?, last_error_message = ?, updated_at = ?, finished_at = ?"
            + " WHERE saga_id = ?",
        state,
        claimId,
        errorStep,
        errorCode,
        errorMessage == null
            ? null
            : errorMessage.substring(0, Math.min(500, errorMessage.length())),
        Timestamp.from(at),
        Timestamp.from(at),
        sagaId);
  }

  private static ReshipmentRow row(ResultSet rs) throws SQLException {
    Timestamp f = rs.getTimestamp("finished_at");
    Timestamp c = rs.getTimestamp("confirmed_at");
    return new ReshipmentRow(
        rs.getString("saga_id"),
        rs.getString("case_id"),
        rs.getString("delivery_id"),
        rs.getString("order_id"),
        rs.getString("mfc_id"),
        rs.getString("shipment_id"),
        rs.getString("lines"),
        rs.getString("reserved"),
        rs.getString("short_lines"),
        rs.getInt("reserve_attempt"),
        rs.getString("reservation_id"),
        rs.getString("fulfillment_id"),
        rs.getString("claim_id"),
        rs.getString("state"),
        rs.getString("current_step"),
        rs.getString("last_error_step"),
        rs.getString("last_error_code"),
        rs.getString("last_error_message"),
        c == null ? null : c.toInstant(),
        rs.getTimestamp("started_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant(),
        f == null ? null : f.toInstant());
  }
}
