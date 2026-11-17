package com.pmart.mfc.inventory.infrastructure;

import com.pmart.mfc.inventory.domain.StockRules.Balance;
import com.pmart.mfc.inventory.domain.StockUnit.Key;
import com.pmart.mfc.inventory.domain.StockUnit.Quality;
import com.pmart.mfc.inventory.domain.StockUnit.Work;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 재고 원장 저장(JDBC, ADR-0040): 단위 행 {@code stock_unit}, LOT {@code stock_lot}, LOT 요약 {@code
 * stock_lot_balance}, 센터 요약 {@code stock_balance}, 확정 단위 {@code stock_commit}·{@code
 * stock_ledger_entry}, 수령·실행 중복 방지. 수량 변경은 호출자(application {@code Ledger})가 정한 잠금 순서(센터 요약 → LOT 요약
 * → 단위 행) 안에서 하고, 불변식은 표 CHECK 로도 막는다.
 */
@Repository
public class StockStore {

  /** 확정 단위 머리. */
  public record CommitRow(
      String commitId,
      String commitType,
      String mfcId,
      @Nullable String requestId,
      String sourceSystem,
      @Nullable String sourceRef,
      int appliedQty,
      Instant occurredAt) {}

  /** 원장 기록 한 줄. 요약 수준 기록(R)은 위치·상태가 없다. */
  public record EntryRow(
      String sku,
      @Nullable String lotId,
      @Nullable String locationCode,
      @Nullable String qualityState,
      @Nullable String workState,
      String symbol,
      int delta,
      long balanceVersion) {}

  /** 이미 전기된 수령·실행. */
  public record ReceiptRow(String receiptId, String contentHash, String commitId, int appliedQty) {}

  /** LOT 요약 + LOT 정보. */
  public record LotBalance(
      String sku,
      String lotId,
      String lotNo,
      @Nullable LocalDate expiryDate,
      @Nullable LocalDate manufactureDate,
      String tracking,
      int onHand,
      int quarantined,
      int sellable,
      int reserved,
      @Nullable Instant firstNormalAt,
      long version) {

    public int available() {
      return sellable - reserved;
    }
  }

  /** LOT. */
  public record LotRow(
      String lotId,
      String sku,
      @Nullable String sourceLotRef,
      @Nullable LocalDate expiryDate,
      @Nullable LocalDate manufactureDate,
      String tracking,
      String lotNo) {}

  /** 수령 줄 결과. */
  public record ReceiptLineRow(
      int lineNo,
      String sku,
      String lotId,
      String locationCode,
      int normalQty,
      int quarantinedQty,
      int pendingQty) {}

  /** 단위 행. */
  public record UnitRow(Key key, int qty, long version) {}

  private static final String BALANCE_COLUMNS =
      "sku, on_hand, quarantined, sellable, reserved, row_version";

  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate named;

  public StockStore(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
    this.jdbc = jdbc;
    this.named = named;
  }

  // ---------- 센터 요약 ----------

  /** 없는 센터 요약 행을 0 으로 만든다(수령 등 처음 생기는 SKU). 이미 있으면 그대로. */
  public void ensureCenterRows(String mfcId, Collection<String> skus, Instant at) {
    for (String sku : skus) {
      jdbc.update(
          "INSERT INTO stock_balance (mfc_id, sku, on_hand, quarantined, sellable, reserved,"
              + " row_version, updated_at) VALUES (?,?,0,0,0,0,0,?) ON CONFLICT DO NOTHING",
          mfcId,
          sku,
          Timestamp.from(at));
    }
  }

  /** 센터 요약 행을 SKU 오름차순으로 잠근다(모든 수량 변경의 첫 잠금). 행이 없는 SKU 는 결과에 없다(= 0). */
  public Map<String, Balance> lockBalances(String mfcId, Collection<String> skus) {
    Map<String, Balance> out = new LinkedHashMap<>();
    if (skus.isEmpty()) {
      return out;
    }
    named.query(
        "SELECT "
            + BALANCE_COLUMNS
            + " FROM stock_balance WHERE mfc_id = :mfc AND sku IN (:skus) ORDER BY sku FOR UPDATE",
        Map.of("mfc", mfcId, "skus", skus),
        rs -> {
          Balance b = balance(rs);
          out.put(b.sku(), b);
        });
    return out;
  }

