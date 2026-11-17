package com.pmart.mfc.inventory.application;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.inventory.domain.Gs1;
import com.pmart.mfc.inventory.domain.SkuProjection;
import com.pmart.mfc.inventory.infrastructure.LotLabelStore;
import com.pmart.mfc.inventory.infrastructure.LotLabelStore.LotRow;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * LOT 라벨 인쇄·재출력·해석(0.20.0, 클라이언트 mfc-app L1~L6 — 사용자 "추천대로" 2026-11-17). LOT 기준은 바꾸지 않는다(납입일은 표시만).
 * 그 센터 운영자.
 *
 * <p>(0.21.0, IV-4 — 사용자 결정 GS1-128) 라벨 바코드 = GS1-128 {@code (01)GTIN(17)기한(10)lotNo}. 예전 QR 값
 * {@code LOT:{lotId}} 도 계속 준다·읽는다(이미 붙인 라벨).
 */
@Service
public class LotLabelService {

  public static final String PREFIX = "LOT:";
  public static final String GS1_MISMATCH = "GS1_MISMATCH";
  static final int MAX_ITEMS = 100;

  public record Label(LotRow lot, @Nullable SkuProjection sku) {
    public String value() {
      return PREFIX + lot.lotId();
    }

    /** GS1-128 괄호 꼴. 숫자 상품 바코드가 없으면 null. */
    public @Nullable String gs1() {
      return Gs1.humanReadable(gtin(sku), lot.lotNo(), lot.expiryDate());
    }
  }

  /** 라벨에 넣을 GTIN-14 — 상품 바코드 중 숫자 8·12·13·14자리 첫 값. */
  static @Nullable String gtin(@Nullable SkuProjection sku) {
    if (sku == null) {
      return null;
    }
    return sku.barcodes().stream()
        .map(Gs1::gtin14)
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(null);
  }

  private final LotLabelStore store;
  private final SkuProjector skus;

  public LotLabelService(LotLabelStore store, SkuProjector skus) {
    this.store = store;
    this.skus = skus;
  }

  @Transactional(readOnly = true)
  public Label get(String mfcId, String lotId) {
    Operator.requireCenter(mfcId);
    LotRow lot =
        store
            .find(mfcId, lotId)
            .orElseThrow(() -> PmartException.notFound("LOT_NOT_FOUND", "LOT 이 없습니다: " + lotId));
    return new Label(lot, skus.projections(List.of(lot.sku())).get(lot.sku()));
  }

  /**
   * 찍은 값으로 LOT 찾기(0.21.0): GS1-128(원문·괄호 꼴) 또는 예전 QR {@code LOT:{lotId}}. GS1 은 (10) LOT 번호로 찾고
   * (01)·(17) 이 그 LOT 과 다르면 422 GS1_MISMATCH.
   */
  @Transactional(readOnly = true)
  public Label resolve(String mfcId, String code) {
    Operator.requireCenter(mfcId);
    String c = code.strip();
    if (c.startsWith(PREFIX)) {
      return get(mfcId, c.substring(PREFIX.length()));
    }
    Gs1.Parsed g;
    try {
      g = Gs1.parse(c);
    } catch (Gs1.Invalid e) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, e.getMessage());
    }
    String lotNo = g.lot();
    if (lotNo == null) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "GS1 (10) LOT 번호가 없습니다.");
    }
    LotRow lot =
        store
            .findByLotNo(mfcId, lotNo)
            .orElseThrow(() -> PmartException.notFound("LOT_NOT_FOUND", "LOT 번호가 없습니다: " + lotNo));
    SkuProjection sku = skus.projections(List.of(lot.sku())).get(lot.sku());
    if (g.gtin() != null) {
      boolean same =
          sku != null && sku.barcodes().stream().map(Gs1::gtin14).anyMatch(g.gtin()::equals);
      if (!same) {
        throw mismatch(
            "(01) 상품 " + g.gtin() + " 이 LOT " + lotNo + " 의 상품(" + lot.sku() + ")과 다릅니다.");
      }
    }
    if (g.expiry() != null && !g.expiry().equals(lot.expiryDate())) {
      throw mismatch("(17) 소비기한 " + g.expiry() + " 이 LOT " + lotNo + " 의 기한과 다릅니다.");
    }
    return new Label(lot, sku);
  }

  private static PmartException mismatch(String message) {
    return new PmartException(
        GS1_MISMATCH, HttpStatus.UNPROCESSABLE_CONTENT, message, false, List.of());
  }

  @Transactional(readOnly = true)
  public List<Label> find(String mfcId, @Nullable String sku, @Nullable String locationCode) {
    Operator.requireCenter(mfcId);
    String s = blankToNull(sku);
    String loc = blankToNull(locationCode);
    if (s == null && loc == null) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "sku 나 locationCode 중 하나가 필요합니다.");
    }
    List<LotRow> lots = store.held(mfcId, s, loc, MAX_ITEMS);
    Map<String, SkuProjection> projections =
        skus.projections(lots.stream().map(LotRow::sku).distinct().toList());
    return lots.stream().map(l -> new Label(l, projections.get(l.sku()))).toList();
  }

  private static @Nullable String blankToNull(@Nullable String s) {
    return s == null || s.isBlank() ? null : s;
  }
}
