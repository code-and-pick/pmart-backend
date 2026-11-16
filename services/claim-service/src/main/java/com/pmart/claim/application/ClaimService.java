package com.pmart.claim.application;

import com.pmart.claim.domain.ClaimRules;
import com.pmart.claim.infrastructure.ClaimStore;
import com.pmart.claim.infrastructure.ClaimStore.ClaimRow;
import com.pmart.claim.infrastructure.ClaimStore.LineRow;
import com.pmart.claim.infrastructure.ClaimStore.RefundRow;
import com.pmart.claim.infrastructure.Upstreams;
import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.PmartException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;

/**
 * 클레임 접수·조회와 취소 흐름의 사건 반영(docs/business/flows/cancel-vs-handover.md). 2026-10-31 범위: 고객의 주문 전체
 * 취소(피킹 전).
 *
 * <ol>
 *   <li>접수: order 원장으로 본인·상태·수량 심사({@link ClaimRules}) → 클레임 STOP_PENDING + stopRequestId 발급 →
 *       ClaimOpened· CancellationRequested(order.claim.v1, 한 트랜잭션). orchestration 이 그 사건으로 S3 를
 *       시작한다.
 *   <li>WorkStopResult 에 인계됨이 있으면 REJECTED(출고 전 취소 불가 — 회수 경로).
 *   <li>OrderCancelled(claimId) → 행 승인·IN_PROGRESS → 환불 의도(원 승인액 전액) → 커밋 뒤 payment 취소 실행({@link
 *       RefundService}).
 *   <li>RefundResult SUCCESS → 환불 완료·클레임 CLOSED. PG 성공 전에는 완료로 표시하지 않는다.
 * </ol>
 */
@Service
public class ClaimService {

  public static final String TOPIC = "order.claim.v1";

  private static final Logger log = LoggerFactory.getLogger(ClaimService.class);

  /** 접수 명령(계약 CreateClaimRequest). */
  public record CreateCommand(
      String orderId,
      String type,
      List<ClaimRules.Requested> lines,
      String reason,
      @Nullable String reasonDetail,
      String channel) {}

  public record ClaimView(ClaimRow claim, List<LineRow> lines, List<RefundRow> refunds) {}

  /** 시스템 자동 취소 결과. created=false 면 이미 진행 중이던 취소 클레임. */
  public record SystemCancellation(ClaimView view, boolean created) {}

  private record Opened(String claimId, boolean created) {}

  public static final String SYSTEM_CHANNEL = "SYSTEM";

  private final ClaimStore store;
  private final Upstreams upstreams;
  private final RequestIdempotency idempotency;
  private final ClaimEvents events;
  private final RefundService refunds;
  private final Clock clock;

  public ClaimService(
      ClaimStore store,
      Upstreams upstreams,
      RequestIdempotency idempotency,
      ClaimEvents events,
      RefundService refunds,
      Clock clock) {
    this.store = store;
    this.upstreams = upstreams;
    this.idempotency = idempotency;
    this.events = events;
    this.refunds = refunds;
    this.clock = clock;
  }

  // ---------- 접수 ----------

