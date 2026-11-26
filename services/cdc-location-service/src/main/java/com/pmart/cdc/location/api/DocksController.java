package com.pmart.cdc.location.api;

import com.pmart.cdc.location.api.generated.DocksApi;
import com.pmart.cdc.location.api.generated.model.DockReservation;
import com.pmart.cdc.location.api.generated.model.DockReservationWindow;
import com.pmart.cdc.location.api.generated.model.ListDockReservations200Response;
import com.pmart.cdc.location.api.generated.model.ListDockReservations200ResponseDocksInner;
import com.pmart.cdc.location.application.DockService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 DocksApi — 운영자 도크 예약 보기(0.8.0 DK-1). */
@RestController
public class DocksController implements DocksApi {

  private final DockService docks;

  public DocksController(DockService docks) {
    this.docks = docks;
  }

  @Override
  public ResponseEntity<ListDockReservations200Response> listDockReservations(
      String cdcId, @Nullable LocalDate date, @Nullable String dockCode, @Nullable String state) {
    var b =
        docks.board(
            cdcId,
            date,
            dockCode == null || dockCode.isBlank() ? null : dockCode.strip(),
            state == null || state.isBlank() ? null : state.strip());
    return ResponseEntity.ok(
        new ListDockReservations200Response()
            .docks(
                new ArrayList<>(
                    b.docks().stream()
                        .map(
                            d ->
                                new ListDockReservations200ResponseDocksInner()
                                    .dockCode(d.dockCode())
                                    .floor(d.floor())
                                    .temperatureZone(d.temperatureZone()))
                        .toList()))
            .items(new ArrayList<>(b.items().stream().map(DocksController::dto).toList())));
  }

  static @Nullable OffsetDateTime time(@Nullable Instant i) {
    return i == null ? null : i.atOffset(ZoneOffset.UTC);
  }

  static DockReservation dto(DockService.View v) {
    return new DockReservation()
        .dockReservationId(v.dockReservationId())
        .centerId(v.centerId())
        .dockCode(v.dockCode())
        .tripId(v.tripId())
        .purpose(DockReservation.PurposeEnum.fromValue(v.purpose()))
        .state(DockReservation.StateEnum.fromValue(v.state()))
        .window(new DockReservationWindow().start(time(v.start())).end(time(v.end())))
        .version(v.version())
        .floor(v.floor())
        .temperatureZone(v.temperatureZone())
        .vehicleId(v.vehicleId())
        .routeId(v.routeId())
        .plateNo(v.plateNo())
        .releaseReason(v.releaseReason())
        .createdAt(time(v.createdAt()));
  }
}
