package com.pmart.cdc.outbound.application;

import com.pmart.cdc.outbound.application.Support.CommandResult;
import com.pmart.cdc.outbound.infrastructure.InventoryClient;
import com.pmart.cdc.outbound.infrastructure.OutboundStore;
import com.pmart.cdc.outbound.infrastructure.OutboundStore.TransferLineRow;
import com.pmart.cdc.outbound.infrastructure.OutboundStore.TransferRow;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * 승인 TRF 인수(0.3.0) → cdc-inventory 할당 요청. 인수와 할당 결과는 각각 짧은 트랜잭션으로 남기고, 그 사이의 재고 호출은 트랜잭션 밖에서 한다. 같은
 * 본문 재전송은 ALLOCATING 이면 같은 멱등 키로 다시 할당을 부른다(재고는 한 번만 반영).
 *
 * <p>(0.13.0) 더 높은 버전 = 대체 — 피킹 시작 전만(사용자 2026-11-25): 작업에서 몫 빼기·Wave 에서 빼기 → CHANGING → 예전 할당 해제
 * (TRANSFER_CHANGED) → 새 버전 할당.
 */
@Service
public class TransferService {

  static final Set<String> RETRYABLE = Set.of("RECEIVED", "ALLOCATING", "CHANGING", "CHANGE_HOLD");
  static final Set<String> CHANGE_STATES = Set.of("CHANGING", "CHANGE_HOLD");

  public record IntakeLine(String sku, int approvedQty) {}

  public record Intake(
      String trfId,
      int trfVersion,
      String cdcId,
      String mfcId,
      String approvalRef,
      String deadlineMeaning,
      Instant dueAt,
      List<IntakeLine> lines) {}

  /** 단계별 수량(SKU → 분류·검품합격·상차·출발 BOX 합). */
  public record TransferView(
      TransferRow transfer, List<TransferLineRow> lines, Map<String, int[]> stages) {}

  private final OutboundStore store;
  private final com.pmart.cdc.outbound.infrastructure.LpnStore lpns;
  private final InventoryClient inventory;
  private final TransactionTemplate tx;
  private final Support support;
  private final com.pmart.cdc.outbound.infrastructure.CancellationStore cancels;
  private final WaveService waves;

  public TransferService(
      OutboundStore store,
      com.pmart.cdc.outbound.infrastructure.LpnStore lpns,
      InventoryClient inventory,
      TransactionTemplate tx,
      Support support,
      com.pmart.cdc.outbound.infrastructure.CancellationStore cancels,
      WaveService waves) {
    this.cancels = cancels;
    this.waves = waves;
    this.store = store;
    this.lpns = lpns;
    this.inventory = inventory;
    this.tx = tx;
    this.support = support;
  }

