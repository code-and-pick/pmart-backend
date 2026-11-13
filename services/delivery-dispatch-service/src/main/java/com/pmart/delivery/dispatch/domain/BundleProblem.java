package com.pmart.delivery.dispatch.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 한 MFC 의 라이더 ↔ 배송 묶음 짝짓기(AI 추천 배차 — Python AI 서버 전까지 OR-Tools, 사용자 결정 2026-11-13).
 *
 * <p>묶음 = 고객 방문 순서가 정해진 배송 1~3건(원문 §5.3). 알뜰배달(BUNDLED)끼리만 2~3건으로 묶고, 한집·예약배달은 1건짜리 묶음뿐이다. 라이더 한 명은
 * 한 번에 묶음 하나, 배송 하나는 한 묶음에만.
 *
 * <p>비용(초, 라이더 r 이 묶음 b) = 라이더 → MFC 이동 + 출하 대기보다 늦게 도착한 초 × lateWeight + 일찍 와서 기다린 초 + Σ(MFC 출발 뒤
 * 각 고객 도착까지). 묶으면 라이더 → MFC 이동이 한 번 줄고 뒤 고객 도착이 늦어진다 — 솔버가 둘을 비교한다. 목적: 배정 배송 수 최대 → 비용 최소.
 *
 * @param pickupSeconds [라이더] 라이더 → MFC 도로 소요(초)
 * @param allowed [라이더][배송] 그 배송 시도의 반경 단계 안·이번 회차 미제안
 * @param capacity [라이더] 이번에 더 맡을 수 있는 배송 수(0 이면 제외)
 * @param readyInSeconds [배송] 지금부터 출하 대기까지(초)
 * @param bundles 후보 묶음({@link #candidates})
 * @param promiseInSeconds [배송] 지금부터 배송 약속 시각까지(초, 모르면 {@link Integer#MAX_VALUE}) — 2건 이상 묶음은 모든 고객이
 *     약속 안에 받을 때만(사용자 2026-11-13 "주문한지 50분 이내 전달"). 1건은 늦어도 보낸다
 * @param penaltySeconds [라이더] 비용에 더하는 초 — 최근 반복 배차취소한 라이더는 뒤로(사용자 2026-11-13 "기록을 보고 자동으로 배차 알림
 *     감소")
 */
public record BundleProblem(
    int[] pickupSeconds,
    boolean[][] allowed,
    int[] capacity,
    int[] readyInSeconds,
    List<Bundle> bundles,
    int lateWeight,
    int[] promiseInSeconds,
    int[] penaltySeconds) {

  /** 약속·감점 없이(시험·단순 사용). */
  public BundleProblem(
      int[] pickupSeconds,
      boolean[][] allowed,
      int[] capacity,
      int[] readyInSeconds,
      List<Bundle> bundles,
      int lateWeight) {
    this(
        pickupSeconds,
        allowed,
        capacity,
        readyInSeconds,
        bundles,
        lateWeight,
        filled(readyInSeconds.length, Integer.MAX_VALUE),
        new int[pickupSeconds.length]);
  }

  static int[] filled(int n, int v) {
    int[] a = new int[n];
    Arrays.fill(a, v);
    return a;
  }

  /** 2건 이상 묶음이면 이 라이더로도 모든 고객이 약속 안에 받는가(출발 = 라이더 MFC 도착과 출하 대기 중 늦은 쪽). */
  static boolean withinPromise(Bundle b, int pickup, int[] readyIn, int[] promiseIn) {
    if (b.size() < 2) {
      return true;
    }
    int depart = pickup;
    for (int d : b.deliveries()) {
      depart = Math.max(depart, readyIn[d]);
    }
    for (int i = 0; i < b.size(); i++) {
      if ((long) depart + b.arrivalSeconds()[i] > promiseIn[b.deliveries()[i]]) {
        return false;
      }
    }
    return true;
  }

  /**
   * @param deliveries 방문 순서대로의 배송 번호
   * @param arrivalSeconds 배송마다 MFC 출발 뒤 도착까지(초, deliveries 순서)
   */
  public record Bundle(int[] deliveries, int[] arrivalSeconds) {
    public int size() {
      return deliveries.length;
    }

    public int arrivalSum() {
      return Arrays.stream(arrivalSeconds).sum();
    }
  }

  /** riderForBundle[묶음] = 라이더 번호, 없으면 -1. */
  public record Solution(int[] riderForBundle, String solver) {}

  public int riders() {
    return pickupSeconds.length;
  }

  public boolean allowed(int r, int b) {
    Bundle x = bundles.get(b);
    if (capacity[r] < x.size()) {
      return false;
    }
    for (int d : x.deliveries()) {
      if (!allowed[r][d]) {
        return false;
      }
    }
    return withinPromise(x, pickupSeconds[r], readyInSeconds, promiseInSeconds);
  }

  public long cost(int r, int b) {
    Bundle x = bundles.get(b);
    int ready = 0;
    for (int d : x.deliveries()) {
      ready = Math.max(ready, readyInSeconds[d]);
    }
    long diff = (long) pickupSeconds[r] - ready;
    long wait = diff > 0 ? diff * lateWeight : -diff;
    return pickupSeconds[r] + wait + x.arrivalSum() + penaltySeconds[r];
  }

  /** 규칙 기반 해 — (짝 수 큰 순, 비용 작은 순)으로 겹치지 않게 고른다. 솔버를 못 쓸 때와 힌트. */
  public Solution greedy() {
    record Cell(int r, int b, int size, long c) {}
    List<Cell> cells = new ArrayList<>();
    for (int r = 0; r < riders(); r++) {
      for (int b = 0; b < bundles.size(); b++) {
        if (allowed(r, b)) {
          cells.add(new Cell(r, b, bundles.get(b).size(), cost(r, b)));
        }
      }
    }
    // 배송당 평균 비용이 작은 것 먼저 — 묶음이 손해가 아니면 묶음이 앞선다
    cells.sort(
        Comparator.comparingDouble((Cell c) -> (double) c.c() / c.size())
            .thenComparingInt(c -> -c.size())
            .thenComparingInt(Cell::b)
            .thenComparingInt(Cell::r));
    int[] out = new int[bundles.size()];
    Arrays.fill(out, -1);
    boolean[] riderUsed = new boolean[riders()];
    boolean[] deliveryUsed = new boolean[readyInSeconds.length];
    for (Cell c : cells) {
      if (riderUsed[c.r()]) {
        continue;
      }
      boolean free = true;
      for (int d : bundles.get(c.b()).deliveries()) {
        free &= !deliveryUsed[d];
      }
      if (!free) {
        continue;
      }
      out[c.b()] = c.r();
      riderUsed[c.r()] = true;
      for (int d : bundles.get(c.b()).deliveries()) {
        deliveryUsed[d] = true;
      }
    }
    return new Solution(out, "GREEDY");
  }

  /** 배정된 배송 수. */
  public int covered(int[] riderForBundle) {
    int n = 0;
    for (int b = 0; b < riderForBundle.length; b++) {
      if (riderForBundle[b] >= 0) {
        n += bundles.get(b).size();
      }
    }
    return n;
  }

  // ---------- AI 배정안 검증(ADR-0058 결정 5·13) ----------

  /** 지킨 묶음과 그 라이더, 버린 수. */
  public record Checked(List<Bundle> bundles, int[] riderForBundle, int dropped) {}

  /**
   * AI 서버 배정안을 그대로 믿지 않고 검증한다 — 라이더·배송 중복 없음, 묶음 1~maxSize, 2건 이상은 알뜰배달만, 라이더 남은 용량·갈 수 있는 배송, 고객
   * 사이 길 있음, 어느 배송이든 바로 갈 때보다 maxExtraSeconds 넘게 늦지 않음. 어긴 배정은 버린다(그 배송은 다음 판단에서 다시).
   *
   * @param plan 배정마다 [라이더 번호, 배송 번호…(방문 순서)]. 번호가 -1 이면(모르는 ID) 버린다
   */
  public static Checked check(
      List<int[]> plan,
      int[] directSeconds,
      int[][] betweenSeconds,
      boolean[] bundlable,
      int maxSize,
      int maxExtraSeconds,
      int[] capacity,
      boolean[][] allowed) {
    return check(
        plan,
        directSeconds,
        betweenSeconds,
        bundlable,
        maxSize,
        maxExtraSeconds,
        capacity,
        allowed,
        new int[capacity.length],
        new int[directSeconds.length],
        filled(directSeconds.length, Integer.MAX_VALUE));
  }

  /** 약속 시각까지 보는 검증(라이더 MFC 도착·출하 대기·약속). */
  public static Checked check(
      List<int[]> plan,
      int[] directSeconds,
      int[][] betweenSeconds,
      boolean[] bundlable,
      int maxSize,
      int maxExtraSeconds,
      int[] capacity,
      boolean[][] allowed,
      int[] pickupSeconds,
      int[] readyInSeconds,
      int[] promiseInSeconds) {
    List<Bundle> out = new ArrayList<>();
    List<Integer> riders = new ArrayList<>();
    boolean[] riderUsed = new boolean[capacity.length];
    boolean[] deliveryUsed = new boolean[directSeconds.length];
    int dropped = 0;
    for (int[] a : plan) {
      int r = a.length > 0 ? a[0] : -1;
      int[] ds = Arrays.copyOfRange(a, Math.min(1, a.length), a.length);
      boolean ok =
          r >= 0 && r < capacity.length && !riderUsed[r] && ds.length >= 1 && ds.length <= maxSize;
      ok &= ok && capacity[r] >= ds.length;
      int[] arrival = new int[ds.length];
      for (int i = 0; i < ds.length && ok; i++) {
        int d = ds[i];
        ok = d >= 0 && d < directSeconds.length && !deliveryUsed[d] && allowed[r][d];
        ok &= ok && (ds.length == 1 || bundlable[d]);
        for (int j = 0; j < i && ok; j++) {
          ok = ds[j] != d;
        }
        if (ok) {
          if (i == 0) {
            arrival[0] = directSeconds[d];
          } else {
            int leg = betweenSeconds[ds[i - 1]][d];
            ok = leg >= 0;
            arrival[i] = arrival[i - 1] + Math.max(0, leg);
          }
          ok &= ok && arrival[i] - directSeconds[d] <= maxExtraSeconds;
        }
      }
      ok =
          ok
              && withinPromise(
                  new Bundle(ds, arrival), pickupSeconds[r], readyInSeconds, promiseInSeconds);
      if (!ok) {
        dropped++;
        continue;
      }
      riderUsed[r] = true;
      for (int d : ds) {
        deliveryUsed[d] = true;
      }
      out.add(new Bundle(ds, arrival));
      riders.add(r);
    }
    return new Checked(out, riders.stream().mapToInt(Integer::intValue).toArray(), dropped);
  }

  // ---------- 후보 묶음 ----------

  /**
   * 후보 묶음: 모든 배송의 1건 묶음 + 알뜰배달끼리 2~3건(방문 순서는 순열 중 Σ도착 최소). 묶었을 때 어느 배송이든 바로 갈 때보다 {@code
   * maxExtraSeconds} 넘게 늦어지면 버린다. 길이 없는 구간(-1)이 있으면 버린다.
   *
   * @param directSeconds [배송] MFC → 고객 도로 소요
   * @param betweenSeconds [배송][배송] 고객 → 고객 도로 소요(-1 = 길 없음, 알뜰배달끼리만 채운다)
   * @param bundlable [배송] 알뜰배달인가
   */
  public static List<Bundle> candidates(
      int[] directSeconds,
      int[][] betweenSeconds,
      boolean[] bundlable,
      int maxSize,
      int maxExtraSeconds) {
    return candidates(directSeconds, betweenSeconds, bundlable, maxSize, maxExtraSeconds, 0);
  }

  /**
   * 후보 묶음 — {@code neighbors} 가 0 보다 크면 가까운 고객끼리만 묶는다(큰 MFC 에서 후보가 수천 개가 되어 CP-SAT 가 시간 안에 못 푸는 것을
   * 막는다, 2026-11-13 측정): 2건은 한쪽이 다른 쪽의 가까운 {@code neighbors} 명 안, 3건은 한 고객 기준으로 나머지 둘이 그 안. 0 이면 모든
   * 조합.
   */
  public static List<Bundle> candidates(
      int[] directSeconds,
      int[][] betweenSeconds,
      boolean[] bundlable,
      int maxSize,
      int maxExtraSeconds,
      int neighbors) {
    int n = directSeconds.length;
    boolean[][] near = nearest(betweenSeconds, bundlable, neighbors);
    List<Bundle> out = new ArrayList<>();
    for (int d = 0; d < n; d++) {
      out.add(new Bundle(new int[] {d}, new int[] {directSeconds[d]}));
    }
    List<Integer> bundled = new ArrayList<>();
    for (int d = 0; d < n; d++) {
      if (bundlable[d]) {
        bundled.add(d);
      }
    }
    List<int[]> subsets = new ArrayList<>();
    for (int i = 0; i < bundled.size(); i++) {
      for (int j = i + 1; j < bundled.size(); j++) {
        int a = bundled.get(i);
        int b = bundled.get(j);
        if (maxSize >= 2 && (near == null || near[a][b] || near[b][a])) {
          subsets.add(new int[] {a, b});
        }
        for (int k = j + 1; k < bundled.size() && maxSize >= 3; k++) {
          int c = bundled.get(k);
          if (near == null
              || (near[a][b] && near[a][c])
              || (near[b][a] && near[b][c])
              || (near[c][a] && near[c][b])) {
            subsets.add(new int[] {a, b, c});
          }
        }
      }
    }
    for (int[] s : subsets) {
      Bundle best = null;
      for (int[] order : permutations(s)) {
        int[] arrival = new int[order.length];
        int t = directSeconds[order[0]];
        arrival[0] = t;
        boolean ok = true;
        for (int i = 1; i < order.length && ok; i++) {
          int leg = betweenSeconds[order[i - 1]][order[i]];
          ok = leg >= 0;
          t += Math.max(0, leg);
          arrival[i] = t;
        }
        for (int i = 0; i < order.length && ok; i++) {
          ok = arrival[i] - directSeconds[order[i]] <= maxExtraSeconds;
        }
        if (ok) {
          Bundle b = new Bundle(order, arrival);
          if (best == null || b.arrivalSum() < best.arrivalSum()) {
            best = b;
          }
        }
      }
      if (best != null) {
        out.add(best);
      }
    }
    return out;
  }

  /** near[a][b] = b 가 a 의 가까운(고객 → 고객 도로 시간) 알뜰배달 {@code k} 명 안. k ≤ 0 이면 null(제한 없음). */
  static boolean[][] nearest(int[][] betweenSeconds, boolean[] bundlable, int k) {
    if (k <= 0) {
      return null;
    }
    int n = bundlable.length;
    boolean[][] near = new boolean[n][n];
    for (int a = 0; a < n; a++) {
      if (!bundlable[a]) {
        continue;
      }
      final int from = a;
      List<Integer> others = new ArrayList<>();
      for (int b = 0; b < n; b++) {
        if (b != a && bundlable[b] && betweenSeconds[a][b] >= 0) {
          others.add(b);
        }
      }
      others.sort(
          Comparator.comparingInt((Integer b) -> betweenSeconds[from][b]).thenComparingInt(b -> b));
      for (int i = 0; i < Math.min(k, others.size()); i++) {
        near[a][others.get(i)] = true;
      }
    }
    return near;
  }

  static List<int[]> permutations(int[] s) {
    List<int[]> out = new ArrayList<>();
    permute(s.clone(), 0, out);
    return out;
  }

  private static void permute(int[] a, int k, List<int[]> out) {
    if (k == a.length) {
      out.add(a.clone());
      return;
    }
    for (int i = k; i < a.length; i++) {
      int t = a[k];
      a[k] = a[i];
      a[i] = t;
      permute(a, k + 1, out);
      a[i] = a[k];
      a[k] = t;
    }
  }
}
