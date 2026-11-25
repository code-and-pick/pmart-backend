package com.pmart.cdc.outbound.application;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pmart.outbound.*}.
 *
 * @param cdcInventoryBaseUrl cdc-inventory-service 주소(내부 할당·할당 조회). 설정 정본 pmart-config 의
 *     cdc-outbound-service-&lt;프로필&gt;.yml
 * @param cdcLocationBaseUrl cdc-location-service 주소(0.9.0 — Wave 릴리스 때 위치 통로 피킹 방식 일괄 조회)
 * @param batchMaxLines 묶음 작업 하나의 최대 줄 수(0.9.0). 비우면 자르지 않는다 — 기본값 없음(묶음 피킹 설계 §7 백엔드 판단)
 */
@ConfigurationProperties(prefix = "pmart.outbound")
public record OutboundProperties(
    String cdcInventoryBaseUrl, String cdcLocationBaseUrl, @Nullable Integer batchMaxLines) {

  public OutboundProperties {
    cdcInventoryBaseUrl =
        cdcInventoryBaseUrl == null || cdcInventoryBaseUrl.isBlank()
            ? "http://cdc-inventory-service:8601"
            : cdcInventoryBaseUrl;
    cdcLocationBaseUrl =
        cdcLocationBaseUrl == null || cdcLocationBaseUrl.isBlank()
            ? "http://cdc-location-service:8602"
            : cdcLocationBaseUrl;
    if (batchMaxLines != null && batchMaxLines < 1) {
      batchMaxLines = null;
    }
  }
}
