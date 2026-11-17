package com.pmart.cases.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** 행위 요청 기록(V15 {@code case_action_request}, 0.17.0). */
@Repository
public class ActionRequestStore {

  public record ActionRow(
      String actionRequestId,
      String caseId,
      String actionType,
      String targetService,
      String command,
      @Nullable String targetRef,
      @Nullable String idempotencyKey,
      @Nullable String approvalRef,
      @Nullable Map<String, Object> payloadSummary,
      String state,
      @Nullable String resultSystem,
      @Nullable String resultRef,
      @Nullable String resultKind,
      @Nullable String resultNote,
      String requestedBy,
      Instant requestedAt,
      @Nullable String resultBy,
      @Nullable Instant resultAt,
      int version) {}

  private static final String COLS =
      "action_request_id, case_id, action_type, target_service, command, target_ref,"
          + " idempotency_key, approval_ref, payload_summary, state, result_system, result_ref,"
          + " result_kind, result_note, requested_by, requested_at, result_by, result_at, version";

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public ActionRequestStore(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  public void insert(ActionRow r) {
    jdbc.update(
        "INSERT INTO case_action_request ("
            + COLS
            + ") VALUES (?,?,?,?,?,?,?,?,?::jsonb,?,NULL,NULL,NULL,NULL,?,?,NULL,NULL,0)",
        r.actionRequestId(),
        r.caseId(),
        r.actionType(),
        r.targetService(),
        r.command(),
        r.targetRef(),
        r.idempotencyKey(),
        r.approvalRef(),
        r.payloadSummary() == null ? null : mapper.writeValueAsString(r.payloadSummary()),
        r.state(),
        r.requestedBy(),
        Timestamp.from(r.requestedAt()));
  }

  public Optional<ActionRow> find(String caseId, String actionRequestId) {
    return jdbc
        .query(
            "SELECT "
                + COLS
                + " FROM case_action_request WHERE case_id = ? AND action_request_id = ?",
            this::row,
            caseId,
            actionRequestId)
        .stream()
        .findFirst();
  }

  public Optional<ActionRow> lock(String caseId, String actionRequestId) {
    return jdbc
        .query(
            "SELECT "
                + COLS
                + " FROM case_action_request WHERE case_id = ? AND action_request_id = ?"
                + " FOR UPDATE",
            this::row,
            caseId,
            actionRequestId)
        .stream()
        .findFirst();
  }

  public List<ActionRow> forCase(String caseId) {
    return jdbc.query(
        "SELECT " + COLS + " FROM case_action_request WHERE case_id = ? ORDER BY requested_at",
        this::row,
        caseId);
  }

  public void recordResult(
      String actionRequestId,
      String state,
      @Nullable String system,
      @Nullable String ref,
      @Nullable String kind,
      @Nullable String note,
      String by,
      Instant at) {
    jdbc.update(
        "UPDATE case_action_request SET state = ?, result_system = coalesce(?, result_system),"
            + " result_ref = coalesce(?, result_ref), result_kind = coalesce(?, result_kind),"
            + " result_note = ?, result_by = ?, result_at = ?, version = version + 1"
            + " WHERE action_request_id = ?",
        state,
        system,
        ref,
        kind,
        note,
        by,
        Timestamp.from(at),
        actionRequestId);
  }

  private ActionRow row(ResultSet rs, int n) throws SQLException {
    String payload = rs.getString("payload_summary");
    Timestamp resultAt = rs.getTimestamp("result_at");
    return new ActionRow(
        rs.getString("action_request_id"),
        rs.getString("case_id"),
        rs.getString("action_type"),
        rs.getString("target_service"),
        rs.getString("command"),
        rs.getString("target_ref"),
        rs.getString("idempotency_key"),
        rs.getString("approval_ref"),
        payload == null
            ? null
            : mapper.readValue(payload, new TypeReference<Map<String, Object>>() {}),
        rs.getString("state"),
        rs.getString("result_system"),
        rs.getString("result_ref"),
        rs.getString("result_kind"),
        rs.getString("result_note"),
        rs.getString("requested_by"),
        rs.getTimestamp("requested_at").toInstant(),
        rs.getString("result_by"),
        resultAt == null ? null : resultAt.toInstant(),
        rs.getInt("version"));
  }
}
