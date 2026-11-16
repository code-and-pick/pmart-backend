package com.pmart.notification.domain;

import java.time.LocalDate;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * 고객 알림 문구(2026-11-16, 계약 0.2.0). 문구는 발송 당시 사실만 적는다 — 결과를 모르는 일(환불 요청만 된 상태 등)을 끝났다고 쓰지 않는다.
 * 개인정보(이름·주소·전화)는 넣지 않는다.
 */
public final class Notices {

  /** 알림 한 건의 내용. destinationType 은 계약 Destination.type. */
  public record Notice(
      String templateKey,
      String category,
      String title,
      String body,
      String destinationType,
      @Nullable String destinationRef) {}

  public static final String ORDER = "ORDER";
  public static final String DELIVERY = "DELIVERY";
  public static final String REFUND = "REFUND";
  public static final String CASE = "CASE";

  private Notices() {}

  public static Notice orderConfirmed(
      String orderId,
      @Nullable Long totalAmount,
      @Nullable String arrivalDate,
      @Nullable String start,
      @Nullable String end) {
    StringBuilder b = new StringBuilder();
    if (totalAmount != null) {
      b.append("결제 금액 ").append(won(totalAmount)).append(". ");
    }
    if (arrivalDate != null && start != null && end != null) {
      b.append("도착 예정 ")
          .append(koreanDate(arrivalDate))
          .append(' ')
          .append(start)
          .append('~')
          .append(end)
          .append(". ");
    }
    b.append("상품 준비를 시작하면 다시 알려 드릴게요.");
    return new Notice("ORDER_CONFIRMED", ORDER, "주문이 확정되었어요", b.toString(), "ORDER", orderId);
  }

  public static Notice preparationStarted(String orderId) {
    return new Notice(
        "PREPARATION_STARTED",
        ORDER,
        "상품 준비를 시작했어요",
        "준비가 끝나면 라이더가 출발해요. 이제 배송 시간은 바꿀 수 없어요.",
        "ORDER",
        orderId);
  }

  public static Notice pickedUp(String orderId) {
    return new Notice(
        "DELIVERY_STARTED",
        DELIVERY,
        "배송이 출발했어요",
        "라이더가 상품을 받아 출발했어요. 배송 현황은 주문 상세에서 볼 수 있어요.",
        "ORDER",
        orderId);
  }

  public static Notice delivered(String orderId) {
    return new Notice(
        "DELIVERY_COMPLETED",
        DELIVERY,
        "배송이 완료되었어요",
        "전달 사진은 주문 상세에서 볼 수 있어요. 받은 상품의 리뷰는 14일 안에 남길 수 있어요.",
        "ORDER",
        orderId);
  }

  /**
   * 오배송 다시 배송비 결제 필요(ADR-0065 — 고객이 잘못 입력한 주소로 배달됐고 실제 집이 200m 이상). 기한 30분은 ADR-0065 정한 값(결제 대기는
   * order-orchestration 이 정본 — 화면의 남은 시간은 고객 API deadlineAt). 주소는 쓰지 않는다.
   */
  public static Notice redeliveryPaymentRequired(String orderId, long fee) {
    return new Notice(
        "REDELIVERY_PAYMENT_REQUIRED",
        "PAYMENT",
        "실제 주소로 다시 보내려면 결제가 필요해요",
        "잘못 입력된 주소로 배달된 상품을 실제 주소로 다시 보내려면 "
            + won(fee)
            + " 결제가 필요해요. 30분 안에 결제하지 않으면 상품을 회수하고 상품값에서 "
            + won(fee)
            + "을 뺀 금액을 환불해 드려요. 주문 상세에서 결제할 수 있어요.",
        "ORDER",
        orderId);
  }

  /** 주문 전체 취소. 환불은 결과가 나온 뒤 따로 알린다. */
  public static Notice orderCancelled(String orderId, @Nullable String reason) {
    boolean outOfStock = "OUT_OF_STOCK".equals(reason);
    return new Notice(
        "ORDER_CANCELLED",
        ORDER,
        outOfStock ? "품절로 주문이 취소되었어요" : "주문이 취소되었어요",
        (outOfStock ? "준비하던 상품이 모두 품절되어 주문을 취소했어요. " : "") + "결제 금액 환불 결과가 나오면 다시 알려 드릴게요.",
        "ORDER",
        orderId);
  }

  /** 결품으로 품목 일부·전부 취소(주문은 계속). */
  public static Notice shortage(String orderId, @Nullable String productName) {
    String name = productName == null || productName.isBlank() ? "주문한 상품" : productName;
    return new Notice(
        "SHORTAGE_CANCELLED",
        ORDER,
        "일부 상품이 품절되어 취소되었어요",
        name + "의 일부 또는 전부가 품절되어 취소했어요. 나머지 상품은 그대로 보내 드려요. 환불 결과가 나오면 다시 알려 드릴게요.",
        "ORDER",
        orderId);
  }

  /** 환불 결과. 성공·실패만 알린다(결과 모름은 알리지 않는다). claimId 가 없으면 주문으로 간다. */
  public static @Nullable Notice refund(
      String result, @Nullable Long amount, String orderId, @Nullable String claimId) {
    String type = claimId == null ? "ORDER" : "REFUND";
    String ref = claimId == null ? orderId : claimId;
    return switch (result) {
      case "SUCCESS" ->
          new Notice(
              "REFUND_SUCCEEDED",
              REFUND,
              "환불이 완료되었어요",
              (amount == null ? "결제 금액을" : won(amount) + "을")
                  + " 결제 수단으로 돌려드렸어요. 카드사에 따라 반영까지 며칠 걸릴 수 있어요.",
              type,
              ref);
      case "FAILED" ->
          new Notice(
              "REFUND_DELAYED",
              REFUND,
              "환불 처리가 늦어지고 있어요",
              "확인한 뒤 다시 처리할게요. 처리되면 다시 알려 드릴게요.",
              type,
              ref);
      default -> null;
    };
  }

  public static Notice inquiryAnswered(String inquiryId) {
    return new Notice(
        "INQUIRY_ANSWERED",
        CASE,
        "문의에 답변이 등록되었어요",
        "고객센터에 남긴 문의의 답변을 확인해 주세요.",
        "INQUIRY",
        inquiryId);
  }

  /** 12,300원. */
  public static String won(long amount) {
    return String.format(Locale.ROOT, "%,d원", amount);
  }

  /** 2026-11-17 → 11월 17일(형식이 다르면 그대로). */
  static String koreanDate(String isoDate) {
    try {
      LocalDate d = LocalDate.parse(isoDate);
      return d.getMonthValue() + "월 " + d.getDayOfMonth() + "일";
    } catch (RuntimeException e) {
      return isoDate;
    }
  }
}
