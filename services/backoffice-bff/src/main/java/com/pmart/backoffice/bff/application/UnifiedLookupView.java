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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;

/**
 * 통합 조회(2026-11-16 사용자 "통합 조회로 진행해", 업무 원문 BO-11·§5.1 — 계약 0.3.0).
 *
 * <ul>
 *   <li>역할 OPERATOR_ADMIN·CS_AGENT·RIDER_OPS. 원천은 호출한 직원 토큰으로 다시 판정한다.
 *   <li>찾는 값: 주문·배송·사건·고객 ID(완전 일치). ANY 는 접두어로. TRANSFER·TRIP·모르는 접두어는 422 UNSUPPORTED_LOOKUP.
 *   <li>주문 하나로 잇는다 — 원천마다 따로(ORDER·DELIVERY·DISPATCH·CLAIM·CASE·INQUIRY), 상태를 합치지 않는다. 실패·권한 없음은
 *       unavailableSources(0건·정상으로 바꾸지 않는다), 없는 대상(404)은 항목 없음.
 *   <li>summary 는 원천 필드 중 개인정보가 아닌 것만 고른다(이름·연락처·주소·메모 없음). 찾는 값은 로그에 남기지 않는다(고객 ID).
 * </ul>
 */
@Service
public class UnifiedLookupView {

  static final List<String> ROLES = List.of("OPERATOR_ADMIN", "CS_AGENT", "RIDER_OPS");
  static final int MAX_DELIVERIES = 10;

  private static final Logger log = LoggerFactory.getLogger(UnifiedLookupView.class);

  private static final List<String> ORDER_DETAIL_FIELDS =
      List.of(
          "orderId",
          "customerId",
          "status",
          "version",
          "mfcId",
          "deliveryMode",
          "arrivalWindow",
          "confirmedAt",
          "outOfStockPreference",
          "plannedShipments",
          "amounts",
          "payment");
  private static final List<String> ORDER_SUMMARY_FIELDS =
      List.of(
          "orderId",
          "customerId",
          "status",
          "version",
          "mfcId",
          "deliveryMode",
          "arrivalWindow",
          "confirmedAt",
          "totalAmount",
          "approvedAmount",
          "itemCount",
          "lineCount",
          "plannedShipments",
          "openFulfillmentIssues");
  private static final List<String> DELIVERY_FIELDS =
      List.of(
          "deliveryId",
          "version",
          "type",
          "orderId",
          "mfcId",
          "riderId",
          "state",
          "assignedAt",
          "pickedUpAt",
          "completedAt",
          "createdAt");
  private static final List<String> DISPATCH_FIELDS =
      List.of(
          "deliveryId",
          "version",
          "type",
          "orderId",
          "mfcId",
          "state",
          "arrivalWindow",
          "assignment",
          "attempts",
          "delayAlertedAt",
          "createdAt",
          "updatedAt");
  private static final List<String> INQUIRY_FIELDS =
      List.of(
          "inquiryId",
          "category",
          "orderId",
          "status",
          "origin",
          "createdAt",
          "answeredAt",
          "version");
  private static final List<String> CLAIM_FIELDS =
      List.of("claimId", "orderId", "type", "state", "openedAt");

  /** F-3 작업 지시 — 원천 필드 그대로(shipments 안에 작업·결품 보고·포장·인계, 직원 ID 만 — 메모·QR 없음). */
  private static final List<String> FULFILLMENT_FIELDS =
      List.of(
          "kind",
          "mfcId",
          "workVersion",
          "acceptance",
          "rejectReason",
          "rejectMessage",
          "decidedAt",
          "preparationStartedAt",
          "createdAt",
          "shipments");

  private static final List<String> MFC_STOP_FIELDS =
      List.of("scope", "state", "lastError", "requestedAt", "failedAt", "resolvedAt");

  private static final List<String> CASE_FIELDS =
      List.of(
          "caseId",
          "kind",
          "state",
          "priority",
          "summary",
          "subjectType",
          "subjectRef",
          "centerId",
          "assigneeId",
          "assigneeGroup",
          "slaDueAt",
          "overdue",
          "refs",
          "openedAt",
          "updatedAt");

  private final LookupClients clients;
  private final Clock clock;

  public UnifiedLookupView(LookupClients clients, Clock clock) {
    this.clients = clients;
    this.clock = clock;
  }

