package com.pmart.storefront.api;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.storefront.api.generated.LayoutsApi;
import com.pmart.storefront.api.generated.model.BannerImage;
import com.pmart.storefront.api.generated.model.LayoutBanner;
import com.pmart.storefront.api.generated.model.LayoutDraft;
import com.pmart.storefront.api.generated.model.LayoutDraftBody;
import com.pmart.storefront.api.generated.model.LayoutDraftSubmission;
import com.pmart.storefront.api.generated.model.LayoutTheme;
import com.pmart.storefront.api.generated.model.ListLayoutDrafts200Response;
import com.pmart.storefront.application.LayoutService;
import com.pmart.storefront.domain.LayoutRules;
import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 계약 LayoutsApi — 진열 편성 초안·제출·배너 이미지(0.7.0, ADR-0075 BO-05). 규칙은 application/domain, 여기는 변환만. 게시는
 * 승인 사건으로(LayoutPublisher) — {@code publishLayout} 은 쓰지 않아 인터페이스 기본 501.
 */
@RestController
public class LayoutsController implements LayoutsApi {

  private final LayoutService layouts;

  /** 배너 자리별 그림 규격(0.15.0) — 편성 제출 검사와 같은 값. 직원 토큰이면 누구나. */
  @Override
  public org.springframework.http.ResponseEntity<
          com.pmart.storefront.api.generated.model.ListBannerImageSpecs200Response>
      listBannerImageSpecs() {
    var items =
        new java.util.ArrayList<
            com.pmart.storefront.api.generated.model.ListBannerImageSpecs200ResponseItemsInner>();
    for (String p : com.pmart.storefront.domain.BannerImageSpec.ORDER) {
      var x = com.pmart.storefront.domain.BannerImageSpec.BY_PLACEMENT.get(p);
      items.add(
          new com.pmart.storefront.api.generated.model.ListBannerImageSpecs200ResponseItemsInner()
              .placement(
                  com.pmart.storefront.api.generated.model.ListBannerImageSpecs200ResponseItemsInner
                      .PlacementEnum.fromValue(p))
              .ratio(java.math.BigDecimal.valueOf(x.ratio()))
              .ratioLabel(x.ratioLabel())
              .recommendedWidth(x.recommendedWidth())
              .recommendedHeight(x.recommendedHeight())
              .minWidth(x.minWidth())
              .minHeight(x.minHeight()));
    }
    return org.springframework.http.ResponseEntity.ok(
        new com.pmart.storefront.api.generated.model.ListBannerImageSpecs200Response()
            .ratioTolerance(
                java.math.BigDecimal.valueOf(
                    com.pmart.storefront.domain.BannerImageSpec.RATIO_TOLERANCE))
            .maxBytes(com.pmart.storefront.domain.LayoutRules.MAX_IMAGE_BYTES)
            .contentTypes(
                new java.util.ArrayList<>(
                    java.util.List.of("image/jpeg", "image/png", "image/webp")))
            .items(items));
  }

  public LayoutsController(LayoutService layouts) {
    this.layouts = layouts;
  }

  @Override
  public ResponseEntity<LayoutDraft> createLayoutDraft(
      String idempotencyKey, String xSourceSystem, LayoutDraftBody body, Boolean fromCurrent) {
    var d =
        layouts.create(
            idempotencyKey, xSourceSystem, domain(body), Boolean.TRUE.equals(fromCurrent));
    return ResponseEntity.status(HttpStatus.CREATED).body(view(d));
  }

  @Override
  public ResponseEntity<ListLayoutDrafts200Response> listLayoutDrafts(@Nullable String state) {
    return ResponseEntity.ok(
        new ListLayoutDrafts200Response()
            .items(layouts.list(state).stream().map(LayoutsController::view).toList())
            .currentLayoutVersion(layouts.currentVersion()));
  }

  @Override
  public ResponseEntity<LayoutDraft> getLayoutDraft(String draftId) {
    return ResponseEntity.ok(view(layouts.get(draftId)));
  }

  @Override
  public ResponseEntity<LayoutDraft> updateLayoutDraft(
      String draftId,
      String idempotencyKey,
      String xSourceSystem,
      LayoutDraftBody body,
      @Nullable Integer xExpectedVersion) {
    return ResponseEntity.ok(
        view(
            layouts.update(
                draftId, idempotencyKey, xSourceSystem, xExpectedVersion, domain(body))));
  }

  @Override
  public ResponseEntity<LayoutDraftSubmission> submitLayoutDraft(
      String draftId, String idempotencyKey, String xSourceSystem) {
    var s = layouts.submit(draftId, idempotencyKey, xSourceSystem);
    return ResponseEntity.ok(
        new LayoutDraftSubmission()
            .draftId(s.draftId())
            .draftHash(s.draftHash())
            .approvalRequest(s.approvalRequest()));
  }

