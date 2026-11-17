package com.pmart.delivery.tracking.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProgressRulesTest {

  @Test
  void movesForwardOnly() {
    var pickup = ProgressRules.target("PickupCompleted", false);
    var atStore = ProgressRules.target("RiderArrivedAtStore", false);
    assertThat(ProgressRules.shouldMove("TO_STORE", pickup)).isTrue();
    assertThat(ProgressRules.shouldMove("TO_CUSTOMER", atStore)).isFalse(); // 늦게 온 가게 도착
    assertThat(ProgressRules.shouldMove(null, atStore)).isTrue();
  }

  @Test
  void releaseAndReassignBeforePickupOnly() {
    var released = ProgressRules.target("DeliveryAssignmentChanged", true);
    var reassigned = ProgressRules.target("DeliveryAssignmentChanged", false);
    assertThat(released.stage()).isEqualTo("UNASSIGNED");
    assertThat(ProgressRules.shouldMove("AT_STORE", released)).isTrue();
    assertThat(ProgressRules.shouldMove("UNASSIGNED", reassigned)).isTrue();
    assertThat(ProgressRules.shouldMove("TO_CUSTOMER", released)).isFalse();
  }

  @Test
  void doneIsFinal() {
    var completed = ProgressRules.target("DeliveryCompleted", false);
    assertThat(completed.outcome()).isEqualTo("COMPLETED");
    assertThat(ProgressRules.target("DeliveryCancelledBeforePickup", false).outcome())
        .isEqualTo("CANCELLED");
    assertThat(ProgressRules.shouldMove("AT_CUSTOMER", completed)).isTrue();
    assertThat(ProgressRules.shouldMove("DONE", ProgressRules.target("DeliveryAssigned", false)))
        .isFalse();
    assertThat(ProgressRules.target("DeliveryEtaUpdated", false)).isNull();
  }
}
