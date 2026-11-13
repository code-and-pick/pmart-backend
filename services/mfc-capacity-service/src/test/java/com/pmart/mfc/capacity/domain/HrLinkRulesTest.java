package com.pmart.mfc.capacity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HrLinkRulesTest {

  @Test
  void onlyMfcCenters() {
    assertThat(HrLinkRules.isMfc("MFC-SEED-01")).isTrue();
    assertThat(HrLinkRules.isMfc("CDC-01")).isFalse();
    assertThat(HrLinkRules.isMfc(null)).isFalse();
  }

  @Test
  void regularAndPartTimeStartWithoutQualification() {
    assertThat(HrLinkRules.initialQualification("REGULAR")).isEmpty();
    assertThat(HrLinkRules.initialQualification("PART_TIME")).isEmpty();
    assertThat(HrLinkRules.initialQualification("DAILY"))
        .isEqualTo(WorkerRules.DAILY_DEFAULT_QUALIFICATION);
  }

  @Test
  void desiredStateFromHrAndGrant() {
    assertThat(HrLinkRules.desiredState("ACTIVE", true, true)).isEqualTo("ACTIVE");
    assertThat(HrLinkRules.desiredState("ACTIVE", true, false))
        .as("권한 기간 밖(시작 전·끝 — 센터 이동 뒤 예전 센터)")
        .isEqualTo("SUSPENDED");
    assertThat(HrLinkRules.desiredState("ACTIVE", false, false))
        .as("센터 관리자가 직접 등록한 작업자 — 권한 기록 없음")
        .isEqualTo("ACTIVE");
    assertThat(HrLinkRules.desiredState(null, false, false)).isEqualTo("ACTIVE");
    assertThat(HrLinkRules.desiredState("SUSPENDED", true, true)).isEqualTo("SUSPENDED");
    assertThat(HrLinkRules.desiredState("LEFT", true, true)).isEqualTo("LEFT");
  }

  @Test
  void goingDownAlwaysApplies() {
    assertThat(HrLinkRules.transition("ACTIVE", "SUSPENDED", "sup-1")).isEqualTo("SUSPENDED");
    assertThat(HrLinkRules.transition("SUSPENDED", "LEFT", "sup-1")).isEqualTo("LEFT");
    assertThat(HrLinkRules.transition("SUSPENDED", "SUSPENDED", "sup-1")).isNull();
  }

  @Test
  void goingUpOnlyUndoesAutomaticSuspension() {
    assertThat(HrLinkRules.transition("SUSPENDED", "ACTIVE", HrLinkRules.HR_ACTOR))
        .isEqualTo("ACTIVE");
    assertThat(HrLinkRules.transition("SUSPENDED", "ACTIVE", HrLinkRules.GRANT_ACTOR))
        .isEqualTo("ACTIVE");
    assertThat(HrLinkRules.transition("SUSPENDED", "ACTIVE", "sup-1"))
        .as("센터 관리자가 멈춘 작업자는 그대로")
        .isNull();
  }

  @Test
  void leftIsFinal() {
    assertThat(HrLinkRules.transition("LEFT", "ACTIVE", HrLinkRules.HR_ACTOR)).isNull();
    assertThat(HrLinkRules.transition("LEFT", "SUSPENDED", HrLinkRules.HR_ACTOR)).isNull();
  }
}
