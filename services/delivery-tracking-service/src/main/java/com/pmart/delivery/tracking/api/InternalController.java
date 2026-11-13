package com.pmart.delivery.tracking.api;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.delivery.tracking.api.generated.InternalApi;
import com.pmart.delivery.tracking.api.generated.model.DeliveryEtaAsOf;
import com.pmart.delivery.tracking.api.generated.model.DistanceMatrix;
import com.pmart.delivery.tracking.api.generated.model.DistanceMatrixRowsInner;
import com.pmart.delivery.tracking.api.generated.model.DistanceMatrixRowsInnerElementsInner;
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
      String origins,
      String destinations,
      @Nullable String vehicleType,
      @Nullable OffsetDateTime departAt) {
    var m = tracking.matrix(points(origins), points(destinations));
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

  /** "lat,lng;lat,lng". */
  static List<RoutePlanner.Point> points(String raw) {
    List<RoutePlanner.Point> out = new ArrayList<>();
    for (String pair : raw.split(";")) {
      if (pair.isBlank()) {
        continue;
      }
      String[] ll = pair.split(",");
      try {
        double lat = Double.parseDouble(ll[0].trim());
        double lng = Double.parseDouble(ll[1].trim());
        if (ll.length != 2 || lat < -90 || lat > 90 || lng < -180 || lng > 180) {
          throw new NumberFormatException();
        }
        out.add(new RoutePlanner.Point(lat, lng));
      } catch (RuntimeException e) {
        throw new PmartException(ErrorCode.VALIDATION_FAILED, "좌표 형식은 lat,lng;lat,lng 입니다.");
      }
    }
    return out;
  }
}
