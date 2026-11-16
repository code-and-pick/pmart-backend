package com.pmart.mfc.capacity.infrastructure;

import com.pmart.mfc.capacity.domain.CapacityHold;
import com.pmart.mfc.capacity.domain.CapacityRules.HourWindow;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * CapacityHold·창 행 저장(JDBC). 잠금 순서는 항상 Hold 행 → 창 행(교착 방지). 호출자 트랜잭션 안에서 쓴다.
 *
 * <p>점유 합계는 창 행을 FOR UPDATE 로 잡은 뒤 그 창의 활성 Hold 를 세는 방식이라 별도 카운터가 어긋날 일이 없다.
 */
@Repository
public class HoldStore {

  /** 창 점유(만료 시각이 지난 HELD 는 제외). */
  public record Occupancy(int held, int confirmed, int version) {}

  private static final String COLUMNS =
      "hold_id, order_id, mfc_id, window_start, window_end, units, state, version, expires_at,"
          + " release_reason";

  private final JdbcTemplate jdbc;

  public HoldStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<CapacityHold> find(String holdId, boolean forUpdate) {
    return jdbc
        .query(
            "SELECT "
                + COLUMNS
                + " FROM capacity_hold WHERE hold_id = ?"
                + (forUpdate ? " FOR UPDATE" : ""),
            HoldStore::map,
            holdId)
        .stream()
        .findFirst();
  }

  /** 주문의 활성 Hold 중 만료 시각이 지난 HELD 를 EXPIRED 로(새 Hold 전에 — 주문당 활성 1개 유일 인덱스). 바뀐 Hold. */
  public List<CapacityHold> expireStaleForOrder(String orderId, Instant now) {
    List<CapacityHold> stale =
        jdbc.query(
            "SELECT "
                + COLUMNS
                + " FROM capacity_hold WHERE order_id = ? AND state = 'HELD' AND expires_at <= ?"
                + " FOR UPDATE",
            HoldStore::map,
            orderId,
            Timestamp.from(now));
    stale.forEach(h -> close(h.holdId(), CapacityHold.State.EXPIRED, null, null, now));
    return stale;
  }

  /** 만료 대상 HELD(다른 인스턴스가 잡은 행은 건너뜀). */
  public List<CapacityHold> lockExpired(Instant now, int limit) {
    return jdbc.query(
        "SELECT "
            + COLUMNS
            + " FROM capacity_hold WHERE state = 'HELD' AND expires_at <= ?"
            + " ORDER BY expires_at LIMIT ? FOR UPDATE SKIP LOCKED",
        HoldStore::map,
        Timestamp.from(now),
        limit);
  }

