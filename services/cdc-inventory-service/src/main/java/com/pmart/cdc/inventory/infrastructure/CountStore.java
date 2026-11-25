package com.pmart.cdc.inventory.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 위치 실사(0.8.0) — 세션·칸 잠금·장부 기준/계수 줄. */
@Repository
public class CountStore {

  public record SessionRow(
      String sessionId,
      String cdcId,
      String state,
      @Nullable String reason,
      String startedBy,
      @Nullable String firstCounterId,
      @Nullable String recounterId,
      @Nullable String decidedBy,
      @Nullable String decisionReason,
      @Nullable String approvalRef,
      @Nullable String commitId,
      Instant createdAt,
      @Nullable Instant decidedAt) {}

  /** 줄 키 = 칸·SKU·LOT. */
  public record Key(String locationCode, String sku, String lot) {}

  private static final String COLS =
      "session_id, cdc_id, state, reason, started_by, first_counter_id, recounter_id, decided_by,"
          + " decision_reason, approval_ref, commit_id, created_at, decided_at";

  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate named;

  public CountStore(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
    this.jdbc = jdbc;
    this.named = named;
  }

  /**
   * 세션과 칸 잠금을 만든다.
   *
   * @return false = 칸 하나가 이미 열린 실사에 있음
   */
  public boolean insert(SessionRow s, Collection<String> locations, Instant at) {
    jdbc.update(
        "INSERT INTO count_session (session_id, cdc_id, state, reason, started_by, created_at,"
            + " updated_at) VALUES (?,?,?,?,?,?,?)",
        s.sessionId(),
        s.cdcId(),
        s.state(),
        s.reason(),
        s.startedBy(),
        Timestamp.from(at),
        Timestamp.from(at));
    try {
      for (String l : locations) {
        jdbc.update(
            "INSERT INTO count_location (session_id, cdc_id, location_code, open) VALUES (?,?,?,true)",
            s.sessionId(),
            s.cdcId(),
            l);
      }
    } catch (DuplicateKeyException e) {
      return false;
    }
    return true;
  }

  /** 열린 실사가 있는 칸(그중 주어진 것). */
  public Set<String> openLocations(String cdcId, Collection<String> codes) {
    Set<String> out = new HashSet<>();
    if (codes.isEmpty()) {
      return out;
    }
    named.query(
        "SELECT location_code FROM count_location WHERE cdc_id = :c AND open AND location_code IN (:l)",
        Map.of("c", cdcId, "l", codes),
        rs -> {
          out.add(rs.getString(1));
        });
    return out;
  }

  public void putLines(String sessionId, int round, Map<Key, Integer> lines) {
    jdbc.update("DELETE FROM count_line WHERE session_id = ? AND round = ?", sessionId, round);
    for (Map.Entry<Key, Integer> e : lines.entrySet()) {
      jdbc.update(
          "INSERT INTO count_line (session_id, round, location_code, sku, lot, qty) VALUES (?,?,?,?,?,?)",
          sessionId,
          round,
          e.getKey().locationCode(),
          e.getKey().sku(),
          e.getKey().lot(),
          e.getValue());
    }
  }

  public Map<Key, Integer> lines(String sessionId, int round) {
    Map<Key, Integer> out = new java.util.LinkedHashMap<>();
    jdbc.query(
        "SELECT location_code, sku, lot, qty FROM count_line WHERE session_id = ? AND round = ?"
            + " ORDER BY location_code, sku, lot",
        rs -> {
          out.put(
              new Key(rs.getString("location_code"), rs.getString("sku"), rs.getString("lot")),
              rs.getInt("qty"));
        },
        sessionId,
        round);
    return out;
  }

  public List<String> locations(String sessionId) {
    return jdbc.queryForList(
        "SELECT location_code FROM count_location WHERE session_id = ? ORDER BY location_code",
        String.class,
        sessionId);
  }

  public void close(String sessionId) {
    jdbc.update("UPDATE count_location SET open = false WHERE session_id = ?", sessionId);
  }

  public Optional<SessionRow> find(String sessionId, boolean lock) {
    return jdbc
        .query(
            "SELECT "
                + COLS
                + " FROM count_session WHERE session_id = ?"
                + (lock ? " FOR UPDATE" : ""),
            (rs, i) -> row(rs),
            sessionId)
        .stream()
        .findFirst();
  }

  public void counted(String sessionId, String state, String column, String counter, Instant at) {
    jdbc.update(
        "UPDATE count_session SET state = ?, "
            + ("recounter_id".equals(column) ? "recounter_id" : "first_counter_id")
            + " = ?, updated_at = ? WHERE session_id = ?",
        state,
        counter,
        Timestamp.from(at),
        sessionId);
  }

  public void decided(
      String sessionId,
      String state,
      String by,
      String reason,
      @Nullable String approvalRef,
      @Nullable String commitId,
      Instant at) {
    jdbc.update(
        "UPDATE count_session SET state = ?, decided_by = ?, decision_reason = ?, approval_ref = ?,"
            + " commit_id = ?, decided_at = ?, updated_at = ? WHERE session_id = ?",
        state,
        by,
        reason,
        approvalRef,
        commitId,
        Timestamp.from(at),
        Timestamp.from(at),
        sessionId);
  }

  private static SessionRow row(ResultSet rs) throws SQLException {
    Timestamp d = rs.getTimestamp("decided_at");
    return new SessionRow(
        rs.getString("session_id"),
        rs.getString("cdc_id"),
        rs.getString("state"),
        rs.getString("reason"),
        rs.getString("started_by"),
        rs.getString("first_counter_id"),
        rs.getString("recounter_id"),
        rs.getString("decided_by"),
        rs.getString("decision_reason"),
        rs.getString("approval_ref"),
        rs.getString("commit_id"),
        rs.getTimestamp("created_at").toInstant(),
        d == null ? null : d.toInstant());
  }
}
