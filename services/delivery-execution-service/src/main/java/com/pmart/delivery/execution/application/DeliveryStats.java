package com.pmart.delivery.execution.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 라이더 백오피스 대시보드 배송 수(2026-11-17 — 사용자 "대시보드에 필요한 것만"). 범위는 직원 배송 찾기와
 * 같다(`Access.requireStaffScope`).
 */
@Service
public class DeliveryStats {

  static final List<String> OPEN_STATES =
      List.of(
          "UNASSIGNED",
          "ASSIGNED",
          "ARRIVED_AT_STORE",
          "PICKED_UP",
          "ARRIVED_AT_CUSTOMER",
          "FAILURE_REPORTED",
          "RETURN_PICKED_UP");

  public record Stats(
      Map<String, Integer> inProgress,
      int completedToday,
      int failedToday,
      int openHelpRequests,
      int adjustmentPending,
      int surchargeRequested,
      Instant asOf) {}

  private final JdbcTemplate jdbc;
  private final Clock clock;

  public DeliveryStats(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  public Stats stats(@Nullable String mfcId) {
    Access.requireStaffScope(mfcId);
    String center = mfcId == null || mfcId.isBlank() ? null : mfcId.strip();
    Instant now = clock.instant();
    ZoneId seoul = ZoneId.of("Asia/Seoul");
    Timestamp dayStart =
        Timestamp.from(LocalDate.now(clock.withZone(seoul)).atStartOfDay(seoul).toInstant());
    Map<String, Integer> open = new LinkedHashMap<>();
    OPEN_STATES.forEach(s -> open.put(s, 0));
    jdbc.query(
        "SELECT state, count(*) FROM execution_delivery WHERE state IN ("
            + String.join(",", OPEN_STATES.stream().map(x -> "'" + x + "'").toList())
            + ") AND (CAST(? AS varchar) IS NULL OR mfc_id = ?) GROUP BY state",
        rs -> {
          open.put(rs.getString(1), rs.getInt(2));
        },
        center,
        center);
    Integer completed =
        jdbc.queryForObject(
            "SELECT count(*) FROM execution_delivery WHERE state IN ('COMPLETED', 'RETURNED_TO_STORE')"
                + " AND completed_at >= ? AND (CAST(? AS varchar) IS NULL OR mfc_id = ?)",
            Integer.class,
            dayStart,
            center,
            center);
    Integer failed =
        jdbc.queryForObject(
            "SELECT count(*) FROM execution_delivery WHERE state = 'FAILED' AND updated_at >= ? AND"
                + " (CAST(? AS varchar) IS NULL OR mfc_id = ?)",
            Integer.class,
            dayStart,
            center,
            center);
    Integer helps =
        jdbc.queryForObject(
            "SELECT count(*) FROM help_request h JOIN execution_delivery d ON d.delivery_id ="
                + " h.delivery_id WHERE h.state IN ('OPEN', 'IN_PROGRESS') AND (CAST(? AS varchar) IS"
                + " NULL OR d.mfc_id = ?)",
            Integer.class,
            center,
            center);
    Integer adjust =
        jdbc.queryForObject(
            "SELECT count(*) FROM rider_earning WHERE NOT calculable AND settlement_state NOT IN"
                + " ('ADJUSTED', 'PAYING', 'PAID')",
            Integer.class);
    Integer surcharge =
        jdbc.queryForObject(
            "SELECT count(*) FROM surcharge_request WHERE state = 'REQUESTED'", Integer.class);
    return new Stats(
        open,
        completed == null ? 0 : completed,
        failed == null ? 0 : failed,
        helps == null ? 0 : helps,
        adjust == null ? 0 : adjust,
        surcharge == null ? 0 : surcharge,
        now);
  }
}
