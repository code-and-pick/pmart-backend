package com.pmart.delivery.tracking.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 위치 점·마지막 위치(JDBC, V2__rider_locations.sql). */
@Repository
public class LocationStore {

  public record Latest(
      String riderId,
      double lat,
      double lng,
      @Nullable Double accuracyM,
      Instant recordedAt,
      Instant receivedAt,
      String quality) {}

  public record Point(
      double lat,
      double lng,
      @Nullable Double accuracyM,
      @Nullable Double speedMps,
      @Nullable Double headingDeg,
      @Nullable String deliveryId,
      Instant recordedAt) {}

  private final JdbcTemplate jdbc;

  public LocationStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void insertPoint(String riderId, Point p, Instant receivedAt, String quality) {
    jdbc.update(
        "INSERT INTO rider_location_point (rider_id, lat, lng, accuracy_m, speed_mps, heading_deg,"
            + " delivery_id, recorded_at, received_at, quality) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        riderId,
        p.lat(),
        p.lng(),
        p.accuracyM(),
        p.speedMps(),
        p.headingDeg(),
        p.deliveryId(),
        Timestamp.from(p.recordedAt()),
        Timestamp.from(receivedAt),
        quality);
  }

  /** 단말 시각이 더 늦을 때만 바꾼다(조건부 upsert). */
  public void upsertLatest(String riderId, Point p, Instant receivedAt, String quality) {
    jdbc.update(
        "INSERT INTO rider_latest_location (rider_id, lat, lng, accuracy_m, recorded_at, received_at,"
            + " quality) VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (rider_id) DO UPDATE SET"
            + " lat = EXCLUDED.lat, lng = EXCLUDED.lng, accuracy_m = EXCLUDED.accuracy_m,"
            + " recorded_at = EXCLUDED.recorded_at, received_at = EXCLUDED.received_at,"
            + " quality = EXCLUDED.quality WHERE rider_latest_location.recorded_at <"
            + " EXCLUDED.recorded_at",
        riderId,
        p.lat(),
        p.lng(),
        p.accuracyM(),
        Timestamp.from(p.recordedAt()),
        Timestamp.from(receivedAt),
        quality);
  }

