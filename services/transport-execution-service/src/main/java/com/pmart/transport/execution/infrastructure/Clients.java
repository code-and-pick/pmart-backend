package com.pmart.transport.execution.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * cdc-outbound(출발 검증)·transport-planning(운행 상태 사본)·fleet(점유 해제) 호출. 내부 경로라 토큰 없이 X-Source-System 만
 * (ADR-0026). 4xx 는 그 코드로 PmartException, 미전달·5xx·시간 초과는 {@link Downstream} 이
 * DEPENDENCY_UNAVAILABLE·UNKNOWN_RESULT 로 — {@link #unknown} 이면 같은 키로 다시.
 */
@Component
public class Clients {

  public static final String SOURCE = "transport-execution-service";

  private final RestClient outbound;
  private final RestClient planning;
  private final RestClient fleet;
  private final ObjectMapper mapper;

  public Clients(
      RestClient.Builder builder,
      ObjectMapper mapper,
      @Value("${pmart.execution.cdc-outbound-base-url:http://cdc-outbound-service:8603}")
          String outboundUrl,
      @Value("${pmart.execution.planning-base-url:http://transport-planning-service:8701}")
          String planningUrl,
      @Value("${pmart.execution.fleet-base-url:http://fleet-service:8703}") String fleetUrl) {
    this.outbound = builder.clone().baseUrl(outboundUrl).build();
    this.planning = builder.clone().baseUrl(planningUrl).build();
    this.fleet = builder.clone().baseUrl(fleetUrl).build();
    this.mapper = mapper;
  }

  /** cdc-outbound 출발 검증 — 키 = departureId(같은 출발은 같은 결과). */
  public JsonNode validateDeparture(String departureId, Map<String, Object> body) {
    return call(
        "cdc-outbound-service",
        () ->
            outbound
                .post()
                .uri("/api/v1/internal/departure-validations")
                .header("X-Source-System", SOURCE)
                .header("Idempotency-Key", departureId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class));
  }

  public void tripUpdate(String tripId, int version, Map<String, Object> body) {
    call(
        "transport-planning-service",
        () ->
            planning
                .post()
                .uri("/api/v1/internal/transport-orders/{t}/trip-updates", tripId)
                .header("X-Source-System", SOURCE)
                .header("Idempotency-Key", "exec-trip-" + tripId + "-v" + version)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class));
  }

  public void releaseReservation(String reservationId) {
    call(
        "fleet-service",
        () ->
            fleet
                .post()
                .uri("/api/v1/internal/reservations/{r}/releases", reservationId)
                .header("X-Source-System", SOURCE)
                .header("Idempotency-Key", "exec-end-" + reservationId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("reason", "TRIP_ENDED"))
                .retrieve()
                .body(String.class));
  }

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
