package com.pmart.cdc.inbound.api;

import com.pmart.cdc.inbound.api.generated.ReplenishmentMovesApi;
import com.pmart.cdc.inbound.api.generated.model.ListReplenishmentChecks200Response;
import com.pmart.cdc.inbound.api.generated.model.ReplenishmentCheck;
import com.pmart.cdc.inbound.application.ReplenishmentService;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 ReplenishmentMovesApi — 자동 보충 판단 조회(0.2.0). 보충 작업 자체는 putaway-tasks(kind REPLENISHMENT). */
@RestController
public class ReplenishmentMovesController implements ReplenishmentMovesApi {

  private final ReplenishmentService replenishment;

  public ReplenishmentMovesController(ReplenishmentService replenishment) {
    this.replenishment = replenishment;
  }

  @Override
  public ResponseEntity<ListReplenishmentChecks200Response> listReplenishmentChecks(
      String cdcId, @Nullable String reason, @Nullable Integer limit) {
    var out = new ListReplenishmentChecks200Response();
    out.setItems(
        replenishment.checks(cdcId, reason, limit == null ? 500 : limit).stream()
            .map(
                c ->
                    new ReplenishmentCheck()
                        .locationCode(c.locationCode())
                        .sku(c.sku())
                        .currentQty(c.currentQty())
                        .minQty(c.minQty())
                        .targetQty(c.targetQty())
                        .openQty(c.openQty())
                        .needQty(c.needQty())
                        .orderedQty(c.orderedQty())
                        .unitQty(c.unitQty())
                        .reason(c.reason())
                        .taskId(c.taskId())
                        .checkedAt(PutawayTasksController.time(c.checkedAt())))
            .toList());
    return ResponseEntity.ok(out);
  }
}
