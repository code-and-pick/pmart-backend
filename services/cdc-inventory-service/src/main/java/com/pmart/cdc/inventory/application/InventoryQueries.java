package com.pmart.cdc.inventory.application;

import com.pmart.cdc.inventory.domain.Fefo;
import com.pmart.cdc.inventory.infrastructure.LedgerStore;
import com.pmart.cdc.inventory.infrastructure.LedgerStore.CommitRow;
import com.pmart.cdc.inventory.infrastructure.LedgerStore.Delta;
import com.pmart.cdc.inventory.infrastructure.LedgerStore.TransitLineRow;
import com.pmart.cdc.inventory.infrastructure.LedgerStore.TransitRow;
import com.pmart.cdc.inventory.infrastructure.StockStore;
import com.pmart.cdc.inventory.infrastructure.StockStore.BalanceRow;
import com.pmart.cdc.inventory.infrastructure.StockStore.SafetyRow;
import com.pmart.common.web.PmartException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 조회: 공급가능량(가용), 원결과(commit), Transit. 조회 사본이며 할당 성공을 보장하지 않는다. */
@Service
public class InventoryQueries {

  /** 위치·품질 상태별 현물·예약(스냅샷만). */
  public record Place(String locationCode, String qualityState, int onHand, int reserved) {}

  public record LotAvailability(
      String lot,
      @Nullable LocalDate expiresAt,
      @Nullable Integer remainingDays,
      String identification,
      int eligible,
      int reserved,
      int held,
      List<Place> places) {}

  public record AvailabilityView(
      String cdcId,
      String sku,
      Instant asOf,
      int onHand,
      int eligible,
      int reserved,
      int held,
      @Nullable SafetyRow safety,
      @Nullable Integer available,
      int transit,
      boolean handlingActive,
      List<LotAvailability> lots) {}

  public record Snapshot(
      String snapshotId, String cdcId, Instant asOf, List<AvailabilityView> items) {}

  public record CommitView(CommitRow commit, List<Delta> deltas) {}

  public record TransitView(TransitRow transit, List<TransitLineRow> lines) {}

  public record TransitPage(List<TransitView> items, @Nullable String nextCursor) {}

  private final StockStore stock;
  private final LedgerStore ledgerStore;
  private final SkuGate gate;
  private final SkuHandlingService handlings;
  private final Ledger ledger;

  public InventoryQueries(
      StockStore stock,
      LedgerStore ledgerStore,
      SkuGate gate,
      SkuHandlingService handlings,
      Ledger ledger) {
    this.stock = stock;
    this.ledgerStore = ledgerStore;
    this.gate = gate;
    this.handlings = handlings;
    this.ledger = ledger;
  }

  /** available = max(0, E − R − S). E 는 정상·VERIFIED·D ≥ 1 현물, R 은 거기 걸린 예약. */
  @Transactional(readOnly = true)
  public AvailabilityView availability(String cdcId, String sku) {
    gate.requireAllocatable(cdcId, List.of(sku));
    SafetyRow safety = gate.requireSafety(cdcId, sku);
    return compute(cdcId, sku, safety, true, false);
  }

  /**
   * 재고 스냅샷(0.4.0) — 지금 장부의 조회 사본. 취급 OFF·안전량 미설정도 거절하지 않고(available null), LOT 마다 위치별 현물·예약을 준다.
   * sku 를 비우면 현물 행이 있는 SKU 전부.
   */
  @Transactional(readOnly = true)
  public Snapshot snapshot(String cdcId, @Nullable String sku) {
    List<String> skus = sku == null ? stock.skusWithStock(cdcId) : List.of(sku);
    Map<String, Boolean> flags = handlings.enabledFlags(cdcId, skus);
    List<AvailabilityView> items = new ArrayList<>();
    for (String s : skus) {
      items.add(
          compute(
              cdcId,
              s,
              stock.safety(cdcId, s).orElse(null),
              Boolean.TRUE.equals(flags.get(s)),
              true));
    }
    return new Snapshot(Ledger.newId("SNAP"), cdcId, ledger.now(), items);
  }

  private AvailabilityView compute(
      String cdcId,
      String sku,
      @Nullable SafetyRow safety,
      boolean handlingActive,
      boolean withPlaces) {
    LocalDate today = ledger.today();
    Map<String, int[]> perLot = new LinkedHashMap<>(); // eligible, reserved, held
    Map<String, BalanceRow> lotInfo = new LinkedHashMap<>();
    Map<String, List<Place>> places = new LinkedHashMap<>();
    int onHand = 0;
    int e = 0;
    int rOnEligible = 0;
    int reserved = 0;
    int held = 0;
    for (BalanceRow b : stock.balances(cdcId, sku)) {
      int[] acc = perLot.computeIfAbsent(b.lot(), k -> new int[3]);
      lotInfo.putIfAbsent(b.lot(), b);
      if (withPlaces) {
        places
            .computeIfAbsent(b.lot(), k -> new ArrayList<>())
            .add(new Place(b.locationCode(), b.qualityState(), b.onHand(), b.reserved()));
      }
      onHand += b.onHand();
      reserved += b.reserved();
      acc[1] += b.reserved();
      if (!Fefo.RELEASED.equals(b.qualityState())) {
        held += b.onHand();
        acc[2] += b.onHand();
      } else if (Fefo.eligibleLot(b.identification(), b.expiresAt(), today, null)) {
        e += b.onHand();
        rOnEligible += b.reserved();
        acc[0] += b.onHand();
      }
    }
    List<LotAvailability> lots = new ArrayList<>();
    for (Map.Entry<String, int[]> x : perLot.entrySet()) {
      BalanceRow b = lotInfo.get(x.getKey());
      lots.add(
          new LotAvailability(
              x.getKey(),
              b.expiresAt(),
              Fefo.remainingDays(b.expiresAt(), today),
              b.identification(),
              x.getValue()[0],
              x.getValue()[1],
              x.getValue()[2],
              places.getOrDefault(x.getKey(), List.of())));
    }
    return new AvailabilityView(
        cdcId,
        sku,
        ledger.now(),
        onHand,
        e,
        reserved,
        held,
        safety,
        safety == null ? null : Fefo.cap(e, rOnEligible, safety.qty()),
        stock.transitQty(cdcId, sku),
        handlingActive,
        lots);
  }

  @Transactional(readOnly = true)
  public CommitView commit(String commitId) {
    CommitRow c =
        ledgerStore
            .findCommit(commitId)
            .orElseThrow(
                () -> PmartException.notFound("NOT_FOUND", "원결과 " + commitId + " 가 없습니다."));
    return new CommitView(c, ledgerStore.deltas(commitId));
  }

  @Transactional(readOnly = true)
  public TransitPage transits(
      @Nullable String trfId,
      @Nullable String departureId,
      @Nullable String state,
      @Nullable String cursor,
      int size) {
    List<TransitRow> rows = ledgerStore.listTransits(trfId, departureId, state, cursor, size + 1);
    boolean more = rows.size() > size;
    List<TransitView> items = new ArrayList<>();
    for (TransitRow t : more ? rows.subList(0, size) : rows) {
      items.add(new TransitView(t, ledgerStore.transitLines(t.trfId())));
    }
    return new TransitPage(items, more ? items.get(items.size() - 1).transit().trfId() : null);
  }
}
