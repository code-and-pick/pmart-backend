package com.pmart.mfc.inbound.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.CommandResponse;
import com.pmart.common.web.CommandStatus;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.inbound.infrastructure.InboundTaskStore;
import com.pmart.mfc.inbound.infrastructure.InboundTaskStore.ExecutionRow;
import com.pmart.mfc.inbound.infrastructure.InboundTaskStore.TaskRow;
import com.pmart.mfc.inbound.infrastructure.InitialStockStore;
import com.pmart.mfc.inbound.infrastructure.InitialStockStore.LoadRow;
import com.pmart.mfc.inbound.infrastructure.InventoryReceiptClient;
import com.pmart.mfc.inbound.infrastructure.InventoryReceiptClient.Entry;
import com.pmart.mfc.inbound.infrastructure.InventoryReceiptClient.Result;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 입고 현장 작업(ADR-0040 3단계, 사용자 결정 2026-11-07): 초기 보유 입고 예정의 현장 수령(상품·수량·수령 위치) → 품질검사(정상/격리 판정 + LOT
 * 분리) → 적치. 품질검사·적치는 IV-13 순서다 — 현장 사실을 먼저 기록(CAPTURED, 짧은 트랜잭션) → mfc-inventory 반영(트랜잭션 밖) → 결과
 * 기록(APPLIED 면 작업 완료량·다음 작업, 거절이면 REJECTED, 응답 불명이면 UNKNOWN_RESULT 로 두고 같은 실행 ID 로 다시). 쓰기·읽기는 그
 * 센터의 운영자(현장 단말 자리 — PDA 가 생기면 같은 API).
 */
@Service
public class InboundTaskService {

  public static final String TASK_NOT_FOUND = "INBOUND_TASK_NOT_FOUND";

  private static final Logger log = LoggerFactory.getLogger(InboundTaskService.class);

  /** 품질검사 결과 줄. */
  public record InspectResult(
      int qty,
      String resultQuality,
      @Nullable String sourceLotRef,
      @Nullable LocalDate expiryDate,
      @Nullable LocalDate manufactureDate) {}

  /** 실행 결과. */
  public record ExecutionView(
      String executionId,
      String taskId,
      String applyStatus,
      @Nullable String commitId,
      @Nullable String rejectReason,
      List<String> createdTaskIds,
      TaskRow task) {}

  record ReceiveRequest(String receiptId, String toLocationCode, Map<String, Integer> lines) {}

  private final InitialStockStore loads;
  private final InboundTaskStore tasks;
  private final InventoryReceiptClient inventory;
  private final RequestIdempotency idempotency;
  private final TransactionTemplate tx;
  private final ObjectMapper mapper;
  private final Clock clock;

  public InboundTaskService(
      InitialStockStore loads,
      InboundTaskStore tasks,
      InventoryReceiptClient inventory,
      RequestIdempotency idempotency,
      TransactionTemplate tx,
      ObjectMapper mapper,
      Clock clock) {
    this.loads = loads;
    this.tasks = tasks;
    this.inventory = inventory;
    this.idempotency = idempotency;
    this.tx = tx;
    this.mapper = mapper;
    this.clock = clock;
  }

  // ---------- 현장 수령 ----------

