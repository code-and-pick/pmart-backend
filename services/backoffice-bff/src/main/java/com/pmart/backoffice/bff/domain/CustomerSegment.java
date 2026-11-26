package com.pmart.backoffice.bff.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.jspecify.annotations.Nullable;

/**
 * 고객 분류(BF-5 2차, 0.29.0 — 사용자 결정 2026-11-23·2026-11-26). 판정은 한국 날짜(Asia/Seoul).
 *
 * <ul>
 *   <li>휴면 DORMANT = 마지막 구매가 1년보다 전. 산 적이 없으면 가입이 1년보다 전.
 *   <li>위험 AT_RISK = 마지막 구매가 6개월보다 전(1년 안). 산 적이 없으면 가입이 6개월보다 전(2026-11-26 "가입일로 판정").
 *   <li>우수 TOP = 3개월 안 구매 금액(order amount3m) 100만 원 이상.
 *   <li>신규 NEW = 가입 1달 이내.
 *   <li>그 밖 일반 REGULAR.
 * </ul>
 *
 * 겹치면 휴면 → 위험 → 우수 → 신규(2026-11-23 "휴면 우선", 2026-11-26 "우수 우선"). 구매는 O-12 정의(취소·만료 빼고, 환불은 안 뺌).
 */
public enum CustomerSegment {
  DORMANT,
  AT_RISK,
  TOP,
  NEW,
  REGULAR;

  public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
  public static final long TOP_AMOUNT_3M = 1_000_000L;

  /**
   * 판정할 수 없으면 null — 산 적이 없는데 가입일을 모르거나, 신규 여부를 가입일 없이 정해야 할 때(추측하지 않는다).
   *
   * @param joinedAt customer 가입 시각
   * @param lastOrderAt order 마지막 산 주문 확정 시각(산 적 없으면 null)
   * @param amount3m order 3개월 안 산 금액
   */
  public static @Nullable CustomerSegment of(
      LocalDate today, @Nullable Instant joinedAt, @Nullable Instant lastOrderAt, long amount3m) {
    LocalDate joined = joinedAt == null ? null : joinedAt.atZone(SEOUL).toLocalDate();
    LocalDate ref = lastOrderAt != null ? lastOrderAt.atZone(SEOUL).toLocalDate() : joined;
    if (ref == null) {
      return null;
    }
    if (ref.isBefore(today.minusYears(1))) {
      return DORMANT;
    }
    if (ref.isBefore(today.minusMonths(6))) {
      return AT_RISK;
    }
    if (amount3m >= TOP_AMOUNT_3M) {
      return TOP;
    }
    if (joined == null) {
      return null;
    }
    return joined.isBefore(today.minusMonths(1)) ? REGULAR : NEW;
  }
}
