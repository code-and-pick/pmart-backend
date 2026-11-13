package com.pmart.delivery.execution.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.PmartException;
import com.pmart.delivery.execution.domain.ExecutionRules;
import com.pmart.delivery.execution.infrastructure.ExecutionStore;
import com.pmart.delivery.execution.infrastructure.ExecutionStore.DeliveryRow;
import com.pmart.delivery.execution.infrastructure.ExecutionStore.HelpRow;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 라이더 도움요청(원문 §5.4·§9.1, 사용자 결정 2026-11-13 "1번"): 이 서비스에 기록 + DeliveryIncidentRaised(type
 * HELP_REQUEST). case-service 가 받아 지원센터 사건을 만든다(구현 전 — {@code service-requests/case-service.md}).
 * 사건 번호는 그 뒤 연결된다. hr-service GrantRequested → approval-service 와 같은 모양(받은 쪽이 자기 기록을 만든다).
 */
@Service
public class HelpRequestService {

  private static final Logger log = LoggerFactory.getLogger(HelpRequestService.class);

  public record Created(String requestId, String deliveryId, int version, Instant createdAt) {}

  private final ExecutionStore store;
  private final ExecutionEvents events;
  private final RequestIdempotency idempotency;
  private final ObjectMapper mapper;
  private final Clock clock;

  public HelpRequestService(
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

  public Created create(
      String deliveryId,
      String requestId,
      String source,
      String category,
      @Nullable String detail,
      List<String> evidenceRefs) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .filter(x -> x.type() == PmartPrincipal.PrincipalType.RIDER)
            .orElseThrow(() -> ExecutionService.forbidden("라이더만 도움요청할 수 있습니다."));
    return idempotency
        .execute(
            new RequestKey(source, requestId, "createHelpRequest", p.subject()),
            Map.of(
                "deliveryId", deliveryId,
                "category", category,
                "detail", String.valueOf(detail),
                "evidenceRefs", evidenceRefs),
            Created.class,
            () -> {
              Instant now = clock.instant();
              DeliveryRow d =
                  store
                      .lockDelivery(deliveryId)
                      .orElseThrow(
                          () -> PmartException.notFound("DELIVERY_NOT_FOUND", "배송이 없습니다."));
              if (!p.subject().equals(d.riderId())) {
                throw ExecutionService.forbidden("본인에게 배정된 배송만 도움요청할 수 있습니다.");
              }
              if (!ExecutionRules.HELP_FROM.contains(d.state())) {
                throw PmartException.invalidState("진행 중인 배송만 도움요청할 수 있습니다(지금 " + d.state() + ").");
              }
              HelpRow h =
                  new HelpRow(
                      requestId,
                      deliveryId,
                      p.subject(),
                      category,
                      detail,
                      mapper.writeValueAsString(evidenceRefs),
                      d.state(),
                      "OPEN",
                      null,
                      now);
              if (!store.insertHelp(h)) {
                throw PmartException.idempotencyConflict(
                    "같은 Idempotency-Key 로 다른 요청이 이미 처리되었습니다. 새 키를 사용하세요.");
              }
              int version = store.bumpVersion(deliveryId, now);
              events.helpRequested(d, h, evidenceRefs, version);
              log.info("배송 {} 도움요청 {} — {}", deliveryId, requestId, category); // 내용은 로그에 남기지 않는다
              return new Created(requestId, deliveryId, version, now);
            })
        .value();
  }

  /** 요청한 라이더 본인, 그 센터 운영자·OPERATOR_ADMIN·CS_AGENT. 볼 수 없으면 404. */
  public HelpRow get(String deliveryId, String requestId) {
    PmartPrincipal p =
        PmartPrincipal.current().orElseThrow(() -> ExecutionService.forbidden("인증이 필요합니다."));
    HelpRow h =
        store
            .help(deliveryId, requestId)
            .orElseThrow(() -> PmartException.notFound("HELP_REQUEST_NOT_FOUND", "도움요청이 없습니다."));
    String mfcId = store.delivery(deliveryId).map(DeliveryRow::mfcId).orElse(null);
    boolean ok =
        p.type() == PmartPrincipal.PrincipalType.SERVICE
            || (p.type() == PmartPrincipal.PrincipalType.RIDER && p.subject().equals(h.riderId()))
            || Access.operatorCanSee(p, mfcId);
    if (!ok) {
      throw PmartException.notFound("HELP_REQUEST_NOT_FOUND", "도움요청이 없습니다.");
    }
    return h;
  }
}
