package com.pmart.backoffice.bff.infrastructure;

import com.pmart.backoffice.bff.application.BffProperties;
import com.pmart.common.web.Downstream;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 처리 대기(BO-12, 2026-11-16) 원천 호출. order-orchestration-service 는 서비스 간 내부 경로(토큰 없음, ADR-0026),
 * order-service 이행 확인 필요는 운영자 API 라 <b>호출한 운영자의 토큰</b>을 넘긴다. 실패는 {@link Downstream} 규칙(미전달·503 →
 * DEPENDENCY_UNAVAILABLE, 4xx 그대로).
 */
@Component
public class PendingWorkClients {

  private final RestClient orchestration;
  private final RestClient order;
  private final RestClient mfc;
  private final ObjectMapper mapper;

  public PendingWorkClients(RestClient.Builder builder, BffProperties props, ObjectMapper mapper) {
    this.orchestration = builder.clone().baseUrl(props.orchestrationBaseUrl()).build();
    this.order = builder.clone().baseUrl(props.orderBaseUrl()).build();
    this.mfc = builder.clone().baseUrl(props.mfcFulfillmentBaseUrl()).build();
    this.mapper = mapper;
  }

  public JsonNode stuckSagas(int size) {
    String raw =
        Downstream.call(
            "order-orchestration stuck-sagas",
            () ->
                orchestration
                    .get()
                    .uri(
                        u ->
                            u.path("/api/v1/internal/stuck-sagas").queryParam("size", size).build())
                    .header("X-Source-System", "backoffice-bff")
                    .retrieve()
                    .body(String.class));
    return parse(raw);
  }

  public JsonNode openFulfillmentIssues(
      String bearerToken, @org.jspecify.annotations.Nullable String mfcId) {
    String raw =
        Downstream.call(
            "order fulfillment-issues",
            () ->
                order
                    .get()
                    .uri(
                        u -> {
                          var b = u.path("/api/v1/fulfillment-issues").queryParam("state", "OPEN");
                          if (mfcId != null) {
                            b.queryParam("mfcId", mfcId);
                          }
                          return b.build();
                        })
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .header("X-Source-System", "backoffice-bff")
                    .retrieve()
                    .body(String.class));
    return parse(raw);
  }

  /**
   * MFC 실패로 멈춘 중지 요청 목록(PC 3 요청 F-4 — <b>아직 없다</b>, 경로·모양은 그쪽이 정하면 맞춘다). 운영자 토큰 전달. 설정 {@code
   * pmart.bff.mfc-pending-lists} 가 켜졌을 때만 부른다.
   */
  public JsonNode failedStopRequests(
      String bearerToken, @org.jspecify.annotations.Nullable String mfcId) {
    return mfcList(
        "mfc stop-requests", "/api/v1/stop-requests", "failed", "true", mfcId, bearerToken);
  }

  /** MFC 재고가 거절한 인계 목록(F-4 — mfc-fulfillment 0.9.0). {@link #failedStopRequests} 와 같다. */
  public JsonNode rejectedHandovers(
      String bearerToken, @org.jspecify.annotations.Nullable String mfcId) {
    return mfcList(
        "mfc package-handovers",
        "/api/v1/package-handovers",
        "status",
        "REJECTED",
        mfcId,
        bearerToken);
  }

  /** MFC 목록 한 쪽 크기(MFC 상한 200)·최대 쪽 수 — 넘으면 truncated. */
  static final int MFC_PAGE_SIZE = 200;

  static final int MFC_MAX_PAGES = 5;

