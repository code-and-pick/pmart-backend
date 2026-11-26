package com.pmart.transport.execution.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class TripRulesTest {

  static final Instant T0 = Instant.parse("2026-12-01T15:30:00Z"); // 00:30 KST 출차 계획
  static final Instant FAR = Instant.parse("2030-01-01T00:00:00Z");

  @Test
  void handoverComparison() {
    var same = TripRules.compare(List.of("B1", "B2"), List.of("B2", "B1"));
    assertThat(same.result()).isEqualTo("MATCHED");
    var diff = TripRules.compare(List.of("B1", "B2"), List.of("B2", "BX"));
    assertThat(diff.result()).isEqualTo("MISMATCHED");
    assertThat(diff.missing()).containsExactly("B1");
    assertThat(diff.extra()).containsExactly("BX");
  }

  @Test
  void deadlineIsNextDay0730Seoul() {
    assertThat(TripRules.deadline(LocalDate.of(2026, 12, 1)))
        .isEqualTo(Instant.parse("2026-12-01T22:30:00Z"));
  }

  @Test
  void etaFromActualDepartureAndArrivals() {
    var stops =
        List.of(
            new TripRules.StopIn(1, 200, T0.plusSeconds(200 * 60), null, null),
            new TripRules.StopIn(2, 20, T0.plusSeconds(250 * 60), null, null));
    // 40분 늦게 출차 → 정차 1 예상 = 출차 + 200분(계획 + 40분 → 지연), 정차 2 = + 30 + 20
    Instant late = T0.plusSeconds(40 * 60);
    var e = TripRules.eta(stops, T0, late, late, FAR, false);
    assertThat(e.get(0).estimatedArrival()).isEqualTo(late.plusSeconds(200 * 60));
    assertThat(e.get(0).delayed()).isTrue();
    assertThat(e.get(1).estimatedArrival()).isEqualTo(late.plusSeconds(250 * 60));
    // 제시간 출차면 지연 아님, 07:30 마감을 넘으면 지연
    var ok = TripRules.eta(stops, T0, T0, T0, FAR, false);
    assertThat(ok.get(0).delayed()).isFalse();
    var tight = TripRules.eta(stops, T0, T0, T0, T0.plusSeconds(210 * 60), false);
    assertThat(tight.get(0).delayed()).isFalse();
    assertThat(tight.get(1).delayed()).isTrue();
    // 도착한 정차는 실제 도착 시각
    var arrived =
        List.of(
            new TripRules.StopIn(
                1,
                200,
                T0.plusSeconds(200 * 60),
                T0.plusSeconds(190 * 60),
                T0.plusSeconds(215 * 60)),
            new TripRules.StopIn(2, 20, T0.plusSeconds(250 * 60), null, null));
    var a = TripRules.eta(arrived, T0, T0, T0.plusSeconds(215 * 60), FAR, false);
    assertThat(a.get(0).estimatedArrival()).isEqualTo(T0.plusSeconds(190 * 60));
    assertThat(a.get(1).estimatedArrival()).isEqualTo(T0.plusSeconds(235 * 60));
  }
}
