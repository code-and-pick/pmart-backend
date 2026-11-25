package com.pmart.cdc.inventory.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pmart.inventory.*}.
 *
 * @param cdcLocationBaseUrl cdc-location-service 주소(할당의 피킹 선반 먼저 — 위치 일괄 조회). 설정 정본 pmart-config 의
 *     cdc-inventory-service-&lt;프로필&gt;.yml
 */
@ConfigurationProperties(prefix = "pmart.inventory")
public record InventoryProperties(String cdcLocationBaseUrl) {

  public InventoryProperties {
    cdcLocationBaseUrl =
        cdcLocationBaseUrl == null || cdcLocationBaseUrl.isBlank()
            ? "http://cdc-location-service:8602"
            : cdcLocationBaseUrl;
  }
}
