package com.pmart.cases.application;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pmart.cases.*}. 값의 정본은 설정 저장소 {@code case-service*.yml}(ADR-0029).
 *
 * @param orderServiceBaseUrl 고객센터 문의의 본인 주문 확인(order `GET /orders/{id}`). 없으면 주문을 고른 문의만 422
 *     CONFIG_REQUIRED
 * @param deliveryDispatchBaseUrl 라이더 컴플레인의 배정 라이더 조회(dispatch `GET
 *     /internal/deliveries/{id}/assignment`, 0.3.0). 없으면 컴플레인 기록만 422 CONFIG_REQUIRED
 * @param sla 사건함 종류별 처리 기한(0.4.0, 2026-11-16 — 표시만). 없으면 {@code CaseRules.DEFAULT_SLA}
 */
@ConfigurationProperties("pmart.cases")
public record CaseProperties(
    @Nullable String orderServiceBaseUrl,
    @Nullable String deliveryDispatchBaseUrl,
    java.util.@Nullable Map<String, java.time.Duration> sla) {}
