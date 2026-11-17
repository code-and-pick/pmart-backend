package com.pmart.mfc.fulfillment.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.fulfillment.domain.CancelReturnRules;
import com.pmart.mfc.fulfillment.domain.TaskRules;
import com.pmart.mfc.fulfillment.domain.TaskRules.ActiveAssignment;
import com.pmart.mfc.fulfillment.infrastructure.CapacityClient;
import com.pmart.mfc.fulfillment.infrastructure.InventoryClient;
import com.pmart.mfc.fulfillment.infrastructure.InventoryClient.PlanPart;
import com.pmart.mfc.fulfillment.infrastructure.TaskStore;
import com.pmart.mfc.fulfillment.infrastructure.TaskStore.ExecutionRow;
import com.pmart.mfc.fulfillment.infrastructure.TaskStore.LineView;
import com.pmart.mfc.fulfillment.infrastructure.TaskStore.StartRow;
import com.pmart.mfc.fulfillment.infrastructure.TaskStore.TaskInfo;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 점원 단말 작업(슬라이스 6b, WK-03·WK-05·IV-07). 작업자 = 그 센터 운영자 + 역할 {@code MFC_WORKER}(작업자 로그인 BR-02 결정 전
 * 잠정).
 *
 * <ul>
 *   <li>시작: mfc-capacity 배정 정본을 동기 조회(트랜잭션 밖, 실패면 503·기록 없음) → 작업 행 잠금 → 판정 기록(STARTED/REJECTED).
 *       같은 키는 기록된 판정. 그 주문의 첫 PICK 시작이면 PreparationStarted(D6-1).
 *   <li>피킹 실적: 접수(RECORDED) → 트랜잭션 밖에서 mfc-inventory PICK(같은 실행 ID 는 한 번만 반영) → 반영되면 완료량, 거절이면
 *       REJECTED, 결과 모름이면 UNKNOWN_RESULT(같은 키로 다시). 결품 보고는 작업 BLOCKED + LineShortageReported(재고 처리는
 *       IV-09 단계).
 *   <li>PICK 이 모두 끝나면 COMPLETED, 같은 출고의 PACK 이 READY(담당 있으면 ASSIGNED).
 * </ul>
 */
@Service
public class TaskService {

  public static final String WORKER_ROLE = "MFC_WORKER";
  public static final String TASK_NOT_FOUND = "TASK_NOT_FOUND";

  private static final Logger log = LoggerFactory.getLogger(TaskService.class);
  private static final TypeReference<List<ExecLine>> EXEC_LINES = new TypeReference<>() {};
  private static final TypeReference<List<CancelReturnRules.Return>> RETURNS =
      new TypeReference<>() {};

  /** 실적 줄(요청·저장 모양). */
  public record Shortage(int qty, String reason, @Nullable String note) {}

  /** resultQuality 는 RETURN 만(복귀 중 이상품 QUARANTINED, 기본 NORMAL). */
  public record ExecLine(
      String orderLineId,
      String sku,
      @Nullable String lotId,
      int pickedQty,
      @Nullable Shortage shortage,
      @Nullable String resultQuality) {

    public ExecLine(
        String orderLineId,
        String sku,
        @Nullable String lotId,
        int pickedQty,
        @Nullable Shortage shortage) {
      this(orderLineId, sku, lotId, pickedQty, shortage, null);
    }
  }

  public record ExecCommand(
      String executionType,
      String workerId,
      @Nullable Integer assignmentVersion,
      @Nullable String fromLocation,
      @Nullable String toLocation,
      List<ExecLine> lines) {}

  /** 실적 결과(상태 APPLIED / REJECTED / UNKNOWN_RESULT / RECORDED). */
  public record ExecResult(ExecutionRow execution, int taskVersion) {}

  /**
   * 지시 한 LOT. allocated = 위치 → 이 주문 몫으로 배정된 남은 양(inventory 위치 배정, 배정된 위치가 locations 앞). lotNo = GS1
   * (10) LOT 번호(0.11.0, IV-4 — PICK 만, inventory 피킹 계획 사본).
   */
  public record Pick(
      String lotId,
      @Nullable String expiresOn,
      int remainingQty,
      List<Map.Entry<String, Integer>> locations,
      Map<String, Integer> allocated,
      List<String> suggestedTo,
      @Nullable String lotNo) {
    public Pick(
        String lotId,
        @Nullable String expiresOn,
        int remainingQty,
        List<Map.Entry<String, Integer>> locations,
        Map<String, Integer> allocated,
        List<String> suggestedTo) {
      this(lotId, expiresOn, remainingQty, locations, allocated, suggestedTo, null);
    }

    public Pick(
        String lotId,
        @Nullable String expiresOn,
        int remainingQty,
        List<Map.Entry<String, Integer>> locations,
        Map<String, Integer> allocated) {
      this(lotId, expiresOn, remainingQty, locations, allocated, List.of());
    }

    public Pick(
        String lotId,
        @Nullable String expiresOn,
        int remainingQty,
        List<Map.Entry<String, Integer>> locations) {
      this(lotId, expiresOn, remainingQty, locations, Map.of());
    }
  }

  /** 상세 화면. picksByLine 은 PICK 만, planFresh = 재고 지시 조회 성공. */
  public record Detail(
      TaskInfo task,
      List<LineView> lines,
      Map<String, List<Pick>> picksByLine,
      boolean planFresh,
      List<ExecutionRow> pending,
      List<String> packingLocations,
      Map<String, Integer> visitSeq,
      @Nullable String routeMethod,
      Map<String, List<String>> barcodesBySku) {

    public Detail(
        TaskInfo task,
        List<LineView> lines,
        Map<String, List<Pick>> picksByLine,
        boolean planFresh,
        List<ExecutionRow> pending,
        List<String> packingLocations) {
      this(
          task, lines, picksByLine, planFresh, pending, packingLocations, Map.of(), null, Map.of());
    }
  }

  /** 조각을 집는 칸 — 이 주문 몫으로 배정된 칸 우선, 없으면 첫 칸(capacity 다음 작업 상세와 같은 기준). */
  public static @Nullable String fromLocation(Pick p) {
    for (var e : p.locations()) {
      if (p.allocated().getOrDefault(e.getKey(), 0) > 0) {
        return e.getKey();
      }
    }
    return p.locations().isEmpty() ? null : p.locations().get(0).getKey();
  }

  private final TaskStore store;
  private final com.pmart.mfc.fulfillment.infrastructure.WorkOrderStore works;
  private final com.pmart.common.messaging.RequestIdempotency idempotency;
  private final CapacityClient capacity;
  private final InventoryClient inventory;
  private final FulfillmentEvents events;
  private final ObjectMapper mapper;
  private final TransactionTemplate tx;
  private final Clock clock;

