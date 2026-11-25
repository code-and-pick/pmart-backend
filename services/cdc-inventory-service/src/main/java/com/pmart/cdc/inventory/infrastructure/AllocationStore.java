package com.pmart.cdc.inventory.infrastructure;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 승인 TRF 할당 저장(JDBC). */
@Repository
public class AllocationStore {

  public record AllocationRow(
      String allocationId,
      String cdcId,
      String trfId,
      int trfVersion,
      String state,
      int version,
      String commitId,
      Instant allocatedAt) {}

  public record LineRow(String sku, int requestedQty, int lineNo) {}

  public record LotRow(
      String sku,
      String lot,
      String locationCode,
      @Nullable LocalDate expiresAt,
      int fefoRank,
      int allocatedQty,
      int releasedQty,
      int departedQty,
      int movedInQty,
      int movedOutQty) {
    /** 이 위치에 남은 예약 = 처음 할당 + 옮겨 온 것 − 옮겨 간 것 − 해제 − 출발. */
    public int remaining() {
      return allocatedQty + movedInQty - movedOutQty - releasedQty - departedQty;
    }

    /** 이 위치에 지금 잡혀 있던(잡혀 있는) 양 = 처음 할당 + 옮겨 온 것 − 옮겨 간 것. */
    public int heldHere() {
      return allocatedQty + movedInQty - movedOutQty;
    }
  }

  private static final String SELECT =
      "SELECT allocation_id, cdc_id, trf_id, trf_version, state, row_version, commit_id,"
          + " allocated_at FROM allocation";

  private final JdbcTemplate jdbc;

