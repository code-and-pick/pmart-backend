package com.pmart.transport.planning.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScheduleAndRulesTest {

  static final LocalDate D = LocalDate.of(2026, 12, 1);

  @Test
  void departureCrossesMidnight() {
    // 23:00 은 출고일 D, 01:30 은 D+1(자정을 넘는 운행 — 정본 3.4)
    assertThat(Schedule.departureAt(D, "23:00")).isEqualTo(Instant.parse("2026-12-01T14:00:00Z"));
    assertThat(Schedule.departureAt(D, "01:30")).isEqualTo(Instant.parse("2026-12-01T16:30:00Z"));
  }

  @Test
  void planAddsUnloadingAndChecksDeadline() {
    Schedule.Plan p =
        Schedule.plan(
            D, "00:30", List.of(new Schedule.Leg("M1", 200), new Schedule.Leg("M2", 20)), 240);
    // 00:30 + 200분 = 03:50 도착, 하차 30분 → 04:20 출발, + 20분 = 04:40 도착
    assertThat(p.loadingStart()).isEqualTo(Instant.parse("2026-12-01T15:00:00Z"));
    assertThat(p.stops().get(0).arrival()).isEqualTo(Instant.parse("2026-12-01T18:50:00Z"));
    assertThat(p.stops().get(1).arrival()).isEqualTo(Instant.parse("2026-12-01T19:40:00Z"));
    assertThat(p.returnAt()).isEqualTo(Instant.parse("2026-12-02T00:10:00Z")); // 05:10 + 240분
    assertThat(p.feasible()).isTrue();
    // 마지막 도착 07:31 → 불가, 07:30 정각은 됨
    assertThat(Schedule.plan(D, "00:30", List.of(new Schedule.Leg("M1", 421)), 60).feasible())
        .isFalse();
    assertThat(Schedule.plan(D, "00:30", List.of(new Schedule.Leg("M1", 420)), 60).feasible())
        .isTrue();
  }

  @Test
  void dueWindowAndShipDate() {
    Instant[] w = Schedule.dueWindow(D);
    assertThat(w[0]).isEqualTo(Instant.parse("2026-12-01T03:00:00Z"));
    assertThat(w[1]).isEqualTo(Instant.parse("2026-12-02T03:00:00Z"));
    assertThat(Schedule.shipDateOf(Instant.parse("2026-12-01T22:30:00Z")))
        .isEqualTo(D); // D+1 07:30
    assertThat(Schedule.lateReceipt(D, Instant.parse("2026-12-01T12:31:00Z"))).isTrue(); // 21:31
    assertThat(Schedule.lateReceipt(D, Instant.parse("2026-12-01T12:29:00Z"))).isFalse();
  }

  static RequestRules.Input in(
      String state, int approved, int allocated, List<String> temps, Integer rt) {
    return new RequestRules.Input("MFC-1", state, approved, allocated, temps, rt);
  }

  @Test
  void verdictOrder() {
    var ok = RequestRules.evaluate(in("ALLOCATED", 10, 10, List.of("AMBIENT"), 3), true, 20);
    assertThat(ok.state()).isEqualTo("ACCEPTED");
    assertThat(ok.tempClass()).isEqualTo("AMBIENT");
    // 부분할당은 할당량만(V6)
    assertThat(
            RequestRules.evaluate(
                    in("PARTIALLY_ALLOCATED", 100, 60, List.of("AMBIENT"), null), true, 20)
                .acceptedQty())
        .isEqualTo(60);
    assertThat(RequestRules.evaluate(in("ALLOCATED", 0, 0, List.of(), null), true, 20).reason())
        .isEqualTo("MISSING_FIELDS");
    assertThat(RequestRules.evaluate(in("CANCELLED", 10, 0, List.of(), null), true, 20).state())
        .isEqualTo("EXCLUDED");
    assertThat(
            RequestRules.evaluate(in("ALLOCATED", 10, 10, List.of("AMBIENT"), 3), false, 20)
                .reason())
        .isEqualTo("NO_ROUTE");
    assertThat(
            RequestRules.evaluate(in("ALLOCATION_FAILED", 10, 0, List.of(), null), true, 20)
                .reason())
        .isEqualTo("ALLOCATION_PENDING");
    assertThat(RequestRules.evaluate(in("IN_WAVE", 10, 10, List.of(), null), true, 20).reason())
        .isEqualTo("TEMP_UNKNOWN");
    assertThat(
            RequestRules.evaluate(
                    in("IN_WAVE", 10, 10, List.of("AMBIENT", "CHILLED"), null), true, 20)
                .reason())
        .isEqualTo("MIXED_TEMPERATURE");
    assertThat(
            RequestRules.evaluate(in("IN_WAVE", 10, 10, List.of("AMBIENT"), 21), true, 20).reason())
        .isEqualTo("OVER_VEHICLE_CAPACITY");
    // R/T 카운팅 전(null)이나 정규차 모름이면 용량 검사를 미룬다
    assertThat(
            RequestRules.evaluate(in("IN_WAVE", 10, 10, List.of("AMBIENT"), null), true, 20)
                .state())
        .isEqualTo("ACCEPTED");
    assertThat(
            RequestRules.evaluate(in("IN_WAVE", 10, 10, List.of("AMBIENT"), 99), true, null)
                .state())
        .isEqualTo("ACCEPTED");
  }
}
