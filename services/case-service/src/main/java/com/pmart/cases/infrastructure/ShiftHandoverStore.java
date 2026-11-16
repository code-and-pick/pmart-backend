package com.pmart.cases.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** 교대 인계서(V8 — 계약 0.9.0). 표 {@code shift_handover}·{@code shift_handover_item}. */
@Repository
public class ShiftHandoverStore {

  public record HandoverRow(
      String handoverId,
      String group,
      @Nullable String centerId,
      String fromOperatorId,
      String toOperatorId,
      String createdBy,
      String state,
      @Nullable String note,
      List<Map<String, Object>> teamSnapshot,
      int version,
      Instant createdAt,
      @Nullable Instant completedAt,
      @Nullable Instant cancelledAt) {}

  public record ItemRow(
      String caseId,
      String nextAction,
      @Nullable Instant checkDueAt,
      String caseStateAt,
      @Nullable String priorityAt,
      int openObligationsAt,
      String outcome,
      @Nullable Instant resolvedAt) {}

  private static final TypeReference<List<Map<String, Object>>> SNAPSHOT = new TypeReference<>() {};
  private static final String SELECT =
      "SELECT handover_id, group_code, center_id, from_operator_id, to_operator_id, created_by, state, note,"
          + " team_snapshot::text, version, created_at, completed_at, cancelled_at FROM shift_handover h";

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public ShiftHandoverStore(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  public void insert(HandoverRow h) {
    jdbc.update(
        "INSERT INTO shift_handover (handover_id, group_code, center_id, from_operator_id, to_operator_id,"
            + " created_by, state, note, team_snapshot, version, created_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?::jsonb,?,?)",
        h.handoverId(),
        h.group(),
        h.centerId(),
        h.fromOperatorId(),
        h.toOperatorId(),
        h.createdBy(),
        h.state(),
        h.note(),
        mapper.writeValueAsString(h.teamSnapshot()),
        h.version(),
        ts(h.createdAt()));
  }

  /** 항목 넣기. 그 건이 다른 진행 중 인계서에 있으면 유일 색인에 걸려 DuplicateKeyException. */
  public void insertItem(String handoverId, ItemRow i) {
    jdbc.update(
        "INSERT INTO shift_handover_item (handover_id, case_id, next_action, check_due_at, case_state_at,"
            + " priority_at, open_obligations_at, outcome) VALUES (?,?,?,?,?,?,?,?)",
        handoverId,
        i.caseId(),
        i.nextAction(),
        ts(i.checkDueAt()),
        i.caseStateAt(),
        i.priorityAt(),
        i.openObligationsAt(),
        i.outcome());
  }

  public Optional<HandoverRow> find(String id) {
    return jdbc.query(SELECT + " WHERE handover_id = ?", this::row, id).stream().findFirst();
  }

  public Optional<HandoverRow> lock(String id) {
    return jdbc.query(SELECT + " WHERE handover_id = ? FOR UPDATE", this::row, id).stream()
        .findFirst();
  }

  public List<ItemRow> items(String handoverId) {
    return jdbc.query(
        "SELECT case_id, next_action, check_due_at, case_state_at, priority_at, open_obligations_at,"
            + " outcome, resolved_at FROM shift_handover_item WHERE handover_id = ? ORDER BY case_id",
        (rs, n) ->
            new ItemRow(
                rs.getString(1),
                rs.getString(2),
                instant(rs.getTimestamp(3)),
                rs.getString(4),
                rs.getString(5),
                rs.getInt(6),
                rs.getString(7),
                instant(rs.getTimestamp(8))),
        handoverId);
  }

  public void resolveItem(String handoverId, String caseId, String outcome, Instant at) {
    jdbc.update(
        "UPDATE shift_handover_item SET outcome = ?, resolved_at = ? WHERE handover_id = ? AND case_id = ?",
        outcome,
        ts(at),
        handoverId,
        caseId);
  }

  public void complete(String handoverId, Instant at) {
    jdbc.update(
        "UPDATE shift_handover SET state = 'COMPLETED', completed_at = ?, version = version + 1"
            + " WHERE handover_id = ?",
        ts(at),
        handoverId);
  }

  public void cancel(String handoverId, String by, Instant at) {
    jdbc.update(
        "UPDATE shift_handover SET state = 'CANCELLED', cancelled_at = ?, cancelled_by = ?,"
            + " version = version + 1 WHERE handover_id = ?",
        ts(at),
        by,
        handoverId);
    jdbc.update(
        "UPDATE shift_handover_item SET outcome = 'CANCELLED', resolved_at = ?"
            + " WHERE handover_id = ? AND outcome = 'PENDING'",
        ts(at),
        handoverId);
  }

  /** 미인수 — 수락 전이거나 빠진(EXCLUDED) 건이 있는가. */
  public boolean unreceived(HandoverRow h) {
    if ("PENDING".equals(h.state())) {
      return true;
    }
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM shift_handover_item WHERE handover_id = ? AND outcome = 'EXCLUDED'",
            Integer.class,
            h.handoverId());
    return n != null && n > 0;
  }

