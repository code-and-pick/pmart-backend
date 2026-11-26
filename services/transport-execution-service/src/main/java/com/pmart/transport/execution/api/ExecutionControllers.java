package com.pmart.transport.execution.api;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.transport.execution.api.generated.IncidentsApi;
import com.pmart.transport.execution.api.generated.InternalApi;
import com.pmart.transport.execution.api.generated.StopsApi;
import com.pmart.transport.execution.api.generated.TripsApi;
import com.pmart.transport.execution.api.generated.model.CancelTripRequest;
import com.pmart.transport.execution.api.generated.model.DepartureFact;
import com.pmart.transport.execution.api.generated.model.DepartureInput;
import com.pmart.transport.execution.api.generated.model.EventInput;
import com.pmart.transport.execution.api.generated.model.HandoverInput;
import com.pmart.transport.execution.api.generated.model.LoadingInput;
import com.pmart.transport.execution.api.generated.model.RaiseIncidentRequest;
import com.pmart.transport.execution.api.generated.model.ResolveIncidentRequest;
import com.pmart.transport.execution.api.generated.model.Trip;
import com.pmart.transport.execution.api.generated.model.TripInput;
import com.pmart.transport.execution.api.generated.model.TripPage;
import com.pmart.transport.execution.api.generated.model.TripState;
import com.pmart.transport.execution.application.TripService;
import com.pmart.transport.execution.application.TripService.Caller;
import java.time.LocalDate;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * 계약 TripsApi·StopsApi·IncidentsApi·InternalApi — 변환만. 직원 경로와 시뮬레이터 명령은 같은 {@link Commands} 를 쓴다.
 */
final class ExecutionControllers {

  private ExecutionControllers() {}

  /** 명령 공통 — 직원(Caller.staff)·시뮬레이터(Caller.internal). */
  static final class Commands {
    private final TripService trips;

    Commands(TripService trips) {
      this.trips = trips;
    }

    Trip checkIn(Caller c, String tripId, @Nullable EventInput e) {
      return Dto.trip(trips.checkIn(c, tripId, Dto.event(e)));
    }

    Trip loading(Caller c, String tripId, LoadingInput b) {
      return Dto.trip(
          trips.completeLoading(
              c,
              tripId,
              Dto.event(b.getAt(), b.getLocation(), b.getTemperatureC()),
              Dto.list(b.getTransfers()).stream()
                  .map(
                      t ->
                          new TripService.LoadTrf(
                              t.getTrfId(),
                              Dto.list(t.getLpns()),
                              t.getRtCount(),
                              Dto.list(t.getSealNos())))
                  .toList()));
    }

    Trip depart(Caller c, String tripId, @Nullable DepartureInput b) {
      return Dto.trip(
          trips.depart(
              c,
              tripId,
              b == null
                  ? TripService.EventIn.none()
                  : Dto.event(b.getAt(), b.getLocation(), b.getTemperatureC()),
              b == null || b.getSource() == null ? null : b.getSource().getValue(),
              b == null ? null : b.getEvidenceRef()));
    }

    Trip end(Caller c, String tripId, @Nullable EventInput e) {
      return Dto.trip(trips.end(c, tripId, Dto.event(e)));
    }

    Trip arrive(Caller c, String tripId, int seq, @Nullable EventInput e) {
      return Dto.trip(trips.arrive(c, tripId, seq, Dto.event(e)));
    }

    Trip handover(Caller c, String tripId, int seq, HandoverInput b) {
      return Dto.trip(
          trips.handover(
              c,
              tripId,
              seq,
              Dto.event(b.getAt(), b.getLocation(), b.getTemperatureC()),
              b.getReceiverName(),
              Dto.list(b.getTransfers()).stream()
                  .map(
                      t ->
                          new TripService.HandTrf(
                              t.getTrfId(),
                              Dto.list(t.getReceivedLpns()),
                              Dto.list(t.getDamagedLpns())))
                  .toList()));
    }

    Trip departStop(Caller c, String tripId, int seq, @Nullable EventInput e) {
      return Dto.trip(trips.departStop(c, tripId, seq, Dto.event(e)));
    }
  }

