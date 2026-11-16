package com.pmart.cases.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 사건 의무(V7 — 계약 0.8.0). 표 {@code case_obligation}·{@code case_obligation_resolution}. */
@Repository
public class ObligationStore {

  public record ObligationRow(
      String obligationId,
      String caseId,
      String kind,
      String owner,
      @Nullable String targetRef,
      @Nullable Integer qty,
      @Nullable Long amount,
      @Nullable Instant dueAt,
      @Nullable String note,
      String state,
      int resolvedQty,
      long resolvedAmount,
      int version,
      String createdBy,
      Instant createdAt,
      Instant updatedAt) {}

  public record ResolutionRow(
      String outcome,
      @Nullable Integer qty,
      @Nullable Long amount,
      String evidenceSystem,
      String evidenceRef,
      @Nullable String evidenceKind,
      @Nullable Instant evidenceAsOf,
      @Nullable String note,
      String resolvedBy,
      Instant resolvedAt) {}

  /** 목록 조건. kinds = 볼 수 있는 사건 종류, centers 가 null 이 아니면 그 센터 사건만(센터 관리자). */
  public record Filter(
      Collection<String> kinds,
      @Nullable Collection<String> centers,
      @Nullable String owner,
      Collection<String> states,
      @Nullable String caseId) {}

  private static final String SELECT =
      "SELECT o.obligation_id, o.case_id, o.kind, o.owner, o.target_ref, o.qty, o.amount, o.due_at, o.note,"
          + " o.state, o.resolved_qty, o.resolved_amount, o.version, o.created_by, o.created_at, o.updated_at"
          + " FROM case_obligation o";

  private final JdbcTemplate jdbc;

  public ObligationStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(ObligationRow o) {
    jdbc.update(
        "INSERT INTO case_obligation (obligation_id, case_id, kind, owner, target_ref, qty, amount, due_at,"
            + " note, state, resolved_qty, resolved_amount, version, created_by, created_at, updated_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        o.obligationId(),
        o.caseId(),
        o.kind(),
        o.owner(),
        o.targetRef(),
        o.qty(),
        o.amount(),
        ts(o.dueAt()),
        o.note(),
        o.state(),
        o.resolvedQty(),
        o.resolvedAmount(),
        o.version(),
        o.createdBy(),
        ts(o.createdAt()),
        ts(o.updatedAt()));
  }

  public Optional<ObligationRow> find(String obligationId) {
    return jdbc.query(SELECT + " WHERE o.obligation_id = ?", this::row, obligationId).stream()
        .findFirst();
  }

  public Optional<ObligationRow> lock(String obligationId) {
    return jdbc
        .query(SELECT + " WHERE o.obligation_id = ? FOR UPDATE", this::row, obligationId)
        .stream()
        .findFirst();
  }

  public List<ObligationRow> forCase(String caseId) {
    return jdbc.query(SELECT + " WHERE o.case_id = ? ORDER BY o.created_at", this::row, caseId);
  }

