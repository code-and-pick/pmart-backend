package com.pmart.backoffice.bff.application;

import com.pmart.backoffice.bff.infrastructure.PendingWorkClients;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;

/**
 * 처리 대기 화면과 수동 조치 전달(BO-12, 2026-11-16 사용자 결정 — 추천대로). OPERATOR_ADMIN 만.
 *
 * <ul>
 *   <li>화면: 멈춘 사가(order-orchestration) + 이행 확인 필요(order, OPEN·모든 센터)를 오래된 것부터. 원천이 응답하지 않으면 그 항목 없이
 *       unavailableSources(추정하지 않음).
 *   <li>명령: {@code order-orchestration-service/saga-requery}(REQUERY_RESULT)·{@code
 *       saga-resolve}(MARK_RESOLVED) 만 — 사유 필수, 운영자 ID 는 토큰에서. 승인은 받지 않는다(돈·재고를 움직이지 않는 조치). 그 밖
 *       422 UNSUPPORTED_ACTION.
 * </ul>
 */
@Service
public class PendingWorkView {

  static final String ORCHESTRATION = "order-orchestration-service";
  static final String ORDER = "order-service";
  static final String MFC = "mfc-fulfillment-service";
  static final Map<String, String> COMMANDS =
      Map.of("saga-requery", "REQUERY_RESULT", "saga-resolve", "MARK_RESOLVED");
  static final Set<String> MFC_COMMANDS = Set.of("stop-retry", "handover-retry");

  private static final Logger log = LoggerFactory.getLogger(PendingWorkView.class);

  private final PendingWorkClients clients;
  private final Clock clock;
  private final boolean mfcLists;

  public PendingWorkView(PendingWorkClients clients, Clock clock, BffProperties props) {
    this.clients = clients;
    this.clock = clock;
    this.mfcLists = Boolean.TRUE.equals(props.mfcPendingLists());
  }