  /** 잠금 없는 조회(가용 조회). */
  public Map<String, Balance> balances(String mfcId, Collection<String> skus) {
    Map<String, Balance> out = new LinkedHashMap<>();
    if (skus.isEmpty()) {
      return out;
    }
    named.query(
        "SELECT " + BALANCE_COLUMNS + " FROM stock_balance WHERE mfc_id = :mfc AND sku IN (:skus)",
        Map.of("mfc", mfcId, "skus", skus),
        rs -> {
          Balance b = balance(rs);
          out.put(b.sku(), b);
        });
    return out;
  }

  /**
   * 센터 요약 증감(잠근 행).
   *
   * @return 반영 후 row_version
   */
  public long applyCenter(String mfcId, String sku, int dh, int dq, int de, int dr, Instant at) {
    Long v =
        jdbc.queryForObject(
            "UPDATE stock_balance SET on_hand = on_hand + ?, quarantined = quarantined + ?,"
                + " sellable = sellable + ?, reserved = reserved + ?, row_version = row_version + 1,"
                + " updated_at = ? WHERE mfc_id = ? AND sku = ? RETURNING row_version",
            Long.class,
            dh,
            dq,
            de,
            dr,
            Timestamp.from(at),
            mfcId,
            sku);
    return v == null ? 0 : v;
  }

  // ---------- LOT ----------

  /** 추적 LOT: 같은 SKU·원문·소비기한·제조일이면 같은 LOT(제조일은 ADR-0041). 없으면 만든다(경합 시 유일 인덱스가 하나만 남긴다). */
  public String trackedLot(
      String sku,
      @Nullable String sourceLotRef,
      @Nullable LocalDate expiryDate,
      @Nullable LocalDate manufactureDate,
      String newLotId,
      String originRef,
      Instant at) {
    Date exp = expiryDate == null ? null : Date.valueOf(expiryDate);
    Date mfg = manufactureDate == null ? null : Date.valueOf(manufactureDate);
    jdbc.update(
        "INSERT INTO stock_lot (lot_id, sku, source_lot_ref, expiry_date, manufacture_date, tracking,"
            + " origin_ref, created_at) VALUES (?,?,?,?,?,'TRACKED',?,?)"
            + " ON CONFLICT (sku, source_lot_ref, expiry_date, manufacture_date)"
            + " WHERE tracking = 'TRACKED' DO NOTHING",
        newLotId,
        sku,
        sourceLotRef,
        exp,
        mfg,
        originRef,
        Timestamp.from(at));
    return jdbc.queryForObject(
        "SELECT lot_id FROM stock_lot WHERE tracking = 'TRACKED' AND sku = ?"
            + " AND source_lot_ref IS NOT DISTINCT FROM ? AND expiry_date IS NOT DISTINCT FROM ?"
            + " AND manufacture_date IS NOT DISTINCT FROM ?",
        String.class,
        sku,
        sourceLotRef,
        exp,
        mfg);
  }

  /** 추적 미확인 LOT(수령마다 새로). */
  public String untrackedLot(String sku, String newLotId, String originRef, Instant at) {
    jdbc.update(
        "INSERT INTO stock_lot (lot_id, sku, tracking, origin_ref, created_at)"
            + " VALUES (?,?,'UNTRACKED',?,?)",
        newLotId,
        sku,
        originRef,
        Timestamp.from(at));
    return newLotId;
  }

  public Map<String, LotRow> lots(Collection<String> lotIds) {
    Map<String, LotRow> out = new LinkedHashMap<>();
    if (lotIds.isEmpty()) {
      return out;
    }
    named.query(
        "SELECT lot_id, sku, source_lot_ref, expiry_date, manufacture_date, tracking, lot_no"
            + " FROM stock_lot WHERE lot_id IN (:ids)",
        Map.of("ids", lotIds),
        rs -> {
          Date d = rs.getDate("expiry_date");
          Date m = rs.getDate("manufacture_date");
          out.put(
              rs.getString("lot_id"),
              new LotRow(
                  rs.getString("lot_id"),
                  rs.getString("sku"),
                  rs.getString("source_lot_ref"),
                  d == null ? null : d.toLocalDate(),
                  m == null ? null : m.toLocalDate(),
                  rs.getString("tracking"),
                  rs.getString("lot_no")));
        });
    return out;
  }