  /** 목록 — operator 가 null 이면 모두(관리자), 아니면 전임·후임이 그 사람인 것. 최근 것부터. */
  public List<HandoverRow> page(
      @Nullable String operator,
      @Nullable String state,
      boolean unreceivedOnly,
      int offset,
      int limit) {
    StringBuilder sql = new StringBuilder(SELECT + " WHERE 1=1");
    List<Object> args = new ArrayList<>();
    if (operator != null) {
      sql.append(" AND (h.from_operator_id = ? OR h.to_operator_id = ?)");
      args.add(operator);
      args.add(operator);
    }
    if (state != null) {
      sql.append(" AND h.state = ?");
      args.add(state);
    }
    if (unreceivedOnly) {
      sql.append(
          " AND (h.state = 'PENDING' OR EXISTS (SELECT 1 FROM shift_handover_item i"
              + " WHERE i.handover_id = h.handover_id AND i.outcome = 'EXCLUDED'))");
    }
    sql.append(" ORDER BY h.created_at DESC, h.handover_id LIMIT ? OFFSET ?");
    args.add(limit);
    args.add(offset);
    return jdbc.query(sql.toString(), this::row, args.toArray());
  }

  /** CloseSnapshot — 그 팀의 열린 접수 건(최대 200)과 열린 후속 처리 수. */
  public List<Map<String, Object>> teamSnapshot(String group) {
    return jdbc.query(
        "SELECT c.case_id, c.case_type, c.state, c.priority, c.assignee_id,"
            + " (SELECT count(*) FROM case_obligation o WHERE o.case_id = c.case_id"
            + "   AND o.state IN ('OPEN', 'PARTIALLY_RESOLVED')) AS open_obl"
            + " FROM case_record c WHERE c.assignee_group = ? AND c.state <> 'CLOSED'"
            + " AND c.case_type <> 'CS_INQUIRY' ORDER BY c.created_at LIMIT 200",
        (rs, n) -> {
          Map<String, Object> m = new java.util.LinkedHashMap<>();
          m.put("caseId", rs.getString(1));
          m.put("kind", rs.getString(2));
          m.put("state", rs.getString(3));
          m.put("priority", rs.getString(4));
          m.put("assigneeId", rs.getString(5));
          m.put("openObligationCount", rs.getInt(6));
          return m;
        },
        group);
  }

  private HandoverRow row(ResultSet rs, int n) throws SQLException {
    return new HandoverRow(
        rs.getString(1),
        rs.getString(2),
        rs.getString(3),
        rs.getString(4),
        rs.getString(5),
        rs.getString(6),
        rs.getString(7),
        rs.getString(8),
        mapper.readValue(rs.getString(9), SNAPSHOT),
        rs.getInt(10),
        Objects.requireNonNull(instant(rs.getTimestamp(11))),
        instant(rs.getTimestamp(12)),
        instant(rs.getTimestamp(13)));
  }

  private static @Nullable Timestamp ts(@Nullable Instant i) {
    return i == null ? null : Timestamp.from(i);
  }

  private static @Nullable Instant instant(@Nullable Timestamp t) {
    return t == null ? null : t.toInstant();
  }
}
