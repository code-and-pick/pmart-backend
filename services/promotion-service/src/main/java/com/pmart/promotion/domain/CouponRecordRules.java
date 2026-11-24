package com.pmart.promotion.domain;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * 쿠폰 발급 기록(0.3.1 — 직원)의 상태·받은 경로. 고객 쿠폰함과 달리 결제 중 점유(HELD)를 따로 보인다. {@code
 * infrastructure/CouponRecordStore} 의 SQL 거르기와 같은 뜻이다.
 */
public final class CouponRecordRules {

  /** 서버가 준 쿠폰(가입 축하·보상)의 issued_by. */
  public static final String SYSTEM = "system";

  private CouponRecordRules() {}

  /** AVAILABLE(기한 안 보유) · HELD(결제 중) · USED(사용 확정) · EXPIRED(기한 지남, 안 씀). */
  public static String status(String state, Instant validUntil, Instant now) {
    if ("HELD".equals(state) || "USED".equals(state)) {
      return state;
    }
    return now.isAfter(validUntil) ? "EXPIRED" : "AVAILABLE";
  }

  /** DOWNLOAD · STAFF(직원 지정 발급) · SIGNUP(가입 축하) · COMPENSATION(회사 사유 보상 — 서버가 지정 발급 채널로 줌). */
  public static String source(String channel, @Nullable String issuedBy) {
    return switch (channel) {
      case "DOWNLOAD" -> "DOWNLOAD";
      case "SIGNUP" -> "SIGNUP";
      default -> SYSTEM.equals(issuedBy) ? "COMPENSATION" : "STAFF";
    };
  }

  /** 발급한 직원 — 서버가 준 것·내려받기는 null. */
  public static @Nullable String issuedBy(String channel, @Nullable String issuedBy) {
    return "STAFF".equals(source(channel, issuedBy)) ? issuedBy : null;
  }
}
