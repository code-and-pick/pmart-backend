package com.pmart.cdc.inventory.application;

import com.pmart.cdc.inventory.application.Ledger.CommandResult;
import com.pmart.cdc.inventory.infrastructure.LedgerStore.Delta;
import com.pmart.cdc.inventory.infrastructure.SkuProjectionStore;
import com.pmart.cdc.inventory.infrastructure.StockStore;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.Outcome;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 운영 개시 준비: 승인 초기 보유 INITIAL_BALANCE(CDC-F-003, 센터·SKU·LOT 당 1회)와 승인 안전량 S(CDC-F-008). 초기 보유는
 * 실입고·검수·적치 이력을 만들지 않는다. 근거 없는 MFC·이동 중 재고도 만들지 않는다.
 */
@Service
public class StockSetupService {

  static final String OP_INITIAL = "cdcInventory:initialBalance";
  static final String OP_SAFETY = "cdcInventory:safetyStock";
  static final Set<String> QUALITIES = Set.of("RELEASED", "HELD", "QUARANTINED");

  public record InitialLine(
      String sku,
      String lot,
      @Nullable String identification,
      @Nullable LocalDate expiresAt,
      String locationCode,
      int qty,
      String qualityState) {}

  public record InitialBalance(String cdcId, String approvalRef, List<InitialLine> lines) {}

  public record SafetyLine(String sku, int safetyQty) {}

  public record SafetyStocks(String cdcId, String approvalRef, List<SafetyLine> lines) {}

  private final StockStore stock;
  private final SkuProjectionStore projections;
  private final RequestIdempotency idempotency;
  private final Ledger ledger;

  public StockSetupService(
      StockStore stock,
      SkuProjectionStore projections,
      RequestIdempotency idempotency,
      Ledger ledger) {
    this.stock = stock;
    this.projections = projections;
    this.idempotency = idempotency;
    this.ledger = ledger;
  }

  public Outcome<CommandResult> initialBalance(
      String requestId, String sourceSystem, InitialBalance cmd) {
    requireApproval(cmd.approvalRef());
    validate(cmd.lines());
    return idempotency.execute(
        Ledger.key(sourceSystem, requestId, OP_INITIAL),
        cmd,
        CommandResult.class,
        () -> {
          Instant at = ledger.now();
          Set<String> skus = new LinkedHashSet<>();
          cmd.lines().forEach(l -> skus.add(l.sku()));
          Set<String> known = projections.findAll(skus).keySet();
          List<PmartException.Detail> missing = new ArrayList<>();
          for (int i = 0; i < cmd.lines().size(); i++) {
            if (!known.contains(cmd.lines().get(i).sku())) {
              missing.add(new PmartException.Detail("lines[" + i + "].sku", "SKU_NOT_FOUND"));
            }
          }
          if (!missing.isEmpty()) {
            throw new PmartException(
                "SKU_NOT_FOUND",
                HttpStatus.NOT_FOUND,
                "SKU 투영이 없는 줄이 있습니다(상품 승인 이벤트 수신 전이거나 없는 SKU).",
                false,
                missing);
          }
          Set<String> existing = stock.existingLots(cmd.cdcId(), skus);
          List<PmartException.Detail> dup = new ArrayList<>();
          for (int i = 0; i < cmd.lines().size(); i++) {
            InitialLine l = cmd.lines().get(i);
            if (existing.contains(l.sku() + "|" + l.lot())) {
              dup.add(new PmartException.Detail("lines[" + i + "]", "INITIAL_BALANCE_EXISTS"));
            }
          }
          if (!dup.isEmpty()) {
            throw new PmartException(
                "INITIAL_BALANCE_EXISTS",
                HttpStatus.CONFLICT,
                "이미 수불 이력이 있는 LOT 입니다. 이후 변경은 조정(ADJ)으로만 합니다.",
                false,
                dup);
          }
          Set<String> lotsDone = new HashSet<>();
          List<Delta> deltas = new ArrayList<>();
          int total = 0;
          for (InitialLine l : cmd.lines()) {
            if (lotsDone.add(l.sku() + "|" + l.lot())) {
              stock.insertLot(
                  cmd.cdcId(),
                  l.sku(),
                  l.lot(),
                  l.expiresAt(),
                  identification(l),
                  at,
                  "INITIAL_BALANCE");
            }
            stock.insertBalance(
                cmd.cdcId(), l.sku(), l.lot(), l.locationCode(), l.qualityState(), l.qty(), at);
            deltas.add(new Delta(l.sku(), l.lot(), l.locationCode(), "ON_HAND", l.qty()));
            if (!"RELEASED".equals(l.qualityState())) {
              deltas.add(new Delta(l.sku(), l.lot(), l.locationCode(), "HELD", l.qty()));
            }
            total += l.qty();
          }
          String commitId =
              ledger.record(
                  requestId,
                  "INITIAL_BALANCE",
                  sourceSystem,
                  cmd.approvalRef(),
                  cmd.cdcId(),
                  at,
                  deltas);
          return new CommandResult(requestId, commitId, total, 1, at);
        });
  }