  public Map<String, Object> view() {
    PmartPrincipal p = requireAdmin();
    String token =
        PmartPrincipal.currentTokenValue()
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "운영자 토큰이 필요합니다."));
    Instant now = clock.instant();
    List<Map<String, Object>> items = new ArrayList<>();
    List<Map<String, Object>> sources = new ArrayList<>();
    List<String> unavailable = new ArrayList<>();

    try {
      JsonNode stuck = clients.stuckSagas(200);
      for (JsonNode s : stuck.path("items")) {
        Map<String, Object> i = new LinkedHashMap<>();
        i.put("kind", "STUCK_SAGA");
        i.put("refId", s.path("sagaId").asString());
        i.put("orderId", s.path("orderId").asString());
        i.put("mfcId", null);
        i.put("sagaType", text(s, "sagaType"));
        i.put("state", text(s, "state"));
        i.put(
            "step",
            text(s, "lastErrorStep") != null ? text(s, "lastErrorStep") : text(s, "currentStep"));
        i.put("reasonCode", text(s, "lastErrorCode"));
        i.put("reasonMessage", text(s, "lastErrorMessage"));
        i.put("since", text(s, "updatedAt"));
        List<String> actions = new ArrayList<>();
        s.path("allowedActions").forEach(a -> actions.add(a.asString()));
        i.put("allowedActions", actions);
        i.put("source", stamp(ORCHESTRATION, now, "CURRENT", "DIRECT"));
        items.add(i);
      }
      sources.add(stamp(ORCHESTRATION, now, "CURRENT", "DIRECT"));
    } catch (PmartException | HttpClientErrorException e) {
      log.warn("처리 대기: 멈춘 사가 조회 실패 — {}", e.getClass().getSimpleName());
      unavailable.add(ORCHESTRATION);
      sources.add(stamp(ORCHESTRATION, now, "UNAVAILABLE", "UNKNOWN"));
    }

    try {
      JsonNode issues = clients.openFulfillmentIssues(token);
      for (JsonNode f : issues.path("items")) {
        Map<String, Object> i = new LinkedHashMap<>();
        i.put("kind", "FULFILLMENT_ISSUE");
        i.put("refId", f.path("issueId").asString());
        i.put("orderId", f.path("orderId").asString());
        i.put("mfcId", text(f, "mfcId"));
        i.put("sagaType", null);
        i.put("state", text(f, "state"));
        i.put("step", null);
        i.put("reasonCode", text(f, "reason"));
        i.put(
            "reasonMessage",
            text(f, "sku") == null
                ? null
                : "SKU " + text(f, "sku") + " 보류 " + f.path("blockedQty").asInt());
        i.put("since", text(f, "blockedAt"));
        i.put("allowedActions", List.of("ACKNOWLEDGE"));
        i.put("source", stamp(ORDER, now, "CURRENT", "DIRECT"));
        items.add(i);
      }
      sources.add(stamp(ORDER, now, "CURRENT", "DIRECT"));
    } catch (PmartException | HttpClientErrorException e) {
      log.warn("처리 대기: 이행 확인 필요 조회 실패 — {}", e.getClass().getSimpleName());
      unavailable.add(ORDER);
      sources.add(stamp(ORDER, now, "UNAVAILABLE", "UNKNOWN"));
    }

    boolean truncated = false;
    if (mfcLists) {
      truncated = mfcItems(token, now, items, sources, unavailable);
    }

    items.sort(Comparator.comparing(i -> String.valueOf(i.get("since"))));
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("composedAt", now.toString());
    out.put("sources", sources);
    out.put("unavailableSources", unavailable);
    out.put("items", items);
    out.put("truncated", truncated);
    log.debug("처리 대기 {}건 by {}", items.size(), p.subject());
    return out;
  }

  /** 명령 전달. 대상 CommandResponse 그대로. */
  public JsonNode command(
      String targetService,
      String command,
      String idempotencyKey,
      String targetRef,
      @Nullable String reason) {
    PmartPrincipal p = requireAdmin();
    if (MFC.equals(targetService) && MFC_COMMANDS.contains(command)) {
      return mfcRetry(command, idempotencyKey, targetRef, reason, p.subject());
    }
    String action = COMMANDS.get(command);
    if (!ORCHESTRATION.equals(targetService) || action == null) {
      throw new PmartException(
          "UNSUPPORTED_ACTION",
          HttpStatus.UNPROCESSABLE_CONTENT,
          "지원하지 않는 명령입니다(order-orchestration-service 의 saga-requery·saga-resolve,"
              + " mfc-fulfillment-service 의 stop-retry·handover-retry).",
          false,
          List.of());
    }
    if (reason == null || reason.isBlank()) {
      if (!"saga-requery".equals(command)) {
        throw new PmartException(ErrorCode.VALIDATION_FAILED, "reason(조치 사유)이 필요합니다.");
      }
      // 다시 확인은 메모 선택(클라이언트 2026-11-16) — 기록용 기본 문구
      reason = "결제 결과 다시 확인";
    }
    if (targetRef == null || targetRef.isBlank()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "targetRef(사가 ID)가 필요합니다.");
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("action", action);
    body.put("reason", reason.strip());
    body.put("operatorId", p.subject());
    try {
      return clients.manualAction(targetRef.strip(), idempotencyKey, body);
    } catch (HttpClientErrorException e) {
      // 대상의 업무 거절(422 NOT_REQUERYABLE 등)은 코드·문구 그대로
      JsonNode problem = readProblem(e);
      throw new PmartException(
          problem.path("code").asString("TARGET_REJECTED"),
          HttpStatus.valueOf(e.getStatusCode().value()),
          problem.path("detail").asString("대상 서비스가 거절했습니다."),
          false,
          List.of());
    }
  }

  /**
   * MFC 재시도(BO-12 대상 2·3번). 사유 필수(BO-12 결정, 500자) — 운영자 ID(토큰 주체)·사유를 MFC 에 넘겨 재시도 기록으로 남긴다(F-4,
   * mfc-fulfillment 0.9.0). 응답은 CommandResponse 로 옮긴다(계약 0.4.0).
   */
  private JsonNode mfcRetry(
      String command,
      String idempotencyKey,
      String targetRef,
      @Nullable String reason,
      String operatorId) {
    if (reason == null || reason.isBlank()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "reason(조치 사유)이 필요합니다.");
    }
    if (reason.strip().length() > 500) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "reason(조치 사유)은 500자까지입니다.");
    }
    String memo = reason.strip();
    if (targetRef == null || targetRef.isBlank()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "targetRef(중지 요청 ID 또는 인계 ID)가 필요합니다.");
    }
    String id = targetRef.strip();
    try {
      if ("stop-retry".equals(command)) {
        JsonNode r = clients.retryStopRequest(id, idempotencyKey, operatorId, memo);
        String state = text(r, "state");
        String status;
        String next;
        if ("RESOLVED".equals(state)) {
          status = "APPLIED";
          next = null;
        } else if ("PENDING".equals(state) && text(r, "lastError") != null) {
          status = "REJECTED";
          next = "FIX_AND_RETRY";
        } else if ("PENDING".equals(state)) {
          status = "ACCEPTED";
          next = "QUERY_RESULT";
        } else {
          status = "UNKNOWN_RESULT";
          next = "QUERY_RESULT";
        }
        return commandResponse(status, idempotencyKey, id, next);
      }
      JsonNode r = clients.retryHandover(id, idempotencyKey, operatorId, memo);
      return commandResponse(
          r.path("status").asString("UNKNOWN_RESULT"), idempotencyKey, id, text(r, "nextAction"));
    } catch (PmartException e) {
      // 0.9.2: MFC 응답 시간 초과·5xx = 결과 모름 — 오류 대신 202 UNKNOWN_RESULT(같은 Idempotency-Key 로 다시 보내거나
      // 목록으로 확인)
      if (ErrorCode.UNKNOWN_RESULT.name().equals(e.code())) {
        return commandResponse("UNKNOWN_RESULT", idempotencyKey, id, "QUERY_RESULT");
      }
      throw e;
    } catch (HttpClientErrorException e) {
      JsonNode problem = readProblem(e);
      throw new PmartException(
          problem.path("code").asString("TARGET_REJECTED"),
          HttpStatus.valueOf(e.getStatusCode().value()),
          problem.path("detail").asString("대상 서비스가 거절했습니다."),
          false,
          List.of());
    }
  }

  private static JsonNode commandResponse(
      String status, String requestId, String resultId, @Nullable String nextAction) {
    var n = new tools.jackson.databind.ObjectMapper().createObjectNode();
    n.put("status", status);
    n.put("requestId", requestId);
    n.put("resultId", resultId);
    if (nextAction != null) {
      n.put("nextAction", nextAction);
    }
    return n;
  }

  /** MFC 두 목록(F-4, 설정으로 켰을 때만). 원천 장애면 그 항목 없이 unavailableSources. */
  private boolean mfcItems(
      String token,
      Instant now,
      List<Map<String, Object>> items,
      List<Map<String, Object>> sources,
      List<String> unavailable) {
    try {
      JsonNode stops = clients.failedStopRequests(token);
      for (JsonNode s : stops.path("items")) {
        Map<String, Object> i = new LinkedHashMap<>();
        i.put("kind", "MFC_STOP_FAILED");
        i.put("refId", text(s, "stopRequestId"));
        i.put("orderId", text(s, "orderId"));
        i.put("mfcId", text(s, "mfcId"));
        i.put("sagaType", null);
        i.put("state", text(s, "state"));
        i.put("step", null);
        i.put("reasonCode", code(text(s, "lastError")));
        i.put("reasonMessage", text(s, "lastError"));
        i.put("since", text(s, "failedAt") != null ? text(s, "failedAt") : text(s, "requestedAt"));
        i.put("lastRetry", lastRetry(s));
        i.put("allowedActions", List.of("RETRY"));
        i.put("source", stamp(MFC, now, "CURRENT", "DIRECT"));
        items.add(i);
      }
      JsonNode handovers = clients.rejectedHandovers(token);
      for (JsonNode h : handovers.path("items")) {
        Map<String, Object> i = new LinkedHashMap<>();
        i.put("kind", "MFC_HANDOVER_REJECTED");
        i.put("refId", text(h, "handoverId"));
        i.put("orderId", text(h, "orderId"));
        i.put("mfcId", text(h, "mfcId"));
        i.put("sagaType", null);
        i.put("state", text(h, "status") != null ? text(h, "status") : "REJECTED");
        i.put("step", null);
        i.put("reasonCode", code(text(h, "rejectReason")));
        i.put("reasonMessage", text(h, "rejectReason"));
        i.put("since", text(h, "rejectedAt"));
        i.put("lastRetry", lastRetry(h));
        i.put("allowedActions", List.of("RETRY"));
        i.put("source", stamp(MFC, now, "CURRENT", "DIRECT"));
        items.add(i);
      }
      sources.add(stamp(MFC, now, "CURRENT", "DIRECT"));
      return stops.path("truncated").asBoolean(false)
          || handovers.path("truncated").asBoolean(false);
    } catch (PmartException | HttpClientErrorException e) {
      log.warn("처리 대기: MFC 목록 조회 실패 — {}", e.getClass().getSimpleName());
      // 두 목록 중 하나만 읽힌 채로 내보내지 않는다 — MFC 항목은 모두 빼고 원천 실패로
      items.removeIf(i -> MFC.equals(sourceSystem(i)));
      unavailable.add(MFC);
      sources.add(stamp(MFC, now, "UNAVAILABLE", "UNKNOWN"));
      return false;
    }
  }

  /** MFC 최근 운영자 재시도(F-4 0.9.0 lastRetry) — 없으면 null. */
  private static @Nullable Map<String, Object> lastRetry(JsonNode item) {
    JsonNode r = item.path("lastRetry");
    if (!r.isObject()) {
      return null;
    }
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("operatorId", text(r, "operatorId"));
    m.put("reason", text(r, "reason"));
    m.put("retriedAt", text(r, "retriedAt"));
    return m;
  }

  @SuppressWarnings("unchecked")
  private static @Nullable Object sourceSystem(Map<String, Object> item) {
    Object s = item.get("source");
    return s instanceof Map<?, ?> m ? ((Map<String, Object>) m).get("system") : null;
  }

  /** MFC 사유는 "코드: 상세" — 앞 코드만. */
  private static @Nullable String code(@Nullable String reason) {
    if (reason == null) {
      return null;
    }
    int c = reason.indexOf(':');
    return (c > 0 ? reason.substring(0, c) : reason).strip();
  }

  private JsonNode readProblem(HttpClientErrorException e) {
    try {
      return new tools.jackson.databind.ObjectMapper().readTree(e.getResponseBodyAsString());
    } catch (RuntimeException ex) {
      return new tools.jackson.databind.ObjectMapper().createObjectNode();
    }
  }

  private static PmartPrincipal requireAdmin() {
    PmartPrincipal p =
        PmartPrincipal.current()
            .filter(x -> x.type() == PmartPrincipal.PrincipalType.OPERATOR)
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "운영자만 볼 수 있습니다."));
    if (!p.hasRole("OPERATOR_ADMIN")) {
      throw new PmartException(ErrorCode.FORBIDDEN, "처리 대기는 OPERATOR_ADMIN 만 다룹니다(BO-12).");
    }
    return p;
  }

  private static Map<String, Object> stamp(
      String system, Instant at, String freshness, String confidence) {
    Map<String, Object> s = new LinkedHashMap<>();
    s.put("system", system);
    s.put("asOf", Map.of("asOf", at.toString(), "freshness", freshness));
    s.put("confidence", confidence);
    return s;
  }

  private static @Nullable String text(JsonNode n, String field) {
    JsonNode v = n.path(field);
    return v.isNull() || v.isMissingNode() ? null : v.asString();
  }
}
