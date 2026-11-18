package com.pmart.delivery.execution.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 라이더 운행시간(2026-11-17 사용자 결정 "운행시간은 배차 수락하고 전달완료까지의 시간"). 배송마다 [수락, 전달 완료(회수는 MFC 반납)] 구간을 모아 **겹치는
 * 구간은 한 번만** 센다(알뜰배달 묶음처럼 여러 건을 함께 들고 있는 시간을 두 번 세지 않게 — 설계 해석). 날짜는 Asia/Seoul 자정으로 나눈다(ADR-0010).
 */
public final class OperatingTime {

  private OperatingTime() {}

  public record Span(Instant start, Instant end) {}

  /** from~to(둘 다 포함) 날짜마다 초. 날짜에 걸친 구간은 자정에서 나눈다. 구간이 없는 날도 0 으로 넣는다. */
  public static Map<LocalDate, Long> perDay(
      List<Span> spans, LocalDate from, LocalDate to, ZoneId zone) {
    List<Span> merged = merge(spans);
    Map<LocalDate, Long> out = new LinkedHashMap<>();
    // 나노초를 쌓아 가며 초로 나눈다 — 날마다 따로 버리면 자정을 걸친 구간의 합이 1초 모자랄 수 있다(2026-11-18)
    long cumulativeNs = 0;
    for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
      Instant dayStart = day.atStartOfDay(zone).toInstant();
      Instant dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant();
      long ns = 0;
      for (Span s : merged) {
        Instant a = s.start().isAfter(dayStart) ? s.start() : dayStart;
        Instant b = s.end().isBefore(dayEnd) ? s.end() : dayEnd;
        if (b.isAfter(a)) {
          ns += Duration.between(a, b).toNanos();
        }
      }
      out.put(day, (cumulativeNs + ns) / 1_000_000_000L - cumulativeNs / 1_000_000_000L);
      cumulativeNs += ns;
    }
    return out;
  }

  /** base 구간에서 cut 구간을 뺀다(2026-11-18 — 대기 = 신규배차 켠 구간 − 배송을 맡은 구간). */
  public static List<Span> subtract(List<Span> base, List<Span> cut) {
    List<Span> cuts = merge(cut);
    List<Span> out = new ArrayList<>();
    for (Span b : merge(base)) {
      Instant cursor = b.start();
      for (Span c : cuts) {
        if (!c.end().isAfter(cursor) || !c.start().isBefore(b.end())) {
          continue;
        }
        if (c.start().isAfter(cursor)) {
          out.add(new Span(cursor, c.start()));
        }
        if (c.end().isAfter(cursor)) {
          cursor = c.end();
        }
      }
      if (b.end().isAfter(cursor)) {
        out.add(new Span(cursor, b.end()));
      }
    }
    return out;
  }

  /** 시작 순으로 놓고 겹치거나 맞닿은 구간을 합친다. 끝이 시작보다 앞선 구간(시계 어긋남)은 버린다. */
  static List<Span> merge(List<Span> spans) {
    List<Span> sorted =
        spans.stream()
            .filter(s -> s.end().isAfter(s.start()))
            .sorted(Comparator.comparing(Span::start))
            .toList();
    List<Span> out = new ArrayList<>();
    for (Span s : sorted) {
      if (!out.isEmpty() && !s.start().isAfter(out.get(out.size() - 1).end())) {
        Span last = out.remove(out.size() - 1);
        out.add(new Span(last.start(), s.end().isAfter(last.end()) ? s.end() : last.end()));
      } else {
        out.add(s);
      }
    }
    return out;
  }
}
