package com.pmart.cdc.quality.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * cdc-inventory 품질 전기({@code POST /internal/quality-postings}, 0.15.0). 내부 경로라 토큰 없이
 * X-Source-System 만(ADR-0026). 4xx 는 그 코드로 PmartException, 미전달·5xx·시간 초과는 {@link Downstream} 이
 * DEPENDENCY_UNAVAILABLE·UNKNOWN_RESULT 로 — 호출자는 {@link #unknown} 이면 같은 키로 다시 보낸다.
 */
@Component
public class InventoryClient {

  static final String INVENTORY = "cdc-inventory-service";
  public static final String SOURCE = "cdc-quality-service";

  /** 반영 결과 — appliedQty·commitId(0 이면 원결과 없음). */
  public record Posted(int appliedQty, String commitId) {}

  private final RestClient http;
  private final ObjectMapper mapper;

  public InventoryClient(
      RestClient.Builder builder,
      ObjectMapper mapper,
      @Value("${pmart.quality.cdc-inventory-base-url:http://localhost:8601}") String baseUrl) {
    this.http = builder.clone().baseUrl(baseUrl).build();
    this.mapper = mapper;
  }

  public Posted qualityPosting(String key, Map<String, Object> body) {
    String raw;
    try {
      raw =
          Downstream.call(
              INVENTORY,
              () ->
                  http.post()
                      .uri("/api/v1/internal/quality-postings")
                      .header("Idempotency-Key", key)
                      .header("X-Source-System", SOURCE)
                      .contentType(MediaType.APPLICATION_JSON)
                      .body(body)
                      .retrieve()
                      .body(String.class));
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
    JsonNode n = mapper.readTree(raw == null ? "{}" : raw);
    return new Posted(n.path("appliedQty").asInt(0), n.path("resultId").asString(null));
  }

  /** 다시 보내야 하는 실패(응답 불명·전달 안 됨). */
  public static boolean unknown(PmartException e) {
    return e.status().is5xxServerError()
        || "UNKNOWN_RESULT".equals(e.code())
        || ErrorCode.DEPENDENCY_UNAVAILABLE.name().equals(e.code());
  }

  public static String reason(PmartException e) {
    String r = e.code() + ": " + e.getMessage();
    return r.length() > 300 ? r.substring(0, 300) : r;
  }
}
