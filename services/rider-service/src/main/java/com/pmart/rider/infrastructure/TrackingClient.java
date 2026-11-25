package com.pmart.rider.infrastructure;

import com.pmart.common.web.Downstream;
import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * delivery-tracking 라이더 마지막 위치({@code GET /internal/riders/{id}/location}, 토큰 없는 내부 호출 — 2026-11-25
 * 배달 지역 바꾸기 위치 검사). 받은 위치가 없으면(404) empty, 못 부르면 {@link Downstream} 예외(503). 좌표는 로그에 남기지 않는다.
 */
@Component
public class TrackingClient {

  /** 마지막 위치 — receivedAt 은 tracking 이 받은 시각(단말 시계와 무관). */
  public record Latest(double lat, double lng, Instant receivedAt) {}

  private final RestClient client;
  private final ObjectMapper mapper;

  public TrackingClient(
      RestClient.Builder builder,
      ObjectMapper mapper,
      @Value("${pmart.rider.tracking-base-url:http://delivery-tracking-service:8406}")
          String baseUrl) {
    this.client = builder.clone().baseUrl(baseUrl).build();
    this.mapper = mapper;
  }

  public Optional<Latest> latest(String riderId) {
    String raw;
    try {
      raw =
          Downstream.call(
              "delivery-tracking rider location",
              () ->
                  client
                      .get()
                      .uri("/api/v1/internal/riders/{id}/location", riderId)
                      .retrieve()
                      .body(String.class));
    } catch (HttpClientErrorException.NotFound e) {
      return Optional.empty();
    }
    JsonNode n = mapper.readTree(raw == null ? "{}" : raw);
    if (!n.path("lat").isNumber()
        || !n.path("lng").isNumber()
        || n.path("receivedAt").isMissingNode()) {
      return Optional.empty();
    }
    return Optional.of(
        new Latest(
            n.path("lat").asDouble(),
            n.path("lng").asDouble(),
            Instant.parse(n.path("receivedAt").asString())));
  }
}
