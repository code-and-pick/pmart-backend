package com.pmart.delivery.dispatch.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 한 MFC 의 라이더 ↔ 배송 짝짓기(AI 추천 배차 — Python AI 서버 전까지 OR-Tools, 사용자 결정 2026-11-13).
 *
 * <p>라이더 한 명은 한 번에 제안 하나, 배송 하나에는 라이더 한 명에게 제안. 비용(초) = 도착 시각과 출하 대기 시각의 차이 — 늦게 오면 {@code
 * lateWeight} 배, 일찍 와서 기다리면 1배(원문 §3.1 "라이더가 포장이 끝날 즈음 도착"). 가능한 칸(반경 안·길 있음)만 짝지을 수 있다. 목적: 짝 수 최대
 * → 비용 최소.
 *
 * @param pickupSeconds [라이더][배송] 라이더 → MFC 도로 소요(초), 짝지을 수 없으면 -1
 * @param readyInSeconds [배송] 지금부터 출하 대기까지(초, 이미 대기면 0)
 */
public record AssignmentProblem(int[][] pickupSeconds, int[] readyInSeconds, int lateWeight) {

  /** result[배송] = 라이더 번호, 없으면 -1. */
  public record Solution(int[] riderForDelivery, String solver) {}

  public int riders() {
    return pickupSeconds.length;
  }

  public int deliveries() {
    return readyInSeconds.length;
  }

  public boolean allowed(int r, int d) {
    return pickupSeconds[r][d] >= 0;
  }

  public long cost(int r, int d) {
    long diff = (long) pickupSeconds[r][d] - readyInSeconds[d];
    return diff > 0 ? diff * lateWeight : -diff;
  }

  /** 규칙 기반 해 — 칸을 비용 순으로 보고 둘 다 비었으면 짝짓는다. 솔버를 못 쓸 때와 첫 해(힌트)로 쓴다. */
  public Solution greedy() {
    record Cell(int r, int d, long c) {}
    List<Cell> cells = new ArrayList<>();
    for (int r = 0; r < riders(); r++) {
      for (int d = 0; d < deliveries(); d++) {
        if (allowed(r, d)) {
          cells.add(new Cell(r, d, cost(r, d)));
        }
      }
    }
    cells.sort(
        Comparator.comparingLong(Cell::c).thenComparingInt(Cell::d).thenComparingInt(Cell::r));
    int[] out = new int[deliveries()];
    Arrays.fill(out, -1);
    boolean[] used = new boolean[riders()];
    for (Cell c : cells) {
      if (out[c.d()] < 0 && !used[c.r()]) {
        out[c.d()] = c.r();
        used[c.r()] = true;
      }
    }
    return new Solution(out, "GREEDY");
  }

  public long totalCost(int[] riderForDelivery) {
    long s = 0;
    for (int d = 0; d < riderForDelivery.length; d++) {
      if (riderForDelivery[d] >= 0) {
        s += cost(riderForDelivery[d], d);
      }
    }
    return s;
  }

  public static int matched(int[] riderForDelivery) {
    return (int) Arrays.stream(riderForDelivery).filter(x -> x >= 0).count();
  }
}
