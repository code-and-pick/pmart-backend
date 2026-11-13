package com.pmart.delivery.dispatch.infrastructure;

import com.pmart.delivery.dispatch.application.DispatchProperties;
import com.pmart.delivery.dispatch.domain.Geo;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Python AI 최적화 서버(delivery-dispatch-optimizer, ADR-0058 결정 9~13) 어댑터 — {@code POST
 * /api/v1/internal/dispatch-plans}. 계약 {@code contracts/openapi/delivery-dispatch-optimizer.yaml}.
 *
 * <p>{@code pmart.dispatch.ai.base-url} 이 비어 있으면 부르지 않는다(처음부터 OR-Tools). 시간 초과(기본 3초)·오류·503 이면
 * empty — 호출자가 OR-Tools 로 대신한다. 좌표·배송 ID 만 보낸다(주소·이름 없음). 이 서버는 Eureka 에 없어 주소를 그대로 쓴다.
 */
@Component
public class AiOptimizerClient {

  private static final Logger log = LoggerFactory.getLogger(AiOptimizerClient.class);

  public record Delivery(
      String deliveryId, Geo at, int directSeconds, int readyInSeconds, boolean bundlable) {}

  public record Rider(
      String riderId, int pickupSeconds, int capacity, List<String> allowedDeliveryIds) {}

  public record Assignment(String riderId, List<String> deliveryIds) {}

  public record Plan(List<Assignment> assignments, String solver) {}

  private final @Nullable RestClient http;

  public AiOptimizerClient(DispatchProperties props) {
    DispatchProperties.Ai ai = props.ai();
    if (ai.baseUrl() == null) {
      this.http = null;
      return;
    }
    JdkClientHttpRequestFactory factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(1))
                .build());
    factory.setReadTimeout(ai.timeout());
    this.http = RestClient.builder().baseUrl(ai.baseUrl()).requestFactory(factory).build();
  }

  public boolean enabled() {
    return http != null;
  }

  public Optional<Plan> plan(
      Geo mfc,
      List<Delivery> deliveries,
      List<Rider> riders,
      int[][] betweenSeconds,
      int maxBundleSize,
      int maxExtraSeconds,
      int lateWeight) {
    if (http == null || deliveries.isEmpty() || riders.isEmpty()) {
      return Optional.empty();
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("mfc", point(mfc));
    List<Map<String, Object>> ds = new ArrayList<>();
    for (Delivery d : deliveries) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("deliveryId", d.deliveryId());
      m.put("at", point(d.at()));
      m.put("directSeconds", d.directSeconds());
      m.put("readyInSeconds", d.readyInSeconds());
      m.put("bundlable", d.bundlable());
      ds.add(m);
    }
    body.put("deliveries", ds);
    List<Map<String, Object>> rs = new ArrayList<>();
    for (Rider r : riders) {
      rs.add(
          Map.of(
              "riderId", r.riderId(),
              "pickupSeconds", r.pickupSeconds(),
              "capacity", r.capacity(),
              "allowedDeliveryIds", r.allowedDeliveryIds()));
    }
    body.put("riders", rs);
    List<List<Integer>> between = new ArrayList<>();
    for (int[] row : betweenSeconds) {
      List<Integer> out = new ArrayList<>();
      for (int v : row) {
        out.add(v < 0 ? null : v);
      }
      between.add(out);
    }
    body.put("betweenSeconds", between);
    body.put("maxBundleSize", maxBundleSize);
    body.put("maxExtraSeconds", maxExtraSeconds);
    body.put("lateWeight", lateWeight);
    try {
      JsonNode res =
          http.post()
              .uri("/api/v1/internal/dispatch-plans")
              .contentType(MediaType.APPLICATION_JSON)
              .header("X-Source-System", "delivery-dispatch-service")
              .body(body)
              .retrieve()
              .body(JsonNode.class);
      if (res == null) {
        return Optional.empty();
      }
      List<Assignment> out = new ArrayList<>();
      for (JsonNode a : res.path("assignments")) {
        List<String> ids = new ArrayList<>();
        a.path("deliveryIds").forEach(x -> ids.add(x.asString()));
        out.add(new Assignment(a.path("riderId").asString(), ids));
      }
      return Optional.of(new Plan(out, res.path("solver").asString("AI")));
    } catch (RuntimeException e) {
      log.warn("AI 최적화 서버 응답 없음·오류 — OR-Tools 로 대신한다: {}", e.getClass().getSimpleName());
      return Optional.empty();
    }
  }

  private static Map<String, Object> point(Geo g) {
    return Map.of("lat", g.lat(), "lng", g.lng());
  }
}