  public TaskService(
      TaskStore store,
      com.pmart.mfc.fulfillment.infrastructure.WorkOrderStore works,
      com.pmart.common.messaging.RequestIdempotency idempotency,
      CapacityClient capacity,
      InventoryClient inventory,
      FulfillmentEvents events,
      ObjectMapper mapper,
      PlatformTransactionManager txManager,
      Clock clock) {
    this.store = store;
    this.works = works;
    this.idempotency = idempotency;
    this.capacity = capacity;
    this.inventory = inventory;
    this.events = events;
    this.mapper = mapper;
    this.tx = new TransactionTemplate(txManager);
    this.clock = clock;
  }

  // ---------- 조회 ----------

  public List<TaskInfo> list(
      String mfcId,
      @Nullable String assignee,
      @Nullable String type,
      @Nullable List<String> states,
      int size,
      @Nullable String cursor) {
    String worker = null;
    if ("me".equals(assignee)) {
      worker = requireWorker(mfcId);
    } else {
      requireReader(mfcId);
    }
    List<String> s =
        states == null || states.isEmpty() ? List.of("ASSIGNED", "IN_PROGRESS") : states;
    return store.list(mfcId, worker, type, s, cursor, Math.max(1, Math.min(size, 200)));
  }

  /** 목록 표시용 줄(0.4.9). */
  public Map<String, List<LineView>> linesOf(List<String> taskIds) {
    return store.linesOf(taskIds);
  }

  public Detail detail(String taskId) {
    TaskInfo t = task(taskId);
    requireReader(t.mfcId());
    List<LineView> lines = store.lines(taskId);
    Map<String, List<Pick>> picks = new LinkedHashMap<>();
    boolean fresh = true;
    if ("PICK".equals(t.taskType())) {
      try {
        for (PlanPart p : inventory.pickPlan(t.reservationId())) {
          int remaining = p.qty() - p.pickedQty();
          if (p.orderLineId() == null || remaining <= 0 || !"CONFIRMED".equals(p.state())) {
            continue;
          }
          picks
              .computeIfAbsent(p.orderLineId(), k -> new ArrayList<>())
              .add(
                  new Pick(
                      p.lotId(),
                      p.expiryDate(),
                      remaining,
                      p.locations(),
                      p.allocated(),
                      List.of(),
                      p.lotNo()));
        }
      } catch (PmartException | org.springframework.web.client.RestClientException e) {
        fresh = false; // 지시는 참고 — 실적 판정은 재고가 한다
        log.warn("작업 {} 피킹 지시 조회 실패: {}", taskId, e.getMessage());
      }
    } else if ("RETURN".equals(t.taskType())) {
      picks = returnPicks(taskId);
    }
    Map<String, Integer> seq = Map.of();
    String method = null;
    if ("PICK".equals(t.taskType()) && !picks.isEmpty()) {
      // 방문 순서(0.4.10): 시작 때 capacity 가 정한 순서, 없으면 칸 코드 순. 줄·조각을 그 순서로 늘어놓는다
      TaskStore.Route route = store.route(taskId);
      seq = new java.util.HashMap<>(route.seq());
      method = route.method();
      java.util.TreeSet<String> unknown = new java.util.TreeSet<>();
      for (List<Pick> ps : picks.values()) {
        for (Pick p : ps) {
          String c = fromLocation(p);
          if (c != null && !seq.containsKey(c)) {
            unknown.add(c);
          }
        }
      }
      if (!unknown.isEmpty()) {
        int next = seq.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        for (String c : unknown) {
          seq.put(c, ++next);
        }
        if (method == null) {
          method = "CODE_ORDER";
        }
      }
      Map<String, Integer> fseq = seq;
      java.util.Comparator<Pick> byVisit =
          java.util.Comparator.comparingInt(
              p -> {
                String c = fromLocation(p);
                return c == null ? Integer.MAX_VALUE : fseq.getOrDefault(c, Integer.MAX_VALUE);
              });
      Map<String, List<Pick>> sorted = new LinkedHashMap<>();
      picks.forEach(
          (line, ps) -> {
            List<Pick> copy = new ArrayList<>(ps);
            copy.sort(byVisit);
            sorted.put(line, copy);
          });
      picks = sorted;
      Map<String, List<Pick>> fp = picks;
      lines = new ArrayList<>(lines);
      lines.sort(
          java.util.Comparator.comparingInt(
              (LineView l) -> {
                List<Pick> ps = fp.getOrDefault(l.orderLineId(), List.of());
                return ps.isEmpty() ? Integer.MAX_VALUE : byVisitKey(ps.get(0), fseq);
              }));
    }
    return new Detail(
        t,
        lines,
        picks,
        fresh,
        store.openExecutions(taskId),
        "PACK".equals(t.taskType()) ? store.packingLocations(t.shipmentId()) : List.of(),
        seq,
        method,
        barcodes(taskId, lines));
  }

  /**
   * 줄 SKU 의 활성 바코드(0.7.0 — PDA 스캔 대조). 저장하지 않고 조회 때 mfc-inventory SKU 투영에서 읽는다(이미 만든 작업도 맞게). 표시용이라
   * 조회가 안 되면 빈 결과로 상세는 그대로 준다(경고 로그).
   */
  private Map<String, List<String>> barcodes(String taskId, List<LineView> lines) {
    if (lines.isEmpty()) {
      return Map.of();
    }
    try {
      return inventory.skuBarcodes(lines.stream().map(LineView::sku).toList());
    } catch (PmartException | org.springframework.web.client.RestClientException e) {
      log.warn("작업 {} 바코드 조회 실패: {}", taskId, e.getMessage());
      return Map.of();
    }
  }

  public Map<String, Object> getStart(String taskId, String requestId) {
    TaskInfo t = task(taskId);
    requireReader(t.mfcId());
    StartRow r =
        store
            .findStart(taskId, requestId)
            .orElseThrow(
                () -> PmartException.notFound("START_NOT_FOUND", "기록된 시작 판정이 없습니다(시작 안 됨 확정 아님)."));
    return startView(r);
  }

  public ExecutionRow getExecution(String taskId, String executionId) {
    TaskInfo t = task(taskId);
    requireReader(t.mfcId());
    ExecutionRow e =
        store
            .execution(executionId)
            .filter(x -> x.taskId().equals(taskId))
            .orElseThrow(() -> PmartException.notFound("EXECUTION_NOT_FOUND", "실적이 없습니다."));
    return e;
  }

  // ---------- 시작 ----------

