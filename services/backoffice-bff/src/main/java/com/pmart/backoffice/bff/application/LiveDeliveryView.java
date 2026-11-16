package com.pmart.backoffice.bff.application;

import com.pmart.backoffice.bff.infrastructure.LookupClients;
import com.pmart.backoffice.bff.infrastructure.LookupClients.Source;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
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
 * 실시간 배송 현황(2026-11-16 사용자 "백오피스용부터 추천대로" — 배송 업무 원문 §10.1, 계약 0.3.0).
 *
 * <ul>
 *   <li>역할 OPERATOR_ADMIN·CS_AGENT·RIDER_OPS(통합 조회와 같다). 원천은 호출한 직원 토큰으로 다시 판정.
 *   <li>state 를 비우면 끝나지 않은 배송을 상태마다 읽어 생긴 순으로 합쳐 size 건까지(넘으면 truncated). state 를 주면 그 상태만 원천 쪽
 *       커서로.
 *   <li>배송마다 배차를 한 번 읽고, 열린 사건은 한 번에 읽어 배송 번호로 잇는다. 원천마다 따로 표시 — 실패는 unavailableSources, 0건으로 바꾸지
 *       않는다.
 * </ul>
 */
@Service
public class LiveDeliveryView {

  /** 끝나지 않은 배송(ExecutionState 중 COMPLETED·FAILED·RETURNED_TO_STORE·CANCELLED 를 뺀 것). */
  static final List<String> ACTIVE_STATES =
      List.of(
          "UNASSIGNED",
          "ASSIGNED",
          "ARRIVED_AT_STORE",
          "PICKED_UP",
          "ARRIVED_AT_CUSTOMER",
          "FAILURE_REPORTED",
          "SUSPENDED",
          "RETURN_IN_PROGRESS",
          "RETURN_PICKED_UP");

  static final Set<String> ALL_STATES =
      Set.of(
          "UNASSIGNED",
          "ASSIGNED",
          "ARRIVED_AT_STORE",
          "PICKED_UP",
          "ARRIVED_AT_CUSTOMER",
          "FAILURE_REPORTED",
          "COMPLETED",
          "FAILED",
          "SUSPENDED",
          "RETURN_IN_PROGRESS",
          "RETURN_PICKED_UP",
          "RETURNED_TO_STORE",
          "CANCELLED");

  static final int MAX_SIZE = 50;
  private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
  private static final Logger log = LoggerFactory.getLogger(LiveDeliveryView.class);

  private final LookupClients clients;
  private final Clock clock;

  public LiveDeliveryView(LookupClients clients, Clock clock) {
    this.clients = clients;
    this.clock = clock;
  }

