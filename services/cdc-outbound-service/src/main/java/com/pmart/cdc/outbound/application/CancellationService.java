package com.pmart.cdc.outbound.application;

import com.pmart.cdc.outbound.application.Support.CommandResult;
import com.pmart.cdc.outbound.infrastructure.CancellationStore;
import com.pmart.cdc.outbound.infrastructure.CancellationStore.CancelRow;
import com.pmart.cdc.outbound.infrastructure.InventoryClient;
import com.pmart.cdc.outbound.infrastructure.LpnStore;
import com.pmart.cdc.outbound.infrastructure.OutboundStore;
import com.pmart.cdc.outbound.infrastructure.OutboundStore.TransferLineRow;
import com.pmart.cdc.outbound.infrastructure.OutboundStore.TransferRow;
import com.pmart.cdc.outbound.infrastructure.OutboundStore.WaveRow;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * 미출발 취소·축소(0.7.0, WMS CDC-F-026) — **피킹 시작 전** 범위(사용자 2026-11-25). 순서는 문서대로 "작업 막기 → 할당 해제": 기록·작업
 * 중지는 짧은 트랜잭션, cdc-inventory 해제는 트랜잭션 밖(멱등 키 {@code outbound-cancel-<cancellationId>}), 결과는 다시 짧은
 * 트랜잭션. 해제 응답이 불명이면 UNKNOWN 으로 두고 같은 본문 재전송이 같은 키로 다시 부른다. 피킹이 시작된 TRF 는 실제 복귀·하차 결과가 필요해 거절한다.
 */
@Service
public class CancellationService {

  public static final Set<String> CANCEL_STATES = Set.of("CANCELLED", "CANCELLING", "CANCEL_HOLD");
  static final Set<String> NOT_STARTED_TASK =
      Set.of("CREATED", "ASSIGNED", "ACCEPTED", "CANCELLED");
  static final Set<String> INVENTORY_REASONS =
      Set.of("TRANSFER_CANCELLED", "TRANSFER_CHANGED", "PICK_SHORTAGE", "QUALITY_BLOCKED");
  private static final Logger log = LoggerFactory.getLogger(CancellationService.class);

  public record Reduce(String sku, int qty) {}

  public record Cancel(
      String cdcId,
      String trfId,
      String requestKey,
      String sourceSystem,
      @Nullable Integer expectedVersion,
      String reason,
      String scope,
      @Nullable String sourceRef,
      List<Reduce> lines) {}

  private record Prep(CancelRow row, boolean callInventory, @Nullable String allocationId) {}

  private final OutboundStore store;
  private final CancellationStore cancels;
  private final LpnStore lpns;
  private final InventoryClient inventory;
  private final WaveService waves;
  private final TransactionTemplate tx;
  private final Support support;

  public CancellationService(
      OutboundStore store,
      CancellationStore cancels,
      LpnStore lpns,
      InventoryClient inventory,
      WaveService waves,
      TransactionTemplate tx,
      Support support) {
    this.store = store;
    this.cancels = cancels;
    this.lpns = lpns;
    this.inventory = inventory;
    this.waves = waves;
    this.tx = tx;
    this.support = support;
  }

