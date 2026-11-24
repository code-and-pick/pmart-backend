package com.pmart.cdc.inventory.application;

import com.pmart.cdc.inventory.infrastructure.SkuProjectionStore;
import com.pmart.cdc.inventory.infrastructure.StockStore;
import com.pmart.cdc.inventory.infrastructure.StockStore.SafetyRow;
import com.pmart.common.web.PmartException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 가용·할당 앞 검사: SKU 투영 있음(404 SKU_NOT_FOUND) → 센터 취급 ON(422 SKU_NOT_HANDLED) → 안전량 설정됨(422
 * CONFIG_REQUIRED — 미설정을 0 으로 대신하지 않는다).
 */
@Component
public class SkuGate {

  static final String SAFETY_POLICY = "cdc-inventory.safety-stock";

  private final SkuProjectionStore projections;
  private final SkuHandlingService handlings;
  private final StockStore stock;

  public SkuGate(SkuProjectionStore projections, SkuHandlingService handlings, StockStore stock) {
    this.projections = projections;
    this.handlings = handlings;
    this.stock = stock;
  }

  /**
   * @return SKU → 안전량 S
   */
  Map<String, Integer> requireAllocatable(String cdcId, List<String> skus) {
    Set<String> known = projections.findAll(skus).keySet();
    List<PmartException.Detail> missing = new ArrayList<>();
    List<PmartException.Detail> off = new ArrayList<>();
    Map<String, Boolean> flags = handlings.enabledFlags(cdcId, skus);
    for (int i = 0; i < skus.size(); i++) {
      String s = skus.get(i);
      if (!known.contains(s)) {
        missing.add(new PmartException.Detail("lines[" + i + "].sku", "SKU_NOT_FOUND"));
      } else if (!Boolean.TRUE.equals(flags.get(s))) {
        off.add(new PmartException.Detail("lines[" + i + "].sku", "SKU_NOT_HANDLED"));
      }
    }
    if (!missing.isEmpty()) {
      throw new PmartException(
          "SKU_NOT_FOUND", HttpStatus.NOT_FOUND, "모르는 SKU 가 있습니다.", false, missing);
    }
    if (!off.isEmpty()) {
      throw new PmartException(
          "SKU_NOT_HANDLED",
          HttpStatus.UNPROCESSABLE_CONTENT,
          "센터 " + cdcId + " 가 취급하지 않는 SKU 가 있습니다.",
          false,
          off);
    }
    Map<String, Integer> out = new LinkedHashMap<>();
    for (String s : skus) {
      out.put(s, requireSafety(cdcId, s).qty());
    }
    return out;
  }

  SafetyRow requireSafety(String cdcId, String sku) {
    Optional<SafetyRow> s = stock.safety(cdcId, sku);
    if (s.isEmpty()) {
      throw PmartException.configRequired(SAFETY_POLICY + "[" + cdcId + "/" + sku + "]");
    }
    return s.get();
  }
}
