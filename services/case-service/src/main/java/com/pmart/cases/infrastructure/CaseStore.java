package com.pmart.cases.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 사건함(V4 — 표 {@code case_record}·{@code case_history}, 2026-11-16). 고객 문의 행(CS_INQUIRY)은
 * InquiryStore 가 넣는다.
 */
@Repository
public class CaseStore {

  /** 참조 하나(incidentId·deliveryId·orderId·riderId …). */
  public record Ref(String refType, String refId) {}

  public record CaseRow(
      String caseId,
      String kind,
      String state,
      @Nullable String priority,
      @Nullable String subjectType,
      @Nullable String subjectRef,
      @Nullable String centerId,
      @Nullable String assigneeId,
      @Nullable String assigneeGroup,
      @Nullable Instant slaDueAt,
      @Nullable String summary,
      List<Ref> refs,
      @Nullable String sourceEventId,
      int version,
      Instant openedAt,
      Instant updatedAt,
      @Nullable Instant closedAt,
      @Nullable String closedBy,
      @Nullable String resolution,
      @Nullable String closureSummary) {}

  public record HistoryRow(
      Instant at,
      String actorId,
      String action,
      @Nullable String fromState,
      @Nullable String toState,
      @Nullable String note) {}

  /** 목록 조건. kinds = 볼 수 있는 종류(비면 결과 없음). */
  public record Filter(
      Collection<String> kinds,
      @Nullable String kind,
      @Nullable String state,
      @Nullable String centerId,
      @Nullable String assigneeId,
      @Nullable String priority,
      @Nullable String subjectRef,
      @Nullable String assigneeGroup,
      boolean unassigned,
      boolean overdue,
      Instant now,
      /** null 이 아니면 이 센터들의 사건만(센터 관리자 — 2026-11-16). 비어 있으면 하나도 없음. */
      @Nullable Collection<String> centers) {}

