package com.pmart.backoffice.bff.application;

import static com.pmart.backoffice.bff.application.ViewSupport.stamp;
import static com.pmart.backoffice.bff.application.ViewSupport.text;

import com.pmart.backoffice.bff.infrastructure.ApprovalClient;
import com.pmart.backoffice.bff.infrastructure.LookupClients;
import com.pmart.backoffice.bff.infrastructure.LookupClients.Source;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;

/**
 * 지금 일 건수(0.15.0, 2026-11-18 사용자 "진행해" — 백오피스 웹 대시보드 요청 D4). OPERATOR_ADMIN 만. 원천에서 센 지금 시각 스냅샷 —
 * 사건·후속 처리·교대 인계·문의는 case {@code /cases/metrics}(정확), 처리 대기는 {@link PendingWorkView} 모음, 승인 심사 대기와
 * 끝나지 않은 배송은 원천 목록을 쪽 넘김으로 센다(상한에 걸리면 {@code complete=false} — "적어도 이만큼"). 원천 실패는 그 묶음 줄 없이
 * unavailableSources — 0건으로 바꾸지 않는다(BO-28). 늦은 배송 수는 세지 않는다(배송마다 도착 예정이 필요).
 */
@Service
public class WorkCountsView {

  static final String APPROVAL = "approval-service";
  static final List<String> APPROVAL_STATES = List.of("REVIEW_PENDING", "EVIDENCE_REQUIRED");
  static final int APPROVAL_PAGE = 200;
  static final int APPROVAL_MAX_PAGES = 10;

  /** 승인 줄 key — 지금 보는 사람이 결정할 수 있는 심사 대기(AP-3). */
  static final String DECIDABLE_BY_ME = "DECIDABLE_BY_ME";

  static final int DELIVERY_PAGE = 50;
  static final int DELIVERY_MAX_PAGES = 10;

  private static final Logger log = LoggerFactory.getLogger(WorkCountsView.class);

  private final LookupClients clients;
  private final ApprovalClient approvals;
  private final PendingWorkView pendingWork;
  private final Clock clock;

  public WorkCountsView(
      LookupClients clients, ApprovalClient approvals, PendingWorkView pendingWork, Clock clock) {
    this.clients = clients;
    this.approvals = approvals;
    this.pendingWork = pendingWork;
    this.clock = clock;
  }

