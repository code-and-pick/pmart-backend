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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;

/**
 * 지표 화면(0.13.0, 2026-11-18 사용자 "일단 그렇게 하고" — 추천안). 업무 원문 BO-28: 분모를 줄여 비율을 올리지 않는다, 분모 0 은 N/A, 승인된
 * 정의만. 지금은 CASE_SLA 하나(원천 case {@code /cases/metrics/sla}) — 그 밖 지표는 501. 판정할 수 없는 건(기한이 없던 건)은
 * 분모에서 빼되 {@code unjudged} 로 따로 보인다(숨기지 않음).
 */
@Service
public class MetricsView {

  private static final Logger log = LoggerFactory.getLogger(MetricsView.class);
  static final String CASE_SLA = "CASE_SLA";
  static final String SHORTAGE_RATE = "SHORTAGE_RATE";

  /** 상품별 결품 정렬(원천 F-13 값 그대로). */
  private static final java.util.Set<String> SKU_SORTS =
      java.util.Set.of("shortLines", "shortRate");

  static final String ON_TIME_DELIVERY = "ON_TIME_DELIVERY";
  static final List<String> SHORT_REASONS = List.of("SHORT", "DAMAGED", "BLOCKED", "SPOILED");

  /** 원천에 칸이 있을 때만 점을 내는 사유(BF-MFC-S — mfc-fulfillment 0.22.0 변질, 선택 칸). 없는 칸을 0 으로 보이지 않게. */
  static final java.util.Set<String> OPTIONAL_REASONS = java.util.Set.of("SPOILED");

  private final LookupClients clients;
  private final Clock clock;

  public MetricsView(LookupClients clients, Clock clock) {
    this.clients = clients;
    this.clock = clock;
  }

