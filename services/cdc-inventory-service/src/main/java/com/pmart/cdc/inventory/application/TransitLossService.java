package com.pmart.cdc.inventory.application;

import com.pmart.cdc.inventory.domain.HqApprovalRules.Line;
import com.pmart.cdc.inventory.infrastructure.LedgerStore;
import com.pmart.cdc.inventory.infrastructure.LedgerStore.Delta;
import com.pmart.cdc.inventory.infrastructure.LedgerStore.TransitRow;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.Outcome;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 운송 손실 승인 반영(0.14.0, WMS CDC-F-027). 미정산 t 를 손실 l 로 — 자동 손실은 없다.
 *
 * <ul>
 *   <li>MFC 입고 종결(transit_closure)이 기록된 TRF 만(사용자 2026-11-26 "MFC 입고 종결 확인을 받은 뒤에만" — 아직 받는 중일 수 있는
 *       잔량을 손실로 종결하지 않는다).
 *   <li>승인 관문은 실사와 같다(사용자 "실사 조정과 같은 본사 기준") — 트랜잭션 밖.
 *   <li>줄마다 SKU·LOT 미정산 이하. 출발 줄 loss_qty +l(뒤 줄부터), Transit −l(원결과 TRANSPORT_LOSS) →
 *       TransitReconciled.
 * </ul>
 */
@Service
public class TransitLossService {

  static final String OP_LOSS = "cdcInventory:transitLoss";

  public record LossLine(String sku, String lot, int lossQty, @Nullable String lossReason) {}

  public record LossResult(
      String trfId,
      String commitId,
      int lossQty,
      int unreconciledQty,
      String transitState,
      @Nullable String approvalRef) {}

  public record Closure(String trfId, Instant closedAt, int unreconciledQty) {}

  private final JdbcTemplate jdbc;
  private final LedgerStore ledgerStore;
  private final Ledger ledger;
  private final TransactionTemplate tx;
  private final RequestIdempotency idempotency;
  private final ApprovalGate gate;
  private final ReceiptService receipts;
  private final ObjectMapper mapper;

  public TransitLossService(
      JdbcTemplate jdbc,
      LedgerStore ledgerStore,
      Ledger ledger,
      TransactionTemplate tx,
      RequestIdempotency idempotency,
      ApprovalGate gate,
      ReceiptService receipts,
      ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.ledgerStore = ledgerStore;
    this.ledger = ledger;
    this.tx = tx;
    this.idempotency = idempotency;
    this.gate = gate;
    this.receipts = receipts;
    this.mapper = mapper;
  }

  // ---------- MFC 입고 종결 ----------

  /** TRF 하나에 한 번(같은 본문 = 그대로, 다른 본문 409 TRANSIT_CLOSURE_CONFLICT). TOUT 전이면 404. */
  public Closure close(
      String sourceSystem,
      String trfId,
      String departureId,
      String mfcId,
      Instant closedAt,
      @Nullable String sourceRef) {
    String hash =
        ReceiptService.sha(
            trfId + "|" + departureId + "|" + mfcId + "|" + closedAt + "|" + sourceRef);
    return tx.execute(
        s -> {
          TransitRow t = transit(trfId, departureId);
          List<Map<String, Object>> prior =
              jdbc.queryForList(
                  "SELECT body_hash, closed_at FROM transit_closure WHERE trf_id = ? FOR UPDATE",
                  trfId);
          if (!prior.isEmpty()) {
            if (!hash.equals(prior.get(0).get("body_hash"))) {
              throw new PmartException(
                  "TRANSIT_CLOSURE_CONFLICT",
                  HttpStatus.CONFLICT,
                  "TRF " + trfId + " 입고 종결이 다른 내용으로 이미 기록돼 있습니다.",
                  false,
                  List.of());
            }
            return new Closure(trfId, closedAt, receipts.unreconciled(trfId));
          }
          if (!mfcId.equals(t.toMfcId())) {
            throw new PmartException(
                ErrorCode.VALIDATION_FAILED, "TRF " + trfId + " 의 MFC 는 " + t.toMfcId() + " 입니다.");
          }
          jdbc.update(
              "INSERT INTO transit_closure (trf_id, departure_id, mfc_id, closed_at, source_ref,"
                  + " source_system, body_hash, recorded_at) VALUES (?,?,?,?,?,?,?,?)",
              trfId,
              departureId,
              mfcId,
              Timestamp.from(closedAt),
              sourceRef,
              sourceSystem,
              hash,
              Timestamp.from(ledger.now()));
          return new Closure(trfId, closedAt, receipts.unreconciled(trfId));
        });
  }

