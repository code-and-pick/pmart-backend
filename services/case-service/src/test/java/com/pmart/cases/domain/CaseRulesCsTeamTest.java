package com.pmart.cases.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** R-8(2026-11-17 사용자 "상담원 팀"): 반품 회수 실패는 상담원 팀(CS) — 상담원이 보고 집고 닫는다, 지원센터에는 안 보인다. */
class CaseRulesCsTeamTest {

  static final String RR = CaseRules.RETURN_RECOVERY_FAILED;

  @Test
  void csAgentSeesAndWorksReturnRecoveryRiderOpsDoesNot() {
    assertThat(CaseRules.group(RR)).isEqualTo("CS");
    assertThat(CaseRules.visibleKinds(Set.of("CS_AGENT"))).contains(RR, "DISPATCH_FAILED");
    assertThat(CaseRules.visibleKinds(Set.of("RIDER_OPS"))).doesNotContain(RR);
    assertThat(CaseRules.visibleKinds(Set.of("RIDER_OPS", "CS_AGENT"))).contains(RR, "RIDER_HELP");
    assertThat(CaseRules.visibleKinds(Set.of("MFC_SUPERVISOR"))).doesNotContain(RR);
    assertThat(CaseRules.visibleKinds(Set.of("OPERATOR_ADMIN"))).contains(RR);

    assertThat(CaseRules.canWork(Set.of("CS_AGENT"), RR, "CS")).isTrue();
    assertThat(CaseRules.canWork(Set.of("RIDER_OPS"), RR, "CS")).isFalse();
    assertThat(CaseRules.canWork(Set.of("OPERATOR_ADMIN"), RR, "CS")).isTrue();
    assertThat(CaseRules.canWork(Set.of("CS_AGENT"), "DISPATCH_FAILED", "DELIVERY_OPS"))
        .as("상담원은 배송 쪽 사건을 여전히 보기만")
        .isFalse();
    assertThat(CaseRules.transferable(RR)).isFalse();
    assertThat(CaseRules.canHandOver(Set.of("CS_AGENT"), "CS")).as("교대 인계는 두 팀만").isFalse();
    assertThat(CaseRules.DEFAULT_SLA.get(RR)).hasHours(1);
  }

  @Test
  void obligationOwnerCsCanSeeReturnRecovery() {
    assertThat(ObligationRules.ownerCanSee("CS", RR)).isTrue();
    assertThat(ObligationRules.ownerCanSee("DELIVERY_OPS", RR)).isFalse();
    assertThat(ObligationRules.defaultOwner(CaseRules.group(RR))).isEqualTo("CS");
  }
}
