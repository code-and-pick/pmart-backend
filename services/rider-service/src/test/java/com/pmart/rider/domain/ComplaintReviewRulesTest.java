package com.pmart.rider.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pmart.rider.domain.ComplaintReviewRules.Complaint;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ComplaintReviewRulesTest {

  static final Duration WEEK = Duration.ofDays(7);
  static final Instant T0 = Instant.parse("2026-11-01T00:00:00Z");

  static Complaint c(String id, String delivery, double days) {
    return new Complaint(id, delivery, T0.plusSeconds((long) (days * 86400)));
  }

  @Test
  void threeDifferentDeliveriesWithinAWeekQualify() {
    List<Complaint> all = List.of(c("1", "D1", 0), c("2", "D2", 3), c("3", "D3", 6.9));
    assertThat(ComplaintReviewRules.qualifying(all, all.get(2), WEEK, 3)).hasSize(3);
  }

  @Test
  void spreadOverMoreThanAWeekDoesNot() {
    List<Complaint> all = List.of(c("1", "D1", 0), c("2", "D2", 3), c("3", "D3", 7.1));
    assertThat(ComplaintReviewRules.qualifying(all, all.get(2), WEEK, 3)).isEmpty();
  }

  @Test
  void sameDeliveryCountsOnce() {
    List<Complaint> all = List.of(c("1", "D1", 0), c("2", "D1", 1), c("3", "D2", 2));
    assertThat(ComplaintReviewRules.qualifying(all, all.get(2), WEEK, 3)).isEmpty();
  }

  @Test
  void lateArrivingOlderComplaintStillCompletesTheWindow() {
    // 2·3 이 먼저 왔고 그보다 이른 1 이 늦게 도착 — 1 을 포함하는 창에서 3건
    List<Complaint> all = List.of(c("2", "D2", 4), c("3", "D3", 5), c("1", "D1", 1));
    assertThat(ComplaintReviewRules.qualifying(all, all.get(2), WEEK, 3))
        .extracting(Complaint::complaintId)
        .containsExactly("1", "2", "3");
  }

  @Test
  void missingRuleIsRejected() {
    assertThatThrownBy(
            () -> ComplaintReviewRules.qualifying(List.of(), c("1", "D1", 0), Duration.ZERO, 3))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
