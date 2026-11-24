package com.pmart.delivery.execution.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.delivery.execution.domain.DeductionRules;
import com.pmart.delivery.execution.infrastructure.ExecutionStore;
import com.pmart.delivery.execution.infrastructure.ExecutionStore.DeliveryRow;
import com.pmart.delivery.execution.infrastructure.OrderClient;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 오배송 정산 차감(2026-11-24, ADR-0086 — DE-9, 사용자 "하루 지나면 회수 배송 포기하고 라이더 귀책으로 고객 주문금액 만큼 정산금 차감").
 *
 * <ol>
 *   <li>case {@code MisdeliveryConfirmed} → 귀책 사본({@code misdelivery_fault}, 사건마다 처음 값).
 *   <li>dispatch {@code DeliveryCancelledBeforePickup} reason RECOVERY_ABANDONED(회수 배송 하루 포기) + 귀책
 *       RIDER → 그 오배송 배송의 라이더에게 차감 예정(PENDING_OBJECTION, 금액 = order 결제 금액 − 배달비, 이의 기한 5일) +
 *       RiderDeductionScheduled. 하루 안에 회수되면 포기가 없으니 차감도 없다.
 *   <li>라이더 이의(기한 전 한 번) → OBJECTED. 지원센터(RIDER_OPS·OPERATOR_ADMIN) 받아들임 → CANCELLED, 거절 →
 *       CONFIRMED. 이의 없이 기한이 지나면 CONFIRMED({@link #confirmDue}).
 *   <li>확정 차감은 지급 묶음이 지급 중·지급됨이 될 때 먼저 확정된 것부터 뺀다({@link #applyToBatch} — {@link PayoutResults}),
 *       모자라면 다음 묶음으로(상한 없음).
 * </ol>
 */
@Service
public class RiderDeductions {

  private static final Logger log = LoggerFactory.getLogger(RiderDeductions.class);
  public static final String RECOVERY_ABANDONED = "RECOVERY_ABANDONED";

  public record Application(LocalDate payoutDate, int amount) {}

  public record Row(
      String deductionId,
      String caseId,
      String deliveryId,
      @Nullable String deliveryNumber,
      @Nullable String recoveryDeliveryId,
      String orderId,
      String riderId,
      int amount,
      int remaining,
      String state,
      Instant objectionDeadline,
      @Nullable String objectionNote,
      @Nullable Instant objectedAt,
      @Nullable String decision,
      @Nullable String decisionNote,
      @Nullable String decidedBy,
      @Nullable Instant decidedAt,
      @Nullable Instant confirmedAt,
      Instant createdAt,
      List<Application> applications) {

    Row withApplications(List<Application> a) {
      return new Row(
          deductionId,
          caseId,
          deliveryId,
          deliveryNumber,
          recoveryDeliveryId,
          orderId,
          riderId,
          amount,
          remaining,
          state,
          objectionDeadline,
          objectionNote,
          objectedAt,
          decision,
          decisionNote,
          decidedBy,
          decidedAt,
          confirmedAt,
          createdAt,
          a);
    }

    Row hideDecider() {
      return new Row(
          deductionId,
          caseId,
          deliveryId,
          deliveryNumber,
          recoveryDeliveryId,
          orderId,
          riderId,
          amount,
          remaining,
          state,
          objectionDeadline,
          objectionNote,
          objectedAt,
          decision,
          decisionNote,
          null,
          decidedAt,
          confirmedAt,
          createdAt,
          applications);
    }
  }

  public record Listing(
      List<Row> items, @Nullable Long remainingTotal, @Nullable Long pendingTotal) {}

  private final JdbcTemplate jdbc;
  private final ExecutionStore store;
  private final ExecutionEvents events;
  private final OrderClient orders;
  private final TransactionTemplate tx;
  private final Clock clock;

  public RiderDeductions(
      JdbcTemplate jdbc,
      ExecutionStore store,
      ExecutionEvents events,
      OrderClient orders,
      TransactionTemplate tx,
      Clock clock) {
    this.jdbc = jdbc;
    this.store = store;
    this.events = events;
    this.orders = orders;
    this.tx = tx;
    this.clock = clock;
  }

  // ---------- 사건 ----------

  /** MisdeliveryConfirmed 의 귀책 사본(호출자 트랜잭션 — inbox). 같은 사건은 처음 값. */
  public void recordFault(String caseId, String originDeliveryId, String riderId, String fault) {
    if (caseId.isBlank() || originDeliveryId.isBlank() || fault.isBlank()) {
      return;
    }
    jdbc.update(
        "INSERT INTO misdelivery_fault (case_id, origin_delivery_id, rider_id, fault, recorded_at)"
            + " VALUES (?,?,?,?,?) ON CONFLICT (case_id) DO NOTHING",
        caseId,
        originDeliveryId,
        riderId,
        fault,
        Timestamp.from(clock.instant()));
  }

  /**
   * 회수 배송 포기(호출자 트랜잭션 — inbox). 귀책 RIDER 사건이면 차감 예정. order 를 못 부르면 예외(사건을 다시 받는다), 금액이 0 이면 만들지
   * 않는다.
   */
  public void onRecoveryAbandoned(DeliveryRow recovery, @Nullable String caseIdFromEvent) {
    String caseId = caseIdFromEvent != null ? caseIdFromEvent : recovery.caseId();
    if (caseId == null || caseId.isBlank()) {
      log.warn("회수 배송 {} 포기 — 사건 번호가 없어 차감 판단 안 함", recovery.deliveryId());
      return;
    }
    List<String[]> fault =
        jdbc.query(
            "SELECT origin_delivery_id, rider_id, fault FROM misdelivery_fault WHERE case_id = ?",
            (rs, i) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3)},
            caseId);
    if (fault.isEmpty()) {
      log.warn("회수 배송 {} 포기 — 사건 {} 의 귀책 사본이 없어 차감 안 함(지원센터 확인)", recovery.deliveryId(), caseId);
      return;
    }
    if (!"RIDER".equals(fault.get(0)[2])) {
      return;
    }
    String originId = fault.get(0)[0];
    DeliveryRow origin = store.delivery(originId).orElse(null);
    String riderId =
        origin != null && origin.riderId() != null ? origin.riderId() : fault.get(0)[1];
    String orderId = origin != null ? origin.orderId() : recovery.orderId();
    if (orderId == null) {
      log.warn("회수 배송 {} 포기 — 주문을 몰라 차감 안 함(사건 {})", recovery.deliveryId(), caseId);
      return;
    }
    long[] paid = orders.paidAndTip(orderId).orElse(null);
    if (paid == null) {
      log.warn("회수 배송 {} 포기 — 주문 {} 금액이 없어 차감 안 함(사건 {})", recovery.deliveryId(), orderId, caseId);
      return;
    }
    long amount = DeductionRules.amount(paid[0], paid[1]);
    if (amount <= 0) {
      log.info("사건 {} — 결제 금액 − 배달비 = 0, 차감 없음", caseId);
      return;
    }
    Instant now = clock.instant();
    String id = "DED-" + UUID.nameUUIDFromBytes(("deduction:" + caseId).getBytes());
    int n =
        jdbc.update(
            "INSERT INTO rider_deduction (deduction_id, case_id, origin_delivery_id, delivery_number,"
                + " recovery_delivery_id, order_id, rider_id, amount, remaining, state,"
                + " objection_deadline, created_at, updated_at) VALUES"
                + " (?,?,?,?,?,?,?,?,?,'PENDING_OBJECTION',?,?,?) ON CONFLICT (case_id) DO NOTHING",
            id,
            caseId,
            originId,
            origin != null ? origin.deliveryNumber() : null,
            recovery.deliveryId(),
            orderId,
            riderId,
            (int) amount,
            (int) amount,
            Timestamp.from(now.plus(DeductionRules.OBJECTION_WINDOW)),
            Timestamp.from(now),
            Timestamp.from(now));
    if (n == 0) {
      return; // 같은 사건 — 이미 있음
    }
    Row r = one("WHERE deduction_id = ?", id).orElseThrow();
    events.deduction(
        "RiderDeductionScheduled", r, store.bumpVersion(originId, now), system(), null, now);
    log.warn(
        "사건 {} 라이더 귀책 오배송 — 회수 포기, 차감 예정 {}원(이의 기한 {})", caseId, amount, r.objectionDeadline());
  }

  // ---------- 라이더 ----------

  public Listing mine() {
    String riderId = rider();
    List<Row> items =
        list("WHERE rider_id = ? ORDER BY created_at DESC LIMIT 200", riderId).stream()
            .map(Row::hideDecider)
            .toList();
    return new Listing(items, remainingTotal(riderId), pendingTotal(riderId));
  }

  public Row object(String deductionId, String idempotencyKey, String note) {
    String riderId = rider();
    String n = note == null ? "" : note.strip();
    if (n.length() < 2 || n.length() > 500) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "이의 내용은 2~500자입니다.");
    }
    String key = riderId + ":" + idempotencyKey;
    return tx.execute(
        s -> {
          Row d =
              lock(deductionId)
                  .filter(x -> x.riderId().equals(riderId))
                  .orElseThrow(() -> PmartException.notFound("DEDUCTION_NOT_FOUND", "차감이 없습니다."));
          if (key.equals(
              jdbc.queryForObject(
                  "SELECT objection_key FROM rider_deduction WHERE deduction_id = ?",
                  String.class,
                  deductionId))) {
            return d.hideDecider();
          }
          Instant now = clock.instant();
          if (!"PENDING_OBJECTION".equals(d.state()) || !now.isBefore(d.objectionDeadline())) {
            throw new PmartException(
                "OBJECTION_CLOSED",
                HttpStatus.CONFLICT,
                "이의 기간이 아니거나 이미 이의했습니다.",
                false,
                List.of());
          }
          jdbc.update(
              "UPDATE rider_deduction SET state = 'OBJECTED', objection_note = ?, objected_at = ?,"
                  + " objection_key = ?, updated_at = ? WHERE deduction_id = ?",
              n,
              Timestamp.from(now),
              key,
              Timestamp.from(now),
              deductionId);
          return one("WHERE deduction_id = ?", deductionId).orElseThrow().hideDecider();
        });
  }

  // ---------- 직원 ----------

  public Listing forStaff(
      @Nullable String riderId, @Nullable String state, @Nullable String caseId) {
    PmartPrincipal.current()
        .filter(
            p ->
                p.type() == PmartPrincipal.PrincipalType.OPERATOR
                    && (p.hasRole("OPERATOR_ADMIN")
                        || p.hasRole("RIDER_OPS")
                        || p.hasRole("CS_AGENT")))
        .orElseThrow(() -> ExecutionService.forbidden("정산 차감은 라이더 운영·관리자·상담원만 봅니다."));
    StringBuilder w = new StringBuilder("WHERE true");
    List<Object> args = new ArrayList<>();
    if (riderId != null) {
      w.append(" AND rider_id = ?");
      args.add(riderId);
    }
    if (state != null) {
      w.append(" AND state = ?");
      args.add(state);
    }
    if (caseId != null) {
      w.append(" AND case_id = ?");
      args.add(caseId);
    }
    List<Row> items = list(w + " ORDER BY created_at DESC LIMIT 200", args.toArray());
    return new Listing(
        items,
        riderId == null ? null : remainingTotal(riderId),
        riderId == null ? null : pendingTotal(riderId));
  }

  public Row decide(String deductionId, String idempotencyKey, String decision, String note) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .filter(
                x ->
                    x.type() == PmartPrincipal.PrincipalType.OPERATOR
                        && (x.hasRole("OPERATOR_ADMIN") || x.hasRole("RIDER_OPS")))
            .orElseThrow(() -> ExecutionService.forbidden("차감 이의는 라이더 운영·관리자만 결정합니다."));
    String n = note == null ? "" : note.strip();
    if (n.length() < 2
        || n.length() > 500
        || !("ACCEPT".equals(decision) || "REJECT".equals(decision))) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "결정은 ACCEPT·REJECT, 메모는 2~500자입니다.");
    }
    String key = p.subject() + ":" + idempotencyKey;
    return tx.execute(
        s -> {
          Row d =
              lock(deductionId)
                  .orElseThrow(() -> PmartException.notFound("DEDUCTION_NOT_FOUND", "차감이 없습니다."));
          if (key.equals(
              jdbc.queryForObject(
                  "SELECT decision_key FROM rider_deduction WHERE deduction_id = ?",
                  String.class,
                  deductionId))) {
            return d;
          }
          if (!"OBJECTED".equals(d.state())) {
            throw new PmartException(
                "NOT_DECIDABLE", HttpStatus.CONFLICT, "이의 중인 차감만 결정합니다.", false, List.of());
          }
          Instant now = clock.instant();
          boolean accept = "ACCEPT".equals(decision);
          jdbc.update(
              "UPDATE rider_deduction SET state = ?, remaining = CASE WHEN ? THEN 0 ELSE remaining END,"
                  + " decision = ?, decision_note = ?, decided_by = ?, decided_at = ?, decision_key = ?,"
                  + " confirmed_at = ?, updated_at = ? WHERE"
                  + " deduction_id = ?",
              accept ? "CANCELLED" : "CONFIRMED",
              accept,
              decision,
              n,
              p.subject(),
              Timestamp.from(now),
              key,
              accept ? null : Timestamp.from(now),
              Timestamp.from(now),
              deductionId);
          Row after = one("WHERE deduction_id = ?", deductionId).orElseThrow();
          events.deduction(
              accept ? "RiderDeductionCancelled" : "RiderDeductionConfirmed",
              after,
              store.bumpVersion(after.deliveryId(), now),
              new EventEnvelope.Actor(EventEnvelope.ActorType.OPERATOR, p.subject()),
              decision,
              now);
          return after;
        });
  }

  // ---------- 기한 ----------

  @Scheduled(
      initialDelayString = "${pmart.execution.confirm-interval:PT1M}",
      fixedDelayString = "${pmart.execution.confirm-interval:PT1M}")
  public void scheduledConfirm() {
    try {
      confirmDue();
    } catch (RuntimeException e) {
      log.error("차감 확정 주기 실패 — 다음 주기에 다시", e);
    }
  }

  /** 이의 없이 기한이 지난 차감 → CONFIRMED. 시험은 직접 부른다. */
  public int confirmDue() {
    Instant now = clock.instant();
    List<String> due =
        jdbc.queryForList(
            "SELECT deduction_id FROM rider_deduction WHERE state = 'PENDING_OBJECTION' AND"
                + " objection_deadline <= ? ORDER BY objection_deadline LIMIT 100",
            String.class,
            Timestamp.from(now));
    int done = 0;
    for (String id : due) {
      Boolean ok =
          tx.execute(
              s -> {
                Row d = lock(id).orElse(null);
                if (d == null
                    || !"PENDING_OBJECTION".equals(d.state())
                    || d.objectionDeadline().isAfter(now)) {
                  return false;
                }
                jdbc.update(
                    "UPDATE rider_deduction SET state = 'CONFIRMED', confirmed_at = ?, updated_at = ?"
                        + " WHERE deduction_id = ?",
                    Timestamp.from(now),
                    Timestamp.from(now),
                    id);
                Row after = one("WHERE deduction_id = ?", id).orElseThrow();
                events.deduction(
                    "RiderDeductionConfirmed",
                    after,
                    store.bumpVersion(after.deliveryId(), now),
                    system(),
                    null,
                    now);
                return true;
              });
      if (Boolean.TRUE.equals(ok)) {
        done++;
      }
    }
    return done;
  }

  // ---------- 지급 묶음 ----------

  /** 이 묶음에서 이미 뺀 합. */
  public long appliedTo(String riderId, LocalDate payoutDate) {
    Long v =
        jdbc.queryForObject(
            "SELECT COALESCE(sum(amount), 0) FROM rider_deduction_application WHERE rider_id = ? AND"
                + " payout_date = ?",
            Long.class,
            riderId,
            payoutDate);
    return v == null ? 0 : v;
  }

  /** 묶음이 지급 중·지급됨이 될 때(호출자 트랜잭션) 한 번 — 이미 뺀 묶음이면 그대로. 먼저 확정된 차감부터 gross 안에서. @return 뺀 합 */
  public long applyToBatch(String riderId, LocalDate payoutDate, long gross, Instant now) {
    Integer already =
        jdbc.queryForObject(
            "SELECT count(*) FROM rider_deduction_application WHERE rider_id = ? AND payout_date = ?",
            Integer.class,
            riderId,
            payoutDate);
    if (already != null && already > 0) {
      return appliedTo(riderId, payoutDate);
    }
    List<DeductionRules.Open> open =
        jdbc.query(
            "SELECT deduction_id, remaining FROM rider_deduction WHERE rider_id = ? AND state ="
                + " 'CONFIRMED' AND remaining > 0 ORDER BY confirmed_at, deduction_id FOR UPDATE",
            (rs, i) -> new DeductionRules.Open(rs.getString(1), rs.getLong(2)),
            riderId);
    long total = 0;
    for (Map.Entry<String, Long> e : DeductionRules.allocate(open, gross).entrySet()) {
      jdbc.update(
          "INSERT INTO rider_deduction_application (deduction_id, rider_id, payout_date, amount,"
              + " applied_at) VALUES (?,?,?,?,?)",
          e.getKey(),
          riderId,
          payoutDate,
          e.getValue().intValue(),
          Timestamp.from(now));
      jdbc.update(
          "UPDATE rider_deduction SET remaining = remaining - ?, updated_at = ? WHERE deduction_id = ?",
          e.getValue().intValue(),
          Timestamp.from(now),
          e.getKey());
      total += e.getValue();
    }
    if (total > 0) {
      log.info("라이더 {} {} 지급 묶음에서 오배송 차감 {}원", riderId, payoutDate, total);
    }
    return total;
  }

  /** 확정되어 앞으로 뺄 금액 합. */
  public long remainingTotal(String riderId) {
    Long v =
        jdbc.queryForObject(
            "SELECT COALESCE(sum(remaining), 0) FROM rider_deduction WHERE rider_id = ? AND state ="
                + " 'CONFIRMED'",
            Long.class,
            riderId);
    return v == null ? 0 : v;
  }

  private long pendingTotal(String riderId) {
    Long v =
        jdbc.queryForObject(
            "SELECT COALESCE(sum(remaining), 0) FROM rider_deduction WHERE rider_id = ? AND state IN"
                + " ('PENDING_OBJECTION', 'OBJECTED')",
            Long.class,
            riderId);
    return v == null ? 0 : v;
  }

  // ---------- 읽기 ----------

  private static String rider() {
    return PmartPrincipal.current()
        .filter(x -> x.type() == PmartPrincipal.PrincipalType.RIDER)
        .map(PmartPrincipal::subject)
        .orElseThrow(() -> ExecutionService.forbidden("라이더만 할 수 있습니다."));
  }

  private static EventEnvelope.Actor system() {
    return new EventEnvelope.Actor(EventEnvelope.ActorType.SYSTEM, "delivery-execution-service");
  }

  private Optional<Row> lock(String id) {
    List<String> ids =
        jdbc.queryForList(
            "SELECT deduction_id FROM rider_deduction WHERE deduction_id = ? FOR UPDATE",
            String.class,
            id);
    return ids.isEmpty() ? Optional.empty() : one("WHERE deduction_id = ?", id);
  }

  private Optional<Row> one(String where, Object... args) {
    return list(where, args).stream().findFirst();
  }

  private List<Row> list(String where, Object... args) {
    List<Row> rows =
        jdbc.query("SELECT * FROM rider_deduction " + where, RiderDeductions::row, args);
    return rows.stream()
        .map(
            r ->
                r.withApplications(
                    jdbc.query(
                        "SELECT payout_date, amount FROM rider_deduction_application WHERE"
                            + " deduction_id = ? ORDER BY payout_date",
                        (rs, i) -> new Application(rs.getDate(1).toLocalDate(), rs.getInt(2)),
                        r.deductionId())))
        .toList();
  }

  private static @Nullable Instant ts(ResultSet rs, String c) throws SQLException {
    Timestamp t = rs.getTimestamp(c);
    return t == null ? null : t.toInstant();
  }

  private static Row row(ResultSet rs, int i) throws SQLException {
    return new Row(
        rs.getString("deduction_id"),
        rs.getString("case_id"),
        rs.getString("origin_delivery_id"),
        rs.getString("delivery_number"),
        rs.getString("recovery_delivery_id"),
        rs.getString("order_id"),
        rs.getString("rider_id"),
        rs.getInt("amount"),
        rs.getInt("remaining"),
        rs.getString("state"),
        rs.getTimestamp("objection_deadline").toInstant(),
        rs.getString("objection_note"),
        ts(rs, "objected_at"),
        rs.getString("decision"),
        rs.getString("decision_note"),
        rs.getString("decided_by"),
        ts(rs, "decided_at"),
        ts(rs, "confirmed_at"),
        rs.getTimestamp("created_at").toInstant(),
        List.of());
  }
}
