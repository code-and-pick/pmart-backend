package com.pmart.mfc.capacity.domain;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * PDA 업무(작업자가 고르는 것) ↔ 작업 유형 묶음. 순수 계산.
 *
 * <p>사용자 결정 2026-11-10(BR-52 R4): 입고 = RECEIVE / 품질검사 = INSPECT + 회수품 검사 / 재고 이동 =
 * PUTAWAY·REPLENISH·MOVE·RETURN + 회수품 반입 / 재고 실사 = 실사 / 피패킹 = PICK·PACK. 회수품 검사·회수품 반입·실사는 아직 배정
 * 가능한 작업 유형이 없어(BR-52 R2·R12~R14) 묶음에 넣지 않았다 — 생기면 여기에 더한다. DISPATCH(배차 요청 — 점원)는 어느 업무에도 없다.
 */
public final class WorkAreas {

  public static final String WORK_AREA_NOT_ALLOWED = "WORK_AREA_NOT_ALLOWED";

  /** 업무 → 작업 유형(계약 WorkArea 순서). */
  public static final Map<String, Set<String>> TASK_TYPES = areas();

  private static Map<String, Set<String>> areas() {
    Map<String, Set<String>> m = new LinkedHashMap<>();
    m.put("INBOUND", Set.of("RECEIVE"));
    m.put("INSPECTION", Set.of("INSPECT"));
    m.put("STOCK_MOVE", Set.of("PUTAWAY", "REPLENISH", "MOVE", "RETURN"));
    m.put("STOCK_COUNT", Set.of());
    m.put("PICK_PACK", Set.of("PICK", "PACK"));
    return java.util.Collections.unmodifiableMap(m);
  }

  /** 지금 고를 수 있는 업무 — 묶음이 유효 업무와 하나라도 겹치는 것(계약 순서). */
  public static List<String> allowed(Collection<String> effective) {
    return TASK_TYPES.entrySet().stream()
        .filter(e -> e.getValue().stream().anyMatch(effective::contains))
        .map(Map.Entry::getKey)
        .toList();
  }

  /**
   * 다음 작업 받기에서 쓸 작업 유형: 업무를 안 골랐으면 유효 업무 전체, 골랐으면 그 묶음 ∩ 유효 업무. 모르는 업무면 IllegalArgumentException.
   */
  public static Set<String> narrow(Set<String> effective, @Nullable String workArea) {
    if (workArea == null) {
      return effective;
    }
    Set<String> area = TASK_TYPES.get(workArea);
    if (area == null) {
      throw new IllegalArgumentException("모르는 업무: " + workArea);
    }
    Set<String> out = new TreeSet<>(area);
    out.retainAll(effective);
    return out;
  }

  private WorkAreas() {}
}