  /**
   * MFC 목록을 커서로 끝까지(최대 {@link #MFC_MAX_PAGES} 쪽) 읽어 {@code {items, truncated}} 로 합친다(0.9.1 — 전에는
   * 200건 한 쪽만). 한 쪽이라도 실패하면 예외 — 반만 읽힌 목록을 내보내지 않는다(호출 쪽이 MFC 항목 전체를 뺀다).
   */
  private JsonNode mfcList(
      String what,
      String path,
      String key,
      String value,
      @org.jspecify.annotations.Nullable String mfcId,
      String bearerToken) {
    ArrayNode all = mapper.createArrayNode();
    String cursor = null;
    boolean truncated = false;
    for (int page = 0; ; page++) {
      String c = cursor;
      String raw =
          Downstream.call(
              what,
              () ->
                  mfc.get()
                      .uri(
                          u -> {
                            var b =
                                u.path(path)
                                    .queryParam(key, value)
                                    .queryParam("size", MFC_PAGE_SIZE);
                            if (mfcId != null) {
                              b.queryParam("mfcId", mfcId);
                            }
                            if (c != null) {
                              b.queryParam("cursor", c);
                            }
                            return b.build();
                          })
                      .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                      .header("X-Source-System", "backoffice-bff")
                      .retrieve()
                      .body(String.class));
      JsonNode n = parse(raw);
      n.path("items").forEach(all::add);
      JsonNode next = n.path("page").path("nextCursor");
      cursor = next.isString() && !next.asString().isBlank() ? next.asString() : null;
      if (cursor == null) {
        break;
      }
      if (page + 1 >= MFC_MAX_PAGES) {
        truncated = true;
        break;
      }
    }
    ObjectNode out = mapper.createObjectNode();
    out.set("items", all);
    out.put("truncated", truncated);
    return out;
  }

  /** 실패로 멈춘 집품 뒤 중지 다시 시도 — MFC 내부 경로(0.3.1), StopRequestResult 그대로. */
  public JsonNode retryStopRequest(
      String stopRequestId, String idempotencyKey, String operatorId, String reason) {
    return mfcPost(
        "mfc stop-request retry",
        "/api/v1/internal/stop-requests/{id}/retries",
        stopRequestId,
        idempotencyKey,
        operatorId,
        reason);
  }

  /** 재고가 거절한 인계 다시 보내기 — MFC 내부 경로(0.4.1, D-8), HandoverCommitResult 그대로. */
  public JsonNode retryHandover(
      String handoverId, String idempotencyKey, String operatorId, String reason) {
    return mfcPost(
        "mfc handover retry",
        "/api/v1/internal/package-handovers/{id}/retries",
        handoverId,
        idempotencyKey,
        operatorId,
        reason);
  }

  /** 재시도 — 본문 {operatorId, reason}(F-4, mfc-fulfillment 0.9.0: 실제로 다시 시도했을 때 재시도 기록으로 남김). */
  private JsonNode mfcPost(
      String what,
      String path,
      String id,
      String idempotencyKey,
      String operatorId,
      String reason) {
    String raw =
        Downstream.call(
            what,
            () ->
                mfc.post()
                    .uri(path, id)
                    .header("Idempotency-Key", idempotencyKey)
                    .header("X-Source-System", "backoffice-bff")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("operatorId", operatorId, "reason", reason))
                    .retrieve()
                    .body(String.class));
    return parse(raw);
  }

  /** 수동 조치 전달 — 대상 CommandResponse 그대로. */
  public JsonNode manualAction(String sagaId, String idempotencyKey, Map<String, Object> body) {
    String raw =
        Downstream.call(
            "order-orchestration manual-actions",
            () ->
                orchestration
                    .post()
                    .uri("/api/v1/internal/sagas/{id}/manual-actions", sagaId)
                    .header("Idempotency-Key", idempotencyKey)
                    .header("X-Source-System", "backoffice-bff")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class));
    return parse(raw);
  }

  /** 수동 조치 결과 조회(0.12.0 명령 결과) — 원천 ManualAction 그대로. */
  public JsonNode getManualAction(String sagaId, String requestId) {
    String raw =
        Downstream.call(
            "order-orchestration manual-action result",
            () ->
                orchestration
                    .get()
                    .uri("/api/v1/internal/sagas/{id}/manual-actions/{rid}", sagaId, requestId)
                    .header("X-Source-System", "backoffice-bff")
                    .retrieve()
                    .body(String.class));
    return parse(raw);
  }

  /** 중지 요청 판정 다시 읽기(0.12.0 명령 결과) — MFC 내부 경로, StopRequestResult 그대로. */
  public JsonNode getStopRequest(String stopRequestId) {
    String raw =
        Downstream.call(
            "mfc stop-request result",
            () ->
                mfc.get()
                    .uri("/api/v1/internal/stop-requests/{id}", stopRequestId)
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
