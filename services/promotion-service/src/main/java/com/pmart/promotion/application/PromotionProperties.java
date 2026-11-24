package com.pmart.promotion.application;

import java.time.ZoneId;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** pmart.promotion.* — zone: 쿠폰 "받은 날부터 N일"의 날짜 경계(없으면 Asia/Seoul, ADR-0010). */
@ConfigurationProperties("pmart.promotion")
public record PromotionProperties(
    @Nullable ZoneId zone,
    @Nullable Compensation compensation,
    java.time.@Nullable Duration signupGrantRetention,
    /** 0.3.0 진열 테마 사본을 받을 storefront 주소(없으면 http://storefront-service:8106). */
    @Nullable String storefrontBaseUrl) {

  /**
   * 회사 사유 보상 쿠폰(ADR-0082 5단계, CL-5) — dispatch-unavailable-promotion-id = 배차 불가 취소 때 줄 쿠폰
   * 행사(COUPON·발급 방식 ADMIN, 승인된 것). 없으면 보상을 PENDING 으로 남긴다(기본 행사를 지어내지 않음).
   */
  public record Compensation(@Nullable String dispatchUnavailablePromotionId) {}

  public @Nullable String dispatchUnavailablePromotionId() {
    return compensation == null ? null : compensation.dispatchUnavailablePromotionId();
  }

  public String storefrontBaseUrlOrDefault() {
    return storefrontBaseUrl == null || storefrontBaseUrl.isBlank()
        ? "http://storefront-service:8106"
        : storefrontBaseUrl;
  }

  public ZoneId zoneOrDefault() {
    return zone == null ? ZoneId.of("Asia/Seoul") : zone;
  }
}
