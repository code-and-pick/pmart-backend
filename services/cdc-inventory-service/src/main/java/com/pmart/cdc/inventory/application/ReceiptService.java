package com.pmart.cdc.inventory.application;

import com.pmart.cdc.inventory.infrastructure.LedgerStore;
import com.pmart.cdc.inventory.infrastructure.LedgerStore.Delta;
import com.pmart.cdc.inventory.infrastructure.LedgerStore.TransitRow;
import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * MFC 수령 확인 → 운송 중 재고(Transit) 대사(0.11.0 — WMS CDC-F-024·025, 설계
 * docs/cdc/design/2026-11-25-receipt-transit.md, 사용자 2026-11-25: 미판정도 받은 수량·사건 + 복구용 API). MFC 가
 * 확정한 TIN 결과로만 줄인다. 같은 receiptId 한 번(다른 본문은 RECEIPT_CONFLICT). 줄별로 SKU·LOT 이 같은 출발 줄에 앞 줄부터 남은 수량만큼
 * — 넘거나 없는 줄은 그 줄만 CONFLICT(차감 0, 조사). 미정산 t 는 자동 손실로 지우지 않는다. TOUT 보다 먼저 온 사건은 PENDING_DEPARTURE 로
 * 두고 TOUT 때 처리한다.
 */
@Service
public class ReceiptService {

  public record ReceiptLine(
      String sku, @Nullable String lot, int normal, int quarantined, int pending) {
    int received() {
      return normal + quarantined + pending;
    }
  }

  public record Receipt(
      String receiptId,
      String trfId,
      String departureId,
      String mfcId,
      @Nullable String mfcCommitId,
      List<ReceiptLine> lines) {}

  public record Conflict(String sku, @Nullable String lot, int qty, String reason) {}

  public record Result(
      String receiptId,
      String state,
      @Nullable String commitId,
      int appliedQty,
      @Nullable String transitState,
      int unreconciledQty,
      List<Conflict> conflicts,
      Instant at) {}

  private final JdbcTemplate jdbc;
  private final LedgerStore ledgerStore;
  private final Ledger ledger;
  private final TransactionTemplate tx;
  private final EventPublisher events;
  private final EventEnvelopeCodec codec;
  private final ObjectMapper mapper;

  public ReceiptService(
      JdbcTemplate jdbc,
      LedgerStore ledgerStore,
      Ledger ledger,
      TransactionTemplate tx,
      EventPublisher events,
      EventEnvelopeCodec codec,
      ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.ledgerStore = ledgerStore;
    this.ledger = ledger;
    this.tx = tx;
    this.events = events;
    this.codec = codec;
    this.mapper = mapper;
  }

  /** 복구용 API — TOUT 이 없으면 404 DEPARTURE_NOT_POSTED(기록하지 않음). */
  public Result acknowledge(String sourceSystem, Receipt r) {
    validate(r);
    return tx.execute(s -> apply(sourceSystem, r, false));
  }

  /** 사건 구독 — inbox 와 같은 트랜잭션. TOUT 이 없으면 기다린다. */
  public void fromEvent(EventEnvelope e) {
    JsonNode p = e.payload();
    List<ReceiptLine> lines = new ArrayList<>();
    for (JsonNode l : p.path("lines")) {
      lines.add(
          new ReceiptLine(
              l.path("sku").asString(),
              l.hasNonNull("lot") ? l.get("lot").asString() : null,
              l.path("normalQty").asInt(0),
              l.path("quarantinedQty").asInt(0),
              l.path("pendingQty").asInt(0)));
    }
    Receipt r =
        new Receipt(
            p.path("receiptId").asString(),
            p.path("trfId").asString(),
            p.path("departureId").asString(),
            p.path("mfcId").asString(),
            p.hasNonNull("commitId") ? p.get("commitId").asString() : null,
            lines);
    validate(r);
    apply(e.sourceSystem(), r, true);
  }

  /** TOUT 직후(같은 트랜잭션) — 먼저 와 기다리던 수령을 처리한다. */
  void applyPending(String trfId) {
    List<String> bodies =
        jdbc.queryForList(
            "SELECT body FROM transit_receipt WHERE trf_id = ? AND state = 'PENDING_DEPARTURE'"
                + " ORDER BY created_at",
            String.class,
            trfId);
    for (String b : bodies) {
      Receipt r = mapper.readValue(b, Receipt.class);
      jdbc.update("DELETE FROM transit_receipt WHERE receipt_id = ?", r.receiptId());
      apply("cdc-inventory-service", r, true);
    }
  }

