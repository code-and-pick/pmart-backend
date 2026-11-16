package com.pmart.mfc.fulfillment.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.fulfillment.domain.WorkRules;
import com.pmart.mfc.fulfillment.domain.WorkRules.Line;
import com.pmart.mfc.fulfillment.domain.WorkRules.PickStart;
import com.pmart.mfc.fulfillment.infrastructure.InventoryClient;
import com.pmart.mfc.fulfillment.infrastructure.WorkOrderStore;
import com.pmart.mfc.fulfillment.infrastructure.WorkOrderStore.ConfigWaiting;
import com.pmart.mfc.fulfillment.infrastructure.WorkOrderStore.ShipmentLineRow;
import com.pmart.mfc.fulfillment.infrastructure.WorkOrderStore.ShipmentRow;
import com.pmart.mfc.fulfillment.infrastructure.WorkOrderStore.TaskLineRow;
import com.pmart.mfc.fulfillment.infrastructure.WorkOrderStore.TaskRow;
import com.pmart.mfc.fulfillment.infrastructure.WorkOrderStore.VersionRow;
import com.pmart.mfc.fulfillment.infrastructure.WorkOrderStore.WorkOrderRow;
import com.pmart.mfc.fulfillment.infrastructure.WorkStopStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 작업 접수 ConfirmOrder(슬라이스 6a, docs/business/flows/order-checkout-saga.md §2.0). 판정 주체는 이 서비스다.
 *
 * <ol>
 *   <li>주문 하나에 작업 지시 하나, 요청마다 작업 버전 하나. 같은 버전은 기존 결정을 돌려준다. 접수된 주문에 다른 버전, 마지막보다 작은 버전은 409.
 *   <li>이미 중지된 수량(order_work_stop)을 뺀다 — 남는 게 없으면 거절 ALREADY_STOPPED.
 *   <li>원래 재고 예약이 확정 상태로 남은 수량을 덮지 못하면 거절 INVENTORY_MISMATCH(재시도 대상, D6-5). 예약 조회가 안 되면 기록하지 않고
 *       503(같은 키 재전송).
 *   <li>접수: 출고마다 PICK(READY, 예약 주문은 슬롯 시작 − 센터 설정 N분까지 WAITING, 설정 없으면 CONFIG_REQUIRED 대기) +
 *       PACK(WAITING). 작업자 배정·DISPATCH 는 6a 밖.
 *   <li>결정과 WorkAccepted / WorkRejected(mfc.fulfillment.v1)는 한 트랜잭션.
 * </ol>
 *
 * <p>오배송 재발송(kind RESHIPMENT, 2026-11-16 — ADR-0052 결정 4): 같은 주문의 두 번째 작업 지시. reshipmentRef(오배송 확인
 * 사건) 당 하나, 자기 재고 예약으로 덮이는지 보고, 원래 주문의 작업 중지 수량은 빼지 않는다(배송된 상품을 다시 보내는 것). 사건에 purpose
 * MISDELIVERY_RESHIPMENT·caseId, 접수 사건 집계 키는 fulfillmentId.
 *
 * <p>OPERATION_RESTRICTED(센터 운영 중단)는 원천(mfc-capacity 운영 제한)이 아직 없어 이 서비스가 내지 않는다(사유 값만 계약에 있다).
 */
@Service
public class WorkOrderService {

  public static final String TOPIC = WorkStopService.TOPIC;

  private static final Logger log = LoggerFactory.getLogger(WorkOrderService.class);

  public record ShipLine(
      String orderLineId,
      String sku,
      int qty,
      @Nullable String productName,
      @Nullable Integer unitsPerSku,
      @Nullable Integer weightG) {}

  public record Ship(String shipmentId, @Nullable Integer weightG, List<ShipLine> lines) {}

  public record ConfirmCommand(
      String orderId,
      int workVersion,
      @Nullable String sagaId,
      String mfcId,
      String reservationId,
      @Nullable String deliveryMode,
      @Nullable String outOfStockPreference,
      @Nullable Instant arrivalStart,
      @Nullable Instant arrivalEnd,
      List<Ship> shipments,
      @Nullable String kind,
      @Nullable String reshipmentRef) {

    public boolean reshipment() {
      return "RESHIPMENT".equals(kind);
    }
  }

  /** 결정(계약 WorkOrderDecision). */
  public record Decision(
      @Nullable String fulfillmentId,
      String orderId,
      int workVersion,
      String decision,
      @Nullable String rejectReason,
      boolean retryable,
      @Nullable String message,
      List<String> shipmentIds,
      List<String> taskIds,
      Instant decidedAt) {}