  // ---------- LOT 요약 ----------

  /** 없는 LOT 요약 행을 0 으로 만든다. */
  public void ensureLotRow(String mfcId, String sku, String lotId, Instant at) {
    jdbc.update(
        "INSERT INTO stock_lot_balance (mfc_id, sku, lot_id, row_version, updated_at)"
            + " VALUES (?,?,?,0,?) ON CONFLICT DO NOTHING",
        mfcId,
        sku,
        lotId,
        Timestamp.from(at));
  }

  private static final String LOT_BALANCE_SELECT =
      "SELECT b.sku, b.lot_id, l.lot_no, l.expiry_date, l.manufacture_date, l.tracking, b.on_hand, b.quarantined, b.sellable,"
          + " b.reserved, b.first_normal_at, b.row_version"
          + " FROM stock_lot_balance b JOIN stock_lot l ON l.lot_id = b.lot_id";

  /** SKU 들의 LOT 요약을 (SKU, lot_id) 순으로 잠근다. 센터 요약을 먼저 잠근 뒤에 부른다. */
  public List<LotBalance> lockLotBalances(String mfcId, Collection<String> skus) {
    if (skus.isEmpty()) {
      return List.of();
    }
    return named.query(
        LOT_BALANCE_SELECT
            + " WHERE b.mfc_id = :mfc AND b.sku IN (:skus) ORDER BY b.sku, b.lot_id"
            + " FOR UPDATE OF b",
        Map.of("mfc", mfcId, "skus", skus),
        (rs, i) -> lotBalance(rs));
  }

  public List<LotBalance> lotBalances(String mfcId, String sku) {
    return jdbc.query(
        LOT_BALANCE_SELECT + " WHERE b.mfc_id = ? AND b.sku = ? ORDER BY b.lot_id",
        (rs, i) -> lotBalance(rs),
        mfcId,
        sku);
  }

  /**
   * LOT 요약 증감(잠근 행). E 가 처음 생기면 최초 정상 인수 시각을 남긴다(이후 바꾸지 않음).
   *
   * @return 반영 후 row_version
   */
  public long applyLot(
      String mfcId, String sku, String lotId, int dh, int dq, int de, int dr, Instant at) {
    Long v =
        jdbc.queryForObject(
            "UPDATE stock_lot_balance SET on_hand = on_hand + ?, quarantined = quarantined + ?,"
                + " sellable = sellable + ?, reserved = reserved + ?,"
                + " first_normal_at = CASE WHEN ? > 0 THEN COALESCE(first_normal_at, ?) ELSE"
                + " first_normal_at END,"
                + " row_version = row_version + 1, updated_at = ?"
                + " WHERE mfc_id = ? AND sku = ? AND lot_id = ? RETURNING row_version",
            Long.class,
            dh,
            dq,
            de,
            dr,
            de,
            Timestamp.from(at),
            Timestamp.from(at),
            mfcId,
            sku,
            lotId);
    return v == null ? 0 : v;
  }

  // ---------- 단위 행 ----------

  /** 단위 행을 잠그고 수량을 읽는다. 없으면 0. */
  /** 단위 키의 OPEN 분실 조사 보류 합(ADR-0044). */
  public int openHold(String mfcId, Key k) {
    Integer v =
        jdbc.queryForObject(
            "SELECT COALESCE(SUM(qty), 0) FROM stock_hold WHERE state = 'OPEN' AND mfc_id = ?"
                + " AND location_code = ? AND sku = ? AND lot_id = ? AND quality_state = ?"
                + " AND work_state = ?",
            Integer.class,
            mfcId,
            k.locationCode(),
            k.sku(),
            k.lotId(),
            k.quality().name(),
            k.work().name());
    return v == null ? 0 : v;
  }

