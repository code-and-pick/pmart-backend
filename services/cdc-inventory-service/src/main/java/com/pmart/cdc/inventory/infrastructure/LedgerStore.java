package com.pmart.cdc.inventory.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 원결과(commit·delta)와 Transit 저장(JDBC). */
@Repository
public class LedgerStore {

  public record Delta(
      String sku, String lot, @Nullable String locationCode, String bucket, int delta) {}

  public record CommitRow(
      String commitId,
      String requestId,
      String postingKind,
      String status,
      String sourceSystem,
      @Nullable String sourceRef,
      String cdcId,
      Instant appliedAt) {}

  public record TransitRow(
      String trfId,
      int trfVersion,
      String departureId,
      String cdcId,
      @Nullable String toMfcId,
      String allocationId,
      String state,
      String departureSource,
      @Nullable String shipmentManifestId,
      String commitId,
      Instant departedAt,
      @Nullable Instant reconciledAt) {}

  public record TransitLineRow(
      String departureLineId,
      String trfId,
      int lineNo,
      String sku,
      String lot,
      String locationCode,
      int q,
      int normalQty,
      int quarantinedQty,
      int pendingQty,
      int lossQty,
      @Nullable String lpns) {}

  private final JdbcTemplate jdbc;

  public LedgerStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  // ---------- commit ----------

  public void insertCommit(CommitRow c, List<Delta> deltas) {
    jdbc.update(
        "INSERT INTO inventory_commit (commit_id, request_id, posting_kind, status, source_system,"
            + " source_ref, cdc_id, applied_at) VALUES (?,?,?,?,?,?,?,?)",
        c.commitId(),
        c.requestId(),
        c.postingKind(),
        c.status(),
        c.sourceSystem(),
        c.sourceRef(),
        c.cdcId(),
        Timestamp.from(c.appliedAt()));
    List<Object[]> rows = new ArrayList<>();
    for (int i = 0; i < deltas.size(); i++) {
      Delta d = deltas.get(i);
      rows.add(
          new Object[] {
            c.commitId(), i, d.sku(), d.lot(), d.locationCode(), d.bucket(), d.delta()
          });
    }
    if (!rows.isEmpty()) {
      jdbc.batchUpdate(
          "INSERT INTO inventory_commit_delta (commit_id, seq, sku, lot, location_code, bucket,"
              + " delta) VALUES (?,?,?,?,?,?,?)",
          rows);
    }
  }

  public Optional<CommitRow> findCommit(String commitId) {
    return jdbc
        .query(
            "SELECT commit_id, request_id, posting_kind, status, source_system, source_ref, cdc_id,"
                + " applied_at FROM inventory_commit WHERE commit_id = ?",
            (rs, i) ->
                new CommitRow(
                    rs.getString("commit_id"),
                    rs.getString("request_id"),
                    rs.getString("posting_kind"),
                    rs.getString("status"),
                    rs.getString("source_system"),
                    rs.getString("source_ref"),
                    rs.getString("cdc_id"),
                    rs.getTimestamp("applied_at").toInstant()),
            commitId)
        .stream()
        .findFirst();
  }

  public List<Delta> deltas(String commitId) {
    return jdbc.query(
        "SELECT sku, lot, location_code, bucket, delta FROM inventory_commit_delta"
            + " WHERE commit_id = ? ORDER BY seq",
        (rs, i) ->
            new Delta(
                rs.getString("sku"),
                rs.getString("lot"),
                rs.getString("location_code"),
                rs.getString("bucket"),
                rs.getInt("delta")),
        commitId);
  }

  // ---------- transit ----------

