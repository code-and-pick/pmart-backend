package com.pmart.mfc.fulfillment.api;

import com.pmart.mfc.fulfillment.api.generated.WorkOrdersApi;
import com.pmart.mfc.fulfillment.api.generated.model.ConfirmOrderRequest;
import com.pmart.mfc.fulfillment.api.generated.model.Shipment;
import com.pmart.mfc.fulfillment.api.generated.model.ShipmentState;
import com.pmart.mfc.fulfillment.api.generated.model.StopOrderWorkRequest;
import com.pmart.mfc.fulfillment.api.generated.model.StopRequestResult;
import com.pmart.mfc.fulfillment.api.generated.model.StopRequestResultNotStartedLinesInner;
import com.pmart.mfc.fulfillment.api.generated.model.StopRequestResultPackagesInner;
import com.pmart.mfc.fulfillment.api.generated.model.StopRequestResultPackagesInnerStoppedLinesInner;
import com.pmart.mfc.fulfillment.api.generated.model.TaskState;
import com.pmart.mfc.fulfillment.api.generated.model.WorkOrder;
import com.pmart.mfc.fulfillment.api.generated.model.WorkOrderDecision;
import com.pmart.mfc.fulfillment.api.generated.model.WorkOrderTasksInner;
import com.pmart.mfc.fulfillment.api.generated.model.WorkRejectReason;
import com.pmart.mfc.fulfillment.application.WorkOrderService;
import com.pmart.mfc.fulfillment.application.WorkStopService;
import com.pmart.mfc.fulfillment.infrastructure.WorkOrderStore.TaskLineRow;
import com.pmart.mfc.fulfillment.infrastructure.WorkOrderStore.TaskRow;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 WorkOrdersApi — 작업 접수(ConfirmOrder)·작업 지시 조회(슬라이스 6a)와 주문 단위 작업 중지(S3-a). 작업 지시 단위 중지는 인터페이스
 * default 가 501 을 돌려준다. 업무 규칙은 application 에 두고 여기서는 요청·응답 변환만 한다.
 */
@RestController
public class WorkOrdersController implements WorkOrdersApi {

  @org.springframework.beans.factory.annotation.Autowired
  private com.pmart.mfc.fulfillment.application.TaskService tasks;

  @Override
  public ResponseEntity<com.pmart.mfc.fulfillment.api.generated.model.ResolveShortage200Response>
      resolveShortage(
          String fulfillmentId,
          String idempotencyKey,
          String xSourceSystem,
          com.pmart.mfc.fulfillment.api.generated.model.ResolveShortageRequest body) {
    var r =
        tasks.resolveShortage(
            fulfillmentId, idempotencyKey, xSourceSystem, body.getOrderLineId(), body.getQty());
    var out = new com.pmart.mfc.fulfillment.api.generated.model.ResolveShortage200Response();
    out.setStatus(
        com.pmart.mfc.fulfillment.api.generated.model.ResolveShortage200Response.StatusEnum
            .APPLIED);
    out.setRequestId(r.requestId());
    out.setResultId(r.resultId());
    out.setAppliedQty(r.appliedQty());
    out.setVersion(r.version());
    out.setRetryable(false);
    out.setOccurredAt(TasksController.time(r.occurredAt()));
    return ResponseEntity.ok(out);
  }

  private final WorkStopService stops;
  private final WorkOrderService works;

  public WorkOrdersController(WorkStopService stops, WorkOrderService works) {
    this.stops = stops;
    this.works = works;
  }

  @Override
  public ResponseEntity<WorkOrderDecision> confirmOrder(
      String idempotencyKey,
      String xSourceSystem,
      ConfirmOrderRequest body,
      @Nullable String traceparent) {
    var window = body.getArrivalWindow();
    WorkOrderService.Decision d =
        works.confirm(
            idempotencyKey,
            xSourceSystem,
            new WorkOrderService.ConfirmCommand(
                body.getOrderId(),
                body.getWorkVersion() == null ? 1 : body.getWorkVersion(),
                body.getSagaId(),
                body.getMfcId(),
                body.getReservationId(),
                body.getDeliveryMode() == null ? null : body.getDeliveryMode().getValue(),
                body.getOutOfStockPreference(),
                window == null || window.getStart() == null ? null : window.getStart().toInstant(),
                window == null || window.getEnd() == null ? null : window.getEnd().toInstant(),
                body.getShipments().stream()
                    .map(
                        s ->
                            new WorkOrderService.Ship(
                                s.getShipmentId(),
                                s.getWeightG(),
                                s.getLines().stream()
                                    .map(
                                        l ->
                                            new WorkOrderService.ShipLine(
                                                l.getOrderLineId(),
                                                l.getSku(),
                                                l.getQty(),
                                                l.getProductName(),
                                                l.getUnitsPerSku(),
                                                l.getWeightG()))
                                    .toList()))
                    .toList(),
                body.getKind() == null ? null : body.getKind().getValue(),
                body.getReshipmentRef()));
    return ResponseEntity.status(HttpStatus.CREATED).body(decision(d));
  }

  @Override
  public ResponseEntity<WorkOrder> getWorkOrder(String fulfillmentId) {
    return ResponseEntity.ok(workOrder(works.get(fulfillmentId)));
  }

  @Override
  public ResponseEntity<WorkOrder> findWorkOrderByOrder(String orderId) {
    return ResponseEntity.ok(workOrder(works.findByOrder(orderId)));
  }

