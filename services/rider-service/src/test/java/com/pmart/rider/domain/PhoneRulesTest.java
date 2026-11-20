package com.pmart.rider.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class PhoneRulesTest {

  @Test
  void rejoinBlockSpanIsReadable() {
    assertThat(PhoneRules.spanText(Duration.ofDays(30))).isEqualTo("30일");
    assertThat(PhoneRules.spanText(Duration.ofMinutes(5))).isEqualTo("5분");
    assertThat(PhoneRules.spanText(Duration.ofHours(2))).isEqualTo("2시간");
    assertThat(PhoneRules.spanText(Duration.ofMinutes(90))).isEqualTo("90분");
    assertThat(PhoneRules.spanText(Duration.ofSeconds(10))).isEqualTo("1분");
  }
}
