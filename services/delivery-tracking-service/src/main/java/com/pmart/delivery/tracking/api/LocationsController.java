package com.pmart.delivery.tracking.api;

import com.pmart.common.web.PmartException;
import com.pmart.delivery.tracking.api.generated.LocationsApi;
import com.pmart.delivery.tracking.api.generated.model.LocationBatch;
import com.pmart.delivery.tracking.api.generated.model.LocationPoint;
import com.pmart.delivery.tracking.api.generated.model.RiderLocation;
import com.pmart.delivery.tracking.api.generated.model.UploadMyLocations202Response;
import com.pmart.delivery.tracking.application.TrackingService;
import com.pmart.delivery.tracking.infrastructure.LocationStore;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 LocationsApi — 라이더 앱 위치 배치 업로드·마지막 위치. */
@RestController
public class LocationsController implements LocationsApi {

  private final TrackingService tracking;

  public LocationsController(TrackingService tracking) {
    this.tracking = tracking;
  }

  @Override
  public ResponseEntity<UploadMyLocations202Response> uploadMyLocations(
      String idempotencyKey, String xSourceSystem, LocationBatch body) {
    var points =
        body.getPoints().stream()
            .map(
                (LocationPoint p) ->
                    new LocationStore.Point(
                        p.getLat().doubleValue(),
                        p.getLng().doubleValue(),
                        d(p.getAccuracyM()),
                        d(p.getSpeedMps()),
                        d(p.getHeadingDeg()),
                        p.getDeliveryId(),
                        p.getRecordedAt().toInstant()))
            .toList();
    var r = tracking.upload(idempotencyKey, xSourceSystem, points);
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(
            new UploadMyLocations202Response()
                .status(UploadMyLocations202Response.StatusEnum.APPLIED)
                .requestId(idempotencyKey)
                .resultId(UUID.nameUUIDFromBytes(idempotencyKey.getBytes()).toString())
                .appliedQty(r.appliedQty())
                .retryable(false)
                .occurredAt(OffsetDateTime.ofInstant(r.receivedAt(), ZoneOffset.UTC)));
  }

  @Override
  public ResponseEntity<RiderLocation> getMyLatestLocation() {
    var l =
        tracking
            .myLatest()
            .orElseThrow(() -> PmartException.notFound("LOCATION_NOT_FOUND", "받은 위치가 없습니다."));
    return ResponseEntity.ok(TrackingViews.location(l, tracking.staleSeconds(l)));
  }

  private static @Nullable Double d(@Nullable BigDecimal v) {
    return v == null ? null : v.doubleValue();
  }
}
