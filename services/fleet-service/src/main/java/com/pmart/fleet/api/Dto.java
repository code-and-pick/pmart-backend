package com.pmart.fleet.api;

import com.pmart.fleet.api.generated.model.Carrier;
import com.pmart.fleet.api.generated.model.ContractType;
import com.pmart.fleet.api.generated.model.Driver;
import com.pmart.fleet.api.generated.model.FleetReservation;
import com.pmart.fleet.api.generated.model.TempClass;
import com.pmart.fleet.api.generated.model.TimeWindow;
import com.pmart.fleet.api.generated.model.Vehicle;
import com.pmart.fleet.application.FleetService;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;

/** 응답 변환. */
final class Dto {

  private Dto() {}

  static @Nullable OffsetDateTime time(@Nullable Instant i) {
    return i == null ? null : i.atOffset(ZoneOffset.UTC);
  }

  static @Nullable String blank(@Nullable String v) {
    return v == null || v.isBlank() ? null : v.strip();
  }

  static Carrier carrier(FleetService.Carrier c) {
    return new Carrier()
        .carrierId(c.carrierId())
        .name(c.name())
        .active(c.active())
        .createdAt(time(c.createdAt()));
  }

  static Vehicle vehicle(FleetService.Vehicle v) {
    return new Vehicle()
        .vehicleId(v.vehicleId())
        .carrierId(v.carrierId())
        .plateNo(v.plateNo())
        .contractType(ContractType.fromValue(v.contractType()))
        .tempClass(TempClass.fromValue(v.tempClass()))
        .tonnage(v.tonnage())
        .maxRt(v.maxRt())
        .maxWeightKg(v.maxWeightKg())
        .active(v.active())
        .version(v.version())
        .createdAt(time(v.createdAt()))
        .updatedAt(time(v.updatedAt()));
  }

  static Driver driver(FleetService.Driver d) {
    return new Driver()
        .driverId(d.driverId())
        .carrierId(d.carrierId())
        .displayName(d.displayName())
        .active(d.active())
        .version(d.version())
        .createdAt(time(d.createdAt()));
  }

  static FleetReservation reservation(FleetService.Reservation r) {
    return new FleetReservation()
        .reservationId(r.reservationId())
        .state(FleetReservation.StateEnum.fromValue(r.state()))
        .vehicleId(r.vehicleId())
        .driverId(r.driverId())
        .window(new TimeWindow().start(time(r.start())).end(time(r.end())))
        .purposeRef(r.purposeRef())
        .releaseReason(r.releaseReason())
        .createdAt(time(r.createdAt()))
        .releasedAt(time(r.releasedAt()));
  }
}
