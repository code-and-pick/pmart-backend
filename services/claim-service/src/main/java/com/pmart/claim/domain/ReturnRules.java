package com.pmart.claim.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * 반품(인계 뒤 회수) 규칙 — CL-1, D-7·ADR-0060 결정 2, 사용자 결정 2026-11-17 "추천대로". 프레임워크 없음.
 *
 * <ul>
 *   <li>접수는 고객센터(CS_AGENT·OPERATOR_ADMIN)만. 반품 가능 수량 = 인계 수량(라이더 픽업·전달) − 이 주문의 진행·완료 반품 수량.
 *   <li>회수비 9,000원(Delivery 381행) — 회사 잘못 사유 다섯 가지면 면제. 회수비는 상품 환불과 상계하지 않고 따로 결제.
 *   <li>1차 감액 없음: 검수 수량(RESALE·DISPOSE 모두) × 원단가 − 할인 안분 전액 환불(봉투값·배달팁 없음).
 *   <li>회수 실패: 첫 실패는 자동으로 한 번 더, 두 번째 시도부터 실패하면 상담원 재연락(RECOVERY_FAILED). 회수비는 한 번만.
 * </ul>
 */
public final class ReturnRules {

  private ReturnRules() {}

  /** 회수비(원) — 업무 원문 Delivery 381행, D-7(사용자 "응 그대로"). */
  public static final long RECOVERY_FEE = 9_000;

  /** 자동으로 내는 회수 요청 수(사용자 2026-11-17 "2번 시도 뒤 상담원 재연락"). */
  public static final int AUTO_PICKUP_ATTEMPTS = 2;

  /** 결제창 표시용 주문명. */
  public static final String FEE_ORDER_NAME = "반품 회수비";

  public static final String CS_AGENT = "CS_AGENT";
  public static final String OPERATOR_ADMIN = "OPERATOR_ADMIN";

  /** 반품 접수·후속 조치를 할 수 있는 역할(고객센터 — CS 접수와 같은 역할). */
  public static final Set<String> STAFF_ROLES = Set.of(CS_AGENT, OPERATOR_ADMIN);

  /** 회사 잘못 — 회수비 면제(사용자 2026-11-17 ②). */
  public static final Set<String> FAULT_CODES =
      Set.of("MISDELIVERY", "DEFECTIVE", "DAMAGED_IN_DELIVERY", "SEVERE_DELAY", "WRONG_SUBSTITUTE");

  public static final String ORDER_DETAILS_MATCH = "ORDER_DETAILS_MATCH";
  public static final String APP_SIGNED_IN_CHAT = "APP_SIGNED_IN_CHAT";
  public static final String OTHER = "OTHER";

  public static final Set<String> STAFF_CHANNELS = Set.of("CS", "OPERATOR");

  // 클레임 상태(RETURN)
  public static final String FEE_PENDING = "FEE_PENDING";
  public static final String IN_PROGRESS = "IN_PROGRESS";
  public static final String RECOVERY_FAILED = "RECOVERY_FAILED";
  public static final String CANCELLED = "CANCELLED";
  public static final String CLOSED = "CLOSED";

  // 회수비 상태
  public static final String FEE_WAITING = "WAITING";
  public static final String FEE_PAID = "PAID";
  public static final String FEE_UNPAID = "UNPAID";
  public static final String FEE_LATE_CANCEL_PENDING = "LATE_CANCEL_PENDING";
  public static final String FEE_LATE_CANCELLED = "LATE_CANCELLED";
  public static final String FEE_LATE_CANCEL_FAILED = "LATE_CANCEL_FAILED";

  /** 결과가 아직 안 난 결제 상태(payment PaymentState) — 기한이 지나도 결과를 기다린다(오배송 다시 배송비와 같은 규칙). */
  private static final Set<String> IN_FLIGHT = Set.of("PENDING", "UNKNOWN", "MISMATCH");

  /** 새 결제 시도가 필요한 상태. CREATED·PENDING 은 그대로 쓴다. */
  private static final Set<String> DEAD = Set.of("FAILED", "CANCELLED", "PARTIALLY_CANCELLED");

  /** 원장 행(심사·환불 근거). */
  public record OrderLine(
      String orderLineId,
      String sku,
      long unitPrice,
      int originalQty,
      int deliveredQty,
      long discountShare) {}

  /** 요청 행. */
  public record Requested(String orderLineId, int qty) {}

  /** 행별 반품 가능 수량 심사. 통과하면 아무것도 돌려주지 않고, 아니면 {@link ClaimRules.Rejection}. */
  public static void checkReturnable(
      List<OrderLine> lines, List<Requested> requested, Map<String, Integer> occupied) {
    Map<String, OrderLine> byId = new HashMap<>();
    int delivered = 0;
    for (OrderLine l : lines) {
      byId.put(l.orderLineId(), l);
      delivered += l.deliveredQty();
    }
    // 사용자 2026-11-17 "배달 중이나 전달 완료 전에는 반품 불가" — 인계(라이더 픽업)가 아니라 고객 전달 완료 수량으로 본다
    if (delivered == 0) {
      throw new ClaimRules.Rejection(
          "NOT_DELIVERED_YET", "고객에게 전달 완료된 상품이 없습니다 — 반품(회수)은 전달 완료 뒤에만 접수합니다.");
    }
    Map<String, Integer> seen = new HashMap<>();
    for (Requested r : requested) {
      OrderLine l = byId.get(r.orderLineId());
      if (l == null) {
        throw new ClaimRules.Rejection(
            "QUANTITY_EXCEEDS_AVAILABLE", "주문에 없는 상품입니다: " + r.orderLineId());
      }
      if (seen.put(r.orderLineId(), r.qty()) != null) {
        throw new ClaimRules.Rejection(
            "QUANTITY_EXCEEDS_AVAILABLE", "같은 상품이 두 번 들어 있습니다: " + r.orderLineId());
      }
      if (r.qty() < 1) {
        throw new ClaimRules.Rejection("QUANTITY_EXCEEDS_AVAILABLE", "수량은 1 이상입니다.");
      }
      int available = l.deliveredQty() - occupied.getOrDefault(r.orderLineId(), 0);
      if (l.deliveredQty() == 0) {
        throw new ClaimRules.Rejection(
            "NOT_DELIVERED_YET", "아직 전달 완료되지 않은 상품입니다: " + r.orderLineId());
      }
      if (r.qty() > available) {
        throw new ClaimRules.Rejection(
            "QUANTITY_EXCEEDS_AVAILABLE",
            "반품 가능한 수량("
                + Math.max(0, available)
                + " = 전달 완료 "
                + l.deliveredQty()
                + " − 진행·완료 반품 "
                + occupied.getOrDefault(r.orderLineId(), 0)
                + ")보다 많습니다: "
                + r.orderLineId());
      }
    }
  }

