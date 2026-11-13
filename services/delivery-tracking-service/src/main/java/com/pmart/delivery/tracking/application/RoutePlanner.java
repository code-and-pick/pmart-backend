package com.pmart.delivery.tracking.application;

import java.util.List;

/** 도로 거리·시간 조회 포트(카카오모빌리티 어댑터가 구현). 실패는 예외(직선거리로 대신하지 않는다 — ADR-0058). */
public interface RoutePlanner {

  record Point(double lat, double lng) {}

  /** 한 칸. ok=false 면 길 없음(NO_ROUTE). */
  record Leg(boolean ok, int distanceM, int durationSeconds) {
    public static final Leg NO_ROUTE = new Leg(false, 0, 0);
  }

  /** 한 출발지 → 여러 도착지(도착지 순서). */
  List<Leg> fromOne(Point origin, List<Point> destinations);

  /** 여러 출발지 → 한 도착지(출발지 순서). */
  List<Leg> toOne(List<Point> origins, Point destination);
}
