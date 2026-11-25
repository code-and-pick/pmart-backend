package com.pmart.cdc.inventory.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * CDC 본사 재고 승인 기준(0.8.0 — 사용자 2026-11-25 "MFC 와 같은 본사 승인 기준"·"MFC 와 같게"): 한 건 손실 Σ(감소 수량 × 판매 기준가)
 * ≥ 손실 기준 <b>또는</b> 한 SKU 움직이는 수량 ≥ 수량 기준이면 본사 승인. 단가 없는 감소 줄은 미평가 → 본사(근거 없는 금액으로 승인선을 고르지 않는다).
 * 기준 값은 설정 — 여기서는 받기만 한다. mfc-inventory {@code HqApprovalRules} 와 같은 규칙.
 */
public final class HqApprovalRules {

  /** 한 SKU 의 집행. qty = 움직이는 수량(절대값 합), decrease = 줄어드는 수량. */
  public record Line(String sku, int qty, int decrease) {}

  public record Price(long unitPrice, long priceVersion) {}

  public record Assessment(
      boolean hqRequired, long loss, List<String> reasons, List<Map<String, Object>> lines) {}

  private HqApprovalRules() {}

  public static Assessment assess(
      List<Line> lines, Map<String, Price> prices, long lossThreshold, int skuQtyThreshold) {
    long loss = 0;
    List<String> reasons = new ArrayList<>();
    List<Map<String, Object>> out = new ArrayList<>();
    for (Line l : merge(lines)) {
      Map<String, Object> attrs = new LinkedHashMap<>();
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("lineKey", l.sku());
      row.put("requestedQty", l.qty());
      Price p = prices.get(l.sku());
      if (l.decrease() > 0) {
        if (p == null) {
          attrs.put("unpriced", true);
          reasons.add(l.sku() + " 단가 없음(미평가)");
        } else {
          long amount = p.unitPrice() * l.decrease();
          loss += amount;
          row.put("requestedAmount", amount);
        }
      }
      if (p != null) {
        attrs.put("unitPrice", p.unitPrice());
        attrs.put("priceVersion", p.priceVersion());
      }
      if (l.qty() >= skuQtyThreshold) {
        reasons.add(l.sku() + " " + l.qty() + "개 ≥ " + skuQtyThreshold + "개");
      }
      row.put("attributes", attrs);
      out.add(row);
    }
    if (loss >= lossThreshold) {
      reasons.add("손실 " + loss + "원 ≥ " + lossThreshold + "원");
    }
    return new Assessment(!reasons.isEmpty(), loss, List.copyOf(reasons), List.copyOf(out));
  }

  static List<Line> merge(List<Line> lines) {
    Map<String, Line> by = new LinkedHashMap<>();
    for (Line l : lines) {
      by.merge(
          l.sku(), l, (a, b) -> new Line(a.sku(), a.qty() + b.qty(), a.decrease() + b.decrease()));
    }
    return List.copyOf(by.values());
  }

  /** 집행이 승인 범위 안인지 — SKU 마다 승인 수량 ≥ 집행 수량. 벗어나면 설명, 안이면 null. */
  public static @Nullable String outOfScope(List<Line> lines, Map<String, Integer> approved) {
    for (Line l : merge(lines)) {
      Integer a = approved.get(l.sku());
      if (a == null) {
        return l.sku() + " 은 승인에 없는 SKU 입니다.";
      }
      if (a < l.qty()) {
        return l.sku() + " 집행 " + l.qty() + "개가 승인 " + a + "개를 넘습니다 — 새 승인이 필요합니다.";
      }
    }
    return null;
  }
}
