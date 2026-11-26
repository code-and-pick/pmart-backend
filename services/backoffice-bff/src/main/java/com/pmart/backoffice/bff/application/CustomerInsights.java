package com.pmart.backoffice.bff.application;

import static com.pmart.backoffice.bff.application.ViewSupport.text;

import com.pmart.backoffice.bff.domain.CustomerSegment;
import com.pmart.backoffice.bff.infrastructure.LookupClients;
import com.pmart.backoffice.bff.infrastructure.LookupClients.Source;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
 * 고객 목록·구매 요약·최근 활동일·분류·구매 지표(BF-5 2차, 0.29.0 — 2026-11-26). 원천은 모두 <b>호출한 직원의 토큰</b>으로.
 *
 * <ul>
 *   <li>목록 = customer 0.22.0 CU-5 {@code GET /customers} 목록 모드, 또는 마지막 구매일 거르기면 order 0.25.0 O-12
 *       {@code /orders/metrics/customer-ids} → customer {@code ?customerIds=}.
 *   <li>구매 요약 = order {@code /orders/metrics/customer-summaries}(50명). 최근 활동일 = max(auth 0.12.0
 *       CU-6 {@code /internal/customers/activities} 로그인·토큰 갱신, 마지막 주문) — 둘 중 하나라도 못 읽으면 null(작게 보이지
 *       않게).
 *   <li>분류 = {@link CustomerSegment}. 구매 요약을 못 읽으면 null.
 *   <li>구매 지표 = order 0.24.0 O-13 {@code /orders/metrics/customers} — 비율은 분자·분모와 함께(BO-28, 분모 0 →
 *       null).
 *   <li>덤 원천(구매 요약·활동) 404 는 원천 배포 전 — 값만 null, unavailableSources 에 넣지 않는다. 고객 ID 로그 금지.
 * </ul>
 */
@Service
public class CustomerInsights {

  /** order customer-summaries·customer `?customerIds=` 한 번 상한 — 목록 한 쪽도 이만큼. */
  public static final int MAX_PAGE = 50;

  private static final Logger log = LoggerFactory.getLogger(CustomerInsights.class);

  private final LookupClients clients;
  private final Clock clock;

  public CustomerInsights(LookupClients clients, Clock clock) {
    this.clients = clients;
    this.clock = clock;
  }

  /** 한 고객의 덧붙일 값. */
  public record Insight(
      @Nullable Map<String, Object> purchase,
      @Nullable String lastActiveAt,
      @Nullable String segment) {
    static final Insight NONE = new Insight(null, null, null);

    void into(Map<String, Object> row) {
      row.put("purchase", purchase);
      row.put("lastActiveAt", lastActiveAt);
      row.put("segment", segment);
    }
  }

  // ------------------------------------------------------------------ 목록

