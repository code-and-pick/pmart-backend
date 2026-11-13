package com.pmart.delivery.dispatch.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** AI 배정안 검증(ADR-0058 결정 13). */
class BundleCheckTest {

  static final int[] DIRECT = {400, 450, 500};
  static final int[][] BETWEEN = {{0, 120, -1}, {120, 0, 100}, {-1, 100, 0}};
  static final boolean[] BUNDLABLE = {true, true, false};
  static final int[] CAPACITY = {3, 1};
  static final boolean[][] ALLOWED = {{true, true, true}, {true, true, false}};

  BundleProblem.Checked check(List<int[]> plan) {
    return BundleProblem.check(plan, DIRECT, BETWEEN, BUNDLABLE, 3, 1200, CAPACITY, ALLOWED);
  }

  @Test
  void validBundleKeepsAiOrder() {
    var c = check(List.of(new int[] {0, 1, 0}));
    assertThat(c.dropped()).isZero();
    assertThat(c.bundles().get(0).deliveries()).containsExactly(1, 0);
    assertThat(c.bundles().get(0).arrivalSeconds()).containsExactly(450, 570);
    assertThat(c.riderForBundle()).containsExactly(0);
  }

  @Test
  void rejectsRuleBreakingAssignments() {
    assertThat(check(List.<int[]>of(new int[] {0, 0, 2})).dropped()).isEqualTo(1); // 한집배달을 묶음에
    assertThat(check(List.<int[]>of(new int[] {1, 0, 1})).dropped()).isEqualTo(1); // 용량 1 에 2건
    assertThat(check(List.<int[]>of(new int[] {1, 2})).dropped()).isEqualTo(1); // 반경 밖
    assertThat(check(List.<int[]>of(new int[] {-1, 0})).dropped()).isEqualTo(1); // 모르는 라이더
    assertThat(check(List.<int[]>of(new int[] {0, 0}, new int[] {1, 0})).dropped())
        .isEqualTo(1); // 배송 중복
    assertThat(check(List.<int[]>of(new int[] {0, 0}, new int[] {0, 1})).dropped())
        .isEqualTo(1); // 라이더 중복
    var far =
        BundleProblem.check(
            List.<int[]>of(new int[] {0, 0, 1}),
            DIRECT,
            new int[][] {{0, 2000, -1}, {2000, 0, 100}, {-1, 100, 0}},
            BUNDLABLE,
            3,
            1200,
            CAPACITY,
            ALLOWED);
    assertThat(far.dropped()).isEqualTo(1); // 지연 한도 초과
  }
}
