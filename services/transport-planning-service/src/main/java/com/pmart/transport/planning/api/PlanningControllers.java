package com.pmart.transport.planning.api;

import com.pmart.transport.planning.api.generated.InternalApi;
import com.pmart.transport.planning.api.generated.RoutesApi;
import com.pmart.transport.planning.api.generated.TransportOrdersApi;
import com.pmart.transport.planning.api.generated.TransportRequestsApi;
import com.pmart.transport.planning.api.generated.model.ConfirmTransportOrderRequest;
import com.pmart.transport.planning.api.generated.model.GenerateTransportOrders200Response;
import com.pmart.transport.planning.api.generated.model.GenerateTransportOrders200ResponseUnplannedReasonsInner;
import com.pmart.transport.planning.api.generated.model.GenerateTransportOrdersRequest;
import com.pmart.transport.planning.api.generated.model.ListRoutes200Response;
import com.pmart.transport.planning.api.generated.model.ListTransportOrders200Response;
import com.pmart.transport.planning.api.generated.model.ListTransportRequests200Response;
import com.pmart.transport.planning.api.generated.model.MoveTransportRequestRequest;
import com.pmart.transport.planning.api.generated.model.RecordTripUpdateRequest;
import com.pmart.transport.planning.api.generated.model.Route;
import com.pmart.transport.planning.api.generated.model.RouteInput;
import com.pmart.transport.planning.api.generated.model.SetRegularVehicleRequest;
import com.pmart.transport.planning.api.generated.model.SkipTransportOrderRequest;
import com.pmart.transport.planning.api.generated.model.TransportOrder;
import com.pmart.transport.planning.api.generated.model.TransportOrderState;
import com.pmart.transport.planning.api.generated.model.TransportRequest;
import com.pmart.transport.planning.api.generated.model.TransportRequestState;
import com.pmart.transport.planning.application.PlanningService;
import com.pmart.transport.planning.application.RouteService;
import com.pmart.transport.planning.domain.Schedule;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 RoutesApi·TransportRequestsApi·TransportOrdersApi·InternalApi — 변환만. */
final class PlanningControllers {

  private PlanningControllers() {}

  @RestController
  static class Routes implements RoutesApi {
    private final RouteService routes;

    Routes(RouteService routes) {
      this.routes = routes;
    }

    @Override
    public ResponseEntity<ListRoutes200Response> listRoutes(
        String cdcId, @Nullable Boolean active) {
      return ResponseEntity.ok(
          new ListRoutes200Response()
              .items(
                  new ArrayList<>(routes.list(cdcId, active).stream().map(Dto::route).toList())));
    }

    @Override
    public ResponseEntity<Route> getRoute(String cdcId, String routeId) {
      return ResponseEntity.ok(Dto.route(routes.get(cdcId, routeId)));
    }

    @Override
    public ResponseEntity<Route> createRoute(
        String cdcId, String idempotencyKey, String xSourceSystem, RouteInput b) {
      var n =
          new RouteService.NewRoute(
              b.getRouteId(),
              b.getName(),
              b.getDepartureTime(),
              b.getStops().stream()
                  .map(s -> new Schedule.Leg(s.getMfcId(), s.getLegMinutes()))
                  .toList(),
              b.getReturnMinutes());
      return ResponseEntity.status(HttpStatus.CREATED)
          .body(Dto.route(routes.create(idempotencyKey, xSourceSystem, cdcId, n)));
    }

    @Override
    public ResponseEntity<Route> setRegularVehicle(
        String cdcId,
        String routeId,
        String idempotencyKey,
        String xSourceSystem,
        SetRegularVehicleRequest b) {
      return ResponseEntity.ok(
          Dto.route(
              routes.setRegularVehicle(
                  idempotencyKey,
                  xSourceSystem,
                  cdcId,
                  routeId,
                  b.getTempClass().getValue(),
                  b.getVehicleId(),
                  b.getDriverId())));
    }
  }

  @RestController
  static class Requests implements TransportRequestsApi {
    private final PlanningService planning;

    Requests(PlanningService planning) {
      this.planning = planning;
    }

    @Override
    public ResponseEntity<ListTransportRequests200Response> listTransportRequests(
        String cdcId,
        @Nullable LocalDate shipDate,
        @Nullable TransportRequestState state,
        @Nullable String mfcId,
        Integer page,
        Integer size) {
      var p =
          planning.requests(
              cdcId,
              shipDate,
              state == null ? null : state.getValue(),
              Dto.blank(mfcId),
              page == null ? 0 : page,
              size == null ? 50 : size);
      return ResponseEntity.ok(
          new ListTransportRequests200Response()
              .items(new ArrayList<>(p.items().stream().map(Dto::request).toList()))
              .page(p.page())
              .size(p.size())
              .hasNext(p.hasNext()));
    }

    @Override
    public ResponseEntity<TransportRequest> getTransportRequest(String cdcId, String trfId) {
      return ResponseEntity.ok(Dto.request(planning.request(cdcId, trfId)));
    }

    @Override
    public ResponseEntity<TransportRequest> carryOverTransportRequest(
        String cdcId,
        String trfId,
        String idempotencyKey,
        String xSourceSystem,
        com.pmart.transport.planning.api.generated.model.CarryOverTransportRequestRequest b) {
      return ResponseEntity.ok(
          Dto.request(
              planning.carryOver(
                  xSourceSystem, cdcId, trfId, b.getToShipDate(), b.getReason().strip())));
    }
  }

