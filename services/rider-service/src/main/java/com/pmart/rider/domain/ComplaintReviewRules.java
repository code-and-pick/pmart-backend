package com.pmart.rider.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * 컴플레인 누적 → 제한 검토 대상(RS-3, 사용자 2026-11-14 "비슷한 종류의 컴플레인이 일주일 이내에 3건 이상", 신고·오배송·파손 전부).
 *
 * <ul>
 *   <li>종류(신고 REPORT·오배송 MISDELIVERY·파손 DAMAGE)마다 따로 센다.
 *   <li>어느 7일(window) 안에 <b>서로 다른 배송</b> 3건(threshold) 이상이면 대상 — 한 배송에 컴플레인이 여러 번 와도 1건(같은 일을 여러 번
 *       신고한 것과 여러 번 일어난 것을 가른다).
 *   <li>새로 온 건(pivot)을 포함하는 창만 본다 — 늦게 도착한 옛 컴플레인도 그 시각 기준으로 센다.
 *   <li>이미 처리된 검토에 묶인 건은 후보에 넣지 않는다(호출하는 쪽) — 운영자가 확인한 뒤에는 새로 쌓여야 다시 대상.
 * </ul>
 */
public final class ComplaintReviewRules {

  /** 컴플레인 한 건(종류는 호출하는 쪽에서 같은 것만). */
  public record Complaint(String complaintId, String deliveryId, Instant at) {}

  private ComplaintReviewRules() {}

  /**
   * pivot 을 포함하는 window 안에 서로 다른 배송이 threshold 건 이상인 컴플레인 묶음(가장 이른 창). 없으면 빈 목록.
   *
   * @param candidates 같은 라이더·같은 종류의 아직 처리되지 않은 컴플레인(pivot 포함)
   */
  public static List<Complaint> qualifying(
      List<Complaint> candidates, Complaint pivot, Duration window, int threshold) {
    if (threshold <= 0 || window.isNegative() || window.isZero()) {
      throw new IllegalArgumentException("검토 기준(window·threshold)이 없습니다.");
    }
    List<Complaint> sorted =
        candidates.stream()
            .sorted(Comparator.comparing(Complaint::at).thenComparing(Complaint::complaintId))
            .toList();
    for (Complaint start : sorted) {
      if (start.at().isAfter(pivot.at())
          || Duration.between(start.at(), pivot.at()).compareTo(window) > 0) {
        continue;
      }
      Instant end = start.at().plus(window);
      List<Complaint> in =
          sorted.stream()
              .filter(c -> !c.at().isBefore(start.at()) && !c.at().isAfter(end))
              .toList();
      long deliveries = in.stream().map(Complaint::deliveryId).distinct().count();
      if (deliveries >= threshold) {
        return in;
      }
    }
    return List.of();
  }
}
