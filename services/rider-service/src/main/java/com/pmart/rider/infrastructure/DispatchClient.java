package com.pmart.rider.infrastructure;

import com.pmart.common.web.Downstream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * delivery-dispatch 내부 호출 — 라이더 여럿이 맡고 있는 배송 수({@code GET
 * /internal/riders/active-delivery-counts?riderIds=}, dispatch 0.15.0, 토큰 없음 — 2026-11-23 라이더 백오피스
 * 목록 "진행 중 n"). 못 부르면 {@link Downstream} 예외 — 부른 쪽이 목록은 그대로 두고 값만 비운다.
 */
@Component
public class DispatchClient {

  private final RestClient client;
  private final ObjectMapper mapper;

  public DispatchClient(
      RestClient.Builder builder,
      ObjectMapper mapper,
      @Value("${pmart.rider.dispatch-base-url:http://delivery-dispatch-service:8404}")
          String baseUrl) {
    this.client = builder.clone().baseUrl(baseUrl).build();
    this.mapper = mapper;
  }

  /** riderId → 맡은 배송 수(50명까지 — 직원 목록 한 쪽). */
  public Map<String, Integer> activeDeliveryCounts(List<String> riderIds) {
    String raw =
        Downstream.call(
            "delivery-dispatch active-delivery-counts",
            () ->
                client
                    .get()
                    .uri(
                        b ->
                            b.path("/api/v1/internal/riders/active-delivery-counts")
                                .queryParam("riderIds", String.join(",", riderIds))
                                .build())
                    .retrieve()
                    .body(String.class));
    JsonNode n = mapper.readTree(raw == null ? "{}" : raw);
    if (!n.path("items").isArray()) {
      throw new IllegalStateException("dispatch 응답에 items 가 없습니다");
    }
    Map<String, Integer> out = new HashMap<>();
    n.path("items").forEach(i -> out.put(i.path("riderId").asString(), i.path("count").asInt()));
    return out;
  }
}
