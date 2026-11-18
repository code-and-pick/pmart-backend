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
 * 화면 라이더 표시(ADR-0070, 2026-11-18 — "가린 이름 + 라이더 번호 6자리"). 화면 응답 하나에서 나온 라이더 ID 를 모아 rider-service
 * {@code GET /riders?riderIds=}(직원 토큰, 50개씩)로 한 번에 읽는다. 덤으로 붙이는 값이라 실패해도 화면은 그대로 — 번호는 null 이고 부른
 * 화면이 rider-service 를 unavailableSources 에 넣는다. 404 는 원천의 답(없음). 라이더 ID·이름은 로그에 남기지 않는다.
 */
final class RiderDisplays {

  static final int BATCH = 50;
  private static final Logger log = LoggerFactory.getLogger(RiderDisplays.class);

  private RiderDisplays() {}

  /**
   * 라이더 ID → {riderNumber, nameMasked}. 원천 실패(403 포함)면 null — 부른 쪽이 {@code Source.RIDER} 를 실패로
   * 표시한다. 빈 목록이면 부르지 않고 빈 맵.
   */
  static @Nullable Map<String, JsonNode> lookup(
      LookupClients clients, String token, Collection<String> riderIds) {
    List<String> ids = new ArrayList<>(new LinkedHashSet<>(riderIds));
    ids.removeIf(id -> id == null || id.isBlank());
    Map<String, JsonNode> out = new HashMap<>();
    for (int from = 0; from < ids.size(); from += BATCH) {
      List<String> part = ids.subList(from, Math.min(ids.size(), from + BATCH));
      Map<String, Object> q = new LinkedHashMap<>();
      q.put("riderIds", String.join(",", part));
      q.put("size", BATCH);
      try {
        JsonNode page = clients.get(Source.RIDER, token, "/riders", Map.of(), q);
        for (JsonNode r : page.path("items")) {
          String id = text(r, "riderId");
          if (id != null) {
            out.put(id, r);
          }
        }
      } catch (HttpClientErrorException.NotFound e) {
        // 없음 — 원천의 답(번호 null)
      } catch (HttpClientErrorException | PmartException e) {
        log.warn("화면 라이더 번호: rider-service 실패 — {}", e.getClass().getSimpleName());
        return null;
      }
    }
    return out;
  }

  /**
   * 화면 라이더 칸 — riderId 가 없으면 null. 원천을 못 읽었거나(found null) 그 라이더가 없으면(탈퇴 등) 번호·이름 null 이고 riderId 는
   * 그대로(링크·상세용).
   */
  static @Nullable Map<String, Object> of(
      @Nullable String riderId, @Nullable Map<String, JsonNode> found) {
    if (riderId == null) {
      return null;
    }
    JsonNode r = found == null ? null : found.get(riderId);
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("riderId", riderId);
    m.put("riderNumber", r == null ? null : text(r, "riderNumber"));
    m.put("nameMasked", r == null ? null : text(r, "nameMasked"));
    return m;
  }
}
