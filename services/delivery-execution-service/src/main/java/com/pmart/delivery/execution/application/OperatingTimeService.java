package com.pmart.delivery.execution.application;

import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.delivery.execution.domain.OperatingTime;
import com.pmart.delivery.execution.infrastructure.ExecutionStore;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * 라이더 본인 운행시간(2026-11-17 — 라이더 앱 기록 화면, 원문 §8.1 "오늘배달내역 … 운행시간"). 사용자 결정 "운행시간은 배차 수락하고 전달완료까지의
 * 시간": 이 라이더가 끝낸 배송(전달 완료·회수 반납)마다 [수락, 완료] 구간, 겹치면 한 번({@link OperatingTime}). 실패·취소·배정 해제된 배송은 넣지
 * 않는다.
 */
@Service
public class OperatingTimeService {

  static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
  static final int MAX_DAYS = 31;

  public record Result(
      LocalDate from,
      LocalDate to,
      long totalSeconds,
      Map<LocalDate, Long> days,
      @Nullable Map<LocalDate, Long> connected,
      @Nullable Map<LocalDate, Long> waiting) {

    Result(LocalDate from, LocalDate to, long totalSeconds, Map<LocalDate, Long> days) {
      this(from, to, totalSeconds, days, null, null);
    }
  }

  private final ExecutionStore store;
  private final Clock clock;
  private final com.pmart.delivery.execution.infrastructure.RiderClient riders;

  public OperatingTimeService(
      ExecutionStore store,
      Clock clock,
      com.pmart.delivery.execution.infrastructure.RiderClient riders) {
    this.store = store;
    this.clock = clock;
    this.riders = riders;
  }

  public Result mine(@Nullable LocalDate from, @Nullable LocalDate to) {
    String rider =
        PmartPrincipal.current()
            .filter(p -> p.type() == PmartPrincipal.PrincipalType.RIDER)
            .map(PmartPrincipal::subject)
            .orElseThrow(() -> ExecutionService.forbidden("라이더만 볼 수 있습니다."));
    return of(rider, from, to);
  }

  /** 직원용(2026-11-17 라이더 백오피스) — RIDER_OPS·OPERATOR_ADMIN·CS_AGENT 보기. */
  public Result forStaff(String rider, @Nullable LocalDate from, @Nullable LocalDate to) {
    EarningService.requireRiderStaff();
    Result r = of(rider, from, to);
    // 접속·대기(2026-11-18 — 사용자 "접속 시간 따로 더함", 대기 = 운행 중 + 신규배차 켬 + 배송 0건). rider 를 못 부르면 null
    Instant start = r.from().atStartOfDay(SEOUL).toInstant();
    Instant end = r.to().plusDays(1).atStartOfDay(SEOUL).toInstant();
    Instant now = clock.instant();
    var segments = riders.dutySegments(rider, start, end);
    if (segments.isEmpty()) {
      return r;
    }
    List<OperatingTime.Span> on = new ArrayList<>();
    List<OperatingTime.Span> available = new ArrayList<>();
    for (var s : segments.get()) {
      Instant e = s.endedAt() != null ? s.endedAt() : now;
      OperatingTime.Span span = new OperatingTime.Span(s.startedAt(), e);
      on.add(span);
      if (s.available()) {
        available.add(span);
      }
    }
    var busy = store.riderBusySpans(rider, start, end, now);
    return new Result(
        r.from(),
        r.to(),
        r.totalSeconds(),
        r.days(),
        OperatingTime.perDay(on, r.from(), r.to(), SEOUL),
        OperatingTime.perDay(OperatingTime.subtract(available, busy), r.from(), r.to(), SEOUL));
  }

  private Result of(String rider, @Nullable LocalDate from, @Nullable LocalDate to) {
    LocalDate f = from != null ? from : LocalDate.now(clock.withZone(SEOUL));
    LocalDate t = to != null ? to : f;
    if (t.isBefore(f) || ChronoUnit.DAYS.between(f, t) >= MAX_DAYS) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED, "기간은 from ≤ to, 최대 " + MAX_DAYS + "일까지입니다.");
    }
    var spans =
        store.riderOperatingSpans(
            rider,
            f.atStartOfDay(SEOUL).toInstant(),
            t.plusDays(1).atStartOfDay(SEOUL).toInstant());
    Map<LocalDate, Long> days = OperatingTime.perDay(spans, f, t, SEOUL);
    long total = days.values().stream().mapToLong(Long::longValue).sum();
    return new Result(f, t, total, days);
  }
}
