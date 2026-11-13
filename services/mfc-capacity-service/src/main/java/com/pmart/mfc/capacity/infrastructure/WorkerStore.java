package com.pmart.mfc.capacity.infrastructure;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 작업자(계정)·업무 버전·상태 이력·근무(V5). 계정 행은 열쇠·표시명·상태·지금 업무 버전만 — 업무 내용은 업무 표에. */
@Repository
public class WorkerStore {

  public record WorkerRow(
      String mfcId,
      String workerId,
      String displayName,
      String state,
      int dutyVersion,
      String registeredBy,
      Instant registeredAt,
      double speedFactor) {}

  public record DutyRow(
      int dutyVersion,
      Set<String> taskTypes,
      Set<String> zones,
      @Nullable String reason,
      String changedBy,
      Instant changedAt) {}

  public record StateChangeRow(
      @Nullable String fromState,
      String toState,
      @Nullable String reason,
      String changedBy,
      Instant changedAt) {}

  public record ShiftRow(
      String shiftId,
      String mfcId,
      String workerId,
      Instant startAt,
      Instant endAt,
      String state,
      @Nullable String note,
      String createdBy,
      @Nullable String cancelReason) {}

  private final JdbcTemplate jdbc;

  public WorkerStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<WorkerRow> find(String mfcId, String workerId) {
    return jdbc
        .query(
            "SELECT * FROM mfc_worker WHERE mfc_id = ? AND worker_id = ?",
            (rs, i) -> worker(rs),
            mfcId,
            workerId)
        .stream()
        .findFirst();
  }

  public Optional<WorkerRow> lock(String mfcId, String workerId) {
    return jdbc
        .query(
            "SELECT * FROM mfc_worker WHERE mfc_id = ? AND worker_id = ? FOR UPDATE",
            (rs, i) -> worker(rs),
            mfcId,
            workerId)
        .stream()
        .findFirst();
  }

  public List<WorkerRow> list(String mfcId, @Nullable String state) {
    return state == null
        ? jdbc.query(
            "SELECT * FROM mfc_worker WHERE mfc_id = ? AND state <> 'LEFT' ORDER BY worker_id",
            (rs, i) -> worker(rs),
            mfcId)
        : jdbc.query(
            "SELECT * FROM mfc_worker WHERE mfc_id = ? AND state = ? ORDER BY worker_id",
            (rs, i) -> worker(rs),
            mfcId,
            state);
  }

  /** 새로 넣었으면 true(이미 있으면 false). */
  public boolean insert(String mfcId, String workerId, String displayName, String by, Instant at) {
    return jdbc.update(
            "INSERT INTO mfc_worker (mfc_id, worker_id, display_name, state, duty_version,"
                + " registered_by, registered_at, updated_at) VALUES (?,?,?,'ACTIVE',1,?,?,?)"
                + " ON CONFLICT DO NOTHING",
            mfcId,
            workerId,
            displayName,
            by,
            Timestamp.from(at),
            Timestamp.from(at))
        == 1;
  }

  public void insertDuty(
      String mfcId,
      String workerId,
      int version,
      Set<String> taskTypes,
      Set<String> zones,
      @Nullable String reason,
      String by,
      Instant at) {
    jdbc.update(
        con -> {
          var ps =
              con.prepareStatement(
                  "INSERT INTO mfc_worker_duty (mfc_id, worker_id, duty_version, task_types,"
                      + " temperature_zones, reason, changed_by, changed_at) VALUES (?,?,?,?,?,?,?,?)");
          ps.setString(1, mfcId);
          ps.setString(2, workerId);
          ps.setInt(3, version);
          ps.setArray(4, con.createArrayOf("text", taskTypes.stream().sorted().toArray()));
          ps.setArray(5, con.createArrayOf("text", zones.stream().sorted().toArray()));
          ps.setString(6, reason);
          ps.setString(7, by);
          ps.setTimestamp(8, Timestamp.from(at));
          return ps;
        });
    jdbc.update(
        "UPDATE mfc_worker SET duty_version = ?, updated_at = ? WHERE mfc_id = ? AND worker_id = ?",
        version,
        Timestamp.from(at),
        mfcId,
        workerId);
  }

  public DutyRow duty(String mfcId, String workerId, int version) {
    return jdbc.queryForObject(
        "SELECT * FROM mfc_worker_duty WHERE mfc_id = ? AND worker_id = ? AND duty_version = ?",
        (rs, i) -> duty(rs),
        mfcId,
        workerId,
        version);
  }

  public List<DutyRow> duties(String mfcId, String workerId) {
    return jdbc.query(
        "SELECT * FROM mfc_worker_duty WHERE mfc_id = ? AND worker_id = ? ORDER BY duty_version DESC",
        (rs, i) -> duty(rs),
        mfcId,
        workerId);
  }

  public void setState(
      String mfcId,
      String workerId,
      @Nullable String from,
      String to,
      @Nullable String reason,
      String by,
      Instant at) {
    jdbc.update(
        "UPDATE mfc_worker SET state = ?, updated_at = ? WHERE mfc_id = ? AND worker_id = ?",
        to,
        Timestamp.from(at),
        mfcId,
        workerId);
    jdbc.update(
        "INSERT INTO mfc_worker_state_change (change_id, mfc_id, worker_id, from_state, to_state,"
            + " reason, changed_by, changed_at) VALUES (?,?,?,?,?,?,?,?)",
        "WSC-" + UUID.randomUUID(),
        mfcId,
        workerId,
        from,
        to,
        reason,
        by,
        Timestamp.from(at));
  }

