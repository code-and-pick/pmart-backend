package com.pmart.promotion.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class CouponRecordRulesTest {

  static final Instant NOW = Instant.parse("2026-11-24T03:00:00Z");

  @Test
  void statusShowsHeldSeparatelyAndExpiredAfterValidUntil() {
    assertThat(CouponRecordRules.status("HELD", NOW.minusSeconds(60), NOW)).isEqualTo("HELD");
    assertThat(CouponRecordRules.status("USED", NOW.minusSeconds(60), NOW)).isEqualTo("USED");
    assertThat(CouponRecordRules.status("AVAILABLE", NOW.plusSeconds(60), NOW))
        .isEqualTo("AVAILABLE");
    assertThat(CouponRecordRules.status("AVAILABLE", NOW.minusSeconds(1), NOW))
        .isEqualTo("EXPIRED");
  }

  @Test
  void sourceSplitsStaffGrantFromServerCompensation() {
    assertThat(CouponRecordRules.source("DOWNLOAD", null)).isEqualTo("DOWNLOAD");
    assertThat(CouponRecordRules.source("SIGNUP", "system")).isEqualTo("SIGNUP");
    assertThat(CouponRecordRules.source("ADMIN", "cs-1")).isEqualTo("STAFF");
    assertThat(CouponRecordRules.source("ADMIN", "system")).isEqualTo("COMPENSATION");
    assertThat(CouponRecordRules.issuedBy("ADMIN", "cs-1")).isEqualTo("cs-1");
    assertThat(CouponRecordRules.issuedBy("ADMIN", "system")).isNull();
    assertThat(CouponRecordRules.issuedBy("SIGNUP", "system")).isNull();
  }
}
