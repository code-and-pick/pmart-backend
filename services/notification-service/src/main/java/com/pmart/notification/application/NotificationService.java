package com.pmart.notification.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.notification.domain.Notices;
import com.pmart.notification.domain.Notices.Notice;
import com.pmart.notification.infrastructure.NotificationStore;
import com.pmart.notification.infrastructure.NotificationStore.NotificationRow;
import com.pmart.notification.infrastructure.NotificationStore.PendingResult;
import com.pmart.notification.infrastructure.NotificationStore.RefundLink;
import com.pmart.notification.infrastructure.OrderClient;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 고객 알림함(2026-11-16, 계약 0.2.0 — 사용자 "앱 알림함만 먼저", "이번엔 고객만"). 사건마다 받는 고객 한 명에게 알림 한 건(같은 사건은 한 번 —
 * dedupe_key = 사건 ID). 알림 실패로 원래 업무를 다시 하지 않는다. 문구는 {@link Notices}.
 *
 * <p>받는 고객: 사건에 accountId·customerId 가 있으면 그것, 없으면 order 내부 주문 조회(없는 주문은 건너뜀, order 장애는 예외 → 재시도).
 */
@Service
public class NotificationService {

  private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
  public static final String CUSTOMER = "CUSTOMER";

  /** 라이더 알림함(2026-11-16 — 라이더 앱 요청 §11-5, 사용자 "라이더 알림함 먼저"). */
  public static final String RIDER = "RIDER";

  public static final String UNREAD = "NOTICE_UNREAD";

  public record Page(List<NotificationRow> items, int unreadCount, @Nullable String nextCursor) {}

  private final NotificationStore store;
  private final OrderClient orders;
  private final Clock clock;

  public NotificationService(NotificationStore store, OrderClient orders, Clock clock) {
    this.store = store;
    this.orders = orders;
    this.clock = clock;
  }

  // ---------------------------------------------------------------- 사건 → 알림(inbox 트랜잭션 안)

  /** order.order.v1. */
  public void onOrderEvent(EventEnvelope e) {
    JsonNode p = e.payload();
    String orderId = text(p, "orderId");
    if (orderId == null) {
      return;
    }
    switch (e.eventType()) {
      case "OrderConfirmed" -> {
        JsonNode w = p.path("arrivalWindow");
        Long total = p.hasNonNull("totalAmount") ? p.get("totalAmount").asLong() : null;
        notify(
            e,
            text(p, "accountId"),
            orderId,
            Notices.orderConfirmed(
                orderId, total, text(w, "date"), text(w, "start"), text(w, "end")),
            "");
      }
      case "OrderPreparationStarted" ->
          notify(e, null, orderId, Notices.preparationStarted(orderId), "");
      case "ReservationTimeChanged" -> {
        JsonNode w = p.path("arrivalWindow");
        notify(
            e,
            null,
            orderId,
            Notices.reservationTimeChanged(
                orderId, text(w, "date"), text(w, "start"), text(w, "end")),
            "");
      }
      case "OrderCancelled" ->
          notify(
              e,
              text(p, "accountId"),
              orderId,
              Notices.orderCancelled(orderId, text(p, "reason")),
              "");
      case "OrderLineQuantityChanged" -> {
        // 결품 부분 취소만(N-1). 주문 전체가 취소되면 OrderCancelled 가 따로 알린다
        if (!"SHORTAGE".equals(text(p, "reason")) || "FULL".equals(text(p, "scope"))) {
          return;
        }
        String lineId = text(p, "orderLineId");
        OrderClient.OrderInfo info = orders.order(orderId);
        if (info == null) {
          skip(e, orderId);
          return;
        }
        save(
            e,
            info.accountId(),
            Notices.shortage(orderId, lineId == null ? null : info.productNames().get(lineId)),
            "");
      }
      default -> {}
    }
  }

