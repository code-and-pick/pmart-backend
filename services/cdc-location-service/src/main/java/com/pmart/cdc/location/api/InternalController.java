package com.pmart.cdc.location.api;

import com.pmart.cdc.location.api.generated.InternalApi;
import com.pmart.cdc.location.api.generated.model.CreateLocationBatch200Response;
import com.pmart.cdc.location.api.generated.model.CreateLocationBatchRequest;
import com.pmart.cdc.location.api.generated.model.LocationState;
import com.pmart.cdc.location.api.generated.model.LocationType;
import com.pmart.cdc.location.api.generated.model.LookupLocations200Response;
import com.pmart.cdc.location.api.generated.model.LookupLocations200ResponseItemsInner;
import com.pmart.cdc.location.api.generated.model.LookupLocationsRequest;
import com.pmart.cdc.location.api.generated.model.PickMethod;
import com.pmart.cdc.location.api.generated.model.TemperatureZone;
import com.pmart.cdc.location.application.LocationService;
import com.pmart.cdc.location.application.LocationService.Batch;
import com.pmart.cdc.location.application.PickMethodService;
import com.pmart.cdc.location.infrastructure.LocationStore.LocationRow;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 InternalApi — 위치 일괄 등록(0.2.0)·일괄 조회(0.4.0, 0.5.0 부터 통로 피킹 방식 포함). 적치 후보·공간 예약·도크 예약은 아직 인터페이스
 * default(501).
 */
@RestController
public class InternalController implements InternalApi {

  private final LocationService service;
  private final PickMethodService pickMethods;
  private final com.pmart.cdc.location.application.SpaceService space;
  private final com.pmart.cdc.location.application.DockService docks;

  public InternalController(
      LocationService service,
      PickMethodService pickMethods,
      com.pmart.cdc.location.application.SpaceService space,
      com.pmart.cdc.location.application.DockService docks) {
    this.docks = docks;
    this.space = space;
    this.service = service;
    this.pickMethods = pickMethods;
  }