  /** 본인 확인 기록 검사 — 어긋나면 사람이 읽을 이유, 맞으면 null(case CS 접수와 같은 값). */
  public static @Nullable String identityRejection(@Nullable String method, @Nullable String note) {
    if (method == null || method.isBlank()) {
      return "본인 확인 방법(identityCheck.method)이 필요합니다.";
    }
    if (!Set.of(ORDER_DETAILS_MATCH, APP_SIGNED_IN_CHAT, OTHER).contains(method)) {
      return "알 수 없는 본인 확인 방법입니다: " + method;
    }
    if (OTHER.equals(method) && (note == null || note.isBlank())) {
      return "본인 확인 방법이 기타면 어떻게 확인했는지(identityCheck.note)를 적어야 합니다.";
    }
    return null;
  }

  /** 회수비 — 회사 잘못 사유가 있으면 0(면제). */
  public static long fee(@Nullable String faultCode) {
    return faultCode == null ? RECOVERY_FEE : 0;
  }

  public static boolean feeWaived(@Nullable String faultCode) {
    return faultCode != null;
  }

  /** 결제 주문 참조 — 원래 주문 결제와 별개(같은 주문에 승인된 결제가 있어도 새 시도를 막지 않게). */
  public static String feeChargeRef(String claimId) {
    return "RTF-" + claimId;
  }

  public static String feeRequestId(String claimId, int attemptNo) {
    return claimId + ":FEE:" + attemptNo;
  }

  public static String pickupRequestId(String claimId, int attemptNo) {
    return claimId + "-P" + attemptNo;
  }

  public static boolean feeInFlight(@Nullable String paymentState) {
    return paymentState != null && IN_FLIGHT.contains(paymentState);
  }

  public static boolean feeDead(@Nullable String paymentState) {
    return paymentState != null && DEAD.contains(paymentState);
  }

  public static boolean expired(Instant deadline, Instant now) {
    return !now.isBefore(deadline);
  }

  /** 이 시도가 실패하면 자동으로 다음 회수를 요청하나(첫 시도만). 그 밖은 상담원 재연락. */
  public static boolean autoRetryAfterFailure(int failedAttemptNo) {
    return failedAttemptNo < AUTO_PICKUP_ATTEMPTS;
  }

  /**
   * 검수 수량 환불액 = 단가 × 수량 − 할인 안분. 할인은 원수량 기준 누계 비례(원 미만 버림) — 앞서 검수한 수량까지의 몫을 빼서 이번 몫만. 원수량을 다 돌려주면
   * 할인 합이 정확히 discountShare 가 된다.
   *
   * @param inspectedBefore 이 행에서 이미 환불 근거가 된(검수된) 수량
   */
  public static long refundAmount(
      long unitPrice, int originalQty, long discountShare, int inspectedBefore, int qty) {
    if (qty <= 0) {
      return 0;
    }
    long discount = 0;
    if (discountShare > 0 && originalQty > 0) {
      long after = Math.floorDiv(discountShare * (inspectedBefore + qty), originalQty);
      long before = Math.floorDiv(discountShare * inspectedBefore, originalQty);
      discount = after - before;
    }
    return Math.max(0, unitPrice * qty - discount);
  }

  /** 클레임 행(배분 대상). */
  public record ClaimLineSlot(String claimLineId, String orderLineId, String sku, int room) {}

  /**
   * 들어온 수량(MFC 반입·검수 줄)을 클레임 행에 나눈다. orderLineId 가 있으면 그 행, 없으면 같은 SKU 행을 앞에서부터(남은 칸 room 까지). 넘는
   * 수량은 버린다(경고는 호출자).
   *
   * @return claimLineId → 배분 수량
   */
  public static Map<String, Integer> allocate(
      List<ClaimLineSlot> slots, @Nullable String orderLineId, String sku, int qty) {
    Map<String, Integer> out = new LinkedHashMap<>();
    int left = qty;
    List<ClaimLineSlot> candidates = new ArrayList<>();
    for (ClaimLineSlot s : slots) {
      if (orderLineId != null ? orderLineId.equals(s.orderLineId()) : sku.equals(s.sku())) {
        candidates.add(s);
      }
    }
    for (ClaimLineSlot s : candidates) {
      if (left <= 0) {
        break;
      }
      int take = Math.min(left, Math.max(0, s.room()));
      if (take > 0) {
        out.merge(s.claimLineId(), take, Integer::sum);
        left -= take;
      }
    }
    return out;
  }
}