  public ClaimView create(
      String idempotencyKey, String sourceSystem, PmartPrincipal who, CreateCommand in) {
    if (who.type() != PmartPrincipal.PrincipalType.CUSTOMER) {
      throw unsupported("지금은 고객 앱의 취소 접수만 받습니다(운영자·CS 접수는 아직 없습니다).");
    }
    if (!"CANCEL".equals(in.type())) {
      throw unsupported("반품·교환 접수는 아직 준비 중입니다.");
    }
    if ("FULFILLMENT_REJECTED".equals(in.reason()) || OUT_OF_STOCK.equals(in.reason())) {
      throw new PmartException(
          com.pmart.common.web.ErrorCode.VALIDATION_FAILED, "시스템 전용 사유입니다: " + in.reason());
    }
    JsonNode order = upstreams.order(in.orderId());
    if (order == null || !who.subject().equals(order.path("accountId").asString())) {
      throw PmartException.notFound("ORDER_NOT_FOUND", "주문이 없습니다.");
    }
    List<ClaimRules.OrderLine> lines = new ArrayList<>();
    Map<String, JsonNode> byLine = new LinkedHashMap<>();
    for (JsonNode l : order.path("lines")) {
      lines.add(
          new ClaimRules.OrderLine(
              l.path("orderLineId").asString(),
              l.path("sku").asString(),
              l.path("unitPrice").asLong(),
              l.path("originalQty").asInt(),
              l.path("unfulfilledQty").asInt()));
      byLine.put(l.path("orderLineId").asString(), l);
    }
    try {
      ClaimRules.checkFullCancel(order.path("status").asString(), lines, in.lines());
    } catch (ClaimRules.Rejection r) {
      throw new PmartException(
          r.code(), HttpStatus.UNPROCESSABLE_CONTENT, r.getMessage(), false, List.of());
    }
    String claimId =
        idempotency
            .execute(
                new RequestKey(sourceSystem, idempotencyKey, "createClaim", who.subject()),
                in,
                String.class,
                () -> open(who.subject(), in, order, byLine))
            .value();
    return view(claimId);
  }

  /**
   * 시스템 자동 전체 취소(D6-4, 슬라이스 6a). orchestration 이 MFC 작업 거절을 확인한 뒤 부른다. 고객 선택 단계 없이 주문 전체(모든 행 × 미이행
   * 수량)를 취소 접수 — 뒤 흐름(S3 → OrderCancelled → 원 승인액 전액 환불)은 고객 취소와 같다. 이미 진행 중인 취소 클레임이 있으면 새로 만들지 않고
   * 그것을 돌려준다(고객이 먼저 취소한 경우).
   */
  public SystemCancellation createSystemCancellation(
      String idempotencyKey,
      String sourceSystem,
      String orderId,
      String reason,
      @Nullable String reasonDetail) {
    JsonNode order = upstreams.order(orderId);
    if (order == null) {
      throw PmartException.notFound("ORDER_NOT_FOUND", "주문이 없습니다.");
    }
    List<ClaimRules.OrderLine> lines = new ArrayList<>();
    List<ClaimRules.Requested> all = new ArrayList<>();
    Map<String, JsonNode> byLine = new LinkedHashMap<>();
    for (JsonNode l : order.path("lines")) {
      String id = l.path("orderLineId").asString();
      int unfulfilled = l.path("unfulfilledQty").asInt();
      lines.add(
          new ClaimRules.OrderLine(
              id,
              l.path("sku").asString(),
              l.path("unitPrice").asLong(),
              l.path("originalQty").asInt(),
              unfulfilled));
      if (unfulfilled > 0) {
        all.add(new ClaimRules.Requested(id, unfulfilled));
      }
      byLine.put(id, l);
    }
    CreateCommand in =
        new CreateCommand(orderId, "CANCEL", all, reason, reasonDetail, SYSTEM_CHANNEL);
    Opened o =
        idempotency
            .execute(
                new RequestKey(sourceSystem, idempotencyKey, "createSystemCancellation", actor()),
                in,
                Opened.class,
                () -> {
                  ClaimRow active = store.activeCancel(orderId).orElse(null);
                  if (active != null) {
                    log.info(
                        "주문 {} 에 진행 중인 취소 {} 가 있어 자동 취소를 새로 만들지 않는다", orderId, active.claimId());
                    return new Opened(active.claimId(), false);
                  }
                  if (all.isEmpty()) {
                    throw new PmartException(
                        "ORDER_NOT_CANCELLABLE",
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        "취소할 미이행 수량이 없습니다.",
                        false,
                        List.of());
                  }
                  try {
                    ClaimRules.checkFullCancel(order.path("status").asString(), lines, all, true);
                  } catch (ClaimRules.Rejection r) {
                    throw new PmartException(
                        r.code(),
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        r.getMessage(),
                        false,
                        List.of());
                  }
                  return new Opened(
                      open(order.path("accountId").asString(), in, order, byLine), true);
                })
            .value();
    return new SystemCancellation(view(o.claimId()), o.created());
  }

