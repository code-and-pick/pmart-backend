package com.pmart.backoffice.bff.application;

import static com.pmart.backoffice.bff.application.ViewSupport.text;

import com.pmart.backoffice.bff.infrastructure.LookupClients;
import com.pmart.backoffice.bff.infrastructure.LookupClients.Source;
import com.pmart.common.web.PmartException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;

/**
 * 화면 직원 표시(ADR-0070 — "이름 · 사번", 2026-11-19 HR-4). 화면 응답 하나에서 나온 운영자 ID 를 모아 hr-service {@code GET
 * /hr/employee-labels?operatorIds=}(직원 토큰, 50개씩)로 한 번에 읽는다. 덤으로 붙이는 값이라 실패해도 화면은 그대로 — 이름·사번은 null
 * 이고 부른 화면이 hr-service 를 unavailableSources 에 넣는다(403 은 권한의 답이라 장애로 보이지 않음 — {@link RiderDisplays}
 * 와 같음). 모르는 ID 는 원천처럼 빠진다(이름 null). 운영자 ID·이름은 로그에 남기지 않는다.
 */
final class OperatorLabels {

  static final int BATCH = 50;
  private static final Logger log = LoggerFactory.getLogger(OperatorLabels.class);

  private OperatorLabels() {}

  /**
   * 운영자 ID → {name, employeeNo}. 원천 실패(연결·5xx·403·404 밖 4xx)면 null — 부른 쪽이 {@code Source.HR} 를 실패로
   * 표시한다. 403·404 는 빈 결과(장애 아님). 빈 목록이면 부르지 않고 빈 맵.
   */
  static @Nullable Map<String, JsonNode> lookup(
      LookupClients clients, String token, Collection<String> operatorIds) {
    List<String> ids = new ArrayList<>(new LinkedHashSet<>(operatorIds));
    ids.removeIf(id -> id == null || id.isBlank());
    Map<String, JsonNode> out = new HashMap<>();
    for (int from = 0; from < ids.size(); from += BATCH) {
      List<String> part = List.copyOf(ids.subList(from, Math.min(ids.size(), from + BATCH)));
      try {
        // 반복 파라미터(?operatorIds=a&operatorIds=b) — LookupClients 가 Collection 을 반복으로 보낸다
        JsonNode body =
            clients.get(
                Source.HR, token, "/hr/employee-labels", Map.of(), Map.of("operatorIds", part));
        for (JsonNode e : body.path("items")) {
          String id = text(e, "operatorId");
          if (id != null) {
            out.put(id, e);
          }
        }
      } catch (HttpClientErrorException.NotFound | HttpClientErrorException.Forbidden e) {
        // 없음·권한의 답 — 이름만 null, 장애로 보이지 않게
        return out;
      } catch (HttpClientErrorException | PmartException e) {
        log.warn("화면 직원 이름: hr-service 실패 — {}", e.getClass().getSimpleName());
        return null;
      }
    }
    return out;
  }

  /**
   * 화면 직원 칸 {operatorId, name, employeeNo} — operatorId 가 없으면 null. 원천을 못 읽었거나 모르는 ID 면
   * name·employeeNo null 이고 operatorId 는 그대로(상세의 "시스템 번호" 칸용).
   */
  static @Nullable Map<String, Object> of(
      @Nullable String operatorId, @Nullable Map<String, JsonNode> found) {
    if (operatorId == null || operatorId.isBlank()) {
      return null;
    }
    JsonNode e = found == null ? null : found.get(operatorId);
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("operatorId", operatorId);
    m.put("name", e == null ? null : text(e, "name"));
    m.put("employeeNo", e == null ? null : text(e, "employeeNo"));
    return m;
  }
}