  @RestController
  static class Trips implements TripsApi {
    private final TripService trips;
    private final Commands cmd;

    Trips(TripService trips) {
      this.trips = trips;
      this.cmd = new Commands(trips);
    }

    @Override
    public ResponseEntity<TripPage> listCenterTrips(
        String cdcId,
        @Nullable LocalDate shipDate,
        @Nullable TripState state,
        @Nullable Boolean delayed,
        Integer page,
        Integer size) {
      return ResponseEntity.ok(
          Dto.page(
              trips.centerTrips(
                  Caller.staff("staff"),
                  cdcId,
                  shipDate,
                  state == null ? null : state.getValue(),
                  delayed,
                  page == null ? 0 : page,
                  size == null ? 50 : size)));
    }

    @Override
    public ResponseEntity<TripPage> listMfcInboundTrips(
        String mfcId, @Nullable LocalDate arrivalDate, Integer page, Integer size) {
      return ResponseEntity.ok(
          Dto.page(
              trips.mfcTrips(
                  Caller.staff("staff"),
                  mfcId,
                  arrivalDate,
                  page == null ? 0 : page,
                  size == null ? 50 : size)));
    }

    @Override
    public ResponseEntity<Trip> getTrip(String tripId) {
      return ResponseEntity.ok(Dto.trip(trips.get(Caller.staff("staff"), tripId)));
    }

    @Override
    public ResponseEntity<Trip> checkInTrip(
        String tripId, String idempotencyKey, String xSourceSystem, @Nullable EventInput e) {
      return ResponseEntity.ok(cmd.checkIn(Caller.staff(xSourceSystem), tripId, e));
    }

    @Override
    public ResponseEntity<Trip> completeLoading(
        String tripId, String idempotencyKey, String xSourceSystem, LoadingInput b) {
      return ResponseEntity.ok(cmd.loading(Caller.staff(xSourceSystem), tripId, b));
    }

    @Override
    public ResponseEntity<Trip> departTrip(
        String tripId, String idempotencyKey, String xSourceSystem, @Nullable DepartureInput b) {
      return ResponseEntity.ok(cmd.depart(Caller.staff(xSourceSystem), tripId, b));
    }

    @Override
    public ResponseEntity<Trip> endTrip(
        String tripId, String idempotencyKey, String xSourceSystem, @Nullable EventInput e) {
      return ResponseEntity.ok(cmd.end(Caller.staff(xSourceSystem), tripId, e));
    }
  }

  @RestController
  static class Stops implements StopsApi {
    private final Commands cmd;

    Stops(TripService trips) {
      this.cmd = new Commands(trips);
    }

    @Override
    public ResponseEntity<Trip> arriveAtStop(
        String tripId,
        Integer stopSeq,
        String idempotencyKey,
        String xSourceSystem,
        @Nullable EventInput e) {
      return ResponseEntity.ok(cmd.arrive(Caller.staff(xSourceSystem), tripId, stopSeq, e));
    }

    @Override
    public ResponseEntity<Trip> recordHandover(
        String tripId,
        Integer stopSeq,
        String idempotencyKey,
        String xSourceSystem,
        HandoverInput b) {
      return ResponseEntity.ok(cmd.handover(Caller.staff(xSourceSystem), tripId, stopSeq, b));
    }

    @Override
    public ResponseEntity<Trip> departStop(
        String tripId,
        Integer stopSeq,
        String idempotencyKey,
        String xSourceSystem,
        @Nullable EventInput e) {
      return ResponseEntity.ok(cmd.departStop(Caller.staff(xSourceSystem), tripId, stopSeq, e));
    }
  }

  @RestController
  static class Incidents implements IncidentsApi {
    private final TripService trips;

    Incidents(TripService trips) {
      this.trips = trips;
    }

    @Override
    public ResponseEntity<Trip> raiseIncident(
        String tripId, String idempotencyKey, String xSourceSystem, RaiseIncidentRequest b) {
      return ResponseEntity.ok(
          Dto.trip(
              trips.raiseIncident(
                  Caller.staff(xSourceSystem),
                  tripId,
                  b.getType().getValue(),
                  b.getStopSeq(),
                  b.getDescription())));
    }

