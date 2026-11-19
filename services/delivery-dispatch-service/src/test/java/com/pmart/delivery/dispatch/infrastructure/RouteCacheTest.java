package com.pmart.delivery.dispatch.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pmart.delivery.dispatch.application.DispatchProperties;
import com.pmart.delivery.dispatch.domain.Geo;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 카카오 도로 시간 다시 쓰기(M3). */
class RouteCacheTest {

  static final DispatchProperties PROPS =
      new DispatchProperties(
          null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
          null, null, null, null, null, null, null, null, null, null, null);

  @Test
  void riderToMfcIsReusedWithinTtlAndDropPairsForever() {
    Upstreams up = mock(Upstreams.class);
    Upstreams.Leg leg = new Upstreams.Leg(true, 900, 300);
    when(up.matrix(anyList(), anyList()))
        .thenReturn(new Upstreams.Leg[][] {{leg}})
        .thenReturn(
            new Upstreams.Leg[][] {
              {new Upstreams.Leg(true, 0, 0), new Upstreams.Leg(true, 500, 120)},
              {new Upstreams.Leg(true, 500, 110), new Upstreams.Leg(true, 0, 0)}
            });
    Clock clock = Clock.fixed(Instant.parse("2026-11-13T05:00:00Z"), ZoneOffset.UTC);
    RouteCache cache = new RouteCache(up, PROPS, clock);
    Geo mfc = new Geo(37.4979, 127.0276);
    Geo rider = new Geo(37.49862, 127.02812);
    assertThat(cache.toMfc("M", List.of(rider), mfc)[0].durationS()).isEqualTo(300);
    // 같은 100m 칸 — 다시 부르지 않는다
    assertThat(cache.toMfc("M", List.of(new Geo(37.49871, 127.02819)), mfc)[0].durationS())
        .isEqualTo(300);
    List<Geo> drops = List.of(new Geo(37.5, 127.03), new Geo(37.501, 127.031));
    assertThat(cache.betweenDeliveries(List.of("D1", "D2"), drops)[0][1]).isEqualTo(120);
    assertThat(cache.betweenDeliveries(List.of("D2", "D1"), drops.reversed())[0][1]).isEqualTo(110);
    verify(up, times(2)).matrix(anyList(), anyList());
  }
}
