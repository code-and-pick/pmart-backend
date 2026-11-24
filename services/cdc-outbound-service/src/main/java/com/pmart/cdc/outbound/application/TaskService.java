package com.pmart.cdc.outbound.application;

import com.pmart.cdc.outbound.application.Support.CommandResult;
import com.pmart.cdc.outbound.domain.PickPlan;
import com.pmart.cdc.outbound.infrastructure.OutboundStore;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 피킹 Task(0.3.0): 목록·조회, PDA 다음 작업 받기(자동 배정 — 사용자 결정 2026-11-25), 착수, 피킹 실적. 받기 = 수락(CDC-F-017 상세안
 * 5번), 동시에 받아도 한 작업은 한 사람(SKIP LOCKED). 실적은 담당의 IN_PROGRESS 작업에만.
 */
@Service
public class TaskService {

  static final String OP_NEXT = "cdcOutbound:nextTask";
  static final String OP_START = "cdcOutbound:startTask";
  static final String OP_PICK = "cdcOutbound:pickResult";

  public record TaskView(TaskRow task, List<TaskLineRow> lines) {}

  public record NextResult(boolean created, TaskView task) {}

  public record Page(List<TaskView> items, @Nullable String nextCursor) {}

  public record PickLine(
      String sku,
      String lot,
      String locationCode,
      int pickedQty,
      int shortQty,
      @Nullable String shortReason) {}

  private final OutboundStore store;
  private final RequestIdempotency idempotency;
  private final Support support;
  private final WaveService waves;

  public TaskService(
      OutboundStore store, RequestIdempotency idempotency, Support support, WaveService waves) {
    this.store = store;
    this.idempotency = idempotency;
    this.support = support;
    this.waves = waves;
  }

  @Transactional(readOnly = true)
  public Page list(
      String cdcId,
      String waveId,
      @Nullable String state,
      @Nullable String assigneeId,
      @Nullable String cursor,
      int size) {
    Support.requireCenter(cdcId);
    List<TaskRow> rows = store.tasks(cdcId, waveId, state, assigneeId, cursor, size + 1);
    boolean more = rows.size() > size;
    List<TaskView> items = new ArrayList<>();
    for (TaskRow t : more ? rows.subList(0, size) : rows) {
      items.add(new TaskView(t, store.taskLines(t.taskId())));
    }
    return new Page(items, more ? items.get(items.size() - 1).task().taskId() : null);
  }

  @Transactional(readOnly = true)
  public TaskView get(String cdcId, String taskId) {
    Support.requireCenter(cdcId);
    TaskRow t = find(cdcId, taskId, false);
    return new TaskView(t, store.taskLines(taskId));
  }

  public Outcome<NextResult> next(
      String requestId,
      String sourceSystem,
      String cdcId,
      String workerId,
      @Nullable String kind,
      @Nullable String zone,
      @Nullable String floor) {
    Support.requireSelf(cdcId, workerId);
    Map<String, String> req = new LinkedHashMap<>();
    req.put("cdcId", cdcId);
    req.put("workerId", workerId);
    req.put("kind", String.valueOf(kind));
    req.put("zone", String.valueOf(zone));
    req.put("floor", String.valueOf(floor));
    return idempotency.execute(
        Support.key(sourceSystem, requestId, OP_NEXT, workerId),
        req,
        NextResult.class,
        () -> {
          store.lockWorker(cdcId, workerId);
          Optional<TaskRow> held = store.heldBy(cdcId, workerId);
          if (held.isPresent()) {
            return new NextResult(false, view(held.get().taskId()));
          }
          TaskRow cand =
              store
                  .lockNextCandidate(cdcId, kind, zone, floor)
                  .orElseThrow(
                      () ->
                          new PmartException(
                              "NO_TASK_AVAILABLE",
                              HttpStatus.CONFLICT,
                              "받을 수 있는 작업이 없습니다.",
                              false,
                              List.of()));
          store.assign(cand.taskId(), workerId, support.now());
          return new NextResult(true, view(cand.taskId()));
        });
  }

