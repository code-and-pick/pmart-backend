package com.pmart.mfc.inventory.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.inventory.domain.ProductVersionGate;
import com.pmart.mfc.inventory.domain.SkuProjection;
import com.pmart.mfc.inventory.domain.SkuProjection.SkuState;
import com.pmart.mfc.inventory.infrastructure.SkuProjectionStore;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 상품 이벤트 → SKU 투영. InboxGuard 트랜잭션 안에서 호출된다. 오래된 상품 버전은 버린다({@link ProductVersionGate}). 취급 레코드는
 * 만들지 않는다.
 */
@Service
public class SkuProjector {

  private static final Logger log = LoggerFactory.getLogger(SkuProjector.class);

  private final SkuProjectionStore store;
  private final Clock clock;

  public SkuProjector(SkuProjectionStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  /** 투영 조회(서비스 간). */
  public SkuProjection projection(String sku) {
    return store
        .find(sku)
        .orElseThrow(() -> PmartException.notFound("SKU_NOT_FOUND", "SKU " + sku + " 투영이 없습니다."));
  }

  /** 투영 일괄 조회(서비스 간). 없는 SKU 는 결과에 없다. */
  public java.util.Map<String, SkuProjection> projections(java.util.Collection<String> skus) {
    return store.findAll(skus);
  }

  public void apply(EventEnvelope e) {
    switch (e.eventType()) {
      case "SkuIssued" -> issued(e.payload());
      case "ProductVersionPublished" -> published(e.payload());
      default -> {
        // 같은 토픽의 다른 사건(ProductSaleStateChanged 등)은 이 서비스가 쓰지 않는다
      }
    }
  }

  private void issued(JsonNode p) {
    String sku = p.path("sku").asString();
    int version = p.path("version").asInt();
    Optional<SkuProjection> stored = store.find(sku);
    if (stored.isPresent()
        && !ProductVersionGate.acceptsIssued(stored.get().productVersion(), version)) {
      log.debug(
          "SkuIssued {} v{} ignored (stored v{})", sku, version, stored.get().productVersion());
      return;
    }
    store.upsertIssued(
        sku, text(p, "productId"), text(p, "baseProductId"), version, Instant.now(clock));
  }

  private void published(JsonNode p) {
    int version = p.path("version").asInt();
    String productId = text(p, "productId");
    String baseProductId = text(p, "baseProductId");
    Instant at = Instant.now(clock);
    java.util.List<String> paths = new java.util.ArrayList<>();
    for (JsonNode c : p.path("categoryPaths")) {
      if (!c.asString("").isBlank()) {
        paths.add(c.asString());
      }
    }
    for (JsonNode s : p.path("skus")) {
      String sku = s.path("sku").asString();
      if (!paths.isEmpty()) {
        store.upsertCategories(sku, paths, version, at); // 진열 카테고리 판정(2026-11-08)
      }
      Optional<SkuProjection> stored = store.find(sku);
      if (stored.isPresent()
          && !ProductVersionGate.acceptsSnapshot(stored.get().productVersion(), version)) {
        log.info(
            "ProductVersionPublished {} v{} ignored for {} (stored v{})",
            productId,
            version,
            sku,
            stored.get().productVersion());
        continue;
      }
      String unitType = text(s, "unitType");
      store.upsertSnapshot(
          new SkuProjection(
              sku,
              productId,
              baseProductId,
              unitType != null ? unitType : SkuProjection.unitTypeOf(sku),
              integer(s, "unitsPerSku"),
              integer(s, "weightG"),
              integer(s, "volumeMl"),
              text(s, "storageType"),
              s.hasNonNull("alcohol") ? s.get("alcohol").asBoolean() : null,
              integer(s.path("shelfLifeGuarantee"), "minRemainingDays"),
              integer(s.path("shelfLifeGuarantee"), "maxDaysSinceManufacture"),
              text(p, "internalName"),
              strings(s.path("barcodes")),
              "INACTIVE".equals(text(s, "state")) ? SkuState.INACTIVE : SkuState.ACTIVE,
              version,
              at));
    }
  }

  private static @Nullable String text(JsonNode n, String field) {
    return n.hasNonNull(field) ? n.get(field).asString() : null;
  }

  private static java.util.List<String> strings(JsonNode arr) {
    java.util.List<String> out = new java.util.ArrayList<>();
    for (JsonNode b : arr) {
      String v = b.asString("");
      if (!v.isBlank()) {
        out.add(v);
      }
    }
    return out;
  }

  private static @Nullable Integer integer(JsonNode n, String field) {
    return n.hasNonNull(field) ? n.get(field).asInt() : null;
  }
}
