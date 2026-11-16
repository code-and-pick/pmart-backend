package com.pmart.backoffice.bff.application;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pmart.bff.*} — 화면 조회가 읽는 정본 서비스 주소. 기본 = 서비스 이름(LAN 은 Eureka·extra_hosts, 운영은 Service
 * DNS).
 *
 * @param approvalBaseUrl approval-service(승인함)
 * @param orchestrationBaseUrl order-orchestration-service(처리 대기 — 멈춘 사가·수동 조치, 2026-11-16 BO-12)
 * @param orderBaseUrl order-service(처리 대기 — 이행 확인 필요, 통합 조회 — 직원용 주문)
 * @param executionBaseUrl delivery-execution-service(통합 조회 — 배송, 2026-11-16)
 * @param dispatchBaseUrl delivery-dispatch-service(통합 조회 — 배차, 2026-11-16)
 * @param claimBaseUrl claim-service(통합 조회 — 클레임, 2026-11-16)
 * @param caseBaseUrl case-service(통합 조회 — 사건, 2026-11-16)
 * @param mfcFulfillmentBaseUrl mfc-fulfillment-service(처리 대기 — 중지 실패·인계 거절, 재시도 — 2026-11-16 F-4)
 * @param mfcPendingLists 처리 대기에 MFC 목록을 읽을지(기본 false — PC 3 목록 API F-4 가 생긴 뒤 켠다. 재시도 명령은 상관없이 열림)
 */
@ConfigurationProperties(prefix = "pmart.bff")
public record BffProperties(
    @Nullable String approvalBaseUrl,
    @Nullable String orchestrationBaseUrl,
    @Nullable String orderBaseUrl,
    @Nullable String executionBaseUrl,
    @Nullable String dispatchBaseUrl,
    @Nullable String claimBaseUrl,
    @Nullable String caseBaseUrl,
    @Nullable String mfcFulfillmentBaseUrl,
    @Nullable Boolean mfcPendingLists) {

  public BffProperties {
    approvalBaseUrl = or(approvalBaseUrl, "http://approval-service:8505");
    orchestrationBaseUrl = or(orchestrationBaseUrl, "http://order-orchestration-service:8202");
    orderBaseUrl = or(orderBaseUrl, "http://order-service:8201");
    executionBaseUrl = or(executionBaseUrl, "http://delivery-execution-service:8405");
    dispatchBaseUrl = or(dispatchBaseUrl, "http://delivery-dispatch-service:8404");
    claimBaseUrl = or(claimBaseUrl, "http://claim-service:8204");
    caseBaseUrl = or(caseBaseUrl, "http://case-service:8506");
    mfcFulfillmentBaseUrl = or(mfcFulfillmentBaseUrl, "http://mfc-fulfillment-service:8302");
    mfcPendingLists = mfcPendingLists != null && mfcPendingLists;
  }

  private static String or(@Nullable String v, String fallback) {
    return v == null || v.isBlank() ? fallback : v;
  }
}
