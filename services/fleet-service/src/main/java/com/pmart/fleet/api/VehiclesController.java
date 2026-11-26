package com.pmart.fleet.api;

import com.pmart.fleet.api.generated.VehiclesApi;
import com.pmart.fleet.api.generated.model.ChangeVehicleRequest;
import com.pmart.fleet.api.generated.model.ContractType;
import com.pmart.fleet.api.generated.model.ListVehicles200Response;
import com.pmart.fleet.api.generated.model.TempClass;
import com.pmart.fleet.api.generated.model.Vehicle;
import com.pmart.fleet.api.generated.model.VehicleInput;
import com.pmart.fleet.application.FleetService;
import java.util.ArrayList;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 VehiclesApi — 변환만. 최대 R/T·중량을 비우면 5톤 기본값(20 R/T·5,000kg — 계약 default). */
@RestController
public class VehiclesController implements VehiclesApi {

  private final FleetService fleet;

  public VehiclesController(FleetService fleet) {
    this.fleet = fleet;
  }

  @Override
  public ResponseEntity<ListVehicles200Response> listVehicles(
      @Nullable String carrierId,
      @Nullable TempClass tempClass,
      @Nullable ContractType contractType,
      @Nullable Boolean active) {
    var items =
        fleet
            .vehicles(
                Dto.blank(carrierId),
                tempClass == null ? null : tempClass.getValue(),
                contractType == null ? null : contractType.getValue(),
                active)
            .stream()
            .map(Dto::vehicle)
            .toList();
    return ResponseEntity.ok(new ListVehicles200Response().items(new ArrayList<>(items)));
  }

  @Override
  public ResponseEntity<Vehicle> getVehicle(String vehicleId) {
    return ResponseEntity.ok(Dto.vehicle(fleet.vehicle(vehicleId)));
  }

  @Override
  public ResponseEntity<Vehicle> createVehicle(
      String idempotencyKey, String xSourceSystem, VehicleInput b) {
    var v =
        new FleetService.NewVehicle(
            b.getVehicleId(),
            b.getCarrierId(),
            Dto.blank(b.getPlateNo()),
            b.getContractType().getValue(),
            b.getTempClass().getValue(),
            b.getTonnage(),
            b.getMaxRt() == null ? 20 : b.getMaxRt(),
            b.getMaxWeightKg() == null ? 5000 : b.getMaxWeightKg());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(Dto.vehicle(fleet.createVehicle(idempotencyKey, xSourceSystem, v)));
  }

  @Override
  public ResponseEntity<Vehicle> changeVehicle(
      String vehicleId,
      String idempotencyKey,
      String xSourceSystem,
      ChangeVehicleRequest b,
      @Nullable Integer xExpectedVersion) {
    var c =
        new FleetService.VehicleChange(
            Dto.blank(b.getPlateNo()), b.getMaxRt(), b.getMaxWeightKg(), b.getActive());
    return ResponseEntity.ok(
        Dto.vehicle(
            fleet.changeVehicle(idempotencyKey, xSourceSystem, vehicleId, c, xExpectedVersion)));
  }
}
