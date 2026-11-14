package com.pmart.order.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.CommandResponse;
import com.pmart.common.web.CommandStatus;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.order.domain.OrderRules;
import com.pmart.order.domain.ShippingAddress;
import com.pmart.order.infrastructure.DraftStore;
import com.pmart.order.infrastructure.DraftStore.DraftRow;
import com.pmart.order.infrastructure.OrderStore;
import com.pmart.order.infrastructure.OrderStore.LineRow;
import com.pmart.order.infrastructure.OrderStore.OrderRow;
import com.pmart.order.infrastructure.Upstreams;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 주문 확정(S1 g~j 의 order 단계)·주문 조회. orchestration 이 PG 승인 대사 일치와 재고·여력·슬롯 확정을 마친 뒤 부른다 — 이 서비스는 결제·자원
 * 성공을 다시 판정하지 않고, 초안의 동결 금액과 승인 금액이 같은지만 확인해 초안 스냅샷을 주문으로 옮긴다.
 *
 * <ul>
 *   <li>주문 저장·초안 닫기·{@code OrderConfirmed}(Outbox, 토픽 order.order.v1)는 한 로컬 트랜잭션.
 *   <li>같은 키는 저장 결과 재생. 이미 확정된 주문은 다른 키로 와도 200(사건을 다시 내지 않는다).
 *   <li>승인 금액 ≠ 초안 총액 → 422 AMOUNT_MISMATCH(확정 차단, 사가가 대사 대상으로 넘긴다). 주문 ID 를 가진 초안이 없으면 404.
 *   <li>같은 초안이 이미 다른 주문 ID 로 확정됐으면 409 INVALID_STATE.
 * </ul>
 */
@Service
public class OrderService {

  public static final String TOPIC = "order.order.v1";

  private static final Logger log = LoggerFactory.getLogger(OrderService.class);

  /** orchestration 이 보낸 확정 근거(PG 승인·자원 참조). */
  public record Confirmation(
      String paymentAttemptId,
      String pgTransactionId,
      long approvedAmount,
      String reservationId,
      @Nullable String capacityHoldId,
      @Nullable String slotHoldId) {}

  /** shipping: 주문 당시 배송지 사본(BR-37) — 본인 조회만 채운다(내부 조회는 null). */
  public record OrderView(
      OrderRow order, List<LineRow> lines, @Nullable ShippingAddress shipping) {}

  public record Page(List<OrderStore.Summary> items, @Nullable String nextCursor, int size) {}

  private final DraftStore drafts;
  private final OrderStore orders;
  private final Upstreams upstreams;
  private final RequestIdempotency idempotency;
  private final EventPublisher events;
  private final EventEnvelopeCodec codec;
  private final ObjectMapper mapper;
  private final Clock clock;

  public OrderService(
      DraftStore drafts,
      OrderStore orders,
      Upstreams upstreams,
      RequestIdempotency idempotency,
      EventPublisher events,
      EventEnvelopeCodec codec,
      ObjectMapper mapper,
      Clock clock) {
    this.drafts = drafts;
    this.orders = orders;
    this.upstreams = upstreams;
    this.idempotency = idempotency;
    this.events = events;
    this.codec = codec;
    this.mapper = mapper;
    this.clock = clock;
  }

  // ---------- 주문 확정 ----------