  /** delivery.delivery.v1 — 출발·완료, 오배송 다시 배송비 결제 필요(N-3, 2026-11-16). */
  public void onDeliveryEvent(EventEnvelope e) {
    if ("RiderEarningRecorded".equals(e.eventType())) {
      // 지원센터 수행료 조정(2026-11-17)만 — 일반 기록·확정은 알리지 않는다. 사유는 사건에 없다
      JsonNode p = e.payload();
      String riderId = text(p, "riderId");
      String decision = text(p.path("adjustment"), "decision");
      if (riderId != null && decision != null) {
        JsonNode total = p.path("feeBasis").path("totalFee");
        Notice n =
            Notices.earningAdjusted(
                text(p, "deliveryId"),
                "PAY".equals(decision),
                total.isNumber() ? total.asLong() : null,
                text(p, "payoutDate"));
        saveFor(e, RIDER, riderId, n);
      }
      return;
    }
    if ("HelpRequestReplied".equals(e.eventType())) {
      JsonNode h = e.payload();
      String riderId = text(h, "riderId");
      String deliveryId = text(h, "deliveryId");
      String requestId = text(h, "requestId");
      if (riderId != null && deliveryId != null && requestId != null) {
        saveFor(e, RIDER, riderId, Notices.helpRequestReplied(deliveryId, requestId));
      }
      return;
    }
    String orderId = text(e.payload(), "orderId");
    if (orderId == null) {
      return;
    }
    switch (e.eventType()) {
      case "PickupCompleted" -> notify(e, null, orderId, Notices.pickedUp(orderId), "");
      case "DeliveryCompleted" -> notify(e, null, orderId, Notices.delivered(orderId), "");
      case "RedeliveryPaymentRequired" ->
          notify(
              e,
              null,
              orderId,
              Notices.redeliveryPaymentRequired(
                  orderId,
                  e.payload().path("fee").isNumber() ? e.payload().path("fee").asLong() : 9_000),
              "");
      default -> {}
    }
  }

  /**
   * delivery.slot.v1 — SlotCapacityReduced(CP-1, 2026-11-16): 영향 주문마다 "배송이 늦어질 수 있어요"(도착 시간대가 있으면 그
   * 시간). 한 사건에 주문이 여럿이라 주문 ID 로 구분해 한 번씩.
   */
  public void onSlotEvent(EventEnvelope e) {
    if (!"SlotCapacityReduced".equals(e.eventType())) {
      return;
    }
    JsonNode w = e.payload().path("window");
    boolean arrival = "ARRIVAL".equals(w.path("meaning").asString(""));
    Instant start = arrival ? parse(w.path("start").asString(null)) : null;
    Instant end = arrival ? parse(w.path("end").asString(null)) : null;
    for (JsonNode o : e.payload().path("affectedOrders")) {
      String orderId = o.asString("");
      if (!orderId.isBlank()) {
        notify(e, null, orderId, Notices.mayBeDelayed(orderId, start, end), ":" + orderId);
      }
    }
  }

  private static @Nullable Instant parse(@Nullable String s) {
    try {
      return s == null ? null : Instant.parse(s);
    } catch (RuntimeException ex) {
      return null;
    }
  }

  /** order.claim.v1 — 환불 의도로 주문·클레임을 잇고, 먼저 와 있던 결과가 있으면 알린다. */
  public void onClaimEvent(EventEnvelope e) {
    if (!"RefundIntentCreated".equals(e.eventType())) {
      return;
    }
    JsonNode p = e.payload();
    String intent = text(p, "refundIntentId");
    String orderId = text(p, "orderId");
    if (intent == null || orderId == null) {
      return;
    }
    RefundLink link = new RefundLink(intent, orderId, text(p, "claimId"));
    store.lockRefund(intent);
    store.saveRefundLink(link, now());
    for (PendingResult r : store.takePending(intent)) {
      notifyRefund(link, r.sourceEventId(), r.result(), r.amount(), r.resultAt(), e);
    }
  }

  /** order.payment.v1 — 환불 결과(성공·실패만 알림). */
  public void onPaymentEvent(EventEnvelope e) {
    if (!"RefundResult".equals(e.eventType())) {
      return;
    }
    JsonNode p = e.payload();
    String intent = text(p, "refundIntentId");
    String result = text(p, "result");
    if (intent == null || result == null || "UNKNOWN".equals(result)) {
      return;
    }
    Long amount = p.hasNonNull("amount") ? p.get("amount").asLong() : null;
    Instant at = instant(text(p, "resultAt"));
    store.lockRefund(intent);
    var link = store.refundLink(intent);
    if (link.isEmpty()) {
      // 의도 사건이 아직 안 왔다(토픽이 달라 순서가 보장되지 않는다) — 잠시 둔다
      store.savePending(
          new PendingResult(intent, e.eventId().toString(), result, amount, at), now());
      log.info("환불 결과 {} — 환불 의도를 기다림", intent);
      return;
    }
    notifyRefund(link.get(), e.eventId().toString(), result, amount, at, e);
  }