  private static final TypeReference<List<Ref>> REFS = new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public CaseStore(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  /** 새 사건. 같은 dedupe_key 로 열린 사건·같은 원 사건이 있으면 false(아무것도 넣지 않음). */
  public boolean insert(CaseRow c, @Nullable String dedupeKey, @Nullable String sourceEventType) {
    return jdbc.update(
            "INSERT INTO case_record (case_id, case_type, state, priority, subject_type, subject_ref, center_id,"
                + " assignee_id, assignee_group, sla_due_at, summary, refs, source_event_id, source_event_type,"
                + " dedupe_key, version, created_at, updated_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?,?) ON CONFLICT DO NOTHING",
            c.caseId(),
            c.kind(),
            c.state(),
            c.priority(),
            c.subjectType(),
            c.subjectRef(),
            c.centerId(),
            c.assigneeId(),
            c.assigneeGroup(),
            ts(c.slaDueAt()),
            c.summary(),
            mapper.writeValueAsString(c.refs()),
            c.sourceEventId(),
            sourceEventType,
            dedupeKey,
            c.version(),
            ts(c.openedAt()),
            ts(c.updatedAt()))
        == 1;
  }

  /** 같은 dedupe_key 로 열린 사건(잠금). */
  public Optional<CaseRow> lockOpenByDedupe(String dedupeKey) {
    return jdbc
        .query(
            SELECT + " WHERE dedupe_key = ? AND state <> 'CLOSED' FOR UPDATE", this::row, dedupeKey)
        .stream()
        .findFirst();
  }

  public Optional<CaseRow> find(String caseId) {
    return jdbc.query(SELECT + " WHERE case_id = ?", this::row, caseId).stream().findFirst();
  }

  public Optional<CaseRow> lock(String caseId) {
    return jdbc.query(SELECT + " WHERE case_id = ? FOR UPDATE", this::row, caseId).stream()
        .findFirst();
  }

  public void assign(String caseId, String assigneeId, String state, Instant at) {
    jdbc.update(
        "UPDATE case_record SET assignee_id = ?, state = ?, version = version + 1, updated_at = ?"
            + " WHERE case_id = ?",
        assigneeId,
        state,
        ts(at),
        caseId);
  }

  public void close(String caseId, String by, String resolution, String summary, Instant at) {
    jdbc.update(
        "UPDATE case_record SET state = 'CLOSED', closed_at = ?, closed_by = ?, resolution = ?,"
            + " closure_summary = ?, version = version + 1, updated_at = ? WHERE case_id = ?",
        ts(at),
        by,
        resolution,
        summary,
        ts(at),
        caseId);
  }

  /** 배송별 배차 실패 횟수를 하나 올리고 올린 뒤 값을 돌려준다(V5). */
  public int countDispatchFailure(String deliveryId, String reason, Instant at) {
    Integer n =
        jdbc.queryForObject(
            "INSERT INTO dispatch_failure_watch (delivery_id, failures, first_failed_at, last_failed_at,"
                + " last_reason) VALUES (?, 1, ?, ?, ?) ON CONFLICT (delivery_id) DO UPDATE SET"
                + " failures = dispatch_failure_watch.failures + 1, last_failed_at = EXCLUDED.last_failed_at,"
                + " last_reason = EXCLUDED.last_reason RETURNING failures",
            Integer.class,
            deliveryId,
            ts(at),
            ts(at),
            reason);
    return n == null ? 1 : n;
  }

  public void touch(String caseId, Instant at) {
    jdbc.update(
        "UPDATE case_record SET version = version + 1, updated_at = ? WHERE case_id = ?",
        ts(at),
        caseId);
  }

  public void history(
      String caseId,
      Instant at,
      String actorId,
      String action,
      @Nullable String from,
      @Nullable String to,
      @Nullable String note) {
    jdbc.update(
        "INSERT INTO case_history (case_id, at, actor_id, action, from_state, to_state, note)"
            + " VALUES (?,?,?,?,?,?,?)",
        caseId,
        ts(at),
        actorId,
        action,
        from,
        to,
        note);
  }

  public List<HistoryRow> historyOf(String caseId) {
    return jdbc.query(
        "SELECT at, actor_id, action, from_state, to_state, note FROM case_history WHERE case_id = ?"
            + " ORDER BY at, history_id",
        (rs, i) ->
            new HistoryRow(
                rs.getTimestamp("at").toInstant(),
                rs.getString("actor_id"),
                rs.getString("action"),
                rs.getString("from_state"),
                rs.getString("to_state"),
                rs.getString("note")),
        caseId);
  }

  /**
   * 사건함 한 쪽 — 열린 것은 P1 먼저, 기한 이른 것 먼저, 그다음 오래된 것. 닫힌 것(state=CLOSED)은 닫힌 시각 최근 순(0.5.1 — 통합 조회의 "지난
   * 사건"). 커서 = 건너뛸 수.
   */
  public List<CaseRow> page(Filter f, int offset, int limit) {
    if (f.kinds().isEmpty()) {
      return List.of();
    }
    StringBuilder sql = new StringBuilder(SELECT + " WHERE case_type IN (");
    List<Object> args = new ArrayList<>();
    String sep = "";
    for (String k : f.kinds()) {
      sql.append(sep).append('?');
      args.add(k);
      sep = ",";
    }
    sql.append(')');
    if (f.state() != null) {
      eq(sql, args, "state", f.state());
    } else {
      sql.append(" AND state <> 'CLOSED'");
    }
    eq(sql, args, "case_type", f.kind());
    eq(sql, args, "center_id", f.centerId());
    if (f.centers() != null) {
      if (f.centers().isEmpty()) {
        return List.of();
      }
      sql.append(" AND center_id IN (");
      String csep = "";
      for (String c : f.centers()) {
        sql.append(csep).append('?');
        args.add(c);
        csep = ",";
      }
      sql.append(')');
    }
    eq(sql, args, "assignee_id", f.assigneeId());
    eq(sql, args, "priority", f.priority());
    eq(sql, args, "subject_ref", f.subjectRef());
    eq(sql, args, "assignee_group", f.assigneeGroup());
    if (f.unassigned()) {
      sql.append(" AND assignee_id IS NULL");
    }
    if (f.overdue()) {
      sql.append(" AND state <> 'CLOSED' AND sla_due_at IS NOT NULL AND sla_due_at < ?");
      args.add(ts(f.now()));
    }
    sql.append(
        "CLOSED".equals(f.state())
            ? " ORDER BY closed_at DESC NULLS LAST, case_id DESC LIMIT ? OFFSET ?"
            : " ORDER BY priority NULLS LAST, sla_due_at NULLS LAST, created_at, case_id LIMIT ? OFFSET ?");
    args.add(limit);
    args.add(offset);
    return jdbc.query(sql.toString(), this::row, args.toArray());
  }

  private static void eq(StringBuilder sql, List<Object> args, String col, @Nullable String v) {
    if (v != null) {
      sql.append(" AND ").append(col).append(" = ?");
      args.add(v);
    }
  }

  private static final String SELECT =
      "SELECT case_id, case_type, state, priority, subject_type, subject_ref, center_id, assignee_id,"
          + " assignee_group, sla_due_at, summary, refs::text AS refs, source_event_id, version,"
          + " created_at, updated_at, closed_at, closed_by, resolution, closure_summary FROM case_record";

  private CaseRow row(ResultSet rs, int i) throws SQLException {
    String refs = rs.getString("refs");
    return new CaseRow(
        rs.getString("case_id"),
        rs.getString("case_type"),
        rs.getString("state"),
        rs.getString("priority"),
        rs.getString("subject_type"),
        rs.getString("subject_ref"),
        rs.getString("center_id"),
        rs.getString("assignee_id"),
        rs.getString("assignee_group"),
        instant(rs, "sla_due_at"),
        rs.getString("summary"),
        refs == null ? List.of() : mapper.readValue(refs, REFS),
        rs.getString("source_event_id"),
        rs.getInt("version"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant(),
        instant(rs, "closed_at"),
        rs.getString("closed_by"),
        rs.getString("resolution"),
        rs.getString("closure_summary"));
  }

  /** 사건 payload 의 refs 모양. */
  public static List<Map<String, String>> refsPayload(List<Ref> refs) {
    return refs.stream().map(r -> Map.of("refType", r.refType(), "refId", r.refId())).toList();
  }

  private static @Nullable Instant instant(ResultSet rs, String col) throws SQLException {
    Timestamp t = rs.getTimestamp(col);
    return t == null ? null : t.toInstant();
  }

  private static @Nullable Timestamp ts(@Nullable Instant i) {
    return i == null ? null : Timestamp.from(i);
  }
}
