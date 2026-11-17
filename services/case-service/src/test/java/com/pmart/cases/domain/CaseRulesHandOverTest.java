package com.pmart.cases.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class CaseRulesHandOverTest {

  @Test
  void deliveryTeamIsRiderOpsOrAdminAndOrderTeamIsAdminOnly() {
    assertThat(CaseRules.canHandOver(Set.of("RIDER_OPS"), "DELIVERY_OPS")).isTrue();
    assertThat(CaseRules.canHandOver(Set.of("OPERATOR_ADMIN"), "DELIVERY_OPS")).isTrue();
    assertThat(CaseRules.canHandOver(Set.of("OPERATOR_ADMIN"), "ORDER_OPS")).isTrue();
    assertThat(CaseRules.canHandOver(Set.of("RIDER_OPS"), "ORDER_OPS")).isFalse();
    assertThat(CaseRules.canHandOver(Set.of("CS_AGENT"), "DELIVERY_OPS")).isFalse();
    assertThat(CaseRules.canHandOver(Set.of("MFC_SUPERVISOR"), "DELIVERY_OPS")).isFalse();
    assertThat(CaseRules.canHandOver(Set.of(), "DELIVERY_OPS")).isFalse();
  }
}
