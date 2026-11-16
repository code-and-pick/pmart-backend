package com.pmart.cases.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** 의무 규칙(계약 0.8.0 — 2026-11-16 사용자 "고친 안으로 진행해 줘"). */
class ObligationRulesTest {

  @Test
  void onlyKindsWithAnOwningTeam() {
    assertThat(ObligationRules.KINDS)
        .hasSize(8)
        .contains("REFUND", "RETURN_PICKUP", "CUSTOMER_CONTACT")
        .doesNotContain("INVENTORY_RECOVERY", "DISPOSAL", "SUPPLIER_CLAIM");
  }

  @Test
  void ownerTeamMustSeeTheCase() {
    assertThat(ObligationRules.ownerCanSee("CS", "MISDELIVERY")).isTrue();
    assertThat(ObligationRules.ownerCanSee("CS", "RECONCILIATION"))
        .as("상담원은 사가·결제 사건을 못 봄")
        .isFalse();
    assertThat(ObligationRules.ownerCanSee("DELIVERY_OPS", "PAYMENT_UNKNOWN")).isFalse();
    assertThat(ObligationRules.ownerCanSee("ORDER_OPS", "PAYMENT_UNKNOWN")).isTrue();
    assertThat(ObligationRules.defaultOwner("DELIVERY_OPS")).isEqualTo("DELIVERY_OPS");
    assertThat(ObligationRules.defaultOwner(null)).isEqualTo("ORDER_OPS");
  }

  @Test
  void waiveAndFailAreAdminOnly() {
    Set<String> cs = Set.of("CS_AGENT");
    Set<String> ops = Set.of("RIDER_OPS");
    Set<String> admin = Set.of("OPERATOR_ADMIN");
    assertThat(ObligationRules.canResolve(cs, "CS", "FULFILLED")).isTrue();
    assertThat(ObligationRules.canResolve(cs, "CS", "ATTEMPTED")).isTrue();
    assertThat(ObligationRules.canResolve(cs, "CS", "WAIVED")).isFalse();
    assertThat(ObligationRules.canResolve(cs, "CS", "FAILED")).isFalse();
    assertThat(ObligationRules.canResolve(cs, "DELIVERY_OPS", "FULFILLED")).isFalse();
    assertThat(ObligationRules.canResolve(ops, "DELIVERY_OPS", "FULFILLED")).isTrue();
    assertThat(ObligationRules.canResolve(ops, "ORDER_OPS", "FULFILLED")).isFalse();
    assertThat(ObligationRules.canResolve(admin, "CS", "WAIVED")).isTrue();
  }

  @Test
  void resolvedWhenEveryPresentDimensionIsFilled() {
    assertThat(ObligationRules.stateAfter(2, null, 1, 0)).isEqualTo("PARTIALLY_RESOLVED");
    assertThat(ObligationRules.stateAfter(2, null, 2, 0)).isEqualTo("RESOLVED");
    assertThat(ObligationRules.stateAfter(2, 1000L, 2, 500)).isEqualTo("PARTIALLY_RESOLVED");
    assertThat(ObligationRules.stateAfter(null, null, 0, 0)).isEqualTo("RESOLVED");
  }
}