  /** 찾는 값의 종류(계약 kind 중 지금 되는 것). */
  enum Kind {
    ORDER,
    DELIVERY,
    CASE,
    CUSTOMER
  }

  public Map<String, Object> view(String q, @Nullable String kind) {
    requireRole();
    String token =
        PmartPrincipal.currentTokenValue()
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "운영자 토큰이 필요합니다."));
    String id = q.strip();
    if (id.isEmpty()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "q(찾는 값)가 필요합니다.");
    }
    Kind k = resolveKind(kind, id);
    Run run = new Run(token, clock.instant());
    String orderId = null;
    switch (k) {
      case ORDER -> {
        orderId = id;
        composeOrder(run, id);
      }
      case DELIVERY -> {
        JsonNode d = run.get(Source.EXECUTION, "/deliveries/{id}", Map.of("id", id), Map.of());
        orderId = d == null ? null : text(d, "orderId");
        if (orderId != null) {
          composeOrder(run, orderId);
        } else if (d != null) {
          run.add("DELIVERY", id, pick(d, DELIVERY_FIELDS), Source.EXECUTION);
          composeDeliveries(run, List.of(id));
          composeCases(run, List.of(id));
        }
      }
      case CASE -> {
        JsonNode c = run.get(Source.CASE, "/cases/{id}", Map.of("id", id), Map.of());
        if (c != null) {
          run.add("CASE", id, pick(c, CASE_FIELDS), Source.CASE);
          orderId = ref(c, "orderId");
          String deliveryId = ref(c, "deliveryId");
          if (orderId == null && deliveryId != null) {
            JsonNode d =
                run.get(Source.EXECUTION, "/deliveries/{id}", Map.of("id", deliveryId), Map.of());
            orderId = d == null ? null : text(d, "orderId");
          }
          if (orderId != null) {
            composeOrder(run, orderId);
          }
        }
      }
      case CUSTOMER -> {
        JsonNode page =
            run.get(
                Source.ORDER, "/orders/lookups", Map.of(), Map.of("customerId", id, "size", 50));
        if (page != null) {
          for (JsonNode o : page.path("items")) {
            run.add("ORDER", text(o, "orderId"), pick(o, ORDER_SUMMARY_FIELDS), Source.ORDER);
          }
        }
      }
    }
    Map<String, Object> resolved = new LinkedHashMap<>();
    resolved.put("kind", k.name());
    resolved.put("orderId", orderId);
    Map<String, Object> out = run.result();
    out.put("resolved", resolved);
    return out;
  }

  /** 주문 하나 → 주문·배송·배차·클레임·사건. */
  private void composeOrder(Run run, String orderId) {
    JsonNode order = run.get(Source.ORDER, "/orders/lookups/{id}", Map.of("id", orderId), Map.of());
    if (order != null) {
      Map<String, Object> s = pick(order, ORDER_DETAIL_FIELDS);
      if (order.path("lines").isArray()) {
        s.put("lineCount", order.path("lines").size());
      }
      if (order.path("fulfillmentIssues").isArray()) {
        s.put("fulfillmentIssueCount", order.path("fulfillmentIssues").size());
      }
      run.add("ORDER", orderId, s, Source.ORDER);
    }
    List<String> deliveryIds = new ArrayList<>();
    JsonNode deliveries =
        run.get(
            Source.EXECUTION,
            "/deliveries",
            Map.of(),
            Map.of("orderId", orderId, "size", 50, "sort", "OLDEST"));
    if (deliveries != null) {
      for (JsonNode d : deliveries.path("items")) {
        String deliveryId = text(d, "deliveryId");
        if (deliveryId == null) {
          continue;
        }
        deliveryIds.add(deliveryId);
        run.add("DELIVERY", deliveryId, pick(d, DELIVERY_FIELDS), Source.EXECUTION);
      }
    }
    composeDeliveries(run, deliveryIds);
    JsonNode claims = run.get(Source.CLAIM, "/claims", Map.of(), Map.of("orderId", orderId));
    if (claims != null) {
      for (JsonNode c : claims.path("items")) {
        run.add("CLAIM", text(c, "claimId"), pick(c, CLAIM_FIELDS), Source.CLAIM);
      }
    }
    List<String> subjects = new ArrayList<>();
    subjects.add(orderId);
    subjects.addAll(deliveryIds);
    composeCases(run, subjects);
    composeInquiries(run, orderId);
    composeFulfillment(run, orderId);
  }

  /**
   * 그 주문의 MFC 현장 상태(F-3, mfc-fulfillment 0.10.0 {@code GET /work-orders?orderId=} — 직원 토큰). 작업 지시마다
   * FULFILLMENT (결정·출고별 작업·결품 보고·포장·인계 — 원천 모양 그대로), 중지 요청마다 MFC_STOP. 원천에 메모·QR·개인정보 없음. 404 = 그
   * 주문의 현장 기록 없음.
   */
  private void composeFulfillment(Run run, String orderId) {
    JsonNode v = run.get(Source.FULFILLMENT, "/work-orders", Map.of(), Map.of("orderId", orderId));
    if (v == null) {
      return;
    }
    for (JsonNode w : v.path("workOrders")) {
      Map<String, Object> s = pick(w, FULFILLMENT_FIELDS);
      run.add("FULFILLMENT", text(w, "fulfillmentId"), s, Source.FULFILLMENT);
    }
    for (JsonNode st : v.path("stopRequests")) {
      run.add("MFC_STOP", text(st, "stopRequestId"), pick(st, MFC_STOP_FIELDS), Source.FULFILLMENT);
    }
  }

  /**
   * 그 주문의 고객 문의(case 0.5.0 {@code GET /inquiries?orderId=}). 볼 수 있는 역할은 CS_AGENT·OPERATOR_ADMIN 뿐 —
   * 그 밖(RIDER_OPS)은 403 이 {@code case-service/inquiries} 로 unavailableSources 에 남는다(사건 원천과 따로). 예전
   * case 가 orderId 를 모르고 전부 돌려줘도 섞이지 않게 여기서 한 번 더 거른다. 제목·내용은 고객이 쓴 글이라 싣지 않는다.
   */
  private void composeInquiries(Run run, String orderId) {
    JsonNode page =
        run.get(Source.INQUIRY, "/inquiries", Map.of(), Map.of("orderId", orderId, "size", 50));
    if (page != null) {
      for (JsonNode i : page.path("items")) {
        if (orderId.equals(text(i, "orderId"))) {
          run.add("INQUIRY", text(i, "inquiryId"), pick(i, INQUIRY_FIELDS), Source.INQUIRY);
        }
      }
    }
  }

  /** 배송마다 배차(앞 {@value #MAX_DELIVERIES}개). */
  private void composeDeliveries(Run run, List<String> deliveryIds) {
    for (String deliveryId : deliveryIds.stream().limit(MAX_DELIVERIES).toList()) {
      JsonNode d =
          run.get(Source.DISPATCH, "/deliveries/{id}/dispatch", Map.of("id", deliveryId), Map.of());
      if (d != null) {
        run.add("DISPATCH", deliveryId, pick(d, DISPATCH_FIELDS), Source.DISPATCH);
      }
    }
  }

  /** 주문·배송 번호로 사건 — 열린 것과 닫힌 것(그 직원이 볼 수 있는 종류만, case 가 판정). */
  private void composeCases(Run run, List<String> subjectRefs) {
    for (String subject : subjectRefs.stream().limit(MAX_DELIVERIES + 1).toList()) {
      // 열린 것(state 비움) + 닫힌 것(최근 20건) — 같은 주문에서 전에 있었던 일도 보이게
      for (Map<String, ?> q :
          List.<Map<String, ?>>of(
              Map.of("subjectRef", subject, "size", 50),
              Map.of("subjectRef", subject, "state", "CLOSED", "size", 20))) {
        JsonNode page = run.get(Source.CASE, "/cases", Map.of(), q);
        if (page != null) {
          for (JsonNode c : page.path("items")) {
            run.add("CASE", text(c, "caseId"), pick(c, CASE_FIELDS), Source.CASE);
          }
        }
      }
    }
  }

  static Kind resolveKind(@Nullable String kind, String id) {
    String k = kind == null || kind.isBlank() ? "ANY" : kind;
    return switch (k) {
      case "ORDER" -> Kind.ORDER;
      case "DELIVERY" -> Kind.DELIVERY;
      case "CASE" -> Kind.CASE;
      case "CUSTOMER" -> Kind.CUSTOMER;
      case "ANY" -> byPrefix(id).orElseThrow(() -> unsupported("찾는 값의 종류를 알 수 없습니다."));
      default -> throw unsupported(k + " 조회는 아직 없습니다(원천 준비 전).");
    };
  }

  private static Optional<Kind> byPrefix(String id) {
    if (id.startsWith("ORD-")) {
      return Optional.of(Kind.ORDER);
    }
    if (id.startsWith("DLV-")) {
      return Optional.of(Kind.DELIVERY);
    }
    if (id.startsWith("CASE-")) {
      return Optional.of(Kind.CASE);
    }
    if (id.startsWith("CU-")) {
      return Optional.of(Kind.CUSTOMER);
    }
    return Optional.empty();
  }

  private static PmartException unsupported(String message) {
    return new PmartException(
        "UNSUPPORTED_LOOKUP", HttpStatus.UNPROCESSABLE_ENTITY, message, false, List.of());
  }

  /** 배송·주문을 보는 직원 화면(통합 조회·실시간 배송 현황) 공통 — OPERATOR_ADMIN·CS_AGENT·RIDER_OPS. */
  static void requireRole() {
    PmartPrincipal p =
        PmartPrincipal.current()
            .filter(x -> x.type() == PmartPrincipal.PrincipalType.OPERATOR)
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "직원만 쓸 수 있는 화면입니다."));
    if (ROLES.stream().noneMatch(p::hasRole)) {
      throw new PmartException(
          ErrorCode.FORBIDDEN, "이 화면을 볼 권한이 없습니다(" + String.join("·", ROLES) + ").");
    }
  }

  /** 한 번의 조회 — 원천 호출·실패 기록·항목 모으기. */
  private final class Run {
    final String token;
    final Instant now;
    final Set<Source> used = EnumSet.noneOf(Source.class);
    final Set<Source> failed = EnumSet.noneOf(Source.class);
    final Set<String> seen = new LinkedHashSet<>();
    final List<Map<String, Object>> matches = new ArrayList<>();

    Run(String token, Instant now) {
      this.token = token;
      this.now = now;
    }

    /** 없는 대상(404)·실패·권한 없음이면 null — 실패·권한 없음은 그 원천을 unavailable 로. */
    @Nullable JsonNode get(
        Source source, String path, Map<String, ?> uriVariables, Map<String, ?> query) {
      used.add(source);
      try {
        return clients.get(source, token, path, uriVariables, query);
      } catch (HttpClientErrorException.NotFound e) {
        return null;
      } catch (HttpClientErrorException | PmartException e) {
        // 찾는 값(고객 ID 등)·응답 본문은 남기지 않는다 — 경로 틀과 오류 종류만
        log.warn("통합 조회: {} {} 실패 — {}", source.system, path, e.getClass().getSimpleName());
        failed.add(source);
        return null;
      }
    }

    void add(String kind, @Nullable String id, Map<String, Object> summary, Source source) {
      if (id == null || !seen.add(kind + ":" + id)) {
        return;
      }
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("kind", kind);
      m.put("id", id);
      m.put("summary", summary);
      m.put("source", stamp(source.system, now, "CURRENT", "DIRECT"));
      matches.add(m);
    }

    Map<String, Object> result() {
      List<Map<String, Object>> sources = new ArrayList<>();
      List<String> unavailable = new ArrayList<>();
      for (Source s : used) {
        if (failed.contains(s)) {
          unavailable.add(s.system);
          sources.add(stamp(s.system, now, "UNAVAILABLE", "UNKNOWN"));
        } else {
          sources.add(stamp(s.system, now, "CURRENT", "DIRECT"));
        }
      }
      Map<String, Object> out = new LinkedHashMap<>();
      out.put("composedAt", now.toString());
      out.put("sources", sources);
      out.put("unavailableSources", unavailable);
      out.put("matches", matches);
      return out;
    }
  }

  private static Map<String, Object> pick(JsonNode n, List<String> fields) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (String f : fields) {
      JsonNode v = n.path(f);
      if (!v.isMissingNode()) {
        out.put(f, v);
      }
    }
    return out;
  }

  private static @Nullable String ref(JsonNode caseNode, String refType) {
    for (JsonNode r : caseNode.path("refs")) {
      if (refType.equals(text(r, "refType"))) {
        return text(r, "refId");
      }
    }
    return null;
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