  public StartRow start(
      String taskId,
      String requestId,
      String workerId,
      int assignmentVersion,
      @Nullable String deviceId,
      @Nullable Integer expectedVersion) {
    TaskInfo t0 = task(taskId);
    String me = requireWorker(t0.mfcId());
    if (!me.equals(workerId)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "workerId 가 로그인한 작업자와 다릅니다.");
    }
    var recorded = store.findStart(taskId, requestId);
    if (recorded.isPresent()) {
      return recorded.get();
    }
    ActiveAssignment active = capacity.active(t0.mfcId(), taskId); // 실패면 503 — 기록 없음
    StartRow started =
        Objects.requireNonNull(
            tx.execute(
                s -> {
                  var again = store.findStart(taskId, requestId);
                  if (again.isPresent()) {
                    return again.get();
                  }
                  TaskInfo t = store.lock(taskId).orElseThrow();
                  Instant now = clock.instant();
                  String reject;
                  if (expectedVersion != null && expectedVersion != t.version()) {
                    reject = TaskRules.VERSION_CONFLICT;
                  } else {
                    boolean predecessorDone =
                        !"PACK".equals(t.taskType())
                            || store
                                .sibling(t.shipmentId(), "PICK")
                                .map(p -> "COMPLETED".equals(p.state()))
                                .orElse(true);
                    reject =
                        TaskRules.startRejection(
                            t.state(), predecessorDone, active, workerId, assignmentVersion);
                  }
                  if (reject != null) {
                    StartRow r =
                        new StartRow(
                            taskId,
                            requestId,
                            "REJECTED",
                            reject,
                            workerId,
                            assignmentVersion,
                            t.version(),
                            now);
                    store.insertStart(r);
                    return r;
                  }
                  store.markStarted(taskId, requestId, workerId, deviceId, now);
                  StartRow r =
                      new StartRow(
                          taskId,
                          requestId,
                          "STARTED",
                          null,
                          workerId,
                          assignmentVersion,
                          t.version() + 1,
                          now);
                  store.insertStart(r);
                  events.taskProgressed(
                      t, "IN_PROGRESS", t.state(), t.version() + 1, workerId, null, now);
                  if ("PICK".equals(t.taskType())
                      && store.markPreparationStarted(t.fulfillmentId(), now)) {
                    events.preparationStarted(t, now);
                  }
                  log.info("작업 {} 시작 by {}", taskId, workerId);
                  return r;
                }));
    if ("STARTED".equals(started.outcome()) && "PICK".equals(t0.taskType())) {
      // 위치 배정(IV-05 위치 세분화) — 지시는 참고라 실패해도 시작은 그대로(집품 때 inventory 가 판정)
      try {
        inventory.allocateLocations(t0.reservationId(), taskId + ":" + requestId);
      } catch (PmartException | org.springframework.web.client.RestClientException e) {
        log.warn("작업 {} 위치 배정 실패(시작은 유지): {}", taskId, e.getMessage());
      }
      planRoute(t0.mfcId(), taskId, t0.reservationId());
      reportBlocked(taskId);
    }
    return started;
  }

  private static int byVisitKey(Pick p, Map<String, Integer> seq) {
    String c = fromLocation(p);
    return c == null ? Integer.MAX_VALUE : seq.getOrDefault(c, Integer.MAX_VALUE);
  }

  /**
   * 피킹 방문 순서(0.4.10, ADR-0050 B2): 위치 배정 뒤 이 작업의 칸 목록을 mfc-capacity 에 물어 저장한다. 실패해도 시작은 그대로 — 상세가 칸
   * 코드 순으로 대신한다.
   */
  void planRoute(String mfcId, String taskId, String reservationId) {
    try {
      java.util.LinkedHashSet<String> codes = new java.util.LinkedHashSet<>();
      for (PlanPart p : inventory.pickPlan(reservationId)) {
        if (p.orderLineId() == null
            || p.qty() - p.pickedQty() <= 0
            || !"CONFIRMED".equals(p.state())) {
          continue;
        }
        String c =
            fromLocation(new Pick(p.lotId(), p.expiryDate(), 0, p.locations(), p.allocated()));
        if (c != null) {
          codes.add(c);
        }
      }
      if (codes.isEmpty()) {
        return;
      }
      CapacityClient.PickRoute r = capacity.pickRoute(mfcId, new ArrayList<>(codes));
      store.saveRoute(taskId, r.ordered(), r.method(), clock.instant());
      log.info("작업 {} 방문 순서 {}칸 ({})", taskId, r.ordered().size(), r.method());
    } catch (RuntimeException e) {
      log.warn("작업 {} 방문 순서 못 받음 — 칸 코드 순으로: {}", taskId, e.getMessage());
    }
  }

  /** mfc.inventory.v1 ReservationBlocked(inbox 트랜잭션 안) — 진행 중 PICK 에 점검 표시만(외부 호출 없음). */
  public void onInventoryEvent(com.pmart.common.messaging.EventEnvelope e) {
    if (!"ReservationBlocked".equals(e.eventType())) {
      return;
    }
    String rid = e.payload().path("reservationId").asString(null);
    if (rid != null && store.markBlockedCheck(rid, clock.instant()) > 0) {
      log.info("예약 {} 보류 — 진행 중 피킹 점검 예정", rid);
    }
  }

  /** 표시된 작업의 보류 점검(#5 — 마지막 집품 뒤 생긴 보류도). 실패는 표시를 남겨 다음 주기에. */
  @org.springframework.scheduling.annotation.Scheduled(
      fixedDelayString = "${pmart.fulfillment.blocked-check-interval:PT10S}",
      initialDelayString = "PT20S")
  public void sweepBlockedChecks() {
    for (String taskId : store.blockedCheckDue(50)) {
      try {
        reportBlocked(taskId);
        store.clearBlockedCheck(taskId);
      } catch (RuntimeException ex) {
        log.warn("작업 {} 보류 점검 실패(다음 주기): {}", taskId, ex.getMessage());
      }
    }
  }

  /**
   * 예약 보류분 → 결품(2026-11-08 사용자 결정 #5 안 C): 피킹 시작 때(그리고 집품마다) 이 주문 예약에 BLOCKED 조각(격리·실사 손실로 보류되고 다시
   * 못 잡은 양)이 남아 있으면 결품 보고와 같게 작업 BLOCKED + {@code LineShortageReported}(reason BLOCKED) — 결품 사가가 고객
   * 사전 선택대로 처리한다(품절만 취소 = 부분 취소·환불, 전체 취소, 선택 없음 = 상신). 재고 쪽 반영은 이미 끝났으므로(보류) inventory 결품 보고는 부르지
   * 않는다. 작업이 IN_PROGRESS 일 때만(이미 BLOCKED 면 사가 처리 중). 피킹 시작 전에는 mfc-inventory 가 보류분을 자동으로 다시 잡는다.
   */
  private void reportBlocked(String taskId) {
    TaskInfo t0 = task(taskId);
    if (!"PICK".equals(t0.taskType()) || t0.reservationId() == null) {
      return;
    }
    List<PlanPart> plan;
    try {
      plan = inventory.pickPlan(t0.reservationId());
    } catch (PmartException | org.springframework.web.client.RestClientException e) {
      log.warn("작업 {} 보류 확인 실패(다음 집품 때 다시): {}", taskId, e.getMessage());
      return;
    }
    List<PlanPart> blocked =
        plan.stream()
            .filter(p -> "BLOCKED".equals(p.state()) && p.orderLineId() != null && p.qty() > 0)
            .toList();
    if (blocked.isEmpty()) {
      return;
    }
    tx.executeWithoutResult(
        s -> {
          TaskInfo t = store.lock(taskId).orElseThrow();
          if (!"IN_PROGRESS".equals(t.state())) {
            return;
          }
          Map<String, Integer> room = new LinkedHashMap<>();
          for (var l : store.lines(taskId)) {
            room.put(l.orderLineId(), l.remaining());
          }
          List<PlanPart> report = new ArrayList<>();
          List<Integer> qtys = new ArrayList<>();
          for (PlanPart p : blocked) {
            int left = room.getOrDefault(p.orderLineId(), 0);
            int q = Math.min(left, p.qty());
            if (q > 0) {
              room.put(p.orderLineId(), left - q);
              report.add(p);
              qtys.add(q);
            }
          }
          if (report.isEmpty()) {
            return;
          }
          Instant now = clock.instant();
          String reason =
              "예약 보류(격리·실사 손실 — 재확보 못 함): "
                  + String.join(
                      ", ",
                      java.util.stream.IntStream.range(0, report.size())
                          .mapToObj(i -> report.get(i).orderLineId() + " " + qtys.get(i) + "개")
                          .toList());
          store.setState(taskId, "BLOCKED", reason, false, now);
          events.taskProgressed(t, "BLOCKED", t.state(), t.version() + 1, t.startedBy(), null, now);
          for (int i = 0; i < report.size(); i++) {
            PlanPart p = report.get(i);
            events.lineShortage(
                t,
                p.orderLineId(),
                p.sku(),
                p.lotId(),
                new Shortage(qtys.get(i), "BLOCKED", "예약 보류분(피킹 시작 시점)"),
                now);
          }
          log.warn("작업 {} 예약 보류분 → 결품 처리로 BLOCKED ({})", taskId, reason);
        });
  }

  // ---------- 피킹 실적 ----------

  public ExecResult execute(
      String taskId, String requestId, ExecCommand cmd, @Nullable Integer expectedVersion) {
    if (!"PICK".equals(cmd.executionType()) && !"RETURN".equals(cmd.executionType())) {
      throw new PmartException(
          "UNSUPPORTED_EXECUTION",
          HttpStatus.UNPROCESSABLE_CONTENT,
          "PACK 실적은 포장 밀봉(POST /shipments/{id}/packages)으로 등록합니다.",
          false,
          List.of());
    }
    TaskInfo t0 = task(taskId);
    if (!cmd.executionType().equals(t0.taskType())) {
      throw new PmartException(
          "UNSUPPORTED_EXECUTION",
          HttpStatus.UNPROCESSABLE_CONTENT,
          cmd.executionType()
              + " 실적은 "
              + cmd.executionType()
              + " 작업에만 등록합니다(이 작업 "
              + t0.taskType()
              + ").",
          false,
          List.of());
    }
    String me = requireWorker(t0.mfcId());
    if (!me.equals(cmd.workerId())) {
      throw new PmartException(ErrorCode.FORBIDDEN, "workerId 가 로그인한 작업자와 다릅니다.");
    }
    ExecutionRow exec =
        Objects.requireNonNull(tx.execute(s -> record(taskId, requestId, cmd, expectedVersion)));
    if (!isOpen(exec)) {
      return new ExecResult(exec, task(taskId).version());
    }
    ExecResult res = post(exec, t0);
    if ("PICK".equals(t0.taskType()) && "APPLIED".equals(res.execution().state())) {
      reportBlocked(taskId); // 피킹 중에 생긴 보류도(#5)
    }
    return res;
  }

  private ExecutionRow record(
      String taskId, String requestId, ExecCommand cmd, @Nullable Integer expectedVersion) {
    var existing = store.findExecution(taskId, requestId);
    if (existing.isPresent()) {
      return existing.get(); // 같은 키 — 끝났으면 그 결과, 열려 있으면 같은 실행 ID 로 다시 보낸다
    }
    TaskInfo t = store.lock(taskId).orElseThrow();
    if (!"IN_PROGRESS".equals(t.state())) {
      throw conflict(
          TaskRules.INVALID_STATE, "작업 " + taskId + " 은 " + t.state() + " 상태입니다(시작한 작업만).");
    }
    if (!cmd.workerId().equals(t.startedBy())) {
      throw conflict(TaskRules.NOT_ASSIGNEE, "이 작업을 시작한 작업자가 아닙니다.");
    }
    if (expectedVersion != null && expectedVersion != t.version()) {
      throw conflict(TaskRules.VERSION_CONFLICT, "작업이 이미 바뀌었습니다(현재 버전 " + t.version() + ").");
    }
    if ("RETURN".equals(t.taskType())) {
      return recordReturn(t, requestId, cmd);
    }
    Map<String, Integer> remaining = new HashMap<>();
    for (LineView l : store.lines(taskId)) {
      remaining.put(l.orderLineId(), l.remaining());
    }
    for (ExecutionRow open : store.openExecutions(taskId)) {
      for (ExecLine l : lines(open)) {
        remaining.merge(l.orderLineId(), -(l.pickedQty()), Integer::sum);
      }
    }
    boolean anyPick = false;
    boolean anyShortage = cmd.lines().stream().anyMatch(l -> l.shortage() != null);
    if (anyShortage) {
      // 결품 보고는 한 줄짜리 따로(집품과 섞으면 일부만 반영되는 경우가 생긴다, ADR-0044)
      if (cmd.lines().size() != 1 || cmd.lines().get(0).pickedQty() != 0) {
        throw invalid("결품 보고는 집품과 섞지 않고 한 줄(pickedQty 0 + shortage)로 따로 보냅니다.");
      }
      ExecLine l = cmd.lines().get(0);
      if (blank(l.lotId()) || blank(cmd.fromLocation())) {
        throw invalid("결품 보고는 lotId(예약 LOT)·fromLocation(발견한 선반)이 필요합니다.");
      }
    }
    for (ExecLine l : cmd.lines()) {
      Integer left = remaining.get(l.orderLineId());
      if (left == null) {
        throw invalid("작업에 없는 주문 줄입니다: " + l.orderLineId());
      }
      int shortQty = l.shortage() == null ? 0 : l.shortage().qty();
      if (l.pickedQty() < 0 || l.pickedQty() + shortQty > left) {
        throw conflict(
            "QTY_EXCEEDS_REMAINING",
            "주문 줄 "
                + l.orderLineId()
                + ": 집품 "
                + l.pickedQty()
                + " + 결품 "
                + shortQty
                + " > 남은 "
                + left);
      }
      if (l.pickedQty() == 0 && shortQty == 0) {
        throw invalid("집품도 결품도 없는 줄입니다: " + l.orderLineId());
      }
      if (l.pickedQty() > 0 && (l.lotId() == null || l.lotId().isBlank())) {
        throw invalid("집품 줄은 lotId 가 필요합니다(예약 LOT — 다른 LOT 집품 차단, IV-07): " + l.orderLineId());
      }
      remaining.merge(l.orderLineId(), -(l.pickedQty() + shortQty), Integer::sum);
      anyPick |= l.pickedQty() > 0;
    }
    if (anyPick && (blank(cmd.fromLocation()) || blank(cmd.toLocation()))) {
      throw new PmartException(
          TO_LOCATION_REQUIRED,
          HttpStatus.BAD_REQUEST,
          "집품은 fromLocation(선반)·toLocation(장바구니 또는 포장 위치)이 필요합니다.",
          false,
          List.of());
    }
    if (anyPick) {
      // 한 출고 = 한 장바구니(P4, IV-07 "지정 토트") — 이 작업 잠금 아래(출고당 PICK 작업 하나)
      List<String> used = store.pickDestinations(t.shipmentId());
      String to = java.util.Objects.requireNonNull(cmd.toLocation());
      if (!used.isEmpty() && !used.contains(to)) {
        throw conflict(
            CART_MISMATCH, "이 출고는 " + String.join(", ", used) + " 로 집품하고 있습니다(한 출고 = 한 장바구니).");
      }
    }
    Instant now = clock.instant();
    ExecutionRow r =
        new ExecutionRow(
            "EXE-" + UUID.randomUUID(),
            taskId,
            requestId,
            cmd.workerId(),
            "PICK",
            cmd.fromLocation(),
            cmd.toLocation(),
            mapper.writeValueAsString(cmd.lines()),
            "RECORDED",
            null,
            0,
            null,
            now);
    store.insertExecution(r);
    return r;
  }

  /** 복귀 실적(IV-08): 한 포장 위치 → 한 선반. 줄마다 주문 줄·LOT·출발 위치의 남은 복귀량 이하. */
  private ExecutionRow recordReturn(TaskInfo t, String requestId, ExecCommand cmd) {
    if (blank(cmd.fromLocation()) || blank(cmd.toLocation())) {
      throw invalid("복귀는 fromLocation(포장 위치)·toLocation(선반)이 필요합니다.");
    }
    Map<String, Integer> left = returnRemaining(t.taskId(), true);
    Map<String, Integer> asked = new HashMap<>();
    for (ExecLine l : cmd.lines()) {
      if (l.shortage() != null) {
        throw invalid("복귀 실적에는 결품 보고가 없습니다.");
      }
      if (blank(l.lotId()) || l.pickedQty() <= 0) {
        throw invalid("복귀 줄은 lotId 와 옮긴 수량(pickedQty > 0)이 필요합니다: " + l.orderLineId());
      }
      if (l.resultQuality() != null
          && !"NORMAL".equals(l.resultQuality())
          && !"QUARANTINED".equals(l.resultQuality())) {
        throw invalid("resultQuality 는 NORMAL·QUARANTINED 만: " + l.resultQuality());
      }
      String k =
          CancelReturnRules.key(
              l.orderLineId(),
              l.sku(),
              Objects.requireNonNull(l.lotId()),
              Objects.requireNonNull(cmd.fromLocation()));
      int sum = asked.merge(k, l.pickedQty(), Integer::sum);
      int room = left.getOrDefault(k, 0);
      if (sum > room) {
        throw conflict(
            "QTY_EXCEEDS_REMAINING",
            "주문 줄 "
                + l.orderLineId()
                + " LOT "
                + l.lotId()
                + " @"
                + cmd.fromLocation()
                + ": 복귀 "
                + sum
                + " > 남은 "
                + room);
      }
    }
    Instant now = clock.instant();
    ExecutionRow r =
        new ExecutionRow(
            "EXE-" + UUID.randomUUID(),
            t.taskId(),
            requestId,
            cmd.workerId(),
            "RETURN",
            cmd.fromLocation(),
            cmd.toLocation(),
            mapper.writeValueAsString(cmd.lines()),
            "RECORDED",
            null,
            0,
            null,
            now);
    store.insertExecution(r);
    return r;
  }

  /** 복귀 잔량(주문 줄·SKU·LOT·출발 위치). withOpen 이면 진행 중 실적도 뺀다. */
  private Map<String, Integer> returnRemaining(String taskId, boolean withOpen) {
    List<CancelReturnRules.Return> returns = returnsOf(taskId);
    List<CancelReturnRules.Unit> moved = new ArrayList<>();
    List<ExecutionRow> done = new ArrayList<>(store.taskExecutions(taskId, "APPLIED"));
    if (withOpen) {
      done.addAll(store.openExecutions(taskId));
    }
    for (ExecutionRow e : done) {
      for (ExecLine l : lines(e)) {
        moved.add(
            new CancelReturnRules.Unit(
                l.orderLineId(),
                l.sku(),
                Objects.requireNonNull(l.lotId()),
                Objects.requireNonNull(e.fromLocation()),
                l.pickedQty()));
      }
    }
    return CancelReturnRules.remaining(returns, moved);
  }

  private List<CancelReturnRules.Return> returnsOf(String taskId) {
    String json = store.returns(taskId);
    return json == null ? List.of() : mapper.readValue(json, RETURNS);
  }

  /** RETURN 상세 지시: 주문 줄 → LOT 별 남은 복귀량·포장 위치. */
  private Map<String, List<Pick>> returnPicks(String taskId) {
    Map<String, Integer> left = returnRemaining(taskId, false);
    Map<String, Map<String, Map<String, Integer>>> byLineLot = new LinkedHashMap<>();
    Map<String, String> skuOfLine = new LinkedHashMap<>();
    String mfcId = task(taskId).mfcId();
    for (CancelReturnRules.Return r : returnsOf(taskId)) {
      skuOfLine.put(r.orderLineId(), r.sku());
      int q =
          left.getOrDefault(
              CancelReturnRules.key(r.orderLineId(), r.sku(), r.lotId(), r.locationCode()), 0);
      if (q <= 0) {
        continue;
      }
      // 같은 위치의 PICKED·PACKED 는 한 키(잔량이 이미 합)
      byLineLot
          .computeIfAbsent(r.orderLineId(), k -> new LinkedHashMap<>())
          .computeIfAbsent(r.lotId(), k -> new LinkedHashMap<>())
          .put(r.locationCode(), q);
    }
    Map<String, List<Pick>> out = new LinkedHashMap<>();
    byLineLot.forEach(
        (line, lots) ->
            lots.forEach(
                (lot, locs) ->
                    out.computeIfAbsent(line, k -> new ArrayList<>())
                        .add(
                            new Pick(
                                lot,
                                null,
                                locs.values().stream().mapToInt(Integer::intValue).sum(),
                                List.copyOf(
                                    locs.entrySet().stream()
                                        .map(e -> Map.entry(e.getKey(), e.getValue()))
                                        .toList()),
                                Map.of(),
                                suggest(
                                    mfcId,
                                    skuOfLine.get(line),
                                    lot,
                                    locs.values().stream().mapToInt(Integer::intValue).sum())))));
    return out;
  }

  /** 복귀 추천 칸(#59) — 지시는 참고라 실패하면 빈 목록(실적 판정은 inventory 진열 규칙). */
  private List<String> suggest(String mfcId, @Nullable String sku, String lotId, int qty) {
    if (sku == null || qty <= 0) {
      return List.of();
    }
    try {
      return inventory.suggestPlacement(mfcId, sku, lotId, qty);
    } catch (PmartException | org.springframework.web.client.RestClientException e) {
      log.warn("복귀 추천 칸 조회 실패 {} {}: {}", sku, lotId, e.getMessage());
      return List.of();
    }
  }

  /** 재고 반영(트랜잭션 밖) → 결과 기록. */
  private ExecResult post(ExecutionRow exec, TaskInfo t0) {
    List<ExecLine> lines = lines(exec);
    if ("RETURN".equals(exec.executionType())) {
      List<Map<String, Object>> moveLines = new ArrayList<>();
      for (ExecLine l : lines) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sku", l.sku());
        m.put("lotId", l.lotId());
        m.put("qty", l.pickedQty());
        if (l.resultQuality() != null) {
          m.put("resultQuality", l.resultQuality());
        }
        moveLines.add(m);
      }
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("executionType", "MOVE");
      body.put("mfcId", t0.mfcId());
      body.put("taskId", exec.taskId());
      body.put("executionId", exec.executionId());
      body.put("fromLocation", exec.fromLocation());
      body.put("toLocation", exec.toLocation());
      body.put("lines", moveLines);
      InventoryClient.Commit mc = inventory.commit(exec.executionId(), body);
      return Objects.requireNonNull(tx.execute(s -> apply(exec, lines, mc, 0)));
    }
    List<Map<String, Object>> invLines = new ArrayList<>();
    for (ExecLine l : lines) {
      if (l.pickedQty() > 0) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sku", l.sku());
        m.put("lotId", l.lotId());
        m.put("orderLineId", l.orderLineId());
        m.put("qty", l.pickedQty());
        invLines.add(m);
      }
    }
    InventoryClient.Commit c;
    int blocked = 0;
    if (invLines.isEmpty()) {
      // 결품 보고(한 줄): 재고가 판매 제외·예약 BLOCKED·같은 SKU 적격 LOT 재확보를 한 번에(IV-09)
      ExecLine l = lines.get(0);
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("reportId", exec.executionId());
      body.put("mfcId", t0.mfcId());
      body.put("reservationId", t0.reservationId());
      body.put("orderLineId", l.orderLineId());
      body.put("sku", l.sku());
      body.put("lotId", l.lotId());
      body.put("locationCode", exec.fromLocation());
      body.put("qty", l.shortage().qty());
      body.put("reason", l.shortage().reason());
      body.put("taskId", exec.taskId());
      InventoryClient.ShortageOutcome s = inventory.reportShortage(exec.executionId(), body);
      c = s.commit();
      blocked = s.blockedQty();
    } else {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("executionType", "PICK");
      body.put("mfcId", t0.mfcId());
      body.put("taskId", exec.taskId());
      body.put("executionId", exec.executionId());
      body.put("reservationId", t0.reservationId());
      body.put("fromLocation", exec.fromLocation());
      body.put("toLocation", exec.toLocation());
      body.put("lines", invLines);
      c = inventory.commit(exec.executionId(), body);
    }
    InventoryClient.Commit result = c;
    int blockedQty = blocked;
    return Objects.requireNonNull(tx.execute(s -> apply(exec, lines, result, blockedQty)));
  }

  /**
   * @param blockedQty 결품 보고에서 재확보하지 못한 양(이행부족 D). 0 이면 같은 SKU 적격 LOT 으로 다시 잡혀 작업은 그대로 진행한다
   */
  private ExecResult apply(
      ExecutionRow exec, List<ExecLine> lines, InventoryClient.Commit c, int blockedQty) {
    TaskInfo t = store.lock(exec.taskId()).orElseThrow();
    Instant now = clock.instant();
    switch (c.outcome()) {
      case "APPLIED" -> {
        int picked = lines.stream().mapToInt(ExecLine::pickedQty).sum();
        if (store.finishExecution(exec.executionId(), "APPLIED", c.commitId(), picked, null, now)
            == 0) {
          break; // 다른 재시도가 먼저 끝냈다
        }
        for (ExecLine l : lines) {
          if (l.pickedQty() > 0) {
            store.addCompleted(t.taskId(), l.orderLineId(), l.pickedQty());
          }
        }
        List<ExecLine> shortages =
            lines.stream().filter(l -> l.shortage() != null && blockedQty > 0).toList();
        if (lines.stream().anyMatch(l -> l.shortage() != null) && blockedQty == 0) {
          log.info(
              "작업 {} 결품 {}개 — 같은 SKU 적격 LOT 으로 재확보, 작업 계속",
              t.taskId(),
              lines.get(0).shortage().qty());
        } else if (!shortages.isEmpty()) {
          String reason =
              "결품 보고: "
                  + String.join(
                      ", ",
                      shortages.stream()
                          .map(
                              l ->
                                  l.orderLineId()
                                      + " "
                                      + blockedQty
                                      + "개 "
                                      + l.shortage().reason()
                                      + "(재확보 못 함)")
                          .toList());
          store.setState(t.taskId(), "BLOCKED", reason, false, now);
          events.taskProgressed(
              t, "BLOCKED", t.state(), t.version() + 1, exec.workerId(), null, now);
          for (ExecLine l : shortages) {
            events.lineShortage(
                t,
                l.orderLineId(),
                l.sku(),
                l.lotId(),
                new Shortage(blockedQty, l.shortage().reason(), l.shortage().note()),
                now);
          }
          log.warn("작업 {} 결품 보고 → BLOCKED ({})", t.taskId(), reason);
        } else if ("RETURN".equals(t.taskType())) {
          if (store.lines(t.taskId()).stream().allMatch(l -> l.remaining() == 0)) {
            store.setState(t.taskId(), "COMPLETED", null, false, now);
            events.taskProgressed(
                t, "COMPLETED", t.state(), t.version() + 1, exec.workerId(), picked, now);
            log.info("복귀 작업 {} 완료 — 취소 집품분 선반 복귀", t.taskId());
          }
        } else if (store.lines(t.taskId()).stream().allMatch(l -> l.remaining() == 0)) {
          store.setState(t.taskId(), "COMPLETED", null, false, now);
          events.taskProgressed(
              t, "COMPLETED", t.state(), t.version() + 1, exec.workerId(), picked, now);
          store.releasePack(t.shipmentId(), now);
          store
              .sibling(t.shipmentId(), "PACK")
              .ifPresent(
                  p ->
                      events.taskProgressed(p, p.state(), "WAITING", p.version(), null, null, now));
          log.info("작업 {} 피킹 완료 — 포장 작업 준비", t.taskId());
        }
      }
      case "REJECTED" ->
          store.finishExecution(
              exec.executionId(),
              "REJECTED",
              null,
              0,
              c.code() + (c.detail() == null ? "" : ": " + c.detail()),
              now);
      case "UNKNOWN" ->
          store.finishExecution(exec.executionId(), "UNKNOWN_RESULT", null, 0, c.detail(), now);
      default -> {
        // NOT_DELIVERED: 재고에 가지 않았다 — RECORDED 그대로, 같은 키로 다시
      }
    }
    ExecutionRow after = store.execution(exec.executionId()).orElseThrow();
    if ("NOT_DELIVERED".equals(c.outcome())) {
      throw new PmartException(
          ErrorCode.DEPENDENCY_UNAVAILABLE, "재고에 반영하지 못했습니다. 같은 Idempotency-Key 로 다시 보내 주세요.");
    }
    return new ExecResult(after, store.find(exec.taskId()).orElseThrow().version());
  }

  // ---------- 결품 해결(ADR-0045) ----------

  /**
   * 품절 상품만 취소(CANCEL_OOS_ONLY)된 결품 수량을 PICK·PACK 작업 행에서 뺀다. 결품으로 BLOCKED 였던 PICK 은 다시 진행(시작한 작업자가
   * 있으면 IN_PROGRESS, 없으면 담당 따라 READY/ASSIGNED). 남은 집품이 0 이면 PICK COMPLETED → 포장 준비. 같은 키는 재생.
   */
  public com.pmart.common.web.CommandResponse resolveShortage(
      String fulfillmentId, String requestId, String sourceSystem, String orderLineId, int qty) {
    String pickId =
        works.shipments(fulfillmentId).stream()
            .filter(s -> !"CANCELLED".equals(s.state()))
            .findFirst()
            .map(s -> s.shipmentId() + "-PICK")
            .orElseThrow(() -> PmartException.notFound("WORK_ORDER_NOT_FOUND", "작업 지시가 없습니다."));
    return idempotency
        .execute(
            new com.pmart.common.messaging.RequestIdempotency.RequestKey(
                sourceSystem, requestId, "resolveShortage", "system"),
            java.util.Map.of(
                "fulfillmentId", fulfillmentId, "orderLineId", orderLineId, "qty", qty),
            com.pmart.common.web.CommandResponse.class,
            () -> {
              TaskInfo t = store.lock(pickId).orElseThrow();
              Instant now = clock.instant();
              LineView line =
                  store.lines(pickId).stream()
                      .filter(l -> l.orderLineId().equals(orderLineId))
                      .findFirst()
                      .orElseThrow(
                          () -> PmartException.invalidState("작업에 없는 주문 줄: " + orderLineId));
              if (qty > line.remaining()) {
                throw PmartException.invalidState(
                    "주문 줄 " + orderLineId + " 남은 집품 " + line.remaining() + " < 취소 " + qty);
              }
              store.addCancelled(pickId, orderLineId, qty);
              store
                  .sibling(t.shipmentId(), "PACK")
                  .ifPresent(p -> store.addCancelled(p.taskId(), orderLineId, qty));
              boolean done = store.lines(pickId).stream().allMatch(l -> l.remaining() == 0);
              if (done) {
                store.setState(pickId, "COMPLETED", null, false, now);
                events.taskProgressed(
                    t, "COMPLETED", t.state(), t.version() + 1, t.startedBy(), null, now);
                store.releasePack(t.shipmentId(), now);
              } else if ("BLOCKED".equals(t.state())) {
                String next =
                    t.startedBy() != null
                        ? "IN_PROGRESS"
                        : t.workerId() != null ? "ASSIGNED" : "READY";
                store.setState(pickId, next, null, false, now);
                events.taskProgressed(
                    t, next, "BLOCKED", t.version() + 1, t.startedBy(), null, now);
              }
              log.info("작업 {} 줄 {} 결품 {}개 취소(품절 상품만 취소)", pickId, orderLineId, qty);
              return new com.pmart.common.web.CommandResponse(
                  com.pmart.common.web.CommandStatus.APPLIED,
                  requestId,
                  pickId,
                  qty,
                  t.version() + 1,
                  false,
                  null,
                  now);
            })
        .value();
  }

  // ---------- 배정 투영 ----------

  /**
   * AssignmentPublished(mfc.capacity.v1) — 더 새 배정 버전만 반영. READY → ASSIGNED, 진행 중에 담당이 바뀌면(WK-05)
   * ASSIGNED 로 돌리고 시작 점유를 지운다(완료량은 그대로, 후임은 잔량만). inbox 트랜잭션 안에서 호출된다.
   */
  // ---------- 작업 내놓기·주의 표시(작업자 쪽 3단계, Q-09) ----------

  /** 작업 내놓기: 담당 본인 또는 센터 관리자, 진행 중 실적·열린 포장 없을 때만, capacity 배정 해제 뒤 READY. */
  public TaskInfo release(String taskId, String workerId, @Nullable String reason) {
    TaskInfo t0 = task(taskId);
    com.pmart.common.security.PmartPrincipal p =
        com.pmart.common.security.PmartPrincipal.current()
            .filter(
                x -> x.type() == com.pmart.common.security.PmartPrincipal.PrincipalType.OPERATOR)
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "운영자만 할 수 있습니다."));
    if (!p.canAccessCenter(t0.mfcId())
        || !(p.subject().equals(workerId) || p.hasRole("MFC_SUPERVISOR"))) {
      throw new PmartException(ErrorCode.FORBIDDEN, "담당 본인 또는 그 센터 관리자만 내놓을 수 있습니다.");
    }
    if (!"ASSIGNED".equals(t0.state()) && !"IN_PROGRESS".equals(t0.state())) {
      throw PmartException.invalidState("배정·진행 중인 작업만 내놓습니다(지금 " + t0.state() + ").");
    }
    if (!workerId.equals(t0.workerId())) {
      throw new PmartException(
          "NOT_ASSIGNEE",
          HttpStatus.CONFLICT,
          "지금 담당(" + t0.workerId() + ")이 아닙니다.",
          false,
          List.of());
    }
    boolean openPack =
        "PACK".equals(t0.taskType())
            && store.packages(t0.shipmentId()).stream().anyMatch(x -> "OPEN".equals(x.state()));
    if (!store.openExecutions(taskId).isEmpty() || openPack) {
      throw new PmartException(
          "EXECUTION_IN_FLIGHT",
          HttpStatus.CONFLICT,
          "반영 중인 실적·포장이 있습니다 — 끝난 뒤 내놓습니다(잔량 확인).",
          true,
          List.of());
    }
    capacity.release(taskId, workerId, reason); // 실패면 503 — 바꾸지 않음
    return Objects.requireNonNull(
        tx.execute(
            s -> {
              TaskInfo t = store.lock(taskId).orElseThrow();
              Instant now = clock.instant();
              if (workerId.equals(t.workerId())
                  && ("ASSIGNED".equals(t.state()) || "IN_PROGRESS".equals(t.state()))) {
                store.releaseToReady(taskId, now);
                events.taskProgressed(t, "READY", t.state(), t.version() + 1, workerId, null, now);
                log.info("작업 {} 내놓기 by {}({}) — READY", taskId, p.subject(), reason);
              }
              return store.find(taskId).orElseThrow();
            }));
  }

  private FulfillmentProperties props;

  @org.springframework.beans.factory.annotation.Autowired
  void setProps(FulfillmentProperties props) {
    this.props = props;
  }

  /** 주의 표시: late = 시작 뒤 예상 2배 넘김, idle = 진행 중 30분 실적 없음(표시만). */
  public record Attention(boolean late, boolean idle, @Nullable Instant lastActivityAt) {}

  public Attention attention(TaskInfo t) {
    if (!"IN_PROGRESS".equals(t.state()) || t.startedAt() == null) {
      return new Attention(false, false, null);
    }
    Instant now = clock.instant();
    Instant last = store.lastExecutionAt(t.taskId());
    Instant activity = last == null || last.isBefore(t.startedAt()) ? t.startedAt() : last;
    java.time.Duration est = props.taskMinutes(t.taskType());
    boolean late = est != null && t.startedAt().plus(est.multipliedBy(2)).isBefore(now);
    boolean idle = activity.plus(java.time.Duration.ofMinutes(30)).isBefore(now);
    return new Attention(late, idle, activity);
  }

  public void applyAssignment(EventEnvelope e) {
    var payload = e.payload();
    Instant now = clock.instant();
    for (var a : payload.path("assignments")) {
      String taskId = a.path("taskId").asString("");
      var t = store.lock(taskId);
      if (t.isEmpty()) {
        continue; // 이 서비스의 작업이 아니다(inbound 작업 등)
      }
      String worker = a.path("workerId").asString("");
      int version = a.path("assignmentVersion").asInt(0);
      if (store.applyAssignment(taskId, worker, a.path("assignmentId").asString(null), version, now)
          == 0) {
        continue;
      }
      TaskInfo cur = t.get();
      if ("READY".equals(cur.state())) {
        store.setState(taskId, "ASSIGNED", null, false, now);
      } else if ("IN_PROGRESS".equals(cur.state()) && !worker.equals(cur.startedBy())) {
        store.setState(taskId, "ASSIGNED", null, true, now);
        log.info("작업 {} 재배정 {} → {} (진행 중 → ASSIGNED)", taskId, cur.startedBy(), worker);
      }
    }
  }

  // ---------- 내부 ----------

  private TaskInfo task(String taskId) {
    return store
        .find(taskId)
        .orElseThrow(() -> PmartException.notFound(TASK_NOT_FOUND, "작업이 없습니다: " + taskId));
  }

  private List<ExecLine> lines(ExecutionRow e) {
    return mapper.readValue(e.lines(), EXEC_LINES);
  }

  private static boolean isOpen(ExecutionRow e) {
    return "RECORDED".equals(e.state()) || "UNKNOWN_RESULT".equals(e.state());
  }

  static Map<String, Object> startView(StartRow r) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("requestId", r.requestId());
    m.put("taskId", r.taskId());
    m.put("outcome", r.outcome());
    m.put("rejectCode", r.rejectCode());
    m.put("workerId", r.workerId());
    m.put("assignmentVersion", r.assignmentVersion());
    m.put("taskVersion", r.taskVersion());
    m.put("decidedAt", r.decidedAt());
    return m;
  }

  /** 집품 도착지가 이 출고가 쓰는 장바구니와 다름(409, 2026-11-17). */
  public static final String CART_MISMATCH = "CART_MISMATCH";

  /** 집은 양이 있는데 toLocation(장바구니·포장 위치)이 없음(400, 2026-11-17). */
  public static final String TO_LOCATION_REQUIRED = "TO_LOCATION_REQUIRED";

  static PmartException conflict(String code, String message) {
    return new PmartException(code, HttpStatus.CONFLICT, message, false, List.of());
  }

  static PmartException invalid(String message) {
    return new PmartException(ErrorCode.VALIDATION_FAILED, message);
  }

  private static boolean blank(@Nullable String s) {
    return s == null || s.isBlank();
  }

  /** 그 센터의 작업자(MFC_WORKER). 반환 = 작업자 ID. */
  static String requireWorker(String mfcId) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .filter(x -> x.type() == PmartPrincipal.PrincipalType.OPERATOR)
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "작업자(운영자)만 할 수 있습니다."));
    if (!p.canAccessCenter(mfcId) || !p.hasRole(WORKER_ROLE)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "이 센터의 " + WORKER_ROLE + " 만 할 수 있습니다.");
    }
    return p.subject();
  }

  static void requireReader(String mfcId) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "토큰이 필요합니다."));
    if (p.isService()) {
      return;
    }
    if (p.type() != PmartPrincipal.PrincipalType.OPERATOR || !p.canAccessCenter(mfcId)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "이 센터에 대한 권한이 없습니다.");
    }
  }
}
