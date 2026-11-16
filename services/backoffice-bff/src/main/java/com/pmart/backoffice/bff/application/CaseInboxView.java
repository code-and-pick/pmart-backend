package com.pmart.backoffice.bff.application;

import com.pmart.backoffice.bff.infrastructure.LookupClients;
import com.pmart.backoffice.bff.infrastructure.LookupClients.Source;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;

/**
 * 사건함 화면 조합(2026-11-16 사용자 "1번부터 진행해" — 계약 0.5.0).
 *
 * <ul>
 *   <li>case-service {@code GET /cases} 를 호출한 직원 토큰으로 — 보이는 범위·정렬·403 은 case 판정 그대로.
 *   <li>사건마다 연결된 배송·주문의 지금 상태를 원천에서(같은 ID 는 한 번). 합치거나 해석하지 않는다 — 원천마다 따로, 실패·403 은 그 칸만 UNKNOWN +
 *       unavailableSources.
 * </ul>
 */
@Service
public class CaseInboxView {

  static final int MAX_SIZE = 50;
  private static final Logger log = LoggerFactory.getLogger(CaseInboxView.class);

  private final LookupClients clients;
  private final Clock clock;

  public CaseInboxView(LookupClients clients, Clock clock) {
    this.clients = clients;
    this.clock = clock;
  }

  /** 사건함 필터(case 목록 질의 이름으로 옮김). */
  public record Filter(
      @Nullable String state,
      @Nullable String kind,
      @Nullable String assignee,
      @Nullable String assigneeGroup,
      @Nullable Boolean unassigned,
      @Nullable Boolean overdue,
      @Nullable String centerId,
      @Nullable String priority,
      @Nullable Integer size,
      @Nullable String cursor) {}

