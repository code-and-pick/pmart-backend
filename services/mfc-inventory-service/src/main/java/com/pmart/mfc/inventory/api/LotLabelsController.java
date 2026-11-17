package com.pmart.mfc.inventory.api;

import com.pmart.mfc.inventory.api.generated.LotLabelsApi;
import com.pmart.mfc.inventory.api.generated.model.ListLotLabels200Response;
import com.pmart.mfc.inventory.api.generated.model.LotLabel;
import com.pmart.mfc.inventory.application.LotLabelService;
import com.pmart.mfc.inventory.application.LotLabelService.Label;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** LOT 라벨 인쇄·재출력·해석(0.20.0), GS1-128 LOT 찾기(0.21.0). */
@RestController
public class LotLabelsController implements LotLabelsApi {

  private final LotLabelService labels;

  public LotLabelsController(LotLabelService labels) {
    this.labels = labels;
  }

  @Override
  public ResponseEntity<LotLabel> getLotLabel(String mfcId, String lotId) {
    return ResponseEntity.ok(dto(labels.get(mfcId, lotId)));
  }

  @Override
  public ResponseEntity<LotLabel> resolveLot(String mfcId, String code) {
    return ResponseEntity.ok(dto(labels.resolve(mfcId, code)));
  }

  @Override
  public ResponseEntity<ListLotLabels200Response> listLotLabels(
      String mfcId, @Nullable String sku, @Nullable String locationCode) {
    var out = new ListLotLabels200Response();
    out.setItems(
        labels.find(mfcId, sku, locationCode).stream().map(LotLabelsController::dto).toList());
    return ResponseEntity.ok(out);
  }

  private static LotLabel dto(Label l) {
    var lot = l.lot();
    var sku = l.sku();
    var out = new LotLabel();
    out.setLabelValue(l.value());
    out.setLotId(lot.lotId());
    out.setLotNo(lot.lotNo());
    out.setGs1(l.gs1());
    out.setSku(lot.sku());
    out.setProductName(sku == null ? null : sku.productName());
    out.setBarcodes(sku == null ? List.of() : new ArrayList<>(sku.barcodes()));
    out.setUnitType(sku == null ? null : sku.unitType());
    out.setUnitsPerSku(sku == null ? null : sku.unitsPerSku());
    out.setTracking(LotLabel.TrackingEnum.fromValue(lot.tracking()));
    out.setSourceLotRef(lot.sourceLotRef());
    out.setExpiryDate(lot.expiryDate());
    out.setManufactureDate(lot.manufactureDate());
    out.setReceivedAt(
        lot.receivedAt() == null ? null : SkuHandlingsController.time(lot.receivedAt()));
    out.setOnHand(lot.onHand());
    out.setQtyAtLocation(lot.qtyAtLocation());
    return out;
  }
}
