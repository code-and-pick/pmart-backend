package com.pmart.fleet.api;

import com.pmart.fleet.api.generated.CarriersApi;
import com.pmart.fleet.api.generated.model.Carrier;
import com.pmart.fleet.api.generated.model.CarrierInput;
import com.pmart.fleet.api.generated.model.ListCarriers200Response;
import com.pmart.fleet.application.FleetService;
import java.util.ArrayList;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 CarriersApi — 변환만, 규칙은 {@link FleetService}. */
@RestController
public class CarriersController implements CarriersApi {

  private final FleetService fleet;

  public CarriersController(FleetService fleet) {
    this.fleet = fleet;
  }

  @Override
  public ResponseEntity<ListCarriers200Response> listCarriers() {
    return ResponseEntity.ok(
        new ListCarriers200Response()
            .items(new ArrayList<>(fleet.carriers().stream().map(Dto::carrier).toList())));
  }

  @Override
  public ResponseEntity<Carrier> createCarrier(
      String idempotencyKey, String xSourceSystem, CarrierInput body) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            Dto.carrier(
                fleet.createCarrier(
                    idempotencyKey, xSourceSystem, body.getCarrierId(), body.getName())));
  }
}
