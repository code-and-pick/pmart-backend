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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** S3 취소 사가 진행 사본(JDBC). 단계 실행 중에는 사가 행을 FOR UPDATE 로 잡는다. */
@Repository
public class CancellationStore {

  public record CancellationRow(
      String sagaId,
      String claimId,
      String stopRequestId,
      String orderId,
      String scope,
      @Nullable String reason,
      JsonNode lines,
      String state,
      String currentStep,
      int version,
      @Nullable JsonNode stopResult,
      @Nullable JsonNode resources,
      @Nullable String lastErrorStep,
      @Nullable String lastErrorCode,
      @Nullable String lastErrorMessage,
      Instant startedAt,
      Instant updatedAt,
      @Nullable Instant finishedAt) {}

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public CancellationStore(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  public void insert(CancellationRow r) {
    jdbc.update(
        "INSERT INTO cancellation_saga (saga_id, claim_id, stop_request_id, order_id, scope, reason,"
            + " lines, state, current_step, version, started_at, updated_at)"
            + " VALUES (?,?,?,?,?,?,?::jsonb,?,?,0,?,?)",
        r.sagaId(),
        r.claimId(),
        r.stopRequestId(),
        r.orderId(),
        r.scope(),
        r.reason(),
        mapper.writeValueAsString(r.lines()),
        r.state(),
        r.currentStep(),
        ts(r.startedAt()),
        ts(r.updatedAt()));
  }

  public Optional<CancellationRow> find(String sagaId) {
    return one("SELECT * FROM cancellation_saga WHERE saga_id = ?", sagaId);
  }

  public Optional<CancellationRow> lock(String sagaId) {
    return one("SELECT * FROM cancellation_saga WHERE saga_id = ? FOR UPDATE", sagaId);
  }

  public Optional<CancellationRow> byStopRequest(String stopRequestId) {
    return one("SELECT * FROM cancellation_saga WHERE stop_request_id = ?", stopRequestId);
  }

  public List<CancellationRow> byOrder(String orderId) {
    return jdbc.query(
        "SELECT * FROM cancellation_saga WHERE order_id = ? ORDER BY started_at DESC",
        (rs, i) -> row(rs),
        orderId);
  }

  /** 멈춘 RUNNING·결과 대기 사가(재개 대상). */
  public List<String> due(Instant stuckBefore) {
    return jdbc.queryForList(
        "SELECT saga_id FROM cancellation_saga WHERE state IN ('RUNNING', 'WAITING_RESULT')"
            + " AND updated_at < ? ORDER BY updated_at LIMIT 50",
        String.class,
        ts(stuckBefore));
  }

  public void update(
      String sagaId,
      String state,
      String currentStep,
      @Nullable JsonNode stopResult,
      @Nullable JsonNode resources,
      @Nullable String errorStep,
      @Nullable String errorCode,
      @Nullable String errorMessage,
      Instant now,
      boolean finished) {
    jdbc.update(
        "UPDATE cancellation_saga SET state = ?, current_step = ?, stop_result = ?::jsonb,"
            + " resources = ?::jsonb, last_error_step = ?, last_error_code = ?,"
            + " last_error_message = ?, version = version + 1, updated_at = ?, finished_at = ?"
            + " WHERE saga_id = ?",
        state,
        currentStep,
        stopResult == null ? null : mapper.writeValueAsString(stopResult),
        resources == null ? null : mapper.writeValueAsString(resources),
        errorStep,
        errorCode,
        clip(errorMessage),
        ts(now),
        finished ? ts(now) : null,
        sagaId);
  }

  /**
   * RELEASE_RESOURCES 에서 슬롯 점유가 정본에 없어(SLOT_HOLD_NOT_FOUND) 상신된 사가를 RUNNING 으로 되돌린다(2026-11-25). 이제
   * 그 거절은 "풀 것 없음"이라 같은 단계를 다시 돌면 지나간다. 운영자가 처리 완료(saga_manual_resolution)로 표시한 사가는 건드리지 않는다.
   * updated_at 을 옛 시각으로 두어 이번 sweep 의 due 가 바로 집는다.
   */
  public List<String> reopenSlotHoldMissing() {
    return jdbc.queryForList(
        "UPDATE cancellation_saga c SET state = 'RUNNING', last_error_step = NULL,"
            + " last_error_code = NULL, last_error_message = NULL, version = version + 1,"
            + " updated_at = ? WHERE c.state = 'ESCALATED' AND c.current_step = 'RELEASE_RESOURCES'"
            + " AND c.last_error_code = 'SLOT_HOLD_NOT_FOUND' AND NOT EXISTS (SELECT 1 FROM"
            + " saga_manual_resolution m WHERE m.saga_id = c.saga_id) RETURNING c.saga_id",
        String.class,
        ts(Instant.EPOCH));
  }

  /** 진행 표시만 갱신(멈춤 감시 기준 시각). */
  public void touch(String sagaId, Instant now) {
    jdbc.update("UPDATE cancellation_saga SET updated_at = ? WHERE saga_id = ?", ts(now), sagaId);
  }

  private Optional<CancellationRow> one(String sql, Object arg) {
    return jdbc.query(sql, (rs, i) -> row(rs), arg).stream().findFirst();
  }

  private CancellationRow row(ResultSet rs) throws SQLException {
    return new CancellationRow(
        rs.getString("saga_id"),
        rs.getString("claim_id"),
        rs.getString("stop_request_id"),
        rs.getString("order_id"),
        rs.getString("scope"),
        rs.getString("reason"),
        mapper.readTree(rs.getString("lines")),
        rs.getString("state"),
        rs.getString("current_step"),
        rs.getInt("version"),
        json(rs.getString("stop_result")),
        json(rs.getString("resources")),
        rs.getString("last_error_step"),
        rs.getString("last_error_code"),
        rs.getString("last_error_message"),
        rs.getTimestamp("started_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant(),
        instant(rs.getTimestamp("finished_at")));
  }

  private @Nullable JsonNode json(@Nullable String raw) {
    return raw == null ? null : mapper.readTree(raw);
  }

  private static @Nullable Instant instant(@Nullable Timestamp t) {
    return t == null ? null : t.toInstant();
  }

  private static @Nullable Timestamp ts(@Nullable Instant i) {
    return i == null ? null : Timestamp.from(i);
  }

  private static @Nullable String clip(@Nullable String s) {
    return s == null || s.length() <= 500 ? s : s.substring(0, 500);
  }
}
