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
 * @param orphanPhotoAfter 올리다 실패해 참조가 없는 증빙 사진 객체를 이만큼 지나면 지운다 — 사용자 결정 2026-11-14 "5시간이 지나면 삭제"(바로
 *     지우지 않는 이유는 {@link PhotoSweeper})
 * @param photoSweepInterval 참조 없는 사진 정리 주기
 * @param payoutMaxRollovers 지급 실패 줄을 다음 지급일로 자동으로 넘기는 최대 횟수(기본 3)
 */
@ConfigurationProperties(prefix = "pmart.execution")
public record ExecutionProperties(
    @Nullable Duration confirmAfter,
    @Nullable Integer payoutBusinessDays,
    @Nullable List<LocalDate> publicHolidays,
    @Nullable Duration confirmInterval,
    @Nullable Duration orphanPhotoAfter,
    @Nullable Duration photoSweepInterval,
    @Nullable Duration autoCompleteAfter,
    @Nullable Integer payoutMaxRollovers) {

  public ExecutionProperties {
    confirmAfter = confirmAfter == null ? Duration.ofHours(5) : confirmAfter;
    payoutBusinessDays = payoutBusinessDays == null ? 3 : payoutBusinessDays;
    publicHolidays =
        publicHolidays == null || publicHolidays.isEmpty()
            ? List.copyOf(KoreanPublicHolidays.Y2026_2027)
            : List.copyOf(publicHolidays);
    confirmInterval = confirmInterval == null ? Duration.ofMinutes(1) : confirmInterval;
    orphanPhotoAfter = orphanPhotoAfter == null ? Duration.ofHours(5) : orphanPhotoAfter;
    photoSweepInterval = photoSweepInterval == null ? Duration.ofMinutes(10) : photoSweepInterval;
    // 자동 전달 완료(2026-11-17 사용자 "5시간 지나면 자동완료") — 픽업 뒤
    autoCompleteAfter = autoCompleteAfter == null ? Duration.ofHours(5) : autoCompleteAfter;
    // 지급 실패 넘김 상한(2026-11-24 사용자 "3번 안에는 반드시 줘야해") — 줄마다, 다 쓰면 직원 직접 지급
    payoutMaxRollovers =
        payoutMaxRollovers == null || payoutMaxRollovers < 1 ? 3 : payoutMaxRollovers;
  }

  public Set<LocalDate> holidays() {
    return Set.copyOf(publicHolidays);
  }
}
