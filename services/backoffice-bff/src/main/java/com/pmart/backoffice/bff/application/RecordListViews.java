package com.pmart.backoffice.bff.application;

import static com.pmart.backoffice.bff.application.ViewSupport.stamp;
import static com.pmart.backoffice.bff.application.ViewSupport.text;

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
import java.util.LinkedHashSet;
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
 * 클레임·라이더 컴플레인 목록에 화면 번호(ADR-0070)를 붙인 화면(0.18.0, 2026-11-19 — 웹 요청
 * backend-display-numbers-in-responses, 사용자 "진행하고"). 웹이 줄마다 번호를 따로 묻던 것(목록 50줄 → 요청 최대 50개)을 bff 한
 * 번으로. 원천 목록은 직원 토큰으로 그대로(보이는 범위·권한은 원천 판정), 번호는 원천 정본 값 그대로 — case·claim 에 다른 서비스 값을 사본으로 두지
 * 않는다(라이더 탈퇴 때 rider 가 이름을 지우는데 사본이 남지 않게).
 *
 * <ul>
 *   <li>번호: 주문 = order {@code GET /orders/lookups/{id}} 의 orderNumber, 배송 = execution {@code GET
 *       /deliveries/{id}} 의 deliveryNumber(·orderSummary.orderNumber), 라이더 = {@link RiderDisplays},
 *       기록한 직원 = {@link OperatorLabels}. 같은 ID 는 한 번만. order·execution 은 여러 ID 조회가 없어 ID 마다 한 번(쪽
 *       크기 ≤ 50).
 *   <li>번호 원천 실패: 그 칸만 null + unavailableSources(0·빈칸으로 바꾸지 않음), 한 번 장애면 남은 ID 는 부르지 않음. 404 는 없음.
 *   <li>주 원천(claim·case) 거절: 401 → 401, 403 → 403, 400 → 400, 그 밖 4xx 그대로. 장애는 200 + 빈 items +
 *       unavailableSources.
 * </ul>
 */
@Service
public class RecordListViews {

  static final int MAX_SIZE = 50;
  private static final Logger log = LoggerFactory.getLogger(RecordListViews.class);

  private final LookupClients clients;
  private final Clock clock;

  public RecordListViews(LookupClients clients, Clock clock) {
    this.clients = clients;
    this.clock = clock;
  }

  /** 클레임 목록 + 주문번호. 클레임 원천 claim-service {@code GET /claims}(직원 토큰). */
  public Map<String, Object> claims(
      @Nullable String orderId,
      @Nullable String type,
      @Nullable String state,
      @Nullable Integer size,
      @Nullable String cursor) {
    String token = token();
    Instant now = clock.instant();
    Map<String, Object> q = new LinkedHashMap<>();
    put(q, "orderId", orderId);
    put(q, "type", type);
    put(q, "state", state);
    q.put("size", clamp(size));
    put(q, "cursor", cursor);
    Set<Source> used = EnumSet.of(Source.CLAIM);
    Set<Source> failed = EnumSet.noneOf(Source.class);
    JsonNode page = primary(Source.CLAIM, token, "/claims", q, failed, "클레임");

    List<Map<String, Object>> items = new ArrayList<>();
    List<String> orderIds = new ArrayList<>();
    for (JsonNode c : page == null ? List.<JsonNode>of() : listOf(page.path("items"))) {
      Map<String, Object> i = new LinkedHashMap<>();
      i.put("claimId", text(c, "claimId"));
      i.put("orderId", text(c, "orderId"));
      i.put("type", text(c, "type"));
      i.put("state", text(c, "state"));
      i.put("openedAt", text(c, "openedAt"));
      // claim 0.6.0(2026-11-19 BR-33) 줄 값 그대로 — 사유·접수 경로·닫힌 시각·상품 환불 요약
      i.put("reason", text(c, "reason"));
      i.put("channel", text(c, "channel"));
      i.put("closedAt", text(c, "closedAt"));
      i.put("refund", c.path("refund").isObject() ? c.path("refund") : null);
      items.add(i);
      if (text(c, "orderId") != null) {
        orderIds.add(text(c, "orderId"));
      }
    }
    Map<String, String> orderNumbers = new HashMap<>();
    if (!orderIds.isEmpty()) {
      used.add(Source.ORDER);
      for (String id : new LinkedHashSet<>(orderIds)) {
        if (failed.contains(Source.ORDER)) {
          break; // 한 번 실패한 원천은 남은 ID 에서 부르지 않는다
        }
        JsonNode o = one(Source.ORDER, token, "/orders/lookups/{id}", id, failed);
        if (o != null && text(o, "orderNumber") != null) {
          orderNumbers.put(id, text(o, "orderNumber"));
        }
      }
    }
    for (Map<String, Object> i : items) {
      i.put("orderNumber", orderNumbers.get((String) i.get("orderId")));
    }
    return out(now, used, failed, items, page);
  }

