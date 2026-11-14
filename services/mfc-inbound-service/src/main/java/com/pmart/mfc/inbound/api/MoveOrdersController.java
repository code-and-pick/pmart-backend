package com.pmart.mfc.inbound.api;

import com.pmart.mfc.inbound.api.generated.MovesApi;
import com.pmart.mfc.inbound.api.generated.model.CancelMoveOrderRequest;
import com.pmart.mfc.inbound.api.generated.model.CreateMoveOrderRequest;
import com.pmart.mfc.inbound.api.generated.model.DropMoveOrderRequest;
import com.pmart.mfc.inbound.api.generated.model.ListMoveOrders200Response;
import com.pmart.mfc.inbound.api.generated.model.MoveOrder;
import com.pmart.mfc.inbound.api.generated.model.PickUpMoveOrderRequest;
import com.pmart.mfc.inbound.application.MoveOrderService;
import com.pmart.mfc.inbound.application.MoveOrderService.Row;
import com.pmart.mfc.inbound.application.SkuLabels;
import com.pmart.mfc.inbound.application.SkuLabels.Label;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 MovesApi — 재고 위치 이동 지시·실행(IV-03, 0.5.0). 업무 규칙은 {@link MoveOrderService}·mfc-inventory. */
@RestController
public class MoveOrdersController implements MovesApi {

  private final MoveOrderService service;
  private final SkuLabels labels;

  public MoveOrdersController(MoveOrderService service, SkuLabels labels) {
    this.service = service;
    this.labels = labels;
  }

  @Override
  public ResponseEntity<MoveOrder> createMoveOrder(
      String idempotencyKey, String xSourceSystem, CreateMoveOrderRequest b) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            labeled(
                service.create(
                    idempotencyKey,
                    xSourceSystem,
                    new MoveOrderService.CreateCommand(
                        b.getMfcId(),
                        b.getSku(),
                        b.getLotId(),
                        b.getFromLocation(),
                        b.getToLocation(),
                        b.getQty(),
                        b.getReason(),
                        Boolean.TRUE.equals(b.getUrgent())))));
  }

  @Override
  public ResponseEntity<ListMoveOrders200Response> listMoveOrders(
      String mfcId, @Nullable String state, @Nullable Boolean withLabels) {
    List<Row> rows = service.list(mfcId, state);
    Map<String, Label> names =
        Boolean.FALSE.equals(withLabels)
            ? Map.of()
            : labels.of(rows.stream().map(Row::sku).toList());
    var out = new ListMoveOrders200Response();
    out.setItems(rows.stream().map(r -> dto(r, SkuLabels.get(names, r.sku()))).toList());
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<MoveOrder> getMoveOrder(String moveId, @Nullable Boolean withLabels) {
    Row r = service.find(moveId);
    return ResponseEntity.ok(Boolean.FALSE.equals(withLabels) ? dto(r, Label.UNKNOWN) : labeled(r));
  }

  @Override
  public ResponseEntity<MoveOrder> pickUpMoveOrder(
      String moveId, String idempotencyKey, String xSourceSystem, PickUpMoveOrderRequest b) {
    return ResponseEntity.ok(
        labeled(service.pickUp(idempotencyKey, moveId, b.getCartLocation(), b.getQty())));
  }

  @Override
  public ResponseEntity<MoveOrder> dropMoveOrder(
      String moveId, String idempotencyKey, String xSourceSystem, DropMoveOrderRequest b) {
    return ResponseEntity.ok(
        labeled(service.drop(idempotencyKey, moveId, b.getToLocation(), b.getQty())));
  }

  @Override
  public ResponseEntity<MoveOrder> cancelMoveOrder(
      String moveId, String idempotencyKey, String xSourceSystem, CancelMoveOrderRequest b) {
    return ResponseEntity.ok(labeled(service.cancel(idempotencyKey, moveId, b.getReason())));
  }

  /** (0.9.0, BR-52 R19-1) 상품명·바코드 — 조회 실패면 빈 값(SkuLabels). */
  private MoveOrder labeled(Row r) {
    return dto(r, SkuLabels.get(labels.of(List.of(r.sku())), r.sku()));
  }

  static MoveOrder dto(Row r, Label label) {
    var out = new MoveOrder();
    out.setMoveId(r.moveId());
    out.setProductName(label.productName());
    out.setBarcodes(new java.util.ArrayList<>(label.barcodes()));
    out.setKind(MoveOrder.KindEnum.fromValue(r.kind()));
    out.setMfcId(r.mfcId());
    out.setSku(r.sku());
    out.setLotId(r.lotId());
    out.setFromLocation(r.fromLocation());
    out.setToLocation(r.toLocation());
    out.setCartLocation(r.cartLocation());
    out.setQty(r.qty());
    out.setPickedQty(r.pickedQty());
    out.setDroppedQty(r.droppedQty());
    out.setOnCartQty(r.pickedQty() - r.droppedQty());
    out.setState(MoveOrder.StateEnum.fromValue(r.state()));
    out.setReason(r.reason());
    out.setCreatedBy(r.createdBy());
    out.setUrgent(r.urgent());
    out.setAssigneeId(r.assigneeId());
    out.setPickedBy(r.pickedBy());
    out.setCreatedAt(r.createdAt().atOffset(ZoneOffset.UTC));
    out.setUpdatedAt(r.updatedAt().atOffset(ZoneOffset.UTC));
    return out;
  }
}