  /** 조회(계약 WorkOrder). */
  public record View(
      WorkOrderRow order,
      @Nullable VersionRow last,
      List<ShipmentRow> shipments,
      List<TaskRow> tasks,
      List<TaskLineRow> taskLines) {}

  private final WorkOrderStore store;
  private final com.pmart.mfc.fulfillment.infrastructure.TaskStore taskStore;
  private final WorkStopStore stops;
  private final InventoryClient inventory;
  private final FulfillmentProperties props;
  private final RequestIdempotency idempotency;
  private final EventPublisher events;
  private final EventEnvelopeCodec codec;
  private final Clock clock;

  public WorkOrderService(
      WorkOrderStore store,
      com.pmart.mfc.fulfillment.infrastructure.TaskStore taskStore,
      WorkStopStore stops,
      InventoryClient inventory,
      FulfillmentProperties props,
      RequestIdempotency idempotency,
      EventPublisher events,
      EventEnvelopeCodec codec,
      Clock clock) {
    this.store = store;
    this.taskStore = taskStore;
    this.stops = stops;
    this.inventory = inventory;
    this.props = props;
    this.idempotency = idempotency;
    this.events = events;
    this.codec = codec;
    this.clock = clock;
  }

  // ---------- 접수 ----------

  public Decision confirm(String idempotencyKey, String sourceSystem, ConfirmCommand in) {
    if (in.workVersion() < 1) {
      throw new PmartException(
          com.pmart.common.web.ErrorCode.VALIDATION_FAILED, "workVersion 은 1 이상입니다.");
    }
    if (in.reshipment() && (in.reshipmentRef() == null || in.reshipmentRef().isBlank())) {
      throw new PmartException(
          com.pmart.common.web.ErrorCode.VALIDATION_FAILED,
          "재발송(RESHIPMENT)은 reshipmentRef 가 필요합니다.");
    }
    // 예약 조회는 트랜잭션 밖에서(하위 호출을 잠금 안에 두지 않는다). 이미 결정된 버전·접수된 주문이면 부르지 않는다.
    InventoryClient.Reservation reservation =
        needsEvaluation(in) ? inventory.reservation(in.reservationId()) : null;
    return idempotency
        .execute(
            new RequestKey(sourceSystem, idempotencyKey, "confirmOrder", actor()),
            in,
            Decision.class,
            () -> decide(in, reservation))
        .value();
  }

  /** 이 요청의 작업 지시 — 일반은 주문의 ORIGINAL, 재발송은 근거 사건의 것. */
  private java.util.Optional<WorkOrderRow> existing(ConfirmCommand in) {
    return in.reshipment()
        ? store.findBySource(in.reshipmentRef())
        : store.findByOrder(in.orderId());
  }

  private boolean needsEvaluation(ConfirmCommand in) {
    WorkOrderRow w = existing(in).orElse(null);
    return w == null
        || (!"ACCEPTED".equals(w.acceptance())
            && store.version(w.fulfillmentId(), in.workVersion()).isEmpty());
  }

