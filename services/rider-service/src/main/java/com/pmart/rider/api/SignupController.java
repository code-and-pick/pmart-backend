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

  /** 휴대폰 번호 중복 확인(라이더 앱 요청 §14, 2026-11-17). */
  @Override
  public ResponseEntity<com.pmart.rider.api.generated.model.AvailabilityResult> checkRiderPhone(
      com.pmart.rider.api.generated.model.CheckRiderPhoneRequest body) {
    return ResponseEntity.ok(
        new com.pmart.rider.api.generated.model.AvailabilityResult()
            .available(riders.phoneAvailable(body.getPhone())));
  }

  /** 정산 계좌 중복 확인(라이더 앱 요청 §15, 2026-11-17). */
  @Override
  public ResponseEntity<com.pmart.rider.api.generated.model.AvailabilityResult> checkRiderAccount(
      com.pmart.rider.api.generated.model.CheckRiderAccountRequest body) {
    return ResponseEntity.ok(
        new com.pmart.rider.api.generated.model.AvailabilityResult()
            .available(riders.accountAvailable(body.getBankCode(), body.getAccountNumber())));
  }

  /** 정산 은행 목록(금융결제원 코드, 라이더 앱 요청 §16-1, 2026-11-17). */
  @Override
  public ResponseEntity<com.pmart.rider.api.generated.model.ListBankCodes200Response>
      listBankCodes() {
    return ResponseEntity.ok(
        new com.pmart.rider.api.generated.model.ListBankCodes200Response()
            .items(
                com.pmart.rider.domain.BankCodes.ALL.entrySet().stream()
                    .map(
                        e ->
                            new com.pmart.rider.api.generated.model
                                    .ListBankCodes200ResponseItemsInner()
                                .code(e.getKey())
                                .name(e.getValue()))
                    .toList()));
  }

  /** 운전면허 번호 중복 확인(라이더 앱 요청 §7, 2026-11-16). */
  @Override
  public ResponseEntity<com.pmart.rider.api.generated.model.CheckRiderLicense200Response>
      checkRiderLicense(com.pmart.rider.api.generated.model.CheckRiderLicenseRequest body) {
    return ResponseEntity.ok(
        new com.pmart.rider.api.generated.model.CheckRiderLicense200Response()
            .available(riders.licenseAvailable(body.getLicenseNumber())));
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
                        .toList(),
                body.getLicenseNumber()));
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
