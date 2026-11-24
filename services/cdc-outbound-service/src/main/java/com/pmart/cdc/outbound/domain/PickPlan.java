package com.pmart.cdc.outbound.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Wave 릴리스 때 할당 LOT·위치를 피킹 Task 로 묶는 규칙(0.3.0): TRF × 구역(코드 둘째 구간) × 층(셋째 구간)마다 Task 하나. 위치 코드가 7구간
 * 패턴이 아니면 구역·층을 비운 한 묶음으로 둔다. 같은 SKU·LOT·위치가 여러 번 오면 수량을 합친다.
 */
public final class PickPlan {

  static final Pattern CODE =
      Pattern.compile("^[A-Z0-9]+-[A-Z]-[A-Z0-9]+-[0-9]{2}-[0-9]{2}-[0-9]+-[0-9]+$");

  private PickPlan() {}

  /** 할당이 지금 잡고 있는 양(LOT·위치). */
  public record Held(String sku, String lot, String locationCode, int qty) {}

  public record Line(String sku, String lot, String locationCode, int qty) {}

  public record Group(@Nullable String zone, @Nullable String floor, List<Line> lines) {}

  public static List<Group> group(List<Held> held) {
    Map<String, Map<String, Line>> byGroup = new LinkedHashMap<>();
    Map<String, String[]> keys = new LinkedHashMap<>();
    for (Held h : held) {
      if (h.qty() <= 0) {
        continue;
      }
      String zone = null;
      String floor = null;
      if (CODE.matcher(h.locationCode()).matches()) {
        String[] p = h.locationCode().split("-");
        zone = p[1];
        floor = p[2];
      }
      String gk = zone + "|" + floor;
      keys.putIfAbsent(gk, new String[] {zone, floor});
      String lk = h.sku() + "|" + h.lot() + "|" + h.locationCode();
      byGroup
          .computeIfAbsent(gk, k -> new LinkedHashMap<>())
          .merge(
              lk,
              new Line(h.sku(), h.lot(), h.locationCode(), h.qty()),
              (a, b) -> new Line(a.sku(), a.lot(), a.locationCode(), a.qty() + b.qty()));
    }
    List<Group> out = new ArrayList<>();
    for (Map.Entry<String, Map<String, Line>> e : byGroup.entrySet()) {
      String[] k = keys.get(e.getKey());
      out.add(new Group(k[0], k[1], List.copyOf(e.getValue().values())));
    }
    return out;
  }

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
