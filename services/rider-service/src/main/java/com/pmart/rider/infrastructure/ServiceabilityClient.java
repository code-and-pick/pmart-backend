package com.pmart.rider.infrastructure;

import com.pmart.common.web.Downstream;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * delivery-serviceability 지금 발효 권역 이름(2026-11-24 — PC 2 통합 백오피스 라이더 상세 "센터 이름 · 구역 이름"): {@code GET
 * /internal/serviceability-versions/current} 의 zones[] → zoneId → name. 못 부르면 {@link Downstream}
 * 예외.
 */
@Component
public class ServiceabilityClient {

  private final RestClient client;
  private final ObjectMapper mapper;

  public ServiceabilityClient(
      RestClient.Builder builder,
      ObjectMapper mapper,
      @Value("${pmart.rider.serviceability-base-url:http://delivery-serviceability-service:8401}")
          String baseUrl) {
    this.client = builder.clone().baseUrl(baseUrl).build();
    this.mapper = mapper;
  }

  public Map<String, String> currentZoneNames() {
    String raw =
        Downstream.call(
            "serviceability current version",
            () ->
                client
                    .get()
                    .uri("/api/v1/internal/serviceability-versions/current")
                    .retrieve()
                    .body(String.class));
    JsonNode n = mapper.readTree(raw == null ? "{}" : raw);
    Map<String, String> names = new HashMap<>();
    for (JsonNode z : n.path("zones")) {
      String id = z.path("zoneId").asString("");
      String name = z.path("name").asString("");
      if (!id.isBlank() && !name.isBlank()) {
        names.put(id, name);
      }
    }
    return names;
  }
}
