package com.pmart.cdc.outbound.infrastructure;

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

/** TRF·Wave·Task 저장(JDBC). 상태 변경은 version 조건부(경합이면 0 행). */
@Repository
public class OutboundStore {

  public record TransferRow(
      String trfId,
      int trfVersion,
      String cdcId,
      String mfcId,
      String approvalRef,
      String deadlineMeaning,
      Instant dueAt,
      String bodyHash,
      String state,
      @Nullable String allocationId,
      @Nullable String allocationError,
      @Nullable String waveId,
      int version,
      Instant createdAt,
      Instant updatedAt) {}

  public record TransferLineRow(
      String sku, int lineNo, int approvedQty, int allocatedQty, int pickedQty, int shortQty) {}

  public record WaveRow(
      String waveId,
      String cdcId,
      String state,
      int version,
      @Nullable String temperatureZone,
      @Nullable Instant windowStart,
      @Nullable Instant windowEnd,
      String createdBy,
      Instant createdAt,
      @Nullable Instant releasedAt) {}

  /** trfId 는 TRF 하나짜리 작업만(0.9.0 — 묶음이면 비움, 몫은 {@link ShareRow}). */
  public record TaskRow(
      String taskId,
      String waveId,
      @Nullable String trfId,
      String cdcId,
      String kind,
      @Nullable String zone,
      @Nullable String floor,
      Instant dueAt,
      String state,
      int version,
      @Nullable String assigneeId,
      Instant createdAt,
      @Nullable Instant completedAt,
      @Nullable String pickMode,
      @Nullable String pickMethod,
      @Nullable String regionCode) {}

  /** 줄의 TRF 몫(0.9.0). */
  public record ShareRow(
      int lineNo,
      String trfId,
      String mfcId,
      Instant dueAt,
      int plannedQty,
      int pickedQty,
      int shortQty) {}

  /** 지역 ↔ MFC(0.9.0). */
  public record RegionRow(String mfcId, String regionCode, String regionName) {}

  public record TaskLineRow(
      int lineNo,
      String sku,
      String lot,
      String locationCode,
      int plannedQty,
      int pickedQty,
      int shortQty,
      @Nullable String shortReason) {}

  private final JdbcTemplate jdbc;

  public OutboundStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  // ---------- transfer ----------

  private static final String TRF_COLS =
      "trf_id, trf_version, cdc_id, mfc_id, approval_ref, deadline_meaning, due_at, body_hash, state,"
          + " allocation_id, allocation_error, wave_id, row_version, created_at, updated_at";

  public void insertTransfer(TransferRow t, List<TransferLineRow> lines) {
    jdbc.update(
        "INSERT INTO transfer (" + TRF_COLS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        t.trfId(),
        t.trfVersion(),
        t.cdcId(),
        t.mfcId(),
        t.approvalRef(),
        t.deadlineMeaning(),
        Timestamp.from(t.dueAt()),
        t.bodyHash(),
        t.state(),
        t.allocationId(),
        t.allocationError(),
        t.waveId(),
        t.version(),
        Timestamp.from(t.createdAt()),
        Timestamp.from(t.updatedAt()));
    for (TransferLineRow l : lines) {
      jdbc.update(
          "INSERT INTO transfer_line (trf_id, line_no, sku, approved_qty) VALUES (?,?,?,?)",
          t.trfId(),
          l.lineNo(),
          l.sku(),
          l.approvedQty());
    }
  }

  public Optional<TransferRow> transfer(String trfId, boolean lock) {
    return jdbc
        .query(
            "SELECT " + TRF_COLS + " FROM transfer WHERE trf_id = ?" + (lock ? " FOR UPDATE" : ""),
            (rs, i) -> transfer(rs),
            trfId)
        .stream()
        .findFirst();
  }

  public List<TransferRow> transfersOfWave(String waveId) {
    return jdbc.query(
        "SELECT " + TRF_COLS + " FROM transfer WHERE wave_id = ? ORDER BY trf_id",
        (rs, i) -> transfer(rs),
        waveId);
  }

  public List<TransferLineRow> transferLines(String trfId) {
    return jdbc.query(
        "SELECT sku, line_no, approved_qty, allocated_qty, picked_qty, short_qty FROM transfer_line"
            + " WHERE trf_id = ? ORDER BY line_no",
        (rs, i) ->
            new TransferLineRow(
                rs.getString("sku"),
                rs.getInt("line_no"),
                rs.getInt("approved_qty"),
                rs.getInt("allocated_qty"),
                rs.getInt("picked_qty"),
                rs.getInt("short_qty")),
        trfId);
  }