  public CommandResult cancel(Cancel c) {
    String by = Support.requireCenter(c.cdcId());
    validate(c);
    String hash = hash(c);
    Prep p = tx.execute(s -> prepare(c, hash, by));
    if (!p.callInventory()) {
      return result(p.row());
    }
    CancelRow r = p.row();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put(
        "reason", INVENTORY_REASONS.contains(r.reason()) ? r.reason() : "PRE_DEPARTURE_CANCEL");
    body.put("sourceRef", r.sourceRef() == null ? r.cancellationId() : r.sourceRef());
    if ("PARTIAL".equals(r.scope())) {
      body.put(
          "lines",
          r.releaseLines().entrySet().stream()
              .filter(e -> e.getValue() > 0)
              .map(e -> Map.<String, Object>of("sku", e.getKey(), "qty", e.getValue()))
              .toList());
    }
    JsonNode res;
    try {
      res = inventory.release(p.allocationId(), "outbound-cancel-" + r.cancellationId(), body);
    } catch (PmartException e) {
      Instant at = support.now();
      if (TransferService.unknown(e)) {
        tx.executeWithoutResult(
            s -> cancels.finish(r.cancellationId(), "UNKNOWN", 0, null, null, at));
      } else {
        String why = DepartureService.residualReason(e);
        log.warn(
            "미출발 취소 할당 해제 거절 — trfId={} cancellationId={}: {}", r.trfId(), r.cancellationId(), why);
        tx.executeWithoutResult(
            s -> {
              cancels.finish(r.cancellationId(), "REJECTED", 0, null, why, at);
              if ("FULL".equals(r.scope())) {
                store.setTransferState(r.trfId(), "CANCEL_HOLD", null, at);
              }
            });
      }
      return result(cancels.find(r.trfId(), r.sourceSystem(), r.requestKey()).orElseThrow());
    }
    int planned = r.releaseLines().values().stream().mapToInt(Integer::intValue).sum();
    int released = res.path("appliedQty").isNumber() ? res.path("appliedQty").asInt() : planned;
    String commitId = res.path("resultId").asString(null);
    Instant at = support.now();
    tx.executeWithoutResult(s -> apply(r, released, commitId, by, at));
    return result(cancels.find(r.trfId(), r.sourceSystem(), r.requestKey()).orElseThrow());
  }

  @Transactional(readOnly = true)
  public CancelRow get(String cdcId, String trfId, String requestKey) {
    Support.requireCenter(cdcId);
    TransferRow t =
        store
            .transfer(trfId, false)
            .filter(x -> x.cdcId().equals(cdcId))
            .orElseThrow(() -> PmartException.notFound("NOT_FOUND", "TRF " + trfId + " 가 없습니다."));
    return cancels
        .findByKey(t.trfId(), requestKey)
        .orElseThrow(
            () -> PmartException.notFound("NOT_FOUND", "취소 요청 " + requestKey + " 이 없습니다."));
  }