  private Decision decide(ConfirmCommand in, InventoryClient.@Nullable Reservation reservation) {
    store.lockOrder(in.orderId());
    Instant now = clock.instant();
    WorkOrderRow w = existing(in).orElse(null);
    if (w != null && !w.orderId().equals(in.orderId())) {
      throw invalidState("재발송 근거 " + in.reshipmentRef() + " 는 다른 주문의 작업 지시입니다.");
    }
    if (w != null) {
      VersionRow same = store.version(w.fulfillmentId(), in.workVersion()).orElse(null);
      if (same != null) {
        return decision(w.orderId(), same, ids(w.fulfillmentId()));
      }
      if ("ACCEPTED".equals(w.acceptance())) {
        throw invalidState("이미 작업 버전 " + w.lastWorkVersion() + " 으로 접수된 주문입니다: " + in.orderId());
      }
      if (in.workVersion() < w.lastWorkVersion()) {
        throw invalidState(
            "작업 버전 " + in.workVersion() + " 은 마지막 버전 " + w.lastWorkVersion() + " 보다 작습니다.");
      }
    }
    if (reservation == null) {
      // 판단 직전 다른 요청이 거절을 기록한 경합 — 같은 키 재전송이면 새로 조회한다
      throw new PmartException(
          com.pmart.common.web.ErrorCode.DEPENDENCY_UNAVAILABLE,
          "재고 예약을 다시 확인해야 합니다. 같은 키로 다시 보내 주세요.");
    }
    String fulfillmentId = w == null ? "FUL-" + UUID.randomUUID() : w.fulfillmentId();

    List<Line> requested = new ArrayList<>();
    for (Ship s : in.shipments()) {
      for (ShipLine l : s.lines()) {
        requested.add(new Line(l.orderLineId(), l.sku(), l.qty()));
      }
    }
    // 재발송은 배송된 상품을 다시 보내는 것이라 원래 주문의 작업 중지 수량을 빼지 않는다
    List<Line> remaining =
        in.reshipment()
            ? WorkRules.remaining(requested, Map.of())
            : WorkRules.remaining(requested, stops.stoppedByLine(in.orderId()));

    String reject = null;
    String message = null;
    if (remaining.isEmpty()) {
      reject = "ALREADY_STOPPED";
      message = "주문 전체가 이미 중지됐습니다.";
    } else if (!WorkRules.reservationCovers(reservation.state(), reservation.parts(), remaining)) {
      reject = "INVENTORY_MISMATCH";
      message = "재고 예약이 확정 상태로 작업 수량을 덮지 못합니다(예약 상태 " + reservation.state() + ").";
    }
    String acceptance = reject == null ? "ACCEPTED" : "REJECTED";
    if (w == null) {
      store.insert(
          new WorkOrderRow(
              fulfillmentId,
              in.orderId(),
              in.mfcId(),
              in.reservationId(),
              in.deliveryMode(),
              in.arrivalStart(),
              in.arrivalEnd(),
              in.outOfStockPreference(),
              in.workVersion(),
              acceptance,
              1,
              now,
              now,
              in.reshipment() ? "RESHIPMENT" : "ORIGINAL",
              in.reshipment() ? in.reshipmentRef() : null));
    } else {
      store.decide(fulfillmentId, in.workVersion(), acceptance, now);
    }
    VersionRow v =
        new VersionRow(
            fulfillmentId,
            in.workVersion(),
            in.sagaId(),
            acceptance,
            reject,
            reject != null && WorkRules.retryable(reject),
            message,
            now);
    store.insertVersion(v);

    List<String> shipmentIds = new ArrayList<>();
    List<String> taskIds = new ArrayList<>();
    if (reject == null) {
      createWork(fulfillmentId, in, remaining, now, shipmentIds, taskIds);
    }
    int aggregateVersion = store.find(fulfillmentId).orElseThrow().version();
    Decision d = decision(in.orderId(), v, new Ids(shipmentIds, taskIds));
    publish(in, d, aggregateVersion);
    log.info(
        "주문 {} 작업 버전 {} {} {}",
        in.orderId(),
        in.workVersion(),
        acceptance,
        reject == null ? "" : reject);
    return d;
  }

  private void createWork(
      String fulfillmentId,
      ConfirmCommand in,
      List<Line> remaining,
      Instant now,
      List<String> shipmentIds,
      List<String> taskIds) {
    Map<String, Integer> left = new LinkedHashMap<>();
    remaining.forEach(l -> left.put(l.orderLineId(), l.qty()));
    Duration lead = props.pickLeadTime(in.mfcId());
    PickStart pick = WorkRules.pickStart(in.arrivalStart(), lead, now);
    for (Ship s : in.shipments()) {
      List<ShipmentLineRow> lines = new ArrayList<>();
      for (ShipLine l : s.lines()) {
        int q = left.getOrDefault(l.orderLineId(), 0);
        if (q > 0) {
          lines.add(
              new ShipmentLineRow(
                  s.shipmentId(),
                  l.orderLineId(),
                  l.sku(),
                  q,
                  l.productName(),
                  l.unitsPerSku(),
                  l.weightG()));
          left.remove(l.orderLineId());
        }
      }
      if (lines.isEmpty()) {
        continue; // 이 출고의 행이 모두 이미 중지됐다
      }
      store.insertShipment(
          new ShipmentRow(
              s.shipmentId(), fulfillmentId, in.workVersion(), "PREPARING", s.weightG(), 1),
          lines,
          now);
      shipmentIds.add(s.shipmentId());
      String pickId = s.shipmentId() + "-PICK";
      String packId = s.shipmentId() + "-PACK";
      store.insertTask(
          new TaskRow(
              pickId, s.shipmentId(), "PICK", pick.state(), pick.readyAt(), pick.holdReason(), 1),
          taskLines(pickId, lines),
          now);
      store.insertTask(
          new TaskRow(packId, s.shipmentId(), "PACK", "WAITING", null, null, 1),
          taskLines(packId, lines),
          now);
      taskIds.add(pickId);
      taskIds.add(packId);
    }
  }