  public int countOpen(String caseId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM case_obligation WHERE case_id = ?"
                + " AND state IN ('OPEN', 'PARTIALLY_RESOLVED')",
            Integer.class,
            caseId);
    return n == null ? 0 : n;
  }

  /** 사건마다 열린 의무 수(목록 openObligationCount). */
  public Map<String, Integer> countOpen(Collection<String> caseIds) {
    Map<String, Integer> out = new HashMap<>();
    if (caseIds.isEmpty()) {
      return out;
    }
    String in = String.join(",", caseIds.stream().map(x -> "?").toList());
    jdbc.query(
        "SELECT case_id, count(*) FROM case_obligation WHERE state IN ('OPEN', 'PARTIALLY_RESOLVED')"
            + " AND case_id IN ("
            + in
            + ") GROUP BY case_id",
        rs -> {
          out.put(rs.getString(1), rs.getInt(2));
        },
        caseIds.toArray());
    return out;
  }

  /** 볼 수 있는 사건의 의무 — 기한 이른 것(없으면 뒤), 오래된 것 먼저. */
  public List<ObligationRow> page(Filter f, int offset, int limit) {
    if (f.kinds().isEmpty()
        || f.states().isEmpty()
        || (f.centers() != null && f.centers().isEmpty())) {
      return List.of();
    }
    StringBuilder sql =
        new StringBuilder(SELECT + " JOIN case_record c ON c.case_id = o.case_id WHERE");
    List<Object> args = new ArrayList<>();
    sql.append(" c.case_type IN (").append(marks(f.kinds().size())).append(")");
    args.addAll(f.kinds());
    sql.append(" AND o.state IN (").append(marks(f.states().size())).append(")");
    args.addAll(f.states());
    if (f.centers() != null) {
      sql.append(" AND c.center_id IN (").append(marks(f.centers().size())).append(")");
      args.addAll(f.centers());
    }
    if (f.owner() != null) {
      sql.append(" AND o.owner = ?");
      args.add(f.owner());
    }
    if (f.caseId() != null) {
      sql.append(" AND o.case_id = ?");
      args.add(f.caseId());
    }
    sql.append(
        " ORDER BY o.due_at ASC NULLS LAST, o.created_at ASC, o.obligation_id LIMIT ? OFFSET ?");
    args.add(limit);
    args.add(offset);
    return jdbc.query(sql.toString(), this::row, args.toArray());
  }

  public void applyResolution(
      String obligationId, String state, int resolvedQty, long resolvedAmount, Instant at) {
    jdbc.update(
        "UPDATE case_obligation SET state = ?, resolved_qty = ?, resolved_amount = ?,"
            + " version = version + 1, updated_at = ? WHERE obligation_id = ?",
        state,
        resolvedQty,
        resolvedAmount,
        ts(at),
        obligationId);
  }

  public void addResolution(String obligationId, ResolutionRow r) {
    jdbc.update(
        "INSERT INTO case_obligation_resolution (obligation_id, outcome, qty, amount, evidence_system,"
            + " evidence_ref, evidence_kind, evidence_as_of, note, resolved_by, resolved_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?)",
        obligationId,
        r.outcome(),
        r.qty(),
        r.amount(),
        r.evidenceSystem(),
        r.evidenceRef(),
        r.evidenceKind(),
        ts(r.evidenceAsOf()),
        r.note(),
        r.resolvedBy(),
        ts(r.resolvedAt()));
  }

  public List<ResolutionRow> resolutions(String obligationId) {
    return jdbc.query(
        "SELECT outcome, qty, amount, evidence_system, evidence_ref, evidence_kind, evidence_as_of, note,"
            + " resolved_by, resolved_at FROM case_obligation_resolution WHERE obligation_id = ?"
            + " ORDER BY resolved_at, id",
        (rs, i) ->
            new ResolutionRow(
                rs.getString(1),
                (Integer) rs.getObject(2),
                (Long) rs.getObject(3),
                rs.getString(4),
                rs.getString(5),
                rs.getString(6),
                instant(rs.getTimestamp(7)),
                rs.getString(8),
                rs.getString(9),
                java.util.Objects.requireNonNull(instant(rs.getTimestamp(10)))),
        obligationId);
  }

  private ObligationRow row(ResultSet rs, int i) throws SQLException {
    return new ObligationRow(
        rs.getString(1),
        rs.getString(2),
        rs.getString(3),
        rs.getString(4),
        rs.getString(5),
        (Integer) rs.getObject(6),
        (Long) rs.getObject(7),
        instant(rs.getTimestamp(8)),
        rs.getString(9),
        rs.getString(10),
        rs.getInt(11),
        rs.getLong(12),
        rs.getInt(13),
        rs.getString(14),
        java.util.Objects.requireNonNull(instant(rs.getTimestamp(15))),
        java.util.Objects.requireNonNull(instant(rs.getTimestamp(16))));
  }

  private static String marks(int n) {
    return String.join(",", java.util.Collections.nCopies(n, "?"));
  }

  private static @Nullable Timestamp ts(@Nullable Instant i) {
    return i == null ? null : Timestamp.from(i);
  }

  private static @Nullable Instant instant(@Nullable Timestamp t) {
    return t == null ? null : t.toInstant();
  }
}
