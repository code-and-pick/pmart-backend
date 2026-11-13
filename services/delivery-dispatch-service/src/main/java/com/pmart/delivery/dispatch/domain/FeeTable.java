package com.pmart.delivery.dispatch.domain;

/**
 * 제안에 보여 주는 예상 배달료 — 업무 원문 §7.1 수단·거리별 표(부산 내 동일). 거리 = MFC → 고객 도로 거리(카카오모빌리티).
 *
 * <p>원문 표의 열을 구간으로 읽는다(2026-11-13 해석 — 원문 열 이름 그대로, 바꾸려면 원문을 고친다):
 *
 * <ul>
 *   <li>도보: 0~675m 2,600 · 675~810m 2,700 · 810~945m 2,800 · 945m~ 는 2,800 + 시작한 100m 마다 100원.
 *   <li>그 밖(일반·전기자전거·전동킥보드·오토바이·자동차 — "위와 동일"): 0~675m 2,600 · 675~1,350m 2,900 · 1,350~2,000m
 *       3,200 · 2km(2~3km) 3,900 · 3km(3~4km) 4,900 · 4km~ 5,900.
 * </ul>
 *
 * 픽업지 할증(엘리베이터 없는 층 등)·회수·재배송·취소 수수료는 여기서 다루지 않는다(원문 §7.1 — 수행료 정본은 execution).
 */
public final class FeeTable {

  private FeeTable() {}

  public static int estimate(String vehicleType, int distanceM) {
    int d = Math.max(0, distanceM);
    if ("WALK".equals(vehicleType)) {
      if (d < 675) {
        return 2600;
      }
      if (d < 810) {
        return 2700;
      }
      if (d < 945) {
        return 2800;
      }
      int over = d - 945;
      int steps = (over + 99) / 100;
      return 2800 + Math.max(1, steps) * 100;
    }
    if (d < 675) {
      return 2600;
    }
    if (d < 1350) {
      return 2900;
    }
    if (d < 2000) {
      return 3200;
    }
    if (d < 3000) {
      return 3900;
    }
    if (d < 4000) {
      return 4900;
    }
    return 5900;
  }
}