  /** 주문의 HELD(진행 중인 S4 교체 확인). */
  public boolean hasHeldForOrder(String orderId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM capacity_hold WHERE order_id = ? AND state = 'HELD'",
            Integer.class,
            orderId);
    return n != null && n > 0;
  }

  /** S4 교체 Hold 가 바꾸는 기존 Hold. 없으면 null. */
  public @Nullable String replacesOf(String holdId) {
    List<String> r =
        jdbc.queryForList(
            "SELECT coalesce(replaces_hold_id, '') FROM capacity_hold WHERE hold_id = ?",
            String.class,
            holdId);
    return r.isEmpty() || r.get(0).isEmpty() ? null : r.get(0);
  }

  public void markReplacing(String holdId, String replacesHoldId) {
    jdbc.update(
        "UPDATE capacity_hold SET replaces_hold_id = ? WHERE hold_id = ?", replacesHoldId, holdId);
  }

  public boolean hasActiveForOrder(String orderId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM capacity_hold WHERE order_id = ? AND state IN ('HELD','CONFIRMED')",
            Integer.class,
            orderId);
    return n != null && n > 0;
  }

  /** 창 행을 만들고(없으면) FOR UPDATE 로 잡는다. 같은 창의 점유 판정이 여기서 직렬화된다. */
  public void lockWindow(String mfcId, HourWindow w, Instant now) {
    jdbc.update(
        "INSERT INTO capacity_window (mfc_id, window_start, window_end, version, updated_at)"
            + " VALUES (?, ?, ?, 0, ?) ON CONFLICT (mfc_id, window_start) DO NOTHING",
        mfcId,
        Timestamp.from(w.start()),
        Timestamp.from(w.end()),
        Timestamp.from(now));
    jdbc.queryForObject(
        "SELECT version FROM capacity_window WHERE mfc_id = ? AND window_start = ? FOR UPDATE",
        Integer.class,
        mfcId,
        Timestamp.from(w.start()));
  }

  /** 점유가 바뀌었음을 창 버전에 남긴다(창 행이 있을 때만). */
  public void bumpWindow(String mfcId, HourWindow w, Instant now) {
    jdbc.update(
        "UPDATE capacity_window SET version = version + 1, updated_at = ?"
            + " WHERE mfc_id = ? AND window_start = ?",
        Timestamp.from(now),
        mfcId,
        Timestamp.from(w.start()));
  }

  public Occupancy occupancy(String mfcId, HourWindow w, Instant now) {
    Timestamp start = Timestamp.from(w.start());
    Timestamp at = Timestamp.from(now);
    return jdbc.queryForObject(
        "SELECT"
            + " COALESCE((SELECT sum(units) FROM capacity_hold WHERE mfc_id = ? AND window_start = ?"
            + "   AND state = 'HELD' AND expires_at > ?), 0) AS held,"
            + " COALESCE((SELECT sum(units) FROM capacity_hold WHERE mfc_id = ? AND window_start = ?"
            + "   AND state = 'CONFIRMED'), 0) AS confirmed,"
            + " COALESCE((SELECT version FROM capacity_window WHERE mfc_id = ? AND window_start = ?),"
            + "   0) AS version",
        (rs, i) -> new Occupancy(rs.getInt("held"), rs.getInt("confirmed"), rs.getInt("version")),
        mfcId,
        start,
        at,
        mfcId,
        start,
        mfcId,
        start);
  }

  public void insert(CapacityHold h, String demandJson, Instant now) {
    jdbc.update(
        "INSERT INTO capacity_hold (hold_id, order_id, mfc_id, window_start, window_end, units,"
            + " state, version, demand, expires_at, created_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)",
        h.holdId(),
        h.orderId(),
        h.mfcId(),
        Timestamp.from(h.window().start()),
        Timestamp.from(h.window().end()),
        h.units(),
        h.state().name(),
        h.version(),
        demandJson,
        Timestamp.from(h.expiresAt()),
        Timestamp.from(now));
  }

  public void confirm(String holdId, Instant now) {
    jdbc.update(
        "UPDATE capacity_hold SET state = 'CONFIRMED', version = version + 1, confirmed_at = ?"
            + " WHERE hold_id = ? AND state = 'HELD'",
        Timestamp.from(now),
        holdId);
  }

  /** 종료(RELEASED/EXPIRED). */
  public void close(
      String holdId,
      CapacityHold.State to,
      @Nullable String reason,
      @Nullable String sourceRef,
      Instant now) {
    jdbc.update(
        "UPDATE capacity_hold SET state = ?, version = version + 1, release_reason = ?,"
            + " release_source_ref = ?, closed_at = ? WHERE hold_id = ? AND state IN ('HELD','CONFIRMED')",
        to.name(),
        reason,
        sourceRef,
        Timestamp.from(now),
        holdId);
  }

  private static CapacityHold map(ResultSet rs, int i) throws SQLException {
    return new CapacityHold(
        rs.getString("hold_id"),
        rs.getString("order_id"),
        rs.getString("mfc_id"),
        new HourWindow(
            rs.getTimestamp("window_start").toInstant(), rs.getTimestamp("window_end").toInstant()),
        rs.getInt("units"),
        CapacityHold.State.valueOf(rs.getString("state")),
        rs.getInt("version"),
        rs.getTimestamp("expires_at").toInstant(),
        rs.getString("release_reason"));
  }
}
