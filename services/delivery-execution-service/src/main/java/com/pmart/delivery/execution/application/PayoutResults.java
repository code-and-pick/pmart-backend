package com.pmart.delivery.execution.application;

import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.delivery.execution.infrastructure.ExecutionStore;
import com.pmart.delivery.execution.infrastructure.ExecutionStore.EarningRow;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 수행료 지급 결과(2026-11-18 — 사용자 "결과 받는 창구 + 수동 표시"). 지급 실행은 외부 회계(원문 §7.2). 묶음 = 한 라이더 × 같은 지급 예정일의 확정
 * 수행료. 외부 회계(서비스 토큰) 또는 관리자(OPERATOR_ADMIN)가 PAYING·PAID·FAILED 를 남기면 아직 지급 안 된 줄을 한꺼번에 바꾸고 줄마다
 * RiderEarningRecorded. 이미 PAID 인 줄은 건드리지 않는다. 실패 사유는 사건에 싣지 않는다.
 */
@Service
public class PayoutResults {

  private static final Logger log = LoggerFactory.getLogger(PayoutResults.class);
  static final Set<String> BATCH_STATES = Set.of("CONFIRMED", "PAYING", "PAID", "PAYMENT_FAILED");

  public record LastResult(
      String result,
      @Nullable String reference,
      @Nullable String failureReason,
      String recordedBy,
      Instant recordedAt) {}

  public record Batch(
      String riderId,
      LocalDate payoutDate,
      int count,
      long total,
      String state,
      @Nullable LastResult lastResult,
      int compensationCount) {}

  private record Comp(String id, String state, int amount) {}

  /** 그 묶음의 보상금(2026-11-19 — 이의 인정). 잠근다. */
  private List<Comp> comps(String riderId, LocalDate payoutDate) {
    return jdbc.query(
        "SELECT compensation_id, settlement_state, amount FROM rider_compensation WHERE rider_id = ?"
            + " AND payout_date = ? ORDER BY compensation_id FOR UPDATE",
        (rs, i) ->
            new Comp(
                rs.getString("compensation_id"),
                rs.getString("settlement_state"),
                rs.getInt("amount")),
        riderId,
        payoutDate);
  }

  private final JdbcTemplate jdbc;
  private final ExecutionStore store;
  private final ExecutionEvents events;
  private final TransactionTemplate tx;
  private final Clock clock;

  public PayoutResults(
      JdbcTemplate jdbc,
      ExecutionStore store,
      ExecutionEvents events,
      TransactionTemplate tx,
      Clock clock) {
    this.jdbc = jdbc;
    this.store = store;
    this.events = events;
    this.tx = tx;
    this.clock = clock;
  }

  private LocalDate today() {
    return LocalDate.ofInstant(clock.instant(), EarningService.SEOUL);
  }

  public List<Batch> list(
      @Nullable LocalDate payoutDate, @Nullable String riderId, @Nullable String state) {
    PmartPrincipal.current()
        .filter(
            p ->
                p.type() == PmartPrincipal.PrincipalType.OPERATOR
                    && (p.hasRole("OPERATOR_ADMIN") || p.hasRole("RIDER_OPS")))
        .orElseThrow(() -> ExecutionService.forbidden("지급 묶음은 관리자·라이더 운영만 볼 수 있습니다."));
    LocalDate d = payoutDate != null ? payoutDate : today();
    List<Object> args = new ArrayList<>(List.of(d));
    String sql =
        "SELECT rider_id FROM (SELECT rider_id FROM rider_earning WHERE payout_date = ? AND"
            + " settlement_state IN ('CONFIRMED', 'PAYING', 'PAID', 'PAYMENT_FAILED') UNION SELECT"
            + " rider_id FROM rider_compensation WHERE payout_date = ?) b"
            + (riderId != null ? " WHERE rider_id = ?" : "")
            + " GROUP BY rider_id ORDER BY rider_id LIMIT 1000";
    args.add(d);
    if (riderId != null) {
      args.add(riderId);
    }
    return jdbc.queryForList(sql, String.class, args.toArray()).stream()
        .map(r -> batch(r, d))
        .flatMap(Optional::stream)
        .filter(b -> state == null || state.equals(b.state()))
        .toList();
  }

