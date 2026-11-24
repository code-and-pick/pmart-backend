package com.pmart.delivery.execution.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 오배송 정산 차감 규칙(2026-11-24, ADR-0086 — DE-9). 금액 = 결제 금액 − 배달비(봉투값 포함·쿠폰 뒤), 확정된 차감은 지급 묶음에서 먼저 확정된
 * 것부터 빼고 모자라면 다음 묶음으로(상한 없음).
 */
public final class DeductionRules {

  private DeductionRules() {}

  /** 이의 기간(사용자 결정 "5일"). */
  public static final java.time.Duration OBJECTION_WINDOW = java.time.Duration.ofDays(5);

  /** 차감 금액 = approvedAmount − deliveryTip, 0 아래면 0(차감 없음). */
  public static long amount(long approvedAmount, long deliveryTip) {
    return Math.max(0, approvedAmount - deliveryTip);
  }

  /** 남은 차감 한 줄(확정 순). */
  public record Open(String deductionId, long remaining) {}

  /** 묶음 합계(gross) 안에서 앞 줄부터 뺀다. @return 줄마다 뺄 금액(0 인 줄은 없음) */
  public static Map<String, Long> allocate(List<Open> open, long gross) {
    Map<String, Long> out = new LinkedHashMap<>();
    long left = Math.max(0, gross);
    for (Open o : open) {
      if (left == 0) {
        break;
      }
      long take = Math.min(left, Math.max(0, o.remaining()));
      if (take > 0) {
        out.put(o.deductionId(), take);
        left -= take;
      }
    }
    return out;
  }

  /**
   * 아직 빼지 않은 묶음의 예상 차감 — 남은 확정 차감(remainingTotal)을 지급 예정일이 앞선 빼지 않은 묶음(earlierGross 합)부터 채운 뒤 이 묶음
   * 합계(gross) 안에서.
   */
  public static long expected(long remainingTotal, long earlierGross, long gross) {
    return Math.max(0, Math.min(gross, remainingTotal - Math.max(0, earlierGross)));
  }
}
