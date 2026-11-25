package com.pmart.cdc.inventory.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 재계수 대기(0.7.0) — 피킹 부족 칸. HELD 동안 새 할당 후보에서 빠진다. */
@Repository
public class RecountHoldStore {

  public record HoldRow(
      String holdId,
      String cdcId,
      String sku,
      String lot,
      String locationCode,
      String state,
      int shortQty,
      @Nullable String allocationId,
      @Nullable String sourceRef,
      Instant createdAt,
      @Nullable Instant releasedAt,
      @Nullable String releasedBy,
      @Nullable String releaseReason) {}

  private static final String COLS =
      "hold_id, cdc_id, sku, lot, location_code, state, short_qty, allocation_id, source_ref,"
          + " created_at, released_at, released_by, release_reason";

  private final JdbcTemplate jdbc;

  public RecountHoldStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** SKU 의 대기 중 칸 — "LOT|위치". */
  public Set<String> heldKeys(String cdcId, String sku) {
    Set<String> out = new HashSet<>();
    jdbc.query(
        "SELECT lot, location_code FROM recount_hold WHERE cdc_id = ? AND sku = ? AND state = 'HELD'",
        rs -> {
          out.add(rs.getString("lot") + "|" + rs.getString("location_code"));
        },
        cdcId,
        sku);
    return out;
  }

  /** 대기를 만든다. 이미 대기 중이면 부족 수만 더한다. */
  public void hold(
      String cdcId,
      String sku,
      String lot,
      String locationCode,
      int shortQty,
      @Nullable String allocationId,
      @Nullable String sourceRef,
      String by,
      Instant at) {
    jdbc.update(
        "INSERT INTO recount_hold (hold_id, cdc_id, sku, lot, location_code, state, short_qty,"
            + " allocation_id, source_ref, created_by, created_at) VALUES (?,?,?,?,?,'HELD',?,?,?,?,?)"
            + " ON CONFLICT (cdc_id, sku, lot, location_code) WHERE state = 'HELD'"
            + " DO UPDATE SET short_qty = recount_hold.short_qty + EXCLUDED.short_qty",
        "RCH-" + UUID.randomUUID(),
        cdcId,
        sku,
        lot,
        locationCode,
        shortQty,
        allocationId,
        sourceRef,
        by,
        Timestamp.from(at));
  }

  public List<HoldRow> list(String cdcId, @Nullable String state) {
    return state == null
        ? jdbc.query(
            "SELECT " + COLS + " FROM recount_hold WHERE cdc_id = ? ORDER BY created_at, hold_id",
            (rs, i) -> row(rs),
            cdcId)
        : jdbc.query(
            "SELECT "
                + COLS
                + " FROM recount_hold WHERE cdc_id = ? AND state = ? ORDER BY created_at, hold_id",
            (rs, i) -> row(rs),
            cdcId,
            state);
  }

  public Optional<HoldRow> find(String holdId, boolean lock) {
    return jdbc
        .query(
            "SELECT " + COLS + " FROM recount_hold WHERE hold_id = ?" + (lock ? " FOR UPDATE" : ""),
            (rs, i) -> row(rs),
            holdId)
        .stream()
        .findFirst();
  }

  public void release(String holdId, String by, String reason, Instant at) {
    jdbc.update(
        "UPDATE recount_hold SET state = 'RELEASED', released_at = ?, released_by = ?,"
            + " release_reason = ? WHERE hold_id = ? AND state = 'HELD'",
        Timestamp.from(at),
        by,
        reason,
        holdId);
  }

  /** 실사가 끝난 칸의 대기를 푼다(0.8.0 — 다시 세어 장부가 맞춰짐). 푼 수. */
  public int releaseAt(
      String cdcId, java.util.Collection<String> locations, String by, String reason, Instant at) {
    int n = 0;
    for (String l : locations) {
      n +=
          jdbc.update(
              "UPDATE recount_hold SET state = 'RELEASED', released_at = ?, released_by = ?,"
                  + " release_reason = ? WHERE cdc_id = ? AND location_code = ? AND state = 'HELD'",
              Timestamp.from(at),
              by,
              reason,
              cdcId,
              l);
    }
    return n;
  }

  private static HoldRow row(ResultSet rs) throws SQLException {
    Timestamp rel = rs.getTimestamp("released_at");
    return new HoldRow(
        rs.getString("hold_id"),
        rs.getString("cdc_id"),
        rs.getString("sku"),
        rs.getString("lot"),
        rs.getString("location_code"),
        rs.getString("state"),
        rs.getInt("short_qty"),
        rs.getString("allocation_id"),
        rs.getString("source_ref"),
        rs.getTimestamp("created_at").toInstant(),
        rel == null ? null : rel.toInstant(),
        rs.getString("released_by"),
        rs.getString("release_reason"));
  }
}
