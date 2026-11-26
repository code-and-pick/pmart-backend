package com.pmart.cdc.outbound.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** DC 백오피스 직원 조회(0.11.0, 2026-11-25) — 읽기만. */
@Repository
public class StaffViewStore {

  public record TransferRow(
      String trfId,
      int trfVersion,
      String mfcId,
      String state,
      String deadlineMeaning,
      Instant dueAt,
      @Nullable String waveId,
      @Nullable String allocationError,
      int lineCount,
      long approvedQty,
      long allocatedQty,
      long pickedQty,
      long shortQty,
      Instant createdAt,
      Instant updatedAt) {}

  public record LineRow(
      String sku, int approvedQty, int allocatedQty, int pickedQty, int shortQty) {}

  public record Totals(long count, long approved, long allocated, long picked, long shortQty) {}

  private static final String SUMMARY =
      "SELECT t.trf_id, t.trf_version, t.mfc_id, t.state, t.deadline_meaning, t.due_at, t.wave_id,"
          + " t.allocation_error, t.created_at, t.updated_at, count(l.sku) AS line_count,"
          + " coalesce(sum(l.approved_qty),0) AS approved, coalesce(sum(l.allocated_qty),0) AS allocated,"
          + " coalesce(sum(l.picked_qty),0) AS picked, coalesce(sum(l.short_qty),0) AS short_qty"
          + " FROM transfer t LEFT JOIN transfer_line l ON l.trf_id = t.trf_id";

  private final JdbcTemplate jdbc;

  public StaffViewStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** size + 1 개를 읽어 다음 쪽 여부를 판정한다. */
  public List<TransferRow> transfers(
      String cdcId,
      @Nullable String state,
      @Nullable String mfcId,
      @Nullable String trfNumber,
      int page,
      int size) {
    StringBuilder sql = new StringBuilder(SUMMARY).append(" WHERE t.cdc_id = ?");
    List<Object> args = new ArrayList<>(List.of(cdcId));
    if (trfNumber != null) { // 0.21.0 화면 번호로 찾기
      sql.append(" AND t.trf_number = ?");
      args.add(trfNumber);
    }
    if (state != null) {
      sql.append(" AND t.state = ?");
      args.add(state);
    }
    if (mfcId != null) {
      sql.append(" AND t.mfc_id = ?");
      args.add(mfcId);
    }
    sql.append(" GROUP BY t.trf_id ORDER BY t.due_at, t.trf_id LIMIT ? OFFSET ?");
    args.add(size + 1);
    args.add((long) page * size);
    return jdbc.query(sql.toString(), StaffViewStore::transfer, args.toArray());
  }

  /** Wave ID 목록(만든 시각 최신 순) — size + 1 개를 읽어 다음 쪽 여부를 판정한다(0.15.0 W-1). */
  public List<String> waveIds(
      String cdcId, @Nullable String state, @Nullable String waveNumber, int page, int size) {
    StringBuilder sql = new StringBuilder("SELECT wave_id FROM wave WHERE cdc_id = ?");
    List<Object> args = new ArrayList<>(List.of(cdcId));
    if (waveNumber != null) {
      sql.append(" AND wave_number = ?");
      args.add(waveNumber);
    }
    if (state != null) {
      sql.append(" AND state = ?");
      args.add(state);
    }
    sql.append(" ORDER BY created_at DESC, wave_id LIMIT ? OFFSET ?");
    args.add(size + 1);
    args.add((long) page * size);
    return jdbc.queryForList(sql.toString(), String.class, args.toArray());
  }

