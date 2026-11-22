package com.pmart.delivery.execution.application;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 배송 품질·효율·사고 통계(2026-11-23, 0.38.0 — 라이더 백오피스 "운영 통계"·라이더 상세 "성과"). 고객 배송(FORWARD·RESHIPMENT), 날짜
 * = 배정 시각의 Asia/Seoul 날짜. 고객 수령 확인·자동 완료는 전달 시각이 실제가 아니라 지연·배송 소요에서 뺀다(정시 배송률 규칙). 값의 뜻은 계약 설명.
 */
@Service
public class DeliveryMetrics {

  static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

  public record Row(
      LocalDate date,
      String mfcId,
      String riderId,
      int assigned,
      int completed,
      int failed,
      int cancelled,
      int late,
      int judged,
      @Nullable Integer pickupWaitSeconds,
      int pickupWaitCount,
      @Nullable Integer deliverySeconds,
      int deliveryCount) {}

  public record Incident(
      String category, int count, int resolved, @Nullable Integer resolveSeconds) {}

  public record Result(LocalDate from, LocalDate to, List<Row> rows, List<Incident> incidents) {}

  private final JdbcTemplate jdbc;
  private final Clock clock;

  public DeliveryMetrics(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  public Result get(
      @Nullable LocalDate from,
      @Nullable LocalDate to,
      @Nullable String mfcId,
      @Nullable String riderId) {
    String mfc = mfcId == null || mfcId.isBlank() ? null : mfcId.strip();
    Access.requireStaffScope(mfc);
    LocalDate today = LocalDate.now(clock.withZone(SEOUL));
    LocalDate t = to != null ? to : (from != null ? from.plusDays(6) : today);
    LocalDate f = from != null ? from : t.minusDays(6);
    if (t.isBefore(f) || ChronoUnit.DAYS.between(f, t) > 30) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "기간은 from ≤ to, 최대 31일입니다.");
    }
    List<Object> args = new ArrayList<>();
    args.add(Timestamp.from(f.atStartOfDay(SEOUL).toInstant()));
    args.add(Timestamp.from(t.plusDays(1).atStartOfDay(SEOUL).toInstant()));
    StringBuilder where =
        new StringBuilder(
            " d.assigned_at >= ? AND d.assigned_at < ? AND d.type IN ('FORWARD', 'RESHIPMENT') AND"
                + " d.mfc_id IS NOT NULL AND d.rider_id IS NOT NULL");
    if (mfc != null) {
      where.append(" AND d.mfc_id = ?");
      args.add(mfc);
    }
    if (riderId != null && !riderId.isBlank()) {
      where.append(" AND d.rider_id = ?");
      args.add(riderId.strip());
    }
    String real = "(d.method IS NULL OR d.method NOT IN ('CUSTOMER_CONFIRMED', 'AUTO_COMPLETED'))";
    String sql =
        "WITH t AS (SELECT d.*, (d.assigned_at AT TIME ZONE 'Asia/Seoul')::date AS day, (SELECT"
            + " min(h.occurred_at) FROM execution_history h WHERE h.delivery_id = d.delivery_id"
            + " AND h.state = 'ARRIVED_AT_STORE') AS arrived_store_at FROM execution_delivery d"
            + " WHERE"
            + where
            + ") SELECT day, mfc_id, rider_id, count(*) AS assigned, count(*) FILTER (WHERE state ="
            + " 'COMPLETED') AS completed, count(*) FILTER (WHERE state = 'FAILED') AS failed,"
            + " count(*) FILTER (WHERE state = 'CANCELLED') AS cancelled, count(*) FILTER (WHERE"
            + " state = 'COMPLETED' AND completed_at IS NOT NULL AND promised_at IS NOT NULL AND"
            + " "
            + real.replace("d.", "")
            + " AND completed_at > promised_at) AS late, count(*) FILTER (WHERE state ="
            + " 'COMPLETED' AND completed_at IS NOT NULL AND promised_at IS NOT NULL AND "
            + real.replace("d.", "")
            + ") AS judged, avg(extract(epoch FROM picked_up_at - arrived_store_at)) FILTER (WHERE"
            + " picked_up_at IS NOT NULL AND arrived_store_at IS NOT NULL AND picked_up_at >="
            + " arrived_store_at) AS pickup_wait, count(*) FILTER (WHERE picked_up_at IS NOT NULL"
            + " AND arrived_store_at IS NOT NULL AND picked_up_at >= arrived_store_at) AS"
            + " pickup_wait_n, avg(extract(epoch FROM completed_at - picked_up_at)) FILTER (WHERE"
            + " state = 'COMPLETED' AND completed_at IS NOT NULL AND picked_up_at IS NOT NULL AND"
            + " completed_at >= picked_up_at AND "
            + real.replace("d.", "")
            + ") AS delivery_s, count(*) FILTER (WHERE state = 'COMPLETED' AND completed_at IS NOT"
            + " NULL AND picked_up_at IS NOT NULL AND completed_at >= picked_up_at AND "
            + real.replace("d.", "")
            + ") AS delivery_n FROM t GROUP BY day, mfc_id, rider_id ORDER BY day, mfc_id,"
            + " rider_id";
    List<Row> rows =
        jdbc.query(
            sql,
            (rs, i) ->
                new Row(
                    rs.getDate("day").toLocalDate(),
                    rs.getString("mfc_id"),
                    rs.getString("rider_id"),
                    rs.getInt("assigned"),
                    rs.getInt("completed"),
                    rs.getInt("failed"),
                    rs.getInt("cancelled"),
                    rs.getInt("late"),
                    rs.getInt("judged"),
                    seconds(rs, "pickup_wait"),
                    rs.getInt("pickup_wait_n"),
                    seconds(rs, "delivery_s"),
                    rs.getInt("delivery_n")),
            args.toArray());
    List<Incident> incidents =
        jdbc.query(
            "SELECT h.category, count(*), count(*) FILTER (WHERE h.state = 'RESOLVED'),"
                + " avg(extract(epoch FROM h.resolved_at - h.created_at)) FILTER (WHERE"
                + " h.resolved_at IS NOT NULL) FROM help_request h JOIN execution_delivery d ON"
                + " d.delivery_id = h.delivery_id WHERE"
                + where
                + " GROUP BY h.category ORDER BY count(*) DESC, h.category",
            (rs, i) -> {
              double avg = rs.getDouble(4);
              boolean none = rs.wasNull();
              return new Incident(
                  rs.getString(1),
                  rs.getInt(2),
                  rs.getInt(3),
                  none ? null : (int) Math.round(Math.max(0, avg)));
            },
            args.toArray());
    return new Result(f, t, rows, incidents);
  }

  private static @Nullable Integer seconds(java.sql.ResultSet rs, String col)
      throws java.sql.SQLException {
    double v = rs.getDouble(col);
    return rs.wasNull() ? null : (int) Math.round(Math.max(0, v));
  }
}
