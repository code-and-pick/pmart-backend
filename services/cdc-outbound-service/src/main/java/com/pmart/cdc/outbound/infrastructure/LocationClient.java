package com.pmart.cdc.outbound.infrastructure;

import com.pmart.cdc.outbound.application.OutboundProperties;
import com.pmart.common.web.Downstream;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * cdc-location 위치 일괄 조회(cdc-location 0.5.0 {@code POST /internal/location-lookups}의 pickMethod) —
 * Wave 릴리스 때 줄마다 피킹 방식을 정한다(0.9.0). 조회가 안 되면 릴리스를 하지 않는다(503 — 재고 조회와 같음): 방식이 빠진 채로 묶음 작업을 잘못 만들지
 * 않게. 내부 경로라 토큰 없이 X-Source-System 만(ADR-0026).
 */
@Component
public class LocationClient {

  public static final String LOCATION = "cdc-location-service";
  static final int CHUNK = 2000;

  private final RestClient http;
  private final ObjectMapper mapper;

  public LocationClient(RestClient.Builder builder, OutboundProperties props, ObjectMapper mapper) {
    this.http = builder.clone().baseUrl(props.cdcLocationBaseUrl()).build();
    this.mapper = mapper;
  }

  /** 위치 코드 → 통로 피킹 방식. 방식이 없는 위치(팔레트 랙·설정 없음·없는 코드)는 빠진다. */
  public Map<String, String> pickMethods(String centerId, Collection<String> codes) {
    Map<String, String> out = new HashMap<>();
    List<String> all = new ArrayList<>(new TreeSet<>(codes));
    for (int i = 0; i < all.size(); i += CHUNK) {
      List<String> chunk = all.subList(i, Math.min(all.size(), i + CHUNK));
      String raw;
      try {
        raw =
            Downstream.call(
                LOCATION,
                () ->
                    http.post()
                        .uri("/api/v1/internal/location-lookups")
                        // POST 라 공통 헤더 검사가 키를 요구한다(조회라 저장·재생 없음 — 호출마다 새 값)
                        .header(
                            "Idempotency-Key", "cdc-outbound-location-lookup-" + UUID.randomUUID())
                        .header("X-Source-System", InventoryClient.SOURCE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("centerId", centerId, "locationCodes", chunk))
                        .retrieve()
                        .body(String.class));
      } catch (HttpClientErrorException e) {
        throw new PmartException(
            ErrorCode.DEPENDENCY_UNAVAILABLE,
            "cdc-location 위치 조회가 거절됐습니다(HTTP " + e.getStatusCode().value() + ").");
      }
      JsonNode n = mapper.readTree(raw == null ? "{}" : raw);
      for (JsonNode it : n.path("items")) {
        String m = it.path("pickMethod").asString(null);
        if (m != null && !m.isBlank()) {
          out.put(it.path("locationCode").asString(), m);
        }
      }
    }
    return out;
  }
}
