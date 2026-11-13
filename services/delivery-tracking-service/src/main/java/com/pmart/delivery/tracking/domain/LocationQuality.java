package com.pmart.delivery.tracking.domain;

import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * 받은 위치 점의 품질 표시(계약 GpsQuality — 폐기하지 않고 표시만). 배송 상태는 바꾸지 않는다(GPS 만으로 픽업·전달 처리 금지).
 *
 * <ul>
 *   <li>TIME_SKEW: 단말 시각이 서버 수신보다 {@code maxClockAhead} 넘게 앞서거나, 이미 받은 마지막 점보다 이르다(시간 역전).
 *   <li>LOW_ACCURACY: 정확도(m)가 {@code lowAccuracyM} 보다 크다.
 *   <li>그 밖 GOOD. 권역 밖(OUT_OF_ZONE)은 권역 판정 원천을 아직 붙이지 않아 내지 않는다.
 * </ul>
 */
public final class LocationQuality {

  public enum Quality {
    GOOD,
    LOW_ACCURACY,
    OUT_OF_ZONE,
    TIME_SKEW,
    STALE,
    UNKNOWN
  }

  private LocationQuality() {}

  public static Quality classify(
      Instant recordedAt,
      @Nullable Double accuracyM,
      Instant receivedAt,
      @Nullable Instant lastRecordedAt,
      Duration maxClockAhead,
      double lowAccuracyM) {
    if (recordedAt.isAfter(receivedAt.plus(maxClockAhead))) {
      return Quality.TIME_SKEW;
    }
    if (lastRecordedAt != null && recordedAt.isBefore(lastRecordedAt)) {
      return Quality.TIME_SKEW;
    }
    if (accuracyM != null && accuracyM > lowAccuracyM) {
      return Quality.LOW_ACCURACY;
    }
    return Quality.GOOD;
  }

  /** 마지막 위치를 바꿀 점인가 — 시간 역전·시계 앞섬이 아닌 점만. */
  public static boolean updatesLatest(Quality q) {
    return q == Quality.GOOD || q == Quality.LOW_ACCURACY;
  }
}
