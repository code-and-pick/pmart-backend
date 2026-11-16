package com.pmart.order.orchestration.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.web.PmartException;
import com.pmart.order.orchestration.domain.ReshipmentRules;
import com.pmart.order.orchestration.domain.ReshipmentRules.Line;
import com.pmart.order.orchestration.infrastructure.Participants;
import com.pmart.order.orchestration.infrastructure.Participants.Ok;
import com.pmart.order.orchestration.infrastructure.Participants.Rejected;
import com.pmart.order.orchestration.infrastructure.Participants.Result;
import com.pmart.order.orchestration.infrastructure.ReshipmentStore;
import com.pmart.order.orchestration.infrastructure.ReshipmentStore.ReshipmentRow;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 오배송 재발송 사가(2026-11-16 — ADR-0052 결정 4·5, 사용자 "새상품 재배차 서비스 넣어" → "전부 PR로"). 오배송 확인(case
 * MisdeliveryConfirmed, platform.case.v1)마다 하나 — 같은 상품·수량을 MFC 에서 다시 확보·피킹·포장해 원주소로(고객 비용 0원), 재고가
 * 모자란 품목은 그 품목만 부분 취소·환불. 회수(DD-2)는 delivery-dispatch 가 같은 사건으로 따로 한다.
 *
 * <ol>
 *   <li>RESOLVE_ORDER: 사건에 orderId 가 없으면 dispatch 배정 조회로. 주문 원장에서 보낼 줄(원수량 − 출고 전 취소 − 이미 재발송).
 *   <li>RECORD_RESHIPMENT: order 수량 원장 RESHIPMENT(미이행·재발송 +q — 같은 수량을 재발송과 환불로 겹치지 않게).
 *   <li>RESERVE_INVENTORY: 재고 예약. 부족(INSUFFICIENT_STOCK)이면 details 의 가용·적격 수량까지 줄여 다시(키는 시도마다).
 *   <li>CONFIRM_RESERVATION → REQUEST_WORK: mfc-fulfillment 재발송 작업 지시(kind RESHIPMENT, 출고 {@code
 *       <orderId>-RS-…}) — 점원이 피킹·포장·배차 요청하면 배송은 평소대로(delivery-dispatch 가 재발송 배송으로 표시).
 *   <li>APPLY_SHORTAGE → REQUEST_REFUND: 못 보내는 줄 order SHORTAGE + claim 부분 환불.
 * </ol>
 *
 * 하위 명령 키 {@code sagaId:단계}. 업무 거절은 상신(ESCALATED — 수동), 결과 모름·미전달은 같은 키로 sweep 이 다시.
 */
@Service
public class ReshipmentSagaService {

  private static final Logger log = LoggerFactory.getLogger(ReshipmentSagaService.class);

  private final ReshipmentStore store;
  private final Participants participants;
  private final TransactionTemplate tx;
  private final ExecutorService executor;
  private final OrchestrationProperties props;
  private final ObjectMapper mapper;
  private final Clock clock;

  public ReshipmentSagaService(
      ReshipmentStore store,
      Participants participants,
      TransactionTemplate tx,
      ExecutorService sagaExecutor,
      OrchestrationProperties props,
      ObjectMapper mapper,
      Clock clock) {
    this.store = store;
    this.participants = participants;
    this.tx = tx;
    this.executor = sagaExecutor;
    this.props = props;
    this.mapper = mapper;
    this.clock = clock;
  }

