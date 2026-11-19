package com.pmart.delivery.execution.application;

import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 직원 조치 이력(2026-11-18 — 사용자 "이미 기록된 조치를 모아 보기", 관리자만). 이 서비스에 이미 남은 기록을 한 모양으로: 상태
 * 정정(delivery_status_correction), 전달 실패 확정·반려(배송 failure 기록의 결정자), 수행료 조정(earning_adjustment), 추가
 * 배달료 결정(surcharge_request). 라이더 백오피스가 서비스마다 부르고 시각순으로 합친다. 새로 기록하지 않는다(읽기만).
 */
@Service
public class StaffActions {

  /** 조치 한 줄. */
  public record Row(
      Instant at,
      String actorId,
      String action,
      @Nullable String riderId,
      @Nullable String deliveryId,
      @Nullable String detail,
      @Nullable String reason) {}

  static final Duration DEFAULT_SPAN = Duration.ofDays(7);
  static final Duration MAX_SPAN = Duration.ofDays(31);

  private static final String SQL =
"""
      SELECT * FROM (
  SELECT c.corrected_at AS at, c.corrected_by AS actor, 'STATUS_CORRECTED' AS action, d.rider_id, c.delivery_id,
         c.from_state || '→' || c.to_state AS detail, c.reason
    FROM delivery_status_correction c LEFT JOIN execution_delivery d ON d.delivery_id = c.delivery_id
  UNION ALL
  SELECT CAST(failure->>'decidedAt' AS timestamptz), failure->>'decidedBy', 'FAILURE_' || (failure->>'decision'), rider_id, delivery_id,
         failure->>'reason', failure->>'decisionNote'
    FROM execution_delivery WHERE failure IS NOT NULL AND failure->>'decidedBy' IS NOT NULL
  UNION ALL
  SELECT adjusted_at, adjusted_by, 'EARNING_ADJUSTED', rider_id, delivery_id, fault || ' ' || decision || ' ' || amount || '원', reason
    FROM earning_adjustment
  UNION ALL
  SELECT decided_at, decided_by, 'SURCHARGE_' || state, rider_id, delivery_id, reason || ' ' || floor || '층 ' || amount || '원', NULL
    FROM surcharge_request WHERE decided_at IS NOT NULL
  UNION ALL
  SELECT recorded_at, recorded_by, 'PAYOUT_' || result, rider_id, NULL, payout_date || ' ' || earning_count || '건 ' || total || '원', failure_reason
    FROM rider_payout_result
  UNION ALL
  SELECT recorded_at, recorded_by, 'COMPENSATION_RECORDED', rider_id, delivery_id, amount || '원 ' || appeal_id, reason
    FROM rider_compensation
) a
      """;

  private final JdbcTemplate jdbc;
  private final Clock clock;

  public StaffActions(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  public List<Row> list(
      @Nullable String operatorId,
      @Nullable String riderId,
      @Nullable String deliveryId,
      @Nullable Instant from,
      @Nullable Instant to,
      int limit,
      int offset) {
    PmartPrincipal.current()
        .filter(
            p -> p.type() == PmartPrincipal.PrincipalType.OPERATOR && p.hasRole("OPERATOR_ADMIN"))
        .orElseThrow(
            () ->
                new PmartException(
                    ErrorCode.FORBIDDEN.name(),
                    HttpStatus.FORBIDDEN,
                    "직원 조치 이력은 관리자만 볼 수 있습니다.",
                    false,
                    List.of()));
    Instant t = to != null ? to : clock.instant();
    Instant f = from != null ? from : t.minus(DEFAULT_SPAN);
    if (!f.isBefore(t) || Duration.between(f, t).compareTo(MAX_SPAN) > 0) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "기간은 from < to, 최대 31일입니다.");
    }
    StringBuilder sql =
        new StringBuilder(SQL).append(" WHERE a.at >= ? AND a.at < ? AND a.actor IS NOT NULL");
    List<Object> args = new ArrayList<>();
    args.add(Timestamp.from(f));
    args.add(Timestamp.from(t));
    if (operatorId != null) {
      sql.append(" AND a.actor = ?");
      args.add(operatorId);
    }
    if (riderId != null) {
      sql.append(" AND a.rider_id = ?");
      args.add(riderId);
    }
    if (deliveryId != null) {
      sql.append(" AND a.delivery_id = ?");
      args.add(deliveryId);
    }
    sql.append(" ORDER BY a.at DESC LIMIT ? OFFSET ?");
    args.add(limit);
    args.add(offset);
    return jdbc.query(
        sql.toString(),
        (rs, i) ->
            new Row(
                rs.getTimestamp("at").toInstant(),
                rs.getString("actor"),
                rs.getString("action"),
                rs.getString("rider_id"),
                rs.getString("delivery_id"),
                rs.getString("detail"),
                rs.getString("reason")),
        args.toArray());
  }
}
