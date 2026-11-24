package com.pmart.cdc.outbound.infrastructure;

import com.pmart.cdc.outbound.application.OutboundProperties;
import com.pmart.common.web.Downstream;
import com.pmart.common.web.PmartException;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * cdc-inventory 내부 호출(할당·할당 조회). 수량 정본은 inventory — 4xx 는 inventory 의 코드·설명을 그대로 {@link
 * PmartException} 으로, 미전달은 DEPENDENCY_UNAVAILABLE(503)·응답 불명은 UNKNOWN_RESULT({@link Downstream}).
 * 같은 멱등 키로 다시 부르면 inventory 가 한 번만 반영한다. 내부 경로라 토큰 없이 X-Source-System 만(ADR-0026).
 */
@Component
public class InventoryClient {

  public static final String INVENTORY = "cdc-inventory-service";
  static final String SOURCE = "cdc-outbound-service";

  private final RestClient http;
  private final ObjectMapper mapper;

  public InventoryClient(
      RestClient.Builder builder, OutboundProperties props, ObjectMapper mapper) {
    this.http = builder.clone().baseUrl(props.cdcInventoryBaseUrl()).build();
    this.mapper = mapper;
  }

  /** POST /internal/allocations → Allocation(allocationId·state·lines[].lots). */
  public JsonNode allocate(String key, Map<String, Object> body) {
    return call(
        () ->
            http.post()
                .uri("/api/v1/internal/allocations")
                .header("Idempotency-Key", key)
                .header("X-Source-System", SOURCE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class));
  }

  /** GET /internal/allocations/{id} — 지금 잡힌 LOT·위치(보충 MOV 반영). */
  public JsonNode allocation(String allocationId) {
    return call(
        () ->
            http.get()
                .uri("/api/v1/internal/allocations/{id}", allocationId)
                .header("X-Source-System", SOURCE)
                .retrieve()
                .body(String.class));
  }

  private JsonNode call(Supplier<String> s) {
    try {
      String raw = Downstream.call(INVENTORY, s::get);
      return raw == null ? mapper.createObjectNode() : mapper.readTree(raw);
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
}
