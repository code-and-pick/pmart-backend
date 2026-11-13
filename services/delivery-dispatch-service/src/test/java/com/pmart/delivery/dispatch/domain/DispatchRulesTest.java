package com.pmart.delivery.dispatch.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class DispatchRulesTest {

  static final List<Double> RADII = List.of(1.0, 2.0, 3.0);
  static final Duration WAIT = Duration.ofMinutes(1);

  @Test
  void feeTableFollowsOriginalBands() {
    assertThat(FeeTable.estimate("MOTORCYCLE", 0)).isEqualTo(2600);
    assertThat(FeeTable.estimate("MOTORCYCLE", 674)).isEqualTo(2600);
    assertThat(FeeTable.estimate("MOTORCYCLE", 675)).isEqualTo(2900);
    assertThat(FeeTable.estimate("BICYCLE", 1350)).isEqualTo(3200);
    assertThat(FeeTable.estimate("CAR", 2000)).isEqualTo(3900);
    assertThat(FeeTable.estimate("CAR", 3500)).isEqualTo(4900);
    assertThat(FeeTable.estimate("CAR", 4200)).isEqualTo(5900);
    assertThat(FeeTable.estimate("WALK", 700)).isEqualTo(2700);
    assertThat(FeeTable.estimate("WALK", 900)).isEqualTo(2800);
    assertThat(FeeTable.estimate("WALK", 946)).isEqualTo(2900);
    assertThat(FeeTable.estimate("WALK", 1045)).isEqualTo(2900);
    assertThat(FeeTable.estimate("WALK", 1046)).isEqualTo(3000);
  }

  @Test
  void operatingHoursAreSeoulEightToMidnight() {
    OperatingHours h = OperatingHours.parse("08:00", "24:00");
    assertThat(h.isOpen(Instant.parse("2026-11-13T22:59:00Z"))).isFalse(); // 07:59 KST
    assertThat(h.isOpen(Instant.parse("2026-11-13T23:00:00Z"))).isTrue(); // 08:00 KST
    assertThat(h.isOpen(Instant.parse("2026-11-13T14:59:00Z"))).isTrue(); // 23:59 KST
    assertThat(h.isOpen(Instant.parse("2026-11-13T15:00:00Z"))).isFalse(); // 00:00 KST
    assertThat(h.nextOpen(Instant.parse("2026-11-13T15:30:00Z")))
        .isEqualTo(Instant.parse("2026-11-13T23:00:00Z"));
  }

  @Test
  void acceptJudgesLateReasons() {
    Instant now = Instant.parse("2026-11-13T05:00:00Z");
    var ok = new AcceptRules.RiderSnapshot(true, false, 2);
    assertThat(AcceptRules.judge("DISPATCHING", now.plusSeconds(10), now, ok, 3).ok()).isTrue();
    assertThat(AcceptRules.judge("ASSIGNED", now.plusSeconds(10), now, ok, 3).late())
        .isEqualTo(AcceptRules.LateReason.ALREADY_ASSIGNED);
    assertThat(AcceptRules.judge("CANCELLED_BEFORE_PICKUP", null, now, ok, 3).late())
        .isEqualTo(AcceptRules.LateReason.ORDER_CANCELLED);
    assertThat(AcceptRules.judge("DISPATCHING", now, now, ok, 3).late())
        .isEqualTo(AcceptRules.LateReason.EXPIRED);
    assertThat(
            AcceptRules.judge(
                    "DISPATCHING", null, now, new AcceptRules.RiderSnapshot(true, false, 3), 3)
                .late())
        .isEqualTo(AcceptRules.LateReason.INELIGIBLE);
    assertThat(
            AcceptRules.judge(
                    "DISPATCHING", null, now, new AcceptRules.RiderSnapshot(true, true, 1), 3)
                .late())
        .isEqualTo(AcceptRules.LateReason.INELIGIBLE);
    assertThat(
            AcceptRules.judge(
                    "DISPATCHING", null, now, new AcceptRules.RiderSnapshot(false, false, 0), 3)
                .late())
        .isEqualTo(AcceptRules.LateReason.INELIGIBLE);
    assertThat(AcceptRules.expiryCountsAsRejection(null)).isFalse();
    assertThat(AcceptRules.expiryCountsAsRejection(now)).isTrue();
    assertThat(AcceptRules.rejectionNotice(5, 5)).isTrue();
    assertThat(AcceptRules.rejectionNotice(4, 5)).isFalse();
  }

  @Test
  void searchStaysThenWidensThenRetriesThenFails() {
    Instant t0 = Instant.parse("2026-11-13T05:00:00Z");
    assertThat(SearchRules.whenNoCandidate(1, t0, 0, t0.plusSeconds(30), RADII, WAIT, 2))
        .isEqualTo(SearchRules.Next.STAY);
    assertThat(SearchRules.whenNoCandidate(1, t0, 0, t0.plusSeconds(60), RADII, WAIT, 2))
        .isEqualTo(SearchRules.Next.NEXT_STAGE);
    assertThat(SearchRules.whenNoCandidate(3, t0, 1, t0.plusSeconds(60), RADII, WAIT, 2))
        .isEqualTo(SearchRules.Next.RETRY);
    assertThat(SearchRules.whenNoCandidate(3, t0, 2, t0.plusSeconds(60), RADII, WAIT, 2))
        .isEqualTo(SearchRules.Next.FAIL);
    assertThat(SearchRules.failureReason(true, true)).isEqualTo("REPEATED_REJECTION");
    assertThat(SearchRules.failureReason(false, true)).isEqualTo("EXPIRED");
    assertThat(SearchRules.failureReason(false, false)).isEqualTo("NO_RIDER");
    assertThat(SearchRules.radiusKm(RADII, 2)).isEqualTo(2.0);
  }

  @Test
  void straightDistance() {
    double m = new Geo(37.4979, 127.0276).metersTo(new Geo(37.5110, 127.0276));
    assertThat(m).isBetween(1400.0, 1500.0);
  }
}
