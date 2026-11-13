package com.pmart.rider.api;

import com.pmart.rider.api.generated.model.AccountRestriction;
import com.pmart.rider.api.generated.model.DispatchSettings;
import com.pmart.rider.api.generated.model.EligibleRider;
import com.pmart.rider.api.generated.model.OperatingState;
import com.pmart.rider.api.generated.model.OperatingStatus;
import com.pmart.rider.api.generated.model.RiderProfile;
import com.pmart.rider.api.generated.model.RiderProfileRestriction;
import com.pmart.rider.api.generated.model.RiderProfileUpdateDeliveryAreasInner;
import com.pmart.rider.api.generated.model.RiderSignupRequestResidence;
import com.pmart.rider.api.generated.model.VehicleType;
import com.pmart.rider.domain.Rider;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;

/** 도메인 → 계약 응답. 전화·계좌는 가린다. */
final class RiderViews {

  private RiderViews() {}

  static RiderProfile profile(Rider r) {
    return new RiderProfile()
        .riderId(r.riderId())
        .version(r.version())
        .name(r.name())
        .phoneMasked(maskPhone(r.phone()))
        .vehicleType(VehicleType.fromValue(r.vehicleType()))
        .bankAccountMasked(maskAccount(r.bankCode(), r.bankAccountNumber()))
        .residence(
            r.residenceRoad() == null && r.residenceDetail() == null
                ? null
                : new RiderSignupRequestResidence()
                    .roadAddress(r.residenceRoad())
                    .detail(r.residenceDetail()))
        .deliveryAreas(
            r.deliveryAreas().stream()
                .map(
                    a ->
                        new RiderProfileUpdateDeliveryAreasInner()
                            .mfcId(a.mfcId())
                            .zoneId(a.zoneId().isEmpty() ? null : a.zoneId()))
                .toList())
        .operating(operating(r))
        .settings(settings(r))
        .restriction(
            new RiderProfileRestriction()
                .state(AccountRestriction.fromValue(r.restriction().name()))
                .reason(r.restrictionReason())
                .until(time(r.restrictionUntil())))
        .lastHeartbeatAt(time(r.lastHeartbeatAt()))
        .joinedAt(time(r.joinedAt()));
  }

  static OperatingState operating(Rider r) {
    return new OperatingState()
        .status(OperatingStatus.fromValue(r.operatingStatus().name()))
        .newDispatchEnabled(r.newDispatchEnabled())
        .mfcId(r.operatingMfcId())
        .changedAt(time(r.stateChangedAt()))
        .version(r.version());
  }

  static DispatchSettings settings(Rider r) {
    return new DispatchSettings()
        .oneAtATime(r.oneAtATime())
        .dispatchMode(DispatchSettings.DispatchModeEnum.fromValue(r.dispatchMode().name()))
        .version(r.version());
  }

  /** 개인정보 없이(계약). 용량·주류·연속 거절은 원천이 없어 비운다. */
  static EligibleRider eligible(Rider r) {
    return new EligibleRider()
        .riderId(r.riderId())
        .vehicleType(VehicleType.fromValue(r.vehicleType()))
        .operating(operating(r))
        .restriction(AccountRestriction.fromValue(r.restriction().name()))
        .oneAtATime(r.oneAtATime())
        .dispatchMode(r.dispatchMode().name())
        .lastHeartbeatAt(time(r.lastHeartbeatAt()));
  }

  static String maskPhone(String phone) {
    if (phone.length() < 7) {
      return "***";
    }
    return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
  }

  static @Nullable String maskAccount(@Nullable String bank, @Nullable String account) {
    if (account == null) {
      return null;
    }
    String tail = account.length() <= 4 ? account : account.substring(account.length() - 4);
    return (bank == null ? "" : bank + " ") + "****" + tail;
  }

  static @Nullable OffsetDateTime time(@Nullable Instant i) {
    return i == null ? null : OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
  }
}
