package com.pmart.backoffice.bff.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 포장 인계 현황 단계 규칙(계약 0.11.0 — 사용자 2026-11-17 "BFF로 단계표시"). 규칙에 없는 조합은 null. */
class PackingHandoverStageTest {

  static String stage(String shipment, String pick, String pack) {
    return PackingHandoverView.stage(shipment, pick, pack);
  }

  @Test
  void shipmentStateDecidesLaterStages() {
    assertThat(stage("HANDED_OVER", "COMPLETED", "COMPLETED")).isEqualTo("HANDED_OVER");
    assertThat(stage("PARTIALLY_HANDED_OVER", "COMPLETED", "COMPLETED"))
        .isEqualTo("PARTIALLY_HANDED_OVER");
    assertThat(stage("READY", "COMPLETED", "COMPLETED")).isEqualTo("READY_FOR_HANDOVER");
  }

  @Test
  void preparingUsesPickAndPack() {
    assertThat(stage("PREPARING", null, null)).isEqualTo("REQUESTED");
    assertThat(stage("PREPARING", "WAITING", null)).isEqualTo("REQUESTED");
    assertThat(stage("PREPARING", "ASSIGNED", "WAITING")).isEqualTo("REQUESTED");
    assertThat(stage("PREPARING", "IN_PROGRESS", "WAITING")).isEqualTo("PICKING");
    assertThat(stage("PREPARING", "BLOCKED", null)).isEqualTo("PICKING");
    assertThat(stage("PREPARING", "COMPLETED", "IN_PROGRESS")).isEqualTo("PACKING");
  }

  @Test
  void combinationsOutsideTheRuleAreNull() {
    assertThat(stage("PREPARING", "COMPLETED", "WAITING")).as("피킹 끝·패킹 시작 전").isNull();
    assertThat(stage("PREPARING", "COMPLETED", "BLOCKED")).as("패킹 막힘").isNull();
    assertThat(stage("PREPARING", "COMPLETED", null)).isNull();
    assertThat(stage("CANCELLED", null, null)).isNull();
    assertThat(stage("CLOSED_PARTIAL", "COMPLETED", "COMPLETED")).isNull();
    assertThat(stage(null, null, null)).isNull();
  }
}
