package com.pmart.delivery.dispatch.domain;

/** 좌표와 직선거리(하버사인). 반경 단계 판정에만 쓴다 — 거리·시간·배달료는 카카오모빌리티 도로 값(ADR-0058). */
public record Geo(double lat, double lng) {

  private static final double EARTH_M = 6_371_000.0;

  public double metersTo(Geo o) {
    double dLat = Math.toRadians(o.lat - lat);
    double dLng = Math.toRadians(o.lng - lng);
    double a =
        Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat))
                * Math.cos(Math.toRadians(o.lat))
                * Math.sin(dLng / 2)
                * Math.sin(dLng / 2);
    return 2 * EARTH_M * Math.asin(Math.min(1, Math.sqrt(a)));
  }

  /** tracking 거리 행렬 질의 형식 "lat,lng". */
  public String query() {
    return lat + "," + lng;
  }
}