  @RestController
  static class Orders implements TransportOrdersApi {
    private final PlanningService planning;

    Orders(PlanningService planning) {
      this.planning = planning;
    }

    @Override
    public ResponseEntity<GenerateTransportOrders200Response> generateTransportOrders(
        String cdcId,
        String idempotencyKey,
        String xSourceSystem,
        GenerateTransportOrdersRequest b) {
      var g = planning.generate(idempotencyKey, xSourceSystem, cdcId, b.getShipDate());
      return ResponseEntity.ok(
          new GenerateTransportOrders200Response()
              .shipDate(g.shipDate())
              .orders(new ArrayList<>(g.orders().stream().map(Dto::order).toList()))
              .requests(new LinkedHashMap<>(g.requests()))
              .unplannedReasons(
                  new ArrayList<>(
                      g.unplanned().stream()
                          .map(
                              u ->
                                  new GenerateTransportOrders200ResponseUnplannedReasonsInner()
                                      .routeId(u.routeId())
                                      .tempClass(Dto.temp(u.tempClass()))
                                      .reason(u.reason()))
                          .toList())));
    }

    @Override
    public ResponseEntity<ListTransportOrders200Response> listTransportOrders(
        String cdcId,
        @Nullable LocalDate shipDate,
        @Nullable TransportOrderState state,
        @Nullable String routeId,
        Integer page,
        Integer size) {
      var p =
          planning.orders(
              cdcId,
              shipDate,
              state == null ? null : state.getValue(),
              Dto.blank(routeId),
              page == null ? 0 : page,
              size == null ? 50 : size);
      return ResponseEntity.ok(
          new ListTransportOrders200Response()
              .items(new ArrayList<>(p.items().stream().map(Dto::order).toList()))
              .page(p.page())
              .size(p.size())
              .hasNext(p.hasNext()));
    }

    @Override
    public ResponseEntity<TransportOrder> createSpotTransportOrder(
        String cdcId,
        String idempotencyKey,
        String xSourceSystem,
        com.pmart.transport.planning.api.generated.model.CreateSpotTransportOrderRequest b) {
      return ResponseEntity.ok(
          Dto.order(
              planning.createSpot(
                  xSourceSystem,
                  cdcId,
                  b.getShipDate(),
                  b.getVehicleId(),
                  b.getDriverId(),
                  b.getTrfIds(),
                  b.getReason().strip())));
    }

    @Override
    public ResponseEntity<TransportOrder> getTransportOrder(String cdcId, String orderId) {
      return ResponseEntity.ok(Dto.order(planning.order(cdcId, orderId)));
    }

    @Override
    public ResponseEntity<TransportOrder> moveTransportRequest(
        String cdcId,
        String orderId,
        String idempotencyKey,
        String xSourceSystem,
        MoveTransportRequestRequest b,
        @Nullable Integer xExpectedVersion) {
      return ResponseEntity.ok(
          Dto.order(
              planning.move(
                  xSourceSystem,
                  cdcId,
                  orderId,
                  b.getTrfId(),
                  b.getToOrderId(),
                  Dto.blank(b.getReason()),
                  xExpectedVersion)));
    }

    @Override
    public ResponseEntity<TransportOrder> confirmTransportOrder(
        String cdcId,
        String orderId,
        String idempotencyKey,
        String xSourceSystem,
        @Nullable ConfirmTransportOrderRequest b,
        @Nullable Integer xExpectedVersion) {
      return ResponseEntity.ok(
          Dto.order(
              planning.confirm(
                  xSourceSystem,
                  cdcId,
                  orderId,
                  b == null ? null : Dto.blank(b.getVehicleId()),
                  b == null ? null : Dto.blank(b.getDriverId()),
                  xExpectedVersion)));
    }

    @Override
    public ResponseEntity<TransportOrder> skipTransportOrder(
        String cdcId,
        String orderId,
        String idempotencyKey,
        String xSourceSystem,
        SkipTransportOrderRequest b) {
      return ResponseEntity.ok(
          Dto.order(planning.skip(xSourceSystem, cdcId, orderId, b.getReason())));
    }

    @Override
    public ResponseEntity<TransportOrder> cancelTransportOrder(
        String cdcId,
        String orderId,
        String idempotencyKey,
        String xSourceSystem,
        SkipTransportOrderRequest b) {
      return ResponseEntity.ok(
          Dto.order(planning.cancel(xSourceSystem, cdcId, orderId, b.getReason())));
    }
  }

  @RestController
  static class Internal implements InternalApi {
    private final PlanningService planning;

    Internal(PlanningService planning) {
      this.planning = planning;
    }

    @Override
    public ResponseEntity<Void> recordTripUpdate(
        String orderId, String idempotencyKey, String xSourceSystem, RecordTripUpdateRequest b) {
      planning.tripUpdate(
          orderId,
          b.getTripState(),
          b.getTripVersion(),
          b.getAt().toInstant(),
          b.getHandedOverTrfIds() == null ? List.of() : b.getHandedOverTrfIds());
      return ResponseEntity.noContent().build();
    }
  }
}
