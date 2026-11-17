package com.pmart.mfc.fulfillment.api;

import com.pmart.common.web.PmartException;
import com.pmart.mfc.fulfillment.api.generated.TasksApi;
import com.pmart.mfc.fulfillment.api.generated.model.ListTasks200Response;
import com.pmart.mfc.fulfillment.api.generated.model.ListTasks200ResponseAsOf;
import com.pmart.mfc.fulfillment.api.generated.model.ListTasks200ResponsePage;
import com.pmart.mfc.fulfillment.api.generated.model.ResolveShortage200Response;
import com.pmart.mfc.fulfillment.api.generated.model.StartTaskRequest;
import com.pmart.mfc.fulfillment.api.generated.model.TaskActiveStart;
import com.pmart.mfc.fulfillment.api.generated.model.TaskAssignment;
import com.pmart.mfc.fulfillment.api.generated.model.TaskDetail;
import com.pmart.mfc.fulfillment.api.generated.model.TaskExecution;
import com.pmart.mfc.fulfillment.api.generated.model.TaskExecutionRequest;
import com.pmart.mfc.fulfillment.api.generated.model.TaskExecutionShortagesInner;
import com.pmart.mfc.fulfillment.api.generated.model.TaskLine;
import com.pmart.mfc.fulfillment.api.generated.model.TaskLinePicksInner;
import com.pmart.mfc.fulfillment.api.generated.model.TaskLinePicksInnerLocationsInner;
import com.pmart.mfc.fulfillment.api.generated.model.TaskPendingExecution;
import com.pmart.mfc.fulfillment.api.generated.model.TaskStart;
import com.pmart.mfc.fulfillment.api.generated.model.TaskState;
import com.pmart.mfc.fulfillment.api.generated.model.TaskSummary;
import com.pmart.mfc.fulfillment.api.generated.model.TaskType;
import com.pmart.mfc.fulfillment.application.TaskService;
import com.pmart.mfc.fulfillment.application.TaskService.ExecCommand;
import com.pmart.mfc.fulfillment.application.TaskService.ExecLine;
import com.pmart.mfc.fulfillment.application.TaskService.Pick;
import com.pmart.mfc.fulfillment.application.TaskService.Shortage;
import com.pmart.mfc.fulfillment.infrastructure.TaskStore.ExecutionRow;
import com.pmart.mfc.fulfillment.infrastructure.TaskStore.LineView;
import com.pmart.mfc.fulfillment.infrastructure.TaskStore.StartRow;
import com.pmart.mfc.fulfillment.infrastructure.TaskStore.TaskInfo;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** 계약 TasksApi — 점원 단말 작업(슬라이스 6b). 업무 규칙은 {@link TaskService}. */
@RestController
public class TasksController implements TasksApi {

  private final TaskService service;
  private final ObjectMapper mapper;

  public TasksController(TaskService service, ObjectMapper mapper) {
    this.service = service;
    this.mapper = mapper;
  }

