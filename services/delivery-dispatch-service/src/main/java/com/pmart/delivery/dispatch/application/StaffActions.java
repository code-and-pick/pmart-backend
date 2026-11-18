package com.pmart.delivery.dispatch.application;

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
 * 직원 조치 이력(2026-11-18 — 사용자 "이미 기록된 조치를 모아 보기", 관리자만). 이 서비스에 이미 남은 기록을 한 모양으로: 운영자 배차
 * 취소(assignment_cancellation — requested_by OPERATOR). 라이더 백오피스가 서비스마다 부르고 시각순으로 합친다. 새로 기록하지
 * 않는다(읽기만).
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
  SELECT created_at AS at, actor, 'ASSIGNMENT_CANCELLED' AS action, previous_rider_id AS rider_id, delivery_id,
         reason || ' ' || state || CASE WHEN reassign THEN ' 다시 배차' ELSE '' END AS detail, reason_detail AS reason
    FROM assignment_cancellation WHERE requested_by = 'OPERATOR'
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
