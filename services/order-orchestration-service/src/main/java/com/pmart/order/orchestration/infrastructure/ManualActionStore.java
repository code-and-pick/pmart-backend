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

/**
 * 운영자 수동 개입 기록(V7, BO-12 2026-11-16)과 멈춘 사가 목록. 멈춘 사가는 다섯 사가 표(결제 진입·이행·취소·결품·오배송 재발송 — 재발송은
 * 2026-11-16 사용자 "운영자 처리 대기 목록에 재발송 건 추가해")를 합쳐 읽기만 한다(사가 상태는 각 사가 서비스가 정본).
 */
@Repository
public class ManualActionStore {

  public record StuckRow(
      String sagaId,
      String sagaType,
      String orderId,
      String state,
      String currentStep,
      @Nullable String lastErrorStep,
      @Nullable String lastErrorCode,
      @Nullable String lastErrorMessage,
      Instant updatedAt) {}

  public record ActionRow(
      String requestId,
      String sagaId,
      String action,
      String operatorId,
      String reason,
      String state,
      @Nullable String resultSummary,
      Instant requestedAt,
      @Nullable Instant finishedAt) {}

  private static final String STUCK =
      " state IN ('ESCALATED', 'RECONCILIATION_REQUIRED', 'UNKNOWN_TRACKING')"
          + " AND NOT EXISTS (SELECT 1 FROM saga_manual_resolution r WHERE r.saga_id = s.saga_id)";

  private static final String UNION =
      "SELECT * FROM ("
          + part("checkout_saga", "S1_CHECKOUT")
          + " UNION ALL "
          + part("fulfillment_saga", "S2_FULFILLMENT")
          + " UNION ALL "
          + part("cancellation_saga", "S3_CANCELLATION")
          + " UNION ALL "
          + part("shortage_saga", "SHORTAGE")
          + " UNION ALL "
          + reshipmentPart()
          + ") x";

  /**
   * 오배송 재발송 사가 — 주문을 찾기 전에 멈추면 주문 ID 가 없어 빈 값. 사람이 찾을 수 있게 오배송 사건·배송 ID 를 사유 앞에 붙인다(사건·배송 ID 는 개인정보
   * 아님).
   */
  private static String reshipmentPart() {
    return "SELECT s.saga_id, 'RESHIPMENT' AS saga_type, COALESCE(s.order_id, '') AS order_id,"
        + " s.state, s.current_step, s.last_error_step, s.last_error_code, '오배송 사건 ' ||"
        + " s.case_id || ' · 배송 ' || s.delivery_id || COALESCE(' — ' || s.last_error_message, '')"
        + " AS last_error_message, s.updated_at FROM reshipment_saga s WHERE"
        + STUCK;
  }

  private static String part(String table, String type) {
    return "SELECT s.saga_id, '"
        + type
        + "' AS saga_type, s.order_id, s.state, s.current_step, s.last_error_step,"
        + " s.last_error_code, s.last_error_message, s.updated_at FROM "
        + table
        + " s WHERE"
        + STUCK;
  }

  private final JdbcTemplate jdbc;

  public ManualActionStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 멈춘 사가 — 오래 멈춘 것부터. */
  public List<StuckRow> stuck(int offset, int limit) {
    return jdbc.query(
        UNION + " ORDER BY updated_at, saga_id LIMIT ? OFFSET ?", this::stuckRow, limit, offset);
  }

  /** 한 사가가 지금 멈춘 상태인가(처리 완료 표시 전). */
  public Optional<StuckRow> stuckOne(String sagaId) {
    return jdbc.query(UNION + " WHERE saga_id = ?", this::stuckRow, sagaId).stream().findFirst();
  }

  public void insertAction(ActionRow a) {
    jdbc.update(
        "INSERT INTO saga_manual_action (request_id, saga_id, action, operator_id, reason, state,"
            + " result_summary, requested_at, finished_at) VALUES (?,?,?,?,?,?,?,?,?)",
        a.requestId(),
        a.sagaId(),
        a.action(),
        a.operatorId(),
        a.reason(),
        a.state(),
        a.resultSummary(),
        Timestamp.from(a.requestedAt()),
        a.finishedAt() == null ? null : Timestamp.from(a.finishedAt()));
  }

  public Optional<ActionRow> action(String sagaId, String requestId) {
    return jdbc
        .query(
            "SELECT * FROM saga_manual_action WHERE saga_id = ? AND request_id = ?",
            (rs, i) ->
                new ActionRow(
                    rs.getString("request_id"),
                    rs.getString("saga_id"),
                    rs.getString("action"),
                    rs.getString("operator_id"),
                    rs.getString("reason"),
                    rs.getString("state"),
                    rs.getString("result_summary"),
                    rs.getTimestamp("requested_at").toInstant(),
                    rs.getTimestamp("finished_at") == null
                        ? null
                        : rs.getTimestamp("finished_at").toInstant()),
            sagaId,
            requestId)
        .stream()
        .findFirst();
  }

  /** 처리 완료 표시. 이미 있으면 false. */
  public boolean resolve(String sagaId, String by, String reason, String requestId, Instant at) {
    return jdbc.update(
            "INSERT INTO saga_manual_resolution (saga_id, resolved_by, reason, resolved_at, request_id)"
                + " VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING",
            sagaId,
            by,
            reason,
            Timestamp.from(at),
            requestId)
        == 1;
  }

  private StuckRow stuckRow(ResultSet rs, int i) throws SQLException {
    return new StuckRow(
        rs.getString("saga_id"),
        rs.getString("saga_type"),
        rs.getString("order_id"),
        rs.getString("state"),
        rs.getString("current_step"),
        rs.getString("last_error_step"),
        rs.getString("last_error_code"),
        rs.getString("last_error_message"),
        rs.getTimestamp("updated_at").toInstant());
  }
}
