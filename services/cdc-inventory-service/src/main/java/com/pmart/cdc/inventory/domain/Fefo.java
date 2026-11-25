package com.pmart.cdc.inventory.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * CDC 적격 현물·가용 한도·FEFO 다LOT 할당 규칙(WMS-business-logic CDC-F-008·F-009·F-016).
 *
 * <ul>
 *   <li>적격: 품질 RELEASED · LOT 식별 VERIFIED · 잔여일수 D = 소비기한 − 판정일(Asia/Seoul) ≥ max(1, 최소 잔여일수). 날짜형
 *       소비기한은 당일 00:00 부터 차단하므로 D = 0 은 부적격. 소비기한이 없으면 기한 조건을 보지 않는다.
 *   <li>한도: max(0, E − R − S). E·R 은 같은 필터를 통과한 현물과 거기 걸린 예약.
 *   <li>FEFO: 소비기한 빠른 순(없으면 맨 뒤) → 최초 정상 인수시각 → LOT → 피킹 선반 먼저(0.5.0 — 동선 선호, CDC-F-009 "동선 효율은
 *       FEFO 뒤에만") → 위치.
 * </ul>
 *
 * 품목군별 최소 잔여일수(총기간 T 의 30%·20% 등)는 제조일 입력이 아직 없어 여기서 계산하지 않는다 — 호출자가 준 값만 쓴다.
 */
public final class Fefo {

  public static final String VERIFIED = "VERIFIED";
  public static final String RELEASED = "RELEASED";

  private Fefo() {}

  /** 할당 후보 현물(정상 품질 행 하나 = LOT·위치). */
  public record Candidate(
      String lot,
      String locationCode,
      @Nullable LocalDate expiresAt,
      Instant firstReceivedAt,
      int onHand,
      int reserved,
      boolean pickFace) {
    public int free() {
      return Math.max(0, onHand - reserved);
    }
  }

  /** 잡은 수량. rank 는 FEFO 순서(0 부터) — 해제는 역순. */
  public record Pick(Candidate candidate, int qty, int rank) {}

  public static final Comparator<Candidate> ORDER =
      Comparator.comparing(
              Candidate::expiresAt, Comparator.nullsLast(Comparator.<LocalDate>naturalOrder()))
          .thenComparing(Candidate::firstReceivedAt)
          .thenComparing(Candidate::lot)
          .thenComparing(Candidate::pickFace, Comparator.reverseOrder())
          .thenComparing(Candidate::locationCode);

  /** 잔여일수 D(일). 소비기한이 없으면 null. */
  public static @Nullable Integer remainingDays(@Nullable LocalDate expiresAt, LocalDate today) {
    return expiresAt == null ? null : (int) ChronoUnit.DAYS.between(today, expiresAt);
  }

  /** 이 LOT 의 정상 현물이 적격인가(품질은 호출자가 RELEASED 행만 넘긴다). */
  public static boolean eligibleLot(
      String identification,
      @Nullable LocalDate expiresAt,
      LocalDate today,
      @Nullable Integer minRemainingDays) {
    if (!VERIFIED.equals(identification)) {
      return false;
    }
    Integer d = remainingDays(expiresAt, today);
    if (d == null) {
      return true;
    }
    int min = Math.max(1, minRemainingDays == null ? 1 : minRemainingDays);
    return d >= min;
  }

  /** 신규 공급 한도 max(0, E − R − S). */
  public static int cap(int eligible, int reserved, int safety) {
    return Math.max(0, eligible - reserved - safety);
  }

  /**
   * FEFO 순으로 want 만큼 잡는다(한도는 호출자가 want 에 반영).
   *
   * @param candidates 적격 후보(순서 무관 — 여기서 정렬)
   */
  public static List<Pick> pick(List<Candidate> candidates, int want) {
    List<Candidate> sorted = new ArrayList<>(candidates);
    sorted.sort(ORDER);
    List<Pick> out = new ArrayList<>();
    int left = want;
    for (int i = 0; i < sorted.size() && left > 0; i++) {
      Candidate c = sorted.get(i);
      int take = Math.min(c.free(), left);
      if (take > 0) {
        out.add(new Pick(c, take, i));
        left -= take;
      }
    }
    return out;
  }
}
