package com.pmart.cdc.outbound.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pmart.outbound.*}.
 *
 * @param cdcInventoryBaseUrl cdc-inventory-service 주소(내부 할당·할당 조회). 설정 정본 pmart-config 의
 *     cdc-outbound-service-&lt;프로필&gt;.yml
 */
@ConfigurationProperties(prefix = "pmart.outbound")
public record OutboundProperties(String cdcInventoryBaseUrl) {

  public OutboundProperties {
    cdcInventoryBaseUrl =
        cdcInventoryBaseUrl == null || cdcInventoryBaseUrl.isBlank()
            ? "http://cdc-inventory-service:8601"
            : cdcInventoryBaseUrl;
  }
}
