package com.pmart.cdc.outbound.application;

import com.pmart.cdc.outbound.domain.BatchPlan;
import com.pmart.cdc.outbound.infrastructure.InventoryClient;
import com.pmart.cdc.outbound.infrastructure.LocationClient;
import com.pmart.cdc.outbound.infrastructure.OutboundStore;
import com.pmart.cdc.outbound.infrastructure.OutboundStore.TransferRow;
import com.pmart.cdc.outbound.infrastructure.OutboundStore.WaveRow;
import com.pmart.cdc.outbound.infrastructure.ShortageStore;
import com.pmart.cdc.outbound.infrastructure.ShortageStore.FollowupRow;
import com.pmart.common.web.PmartException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * 피킹 부족 후속(0.11.0, WMS CDC-F-018 — 사용자 2026-11-25). 부족 보고(TRF 몫) 하나마다:
 *
 * <ol>
 *   <li>cdc-inventory 해제(PICK_SHORTAGE, 그 LOT·위치, <b>재계수 대기</b> — 부족 칸은 다시 셀 때까지 새 할당에서 뺀다)
 *   <li>같은 할당에 추가 할당(다른 적격 LOT, FEFO) → 잡힌 만큼 새 PICK 작업(같은 Wave, 통로 방식대로)
 *   <li>못 잡은 만큼은 "못 찾음"(unresolved) — 집은 만큼만 출발한다(사용자 "48개만 보냄"), PickShortageResolved 로 알린다
 * </ol>
 *
 * 재고 호출은 트랜잭션 밖. 불명·미전달이면 그 단계에 남고 주기 작업이 같은 키로 다시 부른다(재고는 한 번만 반영). 재고가 거절하면 FAILED(부족 전량 못 찾음).
 */
@Service
public class ShortageService {

  private static final Logger log = LoggerFactory.getLogger(ShortageService.class);
  static final String NO_WAVE = "-";
  static final Set<String> GONE = Set.of("DEPARTED", "CANCELLED", "CANCELLING", "CANCEL_HOLD");

  private final ShortageStore shortages;
  private final OutboundStore store;
  private final InventoryClient inventory;
  private final LocationClient locations;
  private final WaveService waves;
  private final TransactionTemplate tx;
  private final Support support;

  public ShortageService(
      ShortageStore shortages,
      OutboundStore store,
      InventoryClient inventory,
      LocationClient locations,
      WaveService waves,
      TransactionTemplate tx,
      Support support) {
    this.shortages = shortages;
    this.store = store;
    this.inventory = inventory;
    this.locations = locations;
    this.waves = waves;
    this.tx = tx;
    this.support = support;
  }

  /** 피킹 실적 트랜잭션 안 — 부족 후속 기록. */
  void record(
      String trfId,
      String waveId,
      String taskId,
      String sku,
      String lot,
      String locationCode,
      int shortQty,
      Instant at) {
    record(trfId, waveId, taskId, sku, lot, locationCode, shortQty, "PENDING", at);
  }

  /**
   * 재고가 이미 예약을 푼 부족(실사 AllocationShortfall, 0.17.0) — 해제 단계를 건너뛴 RELEASED 로 기록한다. Wave 에 안 든 TRF 는
   * waveId "-"(새 작업 없이 추가 할당만 — 나중 Wave 릴리스가 할당의 지금 LOT 을 읽는다).
   */
  void recordReleased(
      String trfId,
      String waveId,
      String taskId,
      String sku,
      String lot,
      String locationCode,
      int shortQty,
      Instant at) {
    record(trfId, waveId, taskId, sku, lot, locationCode, shortQty, "RELEASED", at);
  }

  private void record(
      String trfId,
      String waveId,
      String taskId,
      String sku,
      String lot,
      String locationCode,
      int shortQty,
      String state,
      Instant at) {
    shortages.insert(
        new FollowupRow(
            Support.newId("PSF"),
            trfId,
            waveId,
            taskId,
            sku,
            lot,
            locationCode,
            shortQty,
            state,
            0,
            0,
            null,
            null),
        at);
  }

  /** 남은 후속을 처리한다(피킹 실적 뒤·주기). */
  @Scheduled(
      initialDelayString = "${pmart.outbound.shortage-retry-interval:PT30S}",
      fixedDelayString = "${pmart.outbound.shortage-retry-interval:PT30S}")
  public void processOpen() {
    for (FollowupRow f : shortages.open(50)) {
      try {
        process(f.followupId());
      } catch (RuntimeException e) {
        log.warn("피킹 부족 후속 처리 실패 — {} 다음에 다시: {}", f.followupId(), e.toString());
      }
    }
  }

