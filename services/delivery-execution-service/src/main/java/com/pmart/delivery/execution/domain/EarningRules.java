package com.pmart.delivery.execution.domain;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

/**
 * 수행료·정산 규칙(원문 §7.2, 사용자 결정 2026-11-13).
 *
 * <ul>
 *   <li>금액 = 배차 수락 당시 금액(dispatch DeliveryAssigned.fee — 원문 §7.1 표, "제안·수락 당시 금액과 적용 조건 기록"). 요율표
 *       버전 {@link #RATE_TABLE_VERSION} = 원문 §7.1 표.
 *   <li>전달 완료 → 확정 대기(PENDING_CONFIRMATION), 완료 + {@code confirmAfter}(5시간) 뒤 확정(CONFIRMED).
 *   <li>지급 예정일 = 수행일(Asia/Seoul) + {@code payoutBusinessDays}(3) 영업일 — 주말과 설정된 공휴일 제외. 실제 지급은 외부
 *       회계.
 *   <li>실패·취소는 원문에 금액 기준이 없어 산정 불가(calculable false) — 지원센터 조정으로.
 * </ul>
 */
public final class EarningRules {

  public static final int RATE_TABLE_VERSION = 1;

  private EarningRules() {}

  public static Instant confirmAt(Instant completedAt, Duration confirmAfter) {
    return completedAt.plus(confirmAfter);
  }

  /** 수행일 다음 날부터 영업일(주말·공휴일 제외)을 센다. */
  public static LocalDate payoutDate(
      LocalDate performedOn, int businessDays, Set<LocalDate> holidays) {
    LocalDate d = performedOn;
    int counted = 0;
    while (counted < businessDays) {
      d = d.plusDays(1);
      if (d.getDayOfWeek() != DayOfWeek.SATURDAY
          && d.getDayOfWeek() != DayOfWeek.SUNDAY
          && !holidays.contains(d)) {
        counted++;
      }
    }
    return d;
  }
}
