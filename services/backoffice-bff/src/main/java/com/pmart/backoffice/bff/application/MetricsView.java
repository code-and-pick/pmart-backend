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
    if (!CASE_SLA.equals(metric)) {
      throw new PmartException(
          "NOT_IMPLEMENTED",
          HttpStatus.NOT_IMPLEMENTED,
          "PAYMENT_UNKNOWN".equals(metric)
              ? "결제 결과 불명은 지표로 만들지 않습니다 — 처리 대기·사건함에서 보세요."
              : "아직 정의·원천이 없는 지표입니다(지금은 CASE_SLA 만).",
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
      throw rejected(e);
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

  static @Nullable Double ratio(long numerator, long denominator) {
    return denominator <= 0 ? null : (double) numerator / denominator;
  }

  private static PmartException rejected(HttpClientErrorException e) {
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
        "CASE_SOURCE_REJECTED", HttpStatus.valueOf(sc), "접수 건 원천이 거절했습니다.", false, List.of());
  }
}
