package com.pmart.cdc.inventory.infrastructure;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** LOT·위치별 현물/예약·안전량 저장(JDBC). 수량 갱신은 조건부(음수·예약 초과는 0 행). */
@Repository
public class StockStore {

  /** 현물 행 + LOT 정보. */
  public record BalanceRow(
      String sku,
      String lot,
      String locationCode,
      String qualityState,
      int onHand,
      int reserved,
      @Nullable LocalDate expiresAt,
      String identification,
      Instant firstReceivedAt) {}

  public record SafetyRow(int qty, int policyVersion, Instant effectiveFrom) {}

  private static final String BALANCE_SELECT =
      "SELECT b.sku, b.lot, b.location_code, b.quality_state, b.on_hand, b.reserved,"
          + " l.expires_at, l.identification, l.first_received_at FROM stock_balance b"
          + " JOIN stock_lot l ON l.cdc_id = b.cdc_id AND l.sku = b.sku AND l.lot = b.lot"
          + " WHERE b.cdc_id = ? AND b.sku = ?";

  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate named;

  public StockStore(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
    this.jdbc = jdbc;
    this.named = named;
  }

  /** 이미 있는 LOT 키("sku|lot"). */
  public Set<String> existingLots(String cdcId, Collection<String> skus) {
    Set<String> out = new HashSet<>();
    if (skus.isEmpty()) {
      return out;
    }
    named.query(
        "SELECT sku, lot FROM stock_lot WHERE cdc_id = :cdc AND sku IN (:skus)",
        Map.of("cdc", cdcId, "skus", skus),
        rs -> {
          out.add(rs.getString("sku") + "|" + rs.getString("lot"));
        });
    return out;
  }

  public void insertLot(
      String cdcId,
      String sku,
      String lot,
      @Nullable LocalDate expiresAt,
      String identification,
      Instant firstReceivedAt,
      String origin) {
    jdbc.update(
        "INSERT INTO stock_lot (cdc_id, sku, lot, expires_at, identification, first_received_at,"
            + " origin) VALUES (?,?,?,?,?,?,?)",
        cdcId,
        sku,
        lot,
        expiresAt == null ? null : Date.valueOf(expiresAt),
        identification,
        Timestamp.from(firstReceivedAt),
        origin);
  }

  public void insertBalance(
      String cdcId,
      String sku,
      String lot,
      String locationCode,
      String qualityState,
      int qty,
      Instant at) {
    jdbc.update(
        "INSERT INTO stock_balance (cdc_id, sku, lot, location_code, quality_state, on_hand,"
            + " reserved, updated_at) VALUES (?,?,?,?,?,?,0,?)",
        cdcId,
        sku,
        lot,
        locationCode,
        qualityState,
        qty,
        Timestamp.from(at));
  }

  /** SKU 의 모든 현물 행(조회). */
  public List<BalanceRow> balances(String cdcId, String sku) {
    return jdbc.query(
        BALANCE_SELECT + " ORDER BY b.lot, b.location_code, b.quality_state",
        StockStore::balance,
        cdcId,
        sku);
  }

  /** SKU 의 모든 현물 행을 잠근다(할당·해제·출발 — 순서 고정으로 교착 방지). */
  public List<BalanceRow> lockBalances(String cdcId, String sku) {
    return jdbc.query(
        BALANCE_SELECT + " ORDER BY b.lot, b.location_code, b.quality_state FOR UPDATE OF b",
        StockStore::balance,
        cdcId,
        sku);
  }

  /** 정상 현물의 예약 ±delta. 0 ≤ reserved ≤ on_hand 를 벗어나면 false. */
  public boolean addReserved(
      String cdcId, String sku, String lot, String locationCode, int delta, Instant at) {
    return jdbc.update(
            "UPDATE stock_balance SET reserved = reserved + ?, updated_at = ?"
                + " WHERE cdc_id = ? AND sku = ? AND lot = ? AND location_code = ?"
                + " AND quality_state = 'RELEASED' AND reserved + ? BETWEEN 0 AND on_hand",
            delta,
            Timestamp.from(at),
            cdcId,
            sku,
            lot,
            locationCode,
            delta)
        == 1;
  }

  /** TOUT: 현물 −q · 예약 −q(예약된 정상 현물에서만). */
  public boolean depart(
      String cdcId, String sku, String lot, String locationCode, int qty, Instant at) {
    return jdbc.update(
            "UPDATE stock_balance SET on_hand = on_hand - ?, reserved = reserved - ?, updated_at = ?"
                + " WHERE cdc_id = ? AND sku = ? AND lot = ? AND location_code = ?"
                + " AND quality_state = 'RELEASED' AND reserved >= ?",
            qty,
            qty,
            Timestamp.from(at),
            cdcId,
            sku,
            lot,
            locationCode,
            qty)
        == 1;
  }

