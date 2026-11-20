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
 * 백오피스 "고객" 메뉴(BF-2, 0.22.0 — 2026-11-20, 사용자 2026-11-19 "응 그대로 진행해"). customer 0.11.0(CU-3·CU-4)의
 * 직원용 찾기·상세를 <b>호출한 직원의 토큰</b>으로 읽고(권한은 원천 판정), 상세에는 최근 주문·문의·상담원 접수를 칸으로 붙인다.
 *
 * <ul>
 *   <li>값은 원천 그대로(가린 값만). 원문 열람은 화면이 customer {@code POST /customers/{id}/pii-reveals} 를 직접 — bff 는
 *       원문을 다루지 않는다.
 *   <li>찾는 값(고객번호·전화·주문번호)·고객 ID·이름은 로그에 남기지 않는다(예외 이름만).
 *   <li>칸 원천 실패·403 은 그 칸만 null + unavailableSources(0건으로 바꾸지 않음). 클레임 칸은 claim CL-7 뒤.
 * </ul>
 */
@Service
public class CustomerViews {

  static final int CELL_SIZE = 10;
  private static final Logger log = LoggerFactory.getLogger(CustomerViews.class);

  private final LookupClients clients;
  private final Clock clock;

  public CustomerViews(LookupClients clients, Clock clock) {
    this.clients = clients;
    this.clock = clock;
  }