  public Map<String, Object> view() {
    PmartPrincipal p =
        PmartPrincipal.current()
            .filter(x -> x.type() == PmartPrincipal.PrincipalType.OPERATOR)
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "운영자만 볼 수 있습니다."));
    if (!p.hasRole("OPERATOR_ADMIN")) {
      throw new PmartException(ErrorCode.FORBIDDEN, "일 건수는 본사 관리자(OPERATOR_ADMIN)만 봅니다.");
    }
    String token =
        PmartPrincipal.currentTokenValue()
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "운영자 토큰이 필요합니다."));
    Instant now = clock.instant();
    List<Map<String, Object>> counts = new ArrayList<>();
    List<Map<String, Object>> sources = new ArrayList<>();
    List<String> unavailable = new ArrayList<>();

    caseCounts(token, now, counts, sources, unavailable);
    pendingWorkCounts(counts, sources, unavailable);
    approvalCounts(token, now, counts, sources, unavailable);
    deliveryCounts(token, now, counts, sources, unavailable);

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("composedAt", now.toString());
    out.put("sources", sources);
    out.put("unavailableSources", unavailable);
    out.put("counts", counts);
    return out;
  }

  private void caseCounts(
      String token,
      Instant now,
      List<Map<String, Object>> counts,
      List<Map<String, Object>> sources,
      List<String> unavailable) {
    String sys = Source.CASE.system;
    JsonNode m;
    try {
      m = clients.get(Source.CASE, token, "/cases/metrics", Map.of(), Map.of());
    } catch (HttpClientErrorException | PmartException e) {
      log.warn("일 건수: case 현황 실패 — {}", e.getClass().getSimpleName());
      unavailable.add(sys);
      sources.add(stamp(sys, now, false));
      return;
    }
    String asOf = text(m, "asOf");
    sources.add(stamp(sys, asOf == null ? now.toString() : asOf, "CURRENT", "DIRECT"));
    for (JsonNode c : m.path("cases")) {
      String kind = text(c, "kind");
      counts.add(row("CASES", "OPEN", kind, c.path("open").asLong(0), true, sys));
      counts.add(row("CASES", "UNASSIGNED", kind, c.path("unassigned").asLong(0), true, sys));
      counts.add(row("CASES", "OVERDUE", kind, c.path("overdue").asLong(0), true, sys));
    }
    for (JsonNode o : m.path("obligations")) {
      String owner = text(o, "owner");
      counts.add(row("OBLIGATIONS", "OPEN", owner, o.path("open").asLong(0), true, sys));
      counts.add(row("OBLIGATIONS", "OVERDUE", owner, o.path("overdue").asLong(0), true, sys));
    }
    counts.add(
        row("SHIFT_HANDOVERS", "PENDING", null, m.path("pendingHandovers").asLong(0), true, sys));
    for (JsonNode i : m.path("inquiries")) {
      counts.add(
          row(
              "INQUIRIES",
              "AWAITING_ANSWER",
              text(i, "requesterType"),
              i.path("awaitingAnswer").asLong(0),
              true,
              sys));
    }
  }

  @SuppressWarnings("unchecked")
  private void pendingWorkCounts(
      List<Map<String, Object>> counts,
      List<Map<String, Object>> sources,
      List<String> unavailable) {
    Map<String, Object> pw = pendingWork.collect();
    boolean complete = !Boolean.TRUE.equals(pw.get("truncated"));
    Map<String, Long> byKind = new TreeMap<>();
    for (Map<String, Object> i : (List<Map<String, Object>>) pw.get("items")) {
      byKind.merge(String.valueOf(i.get("kind")), 1L, Long::sum);
    }
    byKind.forEach(
        (kind, n) -> counts.add(row("PENDING_WORK", "COUNT", kind, n, complete, sourceOf(kind))));
    sources.addAll((List<Map<String, Object>>) pw.get("sources"));
    unavailable.addAll((List<String>) pw.get("unavailableSources"));
  }

  private static String sourceOf(String kind) {
    return switch (kind) {
      case "STUCK_SAGA" -> PendingWorkView.ORCHESTRATION;
      case "FULFILLMENT_ISSUE" -> PendingWorkView.ORDER;
      default -> PendingWorkView.MFC;
    };
  }

  private void approvalCounts(
      String token,
      Instant now,
      List<Map<String, Object>> counts,
      List<Map<String, Object>> sources,
      List<String> unavailable) {
    List<Map<String, Object>> rows = new ArrayList<>();
    try {
      for (String state : APPROVAL_STATES) {
        approvalRows(token, state, null, state, rows);
      }
      // AP-3(approval 0.4.0): 지금 보는 사람이 지금 결정할 수 있는 것만 — 판정은 approval(decidableByMe=true)
      approvalRows(token, null, Boolean.TRUE, DECIDABLE_BY_ME, rows);
    } catch (HttpClientErrorException | PmartException e) {
      log.warn("일 건수: 승인 목록 실패 — {}", e.getClass().getSimpleName());
      unavailable.add(APPROVAL);
      sources.add(stamp(APPROVAL, now, false));
      return; // 반만 보이지 않게 승인 줄 모두 뺀다
    }
    counts.addAll(rows);
    sources.add(stamp(APPROVAL, now, true));
  }

  /** 승인 목록을 쪽 상한까지 읽어 종류별로 센다(상한에 걸리면 complete=false). */
  private void approvalRows(
      String token,
      @Nullable String reviewState,
      @Nullable Boolean decidableByMe,
      String key,
      List<Map<String, Object>> rows) {
    Map<String, Long> byType = new TreeMap<>();
    boolean complete = true;
    String cursor = null;
    for (int page = 0; ; page++) {
      if (page == APPROVAL_MAX_PAGES) {
        complete = false;
        break;
      }
      JsonNode body =
          approvals.list(
              new ApprovalClient.Query(
                  null, reviewState, null, APPROVAL_PAGE, cursor, decidableByMe),
              token);
      for (JsonNode a : body.path("items")) {
        byType.merge(String.valueOf(text(a, "type")), 1L, Long::sum);
      }
      cursor = text(body.path("page"), "nextCursor");
      if (cursor == null) {
        break;
      }
    }
    boolean done = complete;
    byType.forEach((type, n) -> rows.add(row("APPROVALS", key, type, n, done, APPROVAL)));
  }

  private void deliveryCounts(
      String token,
      Instant now,
      List<Map<String, Object>> counts,
      List<Map<String, Object>> sources,
      List<String> unavailable) {
    String sys = Source.EXECUTION.system;
    List<Map<String, Object>> rows = new ArrayList<>();
    try {
      for (String state : LiveDeliveryView.ACTIVE_STATES) {
        long n = 0;
        boolean complete = true;
        String cursor = null;
        for (int page = 0; ; page++) {
          if (page == DELIVERY_MAX_PAGES) {
            complete = false;
            break;
          }
          Map<String, Object> q = new LinkedHashMap<>();
          q.put("state", state);
          q.put("size", DELIVERY_PAGE);
          if (cursor != null) {
            q.put("cursor", cursor);
          }
          JsonNode body = clients.get(Source.EXECUTION, token, "/deliveries", Map.of(), q);
          n += body.path("items").size();
          cursor = text(body.path("page"), "nextCursor");
          if (cursor == null) {
            break;
          }
        }
        if (n > 0) {
          rows.add(row("DELIVERIES", "ACTIVE", state, n, complete, sys));
        }
      }
    } catch (HttpClientErrorException | PmartException e) {
      log.warn("일 건수: 배송 목록 실패 — {}", e.getClass().getSimpleName());
      unavailable.add(sys);
      sources.add(stamp(sys, now, false));
      return;
    }
    counts.addAll(rows);
    sources.add(stamp(sys, now, true));
  }

  private static Map<String, Object> row(
      String group,
      String key,
      @Nullable String segment,
      long value,
      boolean complete,
      String source) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("group", group);
    m.put("key", key);
    m.put("segment", segment);
    m.put("value", value);
    m.put("complete", complete);
    m.put("source", source);
    return m;
  }
}
