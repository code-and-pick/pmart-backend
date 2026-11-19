package com.pmart.rider.application;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pmart.rider.phone-verification.*}(2026-11-19, OCTOMO — 고객과 같은 키를 같이 씀, 사용자 결정). OCTOMO 값이
 * 하나라도 없으면 시작·확인은 CONFIG_REQUIRED. {@code required} 가 true 면 가입에 확인 필수(기본 false — 앱 확인 화면이 나간 뒤
 * 켠다). api-key 는 비밀(pmart-config {cipher}, 로그 금지).
 */
@ConfigurationProperties(prefix = "pmart.rider.phone-verification")
public record PhoneVerificationProperties(
    @Nullable String octomoBaseUrl,
    @Nullable String apiKey,
    @Nullable String sendTo,
    @Nullable Duration ttl,
    @Nullable Integer withinMinutes,
    @Nullable Integer maxAttempts,
    @Nullable Integer maxStartsPerDay,
    boolean required,
    @Nullable Duration rejoinBlock) {

  public PhoneVerificationProperties {
    // 탈퇴 번호 재가입 막기(2026-11-19 사용자 결정 "30일 동안 막기") — 업무 결정값이라 기본값으로 둔다
    rejoinBlock = rejoinBlock == null ? Duration.ofDays(30) : rejoinBlock;
  }

  public boolean complete() {
    return octomoBaseUrl != null
        && !octomoBaseUrl.isBlank()
        && apiKey != null
        && !apiKey.isBlank()
        && sendTo != null
        && !sendTo.isBlank()
        && ttl != null
        && ttl.isPositive()
        && withinMinutes != null
        && withinMinutes >= 1
        && withinMinutes <= 60
        && maxAttempts != null
        && maxAttempts > 0
        && maxStartsPerDay != null
        && maxStartsPerDay > 0;
  }
}
