package com.pmart.rider.application;

import com.pmart.common.web.PmartException;
import com.pmart.rider.infrastructure.ServiceabilityClient;
import com.pmart.rider.infrastructure.TrackingClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 라이더 지금 위치가 속한 배달 권역(2026-11-25 라이더 앱 요청 — 사용자 "배달 가능 지역 내부에 있을 때만 해당 지역으로 권역 변경하게 해줘"). 위치 =
 * delivery-tracking 이 받은 마지막 위치, 받은 지 {@code pmart.rider.area-change.location-max-age}(기본 5분) 안이어야
 * 한다. 권역 = serviceability 발효 매핑에서 그 좌표가 들어가는 권역 모두(겹치면 겹친 권역 모두 "안"). 좌표는 로그·응답에 남기지 않는다. 외부 호출이라
 * 트랜잭션 밖에서 부른다.
 */
@Component
public class LocationAreas {

  public enum State {
    FRESH,
    STALE,
    UNKNOWN
  }

  public record Area(
      State state,
      @Nullable Instant locatedAt,
      @Nullable String servingMfcId,
      Set<String> mfcIds) {}

  private final TrackingClient tracking;
  private final ServiceabilityClient serviceability;
  private final Clock clock;
  private final Duration maxAge;

  public LocationAreas(
      TrackingClient tracking,
      ServiceabilityClient serviceability,
      Clock clock,
      @Value("${pmart.rider.area-change.location-max-age:PT5M}") Duration maxAge) {
    this.tracking = tracking;
    this.serviceability = serviceability;
    this.clock = clock;
    this.maxAge = maxAge;
  }

  public Area current(String riderId) {
    var latest = tracking.latest(riderId);
    if (latest.isEmpty()) {
      return new Area(State.UNKNOWN, null, null, Set.of());
    }
    var l = latest.get();
    if (l.receivedAt().isBefore(clock.instant().minus(maxAge))) {
      return new Area(State.STALE, l.receivedAt(), null, Set.of());
    }
    var z = serviceability.zonesAt(l.lat(), l.lng());
    return new Area(State.FRESH, l.receivedAt(), z.servingMfcId(), z.mfcIds());
  }

  /**
   * 새로 넣는 센터마다 지금 위치가 그 권역 안이어야 한다 — 위치 없음·오래됨 409 LOCATION_UNKNOWN, 밖 422 OUTSIDE_DELIVERY_AREA.
   */
  public void requireInside(String riderId, Collection<String> addedMfcIds) {
    if (addedMfcIds.isEmpty()) {
      return;
    }
    Area a = current(riderId);
    if (a.state() != State.FRESH) {
      throw new PmartException(
          "LOCATION_UNKNOWN",
          HttpStatus.CONFLICT,
          "지금 위치를 알 수 없습니다. 위치를 켜고 잠시 뒤 다시 시도하세요.",
          true,
          List.of());
    }
    for (String mfcId : addedMfcIds) {
      if (!a.mfcIds().contains(mfcId)) {
        throw new PmartException(
            "OUTSIDE_DELIVERY_AREA",
            HttpStatus.UNPROCESSABLE_CONTENT,
            "지금 위치가 그 센터 배달 지역 밖입니다. 그 지역 안에 있을 때 바꿀 수 있습니다.",
            false,
            a.servingMfcId() == null
                ? List.of()
                : List.of(new PmartException.Detail("currentMfcId", a.servingMfcId())));
      }
    }
  }
}
