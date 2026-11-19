package com.pmart.delivery.tracking.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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

  /**
   * {@code at} 앞뒤 {@code window} 안에서 단말 기록 시각이 가장 가까운 점(DD-2 — 오배송 회수지). 품질은 거르지 않는다(quality 로
   * 알린다). 색인 {@code (rider_id, recorded_at)}.
   */
  public Optional<Latest> pointNear(String riderId, Instant at, java.time.Duration window) {
    List<Latest> rows =
        jdbc.query(
            "SELECT rider_id, lat, lng, accuracy_m, recorded_at, received_at, quality FROM"
                + " rider_location_point WHERE rider_id = ? AND recorded_at BETWEEN ? AND ? ORDER BY"
                + " abs(extract(epoch FROM (recorded_at - ?::timestamptz))), point_id DESC LIMIT 1",
            (rs, i) -> row(rs),
            riderId,
            Timestamp.from(at.minus(window)),
            Timestamp.from(at.plus(window)),
            Timestamp.from(at));
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

  /** 라이더 관제(2026-11-17) — 감시 중인 배정과 단계·도착 예정. */
  public record Active(
      String riderId,
      String deliveryId,
      @Nullable String stage,
      boolean pickedUp,
      @Nullable Instant etaPickup,
      @Nullable Instant etaDrop,
      @Nullable String deliveryNumber) {}

  /** 라이더마다 마지막으로 끝난(CLOSED·EXPIRED) 진행 중 배송의 시각(2026-11-18 — 관제 대기 시작). */
  public Map<String, Instant> lastEndedOf(List<String> riderIds) {
    Map<String, Instant> out = new java.util.HashMap<>();
    if (riderIds.isEmpty()) {
      return out;
    }
    String in = String.join(",", Collections.nCopies(riderIds.size(), "?"));
    jdbc.query(
        "SELECT rider_id, max(updated_at) FROM rider_active_delivery WHERE state <> 'ACTIVE' AND"
            + " rider_id IN ("
            + in
            + ") GROUP BY rider_id",
        rs -> {
          out.put(rs.getString(1), rs.getTimestamp(2).toInstant());
        },
        riderIds.toArray());
    return out;
  }

  public List<Active> activeOf(List<String> riderIds) {
    if (riderIds.isEmpty()) {
      return List.of();
    }
    String in = String.join(",", Collections.nCopies(riderIds.size(), "?"));
    return jdbc.query(
        "SELECT a.rider_id, a.delivery_id, p.stage, a.picked_up_at, a.eta_pickup, a.eta_drop,"
            + " p.order_number, p.delivery_seq FROM"
            + " rider_active_delivery a LEFT JOIN delivery_progress p ON p.delivery_id ="
            + " a.delivery_id WHERE a.state = 'ACTIVE' AND a.rider_id IN ("
            + in
            + ") ORDER BY a.rider_id, a.since",
        (rs, i) ->
            new Active(
                rs.getString(1),
                rs.getString(2),
                rs.getString(3),
                rs.getTimestamp(4) != null,
                rs.getTimestamp(5) == null ? null : rs.getTimestamp(5).toInstant(),
                rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant(),
                rs.getString(7) == null || rs.getObject(8) == null
                    ? null
                    : rs.getString(7) + "-" + rs.getInt(8)),
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

  /**
   * 배정(또는 새 라이더로 바뀜). since = 사건에 적힌 배정 시각. 지금보다 높은 배정 버전만 연다 — 늦게 온 옛 배정·이미 닫힌(CLOSED) 배송은 무시.
   * 해제(RELEASED)·시간 지나 끝난(EXPIRED) 배송은 더 높은 버전 배정이 다시 연다.
   */
  public void openDelivery(
      String deliveryId, String riderId, int version, Instant since, Instant now) {
    jdbc.update(
        "INSERT INTO rider_active_delivery (delivery_id, rider_id, assignment_version, state, since,"
            + " updated_at) VALUES (?, ?, ?, 'ACTIVE', ?, ?) ON CONFLICT (delivery_id) DO UPDATE SET"
            + " rider_id = EXCLUDED.rider_id, assignment_version = EXCLUDED.assignment_version, state"
            + " = 'ACTIVE', since = EXCLUDED.since, updated_at = EXCLUDED.updated_at WHERE"
            + " rider_active_delivery.state <> 'CLOSED' AND rider_active_delivery.assignment_version"
            + " < EXCLUDED.assignment_version",
        deliveryId,
        riderId,
        version,
        Timestamp.from(since),
        Timestamp.from(now));
  }

  /**
   * 배정 해제(배차 대기로) — 행을 지우지 않고 RELEASED 와 그 배정 버전을 남긴다. 재처리·DLQ 재전송으로 옛 배정 사건이 늦게 와도 버전이 같거나 낮아 다시
   * 열지 않는다(PC 1 PR #15 검토).
   */
  public void releaseDelivery(String deliveryId, int version, Instant now) {
    jdbc.update(
        "INSERT INTO rider_active_delivery (delivery_id, rider_id, assignment_version, state, since,"
            + " updated_at) VALUES (?, '', ?, 'RELEASED', ?, ?) ON CONFLICT (delivery_id) DO UPDATE"
            + " SET state = 'RELEASED', assignment_version = EXCLUDED.assignment_version, updated_at"
            + " = EXCLUDED.updated_at WHERE rider_active_delivery.state <> 'CLOSED' AND"
            + " rider_active_delivery.assignment_version <= EXCLUDED.assignment_version",
        deliveryId,
        version,
        Timestamp.from(now),
        Timestamp.from(now));
  }

  /** 배정 시각이 before 보다 이른 감시를 끝낸다(EXPIRED — 사용자 2026-11-14 "5시간이 지나면 감시 종료"). 끝낸 수. */
  public int expireWatches(Instant before, Instant now) {
    return jdbc.update(
        "UPDATE rider_active_delivery SET state = 'EXPIRED', updated_at = ? WHERE state = 'ACTIVE'"
            + " AND since < ?",
        Timestamp.from(now),
        Timestamp.from(before));
  }

  /** 픽업 완료(PickupCompleted·회수 ReturnPickedUp) — 이제 ETA 는 라이더 → 도착지. */
  public void markPickedUp(String deliveryId, Instant at) {
    jdbc.update(
        "UPDATE rider_active_delivery SET picked_up_at = COALESCE(picked_up_at, ?) WHERE"
            + " delivery_id = ?",
        Timestamp.from(at),
        deliveryId);
  }

  /** ETA 계산 대상 — 감시 중이고 마지막 계산이 before 보다 이른 배송(라이더 순). */
  public record EtaRow(
      String deliveryId,
      String riderId,
      @Nullable Instant pickedUpAt,
      @Nullable Double pickupLat,
      @Nullable Double pickupLng,
      @Nullable Double dropLat,
      @Nullable Double dropLng,
      @Nullable Integer pickupToDropS,
      @Nullable Instant etaDrop) {}

  public List<EtaRow> etaCandidates(Instant before, int limit) {
    return jdbc.query(
        "SELECT delivery_id, rider_id, picked_up_at, pickup_lat, pickup_lng, drop_lat, drop_lng,"
            + " pickup_to_drop_s, eta_drop FROM rider_active_delivery WHERE state = 'ACTIVE' AND"
            + " (eta_checked_at IS NULL OR eta_checked_at < ?) ORDER BY rider_id, delivery_id"
            + " LIMIT ?",
        (rs, n) ->
            new EtaRow(
                rs.getString("delivery_id"),
                rs.getString("rider_id"),
                instantOrNull(rs, "picked_up_at"),
                rs.getObject("pickup_lat", Double.class),
                rs.getObject("pickup_lng", Double.class),
                rs.getObject("drop_lat", Double.class),
                rs.getObject("drop_lng", Double.class),
                rs.getObject("pickup_to_drop_s", Integer.class),
                instantOrNull(rs, "eta_drop")),
        Timestamp.from(before),
        limit);
  }

  public void saveRoutePoints(
      String deliveryId,
      double pickupLat,
      double pickupLng,
      double dropLat,
      double dropLng,
      @Nullable Integer pickupToDropS) {
    jdbc.update(
        "UPDATE rider_active_delivery SET pickup_lat = ?, pickup_lng = ?, drop_lat = ?, drop_lng ="
            + " ?, pickup_to_drop_s = ? WHERE delivery_id = ?",
        pickupLat,
        pickupLng,
        dropLat,
        dropLng,
        pickupToDropS,
        deliveryId);
  }

  public void etaChecked(String deliveryId, Instant now) {
    jdbc.update(
        "UPDATE rider_active_delivery SET eta_checked_at = ? WHERE delivery_id = ?",
        Timestamp.from(now),
        deliveryId);
  }

  /** 새 도착 예정을 적고 사건 버전을 올려 돌려준다(아직 감시 중일 때만 — 아니면 null). */
  public @Nullable Integer etaPublished(
      String deliveryId, @Nullable Instant etaPickup, Instant etaDrop, Instant now) {
    List<Integer> v =
        jdbc.queryForList(
            "UPDATE rider_active_delivery SET eta_pickup = ?, eta_drop = ?, eta_checked_at = ?,"
                + " eta_version = eta_version + 1 WHERE delivery_id = ? AND state = 'ACTIVE'"
                + " RETURNING eta_version",
            Integer.class,
            etaPickup == null ? null : Timestamp.from(etaPickup),
            Timestamp.from(etaDrop),
            Timestamp.from(now),
            deliveryId);
    return v.isEmpty() ? null : v.get(0);
  }

  /** 완료·실패·픽업 전 취소 — 닫는다(늦게 온 배정 사건이 다시 열지 않게 행은 남김). */
  /** 곧 도착 후보(TR-4) — 감시 중·픽업 뒤·아직 안 알림. */
  public record ApproachRow(
      String deliveryId,
      String riderId,
      @Nullable Double dropLat,
      @Nullable Double dropLng,
      @Nullable Double pickupLat,
      @Nullable Double pickupLng) {}

  public List<ApproachRow> approachCandidates(int limit) {
    return jdbc.query(
        "SELECT delivery_id, rider_id, drop_lat, drop_lng, pickup_lat, pickup_lng FROM"
            + " rider_active_delivery WHERE state = 'ACTIVE' AND picked_up_at IS NOT NULL AND"
            + " approach_notified_at IS NULL ORDER BY delivery_id LIMIT ?",
        (rs, i) ->
            new ApproachRow(
                rs.getString("delivery_id"),
                rs.getString("rider_id"),
                (Double) rs.getObject("drop_lat"),
                (Double) rs.getObject("drop_lng"),
                (Double) rs.getObject("pickup_lat"),
                (Double) rs.getObject("pickup_lng")),
        limit);
  }

  /** 곧 도착 알림 표시 — 처음 한 번만 true. */
  public boolean markApproached(String deliveryId, Instant at) {
    return jdbc.update(
            "UPDATE rider_active_delivery SET approach_notified_at = ? WHERE delivery_id = ? AND"
                + " approach_notified_at IS NULL AND state = 'ACTIVE'",
            Timestamp.from(at),
            deliveryId)
        > 0;
  }

  /** 실패 확정 되돌리기(2026-11-19) — 실패로 끝낸 감시를 다시(같은 라이더·배정). 라이더를 모르던 줄(끝 사건이 먼저 온 것)은 그대로. */
  public void reopenDelivery(String deliveryId, Instant at) {
    jdbc.update(
        "UPDATE rider_active_delivery SET state = 'ACTIVE', updated_at = ? WHERE delivery_id = ?"
            + " AND state = 'CLOSED' AND rider_id <> ''",
        Timestamp.from(at),
        deliveryId);
  }

  public void closeDelivery(String deliveryId, Instant at) {
    jdbc.update(
        "INSERT INTO rider_active_delivery (delivery_id, rider_id, assignment_version, state, since,"
            + " updated_at) VALUES (?, '', 2147483647, 'CLOSED', ?, ?) ON CONFLICT (delivery_id) DO"
            + " UPDATE SET state = 'CLOSED', updated_at = EXCLUDED.updated_at",
        deliveryId,
        Timestamp.from(at),
        Timestamp.from(at));
  }

  /** 기준 시각이 cutoff 보다 이르고 그 기준으로 아직 알리지 않은 라이더(잠그지 않는다). 겹침은 {@link #markAlerted} 조건 갱신이 막는다. */
  public List<Watch> staleCandidates(Instant cutoff, int limit) {
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

  /**
   * 끊김 알림 기록 — 올린 뒤의 알림 횟수(사건 버전으로 쓴다). 같은 기준으로 이미 냈으면 null(다른 인스턴스와 겹쳐도 한 번). 횟수는 갱신하면서 읽는다 — 트랜잭션
   * 밖에서 읽은 값이면 겹친 처리에서 번호가 어긋날 수 있었다.
   */
  public @Nullable Integer markAlerted(String riderId, Instant base) {
    jdbc.update(
        "INSERT INTO rider_signal (rider_id) VALUES (?) ON CONFLICT (rider_id) DO NOTHING",
        riderId);
    List<Integer> n =
        jdbc.queryForList(
            "UPDATE rider_signal SET alerted_for = ?, alert_count = alert_count + 1 WHERE rider_id"
                + " = ? AND (alerted_for IS NULL OR alerted_for < ?) RETURNING alert_count",
            Integer.class,
            Timestamp.from(base),
            riderId,
            Timestamp.from(base));
    return n.isEmpty() ? null : n.get(0);
  }

  /** 보존 기간 지난 위치(서버 수신 시각 기준) 지움 — 한 번에 limit 행. 지운 수. */
  public int purgePoints(Instant before, int limit) {
    return jdbc.update(
        "DELETE FROM rider_location_point WHERE point_id IN (SELECT point_id FROM"
            + " rider_location_point WHERE received_at < ? LIMIT ?)",
        Timestamp.from(before),
        limit);
  }

  /** 보존 기간 동안 새 위치가 없는 라이더의 마지막 위치, 감시 중이 아닌(해제·시간 끝·닫힘) 배송 기록도 지운다. */
  public int purgeStale(Instant before) {
    int n =
        jdbc.update(
            "DELETE FROM rider_latest_location WHERE received_at < ?", Timestamp.from(before));
    n +=
        jdbc.update(
            "DELETE FROM rider_active_delivery WHERE state <> 'ACTIVE' AND updated_at < ?",
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