  /**
   * 라이더 컴플레인 목록 + 배송 번호·주문번호·라이더 번호·기록한 직원. 원천 case-service {@code GET /rider-complaints}(직원 토큰).
   * 메모(note)는 싣지 않는다(목록 원천에도 없음).
   */
  public Map<String, Object> riderComplaints(
      @Nullable String riderId,
      @Nullable String deliveryId,
      @Nullable String complaintType,
      @Nullable String from,
      @Nullable String to,
      @Nullable Integer size,
      @Nullable String cursor) {
    String token = token();
    Instant now = clock.instant();
    Map<String, Object> q = new LinkedHashMap<>();
    put(q, "riderId", riderId);
    put(q, "deliveryId", deliveryId);
    put(q, "complaintType", complaintType);
    put(q, "from", from);
    put(q, "to", to);
    q.put("size", clamp(size));
    put(q, "cursor", cursor);
    Set<Source> used = EnumSet.of(Source.CASE);
    Set<Source> failed = EnumSet.noneOf(Source.class);
    JsonNode page = primary(Source.CASE, token, "/rider-complaints", q, failed, "라이더 컴플레인");

    List<Map<String, Object>> items = new ArrayList<>();
    List<String> deliveryIds = new ArrayList<>();
    List<String> riderIds = new ArrayList<>();
    List<String> staff = new ArrayList<>();
    for (JsonNode c : page == null ? List.<JsonNode>of() : listOf(page.path("items"))) {
      Map<String, Object> i = new LinkedHashMap<>();
      i.put("complaintId", text(c, "complaintId"));
      i.put("caseId", text(c, "caseId"));
      i.put("inquiryId", text(c, "inquiryId"));
      i.put("riderId", text(c, "riderId"));
      i.put("deliveryId", text(c, "deliveryId"));
      i.put("orderId", text(c, "orderId"));
      i.put("complaintType", text(c, "complaintType"));
      i.put("recordedVia", text(c, "source"));
      i.put("recordedBy", text(c, "recordedBy"));
      i.put("recordedAt", text(c, "recordedAt"));
      items.add(i);
      add(deliveryIds, text(c, "deliveryId"));
      add(riderIds, text(c, "riderId"));
      add(staff, text(c, "recordedBy"));
    }

    // 배송 상세 하나로 배송 번호와 주문번호를 같이 읽는다(배송마다 한 번)
    Map<String, JsonNode> deliveries = new HashMap<>();
    if (!deliveryIds.isEmpty()) {
      used.add(Source.EXECUTION);
      for (String id : new LinkedHashSet<>(deliveryIds)) {
        if (failed.contains(Source.EXECUTION)) {
          break;
        }
        JsonNode d = one(Source.EXECUTION, token, "/deliveries/{id}", id, failed);
        if (d != null) {
          deliveries.put(id, d);
        }
      }
    }
    Map<String, JsonNode> riders = null;
    if (!riderIds.isEmpty()) {
      used.add(Source.RIDER);
      riders = RiderDisplays.lookup(clients, token, riderIds);
      if (riders == null) {
        failed.add(Source.RIDER);
      }
    }
    Map<String, JsonNode> labels = null;
    if (!staff.isEmpty()) {
      used.add(Source.HR);
      labels = OperatorLabels.lookup(clients, token, staff);
      if (labels == null) {
        failed.add(Source.HR);
      }
    }
    for (Map<String, Object> i : items) {
      JsonNode d = deliveries.get((String) i.get("deliveryId"));
      i.put("deliveryNumber", d == null ? null : text(d, "deliveryNumber"));
      i.put("orderNumber", d == null ? null : text(d.path("orderSummary"), "orderNumber"));
      i.put("rider", RiderDisplays.of((String) i.get("riderId"), riders));
      i.put("recordedByLabel", OperatorLabels.of((String) i.get("recordedBy"), labels));
    }
    return out(now, used, failed, items, page);
  }