  public Map<String, Object> view(
      String metric,
      LocalDate from,
      LocalDate to,
      @Nullable String centerId,
      @Nullable String granularity) {
    PmartPrincipal.current()
        .filter(x -> x.type() == PmartPrincipal.PrincipalType.OPERATOR)
        .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "직원만 쓸 수 있는 화면입니다."));
    String token =
        PmartPrincipal.currentTokenValue()
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "운영자 토큰이 필요합니다."));
    if (SHORTAGE_RATE.equals(metric)) {
      return shortageRate(token, from, to, centerId, granularity);
    }
    if (ON_TIME_DELIVERY.equals(metric)) {
      return onTimeDelivery(token, from, to, centerId, granularity);
    }
    if (!CASE_SLA.equals(metric)) {
      throw new PmartException(
          "NOT_IMPLEMENTED",
          HttpStatus.NOT_IMPLEMENTED,
          "PAYMENT_UNKNOWN".equals(metric)
              ? "결제 결과 불명은 지표로 만들지 않습니다 — 처리 대기·사건함에서 보세요."
              : "아직 정의·원천이 없는 지표입니다(지금은 CASE_SLA·SHORTAGE_RATE·ON_TIME_DELIVERY).",
          false,
          List.of());
    }
    Instant now = clock.instant();
    Map<String, Object> q = new LinkedHashMap<>();
    q.put("from", from.toString());
    q.put("to", to.toString());
    q.put("granularity", granularity == null ? "DAY" : granularity);
    if (centerId != null && !centerId.isBlank()) {
      q.put("centerId", centerId.strip());
    }
    JsonNode sla;
    try {
      sla = clients.get(Source.CASE, token, "/cases/metrics/sla", Map.of(), q);
    } catch (HttpClientErrorException e) {
      throw rejected(e, "접수 건");
    } catch (PmartException e) {
      // 주 원천이 없으면 빈 목록(0건)으로 보이지 않게 503 — 0건과 모름을 섞지 않는다(BO-28)
      log.warn("지표: case-service 실패 — {}", e.code());
      throw new PmartException(
          ErrorCode.DEPENDENCY_UNAVAILABLE, "접수 건 원천에 닿지 않았습니다 — 잠시 뒤 다시 보세요.");
    }
    String asOf = text(sla, "asOf");
    Instant sourceAt = asOf == null ? now : Instant.parse(asOf);

    List<Map<String, Object>> points = new ArrayList<>();
    Map<String, long[]> totals =
        new LinkedHashMap<>(); // bucket → {numerator, denominator, unjudged}
    for (JsonNode r : sla.path("rows")) {
      long closed = r.path("closed").asLong(0);
      long onTime = r.path("closedOnTime").asLong(0);
      long noSla = r.path("closedWithoutSla").asLong(0);
      long denominator = closed - noSla;
      String bucket = text(r, "bucket");
      Map<String, Object> p = new LinkedHashMap<>();
      p.put("bucket", bucket);
      p.put("numerator", onTime);
      p.put("denominator", denominator);
      p.put("ratio", ratio(onTime, denominator));
      p.put("unjudged", noSla);
      Map<String, Object> seg = new LinkedHashMap<>();
      seg.put("kind", text(r, "kind"));
      seg.put("assigneeGroup", text(r, "assigneeGroup"));
      p.put("segment", seg);
      p.put("source", stamp(Source.CASE.system, sourceAt, true));
      points.add(p);
      long[] t = totals.computeIfAbsent(bucket, k -> new long[3]);
      t[0] += onTime;
      t[1] += denominator;
      t[2] += noSla;
    }
    List<Map<String, Object>> totalRows = new ArrayList<>();
    totals.forEach(
        (b, t) -> {
          Map<String, Object> m = new LinkedHashMap<>();
          m.put("bucket", b);
          m.put("numerator", t[0]);
          m.put("denominator", t[1]);
          m.put("ratio", ratio(t[0], t[1]));
          m.put("unjudged", t[2]);
          totalRows.add(m);
        });

    List<Map<String, Object>> sources = new ArrayList<>();
    sources.add(stamp(Source.CASE.system, sourceAt, true));
    List<String> unavailable = new ArrayList<>();
    List<Map<String, Object>> overdue = null;
    try {
      JsonNode snap = clients.get(Source.CASE, token, "/cases/metrics", Map.of(), Map.of());
      overdue = new ArrayList<>();
      for (JsonNode c : snap.path("cases")) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", text(c, "kind"));
        m.put("open", c.path("open").asLong(0));
        m.put("overdue", c.path("overdue").asLong(0));
        overdue.add(m);
      }
    } catch (HttpClientErrorException | PmartException e) {
      // 보조 원천 — 기간 지표는 그대로 두고 지금 기한 지난 건만 모름
      log.warn("지표: 지금 기한 지난 건 조회 실패 — {}", e.getClass().getSimpleName());
      unavailable.add(Source.CASE.system + " /cases/metrics");
    }

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("composedAt", now.toString());
    out.put("sources", sources);
    out.put("unavailableSources", unavailable);
    out.put("metric", CASE_SLA);
    out.put("timeZone", "Asia/Seoul");
    out.put("points", points);
    out.put("totals", totalRows);
    out.put("currentOverdue", overdue);
    return out;
  }

  /**
   * 상품(SKU)별 결품(0.23.0, 2026-11-20 — 웹 BR-73 SK-2, BF-3). 원천 mfc-fulfillment 0.32.0 {@code GET
   * /metrics/picking-lines/by-sku}(F-13)를 직원 토큰으로 그대로 — 세는 법·정렬·결품률 하한(minPickedLines)은 원천이 정하고 적용한
   * 값을 그대로 싣는다. 비율은 싣지 않는다(화면이 shortLines/pickedLines). 상품명은 원천 사본만(없으면 null). 원천 4xx 그대로, 장애 503.
   */
  public Map<String, Object> shortageBySku(
      LocalDate from,
      LocalDate to,
      @Nullable String centerId,
      @Nullable String sort,
      @Nullable Integer limit,
      @Nullable Integer minPickedLines) {
    PmartPrincipal.current()
        .filter(x -> x.type() == PmartPrincipal.PrincipalType.OPERATOR)
        .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "직원만 쓸 수 있는 화면입니다."));
    String token =
        PmartPrincipal.currentTokenValue()
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "운영자 토큰이 필요합니다."));
    if (sort != null && !SKU_SORTS.contains(sort)) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "정렬은 shortLines·shortRate 중 하나입니다.");
    }
    Instant now = clock.instant();
    Map<String, Object> q = new LinkedHashMap<>();
    q.put("from", from.toString());
    q.put("to", to.toString());
    if (centerId != null && !centerId.isBlank()) {
      q.put("mfcId", centerId.strip());
    }
    if (sort != null) {
      q.put("sort", sort);
    }
    if (limit != null) {
      q.put("limit", limit);
    }
    if (minPickedLines != null) {
      q.put("minPickedLines", minPickedLines);
    }
    JsonNode body;
    try {
      body = clients.get(Source.FULFILLMENT, token, "/metrics/picking-lines/by-sku", Map.of(), q);
    } catch (HttpClientErrorException e) {
      throw rejected(e, "MFC 피킹");
    } catch (PmartException e) {
      log.warn("상품별 결품: mfc-fulfillment 실패 — {}", e.code());
      throw new PmartException(
          ErrorCode.DEPENDENCY_UNAVAILABLE, "MFC 피킹 원천에 닿지 않았습니다 — 잠시 뒤 다시 보세요.");
    }
    String asOf = text(body, "asOf");
    Instant sourceAt = asOf == null ? now : Instant.parse(asOf);
    List<Map<String, Object>> items = new ArrayList<>();
    for (JsonNode r : body.path("items")) {
      Map<String, Object> i = new LinkedHashMap<>();
      i.put("sku", text(r, "sku"));
      i.put("productName", text(r, "productName"));
      i.put("pickedLines", r.path("pickedLines").asLong(0));
      i.put("shortLines", r.path("shortLines").asLong(0));
      Map<String, Object> byReason = new LinkedHashMap<>();
      for (Map.Entry<String, JsonNode> e : r.path("shortLinesByReason").properties()) {
        byReason.put(e.getKey(), e.getValue().asLong(0));
      }
      i.put("shortLinesByReason", byReason);
      items.add(i);
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("composedAt", now.toString());
    out.put("sources", List.of(stamp(Source.FULFILLMENT.system, sourceAt, true)));
    out.put("unavailableSources", List.of());
    out.put("sort", text(body, "sort"));
    JsonNode min = body.path("minPickedLines");
    out.put("minPickedLines", min.isNumber() ? min.asInt() : null);
    out.put("items", items);
    return out;
  }

  /**
   * 결품률(0.20.0, 2026-11-19 — PC 3 F-10 mfc-fulfillment 0.19.0 `GET /metrics/picking-lines`). 사용자
   * 결정(2026-11-18): **줄 단위·사유별** — numerator = 결품이 보고된 줄(shortLines), denominator = 피킹을 시작한
   * 줄(pickedLines, 원래 주문 작업만), 날짜·센터마다. 사유별 점(segment.reason = SHORT·DAMAGED·BLOCKED)은 같은 분모에 그 사유
   * 줄 수 — 한 줄에 사유가 둘이면 둘 다 세서 합이 전체보다 클 수 있다. totals 는 사유 없는 점(전체)만 더한다. 날짜 단위만(시간 단위는 400), 기간은 원천
   * 상한(92일). 판정 불가 개념이 없어 unjudged 0.
   */
  private Map<String, Object> shortageRate(
      String token,
      LocalDate from,
      LocalDate to,
      @Nullable String centerId,
      @Nullable String granularity) {
    if (granularity != null && !"DAY".equals(granularity)) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "결품률은 날짜 단위(DAY)만 있습니다.");
    }
    Instant now = clock.instant();
    Map<String, Object> q = new LinkedHashMap<>();
    q.put("from", from.toString());
    q.put("to", to.toString());
    if (centerId != null && !centerId.isBlank()) {
      q.put("mfcId", centerId.strip());
    }
    JsonNode body;
    try {
      body = clients.get(Source.FULFILLMENT, token, "/metrics/picking-lines", Map.of(), q);
    } catch (HttpClientErrorException e) {
      throw rejected(e, "MFC 피킹");
    } catch (PmartException e) {
      log.warn("지표: mfc-fulfillment 실패 — {}", e.code());
      throw new PmartException(
          ErrorCode.DEPENDENCY_UNAVAILABLE, "MFC 피킹 원천에 닿지 않았습니다 — 잠시 뒤 다시 보세요.");
    }
    String asOf = text(body, "asOf");
    Instant sourceAt = asOf == null ? now : Instant.parse(asOf);
    List<Map<String, Object>> points = new ArrayList<>();
    Map<String, long[]> totals = new LinkedHashMap<>(); // 날짜 → {결품 줄, 피킹 줄}
    for (JsonNode r : body.path("items")) {
      String bucket = text(r, "date");
      String mfc = text(r, "mfcId");
      long picked = r.path("pickedLines").asLong(0);
      long shortLines = r.path("shortLines").asLong(0);
      points.add(shortPoint(bucket, mfc, null, shortLines, picked, sourceAt));
      for (String reason : SHORT_REASONS) {
        if (OPTIONAL_REASONS.contains(reason) && !r.path("shortLinesByReason").has(reason)) {
          continue;
        }
        points.add(
            shortPoint(
                bucket,
                mfc,
                reason,
                r.path("shortLinesByReason").path(reason).asLong(0),
                picked,
                sourceAt));
      }
      long[] t = totals.computeIfAbsent(bucket, k -> new long[2]);
      t[0] += shortLines;
      t[1] += picked;
    }
    List<Map<String, Object>> totalRows = new ArrayList<>();
    totals.forEach(
        (b, t) -> {
          Map<String, Object> m = new LinkedHashMap<>();
          m.put("bucket", b);
          m.put("numerator", t[0]);
          m.put("denominator", t[1]);
          m.put("ratio", ratio(t[0], t[1]));
          m.put("unjudged", 0L);
          totalRows.add(m);
        });
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("composedAt", now.toString());
    out.put("sources", List.of(stamp(Source.FULFILLMENT.system, sourceAt, true)));
    out.put("unavailableSources", List.of());
    out.put("metric", SHORTAGE_RATE);
    out.put("timeZone", "Asia/Seoul");
    out.put("points", points);
    out.put("totals", totalRows);
    out.put("currentOverdue", null); // 접수 건 지표에만
    return out;
  }

  /**
   * 정시 배송률(0.21.0, 2026-11-19 — PC 5 DD-4 dispatch 0.13.0 `GET /metrics/on-time-deliveries`, 사용자 결정
   * DD-4: dispatch 가 배송 단위로, 시간대 지난 미전달은 늦음). numerator = onTime, denominator = due(= onTime +
   * late), unjudged = 고객 확인·자동 완료(분모에서 뺌), pending = 아직 약속 전(분모에서 뺌 — 따로 보인다). 날짜 = 약속 끝 한국 날짜, 센터별
   * 점 + 날짜 합계. DAY 만, 원천 31일 상한·4xx 그대로, 장애 503.
   */
  private Map<String, Object> onTimeDelivery(
      String token,
      LocalDate from,
      LocalDate to,
      @Nullable String centerId,
      @Nullable String granularity) {
    if (granularity != null && !"DAY".equals(granularity)) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "정시 배송률은 날짜 단위(DAY)만 있습니다.");
    }
    Instant now = clock.instant();
    Map<String, Object> q = new LinkedHashMap<>();
    q.put("from", from.toString());
    q.put("to", to.toString());
    if (centerId != null && !centerId.isBlank()) {
      q.put("mfcId", centerId.strip());
    }
    JsonNode body;
    try {
      body = clients.get(Source.DISPATCH, token, "/metrics/on-time-deliveries", Map.of(), q);
    } catch (HttpClientErrorException e) {
      throw rejected(e, "배차");
    } catch (PmartException e) {
      log.warn("지표: delivery-dispatch 실패 — {}", e.code());
      throw new PmartException(ErrorCode.DEPENDENCY_UNAVAILABLE, "배차 원천에 닿지 않았습니다 — 잠시 뒤 다시 보세요.");
    }
    String asOf = text(body, "asOf");
    Instant sourceAt = asOf == null ? now : Instant.parse(asOf);
    List<Map<String, Object>> points = new ArrayList<>();
    Map<String, long[]> totals = new LinkedHashMap<>(); // 날짜 → {정시, 분모, 판정 불가, 약속 전}
    for (JsonNode r : body.path("items")) {
      String bucket = text(r, "date");
      long onTime = r.path("onTime").asLong(0);
      long due = r.path("due").asLong(0);
      long unjudged = r.path("unjudged").asLong(0);
      long pending = r.path("pending").asLong(0);
      Map<String, Object> p = new LinkedHashMap<>();
      p.put("bucket", bucket);
      p.put("numerator", onTime);
      p.put("denominator", due);
      p.put("ratio", ratio(onTime, due));
      p.put("unjudged", unjudged);
      p.put("pending", pending);
      Map<String, Object> seg = new LinkedHashMap<>();
      seg.put("mfcId", text(r, "mfcId"));
      p.put("segment", seg);
      p.put("source", stamp(Source.DISPATCH.system, sourceAt, true));
      points.add(p);
      long[] t = totals.computeIfAbsent(bucket, k -> new long[4]);
      t[0] += onTime;
      t[1] += due;
      t[2] += unjudged;
      t[3] += pending;
    }
    List<Map<String, Object>> totalRows = new ArrayList<>();
    totals.forEach(
        (b, t) -> {
          Map<String, Object> m = new LinkedHashMap<>();
          m.put("bucket", b);
          m.put("numerator", t[0]);
          m.put("denominator", t[1]);
          m.put("ratio", ratio(t[0], t[1]));
          m.put("unjudged", t[2]);
          m.put("pending", t[3]);
          totalRows.add(m);
        });
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("composedAt", now.toString());
    out.put("sources", List.of(stamp(Source.DISPATCH.system, sourceAt, true)));
    out.put("unavailableSources", List.of());
    out.put("metric", ON_TIME_DELIVERY);
    out.put("timeZone", "Asia/Seoul");
    out.put("points", points);
    out.put("totals", totalRows);
    out.put("currentOverdue", null);
    return out;
  }

  private static Map<String, Object> shortPoint(
      @Nullable String bucket,
      @Nullable String mfcId,
      @Nullable String reason,
      long numerator,
      long denominator,
      Instant sourceAt) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("bucket", bucket);
    p.put("numerator", numerator);
    p.put("denominator", denominator);
    p.put("ratio", ratio(numerator, denominator));
    p.put("unjudged", 0L);
    Map<String, Object> seg = new LinkedHashMap<>();
    seg.put("mfcId", mfcId);
    seg.put("reason", reason);
    p.put("segment", seg);
    p.put("source", stamp(Source.FULFILLMENT.system, sourceAt, true));
    return p;
  }

  static @Nullable Double ratio(long numerator, long denominator) {
    return denominator <= 0 ? null : (double) numerator / denominator;
  }

  private static PmartException rejected(HttpClientErrorException e, String what) {
    int sc = e.getStatusCode().value();
    if (sc == 401) {
      return new PmartException(
          "UNAUTHENTICATED", HttpStatus.UNAUTHORIZED, "다시 로그인하세요.", false, List.of());
    }
    if (sc == 403) {
      return new PmartException(ErrorCode.FORBIDDEN, "지표는 본사 관리자만 봅니다.");
    }
    if (sc == 400) {
      String detail = "기간·조건이 올바르지 않습니다.";
      try {
        String d =
            new tools.jackson.databind.ObjectMapper()
                .readTree(e.getResponseBodyAsString())
                .path("detail")
                .asString(null);
        if (d != null) {
          detail = d;
        }
      } catch (RuntimeException ignored) {
        // 본문을 못 읽으면 기본 문구
      }
      return new PmartException(ErrorCode.VALIDATION_FAILED, detail);
    }
    return new PmartException(
        "SOURCE_REJECTED", HttpStatus.valueOf(sc), what + " 원천이 거절했습니다.", false, List.of());
  }
}
