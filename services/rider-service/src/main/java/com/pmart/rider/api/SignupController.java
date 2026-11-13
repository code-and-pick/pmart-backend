package com.pmart.rider.api;

import com.pmart.rider.api.generated.SignupApi;
import com.pmart.rider.api.generated.model.GetRiderSignup200Response;
import com.pmart.rider.api.generated.model.RiderProfile;
import com.pmart.rider.api.generated.model.RiderSignupRequest;
import com.pmart.rider.application.RiderService;
import com.pmart.rider.domain.Rider;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 SignupApi — 라이더 가입(RS-2). 규칙은 {@link RiderService}. */
@RestController
public class SignupController implements SignupApi {

  private final RiderService riders;

  public SignupController(RiderService riders) {
    this.riders = riders;
  }

  @Override
  public ResponseEntity<RiderProfile> createRiderSignup(
      String idempotencyKey,
      String xSourceSystem,
      RiderSignupRequest body,
      @Nullable String traceparent) {
    var bank = body.getBankAccount();
    var residence = body.getResidence();
    Rider r =
        riders.signup(
            idempotencyKey,
            xSourceSystem,
            new RiderService.Signup(
                body.getName(),
                body.getPhone(),
                body.getVehicleType().getValue(),
                bank == null ? null : bank.getBankCode(),
                bank == null ? null : bank.getAccountNumber(),
                bank == null ? null : bank.getHolderName(),
                residence == null ? null : residence.getRoadAddress(),
                residence == null ? null : residence.getDetail(),
                body.getDeliveryAreas().stream()
                    .filter(a -> a.getMfcId() != null && !a.getMfcId().isBlank())
                    .map(
                        a ->
                            new Rider.Area(
                                a.getMfcId(), a.getZoneId() == null ? "" : a.getZoneId()))
                    .distinct()
                    .toList(),
                body.getConsents() == null
                    ? List.of()
                    : body.getConsents().stream()
                        .filter(c -> c.getConsentType() != null && c.getVersion() != null)
                        .map(
                            c ->
                                Map.of(
                                    "consentType", c.getConsentType(), "version", c.getVersion()))
                        .toList()));
    return ResponseEntity.status(HttpStatus.CREATED).body(RiderViews.profile(r));
  }

  @Override
  public ResponseEntity<GetRiderSignup200Response> getRiderSignup(String requestId) {
    Rider r = riders.signupResult(requestId);
    return ResponseEntity.ok(
        new GetRiderSignup200Response()
            .requestId(requestId)
            .state(GetRiderSignup200Response.StateEnum.CREATED)
            .riderId(r.riderId()));
  }
}
