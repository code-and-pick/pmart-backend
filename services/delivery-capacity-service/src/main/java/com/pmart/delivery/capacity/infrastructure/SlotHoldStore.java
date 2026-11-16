package com.pmart.delivery.capacity.infrastructure;

import com.pmart.delivery.capacity.domain.SlotHold;
import com.pmart.delivery.capacity.domain.SlotRules;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 슬롯 점유·슬롯 잠금 행·확정/해제 명령 결과 저장(JDBC, V2__slot_holds.sql). */
@Repository
public class SlotHoldStore {

  /** 슬롯별 점유 수. */
  public record Occupancy(int held, int confirmed) {}

  /** 확정·해제 명령 결과(결과 조회용). */
  public record CommandRow(
      String holdId,
      String kind,
      String requestId,
      String status,
      String resultingState,
      int version,
      Instant occurredAt) {}

  private static final String COLUMNS =
      "hold_id, version, order_id, draft_id, saga_id, mfc_id, slot_date, start_minute, end_minute,"
          + " state, held_at, expires_at, confirmed_at, released_at, release_reason";

  /** 정원을 차지하는 점유: CONFIRMED 와 만료 전 HELD. */
  private static final String OCCUPYING =
      "(state = 'CONFIRMED' OR (state = 'HELD' AND expires_at > ?))";

  private final JdbcTemplate jdbc;

  /** 확정된 도착 시간대(CP-1 — 영향 주문 고객 안내). */
  public record ConfirmedSlot(
      String orderId, String mfcId, java.time.LocalDate date, int startMinute, int endMinute) {}

  public SlotHoldStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** (센터, 날짜, 시작) 잠금 행을 만들고(없으면) 트랜잭션 끝까지 잡는다. 같은 슬롯 점유는 여기서 줄을 선다. */
  public void lockSlot(String mfcId, LocalDate date, int startMinute) {
    jdbc.update(
        "INSERT INTO slot_lock (mfc_id, slot_date, start_minute) VALUES (?, ?, ?)"
            + " ON CONFLICT DO NOTHING",
        mfcId,
        Date.valueOf(date),
        startMinute);
    jdbc.queryForObject(
        "SELECT 1 FROM slot_lock WHERE mfc_id = ? AND slot_date = ? AND start_minute = ?"
            + " FOR UPDATE",
        Integer.class,
        mfcId,
        Date.valueOf(date),
        startMinute);
  }

