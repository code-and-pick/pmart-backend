package com.pmart.cdc.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.cdc.inventory.domain.Fefo.Candidate;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class FefoTest {

  static final LocalDate TODAY = LocalDate.of(2026, 11, 25);
  static final Instant T0 = Instant.parse("2026-11-01T00:00:00Z");

  @Test
  void capIsMaxZeroEminusRminusS() {
    // 업무 문서 예: 정상 100·예약 30·안전량 20 → 50
    assertThat(Fefo.cap(100, 30, 20)).isEqualTo(50);
    assertThat(Fefo.cap(40, 30, 20)).isZero();
  }

  @Test
  void expiryDayItselfIsBlockedAndMinimumIsInclusive() {
    assertThat(Fefo.eligibleLot("VERIFIED", TODAY, TODAY, null)).isFalse(); // D=0
    assertThat(Fefo.eligibleLot("VERIFIED", TODAY.plusDays(1), TODAY, null)).isTrue();
    assertThat(Fefo.eligibleLot("VERIFIED", TODAY.plusDays(30), TODAY, 30)).isTrue();
    assertThat(Fefo.eligibleLot("VERIFIED", TODAY.plusDays(29), TODAY, 30)).isFalse();
    assertThat(Fefo.eligibleLot("VERIFIED", null, TODAY, 30)).isTrue();
  }

  @Test
  void unverifiedOrInvalidLotIsNotEligible() {
    assertThat(Fefo.eligibleLot("UNVERIFIED", TODAY.plusDays(10), TODAY, null)).isFalse();
    assertThat(Fefo.eligibleLot("INVALID", TODAY.plusDays(10), TODAY, null)).isFalse();
  }

  @Test
  void picksEarliestExpiryFirstAcrossLotsAndSkipsReserved() {
    var late =
        new Candidate("L-LATE", "CDC01-C-5-01-01-1-1", TODAY.plusDays(60), T0, 100, 0, false);
    var early =
        new Candidate("L-EARLY", "CDC01-C-5-01-02-1-1", TODAY.plusDays(10), T0, 30, 10, false);
    var noExpiry = new Candidate("L-NONE", "CDC01-C-5-01-03-1-1", null, T0, 100, 0, false);
    var picks = Fefo.pick(List.of(late, noExpiry, early), 50);
    assertThat(picks).hasSize(2);
    assertThat(picks.get(0).candidate().lot()).isEqualTo("L-EARLY");
    assertThat(picks.get(0).qty()).isEqualTo(20);
    assertThat(picks.get(1).candidate().lot()).isEqualTo("L-LATE");
    assertThat(picks.get(1).qty()).isEqualTo(30);
  }

  @Test
  void sameExpiryOrdersByFirstReceivedThenLot() {
    var b = new Candidate("L-B", "X", TODAY.plusDays(5), T0, 5, 0, false);
    var a = new Candidate("L-A", "X", TODAY.plusDays(5), T0, 5, 0, false);
    var older = new Candidate("L-Z", "X", TODAY.plusDays(5), T0.minusSeconds(60), 5, 0, false);
    var picks = Fefo.pick(List.of(b, a, older), 15);
    assertThat(picks).extracting(p -> p.candidate().lot()).containsExactly("L-Z", "L-A", "L-B");
  }

  @Test
  void sameLotPrefersPickFaceBeforeLocationCode() {
    var rack = new Candidate("L1", "CDC01-C-5-01-02-3-1", TODAY.plusDays(10), T0, 50, 0, false);
    var shelf = new Candidate("L1", "CDC01-C-5-61-02-1-1", TODAY.plusDays(10), T0, 30, 0, true);
    var earlier = new Candidate("L0", "CDC01-C-5-01-03-3-1", TODAY.plusDays(5), T0, 5, 0, false);
    var picks = Fefo.pick(List.of(rack, shelf, earlier), 40);
    assertThat(picks)
        .extracting(p -> p.candidate().locationCode())
        .containsExactly("CDC01-C-5-01-03-3-1", "CDC01-C-5-61-02-1-1", "CDC01-C-5-01-02-3-1");
    assertThat(picks.get(1).qty()).isEqualTo(30);
    assertThat(picks.get(2).qty()).isEqualTo(5);
  }
}
