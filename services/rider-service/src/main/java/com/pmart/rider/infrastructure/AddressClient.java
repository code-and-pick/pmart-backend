package com.pmart.rider.infrastructure;

import com.pmart.common.web.Downstream;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 거주지 주소 확인(2026-11-17 — 라이더 앱 요청 §12, 사용자 "서버가 주소 변환으로 확인"): delivery-serviceability 내부 주소 → 좌표
 * ({@code POST /internal/address-geocodes}, 주문 배송지와 같은 정확 일치 변환). 결과가 있으면 실제 주소. 주소는 본문으로만(URL·로그에
 * 남기지 않는다). 못 부르면 {@link Downstream} 예외(503).
 */
@Component
public class AddressClient {

  private final RestClient client;
  private final ObjectMapper mapper;

  public AddressClient(
      RestClient.Builder builder,
      ObjectMapper mapper,
      @Value("${pmart.rider.serviceability-base-url:http://delivery-serviceability-service:8401}")
          String baseUrl) {
    this.client = builder.clone().baseUrl(baseUrl).build();
    this.mapper = mapper;
  }

  public boolean exists(String roadAddress) {
    String raw =
        Downstream.call(
            "serviceability address-geocodes",
            () ->
                client
                    .post()
                    .uri("/api/v1/internal/address-geocodes")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .body(Map.of("query", roadAddress))
                    .retrieve()
                    .body(String.class));
    JsonNode n = mapper.readTree(raw == null ? "{}" : raw);
    return n.path("results").isArray() && !n.path("results").isEmpty();
  }
}
