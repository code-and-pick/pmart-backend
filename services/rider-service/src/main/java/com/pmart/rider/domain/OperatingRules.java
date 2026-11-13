package com.pmart.rider.domain;

import com.pmart.rider.domain.Rider.OperatingStatus;
import com.pmart.rider.domain.Rider.Restriction;
import org.jspecify.annotations.Nullable;

/**
 * 운행 상태 바꾸기 규칙(원문 §2.1 — 운행 종료 / 운행 중·신규배차 허용 / 운행 중·신규배차 중지, 운행 시작 때 계정 제한 확인).
 *
 * <ul>
 *   <li>OFF_DUTY 이면 신규배차는 늘 꺼짐(계약 "OFF_DUTY면 false 강제").
 *   <li>일시 중지·계약 종료는 운행을 시작할 수 없다. 신규배차 제한은 운행은 되지만 신규배차를 켤 수 없다(D-4: 배정분은 그대로).
 *   <li>운행 시작 기준 MFC 는 배달지역 안이어야 한다. 비우면 배달지역 첫 MFC.
 * </ul>
 */
public final class OperatingRules {

  /** 판정 결과. rejection 이 있으면 바꾸지 않는다. */
  public record Decision(
      @Nullable Rejection rejection,
      OperatingStatus status,
      boolean newDispatchEnabled,
      @Nullable String mfcId) {}

  public enum Rejection {
    /** 403 — 계정 제한으로 운행·신규배차 불가. */
    RESTRICTED,
    /** 400 — 배달지역 밖 MFC. */
    MFC_NOT_IN_AREAS
  }

  private OperatingRules() {}

  public static Decision decide(
      Rider rider, OperatingStatus status, boolean newDispatch, @Nullable String mfcId) {
    if (status == OperatingStatus.OFF_DUTY) {
      return new Decision(null, OperatingStatus.OFF_DUTY, false, rider.operatingMfcId());
    }
    Restriction r = rider.restriction();
    if (r == Restriction.SUSPENDED || r == Restriction.TERMINATED) {
      return new Decision(Rejection.RESTRICTED, rider.operatingStatus(), false, null);
    }
    if (newDispatch && r == Restriction.DISPATCH_RESTRICTED) {
      return new Decision(Rejection.RESTRICTED, rider.operatingStatus(), false, null);
    }
    String mfc = mfcId;
    if (mfc == null || mfc.isBlank()) {
      mfc = rider.deliveryAreas().isEmpty() ? null : rider.deliveryAreas().get(0).mfcId();
    } else if (!rider.servesMfc(mfc)) {
      return new Decision(Rejection.MFC_NOT_IN_AREAS, rider.operatingStatus(), false, null);
    }
    return new Decision(null, OperatingStatus.ON_DUTY, newDispatch, mfc);
  }
}
