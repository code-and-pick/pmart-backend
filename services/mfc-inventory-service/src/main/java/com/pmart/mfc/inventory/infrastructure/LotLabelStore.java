package com.pmart.mfc.inventory.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** LOT 라벨 인쇄 정보(0.20.0, 클라이언트 mfc-app L1~L6). 읽기만 — 재출력은 실적이 아니다. */
@Repository
public class LotLabelStore {

  public record LotRow(
      String lotId,
      String lotNo,
      String sku,
      String tracking,
      @Nullable String sourceLotRef,
      @Nullable LocalDate expiryDate,
      @Nullable LocalDate manufactureDate,
      @Nullable Instant receivedAt,
      int onHand,
      @Nullable Integer qtyAtLocation) {}

  private static final String SELECT =
      "SELECT l.lot_id, l.lot_no, l.sku, l.tracking, l.source_lot_ref, l.expiry_date, l.manufacture_date,"
          + " b.first_normal_at, COALESCE(b.on_hand, 0) AS on_hand";

  private final JdbcTemplate jdbc;

  public LotLabelStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** LOT 한 개와 그 센터 보유(없으면 0). LOT 자체가 없으면 빈 값. */
  public Optional<LotRow> find(String mfcId, String lotId) {
    return jdbc
        .query(
            SELECT
                + ", NULL::integer AS at_location FROM stock_lot l LEFT JOIN stock_lot_balance b"
                + " ON b.lot_id = l.lot_id AND b.mfc_id = ? WHERE l.lot_id = ?",
            (rs, i) -> row(rs),
            mfcId,
            lotId)
        .stream()
        .findFirst();
  }

  /** LOT 번호(GS1 (10), 0.21.0)로 LOT 한 개와 그 센터 보유. */
  public Optional<LotRow> findByLotNo(String mfcId, String lotNo) {
    return jdbc
        .query(
            SELECT
                + ", NULL::integer AS at_location FROM stock_lot l LEFT JOIN stock_lot_balance b"
                + " ON b.lot_id = l.lot_id AND b.mfc_id = ? WHERE l.lot_no = ?",
            (rs, i) -> row(rs),
            mfcId,
            lotNo)
        .stream()
        .findFirst();
  }

  /** 그 센터에 보유가 있는(H > 0) LOT — SKU·위치로 거름, 소비기한 이른 순(없으면 뒤), 최대 limit. */
  public List<LotRow> held(
      String mfcId, @Nullable String sku, @Nullable String locationCode, int limit) {
    StringBuilder sql = new StringBuilder(SELECT);
    List<Object> args = new ArrayList<>();
    if (locationCode != null) {
      sql.append(
          ", u.qty AS at_location FROM stock_lot_balance b JOIN stock_lot l ON l.lot_id = b.lot_id"
              + " JOIN (SELECT sku, lot_id, SUM(qty) AS qty FROM stock_unit WHERE mfc_id = ? AND"
              + " location_code = ? GROUP BY sku, lot_id HAVING SUM(qty) > 0) u ON u.lot_id ="
              + " b.lot_id AND u.sku = b.sku");
      args.add(mfcId);
      args.add(locationCode);
    } else {
      sql.append(
          ", NULL::integer AS at_location FROM stock_lot_balance b JOIN stock_lot l ON l.lot_id ="
              + " b.lot_id");
    }
    sql.append(" WHERE b.mfc_id = ? AND b.on_hand > 0");
    args.add(mfcId);
    if (sku != null) {
      sql.append(" AND b.sku = ?");
      args.add(sku);
    }
    sql.append(
        " ORDER BY l.expiry_date NULLS LAST, b.first_normal_at NULLS LAST, l.lot_id LIMIT ?");
    args.add(limit);
    return jdbc.query(sql.toString(), (rs, i) -> row(rs), args.toArray());
  }

  private static LotRow row(ResultSet rs) throws SQLException {
    Timestamp received = rs.getTimestamp("first_normal_at");
    java.sql.Date expiry = rs.getDate("expiry_date");
    java.sql.Date mfg = rs.getDate("manufacture_date");
    int at = rs.getInt("at_location");
    Integer atLocation = rs.wasNull() ? null : at;
    return new LotRow(
        rs.getString("lot_id"),
        rs.getString("lot_no"),
        rs.getString("sku"),
        rs.getString("tracking"),
        rs.getString("source_lot_ref"),
        expiry == null ? null : expiry.toLocalDate(),
        mfg == null ? null : mfg.toLocalDate(),
        received == null ? null : received.toInstant(),
        rs.getInt("on_hand"),
        atLocation);
  }
}
