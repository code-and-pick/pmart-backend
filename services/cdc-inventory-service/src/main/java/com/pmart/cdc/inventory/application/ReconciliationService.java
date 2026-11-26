package com.pmart.cdc.inventory.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 일일 대사(0.16.0, WMS CDC-F-034 — 사용자 2026-11-26 "조회할 때 계산", 대조 "예약·운송 중"). 부를 때 원장으로 계산한다 — 저장·배치·경보
 * 없음, 원장을 고치지 않는다.
 */
@Service
public class ReconciliationService {

  static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

  public record SkuLine(
      String sku,
      int opening,
      int initialBalance,
      int adjustmentIn,
      int departed,
      int disposed,
      int adjustmentOut,
      int movedNet,
      int closing,
      int computedClosing) {
    public int discrepancy() {
      return closing - computedClosing;
    }
  }

  public record ReservationCheck(
      String locationCode, String sku, String lot, int reserved, int allocationRemaining) {}

  public record TransitCheck(String sku, int ledgerTransit, int unreconciled) {}

  public record OpenTransit(
      String trfId,
      String departureId,
      @Nullable String mfcId,
      String state,
      Instant departedAt,
      int unreconciledQty) {}

  public record Report(
      String cdcId,
      LocalDate date,
      String state,
      Instant asOf,
      List<SkuLine> items,
      List<ReservationCheck> reservationChecks,
      List<TransitCheck> transitChecks,
      List<OpenTransit> openTransits) {}

  public record TraceStock(
      String cdcId, String locationCode, String qualityState, int onHand, int reserved) {}

  public record TraceTransit(
      String cdcId,
      String trfId,
      String departureId,
      @Nullable String mfcId,
      Instant departedAt,
      String state,
      int q,
      int received,
      int loss,
      int unreconciled) {}

  public record Trace(
      String sku, String lot, Instant asOf, List<TraceStock> stock, List<TraceTransit> transits) {}

  private final JdbcTemplate jdbc;
  private final Clock clock;

