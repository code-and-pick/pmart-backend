package com.pmart.delivery.execution.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.PmartException;
import com.pmart.delivery.execution.domain.ExecutionRules;
import com.pmart.delivery.execution.infrastructure.ExecutionStore;
import com.pmart.delivery.execution.infrastructure.ExecutionStore.DeliveryRow;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 전달 실패 확정·반려(사용자 결정 2026-11-13 — 지원센터 화면 전까지 백오피스 권한자: 그 센터 운영자·OPERATOR_ADMIN·CS_AGENT).
 *
 * <ul>
 *   <li>CONFIRM → FAILED + DeliveryFailed(배송 종료 — dispatch CLOSED, 라이더 진행 중 수에서 빠짐). 수행료는 산정 불가.
 *       이어지는 회수 배차는 새 배송.
 *   <li>REJECT → 실패 등록 전 상태로(라이더가 전달을 계속).
 * </ul>
 */
@Service
public class FailureDecisionService {

  private static final Logger log = LoggerFactory.getLogger(FailureDecisionService.class);

  public record Decision(String decision, @Nullable String note, @Nullable String custody) {}

  private final ExecutionStore store;
  private final ExecutionEvents events;
  private final EarningService earnings;
  private final RequestIdempotency idempotency;
  private final ObjectMapper mapper;
  private final Clock clock;

  public FailureDecisionService(
      ExecutionStore store,
      ExecutionEvents events,
      EarningService earnings,
      RequestIdempotency idempotency,
      ObjectMapper mapper,
      Clock clock) {
    this.store = store;
    this.events = events;
    this.earnings = earnings;
    this.idempotency = idempotency;
    this.mapper = mapper;
    this.clock = clock;
  }

  public ExecutionService.Applied decide(
      String deliveryId,
      String requestId,
      String source,
      @Nullable Integer expectedVersion,
      Decision c) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .filter(x -> x.type() == PmartPrincipal.PrincipalType.OPERATOR)
            .orElseThrow(() -> ExecutionService.forbidden("운영자만 실패를 확정·반려할 수 있습니다."));
    return idempotency
        .execute(
            new RequestKey(source, requestId, "decideFailure", p.subject()),
            Map.of(
                "deliveryId", deliveryId,
                "decision", c,
                "expectedVersion", String.valueOf(expectedVersion)),
            ExecutionService.Applied.class,
            () -> {
              Instant now = clock.instant();
              DeliveryRow d =
                  store
                      .lockDelivery(deliveryId)
                      .orElseThrow(
                          () -> PmartException.notFound("DELIVERY_NOT_FOUND", "배송이 없습니다."));
              if (!Access.operatorCanDecide(p, d.mfcId())) {
                throw ExecutionService.forbidden(
                    "이 배송의 실패를 결정할 권한이 없습니다(센터 관리자·OPERATOR_ADMIN·CS_AGENT).");
              }
              if (expectedVersion != null && expectedVersion != d.version()) {
                throw PmartException.versionConflict("배송이 바뀌었습니다(현재 버전 " + d.version() + ").");
              }
              if (!"FAILURE_REPORTED".equals(d.state()) || d.failureJson() == null) {
                throw PmartException.invalidState("실패 등록된 배송만 결정할 수 있습니다(지금 " + d.state() + ").");
              }
              JsonNode reported = mapper.readTree(d.failureJson());
              Map<String, Object> failure = new LinkedHashMap<>();
              reported.properties().forEach(en -> failure.put(en.getKey(), en.getValue()));
              failure.put("decision", c.decision());
              failure.put("decidedBy", p.subject());
              failure.put("decidedAt", now.toString());
              if (c.note() != null) {
                failure.put("decisionNote", c.note());
              }
              if ("CONFIRM".equals(c.decision()) && d.claimReturn()) {
                // 고객 반품 회수 실패(CL-1, 2026-11-17): 상품은 고객에게 그대로 — custody 없음. 다시 시도는 반품 쪽이 정한다.
                // 수행료: 고객 사정(부재·연락 두절·주소 오류·거부)이면 수락 당시 금액(사용자 "수락 당시 금액 지급"), 그 밖은 지원센터 조정
                String reason = reported.path("reason").asString("OTHER");
                boolean customerReason = ExecutionRules.CUSTOMER_REASONS.contains(reason);
                store.failure(deliveryId, "FAILED", mapper.writeValueAsString(failure), now);
                store.history(deliveryId, "FAILED", now, "OPERATOR", requestId, now);
                events.returnPickupFailed(
                    d,
                    reason,
                    customerReason,
                    reported.path("contactAttempts").size(),
                    reported.path("reportedAt").asString(null),
                    p.subject(),
                    now);
                if (customerReason) {
                  earnings.completed(d, now);
                } else {
                  earnings.notCalculable(d, "FAILED", now);
                }
                log.info("반품 회수 배송 {} 회수 실패 확정 — 사유 {}", deliveryId, reason);
              } else if ("CONFIRM".equals(c.decision())) {
                String goods = reported.path("goodsLocation").asString(null);
                String custody = ExecutionRules.custody(c.custody(), goods);
                failure.put("custody", custody);
                store.failure(deliveryId, "FAILED", mapper.writeValueAsString(failure), now);
                store.history(deliveryId, "FAILED", now, "OPERATOR", requestId, now);
                String reason = reported.path("reason").asString("OTHER");
                events.failed(d, reason, custody, goods, p.subject(), now);
                // 수행료(2026-11-17 사용자 "고객 귀책이면 라이더는 잘못 없으니 원래 측정돼 있던 수행료"): 고객 사유(부재·연락 두절·주소
                // 오류·거부)면 수락 당시 금액, 그 밖은 산정 불가 → 지원센터 조정. 상품이 라이더 손이면 dispatch 가 반납 배송을 같은 라이더에게
                if (ExecutionRules.CUSTOMER_REASONS.contains(reason)) {
                  earnings.completed(d, now);
                } else {
                  earnings.notCalculable(d, "FAILED", now);
                }
                log.info("배송 {} 전달 실패 확정 — 상품 {}", deliveryId, custody);
              } else {
                String back = reported.path("previousState").asString("ARRIVED_AT_CUSTOMER");
                store.failure(deliveryId, back, mapper.writeValueAsString(failure), now);
                store.history(
                    deliveryId, back, now, "OPERATOR", "FAILURE_REJECTED:" + requestId, now);
                log.info("배송 {} 전달 실패 반려 — {} 로 되돌림", deliveryId, back);
              }
              int version =
                  store.delivery(deliveryId).map(DeliveryRow::version).orElse(d.version() + 1);
              return new ExecutionService.Applied(requestId, deliveryId, version, now);
            })
        .value();
  }
}
