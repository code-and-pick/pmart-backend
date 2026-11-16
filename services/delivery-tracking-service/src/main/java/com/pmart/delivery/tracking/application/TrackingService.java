package com.pmart.delivery.tracking.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.delivery.tracking.application.RoutePlanner.Leg;
import com.pmart.delivery.tracking.application.RoutePlanner.Point;
import com.pmart.delivery.tracking.domain.LocationQuality;
import com.pmart.delivery.tracking.infrastructure.LocationStore;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 라이더 위치 수신·조회와 도로 거리 행렬(카카오모빌리티).
 *
 * <ul>
 *   <li>위치 배치: 단말 시각 순으로 정렬해 모두 저장, 품질은 {@link LocationQuality}. 마지막 위치는 더 늦은 정상 점만 바꾼다. 같은 키 재전송은
 *       같은 결과(저장 점 수).
 *   <li>거리 행렬: 도착지 하나면 다중 출발지 한 번, 아니면 출발지마다 다중 목적지 한 번. 실패는 오류 그대로(직선거리 대체 없음 — ADR-0058).
 * </ul>
 */
@Service
public class TrackingService {

  public static final int MAX_POINTS = 30;

  public record Matrix(List<List<Leg>> rows) {}

  public record Uploaded(int appliedQty, Instant receivedAt) {}

  private final LocationStore store;
  private final RoutePlanner routes;
  private final RequestIdempotency idempotency;
  private final TrackingProperties props;
  private final Clock clock;

  public TrackingService(
      LocationStore store,
      RoutePlanner routes,
      RequestIdempotency idempotency,
      TrackingProperties props,
      Clock clock) {
    this.store = store;
    this.routes = routes;
    this.idempotency = idempotency;
    this.props = props;
    this.clock = clock;
  }

  public Uploaded upload(String requestId, String sourceSystem, List<LocationStore.Point> points) {
    String riderId = requireRider();
    return idempotency
        .execute(
            new RequestKey(sourceSystem, requestId, "uploadMyLocations", riderId),
            points,
            Uploaded.class,
            () -> {
              Instant now = clock.instant();
              Instant last =
                  store.lockLatest(riderId).map(LocationStore.Latest::recordedAt).orElse(null);
              List<LocationStore.Point> sorted = new ArrayList<>(points);
              sorted.sort(Comparator.comparing(LocationStore.Point::recordedAt));
              for (LocationStore.Point p : sorted) {
                LocationQuality.Quality q =
                    LocationQuality.classify(
                        p.recordedAt(),
                        p.accuracyM(),
                        now,
                        last,
                        props.maxClockAhead(),
                        props.lowAccuracyM());
                store.insertPoint(riderId, p, now, q.name());
                if (LocationQuality.updatesLatest(q)) {
                  store.upsertLatest(riderId, p, now, q.name());
                  last = p.recordedAt();
                }
              }
              // 통신이 살아 있다는 표시(품질과 무관) — 위치 끊김 감지의 기준(RiderWatch)
              store.touchSignal(riderId, now);
              return new Uploaded(sorted.size(), now);
            })
        .value();
  }

  public Optional<LocationStore.Latest> myLatest() {
    return store.latest(requireRider());
  }

  public Optional<LocationStore.Latest> latest(String riderId) {
    return store.latest(riderId);
  }

  /** 정한 시각에 가장 가까운 위치 점(DD-2 오배송 회수지). 앞뒤 {@code location-at-window} 안에 없으면 empty. */
  public Optional<LocationStore.Latest> near(String riderId, Instant at) {
    return store.pointNear(riderId, at, props.locationAtWindow());
  }

  public List<LocationStore.Latest> latest(List<String> riderIds) {
    return store.latest(riderIds.stream().distinct().limit(200).toList());
  }

  public Matrix matrix(List<Point> origins, List<Point> destinations) {
    if (origins.isEmpty()
        || destinations.isEmpty()
        || origins.size() > MAX_POINTS
        || destinations.size() > MAX_POINTS) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED, "출발지·도착지는 각각 1~" + MAX_POINTS + "개입니다.");
    }
    List<List<Leg>> rows = new ArrayList<>();
    if (destinations.size() == 1 && origins.size() > 1) {
      List<Leg> col = routes.toOne(origins, destinations.get(0));
      for (Leg l : col) {
        rows.add(List.of(l));
      }
    } else {
      for (Point o : origins) {
        rows.add(routes.fromOne(o, destinations));
      }
    }
    return new Matrix(rows);
  }

  public long staleSeconds(LocationStore.Latest l) {
    return Math.max(0, clock.instant().getEpochSecond() - l.receivedAt().getEpochSecond());
  }

  private static String requireRider() {
    PmartPrincipal p =
        PmartPrincipal.current()
            .filter(x -> x.type() == PmartPrincipal.PrincipalType.RIDER)
            .orElseThrow(
                () ->
                    new PmartException(
                        ErrorCode.FORBIDDEN.name(),
                        HttpStatus.FORBIDDEN,
                        "라이더만 쓸 수 있습니다.",
                        false,
                        List.of()));
    return p.subject();
  }
}
