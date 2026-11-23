package com.pmart.rider.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.rider.domain.Rider.Restriction;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** RS-8 기존 제한과 맞추기: TERMINATED·같거나 긴 정지(기한 없음 포함)는 그대로, 그 밖은 7일 정지로 올린다. */
class ProfanitySuspensionsTest {

  final Instant until = Instant.parse("2026-11-30T00:00:00Z");

  @Test
  void keepsStricterRestrictions() {
    assertThat(ProfanitySuspensions.stricterNeeded(Restriction.TERMINATED, null, until)).isFalse();
    assertThat(ProfanitySuspensions.stricterNeeded(Restriction.SUSPENDED, null, until)).isFalse();
    assertThat(ProfanitySuspensions.stricterNeeded(Restriction.SUSPENDED, until, until)).isFalse();
  }

  @Test
  void raisesWeakerRestrictions() {
    assertThat(ProfanitySuspensions.stricterNeeded(Restriction.NORMAL, null, until)).isTrue();
    assertThat(ProfanitySuspensions.stricterNeeded(Restriction.DISPATCH_RESTRICTED, null, until))
        .isTrue();
    assertThat(
            ProfanitySuspensions.stricterNeeded(
                Restriction.SUSPENDED, until.minusSeconds(3600), until))
        .isTrue();
  }
}
