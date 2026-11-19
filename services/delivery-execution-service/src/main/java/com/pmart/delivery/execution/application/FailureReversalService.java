package com.pmart.delivery.execution.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.delivery.execution.infrastructure.ExecutionStore;
import com.pmart.delivery.execution.infrastructure.ExecutionStore.DeliveryRow;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 전달 실패 확정 되돌리기(2026-11-19 — 사용자 "확정 되돌리기 기능 만들기", LAN 28767701-1 반려하려다 직접 확정이 눌림). 짧은 시간 안, 상품이
 * 라이더 손, 반납 배송을 아직 받기 전, 수행료가 확정 전일 때만 — 배송을 실패 등록 전 상태로 되돌리고 {@code DeliveryFailureReverted} 로 다른
 * 서비스가 따라 되돌린다. 실패 기록은 decision REJECT(라이더 앱은 반려처럼 전달을 이어감) + reversed* 칸.
 */
@Service
public class FailureReversalService {

  private static final Logger log = LoggerFactory.getLogger(FailureReversalService.class);
  private static final Set<String> RETURN_NOT_TAKEN = Set.of("UNASSIGNED", "ASSIGNED", "CANCELLED");
  private static final Set<String> UNSETTLED = Set.of("ESTIMATED", "PENDING_CONFIRMATION");

  private final ExecutionStore store;
  private final ExecutionEvents events;
  private final RequestIdempotency idempotency;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final Duration window;

  public FailureReversalService(
      ExecutionStore store,
      ExecutionEvents events,
      RequestIdempotency idempotency,
      ObjectMapper mapper,
      Clock clock,
      @Value("${pmart.execution.failure-reversal-window:PT30M}") Duration window) {
    this.store = store;
    this.events = events;
    this.idempotency = idempotency;
    this.mapper = mapper;
    this.clock = clock;
    this.window = window;
  }

  static PmartException notReversible(String why) {
    return new PmartException(
        "FAILURE_NOT_REVERSIBLE", HttpStatus.CONFLICT, "되돌릴 수 없습니다 — " + why, false, List.of());
  }

  public ExecutionService.Applied reverse(
      String deliveryId, String requestId, String source, String reason) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .filter(
                x ->
                    x.type() == PmartPrincipal.PrincipalType.OPERATOR
                        && (x.hasRole("RIDER_OPS") || x.hasRole("OPERATOR_ADMIN")))
            .orElseThrow(() -> ExecutionService.forbidden("확정 되돌리기는 라이더 운영·관리자만 할 수 있습니다."));
    String why = reason.strip();
    if (why.length() < 2 || why.length() > 300) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "되돌리는 까닭은 2~300자입니다.");
    }
    return idempotency
        .execute(
            new RequestKey(source, requestId, "reverseDeliveryFailure", p.subject()),
            Map.of("deliveryId", deliveryId, "reasonLength", why.length()),
            ExecutionService.Applied.class,
            () -> {
              Instant now = clock.instant();
              DeliveryRow d =
                  store
                      .lockDelivery(deliveryId)
                      .orElseThrow(
                          () -> PmartException.notFound("DELIVERY_NOT_FOUND", "배송이 없습니다."));
              if (!"FAILED".equals(d.state()) || d.failureJson() == null) {
                throw notReversible("실패 확정된 배송이 아닙니다(지금 " + d.state() + ").");
              }
              if ("RETURN".equals(d.type())) {
                throw notReversible("회수·반납 배송은 되돌리지 않습니다.");
              }
              ObjectNode failure = (ObjectNode) mapper.readTree(d.failureJson());
              if (!"CONFIRM".equals(failure.path("decision").asString(""))) {
                throw notReversible("확정된 실패가 아닙니다.");
              }
              Instant decidedAt = Instant.parse(failure.path("decidedAt").asString());
              if (now.isAfter(decidedAt.plus(window))) {
                throw notReversible("확정 뒤 " + window.toMinutes() + "분이 지났습니다.");
              }
              if (!"RIDER".equals(failure.path("custody").asString(""))) {
                throw notReversible("상품이 라이더에게 없습니다(매장 보관).");
              }
              String ret = store.failedReturnOf(deliveryId).orElse(null);
              if (ret != null) {
                String retState = store.delivery(ret).map(DeliveryRow::state).orElse("UNASSIGNED");
                if (!RETURN_NOT_TAKEN.contains(retState)) {
                  throw notReversible("반납 배송이 이미 진행됐습니다(" + retState + ").");
                }
              }
              if (d.riderId() != null) {
                var earning = store.earningOf(deliveryId, d.riderId()).orElse(null);
                if (earning != null) {
                  if (!UNSETTLED.contains(earning.settlementState())
                      || store.deleteUnsettledEarning(earning.earningId()) == 0) {
                    throw notReversible("수행료가 이미 확정·조정됐습니다.");
                  }
                }
              }
              String back = failure.path("previousState").asString("PICKED_UP");
              String originalReason = failure.path("reason").asString("");
              failure.put("decision", "REJECT");
              failure.put("reversedAt", now.toString());
              failure.put("reversedBy", p.subject());
              failure.put("reversalReason", why);
              store.failure(deliveryId, back, mapper.writeValueAsString(failure), now);
              store.history(
                  deliveryId, back, now, "OPERATOR", "FAILURE_REVERSED:" + requestId, now);
              events.failureReverted(d, originalReason, back, ret, p.subject(), now);
              log.info("배송 {} 실패 확정 되돌림 → {}(반납 배송 {})", deliveryId, back, ret);
              int version =
                  store.delivery(deliveryId).map(DeliveryRow::version).orElse(d.version() + 1);
              return new ExecutionService.Applied(requestId, deliveryId, version, now);
            })
        .value();
  }

  static boolean isReverted(JsonNode failure) {
    return failure.path("reversedAt").isString();
  }
}
