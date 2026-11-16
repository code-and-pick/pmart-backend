package com.pmart.order.api;

import com.pmart.order.api.generated.OrderLookupsApi;
import com.pmart.order.api.generated.model.ArrivalWindow;
import com.pmart.order.api.generated.model.OrderStatus;
import com.pmart.order.api.generated.model.StaffOrderAmounts;
import com.pmart.order.api.generated.model.StaffOrderDetail;
import com.pmart.order.api.generated.model.StaffOrderDetailPayment;
import com.pmart.order.api.generated.model.StaffOrderSearchResult;
import com.pmart.order.api.generated.model.StaffOrderSearchResultPage;
import com.pmart.order.api.generated.model.StaffOrderSummary;
import com.pmart.order.api.generated.model.StaffPiiField;
import com.pmart.order.api.generated.model.StaffPiiReveal;
import com.pmart.order.api.generated.model.StaffPiiRevealRequest;
import com.pmart.order.application.StaffOrderService;
import com.pmart.order.domain.StaffOrderRules.PiiField;
import com.pmart.order.infrastructure.OrderStore.OrderRow;
import com.pmart.order.infrastructure.OrderStore.StaffSummary;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 OrderLookupsApi — 직원용 주문 검색·상세(0.3.0, O-6), 배송지 원문 열람(0.4.0 — 사유 + audit 기록). 권한·기간 상한·가림·기록은
 * {@link StaffOrderService}. 요청·응답 변환만 한다. 개인정보(연락처·배송지)·열람 사유는 로그에 남기지 않는다.
 */
@RestController
public class OrderLookupsController implements OrderLookupsApi {

  private final StaffOrderService service;

