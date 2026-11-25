package com.pmart.delivery.serviceability.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * 좌표 → 담당 권역 판정(BO-07). 권역 경계는 GeoJSON Polygon/MultiPolygon(좌표 [경도, 위도], 첫 링이 바깥, 나머지는 구멍).
 *
 * <ul>
 *   <li>들어가는 권역이 없으면 OUT_OF_AREA, 하나면 SERVICEABLE.
 *   <li>둘 이상이면 우선순위가 가장 큰 권역이 하나일 때만 그 권역. 같거나 우선순위가 없으면 OVERLAP — 가장 가까운·재고 많은 센터를 임의로 고르지 않는다.
 *   <li>경계선 위의 점은 판정이 흔들릴 수 있다(레이 캐스팅). 경계는 승인 시험 주소로 확인한다.
 * </ul>
 */
public final class ZoneMatcher {

  private ZoneMatcher() {}

  /** 권역 하나: 바깥 링·구멍 링 목록을 가진 다각형들. 링은 [lng, lat] 배열. */
  public record Zone(
      String zoneId,
      String mfcId,
      @Nullable Integer priority,
      List<List<List<double[]>>> polygons) {}

  public record Match(String result, @Nullable Zone zone, List<Zone> overlapping) {}

  public static Match match(List<Zone> zones, double lat, double lng) {
    List<Zone> hits = new ArrayList<>();
    for (Zone z : zones) {
      if (contains(z, lng, lat)) {
        hits.add(z);
      }
    }
    if (hits.isEmpty()) {
      return new Match("OUT_OF_AREA", null, List.of());
    }
    if (hits.size() == 1) {
      return new Match("SERVICEABLE", hits.get(0), List.of());
    }
    Optional<Zone> top =
        hits.stream()
            .filter(z -> z.priority() != null)
            .max(Comparator.comparingInt(Zone::priority));
    if (top.isPresent()) {
      int p = top.get().priority();
      long same = hits.stream().filter(z -> z.priority() != null && z.priority() == p).count();
      if (same == 1) {
        return new Match("SERVICEABLE", top.get(), List.of());
      }
    }
    return new Match("OVERLAP", null, hits);
  }

  /** 좌표가 들어가는 권역 모두(겹침 포함 — 2026-11-25 라이더 배달 지역 바꾸기 검사). */
  public static List<Zone> containing(List<Zone> zones, double lat, double lng) {
    List<Zone> hits = new ArrayList<>();
    for (Zone z : zones) {
      if (contains(z, lng, lat)) {
        hits.add(z);
      }
    }
    return hits;
  }

  static boolean contains(Zone z, double x, double y) {
    for (List<List<double[]>> polygon : z.polygons()) {
      if (polygon.isEmpty() || !inRing(polygon.get(0), x, y)) {
        continue;
      }
      boolean inHole = false;
      for (int i = 1; i < polygon.size(); i++) {
        if (inRing(polygon.get(i), x, y)) {
          inHole = true;
          break;
        }
      }
      if (!inHole) {
        return true;
      }
    }
    return false;
  }

  /** 레이 캐스팅(짝수-홀수 규칙). */
  static boolean inRing(List<double[]> ring, double x, double y) {
    boolean inside = false;
    for (int i = 0, j = ring.size() - 1; i < ring.size(); j = i++) {
      double xi = ring.get(i)[0];
      double yi = ring.get(i)[1];
      double xj = ring.get(j)[0];
      double yj = ring.get(j)[1];
      if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) {
        inside = !inside;
      }
    }
    return inside;
  }

  /**
   * GeoJSON 경계 검증: Polygon/MultiPolygon, 링은 4점 이상·닫힘, 경도 −180~180·위도 −90~90. 문제 있으면 사유.
   *
   * @param polygons 이미 파싱한 다각형들
   */
  public static Optional<String> validate(List<List<List<double[]>>> polygons) {
    if (polygons.isEmpty()) {
      return Optional.of("다각형이 없습니다.");
    }
    for (List<List<double[]>> polygon : polygons) {
      if (polygon.isEmpty()) {
        return Optional.of("링이 없는 다각형이 있습니다.");
      }
      for (List<double[]> ring : polygon) {
        if (ring.size() < 4) {
          return Optional.of("링은 4점 이상이어야 합니다(첫 점 = 끝 점).");
        }
        double[] first = ring.get(0);
        double[] last = ring.get(ring.size() - 1);
        if (first[0] != last[0] || first[1] != last[1]) {
          return Optional.of("링이 닫혀 있지 않습니다(첫 점과 끝 점이 같아야 함).");
        }
        for (double[] p : ring) {
          if (p.length < 2 || p[0] < -180 || p[0] > 180 || p[1] < -90 || p[1] > 90) {
            return Optional.of("좌표 범위가 잘못되었습니다([경도, 위도]).");
          }
        }
      }
    }
    return Optional.empty();
  }
}
