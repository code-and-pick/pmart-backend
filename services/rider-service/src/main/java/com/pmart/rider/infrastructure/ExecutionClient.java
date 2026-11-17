package com.pmart.rider.infrastructure;

import com.pmart.common.web.Downstream;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * delivery-execution 내부 호출 — 라이더 탈퇴를 막는 것({@code GET /internal/riders/{id}/withdrawal-blockers},
 * execution 0.12.0): 진행 중 배송·확정 전 수행료·산정 불가 조정 대기·열린 도움요청. {@code /internal} 은 토큰 없이. 못 부르면 {@link
 * Downstream} 예외(503) — 탈퇴를 진행하지 않는다.
 */
@Component
public class ExecutionClient {

  private final RestClient client;
  private final ObjectMapper mapper;

  public ExecutionClient(
      RestClient.Builder builder,
      ObjectMapper mapper,
      @Value("${pmart.rider.execution-base-url:http://delivery-execution-service:8405}")
          String baseUrl) {
    this.client = builder.clone().baseUrl(baseUrl).build();
    this.mapper = mapper;
  }

  public List<String> withdrawalBlockers(String riderId) {
    String raw =
        Downstream.call(
            "delivery-execution withdrawal-blockers",
            () ->
                client
                    .get()
                    .uri("/api/v1/internal/riders/{id}/withdrawal-blockers", riderId)
                    .retrieve()
                    .body(String.class));
    JsonNode n = mapper.readTree(raw == null ? "{}" : raw);
    if (!n.path("blockers").isArray()) {
      throw new IllegalStateException("execution 응답에 blockers 가 없습니다");
    }
    return n.path("blockers").valueStream().map(JsonNode::asString).toList();
  }
}
