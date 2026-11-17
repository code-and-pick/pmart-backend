package com.pmart.notification.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
  public static final String SYSTEM = "SYSTEM";

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

  /** 고객 반품 회수 배송이 생김(2026-11-17) — 근처 기사를 찾는 중. */
  public static Notice returnPickupDispatching(String orderId) {
    return new Notice(
        "RETURN_PICKUP_DISPATCHING",
        DELIVERY,
        "반품 회수 기사를 배정하고 있어요",
        "반품 상품을 받으러 갈 기사를 찾고 있어요. 상품을 문 앞 등 약속한 곳에 준비해 주세요.",
        "ORDER",
        orderId);
  }

  /** 고객 반품 상품을 기사가 받음(2026-11-17). 환불은 MFC 검수 뒤. */
  public static Notice returnPickedUp(String orderId) {
    return new Notice(
        "RETURN_PICKED_UP",
        DELIVERY,
        "반품 상품을 회수했어요",
        "기사가 반품 상품을 받았어요. 매장에서 상품을 확인한 뒤 환불을 진행해요.",
        "ORDER",
        orderId);
  }

  /** 고객 사정으로 회수하지 못함(2026-11-17). 다시 방문·연락은 반품 처리 쪽이 정한다 — 약속하지 않는다. */
  public static Notice returnPickupMissed(String orderId) {
    return new Notice(
        "RETURN_PICKUP_MISSED",
        DELIVERY,
        "반품 상품을 회수하지 못했어요",
        "기사가 방문했지만 상품을 받지 못했어요. 다시 방문할지 곧 안내해 드릴게요.",
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

  /**
   * 배송 지연 가능 안내(CP-1, 2026-11-16 — 결정 2026-11-15 "이미 잡힌 주문은 유지하고 지연될 수 있다고 안내, 고객이 원하면 취소·시간대 변경").
   * 시간대 변경은 아직 없어 취소만 안내한다(고객 취소는 상품 준비 전까지). 도착 시간대가 있으면 Asia/Seoul 로 적는다.
   */
  public static Notice mayBeDelayed(
      String orderId,
      java.time.@Nullable Instant arrivalStart,
      java.time.@Nullable Instant arrivalEnd) {
    String when = "";
    if (arrivalStart != null && arrivalEnd != null) {
      java.time.ZoneId seoul = java.time.ZoneId.of("Asia/Seoul");
      var s = arrivalStart.atZone(seoul);
      var e = arrivalEnd.atZone(seoul);
      when =
          String.format(
              Locale.ROOT,
              "%d월 %d일 %02d:%02d~%02d:%02d 도착 예정 ",
              s.getMonthValue(),
              s.getDayOfMonth(),
              s.getHour(),
              s.getMinute(),
              e.getHour(),
              e.getMinute());
    }
    return new Notice(
        "DELIVERY_MAY_BE_DELAYED",
        DELIVERY,
        "배송이 늦어질 수 있어요",
        "센터 사정으로 "
            + when
            + "배송이 늦어질 수 있어요. 기다리기 어려우시면 상품 준비가 시작되기 전까지 "
            // 예약배달(도착 시간대가 있는 주문)은 S4 시간 변경도 된다(2026-11-16)
            + (when.isEmpty() ? "" : "다른 시간대로 바꾸거나 ")
            + "주문을 취소할 수 있어요.",
        "ORDER",
        orderId);
  }

  /** 예약배달 시간 변경 확정(S4, 2026-11-16 — ReservationTimeChanged). 새 도착 시간대를 적는다. */
  public static Notice reservationTimeChanged(
      String orderId, @Nullable String date, @Nullable String start, @Nullable String end) {
    String when =
        date != null && start != null && end != null
            ? koreanDate(date) + " " + start + "~" + end + "에 도착할 예정이에요."
            : "주문 상세에서 새 도착 시간을 확인해 주세요.";
    return new Notice("RESERVATION_TIME_CHANGED", ORDER, "예약 시간이 바뀌었어요", when, "ORDER", orderId);
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

  // ---------------------------------------------------------------- 라이더(2026-11-16 §11-5)

  /** 도움요청 목적지 참조 = 배송 ID + ":" + 요청 ID(라이더 앱이 GET /deliveries/{id}/help-requests/{requestId}). */
  static @Nullable String helpRef(@Nullable String deliveryId, @Nullable String requestId) {
    return deliveryId == null || requestId == null ? null : deliveryId + ":" + requestId;
  }

  public static Notice helpRequestReplied(String deliveryId, String requestId) {
    return new Notice(
        "HELP_REQUEST_REPLIED",
        CASE,
        "지원센터 답장이 왔어요",
        "도움요청에 지원센터가 답했어요. 대화를 확인해 주세요.",
        "HELP_REQUEST",
        helpRef(deliveryId, requestId));
  }

  public static Notice helpRequestClosed(@Nullable String deliveryId, @Nullable String requestId) {
    String ref = helpRef(deliveryId, requestId);
    return new Notice(
        "HELP_REQUEST_CLOSED",
        CASE,
        "도움요청 처리가 끝났어요",
        "지원센터가 도움요청 처리를 마쳤어요. 더 필요한 것이 있으면 다시 도움요청을 보내 주세요.",
        ref == null ? "NONE" : "HELP_REQUEST",
        ref);
  }

  /**
   * 지원센터 수행료 조정(2026-11-17 — 산정 불가였던 배송). 지급이면 금액·지급 예정일, 미지급이면 그 사실만(사유는 지원센터 문의). 목적지는 정산 화면이 따로
   * 없어 NONE.
   */
  public static Notice earningAdjusted(
      @Nullable String deliveryId,
      boolean pay,
      @Nullable Long amount,
      @Nullable String payoutDate) {
    if (!pay) {
      return new Notice(
          "RIDER_EARNING_ADJUSTED",
          SYSTEM,
          "수행료가 조정되었어요",
          "지원센터 확인 결과 이 배송의 수행료는 지급되지 않아요. 자세한 내용은 지원센터에 문의해 주세요.",
          "NONE",
          null);
    }
    String when = "";
    if (payoutDate != null) {
      java.time.LocalDate d = java.time.LocalDate.parse(payoutDate);
      when = " " + d.getMonthValue() + "월 " + d.getDayOfMonth() + "일에 지급될 예정이에요.";
    }
    String money = amount == null ? "수행료가" : String.format(Locale.KOREAN, "%,d원이", amount);
    return new Notice(
        "RIDER_EARNING_ADJUSTED",
        SYSTEM,
        "수행료가 조정되었어요",
        "지원센터 확인 결과 " + money + " 지급으로 확정되었어요." + when,
        "NONE",
        null);
  }

  public static Notice riderInquiryAnswered(String inquiryId) {
    return new Notice(
        "RIDER_INQUIRY_ANSWERED",
        CASE,
        "문의에 답변이 등록되었어요",
        "지원센터에 남긴 문의의 답변을 확인해 주세요.",
        "INQUIRY",
        inquiryId);
  }

  /**
   * 계정 제한·해제. 사유·근거는 싣지 않는다(지원센터에 문의). 모르는 제한 종류는 알리지 않는다(null). 정상 → 정상(제한이 없었는데 "풀렸어요")도 알리지 않는다
   * — rider-service 는 이 변경을 거절하지만 받는 쪽에서도 막는다(PC 1 #73 검토 후속, 2026-11-17).
   */
  public static @Nullable Notice riderRestriction(
      String restriction, @Nullable String previous, @Nullable Instant until) {
    if ("NORMAL".equals(restriction) && "NORMAL".equals(previous)) {
      return null;
    }
    String untilText =
        until == null
            ? ""
            : " "
                + DateTimeFormatter.ofPattern("M월 d일 HH:mm", Locale.KOREAN)
                    .format(until.atZone(ZoneId.of("Asia/Seoul")))
                + "까지예요.";
    return switch (restriction) {
      case "NORMAL" ->
          new Notice(
              "RIDER_RESTRICTION_LIFTED",
              SYSTEM,
              "계정 제한이 풀렸어요",
              "다시 배차를 받을 수 있어요. 운행 중이면 신규 배차를 켜 주세요.",
              "NONE",
              null);
      case "DISPATCH_RESTRICTED" ->
          new Notice(
              "RIDER_DISPATCH_RESTRICTED",
              SYSTEM,
              "새 배차가 제한되었어요",
              "지금 맡은 배송은 끝까지 전달해 주세요." + untilText + " 자세한 내용은 지원센터에 문의해 주세요.",
              "NONE",
              null);
      case "SUSPENDED" ->
          new Notice(
              "RIDER_SUSPENDED",
              SYSTEM,
              "계정이 정지되었어요",
              "지금 맡은 배송은 끝까지 전달해 주세요." + untilText + " 자세한 내용은 지원센터에 문의해 주세요.",
              "NONE",
              null);
      case "TERMINATED" ->
          new Notice(
              "RIDER_TERMINATED",
              SYSTEM,
              "계정 이용이 종료되었어요",
              "지금 맡은 배송은 끝까지 전달해 주세요. 자세한 내용은 지원센터에 문의해 주세요.",
              "NONE",
              null);
      default -> null;
    };
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
