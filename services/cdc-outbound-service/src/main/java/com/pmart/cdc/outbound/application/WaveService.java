package com.pmart.cdc.outbound.application;

import com.pmart.cdc.outbound.application.Support.CommandResult;
import com.pmart.cdc.outbound.domain.BatchPlan;
import com.pmart.cdc.outbound.domain.PickPlan.Held;
import com.pmart.cdc.outbound.infrastructure.InventoryClient;
import com.pmart.cdc.outbound.infrastructure.LocationClient;
import com.pmart.cdc.outbound.infrastructure.OutboundStore;
import com.pmart.cdc.outbound.infrastructure.OutboundStore.RegionRow;
import com.pmart.cdc.outbound.infrastructure.OutboundStore.ShareRow;
import com.pmart.cdc.outbound.infrastructure.OutboundStore.TaskLineRow;
import com.pmart.cdc.outbound.infrastructure.OutboundStore.TaskRow;
import com.pmart.cdc.outbound.infrastructure.OutboundStore.TransferRow;
import com.pmart.cdc.outbound.infrastructure.OutboundStore.WaveRow;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.Outcome;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Wave 생성·릴리스(0.3.0). 생성 = 확보된 TRF 묶음(PLANNED). 릴리스 = 재고 서비스에서 TRF 마다 지금 잡힌 LOT·위치를 읽고, 위치 서비스에서 통로
 * 피킹 방식을 읽어(0.9.0) {@link BatchPlan} 대로 PICK Task(단건·지역 묶음·SKU 묶음)를 만들고 RELEASED →
 * WaveStateChanged.
 */
@Service
public class WaveService {

  static final String OP_CREATE = "cdcOutbound:waveCreate";
  static final String OP_RELEASE = "cdcOutbound:waveRelease";
  static final Set<String> ALLOCATED = Set.of("ALLOCATED", "PARTIALLY_ALLOCATED");

  public record Create(
      String cdcId,
      List<String> trfIds,
      @Nullable Instant windowStart,
      @Nullable Instant windowEnd,
      @Nullable String temperatureZone) {}

  public record WaveView(WaveRow wave, List<String> trfIds, int total, int done, int exception) {}

  private final OutboundStore store;
  private final com.pmart.cdc.outbound.infrastructure.CancellationStore cancels;
  private final InventoryClient inventory;
  private final LocationClient locations;
  private final RequestIdempotency idempotency;
  private final Support support;
  private final OutboundProperties props;

  public WaveService(
      OutboundStore store,
      com.pmart.cdc.outbound.infrastructure.CancellationStore cancels,
      InventoryClient inventory,
      LocationClient locations,
      RequestIdempotency idempotency,
      Support support,
      OutboundProperties props) {
    this.store = store;
    this.cancels = cancels;
    this.inventory = inventory;
    this.locations = locations;
    this.idempotency = idempotency;
    this.support = support;
    this.props = props;
  }