  /** 한 슬롯의 정원 사용 수(HELD 만료 전 + CONFIRMED). */
  public int occupying(String mfcId, LocalDate date, int startMinute, Instant now) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM slot_hold WHERE mfc_id = ? AND slot_date = ? AND start_minute = ?"
                + " AND "
                + OCCUPYING,
            Integer.class,
            mfcId,
            Date.valueOf(date),
            startMinute,
            Timestamp.from(now));
    return n == null ? 0 : n;
  }

  /** 날짜의 슬롯별 점유 수(시작 분 → 수). */
  public Map<Integer, Occupancy> occupancy(String mfcId, LocalDate date, Instant now) {
    Map<Integer, Occupancy> out = new HashMap<>();
    jdbc.query(
        "SELECT start_minute, count(*) FILTER (WHERE state = 'HELD') AS held,"
            + " count(*) FILTER (WHERE state = 'CONFIRMED') AS confirmed"
            + " FROM slot_hold WHERE mfc_id = ? AND slot_date = ? AND "
            + OCCUPYING
            + " GROUP BY start_minute",
        rs -> {
          out.put(
              rs.getInt("start_minute"), new Occupancy(rs.getInt("held"), rs.getInt("confirmed")));
        },
        mfcId,
        Date.valueOf(date),
        Timestamp.from(now));
    return out;
  }

  /** 주문의 만료 시각이 지난 HELD 를 EXPIRED 로(정리 작업 전에 같은 주문이 다시 점유할 때 활성 1건 색인에 걸리지 않게). */
  public void expireStaleForOrder(String orderId, Instant now) {
    jdbc.update(
        "UPDATE slot_hold SET state = 'EXPIRED', version = version + 1"
            + " WHERE order_id = ? AND state = 'HELD' AND expires_at <= ?",
        orderId,
        Timestamp.from(now));
  }

  public Optional<String> activeHoldIdForOrder(String orderId) {
    return jdbc
        .query(
            "SELECT hold_id FROM slot_hold WHERE order_id = ? AND state IN ('HELD', 'CONFIRMED')",
            (rs, i) -> rs.getString(1),
            orderId)
        .stream()
        .findFirst();
  }

  public void insert(SlotHold h, String sourceSystem) {
    jdbc.update(
        "INSERT INTO slot_hold ("
            + COLUMNS
            + ", source_system) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        h.holdId(),
        h.version(),
        h.orderId(),
        h.draftId(),
        h.sagaId(),
        h.mfcId(),
        Date.valueOf(h.window().date()),
        h.window().startMinute(),
        h.window().endMinute(),
        h.state().name(),
        Timestamp.from(h.heldAt()),
        Timestamp.from(h.expiresAt()),
        ts(h.confirmedAt()),
        ts(h.releasedAt()),
        h.releaseReason(),
        sourceSystem);
  }

  /** 점유 조회. {@code forUpdate} 면 행을 잠근다(확정·해제·만료 정리와 직렬화). */
  public Optional<SlotHold> find(String holdId, boolean forUpdate) {
    return jdbc
        .query(
            "SELECT "
                + COLUMNS
                + " FROM slot_hold WHERE hold_id = ?"
                + (forUpdate ? " FOR UPDATE" : ""),
            SlotHoldStore::map,
            holdId)
        .stream()
        .findFirst();
  }

  public void markConfirmed(String holdId, int newVersion, Instant at) {
    jdbc.update(
        "UPDATE slot_hold SET state = 'CONFIRMED', version = ?, confirmed_at = ? WHERE hold_id = ?",
        newVersion,
        Timestamp.from(at),
        holdId);
  }

  public void markReleased(
      String holdId, int newVersion, Instant at, String reason, @Nullable String approvalRef) {
    jdbc.update(
        "UPDATE slot_hold SET state = 'RELEASED', version = ?, released_at = ?, release_reason = ?,"
            + " approval_ref = ? WHERE hold_id = ?",
        newVersion,
        Timestamp.from(at),
        reason,
        approvalRef,
        holdId);
  }

  /** 만료 시각이 지난 HELD 를 잠가 EXPIRED 로 바꾼다(여러 인스턴스는 SKIP LOCKED 로 나눠 잡는다). 바꾼 수. */
  public int expireDue(Instant now, int limit) {
    List<String> ids =
        jdbc.query(
            "SELECT hold_id FROM slot_hold WHERE state = 'HELD' AND expires_at <= ?"
                + " ORDER BY expires_at LIMIT ? FOR UPDATE SKIP LOCKED",
            (rs, i) -> rs.getString(1),
            Timestamp.from(now),
            limit);
    for (String id : ids) {
      jdbc.update(
          "UPDATE slot_hold SET state = 'EXPIRED', version = version + 1 WHERE hold_id = ?", id);
    }
    return ids.size();
  }

  // ---------- 명령 결과 ----------

  public void insertCommand(CommandRow c) {
    jdbc.update(
        "INSERT INTO slot_hold_command (hold_id, kind, request_id, status, resulting_state, version,"
            + " occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
        c.holdId(),
        c.kind(),
        c.requestId(),
        c.status(),
        c.resultingState(),
        c.version(),
        Timestamp.from(c.occurredAt()));
  }

  public Optional<CommandRow> findCommand(String holdId, String kind, String requestId) {
    return jdbc
        .query(
            "SELECT hold_id, kind, request_id, status, resulting_state, version, occurred_at"
                + " FROM slot_hold_command WHERE hold_id = ? AND kind = ? AND request_id = ?",
            (rs, i) ->
                new CommandRow(
                    rs.getString("hold_id"),
                    rs.getString("kind"),
                    rs.getString("request_id"),
                    rs.getString("status"),
                    rs.getString("resulting_state"),
                    rs.getInt("version"),
                    rs.getTimestamp("occurred_at").toInstant()),
            holdId,
            kind,
            requestId)
        .stream()
        .findFirst();
  }

  private static SlotHold map(ResultSet rs, int i) throws SQLException {
    return new SlotHold(
        rs.getString("hold_id"),
        rs.getInt("version"),
        rs.getString("order_id"),
        rs.getString("draft_id"),
        rs.getString("saga_id"),
        rs.getString("mfc_id"),
        new SlotRules.Window(
            rs.getDate("slot_date").toLocalDate(),
            rs.getInt("start_minute"),
            rs.getInt("end_minute")),
        SlotHold.State.valueOf(rs.getString("state")),
        rs.getTimestamp("held_at").toInstant(),
        rs.getTimestamp("expires_at").toInstant(),
        instant(rs.getTimestamp("confirmed_at")),
        instant(rs.getTimestamp("released_at")),
        rs.getString("release_reason"));
  }

  private static @Nullable Timestamp ts(@Nullable Instant i) {
    return i == null ? null : Timestamp.from(i);
  }

  private static @Nullable Instant instant(@Nullable Timestamp t) {
    return t == null ? null : t.toInstant();
  }

  /** 이 주문들의 확정 점유(도착 시간대). 점유가 없는 주문(바로 배송 등)은 빠진다. */
  public java.util.List<ConfirmedSlot> confirmedFor(java.util.Collection<String> orderIds) {
    if (orderIds.isEmpty()) {
      return java.util.List.of();
    }
    return jdbc.query(
        "SELECT order_id, mfc_id, slot_date, start_minute, end_minute FROM slot_hold WHERE state ="
            + " 'CONFIRMED' AND order_id = ANY (?) ORDER BY slot_date, start_minute, order_id",
        (rs, n) ->
            new ConfirmedSlot(
                rs.getString("order_id"),
                rs.getString("mfc_id"),
                rs.getObject("slot_date", java.time.LocalDate.class),
                rs.getInt("start_minute"),
                rs.getInt("end_minute")),
        (Object) orderIds.toArray(String[]::new));
  }
}
