package com.pmart.rider.api;

import com.pmart.rider.api.generated.MeApi;
import com.pmart.rider.api.generated.model.DispatchSettings;
import com.pmart.rider.api.generated.model.OperatingState;
import com.pmart.rider.api.generated.model.OperatingStateRequest;
import com.pmart.rider.api.generated.model.RiderProfile;
import com.pmart.rider.api.generated.model.RiderProfileUpdate;
import com.pmart.rider.application.RiderService;
import com.pmart.rider.domain.Rider;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 MeApi — 라이더 본인 프로필·운행 상태·배차 설정. 탈퇴는 아직 501(인터페이스 기본). */
@RestController
public class MeController implements MeApi {

  private final RiderService riders;

  public MeController(RiderService riders) {
    this.riders = riders;
  }

  @Override
  public ResponseEntity<RiderProfile> getMyRider() {
    return ResponseEntity.ok(RiderViews.profile(riders.me()));
  }

  @Override
  public ResponseEntity<RiderProfile> updateMyRider(
      String idempotencyKey,
      String xSourceSystem,
      RiderProfileUpdate body,
      @Nullable Integer xExpectedVersion) {
    var bank = body.getBankAccount();
    var residence = body.getResidence();
    Rider r =
        riders.updateProfile(
            idempotencyKey,
            xSourceSystem,
            xExpectedVersion,
            new RiderService.ProfileChange(
                body.getVehicleType() == null ? null : body.getVehicleType().getValue(),
                bank == null ? null : bank.getBankCode(),
                bank == null ? null : bank.getAccountNumber(),
                bank == null ? null : bank.getHolderName(),
                residence == null ? null : residence.getRoadAddress(),
                residence == null ? null : residence.getDetail(),
                body.getDeliveryAreas() == null
                    ? null
                    : body.getDeliveryAreas().stream()
                        .filter(a -> a.getMfcId() != null && !a.getMfcId().isBlank())
                        .map(
                            a ->
                                new Rider.Area(
                                    a.getMfcId(), a.getZoneId() == null ? "" : a.getZoneId()))
                        .distinct()
                        .toList()));
    return ResponseEntity.ok(RiderViews.profile(r));
  }

  @Override
  public ResponseEntity<OperatingState> getMyOperatingState() {
    return ResponseEntity.ok(RiderViews.operating(riders.me()));
  }

  @Override
  public ResponseEntity<OperatingState> setMyOperatingState(
      String idempotencyKey,
      String xSourceSystem,
      OperatingStateRequest body,
      @Nullable Integer xExpectedVersion) {
    Rider r =
        riders.setOperating(
            idempotencyKey,
            xSourceSystem,
            xExpectedVersion,
            new RiderService.StateChange(
                body.getStatus().getValue(),
                Boolean.TRUE.equals(body.getNewDispatchEnabled()),
                body.getMfcId()));
    return ResponseEntity.ok(RiderViews.operating(r));
  }

  @Override
  public ResponseEntity<DispatchSettings> getMyDispatchSettings() {
    return ResponseEntity.ok(RiderViews.settings(riders.me()));
  }

  @Override
  public ResponseEntity<DispatchSettings> setMyDispatchSettings(
      String idempotencyKey,
      String xSourceSystem,
      DispatchSettings body,
      @Nullable Integer xExpectedVersion) {
    Rider r =
        riders.setSettings(
            idempotencyKey,
            xSourceSystem,
            xExpectedVersion,
            new RiderService.SettingsChange(
                body.getOneAtATime(),
                body.getDispatchMode() == null ? null : body.getDispatchMode().getValue()));
    return ResponseEntity.ok(RiderViews.settings(r));
  }
}
