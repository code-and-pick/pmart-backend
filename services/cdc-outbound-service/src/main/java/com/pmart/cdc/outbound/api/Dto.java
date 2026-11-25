package com.pmart.cdc.outbound.api;

import com.pmart.cdc.outbound.api.generated.model.GetOutboundReadiness200ResponseAsOf;
import com.pmart.cdc.outbound.api.generated.model.IntakeTransfer202Response;
import com.pmart.cdc.outbound.api.generated.model.PickMode;
import com.pmart.cdc.outbound.api.generated.model.Task;
import com.pmart.cdc.outbound.api.generated.model.TaskLinesInner;
import com.pmart.cdc.outbound.api.generated.model.TaskLinesInnerSharesInner;
import com.pmart.cdc.outbound.api.generated.model.TaskState;
import com.pmart.cdc.outbound.application.Support.CommandResult;
import com.pmart.cdc.outbound.application.TaskService.TaskView;
import com.pmart.cdc.outbound.infrastructure.OutboundStore.ShareRow;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** 응답 변환 공통. */
final class Dto {

  private Dto() {}

  static @Nullable OffsetDateTime time(@Nullable Instant i) {
    return i == null ? null : i.atOffset(ZoneOffset.UTC);
  }

  /** 지금 장부를 읽은 조회(CURRENT). */
  static GetOutboundReadiness200ResponseAsOf asOf(Instant at) {
    var a = new GetOutboundReadiness200ResponseAsOf();
    a.setAsOf(time(at));
    a.setFreshness(GetOutboundReadiness200ResponseAsOf.FreshnessEnum.CURRENT);
    return a;
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
    out.setTrfIds(v.trfIds());
    out.setPickMode(PickMode.fromValue(t.pickMode() == null ? "DISCRETE" : t.pickMode()));
    out.setPickMethod(
        t.pickMethod() == null ? null : Task.PickMethodEnum.fromValue(t.pickMethod()));
    out.setRegionCode(t.regionCode());
    out.setAisle(t.aisle());
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
                  List<ShareRow> mine =
                      v.shares() == null
                          ? List.of()
                          : v.shares().stream().filter(s -> s.lineNo() == l.lineNo()).toList();
                  x.setShares(
                      mine.stream()
                          .map(
                              s -> {
                                var y = new TaskLinesInnerSharesInner();
                                y.setTrfId(s.trfId());
                                y.setMfcId(s.mfcId());
                                y.setDueAt(time(s.dueAt()));
                                y.setPlannedQty(s.plannedQty());
                                y.setPickedQty(s.pickedQty());
                                y.setShortQty(s.shortQty());
                                return y;
                              })
                          .toList());
                  return x;
                })
            .toList());
    return out;
  }
}
