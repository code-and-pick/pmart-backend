package com.pmart.order.orchestration.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.observability.CorrelationScope;
import com.pmart.common.web.PmartException;
import com.pmart.order.orchestration.domain.CancellationRules;
import com.pmart.order.orchestration.domain.CancellationRules.StopVerdict;
import com.pmart.order.orchestration.infrastructure.CancellationStore;
import com.pmart.order.orchestration.infrastructure.CancellationStore.CancellationRow;
import com.pmart.order.orchestration.infrastructure.Participants;
import com.pmart.order.orchestration.infrastructure.Participants.Ok;
import com.pmart.order.orchestration.infrastructure.Participants.Rejected;
import com.pmart.order.orchestration.infrastructure.Participants.Result;
import com.pmart.order.orchestration.infrastructure.SagaStore;
import com.pmart.order.orchestration.infrastructure.SagaStore.CompensationRow;
import io.micrometer.tracing.Tracer;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * S3 취소 사가(docs/business/flows/cancel-vs-handover.md §2, 2026-10-31 범위: 피킹 전 전체 취소). 시작은
 * claim-service 의 CancellationRequested(order.claim.v1) 또는 {@code POST
 * /internal/cancellation-sagas} — 둘 다 stopRequestId 하나에 사가 하나.
 *
 * <ol>
 *   <li>STOP_WORK: mfc-fulfillment 주문 단위 중지. 전부 STOPPED 일 때만 다음으로. 판정 대기·인계 확인 중이면
 *       WAITING_RESULT(재조회), 인계됨이면 ESCALATED(출고 전 취소 불가 — 회수 S6 는 아직 없다).
 *   <li>RELEASE_RESOURCES: 주문을 확정한 S1 사가의 재고 예약·작업 여력·슬롯을 취소 사유로 해제하고 원예약 조회로 RELEASED 를 확인한다.
 *   <li>APPLY_ORDER_CANCEL: order-service 수량 변경(CANCEL_BEFORE_SHIP) → 주문 CANCELLED·OrderCancelled.
 *       환불은 claim-service 가 그 사건으로 실행한다.
 * </ol>
 *
 * 단계 명령 키는 {@code sagaId:단계} — 실행자가 죽거나 응답이 불명이어도 같은 키 재전송으로 정본에 한 번만 반영된다. 업무 거절은 자동으로 되돌리지 않고
 * ESCALATED 로 멈춘다(강제 취소·강제 해제 금지, 운영자 개입 BO-12).
 */
@Service
public class CancellationSagaService {

  private static final Logger log = LoggerFactory.getLogger(CancellationSagaService.class);

  /** 시작 입력(계약 StartCancellationSagaRequest·사건 CancellationRequested 에서 변환). */
  public record Start(
      String claimId,
      String stopRequestId,
      String orderId,
      String scope,
      @Nullable String reason,
      List<Map<String, Object>> lines) {}

  private final CancellationStore store;
  private final SagaStore checkouts;
  private final com.pmart.order.orchestration.infrastructure.TimeChangeStore timeChanges;
  private final Participants participants;
  private final RequestIdempotency idempotency;
  private final TransactionTemplate tx;
  private final ExecutorService executor;
  private final OrchestrationProperties props;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final ObjectProvider<Tracer> tracer;

  public CancellationSagaService(
      CancellationStore store,
      SagaStore checkouts,
      com.pmart.order.orchestration.infrastructure.TimeChangeStore timeChanges,
      Participants participants,
      RequestIdempotency idempotency,
      TransactionTemplate tx,
      ExecutorService sagaExecutor,
      OrchestrationProperties props,
      ObjectMapper mapper,
      Clock clock,
      ObjectProvider<Tracer> tracer) {
    this.tracer = tracer;
    this.store = store;
    this.checkouts = checkouts;
    this.timeChanges = timeChanges;
    this.participants = participants;
    this.idempotency = idempotency;
    this.tx = tx;
    this.executor = sagaExecutor;
    this.props = props;
    this.mapper = mapper;
    this.clock = clock;
  }

  // ---------- 시작 ----------

  /** HTTP 시작. 같은 키·같은 본문은 같은 사가, 같은 stopRequestId 는 기존 사가. */
  public String start(String idempotencyKey, String sourceSystem, String actor, Start in) {
    if (!"FULL".equals(in.scope())) {
      throw new PmartException(
          "UNSUPPORTED_ACTION",
          HttpStatus.UNPROCESSABLE_CONTENT,
          "지금은 주문 전체 취소(FULL)만 처리합니다.",
          false,
          List.of());
    }
    String sagaId =
        idempotency
            .execute(
                new RequestKey(sourceSystem, idempotencyKey, "startCancellationSaga", actor),
                in,
                String.class,
                () -> open(in))
            .value();
    kick(sagaId);
    return sagaId;
  }

