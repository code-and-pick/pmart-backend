package com.pmart.cdc.outbound.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 미출발 취소 요청(0.7.0, Flyway V6) 저장과 그에 딸린 TRF 줄·Task 변경(JDBC). */
@Repository
public class CancellationStore {

  public record CancelRow(
      String cancellationId,
      String trfId,
      String sourceSystem,
      String requestKey,
      String bodyHash,
      String scope,
      String reason,
      @Nullable String sourceRef,
      Map<String, Integer> reduceLines,
      Map<String, Integer> releaseLines,
      boolean needsRelease,
      String state,
      int releasedQty,
      @Nullable String releaseCommitId,
      @Nullable String rejectReason,
      String requestedBy,
      Instant createdAt) {}

  private static final String COLS =
      "cancellation_id, trf_id, source_system, request_key, body_hash, scope, reason, source_ref,"
          + " reduce_lines, release_lines, needs_release, state, released_qty, release_commit_id,"
          + " reject_reason, requested_by, created_at";

  private final JdbcTemplate jdbc;

  public CancellationStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(CancelRow r) {
    jdbc.update(
        "INSERT INTO pre_departure_cancel ("
            + COLS
            + ", updated_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        r.cancellationId(),
        r.trfId(),
        r.sourceSystem(),
        r.requestKey(),
        r.bodyHash(),
        r.scope(),
        r.reason(),
        r.sourceRef(),
        encode(r.reduceLines()),
        encode(r.releaseLines()),
        r.needsRelease(),
        r.state(),
        r.releasedQty(),
        r.releaseCommitId(),
        r.rejectReason(),
        r.requestedBy(),
        Timestamp.from(r.createdAt()),
        Timestamp.from(r.createdAt()));
  }

  public Optional<CancelRow> find(String trfId, String sourceSystem, String requestKey) {
    return jdbc
        .query(
            "SELECT "
                + COLS
                + " FROM pre_departure_cancel"
                + " WHERE trf_id = ? AND source_system = ? AND request_key = ?",
            (rs, i) -> row(rs),
            trfId,
            sourceSystem,
            requestKey)
        .stream()
        .findFirst();
  }

  /** 결과 조회 — requestId(멱등 키)로. 같은 키를 여러 호출 시스템이 썼으면 가장 먼저 것. */
  public Optional<CancelRow> findByKey(String trfId, String requestKey) {
    return jdbc
        .query(
            "SELECT "
                + COLS
                + " FROM pre_departure_cancel WHERE trf_id = ? AND request_key = ?"
                + " ORDER BY created_at LIMIT 1",
            (rs, i) -> row(rs),
            trfId,
            requestKey)
        .stream()
        .findFirst();
  }

  /** 끝나지 않은(PENDING·UNKNOWN) 취소가 있는가. */
  public boolean inProgress(String trfId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM pre_departure_cancel WHERE trf_id = ? AND state IN ('PENDING', 'UNKNOWN')",
            Integer.class,
            trfId);
    return n != null && n > 0;
  }

  public void finish(
      String cancellationId,
      String state,
      int releasedQty,
      @Nullable String commitId,
      @Nullable String rejectReason,
      Instant at) {
    jdbc.update(
        "UPDATE pre_departure_cancel SET state = ?, released_qty = ?, release_commit_id = ?,"
            + " reject_reason = ?, updated_at = ? WHERE cancellation_id = ?",
        state,
        releasedQty,
        commitId,
        rejectReason,
        Timestamp.from(at),
        cancellationId);
  }

  /** 아직 착수하지 않은 TRF 작업(CREATED·ASSIGNED·ACCEPTED)을 CANCELLED 로. 바꾼 수. */
  public int cancelOpenTasks(String trfId) {
    return jdbc.update(
        "UPDATE task SET state = 'CANCELLED', row_version = row_version + 1"
            + " WHERE trf_id = ? AND state IN ('CREATED', 'ASSIGNED', 'ACCEPTED')",
        trfId);
  }

  /** TRF 줄 축소 — 할당 −released, 취소 승인량 +reduced. */
  public void reduceLine(String trfId, String sku, int released, int reduced) {
    jdbc.update(
        "UPDATE transfer_line SET allocated_qty = allocated_qty - ?, cancelled_qty = cancelled_qty + ?"
            + " WHERE trf_id = ? AND sku = ?",
        released,
        reduced,
        trfId,
        sku);
  }

  /** TRF 전량 취소 — 할당 0, 취소 승인량 = 승인량. */
  public void cancelAllLines(String trfId) {
    jdbc.update(
        "UPDATE transfer_line SET allocated_qty = 0, cancelled_qty = approved_qty WHERE trf_id = ?",
        trfId);
  }

  /** SKU → 이미 취소한 승인량. */
  public Map<String, Integer> cancelledQty(String trfId) {
    Map<String, Integer> out = new LinkedHashMap<>();
    jdbc.query(
        "SELECT sku, cancelled_qty FROM transfer_line WHERE trf_id = ? ORDER BY line_no",
        rs -> {
          out.put(rs.getString("sku"), rs.getInt("cancelled_qty"));
        },
        trfId);
    return out;
  }

  static @Nullable String encode(Map<String, Integer> m) {
    return m.isEmpty()
        ? null
        : m.entrySet().stream()
            .map(e -> e.getKey() + "=" + e.getValue())
            .collect(Collectors.joining(";"));
  }

  static Map<String, Integer> decode(@Nullable String s) {
    Map<String, Integer> out = new LinkedHashMap<>();
    if (s == null || s.isBlank()) {
      return out;
    }
    for (String part : s.split(";")) {
      String[] kv = part.split("=", 2);
      out.put(kv[0], Integer.parseInt(kv[1]));
    }
    return out;
  }

  private static CancelRow row(ResultSet rs) throws SQLException {
    return new CancelRow(
        rs.getString("cancellation_id"),
        rs.getString("trf_id"),
        rs.getString("source_system"),
        rs.getString("request_key"),
        rs.getString("body_hash"),
        rs.getString("scope"),
        rs.getString("reason"),
        rs.getString("source_ref"),
        decode(rs.getString("reduce_lines")),
        decode(rs.getString("release_lines")),
        rs.getBoolean("needs_release"),
        rs.getString("state"),
        rs.getInt("released_qty"),
        rs.getString("release_commit_id"),
        rs.getString("reject_reason"),
        rs.getString("requested_by"),
        rs.getTimestamp("created_at").toInstant());
  }
}
