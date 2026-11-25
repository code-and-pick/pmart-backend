package com.pmart.cdc.location.api;

import com.pmart.cdc.location.api.generated.InternalApi;
import com.pmart.cdc.location.api.generated.model.CreateLocationBatch200Response;
import com.pmart.cdc.location.api.generated.model.CreateLocationBatchRequest;
import com.pmart.cdc.location.api.generated.model.LocationState;
import com.pmart.cdc.location.api.generated.model.LocationType;
import com.pmart.cdc.location.api.generated.model.LookupLocations200Response;
import com.pmart.cdc.location.api.generated.model.LookupLocations200ResponseItemsInner;
import com.pmart.cdc.location.api.generated.model.LookupLocationsRequest;
import com.pmart.cdc.location.api.generated.model.TemperatureZone;
import com.pmart.cdc.location.application.LocationService;
import com.pmart.cdc.location.application.LocationService.Batch;
import com.pmart.cdc.location.infrastructure.LocationStore.LocationRow;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 InternalApi — 위치 일괄 등록(0.2.0)·일괄 조회(0.4.0). 적치 후보·공간 예약·도크 예약은 아직 인터페이스 default(501). */
@RestController
public class InternalController implements InternalApi {

  private final LocationService service;

  public InternalController(LocationService service) {
    this.service = service;
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
                  return x;
                })
            .toList());
    out.setMissing(
        body.getLocationCodes().stream().filter(c -> !found.contains(c)).distinct().toList());
    return ResponseEntity.ok(out);
  }
}
