package com.pmart.delivery.tracking.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.delivery.tracking.domain.LocationQuality.Quality;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class LocationQualityTest {

  static final Instant NOW = Instant.parse("2026-11-13T05:00:00Z");
  static final Duration AHEAD = Duration.ofMinutes(1);

  @Test
  void classifies() {
    assertThat(LocationQuality.classify(NOW.minusSeconds(5), 10.0, NOW, null, AHEAD, 100))
        .isEqualTo(Quality.GOOD);
    assertThat(LocationQuality.classify(NOW.minusSeconds(5), 150.0, NOW, null, AHEAD, 100))
        .isEqualTo(Quality.LOW_ACCURACY);
    assertThat(LocationQuality.classify(NOW.plusSeconds(120), 5.0, NOW, null, AHEAD, 100))
        .isEqualTo(Quality.TIME_SKEW);
    assertThat(
            LocationQuality.classify(
                NOW.minusSeconds(60), 5.0, NOW, NOW.minusSeconds(10), AHEAD, 100))
        .isEqualTo(Quality.TIME_SKEW);
    assertThat(LocationQuality.classify(NOW.minusSeconds(5), null, NOW, null, AHEAD, 100))
        .isEqualTo(Quality.GOOD);
  }

  @Test
  void onlyNormalPointsMoveLatest() {
    assertThat(LocationQuality.updatesLatest(Quality.GOOD)).isTrue();
    assertThat(LocationQuality.updatesLatest(Quality.LOW_ACCURACY)).isTrue();
    assertThat(LocationQuality.updatesLatest(Quality.TIME_SKEW)).isFalse();
  }
}