  /** SKU 의 OPEN 보류: 단위 키 → 수량(피킹 지시 위치 잔량에서 뺀다). */
  public Map<Key, Integer> openHolds(String mfcId, String sku) {
    Map<Key, Integer> out = new LinkedHashMap<>();
    jdbc.query(
        "SELECT location_code, lot_id, quality_state, work_state, SUM(qty) AS qty FROM stock_hold"
            + " WHERE state = 'OPEN' AND mfc_id = ? AND sku = ? GROUP BY 1, 2, 3, 4",
        rs -> {
          out.put(
              new Key(
                  rs.getString("location_code"),
                  sku,
                  rs.getString("lot_id"),
                  com.pmart.mfc.inventory.domain.StockUnit.Quality.valueOf(
                      rs.getString("quality_state")),
                  com.pmart.mfc.inventory.domain.StockUnit.Work.valueOf(
                      rs.getString("work_state"))),
              rs.getInt("qty"));
        },
        mfcId,
        sku);
    return out;
  }

  public void insertHold(
      String holdId, String mfcId, Key k, int qty, String reason, String sourceRef, Instant at) {
    jdbc.update(
        "INSERT INTO stock_hold (hold_id, mfc_id, location_code, sku, lot_id, quality_state,"
            + " work_state, qty, reason, state, source_ref, created_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?,'OPEN',?,?)",
        holdId,
        mfcId,
        k.locationCode(),
        k.sku(),
        k.lotId(),
        k.quality().name(),
        k.work().name(),
        qty,
        reason,
        sourceRef,
        Timestamp.from(at));
  }

  public int lockUnit(String mfcId, Key k) {
    List<Integer> q =
        jdbc.query(
            "SELECT qty FROM stock_unit WHERE mfc_id = ? AND location_code = ? AND sku = ?"
                + " AND lot_id = ? AND quality_state = ? AND work_state = ? FOR UPDATE",
            (rs, i) -> rs.getInt(1),
            mfcId,
            k.locationCode(),
            k.sku(),
            k.lotId(),
            k.quality().name(),
            k.work().name());
    return q.isEmpty() ? 0 : q.get(0);
  }

  /** 단위 행 증감(없으면 만든다). 0 이 된 행은 지우지 않는다(위치 이력 조회). */
  public void applyUnit(String mfcId, Key k, int delta, Instant at) {
    if (delta < 0) {
      // 감소는 잠근 기존 행만(INSERT 후보 행의 음수 수량이 CHECK 에 먼저 걸린다)
      int n =
          jdbc.update(
              "UPDATE stock_unit SET qty = qty + ?, row_version = row_version + 1, updated_at = ?"
                  + " WHERE mfc_id = ? AND location_code = ? AND sku = ? AND lot_id = ?"
                  + " AND quality_state = ? AND work_state = ?",
              delta,
              Timestamp.from(at),
              mfcId,
              k.locationCode(),
              k.sku(),
              k.lotId(),
              k.quality().name(),
              k.work().name());
      if (n != 1) {
        throw new IllegalStateException("감소할 단위 행이 없습니다: " + k);
      }
      return;
    }
    jdbc.update(
        "INSERT INTO stock_unit (mfc_id, location_code, sku, lot_id, quality_state, work_state,"
            + " qty, row_version, updated_at) VALUES (?,?,?,?,?,?,?,1,?)"
            + " ON CONFLICT (mfc_id, location_code, sku, lot_id, quality_state, work_state)"
            + " DO UPDATE SET qty = stock_unit.qty + EXCLUDED.qty,"
            + " row_version = stock_unit.row_version + 1, updated_at = EXCLUDED.updated_at",
        mfcId,
        k.locationCode(),
        k.sku(),
        k.lotId(),
        k.quality().name(),
        k.work().name(),
        delta,
        Timestamp.from(at));
  }

  /** 스냅샷 한 줄: 단위 행 + LOT 소비기한. */
  public record SnapshotRow(UnitRow unit, @Nullable LocalDate expiryDate) {}