  public void setAllocation(
      String trfId,
      String state,
      @Nullable String allocationId,
      @Nullable String error,
      Instant at) {
    jdbc.update(
        "UPDATE transfer SET state = ?, allocation_id = COALESCE(?, allocation_id), allocation_error = ?,"
            + " row_version = row_version + 1, updated_at = ? WHERE trf_id = ?",
        state,
        allocationId,
        error,
        Timestamp.from(at),
        trfId);
  }

  public void setLineAllocated(String trfId, String sku, int qty) {
    jdbc.update(
        "UPDATE transfer_line SET allocated_qty = ? WHERE trf_id = ? AND sku = ?", qty, trfId, sku);
  }

  public void addLinePicked(String trfId, String sku, int picked, int shortQty) {
    jdbc.update(
        "UPDATE transfer_line SET picked_qty = picked_qty + ?, short_qty = short_qty + ?"
            + " WHERE trf_id = ? AND sku = ?",
        picked,
        shortQty,
        trfId,
        sku);
  }

  public void setTransferState(String trfId, String state, @Nullable String waveId, Instant at) {
    jdbc.update(
        "UPDATE transfer SET state = ?, wave_id = COALESCE(?, wave_id), row_version = row_version + 1,"
            + " updated_at = ? WHERE trf_id = ?",
        state,
        waveId,
        Timestamp.from(at),
        trfId);
  }

