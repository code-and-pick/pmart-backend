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
 * @param staleAfter 진행 중 배송이 있는 라이더의 위치가 이만큼 안 오면 {@code RiderLocationStale} — 사용자 결정 2026-11-14
 *     "배달 중 3분"(dispatch 후보 기준 2분보다 조금 길게)
 * @param staleCheckInterval 끊김 판정 주기
 * @param retention 위치 기록 보존 기간 — 사용자 결정 2026-11-14 "30일"(서버 수신 시각 기준)
 * @param purgeInterval 보존 기간 지난 기록 지우기 주기
 */
@ConfigurationProperties(prefix = "pmart.tracking")
public record TrackingProperties(
    @Nullable Double lowAccuracyM,
    @Nullable Duration maxClockAhead,
    KakaoMobility kakaoMobility,
    @Nullable Duration staleAfter,
    @Nullable Duration staleCheckInterval,
    @Nullable Duration retention,
    @Nullable Duration purgeInterval) {

  public TrackingProperties {
    lowAccuracyM = lowAccuracyM == null ? 100.0 : lowAccuracyM;
    maxClockAhead = maxClockAhead == null ? Duration.ofMinutes(1) : maxClockAhead;
    staleAfter = staleAfter == null ? Duration.ofMinutes(3) : staleAfter;
    staleCheckInterval = staleCheckInterval == null ? Duration.ofSeconds(30) : staleCheckInterval;
    retention = retention == null ? Duration.ofDays(30) : retention;
    purgeInterval = purgeInterval == null ? Duration.ofHours(1) : purgeInterval;
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