  // ---------- 운송 손실 ----------

  public Outcome<LossResult> post(
      String requestId,
      String sourceSystem,
      String cdcId,
      String trfId,
      String departureId,
      String reason,
      @Nullable String incidentRef,
      @Nullable String approvalRef,
      List<LossLine> lines) {
    Operator op = Operator.forCenter(cdcId, sourceSystem);
    Map<String, Integer> bySku = new LinkedHashMap<>();
    for (LossLine l : lines) {
      if (l.lossQty() <= 0) {
        throw new PmartException(ErrorCode.VALIDATION_FAILED, "lossQty 는 1 이상입니다.");
      }
      bySku.merge(l.sku(), l.lossQty(), Integer::sum);
    }
    // 손실 ID — 같은 운영자·같은 요청 키면 같다(승인 targetRef·원결과 요청 ID, 64자 안)
    String lossId =
        "TLOSS-"
            + ReceiptService.sha(trfId + "|" + op.id() + "|" + sourceSystem + "|" + requestId)
                .substring(0, 32);
    Map<String, Object> req = new LinkedHashMap<>();
    req.put("cdcId", cdcId);
    req.put("trfId", trfId);
    req.put("departureId", departureId);
    req.put("reason", reason);
    req.put("incidentRef", String.valueOf(incidentRef));
    req.put("approvalRef", String.valueOf(approvalRef));
    req.put("lines", lines);
    Integer done =
        jdbc.queryForObject(
            "SELECT count(*) FROM transit_loss WHERE loss_id = ?", Integer.class, lossId);
    if (done != null && done > 0) {
      // 이미 반영한 요청 — 앞 검사·관문 없이 저장 결과 재생(다른 본문이면 IDEMPOTENCY_CONFLICT)
      return idempotency.execute(
          op.key(requestId, OP_LOSS),
          req,
          LossResult.class,
          () -> {
            throw PmartException.invalidState("손실 " + lossId + " 은 이미 반영됐습니다.");
          });
    }
    // 앞 검사(잠금 없이) — 승인 관문 전에 명백한 거절을 먼저
    TransitRow pre = transit(trfId, departureId);
    if (!cdcId.equals(pre.cdcId())) {
      throw PmartException.notFound(
          "TRANSIT_NOT_FOUND", "센터 " + cdcId + " 의 TRF " + trfId + " 가 없습니다.");
    }
    requireClosed(trfId);
    Map<List<String>, Integer> byLot = new LinkedHashMap<>();
    lines.forEach(l -> byLot.merge(List.of(l.sku(), l.lot()), l.lossQty(), Integer::sum));
    byLot.forEach(
        (k, q) -> {
          Integer room =
              jdbc.queryForObject(
                  "SELECT COALESCE(SUM(q - normal_qty - quarantined_qty - pending_qty - loss_qty), 0)"
                      + " FROM transit_line WHERE trf_id = ? AND sku = ? AND lot = ?",
                  Integer.class,
                  trfId,
                  k.get(0),
                  k.get(1));
          if (room == null || q > room) {
            throw exceeds(k.get(0), k.get(1), q, room == null ? 0 : room);
          }
        });
    List<Line> gateLines = new ArrayList<>();
    bySku.forEach((sku, q) -> gateLines.add(new Line(sku, q, q)));
    // 승인 관문은 트랜잭션 밖(pricing·approval-service 호출 — 같은 요청이면 같은 useRef)
    String ref =
        gate.require(
            cdcId, "TRANSPORT_LOSS", lossId, op.id(), "CDC 운송 손실 " + trfId, gateLines, approvalRef);
    return idempotency.execute(
        op.key(requestId, OP_LOSS),
        req,
        LossResult.class,
        () -> apply(op, lossId, trfId, reason, incidentRef, ref, lines));
  }