  /** platform.case.v1 — 고객센터 답변(고객), 라이더 문의 답변·도움요청 처리 끝(라이더). */
  public void onCaseEvent(EventEnvelope e) {
    JsonNode p = e.payload();
    if ("RiderInquiryAnswered".equals(e.eventType())) {
      String inquiryId = text(p, "inquiryId");
      String riderId = text(p, "riderId");
      if (inquiryId != null && riderId != null) {
        saveFor(e, RIDER, riderId, Notices.riderInquiryAnswered(inquiryId));
      }
      return;
    }
    if ("CaseClosed".equals(e.eventType())) {
      // 도움요청 접수 건(RIDER_HELP)만 — 요청한 라이더에게. 처리 결과·메모는 싣지 않는다
      if (!"RIDER_HELP".equals(text(p, "type"))) {
        return;
      }
      String riderId = ref(p, "riderId");
      if (riderId == null) {
        return;
      }
      saveFor(
          e, RIDER, riderId, Notices.helpRequestClosed(ref(p, "deliveryId"), ref(p, "requestId")));
      return;
    }
    if (!"InquiryAnswered".equals(e.eventType())) {
      return;
    }
    String inquiryId = text(p, "inquiryId");
    String customerId = text(p, "customerId");
    if (inquiryId == null || customerId == null) {
      return;
    }
    save(e, customerId, Notices.inquiryAnswered(inquiryId), "");
  }

  /** delivery.rider.v1 — 계정 제한·해제(사유·근거는 싣지 않는다). */
  public void onRiderEvent(EventEnvelope e) {
    if (!"RiderRestricted".equals(e.eventType())) {
      return;
    }
    JsonNode p = e.payload();
    String riderId = text(p, "riderId");
    String restriction = text(p, "restriction");
    if (riderId == null || restriction == null) {
      return;
    }
    Notice n = Notices.riderRestriction(restriction, text(p, "previous"), instant(text(p, "to")));
    if (n != null) {
      saveFor(e, RIDER, riderId, n);
    }
  }

  /** 사건 refs[{refType, refId}] 에서 값 하나. */
  private static @Nullable String ref(JsonNode p, String type) {
    for (JsonNode r : p.path("refs")) {
      if (type.equals(r.path("refType").asString(""))) {
        return text(r, "refId");
      }
    }
    return null;
  }

  private void notifyRefund(
      RefundLink link,
      String resultEventId,
      String result,
      @Nullable Long amount,
      @Nullable Instant resultAt,
      EventEnvelope trigger) {
    Notice n = Notices.refund(result, amount, link.orderId(), link.claimId());
    if (n == null) {
      return;
    }
    OrderClient.OrderInfo info = orders.order(link.orderId());
    if (info == null) {
      skip(trigger, link.orderId());
      return;
    }
    insert(
        CUSTOMER,
        info.accountId(),
        n,
        resultEventId,
        "RefundResult",
        resultAt,
        "RefundResult:" + resultEventId);
  }

  /** accountId 가 없으면 order 에서 찾는다. */
  private void notify(
      EventEnvelope e, @Nullable String accountId, String orderId, Notice n, String suffix) {
    String account = accountId;
    if (account == null) {
      OrderClient.OrderInfo info = orders.order(orderId);
      if (info == null) {
        skip(e, orderId);
        return;
      }
      account = info.accountId();
    }
    save(e, account, n, suffix);
  }

  /** 라이더 등 고객이 아닌 받는 사람(같은 사건은 한 번). */
  private void saveFor(EventEnvelope e, String recipientType, String recipientId, Notice n) {
    insert(
        recipientType,
        recipientId,
        n,
        e.eventId().toString(),
        e.eventType(),
        e.occurredAt(),
        e.eventType() + ":" + e.eventId());
  }

  private void save(EventEnvelope e, String accountId, Notice n, String suffix) {
    insert(
        CUSTOMER,
        accountId,
        n,
        e.eventId().toString(),
        e.eventType(),
        e.occurredAt(),
        e.eventType() + ":" + e.eventId() + suffix);
  }

  private void insert(
      String recipientType,
      String accountId,
      Notice n,
      String sourceEventId,
      String sourceEventType,
      @Nullable Instant occurredAt,
      String dedupeKey) {
    Instant now = now();
    boolean created =
        store.insert(
            new NotificationRow(
                "NTF-" + UUID.randomUUID(),
                recipientType,
                accountId,
                n.category(),
                n.templateKey(),
                n.title(),
                n.body(),
                n.destinationType(),
                n.destinationRef(),
                sourceEventId,
                sourceEventType,
                UNREAD,
                occurredAt,
                now,
                null),
            dedupeKey);
    if (created) {
      log.info("알림 {} ← {}", n.templateKey(), sourceEventType);
    }
  }

