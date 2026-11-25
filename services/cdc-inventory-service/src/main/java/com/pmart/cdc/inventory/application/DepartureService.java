package com.pmart.cdc.inventory.application;

import com.pmart.cdc.inventory.application.Ledger.CommandResult;
import com.pmart.cdc.inventory.infrastructure.AllocationStore;
import com.pmart.cdc.inventory.infrastructure.AllocationStore.AllocationRow;
import com.pmart.cdc.inventory.infrastructure.AllocationStore.LotRow;
import com.pmart.cdc.inventory.infrastructure.LedgerStore;
import com.pmart.cdc.inventory.infrastructure.LedgerStore.Delta;
import com.pmart.cdc.inventory.infrastructure.LedgerStore.TransitLineRow;
import com.pmart.cdc.inventory.infrastructure.LedgerStore.TransitRow;
import com.pmart.cdc.inventory.infrastructure.StockStore;
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
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * TOUT 출발 전기(CDC-F-021, TruckDepartureValidated 뒤 cdc-outbound 가 부른다): 현물 −q · 예약 −q · Transit +q 를
 * 한 거래로, TRF 당 1회. 결과는 TransferDeparted(cdc.inventory.v1)로 발행 — mfc-inbound 가 입고 예정으로 받는다.
 */
@Service
public class DepartureService {

  static final String OP_DEPART = "cdcInventory:departure";

  public record DepartLine(
      String departureLineId,
      String sku,
      String lot,
      @Nullable String locationCode,
      int qty,
      List<String> lpns) {}

  public record Depart(
      String cdcId,
      String departureId,
      String departureSource,
      @Nullable String toMfcId,
      String trfId,
      int trfVersion,
      String allocationId,
      @Nullable String shipmentManifestId,
      Instant departedAt,
      List<DepartLine> lines) {}

  private final StockStore stock;
  private final AllocationStore allocations;
  private final LedgerStore ledgerStore;
  private final RequestIdempotency idempotency;
  private final Ledger ledger;
  private final EventPublisher events;
  private final EventEnvelopeCodec codec;
  private final ReceiptService receipts;

  public DepartureService(
      StockStore stock,
      AllocationStore allocations,
      LedgerStore ledgerStore,
      RequestIdempotency idempotency,
      Ledger ledger,
      EventPublisher events,
      EventEnvelopeCodec codec,
      @org.springframework.context.annotation.Lazy ReceiptService receipts) {
    this.receipts = receipts;
    this.stock = stock;
    this.allocations = allocations;
    this.ledgerStore = ledgerStore;
    this.idempotency = idempotency;
    this.ledger = ledger;
    this.events = events;
    this.codec = codec;
  }

