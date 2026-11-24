package com.pmart.storefront.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BannerImageSpecTest {

  @Test
  void wideBannerAllowsTenPercentAndMinimumSize() {
    BannerImageSpec wide = BannerImageSpec.of("HOME_TOP");
    assertThat(wide.problem(1960, 700)).isNull();
    assertThat(wide.problem(1080, 400)).as("2.7:1 — 안").isNull();
    assertThat(wide.problem(1984, 793)).as("2.5:1 — 지금 LAN 배너").isEqualTo("BANNER_IMAGE_RATIO");
    assertThat(wide.problem(1000, 357)).isEqualTo("BANNER_IMAGE_TOO_SMALL");
    assertThat(BannerImageSpec.of("CATEGORY")).isEqualTo(wide);
  }

  @Test
  void popupAndSplash() {
    assertThat(BannerImageSpec.of("POPUP").problem(1080, 1350)).isNull();
    assertThat(BannerImageSpec.of("POPUP").problem(2000, 1000)).isEqualTo("BANNER_IMAGE_RATIO");
    assertThat(BannerImageSpec.of("SPLASH").problem(1080, 1920)).isNull();
    assertThat(BannerImageSpec.of("SPLASH").problem(1080, 2340))
        .as("9:19.5 — 밖")
        .isEqualTo("BANNER_IMAGE_RATIO");
    assertThat(BannerImageSpec.of("SPLASH").problem(540, 960)).isEqualTo("BANNER_IMAGE_TOO_SMALL");
    assertThat(BannerImageSpec.of("UNKNOWN")).isNull();
  }
}
