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
      List<LocationStore.Active> active,
      @Nullable Instant waitingSince,
      boolean locationShared) {}

  /** 위치 공유(2026-11-19 사용자 "신규배차도 끄고 배송 중인 것도 없으면 위치 공유가 안 되어야") — 진행 중 배송이 있거나 신규배차를 켠 라이더만. */
  static boolean shares(boolean newDispatchEnabled, boolean hasActive) {
    return newDispatchEnabled || hasActive;
  }

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
    Map<String, Instant> lastEnded = store.lastEndedOf(ids);
    Duration staleAfter = props.staleAfter();
    List<Item> items =
        onDuty.stream()
            .map(
                r -> {
                  List<LocationStore.Active> mine = active.getOrDefault(r.riderId(), List.of());
                  boolean shared = shares(r.newDispatchEnabled(), !mine.isEmpty());
                  LocationStore.Latest l = shared ? latest.get(r.riderId()) : null;
                  Long since =
                      l == null
                          ? null
                          : Math.max(0, Duration.between(l.receivedAt(), now).toSeconds());
                  boolean stale = shared && (l == null || since > staleAfter.toSeconds());
                  return new Item(
                      r,
                      l,
                      since,
                      stale,
                      mine,
                      waitingSince(r.availableSince(), mine.isEmpty(), lastEnded.get(r.riderId())),
                      shared);
                })
            .toList();
    return new Board(items, now);
  }

  /**
   * 대기 시작(2026-11-18 사용자 "대기 = 운행 중 + 신규배차 켬 + 배송 0건"): 신규배차를 켜 두었고 진행 중 배송이 없으면 (켠 시각, 마지막 배송 끝) 중
   * 늦은 때, 아니면 null.
   */
  static @Nullable Instant waitingSince(
      @Nullable Instant availableSince, boolean noActive, @Nullable Instant lastEnded) {
    if (availableSince == null || !noActive) {
      return null;
    }
    return lastEnded != null && lastEnded.isAfter(availableSince) ? lastEnded : availableSince;
  }
}
