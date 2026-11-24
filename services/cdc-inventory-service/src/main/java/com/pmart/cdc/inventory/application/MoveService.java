package com.pmart.cdc.inventory.application;

import com.pmart.cdc.inventory.application.Ledger.CommandResult;
import com.pmart.cdc.inventory.infrastructure.AllocationStore;
import com.pmart.cdc.inventory.infrastructure.AllocationStore.AllocationRow;
import com.pmart.cdc.inventory.infrastructure.AllocationStore.LotRow;
import com.pmart.cdc.inventory.infrastructure.LedgerStore.Delta;
import com.pmart.cdc.inventory.infrastructure.StockStore;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.Outcome;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 내부 이동 전기(MOV·적치·재배치, 계약 0.3.0). 같은 SKU·LOT·품질 상태로 위치만 바꾸고 센터 총량·LOT 최초 인수시각은 그대로 둔다. 줄에
 * allocationId 가 있으면 그 할당이 원위치에 잡아 둔 예약을 현물과 함께 옮긴다(팔레트 랙 → 피킹 선반 보충 — 예약 합은 그대로, CDC-F-008). 위치
 * 상태는 cdc-location 이 공간 선점 때 판단한다.
 */
@Service
public class MoveService {

  static final String OP_MOVE = "cdcInventory:move";
  static final Set<String> QUALITIES = Set.of("RELEASED", "HELD", "QUARANTINED");

  public record MoveLine(
      String sku,
      String lot,
      String fromLocationCode,
      String toLocationCode,
      int qty,
      @Nullable String qualityState,
      @Nullable String allocationId) {
    String quality() {
      return qualityState == null ? "RELEASED" : qualityState;
    }
  }

  public record Move(
      String cdcId,
      String moveKind,
      String sourceRef,
      @Nullable String spaceReservationId,
      List<MoveLine> lines) {}

  private final StockStore stock;
  private final AllocationStore allocations;
  private final RequestIdempotency idempotency;
  private final Ledger ledger;

  public MoveService(
      StockStore stock,
      AllocationStore allocations,
      RequestIdempotency idempotency,
      Ledger ledger) {
    this.stock = stock;
    this.allocations = allocations;
    this.idempotency = idempotency;
    this.ledger = ledger;
  }

