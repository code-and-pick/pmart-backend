package com.pmart.cdc.outbound.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 사건 기록(0.25.0 — DC 백오피스 M-5). 새로 쌓지 않고 이미 있는 표(transfer·wave·wave_adjustment·task·task_adjustment·
 * pick_shortage_followup·departure·pre_departure_cancel·recall_hold)의 시각을 모아 최신 순으로 읽는다. 읽기만.
 */
@Repository
public class EventLogStore {

  public record Row(
      Instant at,
      String type,
      String refType,
      String refId,
      @Nullable String refNumber,
      @Nullable String trfId,
      @Nullable String waveId,
      @Nullable String taskId,
      @Nullable String sku,
      @Nullable Integer qty,
      @Nullable String state,
      @Nullable String action,
      @Nullable String actorId,
      @Nullable String reason) {}

  private static final String NULLS3 = "NULL, NULL, NULL::int"; // task_id, sku, qty

  private static final String UNION =
      String.join(
          " UNION ALL ",
          // TRF 인수
          "SELECT t.created_at AS at, 'TRF_RECEIVED' AS type, 'TRF' AS ref_type, t.trf_id AS ref_id,"
              + " t.trf_number AS ref_number, t.trf_id, t.wave_id, NULL AS task_id, NULL AS sku, NULL::int AS qty,"
              + " t.state, NULL AS action, NULL AS actor_id, NULL AS reason"
              + " FROM transfer t WHERE t.cdc_id = :c AND t.created_at >= :s AND t.created_at < :u",
          // Wave 생성·릴리스·조작
          "SELECT w.created_at, 'WAVE_CREATED', 'WAVE', w.wave_id, w.wave_number, NULL, w.wave_id, "
              + NULLS3
              + ", w.state, NULL, w.created_by, NULL FROM wave w"
              + " WHERE w.cdc_id = :c AND w.created_at >= :s AND w.created_at < :u",
          "SELECT w.released_at, 'WAVE_RELEASED', 'WAVE', w.wave_id, w.wave_number, NULL, w.wave_id, "
              + NULLS3
              + ", w.state, NULL, NULL, NULL FROM wave w"
              + " WHERE w.cdc_id = :c AND w.released_at >= :s AND w.released_at < :u",
          "SELECT a.adjusted_at, 'WAVE_ADJUSTED', 'WAVE', a.wave_id, w.wave_number, NULL, a.wave_id, "
              + NULLS3
              + ", a.to_state, a.action, a.actor_id, a.reason FROM wave_adjustment a"
              + " JOIN wave w ON w.wave_id = a.wave_id"
              + " WHERE a.cdc_id = :c AND a.adjusted_at >= :s AND a.adjusted_at < :u",
          // 작업 시작·끝·조작
          "SELECT k.started_at, 'TASK_STARTED', 'TASK', k.task_id, k.task_number, k.trf_id, k.wave_id, k.task_id,"
              + " NULL, NULL::int, k.state, k.kind, k.assignee_id, NULL FROM task k"
              + " WHERE k.cdc_id = :c AND k.started_at >= :s AND k.started_at < :u",
          "SELECT k.completed_at, 'TASK_COMPLETED', 'TASK', k.task_id, k.task_number, k.trf_id, k.wave_id, k.task_id,"
              + " NULL, NULL::int, k.state, k.kind, k.assignee_id, NULL FROM task k"
              + " WHERE k.cdc_id = :c AND k.completed_at >= :s AND k.completed_at < :u",
          "SELECT a.adjusted_at, 'TASK_ADJUSTED', 'TASK', a.task_id, k.task_number, k.trf_id, k.wave_id, a.task_id,"
              + " NULL, NULL::int, a.to_state, a.action, a.actor_id, a.reason FROM task_adjustment a"
              + " JOIN task k ON k.task_id = a.task_id"
              + " WHERE a.cdc_id = :c AND a.adjusted_at >= :s AND a.adjusted_at < :u",
          // 피킹 부족·정리
          "SELECT f.created_at, 'SHORTAGE_REPORTED', 'SHORTAGE', f.followup_id, NULL, f.trf_id, f.wave_id, f.task_id,"
              + " f.sku, f.short_qty, f.state, NULL, NULL, f.reason FROM pick_shortage_followup f"
              + " JOIN transfer t ON t.trf_id = f.trf_id"
              + " WHERE t.cdc_id = :c AND f.created_at >= :s AND f.created_at < :u",
          "SELECT f.updated_at, 'SHORTAGE_SETTLED', 'SHORTAGE', f.followup_id, NULL, f.trf_id, f.wave_id, f.task_id,"
              + " f.sku, f.reallocated_qty, f.state, NULL, NULL, f.reason FROM pick_shortage_followup f"
              + " JOIN transfer t ON t.trf_id = f.trf_id"
              + " WHERE t.cdc_id = :c AND f.state IN ('DONE', 'FAILED') AND f.updated_at >= :s AND f.updated_at < :u",
          // 출발 검증(이 센터 TRF 가 든 것)
          "SELECT d.validated_at, 'DEPARTURE_VALIDATED', 'DEPARTURE', d.departure_id, d.departure_number, NULL, NULL,"
              + " NULL, NULL, NULL::int, d.overall_result, d.source, NULL, NULL FROM departure d"
              + " WHERE d.validated_at >= :s AND d.validated_at < :u AND EXISTS (SELECT 1 FROM departure_transfer x"
              + " JOIN transfer t ON t.trf_id = x.trf_id WHERE x.departure_id = d.departure_id AND t.cdc_id = :c)",
          // 미출발 취소
          "SELECT p.created_at, 'PRE_DEPARTURE_CANCEL', 'CANCELLATION', p.cancellation_id, NULL, p.trf_id, NULL, NULL,"
              + " NULL, NULL::int, p.state, p.scope, p.requested_by, p.reason FROM pre_departure_cancel p"
              + " JOIN transfer t ON t.trf_id = p.trf_id"
              + " WHERE t.cdc_id = :c AND p.created_at >= :s AND p.created_at < :u",
          // 리콜로 막힌 출고대기 물량
          "SELECT r.created_at, 'RECALL_HOLD', 'TRF', r.trf_id, t.trf_number, r.trf_id, t.wave_id, NULL,"
              + " r.sku, r.qty, NULL, NULL, NULL, r.source_ref FROM recall_hold r"
              + " JOIN transfer t ON t.trf_id = r.trf_id"
              + " WHERE t.cdc_id = :c AND r.created_at >= :s AND r.created_at < :u");

  private final NamedParameterJdbcTemplate named;

  public EventLogStore(NamedParameterJdbcTemplate named) {
    this.named = named;
  }

  /** limit + 1 개까지(잘렸는지 보려고). */
  public List<Row> events(
      String cdcId, Instant since, Instant until, @Nullable Collection<String> types, int limit) {
    MapSqlParameterSource p =
        new MapSqlParameterSource(
            Map.of(
                "c",
                cdcId,
                "s",
                Timestamp.from(since),
                "u",
                Timestamp.from(until),
                "n",
                limit + 1));
    String where = "";
    if (types != null && !types.isEmpty()) {
      where = " WHERE e.type IN (:t)";
      p.addValue("t", types);
    }
    return named.query(
        "SELECT * FROM ("
            + UNION
            + ") e"
            + where
            + " ORDER BY e.at DESC, e.type, e.ref_id LIMIT :n",
        p,
        (rs, i) ->
            new Row(
                rs.getTimestamp(1).toInstant(),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getString(5),
                rs.getString(6),
                rs.getString(7),
                rs.getString(8),
                rs.getString(9),
                (Integer) rs.getObject(10),
                rs.getString(11),
                rs.getString(12),
                rs.getString(13),
                rs.getString(14)));
  }
}