  private Result apply(String sourceSystem, Receipt r, boolean waitForDeparture) {
    Instant at = ledger.now();
    String body = mapper.writeValueAsString(r);
    String hash = sha(body);
    List<Map<String, Object>> prior =
        jdbc.queryForList(
            "SELECT body_hash, state, commit_id, applied_qty, conflicts, applied_at FROM transit_receipt"
                + " WHERE receipt_id = ? FOR UPDATE",
            r.receiptId());
    if (!prior.isEmpty()) {
      Map<String, Object> m = prior.get(0);
      if (!hash.equals(m.get("body_hash"))) {
        throw new PmartException(
            "RECEIPT_CONFLICT",
            HttpStatus.CONFLICT,
            "같은 receiptId " + r.receiptId() + " 에 다른 본문입니다 — 덮어쓰지 않습니다(조사).",
            false,
            List.of());
      }
      TransitRow t = ledgerStore.findTransit(r.trfId()).orElse(null);
      return new Result(
          r.receiptId(),
          (String) m.get("state"),
          (String) m.get("commit_id"),
          ((Number) m.get("applied_qty")).intValue(),
          t == null ? null : t.state(),
          t == null ? 0 : unreconciled(r.trfId()),
          readConflicts((String) m.get("conflicts")),
          at);
    }
    List<TransitRow> locked =
        jdbc.query(
            "SELECT trf_id FROM transit WHERE trf_id = ? FOR UPDATE",
            (rs, i) -> (TransitRow) null,
            r.trfId());
    TransitRow t = locked.isEmpty() ? null : ledgerStore.findTransit(r.trfId()).orElseThrow();
    if (t == null || !t.departureId().equals(r.departureId())) {
      if (!waitForDeparture) {
        throw PmartException.notFound(
            "DEPARTURE_NOT_POSTED", "출발 " + r.departureId() + " 의 TOUT 이 아직 없습니다 — 출발 전기 뒤 다시.");
      }
      insert(r, body, hash, "PENDING_DEPARTURE", null, 0, List.of(), at);
      return new Result(r.receiptId(), "PENDING_DEPARTURE", null, 0, null, 0, List.of(), at);
    }
    // 줄별 대사 — 같은 SKU·LOT 출발 줄에 앞 줄부터 남은 수량만큼
    List<Object[]> lines =
        jdbc.query(
            "SELECT departure_line_id, sku, lot, q - normal_qty - quarantined_qty - pending_qty - loss_qty AS left_qty"
                + " FROM transit_line WHERE trf_id = ? ORDER BY line_no FOR UPDATE",
            (rs, i) ->
                new Object[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4)},
            r.trfId());
    List<Delta> deltas = new ArrayList<>();
    List<Conflict> conflicts = new ArrayList<>();
    int applied = 0;
    for (ReceiptLine l : r.lines()) {
      int need = l.received();
      int fit = 0;
      for (Object[] o : lines) {
        if (o[1].equals(l.sku()) && (l.lot() == null || o[2].equals(l.lot()))) {
          fit += (Integer) o[3];
        }
      }
      if (fit < need) {
        conflicts.add(
            new Conflict(
                l.sku(), l.lot(), need, fit == 0 ? "출발 줄에 없는 SKU·LOT" : "출발 남은 수량 " + fit + " 초과"));
        continue;
      }
      int n = l.normal();
      int h = l.quarantined();
      int p = l.pending();
      for (Object[] o : lines) {
        if (need <= 0) {
          break;
        }
        if (!o[1].equals(l.sku()) || (l.lot() != null && !o[2].equals(l.lot()))) {
          continue;
        }
        int left = (Integer) o[3];
        int take = Math.min(left, need);
        if (take <= 0) {
          continue;
        }
        int dn = Math.min(n, take);
        int dh = Math.min(h, take - dn);
        int dp = take - dn - dh;
        n -= dn;
        h -= dh;
        p -= dp;
        jdbc.update(
            "UPDATE transit_line SET normal_qty = normal_qty + ?, quarantined_qty = quarantined_qty + ?,"
                + " pending_qty = pending_qty + ? WHERE departure_line_id = ?",
            dn,
            dh,
            dp,
            o[0]);
        deltas.add(new Delta(l.sku(), (String) o[2], null, "TRANSIT", -take));
        addReceiptLine(r.receiptId(), l.sku(), (String) o[2], dn, dh, dp);
        o[3] = left - take;
        need -= take;
        applied += take;
      }
    }
    String commitId =
        deltas.isEmpty()
            ? null
            : ledger.record(
                "TIN-" + r.receiptId(),
                "TIN_ACK",
                sourceSystem,
                r.receiptId(),
                t.cdcId(),
                at,
                deltas);
    int unreconciled = unreconciled(r.trfId());
    String state = unreconciled == 0 ? "TRANSIT_RECONCILED" : "RECEIPT_POSTED_AWAITING_ACK";
    jdbc.update(
        "UPDATE transit SET state = ?, reconciled_at = CASE WHEN ? = 'TRANSIT_RECONCILED' THEN ? ELSE reconciled_at END"
            + " WHERE trf_id = ?",
        state,
        state,
        Timestamp.from(at),
        r.trfId());
    insert(r, body, hash, "APPLIED", commitId, applied, conflicts, at);
    publish(t, r.receiptId(), commitId, state, unreconciled, conflicts, at);
    return new Result(
        r.receiptId(), "APPLIED", commitId, applied, state, unreconciled, conflicts, at);
  }

  private void addReceiptLine(String receiptId, String sku, String lot, int n, int h, int p) {
    jdbc.update(
        "INSERT INTO transit_receipt_line (receipt_id, sku, lot, normal_qty, quarantined_qty, pending_qty)"
            + " VALUES (?,?,?,?,?,?) ON CONFLICT (receipt_id, sku, lot) DO UPDATE SET"
            + " normal_qty = transit_receipt_line.normal_qty + EXCLUDED.normal_qty,"
            + " quarantined_qty = transit_receipt_line.quarantined_qty + EXCLUDED.quarantined_qty,"
            + " pending_qty = transit_receipt_line.pending_qty + EXCLUDED.pending_qty",
        receiptId,
        sku,
        lot,
        n,
        h,
        p);
  }

  // ---------- 수령 정정(0.13.0) ----------

  /** 정정 줄 — 증감(사건) 또는 누계와의 차이(API 가 바꿔 넣음). */
  public record CorrectionLine(
      String sku, String lot, int dNormal, int dQuarantined, int dPending) {
    int delta() {
      return dNormal + dQuarantined + dPending;
    }
  }

  /**
   * 복구용 API — lines 는 그 수령의 SKU·LOT 별 <b>정정 뒤 누계</b>(CDC-F-024 "δr = 정정 누계 − 기확인 누계"). 줄에 없는
   * SKU·LOT 은 그대로.
   */
  public Result correctTotals(
      String sourceSystem, String receiptId, String correctionId, List<ReceiptLine> totals) {
    return tx.execute(
        s -> {
          List<CorrectionLine> deltas = new ArrayList<>();
          for (ReceiptLine l : totals) {
            if (l.lot() == null) {
              throw new PmartException(ErrorCode.VALIDATION_FAILED, "정정 줄에는 LOT 이 필요합니다.");
            }
            int[] cur = receiptLine(receiptId, l.sku(), l.lot());
            deltas.add(
                new CorrectionLine(
                    l.sku(),
                    l.lot(),
                    l.normal() - cur[0],
                    l.quarantined() - cur[1],
                    l.pending() - cur[2]));
          }
          return correct(
              sourceSystem,
              receiptId,
              correctionId,
              deltas,
              sha(receiptId + "|totals|" + mapper.writeValueAsString(totals)));
        });
  }

  /** 사건 ReceiptCorrectionPosted — inbox 와 같은 트랜잭션. 줄(증감)이 없으면 줄에 나눌 수 없어 거절(재시도 → DLT, 조사). */
  public void correctionFromEvent(EventEnvelope e) {
    JsonNode p = e.payload();
    List<CorrectionLine> lines = new ArrayList<>();
    for (JsonNode l : p.path("lines")) {
      lines.add(
          new CorrectionLine(
              l.path("sku").asString(),
              l.path("lot").asString(""),
              l.path("deltaNormalQty").asInt(0),
              l.path("deltaQuarantinedQty").asInt(0),
              l.path("deltaPendingQty").asInt(0)));
    }
    if (lines.isEmpty()) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED,
          "수령 정정 " + p.path("correctionId").asString() + " 에 줄(SKU·LOT 증감)이 없습니다.");
    }
    String receiptId = p.path("receiptId").asString();
    correct(
        e.sourceSystem(),
        receiptId,
        p.path("correctionId").asString(),
        lines,
        sha(receiptId + "|deltas|" + mapper.writeValueAsString(lines)));
  }

  private int[] receiptLine(String receiptId, String sku, String lot) {
    List<int[]> r =
        jdbc.query(
            "SELECT normal_qty, quarantined_qty, pending_qty FROM transit_receipt_line"
                + " WHERE receipt_id = ? AND sku = ? AND lot = ?",
            (rs, i) -> new int[] {rs.getInt(1), rs.getInt(2), rs.getInt(3)},
            receiptId,
            sku,
            lot);
    return r.isEmpty() ? new int[3] : r.get(0);
  }

  /**
   * 정정 한 번(correctionId): 줄마다 수령 누계 + 증감이 0 아래면 그 줄 보류, 출발 줄 남은 수량을 넘는 증가도 보류. 맞는 줄만 출발 줄에 더하고(감소는
   * 뒤 줄부터) Transit −δr(δr 음수면 미정산을 다시 엶 — 실제 반송이 아니다).
   */
  private Result correct(
      String sourceSystem,
      String receiptId,
      String correctionId,
      List<CorrectionLine> lines,
      String hash) {
    Instant at = ledger.now();
    List<Map<String, Object>> prior =
        jdbc.queryForList(
            "SELECT body_hash, delta_qty, commit_id, conflicts FROM transit_receipt_correction"
                + " WHERE correction_id = ? FOR UPDATE",
            correctionId);
    List<Map<String, Object>> rc =
        jdbc.queryForList(
            "SELECT trf_id, state FROM transit_receipt WHERE receipt_id = ? FOR UPDATE", receiptId);
    if (rc.isEmpty() || !"APPLIED".equals(rc.get(0).get("state"))) {
      throw PmartException.notFound(
          "RECEIPT_NOT_APPLIED", "수령 " + receiptId + " 가 아직 대사되지 않았습니다 — 원 수령 뒤 정정.");
    }
    String trfId = (String) rc.get(0).get("trf_id");
    TransitRow t = ledgerStore.findTransit(trfId).orElseThrow();
    if (!prior.isEmpty()) {
      Map<String, Object> m = prior.get(0);
      if (!hash.equals(m.get("body_hash"))) {
        throw new PmartException(
            "RECEIPT_CONFLICT",
            HttpStatus.CONFLICT,
            "같은 correctionId " + correctionId + " 에 다른 본문입니다.",
            false,
            List.of());
      }
      return new Result(
          receiptId,
          "APPLIED",
          (String) m.get("commit_id"),
          ((Number) m.get("delta_qty")).intValue(),
          t.state(),
          unreconciled(trfId),
          readConflicts((String) m.get("conflicts")),
          at);
    }
    jdbc.query("SELECT trf_id FROM transit WHERE trf_id = ? FOR UPDATE", rs -> {}, trfId);
    List<Delta> deltas = new ArrayList<>();
    List<Conflict> conflicts = new ArrayList<>();
    int total = 0;
    for (CorrectionLine c : lines) {
      int[] cur = receiptLine(receiptId, c.sku(), c.lot());
      if (cur[0] + c.dNormal() < 0 || cur[1] + c.dQuarantined() < 0 || cur[2] + c.dPending() < 0) {
        conflicts.add(new Conflict(c.sku(), c.lot(), c.delta(), "정정 뒤 수령 누계가 0 보다 작음"));
        continue;
      }
      // 출발 줄(그 SKU·LOT)에 증감 — 증가는 남은 수량 안, 감소는 그 칸 수량 안
      List<Object[]> dl =
          jdbc.query(
              "SELECT departure_line_id, normal_qty, quarantined_qty, pending_qty,"
                  + " q - normal_qty - quarantined_qty - pending_qty - loss_qty FROM transit_line"
                  + " WHERE trf_id = ? AND sku = ? AND lot = ? ORDER BY line_no FOR UPDATE",
              (rs, i) ->
                  new Object[] {
                    rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5)
                  },
              trfId,
              c.sku(),
              c.lot());
      int room = dl.stream().mapToInt(o -> (Integer) o[4]).sum();
      if (dl.isEmpty() || c.delta() > room) {
        conflicts.add(
            new Conflict(
                c.sku(),
                c.lot(),
                c.delta(),
                dl.isEmpty() ? "출발 줄에 없는 SKU·LOT" : "출발 남은 수량 " + room + " 초과"));
        continue;
      }
      int[] want = {c.dNormal(), c.dQuarantined(), c.dPending()};
      // 감소 먼저(뒤 줄부터), 증가는 앞 줄부터 남은 수량 안
      for (int b = 0; b < 3; b++) {
        if (want[b] < 0) {
          for (int i = dl.size() - 1; i >= 0 && want[b] < 0; i--) {
            Object[] o = dl.get(i);
            int have = (Integer) o[1 + b];
            int take = Math.min(have, -want[b]);
            if (take > 0) {
              bump((String) o[0], b, -take);
              o[1 + b] = have - take;
              o[4] = (Integer) o[4] + take;
              want[b] += take;
            }
          }
        }
      }
      for (int b = 0; b < 3; b++) {
        for (int i = 0; i < dl.size() && want[b] > 0; i++) {
          Object[] o = dl.get(i);
          int roomHere = (Integer) o[4];
          int add = Math.min(roomHere, want[b]);
          if (add > 0) {
            bump((String) o[0], b, add);
            o[1 + b] = (Integer) o[1 + b] + add;
            o[4] = roomHere - add;
            want[b] -= add;
          }
        }
      }
      jdbc.update(
          "INSERT INTO transit_receipt_line (receipt_id, sku, lot, normal_qty, quarantined_qty, pending_qty)"
              + " VALUES (?,?,?,?,?,?) ON CONFLICT (receipt_id, sku, lot) DO UPDATE SET"
              + " normal_qty = EXCLUDED.normal_qty, quarantined_qty = EXCLUDED.quarantined_qty,"
              + " pending_qty = EXCLUDED.pending_qty",
          receiptId,
          c.sku(),
          c.lot(),
          cur[0] + c.dNormal(),
          cur[1] + c.dQuarantined(),
          cur[2] + c.dPending());
      if (c.delta() != 0) {
        deltas.add(new Delta(c.sku(), c.lot(), null, "TRANSIT", -c.delta()));
      }
      total += c.delta();
    }
    String commitId =
        deltas.isEmpty()
            ? null
            : ledger.record(
                "TINC-" + correctionId,
                "TIN_CORRECTION",
                sourceSystem,
                correctionId,
                t.cdcId(),
                at,
                deltas);
    int unreconciled = unreconciled(trfId);
    String state = unreconciled == 0 ? "TRANSIT_RECONCILED" : "RECEIPT_POSTED_AWAITING_ACK";
    jdbc.update(
        "UPDATE transit SET state = ?, reconciled_at = CASE WHEN ? = 'TRANSIT_RECONCILED' THEN CAST(? AS timestamptz) ELSE NULL END"
            + " WHERE trf_id = ?",
        state,
        state,
        Timestamp.from(at),
        trfId);
    jdbc.update(
        "INSERT INTO transit_receipt_correction (correction_id, receipt_id, trf_id, body_hash, delta_qty,"
            + " commit_id, conflicts, created_at) VALUES (?,?,?,?,?,?,?,?)",
        correctionId,
        receiptId,
        trfId,
        hash,
        total,
        commitId,
        conflicts.isEmpty() ? null : mapper.writeValueAsString(conflicts),
        Timestamp.from(at));
    publish(t, receiptId, commitId, state, unreconciled, conflicts, at);
    return new Result(receiptId, "APPLIED", commitId, total, state, unreconciled, conflicts, at);
  }

  private void bump(String departureLineId, int bucket, int d) {
    String col = bucket == 0 ? "normal_qty" : bucket == 1 ? "quarantined_qty" : "pending_qty";
    jdbc.update(
        "UPDATE transit_line SET " + col + " = " + col + " + ? WHERE departure_line_id = ?",
        d,
        departureLineId);
  }

  int unreconciled(String trfId) {
    Integer t =
        jdbc.queryForObject(
            "SELECT COALESCE(SUM(q - normal_qty - quarantined_qty - pending_qty - loss_qty), 0)"
                + " FROM transit_line WHERE trf_id = ?",
            Integer.class,
            trfId);
    return t == null ? 0 : t;
  }

  private void insert(
      Receipt r,
      String body,
      String hash,
      String state,
      @Nullable String commitId,
      int applied,
      List<Conflict> conflicts,
      Instant at) {
    jdbc.update(
        "INSERT INTO transit_receipt (receipt_id, trf_id, departure_id, mfc_id, mfc_commit_id, body_hash,"
            + " body, state, commit_id, received_qty, applied_qty, conflicts, created_at, applied_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        r.receiptId(),
        r.trfId(),
        r.departureId(),
        r.mfcId(),
        r.mfcCommitId(),
        hash,
        body,
        state,
        commitId,
        r.lines().stream().mapToInt(ReceiptLine::received).sum(),
        applied,
        conflicts.isEmpty() ? null : mapper.writeValueAsString(conflicts),
        Timestamp.from(at),
        "APPLIED".equals(state) ? Timestamp.from(at) : null);
  }

  private List<Conflict> readConflicts(@Nullable String raw) {
    if (raw == null || raw.isBlank()) {
      return List.of();
    }
    List<Conflict> out = new ArrayList<>();
    for (JsonNode n : mapper.readTree(raw)) {
      out.add(
          new Conflict(
              n.path("sku").asString(),
              n.hasNonNull("lot") ? n.get("lot").asString() : null,
              n.path("qty").asInt(),
              n.path("reason").asString()));
    }
    return out;
  }

  void publish(
      TransitRow t,
      @Nullable String receiptId,
      @Nullable String commitId,
      String state,
      int unreconciled,
      List<Conflict> conflicts,
      Instant at) {
    Integer q =
        jdbc.queryForObject(
            "SELECT COALESCE(SUM(q), 0) FROM transit_line WHERE trf_id = ?",
            Integer.class,
            t.trfId());
    Integer rr =
        jdbc.queryForObject(
            "SELECT COALESCE(SUM(normal_qty + quarantined_qty + pending_qty), 0) FROM transit_line WHERE trf_id = ?",
            Integer.class,
            t.trfId());
    Integer loss =
        jdbc.queryForObject(
            "SELECT COALESCE(SUM(loss_qty), 0) FROM transit_line WHERE trf_id = ?",
            Integer.class,
            t.trfId());
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("trfId", t.trfId());
    p.put("departureId", t.departureId());
    if (receiptId != null) {
      p.put("receiptId", receiptId);
    }
    if (commitId != null) {
      p.put("commitId", commitId);
    }
    p.put("departedQty", q);
    p.put("receivedQty", rr);
    p.put("approvedLossQty", loss);
    p.put("unreconciledQty", unreconciled);
    p.put(
        "state",
        "TRANSIT_RECONCILED".equals(state)
            ? "RECONCILED"
            : conflicts.isEmpty() ? "PENDING" : "CONFLICT");
    if (!conflicts.isEmpty()) {
      p.put(
          "conflictReason",
          String.join(
              "; ",
              conflicts.stream().map(c -> c.sku() + "/" + c.lot() + " " + c.reason()).toList()));
    }
    if ("TRANSIT_RECONCILED".equals(state)) {
      p.put("reconciledAt", at.toString());
    }
    events.publish(
        codec.create(
            events.sourceSystem(),
            "TransitReconciled",
            1,
            "Transit",
            t.trfId(),
            1,
            new EventEnvelope.Actor(EventEnvelope.ActorType.SYSTEM, "cdc-inventory"),
            EventEnvelope.EventSource.REAL,
            p),
        Ledger.TOPIC);
  }

  static void validate(Receipt r) {
    List<PmartException.Detail> bad = new ArrayList<>();
    if (r.lines().isEmpty()) {
      bad.add(new PmartException.Detail("lines", "EMPTY"));
    }
    for (int i = 0; i < r.lines().size(); i++) {
      ReceiptLine l = r.lines().get(i);
      if (l.normal() < 0 || l.quarantined() < 0 || l.pending() < 0 || l.received() <= 0) {
        bad.add(new PmartException.Detail("lines[" + i + "]", "QTY"));
      }
    }
    if (!bad.isEmpty()) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED, "수령 줄을 받을 수 없습니다(r = n + h + p > 0).", bad);
    }
  }

  static String sha(String s) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
