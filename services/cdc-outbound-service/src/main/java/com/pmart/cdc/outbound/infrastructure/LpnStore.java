package com.pmart.cdc.outbound.infrastructure;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** LPN(분류·검품·상차)·작업 결과·출발 검증·매니페스트 저장(JDBC). */
@Repository
public class LpnStore {

  public record Content(String sku, String lot, int qty) {}

  public record LpnRow(
      String lpn,
      String trfId,
      String waveId,
      String unitType,
      @Nullable String parentLpn,
      String sealState,
      @Nullable String sealNo,
      @Nullable String temperatureZone,
      @Nullable Integer weightG,
      @Nullable String inspection,
      boolean loaded,
      @Nullable String tripId,
      @Nullable String compartment,
      @Nullable String loadSealNo,
      @Nullable String departureId) {}

  public record DepartureRow(
      String departureId,
      String tripId,
      String vehicleId,
      String source,
      Instant departedAt,
      @Nullable String evidenceRef,
      String bodyHash,
      String overallResult,
      Instant validatedAt) {}

  public record DepartureTransferRow(
      String trfId,
      int lineNo,
      @Nullable String mfcId,
      String result,
      int departedQty,
      @Nullable String postingStatus,
      @Nullable String commitId,
      @Nullable String rejectReason,
      @Nullable String manifestId,
      @Nullable String residualStatus,
      @Nullable String residualReason) {}

  public record MismatchRow(
      String kind, @Nullable String lpn, @Nullable String trfId, String detail) {}

  public record ManifestLine(
      String lpn, String sku, String lot, @Nullable LocalDate expiresAt, int qty) {}

  private static final String COLS =
      "lpn, trf_id, wave_id, unit_type, parent_lpn, seal_state, seal_no, temperature_zone, weight_g,"
          + " inspection, loaded, trip_id, compartment, load_seal_no, departure_id";

  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate named;

  public LpnStore(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
    this.jdbc = jdbc;
    this.named = named;
  }

  // ---------- LPN ----------

  public void insert(LpnRow l, String sortedBy, Instant at, List<Content> contents) {
    jdbc.update(
        "INSERT INTO lpn (lpn, trf_id, wave_id, unit_type, parent_lpn, seal_state, seal_no,"
            + " temperature_zone, weight_g, sorted_by, sorted_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
        l.lpn(),
        l.trfId(),
        l.waveId(),
        l.unitType(),
        l.parentLpn(),
        l.sealState(),
        l.sealNo(),
        l.temperatureZone(),
        l.weightG(),
        sortedBy,
        Timestamp.from(at));
    for (Content c : contents) {
      jdbc.update(
          "INSERT INTO lpn_content (lpn, sku, lot, qty) VALUES (?,?,?,?)",
          l.lpn(),
          c.sku(),
          c.lot(),
          c.qty());
    }
  }

  public Map<String, LpnRow> find(Collection<String> lpns) {
    Map<String, LpnRow> out = new HashMap<>();
    if (lpns.isEmpty()) {
      return out;
    }
    named.query(
        "SELECT " + COLS + " FROM lpn WHERE lpn IN (:lpns)",
        Map.of("lpns", lpns),
        rs -> {
          LpnRow r = row(rs);
          out.put(r.lpn(), r);
        });
    return out;
  }

  /** TRF 의 LPN 전부(잠금 — 분류·검품·상차·출발 대조를 줄 세움). */
  public List<LpnRow> lockOfTrf(String trfId) {
    return jdbc.query(
        "SELECT " + COLS + " FROM lpn WHERE trf_id = ? ORDER BY lpn FOR UPDATE",
        (rs, i) -> row(rs),
        trfId);
  }

  public List<LpnRow> children(String parentLpn) {
    return jdbc.query(
        "SELECT " + COLS + " FROM lpn WHERE parent_lpn = ? ORDER BY lpn",
        (rs, i) -> row(rs),
        parentLpn);
  }

  public List<Content> contents(String lpn) {
    return jdbc.query(
        "SELECT sku, lot, qty FROM lpn_content WHERE lpn = ? ORDER BY sku, lot",
        (rs, i) -> new Content(rs.getString("sku"), rs.getString("lot"), rs.getInt("qty")),
        lpn);
  }

  /** TRF 의 BOX 수량 합(SKU·LOT) — 조건(검품 합격·상차·출발)별. */
  public Map<String, Integer> boxQty(String trfId, String condition) {
    Map<String, Integer> out = new HashMap<>();
    jdbc.query(
        "SELECT c.sku, c.lot, SUM(c.qty) AS q FROM lpn l JOIN lpn_content c ON c.lpn = l.lpn"
            + " WHERE l.trf_id = ? AND l.unit_type = 'BOX'"
            + switch (condition) {
              case "PASSED" -> " AND l.inspection = 'PASSED'";
              case "LOADED" -> " AND l.loaded";
              case "DEPARTED" -> " AND l.departure_id IS NOT NULL";
              default -> "";
            }
            + " GROUP BY c.sku, c.lot",
        rs -> {
          out.put(rs.getString("sku") + "|" + rs.getString("lot"), rs.getInt("q"));
        },
        trfId);
    return out;
  }