  /**
   * 센터 재고 스냅샷(0 초과 단위 행) — SKU·LOT·위치·품질·업무 순, after 키 다음부터 limit 개(키셋 페이지).
   *
   * @param after 앞 페이지 마지막 키 "sku|lot|location|quality|work"(없으면 처음)
   */
  public List<SnapshotRow> snapshot(
      String mfcId,
      @Nullable String sku,
      @Nullable String locationCode,
      @Nullable String after,
      int limit) {
    String[] a = after == null ? null : after.split(java.util.regex.Pattern.quote("|"), -1);
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("mfc", mfcId);
    p.put("sku", sku);
    p.put("loc", locationCode);
    p.put("limit", limit);
    String keyset = "";
    if (a != null && a.length == 5) {
      keyset =
          " AND (u.sku, u.lot_id, u.location_code, u.quality_state, u.work_state)"
              + " > (:a0, :a1, :a2, :a3, :a4)";
      for (int i = 0; i < 5; i++) {
        p.put("a" + i, a[i]);
      }
    }
    return named.query(
        "SELECT u.location_code, u.sku, u.lot_id, u.quality_state, u.work_state, u.qty,"
            + " u.row_version, l.expiry_date FROM stock_unit u JOIN stock_lot l ON l.lot_id = u.lot_id"
            + " WHERE u.mfc_id = :mfc AND u.qty > 0 AND (CAST(:sku AS varchar) IS NULL OR u.sku = :sku)"
            + " AND (CAST(:loc AS varchar) IS NULL OR u.location_code = :loc)"
            + keyset
            + " ORDER BY u.sku, u.lot_id, u.location_code, u.quality_state, u.work_state LIMIT :limit",
        p,
        (rs, i) -> {
          Date d = rs.getDate("expiry_date");
          return new SnapshotRow(
              new UnitRow(
                  new Key(
                      rs.getString("location_code"),
                      rs.getString("sku"),
                      rs.getString("lot_id"),
                      Quality.valueOf(rs.getString("quality_state")),
                      Work.valueOf(rs.getString("work_state"))),
                  rs.getInt("qty"),
                  rs.getLong("row_version")),
              d == null ? null : d.toLocalDate());
        });
  }

  /** 위치들의 판매 단위(NORMAL·STORED, 0 초과) — 실사 장부(IV-11). 위치·SKU·LOT 순. */
  public List<UnitRow> storedUnitsAt(String mfcId, Collection<String> locations) {
    if (locations.isEmpty()) {
      return List.of();
    }
    return named.query(
        "SELECT location_code, sku, lot_id, quality_state, work_state, qty, row_version"
            + " FROM stock_unit WHERE mfc_id = :mfc AND location_code IN (:locs) AND qty > 0"
            + " AND quality_state = 'NORMAL' AND work_state = 'STORED'"
            + " ORDER BY location_code, sku, lot_id",
        Map.of("mfc", mfcId, "locs", locations),
        (rs, i) ->
            new UnitRow(
                new Key(
                    rs.getString("location_code"),
                    rs.getString("sku"),
                    rs.getString("lot_id"),
                    Quality.valueOf(rs.getString("quality_state")),
                    Work.valueOf(rs.getString("work_state"))),
                rs.getInt("qty"),
                rs.getLong("row_version")));
  }

  /** 단위의 열린 조사 보류를 해소(실사 확정, IV-11). 반환 = 해소한 수량. */
  public int resolveHolds(String mfcId, Key k, String ref, Instant at) {
    int qty = openHold(mfcId, k);
    jdbc.update(
        "UPDATE stock_hold SET state = 'RESOLVED', resolved_at = ?, resolved_ref = ?"
            + " WHERE state = 'OPEN' AND mfc_id = ? AND location_code = ? AND sku = ? AND lot_id = ?"
            + " AND quality_state = ? AND work_state = ?",
        Timestamp.from(at),
        ref,
        mfcId,
        k.locationCode(),
        k.sku(),
        k.lotId(),
        k.quality().name(),
        k.work().name());
    return qty;
  }

