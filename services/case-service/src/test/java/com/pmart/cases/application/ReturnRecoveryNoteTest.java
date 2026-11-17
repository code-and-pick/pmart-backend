package com.pmart.cases.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 반품 회수 실패 사건 메모 — claim 0.3.0 필드가 있으면 쓰고, 없으면 예전 문구(2026-11-18). */
class ReturnRecoveryNoteTest {

  private final JsonMapper json = JsonMapper.builder().build();

  @Test
  void usesCountedAttemptsAndShowsDeadlineWhenRetryAllowed() {
    var p =
        json.readTree(
            """
            {"attempts":3,"countedAttempts":2,"retryAllowed":true,
             "returnDeadlineAt":"2026-11-24T03:00:00Z","lastFailureReason":"CUSTOMER_ABSENT"}
            """);
    assertThat(CaseInboxService.returnRecoveryNote(p))
        .isEqualTo(
            "반품 회수 2번 실패 — 마지막 사유 CUSTOMER_ABSENT, 다시 회수 요청 가능(반품 기한 11월 24일 12:00), 고객에게 다시 연락");
  }

  @Test
  void saysCancelOnlyWhenNoRetryLeft() {
    var p =
        json.readTree(
            """
            {"attempts":3,"countedAttempts":3,"retryAllowed":false,"lastFailureReason":"ADDRESS_ERROR"}
            """);
    assertThat(CaseInboxService.returnRecoveryNote(p))
        .isEqualTo("반품 회수 3번 실패 — 마지막 사유 ADDRESS_ERROR, 다시 회수 불가 — 반품 취소만, 고객에게 다시 연락");
  }

  @Test
  void keepsOldTextForOlderEvents() {
    var p = json.readTree("{\"attempts\":2}");
    assertThat(CaseInboxService.returnRecoveryNote(p))
        .isEqualTo("반품 회수 2번 요청 뒤 실패 — 마지막 사유 -, 고객에게 다시 연락");
  }
}
