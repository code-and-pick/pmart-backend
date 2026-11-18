package com.pmart.delivery.tracking.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 대기 시작(2026-11-18 사용자 "대기 = 운행 중 + 신규배차 켬 + 배송 0건"). */
class RiderControlServiceTest {

  @Test
  void waitingStartsAtLaterOfDispatchOnAndLastDeliveryEnd() {
    Instant on = Instant.parse("2026-11-18T01:00:00Z");
    Instant ended = Instant.parse("2026-11-18T02:00:00Z");
    assertThat(RiderControlService.waitingSince(on, true, ended)).isEqualTo(ended);
    assertThat(RiderControlService.waitingSince(ended, true, on)).isEqualTo(ended);
    assertThat(RiderControlService.waitingSince(on, true, null)).isEqualTo(on);
    assertThat(RiderControlService.waitingSince(on, false, ended)).isNull(); // 배송 중
    assertThat(RiderControlService.waitingSince(null, true, ended)).isNull(); // 신규배차 끔
  }
}