  public AllocationStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 같은 TRF 의 할당·출발을 줄 세운다(트랜잭션 끝까지). 아직 행이 없는 첫 할당끼리도 막으려고 행 잠금 대신 권고 잠금을 쓴다. */
  public void lockTrf(String trfId) {
    jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?))", rs -> {}, "cdc-inventory:trf:" + trfId);
  }

  public void insert(AllocationRow a) {
    jdbc.update(
        "INSERT INTO allocation (allocation_id, cdc_id, trf_id, trf_version, state, row_version,"
            + " commit_id, allocated_at) VALUES (?,?,?,?,?,?,?,?)",
        a.allocationId(),
        a.cdcId(),
        a.trfId(),
        a.trfVersion(),
        a.state(),
        a.version(),
        a.commitId(),
        Timestamp.from(a.allocatedAt()));
  }

  public void insertLine(String allocationId, LineRow l) {
    jdbc.update(
        "INSERT INTO allocation_line (allocation_id, sku, requested_qty, line_no) VALUES (?,?,?,?)",
        allocationId,
        l.sku(),
        l.requestedQty(),
        l.lineNo());
  }

  public void insertLot(String allocationId, LotRow l) {
    jdbc.update(
        "INSERT INTO allocation_lot (allocation_id, sku, lot, location_code, expires_at, fefo_rank,"
            + " allocated_qty) VALUES (?,?,?,?,?,?,?)",
        allocationId,
        l.sku(),
        l.lot(),
        l.locationCode(),
        l.expiresAt() == null ? null : Date.valueOf(l.expiresAt()),
        l.fefoRank(),
        l.allocatedQty());
  }

  public Optional<AllocationRow> find(String allocationId, boolean lock) {
    return jdbc
        .query(
            SELECT + " WHERE allocation_id = ?" + (lock ? " FOR UPDATE" : ""),
            (rs, i) -> row(rs),
            allocationId)
        .stream()
        .findFirst();
  }

  /** 같은 TRF 의 할당(버전 무관) — 잠근다. */
  public List<AllocationRow> lockByTrf(String trfId) {
    return jdbc.query(
        SELECT + " WHERE trf_id = ? ORDER BY trf_version FOR UPDATE", (rs, i) -> row(rs), trfId);
  }

  public List<LineRow> lines(String allocationId) {
    return jdbc.query(
        "SELECT sku, requested_qty, line_no FROM allocation_line WHERE allocation_id = ?"
            + " ORDER BY line_no",
        (rs, i) ->
            new LineRow(rs.getString("sku"), rs.getInt("requested_qty"), rs.getInt("line_no")),
        allocationId);
  }

  public List<LotRow> lots(String allocationId) {
    return jdbc.query(
        "SELECT sku, lot, location_code, expires_at, fefo_rank, allocated_qty, released_qty,"
            + " departed_qty, moved_in_qty, moved_out_qty FROM allocation_lot"
            + " WHERE allocation_id = ? ORDER BY sku, fefo_rank, location_code",
        (rs, i) -> {
          Date exp = rs.getDate("expires_at");
          return new LotRow(
              rs.getString("sku"),
              rs.getString("lot"),
              rs.getString("location_code"),
              exp == null ? null : exp.toLocalDate(),
              rs.getInt("fefo_rank"),
              rs.getInt("allocated_qty"),
              rs.getInt("released_qty"),
              rs.getInt("departed_qty"),
              rs.getInt("moved_in_qty"),
              rs.getInt("moved_out_qty"));
        },
        allocationId);
  }

  /** 추가 할당(0.7.0): 같은 LOT·위치 행이 있으면 처음 할당량에 더하고, 없으면 새 행. */
  public void addAllocated(String allocationId, LotRow l) {
    jdbc.update(
        "INSERT INTO allocation_lot (allocation_id, sku, lot, location_code, expires_at, fefo_rank,"
            + " allocated_qty) VALUES (?,?,?,?,?,?,?)"
            + " ON CONFLICT (allocation_id, sku, lot, location_code)"
            + " DO UPDATE SET allocated_qty = allocation_lot.allocated_qty + EXCLUDED.allocated_qty",
        allocationId,
        l.sku(),
        l.lot(),
        l.locationCode(),
        l.expiresAt() == null ? null : Date.valueOf(l.expiresAt()),
        l.fefoRank(),
        l.allocatedQty());
  }

  public void addReleased(String allocationId, LotRow l, int qty) {
    jdbc.update(
        "UPDATE allocation_lot SET released_qty = released_qty + ?"
            + " WHERE allocation_id = ? AND sku = ? AND lot = ? AND location_code = ?",
        qty,
        allocationId,
        l.sku(),
        l.lot(),
        l.locationCode());
  }

  public void addDeparted(String allocationId, LotRow l, int qty) {
    jdbc.update(
        "UPDATE allocation_lot SET departed_qty = departed_qty + ?"
            + " WHERE allocation_id = ? AND sku = ? AND lot = ? AND location_code = ?",
        qty,
        allocationId,
        l.sku(),
        l.lot(),
        l.locationCode());
  }

  /** 예약을 다른 위치로: 원위치 moved_out +q, 도착 위치 moved_in +q(행이 없으면 처음 할당 0 으로 만든다). */
  public void move(String allocationId, LotRow from, String toLocation, int qty) {
    jdbc.update(
        "UPDATE allocation_lot SET moved_out_qty = moved_out_qty + ?"
            + " WHERE allocation_id = ? AND sku = ? AND lot = ? AND location_code = ?",
        qty,
        allocationId,
        from.sku(),
        from.lot(),
        from.locationCode());
    jdbc.update(
        "INSERT INTO allocation_lot (allocation_id, sku, lot, location_code, expires_at, fefo_rank,"
            + " allocated_qty, moved_in_qty) VALUES (?,?,?,?,?,?,0,?)"
            + " ON CONFLICT (allocation_id, sku, lot, location_code)"
            + " DO UPDATE SET moved_in_qty = allocation_lot.moved_in_qty + EXCLUDED.moved_in_qty",
        allocationId,
        from.sku(),
        from.lot(),
        toLocation,
        from.expiresAt() == null ? null : Date.valueOf(from.expiresAt()),
        from.fefoRank(),
        qty);
  }

  public void updateState(String allocationId, String state) {
    jdbc.update(
        "UPDATE allocation SET state = ?, row_version = row_version + 1 WHERE allocation_id = ?",
        state,
        allocationId);
  }

  private static AllocationRow row(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new AllocationRow(
        rs.getString("allocation_id"),
        rs.getString("cdc_id"),
        rs.getString("trf_id"),
        rs.getInt("trf_version"),
        rs.getString("state"),
        rs.getInt("row_version"),
        rs.getString("commit_id"),
        rs.getTimestamp("allocated_at").toInstant());
  }
}