  @Override
  public ResponseEntity<StopRequestResult> stopOrderWork(
      String idempotencyKey, String xSourceSystem, StopOrderWorkRequest body) {
    WorkStopService.Verdict v =
        stops.stop(
            idempotencyKey,
            xSourceSystem,
            new WorkStopService.StopCommand(
                body.getStopRequestId(),
                body.getOrderId(),
                body.getClaimId(),
                body.getScope().getValue(),
                body.getLines().stream()
                    .map(l -> new WorkStopService.Line(l.getOrderLineId(), l.getQty()))
                    .toList()));
    return ResponseEntity.ok(result(v));
  }

  @Override
  public ResponseEntity<StopRequestResult> retryOrderStopRequest(
      String stopRequestId, String idempotencyKey, String xSourceSystem) {
    return ResponseEntity.ok(result(stops.retry(stopRequestId)));
  }

  @Override
  public ResponseEntity<StopRequestResult> getOrderStopRequest(String stopRequestId) {
    return ResponseEntity.ok(result(stops.get(stopRequestId)));
  }

  private static WorkOrderDecision decision(WorkOrderService.Decision d) {
    return new WorkOrderDecision()
        .fulfillmentId(d.fulfillmentId())
        .orderId(d.orderId())
        .workVersion(d.workVersion())
        .decision(WorkOrderDecision.DecisionEnum.fromValue(d.decision()))
        .rejectReason(
            d.rejectReason() == null ? null : WorkRejectReason.fromValue(d.rejectReason()))
        .retryable(d.rejectReason() == null ? null : d.retryable())
        .message(d.message())
        .shipmentIds(d.shipmentIds())
        .taskIds(d.taskIds())
        .decidedAt(time(d.decidedAt()));
  }

  private static WorkOrder workOrder(WorkOrderService.View v) {
    var w = v.order();
    var last = v.last();
    WorkOrder out =
        new WorkOrder()
            .fulfillmentId(w.fulfillmentId())
            .orderId(w.orderId())
            .mfcId(w.mfcId())
            .version(w.version())
            .workVersion(w.lastWorkVersion())
            .acceptance(WorkOrder.AcceptanceEnum.fromValue(w.acceptance()))
            .rejectReason(
                last == null || last.rejectReason() == null
                    ? null
                    : WorkRejectReason.fromValue(last.rejectReason()))
            .tasks(new java.util.ArrayList<>())
            .shipments(new java.util.ArrayList<>());
    for (TaskRow t : v.tasks()) {
      int planned = 0;
      int completed = 0;
      int cancelled = 0;
      for (TaskLineRow l : v.taskLines()) {
        if (l.taskId().equals(t.taskId())) {
          planned += l.plannedQty();
          completed += l.completedQty();
          cancelled += l.cancelledQty();
        }
      }
      out.addTasksItem(
          new WorkOrderTasksInner()
              .taskId(t.taskId())
              .taskType(WorkOrderTasksInner.TaskTypeEnum.fromValue(t.taskType()))
              .state(TaskState.fromValue(t.state()))
              .shipmentId(t.shipmentId())
              .readyAt(t.readyAt() == null ? null : time(t.readyAt()))
              .holdReason(t.holdReason())
              .plannedQty(planned)
              .completedQty(completed)
              .cancelledQty(cancelled)
              .remainingQty(planned - completed - cancelled));
    }
    v.shipments()
        .forEach(
            s ->
                out.addShipmentsItem(
                    new Shipment()
                        .shipmentId(s.shipmentId())
                        .fulfillmentId(s.fulfillmentId())
                        .orderId(w.orderId())
                        .version(s.version())
                        .state(ShipmentState.fromValue(s.state()))
                        .weightG(s.weightG())
                        .packages(List.of())));
    return out;
  }

  private static StopRequestResult result(WorkStopService.Verdict v) {
    return new StopRequestResult()
        .stopRequestId(v.stopRequestId())
        .orderId(v.orderId())
        .state(StopRequestResult.StateEnum.fromValue(v.state()))
        .notStartedLines(
            v.notStartedLines().stream()
                .map(
                    l ->
                        new StopRequestResultNotStartedLinesInner()
                            .orderLineId(l.orderLineId())
                            .qty(l.qty()))
                .toList())
        .packages(
            v.packages() == null
                ? List.of()
                : v.packages().stream()
                    .map(
                        pv ->
                            new StopRequestResultPackagesInner()
                                .packageId(pv.packageId())
                                .shipmentId(pv.shipmentId())
                                .verdict(
                                    StopRequestResultPackagesInner.VerdictEnum.fromValue(
                                        pv.verdict()))
                                .stoppedLines(
                                    pv.stoppedLines().stream()
                                        .map(
                                            l ->
                                                new StopRequestResultPackagesInnerStoppedLinesInner()
                                                    .orderLineId(l.orderLineId())
                                                    .qty(l.qty())
                                                    .workStateAtStop(
                                                        StopRequestResultPackagesInnerStoppedLinesInner
                                                            .WorkStateAtStopEnum.fromValue(
                                                            l.workStateAtStop())))
                                        .toList()))
                    .toList())
        .resolvedAt(time(v.resolvedAt()))
        .lastError(v.lastError());
  }

  private static OffsetDateTime time(Instant i) {
    return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
  }
}
