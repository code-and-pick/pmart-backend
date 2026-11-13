package com.pmart.mfc.capacity.api;

import com.pmart.mfc.capacity.api.generated.WorkersApi;
import com.pmart.mfc.capacity.api.generated.model.AddWorkerShiftRequest;
import com.pmart.mfc.capacity.api.generated.model.CancelWorkerShiftRequest;
import com.pmart.mfc.capacity.api.generated.model.ChangeWorkerDutyRequest;
import com.pmart.mfc.capacity.api.generated.model.ChangeWorkerStateRequest;
import com.pmart.mfc.capacity.api.generated.model.ListWorkerShifts200Response;
import com.pmart.mfc.capacity.api.generated.model.ListWorkers200Response;
import com.pmart.mfc.capacity.api.generated.model.RegisterWorkerRequest;
import com.pmart.mfc.capacity.api.generated.model.TaskType;
import com.pmart.mfc.capacity.api.generated.model.TemperatureZone;
import com.pmart.mfc.capacity.api.generated.model.Worker;
import com.pmart.mfc.capacity.api.generated.model.WorkerChangeResult;
import com.pmart.mfc.capacity.api.generated.model.WorkerDetail;
import com.pmart.mfc.capacity.api.generated.model.WorkerDetailAllOfStateHistory;
import com.pmart.mfc.capacity.api.generated.model.WorkerDuty;
import com.pmart.mfc.capacity.api.generated.model.WorkerShift;
import com.pmart.mfc.capacity.api.generated.model.WorkerState;
import com.pmart.mfc.capacity.application.WorkerService;
import com.pmart.mfc.capacity.infrastructure.WorkerStore.DutyRow;
import com.pmart.mfc.capacity.infrastructure.WorkerStore.ShiftRow;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 WorkersApi — 작업자·업무·근무(0.4.0). 업무 규칙은 {@link WorkerService}. */
@RestController
public class WorkersController implements WorkersApi {

  private final WorkerService service;
  private com.pmart.mfc.capacity.application.AssignmentService assignments;

  @org.springframework.beans.factory.annotation.Autowired
  void setAssignments(com.pmart.mfc.capacity.application.AssignmentService assignments) {
    this.assignments = assignments;
  }

  /** 다음 작업 받기(0.6.0) — 시스템이 하나씩 배정. */
  @Override
  public ResponseEntity<com.pmart.mfc.capacity.api.generated.model.Assignment> pullNextTask(
      String mfcId,
      String workerId,
      String idempotencyKey,
      String xSourceSystem,
      com.pmart.mfc.capacity.api.generated.model.@Nullable PullNextTaskRequest body) {
    var r =
        assignments.pullNext(
            mfcId,
            workerId,
            body == null || body.getWorkArea() == null ? null : body.getWorkArea().getValue());
    var out = AssignmentsController.dto(r.assignment());
    out.setDetail(detail(assignments.detailOf(out.getTaskId(), out.getTaskType())));
    return ResponseEntity.status(r.created() ? HttpStatus.CREATED : HttpStatus.OK).body(out);
  }

  private static com.pmart.mfc.capacity.api.generated.model.NextTaskDetail detail(
      com.pmart.mfc.capacity.application.NextTaskDetails.Detail d) {
    var out = new com.pmart.mfc.capacity.api.generated.model.NextTaskDetail();
    out.setAvailable(d.available());
    out.setPromisedReadyAt(
        d.promisedReadyAt() == null ? null : java.time.OffsetDateTime.parse(d.promisedReadyAt()));
    out.setUrgent(d.urgent());
    out.setPackingLocations(d.packingLocations());
    java.util.List<com.pmart.mfc.capacity.api.generated.model.NextTaskDetailLinesInner> lines =
        new java.util.ArrayList<>();
    for (var l : d.lines()) {
      var x = new com.pmart.mfc.capacity.api.generated.model.NextTaskDetailLinesInner();
      x.setSku(l.sku());
      x.setProductName(l.productName());
      x.setLotId(l.lotId());
      x.setExpiresOn(l.expiresOn() == null ? null : java.time.LocalDate.parse(l.expiresOn()));
      x.setQty(l.qty());
      x.setFromLocation(l.fromLocation());
      x.setToLocation(l.toLocation());
      x.setNotice(l.notice());
      lines.add(x);
    }
    out.setLines(lines);
    return out;
  }