  @Override
  public ResponseEntity<CreateLocationBatch200Response> createLocationBatch(
      String idempotencyKey,
      String xSourceSystem,
      CreateLocationBatchRequest body,
      @Nullable String traceparent) {
    var r =
        service
            .createBatch(
                idempotencyKey,
                xSourceSystem,
                new Batch(
                    body.getCenterId(),
                    body.getApprovalRef(),
                    Boolean.TRUE.equals(body.getActivate()),
                    body.getItems().stream().map(LocationsController::input).toList()))
            .value();
    var out = new CreateLocationBatch200Response();
    out.setStatus(CreateLocationBatch200Response.StatusEnum.APPLIED);
    out.setRequestId(r.requestId());
    out.setResultId(r.approvalRef());
    out.setAppliedQty(r.created());
    out.setOccurredAt(LocationsController.time(r.at()));
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<LookupLocations200Response> lookupLocations(
      String idempotencyKey, String xSourceSystem, LookupLocationsRequest body) {
    List<LocationRow> rows = service.lookup(body.getCenterId(), body.getLocationCodes());
    var methods = pickMethods.ofLocations(body.getCenterId(), body.getLocationCodes());
    Set<String> found = new HashSet<>();
    var out = new LookupLocations200Response();
    out.setItems(
        rows.stream()
            .map(
                r -> {
                  found.add(r.locationCode());
                  var x = new LookupLocations200ResponseItemsInner();
                  x.setLocationCode(r.locationCode());
                  x.setLocationType(LocationType.fromValue(r.locationType()));
                  x.setState(LocationState.fromValue(r.state()));
                  x.setTemperatureZone(TemperatureZone.fromValue(r.temperatureZone()));
                  String m = methods.get(r.locationCode());
                  x.setPickMethod(m == null ? null : PickMethod.fromValue(m));
                  return x;
                })
            .toList());
    out.setMissing(
        body.getLocationCodes().stream().filter(c -> !found.contains(c)).distinct().toList());
    return ResponseEntity.ok(out);
  }

  // ---------- 0.7.0 공간 예약·피킹 선반 설정 ----------

  @Override
  public ResponseEntity<com.pmart.cdc.location.api.generated.model.SpaceReservation>
      createSpaceReservation(
          String idempotencyKey,
          String xSourceSystem,
          com.pmart.cdc.location.api.generated.model.CreateSpaceReservationRequest body) {
    var v =
        space.reserve(
            idempotencyKey,
            xSourceSystem,
            new com.pmart.cdc.location.application.SpaceService.Reserve(
                body.getCenterId(),
                body.getSourceRef(),
                body.getExpiresAt() == null ? null : body.getExpiresAt().toInstant(),
                body.getLines().stream()
                    .map(
                        l ->
                            new com.pmart.cdc.location.application.SpaceService.ReserveLine(
                                l.getLocationCode(),
                                l.getSku(),
                                l.getLot(),
                                l.getQty(),
                                l.getOccupiedQty()))
                    .toList()));
    return ResponseEntity.ok(reservation(v));
  }

  @Override
  public ResponseEntity<com.pmart.cdc.location.api.generated.model.SpaceReservation>
      getSpaceReservation(String reservationId) {
    return ResponseEntity.ok(reservation(space.get(reservationId)));
  }

  @Override
  public ResponseEntity<com.pmart.cdc.location.api.generated.model.SpaceReservation>
      releaseSpaceReservation(
          String reservationId,
          String idempotencyKey,
          String xSourceSystem,
          com.pmart.cdc.location.api.generated.model.ReleaseSpaceReservationRequest body) {
    var v =
        space.release(
            idempotencyKey,
            xSourceSystem,
            reservationId,
            body.getAction().getValue(),
            body.getCommitId(),
            body.getLines() == null
                ? List.of()
                : body.getLines().stream()
                    .map(
                        l ->
                            new com.pmart.cdc.location.application.SpaceService.ReleaseLine(
                                l.getLocationCode(), l.getQty()))
                    .toList());
    return ResponseEntity.ok(reservation(v));
  }

  @Override
  public ResponseEntity<com.pmart.cdc.location.api.generated.model.SetPickFaceSettings200Response>
      setPickFaceSettings(
          String idempotencyKey,
          String xSourceSystem,
          com.pmart.cdc.location.api.generated.model.SetPickFaceSettingsRequest body) {
    int n =
        space.setPickFaces(
            idempotencyKey,
            xSourceSystem,
            body.getCenterId(),
            body.getItems().stream()
                .map(
                    i ->
                        new com.pmart.cdc.location.application.SpaceService.Setting(
                            i.getLocationCode(), i.getPickFaceMinQty(), i.getPickFaceTargetQty()))
                .toList());
    var out = new com.pmart.cdc.location.api.generated.model.SetPickFaceSettings200Response();
    out.setSetCount(n);
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<com.pmart.cdc.location.api.generated.model.ListPickFaces200Response>
      listPickFaces(@Nullable String centerId, @Nullable String after, @Nullable Integer limit) {
    int size = limit == null ? 5000 : Math.max(1, Math.min(5000, limit));
    var rows = space.pickFaces(centerId, after == null || after.isBlank() ? null : after, size + 1);
    boolean cut = rows.size() > size;
    var page = cut ? rows.subList(0, size) : rows;
    var out = new com.pmart.cdc.location.api.generated.model.ListPickFaces200Response();
    out.setTruncated(cut);
    out.setNextCursor(cut ? page.get(page.size() - 1).locationCode() : null);
    out.setItems(
        page.stream()
            .map(
                r ->
                    new com.pmart.cdc.location.api.generated.model
                            .ListPickFaces200ResponseItemsInner()
                        .centerId(r.centerId())
                        .locationCode(r.locationCode())
                        .temperatureZone(r.temperatureZone())
                        .maxQty(r.maxQty())
                        .pickFaceMinQty(r.minQty())
                        .pickFaceTargetQty(r.targetQty()))
            .toList());
    return ResponseEntity.ok(out);
  }

  static com.pmart.cdc.location.api.generated.model.SpaceReservation reservation(
      com.pmart.cdc.location.application.SpaceService.View v) {
    var r = new com.pmart.cdc.location.api.generated.model.SpaceReservation();
    r.setReservationId(v.reservationId());
    r.setCenterId(v.centerId());
    r.setSourceRef(v.sourceRef());
    r.setState(
        com.pmart.cdc.location.api.generated.model.SpaceReservation.StateEnum.fromValue(v.state()));
    r.setVersion(v.version());
    r.setExpiresAt(v.expiresAt() == null ? null : LocationsController.time(v.expiresAt()));
    r.setLines(
        v.lines().stream()
            .map(
                l ->
                    new com.pmart.cdc.location.api.generated.model.SpaceReservationLinesInner()
                        .locationCode(l.locationCode())
                        .sku(l.sku())
                        .lot(l.lot())
                        .reservedQty(l.reservedQty())
                        .consumedQty(l.consumedQty())
                        .releasedQty(l.releasedQty()))
            .toList());
    return r;
  }

  // ---------- 0.8.0 도크 예약 ----------

  @Override
  public ResponseEntity<com.pmart.cdc.location.api.generated.model.CreateDockReservation200Response>
      createDockReservation(
          String idempotencyKey,
          String xSourceSystem,
          com.pmart.cdc.location.api.generated.model.CreateDockReservationRequest b,
          @Nullable String traceparent) {
    var v =
        docks.reserve(
            idempotencyKey,
            xSourceSystem,
            new com.pmart.cdc.location.application.DockService.Reserve(
                b.getCenterId(),
                b.getTripId(),
                b.getPurpose().getValue(),
                b.getTemperatureZone() == null ? null : b.getTemperatureZone().getValue(),
                b.getVehicleId(),
                b.getRouteId(),
                b.getPlateNo(),
                b.getWindow().getStart().toInstant(),
                b.getWindow().getEnd().toInstant(),
                b.getPreferredDockCode()));
    var d = DocksController.dto(v);
    var out =
        new com.pmart.cdc.location.api.generated.model.CreateDockReservation200Response()
            .status(
                com.pmart.cdc.location.api.generated.model.CreateDockReservation200Response
                    .StatusEnum.APPLIED)
            .requestId(idempotencyKey)
            .resultId(v.dockReservationId())
            .occurredAt(DocksController.time(v.createdAt()))
            .dockReservationId(d.getDockReservationId())
            .centerId(d.getCenterId())
            .dockCode(d.getDockCode())
            .tripId(d.getTripId())
            .purpose(
                com.pmart.cdc.location.api.generated.model.CreateDockReservation200Response
                    .PurposeEnum.fromValue(v.purpose()))
            .state(
                com.pmart.cdc.location.api.generated.model.CreateDockReservation200Response
                    .StateEnum.fromValue(v.state()))
            .window(d.getWindow())
            .version(d.getVersion())
            .floor(d.getFloor())
            .temperatureZone(d.getTemperatureZone())
            .vehicleId(d.getVehicleId())
            .routeId(d.getRouteId())
            .plateNo(d.getPlateNo())
            .releaseReason(d.getReleaseReason())
            .createdAt(d.getCreatedAt());
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<com.pmart.cdc.location.api.generated.model.DockReservation>
      getDockReservation(String reservationId) {
    return ResponseEntity.ok(DocksController.dto(docks.get(reservationId)));
  }

  @Override
  public ResponseEntity<com.pmart.cdc.location.api.generated.model.DockReservation>
      releaseDockReservation(
          String reservationId,
          String idempotencyKey,
          String xSourceSystem,
          com.pmart.cdc.location.api.generated.model.ReleaseDockReservationRequest b) {
    return ResponseEntity.ok(
        DocksController.dto(
            docks.release(idempotencyKey, xSourceSystem, reservationId, b.getReason().getValue())));
  }
}
