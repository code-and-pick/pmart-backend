package com.pmart.cdc.outbound.domain;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 피킹 Task 공통 규칙: 위치 코드 7구간 패턴(구역 = 둘째·층 = 셋째 구간 — 아니면 구역·층을 비운 한 묶음), 할당이 잡은 양, 실적 뒤 상태. 작업 묶기는
 * {@link BatchPlan}(0.9.0).
 */
public final class PickPlan {

  static final Pattern CODE =
      Pattern.compile("^[A-Z0-9]+-[A-Z]-[A-Z0-9]+-[0-9]{2}-[0-9]{2}-[0-9]+-[0-9]+$");

  private PickPlan() {}

  /** 할당이 지금 잡고 있는 양(LOT·위치). */
  public record Held(String sku, String lot, String locationCode, int qty) {}

  /** 줄 결과 반영 뒤 Task 상태: 모두 끝(피킹 + 부족 = 계획)이면 DONE, 부족이 있으면 PARTIAL, 아니면 그대로. */
  public static String afterReport(String current, List<int[]> lines) {
    boolean allDone = true;
    boolean anyShort = false;
    for (int[] l : lines) { // planned, picked, short
      if (l[1] + l[2] < l[0]) {
        allDone = false;
      }
      if (l[2] > 0) {
        anyShort = true;
      }
    }
    if (!allDone) {
      return current;
    }
    return anyShort ? "PARTIAL" : "DONE";
  }
}