  public Outcome<CommandResult> move(String requestId, String sourceSystem, Move cmd) {
    validate(cmd.lines());
    return idempotency.execute(
        Ledger.key(sourceSystem, requestId, OP_MOVE),
        cmd,
        CommandResult.class,
        () -> {
          Instant at = ledger.now();
          // 잠금 순서: 할당(ID 순) → SKU 현물(SKU 순)
          Map<String, AllocationRow> allocs = new HashMap<>();
          for (String id :
              new TreeSet<>(
                  cmd.lines().stream()
                      .map(MoveLine::allocationId)
                      .filter(x -> x != null)
                      .toList())) {
            AllocationRow a =
                allocations
                    .find(id, true)
                    .orElseThrow(
                        () -> PmartException.notFound("NOT_FOUND", "할당 " + id + " 이 없습니다."));
            if (!a.cdcId().equals(cmd.cdcId())) {
              throw PmartException.invalidState(
                  "할당 " + id + " 은 센터 " + cmd.cdcId() + " 의 할당이 아닙니다.");
            }
            if (!AllocationService.ACTIVE.contains(a.state())) {
              throw PmartException.invalidState("할당 상태 " + a.state() + " 에서는 예약을 옮길 수 없습니다.");
            }
            allocs.put(id, a);
          }
          for (String sku : new TreeSet<>(cmd.lines().stream().map(MoveLine::sku).toList())) {
            stock.lockBalances(cmd.cdcId(), sku);
          }
          List<Delta> deltas = new ArrayList<>();
          int total = 0;
          for (int i = 0; i < cmd.lines().size(); i++) {
            MoveLine l = cmd.lines().get(i);
            int reserved = 0;
            LotRow held = null;
            if (l.allocationId() != null) {
              held =
                  allocations.lots(l.allocationId()).stream()
                      .filter(
                          x ->
                              x.sku().equals(l.sku())
                                  && x.lot().equals(l.lot())
                                  && x.locationCode().equals(l.fromLocationCode()))
                      .findFirst()
                      .orElse(null);
              if (held == null || held.remaining() < l.qty()) {
                throw insufficient(
                    i,
                    "할당 "
                        + l.allocationId()
                        + " 이 "
                        + l.fromLocationCode()
                        + " 에 잡아 둔 예약("
                        + (held == null ? 0 : held.remaining())
                        + ")보다 많이 옮길 수 없습니다.");
              }
              reserved = l.qty();
            }
            if (!stock.moveOut(
                cmd.cdcId(),
                l.sku(),
                l.lot(),
                l.fromLocationCode(),
                l.quality(),
                l.qty(),
                reserved,
                at)) {
              throw insufficient(
                  i,
                  l.fromLocationCode()
                      + " 의 "
                      + l.sku()
                      + "/"
                      + l.lot()
                      + "("
                      + l.quality()
                      + ") "
                      + (reserved > 0 ? "예약" : "예약 안 된 현물")
                      + "이 "
                      + l.qty()
                      + " 보다 적습니다.");
            }
            stock.moveIn(
                cmd.cdcId(),
                l.sku(),
                l.lot(),
                l.toLocationCode(),
                l.quality(),
                l.qty(),
                reserved,
                at);
            if (held != null) {
              allocations.move(l.allocationId(), held, l.toLocationCode(), l.qty());
            }
            deltas.add(new Delta(l.sku(), l.lot(), l.fromLocationCode(), "ON_HAND", -l.qty()));
            deltas.add(new Delta(l.sku(), l.lot(), l.toLocationCode(), "ON_HAND", l.qty()));
            if (reserved > 0) {
              deltas.add(new Delta(l.sku(), l.lot(), l.fromLocationCode(), "RESERVED", -reserved));
              deltas.add(new Delta(l.sku(), l.lot(), l.toLocationCode(), "RESERVED", reserved));
            }
            if (!"RELEASED".equals(l.quality())) {
              deltas.add(new Delta(l.sku(), l.lot(), l.fromLocationCode(), "HELD", -l.qty()));
              deltas.add(new Delta(l.sku(), l.lot(), l.toLocationCode(), "HELD", l.qty()));
            }
            total += l.qty();
          }
          String commitId =
              ledger.record(
                  requestId,
                  "MOVE",
                  sourceSystem,
                  cmd.moveKind() + ":" + cmd.sourceRef(),
                  cmd.cdcId(),
                  at,
                  deltas);
          return new CommandResult(requestId, commitId, total, 0, at);
        });
  }

  static void validate(List<MoveLine> lines) {
    List<PmartException.Detail> bad = new ArrayList<>();
    Set<String> keys = new HashSet<>();
    for (int i = 0; i < lines.size(); i++) {
      MoveLine l = lines.get(i);
      String f = "lines[" + i + "]";
      if (l.fromLocationCode().equals(l.toLocationCode())) {
        bad.add(new PmartException.Detail(f + ".toLocationCode", "SAME_LOCATION"));
      }
      if (!QUALITIES.contains(l.quality())) {
        bad.add(new PmartException.Detail(f + ".qualityState", "NOT_MOVABLE"));
      }
      if (l.allocationId() != null && !"RELEASED".equals(l.quality())) {
        bad.add(new PmartException.Detail(f + ".allocationId", "RESERVATION_ONLY_ON_RELEASED"));
      }
      if (l.qty() <= 0) {
        bad.add(new PmartException.Detail(f + ".qty", "NOT_POSITIVE"));
      }
      String key =
          String.join(
              "|",
              l.sku(),
              l.lot(),
              l.fromLocationCode(),
              l.toLocationCode(),
              l.quality(),
              String.valueOf(l.allocationId()));
      if (!keys.add(key)) {
        bad.add(new PmartException.Detail(f, "DUPLICATE_LINE"));
      }
    }
    if (lines.isEmpty() || !bad.isEmpty()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "이동 줄이 올바르지 않습니다.", bad);
    }
  }

  private static PmartException insufficient(int i, String message) {
    return new PmartException(
        "INSUFFICIENT_STOCK",
        HttpStatus.CONFLICT,
        "lines[" + i + "] " + message,
        false,
        List.of(new PmartException.Detail("lines[" + i + "].qty", "INSUFFICIENT_STOCK")));
  }
}