  void process(String followupId) {
    FollowupRow f = shortages.find(followupId, false).orElseThrow();
    TransferRow t = store.transfer(f.trfId(), false).orElseThrow();
    if (GONE.contains(t.state()) || t.allocationId() == null) {
      tx.executeWithoutResult(s -> fail(f, t, "TRF 상태 " + t.state() + " — 재할당 안 함"));
      return;
    }
    if ("PENDING".equals(f.state())) {
      Map<String, Object> line = new LinkedHashMap<>();
      line.put("sku", f.sku());
      line.put("lot", f.lot());
      line.put("locationCode", f.locationCode());
      line.put("qty", f.shortQty());
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("reason", "PICK_SHORTAGE");
      body.put("sourceRef", f.taskId());
      body.put("recountHold", true);
      body.put("lines", List.of(line));
      try {
        inventory.release(t.allocationId(), "outbound-short-rel-" + f.followupId(), body);
      } catch (PmartException e) {
        if (!TransferService.unknown(e)) {
          tx.executeWithoutResult(s -> fail(f, t, DepartureService.residualReason(e)));
        }
        return;
      }
      tx.executeWithoutResult(s -> shortages.released(f.followupId(), support.now()));
    }
    // 추가 할당 — 같은 키라 다시 불러도 재고는 한 번만 잡는다
    JsonNode added;
    try {
      added =
          inventory.add(
              t.allocationId(),
              "outbound-short-add-" + f.followupId(),
              Map.of(
                  "reason",
                  "PICK_SHORTAGE_REALLOCATION",
                  "sourceRef",
                  f.followupId(),
                  "lines",
                  List.of(Map.of("sku", f.sku(), "qty", f.shortQty()))));
    } catch (PmartException e) {
      if (!TransferService.unknown(e)) {
        tx.executeWithoutResult(s -> fail(f, t, DepartureService.residualReason(e)));
      }
      return;
    }
    List<BatchPlan.Item> items = new ArrayList<>();
    Set<String> codes = new TreeSet<>();
    int got = 0;
    for (JsonNode l : added.path("lines")) {
      for (JsonNode lot : l.path("lots")) {
        codes.add(lot.path("locationCode").asString());
      }
    }
    Map<String, String> methods =
        codes.isEmpty() ? Map.of() : locations.pickMethods(t.cdcId(), codes);
    String region = waves.regionOf(t.cdcId()).get(t.mfcId());
    for (JsonNode l : added.path("lines")) {
      for (JsonNode lot : l.path("lots")) {
        int q = lot.path("qty").asInt(0);
        if (q <= 0) {
          continue;
        }
        String loc = lot.path("locationCode").asString();
        items.add(
            new BatchPlan.Item(
                t.trfId(),
                t.mfcId(),
                t.dueAt(),
                region,
                l.path("sku").asString(),
                lot.path("lot").asString(),
                loc,
                q,
                methods.get(loc)));
        got += q;
      }
    }
    int reallocated = Math.min(got, f.shortQty());
    tx.executeWithoutResult(s -> complete(f.followupId(), items, reallocated));
  }

  private void complete(String followupId, List<BatchPlan.Item> items, int reallocated) {
    FollowupRow f = shortages.find(followupId, true).orElseThrow();
    if (!"RELEASED".equals(f.state())) {
      return; // 다른 처리가 먼저 끝냈다
    }
    Instant at = support.now();
    TransferRow t = store.transfer(f.trfId(), true).orElseThrow();
    boolean noWave = NO_WAVE.equals(f.waveId());
    List<String> ids =
        items.isEmpty() || noWave ? List.of() : waves.createTasks(t.cdcId(), f.waveId(), items, at);
    store.reallocated(f.trfId(), f.sku(), reallocated);
    int unresolved = f.shortQty() - reallocated;
    shortages.done(
        f.followupId(), reallocated, unresolved, ids.isEmpty() ? null : String.join(",", ids), at);
    if (!ids.isEmpty()) {
      if ("PICKED".equals(t.state())) {
        store.setTransferState(t.trfId(), "IN_WAVE", null, at); // 다시 집을 것이 생겼다
      }
      WaveRow w = store.wave(f.waveId(), true).orElseThrow();
      if ("WORK_DONE".equals(w.state())
          && store.setWaveState(w.waveId(), "IN_PROGRESS", w.version(), null)) {
        waves.publishState(
            w,
            "IN_PROGRESS",
            store.transfersOfWave(w.waveId()).stream().map(TransferRow::trfId).toList(),
            "cdc-outbound",
            w.version() + 1,
            at);
      }
    }
    publish(f, t, reallocated, unresolved, ids, null, at);
  }

  /** 재고 거절·TRF 상태로 재할당을 못 함 — 부족 전량 못 찾음(TRF 줄 부족은 그대로). */
  private void fail(FollowupRow f0, TransferRow t0, String reason) {
    FollowupRow f = shortages.find(f0.followupId(), true).orElseThrow();
    if ("DONE".equals(f.state()) || "FAILED".equals(f.state())) {
      return;
    }
    Instant at = support.now();
    shortages.failed(f.followupId(), reason, f.shortQty(), at);
    log.warn(
        "피킹 부족 재할당 못 함 — trfId={} sku={} qty={}: {}", f.trfId(), f.sku(), f.shortQty(), reason);
    publish(f, t0, 0, f.shortQty(), List.of(), reason, at);
  }

  private void publish(
      FollowupRow f,
      TransferRow t,
      int reallocated,
      int unresolved,
      List<String> taskIds,
      String reason,
      Instant at) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("trfId", f.trfId());
    if (t.allocationId() != null) {
      p.put("allocationId", t.allocationId());
    }
    p.put("waveId", f.waveId());
    p.put("pickTaskId", f.taskId());
    p.put("sku", f.sku());
    p.put("lot", f.lot());
    p.put("locCode", f.locationCode());
    p.put("shortQty", f.shortQty());
    p.put("reallocatedQty", reallocated);
    p.put("unresolvedQty", unresolved);
    p.put("newTaskIds", taskIds);
    if (reason != null) {
      p.put("reason", reason);
    }
    p.put("resolvedAt", at.toString());
    support.publish(
        "PickShortageResolved", "OutboundTransfer", f.trfId(), t.version(), "cdc-outbound", p);
  }
}