  public WorkersController(WorkerService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<Worker> registerWorker(
      String mfcId, String idempotencyKey, String xSourceSystem, RegisterWorkerRequest body) {
    var v =
        service.register(
            idempotencyKey,
            xSourceSystem,
            mfcId,
            body.getWorkerId(),
            body.getDisplayName(),
            types(body.getDuty().getTaskTypes()),
            zones(body.getDuty().getTemperatureZones()),
            body.getEmploymentType() == null ? null : body.getEmploymentType().getValue(),
            body.getQualification() == null
                ? null
                : body.getQualification().getTaskTypes().stream()
                    .map(TaskType::getValue)
                    .collect(java.util.stream.Collectors.toSet()),
            body.getQualification() == null || body.getQualification().getExpiresAt() == null
                ? null
                : body.getQualification().getExpiresAt().toInstant());
    return ResponseEntity.status(HttpStatus.CREATED).body(worker(v));
  }

  @Override
  public ResponseEntity<ListWorkers200Response> listWorkers(
      String mfcId, @Nullable WorkerState state) {
    var out = new ListWorkers200Response();
    out.setItems(
        service.list(mfcId, state == null ? null : state.getValue()).stream()
            .map(WorkersController::worker)
            .toList());
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<WorkerDetail> getWorker(String mfcId, String workerId) {
    var d = service.detail(mfcId, workerId);
    var w = worker(d.view());
    var out = new WorkerDetail();
    out.setMfcId(w.getMfcId());
    out.setWorkerId(w.getWorkerId());
    out.setDisplayName(w.getDisplayName());
    out.setState(w.getState());
    out.setDuty(w.getDuty());
    out.setEmploymentType(w.getEmploymentType());
    out.setQualification(w.getQualification());
    out.setEffectiveTaskTypes(w.getEffectiveTaskTypes());
    out.setWorkAreas(w.getWorkAreas());
    out.setTodayEnrollment(w.getTodayEnrollment());
    out.setOnBreak(w.getOnBreak());
    out.setSpeedFactor(w.getSpeedFactor());
    out.setRegisteredBy(w.getRegisteredBy());
    out.setRegisteredAt(w.getRegisteredAt());
    out.setDutyHistory(d.duties().stream().map(WorkersController::duty).toList());
    out.setStateHistory(
        d.states().stream()
            .map(
                s -> {
                  var h = new WorkerDetailAllOfStateHistory();
                  h.setFromState(s.fromState());
                  h.setToState(s.toState());
                  h.setReason(s.reason());
                  h.setChangedBy(s.changedBy());
                  h.setChangedAt(odt(s.changedAt()));
                  return h;
                })
            .toList());
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<WorkerChangeResult> changeWorkerDuty(
      String mfcId,
      String workerId,
      String idempotencyKey,
      String xSourceSystem,
      ChangeWorkerDutyRequest body,
      @Nullable Integer xExpectedVersion) {
    return ResponseEntity.ok(
        result(
            service.changeDuty(
                idempotencyKey,
                xSourceSystem,
                mfcId,
                workerId,
                xExpectedVersion,
                types(body.getTaskTypes()),
                zones(body.getTemperatureZones()),
                body.getReason())));
  }

  @Override
  public ResponseEntity<WorkerChangeResult> changeWorkerState(
      String mfcId,
      String workerId,
      String idempotencyKey,
      String xSourceSystem,
      ChangeWorkerStateRequest body) {
    return ResponseEntity.ok(
        result(
            service.changeState(
                idempotencyKey,
                xSourceSystem,
                mfcId,
                workerId,
                body.getState().getValue(),
                body.getReason())));
  }

  @Override
  public ResponseEntity<WorkerShift> addWorkerShift(
      String mfcId,
      String workerId,
      String idempotencyKey,
      String xSourceSystem,
      AddWorkerShiftRequest body) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            shift(
                service.addShift(
                    idempotencyKey,
                    xSourceSystem,
                    mfcId,
                    workerId,
                    body.getStartAt().toInstant(),
                    body.getEndAt().toInstant(),
                    body.getNote())));
  }

  @Override
  public ResponseEntity<ListWorkerShifts200Response> listWorkerShifts(
      String mfcId, String workerId, @Nullable OffsetDateTime from, @Nullable OffsetDateTime to) {
    var out = new ListWorkerShifts200Response();
    out.setItems(
        service
            .shifts(
                mfcId,
                workerId,
                from == null ? null : from.toInstant(),
                to == null ? null : to.toInstant())
            .stream()
            .map(WorkersController::shift)
            .toList());
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<WorkerShift> cancelWorkerShift(
      String mfcId,
      String workerId,
      String shiftId,
      String idempotencyKey,
      String xSourceSystem,
      CancelWorkerShiftRequest body) {
    return ResponseEntity.ok(
        shift(service.cancelShift(mfcId, workerId, shiftId, body.getReason())));
  }

  private static WorkerChangeResult result(WorkerService.ChangeResult r) {
    var out = new WorkerChangeResult();
    out.setWorker(worker(r.view()));
    out.setChanged(r.changed());
    out.setAffectedAssignments(r.affected().stream().map(AssignmentsController::dto).toList());
    return out;
  }

  @Override
  public ResponseEntity<Worker> changeWorkerSpeed(
      String mfcId,
      String workerId,
      String idempotencyKey,
      String xSourceSystem,
      com.pmart.mfc.capacity.api.generated.model.ChangeWorkerSpeedRequest body) {
    return ResponseEntity.ok(
        worker(
            service.changeSpeed(
                idempotencyKey,
                xSourceSystem,
                mfcId,
                workerId,
                body.getSpeedFactor().doubleValue(),
                body.getReason())));
  }

  private static Worker worker(WorkerService.View v) {
    var w = new Worker();
    w.setSpeedFactor(java.math.BigDecimal.valueOf(v.worker().speedFactor()));
    w.setMfcId(v.worker().mfcId());
    w.setWorkerId(v.worker().workerId());
    w.setDisplayName(v.worker().displayName());
    w.setState(WorkerState.fromValue(v.worker().state()));
    w.setDuty(duty(v.duty()));
    w.setEmploymentType(
        com.pmart.mfc.capacity.api.generated.model.EmploymentType.fromValue(v.employmentType()));
    if (v.qualification() != null) {
      var q = new com.pmart.mfc.capacity.api.generated.model.WorkerQualification();
      q.setQualificationVersion(v.qualification().version());
      q.setTaskTypes(v.qualification().taskTypes().stream().map(TaskType::fromValue).toList());
      q.setExpiresAt(
          v.qualification().expiresAt() == null ? null : odt(v.qualification().expiresAt()));
      q.setExpired(!v.qualification().valid(Instant.now()));
      q.setChangedBy(v.qualification().changedBy());
      q.setChangedAt(odt(v.qualification().changedAt()));
      w.setQualification(q);
    }
    w.setEffectiveTaskTypes(v.effective().stream().map(TaskType::fromValue).toList());
    w.setWorkAreas(
        v.workAreas().stream()
            .map(com.pmart.mfc.capacity.api.generated.model.WorkArea::fromValue)
            .toList());
    w.setTodayEnrollment(v.today() == null ? null : day(v.today()));
    w.setOnBreak(v.onBreak());
    w.setRegisteredBy(v.worker().registeredBy());
    w.setRegisteredAt(odt(v.worker().registeredAt()));
    return w;
  }

  private static com.pmart.mfc.capacity.api.generated.model.DayEnrollment day(
      com.pmart.mfc.capacity.infrastructure.QualificationStore.Day d) {
    var out = new com.pmart.mfc.capacity.api.generated.model.DayEnrollment();
    out.setWorkerId(d.workerId());
    out.setBusinessDate(d.businessDate());
    out.setDutyTaskTypes(d.dutyTaskTypes().stream().map(TaskType::fromValue).toList());
    out.setEnrolledBy(d.enrolledBy());
    out.setEnrolledAt(odt(d.enrolledAt()));
    return out;
  }

  @Override
  public ResponseEntity<com.pmart.mfc.capacity.api.generated.model.WorkerBreak> startWorkerBreak(
      String mfcId, String workerId, String idempotencyKey, String xSourceSystem) {
    return ResponseEntity.ok(brk(service.startBreak(mfcId, workerId)));
  }

  @Override
  public ResponseEntity<com.pmart.mfc.capacity.api.generated.model.WorkerBreak> endWorkerBreak(
      String mfcId, String workerId, String idempotencyKey, String xSourceSystem) {
    return ResponseEntity.ok(brk(service.endBreak(mfcId, workerId)));
  }

  private static com.pmart.mfc.capacity.api.generated.model.WorkerBreak brk(
      com.pmart.mfc.capacity.infrastructure.BreakStore.Break b) {
    var out = new com.pmart.mfc.capacity.api.generated.model.WorkerBreak();
    out.setBreakId(b.breakId());
    out.setWorkerId(b.workerId());
    out.setStartedAt(odt(b.startedAt()));
    out.setStartedBy(b.startedBy());
    out.setEndedAt(b.endedAt() == null ? null : odt(b.endedAt()));
    return out;
  }

  @Override
  public ResponseEntity<Worker> changeWorkerQualification(
      String mfcId,
      String workerId,
      String idempotencyKey,
      String xSourceSystem,
      com.pmart.mfc.capacity.api.generated.model.ChangeWorkerQualificationRequest body,
      @org.jspecify.annotations.Nullable Integer xExpectedVersion) {
    var r =
        service.changeQualification(
            idempotencyKey,
            xSourceSystem,
            mfcId,
            workerId,
            xExpectedVersion,
            body.getTaskTypes().stream()
                .map(TaskType::getValue)
                .collect(java.util.stream.Collectors.toSet()),
            body.getExpiresAt() == null ? null : body.getExpiresAt().toInstant(),
            body.getReason());
    return ResponseEntity.ok(worker(r.view()));
  }

  @Override
  public ResponseEntity<com.pmart.mfc.capacity.api.generated.model.DayEnrollment> enrollWorkerDay(
      String mfcId,
      String workerId,
      String idempotencyKey,
      String xSourceSystem,
      com.pmart.mfc.capacity.api.generated.model.@org.jspecify.annotations.Nullable
          EnrollWorkerDayRequest
          body) {
    return ResponseEntity.ok(
        day(
            service.enrollDay(
                idempotencyKey,
                xSourceSystem,
                mfcId,
                workerId,
                body == null ? null : body.getBusinessDate())));
  }

  private static WorkerDuty duty(DutyRow d) {
    var out = new WorkerDuty();
    out.setDutyVersion(d.dutyVersion());
    out.setTaskTypes(d.taskTypes().stream().map(TaskType::fromValue).toList());
    out.setTemperatureZones(d.zones().stream().map(TemperatureZone::fromValue).toList());
    out.setReason(d.reason());
    out.setChangedBy(d.changedBy());
    out.setChangedAt(odt(d.changedAt()));
    return out;
  }

  private static WorkerShift shift(ShiftRow s) {
    var out = new WorkerShift();
    out.setShiftId(s.shiftId());
    out.setMfcId(s.mfcId());
    out.setWorkerId(s.workerId());
    out.setStartAt(odt(s.startAt()));
    out.setEndAt(odt(s.endAt()));
    out.setState(WorkerShift.StateEnum.fromValue(s.state()));
    out.setNote(s.note());
    out.setCreatedBy(s.createdBy());
    out.setCancelReason(s.cancelReason());
    return out;
  }

  private static Set<String> types(java.util.Collection<TaskType> in) {
    return in.stream().map(TaskType::getValue).collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private static Set<String> zones(
      java.util.@org.jspecify.annotations.Nullable Collection<TemperatureZone> in) {
    if (in == null) {
      return Set.of();
    }
    return in.stream()
        .map(TemperatureZone::getValue)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private static OffsetDateTime odt(Instant i) {
    return i.atOffset(ZoneOffset.UTC);
  }
}
