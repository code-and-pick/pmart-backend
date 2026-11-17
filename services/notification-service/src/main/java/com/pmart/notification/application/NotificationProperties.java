package com.pmart.notification.application;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pmart.notification.*}.
 *
 * @param orderBaseUrl order-service(받는 고객·상품명 — {@code GET /internal/orders/{id}}, 내부 호출)
 * @param pushToken 단말 푸시 토큰(0.3.0)
 * @param fcm 라이더 휴대폰 푸시(2026-11-17) — 키가 없으면 꺼짐
 */
@ConfigurationProperties(prefix = "pmart.notification")
public record NotificationProperties(
    @Nullable String orderBaseUrl, @Nullable PushToken pushToken, @Nullable Fcm fcm) {

  /**
   * {@code pmart.notification.fcm.*} — FCM HTTP v1(2026-11-17 사용자 "발송 코드만 먼저, 키는 나중").
   *
   * @param serviceAccountJson Firebase 서비스 계정 키 JSON 전체(설정 저장소 {cipher} — 비우면 푸시 꺼짐)
   * @param tokenUri OAuth 토큰 주소(비우면 키의 token_uri — 시험용)
   * @param sendBaseUrl FCM 주소(비우면 https://fcm.googleapis.com — 시험용)
   */
  public record Fcm(
      @Nullable String serviceAccountJson,
      @Nullable String tokenUri,
      @Nullable String sendBaseUrl) {}

  /**
   * {@code pmart.notification.push-token.*}.
   *
   * @param maxDevices 주인(고객·라이더) 한 명이 둘 수 있는 단말 수. 넘으면 가장 오래 갱신되지 않은 단말을 지운다. 없으면 등록 422
   *     CONFIG_REQUIRED(기본값을 지어내지 않는다)
   */
  public record PushToken(@Nullable Integer maxDevices) {}
}