  public CommandResult intake(String requestId, Intake in) {
    validate(in);
    String hash = hash(in);
    TransferRow t = tx.execute(s -> receive(in, hash));
    if (!RETRYABLE.contains(t.state())) {
      return new CommandResult("ACCEPTED", requestId, in.trfId(), t.version(), support.now());
    }
    if (CHANGE_STATES.contains(t.state()) && !releaseSuperseded(in)) {
      return new CommandResult("ACCEPTED", requestId, in.trfId(), t.version(), support.now());
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("cdcId", in.cdcId());
    body.put("trfId", in.trfId());
    body.put("trfVersion", in.trfVersion());
    body.put(
        "lines",
        in.lines().stream()
            .map(l -> Map.<String, Object>of("sku", l.sku(), "requestedQty", l.approvedQty()))
            .toList());
    String key = "outbound-alloc-" + in.trfId() + "-v" + in.trfVersion();
    JsonNode res;
    try {
      res = inventory.allocate(key, body);
    } catch (PmartException e) {
      if (unknown(e)) {
        return new CommandResult("ACCEPTED", requestId, in.trfId(), t.version(), support.now());
      }
      Instant at = support.now();
      tx.executeWithoutResult(
          s ->
              store.setAllocation(
                  in.trfId(), "ALLOCATION_FAILED", null, e.code() + ": " + e.getMessage(), at));
      return new CommandResult("ACCEPTED", requestId, in.trfId(), t.version() + 1, at);
    }
    Instant at = support.now();
    String state =
        switch (res.path("state").asString("")) {
          case "ALLOCATED" -> "ALLOCATED";
          case "PARTIAL" -> "PARTIALLY_ALLOCATED";
          default -> "ALLOCATION_FAILED";
        };
    String allocationId = res.path("allocationId").asString(null);
    tx.executeWithoutResult(
        s -> {
          store.setAllocation(in.trfId(), state, allocationId, null, at);
          for (JsonNode l : res.path("lines")) {
            store.setLineAllocated(
                in.trfId(), l.path("sku").asString(), l.path("allocatedQty").asInt(0));
          }
        });
    return new CommandResult("ACCEPTED", requestId, in.trfId(), t.version() + 1, at);
  }

  /** 인수 기록(같은 버전·같은 본문이면 지금 행, 다르면 거절). 다시 할당할 수 있으면 ALLOCATING 으로. */
  private TransferRow receive(Intake in, String hash) {
    Instant at = support.now();
    Optional<TransferRow> cur = store.transfer(in.trfId(), true);
    if (cur.isEmpty()) {
      TransferRow row =
          new TransferRow(
              in.trfId(),
              in.trfVersion(),
              in.cdcId(),
              in.mfcId(),
              in.approvalRef(),
              in.deadlineMeaning(),
              in.dueAt(),
              hash,
              "ALLOCATING",
              null,
              null,
              null,
              1,
              at,
              at);
      List<TransferLineRow> lines = new ArrayList<>();
      for (int i = 0; i < in.lines().size(); i++) {
        IntakeLine l = in.lines().get(i);
        lines.add(new TransferLineRow(l.sku(), i, l.approvedQty(), 0, 0, 0));
      }
      store.insertTransfer(row, lines);
      return row;
    }
    TransferRow t = cur.get();
    if (in.trfVersion() < t.trfVersion()) {
      throw PmartException.invalidState(
          "TRF " + in.trfId() + " 는 이미 v" + t.trfVersion() + " 입니다(받은 v" + in.trfVersion() + ").");
    }
    if (in.trfVersion() > t.trfVersion()) {
      return change(t, in, hash, at);
    }
    if (!t.bodyHash().equals(hash)) {
      throw new PmartException(
          "TRANSFER_INTAKE_CONFLICT",
          HttpStatus.CONFLICT,
          "같은 TRF·버전에 다른 본문입니다.",
          false,
          List.of());
    }
    return t;
  }

  /**
   * 새 버전 대체 준비(트랜잭션 안, TRF 잠금) — 피킹 시작 전만. 안 끝난 PICK 작업에서 이 TRF 몫을 빼고 Wave 에서 빼며 새 버전 줄로 바꾼다 →
   * CHANGING.
   */
  private TransferRow change(TransferRow t, Intake in, String hash, Instant at) {
    if ("DEPARTED".equals(t.state()) || CancellationService.CANCEL_STATES.contains(t.state())) {
      throw PmartException.invalidState(
          "TRF " + t.trfId() + " 는 출발·취소됐습니다(" + t.state() + ") — 새 버전으로 바꿀 수 없습니다.");
    }
    if (CHANGE_STATES.contains(t.state())) {
      throw CancellationService.conflict(
          "VERSION_CHANGE_IN_PROGRESS",
          "TRF " + t.trfId() + " 는 v" + t.trfVersion() + " 대체가 아직 끝나지 않았습니다.");
    }
    if (RETRYABLE.contains(t.state())) {
      throw CancellationService.conflict(
          "ALLOCATION_IN_PROGRESS", "예전 버전 할당 결과가 아직 불명입니다. 그 버전 인수를 다시 보내 할당을 확정한 뒤 바꾸세요.");
    }
    if (cancels.inProgress(t.trfId())) {
      throw CancellationService.conflict(
          "CANCELLATION_IN_PROGRESS", "이 TRF 의 미출발 취소·축소가 아직 끝나지 않았습니다.");
    }
    boolean started =
        store.taskStates(t.trfId()).stream()
                .anyMatch(k -> !CancellationService.NOT_STARTED_TASK.contains(k[1]))
            || store.transferLines(t.trfId()).stream()
                .anyMatch(l -> l.pickedQty() > 0 || l.shortQty() > 0)
            || !lpns.lockOfTrf(t.trfId()).isEmpty();
    if (started) {
      throw CancellationService.conflict(
          "PICKING_STARTED", "피킹이 시작된 TRF 는 새 버전으로 바꿀 수 없습니다(피킹 시작 전에만 — 미출발 취소 범위와 같음).");
    }
    cancels.cancelOpenTasks(t.trfId()); // 묶음 작업이면 이 TRF 몫만 빠진다
    TransferRow next =
        new TransferRow(
            in.trfId(),
            in.trfVersion(),
            t.cdcId(),
            in.mfcId(),
            in.approvalRef(),
            in.deadlineMeaning(),
            in.dueAt(),
            hash,
            "CHANGING",
            null,
            null,
            null,
            t.version() + 1,
            t.createdAt(),
            at);
    List<TransferLineRow> lines = new ArrayList<>();
    for (int i = 0; i < in.lines().size(); i++) {
      IntakeLine l = in.lines().get(i);
      lines.add(new TransferLineRow(l.sku(), i, l.approvedQty(), 0, 0, 0));
    }
    store.replaceVersion(next, lines, at);
    leaveWave(t, at);
    return next;
  }

  /** TRF 가 빠진 Wave — 남은 TRF 가 없으면 CANCELLED, 릴리스 뒤 남은 작업이 없으면 WORK_DONE. */
  private void leaveWave(TransferRow t, Instant at) {
    if (t.waveId() == null) {
      return;
    }
    var w = store.wave(t.waveId(), true).orElseThrow();
    if (!Set.of("PLANNED", "RELEASED", "IN_PROGRESS").contains(w.state())) {
      return;
    }
    List<TransferRow> left = store.transfersOfWave(w.waveId());
    String to = null;
    if (left.isEmpty()
        || left.stream().allMatch(x -> CancellationService.CANCEL_STATES.contains(x.state()))) {
      to = "CANCELLED";
    } else if (!"PLANNED".equals(w.state()) && store.openTasks("wave_id", w.waveId()) == 0) {
      to = "WORK_DONE";
    }
    if (to != null && store.setWaveState(w.waveId(), to, w.version(), null)) {
      waves.publishState(
          w,
          to,
          left.stream().map(TransferRow::trfId).toList(),
          "cdc-outbound",
          w.version() + 1,
          at);
    }
  }

  /** 예전 버전 할당 해제(트랜잭션 밖). 끝났으면 true(→ 새 버전 할당), 불명·거절이면 false. */
  private boolean releaseSuperseded(Intake in) {
    String old = store.supersededAllocation(in.trfId());
    if (old != null) {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("reason", "TRANSFER_CHANGED");
      body.put("sourceRef", in.trfId() + "-v" + in.trfVersion());
      try {
        inventory.release(old, "outbound-change-" + in.trfId() + "-v" + in.trfVersion(), body);
      } catch (PmartException e) {
        boolean done = e.status().value() == 409 && ErrorCode.INVALID_STATE.name().equals(e.code());
        if (!done) {
          if (!unknown(e)) {
            Instant at = support.now();
            tx.executeWithoutResult(
                s -> store.changeHold(in.trfId(), DepartureService.residualReason(e), at));
          }
          return false;
        }
      }
    }
    Instant at = support.now();
    tx.executeWithoutResult(s -> store.supersededReleased(in.trfId(), at));
    return true;
  }

  public TransferView get(String trfId) {
    TransferRow t =
        store
            .transfer(trfId, false)
            .orElseThrow(() -> PmartException.notFound("NOT_FOUND", "TRF " + trfId + " 가 없습니다."));
    Map<String, int[]> stages = new java.util.HashMap<>();
    String[] conds = {"ALL", "PASSED", "LOADED", "DEPARTED"};
    for (int i = 0; i < conds.length; i++) {
      int idx = i;
      lpns.boxQty(trfId, conds[i])
          .forEach(
              (k, q) -> stages.computeIfAbsent(k.split("[|]", 2)[0], x -> new int[4])[idx] += q);
    }
    return new TransferView(t, store.transferLines(trfId), stages);
  }

  static boolean unknown(PmartException e) {
    return e.status().is5xxServerError()
        || "UNKNOWN_RESULT".equals(e.code())
        || ErrorCode.DEPENDENCY_UNAVAILABLE.name().equals(e.code());
  }

  static void validate(Intake in) {
    List<PmartException.Detail> bad = new ArrayList<>();
    Set<String> skus = new HashSet<>();
    for (int i = 0; i < in.lines().size(); i++) {
      if (!skus.add(in.lines().get(i).sku())) {
        bad.add(new PmartException.Detail("lines[" + i + "].sku", "DUPLICATE_SKU"));
      }
    }
    if (in.lines().isEmpty() || !bad.isEmpty()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "TRF 줄이 비었거나 같은 SKU 가 두 번 있습니다.", bad);
    }
  }

  /** 본문 비교용(줄 순서 포함). */
  static String hash(Intake in) {
    StringBuilder sb = new StringBuilder();
    sb.append(in.trfId())
        .append('|')
        .append(in.trfVersion())
        .append('|')
        .append(in.cdcId())
        .append('|')
        .append(in.mfcId())
        .append('|')
        .append(in.approvalRef())
        .append('|')
        .append(in.deadlineMeaning())
        .append('|')
        .append(in.dueAt());
    for (IntakeLine l : in.lines()) {
      sb.append('|').append(l.sku()).append(':').append(l.approvedQty());
    }
    try {
      byte[] d =
          MessageDigest.getInstance("SHA-256")
              .digest(sb.toString().getBytes(StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(d);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
