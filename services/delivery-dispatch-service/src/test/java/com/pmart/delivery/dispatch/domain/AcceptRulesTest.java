package com.pmart.delivery.dispatch.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 한집은 단독(2026-11-18 사용자): 한집을 맡은 라이더는 다른 배송을 못 받고, 다른 배송을 맡은 라이더는 한집을 못 받는다. */
class AcceptRulesTest {

  static final Instant NOW = Instant.parse("2026-11-18T03:00:00Z");

  static AcceptRules.Verdict judge(int active, boolean holdsSingle, boolean single) {
    return AcceptRules.judge(
        "DISPATCHING",
        null,
        NOW,
        new AcceptRules.RiderSnapshot(true, false, active, holdsSingle),
        3,
        single);
  }

  @Test
  void singleOnlyForIdleRiderAndBlocksOthers() {
    assertThat(judge(0, false, true).ok()).isTrue(); // 빈 라이더 — 한집 OK
    assertThat(judge(1, false, true).late())
        .isEqualTo(AcceptRules.LateReason.INELIGIBLE); // 알뜰 들고 한집 X
    assertThat(judge(1, true, false).late())
        .isEqualTo(AcceptRules.LateReason.INELIGIBLE); // 한집 들고 알뜰 X
    assertThat(judge(2, false, false).ok()).isTrue(); // 알뜰끼리 3건까지
    assertThat(judge(3, false, false).late()).isEqualTo(AcceptRules.LateReason.INELIGIBLE);
  }
}