  @Override
  public ResponseEntity<BannerImage> uploadBannerImage(
      String idempotencyKey, String xSourceSystem, MultipartFile file) {
    byte[] bytes;
    try {
      bytes = file.getBytes();
    } catch (IOException e) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "이미지를 읽을 수 없습니다.");
    }
    var r = layouts.uploadImage(idempotencyKey, xSourceSystem, bytes);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            new BannerImage()
                .imageId(r.imageId())
                .url("/api/v1/storefront/banner-images/" + r.imageId())
                .contentType(r.contentType())
                .width(r.width())
                .height(r.height()));
  }

  // ---------------------------------------------------------------- 변환

  static LayoutRules.Body domain(@Nullable LayoutDraftBody b) {
    if (b == null) {
      return LayoutRules.Body.empty();
    }
    List<LayoutRules.Banner> banners =
        b.getBanners() == null
            ? List.of()
            : b.getBanners().stream()
                .map(
                    x ->
                        new LayoutRules.Banner(
                            x.getBannerId(),
                            x.getPlacement().getValue(),
                            x.getImageId(),
                            x.getAltText(),
                            x.getLinkType().getValue(),
                            x.getLinkRef(),
                            x.getSortOrder() == null ? 0 : x.getSortOrder(),
                            instant(x.getStartsAt()),
                            instant(x.getEndsAt()),
                            x.getGroupId()))
                .toList();
    List<LayoutRules.Theme> themes =
        b.getThemes() == null
            ? List.of()
            : b.getThemes().stream()
                .map(
                    x ->
                        new LayoutRules.Theme(
                            x.getSectionId(),
                            x.getKind().getValue(),
                            x.getTitle(),
                            x.getSubtitle(),
                            x.getProductIds(),
                            x.getSortOrder() == null ? 0 : x.getSortOrder(),
                            instant(x.getStartsAt()),
                            instant(x.getEndsAt()),
                            Boolean.TRUE.equals(x.getShowTimer()),
                            x.getAutoSize(),
                            x.getTimerStyle() == null ? null : x.getTimerStyle().getValue()))
                .toList();
    List<LayoutRules.Tab> tabs =
        b.getTabs() == null
            ? List.of()
            : b.getTabs().stream()
                .map(
                    x ->
                        new LayoutRules.Tab(
                            x.getTabKey(),
                            x.getTitle(),
                            x.getSectionIds(),
                            x.getSortOrder() == null ? 0 : x.getSortOrder(),
                            instant(x.getStartsAt()),
                            instant(x.getEndsAt()),
                            Boolean.TRUE.equals(x.getShowTimer())))
                .toList();
    return new LayoutRules.Body(
        banners,
        themes,
        tabs,
        b.getNote(),
        b.getHomeOrder() == null ? List.of() : b.getHomeOrder(),
        b.getBannerIntervalSeconds());
  }

  static LayoutDraft view(LayoutService.Draft d) {
    var r = d.row();
    return new LayoutDraft()
        .draftId(r.draftId())
        .state(LayoutDraft.StateEnum.fromValue(r.state()))
        .version(r.version())
        .draftHash(r.draftHash())
        .approvalId(r.approvalId())
        .layoutVersion(r.layoutVersion())
        .rejectionReason(r.rejectionReason())
        .createdBy(r.createdBy())
        .createdAt(odt(r.createdAt()))
        .updatedAt(r.updatedAt() == null ? null : odt(r.updatedAt()))
        .note(d.body().note())
        .warnings(
            new java.util.ArrayList<>(
                d.warnings().stream()
                    .map(
                        w ->
                            new com.pmart.storefront.api.generated.model.LayoutDraftWarning()
                                .field(w.field())
                                .code(
                                    com.pmart.storefront.api.generated.model.LayoutDraftWarning
                                        .CodeEnum.fromValue(w.code()))
                                .detail(w.detail()))
                    .toList()))
        .banners(
            d.body().banners().stream()
                .map(
                    x ->
                        new LayoutBanner()
                            .bannerId(x.bannerId())
                            .placement(LayoutBanner.PlacementEnum.fromValue(x.placement()))
                            .imageId(x.imageId())
                            .altText(x.altText())
                            .linkType(LayoutBanner.LinkTypeEnum.fromValue(x.linkType()))
                            .linkRef(x.linkRef())
                            .sortOrder(x.sortOrder())
                            .startsAt(x.startsAt() == null ? null : odt(x.startsAt()))
                            .endsAt(x.endsAt() == null ? null : odt(x.endsAt()))
                            .groupId(x.groupId()))
                .toList())
        .homeOrder(d.body().homeOrder())
        .bannerIntervalSeconds(d.body().bannerIntervalSeconds())
        .themes(
            d.body().themes().stream()
                .map(
                    x ->
                        new LayoutTheme()
                            .sectionId(x.sectionId())
                            .kind(LayoutTheme.KindEnum.fromValue(x.kind()))
                            .title(x.title())
                            .subtitle(x.subtitle())
                            .productIds(x.productIds())
                            .sortOrder(x.sortOrder())
                            .startsAt(x.startsAt() == null ? null : odt(x.startsAt()))
                            .endsAt(x.endsAt() == null ? null : odt(x.endsAt()))
                            .showTimer(x.showTimer())
                            .timerStyle(
                                x.timerStyle() == null
                                    ? null
                                    : LayoutTheme.TimerStyleEnum.fromValue(x.timerStyle()))
                            .autoSize(x.autoSize()))
                .toList())
        .tabs(
            d.body().tabs().stream()
                .map(
                    x ->
                        new com.pmart.storefront.api.generated.model.LayoutTab()
                            .tabKey(x.tabKey())
                            .title(x.title())
                            .sectionIds(x.sectionIds())
                            .sortOrder(x.sortOrder())
                            .startsAt(x.startsAt() == null ? null : odt(x.startsAt()))
                            .endsAt(x.endsAt() == null ? null : odt(x.endsAt()))
                            .showTimer(x.showTimer()))
                .toList());
  }

  private static @Nullable Instant instant(@Nullable OffsetDateTime t) {
    return t == null ? null : t.toInstant();
  }

  private static OffsetDateTime odt(Instant t) {
    return t.atOffset(ZoneOffset.UTC);
  }
}
