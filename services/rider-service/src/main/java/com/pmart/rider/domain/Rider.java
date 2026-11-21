package com.pmart.rider.domain;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** 라이더 한 명(표 rider + rider_delivery_area). 개인정보 필드는 응답에서 가린다(api 계층). */
public record Rider(
    String riderId,
    int version,
    String signupRequestId,
    @Nullable String name,
    String phone,
    String vehicleType,
    @Nullable String bankCode,
    @Nullable String bankAccountNumber,
    @Nullable String bankHolderName,
    @Nullable String residenceRoad,
    @Nullable String residenceDetail,
    OperatingStatus operatingStatus,
    boolean newDispatchEnabled,
    @Nullable String operatingMfcId,
    Instant stateChangedAt,
    boolean oneAtATime,
    DispatchMode dispatchMode,
    Restriction restriction,
    @Nullable String restrictionReason,
    @Nullable Instant restrictionUntil,
    @Nullable Instant lastHeartbeatAt,
    Instant joinedAt,
    List<Area> deliveryAreas,
    @Nullable String licenseMasked,
    @Nullable String riderNumber,
    /** 탈퇴 시각(2026-11-21 — 직원 탈퇴 라이더 보기). 탈퇴 안 했으면 null. */
    @Nullable Instant withdrawnAt) {

  public enum OperatingStatus {
    OFF_DUTY,
    ON_DUTY
  }

  public enum DispatchMode {
    NORMAL,
    AI_RECOMMENDED
  }

  /** 계정 제한(증빙 기반, BO-20). */
  public enum Restriction {
    NORMAL,
    DISPATCH_RESTRICTED,
    SUSPENDED,
    TERMINATED
  }

  /** 배달지역. zoneId 없으면 빈 문자열. */
  public record Area(String mfcId, String zoneId) {}

  public boolean servesMfc(String mfcId) {
    return deliveryAreas.stream().anyMatch(a -> a.mfcId().equals(mfcId));
  }

  /** 지금 유효한 제한 — 기한({@code restrictionUntil})이 지났으면 NORMAL(RS-1, 기한 끝에 따로 사건은 내지 않는다). */
  public Restriction restrictionAt(Instant now) {
    return restrictionUntil != null && !restrictionUntil.isAfter(now)
        ? Restriction.NORMAL
        : restriction;
  }

  /** 기한이 지난 제한을 NORMAL 로 본 사본(응답·판정용). */
  public Rider asOf(Instant now) {
    if (restrictionAt(now) == restriction) {
      return this;
    }
    return new Rider(
        riderId,
        version,
        signupRequestId,
        name,
        phone,
        vehicleType,
        bankCode,
        bankAccountNumber,
        bankHolderName,
        residenceRoad,
        residenceDetail,
        operatingStatus,
        newDispatchEnabled,
        operatingMfcId,
        stateChangedAt,
        oneAtATime,
        dispatchMode,
        Restriction.NORMAL,
        null,
        null,
        lastHeartbeatAt,
        joinedAt,
        deliveryAreas,
        licenseMasked,
        riderNumber,
        withdrawnAt);
  }

  /** 새 배차를 받을 수 있는 상태(운행 중·신규배차 ON·제한 없음). */
  public boolean acceptsNewDispatch() {
    return operatingStatus == OperatingStatus.ON_DUTY
        && newDispatchEnabled
        && restriction == Restriction.NORMAL;
  }
}