  /** 센터·SKU 의 0 이 아닌 단위 행(조회). */
  public List<UnitRow> units(String mfcId, String sku) {
    return jdbc.query(
        "SELECT location_code, sku, lot_id, quality_state, work_state, qty, row_version"
            + " FROM stock_unit WHERE mfc_id = ? AND sku = ? AND qty > 0"
            + " ORDER BY lot_id, location_code, quality_state, work_state",
        (rs, i) ->
            new UnitRow(
                new Key(
                    rs.getString("location_code"),
                    rs.getString("sku"),
                    rs.getString("lot_id"),
                    Quality.valueOf(rs.getString("quality_state")),
                    Work.valueOf(rs.getString("work_state"))),
                rs.getInt("qty"),
                rs.getLong("row_version")),
        mfcId,
        sku);
  }

  /** 센터의 정상·적치 단위 행(모든 상품) — 출하 부적격 LOT 회수 목록. */
  public List<UnitRow> storedUnits(String mfcId) {
    return jdbc.query(
        "SELECT location_code, sku, lot_id, quality_state, work_state, qty, row_version"
            + " FROM stock_unit WHERE mfc_id = ? AND qty > 0 AND quality_state = 'NORMAL'"
            + " AND work_state = 'STORED'",
        (rs, i) ->
            new UnitRow(
                new Key(
                    rs.getString("location_code"),
                    rs.getString("sku"),
                    rs.getString("lot_id"),
                    Quality.valueOf(rs.getString("quality_state")),
                    Work.valueOf(rs.getString("work_state"))),
                rs.getInt("qty"),
                rs.getLong("row_version")),
        mfcId);
  }

  /** 한 칸의 0 이 아닌 단위 행(모든 상품·상태) — 재고 이동 칸 판정. */
  public List<UnitRow> unitsAt(String mfcId, String locationCode) {
    return jdbc.query(
        "SELECT location_code, sku, lot_id, quality_state, work_state, qty, row_version"
            + " FROM stock_unit WHERE mfc_id = ? AND location_code = ? AND qty > 0",
        (rs, i) ->
            new UnitRow(
                new Key(
                    rs.getString("location_code"),
                    rs.getString("sku"),
                    rs.getString("lot_id"),
                    Quality.valueOf(rs.getString("quality_state")),
                    Work.valueOf(rs.getString("work_state"))),
                rs.getInt("qty"),
                rs.getLong("row_version")),
        mfcId,
        locationCode);
  }

  /** 이동 보류(reason MOVE, source_ref = moveId)를 qty 만큼 줄인다 — 0 이 되면 RESOLVED. */
  public void consumeMoveHold(String moveId, int qty, Instant at) {
    var rows =
        jdbc.queryForList(
            "SELECT hold_id, qty FROM stock_hold WHERE source_ref = ? AND reason = 'MOVE'"
                + " AND state = 'OPEN' FOR UPDATE",
            moveId);
    int left = qty;
    for (var r : rows) {
      if (left == 0) {
        break;
      }
      String id = (String) r.get("hold_id");
      int q = ((Number) r.get("qty")).intValue();
      int take = Math.min(left, q);
      if (q - take > 0) {
        jdbc.update("UPDATE stock_hold SET qty = ? WHERE hold_id = ?", q - take, id);
      } else {
        jdbc.update(
            "UPDATE stock_hold SET state = 'RESOLVED', resolved_at = ?, resolved_ref = ?"
                + " WHERE hold_id = ?",
            Timestamp.from(at),
            moveId,
            id);
      }
      left -= take;
    }
  }

  // ---------- 확정 단위 ----------

  public void insertCommit(CommitRow c) {
    jdbc.update(
        "INSERT INTO stock_commit (commit_id, commit_type, mfc_id, request_id, source_system,"
            + " source_ref, applied_qty, occurred_at) VALUES (?,?,?,?,?,?,?,?)",
        c.commitId(),
        c.commitType(),
        c.mfcId(),
        c.requestId(),
        c.sourceSystem(),
        c.sourceRef(),
        c.appliedQty(),
        Timestamp.from(c.occurredAt()));
  }

