package com.pmart.cdc.inbound.api;

import com.pmart.cdc.inbound.api.generated.InternalApi;
import com.pmart.cdc.inbound.api.generated.model.ClaimPutawayTaskRequest;
import com.pmart.cdc.inbound.api.generated.model.PutawayTask;
import com.pmart.cdc.inbound.api.generated.model.PutawayTaskKind;
import com.pmart.cdc.inbound.api.generated.model.ReceiveWorkResultRequest;
import com.pmart.cdc.inbound.application.TaskService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 InternalApi — 가상 설비의 다음 작업 받기·결과(0.2.0). */
@RestController
public class InternalController implements InternalApi {

  private final TaskService tasks;

  public InternalController(TaskService tasks) {
    this.tasks = tasks;
  }

  @Override
  public ResponseEntity<PutawayTask> claimPutawayTask(
      String idempotencyKey, String xSourceSystem, ClaimPutawayTaskRequest body) {
    String id =
        tasks.claim(
            idempotencyKey,
            xSourceSystem,
            body.getCdcId(),
            body.getDeviceId(),
            body.getKinds() == null
                ? List.of()
                : body.getKinds().stream().map(PutawayTaskKind::getValue).toList());
    return id == null
        ? ResponseEntity.noContent().build()
        : ResponseEntity.ok(PutawayTasksController.dto(tasks.view(id)));
  }

  @Override
  public ResponseEntity<PutawayTask> receiveWorkResult(
      String idempotencyKey, String xSourceSystem, ReceiveWorkResultRequest body) {
    return ResponseEntity.ok(
        PutawayTasksController.dto(
            tasks.result(
                body.getEventId(),
                body.getTaskId(),
                body.getDeviceId(),
                body.getStatus().getValue(),
                body.getCompletedQty(),
                body.getFailureReason())));
  }
}
