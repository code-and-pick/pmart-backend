package com.pmart.cdc.location.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 피킹 선반 통로별 피킹 방식 설정값(0.5.0). 행이 없으면 미설정. */
@Repository
public class AislePickMethodStore {

  /** 통로 = (구역·층·통로). */
  public record Aisle(String area, String floor, String aisle) {}

  public record Row(
      String area, String floor, String aisle, String pickMethod, String updatedBy, Instant at) {}

  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate named;

  public AislePickMethodStore(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
    this.jdbc = jdbc;
    this.named = named;
  }

  public List<Row> list(String centerId, @Nullable String area, @Nullable String floor) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT area, floor, aisle, pick_method, updated_by, updated_at FROM aisle_pick_method"
                + " WHERE center_id = ?");
    List<Object> args = new ArrayList<>();
    args.add(centerId);
    if (area != null) {
      sql.append(" AND area = ?");
      args.add(area);
    }
    if (floor != null) {
      sql.append(" AND floor = ?");
      args.add(floor);
    }
    sql.append(" ORDER BY area, floor, aisle");
    return jdbc.query(
        sql.toString(),
        (rs, i) ->
            new Row(
                rs.getString("area"),
                rs.getString("floor"),
                rs.getString("aisle"),
                rs.getString("pick_method"),
                rs.getString("updated_by"),
                rs.getTimestamp("updated_at").toInstant()),
        args.toArray());
  }

  /** 이 센터에서 PICK_FACE 위치가 하나라도 있는 통로. */
  public List<Aisle> pickFaceAisles(String centerId) {
    return jdbc.query(
        "SELECT DISTINCT area, floor, aisle FROM location WHERE center_id = ?"
            + " AND location_type = 'PICK_FACE'",
        (rs, i) -> new Aisle(rs.getString("area"), rs.getString("floor"), rs.getString("aisle")),
        centerId);
  }

  public void upsert(
      String centerId,
      Aisle a,
      String pickMethod,
      @Nullable String reason,
      String by,
      String requestId,
      Instant at) {
    jdbc.update(
        "INSERT INTO aisle_pick_method (center_id, area, floor, aisle, pick_method, reason,"
            + " updated_by, request_id, updated_at) VALUES (?,?,?,?,?,?,?,?,?)"
            + " ON CONFLICT (center_id, area, floor, aisle) DO UPDATE SET"
            + " pick_method = EXCLUDED.pick_method, reason = EXCLUDED.reason,"
            + " updated_by = EXCLUDED.updated_by, request_id = EXCLUDED.request_id,"
            + " updated_at = EXCLUDED.updated_at",
        centerId,
        a.area(),
        a.floor(),
        a.aisle(),
        pickMethod,
        reason,
        by,
        requestId,
        Timestamp.from(at));
  }

  public int delete(String centerId, Aisle a) {
    return jdbc.update(
        "DELETE FROM aisle_pick_method WHERE center_id = ? AND area = ? AND floor = ? AND aisle = ?",
        centerId,
        a.area(),
        a.floor(),
        a.aisle());
  }

  /** 위치 코드 → 그 통로의 피킹 방식(설정이 있는 PICK_FACE 위치만). */
  public Map<String, String> ofLocations(String centerId, Collection<String> codes) {
    Map<String, String> out = new HashMap<>();
    if (codes.isEmpty()) {
      return out;
    }
    named.query(
        "SELECT l.location_code, m.pick_method FROM location l JOIN aisle_pick_method m"
            + " ON m.center_id = l.center_id AND m.area = l.area AND m.floor = l.floor"
            + " AND m.aisle = l.aisle WHERE l.center_id = :center AND l.location_code IN (:codes)"
            + " AND l.location_type = 'PICK_FACE'",
        Map.of("center", centerId, "codes", codes),
        rs -> {
          out.put(rs.getString("location_code"), rs.getString("pick_method"));
        });
    return out;
  }
}
