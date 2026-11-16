package com.pmart.delivery.tracking.api;

import com.pmart.common.web.PmartException;
import com.pmart.delivery.tracking.api.generated.InternalApi;
import com.pmart.delivery.tracking.api.generated.model.DeliveryEtaAsOf;
import com.pmart.delivery.tracking.api.generated.model.DistanceMatrix;
import com.pmart.delivery.tracking.api.generated.model.DistanceMatrixRequest;
import com.pmart.delivery.tracking.api.generated.model.DistanceMatrixRowsInner;
import com.pmart.delivery.tracking.api.generated.model.DistanceMatrixRowsInnerElementsInner;
import com.pmart.delivery.tracking.api.generated.model.LatLng;
import com.pmart.delivery.tracking.api.generated.model.ListRiderLocationsInternal200Response;
import com.pmart.delivery.tracking.api.generated.model.RiderLocation;
import com.pmart.delivery.tracking.application.RoutePlanner;
import com.pmart.delivery.tracking.application.TrackingService;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 InternalApi — dispatch 가 부르는 거리 행렬·라이더 위치(토큰 없는 내부 호출). */
@RestController
public class InternalController implements InternalApi {

  private final TrackingService tracking;
  private final Clock clock;

  public InternalController(TrackingService tracking, Clock clock) {
    this.tracking = tracking;
    this.clock = clock;
  }

  @Override
  public ResponseEntity<DistanceMatrix> getDistanceMatrix(
      String idempotencyKey, String xSourceSystem, DistanceMatrixRequest body) {
    @Nullable String vehicleType =
        body.getVehicleType() == null ? null : body.getVehicleType().getValue();
    var m = tracking.matrix(points(body.getOrigins()), points(body.getDestinations()));
    List<DistanceMatrixRowsInner> rows = new ArrayList<>();
    for (List<RoutePlanner.Leg> row : m.rows()) {
      rows.add(
          new DistanceMatrixRowsInner()
              .elements(
                  row.stream()
                      .map(
                          l ->
                              l.ok()
                                  ? new DistanceMatrixRowsInnerElementsInner()
                                      .distanceM(l.distanceM())
                                      .durationSeconds(l.durationSeconds())
                                      .status(DistanceMatrixRowsInnerElementsInner.StatusEnum.OK)
                                  : new DistanceMatrixRowsInnerElementsInner()
                                      .status(
                                          DistanceMatrixRowsInnerElementsInner.StatusEnum.NO_ROUTE))
                      .toList()));
    }
    return ResponseEntity.ok(
        new DistanceMatrix()
            .provider(DistanceMatrix.ProviderEnum.KAKAO_MOBILITY)
            .fallback(false)
            .vehicleType(vehicleType)
            .rows(rows)
            .asOf(
                new DeliveryEtaAsOf()
                    .asOf(OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                    .freshness(DeliveryEtaAsOf.FreshnessEnum.CURRENT)));
  }

  @Override
  public ResponseEntity<RiderLocation> getRiderLocationAtInternal(
      String riderId, OffsetDateTime at) {
    var l =
        tracking
            .near(riderId, at.toInstant())
            .orElseThrow(
                () -> PmartException.notFound("LOCATION_NOT_FOUND", "그 시각 앞뒤에 받은 위치가 없습니다."));
    return ResponseEntity.ok(TrackingViews.location(l, 0).staleSinceSeconds(null));
  }

  @Override
  public ResponseEntity<RiderLocation> getRiderLocationInternal(String riderId) {
    var l =
        tracking
            .latest(riderId)
            .orElseThrow(() -> PmartException.notFound("LOCATION_NOT_FOUND", "받은 위치가 없습니다."));
    return ResponseEntity.ok(TrackingViews.location(l, tracking.staleSeconds(l)));
  }

  @Override
  public ResponseEntity<ListRiderLocationsInternal200Response> listRiderLocationsInternal(
      List<String> riderIds) {
    return ResponseEntity.ok(
        new ListRiderLocationsInternal200Response()
            .items(
                tracking.latest(riderIds).stream()
                    .map(l -> TrackingViews.location(l, tracking.staleSeconds(l)))
                    .toList()));
  }

  static List<RoutePlanner.Point> points(List<LatLng> raw) {
    return raw.stream()
        .map(p -> new RoutePlanner.Point(p.getLat().doubleValue(), p.getLng().doubleValue()))
        .toList();
  }
}
