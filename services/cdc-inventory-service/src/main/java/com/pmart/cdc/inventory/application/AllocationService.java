package com.pmart.cdc.inventory.application;

import com.pmart.cdc.inventory.application.Ledger.CommandResult;
import com.pmart.cdc.inventory.domain.Fefo;
import com.pmart.cdc.inventory.domain.Fefo.Candidate;
import com.pmart.cdc.inventory.domain.Fefo.Pick;
import com.pmart.cdc.inventory.infrastructure.AllocationStore;
import com.pmart.cdc.inventory.infrastructure.AllocationStore.AllocationRow;
import com.pmart.cdc.inventory.infrastructure.AllocationStore.LineRow;
import com.pmart.cdc.inventory.infrastructure.AllocationStore.LotRow;
import com.pmart.cdc.inventory.infrastructure.LedgerStore;
import com.pmart.cdc.inventory.infrastructure.LedgerStore.Delta;
import com.pmart.cdc.inventory.infrastructure.LocationClient;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 승인 TRF 할당·해제(CDC-F-016, cdc-outbound → cdc-inventory). SKU 별 한도 max(0, E'−R'−S) 안에서 FEFO 다LOT 로
 * 예약 R 을 잡는다. 부분 할당 허용, 하나도 못 잡으면 FAILED. 결과는 AllocationResult 로 발행한다.
 */
@Service
public class AllocationService {

  static final String OP_ALLOCATE = "cdcInventory:allocate";
  static final String OP_RELEASE = "cdcInventory:release";
  static final Set<String> ACTIVE = Set.of("ALLOCATED", "PARTIAL");

  public record AllocateLine(String sku, int requestedQty, @Nullable Integer minRemainingDays) {}

  public record Allocate(String cdcId, String trfId, int trfVersion, List<AllocateLine> lines) {}

  public record ReleaseLine(String sku, @Nullable String lot, int qty) {}

  public record Release(
      String allocationId,
      String reason,
      @Nullable String sourceRef,
      @Nullable Integer expectedVersion,
      List<ReleaseLine> lines) {}

  public record LotView(String lot, String locationCode, int qty, @Nullable LocalDate expiresAt) {}

  public record LineView(
      String sku,
      int requestedQty,
      int allocatedQty,
      int shortQty,
      int releasedQty,
      int departedQty,
      List<LotView> lots) {}

  public record AllocationView(
      String allocationId,
      String cdcId,
      String trfId,
      int trfVersion,
      String state,
      int version,
      String commitId,
      Instant allocatedAt,
      List<LineView> lines) {}

  public record AllocateResult(String requestId, AllocationView allocation) {}

  private final StockStore stock;
  private final LocationClient locations;
  private final AllocationStore allocations;
  private final LedgerStore ledgerStore;
  private final SkuGate gate;
  private final RequestIdempotency idempotency;
  private final Ledger ledger;
  private final EventPublisher events;
  private final EventEnvelopeCodec codec;

  public AllocationService(
      StockStore stock,
      LocationClient locations,
      AllocationStore allocations,
      LedgerStore ledgerStore,
      SkuGate gate,
      RequestIdempotency idempotency,
      Ledger ledger,
      EventPublisher events,
      EventEnvelopeCodec codec) {
    this.stock = stock;
    this.locations = locations;
    this.allocations = allocations;
    this.ledgerStore = ledgerStore;
    this.gate = gate;
    this.idempotency = idempotency;
    this.ledger = ledger;
    this.events = events;
    this.codec = codec;
  }

  // ---------- 할당 ----------

