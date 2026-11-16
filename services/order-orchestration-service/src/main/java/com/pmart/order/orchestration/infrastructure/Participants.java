package com.pmart.order.orchestration.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.common.web.PmartException;
import com.pmart.order.orchestration.application.OrchestrationProperties;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * S1 참여 정본들의 {@code /internal} 명령(서비스 간 인증 없음 — ADR-0026). 결과는 세 가지로만 나눈다: 성공(정본 결과 ID), 업무 거절(4xx
 * — 보상 대상), 불명·미전달(재전송·원결과 조회 대상 — 실패로 단정하지 않는다).
 */
@Component
public class Participants {

  public sealed interface Result permits Ok, Rejected, Unknown {}

  /** 성공. {@code expiresAt} 은 정본이 준 점유 만료(없으면 null). */
  public record Ok(String resultId, @Nullable Instant expiresAt, JsonNode body) implements Result {}

  /**
   * @param details Problem details(필드별 사유 — 재고 부족 수량 등). 없으면 null
   */
  public record Rejected(String code, String message, @Nullable JsonNode details)
      implements Result {
    public Rejected(String code, String message) {
      this(code, message, null);
    }
  }

  public record Unknown(String code, String message) implements Result {}

  public static final String INVENTORY = "mfc-inventory-service";
  public static final String CAPACITY = "mfc-capacity-service";
  public static final String SLOT = "delivery-capacity-service";
  public static final String PAYMENT = "payment-service";
  public static final String ORDER = "order-service";
  public static final String FULFILLMENT = "mfc-fulfillment-service";
  public static final String CLAIM = "claim-service";
  public static final String DISPATCH = "delivery-dispatch-service";

  private final RestClient inventory;
  private final RestClient capacity;
  private final RestClient slot;
  private final RestClient payment;
  private final RestClient order;
  private final RestClient fulfillment;
  private final RestClient claim;
  private final RestClient dispatch;
  private final ObjectMapper mapper;

  public Participants(
      RestClient.Builder builder, OrchestrationProperties props, ObjectMapper mapper) {
    this.inventory = builder.clone().baseUrl(props.mfcInventoryBaseUrl()).build();
    this.capacity = builder.clone().baseUrl(props.mfcCapacityBaseUrl()).build();
    this.slot = builder.clone().baseUrl(props.deliveryCapacityBaseUrl()).build();
    this.payment = builder.clone().baseUrl(props.paymentBaseUrl()).build();
    this.order = builder.clone().baseUrl(props.orderBaseUrl()).build();
    this.fulfillment = builder.clone().baseUrl(props.mfcFulfillmentBaseUrl()).build();
    this.claim = builder.clone().baseUrl(props.claimBaseUrl()).build();
    this.dispatch = builder.clone().baseUrl(props.deliveryDispatchBaseUrl()).build();
    this.mapper = mapper;
  }

  // ---------- 확보 ----------

  public Result reserveInventory(String requestId, Map<String, Object> body) {
    Result r =
        post(
            inventory,
            INVENTORY + " reservations",
            "/api/v1/internal/reservations",
            requestId,
            body);
    if (r instanceof Ok ok) {
      // 만료 시각은 정본 예약 조회로(명령 응답에는 없다)
      return new Ok(
          ok.resultId(),
          expiresAt(inventory, INVENTORY, "/api/v1/internal/reservations/{id}", ok.resultId()),
          ok.body());
    }
    return r;
  }

  public Result holdCapacity(String requestId, Map<String, Object> body) {
    Result r =
        post(
            capacity,
            CAPACITY + " capacity-holds",
            "/api/v1/internal/capacity-holds",
            requestId,
            body);
    if (r instanceof Ok ok) {
      return new Ok(
          ok.resultId(),
          expiresAt(capacity, CAPACITY, "/api/v1/internal/capacity-holds/{id}", ok.resultId()),
          ok.body());
    }
    return r;
  }

  /** delivery-capacity 는 명령 응답이 SlotHold 자체다(resultId 대신 holdId). */
  public Result holdSlot(String requestId, Map<String, Object> body) {
    Result r = post(slot, SLOT + " slot-holds", "/api/v1/internal/slot-holds", requestId, body);
    if (r instanceof Ok ok) {
      return new Ok(
          ok.body().path("holdId").asString(), instant(ok.body(), "expiresAt"), ok.body());
    }
    return r;
  }

  /** 결제 시작(결제 시도 생성·결제창 진입 정보). 응답 resultId 는 paymentAttemptId. */
  public Result startPayment(String requestId, Map<String, Object> body) {
    Result r = post(payment, PAYMENT + " payments", "/api/v1/internal/payments", requestId, body);
    if (r instanceof Ok ok) {
      JsonNode checkout = ok.body().path("checkout");
      return new Ok(
          ok.body().path("paymentAttemptId").asString(), instant(checkout, "expiresAt"), ok.body());
    }
    return r;
  }