  private String open(
      String accountId, CreateCommand in, JsonNode order, Map<String, JsonNode> byLine) {
    if (store.activeCancel(in.orderId()).isPresent()) {
      throw new PmartException(
          "DUPLICATE_CLAIM",
          HttpStatus.UNPROCESSABLE_CONTENT,
          "이미 진행 중인 취소 요청이 있습니다.",
          false,
          List.of());
    }
    Instant now = clock.instant();
    String claimId = "CLM-" + UUID.randomUUID();
    String stopRequestId = "STOP-" + UUID.randomUUID();
    ClaimRow c =
        new ClaimRow(
            claimId,
            in.orderId(),
            accountId,
            in.type(),
            "STOP_PENDING",
            in.reason(),
            in.reasonDetail(),
            in.channel(),
            stopRequestId,
            1,
            order.path("paymentAttemptId").asString(),
            order.path("approvedAmount").asLong(),
            order.path("itemAmount").asLong(),
            order.path("bagFee").asLong(),
            order.path("deliveryTip").asLong(),
            null,
            now,
            null,
            null,
            now);
    List<LineRow> rows = new ArrayList<>();
    int n = 1;
    for (ClaimRules.Requested r : in.lines()) {
      JsonNode l = byLine.get(r.orderLineId());
      long unitPrice = l == null ? 0 : l.path("unitPrice").asLong();
      rows.add(
          new LineRow(
              claimId + "-L" + n++,
              claimId,
              r.orderLineId(),
              l == null ? "" : l.path("sku").asString(),
              r.qty(),
              null,
              "PENDING",
              unitPrice * r.qty()));
    }
    store.insert(c, rows);
    events.claimOpened(c, rows);
    events.cancellationRequested(c, rows);
    log.info("취소 접수 {} 주문 {} 중지 요청 {}", claimId, in.orderId(), stopRequestId);
    return claimId;
  }

  // ---------- 결품 부분 환불(ADR-0045) ----------

  public static final String OUT_OF_STOCK = "OUT_OF_STOCK";

  /** 결품 부분 환불 요청 행. */
  /**
   * @param feeDeduction 공제액(회수비 등 — ADR-0065 고객 잘못 오배송 9,000원). 환불 = 상품값 − 공제(0 이상)
   */
  public record ShortageRefund(
      String orderId,
      List<ClaimRules.Requested> lines,
      @Nullable String reasonDetail,
      @Nullable String sourceRef,
      long feeDeduction) {}

