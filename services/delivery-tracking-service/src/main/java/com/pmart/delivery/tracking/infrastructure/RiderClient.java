package com.pmart.delivery.tracking.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.delivery.tracking.application.TrackingProperties;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** rider-service 내부 — 센터의 운행 중 라이더(라이더 관제, 2026-11-17). 가린 이름만 온다. */
@Component
public class RiderClient {

  public record OnDuty(
      String riderId,
      @Nullable String nameMasked,
      @Nullable String vehicleType,
      boolean newDispatchEnabled,
      @Nullable String restriction,
      java.time.@Nullable Instant onDutySince,
      java.time.@Nullable Instant availableSince,
      @Nullable String riderNumber) {}

  private final RestClient rider;
  private final ObjectMapper mapper;

  public RiderClient(RestClient.Builder builder, ObjectMapper mapper, TrackingProperties props) {
    this.rider = builder.clone().baseUrl(props.riderBaseUrl()).build();
    this.mapper = mapper;
  }

  /** 장애는 Downstream 규칙(503 등 예외). */
  public List<OnDuty> onDuty(String mfcId) {
    String body =
        Downstream.call(
            "rider on-duty",
            () ->
                rider
                    .get()
                    .uri("/api/v1/internal/riders/on-duty?mfcId={m}", mfcId)
                    .header("X-Source-System", "delivery-tracking-service")
                    .retrieve()
                    .body(String.class));
    List<OnDuty> out = new ArrayList<>();
    for (JsonNode n : mapper.readTree(body == null ? "{}" : body).path("items")) {
      out.add(
          new OnDuty(
              n.path("riderId").asString(),
              n.path("nameMasked").asString(null),
              n.path("vehicleType").asString(null),
              n.path("operating").path("newDispatchEnabled").asBoolean(false),
              n.path("restriction").asString(null),
              instantOrNull(n.path("onDutySince")),
              instantOrNull(n.path("availableSince")),
              n.path("riderNumber").asString(null)));
    }
    return out;
  }

  private static java.time.@Nullable Instant instantOrNull(JsonNode n) {
    return n.isString() ? java.time.Instant.parse(n.asString()) : null;
  }
}
