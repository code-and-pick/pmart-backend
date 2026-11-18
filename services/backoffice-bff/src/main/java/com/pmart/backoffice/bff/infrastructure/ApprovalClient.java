package com.pmart.backoffice.bff.infrastructure;

import com.pmart.backoffice.bff.application.BffProperties;
import com.pmart.common.web.Downstream;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * approval-service 승인 목록(GET /api/v1/approvals) — 운영자 API 라서 <b>호출한 운영자의 토큰</b>을 그대로 넘긴다(서비스 간 인증이
 * 아니다, ADR-0026). 결과는 계약 모양 그대로(JsonNode) 받는다.
 */
@Component
public class ApprovalClient {

  private final RestClient approvals;
  private final ObjectMapper mapper;

  public ApprovalClient(RestClient.Builder builder, BffProperties props, ObjectMapper mapper) {
    this.approvals = builder.clone().baseUrl(props.approvalBaseUrl()).build();
    this.mapper = mapper;
  }

  public record Query(
      @Nullable String type,
      @Nullable String reviewState,
      @Nullable String centerId,
      int size,
      @Nullable String cursor,
      /** AP-3(approval 0.4.0): true 면 지금 토큰의 사람이 지금 결정할 수 있는 승인만. null 이면 보내지 않음. */
      @Nullable Boolean decidableByMe) {}

  /**
   * 4xx 는 그대로(호출자 권한·잘못된 필터), 연결 실패·503 은 DEPENDENCY_UNAVAILABLE, 그 밖은 UNKNOWN_RESULT(Downstream).
   */
  public JsonNode list(Query q, String bearerToken) {
    String raw =
        Downstream.call(
            "approval-service approvals",
            () ->
                approvals
                    .get()
                    .uri(
                        u -> {
                          u.path("/api/v1/approvals").queryParam("size", q.size());
                          if (q.type() != null) {
                            u.queryParam("type", q.type());
                          }
                          if (q.reviewState() != null) {
                            u.queryParam("reviewState", q.reviewState());
                          }
                          if (q.centerId() != null) {
                            u.queryParam("centerId", q.centerId());
                          }
                          if (q.decidableByMe() != null) {
                            u.queryParam("decidableByMe", q.decidableByMe());
                          }
                          if (q.cursor() != null) {
                            u.queryParam("cursor", q.cursor());
                          }
                          return u.build();
                        })
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .header("X-Source-System", "backoffice-bff")
                    .retrieve()
                    .body(String.class));
    return mapper.readTree(raw == null ? "{}" : raw);
  }
}
