package com.pmart.cdc.location.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 위치 마스터 저장(JDBC). 상태 변경은 version 조건부(경합이면 0 행). */
@Repository
public class LocationStore {

  public record LocationRow(
      String locationCode,
      String centerId,
      String area,
      String floor,
      String aisle,
      String bay,
      int level,
      int slot,
      String temperatureZone,
      String locationType,
      @Nullable String capacityUnit,
      @Nullable Integer maxQty,
      @Nullable Long maxWeightG,
      List<String> restrictions,
      @Nullable Integer pickFaceMinQty,
      @Nullable Integer pickFaceTargetQty,
      String state,
      int version,
      @Nullable String approvalRef,
      Instant createdAt,
      Instant updatedAt) {}

  public record Filter(
      String centerId,
      @Nullable String temperatureZone,
      @Nullable String area,
      @Nullable String floor,
      @Nullable String locationType,
      @Nullable String state) {}

  private static final String COLUMNS =
      "location_code, center_id, area, floor, aisle, bay, level_no, slot_no, temperature_zone,"
          + " location_type, capacity_unit, max_qty, max_weight_g, restrictions, pick_face_min_qty,"
          + " pick_face_target_qty, state, row_version, approval_ref, created_at, updated_at";

  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate named;

  public LocationStore(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
    this.jdbc = jdbc;
    this.named = named;
  }

  /** 이미 있는 코드. */
  public List<String> existing(Collection<String> codes) {
    if (codes.isEmpty()) {
      return List.of();
    }
    return named.queryForList(
        "SELECT location_code FROM location WHERE location_code IN (:codes)",
        Map.of("codes", codes),
        String.class);
  }

  public void insertAll(List<LocationRow> rows) {
    List<Object[]> args = new ArrayList<>();
    for (LocationRow r : rows) {
      args.add(
          new Object[] {
            r.locationCode(),
            r.centerId(),
            r.area(),
            r.floor(),
            r.aisle(),
            r.bay(),
            r.level(),
            r.slot(),
            r.temperatureZone(),
            r.locationType(),
            r.capacityUnit(),
            r.maxQty(),
            r.maxWeightG(),
            r.restrictions().isEmpty() ? null : String.join(",", r.restrictions()),
            r.pickFaceMinQty(),
            r.pickFaceTargetQty(),
            r.state(),
            r.version(),
            r.approvalRef(),
            Timestamp.from(r.createdAt()),
            Timestamp.from(r.updatedAt())
          });
    }
    jdbc.batchUpdate(
        "INSERT INTO location (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        args);
  }

  public void insertHistory(
      List<String> codes,
      String action,
      @Nullable String from,
      String to,
      @Nullable String reason,
      @Nullable String approvalRef,
      String by,
      String requestId,
      int version,
      Instant at) {
    List<Object[]> args = new ArrayList<>();
    for (String c : codes) {
      args.add(
          new Object[] {
            c, action, from, to, reason, approvalRef, by, requestId, version, Timestamp.from(at)
          });
    }
    jdbc.batchUpdate(
        "INSERT INTO location_state_history (location_code, action, from_state, to_state, reason,"
            + " approval_ref, changed_by, request_id, row_version, changed_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?)",
        args);
  }

  public Optional<LocationRow> find(String code) {
    return jdbc
        .query(
            "SELECT " + COLUMNS + " FROM location WHERE location_code = ?",
            (rs, i) -> row(rs),
            code)
        .stream()
        .findFirst();
  }

  /** version 조건부 상태 변경. 다른 요청이 먼저 바꿨으면 false. */
  public boolean changeState(String code, String to, int expectedVersion, Instant at) {
    return jdbc.update(
            "UPDATE location SET state = ?, row_version = row_version + 1, updated_at = ?"
                + " WHERE location_code = ? AND row_version = ?",
            to,
            Timestamp.from(at),
            code,
            expectedVersion)
        == 1;
  }

  /** 코드 오름차순, 커서 = 마지막 코드. limit 은 호출자가 +1 해 다음 페이지를 판단한다. */
  public List<LocationRow> list(Filter f, @Nullable String afterCode, int limit) {
    StringBuilder sql =
        new StringBuilder("SELECT " + COLUMNS + " FROM location WHERE center_id = ?");
    List<Object> args = new ArrayList<>();
    args.add(f.centerId());
    add(sql, args, "temperature_zone", f.temperatureZone());
    add(sql, args, "area", f.area());
    add(sql, args, "floor", f.floor());
    add(sql, args, "location_type", f.locationType());
    add(sql, args, "state", f.state());
    if (afterCode != null) {
      sql.append(" AND location_code > ?");
      args.add(afterCode);
    }
    sql.append(" ORDER BY location_code LIMIT ?");
    args.add(limit);
    return jdbc.query(sql.toString(), (rs, i) -> row(rs), args.toArray());
  }

  private static void add(StringBuilder sql, List<Object> args, String col, @Nullable String v) {
    if (v != null) {
      sql.append(" AND ").append(col).append(" = ?");
      args.add(v);
    }
  }

  private static LocationRow row(ResultSet rs) throws SQLException {
    String restrictions = rs.getString("restrictions");
    return new LocationRow(
        rs.getString("location_code"),
        rs.getString("center_id"),
        rs.getString("area"),
        rs.getString("floor"),
        rs.getString("aisle"),
        rs.getString("bay"),
        rs.getInt("level_no"),
        rs.getInt("slot_no"),
        rs.getString("temperature_zone"),
        rs.getString("location_type"),
        rs.getString("capacity_unit"),
        rs.getObject("max_qty", Integer.class),
        rs.getObject("max_weight_g", Long.class),
        restrictions == null ? List.of() : List.of(restrictions.split(",")),
        rs.getObject("pick_face_min_qty", Integer.class),
        rs.getObject("pick_face_target_qty", Integer.class),
        rs.getString("state"),
        rs.getInt("row_version"),
        rs.getString("approval_ref"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }
}
