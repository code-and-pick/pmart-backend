package com.pmart.fleet.api;

import com.pmart.fleet.api.generated.InternalApi;
import com.pmart.fleet.api.generated.model.CreateReservationRequest;
import com.pmart.fleet.api.generated.model.FleetReservation;
import com.pmart.fleet.api.generated.model.ReleaseReservationRequest;
import com.pmart.fleet.application.FleetService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 InternalApi — 점유 예약(planning·execution). */
@RestController
public class InternalController implements InternalApi {

  private final FleetService fleet;

  public InternalController(FleetService fleet) {
    this.fleet = fleet;
  }

  @Override
  public ResponseEntity<FleetReservation> createReservation(
      String idempotencyKey, String xSourceSystem, CreateReservationRequest b) {
    var n =
        new FleetService.NewReservation(
            b.getVehicleId(),
            b.getDriverId(),
            b.getWindow().getStart().toInstant(),
            b.getWindow().getEnd().toInstant(),
            b.getPurposeRef());
    return ResponseEntity.ok(Dto.reservation(fleet.reserve(idempotencyKey, xSourceSystem, n)));
  }

  @Override
  public ResponseEntity<FleetReservation> getReservation(String reservationId) {
    return ResponseEntity.ok(Dto.reservation(fleet.reservation(reservationId)));
  }

  @Override
  public ResponseEntity<FleetReservation> releaseReservation(
      String reservationId,
      String idempotencyKey,
      String xSourceSystem,
      ReleaseReservationRequest b) {
    return ResponseEntity.ok(
        Dto.reservation(
            fleet.release(idempotencyKey, xSourceSystem, reservationId, b.getReason().getValue())));
  }
}