  /** 기록·검사·작업 막기(한 트랜잭션, TRF 행 잠금). 재고를 부를 필요가 없으면 여기서 끝낸다. */
  private Prep prepare(Cancel c, String hash, String by) {
    TransferRow t =
        store
            .transfer(c.trfId(), true)
            .filter(x -> x.cdcId().equals(c.cdcId()))
            .orElseThrow(
                () -> PmartException.notFound("NOT_FOUND", "TRF " + c.trfId() + " 가 없습니다."));
    var prior = cancels.find(c.trfId(), c.sourceSystem(), c.requestKey());
    if (prior.isPresent()) {
      CancelRow r = prior.get();
      if (!r.bodyHash().equals(hash)) {
        throw new PmartException(
            "IDEMPOTENCY_CONFLICT", HttpStatus.CONFLICT, "같은 멱등 키에 다른 본문입니다.", false, List.of());
      }
      boolean retry = "UNKNOWN".equals(r.state()) || "PENDING".equals(r.state());
      return new Prep(r, retry && r.needsRelease(), t.allocationId());
    }
    if (c.expectedVersion() != null && c.expectedVersion() != t.version()) {
      throw PmartException.versionConflict(
          "TRF 가 이미 바뀌었습니다(기대 버전 " + c.expectedVersion() + ", 현재 " + t.version() + ").");
    }
    if (lpns.departureIdOfTrf(t.trfId()).isPresent() || "DEPARTED".equals(t.state())) {
      throw PmartException.invalidState("TRF " + t.trfId() + " 는 이미 출발했습니다 — 출발 후에는 취소하지 않습니다.");
    }
    if (CANCEL_STATES.contains(t.state())) {
      throw PmartException.invalidState(
          "TRF " + t.trfId() + " 는 이미 취소 중이거나 취소됐습니다(" + t.state() + ").");
    }
    if (cancels.inProgress(t.trfId())) {
      throw conflict("CANCELLATION_IN_PROGRESS", "이 TRF 의 다른 취소 요청이 아직 끝나지 않았습니다. 그 결과를 먼저 확인하세요.");
    }
    if (TransferService.CHANGE_STATES.contains(t.state())) {
      throw conflict(
          "VERSION_CHANGE_IN_PROGRESS", "새 버전 대체가 아직 끝나지 않았습니다. 같은 본문을 다시 보내 대체를 끝낸 뒤 취소하세요.");
    }
    if ("ALLOCATING".equals(t.state())) {
      throw conflict("ALLOCATION_IN_PROGRESS", "할당 결과가 아직 불명입니다. TRF 인수를 다시 보내 할당을 확정한 뒤 취소하세요.");
    }
    List<TransferLineRow> lines = store.transferLines(t.trfId());
    List<String[]> tasks = store.taskStates(t.trfId());
    boolean started =
        tasks.stream().anyMatch(k -> !NOT_STARTED_TASK.contains(k[1]))
            || lines.stream().anyMatch(l -> l.pickedQty() > 0 || l.shortQty() > 0)
            || !lpns.lockOfTrf(t.trfId()).isEmpty();
    if (started) {
      throw conflict("PICKING_STARTED", "피킹이 시작된 TRF 는 취소할 수 없습니다(취소는 피킹 시작 전에만).");
    }
    Map<String, Integer> reduce = new TreeMap<>();
    Map<String, Integer> release = new TreeMap<>();
    if ("PARTIAL".equals(c.scope())) {
      if (!tasks.isEmpty()) {
        throw conflict("PARTIAL_AFTER_RELEASE", "Wave 릴리스로 PICK 작업이 만들어진 뒤에는 축소하지 않습니다(FULL 취소만).");
      }
      Map<String, Integer> done = cancels.cancelledQty(t.trfId());
      List<PmartException.Detail> bad = new ArrayList<>();
      for (int i = 0; i < c.lines().size(); i++) {
        Reduce r = c.lines().get(i);
        TransferLineRow l =
            lines.stream().filter(x -> x.sku().equals(r.sku())).findFirst().orElse(null);
        if (l == null) {
          bad.add(new PmartException.Detail("lines[" + i + "].sku", "UNKNOWN_SKU"));
          continue;
        }
        if (r.qty() > l.approvedQty() - done.getOrDefault(r.sku(), 0)) {
          bad.add(
              new PmartException.Detail("lines[" + i + "].reduceQty", "REDUCE_EXCEEDS_APPROVED"));
          continue;
        }
        reduce.put(r.sku(), r.qty());
        release.put(r.sku(), Math.min(r.qty(), l.allocatedQty()));
      }
      if (!bad.isEmpty()) {
        throw new PmartException(ErrorCode.VALIDATION_FAILED, "축소 줄이 맞지 않습니다.", bad);
      }
    } else {
      for (TransferLineRow l : lines) {
        if (l.allocatedQty() > 0) {
          release.put(l.sku(), l.allocatedQty());
        }
      }
    }
    boolean needsRelease =
        t.allocationId() != null && release.values().stream().anyMatch(q -> q > 0);
    Instant at = support.now();
    CancelRow row =
        new CancelRow(
            Support.newId("PDC"),
            t.trfId(),
            c.sourceSystem(),
            c.requestKey(),
            hash,
            c.scope(),
            c.reason(),
            c.sourceRef(),
            reduce,
            release,
            needsRelease,
            "PENDING",
            0,
            null,
            null,
            by,
            at);
    cancels.insert(row);
    if ("FULL".equals(c.scope())) {
      cancels.cancelOpenTasks(t.trfId()); // 막기 먼저 — 다음 작업 받기·착수가 더는 이 TRF 를 잡지 않는다
      if (needsRelease) {
        store.setTransferState(t.trfId(), "CANCELLING", null, at);
      }
    }
    if (!needsRelease) {
      apply(row, 0, null, by, at);
      return new Prep(
          cancels.find(t.trfId(), c.sourceSystem(), c.requestKey()).orElseThrow(), false, null);
    }
    return new Prep(row, true, t.allocationId());
  }

