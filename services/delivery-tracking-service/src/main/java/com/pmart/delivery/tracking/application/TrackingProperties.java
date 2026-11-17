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
 * @param watchMaxAge 배정 시각부터 이만큼 지나면 끝 사건(완료·실패·취소)이 없어도 그 배송의 위치 감시를 끝낸다 — 사용자 결정 2026-11-14 "5시간이
 *     지나면 감시 종료"(끝 사건을 놓치거나 정정만 된 배송이 영원히 감시되지 않게)
 * @param eta 라이더 ETA 계산(TR-1) — {@link Eta}
 * @param dispatchBaseUrl 배송 좌표(픽업지·도착지) 내부 조회 주소
 * @param locationAtWindow 정한 시각의 위치 점 찾기(DD-2 오배송 회수지 — 원래 라이더가 완료를 누른 곳) 앞뒤 허용 폭(설계값 3분 — 위치는 보통
 *     10~30초마다 온다)
 */
@ConfigurationProperties(prefix = "pmart.tracking")
public record TrackingProperties(
    @Nullable Double lowAccuracyM,
    @Nullable Duration maxClockAhead,
    KakaoMobility kakaoMobility,
    @Nullable Duration staleAfter,
    @Nullable Duration staleCheckInterval,
    @Nullable Duration retention,
    @Nullable Duration purgeInterval,
    @Nullable Duration watchMaxAge,
    @Nullable Duration locationAtWindow,
    @Nullable Eta eta,
    @Nullable String dispatchBaseUrl,
    @Nullable String orderBaseUrl) {

  public TrackingProperties {
    lowAccuracyM = lowAccuracyM == null ? 100.0 : lowAccuracyM;
    maxClockAhead = maxClockAhead == null ? Duration.ofMinutes(1) : maxClockAhead;
    staleAfter = staleAfter == null ? Duration.ofMinutes(3) : staleAfter;
    staleCheckInterval = staleCheckInterval == null ? Duration.ofSeconds(30) : staleCheckInterval;
    retention = retention == null ? Duration.ofDays(30) : retention;
    purgeInterval = purgeInterval == null ? Duration.ofHours(1) : purgeInterval;
    watchMaxAge = watchMaxAge == null ? Duration.ofHours(5) : watchMaxAge;
    locationAtWindow = locationAtWindow == null ? Duration.ofMinutes(3) : locationAtWindow;
    kakaoMobility =
        kakaoMobility == null ? new KakaoMobility(null, null, null, null, null) : kakaoMobility;
    eta = eta == null ? new Eta(null, null) : eta;
    dispatchBaseUrl =
        dispatchBaseUrl == null || dispatchBaseUrl.isBlank()
            ? "http://delivery-dispatch-service:8404"
            : dispatchBaseUrl;
    // 고객 소유 확인(TR-3) — order 내부 주문 조회
    orderBaseUrl =
        orderBaseUrl == null || orderBaseUrl.isBlank() ? "http://order-service:8201" : orderBaseUrl;
  }

  /**
   * 라이더 ETA(TR-1, 2026-11-16). 둘 다 있어야 켜진다 — 카카오 길찾기 사용량이 늘기 때문에 값이 없으면 계산하지 않는다(CONFIG 로 켬).
   *
   * @param interval 한 배송의 ETA 를 다시 계산하는 간격(라이더당 길찾기 한 번)
   * @param minChange 직전에 낸 도착 예정과 이만큼 넘게 달라질 때만 {@code DeliveryEtaUpdated} 를 낸다(처음은 항상)
   */
  public record Eta(@Nullable Duration interval, @Nullable Duration minChange) {
    public boolean enabled() {
      return interval != null && minChange != null;
    }
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
