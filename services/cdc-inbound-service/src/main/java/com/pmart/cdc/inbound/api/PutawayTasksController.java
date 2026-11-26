package com.pmart.cdc.inbound.api;

import com.pmart.cdc.inbound.api.generated.PutawayTasksApi;
import com.pmart.cdc.inbound.api.generated.model.CancelPutawayTaskRequest;
import com.pmart.cdc.inbound.api.generated.model.ListPutawayTasks200Response;
import com.pmart.cdc.inbound.api.generated.model.PutawayTask;
import com.pmart.cdc.inbound.api.generated.model.PutawayTaskInput;
import com.pmart.cdc.inbound.api.generated.model.PutawayTaskKind;
import com.pmart.cdc.inbound.api.generated.model.PutawayTaskResultsInner;
import com.pmart.cdc.inbound.api.generated.model.PutawayTaskState;
import com.pmart.cdc.inbound.application.TaskService;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 PutawayTasksApi — 적치 지시·목록·조회·취소(0.2.0). 업무 규칙은 {@link TaskService}. */
@RestController
public class PutawayTasksController implements PutawayTasksApi {

  private final TaskService tasks;

  public PutawayTasksController(TaskService tasks) {
    this.tasks = tasks;
  }

  @Override
  public ResponseEntity<PutawayTask> createPutawayTask(
      String cdcId, String idempotencyKey, String xSourceSystem, PutawayTaskInput body) {
    String id =
        tasks.createPutaway(
            idempotencyKey,
            xSourceSystem,
            cdcId,
            new TaskService.NewTask(
                "PUTAWAY",
                body.getSku(),
                body.getLot(),
                body.getQualityState() == null ? "RELEASED" : body.getQualityState().getValue(),
                body.getFromLocationCode(),
                body.getToLocationCode(),
                body.getQty(),
                body.getReason()));
    return ResponseEntity.status(HttpStatus.CREATED).body(dto(tasks.get(cdcId, id)));
  }

  @Override
  public ResponseEntity<ListPutawayTasks200Response> listPutawayTasks(
      String cdcId,
      @Nullable PutawayTaskState state,
      @Nullable PutawayTaskKind kind,
      @Nullable Integer size,
      @Nullable String cursor) {
    int n = size == null ? 50 : size;
    List<TaskService.View> items =
        tasks.list(
            cdcId,
            state == null ? null : state.getValue(),
            kind == null ? null : kind.getValue(),
            cursor,
            n);
    var out = new ListPutawayTasks200Response();
    out.setItems(items.stream().map(PutawayTasksController::dto).toList());
    out.setNextCursor(items.size() < n ? null : items.get(items.size() - 1).taskId());
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<PutawayTask> getPutawayTask(String cdcId, String taskId) {
    return ResponseEntity.ok(dto(tasks.get(cdcId, taskId)));
  }

  @Override
  public ResponseEntity<PutawayTask> cancelPutawayTask(
      String cdcId,
      String taskId,
      String idempotencyKey,
      String xSourceSystem,
      CancelPutawayTaskRequest body) {
    return ResponseEntity.ok(
        dto(tasks.cancel(idempotencyKey, xSourceSystem, cdcId, taskId, body.getReason())));
  }

  static @Nullable OffsetDateTime time(@Nullable Instant i) {
    return i == null ? null : i.atOffset(ZoneOffset.UTC);
  }

  static PutawayTask dto(TaskService.View v) {
    var t = new PutawayTask();
    t.setTaskId(v.taskId());
    t.setCdcId(v.cdcId());
    t.setKind(PutawayTaskKind.fromValue(v.kind()));
    t.setState(PutawayTaskState.fromValue(v.state()));
    t.setSku(v.sku());
    t.setLot(v.lot());
    t.setQualityState(v.qualityState());
    t.setFromLocationCode(v.from());
    t.setToLocationCode(v.to());
    t.setQty(v.qty());
    t.setCompletedQty(v.completedQty());
    t.setReservationId(v.reservationId());
    t.setDeviceId(v.deviceId());
    t.setCreatedBy(v.createdBy());
    t.setReason(v.reason());
    t.setFailureReason(v.failureReason());
    t.setResults(
        v.results().stream()
            .map(
                r ->
                    new PutawayTaskResultsInner()
                        .eventId(r.eventId())
                        .status(r.status())
                        .completedQty(r.completedQty())
                        .state(PutawayTaskResultsInner.StateEnum.fromValue(r.state()))
                        .commitId(r.commitId())
                        .rejectReason(r.rejectReason())
                        .receivedAt(time(r.receivedAt())))
            .toList());
    t.setCreatedAt(time(v.createdAt()));
    t.setUpdatedAt(time(v.updatedAt()));
    return t;
  }
}