  // ------------------------------------------------------------------ 공통

  private @Nullable JsonNode primary(
      Source source,
      String token,
      String path,
      Map<String, Object> q,
      Set<Source> failed,
      String what) {
    try {
      return clients.get(source, token, path, Map.of(), q);
    } catch (HttpClientErrorException e) {
      // 주 원천의 권한·입력 거절은 그대로(빈 목록으로 바꾸지 않음)
      int sc = e.getStatusCode().value();
      if (sc == 401) {
        throw new PmartException(
            "UNAUTHENTICATED", HttpStatus.UNAUTHORIZED, "다시 로그인하세요.", false, List.of());
      }
      if (sc == 403) {
        throw new PmartException(ErrorCode.FORBIDDEN, what + " 목록을 볼 권한이 없습니다.");
      }
      if (sc == 400) {
        throw new PmartException(ErrorCode.VALIDATION_FAILED, what + " 조회 조건이 올바르지 않습니다.");
      }
      throw new PmartException(
          "SOURCE_REJECTED", HttpStatus.valueOf(sc), what + " 원천이 거절했습니다.", false, List.of());
    } catch (PmartException e) {
      log.warn("{} 목록: {} 실패 — {}", what, source.system, e.getClass().getSimpleName());
      failed.add(source);
      return null;
    }
  }

  /** 번호 원천 단건 — 404 는 없음(null), 그 밖 실패는 그 원천 실패(null). */
  private @Nullable JsonNode one(
      Source source, String token, String path, String id, Set<Source> failed) {
    try {
      return clients.get(source, token, path, Map.of("id", id), Map.of());
    } catch (HttpClientErrorException.NotFound e) {
      return null;
    } catch (HttpClientErrorException | PmartException e) {
      log.warn("화면 번호: {} 실패 — {}", source.system, e.getClass().getSimpleName());
      failed.add(source);
      return null;
    }
  }

  private static Map<String, Object> out(
      Instant now,
      Set<Source> used,
      Set<Source> failed,
      List<Map<String, Object>> items,
      @Nullable JsonNode page) {
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

  private static String token() {
    return PmartPrincipal.currentTokenValue()
        .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "운영자 토큰이 필요합니다."));
  }

  private static int clamp(@Nullable Integer size) {
    return Math.min(Math.max(size == null ? 20 : size, 1), MAX_SIZE);
  }

  private static void put(Map<String, Object> q, String k, @Nullable String v) {
    if (v != null && !v.isBlank()) {
      q.put(k, v);
    }
  }

  private static void add(List<String> l, @Nullable String v) {
    if (v != null && !v.isBlank()) {
      l.add(v);
    }
  }

  private static List<JsonNode> listOf(JsonNode arr) {
    List<JsonNode> l = new ArrayList<>();
    arr.forEach(l::add);
    return l;
  }
}
