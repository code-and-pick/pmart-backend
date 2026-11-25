package com.pmart.cdc.inventory.api;

import com.pmart.cdc.inventory.api.generated.SkuHandlingsApi;
import com.pmart.cdc.inventory.api.generated.model.ListTransits200ResponsePage;
import com.pmart.cdc.inventory.api.generated.model.SkuHandling;
import com.pmart.cdc.inventory.api.generated.model.SkuHandlingBatchRequest;
import com.pmart.cdc.inventory.api.generated.model.SkuHandlingBatchResult;
import com.pmart.cdc.inventory.api.generated.model.SkuHandlingChange;
import com.pmart.cdc.inventory.api.generated.model.SkuHandlingPage;
import com.pmart.cdc.inventory.api.generated.model.SkuHandlingResult;
import com.pmart.cdc.inventory.application.Operator;
import com.pmart.cdc.inventory.application.SkuHandlingService;
import com.pmart.cdc.inventory.application.SkuHandlingService.BatchItem;
import com.pmart.cdc.inventory.application.SkuHandlingService.HandlingView;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 SkuHandlingsApi — CDC 운영자의 SKU 취급 ON/OFF. 업무 규칙은 application/domain 에 있고 여기서는 변환만 한다. */
@RestController
public class SkuHandlingsController implements SkuHandlingsApi {

  private final SkuHandlingService service;
  private final com.pmart.cdc.inventory.infrastructure.ProductNames names;

  public SkuHandlingsController(
      SkuHandlingService service, com.pmart.cdc.inventory.infrastructure.ProductNames names) {
    this.service = service;
    this.names = names;
  }

  @Override
  public ResponseEntity<SkuHandlingPage> listSkuHandlings(
      String cdcId, @Nullable Boolean enabled, @Nullable String cursor, Integer size) {
    var page = service.list(cdcId, enabled, cursor, size == null ? 50 : size);
    var out = new SkuHandlingPage();
    out.setCenterId(cdcId);
    var items = page.items().stream().map(SkuHandlingsController::dto).toList();
    var n = names.of(items.stream().map(SkuHandling::getSku).toList()); // 0.17.0 CI-1
    items.forEach(x -> x.setProductName(n.get(x.getSku())));
    out.setItems(items);
    var p = new ListTransits200ResponsePage();
    p.setNextCursor(page.nextCursor());
    p.setSize(page.items().size());
    out.setPage(p);
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<SkuHandlingResult> changeSkuHandling(
      String cdcId,
      String idempotencyKey,
      String xSourceSystem,
      SkuHandlingChange body,
      @Nullable Integer xExpectedVersion) {
    var r =
        service
            .change(
                idempotencyKey,
                Operator.forCenter(cdcId, xSourceSystem),
                cdcId,
                body.getSku(),
                Boolean.TRUE.equals(body.getEnabled()),
                body.getReason(),
                xExpectedVersion)
            .value();
    var out = new SkuHandlingResult();
    out.setStatus(SkuHandlingResult.StatusEnum.APPLIED);
    out.setRequestId(r.requestId());
    out.setChanged(r.changed());
    out.setOccurredAt(time(r.occurredAt()));
    var h = dto(r.handling());
    h.setProductName(names.of(java.util.List.of(h.getSku())).get(h.getSku()));
    out.setHandling(h);
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<SkuHandlingBatchResult> changeSkuHandlingBatch(
      String cdcId, String idempotencyKey, String xSourceSystem, SkuHandlingBatchRequest body) {
    List<BatchItem> items =
        body.getItems().stream()
            .map(
                i ->
                    new BatchItem(
                        i.getSku(),
                        Boolean.TRUE.equals(i.getEnabled()),
                        i.getReason(),
                        i.getExpectedVersion()))
            .toList();
    var r =
        service
            .changeBatch(
                idempotencyKey,
                Operator.forCenter(cdcId, xSourceSystem),
                cdcId,
                body.getReason(),
                items)
            .value();
    var out = new SkuHandlingBatchResult();
    out.setStatus(SkuHandlingBatchResult.StatusEnum.APPLIED);
    out.setRequestId(r.requestId());
    out.setChangedCount(r.changedCount());
    out.setOccurredAt(time(r.occurredAt()));
    out.setItems(r.items().stream().map(SkuHandlingsController::dto).toList());
    return ResponseEntity.ok(out);
  }

  static SkuHandling dto(HandlingView v) {
    var d = new SkuHandling();
    d.setSku(v.sku());
    d.setHandlingEnabled(v.enabled());
    d.setVersion(v.version());
    d.setSkuState(v.skuState() == null ? null : SkuHandling.SkuStateEnum.fromValue(v.skuState()));
    d.setProductId(v.productId());
    d.setChangedBy(v.changedBy());
    d.setReason(v.reason());
    d.setUpdatedAt(v.updatedAt() == null ? null : time(v.updatedAt()));
    return d;
  }

  static OffsetDateTime time(Instant i) {
    return i.atOffset(ZoneOffset.UTC);
  }
}
