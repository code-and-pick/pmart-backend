package com.pmart.delivery.execution.application;

import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.delivery.execution.domain.EarningRules;
import com.pmart.delivery.execution.infrastructure.RiderClient;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 이의 인정 보상금(2026-11-19 — 사용자 "보상금 한 줄 따로", 원문 §7.1 "이후 보상·조정은 별도 기록"). 원래 수행료는 그대로 두고, 인정된 수행료
 * 이의(rider)에 묶인 한 줄. 이의 하나에 한 번. 바로 확정, 지급 예정일 = 오늘 + 3영업일 → 그날 지급 묶음에 함께({@link PayoutResults}).
 */
@Service
public class Compensations {

  public record Row(
      String compensationId,
      String appealId,
      String deliveryId,
      String riderId,
      int amount,
      String reason,
      String settlementState,
      LocalDate payoutDate,
      @Nullable String recordedBy,
      Instant recordedAt) {}

  private final JdbcTemplate jdbc;
  private final RiderClient riders;
  private final ExecutionProperties props;
  private final TransactionTemplate tx;
  private final Clock clock;

  public Compensations(
      JdbcTemplate jdbc,
      RiderClient riders,
      ExecutionProperties props,
      TransactionTemplate tx,
      Clock clock) {
    this.jdbc = jdbc;
    this.riders = riders;
    this.props = props;
    this.tx = tx;
    this.clock = clock;
  }

  private static PmartPrincipal staff() {
    return PmartPrincipal.current()
        .filter(
            p ->
                p.type() == PmartPrincipal.PrincipalType.OPERATOR
                    && (p.hasRole("OPERATOR_ADMIN") || p.hasRole("RIDER_OPS")))
        .orElseThrow(() -> ExecutionService.forbidden("보상금은 라이더 운영·관리자만 다룹니다."));
  }

  public Row record(String idempotencyKey, String appealId, int amount, String reason) {
    PmartPrincipal p = staff();
    String r = reason.strip();
    if (r.isEmpty() || r.length() > 300 || amount < 1 || amount > 100_000) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "금액은 1~100,000원, 사유는 1~300자입니다.");
    }
    String key = p.subject() + ":" + idempotencyKey;
    Optional<Row> same = one("WHERE request_key = ?", key);
    if (same.isPresent()) {
      return same.get();
    }
    RiderClient.AppealInfo a =
        riders
            .appeal(appealId)
            .orElseThrow(() -> PmartException.notFound("APPEAL_NOT_FOUND", "이의 신청이 없습니다."));
    if (!"EARNING".equals(a.targetType()) || !"ACCEPTED".equals(a.state())) {
      throw new PmartException(
          "APPEAL_NOT_ACCEPTED",
          HttpStatus.UNPROCESSABLE_CONTENT,
          "인정된 수행료 이의에만 보상금을 줄 수 있습니다.",
          false,
          List.of());
    }
    Instant now = clock.instant();
    LocalDate today = LocalDate.ofInstant(now, EarningService.SEOUL);
    LocalDate payout = EarningRules.payoutDate(today, props.payoutBusinessDays(), props.holidays());
    String id = "CMP-" + UUID.randomUUID();
    return tx.execute(
        s -> {
          int n =
              jdbc.update(
                  "INSERT INTO rider_compensation (compensation_id, request_key, appeal_id, delivery_id,"
                      + " rider_id, amount, reason, settlement_state, payout_date, recorded_by,"
                      + " recorded_at, updated_at) VALUES (?,?,?,?,?,?,?,'CONFIRMED',?,?,?,?) ON"
                      + " CONFLICT DO NOTHING",
                  id,
                  key,
                  appealId,
                  a.targetRef(),
                  a.riderId(),
                  amount,
                  r,
                  payout,
                  p.subject(),
                  Timestamp.from(now),
                  Timestamp.from(now));
          if (n == 0) {
            Optional<Row> again = one("WHERE request_key = ?", key);
            if (again.isPresent()) {
              return again.get();
            }
            throw new PmartException(
                "COMPENSATION_EXISTS",
                HttpStatus.CONFLICT,
                "이 이의에는 이미 보상금이 있습니다.",
                false,
                List.of());
          }
          return one("WHERE compensation_id = ?", id).orElseThrow();
        });
  }

  public List<Row> forStaff(
      @Nullable String riderId, @Nullable String deliveryId, @Nullable String appealId) {
    staff();
    StringBuilder w = new StringBuilder("WHERE true");
    List<Object> args = new ArrayList<>();
    if (riderId != null) {
      w.append(" AND rider_id = ?");
      args.add(riderId);
    }
    if (deliveryId != null) {
      w.append(" AND delivery_id = ?");
      args.add(deliveryId);
    }
    if (appealId != null) {
      w.append(" AND appeal_id = ?");
      args.add(appealId);
    }
    return list(w + " ORDER BY recorded_at DESC LIMIT 200", args.toArray());
  }

  public List<Row> mine() {
    PmartPrincipal p =
        PmartPrincipal.current()
            .filter(x -> x.type() == PmartPrincipal.PrincipalType.RIDER)
            .orElseThrow(() -> ExecutionService.forbidden("라이더만 볼 수 있습니다."));
    return list("WHERE rider_id = ? ORDER BY recorded_at DESC LIMIT 200", p.subject()).stream()
        .map(
            c ->
                new Row(
                    c.compensationId(),
                    c.appealId(),
                    c.deliveryId(),
                    c.riderId(),
                    c.amount(),
                    c.reason(),
                    c.settlementState(),
                    c.payoutDate(),
                    null,
                    c.recordedAt()))
        .toList();
  }

  private Optional<Row> one(String where, Object... args) {
    return list(where, args).stream().findFirst();
  }

  private List<Row> list(String where, Object... args) {
    return jdbc.query("SELECT * FROM rider_compensation " + where, Compensations::row, args);
  }

  private static Row row(ResultSet rs, int i) throws SQLException {
    return new Row(
        rs.getString("compensation_id"),
        rs.getString("appeal_id"),
        rs.getString("delivery_id"),
        rs.getString("rider_id"),
        rs.getInt("amount"),
        rs.getString("reason"),
        rs.getString("settlement_state"),
        rs.getDate("payout_date").toLocalDate(),
        rs.getString("recorded_by"),
        rs.getTimestamp("recorded_at").toInstant());
  }
}
