package com.pmart.rider.infrastructure;

import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/** rider-auth 내부 — 라이더 주체의 로그인 수단·가린 이메일(같은 번호 안내, 2026-11-19). 못 부르면 빈 값(안내만 줄어든다). */
@Component
public class RiderAuthClient {

  private static final Logger log = LoggerFactory.getLogger(RiderAuthClient.class);

  public record Identity(String provider, @Nullable String emailMasked) {}

  private final RestClient client;

  public RiderAuthClient(
      RestClient.Builder builder,
      @Value("${pmart.rider.rider-auth-base-url:http://rider-auth-service:8510}") String baseUrl) {
    this.client = builder.clone().baseUrl(baseUrl).build();
  }

  public Optional<Identity> identity(String riderId) {
    try {
      JsonNode n =
          client
              .get()
              .uri("/api/v1/internal/riders/{id}/identity", riderId)
              .retrieve()
              .body(JsonNode.class);
      if (n == null || !n.path("provider").isString()) {
        return Optional.empty();
      }
      return Optional.of(
          new Identity(
              n.path("provider").asString(),
              n.path("emailMasked").isString() ? n.path("emailMasked").asString() : null));
    } catch (RestClientException e) {
      log.warn("rider-auth 로그인 수단 조회 실패 — 안내 없이: {}", e.getClass().getSimpleName());
      return Optional.empty();
    }
  }
}
