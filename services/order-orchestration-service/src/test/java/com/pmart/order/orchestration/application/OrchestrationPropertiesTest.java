package com.pmart.order.orchestration.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** 결제 전 자원 점유 유효시간(Q-04 결정 10분, ADR-0042): 없거나 1분 미만이면 미설정(null) — 0 으로 대체하지 않는다. */
class OrchestrationPropertiesTest {

  static OrchestrationProperties with(Duration holdTtl) {
    return new OrchestrationProperties(
        null, null, null, null, null, null, null, null, holdTtl, null, null, null);
  }

  @Test
  void holdTtlIsMinutesOrUnset() {
    assertThat(with(Duration.ofMinutes(10)).holdTtlMinutes()).isEqualTo(10);
    assertThat(with(null).holdTtlMinutes()).isNull();
    assertThat(with(Duration.ofSeconds(30)).holdTtlMinutes()).isNull();
  }
}
