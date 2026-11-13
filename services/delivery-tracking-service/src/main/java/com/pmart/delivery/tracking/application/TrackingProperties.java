package com.pmart.delivery.tracking.application;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pmart.tracking.*}.
 *
 * @param lowAccuracyM 이 값(m)보다 정확도가 나쁘면 LOW_ACCURACY 표시(설계값, 원문에 없음 — 표시만 하고 버리지 않는다)
 * @param maxClockAhead 단말 시각이 서버보다 이만큼 넘게 앞서면 TIME_SKEW
 * @param kakaoMobility 카카오모빌리티 길찾기(ADR-0058). 키는 {@code
 *     delivery-tracking-service-<프로필>.yml}({cipher})에만 — 비면 거리 행렬이 CONFIG_REQUIRED(직선거리 대체 없음)
 */
@ConfigurationProperties(prefix = "pmart.tracking")
public record TrackingProperties(
    @Nullable Double lowAccuracyM, @Nullable Duration maxClockAhead, KakaoMobility kakaoMobility) {

  public TrackingProperties {
    lowAccuracyM = lowAccuracyM == null ? 100.0 : lowAccuracyM;
    maxClockAhead = maxClockAhead == null ? Duration.ofMinutes(1) : maxClockAhead;
    kakaoMobility =
        kakaoMobility == null ? new KakaoMobility(null, null, null, null, null) : kakaoMobility;
  }

  /**
   * @param radiusM 다중 출발지·목적지 길찾기의 반경(카카오 최대 10000m). 넘는 구간은 NO_ROUTE
   */
  public record KakaoMobility(
      @Nullable String baseUrl,
      @Nullable String restApiKey,
      @Nullable Duration connectTimeout,
      @Nullable Duration readTimeout,
      @Nullable Integer radiusM) {
    public KakaoMobility {
      baseUrl =
          baseUrl == null || baseUrl.isBlank() ? "https://apis-navi.kakaomobility.com" : baseUrl;
      restApiKey = restApiKey == null || restApiKey.isBlank() ? null : restApiKey.trim();
      connectTimeout = connectTimeout == null ? Duration.ofSeconds(1) : connectTimeout;
      readTimeout = readTimeout == null ? Duration.ofSeconds(3) : readTimeout;
      radiusM = radiusM == null ? 10000 : Math.min(10000, Math.max(1, radiusM));
    }
  }
}