  public Outcome<CommandResult> depart(String requestId, String sourceSystem, Depart cmd) {
    Set<String> ids = new HashSet<>();
    List<PmartException.Detail> dup = new ArrayList<>();
    for (int i = 0; i < cmd.lines().size(); i++) {
      if (!ids.add(cmd.lines().get(i).departureLineId())) {
        dup.add(new PmartException.Detail("lines[" + i + "].departureLineId", "DUPLICATE"));
      }
    }
    if (cmd.lines().isEmpty() || !dup.isEmpty()) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED, "출발 줄이 비었거나 같은 departureLineId 가 있습니다.", dup);
    }
    return idempotency.execute(
        Ledger.key(sourceSystem, requestId, OP_DEPART),
        cmd,
        CommandResult.class,
        () -> {
          Instant at = ledger.now();
          allocations.lockTrf(cmd.trfId());
          AllocationRow a =
              allocations
                  .find(cmd.allocationId(), true)
                  .orElseThrow(
                      () ->
                          PmartException.notFound(
                              "NOT_FOUND", "할당 " + cmd.allocationId() + " 이 없습니다."));
          Optional<TransitRow> prior = ledgerStore.findTransit(cmd.trfId());
          if (prior.isPresent()) {
            return replayOrReject(requestId, cmd, ids, prior.get(), at);
          }
          if (!ledgerStore.existingLineIds(List.copyOf(ids)).isEmpty()) {
            throw PmartException.idempotencyConflict("이미 다른 출발에 쓰인 departureLineId 가 있습니다.");
          }
          if (!a.cdcId().equals(cmd.cdcId())
              || !a.trfId().equals(cmd.trfId())
              || a.trfVersion() != cmd.trfVersion()) {
            throw PmartException.invalidState(
                "할당 " + a.allocationId() + " 은 이 센터·TRF·버전의 할당이 아닙니다.");
          }
          if (!AllocationService.ACTIVE.contains(a.state())) {
            throw PmartException.invalidState("할당 상태 " + a.state() + " 에서는 출발 전기할 수 없습니다.");
          }
          List<LotRow> lots = allocations.lots(a.allocationId());
          for (String sku : new TreeSet<>(cmd.lines().stream().map(DepartLine::sku).toList())) {
            stock.lockBalances(a.cdcId(), sku);
          }
          Map<LotRow, Integer> left = new HashMap<>();
          lots.forEach(l -> left.put(l, l.remaining()));
          List<Delta> deltas = new ArrayList<>();
          List<TransitLineRow> transitLines = new ArrayList<>();
          List<Map<String, Object>> eventLines = new ArrayList<>();
          int total = 0;
          for (int i = 0; i < cmd.lines().size(); i++) {
            DepartLine dl = cmd.lines().get(i);
            List<LotRow> match =
                lots.stream()
                    .filter(l -> l.sku().equals(dl.sku()) && l.lot().equals(dl.lot()))
                    .filter(
                        l ->
                            dl.locationCode() == null || l.locationCode().equals(dl.locationCode()))
                    .sorted(Comparator.comparingInt(LotRow::fefoRank))
                    .toList();
            int need = dl.qty();
            String firstLocation = null;
            LocalDate expiry = null;
            for (LotRow l : match) {
              int take = Math.min(need, left.get(l));
              if (take <= 0) {
                continue;
              }
              if (!stock.depart(a.cdcId(), l.sku(), l.lot(), l.locationCode(), take, at)) {
                throw PmartException.invalidState(
                    "예약 "
                        + l.sku()
                        + "/"
                        + l.lot()
                        + "/"
                        + l.locationCode()
                        + " 이 장부와 맞지 않습니다(조사 필요).");
              }
              allocations.addDeparted(a.allocationId(), l, take);
              left.merge(l, -take, Integer::sum);
              deltas.add(new Delta(l.sku(), l.lot(), l.locationCode(), "ON_HAND", -take));
              deltas.add(new Delta(l.sku(), l.lot(), l.locationCode(), "RESERVED", -take));
              firstLocation = firstLocation == null ? l.locationCode() : firstLocation;
              expiry = expiry == null ? l.expiresAt() : expiry;
              need -= take;
              if (need == 0) {
                break;
              }
            }
            if (need > 0) {
              throw new PmartException(
                  "DEPARTURE_EXCEEDS_ALLOCATED",
                  HttpStatus.CONFLICT,
                  "lines[" + i + "] 출발 수량이 남은 할당보다 큽니다(" + need + " 초과).",
                  false,
                  List.of(
                      new PmartException.Detail(
                          "lines[" + i + "].qty", "DEPARTURE_EXCEEDS_ALLOCATED")));
            }
            deltas.add(new Delta(dl.sku(), dl.lot(), null, "TRANSIT", dl.qty()));
            transitLines.add(
                new TransitLineRow(
                    dl.departureLineId(),
                    cmd.trfId(),
                    i,
                    dl.sku(),
                    dl.lot(),
                    firstLocation,
                    dl.qty(),
                    0,
                    0,
                    0,
                    dl.lpns().isEmpty() ? null : String.join(",", dl.lpns())));
            Map<String, Object> el = new LinkedHashMap<>();
            el.put("sku", dl.sku());
            el.put("lot", dl.lot());
            if (dl.lpns().size() == 1) {
              el.put("lpn", dl.lpns().get(0));
            } else if (!dl.lpns().isEmpty()) {
              el.put("lpns", dl.lpns());
            }
            el.put("qty", dl.qty());
            if (expiry != null) {
              el.put("expiryDate", expiry.toString());
            }
            el.put("departureLineId", dl.departureLineId());
            eventLines.add(el);
            total += dl.qty();
          }
          String state = AllocationService.nextState(a.state(), allocations.lots(a.allocationId()));
          allocations.updateState(a.allocationId(), state);
          String commitId =
              ledger.record(
                  requestId, "TOUT", sourceSystem, cmd.departureId(), a.cdcId(), at, deltas);
          ledgerStore.insertTransit(
              new TransitRow(
                  cmd.trfId(),
                  cmd.trfVersion(),
                  cmd.departureId(),
                  a.cdcId(),
                  cmd.toMfcId(),
                  a.allocationId(),
                  "TRANSIT_OPEN",
                  cmd.departureSource(),
                  cmd.shipmentManifestId(),
                  commitId,
                  cmd.departedAt(),
                  null),
              transitLines);
          publish(cmd, commitId, eventLines, total, sourceSystem);
          receipts.applyPending(cmd.trfId()); // 0.11.0 — TOUT 보다 먼저 온 MFC 수령
          return new CommandResult(requestId, commitId, total, a.version() + 1, at);
        });
  }

  /** 같은 TRF 의 재전송: 같은 출발·같은 줄 묶음이면 기존 commitId, 아니면 거절(재차감 없음). */
  private CommandResult replayOrReject(
      String requestId, Depart cmd, Set<String> ids, TransitRow prior, Instant at) {
    List<TransitLineRow> lines = ledgerStore.transitLines(prior.trfId());
    Set<String> priorIds = new HashSet<>();
    lines.forEach(l -> priorIds.add(l.departureLineId()));
    if (prior.departureId().equals(cmd.departureId()) && priorIds.equals(ids)) {
      int q = lines.stream().mapToInt(TransitLineRow::q).sum();
      return new CommandResult(requestId, prior.commitId(), q, 0, at);
    }
    throw PmartException.invalidState(
        "TRF " + cmd.trfId() + " 는 이미 출발 " + prior.departureId() + " 로 전기됐습니다(같은 TRF 2회 출발 금지).");
  }

  private void publish(
      Depart cmd,
      String commitId,
      List<Map<String, Object>> lines,
      int total,
      String sourceSystem) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("trfId", cmd.trfId());
    p.put("trfVersion", cmd.trfVersion());
    p.put("departureId", cmd.departureId());
    p.put("departureLineIds", cmd.lines().stream().map(DepartLine::departureLineId).toList());
    p.put("commitId", commitId);
    p.put("fromCdc", cmd.cdcId());
    if (cmd.toMfcId() != null) {
      p.put("toMfc", cmd.toMfcId());
    }
    p.put("allocationId", cmd.allocationId());
    if (cmd.shipmentManifestId() != null) {
      p.put("shipmentManifestId", cmd.shipmentManifestId());
    }
    p.put("lines", lines);
    p.put("departedQty", total);
    p.put("departedAt", cmd.departedAt().toString());
    p.put("source", cmd.departureSource());
    events.publish(
        codec.create(
            events.sourceSystem(),
            "TransferDeparted",
            1,
            "Transit",
            cmd.trfId(),
            1,
            new EventEnvelope.Actor(EventEnvelope.ActorType.SYSTEM, sourceSystem),
            "SIMULATED".equals(cmd.departureSource())
                ? EventEnvelope.EventSource.SIMULATED
                : EventEnvelope.EventSource.REAL,
            p),
        Ledger.TOPIC);
  }
}