  private static void skip(EventEnvelope e, String orderId) {
    log.warn("{} — 주문 {} 이 order 에 없어 알림을 만들지 않음", e.eventType(), orderId);
  }

  // ---------------------------------------------------------------- 알림함

  public Page page(
      boolean unreadOnly, @Nullable String category, @Nullable String cursor, int size) {
    PmartPrincipal p = recipient();
    int offset = offset(cursor);
    String type = p.type().name();
    List<NotificationRow> rows =
        store.page(type, p.subject(), unreadOnly, category, offset, size + 1);
    boolean more = rows.size() > size;
    return new Page(
        rows.subList(0, Math.min(size, rows.size())),
        store.unreadCount(type, p.subject()),
        more ? String.valueOf(offset + size) : null);
  }

  public NotificationRow get(String notificationId) {
    return own(notificationId, recipient());
  }

  /** 읽음(이미 읽었으면 그대로). 배송 수령·거래 확정의 근거가 아니다. */
  public NotificationRow markRead(String notificationId) {
    PmartPrincipal p = recipient();
    own(notificationId, p);
    store.markRead(notificationId, now());
    return own(notificationId, p);
  }

  private NotificationRow own(String notificationId, PmartPrincipal p) {
    return store
        .find(notificationId)
        .filter(
            n -> n.recipientType().equals(p.type().name()) && n.recipientId().equals(p.subject()))
        .orElseThrow(() -> PmartException.notFound("NOTIFICATION_NOT_FOUND", "알림이 없습니다."));
  }

  // ---------------------------------------------------------------- 수신 설정

  /** 지금 알림은 모두 거래 안내(끌 수 없음)이고 채널은 앱 알림함뿐이다. */
  public static final List<String> CATEGORIES =
      List.of(Notices.ORDER, Notices.DELIVERY, Notices.REFUND, Notices.CASE);

  public void requireRecipient() {
    recipient();
  }

  /**
   * 설정 변경 검사: 앱 알림함이 아닌 채널·마케팅은 아직 없음(CHANNEL_NOT_APPROVED), 거래 안내를 끄기는
   * TRANSACTIONAL_CANNOT_DISABLE. 통과하면 바뀌는 것이 없다(이미 켜져 있음).
   */
  public void checkChanges(List<String[]> changes) {
    recipient();
    for (String[] c : changes) {
      String category = c[0];
      String channel = c[1];
      boolean enabled = Boolean.parseBoolean(c[2]);
      if (!"IN_APP".equals(channel) || !CATEGORIES.contains(category)) {
        throw rejected("CHANNEL_NOT_APPROVED", "지금은 앱 알림함의 주문·배송·환불·고객센터 알림만 있습니다.");
      }
      if (!enabled) {
        throw rejected("TRANSACTIONAL_CANNOT_DISABLE", "주문·배송·환불·고객센터 안내는 끌 수 없습니다.");
      }
    }
  }

  // ---------------------------------------------------------------- 공통

  /** 알림함 주인: 고객·라이더(라이더 알림 2026-11-16 — 도움요청 답장·처리 끝, 문의 답변, 계정 제한). 운영자 403. */
  static PmartPrincipal recipient() {
    return PmartPrincipal.current()
        .filter(
            x ->
                x.type() == PmartPrincipal.PrincipalType.CUSTOMER
                    || x.type() == PmartPrincipal.PrincipalType.RIDER)
        .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "고객 알림함입니다."));
  }

  private static PmartException rejected(String code, String message) {
    return new PmartException(code, HttpStatus.UNPROCESSABLE_CONTENT, message, false, List.of());
  }

  private static @Nullable String text(JsonNode n, String field) {
    JsonNode v = n.get(field);
    return v == null || v.isNull() ? null : v.asString();
  }

  private static @Nullable Instant instant(@Nullable String s) {
    try {
      return s == null ? null : Instant.parse(s);
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static int offset(@Nullable String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return 0;
    }
    try {
      int n = Integer.parseInt(cursor);
      if (n < 0 || n > 100_000) {
        throw new NumberFormatException();
      }
      return n;
    } catch (NumberFormatException e) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "cursor 가 올바르지 않습니다.");
    }
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  @Configuration(proxyBeanMethods = false)
  @org.springframework.boot.context.properties.EnableConfigurationProperties(
      NotificationProperties.class)
  static class Config {
    @Bean
    Clock notificationClock() {
      return Clock.systemUTC();
    }
  }
}
