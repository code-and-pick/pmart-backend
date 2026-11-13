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
