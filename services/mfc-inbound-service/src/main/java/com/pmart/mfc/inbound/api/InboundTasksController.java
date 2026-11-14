package com.pmart.mfc.inbound.api;

import com.pmart.mfc.inbound.api.generated.InboundTasksApi;
import com.pmart.mfc.inbound.api.generated.model.InboundTask;
import com.pmart.mfc.inbound.api.generated.model.InboundTaskExecution;
import com.pmart.mfc.inbound.api.generated.model.InspectInboundTaskRequest;
import com.pmart.mfc.inbound.api.generated.model.ListInboundTasks200Response;
import com.pmart.mfc.inbound.api.generated.model.PutawayInboundTaskRequest;
import com.pmart.mfc.inbound.application.InboundTaskService;
import com.pmart.mfc.inbound.application.InboundTaskService.ExecutionView;
import com.pmart.mfc.inbound.application.InboundTaskService.InspectResult;
import com.pmart.mfc.inbound.application.SkuLabels;
import com.pmart.mfc.inbound.application.SkuLabels.Label;
import com.pmart.mfc.inbound.infrastructure.InboundTaskStore.TaskRow;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 InboundTasksApi — 입고 작업(품질검사·적치). 업무 규칙은 application 에 있고 여기서는 변환만 한다. */
@RestController
public class InboundTasksController implements InboundTasksApi {

  private final InboundTaskService service;
  private final SkuLabels labels;

  public InboundTasksController(InboundTaskService service, SkuLabels labels) {
    this.service = service;
    this.labels = labels;
  }

  @Override
  public ResponseEntity<ListInboundTasks200Response> listInboundTasks(
      String mfcId,
      @Nullable String state,
      @Nullable String taskType,
      @Nullable String receiptId,
      @Nullable Boolean withLabels) {
    List<TaskRow> rows = service.list(mfcId, state, taskType, receiptId);
    Map<String, Label> names =
        Boolean.FALSE.equals(withLabels)
            ? Map.of()
            : labels.of(rows.stream().map(TaskRow::sku).toList());
    var out = new ListInboundTasks200Response();
    out.setItems(rows.stream().map(t -> dto(t, SkuLabels.get(names, t.sku()))).toList());
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<InboundTask> getInboundTask(String taskId, @Nullable Boolean withLabels) {
    TaskRow t = service.get(taskId);
    return ResponseEntity.ok(
        dto(
            t,
            Boolean.FALSE.equals(withLabels)
                ? Label.UNKNOWN
                : SkuLabels.get(labels.of(List.of(t.sku())), t.sku())));
  }

  @Override
  public ResponseEntity<InboundTaskExecution> inspectInboundTask(
      String taskId, String idempotencyKey, String xSourceSystem, InspectInboundTaskRequest body) {
    var results =
        body.getResults().stream()
            .map(
                r ->
                    new InspectResult(
                        r.getQty(),
                        r.getResultQuality().getValue(),
                        r.getSourceLotRef(),
                        r.getExpiryDate(),
                        r.getManufactureDate()))
            .toList();
    return ResponseEntity.ok(
        dto(service.inspect(idempotencyKey, xSourceSystem, taskId, results), labels));
  }

  @Override
  public ResponseEntity<InboundTaskExecution> putawayInboundTask(
      String taskId, String idempotencyKey, String xSourceSystem, PutawayInboundTaskRequest body) {
    return ResponseEntity.ok(
        dto(
            service.putaway(
                idempotencyKey, xSourceSystem, taskId, body.getToLocationCode(), body.getQty()),
            labels));
  }

  static InboundTaskExecution dto(ExecutionView v, SkuLabels labels) {
    var d = new InboundTaskExecution();
    d.setExecutionId(v.executionId());
    d.setTaskId(v.taskId());
    d.setApplyStatus(InboundTaskExecution.ApplyStatusEnum.fromValue(v.applyStatus()));
    d.setInventoryCommitId(v.commitId());
    d.setRejectReason(v.rejectReason());
    d.setCreatedTaskIds(v.createdTaskIds());
    d.setTask(dto(v.task(), SkuLabels.get(labels.of(List.of(v.task().sku())), v.task().sku())));
    return d;
  }

  static InboundTask dto(TaskRow t, Label label) {
    var d = new InboundTask();
    d.setTaskId(t.taskId());
    d.setTaskType(InboundTask.TaskTypeEnum.fromValue(t.taskType()));
    d.setMfcId(t.mfcId());
    d.setReceiptId(t.receiptId());
    d.setParentTaskId(t.parentTaskId());
    d.setSku(t.sku());
    d.setProductName(label.productName());
    d.setBarcodes(new java.util.ArrayList<>(label.barcodes()));
    d.setLotId(t.lotId());
    d.setLocationCode(t.locationCode());
    d.setPlannedQty(t.plannedQty());
    d.setDoneQty(t.doneQty());
    d.setState(InboundTask.StateEnum.fromValue(t.state()));
    d.setVersion(t.version());
    d.setAssigneeId(t.assigneeId());
    d.setCreatedAt(t.createdAt().atOffset(java.time.ZoneOffset.UTC));
    return d;
  }
}
