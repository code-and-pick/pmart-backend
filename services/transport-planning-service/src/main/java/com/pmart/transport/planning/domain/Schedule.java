package com.pmart.transport.planning.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 노선 계획 시각(TMS 정본 3.3). 출차 = 출고일 D 의 노선 출차 시각(12:00 이후면 D, 앞이면 D+1 — 자정을 넘는 운행), 상차 시작 = 출차 − 30분,
 * 정차 도착 = 앞 지점 출발 + 구간 분, 정차 출발 = 도착 + 하차 30분, 복귀 = 마지막 정차 출발 + 복귀 분. 마지막 정차 도착이 D+1
 * 07:30(Asia/Seoul)을 넘으면 불가(정본 D-02). 하차 슬롯 대기(동시 3대)는 1단계에서 계산하지 않는다.
 */
public final class Schedule {

  public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
  public static final Duration UNLOADING = Duration.ofMinutes(30);
  public static final Duration LOADING_LEAD = Duration.ofMinutes(30);
  public static final LocalTime ARRIVAL_DEADLINE = LocalTime.of(7, 30);

  public record Leg(String mfcId, int legMinutes) {}

  public record StopTime(
      int seq, String mfcId, int legMinutes, Instant arrival, Instant departure) {}

  public record Plan(
      Instant loadingStart,
      Instant departure,
      List<StopTime> stops,
      Instant returnAt,
      Instant arrivalDeadline) {

    /** 마지막 정차 도착이 마감(D+1 07:30) 안인가. */
    public boolean feasible() {
      return !stops.isEmpty() && !stops.get(stops.size() - 1).arrival().isAfter(arrivalDeadline);
    }
  }

  private Schedule() {}

  /** 출고일 D 의 출차 시각. */
  public static Instant departureAt(LocalDate shipDate, String hhmm) {
    LocalTime t = LocalTime.parse(hhmm);
    LocalDate day = t.isBefore(LocalTime.NOON) ? shipDate.plusDays(1) : shipDate;
    return day.atTime(t).atZone(SEOUL).toInstant();
  }

  public static Plan plan(LocalDate shipDate, String hhmm, List<Leg> legs, int returnMinutes) {
    Instant dep = departureAt(shipDate, hhmm);
    return planFrom(shipDate, dep, legs, returnMinutes);
  }

  /** 출차 시각이 정해진 뒤(정차를 더할 때) 다시 계산. */
  public static Plan planFrom(LocalDate shipDate, Instant dep, List<Leg> legs, int returnMinutes) {
    List<StopTime> out = new ArrayList<>();
    Instant at = dep;
    int seq = 1;
    for (Leg l : legs) {
      Instant arr = at.plus(Duration.ofMinutes(l.legMinutes()));
      Instant leave = arr.plus(UNLOADING);
      out.add(new StopTime(seq++, l.mfcId(), l.legMinutes(), arr, leave));
      at = leave;
    }
    Instant deadline = shipDate.plusDays(1).atTime(ARRIVAL_DEADLINE).atZone(SEOUL).toInstant();
    return new Plan(
        dep.minus(LOADING_LEAD), dep, out, at.plus(Duration.ofMinutes(returnMinutes)), deadline);
  }

  /**
   * READY(사용 준비 시각) TRF 의 출고일(0.5.0, 시뮬레이터 R-28) — 도착 마감 D+1 07:30 ≤ dueAt 인 가장 늦은 D. 준비 시각이 07:30
   * 이후면 그 전날, 앞이면 이틀 전.
   */
  public static LocalDate readyShipDate(Instant dueAt) {
    var t = dueAt.atZone(SEOUL);
    return t.toLocalTime().isBefore(ARRIVAL_DEADLINE)
        ? t.toLocalDate().minusDays(2)
        : t.toLocalDate().minusDays(1);
  }

  /**
   * 출고일 D 생성이 cdc-outbound 에서 읽는 기한 구간 — ARRIVAL [D 12:00, D+1 12:00) ∪ READY 의 D−1·D 몫 [D 07:30,
   * D+2 07:30).
   */
  public static Instant[] feedWindow(LocalDate shipDate) {
    return new Instant[] {
      shipDate.atTime(ARRIVAL_DEADLINE).atZone(SEOUL).toInstant(),
      shipDate.plusDays(2).atTime(ARRIVAL_DEADLINE).atZone(SEOUL).toInstant()
    };
  }

  /** 출고일 D 의 이관요청서 기한 구간 [D 12:00, D+1 12:00) Asia/Seoul. */
  public static Instant[] dueWindow(LocalDate shipDate) {
    return new Instant[] {
      shipDate.atTime(LocalTime.NOON).atZone(SEOUL).toInstant(),
      shipDate.plusDays(1).atTime(LocalTime.NOON).atZone(SEOUL).toInstant()
    };
  }

  /** 정본 V7 — 출고일 21:30 뒤 처음 받음. */
  public static boolean lateReceipt(LocalDate shipDate, Instant receivedAt) {
    return receivedAt.isAfter(shipDate.atTime(21, 30).atZone(SEOUL).toInstant());
  }

  /** dueAt 이 든 출고일(dueAt − 12시간의 서울 날짜). */
  public static LocalDate shipDateOf(Instant dueAt) {
    return dueAt.minus(Duration.ofHours(12)).atZone(SEOUL).toLocalDate();
  }
}
