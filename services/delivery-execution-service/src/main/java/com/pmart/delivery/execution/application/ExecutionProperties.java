package com.pmart.delivery.execution.application;

import com.pmart.delivery.execution.domain.KoreanPublicHolidays;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pmart.execution.*} — 수행료·정산(원문 §7.2).
 *
 * @param confirmAfter 전달 완료 뒤 수익 확정까지 — 원문 5시간
 * @param payoutBusinessDays 수행일 뒤 지급 예정일까지 영업일 — 원문 3
 * @param publicHolidays 지급 예정일 계산에서 빼는 공휴일(Asia/Seoul). 설정하지 않으면 법정공휴일({@link KoreanPublicHolidays}
 *     — 사용자 결정 2026-11-13). 임시공휴일은 설정에 목록 전체로 넣는다
 * @param confirmInterval 확정 대기 → 확정 판단 주기
 */
@ConfigurationProperties(prefix = "pmart.execution")
public record ExecutionProperties(
    @Nullable Duration confirmAfter,
    @Nullable Integer payoutBusinessDays,
    @Nullable List<LocalDate> publicHolidays,
    @Nullable Duration confirmInterval) {

  public ExecutionProperties {
    confirmAfter = confirmAfter == null ? Duration.ofHours(5) : confirmAfter;
    payoutBusinessDays = payoutBusinessDays == null ? 3 : payoutBusinessDays;
    publicHolidays =
        publicHolidays == null || publicHolidays.isEmpty()
            ? List.copyOf(KoreanPublicHolidays.Y2026_2027)
            : List.copyOf(publicHolidays);
    confirmInterval = confirmInterval == null ? Duration.ofMinutes(1) : confirmInterval;
  }

  public Set<LocalDate> holidays() {
    return Set.copyOf(publicHolidays);
  }
}
