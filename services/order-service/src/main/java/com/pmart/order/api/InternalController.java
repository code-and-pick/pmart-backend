package com.pmart.order.api;

import com.pmart.common.web.CommandResponse;
import com.pmart.common.web.PmartException;
import com.pmart.order.api.generated.InternalApi;
import com.pmart.order.api.generated.model.ApplyLineQuantityChangeRequest;
import com.pmart.order.api.generated.model.ArrivalWindow;
import com.pmart.order.api.generated.model.ConfirmOrderRequest;
import com.pmart.order.api.generated.model.DeliveryDestination;
import com.pmart.order.api.generated.model.DeliveryDestinationGeo;
import com.pmart.order.api.generated.model.DropInfo;
import com.pmart.order.api.generated.model.DropInfoGeo;
import com.pmart.order.api.generated.model.InternalOrder;
import com.pmart.order.api.generated.model.InternalOrderLinesInner;
import com.pmart.order.api.generated.model.OrderStatus;
import com.pmart.order.api.generated.model.StartCheckout202Response;
import com.pmart.order.application.OrderService;
import com.pmart.order.domain.OrderRules;
import com.pmart.order.infrastructure.OrderStore.LineRow;
import com.pmart.order.infrastructure.OrderStore.OrderRow;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 InternalApi — 주문 확정·주문 원장 조회·배차용 배송지(O-4)·주문행 수량 변경(출고 전 취소). 나머지 내부 API(탈퇴 확인·배송 사실·고객 약속)는
 * 인터페이스 default 가 501 을 돌려준다. 업무 규칙은 application/domain 에 두고 여기서는 요청·응답 변환만 한다.
 */
@RestController
public class InternalController implements InternalApi {

  private final OrderService orders;

  public InternalController(OrderService orders) {
    this.orders = orders;
  }

  @Override
  public ResponseEntity<StartCheckout202Response> confirmOrder(
      String orderId,
      String idempotencyKey,
      String xSourceSystem,
      ConfirmOrderRequest body,
      @Nullable Integer xExpectedVersion) {
    CommandResponse r =
        orders.confirm(
            orderId,
            idempotencyKey,
            xSourceSystem,
            new OrderService.Confirmation(
                body.getPaymentAttemptId(),
                body.getPgTransactionId(),
                body.getApprovedAmount(),
                body.getReservationId(),
                body.getCapacityHoldId(),
                body.getSlotHoldId()),
            xExpectedVersion);
    return ResponseEntity.ok(command(r));
  }

  @Override
  public ResponseEntity<StartCheckout202Response> applyLineQuantityChange(
      String orderId,
      String idempotencyKey,
      String xSourceSystem,
      ApplyLineQuantityChangeRequest body,
      @Nullable Integer xExpectedVersion) {
    CommandResponse r =
        orders.changeQuantities(
            orderId,
            idempotencyKey,
            xSourceSystem,
            new OrderService.QuantityChange(
                body.getReason() == null ? "" : body.getReason().getValue(),
                body.getSourceRef(),
                body.getClaimId(),
                body.getChanges().stream()
                    .map(c -> new OrderRules.Change(c.getOrderLineId(), c.getQty()))
                    .toList()),
            xExpectedVersion);
    return ResponseEntity.ok(command(r));
  }

  /**
   * 라이더용 전달지(O-5, 2026-11-14 — 클라이언트 BR-24). 공개 범위는 delivery-execution 이 판정. 사본 없으면 404. 개인정보 — 로그
   * 금지.
   */
  @Override
  public ResponseEntity<DropInfo> getOrderDropInfo(String orderId) {
    OrderService.OrderView v = orders.internalWithShipping(orderId);
    var s = v.shipping();
    if (s == null) {
      throw PmartException.notFound("DROP_INFO_NOT_FOUND", "주문 당시 배송지 사본이 없습니다.");
    }
    return ResponseEntity.ok(
        new DropInfo()
            .orderId(orderId)
            .roadAddress(s.roadAddress())
            .detailAddress(s.detailAddress())
            .postalCode(s.postalCode())
            .receiverName(s.receiverName())
            .receiverPhone(s.receiverPhone())
            .entranceMethod(
                s.entranceMethod() == null
                    ? null
                    : DropInfo.EntranceMethodEnum.fromValue(s.entranceMethod()))
            .entranceNote(s.entranceNote())
            .deliveryNote(v.order().deliveryNote())
            .geo(
                s.lat() == null || s.lng() == null
                    ? null
                    : new DropInfoGeo()
                        .lat(BigDecimal.valueOf(s.lat()))
                        .lng(BigDecimal.valueOf(s.lng()))));
  }

