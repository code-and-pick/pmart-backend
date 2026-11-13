package com.pmart.delivery.dispatch.infrastructure;

import com.pmart.delivery.dispatch.application.DispatchProperties;
import com.pmart.delivery.dispatch.domain.Geo;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 카카오모빌리티 도로 시간 다시 쓰기(PC 1 검토 M3 — 사용자 2026-11-13 "구간 결과를 저장해 다시 쓰고 호출 간격을 늘려").
 *
 * <ul>
 *   <li>라이더 → MFC: (MFC, 라이더 위치를 약 100m 칸으로) 마다 {@code route-cache-ttl}(기본 5분) 동안.
 *   <li>고객 ↔ 고객(알뜰배달 묶음): 배송 좌표는 바뀌지 않으므로 (배송 A, 배송 B) 마다 오래 둔다(배송 수 상한으로 오래된 것부터 버림).
 * </ul>
 *
 * 메모리에만 둔다(재시작하면 다시 조회 — 정본 아님). 좌표는 칸 이름으로만 쓰고 로그에 남기지 않는다.
 */
@Component
public class RouteCache {

  private static final int MAX_PAIRS = 20_000;

  private record Timed(Upstreams.Leg leg, Instant at) {}

  private final Upstreams upstreams;
  private final DispatchProperties props;
  private final Clock clock;
  private final Map<String, Timed> pickup = new ConcurrentHashMap<>();
  private final Map<String, Integer> between = new ConcurrentHashMap<>();

  public RouteCache(Upstreams upstreams, DispatchProperties props, Clock clock) {
    this.upstreams = upstreams;
    this.props = props;
    this.clock = clock;
  }

  /** 라이더들 → MFC. 없는(또는 오래된) 것만 한 번에 조회한다. */
  public Upstreams.Leg[] toMfc(String mfcId, List<Geo> riders, Geo mfc) {
    Instant now = clock.instant();
    Upstreams.Leg[] out = new Upstreams.Leg[riders.size()];
    List<Integer> missing = new ArrayList<>();
    for (int i = 0; i < riders.size(); i++) {
      Timed t = pickup.get(cell(mfcId, riders.get(i)));
      if (t != null && t.at().plus(props.routeCacheTtl()).isAfter(now)) {
        out[i] = t.leg();
      } else {
        missing.add(i);
      }
    }
    if (!missing.isEmpty()) {
      Upstreams.Leg[][] m =
          upstreams.matrix(missing.stream().map(riders::get).toList(), List.of(mfc));
      for (int k = 0; k < missing.size(); k++) {
        int i = missing.get(k);
        out[i] = m[k][0];
        pickup.put(cell(mfcId, riders.get(i)), new Timed(m[k][0], now));
      }
      if (pickup.size() > MAX_PAIRS) {
        pickup
            .entrySet()
            .removeIf(e -> !e.getValue().at().plus(props.routeCacheTtl()).isAfter(now));
      }
    }
    return out;
  }

  /** 배송 ↔ 배송 도로 소요(초, 길 없음 -1). 모르는 쌍이 하나라도 있으면 한 번에 다시 조회해 채운다. */
  public int[][] betweenDeliveries(List<String> deliveryIds, List<Geo> drops) {
    int n = deliveryIds.size();
    int[][] out = new int[n][n];
    boolean complete = true;
    for (int i = 0; i < n && complete; i++) {
      for (int j = 0; j < n && complete; j++) {
        if (i != j) {
          complete = between.containsKey(pair(deliveryIds.get(i), deliveryIds.get(j)));
        }
      }
    }
    if (!complete) {
      Upstreams.Leg[][] m = upstreams.matrix(drops, drops);
      if (between.size() > MAX_PAIRS) {
        between.clear();
      }
      for (int i = 0; i < n; i++) {
        for (int j = 0; j < n; j++) {
          if (i != j) {
            between.put(
                pair(deliveryIds.get(i), deliveryIds.get(j)),
                m[i][j].ok() ? m[i][j].durationS() : -1);
          }
        }
      }
    }
    for (int i = 0; i < n; i++) {
      for (int j = 0; j < n; j++) {
        out[i][j] =
            i == j ? 0 : between.getOrDefault(pair(deliveryIds.get(i), deliveryIds.get(j)), -1);
      }
    }
    return out;
  }

  /** 약 100m 칸(소수 셋째 자리). */
  static String cell(String mfcId, Geo g) {
    return mfcId + "|" + String.format(Locale.ROOT, "%.3f,%.3f", g.lat(), g.lng());
  }

  private static String pair(String a, String b) {
    return a + "|" + b;
  }

  /** 시험용. */
  Map<String, Integer> sizes() {
    Map<String, Integer> m = new HashMap<>();
    m.put("pickup", pickup.size());
    m.put("between", between.size());
    return m;
  }
}