    @Override
    public ResponseEntity<Trip> resolveIncident(
        String tripId,
        String incidentId,
        String idempotencyKey,
        String xSourceSystem,
        ResolveIncidentRequest b) {
      return ResponseEntity.ok(
          Dto.trip(
              trips.resolveIncident(
                  Caller.staff(xSourceSystem), tripId, incidentId, b.getResolution())));
    }
  }

  @RestController
  static class Internal implements InternalApi {
    static final Pattern STOP = Pattern.compile("^stops-([1-9])-(arrivals|handovers|departures)$");
    private final TripService trips;
    private final Commands cmd;
    private final ObjectMapper mapper;

    Internal(TripService trips, ObjectMapper mapper) {
      this.trips = trips;
      this.cmd = new Commands(trips);
      this.mapper = mapper;
    }

    @Override
    public ResponseEntity<Trip> createTrip(
        String idempotencyKey, String xSourceSystem, TripInput b) {
      var in =
          new TripService.TripInput(
              b.getTripId(),
              b.getOrderVersion(),
              b.getCdcId(),
              b.getShipDate(),
              b.getRouteId(),
              b.getTempClass().getValue(),
              b.getVehicleId(),
              b.getPlateNo(),
              b.getDriverId(),
              b.getDriverName(),
              b.getReservationId(),
              b.getPlannedLoadingStartAt().toInstant(),
              b.getPlannedDepartureAt().toInstant(),
              b.getPlannedReturnAt().toInstant(),
              b.getReturnMinutes() == null ? 0 : b.getReturnMinutes(),
              b.getStops().stream()
                  .map(
                      s ->
                          new TripService.StopInput(
                              s.getSeq(),
                              s.getMfcId(),
                              s.getPlannedArrivalAt().toInstant(),
                              s.getPlannedDepartureAt() == null
                                  ? null
                                  : s.getPlannedDepartureAt().toInstant(),
                              s.getLegMinutes(),
                              Dto.list(s.getTrfIds())))
                  .toList());
      return ResponseEntity.ok(Dto.trip(trips.create(in)));
    }

    @Override
    public ResponseEntity<Trip> cancelTrip(
        String tripId, String idempotencyKey, String xSourceSystem, CancelTripRequest b) {
      return ResponseEntity.ok(Dto.trip(trips.cancel(tripId, b.getReason(), xSourceSystem)));
    }

    @Override
    public ResponseEntity<DepartureFact> getDepartureFact(String departureId) {
      return ResponseEntity.ok(Dto.departure(trips.departure(departureId)));
    }

    @Override
    public ResponseEntity<Trip> runTripCommand(
        String tripId,
        String command,
        String idempotencyKey,
        String xSourceSystem,
        @Nullable Map<String, Object> body) {
      Caller c = Caller.internal(xSourceSystem);
      Map<String, Object> b = body == null ? Map.of() : body;
      Matcher m = STOP.matcher(command);
      Trip out;
      if (m.matches()) {
        int seq = Integer.parseInt(m.group(1));
        out =
            switch (m.group(2)) {
              case "arrivals" ->
                  cmd.arrive(c, tripId, seq, mapper.convertValue(b, EventInput.class));
              case "handovers" ->
                  cmd.handover(c, tripId, seq, mapper.convertValue(b, HandoverInput.class));
              default -> cmd.departStop(c, tripId, seq, mapper.convertValue(b, EventInput.class));
            };
      } else {
        out =
            switch (command) {
              case "check-ins" -> cmd.checkIn(c, tripId, mapper.convertValue(b, EventInput.class));
              case "loading-completions" ->
                  cmd.loading(c, tripId, mapper.convertValue(b, LoadingInput.class));
              case "departures" ->
                  cmd.depart(c, tripId, mapper.convertValue(b, DepartureInput.class));
              case "physical-ends" -> cmd.end(c, tripId, mapper.convertValue(b, EventInput.class));
              default ->
                  throw new PmartException(ErrorCode.VALIDATION_FAILED, "모르는 명령: " + command);
            };
      }
      return ResponseEntity.ok(out);
    }
  }
}