  /** 배차용 배송지(O-4, 2026-11-13) — 도로명주소·핀만, 수령인·연락처·출입 정보 없음. */
  @Override
  public ResponseEntity<DeliveryDestination> getOrderDeliveryDestination(String orderId) {
    OrderService.OrderView v = orders.internalWithShipping(orderId);
    OrderRow o = v.order();
    var s = v.shipping();
    DeliveryDestination out =
        new DeliveryDestination()
            .orderId(o.orderId())
            .mfcId(o.mfcId())
            .status(OrderStatus.fromValue(o.status()))
            .deliveryMode(DeliveryDestination.DeliveryModeEnum.fromValue(o.deliveryMode()))
            .confirmedAt(OffsetDateTime.ofInstant(o.confirmedAt(), ZoneOffset.UTC))
            .roadAddress(s == null ? null : s.roadAddress())
            .geo(
                s == null || s.lat() == null || s.lng() == null
                    ? null
                    : new DeliveryDestinationGeo()
                        .lat(BigDecimal.valueOf(s.lat()))
                        .lng(BigDecimal.valueOf(s.lng())));
    if (o.arrivalDate() != null && o.arrivalStart() != null && o.arrivalEnd() != null) {
      out.arrivalWindow(
          new ArrivalWindow().date(o.arrivalDate()).start(o.arrivalStart()).end(o.arrivalEnd()));
    }
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<InternalOrder> getInternalOrder(String orderId) {
    OrderService.OrderView v = orders.internal(orderId);
    OrderRow o = v.order();
    InternalOrder out =
        new InternalOrder()
            .orderId(o.orderId())
            .accountId(o.accountId())
            .status(OrderStatus.fromValue(o.status()))
            .version(o.version())
            .mfcId(o.mfcId())
            .paymentAttemptId(o.paymentAttemptId())
            .approvedAmount(Math.toIntExact(o.approvedAmount()))
            .itemAmount(Math.toIntExact(o.itemAmount()))
            .deliveryTip(Math.toIntExact(o.deliveryTip()))
            .bagFee(Math.toIntExact(o.bagFee()))
            .totalAmount(Math.toIntExact(o.totalAmount()))
            .firstPurchase(o.firstPurchaseApplied())
            .confirmedAt(OffsetDateTime.ofInstant(o.confirmedAt(), ZoneOffset.UTC));
    for (LineRow l : v.lines()) {
      out.addLinesItem(
          new InternalOrderLinesInner()
              .orderLineId(l.orderLineId())
              .sku(l.sku())
              .quantity(l.quantity())
              .unitPrice(Math.toIntExact(l.unitPrice()))
              .lineAmount(Math.toIntExact(l.lineAmount()))
              .discountShare(Math.toIntExact(l.discountShare()))
              .originalQty(l.originalQty())
              .unfulfilledQty(l.unfulfilledQty())
              .handedOverQty(l.handedOverQty())
              .cancelledBeforeShipQty(l.cancelledBeforeShipQty()));
    }
    return ResponseEntity.ok(out);
  }

  private static StartCheckout202Response command(CommandResponse r) {
    return new StartCheckout202Response()
        .status(StartCheckout202Response.StatusEnum.fromValue(r.status().name()))
        .requestId(r.requestId())
        .resultId(r.resultId())
        .version(r.version())
        .retryable(r.retryable())
        .nextAction(r.nextAction())
        .occurredAt(OffsetDateTime.ofInstant(r.occurredAt(), ZoneOffset.UTC));
  }
}