  public void inspect(String lpn, String result, @Nullable String failReason, Instant at) {
    jdbc.update(
        "UPDATE lpn SET inspection = ?, fail_reason = ?, inspected_at = ? WHERE lpn = ?",
        result,
        failReason,
        Timestamp.from(at),
        lpn);
  }

  public void load(
      String lpn,
      String tripId,
      String vehicleId,
      @Nullable String dockCode,
      @Nullable String compartment,
      @Nullable String sealNo,
      Instant at) {
    jdbc.update(
        "UPDATE lpn SET loaded = true, trip_id = ?, vehicle_id = ?, dock_code = ?, compartment = ?,"
            + " load_seal_no = ?, loaded_at = ? WHERE lpn = ?",
        tripId,
        vehicleId,
        dockCode,
        compartment,
        sealNo,
        Timestamp.from(at),
        lpn);
  }

  public void unload(String lpn) {
    jdbc.update(
        "UPDATE lpn SET loaded = false, trip_id = NULL, vehicle_id = NULL, dock_code = NULL,"
            + " compartment = NULL, load_seal_no = NULL, loaded_at = NULL WHERE lpn = ?",
        lpn);
  }

  public void markDeparted(Collection<String> lpns, String departureId) {
    if (lpns.isEmpty()) {
      return;
    }
    named.update(
        "UPDATE lpn SET departure_id = :dep WHERE lpn IN (:lpns)",
        Map.of("dep", departureId, "lpns", lpns));
  }

  // ---------- work result ----------

  public void insertWorkResult(String id, String kind, String trfId, Instant at) {
    jdbc.update(
        "INSERT INTO work_result (work_result_id, kind, trf_id, status, recorded_at) VALUES (?,?,?,'APPLIED',?)",
        id,
        kind,
        trfId,
        Timestamp.from(at));
  }

  public Optional<Map<String, Object>> workResult(String id) {
    return jdbc.queryForList("SELECT * FROM work_result WHERE work_result_id = ?", id).stream()
        .findFirst();
  }

  // ---------- departure ----------

  public Optional<DepartureRow> departure(String departureId, boolean lock) {
    return jdbc
        .query(
            "SELECT departure_id, trip_id, vehicle_id, source, departed_at, evidence_ref, body_hash,"
                + " overall_result, validated_at FROM departure WHERE departure_id = ?"
                + (lock ? " FOR UPDATE" : ""),
            (rs, i) ->
                new DepartureRow(
                    rs.getString("departure_id"),
                    rs.getString("trip_id"),
                    rs.getString("vehicle_id"),
                    rs.getString("source"),
                    rs.getTimestamp("departed_at").toInstant(),
                    rs.getString("evidence_ref"),
                    rs.getString("body_hash"),
                    rs.getString("overall_result"),
                    rs.getTimestamp("validated_at").toInstant()),
            departureId)
        .stream()
        .findFirst();
  }

  public void insertDeparture(
      DepartureRow d, List<DepartureTransferRow> transfers, List<MismatchRow> mismatches) {
    jdbc.update(
        "INSERT INTO departure (departure_id, trip_id, vehicle_id, source, departed_at, evidence_ref,"
            + " body_hash, overall_result, validated_at) VALUES (?,?,?,?,?,?,?,?,?)",
        d.departureId(),
        d.tripId(),
        d.vehicleId(),
        d.source(),
        Timestamp.from(d.departedAt()),
        d.evidenceRef(),
        d.bodyHash(),
        d.overallResult(),
        Timestamp.from(d.validatedAt()));
    for (DepartureTransferRow t : transfers) {
      jdbc.update(
          "INSERT INTO departure_transfer (departure_id, trf_id, line_no, mfc_id, result, departed_qty,"
              + " posting_status) VALUES (?,?,?,?,?,?,?)",
          d.departureId(),
          t.trfId(),
          t.lineNo(),
          t.mfcId(),
          t.result(),
          t.departedQty(),
          t.postingStatus());
    }
    for (int i = 0; i < mismatches.size(); i++) {
      MismatchRow m = mismatches.get(i);
      jdbc.update(
          "INSERT INTO departure_mismatch (departure_id, seq, kind, lpn, trf_id, detail) VALUES (?,?,?,?,?,?)",
          d.departureId(),
          i,
          m.kind(),
          m.lpn(),
          m.trfId(),
          m.detail());
    }
  }

  public List<DepartureTransferRow> departureTransfers(String departureId) {
    return jdbc.query(
        "SELECT trf_id, line_no, mfc_id, result, departed_qty, posting_status, commit_id, reject_reason,"
            + " manifest_id, residual_status, residual_reason FROM departure_transfer WHERE departure_id = ? ORDER BY line_no",
        (rs, i) -> departureTransfer(rs),
        departureId);
  }