  public void insertTransit(TransitRow t, List<TransitLineRow> lines) {
    jdbc.update(
        "INSERT INTO transit (trf_id, trf_version, departure_id, cdc_id, to_mfc_id, allocation_id,"
            + " state, departure_source, shipment_manifest_id, commit_id, departed_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?)",
        t.trfId(),
        t.trfVersion(),
        t.departureId(),
        t.cdcId(),
        t.toMfcId(),
        t.allocationId(),
        t.state(),
        t.departureSource(),
        t.shipmentManifestId(),
        t.commitId(),
        Timestamp.from(t.departedAt()));
    for (TransitLineRow l : lines) {
      jdbc.update(
          "INSERT INTO transit_line (departure_line_id, trf_id, line_no, sku, lot, location_code, q,"
              + " lpns) VALUES (?,?,?,?,?,?,?,?)",
          l.departureLineId(),
          l.trfId(),
          l.lineNo(),
          l.sku(),
          l.lot(),
          l.locationCode(),
          l.q(),
          l.lpns());
    }
  }

  public Optional<TransitRow> findTransit(String trfId) {
    return jdbc.query(TRANSIT_SELECT + " WHERE trf_id = ?", (rs, i) -> transit(rs), trfId).stream()
        .findFirst();
  }

  /** 이미 쓰인 출발 줄 ID(어느 TRF 든). */
  public List<String> existingLineIds(List<String> ids) {
    if (ids.isEmpty()) {
      return List.of();
    }
    String in = String.join(",", ids.stream().map(x -> "?").toList());
    return jdbc.queryForList(
        "SELECT departure_line_id FROM transit_line WHERE departure_line_id IN (" + in + ")",
        String.class,
        ids.toArray());
  }

  public List<TransitLineRow> transitLines(String trfId) {
    return jdbc.query(
        "SELECT departure_line_id, trf_id, line_no, sku, lot, location_code, q, normal_qty,"
            + " quarantined_qty, pending_qty, loss_qty, lpns FROM transit_line WHERE trf_id = ? ORDER BY line_no",
        (rs, i) ->
            new TransitLineRow(
                rs.getString("departure_line_id"),
                rs.getString("trf_id"),
                rs.getInt("line_no"),
                rs.getString("sku"),
                rs.getString("lot"),
                rs.getString("location_code"),
                rs.getInt("q"),
                rs.getInt("normal_qty"),
                rs.getInt("quarantined_qty"),
                rs.getInt("pending_qty"),
                rs.getInt("loss_qty"),
                rs.getString("lpns")),
        trfId);
  }

  /** 필터·커서(trfId 오름차순) 목록. limit 은 호출자가 +1 해서 다음 페이지를 판단한다. */
  public List<TransitRow> listTransits(
      @Nullable String trfId,
      @Nullable String departureId,
      @Nullable String state,
      @Nullable String afterTrfId,
      int limit) {
    StringBuilder sql = new StringBuilder(TRANSIT_SELECT + " WHERE 1 = 1");
    List<Object> args = new ArrayList<>();
    if (trfId != null) {
      sql.append(" AND trf_id = ?");
      args.add(trfId);
    }
    if (departureId != null) {
      sql.append(" AND departure_id = ?");
      args.add(departureId);
    }
    if (state != null) {
      sql.append(" AND state = ?");
      args.add(state);
    }
    if (afterTrfId != null) {
      sql.append(" AND trf_id > ?");
      args.add(afterTrfId);
    }
    sql.append(" ORDER BY trf_id LIMIT ?");
    args.add(limit);
    return jdbc.query(sql.toString(), (rs, i) -> transit(rs), args.toArray());
  }

  private static final String TRANSIT_SELECT =
      "SELECT trf_id, trf_version, departure_id, cdc_id, to_mfc_id, allocation_id, state,"
          + " departure_source, shipment_manifest_id, commit_id, departed_at, reconciled_at"
          + " FROM transit";

  private static TransitRow transit(ResultSet rs) throws SQLException {
    Timestamp rec = rs.getTimestamp("reconciled_at");
    return new TransitRow(
        rs.getString("trf_id"),
        rs.getInt("trf_version"),
        rs.getString("departure_id"),
        rs.getString("cdc_id"),
        rs.getString("to_mfc_id"),
        rs.getString("allocation_id"),
        rs.getString("state"),
        rs.getString("departure_source"),
        rs.getString("shipment_manifest_id"),
        rs.getString("commit_id"),
        rs.getTimestamp("departed_at").toInstant(),
        rec == null ? null : rec.toInstant());
  }
}
