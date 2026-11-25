package com.pmart.cdc.outbound.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 피킹 부족 후속(0.11.0) — TRF 몫의 부족 하나 = 행 하나. */
@Repository
public class ShortageStore {

  public record FollowupRow(
      String followupId,
      String trfId,
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
      @Nullable String reason) {}

  private static final String COLS =
      "followup_id, trf_id, wave_id, task_id, sku, lot, location_code, short_qty, state,"
          + " reallocated_qty, unresolved_qty, new_task_ids, reason";

  private final JdbcTemplate jdbc;

  public ShortageStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(FollowupRow r, Instant at) {
    jdbc.update(
        "INSERT INTO pick_shortage_followup ("
            + COLS
            + ", created_at, updated_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        r.followupId(),
        r.trfId(),
        r.waveId(),
        r.taskId(),
        r.sku(),
        r.lot(),
        r.locationCode(),
        r.shortQty(),
        r.state(),
        r.reallocatedQty(),
        r.unresolvedQty(),
        r.newTaskIds(),
        r.reason(),
        Timestamp.from(at),
        Timestamp.from(at));
  }

  /** 아직 끝나지 않은 것(만든 순). */
  public List<FollowupRow> open(int limit) {
    return jdbc.query(
        "SELECT "
            + COLS
            + " FROM pick_shortage_followup WHERE state IN ('PENDING', 'RELEASED')"
            + " ORDER BY created_at, followup_id LIMIT ?",
        (rs, i) -> row(rs),
        limit);
  }

  public int openOfTrf(String trfId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM pick_shortage_followup WHERE trf_id = ?"
                + " AND state IN ('PENDING', 'RELEASED')",
            Integer.class,
            trfId);
    return n == null ? 0 : n;
  }

  public List<FollowupRow> ofTrf(String trfId) {
    return jdbc.query(
        "SELECT " + COLS + " FROM pick_shortage_followup WHERE trf_id = ? ORDER BY created_at",
        (rs, i) -> row(rs),
        trfId);
  }

  public Optional<FollowupRow> find(String id, boolean lock) {
    return jdbc
        .query(
            "SELECT "
                + COLS
                + " FROM pick_shortage_followup WHERE followup_id = ?"
                + (lock ? " FOR UPDATE" : ""),
            (rs, i) -> row(rs),
            id)
        .stream()
        .findFirst();
  }

  public void released(String id, Instant at) {
    jdbc.update(
        "UPDATE pick_shortage_followup SET state = 'RELEASED', updated_at = ?"
            + " WHERE followup_id = ? AND state = 'PENDING'",
        Timestamp.from(at),
        id);
  }

  public void done(
      String id, int reallocated, int unresolved, @Nullable String taskIds, Instant at) {
    jdbc.update(
        "UPDATE pick_shortage_followup SET state = 'DONE', reallocated_qty = ?, unresolved_qty = ?,"
            + " new_task_ids = ?, updated_at = ? WHERE followup_id = ?",
        reallocated,
        unresolved,
        taskIds,
        Timestamp.from(at),
        id);
  }

  public void failed(String id, String reason, int unresolved, Instant at) {
    jdbc.update(
        "UPDATE pick_shortage_followup SET state = 'FAILED', reason = ?, unresolved_qty = ?,"
            + " updated_at = ? WHERE followup_id = ?",
        reason.length() > 300 ? reason.substring(0, 300) : reason,
        unresolved,
        Timestamp.from(at),
        id);
  }

  private static FollowupRow row(ResultSet rs) throws SQLException {
    return new FollowupRow(
        rs.getString("followup_id"),
        rs.getString("trf_id"),
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
        rs.getString("reason"));
  }
}
