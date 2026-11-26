package com.pmart.cdc.outbound.api;

import com.pmart.cdc.outbound.api.generated.StaffViewsApi;
import com.pmart.cdc.outbound.api.generated.model.CountByKey;
import com.pmart.cdc.outbound.api.generated.model.ListCenterTransfers200Response;
import com.pmart.cdc.outbound.api.generated.model.OutboundSummary;
import com.pmart.cdc.outbound.api.generated.model.TransferDetail;
import com.pmart.cdc.outbound.api.generated.model.TransferDetailAllOfLines;
import com.pmart.cdc.outbound.api.generated.model.TransferSummary;
import com.pmart.cdc.outbound.application.StaffViews;
import com.pmart.cdc.outbound.infrastructure.StaffViewStore.TransferRow;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 StaffViewsApi(0.11.0) — DC 백오피스 직원 조회. */
@RestController
public class StaffViewsController implements StaffViewsApi {

  private final StaffViews views;
  private final com.pmart.cdc.outbound.application.EventLog eventLog;

  public StaffViewsController(
      StaffViews views, com.pmart.cdc.outbound.application.EventLog eventLog) {
    this.views = views;
    this.eventLog = eventLog;
  }

  /** 사건 기록(0.25.0 — DC 백오피스 M-5). */
  @Override
  public ResponseEntity<com.pmart.cdc.outbound.api.generated.model.ListCenterEvents200Response>
      listCenterEvents(
          String cdcId,
          java.time.@Nullable OffsetDateTime since,
          java.time.@Nullable OffsetDateTime until,
          @Nullable List<com.pmart.cdc.outbound.api.generated.model.CenterEventType> types,
          @Nullable Integer limit) {
    var p =
        eventLog.events(
            cdcId,
            since == null ? null : since.toInstant(),
            until == null ? null : until.toInstant(),
            types == null
                ? null
                : types.stream()
                    .map(com.pmart.cdc.outbound.api.generated.model.CenterEventType::getValue)
                    .toList(),
            limit);
    var out = new com.pmart.cdc.outbound.api.generated.model.ListCenterEvents200Response();
    out.setCdcId(cdcId);
    out.setSince(p.since().atOffset(java.time.ZoneOffset.UTC));
    out.setUntil(p.until().atOffset(java.time.ZoneOffset.UTC));
    out.setTruncated(p.truncated());
    out.setItems(
        p.items().stream()
            .map(
                r ->
                    new com.pmart.cdc.outbound.api.generated.model.CenterEvent()
                        .at(r.at().atOffset(java.time.ZoneOffset.UTC))
                        .type(
                            com.pmart.cdc.outbound.api.generated.model.CenterEventType.fromValue(
                                r.type()))
                        .refType(r.refType())
                        .refId(r.refId())
                        .refNumber(r.refNumber())
                        .trfId(r.trfId())
                        .waveId(r.waveId())
                        .taskId(r.taskId())
                        .sku(r.sku())
                        .qty(r.qty())
                        .state(r.state())
                        .action(r.action())
                        .actorId(r.actorId())
                        .reason(r.reason()))
            .toList());
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<ListCenterTransfers200Response> listCenterTransfers(
      String cdcId,
      @Nullable String state,
      @Nullable String mfcId,
      Integer page,
      Integer size,
      @Nullable String number) {
    var p =
        views.transfers(
            cdcId, state, mfcId, number, page == null ? 0 : page, size == null ? 50 : size);
    var out = new ListCenterTransfers200Response();
    out.setItems(new ArrayList<>(p.items().stream().map(StaffViewsController::summary).toList()));
    out.setPage(p.page());
    out.setSize(p.size());
    out.setHasNext(p.hasNext());
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<com.pmart.cdc.outbound.api.generated.model.ListCenterWaves200Response>
      listCenterWaves(
          String cdcId,
          com.pmart.cdc.outbound.api.generated.model.@Nullable WaveState state,
          Integer page,
          Integer size,
          @Nullable String number) {
    var p =
        views.waves(
            cdcId,
            state == null ? null : state.getValue(),
            number,
            page == null ? 0 : page,
            size == null ? 50 : size);
    var out = new com.pmart.cdc.outbound.api.generated.model.ListCenterWaves200Response();
    out.setItems(new ArrayList<>(p.items().stream().map(WavesController::dto).toList()));
    out.setPage(p.page());
    out.setSize(p.size());
    out.setHasNext(p.hasNext());
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<com.pmart.cdc.outbound.api.generated.model.ListCenterDepartures200Response>
      listCenterDepartures(
          String cdcId,
          @Nullable String result,
          Integer page,
          Integer size,
          @Nullable String number) {
    var p =
        views.departures(cdcId, result, number, page == null ? 0 : page, size == null ? 50 : size);
    var out = new com.pmart.cdc.outbound.api.generated.model.ListCenterDepartures200Response();
    out.setItems(new ArrayList<>(p.items().stream().map(InternalController::dto).toList()));
    out.setPage(p.page());
    out.setSize(p.size());
    out.setHasNext(p.hasNext());
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<com.pmart.cdc.outbound.api.generated.model.OutboundReadiness>
      getCenterTransferReadiness(String cdcId, String trfId) {
    return ResponseEntity.ok(InternalController.readinessDto(views.readiness(cdcId, trfId)));
  }

  @Override
  public ResponseEntity<com.pmart.cdc.outbound.api.generated.model.ListCenterShortages200Response>
      listCenterShortages(String cdcId, @Nullable String state, Integer page, Integer size) {
    var p = views.shortages(cdcId, state, page == null ? 0 : page, size == null ? 50 : size);
    var out = new com.pmart.cdc.outbound.api.generated.model.ListCenterShortages200Response();
    out.setItems(
        new ArrayList<>(
            p.items().stream()
                .map(
                    f ->
                        new com.pmart.cdc.outbound.api.generated.model
                                .ListCenterShortages200ResponseItemsInner()
                            .followupId(f.followupId())
                            .trfId(f.trfId())
                            .mfcId(f.mfcId())
                            .waveId(f.waveId())
                            .taskId(f.taskId())
                            .sku(f.sku())
                            .lot(f.lot())
                            .locationCode(f.locationCode())
                            .shortQty(f.shortQty())
                            .state(f.state())
                            .reallocatedQty(f.reallocatedQty())
                            .unresolvedQty(f.unresolvedQty())
                            .newTaskIds(
                                f.newTaskIds() == null || f.newTaskIds().isBlank()
                                    ? new ArrayList<>()
                                    : new ArrayList<>(List.of(f.newTaskIds().split(","))))
                            .reason(f.reason())
                            .createdAt(time(f.createdAt()))
                            .updatedAt(time(f.updatedAt())))
                .toList()));
    out.setPage(p.page());
    out.setSize(p.size());
    out.setHasNext(p.hasNext());
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<com.pmart.cdc.outbound.api.generated.model.ListCenterLpns200Response>
      listCenterLpns(
          String cdcId,
          @Nullable String trfId,
          @Nullable String unitType,
          Integer page,
          Integer size) {
    var p = views.lpns(cdcId, trfId, unitType, page == null ? 0 : page, size == null ? 50 : size);
    var out = new com.pmart.cdc.outbound.api.generated.model.ListCenterLpns200Response();
    out.setItems(
        new ArrayList<>(
            p.items().stream()
                .map(
                    l ->
                        new com.pmart.cdc.outbound.api.generated.model
                                .ListCenterLpns200ResponseItemsInner()
                            .lpn(l.lpn())
                            .trfId(l.trfId())
                            .mfcId(l.mfcId())
                            .waveId(l.waveId())
                            .unitType(l.unitType())
                            .parentLpn(l.parentLpn())
                            .sealState(l.sealState())
                            .sealNo(l.sealNo())
                            .temperatureZone(l.temperatureZone())
                            .weightG(l.weightG())
                            .sortedBy(l.sortedBy())
                            .sortedAt(time(l.sortedAt()))
                            .inspection(l.inspection())
                            .failReason(l.failReason())
                            .inspectedAt(l.inspectedAt() == null ? null : time(l.inspectedAt()))
                            .loaded(l.loaded())
                            .tripId(l.tripId())
                            .vehicleId(l.vehicleId())
                            .dockCode(l.dockCode())
                            .loadedAt(l.loadedAt() == null ? null : time(l.loadedAt()))
                            .departureId(l.departureId())
                            .contents(
                                new ArrayList<>(
                                    p.contents().getOrDefault(l.lpn(), List.of()).stream()
                                        .map(
                                            c ->
                                                new com.pmart.cdc.outbound.api.generated.model
                                                        .ListCenterLpns200ResponseItemsInnerContentsInner()
                                                    .sku(c.sku())
                                                    .lot(c.lot())
                                                    .qty(c.qty()))
                                        .toList())))
                .toList()));
    out.setPage(p.page());
    out.setSize(p.size());
    out.setHasNext(p.hasNext());
    return ResponseEntity.ok(out);
  }

  @org.springframework.beans.factory.annotation.Autowired
  private com.pmart.cdc.outbound.application.TaskAdjustments adjustments;

  @org.springframework.beans.factory.annotation.Autowired
  private com.pmart.cdc.outbound.application.TaskService taskService;

  private ResponseEntity<com.pmart.cdc.outbound.api.generated.model.Task> taskNow(
      String cdcId, String taskId) {
    return ResponseEntity.ok(Dto.task(taskService.get(cdcId, taskId)));
  }

  @Override
  public ResponseEntity<com.pmart.cdc.outbound.api.generated.model.Task> reassignTask(
      String cdcId,
      String taskId,
      String idempotencyKey,
      String xSourceSystem,
      com.pmart.cdc.outbound.api.generated.model.ReassignTaskRequest body,
      @Nullable Integer xExpectedVersion) {
    adjustments.reassign(
        idempotencyKey,
        xSourceSystem,
        cdcId,
        taskId,
        xExpectedVersion,
        body.getWorkerId(),
        body.getReason());
    return taskNow(cdcId, taskId);
  }

  @Override
  public ResponseEntity<com.pmart.cdc.outbound.api.generated.model.Task> holdTask(
      String cdcId,
      String taskId,
      String idempotencyKey,
      String xSourceSystem,
      @Nullable Integer xExpectedVersion,
      com.pmart.cdc.outbound.api.generated.model.@Nullable TaskReasonRequest body) {
    adjustments.hold(
        idempotencyKey,
        xSourceSystem,
        cdcId,
        taskId,
        xExpectedVersion,
        body == null ? null : body.getReason());
    return taskNow(cdcId, taskId);
  }

  @Override
  public ResponseEntity<com.pmart.cdc.outbound.api.generated.model.Task> resumeTask(
      String cdcId,
      String taskId,
      String idempotencyKey,
      String xSourceSystem,
      @Nullable Integer xExpectedVersion,
      com.pmart.cdc.outbound.api.generated.model.@Nullable TaskReasonRequest body) {
    adjustments.resume(
        idempotencyKey,
        xSourceSystem,
        cdcId,
        taskId,
        xExpectedVersion,
        body == null ? null : body.getReason());
    return taskNow(cdcId, taskId);
  }

  @Override
  public ResponseEntity<com.pmart.cdc.outbound.api.generated.model.Task> changeTaskPriority(
      String cdcId,
      String taskId,
      String idempotencyKey,
      String xSourceSystem,
      com.pmart.cdc.outbound.api.generated.model.ChangeTaskPriorityRequest body,
      @Nullable Integer xExpectedVersion) {
    adjustments.priority(
        idempotencyKey,
        xSourceSystem,
        cdcId,
        taskId,
        xExpectedVersion,
        body.getPriority(),
        body.getReason());
    return taskNow(cdcId, taskId);
  }

  @Override
  public ResponseEntity<com.pmart.cdc.outbound.api.generated.model.ListTaskAdjustments200Response>
      listTaskAdjustments(String cdcId, String taskId) {
    var out = new com.pmart.cdc.outbound.api.generated.model.ListTaskAdjustments200Response();
    out.setItems(
        new ArrayList<>(
            adjustments.history(cdcId, taskId).stream()
                .map(
                    a ->
                        new com.pmart.cdc.outbound.api.generated.model.TaskAdjustment()
                            .adjustmentId(a.adjustmentId())
                            .action(a.action())
                            .fromState(a.fromState())
                            .toState(a.toState())
                            .fromAssignee(a.fromAssignee())
                            .toAssignee(a.toAssignee())
                            .fromPriority(a.fromPriority())
                            .toPriority(a.toPriority())
                            .reason(a.reason())
                            .actorId(a.actorId())
                            .adjustedAt(time(a.at())))
                .toList()));
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<TransferDetail> getCenterTransfer(String cdcId, String trfId) {
    var d = views.transfer(cdcId, trfId);
    TransferRow t = d.transfer();
    var out = new TransferDetail();
    out.setTrfId(t.trfId());
    out.setTrfVersion(t.trfVersion());
    out.setMfcId(t.mfcId());
    out.setState(t.state());
    out.setDeadlineMeaning(t.deadlineMeaning());
    out.setDueAt(time(t.dueAt()));
    out.setWaveId(t.waveId());
    out.setAllocationError(t.allocationError());
    out.setLineCount(t.lineCount());
    out.setApprovedQty((int) t.approvedQty());
    out.setAllocatedQty((int) t.allocatedQty());
    out.setPickedQty((int) t.pickedQty());
    out.setShortQty((int) t.shortQty());
    out.setCreatedAt(time(t.createdAt()));
    out.setUpdatedAt(time(t.updatedAt()));
    out.setLines(
        new ArrayList<>(
            d.lines().stream()
                .map(
                    l ->
                        new TransferDetailAllOfLines()
                            .sku(l.sku())
                            .approvedQty(l.approvedQty())
                            .allocatedQty(l.allocatedQty())
                            .pickedQty(l.pickedQty())
                            .shortQty(l.shortQty()))
                .toList()));
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<OutboundSummary> getOutboundSummary(
      String cdcId, @Nullable LocalDate date) {
    var s = views.summary(cdcId, date);
    var out = new OutboundSummary();
    out.setCdcId(s.cdcId());
    out.setDate(s.date());
    out.setTransferCount((int) s.totals().count());
    out.setTransfersByState(counts(s.transfersByState()));
    out.setApprovedQty((int) s.totals().approved());
    out.setAllocatedQty((int) s.totals().allocated());
    out.setPickedQty((int) s.totals().picked());
    out.setShortQty((int) s.totals().shortQty());
    out.setProgress(
        s.progress() == null
            ? null
            : java.math.BigDecimal.valueOf(s.progress())
                .setScale(4, java.math.RoundingMode.HALF_UP));
    out.setWavesByState(counts(s.wavesByState()));
    out.setTasksByKindState(counts(s.tasksByKindState()));
    out.setAsOf(time(s.asOf()));
    out.setByMfc(
        new ArrayList<>(
            s.byMfc().stream()
                .map(
                    m ->
                        new com.pmart.cdc.outbound.api.generated.model.OutboundSummaryByMfcInner()
                            .mfcId(m.mfcId())
                            .transferCount((int) m.transfers())
                            .approvedQty((int) m.approved())
                            .allocatedQty((int) m.allocated())
                            .pickedQty((int) m.picked())
                            .shortQty((int) m.shortQty()))
                .toList()));
    return ResponseEntity.ok(out);
  }

  @org.springframework.beans.factory.annotation.Autowired
  private com.pmart.cdc.outbound.application.WorkerLocations workerLocations;

  /** 작업자 현황(0.18.0 M-1). */
  @Override
  public ResponseEntity<com.pmart.cdc.outbound.api.generated.model.WorkerBoard> listCenterWorkers(
      String cdcId, @Nullable String floor) {
    var b = workerLocations.board(cdcId, floor);
    var out = new com.pmart.cdc.outbound.api.generated.model.WorkerBoard();
    out.setAsOf(time(b.asOf()));
    out.setWorkers(
        new ArrayList<>(
            b.workers().stream()
                .map(
                    w ->
                        new com.pmart.cdc.outbound.api.generated.model.WorkerLocation()
                            .workerId(w.workerId())
                            .locationCode(w.locationCode())
                            .zone(w.zone())
                            .floor(w.floor())
                            .source(w.source())
                            .scannedAt(w.scannedAt() == null ? null : time(w.scannedAt()))
                            .locationState(
                                com.pmart.cdc.outbound.api.generated.model.WorkerLocation
                                    .LocationStateEnum.fromValue(w.locationState()))
                            .taskId(w.taskId())
                            .taskKind(w.taskKind())
                            .taskState(w.taskState()))
                .toList()));
    out.setByFloor(counts(b.byFloor()));
    return ResponseEntity.ok(out);
  }

  /** 위치 스캔(0.18.0 M-1, PDA 본인). */
  @Override
  public ResponseEntity<Void> scanWorkerLocation(
      String cdcId,
      String workerId,
      String idempotencyKey,
      String xSourceSystem,
      com.pmart.cdc.outbound.api.generated.model.ScanWorkerLocationRequest body) {
    workerLocations.scan(cdcId, workerId, body.getLocationCode(), body.getTaskId());
    return ResponseEntity.noContent().build();
  }

  static TransferSummary summary(TransferRow t) {
    return new TransferSummary()
        .trfId(t.trfId())
        .trfVersion(t.trfVersion())
        .mfcId(t.mfcId())
        .state(t.state())
        .deadlineMeaning(t.deadlineMeaning())
        .dueAt(time(t.dueAt()))
        .waveId(t.waveId())
        .allocationError(t.allocationError())
        .lineCount(t.lineCount())
        .approvedQty((int) t.approvedQty())
        .allocatedQty((int) t.allocatedQty())
        .pickedQty((int) t.pickedQty())
        .shortQty((int) t.shortQty())
        .createdAt(time(t.createdAt()))
        .updatedAt(time(t.updatedAt()));
  }

  private static List<CountByKey> counts(Map<String, Long> m) {
    List<CountByKey> out = new ArrayList<>();
    m.forEach((k, v) -> out.add(new CountByKey().key(k).count(v.intValue())));
    return out;
  }

  private static OffsetDateTime time(java.time.Instant i) {
    return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
  }
}