  public CommandResponse confirm(
      String orderId,
      String requestId,
      String sourceSystem,
      Confirmation c,
      @Nullable Integer expectedVersion) {
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("orderId", orderId);
    request.put("confirmation", c);
    // 결제 확정 단계 전에 만든 초안은 SKU 구성 수(unitsPerSku)가 없다 — 트랜잭션 밖에서 product 로 먼저 채운다
    Map<String, Integer> unitsPerSku = legacyUnits(orderId);
    return idempotency
        .execute(
            new RequestKey(sourceSystem, requestId, "confirmOrder", actor()),
            request,
            CommandResponse.class,
            () -> {
              Instant now = clock.instant();
              DraftRow row =
                  drafts
                      .lockByOrderId(orderId)
                      .orElseThrow(() -> PmartException.notFound("ORDER_NOT_FOUND", "주문이 없습니다."));
              OrderRow existing = orders.order(orderId).orElse(null);
              if (existing != null) {
                // 이미 확정 — 다른 키의 재요청도 같은 결과(사건은 한 번만)
                return applied(requestId, orderId, existing.version(), now);
              }
              if (orders.byDraft(row.draftId()).isPresent()) {
                throw PmartException.invalidState("이 주문서는 이미 다른 주문으로 확정됐습니다.");
              }
              if (expectedVersion != null && expectedVersion != row.version()) {
                throw PmartException.versionConflict("주문서가 이미 바뀌었습니다.");
              }
              DraftService.Snapshot s =
                  mapper.readValue(row.snapshot(), DraftService.Snapshot.class);
              if (!OrderRules.amountMatches(c.approvedAmount(), s.quote().totalAmount())) {
                log.warn(
                    "주문 {} 승인 금액 {} ≠ 동결 총액 {} — 확정 차단",
                    orderId,
                    c.approvedAmount(),
                    s.quote().totalAmount());
                throw new PmartException(
                    "AMOUNT_MISMATCH",
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "승인 금액이 주문 금액과 다릅니다. 확정하지 않습니다(대사 대상).",
                    false,
                    List.of());
              }
              OrderRow order = order(row, s, orderId, c, now);
              List<LineRow> lines = lines(orderId, s, unitsPerSku);
              orders.insert(order, lines);
              if (s.delivery().shippingAddress() != null) {
                orders.insertShipping(orderId, s.delivery().shippingAddress());
              }
              drafts.confirmed(row.draftId());
              publish(order, lines, row.sagaId());
              log.info(
                  "주문 확정 {} 초안 {} 총액 {} 첫 구매 {}",
                  orderId,
                  row.draftId(),
                  order.totalAmount(),
                  order.firstPurchaseApplied());
              return applied(requestId, orderId, order.version(), now);
            })
        .value();
  }

  // ---------- 주문행 수량 변경 (출고 전 취소, 2026-10-31) ----------

  /** 수량 변경 명령(계약 ApplyLineQuantityChangeRequest). */
  public record QuantityChange(
      String reason,
      @Nullable String sourceRef,
      @Nullable String claimId,
      List<OrderRules.Change> changes) {}

  public static final String CANCEL_BEFORE_SHIP = "CANCEL_BEFORE_SHIP";

  /**
   * 출고 전 취소 반영. 주문 행 잠금 → 행별 미이행 −q·출고 전 취소 +q → 전부 0 이면 CANCELLED → 행마다 OrderLineQuantityChanged,
   * 주문에 OrderCancelled(같은 트랜잭션, Outbox). 같은 키는 저장 결과 재생. 수량 초과·없는 행은 409 INVALID_STATE(바꾸지 않음).
   */
  public CommandResponse changeQuantities(
      String orderId,
      String requestId,
      String sourceSystem,
      QuantityChange in,
      @Nullable Integer expectedVersion) {
    // SHORTAGE(결품 — 품절 상품만 취소, ADR-0045)는 출고 전 취소와 같은 수량 규칙(미이행 −q, 출고 전 취소 +q)
    if (!CANCEL_BEFORE_SHIP.equals(in.reason()) && !"SHORTAGE".equals(in.reason())) {
      throw new PmartException(
          "UNSUPPORTED_ACTION",
          HttpStatus.UNPROCESSABLE_CONTENT,
          "지금은 출고 전 취소(CANCEL_BEFORE_SHIP)·결품(SHORTAGE)만 반영합니다: " + in.reason(),
          false,
          List.of());
    }
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("orderId", orderId);
    request.put("change", in);
    return idempotency
        .execute(
            new RequestKey(sourceSystem, requestId, "applyLineQuantityChange", actor()),
            request,
            CommandResponse.class,
            () -> {
              Instant now = clock.instant();
              OrderRow o =
                  orders
                      .lock(orderId)
                      .orElseThrow(() -> PmartException.notFound("ORDER_NOT_FOUND", "주문이 없습니다."));
              if (expectedVersion != null && expectedVersion != o.version()) {
                throw PmartException.versionConflict("주문이 이미 바뀌었습니다.");
              }
              if ("CANCELLED".equals(o.status())) {
                throw PmartException.invalidState("이미 취소된 주문입니다.");
              }
              List<LineRow> rows = orders.lines(orderId);
              List<OrderRules.LineQty> before = rows.stream().map(OrderService::qty).toList();
              List<OrderRules.LineQty> after;
              try {
                after = OrderRules.cancelBeforeShip(before, in.changes());
              } catch (IllegalArgumentException e) {
                throw PmartException.invalidState(e.getMessage());
              }
              boolean full = OrderRules.fullyCancelled(after);
              int version = o.version() + 1;
              for (int i = 0; i < after.size(); i++) {
                OrderRules.LineQty b = before.get(i);
                OrderRules.LineQty a = after.get(i);
                if (a.equals(b)) {
                  continue;
                }
                orders.updateLineQuantities(
                    a.orderLineId(), a.unfulfilledQty(), a.cancelledBeforeShipQty());
                publishLineChanged(o, rows.get(i), a, in, full, version, now);
              }
              String status = full ? "CANCELLED" : o.status();
              orders.updateStatus(orderId, status, version);
              publishCancelled(o, in, full, version, now);
              log.info(
                  "주문 {} 출고 전 취소 반영 {}행 → {} (근거 {})",
                  orderId,
                  in.changes().size(),
                  status,
                  in.sourceRef());
              return applied(requestId, orderId, version, now);
            })
        .value();
  }

