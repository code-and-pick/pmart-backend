package com.pmart.cdc.location.api;

import com.pmart.cdc.location.api.generated.LocationsApi;
import com.pmart.cdc.location.api.generated.model.ChangeLocationStateRequest;
import com.pmart.cdc.location.api.generated.model.ComputePutawayCandidates200ResponseAsOf;
import com.pmart.cdc.location.api.generated.model.CreateLocationBatch200Response;
import com.pmart.cdc.location.api.generated.model.ListLocations200Response;
import com.pmart.cdc.location.api.generated.model.ListLocations200ResponsePage;
import com.pmart.cdc.location.api.generated.model.Location;
import com.pmart.cdc.location.api.generated.model.LocationAllOfParts;
import com.pmart.cdc.location.api.generated.model.LocationInput;
import com.pmart.cdc.location.api.generated.model.LocationInputCapacity;
import com.pmart.cdc.location.api.generated.model.LocationState;
import com.pmart.cdc.location.api.generated.model.LocationType;
import com.pmart.cdc.location.api.generated.model.TemperatureZone;
import com.pmart.cdc.location.application.LocationService;
import com.pmart.cdc.location.application.LocationService.Input;
import com.pmart.cdc.location.application.LocationService.StateChange;
import com.pmart.cdc.location.infrastructure.LocationStore.Filter;
import com.pmart.cdc.location.infrastructure.LocationStore.LocationRow;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 LocationsApi — 위치 마스터 등록·조회·상태 전환. 업무 규칙은 application/domain 에 있다. */
@RestController
public class LocationsController implements LocationsApi {

  private final LocationService service;

  public LocationsController(LocationService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<ListLocations200Response> listLocations(
      String centerId,
      @Nullable TemperatureZone temperatureZone,
      @Nullable String area,
      @Nullable String floor,
      @Nullable LocationType locationType,
      @Nullable LocationState state,
      Integer size,
      @Nullable String cursor) {
    var page =
        service.list(
            new Filter(
                centerId,
                temperatureZone == null ? null : temperatureZone.getValue(),
                area,
                floor,
                locationType == null ? null : locationType.getValue(),
                state == null ? null : state.getValue()),
            cursor,
            size == null ? 50 : size);
    var out = new ListLocations200Response();
    out.setItems(page.items().stream().map(LocationsController::dto).toList());
    var p = new ListLocations200ResponsePage();
    p.setNextCursor(page.nextCursor());
    p.setSize(page.items().size());
    out.setPage(p);
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<Location> createLocation(
      String idempotencyKey,
      String xSourceSystem,
      LocationInput body,
      @Nullable String traceparent) {
    LocationRow r = service.create(idempotencyKey, xSourceSystem, input(body)).value();
    return ResponseEntity.status(HttpStatus.CREATED).body(dto(r));
  }

  @Override
  public ResponseEntity<Location> getLocation(String locationCode) {
    return ResponseEntity.ok(dto(service.get(locationCode)));
  }

  @Override
  public ResponseEntity<CreateLocationBatch200Response> changeLocationState(
      String locationCode,
      String idempotencyKey,
      String xSourceSystem,
      ChangeLocationStateRequest body,
      @Nullable Integer xExpectedVersion) {
    var r =
        service
            .changeState(
                idempotencyKey,
                xSourceSystem,
                new StateChange(
                    locationCode,
                    body.getAction().getValue(),
                    body.getReason(),
                    body.getApprovalRef(),
                    xExpectedVersion))
            .value();
    var out = new CreateLocationBatch200Response();
    out.setStatus(CreateLocationBatch200Response.StatusEnum.APPLIED);
    out.setRequestId(r.requestId());
    out.setResultId(r.locationCode());
    out.setVersion(r.version());
    out.setOccurredAt(time(r.at()));
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(out);
  }

  static Input input(LocationInput b) {
    LocationInputCapacity c = b.getCapacity();
    return new Input(
        b.getLocationCode(),
        b.getCenterId(),
        b.getTemperatureZone().getValue(),
        b.getLocationType().getValue(),
        c == null || c.getUnit() == null ? null : c.getUnit().getValue(),
        c == null ? null : c.getMaxQty(),
        c == null ? null : c.getMaxWeightG(),
        b.getRestrictions() == null ? List.of() : List.copyOf(b.getRestrictions()),
        b.getPickFaceMinQty(),
        b.getPickFaceTargetQty());
  }

  static Location dto(LocationRow r) {
    var d = new Location();
    d.setLocationCode(r.locationCode());
    d.setCenterId(r.centerId());
    d.setTemperatureZone(TemperatureZone.fromValue(r.temperatureZone()));
    d.setLocationType(LocationType.fromValue(r.locationType()));
    var c = new LocationInputCapacity();
    c.setUnit(
        r.capacityUnit() == null
            ? null
            : LocationInputCapacity.UnitEnum.fromValue(r.capacityUnit()));
    c.setMaxQty(r.maxQty());
    c.setMaxWeightG(r.maxWeightG() == null ? null : Math.toIntExact(r.maxWeightG()));
    d.setCapacity(c);
    d.setRestrictions(r.restrictions());
    d.setPickFaceMinQty(r.pickFaceMinQty());
    d.setPickFaceTargetQty(r.pickFaceTargetQty());
    d.setState(LocationState.fromValue(r.state()));
    d.setVersion(r.version());
    var p = new LocationAllOfParts();
    p.setCenter(r.centerId());
    p.setArea(r.area());
    p.setFloor(r.floor());
    p.setAisle(r.aisle());
    p.setBay(r.bay());
    p.setLevel(r.level());
    p.setSlot(r.slot());
    d.setParts(p);
    // 점유는 cdc-inventory 정본 — 사본을 아직 받지 않아 모른다(0 으로 채우지 않음). 공간 예약은 아직 없다
    d.setOccupiedQty(null);
    d.setOpenReservedQty(0);
    d.setAvailableQty(null);
    var asOf = new ComputePutawayCandidates200ResponseAsOf();
    asOf.setAsOf(time(Instant.now()));
    asOf.setFreshness(ComputePutawayCandidates200ResponseAsOf.FreshnessEnum.UNAVAILABLE);
    d.setAsOf(asOf);
    d.setUpdatedAt(time(r.updatedAt()));
    return d;
  }

  static OffsetDateTime time(Instant i) {
    return i.atOffset(ZoneOffset.UTC);
  }
}