  public ReconciliationService(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  public Report daily(String cdcId, LocalDate date, @Nullable String sku) {
    Instant now = clock.instant();
    Instant start = date.atStartOfDay(SEOUL).toInstant();
    Instant end = date.plusDays(1).atStartOfDay(SEOUL).toInstant();
    boolean inProgress = now.isBefore(end);
    String skuCond = sku == null ? "" : " AND d.sku = ?";
    Object[] base = sku == null ? new Object[] {cdcId} : new Object[] {cdcId, sku};

    Map<String, Integer> before = sums(cdcId, sku, "c.applied_at < ?", start);
    Map<String, Integer> after = sums(cdcId, sku, "c.applied_at >= ?", end);
    Map<String, Integer> actual = new TreeMap<>();
    jdbc.query(
        "SELECT sku, SUM(on_hand) FROM stock_balance d WHERE cdc_id = ?"
            + skuCond
            + " GROUP BY sku",
        rs -> {
          actual.put(rs.getString(1), rs.getInt(2));
        },
        base);
    // 그날 증감 — SKU·원결과 종류·부호별
    Map<String, int[]> day = new TreeMap<>(); // [initial, adjIn, departed, disposed, adjOut, moved]
    List<Object> args = new ArrayList<>(List.of(cdcId, Timestamp.from(start), Timestamp.from(end)));
    if (sku != null) {
      args.add(sku);
    }
    jdbc.query(
        "SELECT d.sku, c.posting_kind, SUM(CASE WHEN d.delta > 0 THEN d.delta ELSE 0 END),"
            + " SUM(CASE WHEN d.delta < 0 THEN -d.delta ELSE 0 END) FROM inventory_commit c"
            + " JOIN inventory_commit_delta d ON d.commit_id = c.commit_id WHERE c.cdc_id = ?"
            + " AND d.bucket = 'ON_HAND' AND c.applied_at >= ? AND c.applied_at < ?"
            + skuCond
            + " GROUP BY d.sku, c.posting_kind",
        rs -> {
          int[] v = day.computeIfAbsent(rs.getString(1), k -> new int[6]);
          String kind = rs.getString(2);
          int plus = rs.getInt(3);
          int minus = rs.getInt(4);
          switch (kind) {
            case "INITIAL_BALANCE" -> v[0] += plus - minus;
            case "TOUT" -> v[2] += minus - plus;
            case "DISPOSAL" -> v[3] += minus - plus;
            case "CNT", "ADJ" -> {
              v[1] += plus;
              v[4] += minus;
            }
            default -> v[5] += plus - minus; // MOVE·PICK_MOVE·품질 전환 — 센터 합계 0
          }
        },
        args.toArray());
    TreeSet<String> skus = new TreeSet<>();
    skus.addAll(before.keySet());
    skus.addAll(after.keySet());
    skus.addAll(actual.keySet());
    skus.addAll(day.keySet());
    List<SkuLine> items = new ArrayList<>();
    for (String s : skus) {
      int[] v = day.getOrDefault(s, new int[6]);
      int opening = before.getOrDefault(s, 0);
      int computed = opening + v[0] + v[1] - v[2] - v[3] - v[4] + v[5];
      int closing = actual.getOrDefault(s, 0) - after.getOrDefault(s, 0);
      items.add(new SkuLine(s, opening, v[0], v[1], v[2], v[3], v[4], v[5], closing, computed));
    }

    // 예약 대조(지금 시점): 장부 예약 ≠ 살아 있는 할당의 남은 예약
    Map<List<String>, int[]> res = new TreeMap<>(ReconciliationService::compareKeys);
    jdbc.query(
        "SELECT location_code, sku, lot, SUM(reserved) FROM stock_balance d WHERE cdc_id = ?"
            + skuCond
            + " GROUP BY location_code, sku, lot HAVING SUM(reserved) <> 0",
        rs -> {
          res.computeIfAbsent(
                      List.of(rs.getString(1), rs.getString(2), rs.getString(3)), k -> new int[2])[
                  0] +=
              rs.getInt(4);
        },
        base);
    jdbc.query(
        "SELECT d.location_code, d.sku, d.lot, SUM(d.allocated_qty + d.moved_in_qty - d.moved_out_qty"
            + " - d.released_qty - d.departed_qty) FROM allocation_lot d JOIN allocation a"
            + " ON a.allocation_id = d.allocation_id WHERE a.cdc_id = ? AND a.state IN ('ALLOCATED', 'PARTIAL')"
            + skuCond
            + " GROUP BY d.location_code, d.sku, d.lot",
        rs -> {
          res.computeIfAbsent(
                      List.of(rs.getString(1), rs.getString(2), rs.getString(3)), k -> new int[2])[
                  1] +=
              rs.getInt(4);
        },
        base);
    List<ReservationCheck> resChecks = new ArrayList<>();
    res.forEach(
        (k, v) -> {
          if (v[0] != v[1]) {
            resChecks.add(new ReservationCheck(k.get(0), k.get(1), k.get(2), v[0], v[1]));
          }
        });

    // 운송 중 대조(지금 시점): 원장 TRANSIT 합 ≠ 출발 줄 미정산 합
    Map<String, int[]> tr = new TreeMap<>();
    jdbc.query(
        "SELECT d.sku, SUM(d.delta) FROM inventory_commit c JOIN inventory_commit_delta d"
            + " ON d.commit_id = c.commit_id WHERE c.cdc_id = ? AND d.bucket = 'TRANSIT'"
            + skuCond
            + " GROUP BY d.sku",
        rs -> {
          tr.computeIfAbsent(rs.getString(1), k -> new int[2])[0] += rs.getInt(2);
        },
        base);
    jdbc.query(
        "SELECT d.sku, SUM(d.q - d.normal_qty - d.quarantined_qty - d.pending_qty - d.loss_qty)"
            + " FROM transit_line d JOIN transit t ON t.trf_id = d.trf_id WHERE t.cdc_id = ?"
            + skuCond
            + " GROUP BY d.sku",
        rs -> {
          tr.computeIfAbsent(rs.getString(1), k -> new int[2])[1] += rs.getInt(2);
        },
        base);
    List<TransitCheck> trChecks = new ArrayList<>();
    tr.forEach(
        (k, v) -> {
          if (v[0] != v[1]) {
            trChecks.add(new TransitCheck(k, v[0], v[1]));
          }
        });
    List<OpenTransit> open =
        jdbc.query(
            "SELECT t.trf_id, t.departure_id, t.to_mfc_id, t.state, t.departed_at,"
                + " SUM(d.q - d.normal_qty - d.quarantined_qty - d.pending_qty - d.loss_qty)"
                + " FROM transit t JOIN transit_line d ON d.trf_id = t.trf_id WHERE t.cdc_id = ?"
                + " AND t.state <> 'TRANSIT_RECONCILED'"
                + skuCond
                + " GROUP BY t.trf_id, t.departure_id, t.to_mfc_id, t.state, t.departed_at"
                + " ORDER BY t.departed_at, t.trf_id LIMIT 200",
            (rs, i) ->
                new OpenTransit(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getTimestamp(5).toInstant(),
                    rs.getInt(6)),
            base);

    boolean differs =
        items.stream().anyMatch(l -> l.discrepancy() != 0)
            || !resChecks.isEmpty()
            || !trChecks.isEmpty();
    String state = differs ? "DISCREPANCY" : inProgress ? "IN_PROGRESS" : "BALANCED";
    return new Report(cdcId, date, state, now, items, resChecks, trChecks, open);
  }

  /** LOT 추적(0.18.0, 리콜 정방향 추적) — 모든 CDC 의 현물(0 뺌)·출발. 읽기만. */
  public Trace lotTrace(String sku, String lot) {
    List<TraceStock> stock =
        jdbc.query(
            "SELECT cdc_id, location_code, quality_state, on_hand, reserved FROM stock_balance"
                + " WHERE sku = ? AND lot = ? AND on_hand > 0 ORDER BY cdc_id, location_code, quality_state",
            (rs, i) ->
                new TraceStock(
                    rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5)),
            sku,
            lot);
    List<TraceTransit> transits =
        jdbc.query(
            "SELECT t.cdc_id, t.trf_id, t.departure_id, t.to_mfc_id, t.departed_at, t.state, SUM(d.q),"
                + " SUM(d.normal_qty + d.quarantined_qty + d.pending_qty), SUM(d.loss_qty),"
                + " SUM(d.q - d.normal_qty - d.quarantined_qty - d.pending_qty - d.loss_qty)"
                + " FROM transit_line d JOIN transit t ON t.trf_id = d.trf_id WHERE d.sku = ? AND d.lot = ?"
                + " GROUP BY t.cdc_id, t.trf_id, t.departure_id, t.to_mfc_id, t.departed_at, t.state"
                + " ORDER BY t.departed_at, t.trf_id",
            (rs, i) ->
                new TraceTransit(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getTimestamp(5).toInstant(),
                    rs.getString(6),
                    rs.getInt(7),
                    rs.getInt(8),
                    rs.getInt(9),
                    rs.getInt(10)),
            sku,
            lot);
    return new Trace(sku, lot, clock.instant(), stock, transits);
  }

  private Map<String, Integer> sums(String cdcId, @Nullable String sku, String when, Instant at) {
    Map<String, Integer> out = new TreeMap<>();
    List<Object> args = new ArrayList<>(List.of(cdcId, Timestamp.from(at)));
    if (sku != null) {
      args.add(sku);
    }
    jdbc.query(
        "SELECT d.sku, SUM(d.delta) FROM inventory_commit c JOIN inventory_commit_delta d"
            + " ON d.commit_id = c.commit_id WHERE c.cdc_id = ? AND d.bucket = 'ON_HAND' AND "
            + when
            + (sku == null ? "" : " AND d.sku = ?")
            + " GROUP BY d.sku",
        rs -> {
          out.put(rs.getString(1), rs.getInt(2));
        },
        args.toArray());
    return out;
  }

  private static int compareKeys(List<String> a, List<String> b) {
    for (int i = 0; i < a.size(); i++) {
      int c = a.get(i).compareTo(b.get(i));
      if (c != 0) {
        return c;
      }
    }
    return 0;
  }
}
