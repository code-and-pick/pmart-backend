package com.pmart.delivery.execution.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EarningRulesTest {

  @Test
  void confirmedFiveHoursAfterCompletion() {
    assertThat(EarningRules.confirmAt(Instant.parse("2026-11-13T03:00:00Z"), Duration.ofHours(5)))
        .isEqualTo(Instant.parse("2026-11-13T08:00:00Z"));
  }

  @Test
  void payoutSkipsWeekendsAndConfiguredHolidays() {
    // 2026-11-13 금 → 월(16)·화(17)·수(18)
    assertThat(EarningRules.payoutDate(LocalDate.of(2026, 11, 13), 3, Set.of()))
        .isEqualTo(LocalDate.of(2026, 11, 18));
    // 화요일이 공휴일이면 하루 밀린다
    assertThat(
            EarningRules.payoutDate(
                LocalDate.of(2026, 11, 13), 3, Set.of(LocalDate.of(2026, 11, 17))))
        .isEqualTo(LocalDate.of(2026, 11, 19));
    // 월 → 화·수·목
    assertThat(EarningRules.payoutDate(LocalDate.of(2026, 11, 16), 3, Set.of()))
        .isEqualTo(LocalDate.of(2026, 11, 19));
  }

  @Test
  void customerReasonsNeedTwoContactsAndCustodyFollowsGoodsLocation() {
    assertThat(ExecutionRules.enoughContacts("CUSTOMER_ABSENT", 1)).isFalse();
    assertThat(ExecutionRules.enoughContacts("CUSTOMER_ABSENT", 2)).isTrue();
    assertThat(ExecutionRules.enoughContacts("DAMAGED", 0)).isTrue(); // 고객 사유가 아니면 연락 기록 없이도
    assertThat(ExecutionRules.custody(null, "RETURNED_TO_STORE")).isEqualTo("STORE");
    assertThat(ExecutionRules.custody(null, "WITH_RIDER")).isEqualTo("RIDER");
    assertThat(ExecutionRules.custody("STORE", "WITH_RIDER")).isEqualTo("STORE");
  }
}
