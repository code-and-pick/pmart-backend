package com.pmart.promotion.api;

import com.pmart.promotion.api.generated.CouponRecordsApi;
import com.pmart.promotion.api.generated.model.CouponRecord;
import com.pmart.promotion.api.generated.model.ListCouponRecords200Response;
import com.pmart.promotion.api.generated.model.ListPromotions200ResponsePage;
import com.pmart.promotion.application.CouponRecords;
import com.pmart.promotion.domain.CouponRecordRules;
import com.pmart.promotion.infrastructure.CouponRecordStore.RecordRow;
import java.time.Instant;
import java.util.ArrayList;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 CouponRecordsApi — 쿠폰 발급 기록(0.3.1 직원). 규칙은 {@link CouponRecords}·{@link CouponRecordRules}.
 */
@RestController
public class CouponRecordsController implements CouponRecordsApi {

  private final CouponRecords records;

  public CouponRecordsController(CouponRecords records) {
    this.records = records;
  }

  @Override
  public ResponseEntity<ListCouponRecords200Response> listCouponRecords(
      @Nullable String customerId,
      @Nullable String promotionId,
      @Nullable String status,
      @Nullable String source,
      Integer size,
      @Nullable String cursor) {
    CouponRecords.Page page =
        records.list(customerId, promotionId, status, source, size == null ? 20 : size, cursor);
    return ResponseEntity.ok(
        new ListCouponRecords200Response()
            .items(new ArrayList<>(page.items().stream().map(r -> record(r, page.asOf())).toList()))
            .page(
                new ListPromotions200ResponsePage()
                    .nextCursor(page.nextCursor())
                    .size(page.items().size())));
  }

  private static CouponRecord record(RecordRow r, Instant now) {
    String status = CouponRecordRules.status(r.state(), r.validUntil(), now);
    return new CouponRecord()
        .couponId(r.couponId())
        .promotionId(r.promotionId())
        .promotionName(r.promotionName())
        .customerId(r.accountId())
        .status(CouponRecord.StatusEnum.fromValue(status))
        .source(
            CouponRecord.SourceEnum.fromValue(
                CouponRecordRules.source(r.issueChannel(), r.issuedBy())))
        .issuedBy(CouponRecordRules.issuedBy(r.issueChannel(), r.issuedBy()))
        .issueReason(r.issueReason())
        .issuedAt(PromotionMapping.odt(r.issuedAt()))
        .validUntil(PromotionMapping.odt(r.validUntil()))
        .orderId("HELD".equals(status) || "USED".equals(status) ? r.orderId() : null)
        .usedAt("USED".equals(status) ? PromotionMapping.odt(r.usedAt()) : null)
        .discount(PromotionMapping.discount(r.discountType(), r.discountValue(), r.discountMax()));
  }
}
