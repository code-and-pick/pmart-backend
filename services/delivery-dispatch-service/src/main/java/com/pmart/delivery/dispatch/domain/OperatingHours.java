package com.pmart.delivery.dispatch.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 배차 가능 시간(Asia/Seoul, ADR-0010). PMart 배달 서비스 시간 = 08~24시(사용자 결정 2026-11-13 — 원문 §2.5 B마트 09~24시
 * 대신). 끝이 24:00 이면 그날 자정까지. 종료 뒤에도 이미 배정된 배송은 그대로(원문 §2.5) — 새 제안만 멈춘다.
 *
 * @param start 시작(포함)
 * @param endMinutes 끝(분, 제외). 24:00 = 1440
 */
public record OperatingHours(LocalTime start, int endMinutes) {

  public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

  public static OperatingHours parse(String start, String end) {
    LocalTime s = LocalTime.parse(start);
    int e;
    if ("24:00".equals(end)) {
      e = 24 * 60;
    } else {
      LocalTime t = LocalTime.parse(end);
      e = t.getHour() * 60 + t.getMinute();
    }
    if (e <= s.getHour() * 60 + s.getMinute()) {
      throw new IllegalArgumentException("배차 가능 시간 끝은 시작보다 뒤여야 합니다: " + start + "~" + end);
    }
    return new OperatingHours(s, e);
  }

  public boolean isOpen(Instant now) {
    ZonedDateTime z = now.atZone(SEOUL);
    int m = z.getHour() * 60 + z.getMinute();
    return m >= start.getHour() * 60 + start.getMinute() && m < endMinutes;
  }

  /** 지금이 닫힌 시간이면 다음 시작 시각, 열려 있으면 now. */
  public Instant nextOpen(Instant now) {
    if (isOpen(now)) {
      return now;
    }
    ZonedDateTime z = now.atZone(SEOUL);
    LocalDate day = z.toLocalDate();
    ZonedDateTime todayStart = day.atTime(start).atZone(SEOUL);
    return (z.isBefore(todayStart) ? todayStart : day.plusDays(1).atTime(start).atZone(SEOUL))
        .toInstant();
  }
}
