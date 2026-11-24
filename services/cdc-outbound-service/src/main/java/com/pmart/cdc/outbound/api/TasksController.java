package com.pmart.cdc.outbound.api;

import com.pmart.cdc.outbound.api.generated.TasksApi;
import com.pmart.cdc.outbound.api.generated.model.IntakeTransfer202Response;
import com.pmart.cdc.outbound.api.generated.model.ListTasks200Response;
import com.pmart.cdc.outbound.api.generated.model.ListTasks200ResponsePage;
import com.pmart.cdc.outbound.api.generated.model.PullNextTaskRequest;
import com.pmart.cdc.outbound.api.generated.model.ReportPickResultRequest;
import com.pmart.cdc.outbound.api.generated.model.Task;
import com.pmart.cdc.outbound.api.generated.model.TaskState;
import com.pmart.cdc.outbound.application.TaskService;
import com.pmart.cdc.outbound.application.TaskService.PickLine;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 TasksApi — 작업 목록·조회, PDA 다음 작업 받기·착수·피킹 실적(0.3.0, 경로 `/cdcs/{cdcId}/…`). */
@RestController
public class TasksController implements TasksApi {

  private final TaskService tasks;

  public TasksController(TaskService tasks) {
    this.tasks = tasks;
  }

  @Override
  public ResponseEntity<ListTasks200Response> listTasks(
      String cdcId,
      String waveId,
      @Nullable TaskState state,
      @Nullable String assigneeId,
      Integer size,
      @Nullable String cursor) {
    var page =
        tasks.list(
            cdcId,
            waveId,
            state == null ? null : state.getValue(),
            assigneeId,
            cursor,
            size == null ? 50 : size);
    var out = new ListTasks200Response();
    out.setItems(page.items().stream().map(Dto::task).toList());
    var p = new ListTasks200ResponsePage();
    p.setNextCursor(page.nextCursor());
    p.setSize(page.items().size());
    out.setPage(p);
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<Task> getTask(String cdcId, String taskId) {
    return ResponseEntity.ok(Dto.task(tasks.get(cdcId, taskId)));
  }

  @Override
  public ResponseEntity<Task> pullNextTask(
      String cdcId,
      String workerId,
      String idempotencyKey,
      String xSourceSystem,
      @Nullable PullNextTaskRequest body) {
    var r =
        tasks
            .next(
                idempotencyKey,
                xSourceSystem,
                cdcId,
                workerId,
                body == null || body.getKind() == null ? null : body.getKind().getValue(),
                body == null ? null : body.getZone(),
                body == null ? null : body.getFloor())
            .value();
    return ResponseEntity.status(r.created() ? HttpStatus.CREATED : HttpStatus.OK)
        .body(Dto.task(r.task()));
  }

  @Override
  public ResponseEntity<Task> startTask(
      String cdcId,
      String taskId,
      String idempotencyKey,
      String xSourceSystem,
      @Nullable Integer xExpectedVersion) {
    return ResponseEntity.ok(
        Dto.task(
            tasks.start(idempotencyKey, xSourceSystem, cdcId, taskId, xExpectedVersion).value()));
  }

  @Override
  public ResponseEntity<IntakeTransfer202Response> reportPickResult(
      String cdcId,
      String taskId,
      String idempotencyKey,
      String xSourceSystem,
      ReportPickResultRequest body,
      @Nullable Integer xExpectedVersion) {
    var lines =
        body.getLines().stream()
            .map(
                l ->
                    new PickLine(
                        l.getSku(),
                        l.getLot(),
                        l.getLocationCode(),
                        l.getPickedQty(),
                        l.getShortQty() == null ? 0 : l.getShortQty(),
                        l.getShortReason() == null ? null : l.getShortReason().getValue()))
            .toList();
    var r =
        tasks
            .pick(
                idempotencyKey,
                xSourceSystem,
                cdcId,
                taskId,
                body.getWorkerId(),
                lines,
                xExpectedVersion)
            .value();
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(Dto.command(r));
  }
}
