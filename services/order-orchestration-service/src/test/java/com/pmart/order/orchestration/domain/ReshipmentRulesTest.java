package com.pmart.order.orchestration.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReshipmentRulesTest {

  /** 사고·파손 실패만, 일반·오배송 재발송 배송만(2026-11-18). */
  @Test
  void reshipsOnlyIncidentOrDamageOfForwardOrReshipment() {
    assertThat(ReshipmentRules.reshipsOnFailure("FORWARD", "", "RIDER_INCIDENT")).isTrue();
    assertThat(ReshipmentRules.reshipsOnFailure("FORWARD", "", "DAMAGED")).isTrue();
    assertThat(
            ReshipmentRules.reshipsOnFailure(
                "RESHIPMENT", "MISDELIVERY_RESHIPMENT", "RIDER_INCIDENT"))
        .isTrue();
    assertThat(ReshipmentRules.reshipsOnFailure("FORWARD", "", "CUSTOMER_ABSENT")).isFalse();
    assertThat(ReshipmentRules.reshipsOnFailure("RETURN", "CLAIM_RETURN", "DAMAGED")).isFalse();
    assertThat(
            ReshipmentRules.reshipsOnFailure(
                "RESHIPMENT", "MISDELIVERY_REDELIVERY", "RIDER_INCIDENT"))
        .isFalse();
  }

  @Test
  void failedResendIsWhatHasNotReachedTheCustomer() {
    assertThat(ReshipmentRules.failedResendQty(2, 0, 0)).isEqualTo(2);
    assertThat(ReshipmentRules.failedResendQty(3, 1, 0)).isEqualTo(2);
    assertThat(ReshipmentRules.failedResendQty(2, 0, 2)).isZero();
    assertThat(ReshipmentRules.fromFailure(ReshipmentRules.failureRef("DLV-1"))).isTrue();
    assertThat(ReshipmentRules.fromFailure("CASE-1")).isFalse();
  }
}
