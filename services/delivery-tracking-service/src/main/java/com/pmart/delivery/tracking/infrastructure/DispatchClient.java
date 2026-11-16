package com.pmart.delivery.tracking.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.delivery.tracking.application.TrackingProperties;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** delivery-dispatch 내부 — 배송의 픽업지·도착지 좌표(TR-1 ETA). 좌표는 로그에 남기지 않는다. */
@Component
public class DispatchClient {

  public record RoutePoints(
      double pickupLat,
      double pickupLng,
      double dropLat,
      double dropLng,
      @Nullable Integer pickupToDropS) {}

  private final RestClient dispatch;
  private final ObjectMapper mapper;

  public DispatchClient(RestClient.Builder builder, ObjectMapper mapper, TrackingProperties props) {
    this.dispatch = builder.clone().baseUrl(props.dispatchBaseUrl()).build();
    this.mapper = mapper;
  }

  /** 좌표가 아직 없거나 없는 배송이면 empty. 장애는 Downstream 규칙(예외). */
  public Optional<RoutePoints> routePoints(String deliveryId) {
    String body;
    try {
      body =
          Downstream.call(
              "delivery-dispatch route-points",
              () ->
                  dispatch
                      .get()
                      .uri("/api/v1/internal/deliveries/{id}/route-points", deliveryId)
                      .retrieve()
                      .body(String.class));
    } catch (HttpClientErrorException.NotFound e) {
      return Optional.empty();
    }
    JsonNode n = mapper.readTree(body == null ? "{}" : body);
    if (!n.path("pickup").path("lat").isNumber() || !n.path("drop").path("lat").isNumber()) {
      return Optional.empty();
    }
    return Optional.of(
        new RoutePoints(
            n.path("pickup").path("lat").asDouble(),
            n.path("pickup").path("lng").asDouble(),
            n.path("drop").path("lat").asDouble(),
            n.path("drop").path("lng").asDouble(),
            n.path("pickupToDropS").isNumber() ? n.path("pickupToDropS").asInt() : null));
  }
}
