package com.pmart.transport.planning.domain;

import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * 이관요청서 자동 검증(TMS 정본 5.2 — 설계 §3). 순서: V1 필수값 → 취소 제외 → V2 노선에 든 MFC → V6 할당 → D-06 온도대 하나 → V8 R/T
 * ≤ 정규차 최대(R/T 카운팅 전이면 미룸). V4·V5(버전)와 계획완료 뒤 변경은 호출자가 먼저 본다.
 */
public final class RequestRules {

  /** 할당 전·실패·취소 중 — 보류(ALLOCATION_PENDING). */
  static final Set<String> NOT_ALLOCATED =
      Set.of("RECEIVED", "ALLOCATING", "ALLOCATION_FAILED", "CANCELLING", "CANCEL_HOLD");

  public record Input(
      @Nullable String mfcId,
      String trfState,
      int approvedQty,
      int allocatedQty,
      List<String> tempClasses,
      @Nullable Integer rtCount) {}

  /**
   * @param routed 목적 MFC 가 살아 있는 노선에 있는가
   * @param maxRt 그 노선 × 온도대 정규차 최대 R/T(정규차가 없으면 null — 용량 검사 안 함)
   */
  public record Verdict(
      String state, @Nullable String reason, int acceptedQty, @Nullable String tempClass) {}

  private RequestRules() {}

  public static Verdict evaluate(Input in, boolean routed, @Nullable Integer maxRtOfTemp) {
    String temp = in.tempClasses().size() == 1 ? in.tempClasses().get(0) : null;
    if (in.mfcId() == null || in.mfcId().isBlank() || in.approvedQty() <= 0) {
      return new Verdict("REJECTED", "MISSING_FIELDS", 0, temp);
    }
    if ("CANCELLED".equals(in.trfState())) {
      return new Verdict("EXCLUDED", null, 0, temp);
    }
    if (!routed) {
      return new Verdict("HELD", "NO_ROUTE", 0, temp);
    }
    if (NOT_ALLOCATED.contains(in.trfState()) || in.allocatedQty() <= 0) {
      return new Verdict("HELD", "ALLOCATION_PENDING", 0, temp);
    }
    int accepted = Math.min(in.approvedQty(), in.allocatedQty()); // 부분할당은 할당량만(V6)
    if (in.tempClasses().isEmpty()) {
      return new Verdict("HELD", "TEMP_UNKNOWN", accepted, null);
    }
    if (in.tempClasses().size() > 1) {
      return new Verdict("HELD", "MIXED_TEMPERATURE", accepted, null);
    }
    if (in.rtCount() != null && maxRtOfTemp != null && in.rtCount() > maxRtOfTemp) {
      return new Verdict("HELD", "OVER_VEHICLE_CAPACITY", accepted, temp);
    }
    return new Verdict("ACCEPTED", null, accepted, temp);
  }
}
