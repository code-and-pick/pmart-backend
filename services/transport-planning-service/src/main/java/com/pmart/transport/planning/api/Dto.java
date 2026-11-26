package com.pmart.transport.planning.api;

import com.pmart.transport.planning.api.generated.model.Route;
import com.pmart.transport.planning.api.generated.model.RouteRegularVehiclesInner;
import com.pmart.transport.planning.api.generated.model.RouteStopsInner;
import com.pmart.transport.planning.api.generated.model.TempClass;
import com.pmart.transport.planning.api.generated.model.TransportOrder;
import com.pmart.transport.planning.api.generated.model.TransportOrderHistoryInner;
import com.pmart.transport.planning.api.generated.model.TransportOrderState;
import com.pmart.transport.planning.api.generated.model.TransportOrderStopsInner;
import com.pmart.transport.planning.api.generated.model.TransportRequest;
import com.pmart.transport.planning.api.generated.model.TransportRequestState;
import com.pmart.transport.planning.application.PlanningService.OrderView;
import com.pmart.transport.planning.application.PlanningService.RequestView;
import com.pmart.transport.planning.application.RouteService.RouteView;
import com.pmart.transport.planning.domain.Schedule;
import java.math.BigDecimal;
import java.math.RoundingMode;
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

  static @Nullable String blank(@Nullable String v) {
    return v == null || v.isBlank() ? null : v.strip();
  }

  static @Nullable TempClass temp(@Nullable String t) {
    return t == null ? null : TempClass.fromValue(t);
  }

  static Route route(RouteView r) {
    return new Route()
        .routeId(r.routeId())
        .cdcId(r.cdcId())
        .name(r.name())
        .departureTime(r.departureTime())
        .returnMinutes(r.returnMinutes())
        .active(r.active())
        .version(r.version())
        .stops(
            new ArrayList<>(
                r.stops().stream()
                    .map(
                        s ->
                            new RouteStopsInner()
                                .seq(s.seq())
                                .mfcId(s.mfcId())
                                .legMinutes(s.legMinutes())
                                .plannedArrivalOffsetMinutes(s.arrivalOffsetMinutes()))
                    .toList()))
        .regularVehicles(
            new ArrayList<>(
                r.vehicles().stream()
                    .map(
                        v ->
                            new RouteRegularVehiclesInner()
                                .tempClass(temp(v.tempClass()))
                                .vehicleId(v.vehicleId())
                                .driverId(v.driverId())
                                .maxRt(v.maxRt())
                                .maxWeightKg(v.maxWeightKg()))
                    .toList()));
  }

  static TransportRequest request(RequestView r) {
    return new TransportRequest()
        .trfId(r.trfId())
        .trfNumber(r.trfNumber())
        .trfVersion(r.trfVersion())
        .cdcId(r.cdcId())
        .mfcId(r.mfcId())
        .shipDate(r.shipDate())
        .dueAt(time(r.dueAt()))
        .tempClass(temp(r.tempClass()))
        .tempClasses(new ArrayList<>(r.tempClasses().stream().map(TempClass::fromValue).toList()))
        .allocationState(r.allocationState())
        .approvedQty(r.approvedQty())
        .acceptedQty(r.acceptedQty())
        .boxCount(r.boxCount())
        .rtCount(r.rtCount())
        .weightKg(r.weightKg())
        .state(TransportRequestState.fromValue(r.state()))
        .reason(r.reason())
        .lateReceipt(r.lateReceipt())
        .orderId(r.orderId())
        .receivedAt(time(r.receivedAt()))
        .updatedAt(time(r.updatedAt()));
  }

  static TransportOrder order(OrderView o) {
    List<TransportOrder.FlagsEnum> flags =
        o.overCapacity() ? List.of(TransportOrder.FlagsEnum.OVER_CAPACITY) : List.of();
    return new TransportOrder()
        .orderId(o.orderId())
        .cdcId(o.cdcId())
        .shipDate(o.shipDate())
        .arrivalDate(
            o.plannedDepartureAt().plusSeconds(6 * 3600).atZone(Schedule.SEOUL).toLocalDate())
        .routeId(o.routeId())
        .tempClass(TempClass.fromValue(o.tempClass()))
        .contractType(TransportOrder.ContractTypeEnum.fromValue(o.contractType()))
        .vehicleId(o.vehicleId())
        .driverId(o.driverId())
        .maxRt(o.maxRt())
        .maxWeightKg(o.maxWeightKg())
        .loadRt(o.loadRt())
        .loadWeightKg(o.loadWeightKg())
        .loadRatio(
            o.maxRt() == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(o.loadRt())
                    .divide(BigDecimal.valueOf(o.maxRt()), 3, RoundingMode.HALF_UP))
        .plannedLoadingStartAt(time(o.plannedLoadingStartAt()))
        .plannedDepartureAt(time(o.plannedDepartureAt()))
        .plannedReturnAt(time(o.plannedReturnAt()))
        .stops(
            new ArrayList<>(
                o.stops().stream()
                    .map(
                        s ->
                            new TransportOrderStopsInner()
                                .seq(s.seq())
                                .mfcId(s.mfcId())
                                .plannedArrivalAt(time(s.plannedArrivalAt()))
                                .plannedDepartureAt(time(s.plannedDepartureAt()))
                                .trfIds(new ArrayList<>(s.trfIds()))
                                .boxCount(s.boxCount())
                                .rtCount(s.rtCount()))
                    .toList()))
        .trfIds(new ArrayList<>(o.trfIds()))
        .cancelCandidate(o.cancelCandidate())
        .flags(new ArrayList<>(flags))
        .state(TransportOrderState.fromValue(o.state()))
        .reservationId(o.reservationId())
        .dockCode(o.dockCode())
        .dockReservationId(o.dockReservationId())
        .tripState(o.tripState())
        .reason(o.reason())
        .version(o.version())
        .history(
            new ArrayList<>(
                o.history().stream()
                    .map(
                        h ->
                            new TransportOrderHistoryInner()
                                .at(time(h.at()))
                                .action(h.action())
                                .actorId(h.actorId())
                                .detail(h.detail())
                                .version(h.version()))
                    .toList()))
        .createdAt(time(o.createdAt()))
        .updatedAt(time(o.updatedAt()));
  }
}
