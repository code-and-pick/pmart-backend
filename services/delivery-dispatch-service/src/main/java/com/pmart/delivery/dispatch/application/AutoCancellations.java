package com.pmart.delivery.dispatch.application;

import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 배차 불가 자동 취소 내역(2026-11-24, 0.17.0 — 사용자 "자동 취소한 것도 라이더 백오피스에서 확인"). {@link DispatchEngine}
 * autoCancelNoRider 가 남긴 결과(auto_cancel_at·auto_cancel_result)를 건별로. 결과 CANCELLED = claim 이
 * 받음(ACCEPTED), BLOCKED = claim 이 거절해 다시 부르지 않은 것(MFC 관리자 수동 취소 필요). BLOCKED_OPEN = 막혔고 아직 배차
 * 대기(날짜와 무관 — 지금 볼 것). 권한은 배차 효율과 같다.
 */
@Service
public class AutoCancellations {

  static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
  static final int LIMIT = 200;

  public enum Filter {
    ALL,
    CANCELLED,
    BLOCKED,
    BLOCKED_OPEN
  }

  public record Item(
      String deliveryId,
      @Nullable String orderId,
      String mfcId,
      @Nullable String deliveryNumber,
      @Nullable Instant promisedAt,
      Instant attemptedAt,
      String result,
      @Nullable String rejectCode,
      String deliveryState) {}

  public record Result(LocalDate from, LocalDate to, List<Item> items) {}

  private final JdbcTemplate jdbc;
  private final Clock clock;

  public AutoCancellations(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  public Result list(
      @Nullable LocalDate from, @Nullable LocalDate to, @Nullable String mfcId, Filter filter) {
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
    StringBuilder sql =
        new StringBuilder(
            "SELECT delivery_id, order_id, mfc_id, order_summary->>'orderNumber', delivery_seq,"
                + " promised_at, auto_cancel_at, auto_cancel_result, state FROM delivery WHERE"
                + " auto_cancel_at IS NOT NULL AND auto_cancel_result IS NOT NULL");
    List<Object> args = new ArrayList<>();
    if (filter == Filter.BLOCKED_OPEN) {
      sql.append(" AND auto_cancel_result <> 'ACCEPTED' AND state = 'DISPATCHING'");
    } else {
      sql.append(" AND auto_cancel_at >= ? AND auto_cancel_at < ?");
      args.add(Timestamp.from(f.atStartOfDay(SEOUL).toInstant()));
      args.add(Timestamp.from(t.plusDays(1).atStartOfDay(SEOUL).toInstant()));
      if (filter == Filter.CANCELLED) {
        sql.append(" AND auto_cancel_result = 'ACCEPTED'");
      } else if (filter == Filter.BLOCKED) {
        sql.append(" AND auto_cancel_result <> 'ACCEPTED'");
      }
    }
    if (mfcId != null && !mfcId.isBlank()) {
      sql.append(" AND mfc_id = ?");
      args.add(mfcId);
    }
    sql.append(" ORDER BY auto_cancel_at DESC LIMIT ").append(LIMIT);
    List<Item> items =
        jdbc.query(
            sql.toString(),
            (rs, i) -> {
              String raw = rs.getString(8);
              boolean accepted = "ACCEPTED".equals(raw);
              Timestamp promised = rs.getTimestamp(6);
              return new Item(
                  rs.getString(1),
                  rs.getString(2),
                  rs.getString(3),
                  RiderAssignmentHistory.deliveryNumber(
                      rs.getString(4), rs.getObject(5, Integer.class)),
                  promised == null ? null : promised.toInstant(),
                  rs.getTimestamp(7).toInstant(),
                  accepted ? "CANCELLED" : "BLOCKED",
                  accepted ? null : raw,
                  rs.getString(9));
            },
            args.toArray());
    return new Result(f, t, items);
  }
}
