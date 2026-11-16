package com.pmart.mfc.inventory.infrastructure;

import com.pmart.mfc.inventory.domain.ReservationState;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 예약 저장(JDBC): 머리 {@code reservation} + 조각 {@code reservation_part}(LOT 단위, ADR-0040 결정 4). 상태 전이는
 * 호출자가 {@link #lock} 으로 예약 행을 잠근 뒤에 한다. 잠금 순서는 예약 행 → 센터 요약(SKU 오름차순) → LOT 요약.
 */
@Repository
public class ReservationStore {

  /** 예약 머리. */
  public record ReservationRow(
      String reservationId,
      String orderId,
      String mfcId,
      ReservationState state,
      @Nullable Instant expiresAt,
      @Nullable String releaseReason,
      int version,
      @Nullable LocalDate promisedDate,
      Instant createdAt,
      Instant updatedAt) {}

  /** 예약 조각. */
  public record PartRow(
      String partId,
      @Nullable String orderLineId,
      String sku,
      String lotId,
      String kind,
      int qty,
      ReservationState state,
      @Nullable String blockedReason) {}

  /** 피킹·포장용 조각(집품·포장한 양 포함, IV-07). */
  public record PickPart(
      String partId,
      @Nullable String orderLineId,
      String sku,
      String lotId,
      ReservationState state,
      int qty,
      int pickedQty,
      int packedQty) {}

  private static final String COLUMNS =
      "reservation_id, order_id, mfc_id, state, expires_at, release_reason, row_version,"
          + " promised_date, created_at, updated_at";

  private final JdbcTemplate jdbc;
  private final AllocationStore allocations;

  public ReservationStore(JdbcTemplate jdbc, AllocationStore allocations) {
    this.jdbc = jdbc;
    this.allocations = allocations;
  }

  public void insert(
      String reservationId,
      String orderId,
      String mfcId,
      Instant expiresAt,
      @Nullable LocalDate promisedDate,
      String sourceSystem,
      String requestId,
      Instant at) {
    jdbc.update(
        "INSERT INTO reservation (reservation_id, order_id, mfc_id, state, expires_at, row_version,"
            + " promised_date, source_system, request_id, created_at, updated_at)"
            + " VALUES (?,?,?,'HELD',?,1,?,?,?,?,?)",
        reservationId,
        orderId,
        mfcId,
        Timestamp.from(expiresAt),
        promisedDate == null ? null : Date.valueOf(promisedDate),
        sourceSystem,
        requestId,
        Timestamp.from(at),
        Timestamp.from(at));
  }

  public void insertPart(String reservationId, PartRow p, Instant at) {
    jdbc.update(
        "INSERT INTO reservation_part (part_id, reservation_id, order_line_id, sku, lot_id, kind,"
            + " qty, state, blocked_reason, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
        p.partId(),
        reservationId,
        p.orderLineId(),
        p.sku(),
        p.lotId(),
        p.kind(),
        p.qty(),
        p.state().name(),
        p.blockedReason(),
        Timestamp.from(at),
        Timestamp.from(at));
  }

  public Optional<ReservationRow> find(String reservationId) {
    return jdbc
        .query(
            "SELECT " + COLUMNS + " FROM reservation WHERE reservation_id = ?",
            (rs, i) -> row(rs),
            reservationId)
        .stream()
        .findFirst();
  }

  /** 예약 행을 잠그고 읽는다. */
  /** S4: 배송 약속일 바꾸기. */
  public void setPromisedDate(String reservationId, java.time.LocalDate date, Instant at) {
    jdbc.update(
        "UPDATE reservation SET promised_date = ?, row_version = row_version + 1, updated_at = ?"
            + " WHERE reservation_id = ?",
        java.sql.Date.valueOf(date),
        java.sql.Timestamp.from(at),
        reservationId);
  }

  public Optional<ReservationRow> lock(String reservationId) {
    return jdbc
        .query(
            "SELECT " + COLUMNS + " FROM reservation WHERE reservation_id = ? FOR UPDATE",
            (rs, i) -> row(rs),
            reservationId)
        .stream()
        .findFirst();
  }

  /**
   * 만료 시각이 지난 HELD 예약을 잠근다. 다른 인스턴스·요청이 잡고 있는 행은 건너뛴다(SKIP LOCKED) — 확정·해제 요청과 겹치면 그쪽이 먼저 끝내고, 다음
   * 주기에 다시 본다.
   */
  public List<ReservationRow> lockExpired(Instant now, int limit) {
    return jdbc.query(
        "SELECT "
            + COLUMNS
            + " FROM reservation WHERE state = 'HELD' AND expires_at <= ?"
            + " ORDER BY expires_at LIMIT ? FOR UPDATE SKIP LOCKED",
        (rs, i) -> row(rs),
        Timestamp.from(now),
        limit);
  }

  /** 조각(SKU·LOT 순). */
  public List<PartRow> parts(String reservationId) {
    return jdbc.query(
        "SELECT part_id, order_line_id, sku, lot_id, kind, qty, state, blocked_reason"
            + " FROM reservation_part WHERE reservation_id = ? ORDER BY sku, lot_id, part_id",
        (rs, i) ->
            new PartRow(
                rs.getString("part_id"),
                rs.getString("order_line_id"),
                rs.getString("sku"),
                rs.getString("lot_id"),
                rs.getString("kind"),
                rs.getInt("qty"),
                ReservationState.valueOf(rs.getString("state")),
                rs.getString("blocked_reason")),
        reservationId);
  }

  /** 한 LOT 에 걸린, 아직 집지 않은 양이 있는 HELD·CONFIRMED 조각. */
  public record LotPart(
      String partId,
      String reservationId,
      @Nullable String orderLineId,
      int qty,
      int pickedQty,
      ReservationState state) {}

  /**
   * 센터·SKU·LOT 의 HELD·CONFIRMED 조각 중 아직 집지 않은 양이 있는 것 — 나중에 잡은 예약부터(품질 격리 보류 순서, 기존 약속 우선). 예약 행을
   * 잠근 뒤 다시 부르면 그 사이 변화가 반영된다.
   */
  public List<LotPart> activeLotParts(String mfcId, String sku, String lotId) {
    return jdbc.query(
        "SELECT p.part_id, p.reservation_id, p.order_line_id, p.qty, p.picked_qty, p.state"
            + " FROM reservation_part p JOIN reservation r ON r.reservation_id = p.reservation_id"
            + " WHERE r.mfc_id = ? AND p.sku = ? AND p.lot_id = ? AND p.state IN ('HELD', 'CONFIRMED')"
            + " AND p.qty > p.picked_qty ORDER BY r.created_at DESC, p.part_id DESC",
        (rs, i) ->
            new LotPart(
                rs.getString("part_id"),
                rs.getString("reservation_id"),
                rs.getString("order_line_id"),
                rs.getInt("qty"),
                rs.getInt("picked_qty"),
                ReservationState.valueOf(rs.getString("state"))),
        mfcId,
        sku,
        lotId);
  }

  /** 피킹·포장 조각(part_id 순). lock 이면 FOR UPDATE — 예약 행을 먼저 잠근 뒤 부른다. */
  public List<PickPart> pickParts(String reservationId, boolean lock) {
    return jdbc.query(
        "SELECT part_id, order_line_id, sku, lot_id, state, qty, picked_qty, packed_qty"
            + " FROM reservation_part WHERE reservation_id = ? ORDER BY part_id"
            + (lock ? " FOR UPDATE" : ""),
        (rs, i) ->
            new PickPart(
                rs.getString("part_id"),
                rs.getString("order_line_id"),
                rs.getString("sku"),
                rs.getString("lot_id"),
                ReservationState.valueOf(rs.getString("state")),
                rs.getInt("qty"),
                rs.getInt("picked_qty"),
                rs.getInt("packed_qty")),
        reservationId);
  }

  /** 집품(pick=true)·포장 양 증가. CHECK(packed ≤ picked ≤ qty)가 마지막 방어선. */
  public void addPicked(String partId, int qty, boolean pick, Instant at) {
    jdbc.update(
        "UPDATE reservation_part SET "
            + (pick ? "picked_qty = picked_qty + ?" : "packed_qty = packed_qty + ?")
            + ", updated_at = ? WHERE part_id = ?",
        qty,
        Timestamp.from(at),
        partId);
  }

  /** 조각 수량 줄이기(결품 보고 — 아직 집지 않은 양에서만, IV-09). */
  public void shrinkPart(String partId, int newQty, Instant at) {
    jdbc.update(
        "UPDATE reservation_part SET qty = ?, updated_at = ? WHERE part_id = ?",
        newQty,
        Timestamp.from(at),
        partId);
    Integer picked =
        jdbc.queryForObject(
            "SELECT picked_qty FROM reservation_part WHERE part_id = ?", Integer.class, partId);
    allocations.trim(partId, newQty - (picked == null ? 0 : picked), at); // 위치 배정도 같이 줄인다
  }

  /** 조각 상태 바꾸기(결품 보고로 전부 빠진 조각 → RELEASED, 사유 기록). */
  public void setPartState(
      String partId, ReservationState state, @Nullable String reason, Instant at) {
    jdbc.update(
        "UPDATE reservation_part SET state = ?, blocked_reason = ?, updated_at = ? WHERE part_id = ?",
        state.name(),
        reason,
        Timestamp.from(at),
        partId);
    if (state != ReservationState.CONFIRMED) {
      allocations.releasePart(partId, at);
    }
  }

  /** 집품이 시작된 조각이 있는가(해제 차단 — 집품 후 취소는 IV-08). */
  /** 피킹 시작 표시(위치 배정 호출 = PICK 시작, #5) — 처음 한 번만. 그 뒤 보류분 자동 재확보는 하지 않는다. */
  public void markPickingStarted(String reservationId, Instant at) {
    jdbc.update(
        "UPDATE reservation SET picking_started_at = ? WHERE reservation_id = ?"
            + " AND picking_started_at IS NULL",
        java.sql.Timestamp.from(at),
        reservationId);
  }

  public boolean anyPicked(String reservationId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM reservation_part WHERE reservation_id = ? AND picked_qty > 0",
            Integer.class,
            reservationId);
    return n != null && n > 0;
  }

  /** SKU 오름차순 SKU → 현물 점유 수량(HELD+CONFIRMED 조각 합). */
  public Map<String, Integer> heldBySku(String reservationId) {
    Map<String, Integer> out = new TreeMap<>();
    for (PartRow p : parts(reservationId)) {
      if (p.state().holdsStock()) {
        out.merge(p.sku(), p.qty(), Integer::sum);
      }
    }
    return out;
  }

  /** 머리 상태 전이(잠근 행). 확정·해제·만료 뒤에는 만료 시각을 지운다. */
  public void transition(
      String reservationId,
      ReservationState to,
      @Nullable String releaseReason,
      int newVersion,
      Instant at) {
    jdbc.update(
        "UPDATE reservation SET state = ?, expires_at = NULL, release_reason = ?, row_version = ?,"
            + " updated_at = ? WHERE reservation_id = ?",
        to.name(),
        releaseReason,
        newVersion,
        Timestamp.from(at),
        reservationId);
  }

  /** 조각 상태 전이: from 상태인 조각 전부를 to 로. */
  public int transitionParts(
      String reservationId, ReservationState from, ReservationState to, Instant at) {
    int n =
        jdbc.update(
            "UPDATE reservation_part SET state = ?, updated_at = ? WHERE reservation_id = ?"
                + " AND state = ?",
            to.name(),
            Timestamp.from(at),
            reservationId,
            from.name());
    if (to != ReservationState.CONFIRMED) {
      allocations.releaseUnconfirmed(reservationId, at);
    }
    return n;
  }

  private static ReservationRow row(ResultSet rs) throws SQLException {
    Timestamp exp = rs.getTimestamp("expires_at");
    Date pd = rs.getDate("promised_date");
    return new ReservationRow(
        rs.getString("reservation_id"),
        rs.getString("order_id"),
        rs.getString("mfc_id"),
        ReservationState.valueOf(rs.getString("state")),
        exp == null ? null : exp.toInstant(),
        rs.getString("release_reason"),
        rs.getInt("row_version"),
        pd == null ? null : pd.toLocalDate(),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }
}