  /** CancellationRequested(order.claim.v1) — inbox 트랜잭션 안. 커밋 뒤 진행한다. */
  public void onClaimEvent(EventEnvelope e) {
    if (!"CancellationRequested".equals(e.eventType())) {
      return;
    }
    JsonNode p = e.payload();
    String stopRequestId = p.path("stopRequestId").asString(null);
    if (stopRequestId == null || stopRequestId.isBlank()) {
      log.warn("취소 접수 {} 에 stopRequestId 가 없다 — 사가를 시작하지 않는다", p.path("claimId").asString());
      return;
    }
    List<Map<String, Object>> lines = new java.util.ArrayList<>();
    for (JsonNode l : p.path("lines")) {
      lines.add(
          Map.of("orderLineId", l.path("orderLineId").asString(), "qty", l.path("qty").asInt()));
    }
    Start in =
        new Start(
            p.path("claimId").asString(),
            stopRequestId,
            p.path("orderId").asString(),
            p.path("scope").asString("FULL"),
            p.path("reason").asString(null),
            lines);
    String sagaId = open(in);
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            kick(sagaId);
          }
        });
  }

  /** stopRequestId 에 사가가 있으면 그것(다른 주문이면 409), 없으면 새로. 호출자 트랜잭션 안. */
  private String open(Start in) {
    Optional<CancellationRow> existing = store.byStopRequest(in.stopRequestId());
    if (existing.isPresent()) {
      if (!existing.get().orderId().equals(in.orderId())) {
        throw PmartException.idempotencyConflict(
            "같은 중지 요청 ID 로 다른 주문이 왔습니다: " + in.stopRequestId());
      }
      return existing.get().sagaId();
    }
    Instant now = clock.instant();
    String id = "SAGA-C-" + UUID.randomUUID();
    store.insert(
        new CancellationRow(
            id,
            in.claimId(),
            in.stopRequestId(),
            in.orderId(),
            in.scope(),
            in.reason(),
            mapper.valueToTree(in.lines()),
            "RUNNING",
            CancellationRules.STOP_WORK,
            0,
            null,
            null,
            null,
            null,
            null,
            now,
            now,
            null));
    log.info("취소 사가 {} 시작 — 주문 {} 클레임 {}", id, in.orderId(), in.claimId());
    return id;
  }

  void kick(String sagaId) {
    executor.execute(
        () -> {
          try {
            advance(sagaId);
          } catch (RuntimeException e) {
            log.warn("취소 사가 {} 진행 중 오류 — 재개 대기: {}", sagaId, e.toString());
          }
        });
  }

  /** 멈춘 사가 재개·판정 재조회. */
  @Scheduled(fixedDelayString = "${pmart.orchestration.sweep-interval:PT10S}")
  public void sweep() {
    // 슬롯 기록이 없어 멈춘 사가(2026-11-25 고침 전)는 다시 진행 — 운영자가 처리 완료로 표시한 것은 그대로
    for (String sagaId : store.reopenSlotHoldMissing()) {
      log.info("취소 사가 {} 슬롯 기록 없음으로 멈췄던 것을 다시 진행", sagaId);
    }
    for (String sagaId : store.due(clock.instant().minus(props.stuckAfter()))) {
      try {
        advance(sagaId);
      } catch (RuntimeException e) {
        log.warn("취소 사가 {} 재개 실패: {}", sagaId, e.toString());
      }
    }
  }

  // ---------- 진행 ----------

  public void advance(String sagaId) {
    tx.executeWithoutResult(
        status -> {
          CancellationRow s = store.lock(sagaId).orElse(null);
          if (s == null) {
            return;
          }
          // S3 의 correlationId = claimId(2026-11-04 결정) — 취소 요청부터 환불 종결까지 claim 이 처음과 끝이다
          try (CorrelationScope c = CorrelationScope.open(tracer.getIfAvailable(), s.claimId())) {
            switch (s.state()) {
              case "RUNNING" -> run(s);
              case "WAITING_RESULT" -> recheckStop(s);
              default -> {
                // 종료·상신
              }
            }
          }
        });
  }

  private void run(CancellationRow s) {
    while (s != null && "RUNNING".equals(s.state())) {
      boolean moved =
          switch (s.currentStep()) {
            case CancellationRules.STOP_WORK -> stopWork(s);
            case CancellationRules.RELEASE_RESOURCES -> releaseResources(s);
            case CancellationRules.APPLY_ORDER_CANCEL -> applyOrderCancel(s);
            default -> throw new IllegalStateException("알 수 없는 단계 " + s.currentStep());
          };
      if (!moved) {
        return;
      }
      s = store.find(s.sagaId()).orElse(null);
    }
  }

  /** a. 현장 중지. 다음 단계로 넘어가면 true. */
  private boolean stopWork(CancellationRow s) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("stopRequestId", s.stopRequestId());
    body.put("orderId", s.orderId());
    body.put("claimId", s.claimId());
    body.put("scope", s.scope());
    body.put("lines", mapper.convertValue(s.lines(), List.class));
    Result r =
        participants.stopOrderWork(
            CancellationRules.requestId(s.sagaId(), CancellationRules.STOP_WORK), body);
    Instant now = clock.instant();
    return switch (r) {
      case Ok ok -> applyStopVerdict(s, ok.body(), now);
      case Rejected rej -> {
        escalate(s, CancellationRules.STOP_WORK, rej.code(), rej.message(), now);
        yield false;
      }
      case Participants.Unknown u -> {
        // 실패로 단정하지 않는다 — 같은 키로 다시 보내 원판정을 받는다(sweep)
        store.touch(s.sagaId(), now);
        yield false;
      }
    };
  }

  /** 판정 대기 사가: 같은 중지 요청을 재조회한다. */
  private void recheckStop(CancellationRow s) {
    JsonNode result = participants.stopRequest(s.stopRequestId());
    Instant now = clock.instant();
    if (result == null) {
      store.touch(s.sagaId(), now);
      return;
    }
    if (applyStopVerdict(s, result, now)) {
      run(store.find(s.sagaId()).orElseThrow());
    }
  }

  private boolean applyStopVerdict(CancellationRow s, JsonNode result, Instant now) {
    StopVerdict v = CancellationRules.verdict(result);
    switch (v) {
      case STOPPED -> {
        store.update(
            s.sagaId(),
            "RUNNING",
            CancellationRules.RELEASE_RESOURCES,
            result,
            s.resources(),
            null,
            null,
            null,
            now,
            false);
        return true;
      }
      case WAIT -> {
        store.update(
            s.sagaId(),
            "WAITING_RESULT",
            CancellationRules.STOP_WORK,
            result,
            s.resources(),
            null,
            null,
            null,
            now,
            false);
        return false;
      }
      case HANDED_OVER -> {
        store.update(
            s.sagaId(),
            s.state(),
            s.currentStep(),
            result,
            s.resources(),
            null,
            null,
            null,
            now,
            false);
        escalate(
            store.find(s.sagaId()).orElseThrow(),
            CancellationRules.STOP_WORK,
            "HANDED_OVER",
            "인계된 포장이 있어 출고 전 취소할 수 없습니다(회수 절차 필요).",
            now);
        return false;
      }
    }
    return false;
  }

  /** c. S1 이 확정한 점유를 취소 사유로 해제. 모두 해제 확인되면 true. */
  private boolean releaseResources(CancellationRow s) {
    Instant now = clock.instant();
    ArrayNode resources = s.resources() == null ? null : (ArrayNode) s.resources().deepCopy();
    if (resources == null) {
      resources = resourcesOf(s.orderId());
      if (resources == null) {
        escalate(
            s,
            CancellationRules.RELEASE_RESOURCES,
            "RESOURCES_NOT_FOUND",
            "주문을 확정한 결제 사가를 찾지 못해 해제할 점유를 알 수 없습니다.",
            now);
        return false;
      }
    }
    boolean all = true;
    for (JsonNode n : resources) {
      ObjectNode r = (ObjectNode) n;
      String done = r.path("state").asString();
      if ("RELEASED".equals(done) || "ABSENT".equals(done)) {
        continue;
      }
      String type = r.path("resourceType").asString();
      String id = r.path("resourceId").asString();
      Result res =
          participants.releaseForCancel(
              type,
              id,
              CancellationRules.releaseRequestId(s.sagaId(), type),
              s.sagaId(),
              s.stopRequestId(),
              CancellationRules.releaseReason(type));
      String state = participants.resourceState(type, id);
      if (CancellationRules.released(state)) {
        r.put("state", "RELEASED");
        continue;
      }
      if (res instanceof Rejected gone && CancellationRules.absent(type, gone.code())) {
        // 정본에 점유 기록이 없다 — 차지한 정원도 없어 풀 것이 없다(2026-11-25, 11-10 슬롯 기록 유실)
        r.put("state", "ABSENT");
        log.warn("취소 사가 {} {} {} 정본에 없음 — 풀 것 없이 다음으로", s.sagaId(), type, id);
        continue;
      }
      all = false;
      if (res instanceof Rejected rej) {
        r.put("state", "RELEASE_FAILED");
        store.update(
            s.sagaId(),
            s.state(),
            s.currentStep(),
            s.stopResult(),
            resources,
            null,
            null,
            null,
            now,
            false);
        escalate(
            store.find(s.sagaId()).orElseThrow(),
            CancellationRules.RELEASE_RESOURCES,
            rej.code(),
            type + " " + id + ": " + rej.message(),
            now);
        return false;
      }
      r.put("state", "RELEASE_UNKNOWN");
      log.warn("취소 사가 {} {} {} 해제 미확인({}) — 재시도 대기", s.sagaId(), type, id, state);
    }
    store.update(
        s.sagaId(),
        "RUNNING",
        all ? CancellationRules.APPLY_ORDER_CANCEL : CancellationRules.RELEASE_RESOURCES,
        s.stopResult(),
        resources,
        null,
        null,
        null,
        clock.instant(),
        false);
    return all;
  }

  /** 주문을 확정한 S1 사가의 점유(재고·여력·슬롯). 사가가 없으면 null. */
  private @Nullable ArrayNode resourcesOf(String orderId) {
    String checkoutSagaId = checkouts.completedSagaIdByOrder(orderId).orElse(null);
    if (checkoutSagaId == null) {
      return null;
    }
    List<CompensationRow> rows = checkouts.compensations(checkoutSagaId);
    // S4(2026-11-16): 시간 변경이 확정된 주문은 슬롯·작업 여력이 그 변경의 새 점유다(S1 점유는 이미 TIME_CHANGED·RESCHEDULED 로 해제)
    var changed = timeChanges.lastConfirmed(orderId);
    ArrayNode out = mapper.createArrayNode();
    for (String type : CancellationRules.RELEASABLE) {
      if (changed.isPresent() && ("SLOT_HOLD".equals(type) || "CAPACITY_HOLD".equals(type))) {
        ObjectNode n = out.addObject();
        n.put("resourceType", type);
        n.put(
            "resourceId",
            "SLOT_HOLD".equals(type)
                ? changed.get().newSlotHoldId()
                : changed.get().newCapacityHoldId());
        n.put("state", "PENDING");
        continue;
      }
      for (CompensationRow c : rows) {
        if (c.resourceType().equals(type)) {
          ObjectNode n = out.addObject();
          n.put("resourceType", type);
          n.put("resourceId", c.resourceId());
          n.put("state", "PENDING");
        }
      }
    }
    return out;
  }

  /** d. 주문 수량 반영(출고 전 취소). 성공이면 사가 완료. */
  private boolean applyOrderCancel(CancellationRow s) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("reason", "CANCEL_BEFORE_SHIP");
    if (s.reason() != null) {
      body.put("cancelReason", s.reason()); // 클레임 사유(2026-11-19 CL-5) — 주문 취소 사건으로 알림이 사유별 문구를 고른다
    }
    body.put("sourceRef", s.stopRequestId());
    body.put("claimId", s.claimId());
    body.put("changes", mapper.convertValue(s.lines(), List.class));
    Result r =
        participants.applyOrderCancel(
            s.orderId(),
            CancellationRules.requestId(s.sagaId(), CancellationRules.APPLY_ORDER_CANCEL),
            body);
    Instant now = clock.instant();
    switch (r) {
      case Ok ok -> {
        store.update(
            s.sagaId(),
            "COMPLETED",
            CancellationRules.APPLY_ORDER_CANCEL,
            s.stopResult(),
            s.resources(),
            null,
            null,
            null,
            now,
            true);
        log.info("취소 사가 {} 완료 — 주문 {} 출고 전 취소 반영", s.sagaId(), s.orderId());
      }
      case Rejected rej ->
          escalate(s, CancellationRules.APPLY_ORDER_CANCEL, rej.code(), rej.message(), now);
      case Participants.Unknown u -> store.touch(s.sagaId(), now);
    }
    return false;
  }

  /** 자동 진행을 멈추고 운영자 개입을 기다린다(강제 취소·강제 해제 금지). */
  private void escalate(CancellationRow s, String step, String code, String message, Instant now) {
    store.update(
        s.sagaId(),
        "ESCALATED",
        step,
        s.stopResult(),
        s.resources(),
        step,
        code,
        message,
        now,
        false);
    log.warn("취소 사가 {} 상신 — {} {}: {}", s.sagaId(), step, code, message);
  }

  // ---------- 조회 ----------

  public Optional<CancellationRow> find(String sagaId) {
    return store.find(sagaId);
  }

  public Optional<CancellationRow> byStopRequest(String stopRequestId) {
    return store.byStopRequest(Objects.requireNonNull(stopRequestId));
  }
}