  /**
   * 품절 상품만 취소(CANCEL_OOS_ONLY) 의 부분 환불. 주문 수량 변경(SHORTAGE) 뒤에 부른다 — 요청 수량 ≤ 출고 전 취소 누계 − 이미 결품 환불한
   * 수량. 환불액 = 단가 × 수량(할인 안분 1차 제외). 클레임은 바로 IN_PROGRESS, 환불 의도 PARTIAL → 커밋 뒤 payment 부분 취소.
   */
  public ClaimView createShortageRefund(
      String idempotencyKey, String sourceSystem, ShortageRefund in) {
    JsonNode order = upstreams.order(in.orderId());
    if (order == null) {
      throw PmartException.notFound("ORDER_NOT_FOUND", "주문이 없습니다.");
    }
    Map<String, JsonNode> byLine = new LinkedHashMap<>();
    for (JsonNode l : order.path("lines")) {
      byLine.put(l.path("orderLineId").asString(), l);
    }
    String claimId =
        idempotency
            .execute(
                new RequestKey(sourceSystem, idempotencyKey, "createShortageRefund", actor()),
                in,
                String.class,
                () -> {
                  long amount = 0;
                  for (ClaimRules.Requested r : in.lines()) {
                    JsonNode l = byLine.get(r.orderLineId());
                    if (l == null) {
                      throw unprocessable(
                          "QUANTITY_EXCEEDS_AVAILABLE", "주문에 없는 행입니다: " + r.orderLineId());
                    }
                    if (l.path("discountShare").asLong(0) != 0) {
                      throw unprocessable(
                          "UNSUPPORTED_ACTION", "할인 안분이 있는 행의 부분 환불은 아직 지원하지 않습니다(1차 할인 제외).");
                    }
                    int cancelled = l.path("cancelledBeforeShipQty").asInt(0);
                    int refunded = store.shortageRefundedQty(in.orderId(), r.orderLineId());
                    if (r.qty() > cancelled - refunded) {
                      throw unprocessable(
                          "QUANTITY_EXCEEDS_AVAILABLE",
                          "행 "
                              + r.orderLineId()
                              + ": 요청 "
                              + r.qty()
                              + " > 출고 전 취소 "
                              + cancelled
                              + " − 이미 결품 환불 "
                              + refunded);
                    }
                    amount += l.path("unitPrice").asLong() * r.qty();
                  }
                  Instant now = clock.instant();
                  String id = "CLM-" + UUID.randomUUID();
                  long items = amount;
                  long refund = Math.max(0, items - Math.max(0, in.feeDeduction()));
                  boolean nothing = refund == 0;
                  ClaimRow c =
                      new ClaimRow(
                          id,
                          in.orderId(),
                          order.path("accountId").asString(),
                          "CANCEL",
                          nothing ? "CLOSED" : "IN_PROGRESS",
                          OUT_OF_STOCK,
                          (in.reasonDetail() == null ? "결품" : in.reasonDetail())
                              + (in.sourceRef() == null ? "" : " [" + in.sourceRef() + "]"),
                          SYSTEM_CHANNEL,
                          null,
                          1,
                          order.path("paymentAttemptId").asString(),
                          order.path("approvedAmount").asLong(),
                          amount,
                          0,
                          0,
                          null,
                          now,
                          now,
                          nothing ? now : null,
                          now);
                  List<LineRow> rows = new ArrayList<>();
                  int n = 1;
                  for (ClaimRules.Requested r : in.lines()) {
                    JsonNode l = byLine.get(r.orderLineId());
                    rows.add(
                        new LineRow(
                            id + "-L" + n++,
                            id,
                            r.orderLineId(),
                            l.path("sku").asString(),
                            r.qty(),
                            r.qty(),
                            "APPROVED",
                            l.path("unitPrice").asLong() * r.qty()));
                  }
                  store.insert(c, rows);
                  events.claimOpened(c, rows);
                  if (nothing) {
                    // 공제가 상품값 이상 — 돌려줄 돈이 없다(ADR-0065)
                    log.info("부분 환불 {} 주문 {} 공제 뒤 0원 — 환불 없이 닫음", id, in.orderId());
                    return id;
                  }
                  RefundRow refundRow = refunds.createShortageRefund(c, items, refund, now);
                  String refundId = refundRow.refundIntentId();
                  TransactionSynchronizationManager.registerSynchronization(
                      new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                          refunds.executeAsync(refundId);
                        }
                      });
                  log.info(
                      "결품 부분 환불 {} 주문 {} {}원(공제 {})", id, in.orderId(), refund, items - refund);
                  return id;
                })
            .value();
    return view(claimId);
  }

  private static PmartException unprocessable(String code, String message) {
    return new PmartException(code, HttpStatus.UNPROCESSABLE_CONTENT, message, false, List.of());
  }

  // ---------- 조회 ----------

  /** 고객은 본인 것만(남의 것 404), 운영자·서비스는 모두. */
  public ClaimView get(PmartPrincipal who, String claimId) {
    ClaimRow c =
        store
            .find(claimId)
            .filter(x -> canSee(who, x))
            .orElseThrow(() -> PmartException.notFound("CLAIM_NOT_FOUND", "클레임이 없습니다."));
    return new ClaimView(c, store.lines(claimId), store.refunds(claimId));
  }

  public List<ClaimRow> list(
      PmartPrincipal who,
      @Nullable String orderId,
      @Nullable String type,
      @Nullable String state,
      int size) {
    String account = who.type() == PmartPrincipal.PrincipalType.CUSTOMER ? who.subject() : null;
    if (account == null
        && who.type() != PmartPrincipal.PrincipalType.OPERATOR
        && who.type() != PmartPrincipal.PrincipalType.SERVICE) {
      throw new PmartException(com.pmart.common.web.ErrorCode.FORBIDDEN, "클레임을 볼 수 없습니다.");
    }
    return store.list(account, orderId, type, state, Math.max(1, Math.min(size, 100)));
  }

  private ClaimView view(String claimId) {
    ClaimRow c = store.find(claimId).orElseThrow();
    return new ClaimView(c, store.lines(claimId), store.refunds(claimId));
  }

  private static boolean canSee(PmartPrincipal who, ClaimRow c) {
    return switch (who.type()) {
      case CUSTOMER -> who.subject().equals(c.accountId());
      case OPERATOR, SERVICE -> true;
      default -> false;
    };
  }

  // ---------- 사건 반영 (inbox 트랜잭션 안) ----------

  /** order.order.v1 — OrderCancelled(claimId) 로 취소 확정·환불 의도. */
  public void onOrderEvent(EventEnvelope e) {
    if (!"OrderCancelled".equals(e.eventType())) {
      return;
    }
    JsonNode p = e.payload();
    String claimId = p.path("claimId").asString(null);
    if (claimId == null || claimId.isBlank()) {
      return; // 클레임 없는 취소(결품 등)는 이 단계에서 다루지 않는다
    }
    ClaimRow c = store.lock(claimId).orElse(null);
    if (c == null || !"STOP_PENDING".equals(c.state())) {
      return; // 이미 반영됐거나 모르는 클레임
    }
    if (!"FULL".equals(p.path("scope").asString())) {
      log.warn("클레임 {} 부분 취소 확정은 아직 다루지 않는다 — 진행 중으로 둔다", claimId);
      return;
    }
    Instant now = clock.instant();
    for (JsonNode l : p.path("lines")) {
      store.decideLine(
          claimId, l.path("orderLineId").asString(), l.path("qty").asInt(), "APPROVED");
    }
    store.updateState(claimId, "IN_PROGRESS", null, now, null, now);
    RefundRow refund = refunds.createFullRefund(c, now);
    if ("PENDING_EXECUTION".equals(refund.state())) {
      String id = refund.refundIntentId();
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              refunds.executeAsync(id);
            }
          });
    }
    log.info("클레임 {} 취소 확정 — 환불 {} {}원", claimId, refund.refundIntentId(), refund.amount());
  }

  /** mfc.fulfillment.v1 — WorkStopResult 에 인계됨이 있으면 출고 전 취소 불가(REJECTED). */
  public void onFulfillmentEvent(EventEnvelope e) {
    if (!"WorkStopResult".equals(e.eventType())) {
      return;
    }
    JsonNode p = e.payload();
    String stopRequestId = p.path("stopRequestId").asString(null);
    if (stopRequestId == null) {
      return;
    }
    boolean handedOver = false;
    for (JsonNode pkg : p.path("perPackage")) {
      if ("HANDED_OVER".equals(pkg.path("state").asString())) {
        handedOver = true;
      }
    }
    if (!handedOver) {
      return; // 중지 확정·대기 — 주문 취소 확정(OrderCancelled)을 기다린다
    }
    ClaimRow c = store.lockByStopRequest(stopRequestId).orElse(null);
    if (c == null || !"STOP_PENDING".equals(c.state())) {
      return;
    }
    Instant now = clock.instant();
    store.updateState(c.claimId(), "REJECTED", "HANDED_OVER", now, now, now);
    events.claimClosed(c, "REJECTED_HANDED_OVER", now);
    log.info("클레임 {} 인계된 포장이 있어 출고 전 취소 불가", c.claimId());
  }

  private static String actor() {
    return PmartPrincipal.current()
        .map(PmartPrincipal::subject)
        .orElse(RequestIdempotency.ANONYMOUS);
  }

  private static PmartException unsupported(String message) {
    return new PmartException(
        "UNSUPPORTED_ACTION", HttpStatus.UNPROCESSABLE_CONTENT, message, false, List.of());
  }
}
