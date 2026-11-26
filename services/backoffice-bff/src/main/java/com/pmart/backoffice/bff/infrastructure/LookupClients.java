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
    FULFILLMENT("mfc-fulfillment-service"),
    /** 화면 라이더 번호·가린 이름(ADR-0070, 2026-11-18 — 직원 찾기 riderIds). */
    RIDER("rider-service"),
    /** 직원 "이름 · 사번"(HR-4 hr 0.8.0 `GET /hr/employee-labels`, 2026-11-19). */
    HR("hr-service"),
    /** 고객 찾기·상세(BF-2, CU-4 customer 0.11.0, 2026-11-20). */
    CUSTOMER("customer-service"),
    /** 고객 정보 열람 기록(BF-5, audit 0.3.0 `GET /pii-access-logs` — OPERATOR_ADMIN 만, 2026-11-23). */
    AUDIT("audit-service"),
    /** 고객별 구매 요약·마지막 구매 고객·구매 지표(O-12·O-13 order 0.24.0·0.25.0 — 2026-11-26). 주문 칸과 따로 표시. */
    ORDER_METRICS("order-service/metrics"),
    /** 고객 마지막 로그인·토큰 갱신(CU-6 auth 0.12.0 내부 `/internal/customers/activities` — 2026-11-26). */
    AUTH("auth-service");

    public final String system;

    Source(String system) {
      this.system = system;
    }
  }

  private final Map<Source, RestClient> clients;
  private final ObjectMapper mapper;

  public LookupClients(RestClient.Builder builder, BffProperties props, ObjectMapper mapper) {
    // 원천이 10개를 넘어(Map.of 상한) EnumMap 으로(2026-11-20 CUSTOMER)
    Map<Source, String> urls = new java.util.EnumMap<>(Source.class);
    urls.put(Source.ORDER, props.orderBaseUrl());
    urls.put(Source.EXECUTION, props.executionBaseUrl());
    urls.put(Source.DISPATCH, props.dispatchBaseUrl());
    urls.put(Source.CLAIM, props.claimBaseUrl());
    urls.put(Source.CASE, props.caseBaseUrl());
    urls.put(Source.INQUIRY, props.caseBaseUrl());
    urls.put(Source.TRACKING, props.trackingBaseUrl());
    urls.put(Source.FULFILLMENT, props.mfcFulfillmentBaseUrl());
    urls.put(Source.RIDER, props.riderBaseUrl());
    urls.put(Source.HR, props.hrBaseUrl());
    urls.put(Source.CUSTOMER, props.customerBaseUrl());
    urls.put(Source.AUDIT, props.auditBaseUrl());
    urls.put(Source.ORDER_METRICS, props.orderBaseUrl());
    urls.put(Source.AUTH, props.authBaseUrl());
    Map<Source, RestClient> built = new java.util.EnumMap<>(Source.class);
    urls.forEach((k, v) -> built.put(k, builder.clone().baseUrl(v).build()));
    this.clients = java.util.Collections.unmodifiableMap(built);
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
                          query.forEach(
                              (k, v) -> {
                                if (v instanceof java.util.Collection<?> c) {
                                  b.queryParam(k, c.toArray()); // 반복 파라미터(state=A&state=B)
                                } else {
                                  b.queryParam(k, v);
                                }
                              });
                          return b.build(uriVariables);
                        })
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .header("X-Source-System", "backoffice-bff")
                    .retrieve()
                    .body(String.class));
    return parse(raw);
  }

  /** 원천 본문 읽기 — JSON 이 아니면(프록시 HTML 등) 원천 장애로(0.9.2, 화면 전체 500 이 아니라 그 원천만 불명). */
  private JsonNode parse(@org.jspecify.annotations.Nullable String raw) {
    try {
      return mapper.readTree(raw == null ? "{}" : raw);
    } catch (tools.jackson.core.JacksonException e) {
      throw com.pmart.common.web.PmartException.dependencyUnavailable("원천 응답을 읽을 수 없습니다.", e);
    }
  }
}