  /** 고객 찾기 — 고객번호·전화·주문번호 중 하나만(완전 일치). */
  public Map<String, Object> search(
      @Nullable String customerNumber, @Nullable String phone, @Nullable String orderNumber) {
    int given =
        (blank(customerNumber) ? 0 : 1) + (blank(phone) ? 0 : 1) + (blank(orderNumber) ? 0 : 1);
    if (given != 1) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "고객번호·전화번호·주문번호 중 하나만 넣어 주세요.");
    }
    String token = token();
    Instant now = clock.instant();
    Set<Source> used = EnumSet.noneOf(Source.class);
    Set<Source> failed = EnumSet.noneOf(Source.class);
    List<Map<String, Object>> items = new ArrayList<>();

    Map<String, Object> q = new LinkedHashMap<>();
    if (!blank(orderNumber)) {
      used.add(Source.ORDER);
      JsonNode orders =
          primary(
              Source.ORDER,
              token,
              "/orders/lookups",
              Map.of("orderNumber", orderNumber, "size", 50),
              failed,
              "주문");
      Set<String> ids = new LinkedHashSet<>();
      if (orders != null) {
        for (JsonNode o : orders.path("items")) {
          if (text(o, "customerId") != null) {
            ids.add(text(o, "customerId"));
          }
        }
      }
      if (ids.isEmpty()) {
        return out(now, used, failed, Map.of("items", items));
      }
      q.put("customerIds", String.join(",", ids));
    } else if (!blank(customerNumber)) {
      q.put("customerNumber", customerNumber);
    } else {
      q.put("phone", phone);
    }
    used.add(Source.CUSTOMER);
    JsonNode found = primary(Source.CUSTOMER, token, "/customers", q, failed, "고객");
    if (found != null) {
      for (JsonNode c : found.path("items")) {
        items.add(summary(c));
      }
    }
    return out(now, used, failed, Map.of("items", items));
  }

  /** 고객 상세 — 머리(customer) + 최근 주문·문의·상담원 접수 칸. */
  public Map<String, Object> detail(String customerId) {
    String token = token();
    Instant now = clock.instant();
    Set<Source> used = EnumSet.of(Source.CUSTOMER);
    Set<Source> failed = EnumSet.noneOf(Source.class);

    JsonNode c;
    try {
      c =
          clients.get(
              Source.CUSTOMER, token, "/customers/{id}", Map.of("id", customerId), Map.of());
    } catch (HttpClientErrorException.NotFound e) {
      throw new PmartException(ErrorCode.NOT_FOUND, "고객을 찾을 수 없습니다.");
    } catch (HttpClientErrorException e) {
      throw rejected(e, "고객");
    }
    // 머리 원천 장애(PmartException)는 그대로 503 — 머리 없이 칸만 보이지 않게

    Map<String, Object> head = new LinkedHashMap<>();
    head.put("customerId", text(c, "customerId") == null ? customerId : text(c, "customerId"));
    for (String f :
        List.of(
            "customerNumber",
            "nameMasked",
            "phoneMasked",
            "emailMasked",
            "status",
            "socialProvider",
            "joinedAt")) {
      head.put(f, text(c, f));
    }
    head.put(
        "phoneVerified",
        c.path("phoneVerified").isBoolean() ? c.path("phoneVerified").asBoolean() : null);

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("customer", head);

    // 주문 — order 직원용 검색(customerId = accountId), 연락처는 싣지 않는다
    used.add(Source.ORDER);
    JsonNode orders =
        cell(
            Source.ORDER,
            token,
            "/orders/lookups",
            Map.of("customerId", customerId, "size", CELL_SIZE),
            failed);
    body.put(
        "orders",
        orders == null
            ? null
            : cellOut(
                orders,
                orders.path("page").path("hasNext").asBoolean(false),
                List.of(
                    "orderId",
                    "orderNumber",
                    "status",
                    "confirmedAt",
                    "mfcId",
                    "deliveryMode",
                    "totalAmount",
                    "itemCount",
                    "openFulfillmentIssues")));

    // 문의 — case 0.29.0 customerId 거르기(제목·내용 없음)
    used.add(Source.INQUIRY);
    JsonNode inquiries =
        cell(
            Source.INQUIRY,
            token,
            "/inquiries",
            Map.of("customerId", customerId, "size", CELL_SIZE),
            failed);
    body.put(
        "inquiries",
        inquiries == null
            ? null
            : cellOut(
                inquiries,
                text(inquiries.path("page"), "nextCursor") != null,
                List.of(
                    "inquiryId",
                    "inquiryNumber",
                    "category",
                    "status",
                    "orderId",
                    "origin",
                    "channel",
                    "createdAt",
                    "answeredAt")));

    // 상담원 접수 — case customerRef(= accountId), 요약 글은 싣지 않는다
    used.add(Source.CASE);
    JsonNode intakes =
        cell(
            Source.CASE,
            token,
            "/cs-intakes",
            Map.of("customerRef", customerId, "size", CELL_SIZE),
            failed);
    if (intakes == null) {
      body.put("csIntakes", null);
    } else {
      Map<String, Object> cellMap =
          cellOut(
              intakes,
              text(intakes.path("page"), "nextCursor") != null,
              List.of(
                  "intakeId",
                  "caseId",
                  "inquiryId",
                  "channel",
                  "category",
                  "subjectRef",
                  "callerRelation",
                  "linked",
                  "receivedAt",
                  "receivedBy"));
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> rows = (List<Map<String, Object>>) cellMap.get("items");
      List<String> staff = new ArrayList<>();
      for (Map<String, Object> r : rows) {
        if (r.get("receivedBy") instanceof String s && !s.isBlank()) {
          staff.add(s);
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
      for (Map<String, Object> r : rows) {
        r.put("receivedByLabel", OperatorLabels.of((String) r.get("receivedBy"), labels));
      }
      body.put("csIntakes", cellMap);
    }
    return out(now, used, failed, body);
  }

  // ------------------------------------------------------------------ 공통

  private static Map<String, Object> summary(JsonNode c) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("customerId", text(c, "customerId"));
    m.put("customerNumber", text(c, "customerNumber"));
    m.put("nameMasked", text(c, "nameMasked"));
    m.put("phoneMasked", text(c, "phoneMasked"));
    m.put("status", text(c, "status"));
    return m;
  }

  /** 칸 — 고른 필드만 원천 값 그대로(숫자·참거짓은 그 형으로). */
  private static Map<String, Object> cellOut(JsonNode page, boolean hasMore, List<String> fields) {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (JsonNode n : page.path("items")) {
      Map<String, Object> r = new LinkedHashMap<>();
      for (String f : fields) {
        JsonNode v = n.path(f);
        r.put(
            f,
            v.isMissingNode() || v.isNull()
                ? null
                : v.isValueNode() && !v.isString() ? v : v.asString());
      }
      rows.add(r);
    }
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("hasMore", hasMore);
    m.put("items", rows);
    return m;
  }

  /** 찾기 주 원천 — 401·403·400 그대로, 장애는 null(빈 목록 + unavailableSources). */
  private @Nullable JsonNode primary(
      Source source, String token, String path, Map<String, ?> q, Set<Source> failed, String what) {
    try {
      return clients.get(source, token, path, Map.of(), q);
    } catch (HttpClientErrorException e) {
      throw rejected(e, what);
    } catch (PmartException e) {
      log.warn("고객 찾기: {} 실패 — {}", source.system, e.getClass().getSimpleName());
      failed.add(source);
      return null;
    }
  }

  /** 상세 칸 — 실패·403·그 밖 4xx 면 null(그 칸만 확인 불가) + unavailableSources. */
  private @Nullable JsonNode cell(
      Source source, String token, String path, Map<String, ?> q, Set<Source> failed) {
    try {
      return clients.get(source, token, path, Map.of(), q);
    } catch (HttpClientErrorException | PmartException e) {
      log.warn("고객 상세 칸: {} 실패 — {}", source.system, e.getClass().getSimpleName());
      failed.add(source);
      return null;
    }
  }

  private static PmartException rejected(HttpClientErrorException e, String what) {
    int sc = e.getStatusCode().value();
    if (sc == 401) {
      return new PmartException(
          "UNAUTHENTICATED", HttpStatus.UNAUTHORIZED, "다시 로그인하세요.", false, List.of());
    }
    if (sc == 403) {
      return new PmartException(ErrorCode.FORBIDDEN, what + " 정보를 볼 권한이 없습니다.");
    }
    if (sc == 400) {
      return new PmartException(ErrorCode.VALIDATION_FAILED, what + " 조회 조건이 올바르지 않습니다.");
    }
    return new PmartException(
        "SOURCE_REJECTED", HttpStatus.valueOf(sc), what + " 원천이 거절했습니다.", false, List.of());
  }

  private static Map<String, Object> out(
      Instant now, Set<Source> used, Set<Source> failed, Map<String, Object> body) {
    List<Map<String, Object>> sources = new ArrayList<>();
    List<String> unavailable = new ArrayList<>();
    for (Source s : used) {
      boolean bad = failed.contains(s);
      if (bad) {
        unavailable.add(s.system);
      }
      sources.add(stamp(s.system, now, !bad));
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("composedAt", now.toString());
    out.put("sources", sources);
    out.put("unavailableSources", unavailable);
    out.putAll(body);
    return out;
  }

  private static boolean blank(@Nullable String v) {
    return v == null || v.isBlank();
  }

  private static String token() {
    return PmartPrincipal.currentTokenValue()
        .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "운영자 토큰이 필요합니다."));
  }
}
