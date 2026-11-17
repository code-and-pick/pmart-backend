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
    // 고객 반품 회수 실패(CL-1, 2026-11-17) — 받기 전(TO_CUSTOMER 아님)에도 끝
    var returnFailed = ProgressRules.target("ReturnPickupFailed", false);
    assertThat(returnFailed.stage()).isEqualTo("DONE");
    assertThat(returnFailed.outcome()).isEqualTo("FAILED");
    assertThat(ProgressRules.shouldMove("TO_STORE", returnFailed)).isTrue();
  }

  /** 운영자 상태 정정(2026-11-17) — 정정한 수행 상태를 단계로(뒤로도). 정정할 수 없는 상태는 null. */
  @org.junit.jupiter.api.Test
  void correctedStateMapsToStage() {
    assertThat(ProgressRules.stageOfState("ARRIVED_AT_CUSTOMER")).isEqualTo("AT_CUSTOMER");
    assertThat(ProgressRules.stageOfState("PICKED_UP")).isEqualTo("TO_CUSTOMER");
    assertThat(ProgressRules.stageOfState("ARRIVED_AT_STORE")).isEqualTo("AT_STORE");
    assertThat(ProgressRules.stageOfState("ASSIGNED")).isEqualTo("TO_STORE");
    assertThat(ProgressRules.stageOfState("COMPLETED")).isNull();
  }
}
