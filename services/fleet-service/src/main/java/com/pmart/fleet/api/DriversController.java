package com.pmart.fleet.api;

import com.pmart.fleet.api.generated.DriversApi;
import com.pmart.fleet.api.generated.model.Driver;
import com.pmart.fleet.api.generated.model.DriverInput;
import com.pmart.fleet.api.generated.model.ListDrivers200Response;
import com.pmart.fleet.application.FleetService;
import java.util.ArrayList;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 DriversApi — 변환만. 기사는 표시 이름만 둔다(개인정보 없음). */
@RestController
public class DriversController implements DriversApi {

  private final FleetService fleet;

  public DriversController(FleetService fleet) {
    this.fleet = fleet;
  }

  @Override
  public ResponseEntity<ListDrivers200Response> listDrivers(
      @Nullable String carrierId, @Nullable Boolean active) {
    var items = fleet.drivers(Dto.blank(carrierId), active).stream().map(Dto::driver).toList();
    return ResponseEntity.ok(new ListDrivers200Response().items(new ArrayList<>(items)));
  }

  @Override
  public ResponseEntity<Driver> getDriver(String driverId) {
    return ResponseEntity.ok(Dto.driver(fleet.driver(driverId)));
  }

  @Override
  public ResponseEntity<Driver> createDriver(
      String idempotencyKey, String xSourceSystem, DriverInput b) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            Dto.driver(
                fleet.createDriver(
                    idempotencyKey,
                    xSourceSystem,
                    b.getDriverId(),
                    b.getCarrierId(),
                    b.getDisplayName())));
  }
}
