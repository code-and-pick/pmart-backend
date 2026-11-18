package com.pmart.delivery.execution.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class OperatingTimeTest {

  private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

  private static OperatingTime.Span span(String start, String end) {
    return new OperatingTime.Span(Instant.parse(start), Instant.parse(end));
  }

  @Test
  void overlappingDeliveriesCountOnce() {
    // 10:00~10:30, 10:10~10:50(묶음 — 겹침), 11:00~11:20 (KST = UTC+9)
    var days =
        OperatingTime.perDay(
            List.of(
                span("2026-11-17T01:00:00Z", "2026-11-17T01:30:00Z"),
                span("2026-11-17T01:10:00Z", "2026-11-17T01:50:00Z"),
                span("2026-11-17T02:00:00Z", "2026-11-17T02:20:00Z")),
            LocalDate.of(2026, 11, 17),
            LocalDate.of(2026, 11, 17),
            SEOUL);
    assertThat(days.get(LocalDate.of(2026, 11, 17))).isEqualTo(50 * 60 + 20 * 60);
  }

  @Test
  void splitsAtSeoulMidnightAndFillsEmptyDays() {
    // 23:40 KST ~ 다음날 00:15 KST
    var days =
        OperatingTime.perDay(
            List.of(span("2026-11-16T14:40:00Z", "2026-11-16T15:15:00Z")),
            LocalDate.of(2026, 11, 16),
            LocalDate.of(2026, 11, 18),
            SEOUL);
    assertThat(days.get(LocalDate.of(2026, 11, 16))).isEqualTo(20 * 60);
    assertThat(days.get(LocalDate.of(2026, 11, 17))).isEqualTo(15 * 60);
    assertThat(days.get(LocalDate.of(2026, 11, 18))).isZero();
    assertThat(days).hasSize(3);
  }

  /** 자정을 걸친 구간이 초 단위가 아니어도 날짜별 합 = 전체(2026-11-18 — 날마다 버리면 1초 모자랐다). */
  @Test
  void dayPartsAddUpWhenSplitMidSecond() {
    var days =
        OperatingTime.perDay(
            List.of(span("2026-11-17T14:50:00.400Z", "2026-11-17T15:15:00.400Z")),
            LocalDate.of(2026, 11, 17),
            LocalDate.of(2026, 11, 18),
            SEOUL);
    assertThat(days.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(25 * 60);
  }

  @Test
  void ignoresBackwardSpans() {
    var days =
        OperatingTime.perDay(
            List.of(span("2026-11-17T02:00:00Z", "2026-11-17T01:00:00Z")),
            LocalDate.of(2026, 11, 17),
            LocalDate.of(2026, 11, 17),
            SEOUL);
    assertThat(days.get(LocalDate.of(2026, 11, 17))).isZero();
  }

  /** 대기(2026-11-18) = 신규배차 켠 구간 − 배송 맡은 구간. */
  @Test
  void subtractRemovesBusySpans() {
    java.time.Instant t0 = java.time.Instant.parse("2026-11-18T01:00:00Z");
    var base = List.of(new OperatingTime.Span(t0, t0.plusSeconds(3600)));
    var busy =
        List.of(
            new OperatingTime.Span(t0.plusSeconds(600), t0.plusSeconds(1200)),
            new OperatingTime.Span(t0.plusSeconds(1000), t0.plusSeconds(1800)),
            new OperatingTime.Span(t0.plusSeconds(3000), t0.plusSeconds(4000)));
    var left = OperatingTime.subtract(base, busy);
    assertThat(left)
        .containsExactly(
            new OperatingTime.Span(t0, t0.plusSeconds(600)),
            new OperatingTime.Span(t0.plusSeconds(1800), t0.plusSeconds(3000)));
    assertThat(OperatingTime.subtract(base, List.of())).isEqualTo(base);
  }
}
