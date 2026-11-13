package com.pmart.delivery.execution.domain;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 배송 수행 규칙(상태 전이·픽업 완료 판정·증빙 사진 형식). 저장·사건과 무관한 순수 판단. */
public final class ExecutionRules {

  /** 사진 한 장 상한(계약 0.2.0). */
  public static final int MAX_PHOTO_BYTES = 10 * 1024 * 1024;

  /** 픽업 전 상태 — 배정 해제·픽업 전 취소가 먹는 상태. */
  public static final Set<String> BEFORE_PICKUP =
      Set.of("UNASSIGNED", "ASSIGNED", "ARRIVED_AT_STORE");

  /** 픽업 완료로 갈 수 있는 상태. 가게 도착을 누르지 않았어도 인계는 실물 사실이라 받아들인다(MFC 가 인계를 확정했다). */
  public static final Set<String> PICKUP_FROM = Set.of("ASSIGNED", "ARRIVED_AT_STORE");

  /** 증빙 사진을 올릴 수 있는 상태(원문 §6.2 — 전달 직전). */
  public static final Set<String> PHOTO_FROM = Set.of("PICKED_UP", "ARRIVED_AT_CUSTOMER");

  private ExecutionRules() {}

  /**
   * 픽업 완료(DE-1·D-6): 배송의 포장 목록을 알고, 그 포장 전부의 인계 사실이 있을 때만. 목록을 모르면 판단하지 않는다(첫 인계만으로 픽업 처리 금지 — PC 1
   * 검토 M4 와 같은 규칙).
   */
  public static boolean allHandedOver(
      Collection<String> knownPackageIds, Collection<String> handedPackageIds) {
    return !knownPackageIds.isEmpty() && handedPackageIds.containsAll(knownPackageIds);
  }

  /** 인계된 포장 줄을 주문 줄별 수량으로 합친다(DeliveryCompleted.lines). */
  public static List<Map<String, Object>> deliveredLines(
      Collection<Map<String, Integer>> perPackage) {
    Map<String, Integer> sum = new LinkedHashMap<>();
    for (Map<String, Integer> lines : perPackage) {
      lines.forEach((line, qty) -> sum.merge(line, qty, Integer::sum));
    }
    return sum.entrySet().stream()
        .<Map<String, Object>>map(e -> Map.of("orderLineId", e.getKey(), "qty", e.getValue()))
        .toList();
  }

  /** 파일 앞부분으로 형식 판정(이름·라이더가 보낸 Content-Type 은 믿지 않는다). JPEG·PNG·WEBP 만. */
  public static Optional<String> photoType(byte[] b) {
    if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
      return Optional.of("image/jpeg");
    }
    if (b.length >= 8
        && (b[0] & 0xFF) == 0x89
        && b[1] == 'P'
        && b[2] == 'N'
        && b[3] == 'G'
        && b[4] == 0x0D
        && b[5] == 0x0A
        && b[6] == 0x1A
        && b[7] == 0x0A) {
      return Optional.of("image/png");
    }
    if (b.length >= 12
        && b[0] == 'R'
        && b[1] == 'I'
        && b[2] == 'F'
        && b[3] == 'F'
        && b[8] == 'W'
        && b[9] == 'E'
        && b[10] == 'B'
        && b[11] == 'P') {
      return Optional.of("image/webp");
    }
    return Optional.empty();
  }
}