  public Outcome<TaskView> start(
      String requestId,
      String sourceSystem,
      String cdcId,
      String taskId,
      @Nullable Integer expectedVersion) {
    String me = Support.requireCenter(cdcId);
    return idempotency.execute(
        Support.key(sourceSystem, requestId, OP_START, me),
        Map.of(
            "cdcId", cdcId, "taskId", taskId, "expectedVersion", String.valueOf(expectedVersion)),
        TaskView.class,
        () -> {
          Instant at = support.now();
          TaskRow t = find(cdcId, taskId, true);
          if (!me.equals(t.assigneeId())) {
            throw notAssignee(taskId);
          }
          checkVersion(t, expectedVersion);
          if ("IN_PROGRESS".equals(t.state())) {
            return view(taskId);
          }
          if (!"ACCEPTED".equals(t.state())) {
            throw PmartException.invalidState("작업 상태 " + t.state() + " 에서는 착수할 수 없습니다.");
          }
          store.setTaskState(taskId, "IN_PROGRESS", at);
          WaveRow w = store.wave(t.waveId(), true).orElseThrow();
          if ("RELEASED".equals(w.state())
              && store.setWaveState(w.waveId(), "IN_PROGRESS", w.version(), null)) {
            waves.publishState(w, "IN_PROGRESS", trfIds(w.waveId()), me, w.version() + 1, at);
          }
          return view(taskId);
        });
  }

