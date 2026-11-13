package com.pmart.mfc.inventory.api;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.inventory.api.generated.SkusApi;
import com.pmart.mfc.inventory.api.generated.model.ListSkuProjections200Response;
import com.pmart.mfc.inventory.api.generated.model.SkuProjection;
import com.pmart.mfc.inventory.application.SkuProjector;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 SkusApi — 상품 SKU 투영 조회(서비스 간, /internal). */
@RestController
public class SkusController implements SkusApi {

  static final int MAX_BATCH = 100;

  private final SkuProjector projector;

  public SkusController(SkuProjector projector) {
    this.projector = projector;
  }

  @Override
  public ResponseEntity<SkuProjection> getSkuProjection(String sku) {
    return ResponseEntity.ok(dto(projector.projection(sku)));
  }

  @Override
  public ResponseEntity<ListSkuProjections200Response> listSkuProjections(List<String> skus) {
    var wanted = new LinkedHashSet<>(skus);
    if (wanted.isEmpty() || wanted.size() > MAX_BATCH) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED, "skus 는 1~" + MAX_BATCH + "건입니다: " + wanted.size());
    }
    Map<String, com.pmart.mfc.inventory.domain.SkuProjection> found = projector.projections(wanted);
    var out = new ListSkuProjections200Response();
    List<SkuProjection> items = new ArrayList<>();
    List<String> missing = new ArrayList<>();
    for (String sku : wanted) {
      var p = found.get(sku);
      if (p == null) {
        missing.add(sku);
      } else {
        items.add(dto(p));
      }
    }
    out.setItems(items);
    out.setMissing(missing);
    return ResponseEntity.ok(out);
  }

  private static SkuProjection dto(com.pmart.mfc.inventory.domain.SkuProjection p) {
    var d = new SkuProjection();
    d.setSku(p.sku());
    d.setProductId(p.productId());
    d.setBaseProductId(p.baseProductId());
    d.setUnitType(p.unitType());
    d.setUnitsPerSku(p.unitsPerSku());
    d.setWeightG(p.weightG());
    d.setVolumeMl(p.volumeMl());
    d.setStorageType(p.storageType());
    d.setAlcohol(p.alcohol());
    d.setShelfMinRemainingDays(p.shelfMinRemainingDays());
    d.setShelfMaxDaysSinceManufacture(p.shelfMaxDaysSinceManufacture());
    d.setProductName(p.productName());
    d.setBarcodes(new ArrayList<>(p.barcodes()));
    d.setState(SkuProjection.StateEnum.fromValue(p.state().name()));
    d.setProductVersion(p.productVersion());
    d.setUpdatedAt(SkuHandlingsController.time(p.updatedAt()));
    return d;
  }
}
