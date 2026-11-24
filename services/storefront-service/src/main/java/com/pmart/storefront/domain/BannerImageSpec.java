package com.pmart.storefront.domain;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * 배너 자리별 그림 규격(0.15.0 — 2026-11-24 사용자 "사진이 들어가는 곳 옆에 사진 규격 필요"·"가로 세로 크기나 비율이 필요한건데"·"저 대안대로 작업").
 * 고객 앱이 그리는 비율 그대로: 홈·카테고리 배너 = 화면 폭 ÷ 2.8 높이(2.8:1), 팝업 4:5, 로딩 화면(SPLASH) 화면 가득 9:16. 앱은 칸을 꽉 채우고
 * 넘치는 곳을 자르므로(BoxFit.cover) 비율이 다르면 가장자리가 잘린다 — 편성 제출 때 비율 ±10%·최소 크기를 막는다.
 *
 * @param ratio 가로 ÷ 세로
 */
public record BannerImageSpec(
    double ratio,
    String ratioLabel,
    int recommendedWidth,
    int recommendedHeight,
    int minWidth,
    int minHeight) {

  /** 비율 허용 범위(±10%). */
  public static final double RATIO_TOLERANCE = 0.10;

  private static final BannerImageSpec WIDE =
      new BannerImageSpec(2.8, "2.8:1", 1960, 700, 1080, 386);
  private static final BannerImageSpec POPUP =
      new BannerImageSpec(0.8, "4:5", 1080, 1350, 720, 900);
  private static final BannerImageSpec SPLASH =
      new BannerImageSpec(0.5625, "9:16", 1080, 1920, 720, 1280);

  /** 계약 순서(화면 표시용). */
  public static final java.util.List<String> ORDER =
      java.util.List.of("HOME_TOP", "HOME_MIDDLE", "HOME_INLINE", "CATEGORY", "POPUP", "SPLASH");

  public static final Map<String, BannerImageSpec> BY_PLACEMENT =
      Map.of(
          "HOME_TOP", WIDE,
          "HOME_MIDDLE", WIDE,
          "HOME_INLINE", WIDE,
          "CATEGORY", WIDE,
          "POPUP", POPUP,
          "SPLASH", SPLASH);

  public static @Nullable BannerImageSpec of(String placement) {
    return BY_PLACEMENT.get(placement);
  }

  /** 맞으면 null, 아니면 문제 코드(BANNER_IMAGE_TOO_SMALL·BANNER_IMAGE_RATIO). */
  public @Nullable String problem(int width, int height) {
    if (width < minWidth || height < minHeight) {
      return "BANNER_IMAGE_TOO_SMALL";
    }
    double actual = (double) width / height;
    return Math.abs(actual / ratio - 1) > RATIO_TOLERANCE ? "BANNER_IMAGE_RATIO" : null;
  }
}
