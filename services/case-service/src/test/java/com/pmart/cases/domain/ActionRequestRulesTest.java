package com.pmart.cases.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ActionRequestRulesTest {

  @Test
  void rejectsForceCommandsAndCompensationWithoutApproval() {
    assertThat(ActionRequestRules.rejection("REQUERY_RESULT", "requerySagaResult", null)).isNull();
    assertThat(ActionRequestRules.rejection("RESEND_UNDELIVERED", "forceSuccess", null))
        .isEqualTo("UNSUPPORTED_ACTION");
    assertThat(ActionRequestRules.rejection("RESEND_UNDELIVERED", "overwriteBalance", null))
        .isEqualTo("UNSUPPORTED_ACTION");
    assertThat(ActionRequestRules.rejection("EXECUTE_APPROVED_COMPENSATION", "refund", " "))
        .isEqualTo("APPROVAL_REQUIRED");
    assertThat(ActionRequestRules.rejection("EXECUTE_APPROVED_COMPENSATION", "refund", "APR-1"))
        .isNull();
    assertThat(ActionRequestRules.rejection("DELETE_ORDER", "x", null))
        .isEqualTo("UNSUPPORTED_ACTION");
  }

  @Test
  void finalStatesStopResults() {
    assertThat(ActionRequestRules.canRecordResult("UNKNOWN_RESULT")).isTrue();
    assertThat(ActionRequestRules.canRecordResult("APPLIED")).isFalse();
    assertThat(ActionRequestRules.canRecordResult("REJECTED")).isFalse();
  }
}