  public Map<String, Object> view(Filter f) {
    PmartPrincipal.current()
        .filter(x -> x.type() == PmartPrincipal.PrincipalType.OPERATOR)
        .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "직원만 쓸 수 있는 화면입니다."));
    String token =
        PmartPrincipal.currentTokenValue()
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "운영자 토큰이 필요합니다."));
    Instant now = clock.instant();
    Set<Source> used = EnumSet.of(Source.CASE);
    Set<Source> failed = EnumSet.noneOf(Source.class);

    Map<String, Object> q = new LinkedHashMap<>();
    put(q, "state", f.state());
    put(q, "kind", f.kind());
    put(q, "assigneeId", f.assignee());
    put(q, "assigneeGroup", f.assigneeGroup());
    put(q, "unassigned", f.unassigned());
    put(q, "overdue", f.overdue());
    put(q, "centerId", f.centerId());
    put(q, "priority", f.priority());
    q.put("size", Math.min(Math.max(f.size() == null ? 20 : f.size(), 1), MAX_SIZE));
    put(q, "cursor", f.cursor());

    JsonNode page;
    try {
      page = clients.get(Source.CASE, token, "/cases", Map.of(), q);
    } catch (HttpClientErrorException e) {
      // 사건함의 주 원천 — 권한·입력 거절은 그대로 돌려준다(빈 목록으로 바꾸지 않음)
      throw new PmartException(
          e.getStatusCode().value() == 403 ? ErrorCode.FORBIDDEN : ErrorCode.VALIDATION_FAILED,
          e.getStatusCode().value() == 403 ? "사건함을 볼 권한이 없습니다." : "사건함 조건이 올바르지 않습니다.");
    } catch (PmartException e) {
      log.warn("사건함: case-service 실패 — {}", e.getClass().getSimpleName());
      failed.add(Source.CASE);
      page = null;
    }

    Map<String, JsonNode> deliveries = new HashMap<>();
    Map<String, JsonNode> orders = new HashMap<>();
    Map<String, Boolean> deliveryOk = new HashMap<>();
    Map<String, Boolean> orderOk = new HashMap<>();
    List<Map<String, Object>> items = new ArrayList<>();
    for (JsonNode c : page == null ? List.<JsonNode>of() : listOf(page.path("items"))) {
      Map<String, Object> i = new LinkedHashMap<>();
      i.put("caseId", text(c, "caseId"));
      i.put("kind", text(c, "kind"));
      i.put("state", text(c, "state"));
      i.put("priority", text(c, "priority"));
      i.put("summary", text(c, "summary"));
      i.put("subjectType", text(c, "subjectType"));
      i.put("subjectRef", text(c, "subjectRef"));
      i.put("centerId", text(c, "centerId"));
      i.put("assigneeId", text(c, "assigneeId"));
      i.put("assigneeGroup", text(c, "assigneeGroup"));
      i.put("slaDueAt", text(c, "slaDueAt"));
      i.put("overdue", c.path("overdue").isBoolean() ? c.path("overdue").asBoolean() : null);
      i.put("openedAt", text(c, "openedAt"));
      i.put("openObligations", c.path("openObligationCount").asInt(0));
      List<Map<String, Object>> refs = new ArrayList<>();
      for (JsonNode r : c.path("refs")) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("kind", text(r, "refType"));
        ref.put("id", text(r, "refId"));
        refs.add(ref);
      }
      i.put("linkedRefs", refs);

      List<Map<String, Object>> linked = new ArrayList<>();
      for (String d : ids(c, "DELIVERY", Set.of("deliveryId", "recoveryDeliveryId"))) {
        used.add(Source.EXECUTION);
        if (!deliveryOk.containsKey(d)) {
          JsonNode n = fetch(token, Source.EXECUTION, "/deliveries/{id}", d, failed, deliveryOk, d);
          if (n != null) {
            deliveries.put(d, n);
          }
        }
        linked.add(
            linkedEntry(
                "DELIVERY",
                d,
                deliveries.get(d),
                "state",
                Source.EXECUTION,
                deliveryOk.get(d),
                now));
      }
      for (String o : ids(c, "ORDER", Set.of("orderId"))) {
        used.add(Source.ORDER);
        if (!orderOk.containsKey(o)) {
          JsonNode n = fetch(token, Source.ORDER, "/orders/lookups/{id}", o, failed, orderOk, o);
          if (n != null) {
            orders.put(o, n);
          }
        }
        linked.add(
            linkedEntry("ORDER", o, orders.get(o), "status", Source.ORDER, orderOk.get(o), now));
      }
      i.put("linked", linked);
      i.put("source", stamp(Source.CASE.system, now, true));
      items.add(i);
    }

    List<Map<String, Object>> sources = new ArrayList<>();
    List<String> unavailable = new ArrayList<>();
    for (Source s : used) {
      boolean bad = failed.contains(s);
      if (bad) {
        unavailable.add(s.system);
      }
      sources.add(stamp(s.system, now, !bad));
    }
    Map<String, Object> pageOut = new LinkedHashMap<>();
    pageOut.put("nextCursor", page == null ? null : text(page.path("page"), "nextCursor"));
    pageOut.put("size", items.size());
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("composedAt", now.toString());
    out.put("sources", sources);
    out.put("unavailableSources", unavailable);
    out.put("items", items);
    out.put("page", pageOut);
    return out;
  }

  /**
   * 원천 한 건. 성공이면 본문, 404 면 null(있음 표시 ok=true — "없음"도 원천의 답), 실패·403 이면 null + ok=false + 원천 실패.
   */
  private @Nullable JsonNode fetch(
      String token,
      Source source,
      String path,
      String id,
      Set<Source> failed,
      Map<String, Boolean> ok,
      String key) {
    try {
      JsonNode n = clients.get(source, token, path, Map.of("id", id), Map.of());
      ok.put(key, true);
      return n;
    } catch (HttpClientErrorException.NotFound e) {
      ok.put(key, true);
      return null;
    } catch (HttpClientErrorException | PmartException e) {
      log.warn("사건함: {} 연결 조회 실패 — {}", source.system, e.getClass().getSimpleName());
      failed.add(source);
      ok.put(key, false);
      return null;
    }
  }

  private static Map<String, Object> linkedEntry(
      String kind,
      String id,
      @Nullable JsonNode body,
      String stateField,
      Source source,
      @Nullable Boolean ok,
      Instant now) {
    Map<String, Object> e = new LinkedHashMap<>();
    e.put("kind", kind);
    e.put("id", id);
    e.put("state", body == null ? null : text(body, stateField));
    JsonNode v = body == null ? null : body.path("version");
    e.put("version", v != null && v.isNumber() ? v.asLong() : null);
    e.put("source", stamp(source.system, now, Boolean.TRUE.equals(ok)));
    return e;
  }

  /**
   * 사건이 가리키는 배송·주문 ID(대상이 그 종류면 subjectRef, 그 밖 refs — 배송은 오배송 회수 배송 recoveryDeliveryId 도, 0.5.1).
   */
  private static List<String> ids(JsonNode c, String subjectType, Set<String> refTypes) {
    List<String> out = new ArrayList<>();
    if (subjectType.equals(text(c, "subjectType")) && text(c, "subjectRef") != null) {
      out.add(text(c, "subjectRef"));
    }
    for (JsonNode r : c.path("refs")) {
      String id = text(r, "refId");
      String type = text(r, "refType");
      if (type != null && refTypes.contains(type) && id != null && !out.contains(id)) {
        out.add(id);
      }
    }
    return out;
  }

  private static List<JsonNode> listOf(JsonNode arr) {
    List<JsonNode> out = new ArrayList<>();
    arr.forEach(out::add);
    return out;
  }

  private static void put(Map<String, Object> q, String k, @Nullable Object v) {
    if (v instanceof String s && s.isBlank()) {
      return;
    }
    if (v != null) {
      q.put(k, v instanceof String s ? s.strip() : v);
    }
  }

  private static Map<String, Object> stamp(String system, Instant at, boolean ok) {
    Map<String, Object> s = new LinkedHashMap<>();
    s.put("system", system);
    s.put("asOf", Map.of("asOf", at.toString(), "freshness", ok ? "CURRENT" : "UNAVAILABLE"));
    s.put("confidence", ok ? "DIRECT" : "UNKNOWN");
    return s;
  }

  private static @Nullable String text(JsonNode n, String field) {
    JsonNode v = n.path(field);
    return v.isNull() || v.isMissingNode() ? null : v.asString();
  }
}
