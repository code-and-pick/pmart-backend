package com.pmart.mfc.inbound.application;

import com.pmart.mfc.inbound.infrastructure.InventorySkuClient;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 현장 화면 표시용 상품명·바코드(클라이언트 BR-53 R8, 0.8.0). mfc-inventory SKU 사본(상품 사건 투영)에서 읽는다. 표시용이라 조회가 실패하면 빈
 * 값으로 두고 목록은 그대로 돌려준다(작업 조회를 막지 않는다).
 */
@Service
public class SkuLabels {

  /** 상품명(관리용 명칭, 모르면 null)·활성 바코드. */
  public record Label(@Nullable String productName, List<String> barcodes) {
    public static final Label UNKNOWN = new Label(null, List.of());
  }

  private static final Logger log = LoggerFactory.getLogger(SkuLabels.class);

  private final InventorySkuClient client;

  public SkuLabels(InventorySkuClient client) {
    this.client = client;
  }

  public Map<String, Label> of(Collection<String> skus) {
    List<String> distinct = skus.stream().distinct().toList();
    if (distinct.isEmpty()) {
      return Map.of();
    }
    try {
      return client.labels(distinct);
    } catch (RuntimeException ex) {
      log.warn("SKU 상품명·바코드 조회 실패 — 빈 값으로 응답: {}", ex.getMessage());
      return Map.of();
    }
  }

  public static Label get(Map<String, Label> labels, String sku) {
    return labels.getOrDefault(sku, Label.UNKNOWN);
  }
}
