package com.pmart.promotion.api;

import com.pmart.promotion.api.generated.PromotionsApi;
import com.pmart.promotion.api.generated.model.GrantCoupons200Response;
import com.pmart.promotion.api.generated.model.GrantCoupons200ResponseResultsInner;
import com.pmart.promotion.api.generated.model.GrantCouponsRequest;
import com.pmart.promotion.api.generated.model.ListPromotions200Response;
import com.pmart.promotion.api.generated.model.ListPromotions200ResponsePage;
import com.pmart.promotion.api.generated.model.Promotion;
import com.pmart.promotion.api.generated.model.PromotionInput;
import com.pmart.promotion.api.generated.model.PromotionKind;
import com.pmart.promotion.api.generated.model.PromotionState;
import com.pmart.promotion.api.generated.model.PromotionSubmission;
import com.pmart.promotion.api.generated.model.SubmitPromotionEndRequest;
import com.pmart.promotion.application.CouponService;
import com.pmart.promotion.application.PromotionService;
import java.util.ArrayList;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/** 계약 PromotionsApi — 행사 관리(직원, ADR-0082). */
@RestController
public class PromotionsController implements PromotionsApi {

  private final PromotionService promotions;
  private final CouponService coupons;
  private final ObjectMapper mapper;

  public PromotionsController(
      PromotionService promotions, CouponService coupons, ObjectMapper mapper) {
    this.promotions = promotions;
    this.coupons = coupons;
    this.mapper = mapper;
  }

  @Override
  public ResponseEntity<Promotion> createPromotion(
      String idempotencyKey, String xSourceSystem, PromotionInput body) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            PromotionMapping.promotion(
                promotions.create(
                    idempotencyKey,
                    xSourceSystem,
                    PromotionMapping.input(body),
                    mapper.writeValueAsString(body))));
  }

  @Override
  public ResponseEntity<Promotion> updatePromotion(
      String promotionId,
      String idempotencyKey,
      String xSourceSystem,
      PromotionInput body,
      @Nullable Integer xExpectedVersion) {
    return ResponseEntity.ok(
        PromotionMapping.promotion(
            promotions.update(
                promotionId,
                idempotencyKey,
                xSourceSystem,
                xExpectedVersion,
                PromotionMapping.input(body),
                mapper.writeValueAsString(body))));
  }

  /** 고를 수 있는 진열 테마(0.3.0) — promotion 이 받아 둔 사본. */
  @Override
  public ResponseEntity<
          com.pmart.promotion.api.generated.model.ListPromotionThemeTargets200Response>
      listPromotionThemeTargets() {
    var t = promotions.themeTargets();
    return ResponseEntity.ok(
        new com.pmart.promotion.api.generated.model.ListPromotionThemeTargets200Response()
            .layoutVersion(t.layoutVersion())
            .syncedAt(PromotionMapping.odt(t.syncedAt()))
            .themes(
                new java.util.ArrayList<>(
                    t.themes().stream()
                        .map(
                            x ->
                                new com.pmart.promotion.api.generated.model
                                        .ListPromotionThemeTargets200ResponseThemesInner()
                                    .sectionId(x.sectionId())
                                    .kind(
                                        com.pmart.promotion.api.generated.model
                                            .ListPromotionThemeTargets200ResponseThemesInner
                                            .KindEnum.fromValue(x.kind()))
                                    .title(x.title())
                                    .productCount(x.productCount())
                                    .selectable(
                                        !com.pmart.promotion.domain.PromotionRules.RANKING_AUTO
                                            .equals(x.kind())))
                        .toList())));
  }

  @Override
  public ResponseEntity<Promotion> getPromotion(String promotionId) {
    return ResponseEntity.ok(PromotionMapping.promotion(promotions.get(promotionId)));
  }

  @Override
  public ResponseEntity<ListPromotions200Response> listPromotions(
      @Nullable PromotionKind kind,
      @Nullable PromotionState state,
      Integer size,
      @Nullable String cursor) {
    PromotionService.Page page =
        promotions.list(
            kind == null ? null : kind.getValue(),
            state == null ? null : state.getValue(),
            size == null ? 50 : size,
            cursor);
    return ResponseEntity.ok(
        new ListPromotions200Response()
            .items(new ArrayList<>(page.items().stream().map(PromotionMapping::promotion).toList()))
            .page(
                new ListPromotions200ResponsePage()
                    .size(page.items().size())
                    .nextCursor(page.nextCursor())));
  }

  @Override
  public ResponseEntity<PromotionSubmission> submitPromotion(
      String promotionId, String idempotencyKey, String xSourceSystem) {
    return ResponseEntity.ok(
        submission(promotions.submit(promotionId, idempotencyKey, xSourceSystem)));
  }

  @Override
  public ResponseEntity<PromotionSubmission> submitPromotionEnd(
      String promotionId,
      String idempotencyKey,
      String xSourceSystem,
      SubmitPromotionEndRequest body) {
    return ResponseEntity.ok(
        submission(
            promotions.submitEnd(promotionId, idempotencyKey, xSourceSystem, body.getReason())));
  }

  @Override
  public ResponseEntity<GrantCoupons200Response> grantCoupons(
      String promotionId, String idempotencyKey, String xSourceSystem, GrantCouponsRequest body) {
    GrantCoupons200Response out = new GrantCoupons200Response().results(new ArrayList<>());
    coupons
        .grant(promotionId, body.getAccountIds(), body.getReason(), idempotencyKey, xSourceSystem)
        .forEach(
            r ->
                out.addResultsItem(
                    new GrantCoupons200ResponseResultsInner()
                        .accountId(r.accountId())
                        .result(
                            GrantCoupons200ResponseResultsInner.ResultEnum.fromValue(r.result()))
                        .couponId(r.couponId())));
    return ResponseEntity.ok(out);
  }

  private static PromotionSubmission submission(PromotionService.Submission s) {
    return new PromotionSubmission()
        .promotionId(s.promotionId())
        .draftHash(s.draftHash())
        .approvalRequest(s.approvalRequest());
  }
}