  /** 행을 잡고 마지막 위치를 본다(같은 라이더 업로드를 줄 세움). */
  public Optional<Latest> lockLatest(String riderId) {
    jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))", riderId);
    return latest(riderId);
  }

  public Optional<Latest> latest(String riderId) {
    List<Latest> rows =
        jdbc.query(
            "SELECT * FROM rider_latest_location WHERE rider_id = ?", (rs, i) -> row(rs), riderId);
    return rows.stream().findFirst();
  }

  public List<Latest> latest(List<String> riderIds) {
    if (riderIds.isEmpty()) {
      return List.of();
    }
    String in = String.join(",", Collections.nCopies(riderIds.size(), "?"));
    return jdbc.query(
        "SELECT * FROM rider_latest_location WHERE rider_id IN (" + in + ") ORDER BY rider_id",
        (rs, i) -> row(rs),
        riderIds.toArray());
  }

  // ---------- 위치 끊김·진행 중 배송·보존(V3, 2026-11-14) ----------

  /** 끊김 후보 — 진행 중 배송이 있는 라이더의 기준 시각(마지막 수신과 가장 이른 배정 중 늦은 것). */
  public record Watch(
      String riderId,
      String deliveryId,
      @Nullable Instant lastReceivedAt,
      Instant since,
      @Nullable Instant alertedFor,
      int alertCount) {
    public Instant base() {
      return lastReceivedAt == null || lastReceivedAt.isBefore(since) ? since : lastReceivedAt;
    }
  }

  public void touchSignal(String riderId, Instant now) {
    jdbc.update(
        "INSERT INTO rider_signal (rider_id, last_received_at) VALUES (?, ?) ON CONFLICT (rider_id)"
            + " DO UPDATE SET last_received_at = GREATEST(rider_signal.last_received_at,"
            + " EXCLUDED.last_received_at)",
        riderId,
        Timestamp.from(now));
  }

  /** 배정(또는 새 라이더로 바뀜) — 더 낮은 배정 버전·이미 닫힌 배송은 무시. */
  public void openDelivery(String deliveryId, String riderId, int version, Instant at) {
    jdbc.update(
        "INSERT INTO rider_active_delivery (delivery_id, rider_id, assignment_version, state, since,"
            + " updated_at) VALUES (?, ?, ?, 'ACTIVE', ?, ?) ON CONFLICT (delivery_id) DO UPDATE SET"
            + " rider_id = EXCLUDED.rider_id, assignment_version = EXCLUDED.assignment_version, since"
            + " = EXCLUDED.since, updated_at = EXCLUDED.updated_at WHERE"
            + " rider_active_delivery.state = 'ACTIVE' AND rider_active_delivery.assignment_version"
            + " < EXCLUDED.assignment_version",
        deliveryId,
        riderId,
        version,
        Timestamp.from(at),
        Timestamp.from(at));
  }

  /** 배정 해제(배차 대기로) — 그 버전 이상이면 지운다(다음 배정이 다시 연다). */
  public void releaseDelivery(String deliveryId, int version) {
    jdbc.update(
        "DELETE FROM rider_active_delivery WHERE delivery_id = ? AND state = 'ACTIVE' AND"
            + " assignment_version <= ?",
        deliveryId,
        version);
  }

  /** 완료·실패·픽업 전 취소 — 닫는다(늦게 온 배정 사건이 다시 열지 않게 행은 남김). */
  public void closeDelivery(String deliveryId, Instant at) {
    jdbc.update(
        "INSERT INTO rider_active_delivery (delivery_id, rider_id, assignment_version, state, since,"
            + " updated_at) VALUES (?, '', 2147483647, 'CLOSED', ?, ?) ON CONFLICT (delivery_id) DO"
            + " UPDATE SET state = 'CLOSED', updated_at = EXCLUDED.updated_at",
        deliveryId,
        Timestamp.from(at),
        Timestamp.from(at));
  }

  /** 기준 시각이 cutoff 보다 이르고 그 기준으로 아직 알리지 않은 라이더. 겹침은 {@link #markAlerted} 조건 갱신이 막는다. */
  public List<Watch> lockStale(Instant cutoff, int limit) {
    return jdbc.query(
        "SELECT a.rider_id, a.delivery_id, s.last_received_at, a.since, s.alerted_for,"
            + " COALESCE(s.alert_count, 0) AS alert_count FROM (SELECT DISTINCT ON (rider_id)"
            + " rider_id, delivery_id, since FROM rider_active_delivery WHERE state = 'ACTIVE' ORDER"
            + " BY rider_id, since) a LEFT JOIN rider_signal s ON s.rider_id = a.rider_id WHERE"
            + " GREATEST(a.since, COALESCE(s.last_received_at, a.since)) < ? AND (s.alerted_for IS"
            + " NULL OR s.alerted_for < GREATEST(a.since, COALESCE(s.last_received_at, a.since)))"
            + " ORDER BY a.rider_id LIMIT ?",
        (rs, i) ->
            new Watch(
                rs.getString("rider_id"),
                rs.getString("delivery_id"),
                instantOrNull(rs, "last_received_at"),
                rs.getTimestamp("since").toInstant(),
                instantOrNull(rs, "alerted_for"),
                rs.getInt("alert_count")),
        Timestamp.from(cutoff),
        limit);
  }

  /** 끊김 알림 기록 — 같은 기준으로 이미 냈으면 false(다른 인스턴스와 겹쳐도 한 번). */
  public boolean markAlerted(String riderId, Instant base) {
    jdbc.update(
        "INSERT INTO rider_signal (rider_id) VALUES (?) ON CONFLICT (rider_id) DO NOTHING",
        riderId);
    return jdbc.update(
            "UPDATE rider_signal SET alerted_for = ?, alert_count = alert_count + 1 WHERE rider_id"
                + " = ? AND (alerted_for IS NULL OR alerted_for < ?)",
            Timestamp.from(base),
            riderId,
            Timestamp.from(base))
        == 1;
  }

  /** 보존 기간 지난 위치(서버 수신 시각 기준) 지움 — 한 번에 limit 행. 지운 수. */
  public int purgePoints(Instant before, int limit) {
    return jdbc.update(
        "DELETE FROM rider_location_point WHERE point_id IN (SELECT point_id FROM"
            + " rider_location_point WHERE received_at < ? LIMIT ?)",
        Timestamp.from(before),
        limit);
  }

  /** 보존 기간 동안 새 위치가 없는 라이더의 마지막 위치·닫힌 배송 기록도 지운다. */
  public int purgeStale(Instant before) {
    int n =
        jdbc.update(
            "DELETE FROM rider_latest_location WHERE received_at < ?", Timestamp.from(before));
    n +=
        jdbc.update(
            "DELETE FROM rider_active_delivery WHERE state = 'CLOSED' AND updated_at < ?",
            Timestamp.from(before));
    return n;
  }

  private static @Nullable Instant instantOrNull(ResultSet rs, String col) throws SQLException {
    Timestamp t = rs.getTimestamp(col);
    return t == null ? null : t.toInstant();
  }

  private static Latest row(ResultSet rs) throws SQLException {
    return new Latest(
        rs.getString("rider_id"),
        rs.getDouble("lat"),
        rs.getDouble("lng"),
        rs.getObject("accuracy_m", Double.class),
        rs.getTimestamp("recorded_at").toInstant(),
        rs.getTimestamp("received_at").toInstant(),
        rs.getString("quality"));
  }
}