  public Map<String, Object> view(
      @Nullable String mfcId, @Nullable String state, int size, @Nullable String cursor) {
    UnifiedLookupView.requireRole();
    String token =
        PmartPrincipal.currentTokenValue()
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "운영자 토큰이 필요합니다."));
    String st = blankToNull(state);
    if (st != null && !ALL_STATES.contains(st)) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "state 가 올바르지 않습니다.");
    }
    if (st == null && blankToNull(cursor) != null) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED, "cursor 는 state 를 줄 때만 씁니다(끝나지 않은 배송 전부는 쪽 넘김 없음).");
    }
    String mfc = blankToNull(mfcId);
    int n = Math.min(Math.max(size, 1), MAX_SIZE);
    Instant now = clock.instant();
    Set<Source> used = EnumSet.noneOf(Source.class);
    Set<Source> failed = EnumSet.noneOf(Source.class);

    // 1) 배송(수행)
    List<JsonNode> deliveries = new ArrayList<>();
    boolean truncated = false;
    String nextCursor = null;
    used.add(Source.EXECUTION);
    for (String s : st != null ? List.of(st) : ACTIVE_STATES) {
      Map<String, Object> q = new LinkedHashMap<>();
      if (mfc != null) {
        q.put("mfcId", mfc);
      }
      q.put("state", s);
      q.put("sort", "OLDEST");
      q.put("size", n);
      if (st != null && blankToNull(cursor) != null) {
        q.put("cursor", cursor);
      }
      JsonNode page = call(token, Source.EXECUTION, "/deliveries", Map.of(), q, failed);
      if (page == null) {
        continue;
      }
      page.path("items").forEach(deliveries::add);
      String next = text(page.path("page"), "nextCursor");
      if (st != null) {
        nextCursor = next;
      } else if (next != null) {
        truncated = true;
      }
    }
    if (st == null) {
      deliveries.sort(Comparator.comparing(d -> String.valueOf(text(d, "createdAt"))));
      if (deliveries.size() > n) {
        truncated = true;
        deliveries = new ArrayList<>(deliveries.subList(0, n));
      }
    }

    // 2) 열린 사건 — 한 번에 읽어 배송 번호로 잇는다
    Map<String, List<Map<String, Object>>> casesByDelivery = null;
    if (!deliveries.isEmpty()) {
      used.add(Source.CASE);
      JsonNode page = call(token, Source.CASE, "/cases", Map.of(), Map.of("size", 200), failed);
      if (page != null) {
        casesByDelivery = new HashMap<>();
        for (JsonNode c : page.path("items")) {
          Map<String, Object> brief = new LinkedHashMap<>();
          brief.put("caseId", text(c, "caseId"));
          brief.put("kind", text(c, "kind"));
          brief.put("priority", text(c, "priority"));
          brief.put(
              "overdue", c.path("overdue").isBoolean() ? c.path("overdue").asBoolean() : null);
          for (String d : deliveryRefs(c)) {
            casesByDelivery.computeIfAbsent(d, k -> new ArrayList<>()).add(brief);
          }
        }
      }
    }

    // 3) 배송마다 배차
    List<Map<String, Object>> items = new ArrayList<>();
    for (JsonNode d : deliveries) {
      String deliveryId = text(d, "deliveryId");
      if (deliveryId == null) {
        continue;
      }
      used.add(Source.DISPATCH);
      JsonNode dispatch =
          call(
              token,
              Source.DISPATCH,
              "/deliveries/{id}/dispatch",
              Map.of("id", deliveryId),
              Map.of(),
              failed);
      Map<String, Object> i = new LinkedHashMap<>();
      i.put("deliveryId", deliveryId);
      i.put("orderId", text(d, "orderId"));
      i.put("mfcId", text(d, "mfcId"));
      i.put("type", text(d, "type"));
      i.put("delivery", stamped(text(d, "state"), d.path("version"), Source.EXECUTION, now, true));
      i.put(
          "dispatch",
          stamped(
              dispatch == null ? null : text(dispatch, "state"),
              dispatch == null ? null : dispatch.path("version"),
              Source.DISPATCH,
              now,
              dispatch != null));
      String rider = text(d, "riderId");
      if (rider == null && dispatch != null) {
        rider = text(dispatch.path("assignment"), "riderId");
      }
      i.put("riderId", rider);
      i.put("createdAt", text(d, "createdAt"));
      i.put("assignedAt", text(d, "assignedAt"));
      i.put("pickedUpAt", text(d, "pickedUpAt"));
      JsonNode window = dispatch == null ? null : dispatch.path("arrivalWindow");
      boolean hasWindow = window != null && window.isObject();
      i.put("arrivalWindow", hasWindow ? window : null);
      i.put("promisedAt", hasWindow ? promisedAt(window) : null);
      i.put("latestEta", null);
      i.put(
          "openCases",
          casesByDelivery == null ? null : casesByDelivery.getOrDefault(deliveryId, List.of()));
      items.add(i);
    }

    List<Map<String, Object>> sources = new ArrayList<>();
    List<String> unavailable = new ArrayList<>();
    for (Source s : used) {
      boolean bad = failed.contains(s);
      if (bad) {
        unavailable.add(s.system);
      }
      sources.add(
          stamp(s.system, now, bad ? "UNAVAILABLE" : "CURRENT", bad ? "UNKNOWN" : "DIRECT"));
    }
    Map<String, Object> page = new LinkedHashMap<>();
    page.put("nextCursor", nextCursor);
    page.put("size", items.size());
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("composedAt", now.toString());
    out.put("sources", sources);
    out.put("unavailableSources", unavailable);
    out.put("items", items);
    out.put("truncated", truncated);
    out.put("page", page);
    return out;
  }

  private @Nullable JsonNode call(
      String token,
      Source source,
      String path,
      Map<String, ?> uriVariables,
      Map<String, ?> query,
      Set<Source> failed) {
    try {
      return clients.get(source, token, path, uriVariables, query);
    } catch (HttpClientErrorException.NotFound e) {
      return null;
    } catch (HttpClientErrorException | PmartException e) {
      log.warn("실시간 배송 현황: {} {} 실패 — {}", source.system, path, e.getClass().getSimpleName());
      failed.add(source);
      return null;
    }
  }

  /** 사건이 가리키는 배송 번호(대상이 배송이면 subjectRef, 그 밖 refs 의 deliveryId). */
  private static List<String> deliveryRefs(JsonNode c) {
    List<String> out = new ArrayList<>();
    if ("DELIVERY".equals(text(c, "subjectType")) && text(c, "subjectRef") != null) {
      out.add(text(c, "subjectRef"));
    }
    for (JsonNode r : c.path("refs")) {
      String id = text(r, "refId");
      if ("deliveryId".equals(text(r, "refType")) && id != null && !out.contains(id)) {
        out.add(id);
      }
    }
    return out;
  }

  /** arrivalWindow(date·end, Asia/Seoul) → 약속 시각. 모양이 다르면 null(추정하지 않음). */
  static @Nullable String promisedAt(JsonNode window) {
    String date = text(window, "date");
    String end = text(window, "end");
    if (date == null || end == null) {
      return null;
    }
    try {
      return LocalDate.parse(date)
          .atTime(LocalTime.parse(end))
          .atZone(SEOUL)
          .toInstant()
          .toString();
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static Map<String, Object> stamped(
      @Nullable String state, @Nullable JsonNode version, Source source, Instant now, boolean ok) {
    Map<String, Object> s = new LinkedHashMap<>();
    s.put("state", state);
    s.put("version", version != null && version.isNumber() ? version.asLong() : null);
    s.put("at", null);
    s.put(
        "source",
        stamp(source.system, now, ok ? "CURRENT" : "UNAVAILABLE", ok ? "DIRECT" : "UNKNOWN"));
    return s;
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

  private static @Nullable String blankToNull(@Nullable String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }
}