  public Map<String, Object> list(
      @Nullable String name,
      @Nullable LocalDate joinedFrom,
      @Nullable LocalDate joinedTo,
      @Nullable String status,
      @Nullable LocalDate lastOrderFrom,
      @Nullable LocalDate lastOrderTo,
      @Nullable Integer size,
      @Nullable String cursor) {
    boolean byLastOrder = lastOrderFrom != null || lastOrderTo != null;
    if (byLastOrder
        && (!CustomerViews.blank(name)
            || joinedFrom != null
            || joinedTo != null
            || status != null)) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED, "마지막 구매일 거르기는 이름·가입일·상태 거르기와 함께 쓸 수 없습니다.");
    }
    int n = size == null ? MAX_PAGE : Math.max(1, Math.min(MAX_PAGE, size));
    String token = CustomerViews.token();
    Instant now = clock.instant();
    Set<Source> used = EnumSet.noneOf(Source.class);
    Set<Source> failed = EnumSet.noneOf(Source.class);
    List<JsonNode> rows = new ArrayList<>();
    String next = null;

    if (byLastOrder) {
      used.add(Source.ORDER_METRICS);
      Map<String, Object> q = new LinkedHashMap<>();
      putIf(q, "lastOrderFrom", lastOrderFrom);
      putIf(q, "lastOrderTo", lastOrderTo);
      q.put("size", n);
      putIf(q, "cursor", cursor);
      JsonNode page =
          primary(Source.ORDER_METRICS, token, "/orders/metrics/customer-ids", q, failed, "구매");
      List<String> ids = new ArrayList<>();
      if (page != null) {
        for (JsonNode i : page.path("items")) {
          if (text(i, "customerId") != null) {
            ids.add(text(i, "customerId"));
          }
        }
        next = text(page.path("page"), "nextCursor");
      }
      if (!ids.isEmpty()) {
        used.add(Source.CUSTOMER);
        JsonNode found =
            primary(
                Source.CUSTOMER,
                token,
                "/customers",
                Map.of("customerIds", String.join(",", ids)),
                failed,
                "고객");
        if (found != null) {
          Map<String, JsonNode> byId = new HashMap<>();
          for (JsonNode c : found.path("items")) {
            String id = text(c, "customerId");
            if (id != null) {
              byId.put(id, c);
            }
          }
          for (String id : ids) { // 마지막 구매 최근 순 그대로
            if (byId.containsKey(id)) {
              rows.add(byId.get(id));
            }
          }
        }
      }
    } else {
      used.add(Source.CUSTOMER);
      Map<String, Object> q = new LinkedHashMap<>();
      putIf(q, "name", CustomerViews.blank(name) ? null : name);
      putIf(q, "joinedFrom", joinedFrom);
      putIf(q, "joinedTo", joinedTo);
      putIf(q, "status", status);
      q.put("size", n);
      putIf(q, "cursor", cursor);
      // 이름으로 찾으면 customer 가 audit 검색 기록 뒤에만 준다 — 기록 실패 503 은 여기서 원천 장애(빈 목록 + unavailableSources)
      JsonNode page = primary(Source.CUSTOMER, token, "/customers", q, failed, "고객");
      if (page != null) {
        page.path("items").forEach(rows::add);
        next = text(page.path("page"), "nextCursor");
      }
    }

    Map<String, @Nullable Instant> joined = new LinkedHashMap<>();
    for (JsonNode c : rows) {
      String id = text(c, "customerId");
      if (id != null) {
        joined.put(id, instant(text(c, "joinedAt")));
      }
    }
    Map<String, Insight> insights = insights(token, joined, used, failed);

    List<Map<String, Object>> items = new ArrayList<>();
    for (JsonNode c : rows) {
      Map<String, Object> r = CustomerViews.summary(c);
      r.put("joinedAt", text(c, "joinedAt"));
      String id = text(c, "customerId");
      (id == null ? Insight.NONE : insights.getOrDefault(id, Insight.NONE)).into(r);
      items.add(r);
    }
    Map<String, Object> pageOut = new LinkedHashMap<>();
    pageOut.put("nextCursor", next);
    pageOut.put("size", n);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("items", items);
    body.put("page", pageOut);
    return CustomerViews.out(now, used, failed, body);
  }

  // ------------------------------------------------------------------ 덧붙일 값

  /** 고객마다 구매 요약·최근 활동일·분류(최대 {@link #MAX_PAGE}명). {@code joinedAt} 은 customer 가입 시각(모르면 null 값). */
  public Map<String, Insight> insights(
      String token, Map<String, @Nullable Instant> joinedAt, Set<Source> used, Set<Source> failed) {
    Map<String, Insight> out = new HashMap<>();
    if (joinedAt.isEmpty()) {
      return out;
    }
    String ids = String.join(",", joinedAt.keySet());
    used.add(Source.ORDER_METRICS);
    used.add(Source.AUTH);
    JsonNode sums =
        extra(
            Source.ORDER_METRICS,
            token,
            "/orders/metrics/customer-summaries",
            Map.of("customerIds", ids),
            failed);
    JsonNode acts =
        extra(
            Source.AUTH,
            token,
            "/internal/customers/activities",
            Map.of("accountIds", ids),
            failed);

    Map<String, JsonNode> sumById = byId(sums, "customerId");
    Map<String, JsonNode> actById = byId(acts, "accountId");
    String amountSince = sums == null ? null : text(sums, "amountSince");
    LocalDate today = LocalDate.now(clock.withZone(CustomerSegment.SEOUL));

    for (Map.Entry<String, @Nullable Instant> e : joinedAt.entrySet()) {
      JsonNode s = sumById.get(e.getKey());
      Map<String, Object> purchase = null;
      String segment = null;
      Instant lastOrder = null;
      if (s != null) {
        purchase = new LinkedHashMap<>();
        purchase.put("orderCount", s.path("orderCount").asLong(0));
        purchase.put("totalAmount", s.path("totalAmount").asLong(0));
        purchase.put("amount3m", s.path("amount3m").asLong(0));
        purchase.put("amountSince", amountSince);
        purchase.put("firstOrderAt", text(s, "firstOrderAt"));
        purchase.put("lastOrderAt", text(s, "lastOrderAt"));
        lastOrder = instant(text(s, "lastOrderAt"));
        CustomerSegment seg =
            CustomerSegment.of(today, e.getValue(), lastOrder, s.path("amount3m").asLong(0));
        segment = seg == null ? null : seg.name();
      }
      String lastActive = null;
      JsonNode a = actById.get(e.getKey());
      if (s != null && acts != null) { // 둘 다 읽었을 때만 — 하나라도 모르면 실제보다 옛날로 보일 수 있다
        Instant max = lastOrder;
        for (String f : List.of("lastLoginAt", "lastTokenRefreshAt")) {
          Instant t = a == null ? null : instant(text(a, f));
          if (t != null && (max == null || t.isAfter(max))) {
            max = t;
          }
        }
        lastActive = max == null ? null : max.toString();
      }
      out.put(e.getKey(), new Insight(purchase, lastActive, segment));
    }
    return out;
  }

  // ------------------------------------------------------------------ 구매 지표

  public Map<String, Object> metrics(LocalDate from, LocalDate to, @Nullable String centerId) {
    String token = CustomerViews.token();
    Instant now = clock.instant();
    Map<String, Object> q = new LinkedHashMap<>();
    q.put("from", from);
    q.put("to", to);
    putIf(q, "mfcId", CustomerViews.blank(centerId) ? null : centerId);
    JsonNode m;
    try {
      m = clients.get(Source.ORDER_METRICS, token, "/orders/metrics/customers", Map.of(), q);
    } catch (HttpClientErrorException e) {
      throw CustomerViews.rejected(e, "구매 지표");
    } catch (PmartException e) {
      log.warn("고객 구매 지표: order 실패 — {}", e.getClass().getSimpleName());
      throw PmartException.dependencyUnavailable("주문 서비스에서 구매 지표를 읽지 못했습니다.", e);
    }
    long customers = m.path("customerCount").asLong(0);
    long repeat = m.path("repeatCustomerCount").asLong(0);
    long oneTime = m.path("oneTimeCustomerCount").asLong(0);
    long orders = m.path("orderCount").asLong(0);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("from", from.toString());
    body.put("to", to.toString());
    body.put("centerId", CustomerViews.blank(centerId) ? null : centerId);
    body.put("customerCount", customers);
    body.put("repeatCustomerCount", repeat);
    body.put("oneTimeCustomerCount", oneTime);
    body.put("orderCount", orders);
    body.put("purchaseAmount", m.path("purchaseAmount").asLong(0));
    body.put("repeatRate", ratio(repeat, customers));
    body.put("oneTimeRate", ratio(oneTime, customers));
    body.put("averageOrderCount", ratio(orders, customers));
    return CustomerViews.out(
        now, EnumSet.of(Source.ORDER_METRICS), EnumSet.noneOf(Source.class), body);
  }

  static Map<String, Object> ratio(long numerator, long denominator) {
    Map<String, Object> r = new LinkedHashMap<>();
    r.put("numerator", numerator);
    r.put("denominator", denominator);
    r.put("ratio", denominator == 0 ? null : (double) numerator / denominator);
    return r;
  }

  // ------------------------------------------------------------------ 공통

  /** 주 원천 — 401·403·400 그대로, 장애는 null(빈 목록 + unavailableSources). */
  private @Nullable JsonNode primary(
      Source source, String token, String path, Map<String, ?> q, Set<Source> failed, String what) {
    try {
      return clients.get(source, token, path, Map.of(), q);
    } catch (HttpClientErrorException e) {
      throw CustomerViews.rejected(e, what);
    } catch (PmartException e) {
      log.warn("고객 목록: {} 실패 — {}", source.system, e.getClass().getSimpleName());
      failed.add(source);
      return null;
    }
  }

  /** 덤 원천 — 404 는 배포 전(값만 null), 그 밖 실패·4xx 는 null + unavailableSources. */
  private @Nullable JsonNode extra(
      Source source, String token, String path, Map<String, ?> q, Set<Source> failed) {
    try {
      return clients.get(source, token, path, Map.of(), q);
    } catch (HttpClientErrorException.NotFound e) {
      return null;
    } catch (HttpClientErrorException | PmartException e) {
      log.warn("고객 구매·활동: {} 실패 — {}", source.system, e.getClass().getSimpleName());
      failed.add(source);
      return null;
    }
  }

  private static Map<String, JsonNode> byId(@Nullable JsonNode body, String field) {
    Map<String, JsonNode> m = new HashMap<>();
    if (body != null) {
      for (JsonNode i : body.path("items")) {
        String id = text(i, field);
        if (id != null) {
          m.put(id, i);
        }
      }
    }
    return m;
  }

  private static void putIf(Map<String, Object> q, String k, @Nullable Object v) {
    if (v != null) {
      q.put(k, v.toString());
    }
  }

  static @Nullable Instant instant(@Nullable String v) {
    if (v == null || v.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(v);
    } catch (java.time.format.DateTimeParseException e) {
      try {
        return java.time.OffsetDateTime.parse(v).toInstant();
      } catch (java.time.format.DateTimeParseException e2) {
        return null;
      }
    }
  }
}