  /** platform.case.v1 — MisdeliveryConfirmed 로 시작(inbox 트랜잭션 안). 다른 사건은 지나간다. 커밋 뒤 진행. */
  public void onCaseEvent(EventEnvelope e) {
    if (!"MisdeliveryConfirmed".equals(e.eventType())) {
      return;
    }
    JsonNode p = e.payload();
    Instant after = props.reshipment().startCasesConfirmedAfter();
    Instant confirmed = instant(p, "confirmedAt");
    if (after == null || (confirmed != null && confirmed.isBefore(after))) {
      return; // 전환 시각 전 사건(새 구독 그룹이 토픽 처음부터 읽을 때) — 설정이 없으면 시작하지 않는다
    }
    String caseId = p.path("caseId").asString("");
    String deliveryId = p.path("deliveryId").asString("");
    if (caseId.isBlank() || deliveryId.isBlank()) {
      log.warn("오배송 확인 사건 {} 에 caseId·deliveryId 가 없어 재발송 안 함", e.eventId());
      return;
    }
    String sagaId = "SAGA-R-" + UUID.randomUUID();
    if (!store.insert(
        sagaId,
        caseId,
        deliveryId,
        p.path("orderId").asString(null),
        confirmed,
        ReshipmentRules.RESOLVE_ORDER,
        clock.instant())) {
      return;
    }
    log.info("오배송 재발송 사가 {} 시작 — 사건 {} 배송 {}", sagaId, caseId, deliveryId);
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            kick(sagaId);
          }
        });
  }

  void kick(String sagaId) {
    executor.execute(
        () -> {
          try {
            advance(sagaId);
          } catch (RuntimeException ex) {
            log.warn("오배송 재발송 사가 {} 진행 중 오류 — 재개 대기: {}", sagaId, ex.toString());
          }
        });
  }

  @Scheduled(fixedDelayString = "${pmart.orchestration.sweep-interval:PT10S}")
  public void sweep() {
    for (String sagaId : store.due(clock.instant().minus(props.stuckAfter()))) {
      try {
        advance(sagaId);
      } catch (RuntimeException ex) {
        log.warn("오배송 재발송 사가 {} 재개 실패: {}", sagaId, ex.toString());
      }
    }
  }

  public Optional<ReshipmentRow> find(String sagaId) {
    return store.find(sagaId);
  }

  public Optional<ReshipmentRow> byCase(String caseId) {
    return store.byCase(caseId);
  }

  public List<ReshipmentRow> byOrder(String orderId) {
    return store.byOrder(orderId);
  }

  /** 행 잠금 아래에서 끝까지(또는 기다릴 지점까지). */
  public void advance(String sagaId) {
    tx.executeWithoutResult(
        status -> {
          ReshipmentRow s = store.lock(sagaId).orElse(null);
          while (s != null && "RUNNING".equals(s.state())) {
            if (!step(s)) {
              return;
            }
            s = store.find(sagaId).orElse(null);
          }
        });
  }

  /** 한 단계. 다음 단계로 넘어가면 true. */
  private boolean step(ReshipmentRow s) {
    Instant now = clock.instant();
    String step = s.currentStep();
    String key = ReshipmentRules.requestId(s.sagaId(), step, s.reserveAttempt());
    List<Line> lines = lines(s.linesJson());
    List<Line> reserved = lines(s.reservedJson());
    List<Line> shortLines = lines(s.shortJson());
    return switch (step) {
      case ReshipmentRules.RESOLVE_ORDER -> resolve(s, now);
      case ReshipmentRules.RECORD_RESHIPMENT ->
          handle(
              s,
              step,
              participants.applyOrderCancel(
                  s.orderId(),
                  key,
                  Map.of(
                      "reason", "RESHIPMENT",
                      "sourceRef", s.caseId(),
                      "changes", changes(lines))),
              now,
              ok -> store.step(s.sagaId(), next(step, true, false), now));
      case ReshipmentRules.RESERVE_INVENTORY -> reserve(s, key, lines, shortLines, now);
      case ReshipmentRules.CONFIRM_RESERVATION ->
          handle(
              s,
              step,
              participants.confirmReservation(s.reservationId(), key, s.orderId()),
              now,
              ok -> store.step(s.sagaId(), next(step, true, !shortLines.isEmpty()), now));
      case ReshipmentRules.REQUEST_WORK -> requestWork(s, key, reserved, shortLines, now);
      case ReshipmentRules.APPLY_SHORTAGE ->
          handle(
              s,
              step,
              participants.applyOrderCancel(
                  s.orderId(),
                  key,
                  Map.of(
                      "reason",
                      "SHORTAGE",
                      "sourceRef",
                      s.caseId() + ":SHORTAGE",
                      "changes",
                      changes(shortLines))),
              now,
              ok -> store.step(s.sagaId(), ReshipmentRules.REQUEST_REFUND, now));
      case ReshipmentRules.REQUEST_REFUND ->
          handle(
              s,
              step,
              participants.shortageRefund(
                  key,
                  Map.of(
                      "orderId", s.orderId(),
                      "lines", changes(shortLines),
                      "reasonDetail", "오배송 재발송 재고 부족(사건 " + s.caseId() + ")",
                      "sourceRef", s.sagaId())),
              now,
              ok -> finish(s, ok.body().path("claimId").asString(null), now));
      default -> throw new IllegalStateException("알 수 없는 단계 " + step);
    };
  }

  /** 배송 → 주문 → 보낼 줄. */
  private boolean resolve(ReshipmentRow s, Instant now) {
    String orderId = s.orderId();
    try {
      if (orderId == null || orderId.isBlank()) {
        orderId = participants.deliveryAssignment(s.deliveryId()).path("orderId").asString(null);
      }
    } catch (HttpClientErrorException.NotFound e) {
      return escalate(s, ReshipmentRules.RESOLVE_ORDER, "DELIVERY_NOT_FOUND", "배송을 찾지 못했습니다", now);
    } catch (PmartException e) {
      store.touch(s.sagaId(), now); // 조회 실패 — sweep 이 다시
      return false;
    }
    if (orderId == null || orderId.isBlank()) {
      return escalate(
          s, ReshipmentRules.RESOLVE_ORDER, "ORDER_UNKNOWN", "배송의 주문 ID 를 알 수 없습니다", now);
    }
    JsonNode o = participants.orderLedger(orderId);
    if (o == null) {
      store.touch(s.sagaId(), now);
      return false;
    }
    if ("CANCELLED".equals(o.path("status").asString(""))) {
      return escalate(s, ReshipmentRules.RESOLVE_ORDER, "ORDER_CANCELLED", "취소된 주문입니다", now);
    }
    List<Line> resend = new ArrayList<>();
    for (JsonNode l : o.path("lines")) {
      int q =
          ReshipmentRules.resendQty(
              l.path("originalQty").asInt(0),
              l.path("cancelledBeforeShipQty").asInt(0),
              l.path("reshippedQty").asInt(0));
      if (q > 0) {
        resend.add(
            new Line(
                l.path("orderLineId").asString(),
                l.path("sku").asString(),
                q,
                l.path("productName").asString(null)));
      }
    }
    if (resend.isEmpty()) {
      return escalate(s, ReshipmentRules.RESOLVE_ORDER, "NOTHING_TO_RESEND", "다시 보낼 수량이 없습니다", now);
    }
    store.resolved(
        s.sagaId(),
        orderId,
        o.path("mfcId").asString(),
        ReshipmentRules.shipmentId(orderId, s.caseId()),
        json(resend),
        ReshipmentRules.RECORD_RESHIPMENT,
        now);
    log.info("오배송 재발송 사가 {} — 주문 {} {}줄 다시 보냄", s.sagaId(), orderId, resend.size());
    return true;
  }

  /** 재고 예약 — 부족하면 가용·적격 수량까지 줄여 다음 시도(새 키). */
  private boolean reserve(
      ReshipmentRow s, String key, List<Line> lines, List<Line> shortLines, Instant now) {
    // 이미 줄인 만큼(못 보내는 줄)을 빼고 남은 줄을 예약한다
    List<Line> want = minus(lines, shortLines);
    if (want.isEmpty()) {
      store.reservation(
          s.sagaId(),
          null,
          "[]",
          json(shortLines),
          s.reserveAttempt(),
          ReshipmentRules.APPLY_SHORTAGE,
          now);
      return true;
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("orderId", s.orderId());
    body.put("mfcId", s.mfcId());
    Integer ttl = props.holdTtlMinutes();
    if (ttl != null) {
      body.put("ttlMinutes", ttl);
    }
    List<Map<String, Object>> ls = new ArrayList<>();
    for (Line l : want) {
      ls.add(Map.of("orderLineId", l.orderLineId(), "sku", l.sku(), "qty", l.qty()));
    }
    body.put("lines", ls);
    Result r = participants.reserveInventory(key, body);
    return switch (r) {
      case Ok ok -> {
        store.reservation(
            s.sagaId(),
            ok.resultId(),
            json(want),
            json(shortLines),
            s.reserveAttempt(),
            ReshipmentRules.CONFIRM_RESERVATION,
            now);
        yield true;
      }
      case Rejected rej
          when "INSUFFICIENT_STOCK".equals(rej.code())
              && s.reserveAttempt() + 1 < ReshipmentRules.MAX_RESERVE_ATTEMPTS -> {
        List<List<Line>> cut = ReshipmentRules.shrink(want, details(rej.details()));
        if (cut == null) {
          yield escalate(s, ReshipmentRules.RESERVE_INVENTORY, rej.code(), rej.message(), now);
        }
        List<Line> nowShort = ReshipmentRules.merge(shortLines, cut.get(1));
        store.reservation(
            s.sagaId(),
            null,
            "[]",
            json(nowShort),
            s.reserveAttempt() + 1,
            ReshipmentRules.RESERVE_INVENTORY,
            now);
        log.info("오배송 재발송 사가 {} 재고 부족 — {}줄 줄여 다시 예약", s.sagaId(), cut.get(1).size());
        yield true;
      }
      case Rejected rej ->
          escalate(s, ReshipmentRules.RESERVE_INVENTORY, rej.code(), rej.message(), now);
      case Participants.Unknown u -> {
        store.touch(s.sagaId(), now);
        yield false;
      }
    };
  }

  /** MFC 재발송 작업 지시 — 업무 거절도 201 본문(decision REJECTED). */
  private boolean requestWork(
      ReshipmentRow s, String key, List<Line> reserved, List<Line> shortLines, Instant now) {
    List<Map<String, Object>> ls = new ArrayList<>();
    for (Line l : reserved) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("orderLineId", l.orderLineId());
      m.put("sku", l.sku());
      m.put("qty", l.qty());
      if (l.productName() != null) {
        m.put("productName", l.productName());
      }
      ls.add(m);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("orderId", s.orderId());
    body.put("workVersion", 1);
    body.put("sagaId", s.sagaId());
    body.put("mfcId", s.mfcId());
    body.put("reservationId", s.reservationId());
    body.put("kind", "RESHIPMENT");
    body.put("reshipmentRef", s.caseId());
    body.put("shipments", List.of(Map.of("shipmentId", s.shipmentId(), "lines", ls)));
    Result r = participants.confirmWork(key, body);
    if (r instanceof Ok ok && !"ACCEPTED".equals(ok.body().path("decision").asString(""))) {
      return escalate(
          s,
          ReshipmentRules.REQUEST_WORK,
          ok.body().path("rejectReason").asString("WORK_REJECTED"),
          ok.body().path("message").asString("MFC 가 재발송 작업을 거절했습니다"),
          now);
    }
    return handle(
        s,
        ReshipmentRules.REQUEST_WORK,
        r,
        now,
        ok -> {
          String next = next(ReshipmentRules.REQUEST_WORK, true, !shortLines.isEmpty());
          store.work(
              s.sagaId(),
              ok.body().path("fulfillmentId").asString(""),
              next == null ? ReshipmentRules.REQUEST_WORK : next,
              now);
          if (next == null) {
            finish(s, null, now);
          }
        });
  }

  // ---------- 공통 ----------

  private interface OnOk {
    void accept(Ok ok);
  }

  /** Ok → 진행(true, 단 끝났으면 false), 거절 → 상신, 모름 → 같은 키로 다시. */
  private boolean handle(ReshipmentRow s, String step, Result r, Instant now, OnOk onOk) {
    return switch (r) {
      case Ok ok -> {
        onOk.accept(ok);
        yield store.find(s.sagaId()).map(x -> "RUNNING".equals(x.state())).orElse(false);
      }
      case Rejected rej -> escalate(s, step, rej.code(), rej.message(), now);
      case Participants.Unknown u -> {
        store.touch(s.sagaId(), now); // 실패로 단정하지 않는다 — 같은 키로 다시(sweep)
        yield false;
      }
    };
  }

  private static String next(String step, boolean reserved, boolean short_) {
    String n = ReshipmentRules.next(step, reserved, short_);
    return n == null ? step : n;
  }

  private void finish(ReshipmentRow s, @Nullable String claimId, Instant now) {
    store.finish(s.sagaId(), "COMPLETED", claimId, null, null, null, now);
    log.info("오배송 재발송 사가 {} 완료 — 주문 {} 클레임 {}", s.sagaId(), s.orderId(), claimId);
  }

  private boolean escalate(ReshipmentRow s, String step, String code, String message, Instant now) {
    store.finish(s.sagaId(), "ESCALATED", null, step, code, message, now);
    log.warn("오배송 재발송 사가 {} 상신 — {} {} {}", s.sagaId(), step, code, message);
    return false;
  }

  private static List<Map<String, Object>> changes(List<Line> lines) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Line l : lines) {
      out.add(Map.of("orderLineId", l.orderLineId(), "qty", l.qty()));
    }
    return out;
  }

  /** 줄에서 못 보내는 수량을 뺀다. */
  private static List<Line> minus(List<Line> lines, List<Line> cut) {
    Map<String, Integer> c = new LinkedHashMap<>();
    cut.forEach(l -> c.merge(l.orderLineId(), l.qty(), Integer::sum));
    List<Line> out = new ArrayList<>();
    for (Line l : lines) {
      int q = l.qty() - c.getOrDefault(l.orderLineId(), 0);
      if (q > 0) {
        out.add(new Line(l.orderLineId(), l.sku(), q, l.productName()));
      }
    }
    return out;
  }

  private List<Map<String, String>> details(@Nullable JsonNode d) {
    List<Map<String, String>> out = new ArrayList<>();
    if (d == null) {
      return out;
    }
    for (JsonNode n : d) {
      Map<String, String> m = new LinkedHashMap<>();
      m.put("field", n.path("field").asString(""));
      m.put("reason", n.path("reason").asString(""));
      out.add(m);
    }
    return out;
  }

  private List<Line> lines(@Nullable String json) {
    List<Line> out = new ArrayList<>();
    if (json == null || json.isBlank()) {
      return out;
    }
    for (JsonNode n : mapper.readTree(json)) {
      out.add(
          new Line(
              n.path("orderLineId").asString(),
              n.path("sku").asString(),
              n.path("qty").asInt(),
              n.path("productName").asString(null)));
    }
    return out;
  }

  private String json(List<Line> lines) {
    return mapper.writeValueAsString(lines);
  }

  private static @Nullable Instant instant(JsonNode n, String field) {
    String v = n.path(field).asString(null);
    return v == null || v.isBlank() ? null : Instant.parse(v);
  }
}
