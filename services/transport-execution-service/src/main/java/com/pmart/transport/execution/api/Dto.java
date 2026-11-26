package com.pmart.transport.execution.api;

import com.pmart.transport.execution.api.generated.model.DepartureFact;
import com.pmart.transport.execution.api.generated.model.DepartureFactTrfCompositionsInner;
import com.pmart.transport.execution.api.generated.model.EventInput;
import com.pmart.transport.execution.api.generated.model.IncidentType;
import com.pmart.transport.execution.api.generated.model.StopState;
import com.pmart.transport.execution.api.generated.model.TempClass;
import com.pmart.transport.execution.api.generated.model.Trip;
import com.pmart.transport.execution.api.generated.model.TripEventsInner;
import com.pmart.transport.execution.api.generated.model.TripIncidentsInner;
import com.pmart.transport.execution.api.generated.model.TripPage;
import com.pmart.transport.execution.api.generated.model.TripState;
import com.pmart.transport.execution.api.generated.model.TripStopsInner;
import com.pmart.transport.execution.api.generated.model.TripStopsInnerHandover;
import com.pmart.transport.execution.api.generated.model.TripTransfersInner;
import com.pmart.transport.execution.application.TripService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** 응답 변환. */
final class Dto {

  private Dto() {}

  static @Nullable OffsetDateTime time(@Nullable Instant i) {
    return i == null ? null : i.atOffset(ZoneOffset.UTC);
  }

  static TripService.EventIn event(
      @Nullable OffsetDateTime at, @Nullable String location, @Nullable BigDecimal temp) {
    return new TripService.EventIn(at == null ? null : at.toInstant(), location, temp);
  }

  static TripService.EventIn event(@Nullable EventInput e) {
    return e == null
        ? TripService.EventIn.none()
        : event(e.getAt(), e.getLocation(), e.getTemperatureC());
  }

  @SuppressWarnings("unchecked")
  static <T> List<T> list(@Nullable List<T> xs) {
    return xs == null ? List.of() : xs;
  }

  static Trip trip(TripService.TripView v) {
    return new Trip()
        .tripId(v.tripId())
        .cdcId(v.cdcId())
        .shipDate(v.shipDate())
        .routeId(v.routeId())
        .tempClass(TempClass.fromValue(v.tempClass()))
        .vehicleId(v.vehicleId())
        .plateNo(v.plateNo())
        .driverId(v.driverId())
        .driverName(v.driverName())
        .state(TripState.fromValue(v.state()))
        .delayed(v.delayed())
        .plannedLoadingStartAt(time(v.plannedLoadingStartAt()))
        .plannedDepartureAt(time(v.plannedDepartureAt()))
        .plannedReturnAt(time(v.plannedReturnAt()))
        .checkedInAt(time(v.checkedInAt()))
        .loadedAt(time(v.loadedAt()))
        .departedAt(time(v.departedAt()))
        .endedAt(time(v.endedAt()))
        .departureId(v.departureId())
        .outboundLinkage(
            v.outboundLinkage() == null
                ? null
                : Trip.OutboundLinkageEnum.fromValue(v.outboundLinkage()))
        .outboundResult(v.outboundResult())
        .trfCount(v.transfers().size())
        .boxCount(v.boxCount())
        .rtCount(v.rtCount())
        .stops(new ArrayList<>(v.stops().stream().map(Dto::stop).toList()))
        .transfers(
            new ArrayList<>(
                v.transfers().stream()
                    .map(
                        t ->
                            new TripTransfersInner()
                                .trfId(t.trfId())
                                .stopSeq(t.stopSeq())
                                .loaded(t.loaded())
                                .lpns(new ArrayList<>(t.lpns()))
                                .rtCount(t.rtCount()))
                    .toList()))
        .events(
            new ArrayList<>(
                v.events().stream()
                    .map(
                        e ->
                            new TripEventsInner()
                                .at(time(e.at()))
                                .type(e.type())
                                .stopSeq(e.stopSeq())
                                .location(e.location())
                                .temperatureC(e.temperatureC())
                                .actorId(e.actorId())
                                .source(e.source()))
                    .toList()))
        .incidents(
            new ArrayList<>(
                v.incidents().stream()
                    .map(
                        i ->
                            new TripIncidentsInner()
                                .incidentId(i.incidentId())
                                .type(IncidentType.fromValue(i.type()))
                                .stopSeq(i.stopSeq())
                                .state(TripIncidentsInner.StateEnum.fromValue(i.state()))
                                .description(i.description())
                                .resolution(i.resolution())
                                .raisedAt(time(i.raisedAt()))
                                .resolvedAt(time(i.resolvedAt())))
                    .toList()))
        .version(v.version());
  }

  static TripStopsInner stop(TripService.StopView s) {
    TripService.Handover h = s.handover();
    return new TripStopsInner()
        .seq(s.seq())
        .mfcId(s.mfcId())
        .state(StopState.fromValue(s.state()))
        .trfIds(new ArrayList<>(s.trfIds()))
        .plannedArrivalAt(time(s.plannedArrivalAt()))
        .estimatedArrivalAt(time(s.estimatedArrivalAt()))
        .arrivedAt(time(s.arrivedAt()))
        .handedOverAt(time(s.handedOverAt()))
        .departedAt(time(s.departedAt()))
        .delayed(s.delayed())
        .handover(
            h == null
                ? null
                : new TripStopsInnerHandover()
                    .handoverId(h.handoverId())
                    .result(TripStopsInnerHandover.ResultEnum.fromValue(h.result()))
                    .shippedBoxes(h.shippedBoxes())
                    .receivedBoxes(h.receivedBoxes())
                    .missingLpns(new ArrayList<>(h.missing()))
                    .extraLpns(new ArrayList<>(h.extra()))
                    .damagedLpns(new ArrayList<>(h.damaged()))
                    .receiverId(h.receiverId())
                    .receiverName(h.receiverName()));
  }

  static TripPage page(TripService.Page p) {
    return new TripPage()
        .items(new ArrayList<>(p.items().stream().map(Dto::trip).toList()))
        .page(p.page())
        .size(p.size())
        .hasNext(p.hasNext());
  }

  @SuppressWarnings("unchecked")
  static DepartureFact departure(TripService.DepartureFact f) {
    return new DepartureFact()
        .departureId(f.departureId())
        .tripId(f.tripId())
        .vehicleId(f.vehicleId())
        .driverId(f.driverId())
        .cdcId(f.cdcId())
        .departedAt(time(f.departedAt()))
        .source(DepartureFact.SourceEnum.fromValue(f.source()))
        .trfCompositions(
            new ArrayList<>(
                f.compositions().stream()
                    .map(
                        m ->
                            new DepartureFactTrfCompositionsInner()
                                .trfId((String) m.get("trfId"))
                                .mfcId((String) m.get("mfcId"))
                                .lpns(new ArrayList<>((List<String>) m.get("lpns"))))
                    .toList()));
  }
}
