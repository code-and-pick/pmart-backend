package com.pmart.delivery.tracking.application;

import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.delivery.tracking.infrastructure.LocationStore;
import com.pmart.delivery.tracking.infrastructure.RiderClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * 라이더 관제(2026-11-17, 사용자 "추천대로 전부"): RIDER_OPS·OPERATOR_ADMIN 만, 운행 중인 라이더의 현재 위치만(이동 이력 없음), 화면은
 * 10초마다 다시 부름. 라이더 목록은 rider-service 가 매번 정한다(운행을 끈 라이더는 빠짐). 좌표는 로그에 남기지 않는다.
 */
@Service
public class RiderControlService {

  public record Item(
      RiderClient.OnDuty rider,
      LocationStore.@Nullable Latest location,
      @Nullable Long staleSinceSeconds,
      boolean stale,
      List<LocationStore.Active> active) {}

  public record Board(List<Item> items, Instant asOf) {}

  private final RiderClient riders;
  private final LocationStore store;
  private final TrackingProperties props;
  private final Clock clock;

  public RiderControlService(
      RiderClient riders, LocationStore store, TrackingProperties props, Clock clock) {
    this.riders = riders;
    this.store = store;
    this.props = props;
    this.clock = clock;
  }

  public Board board(String mfcId) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "토큰이 필요합니다."));
    if (p.type() != PmartPrincipal.PrincipalType.OPERATOR
        || !(p.hasRole("RIDER_OPS") || p.hasRole("OPERATOR_ADMIN"))) {
      throw new PmartException(ErrorCode.FORBIDDEN, "라이더 관제는 라이더 운영·관리자만 볼 수 있습니다.");
    }
    Instant now = clock.instant();
    List<RiderClient.OnDuty> onDuty = riders.onDuty(mfcId);
    List<String> ids = onDuty.stream().map(RiderClient.OnDuty::riderId).toList();
    Map<String, LocationStore.Latest> latest = new HashMap<>();
    store.latest(ids).forEach(l -> latest.put(l.riderId(), l));
    Map<String, List<LocationStore.Active>> active = new HashMap<>();
    for (LocationStore.Active a : store.activeOf(ids)) {
      active.computeIfAbsent(a.riderId(), k -> new java.util.ArrayList<>()).add(a);
    }
    Duration staleAfter = props.staleAfter();
    List<Item> items =
        onDuty.stream()
            .map(
                r -> {
                  LocationStore.Latest l = latest.get(r.riderId());
                  Long since =
                      l == null
                          ? null
                          : Math.max(0, Duration.between(l.receivedAt(), now).toSeconds());
                  boolean stale = l == null || since > staleAfter.toSeconds();
                  return new Item(r, l, since, stale, active.getOrDefault(r.riderId(), List.of()));
                })
            .toList();
    return new Board(items, now);
  }
}
