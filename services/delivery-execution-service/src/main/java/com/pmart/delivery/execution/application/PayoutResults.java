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
      int compensationCount,
      long deductionTotal,
      long expectedDeduction,
      long netTotal,
      @Nullable LocalDate rolledTo,
      int rolledInCount,
      int rollCount,
      boolean finalAttempt,
      boolean escalated) {

    Batch rolled(@Nullable LocalDate to) {
      return new Batch(
          riderId,
          payoutDate,
          count,
          total,
          state,
          lastResult,
          compensationCount,
          deductionTotal,
          expectedDeduction,
          netTotal,
          to,
          rolledInCount,
          rollCount,
          finalAttempt,
          escalated);
    }
  }

  private record Comp(String id, String state, int amount, int rollCount) {}

  /** 그 묶음의 보상금(2026-11-19 — 이의 인정). 잠근다. */
  private List<Comp> comps(String riderId, LocalDate payoutDate) {
    return jdbc.query(
        "SELECT compensation_id, settlement_state, amount, roll_count FROM rider_compensation WHERE rider_id = ?"
            + " AND payout_date = ? ORDER BY compensation_id FOR UPDATE",
        (rs, i) ->
            new Comp(
                rs.getString("compensation_id"),
                rs.getString("settlement_state"),
                rs.getInt("amount"),
                rs.getInt("roll_count")),
        riderId,
        payoutDate);
  }

  private final JdbcTemplate jdbc;
  private final ExecutionStore store;
  private final ExecutionEvents events;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final RiderDeductions deductions;
  private final ExecutionProperties props;

  public PayoutResults(
      JdbcTemplate jdbc,
      ExecutionStore store,
      ExecutionEvents events,
      TransactionTemplate tx,
      Clock clock,
      RiderDeductions deductions,
      ExecutionProperties props) {
    this.deductions = deductions;
    this.props = props;
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
    return list(payoutDate, null, null, riderId, state);
  }

  /**
   * from·to(2026-11-24 — 라이더 백오피스 "지급 실패 묶음 n건")를 주면 그 기간(지급 예정일, 포함, 최대 31일)의 묶음 전부(날짜 순), 아니면
   * payoutDate(기본 오늘) 하루.
   */
  public List<Batch> list(
      @Nullable LocalDate payoutDate,
      @Nullable LocalDate from,
      @Nullable LocalDate to,
      @Nullable String riderId,
      @Nullable String state) {
    return list(payoutDate, from, to, riderId, state, false);
  }

  /** escalated(2026-11-24 — 넘김 상한)면 날짜와 상관없이 직원이 직접 지급해야 하는 묶음만. */
  public List<Batch> list(
      @Nullable LocalDate payoutDate,
      @Nullable LocalDate from,
      @Nullable LocalDate to,
      @Nullable String riderId,
      @Nullable String state,
      boolean escalated) {
    PmartPrincipal.current()
        .filter(
            p ->
                p.type() == PmartPrincipal.PrincipalType.OPERATOR
                    && (p.hasRole("OPERATOR_ADMIN") || p.hasRole("RIDER_OPS")))
        .orElseThrow(() -> ExecutionService.forbidden("지급 묶음은 관리자·라이더 운영만 볼 수 있습니다."));
    if (escalated) {
      List<Object> args = new ArrayList<>(List.of(maxRolls(), maxRolls()));
      if (riderId != null) {
        args.add(riderId);
      }
      return jdbc
          .query(
              "SELECT rider_id, payout_date FROM (SELECT rider_id, payout_date FROM rider_earning WHERE"
                  + " settlement_state = 'PAYMENT_FAILED' AND roll_count >= ? UNION SELECT rider_id,"
                  + " payout_date FROM rider_compensation WHERE settlement_state = 'PAYMENT_FAILED' AND"
                  + " roll_count >= ?) b"
                  + (riderId != null ? " WHERE rider_id = ?" : "")
                  + " ORDER BY payout_date, rider_id LIMIT 200",
              (rs, i) -> new Object[] {rs.getString(1), rs.getDate(2).toLocalDate()},
              args.toArray())
          .stream()
          .map(r -> batch((String) r[0], (LocalDate) r[1]))
          .flatMap(Optional::stream)
          .filter(b -> state == null || state.equals(b.state()))
          .toList();
    }
    if (payoutDate == null && (from != null || to != null)) {
      LocalDate f = from != null ? from : to;
      LocalDate t = to != null ? to : from;
      if (f.isAfter(t) || f.plusDays(30).isBefore(t)) {
        throw new PmartException(ErrorCode.VALIDATION_FAILED, "기간은 from ≤ to, 31일 이하입니다.");
      }
      List<Batch> out = new ArrayList<>();
      for (LocalDate d = f; !d.isAfter(t); d = d.plusDays(1)) {
        out.addAll(oneDay(d, riderId, state));
      }
      return out;
    }
    return oneDay(payoutDate != null ? payoutDate : today(), riderId, state);
  }

  private List<Batch> oneDay(LocalDate d, @Nullable String riderId, @Nullable String state) {
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

  public record Rollover(
      String rolloverId,
      String riderId,
      LocalDate fromDate,
      LocalDate toDate,
      int lineCount,
      long total,
      @Nullable String failureReason,
      Instant rolledAt,
      int rollCount,
      boolean finalAttempt) {}

  private int maxRolls() {
    return props.payoutMaxRollovers();
  }

  /** 지급 실패 넘김 기록(직원) — 최근 순 200건. */
  public List<Rollover> rollovers(@Nullable String riderId) {
    PmartPrincipal.current()
        .filter(
            p ->
                p.type() == PmartPrincipal.PrincipalType.OPERATOR
                    && (p.hasRole("OPERATOR_ADMIN") || p.hasRole("RIDER_OPS")))
        .orElseThrow(() -> ExecutionService.forbidden("지급 넘김 기록은 관리자·라이더 운영만 볼 수 있습니다."));
    return jdbc.query(
        "SELECT o.rollover_id, o.rider_id, o.from_date, o.to_date, o.line_count, o.total,"
            + " r.failure_reason, o.rolled_at, o.roll_count, o.final_attempt FROM"
            + " rider_payout_rollover o LEFT JOIN"
            + " rider_payout_result r ON r.result_id = o.failure_result_id"
            + (riderId != null ? " WHERE o.rider_id = ?" : "")
            + " ORDER BY o.rolled_at DESC LIMIT 200",
        (rs, i) ->
            new Rollover(
                rs.getString(1),
                rs.getString(2),
                rs.getDate(3).toLocalDate(),
                rs.getDate(4).toLocalDate(),
                rs.getInt(5),
                rs.getLong(6),
                rs.getString(7),
                rs.getTimestamp(8).toInstant(),
                rs.getInt(9),
                rs.getBoolean(10)),
        riderId != null ? new Object[] {riderId} : new Object[0]);
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
            return batch(riderId, payoutDate).orElseGet(() -> rolledSnapshot(key));
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
          String resultId = "PYR-" + UUID.randomUUID();
          long total = 0;
          for (EarningRow e : change) {
            store.setSettlementState(e.earningId(), to, now);
            if (!"PAYMENT_FAILED".equals(to)) { // 실패는 아래 넘김이 줄마다 한 번(다음 지급일과 함께) 알린다
              EarningRow after = store.earningOf(e.deliveryId(), e.riderId()).orElseThrow();
              events.earningRecorded(after, store.bumpVersion(e.deliveryId(), now), now);
            }
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
          if ("PAYING".equals(result) || "PAID".equals(result)) {
            // 2026-11-24 ADR-0086 — 확정된 오배송 차감을 이 묶음에서(한 번, 모자라면 다음 묶음으로)
            deductions.applyToBatch(riderId, payoutDate, gross(rows, comps), now);
          }
          jdbc.update(
              "INSERT INTO rider_payout_result (result_id, request_key, rider_id, payout_date, result,"
                  + " reference, failure_reason, earning_count, total, recorded_by, recorded_at) VALUES"
                  + " (?,?,?,?,?,?,?,?,?,?,?)",
              resultId,
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
          if ("FAILED".equals(result)) {
            // 2026-11-24 사용자 "실패한 지급을 다음 지급일로 자동으로 넘겨" — 실패 묶음을 보여 주고 줄은 다음 지급일로
            Batch failed = batch(riderId, payoutDate).orElseThrow();
            return failed.rolled(rollFailed(riderId, payoutDate, resultId, now));
          }
          return batch(riderId, payoutDate).orElseThrow();
        });
  }

  /**
   * 지급 실패 줄(PAYMENT_FAILED)을 다음 지급일(오늘과 원래 지급일 중 늦은 날의 다음 영업일) 묶음으로 —
   * CONFIRMED·rolled_from·rolled_at· 넘긴 횟수 +1, 이 묶음에서 뺀 오배송 차감은 되돌림(새 묶음에서 다시), 넘김 기록, 수행료 줄마다
   * RiderEarningRecorded(rolledFrom). 호출자 트랜잭션.
   *
   * <p>넘김 상한(2026-11-24 사용자 "넘기는 횟수에 상한을 둬. 3번 안에는 반드시 줘야해"): 줄마다 {@code payout-max-rollovers}(기본
   * 3)번까지 — 상한째 넘김은 final_attempt(마지막 자동 시도), 상한을 다 쓴 줄은 넘기지 않고 PAYMENT_FAILED 로 남긴다(묶음 escalated —
   * 직원이 직접 지급).
   *
   * @return 넘긴 지급일(넘긴 줄이 없으면 null)
   */
  @Nullable LocalDate rollFailed(
      String riderId, LocalDate from, @Nullable String resultId, Instant now) {
    LocalDate today = today();
    LocalDate to =
        com.pmart.delivery.execution.domain.EarningRules.payoutDate(
            from.isAfter(today) ? from : today, 1, props.holidays());
    int max = maxRolls();
    deductions.revertBatch(riderId, from, now);
    Set<String> underCap =
        Set.copyOf(
            jdbc.queryForList(
                "SELECT earning_id FROM rider_earning WHERE rider_id = ? AND payout_date = ? AND"
                    + " settlement_state = 'PAYMENT_FAILED' AND roll_count < ?",
                String.class,
                riderId,
                from,
                max));
    List<EarningRow> allFailed =
        store.earningsForPayout(riderId, from).stream()
            .filter(e -> "PAYMENT_FAILED".equals(e.settlementState()))
            .toList();
    List<EarningRow> failed =
        allFailed.stream().filter(e -> underCap.contains(e.earningId())).toList();
    List<Comp> allComps =
        comps(riderId, from).stream().filter(c -> "PAYMENT_FAILED".equals(c.state())).toList();
    List<Comp> comps = allComps.stream().filter(c -> c.rollCount() < max).toList();
    int stuck = allFailed.size() - failed.size() + allComps.size() - comps.size();
    if (stuck > 0) {
      log.error(
          "지급 실패 넘김 상한({}번) — 라이더 {} {} {}건은 더 넘기지 않음(직원이 직접 지급해야 함)", max, riderId, from, stuck);
    }
    if (failed.isEmpty() && comps.isEmpty()) {
      return null;
    }
    Integer earlierMax =
        jdbc.queryForObject(
            "SELECT COALESCE(max(roll_count), 0) FROM rider_earning WHERE rider_id = ? AND payout_date = ?"
                + " AND settlement_state = 'PAYMENT_FAILED' AND roll_count < ?",
            Integer.class,
            riderId,
            from,
            max);
    int earlierRolls = earlierMax == null ? 0 : earlierMax;
    long total = 0;
    for (EarningRow e : failed) {
      jdbc.update(
          "UPDATE rider_earning SET settlement_state = 'CONFIRMED', payout_date = ?, rolled_from = ?,"
              + " rolled_at = ?, roll_count = roll_count + 1, updated_at = ? WHERE earning_id = ?",
          to,
          from,
          Timestamp.from(now),
          Timestamp.from(now),
          e.earningId());
      EarningRow after = store.earningOf(e.deliveryId(), e.riderId()).orElseThrow();
      events.earningRecorded(after, store.bumpVersion(e.deliveryId(), now), now);
      total += e.total() == null ? 0 : e.total();
    }
    for (Comp c : comps) {
      jdbc.update(
          "UPDATE rider_compensation SET settlement_state = 'CONFIRMED', payout_date = ?,"
              + " rolled_from = ?, rolled_at = ?, roll_count = roll_count + 1, updated_at = ? WHERE"
              + " compensation_id = ?",
          to,
          from,
          Timestamp.from(now),
          Timestamp.from(now),
          c.id());
      total += c.amount();
    }
    int rollCount =
        1 + Math.max(earlierRolls, comps.stream().mapToInt(Comp::rollCount).max().orElse(0));
    boolean finalAttempt = rollCount >= max;
    jdbc.update(
        "INSERT INTO rider_payout_rollover (rollover_id, rider_id, from_date, to_date, line_count,"
            + " total, failure_result_id, rolled_at, roll_count, final_attempt) VALUES"
            + " (?,?,?,?,?,?,?,?,?,?)",
        "PRO-" + UUID.randomUUID(),
        riderId,
        from,
        to,
        failed.size() + comps.size(),
        total,
        resultId,
        Timestamp.from(now),
        rollCount,
        finalAttempt);
    log.warn(
        "지급 실패 — 라이더 {} {} {}건 {}원을 {} 로 넘김({}번째{})",
        riderId,
        from,
        failed.size() + comps.size(),
        total,
        to,
        rollCount,
        finalAttempt ? " — 마지막 자동 시도" : "");
    return to;
  }

  /** 넘김 뒤 같은 키로 다시 부른 실패 표시 — 그때의 결과 기록과 넘김으로. */
  private Batch rolledSnapshot(String key) {
    Map<String, Object> r =
        jdbc.queryForMap(
            "SELECT r.rider_id, r.payout_date, r.earning_count, r.total, r.reference,"
                + " r.failure_reason, r.recorded_by, r.recorded_at, (SELECT o.to_date FROM"
                + " rider_payout_rollover o WHERE o.failure_result_id = r.result_id LIMIT 1) AS"
                + " to_date FROM rider_payout_result r WHERE r.request_key = ?",
            key);
    long total = ((Number) r.get("total")).longValue();
    java.sql.Date toDate = (java.sql.Date) r.get("to_date");
    return new Batch(
        (String) r.get("rider_id"),
        ((java.sql.Date) r.get("payout_date")).toLocalDate(),
        ((Number) r.get("earning_count")).intValue(),
        total,
        "PAYMENT_FAILED",
        new LastResult(
            "FAILED",
            (String) r.get("reference"),
            (String) r.get("failure_reason"),
            (String) r.get("recorded_by"),
            ((Timestamp) r.get("recorded_at")).toInstant()),
        0,
        0,
        0,
        total,
        toDate == null ? null : toDate.toLocalDate(),
        0,
        0,
        false,
        false);
  }

  /** 이미 지급 실패로 남은 묶음(넘김 배포 전 등)을 같은 규칙으로 — 확정 주기마다. */
  @org.springframework.scheduling.annotation.Scheduled(
      initialDelayString = "${pmart.execution.confirm-interval:PT1M}",
      fixedDelayString = "${pmart.execution.confirm-interval:PT1M}")
  public void scheduledRollStale() {
    try {
      rollStaleFailures();
    } catch (RuntimeException e) {
      log.error("지급 실패 넘김 주기 실패 — 다음 주기에 다시", e);
    }
  }

  /** 남은 지급 실패 묶음을 다음 지급일로. 시험은 직접 부른다. @return 넘긴 묶음 수 */
  public int rollStaleFailures() {
    List<Map<String, Object>> batches =
        jdbc.queryForList(
            "SELECT rider_id, payout_date FROM (SELECT rider_id, payout_date FROM rider_earning WHERE"
                + " settlement_state = 'PAYMENT_FAILED' AND roll_count < ? UNION SELECT rider_id, payout_date"
                + " FROM rider_compensation WHERE settlement_state = 'PAYMENT_FAILED' AND roll_count < ?) b"
                + " ORDER BY payout_date LIMIT 100",
            maxRolls(),
            maxRolls());
    int n = 0;
    for (Map<String, Object> b : batches) {
      String rider = (String) b.get("rider_id");
      LocalDate date = ((java.sql.Date) b.get("payout_date")).toLocalDate();
      tx.executeWithoutResult(
          s -> {
            List<String> last =
                jdbc.queryForList(
                    "SELECT result_id FROM rider_payout_result WHERE rider_id = ? AND payout_date = ?"
                        + " AND result = 'FAILED' ORDER BY recorded_at DESC LIMIT 1",
                    String.class,
                    rider,
                    date);
            rollFailed(rider, date, last.isEmpty() ? null : last.get(0), clock.instant());
          });
      n++;
    }
    return n;
  }

  private int countRolledComps(String riderId, LocalDate payoutDate) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM rider_compensation WHERE rider_id = ? AND payout_date = ? AND"
                + " rolled_from IS NOT NULL",
            Integer.class,
            riderId,
            payoutDate);
    return n == null ? 0 : n;
  }

  private static long gross(List<EarningRow> rows, List<Comp> comps) {
    long g = 0;
    for (EarningRow e : rows) {
      g += e.total() == null ? 0 : e.total();
    }
    for (Comp c : comps) {
      g += c.amount();
    }
    return g;
  }

  /** 아직 빼지 않은 묶음의 예상 차감(ADR-0086) — 그 라이더의 확정 차감 남은 금액을, 이 묶음보다 앞선 빼지 않은 확정 묶음부터 채운 뒤. */
  private long expectedDeduction(String riderId, LocalDate payoutDate, long gross) {
    long remaining = deductions.remainingTotal(riderId);
    if (remaining == 0) {
      return 0;
    }
    Long earlier =
        jdbc.queryForObject(
            "SELECT COALESCE(sum(t), 0) FROM (SELECT COALESCE(total, 0) AS t, payout_date FROM"
                + " rider_earning WHERE rider_id = ? AND payout_date < ? AND settlement_state IN"
                + " ('CONFIRMED', 'PAYMENT_FAILED') UNION ALL SELECT amount, payout_date FROM rider_compensation"
                + " WHERE rider_id = ? AND payout_date < ? AND settlement_state IN ('CONFIRMED',"
                + " 'PAYMENT_FAILED')) x WHERE NOT EXISTS (SELECT 1 FROM rider_deduction_application a WHERE"
                + " a.rider_id = ? AND a.payout_date = x.payout_date)",
            Long.class,
            riderId,
            payoutDate,
            riderId,
            payoutDate,
            riderId);
    return com.pmart.delivery.execution.domain.DeductionRules.expected(
        remaining, earlier == null ? 0 : earlier, gross);
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
    int[] rolls = rolls(riderId, payoutDate);
    long applied = deductions.appliedTo(riderId, payoutDate);
    boolean open = Set.of("CONFIRMED", "PAYMENT_FAILED").contains(state);
    long expected = applied == 0 && open ? expectedDeduction(riderId, payoutDate, total) : 0;
    return Optional.of(
        new Batch(
            riderId,
            payoutDate,
            rows.size() + comps.size(),
            total,
            state,
            last,
            comps.size(),
            applied,
            expected,
            total - applied - expected,
            null,
            (int) rows.stream().filter(e -> e.rolledFrom() != null).count()
                + countRolledComps(riderId, payoutDate),
            rolls[0],
            rolls[0] >= maxRolls(),
            rolls[1] > 0));
  }

  /** 묶음 안 줄의 [가장 큰 넘김 횟수, 상한을 다 쓰고 또 실패한 줄 수]. */
  private int[] rolls(String riderId, LocalDate payoutDate) {
    return jdbc.queryForObject(
        "SELECT COALESCE(max(n), 0), count(*) FILTER (WHERE s = 'PAYMENT_FAILED' AND n >= ?) FROM"
            + " (SELECT roll_count AS n, settlement_state AS s FROM rider_earning WHERE rider_id = ?"
            + " AND payout_date = ? AND settlement_state IN ('CONFIRMED', 'PAYING', 'PAID',"
            + " 'PAYMENT_FAILED') UNION ALL SELECT roll_count, settlement_state FROM"
            + " rider_compensation WHERE rider_id = ? AND payout_date = ?) x",
        (rs, i) -> new int[] {rs.getInt(1), rs.getInt(2)},
        maxRolls(),
        riderId,
        payoutDate,
        riderId,
        payoutDate);
  }
}
