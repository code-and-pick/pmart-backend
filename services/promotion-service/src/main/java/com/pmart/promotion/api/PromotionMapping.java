package com.pmart.promotion.api;

import com.pmart.promotion.api.generated.model.Coupon;
import com.pmart.promotion.api.generated.model.CouponCampaign;
import com.pmart.promotion.api.generated.model.CouponHold;
import com.pmart.promotion.api.generated.model.CouponPolicy;
import com.pmart.promotion.api.generated.model.CouponPolicyRecipients;
import com.pmart.promotion.api.generated.model.Discount;
import com.pmart.promotion.api.generated.model.Problem;
import com.pmart.promotion.api.generated.model.Promotion;
import com.pmart.promotion.api.generated.model.PromotionInput;
import com.pmart.promotion.api.generated.model.PromotionKind;
import com.pmart.promotion.api.generated.model.PromotionPhase;
import com.pmart.promotion.api.generated.model.PromotionRecipientResultsInner;
import com.pmart.promotion.api.generated.model.PromotionState;
import com.pmart.promotion.api.generated.model.PromotionTarget;
import com.pmart.promotion.application.CouponService;
import com.pmart.promotion.application.PromotionService;
import com.pmart.promotion.domain.PromotionRules;
import com.pmart.promotion.infrastructure.PromotionStore.HoldRow;
import com.pmart.promotion.infrastructure.PromotionStore.PromotionRow;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** 계약 DTO ↔ 도메인. 업무 판단은 하지 않는다. */
final class PromotionMapping {

  private PromotionMapping() {}

  static PromotionRules.Input input(PromotionInput in) {
    Discount d = in.getDiscount();
    CouponPolicy c = in.getCoupon();
    List<PromotionTarget> targets = in.getTargets() == null ? List.of() : in.getTargets();
    return new PromotionRules.Input(
        in.getKind().getValue(),
        in.getName(),
        in.getStartsAt().toInstant(),
        in.getEndsAt().toInstant(),
        new PromotionRules.Discount(d.getType().getValue(), d.getValue(), d.getMaxAmount()),
        targets.stream()
            .map(t -> new PromotionRules.Target(t.getType().getValue(), t.getId()))
            .toList(),
        c == null
            ? null
            : new PromotionRules.CouponPolicy(
                c.getIssueMode().getValue(),
                c.getMinOrderAmount(),
                c.getTotalQuantity(),
                c.getPerCustomerLimit(),
                c.getUseDays(),
                c.getUseUntil() == null ? null : c.getUseUntil().toInstant(),
                recipients(c.getRecipients())));
  }

  /** 0.3.0 받을 고객 — 순서 그대로(겹침·한도는 검사가 problems 로). */
  private static PromotionRules.@Nullable Recipients recipients(
      @Nullable CouponPolicyRecipients r) {
    return r == null
        ? null
        : new PromotionRules.Recipients(
            r.getAccountIds() == null ? List.of() : r.getAccountIds(), r.getReason());
  }

  static Discount discount(String type, int value, @Nullable Integer max) {
    return new Discount().type(Discount.TypeEnum.fromValue(type)).value(value).maxAmount(max);
  }