  public Outcome<CommandResult> safetyStocks(
      String requestId, String sourceSystem, SafetyStocks cmd) {
    requireApproval(cmd.approvalRef());
    Set<String> seen = new HashSet<>();
    List<PmartException.Detail> bad = new ArrayList<>();
    for (int i = 0; i < cmd.lines().size(); i++) {
      SafetyLine l = cmd.lines().get(i);
      if (!seen.add(l.sku())) {
        bad.add(new PmartException.Detail("lines[" + i + "].sku", "DUPLICATE_SKU"));
      }
      if (l.safetyQty() < 0) {
        bad.add(new PmartException.Detail("lines[" + i + "].safetyQty", "NEGATIVE"));
      }
    }
    if (!bad.isEmpty()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "안전량 줄이 올바르지 않습니다.", bad);
    }
    return idempotency.execute(
        Ledger.key(sourceSystem, requestId, OP_SAFETY),
        cmd,
        CommandResult.class,
        () -> {
          Instant at = ledger.now();
          int changed = 0;
          for (SafetyLine l : cmd.lines()) {
            if (stock.upsertSafety(cmd.cdcId(), l.sku(), l.safetyQty(), cmd.approvalRef(), at)) {
              changed++;
            }
          }
          return new CommandResult(requestId, cmd.approvalRef(), changed, 0, at);
        });
  }

  private static String identification(InitialLine l) {
    return l.identification() == null ? "UNVERIFIED" : l.identification();
  }

  private static void requireApproval(@Nullable String approvalRef) {
    if (approvalRef == null || approvalRef.isBlank()) {
      throw new PmartException(
          "APPROVAL_REQUIRED",
          HttpStatus.UNPROCESSABLE_CONTENT,
          "본사 승인 근거(approvalRef)가 필요합니다.",
          false,
          List.of());
    }
  }

  /** 줄 검증: 품질 상태, 같은 SKU·LOT·위치 중복, 같은 LOT 의 기한·식별 일치. */
  private static void validate(List<InitialLine> lines) {
    List<PmartException.Detail> bad = new ArrayList<>();
    Set<String> keys = new HashSet<>();
    Map<String, InitialLine> lotHeads = new HashMap<>();
    for (int i = 0; i < lines.size(); i++) {
      InitialLine l = lines.get(i);
      String f = "lines[" + i + "]";
      if (!QUALITIES.contains(l.qualityState())) {
        bad.add(new PmartException.Detail(f + ".qualityState", "NOT_ALLOWED_FOR_INITIAL_BALANCE"));
      }
      if (l.qty() <= 0) {
        bad.add(new PmartException.Detail(f + ".qty", "NOT_POSITIVE"));
      }
      if (l.locationCode() == null || l.locationCode().isBlank()) {
        bad.add(new PmartException.Detail(f + ".locationCode", "REQUIRED"));
      }
      if (!keys.add(l.sku() + "|" + l.lot() + "|" + l.locationCode())) {
        bad.add(new PmartException.Detail(f, "DUPLICATE_SKU_LOT_LOCATION"));
      }
      InitialLine head = lotHeads.putIfAbsent(l.sku() + "|" + l.lot(), l);
      if (head != null
          && (!Objects.equals(head.expiresAt(), l.expiresAt())
              || !Objects.equals(identification(head), identification(l)))) {
        bad.add(new PmartException.Detail(f, "LOT_ATTRIBUTES_MISMATCH"));
      }
    }
    if (!bad.isEmpty()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "초기 보유 줄이 올바르지 않습니다.", bad);
    }
  }
}
