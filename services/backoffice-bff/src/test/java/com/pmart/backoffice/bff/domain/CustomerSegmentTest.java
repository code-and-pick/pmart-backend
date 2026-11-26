package com.pmart.backoffice.bff.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** 고객 분류(사용자 결정 2026-11-23·2026-11-26) — 한국 날짜, 휴면 → 위험 → 우수 → 신규 → 일반. */
class CustomerSegmentTest {

  static final LocalDate TODAY = LocalDate.of(2026, 11, 26);

  /** 한국 날짜 정오. */
  static Instant kst(int y, int m, int d) {
    return LocalDate.of(y, m, d).atTime(12, 0).atZone(CustomerSegment.SEOUL).toInstant();
  }

  @Test
  void lastOrderBeforeOneYearIsDormant() {
    assertThat(CustomerSegment.of(TODAY, kst(2020, 1, 1), kst(2025, 11, 25), 0))
        .isEqualTo(CustomerSegment.DORMANT);
    // 정확히 1년 전 날은 아직 위험(1년"보다 전"만 휴면)
    assertThat(CustomerSegment.of(TODAY, kst(2020, 1, 1), kst(2025, 11, 26), 0))
        .isEqualTo(CustomerSegment.AT_RISK);
  }

  @Test
  void lastOrderBeforeSixMonthsIsAtRisk() {
    assertThat(CustomerSegment.of(TODAY, kst(2020, 1, 1), kst(2026, 5, 25), 0))
        .isEqualTo(CustomerSegment.AT_RISK);
    assertThat(CustomerSegment.of(TODAY, kst(2020, 1, 1), kst(2026, 5, 26), 0))
        .isEqualTo(CustomerSegment.REGULAR);
  }

  @Test
  void neverBoughtIsJudgedByJoinDate() {
    assertThat(CustomerSegment.of(TODAY, kst(2025, 11, 1), null, 0))
        .isEqualTo(CustomerSegment.DORMANT);
    assertThat(CustomerSegment.of(TODAY, kst(2026, 3, 1), null, 0))
        .isEqualTo(CustomerSegment.AT_RISK);
    assertThat(CustomerSegment.of(TODAY, kst(2026, 9, 1), null, 0))
        .isEqualTo(CustomerSegment.REGULAR);
    assertThat(CustomerSegment.of(TODAY, kst(2026, 11, 20), null, 0))
        .isEqualTo(CustomerSegment.NEW);
    // 가입일도 주문도 모르면 판정하지 않는다
    assertThat(CustomerSegment.of(TODAY, null, null, 0)).isNull();
  }

  @Test
  void topWinsOverNew() {
    assertThat(CustomerSegment.of(TODAY, kst(2026, 11, 10), kst(2026, 11, 20), 1_000_000))
        .isEqualTo(CustomerSegment.TOP);
    assertThat(CustomerSegment.of(TODAY, kst(2026, 11, 10), kst(2026, 11, 20), 999_999))
        .isEqualTo(CustomerSegment.NEW);
  }

  @Test
  void newIsWithinOneMonthOfJoining() {
    assertThat(CustomerSegment.of(TODAY, kst(2026, 10, 26), kst(2026, 11, 1), 0))
        .isEqualTo(CustomerSegment.NEW);
    assertThat(CustomerSegment.of(TODAY, kst(2026, 10, 25), kst(2026, 11, 1), 0))
        .isEqualTo(CustomerSegment.REGULAR);
    // 주문은 있는데 가입일을 모르면 신규 여부를 정할 수 없다
    assertThat(CustomerSegment.of(TODAY, null, kst(2026, 11, 1), 0)).isNull();
  }

  @Test
  void seoulDateDecidesTheBoundary() {
    // 2025-11-25 15:30 UTC = 한국 2025-11-26 00:30 → 정확히 1년 전 날이라 위험
    assertThat(CustomerSegment.of(TODAY, kst(2020, 1, 1), Instant.parse("2025-11-25T15:30:00Z"), 0))
        .isEqualTo(CustomerSegment.AT_RISK);
  }
}