  public CommandResponse receive(
      String requestId,
      String sourceSystem,
      String receiptId,
      String toLocationCode,
      Map<String, Integer> received) {
    LoadRow load =
        loads
            .find(receiptId)
            .orElseThrow(
                () ->
                    PmartException.notFound(
                        InitialStockService.NOT_FOUND, "초기 보유 반입 기록이 없습니다: " + receiptId));
    if (load.mfcId() == null) {
      throw PmartException.invalidState("센터가 정해지지 않은 반입 기록입니다(승인 내용 오류).");
    }
    String operator = requireOperator(load.mfcId());
    if (toLocationCode == null || toLocationCode.isBlank()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "toLocationCode(수령 위치)가 필요합니다.");
    }
    int total = 0;
    for (Map.Entry<String, Integer> e : received.entrySet()) {
      Integer approved = load.lines().get(e.getKey());
      if (approved == null) {
        throw new PmartException(
            ErrorCode.VALIDATION_FAILED, e.getKey() + " 는 이 입고 예정(승인)에 없는 상품입니다.");
      }
      if (e.getValue() < 0 || e.getValue() > approved) {
        throw new PmartException(
            ErrorCode.VALIDATION_FAILED,
            e.getKey()
                + " 수령 수량 "
                + e.getValue()
                + " 는 0 이상, 승인 수량 "
                + approved
                + " 이하여야 합니다(초과분은 정상 수령에 합산하지 않음).");
      }
      total += e.getValue();
    }
    if (total == 0) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "수령 수량 합이 0 입니다.");
    }
    int sum = total;
    return idempotency
        .execute(
            new RequestKey(sourceSystem, requestId, "receiveInitialStock", operator),
            new ReceiveRequest(receiptId, toLocationCode, new TreeMap<>(received)),
            CommandResponse.class,
            () -> {
              String state = loads.lockState(receiptId).orElseThrow();
              if (!"EXPECTED".equals(state)) {
                throw PmartException.invalidState(
                    "입고 예정(EXPECTED) 상태가 아닙니다: " + receiptId + " (" + state + ")");
              }
              Instant at = clock.instant();
              loads.markReceived(receiptId, toLocationCode, operator, received, at);
              log.info(
                  "initial stock {} received at {} {} ({} units)",
                  receiptId,
                  load.mfcId(),
                  toLocationCode,
                  sum);
              return new CommandResponse(
                  CommandStatus.ACCEPTED, requestId, receiptId, sum, null, false, null, at);
            })
        .value();
  }

  // ---------- 작업 조회 ----------

  @Transactional(readOnly = true)
  public List<TaskRow> list(
      String mfcId, @Nullable String state, @Nullable String type, @Nullable String receiptId) {
    requireOperator(mfcId);
    return tasks.list(mfcId, state, type, receiptId);
  }

  /** 작업 단건(0.8.0) — 그 센터 운영자만. */
  @Transactional(readOnly = true)
  public TaskRow get(String taskId) {
    TaskRow t =
        tasks
            .find(taskId)
            .orElseThrow(() -> PmartException.notFound(TASK_NOT_FOUND, "입고 작업이 없습니다: " + taskId));
    requireOperator(t.mfcId());
    return t;
  }

  // ---------- 품질검사 ----------

  public ExecutionView inspect(
      String requestId, String sourceSystem, String taskId, List<InspectResult> results) {
    for (InspectResult r : results) {
      if (r.qty() < 1) {
        throw new PmartException(ErrorCode.VALIDATION_FAILED, "수량은 1 이상이어야 합니다.");
      }
      if (!"NORMAL".equals(r.resultQuality()) && !"QUARANTINED".equals(r.resultQuality())) {
        throw new PmartException(
            ErrorCode.VALIDATION_FAILED, "resultQuality 는 NORMAL/QUARANTINED.");
      }
      if (r.manufactureDate() != null) {
        LocalDate today = LocalDate.now(clock.withZone(java.time.ZoneId.of("Asia/Seoul")));
        if (r.manufactureDate().isAfter(today)) {
          throw new PmartException(
              ErrorCode.VALIDATION_FAILED, "제조일이 오늘 이후입니다: " + r.manufactureDate());
        }
        if (r.expiryDate() != null && r.manufactureDate().isAfter(r.expiryDate())) {
          throw new PmartException(
              ErrorCode.VALIDATION_FAILED,
              "제조일 " + r.manufactureDate() + " 이 소비기한 " + r.expiryDate() + " 보다 늦습니다.");
        }
      }
    }
    int qty = results.stream().mapToInt(InspectResult::qty).sum();
    return run(requestId, taskId, "INSPECT", qty, mapper.writeValueAsString(results));
  }

  // ---------- 적치 ----------

  public ExecutionView putaway(
      String requestId, String sourceSystem, String taskId, String toLocationCode, int qty) {
    if (qty < 1) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "수량은 1 이상이어야 합니다.");
    }
    if (toLocationCode == null || toLocationCode.isBlank()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "toLocationCode 가 필요합니다.");
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("toLocationCode", toLocationCode);
    body.put("qty", qty);
    return run(requestId, taskId, "PUTAWAY", qty, mapper.writeValueAsString(body));
  }

  // ---------- 실행(IV-13) ----------

  private ExecutionView run(
      String requestId, String taskId, String type, int qty, String bodyJson) {
    TaskRow peek =
        tasks
            .find(taskId)
            .orElseThrow(() -> PmartException.notFound(TASK_NOT_FOUND, "입고 작업이 없습니다: " + taskId));
    String operator = requireOperator(peek.mfcId());
    if (!type.equals(peek.taskType())) {
      throw PmartException.invalidState(taskId + " 는 " + peek.taskType() + " 작업입니다.");
    }
    ExecutionRow exec =
        tx.execute(
            s -> {
              var existing = tasks.execution(taskId, requestId);
              if (existing.isPresent()) {
                if (!mapper.readTree(existing.get().body()).equals(mapper.readTree(bodyJson))) {
                  throw PmartException.idempotencyConflict("같은 Idempotency-Key 에 다른 내용입니다.");
                }
                return existing.get();
              }
              TaskRow t = tasks.lock(taskId).orElseThrow();
              if (!"READY".equals(t.state())) {
                throw PmartException.invalidState(taskId + " 는 이미 끝난 작업입니다(" + t.state() + ").");
              }
              int open = 0;
              for (ExecutionRow o : tasks.openExecutions(taskId)) {
                open += qtyOf(t.taskType(), o.body());
              }
              if (qty > t.remaining() - open) {
                throw new PmartException(
                    "TASK_QTY_EXCEEDED",
                    HttpStatus.CONFLICT,
                    "작업 잔량(" + (t.remaining() - open) + ")보다 많습니다: " + qty,
                    false,
                    List.of());
              }
              String executionId = "EXE-" + UUID.randomUUID();
              tasks.insertExecution(
                  executionId, taskId, requestId, bodyJson, operator, clock.instant());
              return tasks.execution(taskId, requestId).orElseThrow();
            });
    if (exec == null) {
      throw new IllegalStateException("execution");
    }
    if ("APPLIED".equals(exec.applyStatus()) || "REJECTED".equals(exec.applyStatus())) {
      return view(exec, tasks.find(taskId).orElseThrow());
    }
    return submit(exec, peek);
  }

  /** CAPTURED·UNKNOWN_RESULT 실행을 inventory 에 반영(같은 실행 ID — inventory 가 한 번만 반영). */
  private ExecutionView submit(ExecutionRow exec, TaskRow task) {
    Map<String, Object> body = inventoryBody(exec, task);
    Result r = inventory.execute(exec.executionId(), body);
    Instant at = clock.instant();
    switch (r) {
      case Result.Posted p -> {
        List<Entry> entries;
        if ("INSPECT".equals(task.taskType())) {
          try {
            entries = inventory.commitEntries(p.commitId());
          } catch (RuntimeException e) {
            tx.executeWithoutResult(
                s ->
                    tasks.markExecution(
                        exec.executionId(), "UNKNOWN_RESULT", null, e.getMessage(), at));
            throw PmartException.unknownResult("반영은 됐을 수 있으나 결과(LOT)를 읽지 못했습니다 — 같은 요청을 다시 보내세요.");
          }
        } else {
          entries = List.of();
        }
        List<String> created = new ArrayList<>();
        tx.executeWithoutResult(
            s -> {
              tasks.markExecution(exec.executionId(), "APPLIED", p.commitId(), null, at);
              tasks.addDone(task.taskId(), qtyOf(task.taskType(), exec.body()), at);
              if ("INSPECT".equals(task.taskType())) {
                created.addAll(putawaysFrom(task, entries, at));
              }
            });
        log.info(
            "{} {} on task {} APPLIED as {} (new tasks {})",
            task.taskType(),
            exec.executionId(),
            task.taskId(),
            p.commitId(),
            created);
        return new ExecutionView(
            exec.executionId(),
            task.taskId(),
            "APPLIED",
            p.commitId(),
            null,
            created,
            tasks.find(task.taskId()).orElseThrow());
      }
      case Result.Rejected j -> {
        tx.executeWithoutResult(
            s -> tasks.markExecution(exec.executionId(), "REJECTED", null, j.reason(), at));
        log.warn(
            "{} {} REJECTED by inventory: {}", task.taskType(), exec.executionId(), j.reason());
        return new ExecutionView(
            exec.executionId(), task.taskId(), "REJECTED", null, j.reason(), List.of(), task);
      }
      case Result.Retry t -> {
        tx.executeWithoutResult(
            s -> tasks.markExecution(exec.executionId(), "UNKNOWN_RESULT", null, t.error(), at));
        throw PmartException.unknownResult(
            "재고 반영 결과를 모릅니다(" + t.error() + ") — 같은 Idempotency-Key 로 다시 보내세요.");
      }
    }
  }

  /** 품질검사 결과의 정상 LOT 마다 적치 작업. */
  private List<String> putawaysFrom(TaskRow task, List<Entry> entries, Instant at) {
    Map<String, Integer> byLot = new TreeMap<>();
    for (Entry e : entries) {
      if ("H".equals(e.symbol())
          && e.delta() > 0
          && "NORMAL".equals(e.qualityState())
          && "UNSTOWED".equals(e.workState())
          && task.locationCode().equals(e.locationCode())
          && e.lotId() != null) {
        byLot.merge(e.lotId(), e.delta(), Integer::sum);
      }
    }
    List<String> out = new ArrayList<>();
    byLot.forEach(
        (lot, q) ->
            out.add(
                tasks.insert(
                    "PUTAWAY",
                    task.mfcId(),
                    task.receiptId(),
                    task.taskId(),
                    task.sku(),
                    lot,
                    task.locationCode(),
                    q,
                    at)));
    return out;
  }

  private Map<String, Object> inventoryBody(ExecutionRow exec, TaskRow task) {
    JsonNode b = mapper.readTree(exec.body());
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("executionType", task.taskType());
    body.put("mfcId", task.mfcId());
    body.put("taskId", task.taskId());
    body.put("executionId", exec.executionId());
    body.put("fromLocation", task.locationCode());
    List<Map<String, Object>> lines = new ArrayList<>();
    if ("INSPECT".equals(task.taskType())) {
      for (JsonNode r : b) {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("sku", task.sku());
        l.put("lotId", task.lotId());
        l.put("qty", r.path("qty").asInt());
        l.put("resultQuality", r.path("resultQuality").asString());
        if (r.hasNonNull("sourceLotRef")) {
          l.put("sourceLotRef", r.get("sourceLotRef").asString());
        }
        if (r.hasNonNull("expiryDate")) {
          l.put("expiryDate", r.get("expiryDate").asString());
        }
        if (r.hasNonNull("manufactureDate")) {
          l.put("manufactureDate", r.get("manufactureDate").asString());
        }
        lines.add(l);
      }
    } else {
      body.put("toLocation", b.path("toLocationCode").asString());
      Map<String, Object> l = new LinkedHashMap<>();
      l.put("sku", task.sku());
      l.put("lotId", task.lotId());
      l.put("qty", b.path("qty").asInt());
      lines.add(l);
    }
    body.put("lines", lines);
    return body;
  }

  private int qtyOf(String type, String bodyJson) {
    JsonNode b = mapper.readTree(bodyJson);
    if ("INSPECT".equals(type)) {
      int sum = 0;
      for (JsonNode r : b) {
        sum += r.path("qty").asInt();
      }
      return sum;
    }
    return b.path("qty").asInt();
  }

  private ExecutionView view(ExecutionRow e, TaskRow task) {
    return new ExecutionView(
        e.executionId(),
        task.taskId(),
        e.applyStatus(),
        e.commitId(),
        e.rejectReason(),
        "INSPECT".equals(task.taskType()) && "APPLIED".equals(e.applyStatus())
            ? tasks.childTasks(task.taskId(), task.receiptId())
            : List.of(),
        task);
  }

  static String requireOperator(String mfcId) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .filter(x -> x.type() == PmartPrincipal.PrincipalType.OPERATOR)
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "운영자만 할 수 있습니다."));
    if (!p.canAccessCenter(mfcId)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "이 센터에 대한 권한이 없습니다.");
    }
    return p.subject();
  }
}
