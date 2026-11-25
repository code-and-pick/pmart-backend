package com.pmart.cdc.outbound.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * 묶음 피킹 작업 계획(0.9.0 — docs/cdc/design/2026-11-25-batch-picking.md, 사용자 결정 §7).
 *
 * <ul>
 *   <li>방식은 위치 통로 설정값(cdc-location pickMethod)으로 정한다 — 상품 분류 글자로 정하지 않는다(WMS CDC-F-017).
 *   <li>DPS·방식 없음 → DISCRETE: TRF × 방식 × 구역 × 층(사용자 "방식 없으면 지금처럼 TRF 하나씩").
 *   <li>BOX_PICK·FC·DISTRIBUTION → REGION_BATCH: 지역 × 방식 × 구역 × 층. 지역이 없는 TRF 는 DISCRETE.
 *   <li>LINE → SKU_BATCH: 방식 × 구역 × 층(Wave 안 모든 TRF).
 *   <li>같은 SKU·LOT·위치는 한 줄 — 줄 밑에 TRF 별 몫(trfId 순). 작업 기한 = 몫 TRF 중 가장 이른 기한.
 *   <li>maxLines 가 있으면 묶음 작업을 그 줄 수로 자른다(DISCRETE 는 자르지 않음).
 * </ul>
 */
public final class BatchPlan {

  public static final String DISCRETE = "DISCRETE";
  public static final String REGION_BATCH = "REGION_BATCH";
  public static final String SKU_BATCH = "SKU_BATCH";

  private BatchPlan() {}

  /** TRF 할당이 잡은 양 하나와 그 TRF·위치의 사실. */
  public record Item(
      String trfId,
      String mfcId,
      Instant dueAt,
      @Nullable String regionCode,
      String sku,
      String lot,
      String locationCode,
      int qty,
      @Nullable String pickMethod) {}

  public record Share(String trfId, String mfcId, Instant dueAt, int qty) {}

  public record Line(String sku, String lot, String locationCode, int qty, List<Share> shares) {}

  public record Task(
      String pickMode,
      @Nullable String pickMethod,
      @Nullable String regionCode,
      @Nullable String trfId,
      @Nullable String zone,
      @Nullable String floor,
      Instant dueAt,
      List<Line> lines) {}

  public static String modeOf(@Nullable String pickMethod, @Nullable String regionCode) {
    if (pickMethod == null || "DPS".equals(pickMethod)) {
      return DISCRETE;
    }
    if ("LINE".equals(pickMethod)) {
      return SKU_BATCH;
    }
    return regionCode == null ? DISCRETE : REGION_BATCH;
  }

  public static List<Task> plan(List<Item> items, @Nullable Integer maxLines) {
    Map<String, Head> heads = new LinkedHashMap<>();
    Map<String, Map<String, Map<String, Share>>> lines = new LinkedHashMap<>();
    for (Item it : items) {
      if (it.qty() <= 0) {
        continue;
      }
      String zone = null;
      String floor = null;
      if (PickPlan.CODE.matcher(it.locationCode()).matches()) {
        String[] p = it.locationCode().split("-");
        zone = p[1];
        floor = p[2];
      }
      String mode = modeOf(it.pickMethod(), it.regionCode());
      String trf = DISCRETE.equals(mode) ? it.trfId() : null;
      String region = REGION_BATCH.equals(mode) ? it.regionCode() : null;
      String gk =
          mode + "|" + trf + "|" + region + "|" + it.pickMethod() + "|" + zone + "|" + floor;
      heads.putIfAbsent(gk, new Head(mode, it.pickMethod(), region, trf, zone, floor));
      String lk = it.sku() + "|" + it.lot() + "|" + it.locationCode();
      lines
          .computeIfAbsent(gk, k -> new LinkedHashMap<>())
          .computeIfAbsent(lk, k -> new TreeMap<>())
          .merge(
              it.trfId(),
              new Share(it.trfId(), it.mfcId(), it.dueAt(), it.qty()),
              (a, b) -> new Share(a.trfId(), a.mfcId(), a.dueAt(), a.qty() + b.qty()));
    }
    List<Task> out = new ArrayList<>();
    for (Map.Entry<String, Head> e : heads.entrySet()) {
      Head h = e.getValue();
      List<Line> all = new ArrayList<>();
      for (Map.Entry<String, Map<String, Share>> l : lines.get(e.getKey()).entrySet()) {
        String[] k = l.getKey().split("\\|", 3);
        List<Share> shares = List.copyOf(l.getValue().values());
        all.add(new Line(k[0], k[1], k[2], shares.stream().mapToInt(Share::qty).sum(), shares));
      }
      int size = DISCRETE.equals(h.mode()) || maxLines == null ? all.size() : maxLines;
      for (int i = 0; i < all.size(); i += size) {
        List<Line> part = List.copyOf(all.subList(i, Math.min(all.size(), i + size)));
        Instant due =
            part.stream()
                .flatMap(x -> x.shares().stream())
                .map(Share::dueAt)
                .min(Comparator.naturalOrder())
                .orElseThrow();
        out.add(
            new Task(h.mode(), h.method(), h.region(), h.trf(), h.zone(), h.floor(), due, part));
      }
    }
    return out;
  }

  private record Head(
      String mode,
      @Nullable String method,
      @Nullable String region,
      @Nullable String trf,
      @Nullable String zone,
      @Nullable String floor) {}

  /** 몫 하나의 지금 상태. */
  public record ShareState(String trfId, Instant dueAt, int planned, int picked, int shortQty) {
    int remaining() {
      return planned - picked - shortQty;
    }
  }

  /**
   * 줄 실적(이번에 더한 실피킹·부족)을 몫에 나눈다(사용자 결정 §7-2): 실피킹은 기한 이른 TRF 몫부터 채우고, 부족은 기한 늦은 몫부터 뺀다. 같은 기한이면
   * trfId 순(부족은 trfId 뒤쪽부터). 호출자가 줄의 남은 계획 안인지 먼저 검사한다.
   *
   * @return trfId → {실피킹, 부족} 더할 값(0 인 몫은 빠짐)
   */
  public static Map<String, int[]> attribute(List<ShareState> shares, int picked, int shortQty) {
    Comparator<ShareState> early =
        Comparator.comparing(ShareState::dueAt).thenComparing(ShareState::trfId);
    List<ShareState> order = new ArrayList<>(shares);
    order.sort(early);
    Map<String, int[]> out = new TreeMap<>();
    Map<String, Integer> left = new LinkedHashMap<>();
    order.forEach(s -> left.put(s.trfId(), s.remaining()));
    int p = picked;
    for (ShareState s : order) {
      int take = Math.min(p, left.get(s.trfId()));
      if (take > 0) {
        out.computeIfAbsent(s.trfId(), k -> new int[2])[0] += take;
        left.merge(s.trfId(), -take, Integer::sum);
        p -= take;
      }
    }
    int q = shortQty;
    for (int i = order.size() - 1; i >= 0; i--) {
      ShareState s = order.get(i);
      int take = Math.min(q, left.get(s.trfId()));
      if (take > 0) {
        out.computeIfAbsent(s.trfId(), k -> new int[2])[1] += take;
        left.merge(s.trfId(), -take, Integer::sum);
        q -= take;
      }
    }
    if (p > 0 || q > 0) {
      throw new IllegalArgumentException("몫의 남은 계획보다 많은 실적입니다.");
    }
    return out;
  }
}
