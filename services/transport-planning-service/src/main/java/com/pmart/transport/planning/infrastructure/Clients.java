package com.pmart.transport.planning.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * cdc-outbound(transport-feed)·fleet(차량·기사·점유)·transport-execution(운행) 호출. 내부 경로라 토큰 없이
 * X-Source-System 만(ADR-0026). 4xx 는 그 코드로 PmartException, 미전달·5xx·시간 초과는 {@link Downstream} 이
 * DEPENDENCY_UNAVAILABLE·UNKNOWN_RESULT 로 — {@link #unknown} 이면 같은 키로 다시.
 */
@Component
public class Clients {

  public static final String SOURCE = "transport-planning-service";

  public record FeedItem(
      String trfId,
      @Nullable String trfNumber,
      int trfVersion,
      String mfcId,
      String state,
      Instant dueAt,
      int approvedQty,
      int allocatedQty,
      List<String> tempClasses,
      int boxCount,
      @Nullable Integer rtCount,
      long weightG,
      Instant receivedAt) {}

  public record VehicleInfo(
      String vehicleId,
      @Nullable String plateNo,
      String contractType,
      String tempClass,
      int maxRt,
      int maxWeightKg,
      boolean active) {}

  public record DriverInfo(String driverId, String displayName, boolean active) {}

  private final RestClient outbound;
  private final RestClient fleet;
  private final RestClient execution;
  private final RestClient location;
  private final ObjectMapper mapper;

  public Clients(
      RestClient.Builder builder,
      ObjectMapper mapper,
      @Value("${pmart.planning.cdc-outbound-base-url:http://cdc-outbound-service:8603}")
          String outboundUrl,
      @Value("${pmart.planning.fleet-base-url:http://fleet-service:8703}") String fleetUrl,
      @Value("${pmart.planning.execution-base-url:http://transport-execution-service:8702}")
          String executionUrl,
      @Value("${pmart.planning.cdc-location-base-url:http://cdc-location-service:8602}")
          String locationUrl) {
    this.location = builder.clone().baseUrl(locationUrl).build();
    this.outbound = builder.clone().baseUrl(outboundUrl).build();
    this.fleet = builder.clone().baseUrl(fleetUrl).build();
    this.execution = builder.clone().baseUrl(executionUrl).build();
    this.mapper = mapper;
  }

  // ---------- cdc-outbound ----------

  public List<FeedItem> feed(String cdcId, Instant from, Instant to) {
    JsonNode n =
        call(
            "cdc-outbound-service",
            () ->
                outbound
                    .get()
                    .uri(
                        "/api/v1/internal/transport-feed?cdcId={c}&dueFrom={f}&dueTo={t}",
                        cdcId,
                        from.toString(),
                        to.toString())
                    .header("X-Source-System", SOURCE)
                    .retrieve()
                    .body(String.class));
    List<FeedItem> out = new ArrayList<>();
    for (JsonNode i : n.path("items")) {
      List<String> temps = new ArrayList<>();
      for (JsonNode t : i.path("tempClasses")) {
        temps.add(t.asString());
      }
      JsonNode rt = i.path("rtCount");
      out.add(
          new FeedItem(
              i.path("trfId").asString(),
              i.path("trfNumber").asString(null),
              i.path("trfVersion").asInt(),
              i.path("mfcId").asString(null),
              i.path("state").asString(),
              Instant.parse(i.path("dueAt").asString()),
              i.path("approvedQty").asInt(),
              i.path("allocatedQty").asInt(),
              temps,
              i.path("boxCount").asInt(),
              rt.isNumber() ? rt.asInt() : null,
              i.path("weightG").asLong(),
              i.path("receivedAt").isString()
                  ? Instant.parse(i.path("receivedAt").asString())
                  : Instant.now()));
    }
    return out;
  }

  // ---------- fleet ----------

  public VehicleInfo vehicle(String vehicleId) {
    JsonNode n =
        call(
            "fleet-service",
            () ->
                fleet
                    .get()
                    .uri("/api/v1/vehicles/{v}", vehicleId)
                    .header("X-Source-System", SOURCE)
                    .retrieve()
                    .body(String.class));
    return new VehicleInfo(
        n.path("vehicleId").asString(),
        n.path("plateNo").asString(null),
        n.path("contractType").asString(),
        n.path("tempClass").asString(),
        n.path("maxRt").asInt(),
        n.path("maxWeightKg").asInt(),
        n.path("active").asBoolean());
  }

  public DriverInfo driver(String driverId) {
    JsonNode n =
        call(
            "fleet-service",
            () ->
                fleet
                    .get()
                    .uri("/api/v1/drivers/{d}", driverId)
                    .header("X-Source-System", SOURCE)
                    .retrieve()
                    .body(String.class));
    return new DriverInfo(
        n.path("driverId").asString(),
        n.path("displayName").asString(),
        n.path("active").asBoolean());
  }

  /** 점유 — reservationId. 키 = 운송지시서·차량·기사·창(같은 계획은 같은 키). */
  public String reserve(
      String key, String vehicleId, String driverId, Instant start, Instant end, String purpose) {
    JsonNode n =
        call(
            "fleet-service",
            () ->
                fleet
                    .post()
                    .uri("/api/v1/internal/reservations")
                    .header("X-Source-System", SOURCE)
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(
                        Map.of(
                            "vehicleId",
                            vehicleId,
                            "driverId",
                            driverId,
                            "window",
                            Map.of("start", start.toString(), "end", end.toString()),
                            "purposeRef",
                            purpose))
                    .retrieve()
                    .body(String.class));
    return n.path("reservationId").asString();
  }

  public void release(String key, String reservationId, String reason) {
    call(
        "fleet-service",
        () ->
            fleet
                .post()
                .uri("/api/v1/internal/reservations/{r}/releases", reservationId)
                .header("X-Source-System", SOURCE)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("reason", reason))
                .retrieve()
                .body(String.class));
  }

  // ---------- cdc-location(도크 — 0.3.0) ----------

  public JsonNode reserveDock(
      String key,
      String cdcId,
      String tripId,
      @Nullable String vehicleId,
      String temp,
      String routeId,
      @Nullable String plateNo,
      Instant start,
      Instant end) {
    Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("centerId", cdcId);
    body.put("tripId", tripId);
    body.put("vehicleId", vehicleId);
    body.put("purpose", "OUTBOUND_LOADING");
    body.put("temperatureZone", temp);
    body.put("routeId", routeId);
    body.put("plateNo", plateNo);
    body.put("window", Map.of("start", start.toString(), "end", end.toString()));
    return call(
        "cdc-location-service",
        () ->
            location
                .post()
                .uri("/api/v1/internal/dock-reservations")
                .header("X-Source-System", SOURCE)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class));
  }

  public void releaseDock(String key, String reservationId, String reason) {
    call(
        "cdc-location-service",
        () ->
            location
                .post()
                .uri("/api/v1/internal/dock-reservations/{r}/releases", reservationId)
                .header("X-Source-System", SOURCE)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("reason", reason))
                .retrieve()
                .body(String.class));
  }

  // ---------- transport-execution ----------

  public JsonNode createTrip(String key, Map<String, Object> body) {
    return call(
        "transport-execution-service",
        () ->
            execution
                .post()
                .uri("/api/v1/internal/trips")
                .header("X-Source-System", SOURCE)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class));
  }

  public JsonNode cancelTrip(String key, String tripId, String reason) {
    return call(
        "transport-execution-service",
        () ->
            execution
                .post()
                .uri("/api/v1/internal/trips/{t}/cancellations", tripId)
                .header("X-Source-System", SOURCE)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("reason", reason))
                .retrieve()
                .body(String.class));
  }

  // ---------- 공통 ----------

  private JsonNode call(String target, Supplier<String> s) {
    try {
      String raw = Downstream.call(target, s::get);
      return mapper.readTree(raw == null || raw.isBlank() ? "{}" : raw);
    } catch (HttpClientErrorException e) {
      JsonNode p;
      try {
        p = mapper.readTree(e.getResponseBodyAsString());
      } catch (RuntimeException ex) {
        p = mapper.createObjectNode();
      }
      throw new PmartException(
          p.path("code").asString("HTTP_" + e.getStatusCode().value()),
          HttpStatus.valueOf(e.getStatusCode().value()),
          p.path("detail").asString(e.getStatusText()),
          false,
          List.of());
    }
  }

  public static boolean unknown(PmartException e) {
    return e.status().is5xxServerError()
        || "UNKNOWN_RESULT".equals(e.code())
        || ErrorCode.DEPENDENCY_UNAVAILABLE.name().equals(e.code());
  }
}
