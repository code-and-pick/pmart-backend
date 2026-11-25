package com.pmart.cdc.inventory.infrastructure;

import com.pmart.cdc.inventory.application.InventoryProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * cdc-location 위치 일괄 조회(cdc-location 0.4.0 {@code POST /internal/location-lookups}) — 할당 FEFO 동률 안의
 * "피킹 선반 먼저"(0.5.0)용. 동선 선호일 뿐이라 실패하면 빈 집합(위치 코드 순)을 돌려주고 할당을 막지 않는다. 할당 호출자(cdc-outbound)의 응답 시간
 * 안에 끝나도록 타임아웃을 짧게(연결 0.5초·응답 1.5초) 둔다. 내부 경로라 토큰 없이 X-Source-System 만(ADR-0026).
 */
@Component
public class LocationClient {

  static final int CHUNK = 2000;
  private static final Logger log = LoggerFactory.getLogger(LocationClient.class);

  private final RestClient http;
  private final ObjectMapper mapper;

  public LocationClient(
      RestClient.Builder builder, InventoryProperties props, ObjectMapper mapper) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofMillis(500));
    factory.setReadTimeout(Duration.ofMillis(1500));
    this.http = builder.clone().baseUrl(props.cdcLocationBaseUrl()).requestFactory(factory).build();
    this.mapper = mapper;
  }

  /** 주어진 코드 중 PICK_FACE 인 것. 조회가 안 되면 빈 집합. */
  public Set<String> pickFaces(String centerId, Collection<String> codes) {
    Set<String> out = new HashSet<>();
    List<String> all = new ArrayList<>(new TreeSet<>(codes));
    try {
      for (int i = 0; i < all.size(); i += CHUNK) {
        List<String> chunk = all.subList(i, Math.min(all.size(), i + CHUNK));
        String raw =
            http.post()
                .uri("/api/v1/internal/location-lookups")
                // POST 라 공통 헤더 검사가 키를 요구한다(조회라 저장·재생 없음 — 호출마다 새 값, cdc-location 0.4.1)
                .header("Idempotency-Key", "cdc-inventory-location-lookup-" + UUID.randomUUID())
                .header("X-Source-System", "cdc-inventory-service")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("centerId", centerId, "locationCodes", chunk))
                .retrieve()
                .body(String.class);
        JsonNode n = mapper.readTree(raw == null ? "{}" : raw);
        for (JsonNode it : n.path("items")) {
          if ("PICK_FACE".equals(it.path("locationType").asString())) {
            out.add(it.path("locationCode").asString());
          }
        }
      }
    } catch (RuntimeException e) {
      log.warn("cdc-location 위치 조회 실패 — 피킹 선반 먼저 없이 위치 코드 순으로 할당: {}", e.toString());
      return Set.of();
    }
    return out;
  }
}
