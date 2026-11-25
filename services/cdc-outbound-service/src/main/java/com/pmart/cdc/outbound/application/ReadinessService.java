package com.pmart.cdc.outbound.application;

import com.pmart.cdc.outbound.infrastructure.LpnStore;
import com.pmart.cdc.outbound.infrastructure.LpnStore.BoxFact;
import com.pmart.cdc.outbound.infrastructure.OutboundStore;
import com.pmart.cdc.outbound.infrastructure.OutboundStore.TransferLineRow;
import com.pmart.cdc.outbound.infrastructure.OutboundStore.TransferRow;
import com.pmart.common.web.PmartException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 출발 준비 조회(0.6.0, WMS CDC-F-020) — TRF 하나의 **WMS 자기 화물·작업 사실만**으로 단계와 막힌 이유를 돌려준다. TMS 운행·구획·ETA,
 * MFC 수령창 같은 외부 조건은 보지 않는다(그 판정은 transport-planning 몫 — 여기서 "실행 가능"을 합성하지 않음). 아무것도 바꾸지 않는다.
 *
 * <p>단계: DEPARTED·CANCELLED(TRF 상태) → NOT_STARTED(피킹 실적 없음) → PICKING(끝나지 않은 PICK 작업 또는 실피킹+부족 &lt;
 * 할당) → PICKED(피킹 끝, 분류 BOX 없음) → SORTED(분류했으나 BOX 수량 &lt; 실피킹 또는 합격 아닌 BOX) → INSPECTED(모든 BOX
 * 합격·수량 일치) → LOADED(그 BOX 가 모두 상차).
 */
@Service
public class ReadinessService {

  static final Set<String> CLOSED_TASK = Set.of("DONE", "PARTIAL", "CANCELLED");

  public record Readiness(
      String trfId,
      Instant asOf,
      String readiness,
      int readyLpnCount,
      int readyQty,
      long totalWeightG,
      List<String> temperatureZones,
      @Nullable Instant earliestReadyAt,
      List<String> blockers) {}

  private final OutboundStore store;
  private final LpnStore lpns;
  private final Support support;
  private final com.pmart.cdc.outbound.infrastructure.ShortageStore shortages;

  public ReadinessService(
      OutboundStore store,
      LpnStore lpns,
      Support support,
      com.pmart.cdc.outbound.infrastructure.ShortageStore shortages) {
    this.shortages = shortages;
    this.store = store;
    this.lpns = lpns;
    this.support = support;
  }

  @Transactional(readOnly = true)
  public Readiness of(String trfId) {
    TransferRow t =
        store
            .transfer(trfId, false)
            .orElseThrow(() -> PmartException.notFound("NOT_FOUND", "TRF " + trfId + " 가 없습니다."));
    int allocated = 0;
    int picked = 0;
    int shortQty = 0;
    for (TransferLineRow l : store.transferLines(trfId)) {
      allocated += l.allocatedQty();
      picked += l.pickedQty();
      shortQty += l.shortQty();
    }
    List<String[]> tasks = store.taskStates(trfId);
    boolean pickOpen = false;
    boolean pickTouched = false;
    boolean exception = false;
    for (String[] k : tasks) {
      boolean pick = "PICK".equals(k[0]);
      if (pick && !CLOSED_TASK.contains(k[1])) {
        pickOpen = true;
      }
      if (pick && !"CREATED".equals(k[1]) && !"ASSIGNED".equals(k[1])) {
        pickTouched = true;
      }
      if ("EXCEPTION".equals(k[1])) {
        exception = true;
      }
    }
    List<BoxFact> boxes = lpns.boxFacts(trfId);
    boolean departedState = "DEPARTED".equals(t.state());
    List<BoxFact> ready = new ArrayList<>();
    int boxedQty = 0;
    boolean anyFailed = false;
    boolean anyUninspected = false;
    boolean allLoaded = true;
    for (BoxFact b : boxes) {
      boxedQty += b.qty();
      if (departedState ? b.departureId() != null : "PASSED".equals(b.inspection())) {
        ready.add(b);
        allLoaded &= b.loaded();
      } else if ("FAILED".equals(b.inspection())) {
        anyFailed = true;
      } else if (b.inspection() == null) {
        anyUninspected = true;
      }
    }

    List<String> blockers = new ArrayList<>();
    String stage;
    if (departedState) {
      stage = "DEPARTED";
    } else if ("CANCELLED".equals(t.state())) {
      stage = "CANCELLED";
    } else {
      if ("CANCELLING".equals(t.state())) {
        blockers.add("CANCELLATION_PENDING");
      } else if ("CANCEL_HOLD".equals(t.state())) {
        blockers.add("CANCELLATION_HOLD");
      } else if ("CHANGING".equals(t.state())) {
        blockers.add("VERSION_CHANGE_PENDING"); // 0.13.0
      } else if ("CHANGE_HOLD".equals(t.state())) {
        blockers.add("VERSION_CHANGE_HOLD");
      }
      if (shortages.openOfTrf(trfId) > 0) {
        blockers.add("SHORTAGE_REALLOCATING"); // 0.11.0 — 부족 재할당이 아직 끝나지 않음
      }
      if ("ALLOCATION_FAILED".equals(t.state())) {
        blockers.add("ALLOCATION_FAILED");
      } else if (t.allocationId() == null) {
        blockers.add("NOT_ALLOCATED");
      } else if (t.waveId() == null) {
        blockers.add("NOT_IN_WAVE");
      }
      if (exception) {
        blockers.add("TASK_EXCEPTION");
      }
      boolean pickDone = !pickOpen && allocated > 0 && picked + shortQty >= allocated;
      if (picked == 0 && !pickTouched && !pickDone) {
        stage = "NOT_STARTED";
      } else if (!pickDone) {
        stage = "PICKING";
      } else if (picked == 0) {
        stage = "PICKED";
        blockers.add("NOTHING_PICKED");
      } else if (boxes.isEmpty()) {
        stage = "PICKED";
        blockers.add("UNSORTED_QTY");
      } else if (boxedQty < picked || anyFailed || anyUninspected) {
        stage = "SORTED";
        if (boxedQty < picked) {
          blockers.add("UNSORTED_QTY");
        }
        if (anyUninspected) {
          blockers.add("NOT_INSPECTED");
        }
        if (anyFailed) {
          blockers.add("INSPECTION_FAILED");
        }
      } else {
        stage = allLoaded ? "LOADED" : "INSPECTED";
      }
    }
    boolean whole = Set.of("INSPECTED", "LOADED", "DEPARTED").contains(stage);
    Instant readyAt = null;
    if (whole) {
      for (BoxFact b : ready) {
        if (b.inspectedAt() != null && (readyAt == null || b.inspectedAt().isAfter(readyAt))) {
          readyAt = b.inspectedAt();
        }
      }
    }
    Set<String> zones = new TreeSet<>();
    long weight = 0;
    int qty = 0;
    for (BoxFact b : ready) {
      qty += b.qty();
      weight += b.weightG() == null ? 0 : b.weightG();
      if (b.temperatureZone() != null) {
        zones.add(b.temperatureZone());
      }
    }
    return new Readiness(
        trfId,
        support.now(),
        stage,
        ready.size(),
        qty,
        weight,
        List.copyOf(zones),
        readyAt,
        blockers);
  }
}