  private static List<TaskLineRow> taskLines(String taskId, List<ShipmentLineRow> lines) {
    return lines.stream()
        .map(l -> new TaskLineRow(taskId, l.shipmentId(), l.orderLineId(), l.qty(), 0, 0))
        .toList();
  }

  // ---------- 조회 ----------

  public View get(String fulfillmentId) {
    return view(
        store
            .find(fulfillmentId)
            .orElseThrow(() -> PmartException.notFound("WORK_ORDER_NOT_FOUND", "작업 지시가 없습니다.")));
  }

  public View findByOrder(String orderId) {
    return view(
        store
            .findByOrder(orderId)
            .orElseThrow(() -> PmartException.notFound("WORK_ORDER_NOT_FOUND", "작업 지시가 없습니다.")));
  }

  private View view(WorkOrderRow w) {
    return new View(
        w,
        store.version(w.fulfillmentId(), w.lastWorkVersion()).orElse(null),
        store.shipments(w.fulfillmentId()),
        store.tasks(w.fulfillmentId()),
        store.taskLines(w.fulfillmentId()));
  }

  // ---------- 준비 시각 ----------

  /**
   * 준비 시각이 지난 PICK 을 READY 로, 센터 설정이 새로 들어온 CONFIG_REQUIRED 대기에는 준비 시각을 넣는다(D6-5 세부: 설정이 들어오면 다시
   * 계산). 조건부 갱신이라 여러 인스턴스가 같이 돌아도 한 번만 바뀐다.
   */
  @Scheduled(
      fixedDelayString = "${pmart.fulfillment.ready-sweep-interval:PT30S}",
      initialDelayString = "PT20S")
  public void sweepReady() {
    Instant now = clock.instant();
    for (ConfigWaiting c : store.configWaiting()) {
      Duration lead = props.pickLeadTime(c.mfcId());
      if (lead == null || c.arrivalStart() == null) {
        continue;
      }
      PickStart p = WorkRules.pickStart(c.arrivalStart(), lead, now);
      store.schedule(c.taskId(), p.state(), p.readyAt() == null ? now : p.readyAt(), now);
    }
    int n = store.promoteDue(now);
    if (n > 0) {
      log.info("PICK 준비 시각 도래 {}건 READY", n);
    }
    int a = taskStore.assignReady(now); // 담당이 먼저 정해진 작업(6b)
    if (a > 0) {
      log.info("담당이 있는 READY 작업 {}건 ASSIGNED", a);
    }
  }

  /**
   * S4 예약 시간 변경(0.6.0): 작업 지시 행을 잠근 뒤 실제 준비 시작(PICK 시작)이 없을 때만 도착 시간대·PICK 준비 시각을 바꾼다. 준비가 먼저 시작됐으면
   * 409 PREPARATION_STARTED — 먼저 확정된 쪽만 유효(OMS §4). 재고·출고·포장은 그대로.
   */
  public com.pmart.common.web.CommandResponse reschedule(
      String requestId,
      String sourceSystem,
      String fulfillmentId,
      String orderId,
      Instant start,
      Instant end) {
    if (!end.isAfter(start)) {
      throw new PmartException(
          com.pmart.common.web.ErrorCode.VALIDATION_FAILED, "도착 시간대의 끝이 시작보다 뒤여야 합니다.");
    }
    return idempotency
        .execute(
            new RequestKey(sourceSystem, requestId, "rescheduleWorkOrder", "system"),
            java.util.Map.of(
                "fulfillmentId",
                fulfillmentId,
                "orderId",
                orderId,
                "start",
                start.toString(),
                "end",
                end.toString()),
            com.pmart.common.web.CommandResponse.class,
            () -> {
              Instant now = clock.instant();
              java.util.Optional<Instant> started =
                  store
                      .lockForReschedule(fulfillmentId)
                      .orElseThrow(
                          () ->
                              PmartException.notFound(
                                  "WORK_ORDER_NOT_FOUND", "작업 지시가 없습니다: " + fulfillmentId));
              WorkOrderRow w = store.find(fulfillmentId).orElseThrow();
              if (!w.orderId().equals(orderId)) {
                throw new PmartException(
                    com.pmart.common.web.ErrorCode.VALIDATION_FAILED, "다른 주문의 작업 지시입니다.");
              }
              if (!"ACCEPTED".equals(w.acceptance())) {
                throw PmartException.invalidState("접수되지 않은 작업 지시입니다(" + w.acceptance() + ").");
              }
              if (!"SCHEDULED".equals(w.deliveryMode()) || w.arrivalStart() == null) {
                throw new PmartException(
                    "NOT_SCHEDULED",
                    org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT,
                    "예약배달 작업 지시만 시간을 바꿀 수 있습니다.",
                    false,
                    List.of());
              }
              if (started.isPresent()) {
                throw new PmartException(
                    "PREPARATION_STARTED",
                    org.springframework.http.HttpStatus.CONFLICT,
                    "상품 준비가 이미 시작되었습니다(" + started.get() + ").",
                    false,
                    List.of());
              }
              if (start.equals(w.arrivalStart()) && end.equals(w.arrivalEnd())) {
                return applied(requestId, fulfillmentId, w.version(), now);
              }
              PickStart p = WorkRules.pickStart(start, props.pickLeadTime(w.mfcId()), now);
              store.reschedule(fulfillmentId, start, end, now);
              int picks =
                  store.reschedulePicks(
                      fulfillmentId, "WAITING".equals(p.state()), p.readyAt(), p.holdReason(), now);
              if (picks == 0) {
                throw PmartException.invalidState("시작 전 PICK 작업이 없습니다(중지·취소된 작업).");
              }
              log.info(
                  "작업 지시 {} 예약 시간 변경 {} → {} (PICK {}건 {})",
                  fulfillmentId,
                  w.arrivalStart(),
                  start,
                  picks,
                  p.state());
              return applied(requestId, fulfillmentId, w.version() + 1, now);
            })
        .value();
  }

