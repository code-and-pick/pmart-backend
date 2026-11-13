package com.pmart.delivery.dispatch.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.delivery.dispatch.domain.AssignmentProblem;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class OrToolsAssignmentSolverTest {

  final OrToolsAssignmentSolver solver = new OrToolsAssignmentSolver();

  @Test
  void solverBeatsGreedyWhenGreedyBlocksASecondMatch() {
    // 라이더0 은 두 배송 모두 가깝고, 라이더1 은 배송0 만 갈 수 있다 — 규칙 기반은 라이더0→배송0 을 먼저 집어 배송1 이 남는다
    AssignmentProblem p =
        new AssignmentProblem(new int[][] {{100, 120}, {300, -1}}, new int[] {0, 0}, 2);
    AssignmentProblem.Solution s = solver.solve(p, Duration.ofSeconds(2));
    assertThat(AssignmentProblem.matched(s.riderForDelivery())).isEqualTo(2);
    assertThat(s.riderForDelivery()).containsExactly(1, 0);
    assertThat(s.solver()).startsWith("CP_SAT");
  }

  @Test
  void nothingAllowedMeansNoMatch() {
    AssignmentProblem p = new AssignmentProblem(new int[][] {{-1}}, new int[] {0}, 2);
    assertThat(solver.solve(p, Duration.ofSeconds(1)).riderForDelivery()).containsExactly(-1);
  }
}