  @Override
  public ResponseEntity<ListTasks200Response> listTasks(
      String mfcId,
      @Nullable String assignee,
      @Nullable TaskType type,
      @Nullable List<TaskState> state,
      Integer size,
      @Nullable String cursor) {
    int limit = size == null ? 50 : size;
    List<TaskInfo> rows =
        service.list(
            mfcId,
            assignee,
            type == null ? null : type.getValue(),
            state == null ? null : state.stream().map(TaskState::getValue).toList(),
            limit + 1,
            cursor);
    boolean more = rows.size() > limit;
    List<TaskInfo> page = more ? rows.subList(0, limit) : rows;
    var out = new ListTasks200Response();
    List<TaskSummary> items = new ArrayList<>();
    Map<String, List<LineView>> linesOf =
        service.linesOf(page.stream().map(TaskInfo::taskId).toList());
    for (TaskInfo t : page) {
      TaskSummary s = attention(summary(t), t);
      counts(s, linesOf.getOrDefault(t.taskId(), List.of()));
      items.add(s);
    }
    out.setItems(items);
    var p = new ListTasks200ResponsePage();
    p.setSize(page.size());
    p.setNextCursor(more ? page.get(page.size() - 1).taskId() : null);
    out.setPage(p);
    out.setAsOf(asOf(true));
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<TaskDetail> getTask(String taskId) {
    var d = service.detail(taskId);
    TaskInfo t = d.task();
    var out = new TaskDetail();
    fill(out, t, d.lines());
    var hl = headline(d.lines());
    out.setHeadline(hl);
    var att = service.attention(t);
    out.setLate(att.late());
    out.setIdle(att.idle());
    out.setLastActivityAt(time(att.lastActivityAt()));
    List<TaskLine> lines = new ArrayList<>();
    Map<String, Integer> pending = pendingByLine(d.pending());
    for (LineView l : d.lines()) {
      var tl = new TaskLine();
      tl.setTaskLineId(t.taskId() + ":" + l.orderLineId());
      tl.setOrderLineId(l.orderLineId());
      tl.setSku(l.sku());
      tl.setProductName(l.productName());
      List<String> codes = d.barcodesBySku().getOrDefault(l.sku(), List.of());
      tl.setBarcodes(codes);
      tl.setBarcode(codes.isEmpty() ? null : codes.get(0));
      tl.setPlannedQty(l.plannedQty());
      tl.setCompletedQty(l.completedQty());
      tl.setCancelledQty(l.cancelledQty());
      tl.setRemainingQty(l.remaining());
      tl.setPendingQty(pending.getOrDefault(l.orderLineId(), 0));
      List<TaskLinePicksInner> picks = new ArrayList<>();
      for (Pick p : d.picksByLine().getOrDefault(l.orderLineId(), List.of())) {
        var pi = new TaskLinePicksInner();
        pi.setLotId(p.lotId());
        pi.setLotNo(p.lotNo());
        pi.setExpiresOn(p.expiresOn() == null ? null : LocalDate.parse(p.expiresOn()));
        pi.setRemainingQty(p.remainingQty());
        List<TaskLinePicksInnerLocationsInner> locs = new ArrayList<>();
        for (var e : p.locations()) {
          var li = new TaskLinePicksInnerLocationsInner();
          li.setLocationCode(e.getKey());
          li.setQty(e.getValue());
          li.setAllocatedQty(p.allocated().getOrDefault(e.getKey(), 0));
          locs.add(li);
        }
        pi.setLocations(locs);
        pi.setSuggestedToLocations(p.suggestedTo());
        if ("PICK".equals(t.taskType())) {
          String from = com.pmart.mfc.fulfillment.application.TaskService.fromLocation(p);
          pi.setVisitSeq(from == null ? null : d.visitSeq().get(from));
          var st =
              com.pmart.mfc.fulfillment.domain.TaskRules.pickStatus(
                  p.remainingQty(),
                  p.locations().stream().mapToInt(Map.Entry::getValue).sum(),
                  p.allocated().values().stream().mapToInt(Integer::intValue).sum(),
                  "IN_PROGRESS".equals(t.state()));
          pi.setUnallocatedQty(st.unallocatedQty());
          pi.setPickStatus(TaskLinePicksInner.PickStatusEnum.fromValue(st.status()));
          pi.setNotice(st.notice());
        }
        picks.add(pi);
      }
      tl.setPicks(picks);
      if (!picks.isEmpty()) {
        tl.setLotId(picks.get(0).getLotId());
        tl.setExpiresOn(picks.get(0).getExpiresOn());
        var locs = picks.get(0).getLocations();
        tl.setLocationId(locs == null || locs.isEmpty() ? null : locs.get(0).getLocationCode());
      }
      lines.add(tl);
    }
    out.setLines(lines);
    out.setRouteMethod(
        d.routeMethod() == null ? null : TaskDetail.RouteMethodEnum.fromValue(d.routeMethod()));
    if ("IN_PROGRESS".equals(t.state()) && t.startedBy() != null) {
      var s = new TaskActiveStart();
      s.setRequestId(t.startRequestId());
      s.setWorkerId(t.startedBy());
      s.setDeviceId(t.deviceId());
      s.setStartedAt(time(t.startedAt()));
      out.setActiveStart(s);
    }
    List<TaskPendingExecution> pend = new ArrayList<>();
    for (ExecutionRow e : d.pending()) {
      var pe = new TaskPendingExecution();
      pe.setExecutionId(e.executionId());
      pe.setState(
          TaskPendingExecution.StateEnum.fromValue(
              "RECORDED".equals(e.state()) ? "RECORDED" : "UNKNOWN_RESULT"));
      pe.setRecordedAt(time(e.recordedAt()));
      pend.add(pe);
    }
    out.setPendingExecutions(pend);
    out.setBlockReason(t.blockReason());
    out.setPackingLocations(d.packingLocations());
    out.setAsOf(asOf(d.planFresh()));
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<ResolveShortage200Response> startTask(
      String taskId,
      String idempotencyKey,
      String xSourceSystem,
      StartTaskRequest body,
      @Nullable Integer xExpectedVersion) {
    StartRow r =
        service.start(
            taskId,
            idempotencyKey,
            body.getWorkerId(),
            body.getAssignmentVersion(),
            body.getDeviceId(),
            xExpectedVersion);
    if (!"STARTED".equals(r.outcome())) {
      String code = r.rejectCode() == null ? "INVALID_STATE" : r.rejectCode();
      throw new PmartException(
          code, HttpStatus.CONFLICT, "작업을 시작할 수 없습니다: " + code, false, List.of());
    }
    var out = new ResolveShortage200Response();
    out.setStatus(ResolveShortage200Response.StatusEnum.APPLIED);
    out.setRequestId(idempotencyKey);
    out.setResultId(taskId);
    out.setVersion(r.taskVersion());
    out.setRetryable(false);
    out.setOccurredAt(time(r.decidedAt()));
    return ResponseEntity.status(HttpStatus.CREATED).body(out);
  }

  @Override
  public ResponseEntity<TaskStart> getTaskStart(String taskId, String requestId) {
    Map<String, Object> m = service.getStart(taskId, requestId);
    var out = new TaskStart();
    out.setRequestId((String) m.get("requestId"));
    out.setTaskId((String) m.get("taskId"));
    out.setOutcome(TaskStart.OutcomeEnum.fromValue((String) m.get("outcome")));
    out.setRejectCode((String) m.get("rejectCode"));
    out.setWorkerId((String) m.get("workerId"));
    out.setAssignmentVersion((Integer) m.get("assignmentVersion"));
    out.setTaskVersion((Integer) m.get("taskVersion"));
    out.setDecidedAt(time((Instant) m.get("decidedAt")));
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<ResolveShortage200Response> recordTaskExecution(
      String taskId,
      String idempotencyKey,
      String xSourceSystem,
      TaskExecutionRequest body,
      @Nullable Integer xExpectedVersion) {
    List<ExecLine> lines = new ArrayList<>();
    for (var l : body.getLines()) {
      Shortage s = null;
      if (l.getShortage() != null && l.getShortage().getQty() != null) {
        s =
            new Shortage(
                l.getShortage().getQty(),
                l.getShortage().getReason() == null
                    ? "SHORT"
                    : l.getShortage().getReason().getValue(),
                l.getShortage().getNote());
      }
      lines.add(
          new ExecLine(
              l.getOrderLineId(),
              l.getSku(),
              l.getLotId(),
              l.getPickedQty(),
              s,
              l.getResultQuality() == null ? null : l.getResultQuality().getValue()));
    }
    var r =
        service.execute(
            taskId,
            idempotencyKey,
            new ExecCommand(
                body.getExecutionType().getValue(),
                body.getWorkerId(),
                body.getAssignmentVersion(),
                body.getFromLocation(),
                body.getToLocation(),
                lines),
            xExpectedVersion);
    ExecutionRow e = r.execution();
    var out = new ResolveShortage200Response();
    out.setStatus(
        ResolveShortage200Response.StatusEnum.fromValue(
            switch (e.state()) {
              case "APPLIED" -> "APPLIED";
              case "REJECTED" -> "REJECTED";
              case "UNKNOWN_RESULT" -> "UNKNOWN_RESULT";
              default -> "ACCEPTED";
            }));
    out.setRequestId(idempotencyKey);
    out.setResultId(e.executionId());
    out.setAppliedQty(e.appliedQty());
    out.setVersion(r.taskVersion());
    out.setRetryable(!"APPLIED".equals(e.state()) && !"REJECTED".equals(e.state()));
    out.setNextAction("APPLIED".equals(e.state()) ? null : "QUERY_RESULT");
    out.setOccurredAt(time(e.recordedAt()));
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(out);
  }

  @Override
  public ResponseEntity<TaskExecution> getTaskExecution(String taskId, String executionId) {
    ExecutionRow e = service.getExecution(taskId, executionId);
    var out = new TaskExecution();
    out.setExecutionId(e.executionId());
    out.setTaskId(e.taskId());
    out.setState(TaskExecution.StateEnum.fromValue(e.state()));
    out.setInventoryCommitId(e.inventoryCommitId());
    out.setAppliedQty(e.appliedQty());
    List<TaskExecutionShortagesInner> shortages = new ArrayList<>();
    for (ExecLine l : mapper.readValue(e.lines(), new TypeReference<List<ExecLine>>() {})) {
      if (l.shortage() != null) {
        var s = new TaskExecutionShortagesInner();
        s.setOrderLineId(l.orderLineId());
        s.setQty(l.shortage().qty());
        s.setReason(TaskExecutionShortagesInner.ReasonEnum.fromValue(l.shortage().reason()));
        shortages.add(s);
      }
    }
    out.setShortages(shortages);
    out.setRecordedAt(time(e.recordedAt()));
    return ResponseEntity.ok(out);
  }

  // ---------- 변환 ----------

  private TaskSummary attention(TaskSummary s, TaskInfo t) {
    var a = service.attention(t);
    s.setLate(a.late());
    s.setIdle(a.idle());
    s.setLastActivityAt(time(a.lastActivityAt()));
    return s;
  }

  /** 작업 내놓기(0.4.8, Q-09). */
  @Override
  public ResponseEntity<TaskSummary> releaseTask(
      String taskId,
      String idempotencyKey,
      String xSourceSystem,
      com.pmart.mfc.fulfillment.api.generated.model.ReleaseTaskRequest body) {
    TaskInfo t = service.release(taskId, body.getWorkerId(), body.getReason());
    return ResponseEntity.ok(attention(summary(t), t));
  }

  private static TaskSummary summary(TaskInfo t) {
    var s = new TaskSummary();
    s.setTaskId(t.taskId());
    s.setTaskType(TaskType.fromValue(t.taskType()));
    s.setState(TaskState.fromValue(t.state()));
    s.setVersion(t.version());
    s.setMfcId(t.mfcId());
    s.setFulfillmentId(t.fulfillmentId());
    s.setShipmentId(t.shipmentId());
    s.setAssignment(assignment(t));
    s.setPromisedReadyAt(time(t.readyAt()));
    s.setCreatedAt(time(t.createdAt()));
    return s;
  }

  /** 목록 줄 수·수량·대표 상품(0.4.9). */
  private static void counts(TaskSummary s, List<LineView> lines) {
    s.setLineCount(lines.size());
    s.setPlannedQty(lines.stream().mapToInt(LineView::plannedQty).sum());
    s.setCompletedQty(lines.stream().mapToInt(LineView::completedQty).sum());
    s.setRemainingQty(lines.stream().mapToInt(LineView::remaining).sum());
    s.setHeadline(headline(lines));
  }

  private static com.pmart.mfc.fulfillment.api.generated.model.TaskSummaryHeadline headline(
      List<LineView> lines) {
    var h = new com.pmart.mfc.fulfillment.api.generated.model.TaskSummaryHeadline();
    h.setProductName(lines.isEmpty() ? null : lines.get(0).productName());
    h.setOtherLineCount(Math.max(0, lines.size() - 1));
    return h;
  }

  private static void fill(TaskDetail d, TaskInfo t, List<LineView> lines) {
    d.setTaskId(t.taskId());
    d.setTaskType(TaskType.fromValue(t.taskType()));
    d.setState(TaskState.fromValue(t.state()));
    d.setVersion(t.version());
    d.setMfcId(t.mfcId());
    d.setFulfillmentId(t.fulfillmentId());
    d.setShipmentId(t.shipmentId());
    d.setAssignment(assignment(t));
    d.setLineCount(lines.size());
    d.setPlannedQty(lines.stream().mapToInt(LineView::plannedQty).sum());
    d.setCompletedQty(lines.stream().mapToInt(LineView::completedQty).sum());
    d.setRemainingQty(lines.stream().mapToInt(LineView::remaining).sum());
    d.setPromisedReadyAt(time(t.readyAt()));
    d.setCreatedAt(time(t.createdAt()));
  }

  private static TaskAssignment assignment(TaskInfo t) {
    var a = new TaskAssignment();
    a.setWorkerId(t.workerId());
    a.setAssignmentVersion(t.assignmentVersion());
    a.setAsOf(asOf(true));
    return a;
  }

  /** 재고 확정 대기 실적의 줄별 집품량(remainingQty 에 아직 반영 안 됨). */
  private Map<String, Integer> pendingByLine(List<ExecutionRow> pending) {
    Map<String, Integer> out = new java.util.HashMap<>();
    for (ExecutionRow e : pending) {
      for (ExecLine l : mapper.readValue(e.lines(), new TypeReference<List<ExecLine>>() {})) {
        out.merge(l.orderLineId(), l.pickedQty(), Integer::sum);
      }
    }
    return out;
  }

  private static ListTasks200ResponseAsOf asOf(boolean fresh) {
    var a = new ListTasks200ResponseAsOf();
    a.setAsOf(time(Instant.now()));
    a.setFreshness(
        fresh
            ? ListTasks200ResponseAsOf.FreshnessEnum.CURRENT
            : ListTasks200ResponseAsOf.FreshnessEnum.STALE);
    return a;
  }

  static @Nullable OffsetDateTime time(@Nullable Instant at) {
    return at == null ? null : at.atOffset(ZoneOffset.UTC);
  }
}