  private static com.pmart.common.web.CommandResponse applied(
      String requestId, String id, int version, Instant now) {
    return new com.pmart.common.web.CommandResponse(
        com.pmart.common.web.CommandStatus.APPLIED, requestId, id, null, version, false, null, now);
  }

  // ---------- 내부 ----------

  private record Ids(List<String> shipmentIds, List<String> taskIds) {}

  private Ids ids(String fulfillmentId) {
    return new Ids(
        store.shipments(fulfillmentId).stream().map(ShipmentRow::shipmentId).toList(),
        store.tasks(fulfillmentId).stream().map(TaskRow::taskId).toList());
  }

  private static Decision decision(String orderId, VersionRow v, Ids ids) {
    boolean accepted = "ACCEPTED".equals(v.decision());
    return new Decision(
        v.fulfillmentId(),
        orderId,
        v.workVersion(),
        v.decision(),
        v.rejectReason(),
        v.retryable(),
        v.message(),
        accepted ? ids.shipmentIds() : List.of(),
        accepted ? ids.taskIds() : List.of(),
        v.decidedAt());
  }

  private void publish(ConfirmCommand in, Decision d, int aggregateVersion) {
    boolean accepted = "ACCEPTED".equals(d.decision());
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("orderId", d.orderId());
    p.put("fulfillmentId", d.fulfillmentId());
    p.put("mfcId", in.mfcId());
    p.put("workVersion", d.workVersion());
    if (in.sagaId() != null) {
      p.put("sagaId", in.sagaId());
    }
    if (in.reshipment()) {
      p.put("purpose", "MISDELIVERY_RESHIPMENT");
      p.put("caseId", in.reshipmentRef());
    }
    if (accepted) {
      p.put("shipmentIds", d.shipmentIds());
      p.put("taskIds", d.taskIds());
      p.put("acceptedAt", d.decidedAt().toString());
    } else {
      p.put("rejectReason", d.rejectReason());
      p.put("retryable", d.retryable());
      if (d.message() != null) {
        p.put("message", d.message());
      }
      p.put("rejectedAt", d.decidedAt().toString());
    }
    // 집계 키 = orderId — WorkStopResult 와 같은 파티션(같은 주문의 접수·중지 순서 유지)
    EventEnvelope e =
        codec.create(
            events.sourceSystem(),
            accepted ? "WorkAccepted" : "WorkRejected",
            1,
            "Fulfillment",
            // 재발송은 자기 작업 지시로 센다(원래 주문 집계 버전과 겹치지 않게)
            in.reshipment() ? d.fulfillmentId() : d.orderId(),
            aggregateVersion,
            new EventEnvelope.Actor(EventEnvelope.ActorType.SYSTEM, events.sourceSystem()),
            EventEnvelope.EventSource.REAL,
            p);
    events.publish(e, TOPIC);
  }

  private static PmartException invalidState(String message) {
    return PmartException.invalidState(message);
  }

  private static String actor() {
    return PmartPrincipal.current()
        .map(PmartPrincipal::subject)
        .orElse(RequestIdempotency.ANONYMOUS);
  }
}
