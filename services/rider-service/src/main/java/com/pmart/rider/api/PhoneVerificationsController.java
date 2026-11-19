package com.pmart.rider.api;

import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.rider.api.generated.PhoneVerificationsApi;
import com.pmart.rider.api.generated.model.RiderPhoneVerification;
import com.pmart.rider.api.generated.model.StartRiderPhoneVerification200Response;
import com.pmart.rider.api.generated.model.StartRiderPhoneVerificationRequest;
import com.pmart.rider.application.PhoneVerificationService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 라이더 휴대폰 번호 확인(2026-11-19) — 규칙은 {@link PhoneVerificationService}. */
@RestController
public class PhoneVerificationsController implements PhoneVerificationsApi {

  private final PhoneVerificationService verifications;

  public PhoneVerificationsController(PhoneVerificationService verifications) {
    this.verifications = verifications;
  }

  private static String rider() {
    return PmartPrincipal.current()
        .filter(p -> p.type() == PmartPrincipal.PrincipalType.RIDER)
        .orElseThrow(
            () ->
                new PmartException(
                    ErrorCode.FORBIDDEN.name(),
                    HttpStatus.FORBIDDEN,
                    "라이더만 쓸 수 있습니다.",
                    false,
                    List.of()))
        .subject();
  }

  @Override
  public ResponseEntity<StartRiderPhoneVerification200Response> startRiderPhoneVerification(
      String idempotencyKey, String xSourceSystem, StartRiderPhoneVerificationRequest body) {
    var s = verifications.start(rider(), idempotencyKey, xSourceSystem, body.getPhone());
    return ResponseEntity.ok(
        new StartRiderPhoneVerification200Response()
            .verificationId(s.verificationId())
            .sendTo(s.sendTo())
            .text(s.text())
            .expiresAt(OffsetDateTime.parse(s.expiresAt().replace("Z", "+00:00")))
            .phoneMasked(s.phoneMasked())
            .maxAttempts(s.maxAttempts()));
  }

  @Override
  public ResponseEntity<RiderPhoneVerification> getRiderPhoneVerification(String verificationId) {
    return ResponseEntity.ok(view(verifications.get(rider(), verificationId)));
  }

  @Override
  public ResponseEntity<RiderPhoneVerification> confirmRiderPhoneVerification(
      String verificationId,
      String idempotencyKey,
      String xSourceSystem,
      StartRiderPhoneVerificationRequest body) {
    return ResponseEntity.ok(view(verifications.confirm(rider(), verificationId, body.getPhone())));
  }

  private static RiderPhoneVerification view(PhoneVerificationService.View v) {
    return new RiderPhoneVerification()
        .verificationId(v.verificationId())
        .state(RiderPhoneVerification.StateEnum.fromValue(v.state()))
        .failureReason(
            v.failureReason() == null
                ? null
                : RiderPhoneVerification.FailureReasonEnum.fromValue(v.failureReason()))
        .remainingAttempts(v.remainingAttempts())
        .expiresAt(v.expiresAt().atOffset(ZoneOffset.UTC))
        .verifiedAt(v.verifiedAt() == null ? null : v.verifiedAt().atOffset(ZoneOffset.UTC));
  }
}