  static Promotion promotion(PromotionService.View v) {
    PromotionRow r = v.row();
    Promotion out =
        new Promotion()
            .promotionId(r.promotionId())
            .kind(PromotionKind.fromValue(r.kind()))
            .name(r.name())
            .state(PromotionState.fromValue(r.state()))
            .phase(PromotionPhase.fromValue(v.phase()))
            .startsAt(odt(r.startsAt()))
            .endsAt(odt(r.endsAt()))
            .endedAt(odt(r.endedAt()))
            .discount(discount(r.discountType(), r.discountValue(), r.discountMax()))
            .targets(
                new java.util.ArrayList<>(
                    v.targets().stream()
                        .map(
                            t ->
                                new PromotionTarget()
                                    .type(PromotionTarget.TypeEnum.fromValue(t[0]))
                                    .id(t[1])
                                    .title("THEME".equals(t[0]) ? v.themeTitles().get(t[1]) : null))
                        .toList()))
            .issuedCount(r.issuedCount())
            .usedCount(r.usedCount())
            .recipientResults(
                new java.util.ArrayList<>(
                    v.recipientResults().stream()
                        .map(
                            x ->
                                new PromotionRecipientResultsInner()
                                    .accountId(x.accountId())
                                    .result(
                                        PromotionRecipientResultsInner.ResultEnum.fromValue(
                                            x.result() == null ? "PENDING" : x.result()))
                                    .couponId(x.couponId())
                                    .processedAt(odt(x.processedAt())))
                        .toList()))
            .problems(
                new java.util.ArrayList<>(
                    v.problems().stream()
                        .map(p -> new Problem().field(p.field()).reason(p.reason()).code(p.code()))
                        .toList()))
            .approvalId(r.approvalId())
            .version(r.version())
            .createdBy(r.createdBy())
            .updatedAt(odt(r.updatedAt()));
    if (r.issueMode() != null) {
      out.coupon(
          new CouponPolicy()
              .issueMode(CouponPolicy.IssueModeEnum.fromValue(r.issueMode()))
              .minOrderAmount(r.minOrderAmount() == null ? 0 : Math.toIntExact(r.minOrderAmount()))
              .totalQuantity(r.totalQuantity())
              .perCustomerLimit(r.perCustomerLimit() == null ? 1 : r.perCustomerLimit())
              .useDays(r.useDays())
              .useUntil(odt(r.useUntil()))
              .recipients(
                  v.recipients() == null
                      ? null
                      : new CouponPolicyRecipients()
                          .accountIds(new java.util.ArrayList<>(v.recipients().accountIds()))
                          .reason(v.recipients().reason())));
    }
    return out;
  }

  static CouponCampaign campaign(CouponService.Campaign c) {
    PromotionRow p = c.promotion();
    return new CouponCampaign()
        .promotionId(p.promotionId())
        .name(p.name())
        .discount(discount(p.discountType(), p.discountValue(), p.discountMax()))
        .minOrderAmount(p.minOrderAmount() == null ? 0 : Math.toIntExact(p.minOrderAmount()))
        .perCustomerLimit(p.perCustomerLimit() == null ? 1 : p.perCustomerLimit())
        .myIssuedCount(c.myIssuedCount())
        .remaining(
            p.totalQuantity() == null ? null : Math.max(0, p.totalQuantity() - p.issuedCount()))
        .endsAt(odt(p.endsAt()))
        .useDays(p.useDays())
        .useUntil(odt(p.useUntil()));
  }

  static Coupon coupon(CouponService.CouponView v) {
    PromotionRow p = v.promotion();
    return new Coupon()
        .couponId(v.coupon().couponId())
        .promotionId(p.promotionId())
        .name(p.name())
        .discount(discount(p.discountType(), p.discountValue(), p.discountMax()))
        .minOrderAmount(p.minOrderAmount() == null ? 0 : Math.toIntExact(p.minOrderAmount()))
        .status(Coupon.StatusEnum.fromValue(v.status()))
        .issuedAt(odt(v.coupon().issuedAt()))
        .validUntil(odt(v.coupon().validUntil()))
        .usedOrderId("USED".equals(v.status()) ? v.coupon().orderId() : null)
        // 0.2.1 BR-85 — 사용 확정 시각(주문 확정 때 coupon.used_at), 돌아온 쿠폰은 null
        .usedAt(
            "USED".equals(v.status()) && v.coupon().usedAt() != null
                ? odt(v.coupon().usedAt())
                : null);
  }

  static CouponHold hold(HoldRow h) {
    return new CouponHold()
        .holdId(h.holdId())
        .couponId(h.couponId())
        .orderId(h.orderId())
        .state(CouponHold.StateEnum.fromValue(h.state()))
        .discountAmount(Math.toIntExact(h.discountAmount()))
        .couponUsable(h.couponUsable());
  }

  static @Nullable OffsetDateTime odt(@Nullable Instant i) {
    return i == null ? null : i.atOffset(ZoneOffset.UTC);
  }
}