  public List<StateChangeRow> stateChanges(String mfcId, String workerId) {
    return jdbc.query(
        "SELECT * FROM mfc_worker_state_change WHERE mfc_id = ? AND worker_id = ?"
            + " ORDER BY changed_at DESC, change_id DESC",
        (rs, i) ->
            new StateChangeRow(
                rs.getString("from_state"),
                rs.getString("to_state"),
                rs.getString("reason"),
                rs.getString("changed_by"),
                rs.getTimestamp("changed_at").toInstant()),
        mfcId,
        workerId);
  }

  // ---------- 근무 ----------

  public List<ShiftRow> shifts(
      String mfcId, String workerId, @Nullable Instant from, @Nullable Instant to) {
    List<Object> args = new ArrayList<>(List.of(mfcId, workerId));
    StringBuilder sql =
        new StringBuilder(
            "SELECT * FROM mfc_worker_shift WHERE mfc_id = ? AND worker_id = ? AND state = 'PLANNED'");
    if (from != null) {
      sql.append(" AND end_at > ?");
      args.add(Timestamp.from(from));
    }
    if (to != null) {
      sql.append(" AND start_at < ?");
      args.add(Timestamp.from(to));
    }
    sql.append(" ORDER BY start_at");
    return jdbc.query(sql.toString(), (rs, i) -> shift(rs), args.toArray());
  }

  public boolean overlaps(String mfcId, String workerId, Instant start, Instant end) {
    Integer n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM mfc_worker_shift WHERE mfc_id = ? AND worker_id = ?"
                + " AND state = 'PLANNED' AND start_at < ? AND end_at > ?",
            Integer.class,
            mfcId,
            workerId,
            Timestamp.from(end),
            Timestamp.from(start));
    return n != null && n > 0;
  }

  public ShiftRow insertShift(
      String mfcId,
      String workerId,
      Instant start,
      Instant end,
      @Nullable String note,
      String by,
      Instant at) {
    String id = "SHF-" + UUID.randomUUID();
    jdbc.update(
        "INSERT INTO mfc_worker_shift (shift_id, mfc_id, worker_id, start_at, end_at, state, note,"
            + " created_by, created_at) VALUES (?,?,?,?,?,'PLANNED',?,?,?)",
        id,
        mfcId,
        workerId,
        Timestamp.from(start),
        Timestamp.from(end),
        note,
        by,
        Timestamp.from(at));
    return shift(id).orElseThrow();
  }

  public Optional<ShiftRow> shift(String shiftId) {
    return jdbc
        .query("SELECT * FROM mfc_worker_shift WHERE shift_id = ?", (rs, i) -> shift(rs), shiftId)
        .stream()
        .findFirst();
  }

  public void cancelShift(String shiftId, String reason, String by, Instant at) {
    jdbc.update(
        "UPDATE mfc_worker_shift SET state = 'CANCELLED', cancel_reason = ?, cancelled_by = ?,"
            + " cancelled_at = ? WHERE shift_id = ? AND state = 'PLANNED'",
        reason,
        by,
        Timestamp.from(at),
        shiftId);
  }

  private static WorkerRow worker(ResultSet rs) throws SQLException {
    return new WorkerRow(
        rs.getString("mfc_id"),
        rs.getString("worker_id"),
        rs.getString("display_name"),
        rs.getString("state"),
        rs.getInt("duty_version"),
        rs.getString("registered_by"),
        rs.getTimestamp("registered_at").toInstant(),
        rs.getBigDecimal("speed_factor").doubleValue());
  }

  /** 이 운영자 ID 로 등록된 모든 센터의 작업자 행(잠금) — 인사·권한 반영용(V14·V15). */
  public List<WorkerRow> lockAllCenters(String workerId) {
    return jdbc.query(
        "SELECT * FROM mfc_worker WHERE worker_id = ? ORDER BY mfc_id FOR UPDATE",
        (rs, i) -> worker(rs),
        workerId);
  }

  /** 마지막 상태 변경을 한 주체(없으면 null). */
  public @Nullable String lastStateChangedBy(String mfcId, String workerId) {
    List<String> r =
        jdbc.queryForList(
            "SELECT changed_by FROM mfc_worker_state_change WHERE mfc_id = ? AND worker_id = ?"
                + " ORDER BY changed_at DESC, change_id DESC LIMIT 1",
            String.class,
            mfcId,
            workerId);
    return r.isEmpty() ? null : r.getFirst();
  }

  /** 속도 배율(0.11.0). */
  public void setSpeed(String mfcId, String workerId, double factor) {
    jdbc.update(
        "UPDATE mfc_worker SET speed_factor = ? WHERE mfc_id = ? AND worker_id = ?",
        java.math.BigDecimal.valueOf(factor).setScale(2, java.math.RoundingMode.HALF_UP),
        mfcId,
        workerId);
  }

  private static DutyRow duty(ResultSet rs) throws SQLException {
    return new DutyRow(
        rs.getInt("duty_version"),
        set(rs.getArray("task_types")),
        set(rs.getArray("temperature_zones")),
        rs.getString("reason"),
        rs.getString("changed_by"),
        rs.getTimestamp("changed_at").toInstant());
  }

  private static ShiftRow shift(ResultSet rs) throws SQLException {
    return new ShiftRow(
        rs.getString("shift_id"),
        rs.getString("mfc_id"),
        rs.getString("worker_id"),
        rs.getTimestamp("start_at").toInstant(),
        rs.getTimestamp("end_at").toInstant(),
        rs.getString("state"),
        rs.getString("note"),
        rs.getString("created_by"),
        rs.getString("cancel_reason"));
  }

  private static Set<String> set(Array a) throws SQLException {
    return new LinkedHashSet<>(Arrays.asList((String[]) a.getArray()));
  }
}