  public void insertEntry(String commitId, String mfcId, EntryRow e) {
    jdbc.update(
        "INSERT INTO stock_ledger_entry (commit_id, mfc_id, sku, lot_id, location_code,"
            + " quality_state, work_state, symbol, delta, balance_version)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?)",
        commitId,
        mfcId,
        e.sku(),
        e.lotId(),
        e.locationCode(),
        e.qualityState(),
        e.workState(),
        e.symbol(),
        e.delta(),
        e.balanceVersion());
  }

  public Optional<CommitRow> commit(String commitId) {
    return jdbc
        .query(
            "SELECT commit_id, commit_type, mfc_id, request_id, source_system, source_ref,"
                + " applied_qty, occurred_at FROM stock_commit WHERE commit_id = ?",
            (rs, i) -> commitRow(rs),
            commitId)
        .stream()
        .findFirst();
  }

  /** 요청(X-Source-System, Idempotency-Key)이 만든 확정 단위. 요청 하나는 확정 단위를 하나만 만든다. */
  public Optional<CommitRow> commitByRequest(String sourceSystem, String requestId) {
    return jdbc
        .query(
            "SELECT commit_id, commit_type, mfc_id, request_id, source_system, source_ref,"
                + " applied_qty, occurred_at FROM stock_commit"
                + " WHERE source_system = ? AND request_id = ? ORDER BY occurred_at LIMIT 1",
            (rs, i) -> commitRow(rs),
            sourceSystem,
            requestId)
        .stream()
        .findFirst();
  }

  public List<EntryRow> entries(String commitId) {
    return jdbc.query(
        "SELECT sku, lot_id, location_code, quality_state, work_state, symbol, delta,"
            + " balance_version FROM stock_ledger_entry WHERE commit_id = ? ORDER BY entry_id",
        (rs, i) ->
            new EntryRow(
                rs.getString("sku"),
                rs.getString("lot_id"),
                rs.getString("location_code"),
                rs.getString("quality_state"),
                rs.getString("work_state"),
                rs.getString("symbol"),
                rs.getInt("delta"),
                rs.getLong("balance_version")),
        commitId);
  }

  // ---------- 수령 중복 방지 ----------

  /**
   * receiptId 를 선점한다. 다른 트랜잭션이 같은 receiptId 를 먼저 넣고 있으면 그쪽이 끝날 때까지 기다린다(유일 키).
   *
   * @return 선점했으면 true. 이미 있으면 false(호출자가 {@link #receipt} 로 내용 비교)
   */
  public boolean claimReceipt(
      String receiptId,
      String mfcId,
      String contentHash,
      String commitId,
      int appliedQty,
      Instant at) {
    return jdbc.update(
            "INSERT INTO stock_receipt (receipt_id, mfc_id, content_hash, commit_id, applied_qty,"
                + " posted_at) VALUES (?,?,?,?,?,?) ON CONFLICT (receipt_id) DO NOTHING",
            receiptId,
            mfcId,
            contentHash,
            commitId,
            appliedQty,
            Timestamp.from(at))
        == 1;
  }

  public Optional<ReceiptRow> receipt(String receiptId) {
    return jdbc
        .query(
            "SELECT receipt_id, content_hash, commit_id, applied_qty FROM stock_receipt"
                + " WHERE receipt_id = ?",
            (rs, i) ->
                new ReceiptRow(
                    rs.getString("receipt_id"),
                    rs.getString("content_hash"),
                    rs.getString("commit_id"),
                    rs.getInt("applied_qty")),
            receiptId)
        .stream()
        .findFirst();
  }

  public void insertReceiptLine(String receiptId, ReceiptLineRow l) {
    jdbc.update(
        "INSERT INTO stock_receipt_line (receipt_id, line_no, sku, lot_id, location_code,"
            + " normal_qty, quarantined_qty, pending_qty) VALUES (?,?,?,?,?,?,?,?)",
        receiptId,
        l.lineNo(),
        l.sku(),
        l.lotId(),
        l.locationCode(),
        l.normalQty(),
        l.quarantinedQty(),
        l.pendingQty());
  }

