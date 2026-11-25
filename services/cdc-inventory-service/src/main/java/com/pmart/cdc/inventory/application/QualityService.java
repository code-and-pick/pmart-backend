package com.pmart.cdc.inventory.application;

import com.pmart.cdc.inventory.application.Ledger.CommandResult;
import com.pmart.cdc.inventory.infrastructure.AllocationStore;
import com.pmart.cdc.inventory.infrastructure.LedgerStore.Delta;
import com.pmart.cdc.inventory.infrastructure.StockStore;
import com.pmart.cdc.inventory.infrastructure.StockStore.BalanceRow;
import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.Outcome;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * 품질 보류·해제·폐기 반영(0.15.0, WMS CDC-F-028 — 사용자 2026-11-26 "재고 쪽 먼저"·"나중 예약부터 풀고 출고에 알림"·"출고대기분은 MFC
 * 처럼 건드리지 않음"). 판정·승인은 품질 쪽(cdc-quality, 다음)이 하고 상태·수량은 여기서만 바꾼다.
 *
 * <ul>
 *   <li>HOLD: 정상 → 보류(총현물 그대로). 남는 정상 현물이 예약보다 적어지면 그 칸에 늦게 잡힌 할당부터 예약을 풀고 {@code
 *       AllocationShortfall}(QUALITY_HOLD).
 *   <li>RELEASE: 보류 → 정상(approvalRef 필수).
 *   <li>DISPOSE: 보류 −q(실제 실행 수량, approvalRef·evidenceRef 필수).
 * </ul>
 *
 * 범위에 칸을 주지 않으면 그 LOT 의 모든 칸 — 출고대기(통로 91)는 빼고(이미 피킹한 것).
 */
@Service
public class QualityService {

  static final String OP = "cdcInventory:quality";
  static final String STAGING_AISLE = "91";

  public record Line(
      String sku, String lot, @Nullable String locationCode, @Nullable Integer qty) {}

  public record Cmd(
      String cdcId,
      String action,
      String sourceRef,
      @Nullable String approvalRef,
      @Nullable String evidenceRef,
      @Nullable String reason,
      List<Line> lines) {}

  private final StockStore stock;
  private final AllocationStore allocations;
  private final RequestIdempotency idempotency;
  private final Ledger ledger;
  private final EventPublisher events;
  private final EventEnvelopeCodec codec;

  public QualityService(
      StockStore stock,
      AllocationStore allocations,
      RequestIdempotency idempotency,
      Ledger ledger,
      EventPublisher events,
      EventEnvelopeCodec codec) {
    this.stock = stock;
    this.allocations = allocations;
    this.idempotency = idempotency;
    this.ledger = ledger;
    this.events = events;
    this.codec = codec;
  }

  public Outcome<CommandResult> post(String requestId, String sourceSystem, Cmd c) {
    validate(c);
    return idempotency.execute(
        Ledger.key(sourceSystem, requestId, OP),
        c,
        CommandResult.class,
        () -> apply(requestId, sourceSystem, c));
  }

  static void validate(Cmd c) {
    boolean release = "RELEASE".equals(c.action());
    boolean dispose = "DISPOSE".equals(c.action());
    if (!release && !dispose && !"HOLD".equals(c.action())) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "action 은 HOLD·RELEASE·DISPOSE 입니다.");
    }
    if ((release || dispose) && blank(c.approvalRef())) {
      throw new PmartException(
          ErrorCode.APPROVAL_REQUIRED,
          release ? "품질 해제에는 해제 승인(approvalRef)이 필요합니다." : "폐기에는 폐기 승인(approvalRef)이 필요합니다.");
    }
    if (dispose && blank(c.evidenceRef())) {
      throw new PmartException(
          "EVIDENCE_REQUIRED",
          ErrorCode.APPROVAL_REQUIRED.status(),
          "폐기는 실제 실행 증빙(evidenceRef)이 있어야 반영합니다.",
          false,
          List.of());
    }
    List<PmartException.Detail> bad = new ArrayList<>();
    for (int i = 0; i < c.lines().size(); i++) {
      Line l = c.lines().get(i);
      String f = "lines[" + i + "]";
      if (l.qty() != null && l.locationCode() == null) {
        bad.add(new PmartException.Detail(f + ".qty", "LOCATION_REQUIRED_WITH_QTY"));
      }
      if (dispose && (l.locationCode() == null || l.qty() == null)) {
        bad.add(new PmartException.Detail(f, "DISPOSE_NEEDS_LOCATION_AND_QTY"));
      }
      if (l.locationCode() != null && isStaging(l.locationCode())) {
        bad.add(new PmartException.Detail(f + ".locationCode", "STAGING_NOT_HELD"));
      }
    }
    if (!bad.isEmpty()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "품질 전기 줄이 올바르지 않습니다.", bad);
    }
  }

  static boolean isStaging(String locationCode) {
    String[] p = locationCode.split("-");
    return p.length == 7 && STAGING_AISLE.equals(p[3]);
  }

  private CommandResult apply(String requestId, String sourceSystem, Cmd c) {
    Instant at = ledger.now();
    String from =
        "RELEASE".equals(c.action()) || "DISPOSE".equals(c.action()) ? "HELD" : "RELEASED";
    Map<String, List<BalanceRow>> rows = new LinkedHashMap<>();
    for (String sku : new TreeSet<>(c.lines().stream().map(Line::sku).toList())) {
      rows.put(sku, stock.lockBalances(c.cdcId(), sku));
    }
    List<Delta> deltas = new ArrayList<>();
    List<Shortfall> shortfalls = new ArrayList<>();
    Map<List<String>, Integer> byLot = new LinkedHashMap<>();
    List<Map<String, Object>> adjLines = new ArrayList<>();
    int total = 0;
    for (int i = 0; i < c.lines().size(); i++) {
      Line l = c.lines().get(i);
      List<BalanceRow> scope =
          rows.get(l.sku()).stream()
              .filter(
                  b ->
                      b.lot().equals(l.lot())
                          && from.equals(b.qualityState())
                          && b.onHand() > 0
                          && (l.locationCode() == null
                              ? !isStaging(b.locationCode())
                              : b.locationCode().equals(l.locationCode())))
              .toList();
      int have = scope.stream().mapToInt(BalanceRow::onHand).sum();
      if (l.qty() != null && have < l.qty()) {
        throw new PmartException(
            "INSUFFICIENT_STOCK",
            ErrorCode.INSUFFICIENT_STOCK.status(),
            "lines["
                + i
                + "] "
                + l.locationCode()
                + " 의 "
                + l.sku()
                + "/"
                + l.lot()
                + " "
                + from
                + " 현물("
                + have
                + ")이 "
                + l.qty()
                + " 보다 적습니다.",
            false,
            List.of(new PmartException.Detail("lines[" + i + "].qty", "INSUFFICIENT_STOCK")));
      }
      for (BalanceRow b : scope) {
        int take = l.qty() == null ? b.onHand() : l.qty();
        switch (c.action()) {
          case "HOLD" -> {
            int free = b.onHand() - b.reserved();
            if (take > free) {
              release(c, b, take - free, at, deltas, shortfalls);
            }
            move(c.cdcId(), b, "RELEASED", "HELD", take, at);
            deltas.add(new Delta(b.sku(), b.lot(), b.locationCode(), "HELD", take));
          }
          case "RELEASE" -> {
            move(c.cdcId(), b, "HELD", "RELEASED", take, at);
            deltas.add(new Delta(b.sku(), b.lot(), b.locationCode(), "HELD", -take));
          }
          default -> {
            if (!stock.moveOut(
                c.cdcId(), b.sku(), b.lot(), b.locationCode(), "HELD", take, 0, at)) {
              throw PmartException.versionConflict("재고가 방금 바뀌었습니다 — 다시 보내 주세요.");
            }
            deltas.add(new Delta(b.sku(), b.lot(), b.locationCode(), "HELD", -take));
            deltas.add(new Delta(b.sku(), b.lot(), b.locationCode(), "ON_HAND", -take));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sku", b.sku());
            m.put("lot", b.lot());
            m.put("locCode", b.locationCode());
            m.put("executedDeltaQty", -take);
            m.put("reason", "DISPOSAL");
            adjLines.add(m);
          }
        }
        byLot.merge(List.of(b.sku(), b.lot()), take, Integer::sum);
        total += take;
      }
    }
    if (total == 0) {
      return new CommandResult(requestId, null, 0, 0, at);
    }
    String kind =
        switch (c.action()) {
          case "HOLD" -> "QUALITY_HOLD";
          case "RELEASE" -> "QUALITY_RELEASE";
          default -> "DISPOSAL";
        };
    String commitId =
        ledger.record(
            requestId, kind, sourceSystem, c.action() + ":" + c.sourceRef(), c.cdcId(), at, deltas);
    for (Shortfall s : shortfalls) {
      publishShortfall(c, s, at);
    }
    if ("DISPOSE".equals(c.action())) {
      publishDisposal(c, requestId, commitId, adjLines, total, at);
    } else {
      String scope = c.lines().stream().allMatch(l -> l.locationCode() == null) ? "CENTER" : "LOC";
      byLot.forEach((k, q) -> publishLot(c, k.get(0), k.get(1), q, scope, commitId, at));
    }
    return new CommandResult(requestId, commitId, total, 0, at);
  }

  private void move(String cdcId, BalanceRow b, String from, String to, int qty, Instant at) {
    if (!stock.moveOut(cdcId, b.sku(), b.lot(), b.locationCode(), from, qty, 0, at)) {
      throw PmartException.versionConflict("재고가 방금 바뀌었습니다 — 다시 보내 주세요.");
    }
    stock.moveIn(cdcId, b.sku(), b.lot(), b.locationCode(), to, qty, 0, at);
  }

  record Shortfall(String allocationId, String trfId, BalanceRow at, int qty) {}

  /** 보류로 남는 정상 현물이 예약보다 적으면 모자란 만큼 그 칸에 늦게 잡힌 할당부터 예약을 푼다(실사 손실과 같은 순서). */
  private void release(
      Cmd c, BalanceRow b, int need, Instant at, List<Delta> deltas, List<Shortfall> out) {
    for (AllocationStore.Holder h :
        allocations.holdersAt(c.cdcId(), b.sku(), b.lot(), b.locationCode())) {
      if (need <= 0) {
        break;
      }
      int take = Math.min(need, h.lot().remaining());
      if (take <= 0) {
        continue;
      }
      if (!stock.addReserved(c.cdcId(), b.sku(), b.lot(), b.locationCode(), -take, at)) {
        throw PmartException.versionConflict("예약이 방금 바뀌었습니다 — 다시 보내 주세요.");
      }
      allocations.addReleased(h.allocationId(), h.lot(), take);
      allocations.updateState(
          h.allocationId(),
          AllocationService.nextState(h.state(), allocations.lots(h.allocationId())));
      deltas.add(new Delta(b.sku(), b.lot(), b.locationCode(), "RESERVED", -take));
      out.add(new Shortfall(h.allocationId(), h.trfId(), b, take));
      need -= take;
    }
    if (need > 0) {
      throw PmartException.invalidState(
          b.locationCode() + " " + b.sku() + "/" + b.lot() + " 예약을 풀 할당을 찾지 못했습니다(조사 필요).");
    }
  }

  private void publishShortfall(Cmd c, Shortfall s, Instant at) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("cdcId", c.cdcId());
    p.put("trfId", s.trfId());
    p.put("allocationId", s.allocationId());
    p.put("sku", s.at().sku());
    p.put("lot", s.at().lot());
    p.put("locationCode", s.at().locationCode());
    p.put("qty", s.qty());
    p.put("reason", "QUALITY_HOLD");
    p.put("sourceRef", c.sourceRef());
    p.put("occurredAt", at.toString());
    emit("AllocationShortfall", "Allocation", s.allocationId(), p);
  }

  private void publishLot(
      Cmd c, String sku, String lot, int qty, String scope, String commitId, Instant at) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("cdcId", c.cdcId());
    p.put("sku", sku);
    p.put("lot", lot);
    p.put("scope", scope);
    p.put("reason", c.reason() == null || c.reason().isBlank() ? c.action() : c.reason());
    p.put("commitId", commitId);
    if ("HOLD".equals(c.action())) {
      p.put("blockedQty", qty);
      p.put("holdRequestId", c.sourceRef());
      p.put("blockedAt", at.toString());
      emit("LotBlocked", "Lot", c.cdcId() + ":" + sku + ":" + lot, p);
    } else {
      p.put("releasedQty", qty);
      p.put("holdReleaseId", c.sourceRef());
      p.put("releasedAt", at.toString());
      emit("LotReleased", "Lot", c.cdcId() + ":" + sku + ":" + lot, p);
    }
  }

  private void publishDisposal(
      Cmd c,
      String requestId,
      String commitId,
      List<Map<String, Object>> lines,
      int total,
      Instant at) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("centerType", "CDC");
    p.put("centerId", c.cdcId());
    p.put("adjId", c.sourceRef());
    p.put("approvalRef", Map.of("approvalId", c.approvalRef()));
    p.put("commitId", commitId);
    p.put("postingId", commitId);
    p.put("lines", lines);
    p.put("executedQty", total);
    p.put("executedAt", at.toString());
    emit("InventoryAdjustmentPosted", "InventoryAdjustment", c.sourceRef(), p);
  }

  private void emit(String type, String aggregate, String id, Map<String, Object> payload) {
    events.publish(
        codec.create(
            events.sourceSystem(),
            type,
            1,
            aggregate,
            id,
            1,
            new EventEnvelope.Actor(EventEnvelope.ActorType.SYSTEM, "cdc-inventory"),
            EventEnvelope.EventSource.REAL,
            payload),
        Ledger.TOPIC);
  }

  private static boolean blank(@Nullable String s) {
    return s == null || s.isBlank();
  }
}
