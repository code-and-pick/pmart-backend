package com.pmart.delivery.dispatch.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 라이더 탐색·제안 정책(원문 §3.2·§3.5) — 반경 단계는 배차 방식이 아니다(사용자 결정 2026-11-10·11-13).
 *
 * <ul>
 *   <li>단계마다 반경(기본 1→2→3km, MFC 중심 직선). 한 단계에서 제안할 사람이 없으면 응답 대기(기본 1분)만큼 그 단계에 머문 뒤(배달리스트 라이더가 고를
 *       시간) 다음 단계.
 *   <li>마지막 단계까지 못 정하면 재요청: 최대 2회, 10분 간격 — 회차가 바뀌면 거절했던 라이더에게도 다시 제안한다(원문 §3.5).
 *   <li>재요청을 다 쓰면 배차 실패. 사유는 거절이 있었으면 REPEATED_REJECTION, 만료만 있었으면 EXPIRED, 후보가 없었으면 NO_RIDER.
 * </ul>
 */
public final class SearchRules {

  public enum Next {
    STAY,
    NEXT_STAGE,
    RETRY,
    FAIL
  }

  private SearchRules() {}

  /** 제안할 사람이 없을 때 다음 할 일. */
  public static Next whenNoCandidate(
      int stage,
      Instant stageStartedAt,
      int retryCount,
      Instant now,
      List<Double> radiiKm,
      Duration stageWait,
      int maxRetries) {
    if (Duration.between(stageStartedAt, now).compareTo(stageWait) < 0) {
      return Next.STAY;
    }
    if (stage < radiiKm.size()) {
      return Next.NEXT_STAGE;
    }
    return retryCount < maxRetries ? Next.RETRY : Next.FAIL;
  }

  public static String failureReason(boolean hadRejection, boolean hadExpiry) {
    if (hadRejection) {
      return "REPEATED_REJECTION";
    }
    if (hadExpiry) {
      return "EXPIRED";
    }
    return "NO_RIDER";
  }

  /** 단계(1부터)의 반경. 범위를 넘으면 마지막. */
  public static double radiusKm(List<Double> radiiKm, int stage) {
    return radiiKm.get(Math.max(0, Math.min(radiiKm.size(), stage) - 1));
  }
}