  /**
   * 이동 출발: 현물 −qty, 그중 예약 −reservedQty. 예약 안 된 현물(on_hand − reserved)이 qty − reservedQty 이상이고 예약이
   * reservedQty 이상일 때만(아니면 false).
   */
  public boolean moveOut(
      String cdcId,
      String sku,
      String lot,
      String locationCode,
      String qualityState,
      int qty,
      int reservedQty,
      Instant at) {
    return jdbc.update(
            "UPDATE stock_balance SET on_hand = on_hand - ?, reserved = reserved - ?, updated_at = ?"
                + " WHERE cdc_id = ? AND sku = ? AND lot = ? AND location_code = ? AND quality_state = ?"
                + " AND reserved >= ? AND on_hand - reserved >= ?",
            qty,
            reservedQty,
            Timestamp.from(at),
            cdcId,
            sku,
            lot,
            locationCode,
            qualityState,
            reservedQty,
            qty - reservedQty)
        == 1;
  }

  /** 이동 도착: 현물 +qty, 예약 +reservedQty(행이 없으면 만든다). */
  public void moveIn(
      String cdcId,
      String sku,
      String lot,
      String locationCode,
      String qualityState,
      int qty,
      int reservedQty,
      Instant at) {
    jdbc.update(
        "INSERT INTO stock_balance (cdc_id, sku, lot, location_code, quality_state, on_hand, reserved,"
            + " updated_at) VALUES (?,?,?,?,?,?,?,?)"
            + " ON CONFLICT (cdc_id, sku, lot, location_code, quality_state) DO UPDATE SET"
            + " on_hand = stock_balance.on_hand + EXCLUDED.on_hand,"
            + " reserved = stock_balance.reserved + EXCLUDED.reserved, updated_at = EXCLUDED.updated_at",
        cdcId,
        sku,
        lot,
        locationCode,
        qualityState,
        qty,
        reservedQty,
        Timestamp.from(at));
  }

  public Optional<SafetyRow> safety(String cdcId, String sku) {
    return jdbc
        .query(
            "SELECT safety_qty, policy_version, effective_from FROM safety_stock"
                + " WHERE cdc_id = ? AND sku = ?",
            (rs, i) ->
                new SafetyRow(
                    rs.getInt("safety_qty"),
                    rs.getInt("policy_version"),
                    rs.getTimestamp("effective_from").toInstant()),
            cdcId,
            sku)
        .stream()
        .findFirst();
  }

  /**
   * 안전량 넣기. 값이 같으면 그대로(false), 다르면 policy_version +1.
   *
   * @return 바뀌었는가
   */
  public boolean upsertSafety(String cdcId, String sku, int qty, String approvalRef, Instant at) {
    return jdbc.update(
            "INSERT INTO safety_stock (cdc_id, sku, safety_qty, policy_version, approval_ref,"
                + " effective_from) VALUES (?,?,?,1,?,?)"
                + " ON CONFLICT (cdc_id, sku) DO UPDATE SET safety_qty = EXCLUDED.safety_qty,"
                + " policy_version = safety_stock.policy_version + 1,"
                + " approval_ref = EXCLUDED.approval_ref, effective_from = EXCLUDED.effective_from"
                + " WHERE safety_stock.safety_qty <> EXCLUDED.safety_qty",
            cdcId,
            sku,
            qty,
            approvalRef,
            Timestamp.from(at))
        == 1;
  }

  /** 출발·미대사 Transit 합(t 포함, 승인 손실 제외). */
  public int transitQty(String cdcId, String sku) {
    Integer v =
        jdbc.queryForObject(
            "SELECT COALESCE(SUM(tl.q - tl.normal_qty - tl.quarantined_qty - tl.loss_qty), 0)"
                + " FROM transit_line tl JOIN transit t ON t.trf_id = tl.trf_id"
                + " WHERE t.cdc_id = ? AND tl.sku = ?",
            Integer.class,
            cdcId,
            sku);
    return v == null ? 0 : v;
  }

  private static BalanceRow balance(ResultSet rs, int i) throws SQLException {
    Date exp = rs.getDate("expires_at");
    return new BalanceRow(
        rs.getString("sku"),
        rs.getString("lot"),
        rs.getString("location_code"),
        rs.getString("quality_state"),
        rs.getInt("on_hand"),
        rs.getInt("reserved"),
        exp == null ? null : exp.toLocalDate(),
        rs.getString("identification"),
        rs.getTimestamp("first_received_at").toInstant());
  }
}