  /** 해제 반영(트랜잭션 안). */
  private void apply(CancelRow r, int released, @Nullable String commitId, String by, Instant at) {
    TransferRow t = store.transfer(r.trfId(), true).orElseThrow();
    cancels.finish(r.cancellationId(), "APPLIED", released, commitId, null, at);
    if ("FULL".equals(r.scope())) {
      cancels.cancelAllLines(t.trfId());
      store.setTransferState(t.trfId(), "CANCELLED", null, at);
      closeWave(t, by, at);
      return;
    }
    for (var e : r.reduceLines().entrySet()) {
      cancels.reduceLine(
          t.trfId(), e.getKey(), r.releaseLines().getOrDefault(e.getKey(), 0), e.getValue());
    }
    boolean nothingLeft =
        store.transferLines(t.trfId()).stream().allMatch(l -> l.allocatedQty() == 0)
            && cancels.cancelledQty(t.trfId()).entrySet().stream()
                .allMatch(
                    e ->
                        store.transferLines(t.trfId()).stream()
                            .filter(l -> l.sku().equals(e.getKey()))
                            .allMatch(l -> l.approvedQty() == e.getValue()));
    store.setTransferState(t.trfId(), nothingLeft ? "CANCELLED" : t.state(), null, at);
    if (nothingLeft) {
      closeWave(t, by, at);
    }
  }

  /** 취소로 Wave 에 남은 일이 없어졌으면 닫는다 — TRF 가 모두 취소면 CANCELLED, 릴리스 뒤 남은 작업이 없으면 WORK_DONE. */
  private void closeWave(TransferRow t, String by, Instant at) {
    if (t.waveId() == null) {
      return;
    }
    WaveRow w = store.wave(t.waveId(), true).orElseThrow();
    if (!Set.of("PLANNED", "RELEASED", "IN_PROGRESS").contains(w.state())) {
      return;
    }
    List<TransferRow> trfs = store.transfersOfWave(w.waveId());
    boolean allCancelled = trfs.stream().allMatch(x -> "CANCELLED".equals(x.state()));
    String to = null;
    if (allCancelled) {
      to = "CANCELLED";
    } else if (!"PLANNED".equals(w.state()) && store.openTasks("wave_id", w.waveId()) == 0) {
      to = "WORK_DONE";
    }
    if (to != null && store.setWaveState(w.waveId(), to, w.version(), null)) {
      waves.publishState(
          w, to, trfs.stream().map(TransferRow::trfId).toList(), by, w.version() + 1, at);
    }
  }

  static CommandResult result(CancelRow r) {
    String status =
        switch (r.state()) {
          case "APPLIED" -> "APPLIED";
          case "REJECTED" -> "REJECTED";
          default -> "ACCEPTED";
        };
    return new CommandResult(status, r.requestKey(), r.cancellationId(), 0, r.createdAt());
  }

  static PmartException conflict(String code, String message) {
    return new PmartException(code, HttpStatus.CONFLICT, message, false, List.of());
  }

  static void validate(Cancel c) {
    List<PmartException.Detail> bad = new ArrayList<>();
    if ("FULL".equals(c.scope()) && !c.lines().isEmpty()) {
      bad.add(new PmartException.Detail("lines", "LINES_ONLY_FOR_PARTIAL"));
    }
    if ("PARTIAL".equals(c.scope())) {
      if (c.lines().isEmpty()) {
        bad.add(new PmartException.Detail("lines", "REQUIRED_FOR_PARTIAL"));
      }
      if (c.lines().stream().map(Reduce::sku).distinct().count() != c.lines().size()) {
        bad.add(new PmartException.Detail("lines", "DUPLICATE_SKU"));
      }
    }
    if (!bad.isEmpty()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "취소 요청이 맞지 않습니다.", bad);
    }
  }

  static String hash(Cancel c) {
    StringBuilder sb = new StringBuilder();
    sb.append(c.scope()).append('|').append(c.reason()).append('|').append(c.sourceRef());
    new TreeMap<>(
            c.lines().stream()
                .collect(java.util.stream.Collectors.toMap(Reduce::sku, Reduce::qty, Integer::sum)))
        .forEach((k, v) -> sb.append('|').append(k).append('=').append(v));
    sb.append('|').append(c.expectedVersion());
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