  /** 관리자 수동 표시. */
  public Batch recordByOperator(
      String riderId,
      LocalDate payoutDate,
      String result,
      @Nullable String reference,
      @Nullable String failureReason,
      String idempotencyKey) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .filter(
                x ->
                    x.type() == PmartPrincipal.PrincipalType.OPERATOR
                        && x.hasRole("OPERATOR_ADMIN"))
            .orElseThrow(() -> ExecutionService.forbidden("지급 결과는 관리자만 표시할 수 있습니다."));
    return record(
        p.subject(), riderId, payoutDate, result, reference, failureReason, idempotencyKey);
  }

  /** 외부 회계(서비스 토큰). */
  public Batch recordByService(
      String riderId,
      LocalDate payoutDate,
      String result,
      @Nullable String reference,
      @Nullable String failureReason,
      String idempotencyKey) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .filter(x -> x.type() == PmartPrincipal.PrincipalType.SERVICE)
            .orElseThrow(() -> ExecutionService.forbidden("서비스만 부를 수 있습니다."));
    return record(
        p.subject(), riderId, payoutDate, result, reference, failureReason, idempotencyKey);
  }

  private Batch record(
      String actor,
      String riderId,
      LocalDate payoutDate,
      String result,
      @Nullable String reference,
      @Nullable String failureReason,
      String idempotencyKey) {
    String reason = failureReason == null || failureReason.isBlank() ? null : failureReason.strip();
    if ("FAILED".equals(result) && reason == null) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "지급 실패는 사유가 필요합니다.");
    }
    if (!"FAILED".equals(result) && payoutDate.isAfter(today())) {
      throw new PmartException(
          "PAYOUT_NOT_DUE",
          HttpStatus.UNPROCESSABLE_CONTENT,
          "지급 예정일(" + payoutDate + ") 전입니다.",
          false,
          List.of());
    }
    String key = actor + ":" + idempotencyKey;
    return tx.execute(
        s -> {
          if (jdbc.queryForObject(
                  "SELECT count(*) FROM rider_payout_result WHERE request_key = ?",
                  Integer.class,
                  key)
              > 0) {
            return batch(riderId, payoutDate).orElseThrow();
          }
          Set<String> from =
              switch (result) {
                case "PAYING" -> Set.of("CONFIRMED", "PAYMENT_FAILED");
                case "PAID" -> Set.of("CONFIRMED", "PAYING", "PAYMENT_FAILED");
                default -> Set.of("CONFIRMED", "PAYING");
              };
          String to = "FAILED".equals(result) ? "PAYMENT_FAILED" : result;
          List<EarningRow> rows =
              store.earningsForPayout(riderId, payoutDate).stream()
                  .filter(e -> BATCH_STATES.contains(e.settlementState()))
                  .toList();
          List<Comp> comps = comps(riderId, payoutDate);
          if (rows.isEmpty() && comps.isEmpty()) {
            throw PmartException.notFound("PAYOUT_NOT_FOUND", "이 라이더의 그날 지급 묶음이 없습니다.");
          }
          List<EarningRow> change =
              rows.stream().filter(e -> from.contains(e.settlementState())).toList();
          List<Comp> compChange = comps.stream().filter(c -> from.contains(c.state())).toList();
          if (change.isEmpty() && compChange.isEmpty()) {
            throw PmartException.invalidState("바꿀 수행료가 없습니다(이미 " + to + " 이거나 지급 완료).");
          }
          Instant now = clock.instant();
          long total = 0;
          for (EarningRow e : change) {
            store.setSettlementState(e.earningId(), to, now);
            EarningRow after = store.earningOf(e.deliveryId(), e.riderId()).orElseThrow();
            events.earningRecorded(after, store.bumpVersion(e.deliveryId(), now), now);
            total += e.total() == null ? 0 : e.total();
          }
          for (Comp c : compChange) {
            jdbc.update(
                "UPDATE rider_compensation SET settlement_state = ?, updated_at = ? WHERE"
                    + " compensation_id = ?",
                to,
                Timestamp.from(now),
                c.id());
            total += c.amount();
          }
          jdbc.update(
              "INSERT INTO rider_payout_result (result_id, request_key, rider_id, payout_date, result,"
                  + " reference, failure_reason, earning_count, total, recorded_by, recorded_at) VALUES"
                  + " (?,?,?,?,?,?,?,?,?,?,?)",
              "PYR-" + UUID.randomUUID(),
              key,
              riderId,
              payoutDate,
              result,
              reference,
              reason,
              change.size() + compChange.size(),
              total,
              actor,
              Timestamp.from(now));
          log.info(
              "지급 결과 {} — 라이더 {} {} {}건(보상금 {})",
              result,
              riderId,
              payoutDate,
              change.size() + compChange.size(),
              compChange.size());
          return batch(riderId, payoutDate).orElseThrow();
        });
  }

  private Optional<Batch> batch(String riderId, LocalDate payoutDate) {
    List<EarningRow> rows =
        store.earningsForPayout(riderId, payoutDate).stream()
            .filter(e -> BATCH_STATES.contains(e.settlementState()))
            .toList();
    List<Comp> comps = comps(riderId, payoutDate);
    if (rows.isEmpty() && comps.isEmpty()) {
      return Optional.empty();
    }
    Map<String, Integer> states = new LinkedHashMap<>();
    long total = 0;
    for (EarningRow e : rows) {
      states.merge(e.settlementState(), 1, Integer::sum);
      total += e.total() == null ? 0 : e.total();
    }
    for (Comp c : comps) {
      states.merge(c.state(), 1, Integer::sum);
      total += c.amount();
    }
    String state = states.size() == 1 ? states.keySet().iterator().next() : "MIXED";
    LastResult last =
        jdbc
            .query(
                "SELECT result, reference, failure_reason, recorded_by, recorded_at FROM"
                    + " rider_payout_result WHERE rider_id = ? AND payout_date = ? ORDER BY recorded_at"
                    + " DESC LIMIT 1",
                (rs, i) ->
                    new LastResult(
                        rs.getString("result"),
                        rs.getString("reference"),
                        rs.getString("failure_reason"),
                        rs.getString("recorded_by"),
                        rs.getTimestamp("recorded_at").toInstant()),
                riderId,
                payoutDate)
            .stream()
            .findFirst()
            .orElse(null);
    return Optional.of(
        new Batch(
            riderId, payoutDate, rows.size() + comps.size(), total, state, last, comps.size()));
  }
}
