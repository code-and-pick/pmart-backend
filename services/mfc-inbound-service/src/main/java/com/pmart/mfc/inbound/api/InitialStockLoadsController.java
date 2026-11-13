package com.pmart.mfc.inbound.api;

import com.pmart.mfc.inbound.api.generated.InitialStockLoadsApi;
import com.pmart.mfc.inbound.api.generated.model.ListInitialStockLoads200Response;
import com.pmart.mfc.inbound.api.generated.model.ReceiveInitialStockRequest;
import com.pmart.mfc.inbound.api.generated.model.RecordArrival202Response;
import com.pmart.mfc.inbound.application.InboundTaskService;
import com.pmart.mfc.inbound.application.InitialStockService;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 InitialStockLoadsApi — 운영자가 INITIAL_STOCK 승인 ID 로 반입 결과를 확인하고(LAN 시드 대기에도 쓴다), 입고 예정의 실제
 * 수령(상품·수량·수령 위치)을 넣는다.
 */
@RestController
public class InitialStockLoadsController implements InitialStockLoadsApi {

  private final InitialStockService loads;
  private final InboundTaskService tasks;
  private final com.pmart.mfc.inbound.application.SkuLabels labels;

  public InitialStockLoadsController(
      InitialStockService loads,
      InboundTaskService tasks,
      com.pmart.mfc.inbound.application.SkuLabels labels) {
    this.loads = loads;
    this.tasks = tasks;
    this.labels = labels;
  }

  @Override
  public ResponseEntity<ListInitialStockLoads200Response> listInitialStockLoads(String approvalId) {
    var out = new ListInitialStockLoads200Response();
    var rows = loads.byApproval(approvalId);
    var names = labels.of(rows.stream().flatMap(r -> r.lines().keySet().stream()).toList());
    out.setItems(rows.stream().map(r -> InitialStockLoads.dto(r, names)).toList());
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<RecordArrival202Response> receiveInitialStock(
      String receiptId,
      String idempotencyKey,
      String xSourceSystem,
      ReceiveInitialStockRequest body) {
    Map<String, Integer> lines = new LinkedHashMap<>();
    body.getLines().forEach(l -> lines.merge(l.getSku(), l.getReceivedQty(), Integer::sum));
    var c =
        tasks.receive(idempotencyKey, xSourceSystem, receiptId, body.getToLocationCode(), lines);
    var d = new RecordArrival202Response();
    d.setStatus(RecordArrival202Response.StatusEnum.fromValue(c.status().name()));
    d.setRequestId(c.requestId());
    d.setResultId(c.resultId());
    d.setAppliedQty(c.appliedQty());
    d.setVersion(c.version());
    d.setRetryable(c.retryable());
    d.setNextAction(c.nextAction());
    d.setOccurredAt(c.occurredAt().atOffset(ZoneOffset.UTC));
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(d);
  }
}