  /**
   * MFC 준비 시작(PreparationStarted, mfc.fulfillment.v1 — D6-1 첫 PICK 시작) 반영: CONFIRMED →
   * PREPARING(+버전), 같은 트랜잭션에서 OrderPreparationStarted. 이후 고객 자율 취소는 받지 않는다(claim 심사가 CONFIRMED 만,
   * cancel-vs-handover §1). 이미 준비 중·취소된 주문은 그대로. inbox 트랜잭션 안에서 호출된다.
   */
  public void preparationStarted(com.pmart.common.messaging.EventEnvelope e) {
    var p = e.payload();
    String orderId = p.path("orderId").asString("");
    OrderRow o = orders.lock(orderId).orElse(null);
    if (o == null) {
      log.warn("준비 시작 사건의 주문이 없습니다: {}", orderId);
      return;
    }
    if (!"CONFIRMED".equals(o.status())) {
      log.info("주문 {} 준비 시작 — 이미 {} 라 상태를 바꾸지 않음", orderId, o.status());
      return;
    }
    int version = o.version() + 1;
    orders.updateStatus(orderId, "PREPARING", version);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("orderId", orderId);
    payload.put("orderVersion", version);
    if (p.hasNonNull("fulfillmentId")) {
      payload.put("fulfillmentId", p.get("fulfillmentId").asString());
    }
    payload.put("startedAt", p.path("startedAt").asString(clock.instant().toString()));
    publishOrderEvent("OrderPreparationStarted", orderId, version, payload);
    log.info("주문 {} 준비 시작 → PREPARING", orderId);
  }

  /** 서비스 간 주문 원장 조회(claim 심사·환불 근거). 확정 전 주문 ID 는 404. */
  public OrderView internal(String orderId) {
    OrderRow o =
        orders
            .order(orderId)
            .orElseThrow(() -> PmartException.notFound("ORDER_NOT_FOUND", "주문이 없습니다."));
    return new OrderView(o, orders.lines(orderId), null);
  }

  /**
   * 배송 쪽 내부 조회 — 주문 당시 배송지 사본 포함(배차 좌표 O-4, 라이더 전달지 O-5). {@link #internal} 은 사본을 싣지 않아(claim 용)
   * 배차용 배송지의 도로명주소가 늘 비던 것을 2026-11-14 고침. 확정 전 주문 ID 는 404.
   */
  public OrderView internalWithShipping(String orderId) {
    OrderView v = internal(orderId);
    return new OrderView(v.order(), v.lines(), orders.shipping(orderId).orElse(null));
  }

