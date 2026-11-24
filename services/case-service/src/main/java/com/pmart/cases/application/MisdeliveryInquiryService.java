package com.pmart.cases.application;

import com.pmart.cases.domain.InquiryRules;
import com.pmart.cases.infrastructure.InquiryStore;
import com.pmart.cases.infrastructure.InquiryStore.InquiryRow;
import com.pmart.cases.infrastructure.MisdeliveryInquiryStore;
import com.pmart.cases.infrastructure.MisdeliveryInquiryStore.ChoiceRow;
import com.pmart.cases.infrastructure.MisdeliveryInquiryStore.DeliveryCopy;
import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * R-16 고객 오배송 문의(ADR-0086 결정 1~3 — 사용자 2026-11-24 "고객이 오배송으로 문의를 남기기 편해야 하고 … 채팅에서 다시 보내거나 취소환불 할지를
 * 선택하게 해서 배송이랑 주문 상태를 종결"·"5시간 뒤 자동 환불").
 *
 * <ul>
 *   <li>고객 {@code POST /inquiries} DELIVERY·NOT_RECEIVED·주문이 <b>전달 완료된 고객
 *       배송(FORWARD·RESHIPMENT)</b>을 가리키면 (배송 ID 가 없으면 그 주문의 가장 최근 전달 완료 고객 배송) MISDELIVERY 사건(라이더
 *       귀책, 고객 선택 대기)을 같은 트랜잭션에서 열고 채팅에 선택 메시지를 넣는다. 전달 전이거나 사본이 없으면 지금처럼 상담원 문의.
 *   <li>배송 판단은 case 의 배송 사본(delivery.delivery.v1 DeliveryCreated·DeliveryCompleted) — 동기 호출 없음.
 *   <li>고르기: 고객 본인 또는 상담원(CS_AGENT·OPERATOR_ADMIN — 사유 필수), 한 번만, 기한 안. 고르면 MisdeliveryConfirmed.
 *   <li>기한(기본 5시간)이 지나면 자동 REFUND(autoResolved) + 채팅 알림.
 * </ul>
 */
@Service
public class MisdeliveryInquiryService {

  private static final Logger log = LoggerFactory.getLogger(MisdeliveryInquiryService.class);
  private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
  private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("M월 d일 HH:mm");
  public static final Set<String> CUSTOMER_DELIVERY_TYPES = Set.of("FORWARD", "RESHIPMENT");
  public static final Set<String> RESOLUTIONS = Set.of("RESHIP", "REFUND");
  public static final Set<String> STAFF_ROLES = Set.of("CS_AGENT", "OPERATOR_ADMIN");
  public static final String KIND_REQUEST = "MISDELIVERY_CHOICE_REQUEST";
  public static final String KIND_CHOSEN = "MISDELIVERY_CHOSEN";

  private final MisdeliveryInquiryStore store;
  private final InquiryStore inquiries;
  private final CaseInboxService cases;
  private final CaseProperties props;
  private final RequestIdempotency idempotency;
  private final TransactionTemplate tx;
  private final Clock clock;

  public MisdeliveryInquiryService(
      MisdeliveryInquiryStore store,
      InquiryStore inquiries,
      CaseInboxService cases,
      CaseProperties props,
      RequestIdempotency idempotency,
      TransactionTemplate tx,
      Clock clock) {
    this.store = store;
    this.inquiries = inquiries;
    this.cases = cases;
    this.props = props;
    this.idempotency = idempotency;
    this.tx = tx;
    this.clock = clock;
  }

  // ---------------------------------------------------------------- 배송 사본

  /** delivery.delivery.v1 DeliveryCreated·DeliveryCompleted → 사본(InboxGuard 안). */
  public void onDelivery(EventEnvelope e) {
    JsonNode p = e.payload();
    String deliveryId = text(p, "deliveryId");
    if (deliveryId == null) {
      return;
    }
    Instant now = clock.instant();
    if ("DeliveryCreated".equals(e.eventType())) {
      store.deliveryCreated(
          deliveryId,
          text(p, "orderId"),
          text(p, "type"),
          text(p, "purpose"),
          text(p, "mfcId"),
          now);
    } else if ("DeliveryCompleted".equals(e.eventType())) {
      String at = text(p, "deliveredAt");
      store.deliveryCompleted(
          deliveryId,
          text(p, "orderId"),
          text(p, "riderId"),
          at == null ? e.occurredAt() : Instant.parse(at),
          now);
    }
  }

  // ---------------------------------------------------------------- 문의 받을 때

  /** 고객 NOT_RECEIVED 문의가 전달 완료된 고객 배송을 가리키면 오배송 사건·선택 대기·채팅 안내(문의를 넣은 같은 트랜잭션). 열었으면 true. */
  public boolean openIfDelivered(
      String inquiryId,
      String customerId,
      String orderId,
      @Nullable String deliveryId,
      Instant now) {
    Optional<DeliveryCopy> found =
        deliveryId != null
            ? store.delivery(deliveryId)
            : store.latestCompletedCustomerDelivery(orderId);
    if (found.isEmpty()) {
      return false;
    }
    DeliveryCopy d = found.get();
    if (d.completedAt() == null
        || d.type() == null
        || !CUSTOMER_DELIVERY_TYPES.contains(d.type())
        || (d.orderId() != null && !d.orderId().equals(orderId))) {
      return false; // 전달 전·회수 배송·다른 주문 → 상담원 문의 그대로
    }
    String caseId =
        cases.openCustomerMisdelivery(
            inquiryId, orderId, d.deliveryId(), d.riderId(), d.mfcId(), customerId, now);
    if (caseId == null) {
      return false; // 이미 열린 오배송 사건(직원이 먼저) — 상담원이 처리
    }
    Instant deadline = now.plus(props.misdeliveryChoiceWindowOrDefault());
    store.insert(
        new ChoiceRow(
            inquiryId,
            caseId,
            d.deliveryId(),
            orderId,
            d.riderId(),
            deadline,
            null,
            null,
            null,
            null,
            null,
            now));
    if (deliveryId == null) {
      inquiries.setDeliveryId(inquiryId, d.deliveryId());
    }
    inquiries.addSystemMessage(
        inquiryId,
        "IMS-" + UUID.randomUUID(),
        KIND_REQUEST,
        "상품을 받지 못하셨군요. 다시 보내 드릴까요, 주문을 취소하고 환불해 드릴까요? "
            + WHEN.format(deadline.atZone(SEOUL))
            + "까지 고르지 않으시면 배달팁까지 모두 환불해 드려요.",
        now);
    log.info("고객 오배송 문의 {} → 사건 {} 배송 {}", inquiryId, caseId, d.deliveryId());
    return true;
  }

  // ---------------------------------------------------------------- 고르기

  /** 고객 본인 또는 상담원(대신, 사유 필수). 한 번만·기한 안. 같은 Idempotency-Key 는 같은 결과. */
  public void choose(
      String requestId,
      String sourceSystem,
      String inquiryId,
      String resolution,
      @Nullable String reason) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "토큰이 필요합니다."));
    boolean customer = p.type() == PmartPrincipal.PrincipalType.CUSTOMER;
    boolean staff =
        p.type() == PmartPrincipal.PrincipalType.OPERATOR
            && STAFF_ROLES.stream().anyMatch(p::hasRole);
    if (!customer && !staff) {
      throw new PmartException(
          ErrorCode.FORBIDDEN, "오배송 선택은 문의를 남긴 고객 또는 상담원(CS_AGENT·OPERATOR_ADMIN)만 합니다.");
    }
    if (!RESOLUTIONS.contains(resolution)) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "resolution 은 RESHIP 또는 REFUND 입니다.");
    }
    String why = reason == null || reason.isBlank() ? null : reason.strip();
    if (staff && (why == null || why.length() < 2 || why.length() > 200)) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "고객 대신 고를 때는 사유(2~200자)가 필요합니다.");
    }
    Map<String, Object> key = new LinkedHashMap<>();
    key.put("resolution", resolution);
    key.put("reason", why == null ? "" : why);
    idempotency.execute(
        new RequestKey(sourceSystem, requestId, "misdeliveryChoice:" + inquiryId, p.subject()),
        key,
        String.class,
        () ->
            tx.execute(
                s -> {
                  InquiryRow inq =
                      inquiries
                          .find(inquiryId)
                          .filter(r -> InquiryRules.CUSTOMER.equals(r.requesterType()))
                          .filter(r -> !customer || r.customerId().equals(p.subject()))
                          .orElseThrow(() -> notFound(inquiryId));
                  ChoiceRow c = store.lock(inquiryId).orElseThrow(() -> notFound(inquiryId));
                  if (c.resolution() != null) {
                    throw conflict("MISDELIVERY_ALREADY_CHOSEN", "이미 골랐습니다.");
                  }
                  Instant now = clock.instant();
                  if (!now.isBefore(c.deadlineAt())) {
                    throw conflict("MISDELIVERY_CHOICE_EXPIRED", "고를 수 있는 시간이 지나 자동으로 환불됩니다.");
                  }
                  apply(
                      c,
                      inq.inquiryId(),
                      resolution,
                      customer ? "CUSTOMER" : "STAFF",
                      new EventEnvelope.Actor(
                          customer
                              ? EventEnvelope.ActorType.CUSTOMER
                              : EventEnvelope.ActorType.OPERATOR,
                          p.subject()),
                      why,
                      now);
                  return inquiryId;
                }));
  }

  /** 기한이 지난 것 자동 REFUND(1분마다 — 인스턴스 여럿이어도 SKIP LOCKED). 처리한 수. */
  @Scheduled(
      fixedDelayString = "${pmart.cases.misdelivery-auto-interval:PT1M}",
      initialDelayString = "${pmart.cases.misdelivery-auto-initial-delay:PT40S}")
  public void scheduled() {
    try {
      int n = autoResolveDue();
      if (n > 0) {
        log.info("오배송 문의 자동 환불 {}건", n);
      }
    } catch (RuntimeException ex) {
      log.warn("오배송 문의 자동 환불 실패 — 다음 주기에 다시 ({})", ex.getClass().getSimpleName());
    }
  }

  public int autoResolveDue() {
    Integer n =
        tx.execute(
            s -> {
              Instant now = clock.instant();
              int done = 0;
              for (String inquiryId : store.lockDue(now, 50)) {
                ChoiceRow c = store.find(inquiryId).orElseThrow();
                apply(
                    c,
                    inquiryId,
                    "REFUND",
                    "AUTO",
                    new EventEnvelope.Actor(EventEnvelope.ActorType.SYSTEM, "case-service"),
                    null,
                    now);
                done++;
              }
              return done;
            });
    return n == null ? 0 : n;
  }

  private void apply(
      ChoiceRow c,
      String inquiryId,
      String resolution,
      String byType,
      EventEnvelope.Actor actor,
      @Nullable String reason,
      Instant now) {
    store.chosen(inquiryId, resolution, byType, actor.id(), reason, now);
    boolean auto = "AUTO".equals(byType);
    cases.confirmCustomerMisdelivery(
        c.caseId(),
        inquiryId,
        c.orderId(),
        c.deliveryId(),
        c.riderId(),
        resolution,
        auto,
        actor,
        now);
    String text =
        auto
            ? "5시간 안에 고르지 않으셔서 주문을 취소하고 배달팁까지 모두 환불해 드릴게요."
            : "RESHIP".equals(resolution)
                ? "다시 보내 드릴게요. 새로 보내는 배송은 주문 상세에서 볼 수 있어요."
                : "주문을 취소하고 배달팁까지 모두 환불해 드릴게요.";
    if ("STAFF".equals(byType)) {
      text = "상담원이 고객님 대신 골랐어요. " + text;
    }
    inquiries.addSystemMessage(inquiryId, "IMS-" + UUID.randomUUID(), KIND_CHOSEN, text, now);
  }

  // ---------------------------------------------------------------- 보기

  public Optional<ChoiceRow> find(String inquiryId) {
    return store.find(inquiryId);
  }

  public Map<String, ChoiceRow> byInquiries(Collection<String> inquiryIds) {
    return store.byInquiries(inquiryIds);
  }

  private static @Nullable String text(JsonNode p, String name) {
    JsonNode v = p.path(name);
    return v.isMissingNode() || v.isNull() || v.asString().isBlank() ? null : v.asString();
  }

  private static PmartException notFound(String inquiryId) {
    return PmartException.notFound("MISDELIVERY_NOT_FOUND", "오배송 선택을 기다리는 문의가 아닙니다: " + inquiryId);
  }

  private static PmartException conflict(String code, String message) {
    return new PmartException(code, HttpStatus.CONFLICT, message, false, List.of());
  }
}