  /** 이 센터 TRF 가 든 출발 ID(검증 최신 순) — size + 1 개(0.16.0 M-3). */
  public List<String> departureIds(
      String cdcId, @Nullable String result, @Nullable String departureNumber, int page, int size) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT d.departure_id FROM departure d WHERE EXISTS (SELECT 1 FROM departure_transfer dt"
                + " JOIN transfer t ON t.trf_id = dt.trf_id WHERE dt.departure_id = d.departure_id"
                + " AND t.cdc_id = ?)");
    List<Object> args = new ArrayList<>(List.of(cdcId));
    if (result != null) {
      sql.append(" AND d.overall_result = ?");
      args.add(result);
    }
    if (departureNumber != null) {
      sql.append(" AND d.departure_number = ?");
      args.add(departureNumber);
    }
    sql.append(" ORDER BY d.validated_at DESC, d.departure_id LIMIT ? OFFSET ?");
    args.add(size + 1);
    args.add((long) page * size);
    return jdbc.queryForList(sql.toString(), String.class, args.toArray());
  }

  public record ShortageRow(
      String followupId,
      String trfId,
      String mfcId,
      String waveId,
      String taskId,
      String sku,
      String lot,
      String locationCode,
      int shortQty,
      String state,
      int reallocatedQty,
      int unresolvedQty,
      @Nullable String newTaskIds,
      @Nullable String reason,
      Instant createdAt,
      Instant updatedAt) {}

  /** 피킹 부족 후속(0.19.0) — 최신 순, size + 1 개. */
  public List<ShortageRow> shortages(String cdcId, @Nullable String state, int page, int size) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT f.*, t.mfc_id FROM pick_shortage_followup f JOIN transfer t ON t.trf_id ="
                + " f.trf_id WHERE t.cdc_id = ?");
    List<Object> args = new ArrayList<>(List.of(cdcId));
    if (state != null) {
      sql.append(" AND f.state = ?");
      args.add(state);
    }
    sql.append(" ORDER BY f.created_at DESC, f.followup_id LIMIT ? OFFSET ?");
    args.add(size + 1);
    args.add((long) page * size);
    return jdbc.query(
        sql.toString(),
        (rs, i) ->
            new ShortageRow(
                rs.getString("followup_id"),
                rs.getString("trf_id"),
                rs.getString("mfc_id"),
                rs.getString("wave_id"),
                rs.getString("task_id"),
                rs.getString("sku"),
                rs.getString("lot"),
                rs.getString("location_code"),
                rs.getInt("short_qty"),
                rs.getString("state"),
                rs.getInt("reallocated_qty"),
                rs.getInt("unresolved_qty"),
                rs.getString("new_task_ids"),
                rs.getString("reason"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()),
        args.toArray());
  }

  public record LpnRow(
      String lpn,
      String trfId,
      String mfcId,
      String waveId,
      String unitType,
      @Nullable String parentLpn,
      String sealState,
      @Nullable String sealNo,
      @Nullable String temperatureZone,
      @Nullable Integer weightG,
      String sortedBy,
      Instant sortedAt,
      @Nullable String inspection,
      @Nullable String failReason,
      @Nullable Instant inspectedAt,
      boolean loaded,
      @Nullable String tripId,
      @Nullable String vehicleId,
      @Nullable String dockCode,
      @Nullable Instant loadedAt,
      @Nullable String departureId) {}

  public record LpnContent(String lpn, String sku, String lot, int qty) {}

  /** LPN(0.19.0) — 분류 최신 순, size + 1 개. */
  public List<LpnRow> lpns(
      String cdcId, @Nullable String trfId, @Nullable String unitType, int page, int size) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT p.*, t.mfc_id FROM lpn p JOIN transfer t ON t.trf_id = p.trf_id WHERE t.cdc_id = ?");
    List<Object> args = new ArrayList<>(List.of(cdcId));
    if (trfId != null) {
      sql.append(" AND p.trf_id = ?");
      args.add(trfId);
    }
    if (unitType != null) {
      sql.append(" AND p.unit_type = ?");
      args.add(unitType);
    }
    sql.append(" ORDER BY p.sorted_at DESC, p.lpn LIMIT ? OFFSET ?");
    args.add(size + 1);
    args.add((long) page * size);
    return jdbc.query(
        sql.toString(),
        (rs, i) ->
            new LpnRow(
                rs.getString("lpn"),
                rs.getString("trf_id"),
                rs.getString("mfc_id"),
                rs.getString("wave_id"),
                rs.getString("unit_type"),
                rs.getString("parent_lpn"),
                rs.getString("seal_state"),
                rs.getString("seal_no"),
                rs.getString("temperature_zone"),
                (Integer) rs.getObject("weight_g"),
                rs.getString("sorted_by"),
                rs.getTimestamp("sorted_at").toInstant(),
                rs.getString("inspection"),
                rs.getString("fail_reason"),
                ts(rs, "inspected_at"),
                rs.getBoolean("loaded"),
                rs.getString("trip_id"),
                rs.getString("vehicle_id"),
                rs.getString("dock_code"),
                ts(rs, "loaded_at"),
                rs.getString("departure_id")),
        args.toArray());
  }

  public List<LpnContent> lpnContents(List<String> lpns) {
    if (lpns.isEmpty()) {
      return List.of();
    }
    String marks = String.join(",", java.util.Collections.nCopies(lpns.size(), "?"));
    return jdbc.query(
        "SELECT lpn, sku, lot, qty FROM lpn_content WHERE lpn IN ("
            + marks
            + ") ORDER BY lpn, sku, lot",
        (rs, i) -> new LpnContent(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4)),
        lpns.toArray());
  }

  private static @Nullable Instant ts(ResultSet rs, String col) throws SQLException {
    Timestamp t = rs.getTimestamp(col);
    return t == null ? null : t.toInstant();
  }

  public Optional<TransferRow> transfer(String cdcId, String trfId) {
    return jdbc
        .query(
            SUMMARY + " WHERE t.cdc_id = ? AND t.trf_id = ? GROUP BY t.trf_id",
            StaffViewStore::transfer,
            cdcId,
            trfId)
        .stream()
        .findFirst();
  }

  public List<LineRow> lines(String trfId) {
    return jdbc.query(
        "SELECT sku, approved_qty, allocated_qty, picked_qty, short_qty FROM transfer_line"
            + " WHERE trf_id = ? ORDER BY line_no",
        (rs, i) ->
            new LineRow(
                rs.getString("sku"),
                rs.getInt("approved_qty"),
                rs.getInt("allocated_qty"),
                rs.getInt("picked_qty"),
                rs.getInt("short_qty")),
        trfId);
  }

  /** [from, to) 기한의 TRF 상태별 수. */
  public Map<String, Long> transfersByState(String cdcId, Instant from, Instant to) {
    return counts(
        "SELECT state AS k, count(*) AS n FROM transfer WHERE cdc_id = ? AND due_at >= ?"
            + " AND due_at < ? GROUP BY state ORDER BY state",
        cdcId,
        from,
        to);
  }

  public Totals totals(String cdcId, Instant from, Instant to) {
    return jdbc.queryForObject(
        "SELECT count(DISTINCT t.trf_id) AS c, coalesce(sum(l.approved_qty),0) AS a,"
            + " coalesce(sum(l.allocated_qty),0) AS al, coalesce(sum(l.picked_qty),0) AS p,"
            + " coalesce(sum(l.short_qty),0) AS s FROM transfer t"
            + " LEFT JOIN transfer_line l ON l.trf_id = t.trf_id"
            + " WHERE t.cdc_id = ? AND t.due_at >= ? AND t.due_at < ?",
        (rs, i) ->
            new Totals(
                rs.getLong("c"),
                rs.getLong("a"),
                rs.getLong("al"),
                rs.getLong("p"),
                rs.getLong("s")),
        cdcId,
        Timestamp.from(from),
        Timestamp.from(to));
  }

  public record MfcTotals(
      String mfcId, long transfers, long approved, long allocated, long picked, long shortQty) {}

  /** [from, to) 기한 TRF 의 MFC 별 합(0.18.0 M-4) — MFC 순. */
  public List<MfcTotals> byMfc(String cdcId, Instant from, Instant to) {
    return jdbc.query(
        "SELECT t.mfc_id, count(DISTINCT t.trf_id) AS c, coalesce(sum(l.approved_qty),0) AS a,"
            + " coalesce(sum(l.allocated_qty),0) AS al, coalesce(sum(l.picked_qty),0) AS p,"
            + " coalesce(sum(l.short_qty),0) AS s FROM transfer t LEFT JOIN transfer_line l"
            + " ON l.trf_id = t.trf_id WHERE t.cdc_id = ? AND t.due_at >= ? AND t.due_at < ?"
            + " GROUP BY t.mfc_id ORDER BY t.mfc_id",
        (rs, i) ->
            new MfcTotals(
                rs.getString("mfc_id"),
                rs.getLong("c"),
                rs.getLong("a"),
                rs.getLong("al"),
                rs.getLong("p"),
                rs.getLong("s")),
        cdcId,
        Timestamp.from(from),
        Timestamp.from(to));
  }

  /** 그 TRF 들이 든 Wave 의 상태별 수. */
  public Map<String, Long> wavesByState(String cdcId, Instant from, Instant to) {
    return counts(
        "SELECT w.state AS k, count(*) AS n FROM wave w WHERE w.cdc_id = ? AND EXISTS ("
            + " SELECT 1 FROM transfer t WHERE t.wave_id = w.wave_id AND t.due_at >= ?"
            + " AND t.due_at < ?) GROUP BY w.state ORDER BY w.state",
        cdcId,
        from,
        to);
  }

  /** 그 TRF 들의 작업 종류:상태별 수. */
  public Map<String, Long> tasksByKindState(String cdcId, Instant from, Instant to) {
    return counts(
        "SELECT k.kind || ':' || k.state AS k, count(*) AS n FROM task k JOIN transfer t"
            + " ON t.trf_id = k.trf_id WHERE k.cdc_id = ? AND t.due_at >= ? AND t.due_at < ?"
            + " GROUP BY k.kind, k.state ORDER BY k.kind, k.state",
        cdcId,
        from,
        to);
  }

  private Map<String, Long> counts(String sql, String cdcId, Instant from, Instant to) {
    Map<String, Long> out = new LinkedHashMap<>();
    jdbc.query(
        sql,
        rs -> {
          out.put(rs.getString("k"), rs.getLong("n"));
        },
        cdcId,
        Timestamp.from(from),
        Timestamp.from(to));
    return out;
  }

  private static TransferRow transfer(ResultSet rs, int i) throws SQLException {
    return new TransferRow(
        rs.getString("trf_id"),
        rs.getInt("trf_version"),
        rs.getString("mfc_id"),
        rs.getString("state"),
        rs.getString("deadline_meaning"),
        rs.getTimestamp("due_at").toInstant(),
        rs.getString("wave_id"),
        rs.getString("allocation_error"),
        rs.getInt("line_count"),
        rs.getLong("approved"),
        rs.getLong("allocated"),
        rs.getLong("picked"),
        rs.getLong("short_qty"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }

  /** 끝난 작업 한 건의 줄 합(0.27.0 WF-1). */
  public record FinishedTask(
      String assigneeId,
      String kind,
      @Nullable String zone,
      @Nullable String floor,
      @Nullable Instant startedAt,
      @Nullable Instant completedAt,
      int pickedLines,
      int pickedQty,
      int shortLines,
      int shortQty) {}

  /** 끝난 시각이 [from, to) 인 DONE·PARTIAL 작업(담당자 있는 것). */
  public List<FinishedTask> finishedTasks(String cdcId, Instant from, Instant to) {
    return jdbc.query(
        "SELECT t.assignee_id, t.kind, t.zone, t.floor, t.started_at, t.completed_at,"
            + " count(l.line_no) FILTER (WHERE l.picked_qty > 0) AS pl,"
            + " coalesce(sum(l.picked_qty), 0) AS pq,"
            + " count(l.line_no) FILTER (WHERE l.short_qty > 0) AS sl,"
            + " coalesce(sum(l.short_qty), 0) AS sq"
            + " FROM task t LEFT JOIN task_line l ON l.task_id = t.task_id"
            + " WHERE t.cdc_id = ? AND t.state IN ('DONE', 'PARTIAL') AND t.assignee_id IS NOT NULL"
            + " AND t.completed_at >= ? AND t.completed_at < ? GROUP BY t.task_id",
        (rs, i) -> {
          java.sql.Timestamp st = rs.getTimestamp("started_at");
          java.sql.Timestamp ct = rs.getTimestamp("completed_at");
          return new FinishedTask(
              rs.getString("assignee_id"),
              rs.getString("kind"),
              rs.getString("zone"),
              rs.getString("floor"),
              st == null ? null : st.toInstant(),
              ct == null ? null : ct.toInstant(),
              rs.getInt("pl"),
              rs.getInt("pq"),
              rs.getInt("sl"),
              rs.getInt("sq"));
        },
        cdcId,
        java.sql.Timestamp.from(from),
        java.sql.Timestamp.from(to));
  }
}
