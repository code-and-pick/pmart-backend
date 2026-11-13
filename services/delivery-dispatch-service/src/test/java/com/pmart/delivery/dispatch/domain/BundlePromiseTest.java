package com.pmart.delivery.dispatch.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 알뜰배달 묶음은 모든 고객이 배송 약속(주문 + 50분) 안에 받을 때만(사용자 2026-11-13), 반복 배차취소 감점. */
class BundlePromiseTest {

  static final List<BundleProblem.Bundle> NEAR =
      BundleProblem.candidates(
          new int[] {400, 450},
          new int[][] {{0, 120}, {120, 0}},
          new boolean[] {true, true},
          3,
          3000);

  BundleProblem problem(int[] promiseIn, int[] penalty) {
    return new BundleProblem(
        new int[] {300, 300},
        new boolean[][] {{true, true}, {true, true}},
        new int[] {3, 3},
        new int[] {0, 0},
        NEAR,
        2,
        promiseIn,
        penalty);
  }

  int bundleIndex() {
    for (int b = 0; b < NEAR.size(); b++) {
      if (NEAR.get(b).size() == 2) {
        return b;
      }
    }
    throw new AssertionError();
  }

  @Test
  void bundleOnlyWhenEveryCustomerMakesThePromise() {
    int b = bundleIndex();
    // 출발 300초 + 묶음 도착(400·520 또는 450·570) — 둘 다 약속 3000초 안
    assertThat(problem(new int[] {3000, 3000}, new int[2]).allowed(0, b)).isTrue();
    // 한 고객 약속이 650초 뒤 — 어느 순서로 묶어도(700·870초 도착) 늦는다
    assertThat(problem(new int[] {650, 3000}, new int[2]).allowed(0, b)).isFalse();
    // 1건은 약속을 넘겨도 보낸다
    assertThat(problem(new int[] {100, 100}, new int[2]).allowed(0, 0)).isTrue();
  }

  @Test
  void recentCancellationsPushRiderBack() {
    BundleProblem p = problem(new int[] {3000, 3000}, new int[] {540, 0});
    assertThat(p.cost(0, 0)).isEqualTo(p.cost(1, 0) + 540);
    BundleProblem.Solution s = p.greedy();
    int b = bundleIndex();
    assertThat(s.riderForBundle()[b]).isEqualTo(1); // 감점 없는 라이더가 먼저
  }
}
