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
 * 포장 인계 현황(계약 0.11.0 — 2026-11-17 사용자 "포장 인계 현황 진행하자", 배송 업무 원문 §10.1·§3.1·§3.4·§3.7). 원천
 * mfc-fulfillment {@code GET /shipments}(0.15.0, PC 3 F-9) 한 번 + 출고마다 delivery-execution 배송 한 번.
 *
 * <ul>
 *   <li>역할: 본사 셋(OPERATOR_ADMIN·CS_AGENT·RIDER_OPS — mfcId 필수) 또는 센터 관리자(자기 센터 고정 — 사용자 2026-11-17
 *       "그래 넣고"). 작업자만 가진 직원은 403.
 *   <li>단계(stage)는 {@link #stage} 규칙으로만 — 사용자 2026-11-17 "BFF로 단계표시". 규칙에 없는 조합은 null.
 *   <li>원천 실패·403 은 unavailableSources(0건으로 바꾸지 않음). 배송은 한 번 실패하면 남은 출고는 부르지 않는다.
 * </ul>
 */
@Service
public class PackingHandoverView {

  static final Set<String> SHIPMENT_STATES =
      Set.of(
          "PREPARING",
          "READY",
          "PARTIALLY_HANDED_OVER",
          "HANDED_OVER",
          "CANCELLED",
          "CLOSED_PARTIAL");

  /** 작업이 아직 시작 전인 TaskState. */
  static final Set<String> NOT_STARTED = Set.of("WAITING", "READY", "ASSIGNED");

  static final int MAX_SIZE = 50;
  private static final Logger log = LoggerFactory.getLogger(PackingHandoverView.class);

  private final LookupClients clients;
  private final Clock clock;

  public PackingHandoverView(LookupClients clients, Clock clock) {
    this.clients = clients;
    this.clock = clock;
  }

  public Map<String, Object> view(
      @Nullable String mfcId, @Nullable List<String> states, int size, @Nullable String cursor) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .filter(x -> x.type() == PmartPrincipal.PrincipalType.OPERATOR)
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "직원만 쓸 수 있는 화면입니다."));
    if (UnifiedLookupView.ROLES.stream().noneMatch(p::hasRole)
        && !p.hasRole(LiveDeliveryView.MFC_SUPERVISOR)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "이 화면을 볼 권한이 없습니다.");
    }
    String token =
        PmartPrincipal.currentTokenValue()
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "운영자 토큰이 필요합니다."));
    String mfc = LiveDeliveryView.centerFor(p, blankToNull(mfcId));
    if (mfc == null) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "mfcId 를 골라 주세요.");
    }
    List<String> st = new ArrayList<>();
    if (states != null) {
      for (String s : states) {
        String v = blankToNull(s);
        if (v == null) {
          continue;
        }
        if (!SHIPMENT_STATES.contains(v)) {
          throw new PmartException(ErrorCode.VALIDATION_FAILED, "state 가 올바르지 않습니다.");
        }
        if (!st.contains(v)) {
          st.add(v);
        }
      }
    }
    int n = Math.min(Math.max(size, 1), MAX_SIZE);
    Instant now = clock.instant();
    Set<Source> used = EnumSet.of(Source.FULFILLMENT);
    Set<Source> failed = EnumSet.noneOf(Source.class);

    // 1) 센터 출고(MFC) — 한 번
    Map<String, Object> q = new LinkedHashMap<>();
    q.put("mfcId", mfc);
    if (!st.isEmpty()) {
      q.put("state", st);
    }
    q.put("size", n);
    if (blankToNull(cursor) != null) {
      q.put("cursor", cursor.strip());
    }
    JsonNode page = null;
    try {
      page = clients.get(Source.FULFILLMENT, token, "/shipments", Map.of(), q);
    } catch (HttpClientErrorException.BadRequest e) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "조회 조건이 올바르지 않습니다.");
    } catch (HttpClientErrorException | PmartException e) {
      log.warn("포장 인계 현황: 센터 출고 실패 — {}", e.getClass().getSimpleName());
      failed.add(Source.FULFILLMENT);
    }
    String mfcAsOf = page == null ? null : text(page, "asOf");
    String nextCursor = page == null ? null : text(page, "nextCursor");

    // 2) 출고마다 배송(execution)
    List<Map<String, Object>> items = new ArrayList<>();
    if (page != null) {
      for (JsonNode s : page.path("items")) {
        items.add(item(s, token, mfc, now, mfcAsOf, used, failed));
      }
    }

    // 3) 0.14.0(ADR-0070): 라이더 번호·가린 이름 — 배송 배정 라이더·포장 인계 라이더를 한 번에(실패해도 화면은 그대로)
    List<String> riderIds = new ArrayList<>();
    for (Map<String, Object> i : items) {
      if (i.get("riderId") instanceof String r) {
        riderIds.add(r);
      }
      for (Map<String, Object> pkg : packagesOf(i)) {
        if (pkg.get("riderId") instanceof String r) {
          riderIds.add(r);
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
    for (Map<String, Object> i : items) {
      i.put("rider", RiderDisplays.of((String) i.get("riderId"), riders));
      for (Map<String, Object> pkg : packagesOf(i)) {
        pkg.put("rider", RiderDisplays.of((String) pkg.get("riderId"), riders));
      }
    }

    List<Map<String, Object>> sources = new ArrayList<>();
    List<String> unavailable = new ArrayList<>();
    for (Source s : used) {
      boolean bad = failed.contains(s);
      if (bad) {
        unavailable.add(s.system);
      }
      sources.add(
          stamp(
              s.system,
              s == Source.FULFILLMENT && mfcAsOf != null ? mfcAsOf : now.toString(),
              !bad));
    }
    Map<String, Object> pg = new LinkedHashMap<>();
    pg.put("nextCursor", nextCursor);
    pg.put("size", items.size());
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("composedAt", now.toString());
    out.put("sources", sources);
    out.put("unavailableSources", unavailable);
    out.put("items", items);
    out.put("page", pg);
    return out;
  }

  private Map<String, Object> item(
      JsonNode s,
      String token,
      String mfc,
      Instant now,
      @Nullable String mfcAsOf,
      Set<Source> used,
      Set<Source> failed) {
    String asOf = mfcAsOf != null ? mfcAsOf : now.toString();
    String shipmentState = text(s, "state");
    JsonNode pick = s.path("pick");
    JsonNode pack = s.path("pack");
    Map<String, Object> i = new LinkedHashMap<>();
    i.put("shipmentId", text(s, "shipmentId"));
    i.put("orderId", text(s, "orderId"));
    i.put("orderNumber", text(s, "orderNumber"));
    i.put("fulfillmentId", text(s, "fulfillmentId"));
    i.put("mfcId", text(s, "mfcId"));
    i.put("workOrderKind", text(s, "workOrderKind"));
    i.put(
        "stage",
        stage(
            shipmentState,
            pick.isObject() ? text(pick, "state") : null,
            pack.isObject() ? text(pack, "state") : null));
    i.put("shipment", stamped(shipmentState, null, Source.FULFILLMENT.system, asOf, true));
    i.put("createdAt", text(s, "createdAt"));
    i.put("readyAt", text(s, "readyAt"));
    i.put("dispatchRequestedAt", text(s, "dispatchRequestedAt"));
    i.put("pick", task(pick));
    i.put("pack", task(pack));
    JsonNode counts = s.path("packageCounts");
    Integer total = intOrNull(counts.path("total"));
    Integer handed = intOrNull(counts.path("handedOver"));
    i.put("packageCount", total);
    i.put("sealedPackages", intOrNull(counts.path("sealed")));
    Map<String, Object> qty = new LinkedHashMap<>();
    qty.put("qty", handed);
    qty.put("source", stamp(Source.FULFILLMENT.system, asOf, true));
    i.put("handedOverQty", qty);
    // 같은 원천·같은 기준시각의 두 수라 뺄셈 가능(§3.7 남은 포장)
    i.put("remainingPackages", total != null && handed != null ? total - handed : null);
    List<Map<String, Object>> pkgs = new ArrayList<>();
    for (JsonNode p : s.path("packages")) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("packageId", text(p, "packageId"));
      m.put("state", text(p, "state"));
      m.put("fenceState", text(p, "fenceState"));
      m.put("handoverAuthorizedAt", text(p, "handoverAuthorizedAt"));
      m.put("handedOverAt", text(p, "handedOverAt"));
      m.put("riderId", text(p, "riderId"));
      pkgs.add(m);
    }
    i.put("packages", pkgs);

    // 배송 — 이 요청에서 한 번 실패하면 남은 출고는 부르지 않고 UNKNOWN
    used.add(Source.EXECUTION);
    String orderId = text(s, "orderId");
    boolean ok = !failed.contains(Source.EXECUTION);
    JsonNode delivery = null;
    if (ok && orderId != null) {
      Map<String, Object> dq = new LinkedHashMap<>();
      dq.put("orderId", orderId);
      dq.put("mfcId", mfc);
      dq.put("type", "RESHIPMENT".equals(text(s, "workOrderKind")) ? "RESHIPMENT" : "FORWARD");
      dq.put("sort", "NEWEST");
      dq.put("size", 1);
      try {
        JsonNode list = clients.get(Source.EXECUTION, token, "/deliveries", Map.of(), dq);
        JsonNode first = list.path("items").path(0);
        delivery = first.isObject() ? first : null;
      } catch (HttpClientErrorException.NotFound e) {
        delivery = null; // 배송 없음 — 원천의 답
      } catch (HttpClientErrorException | PmartException e) {
        log.warn("포장 인계 현황: 배송 실패 — {}", e.getClass().getSimpleName());
        failed.add(Source.EXECUTION);
        ok = false;
      }
    }
    i.put("deliveryId", delivery == null ? null : text(delivery, "deliveryId"));
    // 0.14.0(ADR-0070): 화면 배송 번호("주문번호-순번", execution 값 그대로)
    i.put("deliveryNumber", delivery == null ? null : text(delivery, "deliveryNumber"));
    i.put(
        "delivery",
        stamped(
            delivery == null ? null : text(delivery, "state"),
            delivery == null ? null : delivery.path("version"),
            Source.EXECUTION.system,
            now.toString(),
            ok));
    i.put("riderId", delivery == null ? null : text(delivery, "riderId"));
    return i;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> packagesOf(Map<String, Object> item) {
    return item.get("packages") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
  }

  /**
   * 단계(계약 0.11.0 규칙 — 사용자 2026-11-17 "BFF로 단계표시"). 규칙에 없는 조합은 null — 추정하지 않는다.
   *
   * @param shipment 출고 ShipmentState
   * @param pick PICK 작업 TaskState(작업 없으면 null)
   * @param pack PACK 작업 TaskState(작업 없으면 null)
   */
  static @Nullable String stage(
      @Nullable String shipment, @Nullable String pick, @Nullable String pack) {
    if (shipment == null) {
      return null;
    }
    switch (shipment) {
      case "HANDED_OVER":
        return "HANDED_OVER";
      case "PARTIALLY_HANDED_OVER":
        return "PARTIALLY_HANDED_OVER";
      case "READY":
        return "READY_FOR_HANDOVER";
      case "PREPARING":
        break;
      default:
        return null;
    }
    boolean packNotStarted = pack == null || NOT_STARTED.contains(pack);
    // 패킹 진행·막힘 → 패킹중(막힌 사유는 pack.blockReason) — 사용자 2026-11-17 "(가)"
    if ("IN_PROGRESS".equals(pack) || "BLOCKED".equals(pack)) {
      return "PACKING";
    }
    if ("IN_PROGRESS".equals(pick) || "BLOCKED".equals(pick)) {
      return "PICKING";
    }
    // 피킹 끝·패킹 시작 전 → 패킹중(원문 네 단계 안에서 묶음 — 사용자 2026-11-17 "(가)", 실제 시작 여부는 pack.state)
    if ("COMPLETED".equals(pick) && packNotStarted) {
      return "PACKING";
    }
    boolean pickNotStarted = pick == null || NOT_STARTED.contains(pick);
    return pickNotStarted && packNotStarted ? "REQUESTED" : null;
  }

  private static @Nullable Map<String, Object> task(JsonNode t) {
    if (!t.isObject()) {
      return null;
    }
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("state", text(t, "state"));
    m.put("blockReason", text(t, "blockReason"));
    m.put("startedAt", text(t, "startedAt"));
    return m;
  }

  private static Map<String, Object> stamped(
      @Nullable String state, @Nullable JsonNode version, String system, String asOf, boolean ok) {
    Map<String, Object> s = new LinkedHashMap<>();
    s.put("state", state);
    s.put("version", version != null && version.isNumber() ? version.asLong() : null);
    s.put("at", null);
    s.put("source", stamp(system, asOf, ok));
    return s;
  }

  private static @Nullable Integer intOrNull(JsonNode n) {
    return n.isNumber() ? n.asInt() : null;
  }

  private static @Nullable String blankToNull(@Nullable String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }
}