  private static TransferRow transfer(ResultSet rs) throws SQLException {
    return new TransferRow(
        rs.getString("trf_id"),
        rs.getInt("trf_version"),
        rs.getString("cdc_id"),
        rs.getString("mfc_id"),
        rs.getString("approval_ref"),
        rs.getString("deadline_meaning"),
        rs.getTimestamp("due_at").toInstant(),
        rs.getString("body_hash"),
        rs.getString("state"),
        rs.getString("allocation_id"),
        rs.getString("allocation_error"),
        rs.getString("wave_id"),
        rs.getInt("row_version"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }

  // ---------- wave ----------

  private static final String WAVE_COLS =
      "wave_id, cdc_id, state, row_version, temperature_zone, window_start, window_end, created_by,"
          + " created_at, released_at";

  public void insertWave(WaveRow w) {
    jdbc.update(
        "INSERT INTO wave (" + WAVE_COLS + ") VALUES (?,?,?,?,?,?,?,?,?,?)",
        w.waveId(),
        w.cdcId(),
        w.state(),
        w.version(),
        w.temperatureZone(),
        ts(w.windowStart()),
        ts(w.windowEnd()),
        w.createdBy(),
        Timestamp.from(w.createdAt()),
        ts(w.releasedAt()));
  }

  public Optional<WaveRow> wave(String waveId, boolean lock) {
    return jdbc
        .query(
            "SELECT " + WAVE_COLS + " FROM wave WHERE wave_id = ?" + (lock ? " FOR UPDATE" : ""),
            (rs, i) ->
                new WaveRow(
                    rs.getString("wave_id"),
                    rs.getString("cdc_id"),
                    rs.getString("state"),
                    rs.getInt("row_version"),
                    rs.getString("temperature_zone"),
                    instant(rs, "window_start"),
                    instant(rs, "window_end"),
                    rs.getString("created_by"),
                    rs.getTimestamp("created_at").toInstant(),
                    instant(rs, "released_at")),
            waveId)
        .stream()
        .findFirst();
  }

  /**
   * @return 바뀌었는가(version 조건부)
   */
  public boolean setWaveState(
      String waveId, String state, int expectedVersion, @Nullable Instant releasedAt) {
    return jdbc.update(
            "UPDATE wave SET state = ?, row_version = row_version + 1,"
                + " released_at = COALESCE(?, released_at) WHERE wave_id = ? AND row_version = ?",
            state,
            ts(releasedAt),
            waveId,
            expectedVersion)
        == 1;
  }

  /** Wave 의 Task 상태별 수(total·done·exception 요약용). */
  public int[] taskSummary(String waveId) {
    Integer total =
        jdbc.queryForObject("SELECT count(*) FROM task WHERE wave_id = ?", Integer.class, waveId);
    Integer done =
        jdbc.queryForObject(
            "SELECT count(*) FROM task WHERE wave_id = ? AND state IN ('DONE', 'PARTIAL')",
            Integer.class,
            waveId);
    Integer exception =
        jdbc.queryForObject(
            "SELECT count(*) FROM task WHERE wave_id = ? AND state = 'EXCEPTION'",
            Integer.class,
            waveId);
    return new int[] {
      total == null ? 0 : total, done == null ? 0 : done, exception == null ? 0 : exception
    };
  }

  // ---------- task ----------

  private static final String TASK_COLS =
      "t.task_id, t.wave_id, t.trf_id, t.cdc_id, t.kind, t.zone, t.floor, t.due_at, t.state,"
          + " t.row_version, t.assignee_id, t.created_at, t.completed_at, t.pick_mode, t.pick_method,"
          + " t.region_code";

  /** 몫이 든 작업(단건 작업도 몫 한 줄씩 — V7). */
  private static final String TASKS_OF_TRF =
      "SELECT DISTINCT task_id FROM task_line_share WHERE trf_id = ?";

  public void insertTask(TaskRow t, List<TaskLineRow> lines, List<ShareRow> shares) {
    jdbc.update(
        "INSERT INTO task (task_id, wave_id, trf_id, cdc_id, kind, zone, floor, due_at, state,"
            + " row_version, assignee_id, created_at, pick_mode, pick_method, region_code)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        t.taskId(),
        t.waveId(),
        t.trfId(),
        t.cdcId(),
        t.kind(),
        t.zone(),
        t.floor(),
        Timestamp.from(t.dueAt()),
        t.state(),
        t.version(),
        t.assigneeId(),
        Timestamp.from(t.createdAt()),
        t.pickMode() == null ? "DISCRETE" : t.pickMode(),
        t.pickMethod(),
        t.regionCode());
    for (TaskLineRow l : lines) {
      jdbc.update(
          "INSERT INTO task_line (task_id, line_no, sku, lot, location_code, planned_qty)"
              + " VALUES (?,?,?,?,?,?)",
          t.taskId(),
          l.lineNo(),
          l.sku(),
          l.lot(),
          l.locationCode(),
          l.plannedQty());
    }
    for (ShareRow r : shares) {
      jdbc.update(
          "INSERT INTO task_line_share (task_id, line_no, trf_id, mfc_id, due_at, planned_qty)"
              + " VALUES (?,?,?,?,?,?)",
          t.taskId(),
          r.lineNo(),
          r.trfId(),
          r.mfcId(),
          Timestamp.from(r.dueAt()),
          r.plannedQty());
    }
  }

  /** 작업의 몫(줄 → TRF 순). */
  public List<ShareRow> shares(String taskId) {
    return jdbc.query(
        "SELECT line_no, trf_id, mfc_id, due_at, planned_qty, picked_qty, short_qty"
            + " FROM task_line_share WHERE task_id = ? ORDER BY line_no, trf_id",
        (rs, i) ->
            new ShareRow(
                rs.getInt("line_no"),
                rs.getString("trf_id"),
                rs.getString("mfc_id"),
                rs.getTimestamp("due_at").toInstant(),
                rs.getInt("planned_qty"),
                rs.getInt("picked_qty"),
                rs.getInt("short_qty")),
        taskId);
  }

  public void addShareResult(String taskId, int lineNo, String trfId, int picked, int shortQty) {
    jdbc.update(
        "UPDATE task_line_share SET picked_qty = picked_qty + ?, short_qty = short_qty + ?"
            + " WHERE task_id = ? AND line_no = ? AND trf_id = ?",
        picked,
        shortQty,
        taskId,
        lineNo,
        trfId);
  }

  // ---------- region (0.9.0) ----------

  public List<RegionRow> regions(String cdcId) {
    return jdbc.query(
        "SELECT mfc_id, region_code, region_name FROM region_mfc WHERE cdc_id = ?"
            + " ORDER BY region_code, mfc_id",
        (rs, i) ->
            new RegionRow(
                rs.getString("mfc_id"), rs.getString("region_code"), rs.getString("region_name")),
        cdcId);
  }

  public void upsertRegion(String cdcId, RegionRow r, String by, Instant at) {
    jdbc.update(
        "INSERT INTO region_mfc (cdc_id, mfc_id, region_code, region_name, updated_by, updated_at)"
            + " VALUES (?,?,?,?,?,?) ON CONFLICT (cdc_id, mfc_id) DO UPDATE SET"
            + " region_code = EXCLUDED.region_code, region_name = EXCLUDED.region_name,"
            + " updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at",
        cdcId,
        r.mfcId(),
        r.regionCode(),
        r.regionName(),
        by,
        Timestamp.from(at));
  }

  public Optional<TaskRow> task(String taskId, boolean lock) {
    return jdbc
        .query(
            "SELECT "
                + TASK_COLS
                + " FROM task t WHERE t.task_id = ?"
                + (lock ? " FOR UPDATE" : ""),
            (rs, i) -> task(rs),
            taskId)
        .stream()
        .findFirst();
  }

  public List<TaskLineRow> taskLines(String taskId) {
    return jdbc.query(
        "SELECT line_no, sku, lot, location_code, planned_qty, picked_qty, short_qty, short_reason"
            + " FROM task_line WHERE task_id = ? ORDER BY line_no",
        (rs, i) ->
            new TaskLineRow(
                rs.getInt("line_no"),
                rs.getString("sku"),
                rs.getString("lot"),
                rs.getString("location_code"),
                rs.getInt("planned_qty"),
                rs.getInt("picked_qty"),
                rs.getInt("short_qty"),
                rs.getString("short_reason")),
        taskId);
  }

  public List<TaskRow> tasks(
      String cdcId,
      String waveId,
      @Nullable String state,
      @Nullable String assigneeId,
      @Nullable String afterTaskId,
      int limit) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT " + TASK_COLS + " FROM task t WHERE t.cdc_id = ? AND t.wave_id = ?");
    List<Object> args = new ArrayList<>(List.of(cdcId, waveId));
    if (state != null) {
      sql.append(" AND t.state = ?");
      args.add(state);
    }
    if (assigneeId != null) {
      sql.append(" AND t.assignee_id = ?");
      args.add(assigneeId);
    }
    if (afterTaskId != null) {
      sql.append(" AND t.task_id > ?");
      args.add(afterTaskId);
    }
    sql.append(" ORDER BY t.task_id LIMIT ?");
    args.add(limit);
    return jdbc.query(sql.toString(), (rs, i) -> task(rs), args.toArray());
  }

  /** 같은 작업자의 다음 작업 받기를 줄 세운다(트랜잭션 끝까지) — 다른 멱등 키로 동시에 두 번 받아도 한 작업만. */
  public void lockWorker(String cdcId, String workerId) {
    jdbc.query(
        "SELECT pg_advisory_xact_lock(hashtext(?))",
        rs -> {},
        "cdc-outbound:worker:" + cdcId + ":" + workerId);
  }

  /** 작업자가 들고 있는 작업(ACCEPTED·IN_PROGRESS). */
  public Optional<TaskRow> heldBy(String cdcId, String workerId) {
    return jdbc
        .query(
            "SELECT "
                + TASK_COLS
                + " FROM task t WHERE t.cdc_id = ? AND t.assignee_id = ?"
                + " AND t.state IN ('ACCEPTED', 'IN_PROGRESS') ORDER BY t.accepted_at LIMIT 1",
            (rs, i) -> task(rs),
            cdcId,
            workerId)
        .stream()
        .findFirst();
  }

  /** 담당 없는 CREATED 작업 하나를 잠근다(다른 요청이 잠근 줄은 건너뜀 — 동시에 받아도 한 작업은 한 사람). 순서 TRF 기한 → 만든 순. */
  public Optional<TaskRow> lockNextCandidate(
      String cdcId,
      @Nullable String kind,
      @Nullable String zone,
      @Nullable String floor,
      @Nullable String pickMode) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT "
                + TASK_COLS
                + " FROM task t JOIN wave w ON w.wave_id = t.wave_id WHERE t.cdc_id = ?"
                + " AND t.state = 'CREATED' AND t.assignee_id IS NULL"
                + " AND w.state IN ('RELEASED', 'IN_PROGRESS')");
    List<Object> args = new ArrayList<>(List.of(cdcId));
    if (kind != null) {
      sql.append(" AND t.kind = ?");
      args.add(kind);
    }
    if (zone != null) {
      sql.append(" AND t.zone = ?");
      args.add(zone);
    }
    if (floor != null) {
      sql.append(" AND t.floor = ?");
      args.add(floor);
    }
    if (pickMode != null) {
      sql.append(" AND t.pick_mode = ?");
      args.add(pickMode);
    }
    sql.append(" ORDER BY t.due_at, t.seq LIMIT 1 FOR UPDATE OF t SKIP LOCKED");
    return jdbc.query(sql.toString(), (rs, i) -> task(rs), args.toArray()).stream().findFirst();
  }

  public void assign(String taskId, String workerId, Instant at) {
    jdbc.update(
        "UPDATE task SET state = 'ACCEPTED', assignee_id = ?, accepted_at = ?,"
            + " row_version = row_version + 1 WHERE task_id = ?",
        workerId,
        Timestamp.from(at),
        taskId);
  }

  public void setTaskState(String taskId, String state, Instant at) {
    jdbc.update(
        "UPDATE task SET state = ?, row_version = row_version + 1,"
            + " started_at = CASE WHEN ? = 'IN_PROGRESS' THEN ? ELSE started_at END,"
            + " completed_at = CASE WHEN ? IN ('DONE', 'PARTIAL') THEN ? ELSE completed_at END"
            + " WHERE task_id = ?",
        state,
        state,
        Timestamp.from(at),
        state,
        Timestamp.from(at),
        taskId);
  }

  public void addLineResult(
      String taskId, int lineNo, int picked, int shortQty, @Nullable String shortReason) {
    jdbc.update(
        "UPDATE task_line SET picked_qty = picked_qty + ?, short_qty = short_qty + ?,"
            + " short_reason = COALESCE(?, short_reason) WHERE task_id = ? AND line_no = ?",
        picked,
        shortQty,
        shortReason,
        taskId,
        lineNo);
  }

  /** TRF 의 실피킹 합(SKU·LOT → 수량) — 분류 ≤ 실피킹 검사용. */
  public java.util.Map<String, Integer> pickedByLot(String trfId) {
    java.util.Map<String, Integer> out = new java.util.HashMap<>();
    jdbc.query(
        "SELECT l.sku, l.lot, SUM(s.picked_qty) AS q FROM task_line_share s JOIN task_line l"
            + " ON l.task_id = s.task_id AND l.line_no = s.line_no"
            + " WHERE s.trf_id = ? GROUP BY l.sku, l.lot",
        rs -> {
          out.put(rs.getString("sku") + "|" + rs.getString("lot"), rs.getInt("q"));
        },
        trfId);
    return out;
  }

  /** TRF 의 Task (종류, 상태) 목록 — 출발 준비 조회. */
  public List<String[]> taskStates(String trfId) {
    return jdbc.query(
        "SELECT kind, state FROM task WHERE task_id IN (" + TASKS_OF_TRF + ") ORDER BY task_id",
        (rs, i) -> new String[] {rs.getString("kind"), rs.getString("state")},
        trfId);
  }

  /** 아직 끝나지 않은 Task 수(Wave 단위 / TRF 단위 — TRF 는 몫이 든 작업). */
  public int openTasks(String column, String id) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM task WHERE "
                + ("trf_id".equals(column) ? "task_id IN (" + TASKS_OF_TRF + ")" : "wave_id = ?")
                + " AND state NOT IN ('DONE', 'PARTIAL', 'CANCELLED')",
            Integer.class,
            id);
    return n == null ? 0 : n;
  }

  private static TaskRow task(ResultSet rs) throws SQLException {
    return new TaskRow(
        rs.getString("task_id"),
        rs.getString("wave_id"),
        rs.getString("trf_id"),
        rs.getString("cdc_id"),
        rs.getString("kind"),
        rs.getString("zone"),
        rs.getString("floor"),
        rs.getTimestamp("due_at").toInstant(),
        rs.getString("state"),
        rs.getInt("row_version"),
        rs.getString("assignee_id"),
        rs.getTimestamp("created_at").toInstant(),
        instant(rs, "completed_at"),
        rs.getString("pick_mode"),
        rs.getString("pick_method"),
        rs.getString("region_code"));
  }

  private static @Nullable Instant instant(ResultSet rs, String col) throws SQLException {
    Timestamp t = rs.getTimestamp(col);
    return t == null ? null : t.toInstant();
  }

  private static @Nullable Timestamp ts(@Nullable Instant i) {
    return i == null ? null : Timestamp.from(i);
  }
}
