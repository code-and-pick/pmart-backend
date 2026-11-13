package com.pmart.delivery.dispatch.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.delivery.dispatch.domain.BundleProblem;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrToolsAssignmentSolverTest {

  final OrToolsAssignmentSolver solver = new OrToolsAssignmentSolver();

  @Test
  void bundlesNearbyBundledDeliveriesForOneRider() {
    // 알뜰배달 두 건(가까움), 라이더 한 명 — 묶음 하나로 둘 다 배정
    List<BundleProblem.Bundle> bundles =
        BundleProblem.candidates(
            new int[] {400, 450},
            new int[][] {{0, 120}, {120, 0}},
            new boolean[] {true, true},
            3,
            1200);
    BundleProblem p =
        new BundleProblem(
            new int[] {300},
            new boolean[][] {{true, true}},
            new int[] {3},
            new int[] {0, 0},
            bundles,
            2);
    BundleProblem.Solution s = solver.solve(p, Duration.ofSeconds(2));
    assertThat(p.covered(s.riderForBundle())).isEqualTo(2);
    int chosen = indexOfAssigned(s);
    assertThat(bundles.get(chosen).size()).isEqualTo(2);
    assertThat(s.solver()).startsWith("CP_SAT");
  }

  @Test
  void singleDeliveriesAreNeverBundledAndBeatGreedyConflict() {
    // 한집배달 두 건 — 묶음 후보 없음. 라이더0 은 둘 다, 라이더1 은 배송0 만 → 최적은 라이더1→배송0, 라이더0→배송1
    List<BundleProblem.Bundle> bundles =
        BundleProblem.candidates(
            new int[] {400, 400},
            new int[][] {{0, 60}, {60, 0}},
            new boolean[] {false, false},
            3,
            1200);
    assertThat(bundles).hasSize(2);
    BundleProblem p =
        new BundleProblem(
            new int[] {100, 300},
            new boolean[][] {{true, true}, {true, false}},
            new int[] {3, 3},
            new int[] {0, 0},
            bundles,
            2);
    BundleProblem.Solution s = solver.solve(p, Duration.ofSeconds(2));
    assertThat(p.covered(s.riderForBundle())).isEqualTo(2);
    assertThat(s.riderForBundle()).containsExactly(1, 0);
  }

  @Test
  void bundleOverDetourLimitOrCapacityIsNotUsed() {
    // 고객 사이 30분 — 20분 한도를 넘어 묶음 후보에서 빠진다
    List<BundleProblem.Bundle> far =
        BundleProblem.candidates(
            new int[] {400, 400},
            new int[][] {{0, 1800}, {1800, 0}},
            new boolean[] {true, true},
            3,
            1200);
    assertThat(far).hasSize(2);
    // 하나씩 배달하기(용량 1) 라이더는 묶음을 못 받는다
    List<BundleProblem.Bundle> near =
        BundleProblem.candidates(
            new int[] {400, 450},
            new int[][] {{0, 120}, {120, 0}},
            new boolean[] {true, true},
            3,
            1200);
    BundleProblem p =
        new BundleProblem(
            new int[] {300},
            new boolean[][] {{true, true}},
            new int[] {1},
            new int[] {0, 0},
            near,
            2);
    BundleProblem.Solution s = solver.solve(p, Duration.ofSeconds(1));
    assertThat(p.covered(s.riderForBundle())).isEqualTo(1);
  }

  private static int indexOfAssigned(BundleProblem.Solution s) {
    for (int b = 0; b < s.riderForBundle().length; b++) {
      if (s.riderForBundle()[b] >= 0) {
        return b;
      }
    }
    return -1;
  }
}
