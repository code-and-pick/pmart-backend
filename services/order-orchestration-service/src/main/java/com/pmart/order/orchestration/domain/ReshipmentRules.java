package com.pmart.order.orchestration.domain;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * 오배송 재발송 사가 규칙(2026-11-16 — ADR-0052 결정 4 "같은 상품·수량을 다시 확보·피킹·포장해 원주소로, 재고가 모자란 품목은 그 품목만 부분
 * 취소·환불", 결정 5 "같은 피해 수량을 새 발송과 환불로 겹치지 않는다"). 저장·호출과 무관한 순수 판단.
 */
public final class ReshipmentRules {

  public static final String RESOLVE_ORDER = "RESOLVE_ORDER";
  public static final String RECORD_RESHIPMENT = "RECORD_RESHIPMENT";
  public static final String RESERVE_INVENTORY = "RESERVE_INVENTORY";
  public static final String CONFIRM_RESERVATION = "CONFIRM_RESERVATION";
  public static final String REQUEST_WORK = "REQUEST_WORK";
  public static final String APPLY_SHORTAGE = "APPLY_SHORTAGE";
  public static final String REQUEST_REFUND = "REQUEST_REFUND";

  /** 고객 잘못 오배송 회수 후 환불의 공제(원) — 업무 원문 Delivery §9.2 "회수재배송 9000원", ADR-0065(사용자 "상품값 − 9,000원"). */
  public static final long CUSTOMER_FAULT_FEE = 9_000;

  /** 재고 부족으로 줄여 다시 예약하는 최대 횟수(설계값 — 재고가 그사이 계속 줄어드는 경우의 상한). */
  public static final int MAX_RESERVE_ATTEMPTS = 5;

  /** 줄 하나(주문행·SKU·수량, 상품명은 MFC 단말 표시용 사본). */
  public record Line(String orderLineId, String sku, int qty, @Nullable String productName) {}

  /**
   * 다음 단계. 예약된 줄이 없으면 예약 확정·작업 요청을 건너뛰고, 못 보내는 줄이 없으면 작업 요청 뒤 끝(null).
   *
   * @param reserved 예약된 줄이 있는가
   * @param short_ 못 보내는 줄이 있는가
   */
  public static @Nullable String next(String step, boolean reserved, boolean short_) {
    return switch (step) {
      case RESOLVE_ORDER -> RECORD_RESHIPMENT;
      case RECORD_RESHIPMENT -> RESERVE_INVENTORY;
      case RESERVE_INVENTORY -> reserved ? CONFIRM_RESERVATION : short_ ? APPLY_SHORTAGE : null;
      case CONFIRM_RESERVATION -> REQUEST_WORK;
      case REQUEST_WORK -> short_ ? APPLY_SHORTAGE : null;
      case APPLY_SHORTAGE -> REQUEST_REFUND;
      default -> null;
    };
  }

  /** 하위 명령 키 — 같은 단계 재전송은 같은 키. 예약은 줄인 수량마다 다른 키(다른 본문). */
  public static String requestId(String sagaId, String step, int attempt) {
    return RESERVE_INVENTORY.equals(step)
        ? sagaId + ":" + step + ":" + attempt
        : sagaId + ":" + step;
  }

  /**
   * 재발송 출고 ID — 사건마다 하나로 정한다(같은 사건을 다시 받아도 같은 출고). {@code <orderId>-RS-<8자>}(MFC 출고 80자, 작업 ID 에
   * -PICK 이 붙어도 96자 안).
   */
  public static String shipmentId(String orderId, String caseId) {
    String h =
        UUID.nameUUIDFromBytes(("reshipment:" + caseId).getBytes(StandardCharsets.UTF_8))
            .toString()
            .substring(0, 8);
    return orderId + "-RS-" + h;
  }

  /** 전달 실패(사고·파손)로 시작한 재발송의 근거 — 실패한 배송마다 하나(2026-11-18). */
  public static final String FAILURE_REF_PREFIX = "DF-";

  /** 재발송이 일어나는 전달 실패 사유 — 사용자 2026-11-18 "사고나면 재배송", "파손도 재배송". */
  public static final java.util.Set<String> RESHIP_FAILURE_REASONS =
      java.util.Set.of("RIDER_INCIDENT", "DAMAGED");

  public static String failureRef(String deliveryId) {
    return FAILURE_REF_PREFIX + deliveryId;
  }

  public static boolean fromFailure(String caseId) {
    return caseId.startsWith(FAILURE_REF_PREFIX);
  }

  /**
   * 재발송을 거는 배송 — 일반 배송(FORWARD)과 앞선 재발송 배송(RESHIPMENT·MISDELIVERY_RESHIPMENT)만. 회수·반납·오배송 다시 배송(잘못
   * 둔 곳 → 실제 집, 원래 상품)은 아니다.
   */
  public static boolean reshipsOnFailure(String deliveryType, String purpose, String reason) {
    if (!RESHIP_FAILURE_REASONS.contains(reason)) {
      return false;
    }
    return "FORWARD".equals(deliveryType)
        || ("RESHIPMENT".equals(deliveryType) && "MISDELIVERY_RESHIPMENT".equals(purpose));
  }

  /**
   * 전달 실패 재발송 수량 = 원수량 + 재발송 − 출고 전 취소 − 전달됨 − 이미 재발송(2026-11-18). 실패한 출고는 전달되지 않았으므로 아직 고객에게 가지 않은
   * 만큼 — 주문 첫 배송이 실패하면 원수량 − 출고 전 취소. 오배송 재발송 배송이 사고 나면 오배송 상품이 전달됨으로 세어져 0 이 될 수 있다 — 그때는 상신(수동).
   */
  public static int failedResendQty(int originalQty, int cancelledBeforeShipQty, int deliveredQty) {
    return originalQty - cancelledBeforeShipQty - deliveredQty;
  }

  /**
   * 보낼 수량 = 원수량 − 출고 전 취소 − 이미 재발송(배송된 만큼 다시 — 출고는 주문당 하나, D6-2).
   *
   * @return 0 이하면 보낼 것이 없다
   */
  public static int resendQty(int originalQty, int cancelledBeforeShipQty, int reshippedQty) {
    return originalQty - cancelledBeforeShipQty - reshippedQty;
  }

  /**
   * 재고 부족 거절(INSUFFICIENT_STOCK)의 details 로 줄을 줄인다. details 한 줄 = field SKU, reason {@code
   * requestedQty=..;availableQty=..}(센터 가용 부족 — 그 SKU 합계를 가용까지) 또는 {@code
   * NO_ELIGIBLE_LOT;orderLineId=..;requestedQty=..;eligibleQty=..}(그 줄을 적격까지). 줄일 수 없으면(해석 안 됨)
   * null.
   *
   * @return [0] 줄인 뒤 예약할 줄(수량 0 줄은 뺌), [1] 줄인 만큼(못 보내는 줄)
   */
  public static @Nullable List<List<Line>> shrink(
      List<Line> lines, List<Map<String, String>> details) {
    Map<String, Integer> skuAvailable = new LinkedHashMap<>();
    Map<String, Integer> lineEligible = new LinkedHashMap<>();
    for (Map<String, String> d : details) {
      String sku = d.get("field");
      Map<String, String> kv = parse(d.getOrDefault("reason", ""));
      if (kv.containsKey("availableQty") && sku != null) {
        skuAvailable.put(sku, Math.max(0, toInt(kv.get("availableQty"))));
      } else if (kv.containsKey("eligibleQty") && kv.containsKey("orderLineId")) {
        lineEligible.put(kv.get("orderLineId"), Math.max(0, toInt(kv.get("eligibleQty"))));
      }
    }
    if (skuAvailable.isEmpty() && lineEligible.isEmpty()) {
      return null;
    }
    List<Line> keep = new ArrayList<>();
    List<Line> cut = new ArrayList<>();
    Map<String, Integer> skuLeft = new LinkedHashMap<>(skuAvailable);
    for (Line l : lines) {
      int allowed = l.qty();
      if (lineEligible.containsKey(l.orderLineId())) {
        allowed = Math.min(allowed, lineEligible.get(l.orderLineId()));
      }
      if (skuLeft.containsKey(l.sku())) {
        int left = skuLeft.get(l.sku());
        allowed = Math.min(allowed, left);
        skuLeft.put(l.sku(), left - allowed);
      }
      if (allowed > 0) {
        keep.add(new Line(l.orderLineId(), l.sku(), allowed, l.productName()));
      }
      if (allowed < l.qty()) {
        cut.add(new Line(l.orderLineId(), l.sku(), l.qty() - allowed, l.productName()));
      }
    }
    if (cut.isEmpty()) {
      return null; // 거절인데 줄일 것이 없다 — 해석이 맞지 않는다
    }
    return List.of(keep, cut);
  }

  /** 못 보내는 줄 합치기(같은 주문행은 더한다). */
  public static List<Line> merge(List<Line> a, List<Line> b) {
    Map<String, Line> out = new LinkedHashMap<>();
    for (Line l : a) {
      out.merge(
          l.orderLineId(),
          l,
          (x, y) -> new Line(x.orderLineId(), x.sku(), x.qty() + y.qty(), x.productName()));
    }
    for (Line l : b) {
      out.merge(
          l.orderLineId(),
          l,
          (x, y) -> new Line(x.orderLineId(), x.sku(), x.qty() + y.qty(), x.productName()));
    }
    return new ArrayList<>(out.values());
  }

  private static Map<String, String> parse(String reason) {
    Map<String, String> kv = new LinkedHashMap<>();
    for (String part : reason.split(";")) {
      int i = part.indexOf('=');
      if (i > 0) {
        kv.put(part.substring(0, i).trim(), part.substring(i + 1).trim());
      }
    }
    return kv;
  }

  private static int toInt(String s) {
    try {
      return Integer.parseInt(s);
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private ReshipmentRules() {}
}