  public Optional<DepartureTransferRow> departureOfTrf(String trfId) {
    return jdbc
        .query(
            "SELECT trf_id, line_no, mfc_id, result, departed_qty, posting_status, commit_id, reject_reason,"
                + " manifest_id, residual_status, residual_reason FROM departure_transfer WHERE trf_id = ?",
            (rs, i) -> departureTransfer(rs),
            trfId)
        .stream()
        .findFirst();
  }

  public Optional<String> departureIdOfTrf(String trfId) {
    return jdbc
        .queryForList(
            "SELECT departure_id FROM departure_transfer WHERE trf_id = ?", String.class, trfId)
        .stream()
        .findFirst();
  }

  public List<MismatchRow> mismatches(String departureId) {
    return jdbc.query(
        "SELECT kind, lpn, trf_id, detail FROM departure_mismatch WHERE departure_id = ? ORDER BY seq",
        (rs, i) ->
            new MismatchRow(
                rs.getString("kind"),
                rs.getString("lpn"),
                rs.getString("trf_id"),
                rs.getString("detail")),
        departureId);
  }

  public void setPosting(
      String departureId,
      String trfId,
      String status,
      @Nullable String commitId,
      @Nullable String rejectReason,
      @Nullable String manifestId) {
    jdbc.update(
        "UPDATE departure_transfer SET posting_status = ?, commit_id = COALESCE(?, commit_id),"
            + " reject_reason = ?, manifest_id = COALESCE(?, manifest_id) WHERE departure_id = ? AND trf_id = ?",
        status,
        commitId,
        rejectReason,
        manifestId,
        departureId,
        trfId);
  }

  public void insertManifest(String manifestId, String trfId, List<ManifestLine> lines) {
    for (int i = 0; i < lines.size(); i++) {
      ManifestLine l = lines.get(i);
      jdbc.update(
          "INSERT INTO manifest_line (manifest_id, seq, trf_id, lpn, sku, lot, expires_at, qty) VALUES (?,?,?,?,?,?,?,?)",
          manifestId,
          i,
          trfId,
          l.lpn(),
          l.sku(),
          l.lot(),
          l.expiresAt() == null ? null : Date.valueOf(l.expiresAt()),
          l.qty());
    }
  }

  public List<ManifestLine> manifestLines(String manifestId) {
    return jdbc.query(
        "SELECT lpn, sku, lot, expires_at, qty FROM manifest_line WHERE manifest_id = ? ORDER BY seq",
        (rs, i) -> {
          Date d = rs.getDate("expires_at");
          return new ManifestLine(
              rs.getString("lpn"),
              rs.getString("sku"),
              rs.getString("lot"),
              d == null ? null : d.toLocalDate(),
              rs.getInt("qty"));
        },
        manifestId);
  }

  public List<LpnRow> departedOfTrf(String trfId, String departureId) {
    return jdbc.query(
        "SELECT " + COLS + " FROM lpn WHERE trf_id = ? AND departure_id = ? ORDER BY lpn",
        (rs, i) -> row(rs),
        trfId,
        departureId);
  }

  public List<LpnRow> parentsOf(Collection<String> lpns) {
    List<String> ids = new ArrayList<>(lpns);
    if (ids.isEmpty()) {
      return List.of();
    }
    List<LpnRow> out = new ArrayList<>();
    named.query(
        "SELECT "
            + COLS
            + " FROM lpn WHERE lpn IN (SELECT DISTINCT parent_lpn FROM lpn WHERE lpn IN (:ids))",
        Map.of("ids", ids),
        rs -> {
          out.add(row(rs));
        });
    return out;
  }

  private static DepartureTransferRow departureTransfer(ResultSet rs) throws SQLException {
    return new DepartureTransferRow(
        rs.getString("trf_id"),
        rs.getInt("line_no"),
        rs.getString("mfc_id"),
        rs.getString("result"),
        rs.getInt("departed_qty"),
        rs.getString("posting_status"),
        rs.getString("commit_id"),
        rs.getString("reject_reason"),
        rs.getString("manifest_id"),
        rs.getString("residual_status"),
        rs.getString("residual_reason"));
  }

  public void setResidual(
      String departureId, String trfId, String status, @Nullable String reason) {
    jdbc.update(
        "UPDATE departure_transfer SET residual_status = ?, residual_reason = ?"
            + " WHERE departure_id = ? AND trf_id = ?",
        status,
        reason,
        departureId,
        trfId);
  }

  private static LpnRow row(ResultSet rs) throws SQLException {
    return new LpnRow(
        rs.getString("lpn"),
        rs.getString("trf_id"),
        rs.getString("wave_id"),
        rs.getString("unit_type"),
        rs.getString("parent_lpn"),
        rs.getString("seal_state"),
        rs.getString("seal_no"),
        rs.getString("temperature_zone"),
        rs.getObject("weight_g", Integer.class),
        rs.getString("inspection"),
        rs.getBoolean("loaded"),
        rs.getString("trip_id"),
        rs.getString("compartment"),
        rs.getString("load_seal_no"),
        rs.getString("departure_id"));
  }
}
