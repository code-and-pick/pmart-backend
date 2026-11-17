package com.pmart.backoffice.bff.infrastructure;

import com.pmart.backoffice.bff.application.BffProperties;
import com.pmart.common.web.Downstream;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 통합 조회(2026-11-16, BO-11) 원천 호출 — 모두 직원 API 라 <b>호출한 운영자의 토큰</b>을 넘긴다(각 원천이 그 직원 권한으로 다시 판정). 실패는
 * {@link Downstream} 규칙(미전달·503 → DEPENDENCY_UNAVAILABLE, 4xx 는 {@code HttpClientErrorException}
 * 그대로).
 */
@Component
public class LookupClients {

  /** 원천 이름(SourceStamp.system 과 같게). */
  public enum Source {
    ORDER("order-service"),
    EXECUTION("delivery-execution-service"),
    DISPATCH("delivery-dispatch-service"),
    CLAIM("claim-service"),
    CASE("case-service"),
    /** 같은 case-service 지만 권한이 달라(CS_AGENT·OPERATOR_ADMIN 만) 따로 표시한다. */
    INQUIRY("case-service/inquiries"),
    /** 도착 예정(TR-3, 2026-11-17 — 실시간 배송 현황). */
    TRACKING("delivery-tracking-service"),
    /** MFC 현장 상태(F-3, mfc-fulfillment 0.10.0 — 통합 조회). */
    FULFILLMENT("mfc-fulfillment-service");

    public final String system;

    Source(String system) {
      this.system = system;
    }
  }

  private final Map<Source, RestClient> clients;
  private final ObjectMapper mapper;

  public LookupClients(RestClient.Builder builder, BffProperties props, ObjectMapper mapper) {
    this.clients =
        Map.of(
            Source.ORDER, builder.clone().baseUrl(props.orderBaseUrl()).build(),
            Source.EXECUTION, builder.clone().baseUrl(props.executionBaseUrl()).build(),
            Source.DISPATCH, builder.clone().baseUrl(props.dispatchBaseUrl()).build(),
            Source.CLAIM, builder.clone().baseUrl(props.claimBaseUrl()).build(),
            Source.CASE, builder.clone().baseUrl(props.caseBaseUrl()).build(),
            Source.INQUIRY, builder.clone().baseUrl(props.caseBaseUrl()).build(),
            Source.TRACKING, builder.clone().baseUrl(props.trackingBaseUrl()).build(),
            Source.FULFILLMENT, builder.clone().baseUrl(props.mfcFulfillmentBaseUrl()).build());
    this.mapper = mapper;
  }

  /** GET {@code /api/v1}{path} — path 변수는 {@code {0}} 이 아니라 이미 인코딩할 값으로 uriVariables 에. */
  public JsonNode get(
      Source source,
      String bearerToken,
      String path,
      Map<String, ?> uriVariables,
      Map<String, ?> query) {
    String raw =
        Downstream.call(
            source.system + " " + path,
            () ->
                clients
                    .get(source)
                    .get()
                    .uri(
                        u -> {
                          var b = u.path("/api/v1" + path);
                          query.forEach((k, v) -> b.queryParam(k, v));
                          return b.build(uriVariables);
                        })
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .header("X-Source-System", "backoffice-bff")
                    .retrieve()
                    .body(String.class));
    return mapper.readTree(raw == null ? "{}" : raw);
  }
}
