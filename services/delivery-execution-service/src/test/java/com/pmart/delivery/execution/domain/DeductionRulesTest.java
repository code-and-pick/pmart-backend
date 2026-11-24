package com.pmart.delivery.execution.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 오배송 정산 차감(ADR-0086). */
class DeductionRulesTest {

  @Test
  void amountIsPaidMinusDeliveryTipNeverNegative() {
    assertThat(DeductionRules.amount(19_970, 4_000)).isEqualTo(15_970);
    assertThat(DeductionRules.amount(3_000, 4_000)).isZero();
  }

  @Test
  void allocateTakesOldestFirstWithinGross() {
    List<DeductionRules.Open> open =
        List.of(new DeductionRules.Open("A", 6_000), new DeductionRules.Open("B", 9_000));
    assertThat(DeductionRules.allocate(open, 10_000)).isEqualTo(Map.of("A", 6_000L, "B", 4_000L));
    assertThat(DeductionRules.allocate(open, 0)).isEmpty();
    assertThat(DeductionRules.allocate(open, 50_000)).isEqualTo(Map.of("A", 6_000L, "B", 9_000L));
  }

  @Test
  void expectedFillsEarlierBatchesFirst() {
    assertThat(DeductionRules.expected(15_970, 0, 10_000)).isEqualTo(10_000);
    assertThat(DeductionRules.expected(15_970, 10_000, 10_000)).isEqualTo(5_970);
    assertThat(DeductionRules.expected(15_970, 20_000, 10_000)).isZero();
  }
}
