package com.pmart.cdc.outbound.api;

import com.pmart.cdc.outbound.api.generated.model.IntakeTransfer202Response;
import com.pmart.cdc.outbound.api.generated.model.Task;
import com.pmart.cdc.outbound.api.generated.model.TaskLinesInner;
import com.pmart.cdc.outbound.api.generated.model.TaskState;
import com.pmart.cdc.outbound.application.Support.CommandResult;
import com.pmart.cdc.outbound.application.TaskService.TaskView;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;

/** 응답 변환 공통. */
final class Dto {

  private Dto() {}

  static @Nullable OffsetDateTime time(@Nullable Instant i) {
    return i == null ? null : i.atOffset(ZoneOffset.UTC);
  }

  static IntakeTransfer202Response command(CommandResult r) {
    var out = new IntakeTransfer202Response();
    out.setStatus(IntakeTransfer202Response.StatusEnum.fromValue(r.status()));
    out.setRequestId(r.requestId());
    out.setResultId(r.resultId());
    out.setVersion(r.version());
    out.setOccurredAt(time(r.occurredAt()));
    if ("ACCEPTED".equals(r.status())) {
      out.setNextAction("QUERY_RESULT");
    }
    return out;
  }

  static Task task(TaskView v) {
    var t = v.task();
    var out = new Task();
    out.setTaskId(t.taskId());
    out.setWaveId(t.waveId());
    out.setTrfId(t.trfId());
    out.setKind(Task.KindEnum.fromValue(t.kind()));
    out.setZone(t.zone());
    out.setFloor(t.floor());
    out.setDueAt(time(t.dueAt()));
    out.setState(TaskState.fromValue(t.state()));
    out.setVersion(t.version());
    out.setAssigneeId(t.assigneeId());
    out.setCreatedAt(time(t.createdAt()));
    out.setCompletedAt(time(t.completedAt()));
    out.setLines(
        v.lines().stream()
            .map(
                l -> {
                  var x = new TaskLinesInner();
                  x.setSku(l.sku());
                  x.setLot(l.lot());
                  x.setLocationCode(l.locationCode());
                  x.setPlannedQty(l.plannedQty());
                  x.setPickedQty(l.pickedQty());
                  x.setShortQty(l.shortQty());
                  x.setShortReason(l.shortReason());
                  return x;
                })
            .toList());
    return out;
  }
}
