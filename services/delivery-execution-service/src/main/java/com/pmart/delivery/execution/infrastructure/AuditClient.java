package com.pmart.delivery.execution.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.common.web.PmartException;
import java.time.Instant;
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
 * audit-service 개인정보 열람 기록(BO-25, ADR-0062) {@code POST /internal/pii-access-logs} — 직원용
 * 전달지(0.24.0, 2026-11-18). rider-service 의 같은 이름 클래스와 같은 방식. 미전달·503 → DEPENDENCY_UNAVAILABLE, 시간
 * 초과·그 밖 5xx → UNKNOWN_RESULT — 호출자는 둘 다 주소를 주지 않는다. 본문은 로그에 남기지 않는다. 주소 LAN 기본 {@code
 * http://audit-service:8503}.
 */
@Component
public class AuditClient {

  /** 기록 결과. */
  public record Recorded(String piiAccessLogId, Instant recordedAt) {}

  private final RestClient client;
  private final ObjectMapper mapper;

  public AuditClient(
      RestClient.Builder builder,
      @Value("${pmart.execution.urls.audit:http://audit-service:8503}") String baseUrl,
      ObjectMapper mapper) {
    this.client = builder.clone().baseUrl(baseUrl).build();
    this.mapper = mapper;
  }

  public Recorded recordPiiAccess(String requestId, Map<String, Object> body) {
    try {
      String raw =
          Downstream.call(
              "audit-service pii-access-logs",
              () ->
                  client
                      .post()
                      .uri("/api/v1/internal/pii-access-logs")
                      .header("Idempotency-Key", requestId)
                      .contentType(MediaType.APPLICATION_JSON)
                      .body(body)
                      .retrieve()
                      .body(String.class));
      JsonNode n = mapper.readTree(raw == null ? "{}" : raw);
      String id = n.path("piiAccessLogId").asString("");
      if (id.isBlank() || !n.hasNonNull("recordedAt")) {
        throw PmartException.unknownResult("audit-service 기록 결과를 확인하지 못했습니다.");
      }
      return new Recorded(id, Instant.parse(n.get("recordedAt").asString()));
    } catch (HttpClientErrorException e) {
      String b = e.getResponseBodyAsString();
      JsonNode p = mapper.readTree(b.isBlank() ? "{}" : b);
      throw new PmartException(
          p.path("code").asString("PII_ACCESS_LOG_REJECTED"),
          HttpStatus.valueOf(e.getStatusCode().value()),
          p.path("detail").asString("열람 기록이 거절되었습니다."),
          false,
          List.of());
    }
  }
}