  public Outcome<WaveView> create(String requestId, String sourceSystem, Create c) {
    String by = Support.requireCenter(c.cdcId());
    if (c.trfIds().isEmpty() || new TreeSet<>(c.trfIds()).size() != c.trfIds().size()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "trfIds 가 비었거나 같은 TRF 가 두 번 있습니다.");
    }
    return idempotency.execute(
        Support.key(sourceSystem, requestId, OP_CREATE, by),
        c,
        WaveView.class,
        () -> {
          Instant at = support.now();
          String waveId = Support.newId("WAVE");
          for (String trfId : new TreeSet<>(c.trfIds())) {
            TransferRow t =
                store
                    .transfer(trfId, true)
                    .orElseThrow(
                        () -> PmartException.notFound("NOT_FOUND", "TRF " + trfId + " 가 없습니다."));
            if (!t.cdcId().equals(c.cdcId())) {
              throw PmartException.invalidState(
                  "TRF " + trfId + " 는 센터 " + c.cdcId() + " 의 TRF 가 아닙니다.");
            }
            if (t.waveId() != null) {
              throw new PmartException(
                  "TRF_ALREADY_IN_WAVE",
                  HttpStatus.CONFLICT,
                  "TRF " + trfId + " 는 이미 Wave " + t.waveId() + " 에 있습니다.",
                  false,
                  List.of());
            }
            if (!ALLOCATED.contains(t.state())) {
              throw PmartException.invalidState(
                  "TRF " + trfId + " 는 아직 확보되지 않았습니다(" + t.state() + ").");
            }
            if (cancels.inProgress(trfId)) {
              throw new PmartException(
                  "CANCELLATION_IN_PROGRESS",
                  HttpStatus.CONFLICT,
                  "TRF " + trfId + " 의 미출발 취소·축소가 아직 끝나지 않았습니다.",
                  false,
                  List.of());
            }
            store.setTransferState(trfId, "IN_WAVE", waveId, at);
          }
          WaveRow w =
              new WaveRow(
                  waveId,
                  c.cdcId(),
                  "PLANNED",
                  1,
                  c.temperatureZone(),
                  c.windowStart(),
                  c.windowEnd(),
                  by,
                  at,
                  null);
          store.insertWave(w);
          return new WaveView(w, List.copyOf(new TreeSet<>(c.trfIds())), 0, 0, 0);
        });
  }

  @Transactional(readOnly = true)
  public WaveView get(String waveId) {
    WaveRow w =
        store
            .wave(waveId, false)
            .orElseThrow(() -> PmartException.notFound("NOT_FOUND", "Wave " + waveId + " 가 없습니다."));
    Support.requireCenter(w.cdcId());
    return view(w);
  }

  WaveView view(WaveRow w) {
    int[] s = store.taskSummary(w.waveId());
    List<String> trfs = store.transfersOfWave(w.waveId()).stream().map(TransferRow::trfId).toList();
    return new WaveView(w, trfs, s[0], s[1], s[2]);
  }

  public Outcome<CommandResult> release(
      String requestId, String sourceSystem, String waveId, @Nullable Integer expectedVersion) {
    WaveRow before =
        store
            .wave(waveId, false)
            .orElseThrow(() -> PmartException.notFound("NOT_FOUND", "Wave " + waveId + " 가 없습니다."));
    String by = Support.requireCenter(before.cdcId());
    // 재고 조회는 트랜잭션 밖에서(PLANNED 일 때만 — 이미 릴리스됐으면 아래 멱등 재생 또는 상태 거절)
    Map<String, List<Held>> held = new HashMap<>();
    Map<String, String> methods = new HashMap<>();
    if ("PLANNED".equals(before.state())) {
      for (TransferRow t : store.transfersOfWave(waveId)) {
        if (!CancellationService.CANCEL_STATES.contains(t.state())) {
          held.put(t.trfId(), held(t));
        }
      }
      // 통로 피킹 방식(cdc-location 0.5.0) — 안 되면 503 으로 릴리스하지 않는다
      Set<String> codes = new TreeSet<>();
      held.values().forEach(l -> l.forEach(h -> codes.add(h.locationCode())));
      if (!codes.isEmpty()) {
        methods.putAll(locations.pickMethods(before.cdcId(), codes));
      }
    }
    return idempotency.execute(
        Support.key(sourceSystem, requestId, OP_RELEASE, by),
        Map.of("waveId", waveId, "expectedVersion", String.valueOf(expectedVersion)),
        CommandResult.class,
        () -> {
          Instant at = support.now();
          WaveRow w = store.wave(waveId, true).orElseThrow();
          if (expectedVersion != null && expectedVersion != w.version()) {
            throw PmartException.versionConflict(
                "Wave 가 이미 바뀌었습니다(기대 버전 " + expectedVersion + ", 현재 " + w.version() + ").");
          }
          if (!"PLANNED".equals(w.state())) {
            throw PmartException.invalidState("Wave 상태 " + w.state() + " 에서는 릴리스할 수 없습니다.");
          }
          List<TransferRow> trfs = store.transfersOfWave(waveId);
          Map<String, String> regionOf = new HashMap<>();
          for (RegionRow r : store.regions(w.cdcId())) {
            regionOf.put(r.mfcId(), r.regionCode());
          }
          List<BatchPlan.Item> items = new ArrayList<>();
          for (TransferRow t : trfs) {
            if (CancellationService.CANCEL_STATES.contains(t.state())) {
              continue; // 미출발 취소(0.7.0) — 작업을 만들지 않는다
            }
            List<Held> h = held.get(t.trfId());
            if (h == null) {
              throw PmartException.versionConflict("Wave 의 TRF 가 방금 바뀌었습니다. 다시 릴리스하세요.");
            }
            for (Held x : h) {
              items.add(
                  new BatchPlan.Item(
                      t.trfId(),
                      t.mfcId(),
                      t.dueAt(),
                      regionOf.get(t.mfcId()),
                      x.sku(),
                      x.lot(),
                      x.locationCode(),
                      x.qty(),
                      methods.get(x.locationCode())));
            }
          }
          createTasks(w.cdcId(), waveId, items, at);
          if (!store.setWaveState(waveId, "RELEASED", w.version(), at)) {
            throw PmartException.versionConflict("Wave 가 방금 바뀌었습니다.");
          }
          publishState(
              w,
              "RELEASED",
              trfs.stream().map(TransferRow::trfId).toList(),
              by,
              w.version() + 1,
              at);
          return new CommandResult("APPLIED", requestId, waveId, w.version() + 1, at);
        });
  }

  /** 계획대로 PICK 작업을 만든다(릴리스·부족 재할당 공통 — 트랜잭션 안). 만든 작업 ID. */
  List<String> createTasks(String cdcId, String waveId, List<BatchPlan.Item> items, Instant at) {
    List<String> ids = new ArrayList<>();
    for (BatchPlan.Task p : BatchPlan.plan(items, props.batchMaxLines())) {
      String taskId = Support.newId("TASK");
      List<TaskLineRow> lines = new ArrayList<>();
      List<ShareRow> shares = new ArrayList<>();
      for (int i = 0; i < p.lines().size(); i++) {
        var l = p.lines().get(i);
        lines.add(new TaskLineRow(i, l.sku(), l.lot(), l.locationCode(), l.qty(), 0, 0, null));
        for (BatchPlan.Share sh : l.shares()) {
          shares.add(new ShareRow(i, sh.trfId(), sh.mfcId(), sh.dueAt(), sh.qty(), 0, 0));
        }
      }
      store.insertTask(
          new TaskRow(
              taskId,
              waveId,
              p.trfId(),
              cdcId,
              "PICK",
              p.zone(),
              p.floor(),
              p.dueAt(),
              "CREATED",
              1,
              null,
              at,
              null,
              p.pickMode(),
              p.pickMethod(),
              p.regionCode(),
              p.aisle()),
          lines,
          shares);
      ids.add(taskId);
    }
    return ids;
  }

  /** 지역표 MFC → 지역 코드. */
  Map<String, String> regionOf(String cdcId) {
    Map<String, String> out = new HashMap<>();
    for (RegionRow r : store.regions(cdcId)) {
      out.put(r.mfcId(), r.regionCode());
    }
    return out;
  }

  /** TRF 할당이 지금 잡은 LOT·위치(재고 서비스 정본). */
  private List<Held> held(TransferRow t) {
    if (t.allocationId() == null) {
      return List.of();
    }
    JsonNode a = inventory.allocation(t.allocationId());
    List<Held> out = new ArrayList<>();
    for (JsonNode line : a.path("lines")) {
      for (JsonNode lot : line.path("lots")) {
        out.add(
            new Held(
                line.path("sku").asString(),
                lot.path("lot").asString(),
                lot.path("locationCode").asString(),
                lot.path("qty").asInt(0)));
      }
    }
    return out;
  }

  void publishState(WaveRow w, String to, List<String> trfIds, String by, int version, Instant at) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("waveId", w.waveId());
    p.put("cdcId", w.cdcId());
    p.put("state", to);
    p.put("previousState", w.state());
    p.put("trfIds", trfIds);
    p.put("changedAt", at.toString());
    support.publish("WaveStateChanged", "Wave", w.waveId(), version, by, p);
  }
}
