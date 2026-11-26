package com.pmart.transport.execution.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * 운행 규칙(TMS 정본 9.3·10.3). 인계 = 출고(상차 스캔) 박스와 인수 박스 대조. 예상 도착 = 마지막 실제 시각 + 남은 구간 + 하차 30분(계획을 덮지
 * 않음), 지연 = 예상 > 계획 + 30분 또는 > D+1 07:30(플래그 — 상태 아님). 실시간 위치는 쓰지 않는다(D-08).
 */
public final class TripRules {

  public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
  public static final Duration UNLOADING = Duration.ofMinutes(30);
  public static final Duration DELAY = Duration.ofMinutes(30);

  public record Comparison(String result, List<String> missing, List<String> extra) {}

  public record StopIn(
      int seq,
      int legMinutes,
      Instant plannedArrival,
      @Nullable Instant arrivedAt,
      @Nullable Instant departedAt) {}

  public record StopEta(int seq, @Nullable Instant estimatedArrival, boolean delayed) {}

  private TripRules() {}

  public static Comparison compare(List<String> shipped, List<String> received) {
    Set<String> s = new LinkedHashSet<>(shipped);
    Set<String> r = new LinkedHashSet<>(received);
    List<String> missing = new ArrayList<>();
    for (String x : s) {
      if (!r.contains(x)) {
        missing.add(x);
      }
    }
    List<String> extra = new ArrayList<>();
    for (String x : r) {
      if (!s.contains(x)) {
        extra.add(x);
      }
    }
    return new Comparison(
        missing.isEmpty() && extra.isEmpty() ? "MATCHED" : "MISMATCHED", missing, extra);
  }

  /** 마감 D+1 07:30. */
  public static Instant deadline(LocalDate shipDate) {
    return shipDate.plusDays(1).atTime(LocalTime.of(7, 30)).atZone(SEOUL).toInstant();
  }

  /**
   * 정차별 예상 도착. 출차 전이면 max(계획 출차, 지금)부터, 출차 뒤면 실제 출차부터. 도착한 정차는 실제 도착, 출발 안 했으면 max(도착 + 하차, 지금)을 다음
   * 기준으로.
   */
  public static List<StopEta> eta(
      List<StopIn> stops,
      Instant plannedDeparture,
      @Nullable Instant departedAt,
      Instant now,
      Instant deadline,
      boolean ended) {
    List<StopEta> out = new ArrayList<>();
    Instant cursor =
        departedAt != null ? departedAt : (now.isAfter(plannedDeparture) ? now : plannedDeparture);
    for (StopIn s : stops) {
      Instant est;
      if (s.arrivedAt() != null) {
        est = s.arrivedAt();
        if (s.departedAt() != null) {
          cursor = s.departedAt();
        } else {
          Instant done = s.arrivedAt().plus(UNLOADING);
          cursor = ended || done.isAfter(now) ? done : now;
        }
      } else {
        est = cursor.plus(Duration.ofMinutes(s.legMinutes()));
        if (!ended && departedAt != null && est.isBefore(now)) {
          est = now; // 아직 도착 기록이 없으면 지금보다 이르게 보지 않는다
        }
        cursor = est.plus(UNLOADING);
      }
      boolean delayed = est.isAfter(s.plannedArrival().plus(DELAY)) || est.isAfter(deadline);
      out.add(new StopEta(s.seq(), est, delayed));
    }
    return out;
  }
}