  private LossResult apply(
      Operator op,
      String lossId,
      String trfId,
      String reason,
      @Nullable String incidentRef,
      @Nullable String approvalRef,
      List<LossLine> lines) {
    Instant at = ledger.now();
    jdbc.query("SELECT trf_id FROM transit WHERE trf_id = ? FOR UPDATE", rs -> {}, trfId);
    TransitRow t = ledgerStore.findTransit(trfId).orElseThrow();
    requireClosed(trfId);
    List<Delta> deltas = new ArrayList<>();
    int total = 0;
    for (LossLine l : lines) {
      List<Object[]> dl =
          jdbc.query(
              "SELECT departure_line_id, q - normal_qty - quarantined_qty - pending_qty - loss_qty"
                  + " FROM transit_line WHERE trf_id = ? AND sku = ? AND lot = ? ORDER BY line_no DESC FOR UPDATE",
              (rs, i) -> new Object[] {rs.getString(1), rs.getInt(2)},
              trfId,
              l.sku(),
              l.lot());
      int room = dl.stream().mapToInt(o -> (Integer) o[1]).sum();
      if (l.lossQty() > room) {
        throw exceeds(l.sku(), l.lot(), l.lossQty(), room);
      }
      int need = l.lossQty();
      for (Object[] o : dl) {
        int take = Math.min(need, (Integer) o[1]);
        if (take > 0) {
          jdbc.update(
              "UPDATE transit_line SET loss_qty = loss_qty + ? WHERE departure_line_id = ?",
              take,
              o[0]);
          o[1] = (Integer) o[1] - take;
          need -= take;
        }
      }
      deltas.add(new Delta(l.sku(), l.lot(), null, "TRANSIT", -l.lossQty()));
      total += l.lossQty();
    }
    String commitId =
        ledger.record(lossId, "TRANSPORT_LOSS", op.sourceSystem(), trfId, t.cdcId(), at, deltas);
    int unreconciled = receipts.unreconciled(trfId);
    String state = unreconciled == 0 ? "TRANSIT_RECONCILED" : t.state();
    jdbc.update(
        "UPDATE transit SET state = ?, reconciled_at = CASE WHEN ? = 'TRANSIT_RECONCILED'"
            + " THEN CAST(? AS timestamptz) ELSE reconciled_at END WHERE trf_id = ?",
        state,
        state,
        Timestamp.from(at),
        trfId);
    jdbc.update(
        "INSERT INTO transit_loss (loss_id, trf_id, cdc_id, loss_qty, lines, reason, incident_ref,"
            + " approval_ref, commit_id, actor_id, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
        lossId,
        trfId,
        t.cdcId(),
        total,
        mapper.writeValueAsString(lines),
        reason,
        incidentRef,
        approvalRef,
        commitId,
        op.id(),
        Timestamp.from(at));
    receipts.publish(t, null, commitId, state, unreconciled, List.of(), at);
    return new LossResult(trfId, commitId, total, unreconciled, state, approvalRef);
  }

  private TransitRow transit(String trfId, String departureId) {
    TransitRow t =
        ledgerStore
            .findTransit(trfId)
            .orElseThrow(
                () ->
                    PmartException.notFound(
                        "DEPARTURE_NOT_POSTED", "TRF " + trfId + " 의 출발(TOUT)이 없습니다."));
    if (!departureId.equals(t.departureId())) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED,
          "TRF " + trfId + " 의 출발은 " + t.departureId() + " 입니다(받은 값 " + departureId + ").");
    }
    return t;
  }

  private static PmartException exceeds(String sku, String lot, int loss, int room) {
    return new PmartException(
        "LOSS_EXCEEDS_UNRECONCILED",
        HttpStatus.CONFLICT,
        sku + "·" + lot + " 손실 " + loss + " 이 미정산 " + room + " 보다 큽니다.",
        false,
        List.of(new PmartException.Detail("unreconciledQty", String.valueOf(room))));
  }

  private void requireClosed(String trfId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM transit_closure WHERE trf_id = ?", Integer.class, trfId);
    if (n == null || n == 0) {
      throw new PmartException(
          "MFC_RECEIPT_OPEN",
          HttpStatus.CONFLICT,
          "MFC 가 TRF " + trfId + " 입고를 아직 종결하지 않았습니다 — 받는 중일 수 있는 잔량은 손실로 종결하지 않습니다.",
          false,
          List.of());
    }
  }
}