  public OrderLookupsController(StaffOrderService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<StaffOrderSearchResult> searchOrdersForStaff(
      @Nullable String orderId,
      @Nullable String customerId,
      @Nullable OrderStatus status,
      @Nullable String mfcId,
      @Nullable String receiverPhone,
      @Nullable OffsetDateTime createdFrom,
      @Nullable OffsetDateTime createdTo,
      Integer page,
      Integer size) {
    StaffOrderService.Page p =
        service.search(
            new StaffOrderService.Query(
                orderId,
                customerId,
                status == null ? null : status.getValue(),
                mfcId,
                receiverPhone,
                createdFrom == null ? null : createdFrom.toInstant(),
                createdTo == null ? null : createdTo.toInstant(),
                page,
                size));
    return ResponseEntity.ok(
        new StaffOrderSearchResult()
            .items(p.items().stream().map(OrderLookupsController::summary).toList())
            .page(
                new StaffOrderSearchResultPage()
                    .page(p.page())
                    .size(p.size())
                    .hasNext(p.hasNext())));
  }

  @Override
  public ResponseEntity<StaffOrderDetail> getOrderForStaff(String orderId) {
    StaffOrderService.Detail d = service.get(orderId);
    OrderRow o = d.order();
    return ResponseEntity.ok(
        new StaffOrderDetail()
            .orderId(o.orderId())
            .customerId(o.accountId())
            .status(OrderStatus.fromValue(o.status()))
            .version(o.version())
            .mfcId(o.mfcId())
            .deliveryMode(StaffOrderDetail.DeliveryModeEnum.fromValue(o.deliveryMode()))
            .arrivalWindow(window(o.arrivalDate(), o.arrivalStart(), o.arrivalEnd()))
            .confirmedAt(o.confirmedAt().atOffset(ZoneOffset.UTC))
            .outOfStockPreference(
                StaffOrderDetail.OutOfStockPreferenceEnum.fromValue(o.outOfStockPreference()))
            // 배송 요청사항은 가린다 — 공동현관 번호 등이 들어갈 수 있어 ENTRANCE 열람으로만(ADR-0062, 0.5.0)
            .deliveryNote(null)
            .deliveryNoteMasked(o.deliveryNote() != null && !o.deliveryNote().isBlank())
            .weightG(o.weightG())
            .plannedShipments(o.plannedShipments())
            .amounts(
                new StaffOrderAmounts()
                    .itemAmount((int) o.itemAmount())
                    .deliveryTipBase((int) o.deliveryTipBase())
                    .deliveryTip((int) o.deliveryTip())
                    .deliveryTipReason(o.deliveryTipReason())
                    .bagFee((int) o.bagFee())
                    .totalAmount((int) o.totalAmount())
                    .approvedAmount((int) o.approvedAmount())
                    .firstPurchaseApplied(o.firstPurchaseApplied())
                    .quoteVersion(o.quoteVersion())
                    .priceVersion(o.priceVersion()))
            .payment(
                new StaffOrderDetailPayment()
                    .paymentAttemptId(o.paymentAttemptId())
                    .pgTransactionId(o.pgTransactionId()))
            .shippingAddress(ShippingMapping.of(d.shipping()))
            .shippingAddressMasked(true)
            .lines(d.lines().stream().map(OrdersController::line).toList())
            .fulfillmentIssues(
                d.fulfillmentIssues().stream().map(FulfillmentIssuesController::dto).toList()));
  }

  @Override
  public ResponseEntity<StaffPiiReveal> revealOrderPiiForStaff(
      String orderId, String idempotencyKey, String xSourceSystem, StaffPiiRevealRequest body) {
    Set<PiiField> fields = null;
    if (body.getFields() != null && !body.getFields().isEmpty()) {
      fields = EnumSet.noneOf(PiiField.class);
      for (StaffPiiField f : body.getFields()) {
        fields.add(PiiField.valueOf(f.getValue()));
      }
    }
    StaffOrderService.Reveal r =
        service.reveal(orderId, idempotencyKey, xSourceSystem, body.getReason(), fields);
    return ResponseEntity.ok(
        new StaffPiiReveal()
            .orderId(r.orderId())
            .piiAccessLogId(r.piiAccessLogId())
            .recordedAt(r.recordedAt().atOffset(ZoneOffset.UTC))
            .fields(r.fields().stream().map(f -> StaffPiiField.fromValue(f.name())).toList())
            .shippingAddress(ShippingMapping.of(r.shipping()))
            .deliveryNote(r.deliveryNote()));
  }

  @Override
  public ResponseEntity<com.pmart.order.api.generated.model.RedeliveryDestination>
      recordRedeliveryDestination(
          String orderId,
          String idempotencyKey,
          String xSourceSystem,
          com.pmart.order.api.generated.model.RedeliveryDestinationRequest body) {
    var d =
        service.recordRedeliveryDestination(
            orderId,
            idempotencyKey,
            body.getDeliveryId(),
            body.getRoadAddress(),
            body.getDetailAddress(),
            body.getPostalCode(),
            body.getReason());
    // 직원 응답에는 주소를 되돌려 주지 않는다
    return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED)
        .body(
            new com.pmart.order.api.generated.model.RedeliveryDestination()
                .destinationId(d.destinationId())
                .orderId(d.orderId())
                .deliveryId(d.deliveryId())
                .recordedAt(d.recordedAt().atOffset(ZoneOffset.UTC)));
  }

  private static StaffOrderSummary summary(StaffOrderService.Row r) {
    StaffSummary s = r.summary();
    return new StaffOrderSummary()
        .orderId(s.orderId())
        .customerId(s.accountId())
        .status(OrderStatus.fromValue(s.status()))
        .version(s.version())
        .mfcId(s.mfcId())
        .deliveryMode(StaffOrderSummary.DeliveryModeEnum.fromValue(s.deliveryMode()))
        .arrivalWindow(window(s.arrivalDate(), s.arrivalStart(), s.arrivalEnd()))
        .confirmedAt(s.confirmedAt().atOffset(ZoneOffset.UTC))
        .totalAmount((int) s.totalAmount())
        .approvedAmount((int) s.approvedAmount())
        .itemCount(s.itemCount())
        .lineCount(s.lineCount())
        .plannedShipments(s.plannedShipments())
        .openFulfillmentIssues(s.openFulfillmentIssues())
        .receiverPhoneMasked(r.receiverPhoneMasked());
  }

  private static @Nullable ArrivalWindow window(
      @Nullable LocalDate date, @Nullable String start, @Nullable String end) {
    return date == null ? null : new ArrivalWindow().date(date).start(start).end(end);
  }
}
