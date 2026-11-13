package com.pmart.mfc.capacity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HrLinkRulesTest {

  @Test
  void onlyMfcCenterAffiliationMakesWorker() {
    assertThat(HrLinkRules.mfcOf("CENTER", "MFC-SEED-01")).isEqualTo("MFC-SEED-01");
    assertThat(HrLinkRules.mfcOf("CENTER", "CDC-01")).isNull();
    assertThat(HrLinkRules.mfcOf("HQ_DEPARTMENT", "MFC-SEED-01")).isNull();
    assertThat(HrLinkRules.mfcOf(null, null)).isNull();
  }

  @Test
  void regularAndPartTimeStartWithoutQualification() {
    assertThat(HrLinkRules.initialQualification("REGULAR")).isEmpty();
    assertThat(HrLinkRules.initialQualification("PART_TIME")).isEmpty();
    assertThat(HrLinkRules.initialQualification("DAILY"))
        .isEqualTo(WorkerRules.DAILY_DEFAULT_QUALIFICATION);
  }

  @Test
  void suspendAndLeaveAlwaysApply() {
    assertThat(HrLinkRules.nextState("EmployeeSuspended", "ACTIVE", "sup-1"))
        .isEqualTo("SUSPENDED");
    assertThat(HrLinkRules.nextState("EmployeeLeft", "SUSPENDED", "sup-1")).isEqualTo("LEFT");
    assertThat(HrLinkRules.nextState("EmployeeSuspended", "SUSPENDED", "sup-1")).isNull();
  }

  @Test
  void reinstateOnlyUndoesHrSuspension() {
    assertThat(HrLinkRules.nextState("EmployeeReinstated", "SUSPENDED", HrLinkRules.HR_ACTOR))
        .isEqualTo("ACTIVE");
    assertThat(HrLinkRules.nextState("EmployeeReinstated", "SUSPENDED", "sup-1"))
        .as("센터 관리자가 멈춘 작업자는 그대로")
        .isNull();
    assertThat(HrLinkRules.nextState("EmployeeReinstated", "ACTIVE", HrLinkRules.HR_ACTOR))
        .isNull();
  }

  @Test
  void leftIsFinal() {
    assertThat(HrLinkRules.nextState("EmployeeReinstated", "LEFT", HrLinkRules.HR_ACTOR)).isNull();
    assertThat(HrLinkRules.nextState("EmployeeSuspended", "LEFT", HrLinkRules.HR_ACTOR)).isNull();
  }
}
