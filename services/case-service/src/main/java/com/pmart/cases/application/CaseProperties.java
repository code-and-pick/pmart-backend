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
 * @param profanityWords <b>정지 단어</b>(0.35.0 R-14, 0.37.0 부터 정지 목록 — 위협·거친 말). 문의·라이더 문의 대화에 들어 있으면
 *     받되 정지 사건(Customer/RiderProfanityDetected)을 낸다. 사용자 승인 뒤 PC 1 이 설정 저장소에 반영. 비우면 없음
 * @param blockedWords <b>막기 단어</b>(0.37.0 — 욕설). 들어 있으면 422 BLOCKED_WORDS 로 받지 않는다(정지 없음). 비우면 없음
 */
@ConfigurationProperties("pmart.cases")
public record CaseProperties(
    @Nullable String orderServiceBaseUrl,
    @Nullable String deliveryDispatchBaseUrl,
    java.util.@Nullable Map<String, java.time.Duration> sla,
    java.util.@Nullable List<String> profanityWords,
    java.util.@Nullable List<String> blockedWords,
    /** R-16(ADR-0086 결정 3) 오배송 문의 고객 선택 기한 — 없으면 5시간(사용자 2026-11-24 "5시간 뒤 자동 환불"). */
    java.time.@Nullable Duration misdeliveryChoiceWindow) {

  public java.time.Duration misdeliveryChoiceWindowOrDefault() {
    return misdeliveryChoiceWindow == null
        ? java.time.Duration.ofHours(5)
        : misdeliveryChoiceWindow;
  }

  /** 정지 단어 — 없으면 빈 목록. */
  public java.util.List<String> profanity() {
    return profanityWords == null ? java.util.List.of() : profanityWords;
  }

  /** 막기 단어 — 없으면 빈 목록. */
  public java.util.List<String> blocked() {
    return blockedWords == null ? java.util.List.of() : blockedWords;
  }
}
