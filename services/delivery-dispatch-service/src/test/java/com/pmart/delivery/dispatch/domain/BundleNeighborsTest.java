package com.pmart.delivery.dispatch.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** 묶음 후보를 가까운 고객끼리만(bundle-neighbors) — 큰 MFC 에서 후보 수를 줄인다. */
class BundleNeighborsTest {

  // 0·1·2 는 서로 가깝고(60초), 3 은 모두와 멀다(600초) — 한도 20분 안이라 제한이 없으면 3 도 묶음 후보
  static final int[] DIRECT = {400, 420, 430, 450};
  static final int[][] BETWEEN = {
    {0, 60, 60, 600},
    {60, 0, 60, 600},
    {60, 60, 0, 600},
    {600, 600, 600, 0}
  };
  static final boolean[] BUNDLABLE = {true, true, true, true};

  @Test
  void zeroMeansEveryCombination() {
    List<BundleProblem.Bundle> all =
        BundleProblem.candidates(DIRECT, BETWEEN, BUNDLABLE, 3, 1200, 0);
    assertThat(all).hasSize(4 + 6 + 4); // 1건 4, 2건 6, 3건 4
    // 묶음은 배열을 담은 record 라 내용으로 비교한다
    assertThat(all.stream().map(b -> Arrays.toString(b.deliveries())).toList())
        .isEqualTo(
            BundleProblem.candidates(DIRECT, BETWEEN, BUNDLABLE, 3, 1200).stream()
                .map(b -> Arrays.toString(b.deliveries()))
                .toList());
  }

  @Test
  void neighborsKeepOnlyNearbyCustomersTogether() {
    List<BundleProblem.Bundle> near =
        BundleProblem.candidates(DIRECT, BETWEEN, BUNDLABLE, 3, 1200, 2);
    Set<Set<Integer>> groups =
        near.stream()
            .map(b -> Arrays.stream(b.deliveries()).boxed().collect(Collectors.toSet()))
            .collect(Collectors.toSet());
    // 1건은 모두 남는다
    assertThat(groups).contains(Set.of(0), Set.of(1), Set.of(2), Set.of(3));
    // 가까운 셋은 2건·3건 모두
    assertThat(groups).contains(Set.of(0, 1), Set.of(0, 2), Set.of(1, 2), Set.of(0, 1, 2));
    // 3 의 가까운 2명은 0·1(같은 600초면 번호순) — 3 이 포함된 3건은 {0,1,3} 만, 2 와는 짝이 안 된다
    assertThat(groups).contains(Set.of(0, 3), Set.of(1, 3), Set.of(0, 1, 3));
    assertThat(groups).doesNotContain(Set.of(2, 3), Set.of(0, 2, 3), Set.of(1, 2, 3));
  }
}
