package com.pmart.cdc.location.domain;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * LOC 상태 전환(DRAFT→ACTIVE→BLOCKED/INACTIVE→RETIRED, 계약 cdc-location 0.2.0).
 *
 * <p>RETIRE 는 점유·미완료 예약이 없어야 한다. 점유는 cdc-inventory 정본이고 위치별로 물을 경로가 아직 없어, 재고가 생긴 적 없는 DRAFT 만
 * 폐기한다(INACTIVE 는 막음 — 점유를 0 으로 가정하지 않는다).
 */
public final class LocationStates {

  private LocationStates() {}

  private static final Map<String, Set<String>> FROM =
      Map.of(
          "ACTIVATE", Set.of("DRAFT", "INACTIVE"),
          "BLOCK", Set.of("ACTIVE"),
          "UNBLOCK", Set.of("BLOCKED"),
          "DEACTIVATE", Set.of("ACTIVE", "BLOCKED"),
          "RETIRE", Set.of("DRAFT"));

  private static final Map<String, String> TO =
      Map.of(
          "ACTIVATE", "ACTIVE",
          "BLOCK", "BLOCKED",
          "UNBLOCK", "ACTIVE",
          "DEACTIVATE", "INACTIVE",
          "RETIRE", "RETIRED");

  /**
   * @return 다음 상태, 허용되지 않으면 empty
   */
  public static Optional<String> next(String current, String action) {
    Set<String> from = FROM.get(action);
    if (from == null || !from.contains(current)) {
      return Optional.empty();
    }
    return Optional.of(TO.get(action));
  }
}
