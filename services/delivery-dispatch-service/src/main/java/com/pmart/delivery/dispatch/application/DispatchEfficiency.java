package com.pmart.delivery.dispatch.application;

import com.pmart.common.security.PmartPrincipal;
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
 * 배차 효율(2026-11-23, 0.16.0 — 라이더 백오피스 "운영 통계", 사용자 "필요한 기능 … 특히 2번이랑 4번"). 배송 단위, 날짜 = 배송 생성의
 * Asia/Seoul 날짜. 고객 배송(FORWARD·RESHIPMENT)만, 주문 취소로 없어진 배송은 뺀다(정시 배송률과 같은 대상). 값의 뜻은 계약 설명.
 */
@Service
public class DispatchEfficiency {

  static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

  public record Row(
      LocalDate date,
      String mfcId,
      int created,
      int assigned,
      @Nullable Integer avgAssignSeconds,
      int reassigned,
      int failedRounds,
      int autoCancelled,
      int offersSent,
      int offersAccepted,
      int offersRejected,
      int offersExpired,
      int assignedByAi,
      int assignedByList,
      int assignedByFailedReturn) {}

  public record Reason(String reason, int rounds) {}

  public record Result(LocalDate from, LocalDate to, List<Row> rows, List<Reason> reasons) {}

  private final JdbcTemplate jdbc;
  private final Clock clock;

  public DispatchEfficiency(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  public Result get(@Nullable LocalDate from, @Nullable LocalDate to, @Nullable String mfcId) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .filter(x -> x.type() == PmartPrincipal.PrincipalType.OPERATOR)
            .orElseThrow(() -> OfferService.forbidden("직원만 볼 수 있습니다."));
    boolean all = p.hasRole("OPERATOR_ADMIN") || p.hasRole("RIDER_OPS") || p.hasRole("CS_AGENT");
    if (!all && (mfcId == null || mfcId.isBlank() || !p.canAccessCenter(mfcId))) {
      throw OfferService.forbidden("그 센터 직원만 볼 수 있습니다(mfcId 필요).");
    }
    LocalDate today = LocalDate.now(clock.withZone(SEOUL));
    LocalDate f = from != null ? from : (to != null ? to : today);
    LocalDate t = to != null ? to : f;
    if (t.isBefore(f) || ChronoUnit.DAYS.between(f, t) > 30) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "기간은 from ≤ to, 최대 31일입니다.");
    }
    List<Object> args = new ArrayList<>();
    args.add(Timestamp.from(f.atStartOfDay(SEOUL).toInstant()));
    args.add(Timestamp.from(t.plusDays(1).atStartOfDay(SEOUL).toInstant()));
    String byMfc = "";
    if (mfcId != null && !mfcId.isBlank()) {
      byMfc = " AND d.mfc_id = ?";
      args.add(mfcId);
    }
    String target =
        "SELECT d.delivery_id, d.mfc_id, (d.created_at AT TIME ZONE 'Asia/Seoul')::date AS day,"
            + " d.assigned_at, d.created_at, d.auto_cancel_result FROM delivery d WHERE"
            + " d.created_at >= ? AND d.created_at < ? AND d.type IN ('FORWARD', 'RESHIPMENT') AND"
            + " d.mfc_id IS NOT NULL AND NOT (d.state = 'CANCELLED_BEFORE_PICKUP' AND"
            + " d.cancel_reason = 'ORDER_CANCELLED')"
            + byMfc;
    String sql =
        "WITH t AS ("
            + target
            + "), a AS (SELECT delivery_id, min(started_at) AS first_start, count(*) FILTER (WHERE"
            + " state = 'FAILED') AS failed, bool_or(trigger = 'REASSIGN') AS reassigned FROM"
            + " dispatch_attempt WHERE delivery_id IN (SELECT delivery_id FROM t) GROUP BY"
            + " delivery_id), o AS (SELECT delivery_id, count(*) AS sent, count(*) FILTER (WHERE"
            + " state = 'ACCEPTED') AS accepted, count(*) FILTER (WHERE state = 'REJECTED') AS"
            + " rejected, count(*) FILTER (WHERE state = 'EXPIRED') AS expired, count(*) FILTER"
            + " (WHERE state = 'ACCEPTED' AND source = 'AI_RECOMMENDED') AS by_ai, count(*) FILTER"
            + " (WHERE state = 'ACCEPTED' AND source = 'DELIVERY_LIST') AS by_list, count(*) FILTER"
            + " (WHERE state = 'ACCEPTED' AND source = 'FAILED_RETURN') AS by_return FROM"
            + " dispatch_offer WHERE delivery_id IN (SELECT delivery_id FROM t) GROUP BY"
            + " delivery_id) SELECT t.day, t.mfc_id, count(*) AS created, count(t.assigned_at) AS"
            + " assigned, avg(extract(epoch FROM t.assigned_at - COALESCE(a.first_start,"
            + " t.created_at))) FILTER (WHERE t.assigned_at IS NOT NULL) AS avg_assign, count(*)"
            + " FILTER (WHERE a.reassigned) AS reassigned, COALESCE(sum(a.failed), 0) AS failed,"
            + " count(*) FILTER (WHERE t.auto_cancel_result = 'ACCEPTED') AS auto_cancelled,"
            + " COALESCE(sum(o.sent), 0) AS sent, COALESCE(sum(o.accepted), 0) AS accepted,"
            + " COALESCE(sum(o.rejected), 0) AS rejected, COALESCE(sum(o.expired), 0) AS expired,"
            + " COALESCE(sum(o.by_ai), 0) AS by_ai, COALESCE(sum(o.by_list), 0) AS by_list,"
            + " COALESCE(sum(o.by_return), 0) AS by_return FROM t LEFT JOIN a ON a.delivery_id ="
            + " t.delivery_id LEFT JOIN o ON o.delivery_id = t.delivery_id GROUP BY t.day, t.mfc_id"
            + " ORDER BY t.day, t.mfc_id";
    List<Row> rows =
        jdbc.query(
            sql,
            (rs, i) -> {
              double avg = rs.getDouble("avg_assign");
              boolean noAvg = rs.wasNull();
              return new Row(
                  rs.getDate("day").toLocalDate(),
                  rs.getString("mfc_id"),
                  rs.getInt("created"),
                  rs.getInt("assigned"),
                  noAvg ? null : (int) Math.round(Math.max(0, avg)),
                  rs.getInt("reassigned"),
                  rs.getInt("failed"),
                  rs.getInt("auto_cancelled"),
                  rs.getInt("sent"),
                  rs.getInt("accepted"),
                  rs.getInt("rejected"),
                  rs.getInt("expired"),
                  rs.getInt("by_ai"),
                  rs.getInt("by_list"),
                  rs.getInt("by_return"));
            },
            args.toArray());
    List<Reason> reasons =
        jdbc.query(
            "WITH t AS ("
                + target
                + ") SELECT failure_reason, count(*) FROM dispatch_attempt WHERE state = 'FAILED'"
                + " AND failure_reason IS NOT NULL AND delivery_id IN (SELECT delivery_id FROM t)"
                + " GROUP BY failure_reason ORDER BY count(*) DESC, failure_reason",
            (rs, i) -> new Reason(rs.getString(1), rs.getInt(2)),
            args.toArray());
    return new Result(f, t, rows, reasons);
  }
}
