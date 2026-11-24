package com.pmart.delivery.dispatch.application;

import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * DD-4 정시 배송률(2026-11-19 — 사용자 결정 "PC 2 추천대로"). 배송 단위, 날짜 = 약속 끝(promised_at)의 Asia/Seoul 날짜.
 * onTime = 완료 ≤ 약속 끝, late = 약속 끝이 지났는데 정시가 아님(전달 전·실패 포함), unjudged = 고객 수령 확인·자동 완료, pending = 아직
 * 약속 전·전달 전. 주문 취소로 없어진 배송만 뺀다. (2026-11-25 DD-6) 칸 단위 HOUR·DAY·WEEK·MONTH — 칸 = 약속 끝을 그 단위로 내린
 * 값(date_trunc, Asia/Seoul).
 */
@Service
public class OnTimeMetrics {

  static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

  /** 칸 단위(DD-6) — 기간 상한 HOUR 7일(168칸), DAY 31일(예전 그대로), WEEK·MONTH 366일(1년 보기 한 번에). */
  public enum Bucket {
    HOUR(7),
    DAY(31),
    WEEK(366),
    MONTH(366);

    final int maxDays;

    Bucket(int maxDays) {
      this.maxDays = maxDays;
    }
  }

  /** start = 칸 시작(Asia/Seoul 벽시계 — DAY 는 그날 0시, WEEK 는 월요일, MONTH 는 1일). */
  public record Row(
      LocalDateTime start, String mfcId, int due, int onTime, int late, int unjudged, int pending) {
    public LocalDate date() {
      return start.toLocalDate();
    }
  }

  private final JdbcTemplate jdbc;
  private final Clock clock;

  public OnTimeMetrics(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  public List<Row> list(
      @Nullable LocalDate from, @Nullable LocalDate to, @Nullable String mfcId, Bucket bucket) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .filter(x -> x.type() == PmartPrincipal.PrincipalType.OPERATOR)
            .orElseThrow(() -> OfferService.forbidden("직원만 볼 수 있습니다."));
    if (!p.hasRole("OPERATOR_ADMIN")
        && (mfcId == null || mfcId.isBlank() || !p.canAccessCenter(mfcId))) {
      throw OfferService.forbidden("그 센터 직원만 볼 수 있습니다(mfcId 필요).");
    }
    LocalDate today = LocalDate.now(clock.withZone(SEOUL));
    LocalDate f = from != null ? from : (to != null ? to : today);
    LocalDate t = to != null ? to : f;
    if (t.isBefore(f) || ChronoUnit.DAYS.between(f, t) >= bucket.maxDays) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED,
          "기간은 from ≤ to, " + bucket + " 은 최대 " + bucket.maxDays + "일입니다.");
    }
    Timestamp start = Timestamp.from(f.atStartOfDay(SEOUL).toInstant());
    Timestamp end = Timestamp.from(t.plusDays(1).atStartOfDay(SEOUL).toInstant());
    Timestamp now = Timestamp.from(clock.instant());
    List<Object> args = new ArrayList<>(List.of(now, start, end));
    String byMfc = "";
    if (mfcId != null && !mfcId.isBlank()) {
      byMfc = " AND mfc_id = ?";
      args.add(mfcId);
    }
    String sql =
        "SELECT d, mfc_id, count(*) FILTER (WHERE verdict = 'ON_TIME') AS on_time, count(*)"
            + " FILTER (WHERE verdict = 'LATE') AS late, count(*) FILTER (WHERE verdict ="
            + " 'UNJUDGED') AS unjudged, count(*) FILTER (WHERE verdict = 'PENDING') AS pending"
            + " FROM (SELECT date_trunc('"
            + bucket.name().toLowerCase(Locale.ROOT)
            + "', promised_at AT TIME ZONE 'Asia/Seoul') AS d, mfc_id, CASE"
            + " WHEN completed_at IS NOT NULL AND completion_method IN ('CUSTOMER_CONFIRMED',"
            + " 'AUTO_COMPLETED') THEN 'UNJUDGED' WHEN completed_at IS NOT NULL AND completed_at <="
            + " promised_at THEN 'ON_TIME' WHEN promised_at < ? THEN 'LATE' WHEN completed_at IS"
            + " NOT NULL THEN 'LATE' ELSE 'PENDING' END AS verdict FROM delivery WHERE"
            + " promised_at >= ? AND promised_at < ? AND type IN ('FORWARD', 'RESHIPMENT') AND"
            + " mfc_id IS NOT NULL AND NOT (state = 'CANCELLED_BEFORE_PICKUP' AND cancel_reason ="
            + " 'ORDER_CANCELLED')"
            + byMfc
            + ") x GROUP BY d, mfc_id ORDER BY d, mfc_id";
    return jdbc.query(
        sql,
        (rs, i) -> {
          int onTime = rs.getInt("on_time");
          int late = rs.getInt("late");
          return new Row(
              rs.getObject("d", LocalDateTime.class),
              rs.getString("mfc_id"),
              onTime + late,
              onTime,
              late,
              rs.getInt("unjudged"),
              rs.getInt("pending"));
        },
        args.toArray());
  }
}
