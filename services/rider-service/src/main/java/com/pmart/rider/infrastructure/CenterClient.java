package com.pmart.rider.infrastructure;

import com.pmart.common.web.Downstream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * center-master 내부 호출 — 배달 지역으로 고른 센터가 운영 중인 MFC 인지({@code GET /internal/centers/{id}}, 토큰 없음 —
 * 2026-11-23 라이더 앱 배달 지역 바꾸기 요청 3). 404 면 없는 센터, 못 부르면 {@link Downstream} 예외(503).
 */
@Component
public class CenterClient {

  private final RestClient client;
  private final ObjectMapper mapper;

  public CenterClient(
      RestClient.Builder builder,
      ObjectMapper mapper,
      @Value("${pmart.rider.center-master-base-url:http://center-master-service:8507}")
          String baseUrl) {
    this.client = builder.clone().baseUrl(baseUrl).build();
    this.mapper = mapper;
  }

  /** 운영 중(ACTIVE)인 MFC 면 true. 없는 센터(404)·다른 종류·운영하지 않음은 false. */
  public boolean activeMfc(String centerId) {
    String raw;
    try {
      raw =
          Downstream.call(
              "center-master center",
              () ->
                  client
                      .get()
                      .uri("/api/v1/internal/centers/{id}", centerId)
                      .retrieve()
                      .body(String.class));
    } catch (HttpClientErrorException.NotFound e) {
      return false;
    }
    JsonNode n = mapper.readTree(raw == null ? "{}" : raw);
    return "MFC".equals(n.path("type").asString(""))
        && "ACTIVE".equals(n.path("state").asString(""));
  }
}