  private static OrderRules.LineQty qty(LineRow l) {
    return new OrderRules.LineQty(
        l.orderLineId(),
        l.originalQty(),
        l.unfulfilledQty(),
        l.handedOverQty(),
        l.cancelledBeforeShipQty());
  }

  private void publishLineChanged(
      OrderRow o,
      LineRow line,
      OrderRules.LineQty a,
      QuantityChange in,
      boolean full,
      int version,
      Instant at) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("orderId", o.orderId());
    p.put("orderLineId", a.orderLineId());
    p.put("sku", line.sku());
    p.put("originalQty", a.originalQty());
    p.put("unfulfilledQty", a.unfulfilledQty());
    p.put("handedOverQty", a.handedOverQty());
    p.put("cancelledBeforeShipQty", a.cancelledBeforeShipQty());
    p.put("reason", in.reason());
    p.put("scope", full ? "FULL" : "PARTIAL");
    if (in.claimId() != null) {
      p.put("claimId", in.claimId());
    }
    p.put("version", version);
    p.put("changedAt", at.toString());
    publishOrderEvent("OrderLineQuantityChanged", o.orderId(), version, p);
  }

  private void publishCancelled(
      OrderRow o, QuantityChange in, boolean full, int version, Instant at) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("orderId", o.orderId());
    p.put("orderVersion", version);
    p.put("scope", full ? "FULL" : "PARTIAL");
    List<Map<String, Object>> ls = new ArrayList<>();
    for (OrderRules.Change c : in.changes()) {
      ls.add(Map.of("orderLineId", c.orderLineId(), "qty", c.qty()));
    }
    p.put("lines", ls);
    p.put("reason", in.reason());
    if (in.claimId() != null) {
      p.put("claimId", in.claimId());
    }
    p.put("accountId", o.accountId());
    p.put("firstPurchase", o.firstPurchaseApplied());
    p.put("cancelledAt", at.toString());
    publishOrderEvent("OrderCancelled", o.orderId(), version, p);
  }

  private void publishOrderEvent(
      String type, String orderId, int version, Map<String, Object> payload) {
    EventEnvelope e =
        codec.create(
            events.sourceSystem(),
            type,
            1,
            "Order",
            orderId,
            version,
            new EventEnvelope.Actor(EventEnvelope.ActorType.SYSTEM, events.sourceSystem()),
            EventEnvelope.EventSource.REAL,
            payload);
    events.publish(e, TOPIC);
  }

  /** 확정 주문이 있는가(초안 조회·결제 진입이 사가보다 먼저 본다). */
  public boolean confirmed(@Nullable String orderId) {
    return orderId != null && orders.exists(orderId);
  }

  // ---------- 조회 ----------

  /** 내 주문. 남의 주문·확정 전 주문 ID 는 404(존재를 드러내지 않음). */
  public OrderView get(String accountId, String orderId) {
    OrderRow o =
        orders
            .order(orderId)
            .filter(x -> x.accountId().equals(accountId))
            .orElseThrow(() -> PmartException.notFound("ORDER_NOT_FOUND", "주문이 없습니다."));
    return new OrderView(o, orders.lines(orderId), orders.shipping(orderId).orElse(null));
  }

  /** 내 주문 목록(최근 확정 순). 조회 기간 정책값은 아직 없어 기간으로 자르지 않는다. */
  public Page list(String accountId, int size, @Nullable String cursor, @Nullable String status) {
    Instant afterAt = null;
    String afterId = null;
    if (cursor != null && !cursor.isBlank()) {
      String[] p = decodeCursor(cursor);
      afterAt = Instant.parse(p[0]);
      afterId = p[1];
    }
    List<OrderStore.Summary> rows = orders.list(accountId, status, afterAt, afterId, size + 1);
    String next = null;
    if (rows.size() > size) {
      rows = rows.subList(0, size);
      OrderStore.Summary last = rows.getLast();
      next = encodeCursor(last.confirmedAt(), last.orderId());
    }
    return new Page(rows, next, size);
  }

  static String encodeCursor(Instant at, String orderId) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString((at.toString() + "|" + orderId).getBytes(StandardCharsets.UTF_8));
  }

  static String[] decodeCursor(String cursor) {
    try {
      String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      String[] p = raw.split("\\|", 2);
      if (p.length != 2 || p[1].isBlank()) {
        throw new IllegalArgumentException(raw);
      }
      Instant.parse(p[0]);
      return p;
    } catch (IllegalArgumentException | java.time.format.DateTimeParseException e) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "cursor 가 올바르지 않습니다.");
    }
  }

  // ---------- 내부 ----------

  private Map<String, Integer> legacyUnits(String orderId) {
    DraftRow row = drafts.byOrderId(orderId).orElse(null);
    if (row == null || orders.exists(orderId)) {
      return Map.of();
    }
    DraftService.Snapshot s = mapper.readValue(row.snapshot(), DraftService.Snapshot.class);
    List<String> missing =
        s.lines().stream()
            .filter(l -> l.unitsPerSku() == null)
            .map(DraftService.Line::sku)
            .toList();
    if (missing.isEmpty()) {
      return Map.of();
    }
    Map<String, Integer> out = new LinkedHashMap<>();
    upstreams.skus(missing).forEach((sku, v) -> out.put(sku, v.unitsPerSku()));
    return out;
  }

  private static OrderRow order(
      DraftRow row, DraftService.Snapshot s, String orderId, Confirmation c, Instant now) {
    DraftService.Delivery d = s.delivery();
    DraftService.Quote q = s.quote();
    return new OrderRow(
        orderId,
        row.accountId(),
        row.draftId(),
        row.mfcId(),
        "CONFIRMED",
        1,
        d.deliveryMode(),
        d.arrivalWindow() == null ? null : d.arrivalWindow().date(),
        d.arrivalWindow() == null ? null : d.arrivalWindow().start(),
        d.arrivalWindow() == null ? null : d.arrivalWindow().end(),
        d.outOfStockPreference(),
        d.deliveryNote(),
        d.shoppingAddressId(),
        q.quoteVersion(),
        q.priceVersion(),
        q.itemAmount(),
        q.deliveryTipBase(),
        q.deliveryTip(),
        q.deliveryTipReason(),
        q.bagFee(),
        q.totalAmount(),
        OrderRules.firstPurchaseApplied(q.deliveryTipReason()),
        q.weightG(),
        Math.max(1, q.plannedShipments()),
        c.paymentAttemptId(),
        c.pgTransactionId(),
        c.approvedAmount(),
        c.reservationId(),
        c.capacityHoldId(),
        c.slotHoldId(),
        now);
  }

  private static List<LineRow> lines(
      String orderId, DraftService.Snapshot s, Map<String, Integer> legacyUnits) {
    List<LineRow> out = new ArrayList<>();
    int n = 1;
    for (DraftService.Line l : s.lines()) {
      Integer units = l.unitsPerSku() != null ? l.unitsPerSku() : legacyUnits.get(l.sku());
      if (units == null || units < 1) {
        throw PmartException.invalidState("SKU 구성 수를 확인하지 못했습니다: " + l.sku());
      }
      int lineNo = n++;
      out.add(
          new LineRow(
              OrderRules.orderLineId(orderId, lineNo),
              orderId,
              lineNo,
              l.cartItemId(),
              l.sku(),
              l.productId(),
              units,
              l.quantity(),
              l.unitPrice(),
              l.lineAmount(),
              0, // 1차는 할인·쿠폰 없음
              l.weightG(),
              l.quantity(),
              l.quantity(),
              0,
              0,
              0,
              0,
              l.productName(), // 초안 때 뜬 사본(BR-35) — 확정 때 다시 조회하지 않는다
              l.optionLabel()));
    }
    return out;
  }

  private void publish(OrderRow o, List<LineRow> lines, @Nullable String sagaId) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("orderId", o.orderId());
    p.put("orderVersion", o.version());
    p.put("accountId", o.accountId());
    p.put("draftId", o.draftId());
    p.put("mfcId", o.mfcId());
    List<Map<String, Object>> ls = new ArrayList<>();
    for (LineRow l : lines) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("orderLineId", l.orderLineId());
      m.put("sku", l.sku());
      m.put("qty", l.quantity());
      m.put("unitsPerSku", l.unitsPerSku());
      m.put("weightG", l.weightG());
      m.put("unitPrice", l.unitPrice());
      m.put("discountShare", l.discountShare());
      if (l.cartItemId() != null) {
        m.put("cartItemId", l.cartItemId());
      }
      if (l.productName() != null) {
        m.put("productName", l.productName()); // 단말 표시용 사본(슬라이스 6a)
      }
      ls.add(m);
    }
    p.put("lines", ls);
    p.put("shipments", List.of(singleShipment(o.orderId(), lines)));
    p.put("deliveryMode", o.deliveryMode());
    if (o.arrivalDate() != null && o.arrivalStart() != null && o.arrivalEnd() != null) {
      Instant[] w = OrderRules.windowInstants(o.arrivalDate(), o.arrivalStart(), o.arrivalEnd());
      p.put(
          "arrivalWindow",
          Map.of("start", w[0].toString(), "end", w[1].toString(), "meaning", "ARRIVAL"));
    }
    p.put("outOfStockPreference", o.outOfStockPreference());
    p.put("reservationId", o.reservationId());
    if (o.capacityHoldId() != null) {
      p.put("capacityHoldId", o.capacityHoldId());
    }
    if (o.slotHoldId() != null) {
      p.put("slotHoldId", o.slotHoldId());
    }
    p.put("paymentAttemptId", o.paymentAttemptId());
    p.put("itemAmount", o.itemAmount());
    p.put("deliveryTip", o.deliveryTip());
    p.put("deliveryTipReason", o.deliveryTipReason());
    p.put("bagFee", o.bagFee());
    p.put("totalAmount", o.totalAmount());
    p.put("firstPurchase", o.firstPurchaseApplied());
    p.put("plannedShipments", o.plannedShipments());
    p.put("confirmedAt", o.confirmedAt().toString());
    EventEnvelope e =
        codec.create(
            events.sourceSystem(),
            "OrderConfirmed",
            1,
            "Order",
            o.orderId(),
            o.version(),
            new EventEnvelope.Actor(EventEnvelope.ActorType.SYSTEM, events.sourceSystem()),
            EventEnvelope.EventSource.REAL,
            p);
    if (sagaId != null) {
      // correlationId = 결제 진입 사가(S1) — 확정 사실을 사가 흐름에 잇는다
      e =
          new EventEnvelope(
              e.eventId(),
              e.eventType(),
              e.schemaVersion(),
              e.sourceSystem(),
              e.aggregateType(),
              e.aggregateId(),
              e.aggregateVersion(),
              e.occurredAt(),
              e.timezone(),
              e.traceId(),
              sagaId,
              e.causationId(),
              e.actor(),
              e.source(),
              e.payload());
    }
    // 계약 토픽 명시(order.order.v1 — OrderConfirmed.v1.json)
    events.publish(e, TOPIC);
  }

  /**
   * 이행 계획 — 주문당 출고 1개(슬라이스 6 D6-2, MFC Q-10 기준선). 모든 행·미이행 수량 전부. 분할은 Q-10 결정 뒤 출고를 늘린다. shipmentId
   * 는 주문에서 결정적으로 만든다(재발행·재처리에도 같은 값).
   */
  static Map<String, Object> singleShipment(String orderId, List<LineRow> lines) {
    List<Map<String, Object>> ls = new ArrayList<>();
    int weight = 0;
    for (LineRow l : lines) {
      if (l.quantity() <= 0) {
        continue;
      }
      ls.add(Map.of("orderLineId", l.orderLineId(), "qty", l.quantity()));
      weight += l.weightG();
    }
    Map<String, Object> s = new LinkedHashMap<>();
    s.put("shipmentId", orderId + "-S1");
    s.put("weightG", weight);
    s.put("lines", ls);
    return s;
  }

  private static CommandResponse applied(
      String requestId, String orderId, int version, Instant at) {
    return new CommandResponse(
        CommandStatus.APPLIED, requestId, orderId, null, version, false, null, at);
  }

  private static String actor() {
    return PmartPrincipal.current()
        .map(PmartPrincipal::subject)
        .orElse(RequestIdempotency.ANONYMOUS);
  }
}
