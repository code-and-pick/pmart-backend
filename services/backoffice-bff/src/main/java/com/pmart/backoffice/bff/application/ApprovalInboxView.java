package com.pmart.backoffice.bff.application;

import static com.pmart.backoffice.bff.application.ViewSupport.text;

import com.pmart.backoffice.bff.infrastructure.ApprovalClient;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;

/**
 * 승인함 화면(BO-10, BR-40): approval-service 승인 목록을 운영자 본인 토큰으로 읽어 화면 항목으로 묶는다. 업무 판단은 하지 않는다 — 승인·결정은
 * approval-service 상세에서. 출처 표시(ComposedView): 직접 조회 성공이면 approval-service DIRECT·CURRENT, 응답이 없으면 빈
 * 목록 + unavailableSources(추정하지 않음). 4xx(운영자 아님·잘못된 필터)는 그대로 돌려준다.
 */
@Service
@EnableConfigurationProperties(BffProperties.class)
public class ApprovalInboxView {

  static final String SOURCE = "approval-service";

  private static final Logger log = LoggerFactory.getLogger(ApprovalInboxView.class);

  private final ApprovalClient approvals;
  private final Clock clock;

  public ApprovalInboxView(ApprovalClient approvals, Clock clock) {
    this.approvals = approvals;
    this.clock = clock;
  }

  public Map<String, Object> view(
      @Nullable String type,
      @Nullable String reviewState,
      @Nullable String centerId,
      int size,
      @Nullable String cursor) {
    String token =
        PmartPrincipal.currentTokenValue()
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "운영자 토큰이 필요합니다."));
    Instant now = clock.instant();
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("composedAt", now.toString());
    JsonNode page;
    try {
      page =
          approvals.list(
              new ApprovalClient.Query(type, reviewState, centerId, size, cursor), token);
    } catch (HttpClientErrorException e) {
      // approval-service 의 명시적 거절은 같은 뜻으로 돌려준다(운영자 아님 → 403, 잘못된 필터 → 400)
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new PmartException(ErrorCode.FORBIDDEN, "승인함은 운영자만 볼 수 있습니다.");
      }
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "승인함 조회 조건이 올바르지 않습니다.");
    } catch (PmartException e) {
      if (e.status().is4xxClientError()) {
        throw e;
      }
      log.warn("승인함: approval-service 조회 실패({}) — 빈 목록 + unavailableSources", e.code());
      out.put("sources", List.of(stamp(now, "UNAVAILABLE", "UNKNOWN")));
      out.put("unavailableSources", List.of(SOURCE));
      out.put("items", List.of());
      out.put("page", Map.of("size", 0));
      return out;
    }
    List<Map<String, Object>> items = new ArrayList<>();
    for (JsonNode a : page.path("items")) {
      Map<String, Object> i = new LinkedHashMap<>();
      String approvalId = a.path("approvalId").asString();
      i.put("dossierId", approvalId);
      i.put("approvalId", approvalId);
      i.put("changeRequestId", text(a, "changeRequestId"));
      i.put("approvalType", text(a, "type"));
      i.put("targetService", text(a, "targetService"));
      i.put("targetRef", text(a, "targetRef"));
      i.put("summary", text(a, "summary"));
      i.put("requestState", text(a, "requestState"));
      i.put("reviewState", text(a, "reviewState"));
      i.put("executionState", text(a, "executionState"));
      i.put("deliveryState", text(a, "deliveryState"));
      i.put("requestedBy", text(a, "requesterId"));
      i.put("caseId", text(a, "caseId"));
      i.put("submittedAt", text(a, "submittedAt"));
      i.put("expiresAt", text(a, "expiresAt"));
      i.put("source", stamp(now, "CURRENT", "DIRECT"));
      items.add(i);
    }
    out.put("sources", List.of(stamp(now, "CURRENT", "DIRECT")));
    out.put("unavailableSources", List.of());
    out.put("items", items);
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("size", items.size());
    p.put("nextCursor", text(page.path("page"), "nextCursor"));
    out.put("page", p);
    return out;
  }

  private static Map<String, Object> stamp(Instant at, String freshness, String confidence) {
    return ViewSupport.stamp(SOURCE, at, freshness, confidence);
  }
}