  public Outcome<AllocateResult> allocate(String requestId, String sourceSystem, Allocate cmd) {
    Set<String> seen = new HashSet<>();
    List<PmartException.Detail> dup = new ArrayList<>();
    for (int i = 0; i < cmd.lines().size(); i++) {
      if (!seen.add(cmd.lines().get(i).sku())) {
        dup.add(new PmartException.Detail("lines[" + i + "].sku", "DUPLICATE_SKU"));
      }
    }
    if (cmd.lines().isEmpty() || !dup.isEmpty()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "할당 줄이 비었거나 같은 SKU 가 두 번 있습니다.", dup);
    }
    // 피킹 선반 여부는 트랜잭션·행 잠금 밖에서 미리 읽는다(위치 서비스를 기다리며 잠그지 않게). 그 사이 새로 생긴 칸은 선반 아님으로 본다
    Set<String> codes = new HashSet<>();
    for (AllocateLine l : cmd.lines()) {
      stock.balances(cmd.cdcId(), l.sku()).forEach(b -> codes.add(b.locationCode()));
    }
    Set<String> pickFaces = codes.isEmpty() ? Set.of() : locations.pickFaces(cmd.cdcId(), codes);
    return idempotency.execute(
        Ledger.key(sourceSystem, requestId, OP_ALLOCATE),
        cmd,
        AllocateResult.class,
        () -> {
          Instant at = ledger.now();
          LocalDate today = ledger.today();
          allocations.lockTrf(cmd.trfId());
          for (AllocationRow a : allocations.lockByTrf(cmd.trfId())) {
            if (a.trfVersion() == cmd.trfVersion()) {
              throw exists(
                  "TRF "
                      + cmd.trfId()
                      + " v"
                      + a.trfVersion()
                      + " 는 이미 할당했습니다: "
                      + a.allocationId());
            }
            if ("DEPARTED".equals(a.state())) {
              throw PmartException.invalidState("TRF " + cmd.trfId() + " 는 이미 출발 전기됐습니다.");
            }
            if (ACTIVE.contains(a.state())) {
              throw exists(
                  "TRF "
                      + cmd.trfId()
                      + " 의 다른 버전 할당("
                      + a.allocationId()
                      + ")이 살아 있습니다. 먼저 해제하세요.");
            }
          }
          if (ledgerStore.findTransit(cmd.trfId()).isPresent()) {
            throw PmartException.invalidState("TRF " + cmd.trfId() + " 는 이미 출발 전기됐습니다.");
          }
          List<String> skus = cmd.lines().stream().map(AllocateLine::sku).toList();
          Map<String, Integer> safety = gate.requireAllocatable(cmd.cdcId(), skus);

          String allocationId = Ledger.newId("ALC");
          List<Delta> deltas = new ArrayList<>();
          List<LotRow> lots = new ArrayList<>();
          int requested = 0;
          int allocated = 0;
          // 잠금 순서 고정(SKU 오름차순)
          Map<String, AllocateLine> bySku = new LinkedHashMap<>();
          new TreeSet<>(skus).forEach(s -> bySku.put(s, null));
          cmd.lines().forEach(l -> bySku.put(l.sku(), l));
          for (AllocateLine l : bySku.values()) {
            List<Candidate> candidates = new ArrayList<>();
            int e = 0;
            int r = 0;
            for (BalanceRow b : stock.lockBalances(cmd.cdcId(), l.sku())) {
              if (Fefo.RELEASED.equals(b.qualityState())
                  && Fefo.eligibleLot(
                      b.identification(), b.expiresAt(), today, l.minRemainingDays())) {
                candidates.add(
                    new Candidate(
                        b.lot(),
                        b.locationCode(),
                        b.expiresAt(),
                        b.firstReceivedAt(),
                        b.onHand(),
                        b.reserved(),
                        pickFaces.contains(b.locationCode())));
                e += b.onHand();
                r += b.reserved();
              }
            }
            int want = Math.min(l.requestedQty(), Fefo.cap(e, r, safety.get(l.sku())));
            requested += l.requestedQty();
            for (Pick p : Fefo.pick(candidates, want)) {
              Candidate c = p.candidate();
              if (!stock.addReserved(
                  cmd.cdcId(), l.sku(), c.lot(), c.locationCode(), p.qty(), at)) {
                throw PmartException.versionConflict("재고가 방금 바뀌었습니다. 다시 요청하세요.");
              }
              lots.add(
                  new LotRow(
                      l.sku(),
                      c.lot(),
                      c.locationCode(),
                      c.expiresAt(),
                      p.rank(),
                      p.qty(),
                      0,
                      0,
                      0,
                      0));
              deltas.add(new Delta(l.sku(), c.lot(), c.locationCode(), "RESERVED", p.qty()));
              allocated += p.qty();
            }
          }
          String state =
              allocated == 0 ? "FAILED" : allocated == requested ? "ALLOCATED" : "PARTIAL";
          String commitId =
              ledger.record(
                  requestId, "ALLOCATION", sourceSystem, cmd.trfId(), cmd.cdcId(), at, deltas);
          AllocationRow row =
              new AllocationRow(
                  allocationId, cmd.cdcId(), cmd.trfId(), cmd.trfVersion(), state, 1, commitId, at);
          allocations.insert(row);
          for (int i = 0; i < cmd.lines().size(); i++) {
            AllocateLine l = cmd.lines().get(i);
            allocations.insertLine(allocationId, new LineRow(l.sku(), l.requestedQty(), i));
          }
          lots.forEach(x -> allocations.insertLot(allocationId, x));
          AllocationView view = view(row, allocations.lines(allocationId), lots);
          publishResult(requestId, sourceSystem, view);
          return new AllocateResult(requestId, view);
        });
  }

  @Transactional(readOnly = true)
  public AllocationView get(String allocationId) {
    AllocationRow a =
        allocations
            .find(allocationId, false)
            .orElseThrow(
                () -> PmartException.notFound("NOT_FOUND", "할당 " + allocationId + " 이 없습니다."));
    return view(a, allocations.lines(allocationId), allocations.lots(allocationId));
  }

  // ---------- 해제 ----------

  public Outcome<CommandResult> release(String requestId, String sourceSystem, Release cmd) {
    return idempotency.execute(
        Ledger.key(sourceSystem, requestId, OP_RELEASE),
        cmd,
        CommandResult.class,
        () -> {
          Instant at = ledger.now();
          AllocationRow a =
              allocations
                  .find(cmd.allocationId(), true)
                  .orElseThrow(
                      () ->
                          PmartException.notFound(
                              "NOT_FOUND", "할당 " + cmd.allocationId() + " 이 없습니다."));
          if (cmd.expectedVersion() != null && cmd.expectedVersion() != a.version()) {
            throw PmartException.versionConflict(
                "할당이 이미 바뀌었습니다(기대 버전 " + cmd.expectedVersion() + ", 현재 " + a.version() + ").");
          }
          if (!ACTIVE.contains(a.state())) {
            throw PmartException.invalidState("할당 상태 " + a.state() + " 에서는 해제할 수 없습니다.");
          }
          List<LotRow> lots = allocations.lots(a.allocationId());
          Map<LotRow, Integer> plan = plan(lots, cmd.lines());
          for (String sku : new TreeSet<>(plan.keySet().stream().map(LotRow::sku).toList())) {
            stock.lockBalances(a.cdcId(), sku);
          }
          List<Delta> deltas = new ArrayList<>();
          int total = 0;
          for (Map.Entry<LotRow, Integer> p : plan.entrySet()) {
            LotRow l = p.getKey();
            int q = p.getValue();
            if (!stock.addReserved(a.cdcId(), l.sku(), l.lot(), l.locationCode(), -q, at)) {
              throw PmartException.invalidState(
                  "예약 "
                      + l.sku()
                      + "/"
                      + l.lot()
                      + "/"
                      + l.locationCode()
                      + " 이 장부와 맞지 않습니다(조사 필요).");
            }
            allocations.addReleased(a.allocationId(), l, q);
            deltas.add(new Delta(l.sku(), l.lot(), l.locationCode(), "RESERVED", -q));
            total += q;
          }
          List<LotRow> after = allocations.lots(a.allocationId());
          String state = nextState(a.state(), after);
          allocations.updateState(a.allocationId(), state);
          String commitId =
              ledger.record(
                  requestId,
                  "RELEASE",
                  sourceSystem,
                  cmd.reason() + (cmd.sourceRef() == null ? "" : ":" + cmd.sourceRef()),
                  a.cdcId(),
                  at,
                  deltas);
          return new CommandResult(requestId, commitId, total, a.version() + 1, at);
        });
  }

  /** 해제 계획: 줄이 없으면 남은 전량, 있으면 SKU(·LOT)별로 소비기한이 늦은 할당부터. */
  static Map<LotRow, Integer> plan(List<LotRow> lots, List<ReleaseLine> lines) {
    Map<LotRow, Integer> plan = new LinkedHashMap<>();
    if (lines.isEmpty()) {
      lots.stream().filter(l -> l.remaining() > 0).forEach(l -> plan.put(l, l.remaining()));
      return plan;
    }
    Map<LotRow, Integer> left = new HashMap<>();
    lots.forEach(l -> left.put(l, l.remaining()));
    for (int i = 0; i < lines.size(); i++) {
      ReleaseLine rl = lines.get(i);
      List<LotRow> match =
          lots.stream()
              .filter(l -> l.sku().equals(rl.sku()))
              .filter(l -> rl.lot() == null || l.lot().equals(rl.lot()))
              .sorted(Comparator.comparingInt(LotRow::fefoRank).reversed())
              .toList();
      int need = rl.qty();
      for (LotRow l : match) {
        int take = Math.min(need, left.get(l));
        if (take > 0) {
          left.merge(l, -take, Integer::sum);
          plan.merge(l, take, Integer::sum);
          need -= take;
        }
      }
      if (need > 0) {
        throw new PmartException(
            "RELEASE_EXCEEDS_ALLOCATED",
            HttpStatus.CONFLICT,
            "lines[" + i + "] 해제 수량이 남은 할당보다 큽니다(" + need + " 초과).",
            false,
            List.of(
                new PmartException.Detail("lines[" + i + "].qty", "RELEASE_EXCEEDS_ALLOCATED")));
      }
    }
    return plan;
  }

  /** 남은 할당이 0 이면 RELEASED(출발분이 있으면 DEPARTED), 아니면 그대로. */
  static String nextState(String current, List<LotRow> lots) {
    int remaining = lots.stream().mapToInt(LotRow::remaining).sum();
    if (remaining > 0) {
      return current;
    }
    return lots.stream().anyMatch(l -> l.departedQty() > 0) ? "DEPARTED" : "RELEASED";
  }

  static AllocationView view(AllocationRow a, List<LineRow> lines, List<LotRow> lots) {
    List<LineView> out = new ArrayList<>();
    for (LineRow line : lines) {
      List<LotRow> mine = lots.stream().filter(l -> l.sku().equals(line.sku())).toList();
      int alloc = mine.stream().mapToInt(LotRow::allocatedQty).sum();
      out.add(
          new LineView(
              line.sku(),
              line.requestedQty(),
              alloc,
              Math.max(0, line.requestedQty() - alloc),
              mine.stream().mapToInt(LotRow::releasedQty).sum(),
              mine.stream().mapToInt(LotRow::departedQty).sum(),
              // 위치별로 지금 잡혀 있던 양(옮긴 예약 반영). 다 옮겨 간 원위치 행은 빼고 보여 준다
              mine.stream()
                  .filter(l -> l.heldHere() > 0)
                  .sorted(
                      Comparator.comparingInt(LotRow::fefoRank).thenComparing(LotRow::locationCode))
                  .map(l -> new LotView(l.lot(), l.locationCode(), l.heldHere(), l.expiresAt()))
                  .toList()));
    }
    return new AllocationView(
        a.allocationId(),
        a.cdcId(),
        a.trfId(),
        a.trfVersion(),
        a.state(),
        a.version(),
        a.commitId(),
        a.allocatedAt(),
        out);
  }

  private void publishResult(String requestId, String sourceSystem, AllocationView v) {
    List<Map<String, Object>> lines = new ArrayList<>();
    int totalAlloc = 0;
    int totalShort = 0;
    for (LineView l : v.lines()) {
      if (l.lots().isEmpty()) {
        lines.add(eventLine(l.sku(), null, 0, l.shortQty(), null));
      }
      for (int i = 0; i < l.lots().size(); i++) {
        LotView lot = l.lots().get(i);
        // 부족분은 SKU 의 첫 줄에만 적는다(LOT 줄마다 반복하지 않음)
        lines.add(
            eventLine(l.sku(), lot.lot(), lot.qty(), i == 0 ? l.shortQty() : 0, lot.expiresAt()));
      }
      totalAlloc += l.allocatedQty();
      totalShort += l.shortQty();
    }
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("trfId", v.trfId());
    p.put("trfVersion", v.trfVersion());
    p.put("allocationId", v.allocationId());
    p.put("requestId", requestId);
    p.put("commitId", v.commitId());
    p.put("cdcId", v.cdcId());
    p.put("lines", lines);
    p.put("totalAllocatedQty", totalAlloc);
    p.put("totalShortQty", totalShort);
    p.put("result", "FAILED".equals(v.state()) ? "FAILED" : "SUCCESS");
    p.put("state", v.state());
    p.put("allocatedAt", v.allocatedAt().toString());
    events.publish(
        codec.create(
            events.sourceSystem(),
            "AllocationResult",
            1,
            "Allocation",
            v.allocationId(),
            v.version(),
            new EventEnvelope.Actor(EventEnvelope.ActorType.SYSTEM, sourceSystem),
            EventEnvelope.EventSource.REAL,
            p),
        Ledger.TOPIC);
  }

  private static Map<String, Object> eventLine(
      String sku, @Nullable String lot, int allocated, int shortQty, @Nullable LocalDate expiry) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("sku", sku);
    if (lot != null) {
      m.put("lot", lot);
    }
    m.put("allocatedQty", allocated);
    m.put("shortQty", shortQty);
    if (expiry != null) {
      m.put("expiryDate", expiry.toString());
    }
    return m;
  }

  private static PmartException exists(String message) {
    return new PmartException("ALLOCATION_EXISTS", HttpStatus.CONFLICT, message, false, List.of());
  }
}
