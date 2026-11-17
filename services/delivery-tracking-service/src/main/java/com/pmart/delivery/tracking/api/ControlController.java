package com.pmart.delivery.tracking.api;

import com.pmart.delivery.tracking.api.generated.ControlApi;
import com.pmart.delivery.tracking.api.generated.model.ControlRider;
import com.pmart.delivery.tracking.api.generated.model.ControlRiderActiveDeliveriesInner;
import com.pmart.delivery.tracking.api.generated.model.ControlRiderLocation;
import com.pmart.delivery.tracking.api.generated.model.GpsQuality;
import com.pmart.delivery.tracking.api.generated.model.ListRiderLocationsForControl200Response;
import com.pmart.delivery.tracking.application.RiderControlService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 라이더 관제(2026-11-17) — 직원용 위치 목록. 위치는 개인정보라 응답을 남기지 않게 no-store. */
@RestController
public class ControlController implements ControlApi {

  private final RiderControlService control;

  public ControlController(RiderControlService control) {
    this.control = control;
  }

  @Override
  public ResponseEntity<ListRiderLocationsForControl200Response> listRiderLocationsForControl(
      String mfcId) {
    RiderControlService.Board b = control.board(mfcId);
    var out =
        new ListRiderLocationsForControl200Response()
            .asOf(time(b.asOf()))
            .items(b.items().stream().map(ControlController::item).toList());
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(out);
  }

  private static ControlRider item(RiderControlService.Item i) {
    var r = new ControlRider();
    r.setRiderId(i.rider().riderId());
    r.setNameMasked(i.rider().nameMasked());
    r.setVehicleType(i.rider().vehicleType());
    r.setNewDispatchEnabled(i.rider().newDispatchEnabled());
    r.setRestriction(i.rider().restriction());
    if (i.location() != null) {
      var l = i.location();
      r.setLocation(
          new ControlRiderLocation()
              .lat(BigDecimal.valueOf(l.lat()))
              .lng(BigDecimal.valueOf(l.lng()))
              .accuracyM(l.accuracyM() == null ? null : BigDecimal.valueOf(l.accuracyM()))
              .recordedAt(time(l.recordedAt()))
              .receivedAt(time(l.receivedAt()))
              .quality(GpsQuality.fromValue(l.quality())));
    }
    r.setStaleSinceSeconds(
        i.staleSinceSeconds() == null ? null : Math.toIntExact(i.staleSinceSeconds()));
    r.setStale(i.stale());
    r.setActiveDeliveries(
        i.active().stream()
            .map(
                a ->
                    new ControlRiderActiveDeliveriesInner()
                        .deliveryId(a.deliveryId())
                        .stage(
                            a.stage() == null
                                ? null
                                : ControlRiderActiveDeliveriesInner.StageEnum.fromValue(a.stage()))
                        .pickedUp(a.pickedUp())
                        .etaPickup(time(a.etaPickup()))
                        .etaDrop(time(a.etaDrop())))
            .toList());
    return r;
  }

  private static @Nullable OffsetDateTime time(@Nullable Instant i) {
    return i == null ? null : i.atOffset(ZoneOffset.UTC);
  }
}
