package com.pmart.delivery.dispatch.infrastructure;

import com.pmart.delivery.dispatch.domain.BundleProblem;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 큰 MFC(대기 배송 20~40건)에서 운영 설정(CP-SAT 1 작업자·2초) 그대로 재기 — Python 평가(`tools/evaluate.py`)와 같은 분포의 합성
 * 문제. 오래 걸려(문제마다 최대 2초+) 평소 시험에서는 돌지 않는다: {@code PMART_BENCH=1 ./gradlew
 * :services:delivery-dispatch-service:test --tests '*LargeMfcBenchmarkTest'}. 결과는 표준 출력.
 */
@EnabledIfEnvironmentVariable(named = "PMART_BENCH", matches = "1")
class LargeMfcBenchmarkTest {

  static final double MFC_LAT = 37.4979;
  static final double MFC_LNG = 127.0276;

  final OrToolsAssignmentSolver solver = new OrToolsAssignmentSolver();

  @Test
  void largeMfc() {
    for (int k : new int[] {0, 4, 6, 8}) {
      run("작은 MFC 4~12 이웃 " + k, 4, 12, 60, 1, k);
      run("큰 MFC 20~40 이웃 " + k, 20, 40, 30, 1, k);
      run("큰 MFC 20~40 이웃 " + k, 20, 40, 30, 2, k);
    }
  }

  void run(String label, int minN, int maxN, int instances, long seed, int neighbors) {
    Random rnd = new Random(seed);
    int fallback = 0;
    long covered = 0;
    long greedyCovered = 0;
    long cost = 0;
    long greedyCost = 0;
    List<Long> millis = new ArrayList<>();
    List<Integer> candidateCounts = new ArrayList<>();
    for (int i = 0; i < instances; i++) {
      int n = minN + rnd.nextInt(maxN - minN + 1);
      int m =
          Math.max(2, n / 4)
              + rnd.nextInt(Math.max(1, Math.max(2, n / 2) - Math.max(2, n / 4) + 1));
      double[][] pts = new double[n][];
      int[] direct = new int[n];
      int[] ready = new int[n];
      int[] readyChoices = {0, 0, 120, 300, 600};
      for (int d = 0; d < n; d++) {
        pts[d] =
            new double[] {
              MFC_LAT + (rnd.nextDouble() * 0.05 - 0.025),
              MFC_LNG + (rnd.nextDouble() * 0.06 - 0.03)
            };
        direct[d] = secs(MFC_LAT, MFC_LNG, pts[d][0], pts[d][1]);
        ready[d] = readyChoices[rnd.nextInt(readyChoices.length)];
      }
      int[][] between = new int[n][n];
      for (int a = 0; a < n; a++) {
        for (int b = 0; b < n; b++) {
          between[a][b] = a == b ? 0 : secs(pts[a][0], pts[a][1], pts[b][0], pts[b][1]);
        }
      }
      boolean[] bundlable = new boolean[n];
      java.util.Arrays.fill(bundlable, true);
      int[] pickup = new int[m];
      int[] capacity = new int[m];
      boolean[][] allowed = new boolean[m][n];
      for (int r = 0; r < m; r++) {
        pickup[r] = 60 + rnd.nextInt(841);
        capacity[r] = 3;
        java.util.Arrays.fill(allowed[r], true);
      }
      long t0 = System.nanoTime();
      List<BundleProblem.Bundle> bundles =
          BundleProblem.candidates(direct, between, bundlable, 3, 1200, neighbors);
      BundleProblem p = new BundleProblem(pickup, allowed, capacity, ready, bundles, 2);
      BundleProblem.Solution s = solver.solve(p, Duration.ofSeconds(2));
      millis.add((System.nanoTime() - t0) / 1_000_000);
      candidateCounts.add(bundles.size());
      if (!s.solver().startsWith("CP_SAT")) {
        fallback++;
      }
      BundleProblem.Solution g = p.greedy();
      covered += p.covered(s.riderForBundle());
      greedyCovered += p.covered(g.riderForBundle());
      cost += total(p, s);
      greedyCost += total(p, g);
    }
    millis.sort(null);
    candidateCounts.sort(null);
    System.out.printf(
        "%s seed %d (%d문제): 규칙 기반으로 떨어짐 %d, 배정 %d (규칙 기반만 %d), 총비용 %d (규칙 기반만 %d), 시간 중앙 %dms 최대 %dms,"
            + " 후보 묶음 중앙 %d 최대 %d%n",
        label,
        seed,
        instances,
        fallback,
        covered,
        greedyCovered,
        cost,
        greedyCost,
        millis.get(millis.size() / 2),
        millis.getLast(),
        candidateCounts.get(candidateCounts.size() / 2),
        candidateCounts.getLast());
  }

  static long total(BundleProblem p, BundleProblem.Solution s) {
    long sum = 0;
    for (int b = 0; b < s.riderForBundle().length; b++) {
      if (s.riderForBundle()[b] >= 0) {
        sum += p.cost(s.riderForBundle()[b], b);
      }
    }
    return sum;
  }

  /** Python 평가와 같은 합성 도로 시간: 직선거리 × 1.3 ÷ 5m/s. */
  static int secs(double lat1, double lng1, double lat2, double lng2) {
    double dlat = Math.toRadians(lat2 - lat1);
    double dlng = Math.toRadians(lng2 - lng1);
    double h =
        Math.pow(Math.sin(dlat / 2), 2)
            + Math.cos(Math.toRadians(lat1))
                * Math.cos(Math.toRadians(lat2))
                * Math.pow(Math.sin(dlng / 2), 2);
    double m = 2 * 6_371_000 * Math.asin(Math.min(1, Math.sqrt(h)));
    return (int) (m * 1.3 / 5.0);
  }
}