  public Outcome<CommandResult> pick(
      String requestId,
      String sourceSystem,
      String cdcId,
      String taskId,
      String workerId,
      List<PickLine> lines,
      @Nullable Integer expectedVersion) {
    Support.requireSelf(cdcId, workerId);
    if (lines.isEmpty()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "실적 줄이 비었습니다.");
    }
    Map<String, Object> req = new LinkedHashMap<>();
    req.put("cdcId", cdcId);
    req.put("taskId", taskId);
    req.put("workerId", workerId);
    req.put("expectedVersion", String.valueOf(expectedVersion));
    req.put("lines", lines);
    return idempotency.execute(
        Support.key(sourceSystem, requestId, OP_PICK, workerId),
        req,
        CommandResult.class,
        () -> {
          Instant at = support.now();
          TaskRow t = find(cdcId, taskId, true);
          if (!workerId.equals(t.assigneeId())) {
            throw notAssignee(taskId);
          }
          checkVersion(t, expectedVersion);
          if (!"IN_PROGRESS".equals(t.state())) {
            throw PmartException.invalidState("실적은 착수한(IN_PROGRESS) 작업에만 — 지금 " + t.state() + ".");
          }
          List<TaskLineRow> current = store.taskLines(taskId);
          // 1) 전 줄 검증
          List<PmartException.Detail> unknown = new ArrayList<>();
          List<TaskLineRow> matched = new ArrayList<>();
          Map<Integer, int[]> pending = new LinkedHashMap<>();
          for (int i = 0; i < lines.size(); i++) {
            PickLine l = lines.get(i);
            TaskLineRow row =
                current.stream()
                    .filter(
                        r ->
                            r.sku().equals(l.sku())
                                && r.lot().equals(l.lot())
                                && r.locationCode().equals(l.locationCode()))
                    .findFirst()
                    .orElse(null);
            if (row == null) {
              unknown.add(new PmartException.Detail("lines[" + i + "]", "NOT_IN_TASK"));
              continue;
            }
            int[] acc = pending.computeIfAbsent(row.lineNo(), k -> new int[2]);
            acc[0] += l.pickedQty();
            acc[1] += l.shortQty();
            if (row.pickedQty() + row.shortQty() + acc[0] + acc[1] > row.plannedQty()) {
              throw new PmartException(
                  "PICK_EXCEEDS_ALLOCATION",
                  HttpStatus.CONFLICT,
                  "lines["
                      + i
                      + "] 실피킹 + 부족이 남은 계획("
                      + (row.plannedQty() - row.pickedQty() - row.shortQty())
                      + ")보다 많습니다.",
                  false,
                  List.of(
                      new PmartException.Detail("lines[" + i + "]", "PICK_EXCEEDS_ALLOCATION")));
            }
            matched.add(row);
          }
          if (!unknown.isEmpty()) {
            throw new PmartException(ErrorCode.VALIDATION_FAILED, "작업에 없는 줄이 있습니다.", unknown);
          }
          // 2) 반영
          TransferRow trf = store.transfer(t.trfId(), true).orElseThrow();
          for (int i = 0; i < lines.size(); i++) {
            PickLine l = lines.get(i);
            TaskLineRow row = matched.get(i);
            store.addLineResult(taskId, row.lineNo(), l.pickedQty(), l.shortQty(), l.shortReason());
            store.addLinePicked(t.trfId(), l.sku(), l.pickedQty(), l.shortQty());
            if (l.shortQty() > 0) {
              Map<String, Object> p = new LinkedHashMap<>();
              p.put("trfId", t.trfId());
              if (trf.allocationId() != null) {
                p.put("allocationId", trf.allocationId());
              }
              p.put("waveId", t.waveId());
              p.put("pickTaskId", taskId);
              p.put("sku", l.sku());
              p.put("lot", l.lot());
              p.put("locCode", l.locationCode());
              p.put("shortQty", l.shortQty());
              p.put("reason", l.shortReason() == null ? "OTHER" : l.shortReason());
              p.put("reportedAt", at.toString());
              support.publish(
                  "PickShortageReported",
                  "OutboundTransfer",
                  t.trfId(),
                  trf.version(),
                  workerId,
                  p);
            }
          }
          List<int[]> after = new ArrayList<>();
          for (TaskLineRow r : store.taskLines(taskId)) {
            after.add(new int[] {r.plannedQty(), r.pickedQty(), r.shortQty()});
          }
          String state = PickPlan.afterReport(t.state(), after);
          int version = t.version();
          if (!state.equals(t.state())) {
            store.setTaskState(taskId, state, at);
            version++;
            if (store.openTasks("trf_id", t.trfId()) == 0) {
              store.setTransferState(t.trfId(), "PICKED", null, at);
            }
            if (store.openTasks("wave_id", t.waveId()) == 0) {
              WaveRow w = store.wave(t.waveId(), true).orElseThrow();
              if (store.setWaveState(w.waveId(), "WORK_DONE", w.version(), null)) {
                waves.publishState(
                    w, "WORK_DONE", trfIds(w.waveId()), workerId, w.version() + 1, at);
              }
            }
          }
          return new CommandResult("APPLIED", requestId, taskId, version, at);
        });
  }

  private TaskView view(String taskId) {
    return new TaskView(store.task(taskId, false).orElseThrow(), store.taskLines(taskId));
  }

  private TaskRow find(String cdcId, String taskId, boolean lock) {
    return store
        .task(taskId, lock)
        .filter(t -> t.cdcId().equals(cdcId))
        .orElseThrow(() -> PmartException.notFound("NOT_FOUND", "작업 " + taskId + " 이 없습니다."));
  }

  private List<String> trfIds(String waveId) {
    return store.transfersOfWave(waveId).stream().map(TransferRow::trfId).toList();
  }

  private static void checkVersion(TaskRow t, @Nullable Integer expected) {
    if (expected != null && !Objects.equals(expected, t.version())) {
      throw PmartException.versionConflict(
          "작업이 이미 바뀌었습니다(기대 버전 " + expected + ", 현재 " + t.version() + ").");
    }
  }

  private static PmartException notAssignee(String taskId) {
    return new PmartException(
        "NOT_ASSIGNEE", HttpStatus.CONFLICT, "작업 " + taskId + " 의 담당이 아닙니다.", false, List.of());
  }
}
