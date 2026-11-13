package com.pmart.mfc.inventory.domain;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * product-service SKU 의 조회 사본(SkuIssued·ProductVersionPublished 투영). 정본이 아니다. SkuIssued 만 받은 동안은
 * 단위·중량 등이 비어 있다(0 으로 채우지 않는다).
 *
 * <p>productName·barcodes: 현장 화면 표시용(관리용 명칭 internalName·활성 바코드, 0.15.0). 이 열 추가 전에 받은 상품은 다음 상품 버전
 * 발행까지 null·빈 목록.
 *
 * <p>shelfMinRemainingDays·shelfMaxDaysSinceManufacture: 상품별 소비기한 보장(ADR-0041, "주문일 기준 N일 이상
 * 남은"·"N일 이내 제조된"). null 이면 그 보장 없음.
 */
public record SkuProjection(
    String sku,
    @Nullable String productId,
    @Nullable String baseProductId,
    @Nullable String unitType,
    @Nullable Integer unitsPerSku,
    @Nullable Integer weightG,
    @Nullable Integer volumeMl,
    @Nullable String storageType,
    @Nullable Boolean alcohol,
    @Nullable Integer shelfMinRemainingDays,
    @Nullable Integer shelfMaxDaysSinceManufacture,
    @Nullable String productName,
    java.util.List<String> barcodes,
    SkuState state,
    int productVersion,
    Instant updatedAt) {

  public enum SkuState {
    ACTIVE,
    INACTIVE
  }

  /** SKU 문자열 접두사(E-/B-)가 단위 유형이다(Commerce 2.7). 형식이 아니면 null. */
  public static @Nullable String unitTypeOf(String sku) {
    if (sku.length() > 2
        && sku.charAt(1) == '-'
        && (sku.charAt(0) == 'E' || sku.charAt(0) == 'B')) {
      return String.valueOf(sku.charAt(0));
    }
    return null;
  }
}