  // ---------- 해제·확인 ----------

  public Result release(String resourceType, String resourceId, String requestId, String sagaId) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("reason", "SAGA_COMPENSATION");
    return switch (resourceType) {
      case "INVENTORY_RESERVATION" -> {
        body.put("sourceRef", sagaId);
        yield post(
            inventory,
            INVENTORY + " releases",
            "/api/v1/internal/reservations/" + resourceId + "/releases",
            requestId,
            body);
      }
      case "CAPACITY_HOLD" -> {
        body.put("sourceRef", sagaId);
        yield post(
            capacity,
            CAPACITY + " releases",
            "/api/v1/internal/capacity-holds/" + resourceId + "/releases",
            requestId,
            body);
      }
      case "SLOT_HOLD" -> {
        body.put("sagaId", sagaId);
        yield post(
            slot,
            SLOT + " releases",
            "/api/v1/internal/slot-holds/" + resourceId + "/releases",
            requestId,
            body);
      }
      default -> throw new IllegalArgumentException("해제할 수 없는 자원: " + resourceType);
    };
  }

  /** 원예약 조회로 해제를 확인한다. 조회 실패면 null(확인 전 — RELEASED 로 표시하지 않는다). */
  public @Nullable String resourceState(String resourceType, String resourceId) {
    try {
      JsonNode n =
          switch (resourceType) {
            case "INVENTORY_RESERVATION" ->
                query(inventory, INVENTORY, "/api/v1/internal/reservations/{id}", resourceId);
            case "CAPACITY_HOLD" ->
                query(capacity, CAPACITY, "/api/v1/internal/capacity-holds/{id}", resourceId);
            case "SLOT_HOLD" -> query(slot, SLOT, "/api/v1/internal/slot-holds/{id}", resourceId);
            case "PAYMENT_APPROVAL" ->
                query(payment, PAYMENT, "/api/v1/internal/payments/{id}", resourceId);
            default -> throw new IllegalArgumentException(resourceType);
          };
      return n.path("state").asString(null);
    } catch (PmartException | HttpClientErrorException e) {
      return null;
    }
  }

  /** 결제 시도 상태(조회 실패면 null). */
  public @Nullable String paymentState(String paymentAttemptId) {
    JsonNode p = payment(paymentAttemptId);
    return p == null ? null : p.path("state").asString(null);
  }

  /** 결제 시도 원결과(상태·승인액·PG 거래 ID). 조회 실패면 null — 확인 전에는 아무것도 단정하지 않는다. */
  public @Nullable JsonNode payment(String paymentAttemptId) {
    try {
      return query(payment, PAYMENT, "/api/v1/internal/payments/{id}", paymentAttemptId);
    } catch (PmartException | HttpClientErrorException e) {
      return null;
    }
  }

  // ---------- 승인 뒤 확정·보상 ----------

  /** 점유 확정(HELD → CONFIRMED). 이미 확정이면 정본이 멱등 성공을 준다. */
  public Result confirm(
      String resourceType,
      String resourceId,
      String requestId,
      String sagaId,
      String orderId,
      String paymentAttemptId) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("orderId", orderId);
    return switch (resourceType) {
      case "INVENTORY_RESERVATION" -> {
        body.put("paymentAttemptId", paymentAttemptId);
        yield post(
            inventory,
            INVENTORY + " confirmations",
            "/api/v1/internal/reservations/" + resourceId + "/confirmations",
            requestId,
            body);
      }
      case "CAPACITY_HOLD" ->
          post(
              capacity,
              CAPACITY + " confirmations",
              "/api/v1/internal/capacity-holds/" + resourceId + "/confirmations",
              requestId,
              body);
      case "SLOT_HOLD" -> {
        body.put("sagaId", sagaId);
        yield post(
            slot,
            SLOT + " confirmations",
            "/api/v1/internal/slot-holds/" + resourceId + "/confirmations",
            requestId,
            body);
      }
      default -> throw new IllegalArgumentException("확정할 수 없는 자원: " + resourceType);
    };
  }

  /** 주문 확정(order-service). 금액 불일치는 422 AMOUNT_MISMATCH. */
  public Result confirmOrder(String orderId, String requestId, Map<String, Object> body) {
    return post(
        order,
        ORDER + " confirmation",
        "/api/v1/internal/orders/" + orderId + "/confirmation",
        requestId,
        body);
  }

  /** 승인 후 보상 취소(전액). 결과는 결제 시도 조회(CANCELLED)로 확인한다. */
  public Result compensationCancel(
      String paymentAttemptId, String requestId, String sagaId, String step, String reason) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("sagaId", sagaId);
    body.put("step", step);
    body.put("reason", reason);
    return post(
        payment,
        PAYMENT + " compensation-cancellations",
        "/api/v1/internal/payments/" + paymentAttemptId + "/compensation-cancellations",
        requestId,
        body);
  }

  // ---------- S3 취소 ----------

  /** 주문 단위 작업 중지(mfc-fulfillment). 응답 본문이 판정(StopRequestResult). */
  public Result stopOrderWork(String requestId, Map<String, Object> body) {
    return post(
        fulfillment,
        FULFILLMENT + " stop-requests",
        "/api/v1/internal/stop-requests",
        requestId,
        body);
  }

  /** 중지 판정 재조회. 조회 실패면 null(확인 전 — 아무것도 단정하지 않는다). */
  public @Nullable JsonNode stopRequest(String stopRequestId) {
    try {
      return query(fulfillment, FULFILLMENT, "/api/v1/internal/stop-requests/{id}", stopRequestId);
    } catch (PmartException | HttpClientErrorException e) {
      return null;
    }
  }

  /** 확정된 점유를 취소로 해제(정본별 취소 사유). */
  public Result releaseForCancel(
      String resourceType,
      String resourceId,
      String requestId,
      String sagaId,
      String stopRequestId,
      String reason) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("reason", reason);
    return switch (resourceType) {
      case "INVENTORY_RESERVATION" -> {
        body.put("sourceRef", stopRequestId);
        yield post(
            inventory,
            INVENTORY + " releases",
            "/api/v1/internal/reservations/" + resourceId + "/releases",
            requestId,
            body);
      }
      case "CAPACITY_HOLD" -> {
        body.put("sourceRef", stopRequestId);
        yield post(
            capacity,
            CAPACITY + " releases",
            "/api/v1/internal/capacity-holds/" + resourceId + "/releases",
            requestId,
            body);
      }
      case "SLOT_HOLD" -> {
        body.put("sagaId", sagaId);
        yield post(
            slot,
            SLOT + " releases",
            "/api/v1/internal/slot-holds/" + resourceId + "/releases",
            requestId,
            body);
      }
      default -> throw new IllegalArgumentException("해제할 수 없는 자원: " + resourceType);
    };
  }

  /** 주문행 수량 변경(order-service, 출고 전 취소). */
  public Result applyOrderCancel(String orderId, String requestId, Map<String, Object> body) {
    return post(
        order,
        ORDER + " line-quantity-changes",
        "/api/v1/internal/orders/" + orderId + "/line-quantity-changes",
        requestId,
        body);
  }

  // ---------- S2 이행 요청 ----------

  /** 작업 접수(ConfirmOrder). 업무 거절도 201 본문(decision REJECTED)으로 온다 — Ok 의 body 를 본다. */
  public Result confirmWork(String requestId, Map<String, Object> body) {
    return post(
        fulfillment, FULFILLMENT + " work-orders", "/api/v1/internal/work-orders", requestId, body);
  }

  /** 재고 예약 원본(상태·조각). 조회 실패면 null — 되찾음을 단정하지 않는다. */
  public @Nullable JsonNode reservation(String reservationId) {
    try {
      return query(inventory, INVENTORY, "/api/v1/internal/reservations/{id}", reservationId);
    } catch (PmartException | HttpClientErrorException e) {
      return null;
    }
  }

  /** 시스템 자동 전체 취소(claim, D6-4). 응답 본문이 클레임(진행 중이던 것이면 200). */
  public Result systemCancel(String requestId, Map<String, Object> body) {
    return post(
        claim,
        CLAIM + " system-cancellations",
        "/api/v1/internal/claims/system-cancellations",
        requestId,
        body);
  }

  // ---------- 결품 사가(IV-09 2부, ADR-0045) ----------

  /** 주문 원장(행별 미이행 수량). 조회 실패면 null. */
  public @Nullable JsonNode orderLedger(String orderId) {
    try {
      return query(order, ORDER, "/api/v1/internal/orders/{id}", orderId);
    } catch (PmartException | HttpClientErrorException e) {
      return null;
    }
  }

  /** 결품 수량을 작업에서 뺀다(fulfillment). */
  public Result resolveShortage(String fulfillmentId, String requestId, Map<String, Object> body) {
    return post(
        fulfillment,
        FULFILLMENT + " shortage-resolutions",
        "/api/v1/internal/work-orders/" + fulfillmentId + "/shortage-resolutions",
        requestId,
        body);
  }

  /** 이행부족(BLOCKED) 해제(inventory). */
  public Result releaseShortfall(String reservationId, String requestId, Map<String, Object> body) {
    return post(
        inventory,
        INVENTORY + " shortfall-releases",
        "/api/v1/internal/reservations/" + reservationId + "/shortfall-releases",
        requestId,
        body);
  }

  /** 결품 부분 환불(claim). */
  public Result shortageRefund(String requestId, Map<String, Object> body) {
    return post(
        claim,
        CLAIM + " shortage-refunds",
        "/api/v1/internal/claims/shortage-refunds",
        requestId,
        body);
  }

  // ---------- 오배송 재발송(2026-11-16, ADR-0052 결정 4) ----------

  /** 배송 → 주문(dispatch 내부 배정 조회 — orderId·mfcId). 없는 배송이면 404 그대로, 장애는 PmartException. */
  public JsonNode deliveryAssignment(String deliveryId) {
    return query(dispatch, DISPATCH, "/api/v1/internal/deliveries/{id}/assignment", deliveryId);
  }

  /** 재고 예약 확정(결제 없음 — 재발송은 고객 비용 0원). */
  public Result confirmReservation(String reservationId, String requestId, String orderId) {
    return post(
        inventory,
        INVENTORY + " confirmations",
        "/api/v1/internal/reservations/" + reservationId + "/confirmations",
        requestId,
        Map.of("orderId", orderId));
  }

  // ---------- 오배송 다시 배송 9,000원(ADR-0065 4단계) ----------

  /** 결제됨 → dispatch 다시 배송 배차 풀기(멱등). */
  public Result releaseRedelivery(
      String deliveryId, String requestId, String caseId, @Nullable String paymentAttemptId) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("caseId", caseId);
    if (paymentAttemptId != null) {
      body.put("paymentAttemptId", paymentAttemptId);
    }
    return post(
        dispatch,
        DISPATCH + " redelivery-releases",
        "/api/v1/internal/deliveries/" + deliveryId + "/redelivery-releases",
        requestId,
        body);
  }

  /** 기한·거절 → dispatch 다시 배송 닫고 회수로(멱등). reason TIMED_OUT / DECLINED. */
  public Result cancelRedelivery(
      String deliveryId, String requestId, String caseId, String reason) {
    return post(
        dispatch,
        DISPATCH + " redelivery-cancellations",
        "/api/v1/internal/deliveries/" + deliveryId + "/redelivery-cancellations",
        requestId,
        Map.of("caseId", caseId, "reason", reason));
  }

  // ---------- 공통 ----------

  private Result post(RestClient client, String target, String uri, String requestId, Object body) {
    try {
      String raw =
          Downstream.call(
              target,
              () ->
                  client
                      .post()
                      .uri(uri)
                      .header("Idempotency-Key", requestId)
                      .contentType(MediaType.APPLICATION_JSON)
                      .body(body)
                      .retrieve()
                      .body(String.class));
      JsonNode n = raw == null || raw.isBlank() ? mapper.createObjectNode() : mapper.readTree(raw);
      return new Ok(n.path("resultId").asString(""), null, n);
    } catch (HttpClientErrorException e) {
      JsonNode p = problem(e.getResponseBodyAsString());
      String code = p.path("code").asString("HTTP_" + e.getStatusCode().value());
      String message = p.path("detail").asString(e.getStatusText());
      return new Rejected(code, message, p.has("details") ? p.get("details") : null);
    } catch (PmartException e) {
      // Downstream: 미전달(DEPENDENCY_UNAVAILABLE)·불명(UNKNOWN_RESULT) — 같은 키 재전송으로 해소
      return new Unknown(e.code(), e.getMessage());
    }
  }

  private JsonNode query(RestClient client, String target, String uri, String id) {
    String raw =
        Downstream.call(target, () -> client.get().uri(uri, id).retrieve().body(String.class));
    return raw == null ? mapper.createObjectNode() : mapper.readTree(raw);
  }

  /** 정본 조회 실패면 null — 호출자가 TTL 로 보수 추정한다. */
  private @Nullable Instant expiresAt(RestClient client, String target, String uri, String id) {
    try {
      return instant(query(client, target, uri, id), "expiresAt");
    } catch (PmartException | HttpClientErrorException e) {
      return null;
    }
  }

  private JsonNode problem(@Nullable String raw) {
    try {
      return raw == null || raw.isBlank() ? mapper.createObjectNode() : mapper.readTree(raw);
    } catch (RuntimeException e) {
      return mapper.createObjectNode();
    }
  }

  private static @Nullable Instant instant(JsonNode n, String field) {
    String v = n.path(field).asString(null);
    return v == null || v.isBlank() ? null : Instant.parse(v);
  }
}
