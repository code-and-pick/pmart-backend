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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 운영자 상태 정정(2026-11-17, 0.17.0 — 원문 §9.3·§6.4, 사용자 "라이더 운영 관리자가 승인"): 라이더 운영·관리자가 정정하는 것이 곧 승인. 픽업
 * 전 ASSIGNED ↔ ARRIVED_AT_STORE, 픽업 뒤 PICKED_UP ↔ ARRIVED_AT_CUSTOMER 만. 이력은 새로 남기고 지우지 않는다. 사유는 로그
 * 금지.
 */
@Service
public class StatusCorrectionService {

  private static final Logger log = LoggerFactory.getLogger(StatusCorrectionService.class);
  private static final java.util.Set<String> TYPES = java.util.Set.of("FORWARD", "RESHIPMENT");

  public record Command(String targetState, String reason, List<String> evidenceRefs) {}

  private final ExecutionStore store;
  private final ExecutionEvents events;
  private final RequestIdempotency idempotency;
  private final ObjectMapper mapper;
  private final Clock clock;

  public StatusCorrectionService(
      ExecutionStore store,
      ExecutionEvents events,
      RequestIdempotency idempotency,
      ObjectMapper mapper,
      Clock clock) {
    this.store = store;
    this.events = events;
    this.idempotency = idempotency;
    this.mapper = mapper;
    this.clock = clock;
  }

  public ExecutionService.Applied correct(
      String deliveryId,
      String requestId,
      String source,
      @Nullable Integer expectedVersion,
      Command c) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .filter(x -> x.type() == PmartPrincipal.PrincipalType.OPERATOR)
            .orElseThrow(() -> ExecutionService.forbidden("운영자만 정정할 수 있습니다."));
    if (!(p.hasRole("RIDER_OPS") || p.hasRole("OPERATOR_ADMIN"))) {
      throw ExecutionService.forbidden("상태 정정은 라이더 운영·관리자만 할 수 있습니다.");
    }
    return idempotency
        .execute(
            new RequestKey(source, requestId, "correctDeliveryStatus", p.subject()),
            Map.of(
                "deliveryId", deliveryId,
                "command", c,
                "expectedVersion", String.valueOf(expectedVersion)),
            ExecutionService.Applied.class,
            () -> {
              Instant now = clock.instant();
              DeliveryRow d =
                  store
                      .lockDelivery(deliveryId)
                      .orElseThrow(
                          () -> PmartException.notFound("DELIVERY_NOT_FOUND", "배송이 없습니다."));
              if (expectedVersion != null && expectedVersion != d.version()) {
                throw PmartException.versionConflict("배송이 바뀌었습니다(현재 버전 " + d.version() + ").");
              }
              if (!TYPES.contains(d.type())
                  || !ExecutionRules.correctionAllowed(d.state(), c.targetState())) {
                throw new PmartException(
                    "CORRECTION_NOT_ALLOWED",
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "이 정정은 할 수 없습니다(지금 "
                        + d.state()
                        + " → "
                        + c.targetState()
                        + "). 픽업 전 배정↔가게 도착, 픽업 뒤 픽업↔고객 도착만 바꿉니다.",
                    false,
                    List.of());
              }
              // 근거 사진은 이 배송의 것만(2026-11-18 — 직원은 staff-evidence-photos 로 올린다)
              for (String ref : c.evidenceRefs()) {
                if (store.photo(ref).filter(ph -> ph.deliveryId().equals(deliveryId)).isEmpty()) {
                  throw new PmartException(
                      "PHOTO_NOT_FOUND",
                      HttpStatus.UNPROCESSABLE_CONTENT,
                      "이 배송에 올린 사진이 아닙니다.",
                      false,
                      List.of());
                }
              }
              String id = "COR-" + UUID.randomUUID();
              store.insertCorrection(
                  id,
                  deliveryId,
                  d.state(),
                  c.targetState(),
                  c.reason(),
                  mapper.writeValueAsString(c.evidenceRefs()),
                  p.subject(),
                  now);
              store.setState(deliveryId, c.targetState(), now);
              store.history(deliveryId, c.targetState(), now, "OPERATOR", id, now);
              events.statusCorrected(
                  d, d.state(), c.targetState(), p.subject(), c.reason(), c.evidenceRefs(), now);
              log.info("배송 {} 상태 정정 {} → {} ({})", deliveryId, d.state(), c.targetState(), id);
              return new ExecutionService.Applied(id, deliveryId, d.version() + 1, now);
            })
        .value();
  }
}
