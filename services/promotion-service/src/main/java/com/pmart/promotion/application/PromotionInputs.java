package com.pmart.promotion.application;

import com.pmart.promotion.domain.PromotionRules;
import com.pmart.promotion.infrastructure.PromotionStore.PromotionRow;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** 저장된 행 → 도메인 입력(검사·계산용). */
final class PromotionInputs {

  private PromotionInputs() {}

  static PromotionRules.Discount discount(PromotionRow r) {
    return new PromotionRules.Discount(r.discountType(), r.discountValue(), r.discountMax());
  }

  static PromotionRules.@Nullable CouponPolicy coupon(PromotionRow r) {
    return coupon(r, null);
  }

  static PromotionRules.@Nullable CouponPolicy coupon(
      PromotionRow r, PromotionRules.@Nullable Recipients recipients) {
    return r.issueMode() == null
        ? null
        : new PromotionRules.CouponPolicy(
            r.issueMode(),
            r.minOrderAmount() == null ? 0 : r.minOrderAmount(),
            r.totalQuantity(),
            r.perCustomerLimit() == null ? 1 : r.perCustomerLimit(),
            r.useDays(),
            r.useUntil(),
            recipients);
  }

  static PromotionRules.Input fromRow(PromotionRow r, List<String[]> targets) {
    return fromRow(r, targets, null);
  }

  static PromotionRules.Input fromRow(
      PromotionRow r, List<String[]> targets, PromotionRules.@Nullable Recipients recipients) {
    return new PromotionRules.Input(
        r.kind(),
        r.name(),
        r.startsAt(),
        r.endsAt(),
        discount(r),
        targets.stream().map(t -> new PromotionRules.Target(t[0], t[1])).toList(),
        coupon(r, recipients));
  }
}