  public List<ReceiptLineRow> receiptLines(String receiptId) {
    return jdbc.query(
        "SELECT line_no, sku, lot_id, location_code, normal_qty, quarantined_qty, pending_qty"
            + " FROM stock_receipt_line WHERE receipt_id = ? ORDER BY line_no",
        (rs, i) ->
            new ReceiptLineRow(
                rs.getInt("line_no"),
                rs.getString("sku"),
                rs.getString("lot_id"),
                rs.getString("location_code"),
                rs.getInt("normal_qty"),
                rs.getInt("quarantined_qty"),
                rs.getInt("pending_qty")),
        receiptId);
  }

  /** 작업 실행 선점(execution_id 유일). */
  public boolean claimExecution(
      String executionId,
      String mfcId,
      String type,
      String contentHash,
      String commitId,
      int appliedQty,
      Instant at) {
    return jdbc.update(
            "INSERT INTO stock_execution (execution_id, mfc_id, execution_type, content_hash,"
                + " commit_id, applied_qty, posted_at) VALUES (?,?,?,?,?,?,?)"
                + " ON CONFLICT (execution_id) DO NOTHING",
            executionId,
            mfcId,
            type,
            contentHash,
            commitId,
            appliedQty,
            Timestamp.from(at))
        == 1;
  }

  public Optional<ReceiptRow> execution(String executionId) {
    return jdbc
        .query(
            "SELECT execution_id, content_hash, commit_id, applied_qty FROM stock_execution"
                + " WHERE execution_id = ?",
            (rs, i) ->
                new ReceiptRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4)),
            executionId)
        .stream()
        .findFirst();
  }

  // ---------- 요청 기록(common-messaging request_record, 같은 서비스 DB) ----------

  /** 이 행위자(토큰 주체)가 보낸 요청의 X-Source-System. 없으면 empty. */
  public Optional<String> requestSource(String requestId, String actor) {
    return jdbc
        .query(
            "SELECT source_system FROM request_record WHERE request_id = ? AND actor = ?"
                + " ORDER BY created_at LIMIT 1",
            (rs, i) -> rs.getString(1),
            requestId,
            actor)
        .stream()
        .findFirst();
  }

  /** 요청 기록 시각. */
  public Optional<Instant> requestRecordedAt(String sourceSystem, String requestId) {
    List<Instant> out = new ArrayList<>();
    jdbc.query(
        "SELECT created_at FROM request_record WHERE source_system = ? AND request_id = ?",
        rs -> {
          out.add(rs.getTimestamp(1).toInstant());
        },
        sourceSystem,
        requestId);
    return out.stream().findFirst();
  }

  private static Balance balance(ResultSet rs) throws SQLException {
    return new Balance(
        rs.getString("sku"),
        rs.getInt("on_hand"),
        rs.getInt("quarantined"),
        rs.getInt("sellable"),
        rs.getInt("reserved"),
        rs.getLong("row_version"));
  }

  private static LotBalance lotBalance(ResultSet rs) throws SQLException {
    Date d = rs.getDate("expiry_date");
    Date m = rs.getDate("manufacture_date");
    Timestamp f = rs.getTimestamp("first_normal_at");
    return new LotBalance(
        rs.getString("sku"),
        rs.getString("lot_id"),
        rs.getString("lot_no"),
        d == null ? null : d.toLocalDate(),
        m == null ? null : m.toLocalDate(),
        rs.getString("tracking"),
        rs.getInt("on_hand"),
        rs.getInt("quarantined"),
        rs.getInt("sellable"),
        rs.getInt("reserved"),
        f == null ? null : f.toInstant(),
        rs.getLong("row_version"));
  }

  private static CommitRow commitRow(ResultSet rs) throws SQLException {
    return new CommitRow(
        rs.getString("commit_id"),
        rs.getString("commit_type"),
        rs.getString("mfc_id"),
        rs.getString("request_id"),
        rs.getString("source_system"),
        rs.getString("source_ref"),
        rs.getInt("applied_qty"),
        rs.getTimestamp("occurred_at").toInstant());
  }
}
