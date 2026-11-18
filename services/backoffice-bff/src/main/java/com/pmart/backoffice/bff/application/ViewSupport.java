package com.pmart.backoffice.bff.application;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * 화면 조합 공통 보조(2026-11-18 — 화면마다 복사돼 있던 것을 모음, 동작 그대로). 원천 표시(SourceStamp: system·asOf{asOf,
 * freshness}·confidence)와 원천 JSON 값 읽기.
 */
final class ViewSupport {

  private ViewSupport() {}

  /** 원천 표시 — 값을 그대로. */
  static Map<String, Object> stamp(
      String system, String asOf, String freshness, String confidence) {
    Map<String, Object> s = new LinkedHashMap<>();
    s.put("system", system);
    s.put("asOf", Map.of("asOf", asOf, "freshness", freshness));
    s.put("confidence", confidence);
    return s;
  }

  static Map<String, Object> stamp(String system, Instant at, String freshness, String confidence) {
    return stamp(system, at.toString(), freshness, confidence);
  }

  /** 원천 표시 — 읽었으면 CURRENT·DIRECT, 못 읽었으면 UNAVAILABLE·UNKNOWN. */
  static Map<String, Object> stamp(String system, String asOf, boolean ok) {
    return stamp(system, asOf, ok ? "CURRENT" : "UNAVAILABLE", ok ? "DIRECT" : "UNKNOWN");
  }

  static Map<String, Object> stamp(String system, Instant at, boolean ok) {
    return stamp(system, at.toString(), ok);
  }

  /** 필드 값 — 없거나 null 이면 null. */
  static @Nullable String text(JsonNode n, String field) {
    JsonNode v = n.path(field);
    return v.isNull() || v.isMissingNode() ? null : v.asString();
  }
}
