package com.pmart.mfc.capacity.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.capacity.infrastructure.AssignmentStore;
import com.pmart.mfc.capacity.infrastructure.AssignmentStore.AssignmentRow;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 작업 배정(WK-02·WK-05, 슬라이스 6b). Q-08(우선순위·자동 배정) 결정 전에는 권한자 수동 지정만 — 그 센터의 {@code MFC_SUPERVISOR}.
 * 작업자는 운영자 ID(작업자 로그인 BR-02 결정 전 잠정). 작업자 자격·근무·장비 정본이 없어 강제 제약은 아직 검사하지 않는다.
 */
@Service
public class AssignmentService {

  public static final String TOPIC = "mfc.capacity.v1";
  public static final String SUPERVISOR_ROLE = "MFC_SUPERVISOR";

  /** 업무 유형 목록과 하나로(RETURN 포함 — 2026-11-08 LAN E2E 에서 빠진 것 발견). */
  private static final Set<String> TASK_TYPES =
      com.pmart.mfc.capacity.domain.WorkerRules.TASK_TYPES;

  private static final Set<String> STATES = Set.of("ACTIVE", "SUPERSEDED", "RELEASED");
  private static final Logger log = LoggerFactory.getLogger(AssignmentService.class);

  public record AssignCommand(
      String mfcId,
      String taskId,
      String taskType,
      String workerId,
      @Nullable String reason,
      @Nullable Integer expectedVersion,
      @Nullable String temperatureZone) {}

  /** created = false 면 이미 그 작업자(변화 없음). warnings = 막지 않는 경고(OFF_SHIFT). */
  public record Assigned(AssignmentRow assignment, boolean created, List<String> warnings) {}

  private final AssignmentStore store;
  private final RequestIdempotency idempotency;
  private final EventPublisher events;
  private final EventEnvelopeCodec codec;
  private final Clock clock;
  private final WorkerService workers;

  public AssignmentService(
      AssignmentStore store,
      RequestIdempotency idempotency,
      EventPublisher events,
      EventEnvelopeCodec codec,
      Clock clock,
      WorkerService workers) {
    this.store = store;
    this.idempotency = idempotency;
    this.events = events;
    this.codec = codec;
    this.clock = clock;
    this.workers = workers;
  }

  /** 다음 작업 받기 배정의 assignedBy(사람이 아님). */
  public static final String SYSTEM = "SYSTEM";

  public static final String NO_TASK_AVAILABLE = "NO_TASK_AVAILABLE";
  public static final String OFF_SHIFT_BLOCKED = "OFF_SHIFT";

  private com.pmart.mfc.capacity.infrastructure.@Nullable FulfillmentClient fulfillment;
  private com.pmart.mfc.capacity.infrastructure.@Nullable InboundClient inbound;

  @org.springframework.beans.factory.annotation.Autowired
  void setInbound(com.pmart.mfc.capacity.infrastructure.InboundClient inbound) {
    this.inbound = inbound;
  }

  /** 열린 이동 지시(배정 추천용). 이동 조회를 안 쓰면 빈 목록, 조회 실패면 null(추천은 이동 없이 — 한계로 남긴다). */
  public @Nullable List<com.pmart.mfc.capacity.infrastructure.InboundClient.MoveView> openMoves(
      String mfcId) {
    if (inbound == null || !inbound.configured()) {
      return List.of();
    }
    try {
      return inbound.openMoves(mfcId);
    } catch (RuntimeException ex) {
      log.warn("이동 지시 조회 실패 — 배정 추천에서 이동 제외: {}", ex.getMessage());
      return null;
    }
  }

  /** 정해진 순서의 앞섬(배정 추천 미배정 벌점용) — 다음 작업 받기 순서와 같다. 긴급 이동은 보충과 같다. */
  public static int priority(String taskType, boolean urgent) {
    return 3 - Math.min(3, urgent ? rank("REPLENISH") : rank(taskType));
  }

  /** 받은 작업의 상세(0.9.1) — 읽지 못하면 available=false(배정은 그대로). */
  public NextTaskDetails.Detail detailOf(String taskId, String taskType) {
    try {
      if ("MOVE".equals(taskType) || "REPLENISH".equals(taskType)) {
        if (inbound == null || !inbound.configured()) {
          return NextTaskDetails.Detail.unavailable();
        }
        return NextTaskDetails.fromMove(inbound.moveOrder(taskId));
      }
      if (fulfillment == null) {
        return NextTaskDetails.Detail.unavailable();
      }
      return NextTaskDetails.fromTask(fulfillment.taskDetail(taskId));
    } catch (RuntimeException ex) {
      log.warn("작업 {} 상세 조회 실패 — 상세 없이 응답: {}", taskId, ex.getMessage());
      return NextTaskDetails.Detail.unavailable();
    }
  }

  /** 다음 작업 후보(작업 지시·이동 지시 공통). */
  record Candidate(String taskId, String taskType, int rank, @Nullable Instant when) {}

  private org.springframework.transaction.support.@Nullable TransactionTemplate tx;

  @org.springframework.beans.factory.annotation.Autowired
  void setPullDeps(
      com.pmart.mfc.capacity.infrastructure.FulfillmentClient fulfillment,
      org.springframework.transaction.PlatformTransactionManager txm) {
    this.fulfillment = fulfillment;
    this.tx = new org.springframework.transaction.support.TransactionTemplate(txm);
  }

  /**
   * 다음 작업 받기(2026-11-08 사용자 결정 — 현장 크루는 시스템이 작업을 하나씩 배정). 작업자 본인만. 들고 있는 작업이 있으면 그 배정(created =
   * false), 없으면 업무 안의 담당 없는 READY 작업 중 가장 급한 것(PICK·PACK 약속 이른 순 → RETURN)을 SYSTEM 배정.
   */
  public Assigned pullNext(String mfcId, String workerId) {
    return pullNext(mfcId, workerId, null);
  }

  /**
   * (0.13.0, BR-52 R1) 고른 업무(workArea) 안에서만 새 작업 — 업무 묶음 ∩ 유효 업무가 비면 422 WORK_AREA_NOT_ALLOWED. 들고
   * 있는 작업은 업무와 상관없이 돌려준다(한 번에 하나).
   */
  public Assigned pullNext(String mfcId, String workerId, @Nullable String workArea) {
    requireSelf(mfcId, workerId);
    String reject = workers.rejection(mfcId, workerId, "PICK", null);
    if (com.pmart.mfc.capacity.domain.WorkerRules.WORKER_NOT_REGISTERED.equals(reject)
        || com.pmart.mfc.capacity.domain.WorkerRules.WORKER_NOT_ACTIVE.equals(reject)
        || com.pmart.mfc.capacity.domain.WorkerRules.DAILY_NOT_ENROLLED.equals(reject)) {
      throw new PmartException(
          reject,
          org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT,
          "작업을 받을 수 없는 작업자입니다: " + reject,
          false,
          List.of());
    }
    Instant now = clock.instant();
    if (workers.offShift(mfcId, workerId, now)) {
      throw new PmartException(
          OFF_SHIFT_BLOCKED,
          org.springframework.http.HttpStatus.CONFLICT,
          "등록 근무 밖에는 시스템이 작업을 주지 않습니다(센터 관리자 배정만).",
          false,
          List.of());
    }
    var tasks =
        java.util.Objects.requireNonNull(fulfillment)
            .tasks(mfcId, List.of("READY", "ASSIGNED", "IN_PROGRESS"));
    java.util.Map<String, com.pmart.mfc.capacity.infrastructure.FulfillmentClient.TaskView> open =
        new java.util.HashMap<>();
    tasks.forEach(t -> open.put(t.taskId(), t));
    // 이동·보충(mfc-inbound) — 조회가 안 되면 건너뛴다(주문 작업은 계속)
    List<com.pmart.mfc.capacity.infrastructure.InboundClient.MoveView> moves = List.of();
    if (inbound != null && inbound.configured()) {
      try {
        moves = inbound.openMoves(mfcId);
      } catch (RuntimeException ex) {
        log.warn("이동 지시 조회 실패 — 이동은 건너뜀: {}", ex.getMessage());
      }
    }
    java.util.Set<String> openMoves = new java.util.HashSet<>();
    moves.forEach(m -> openMoves.add(m.moveId()));
    // 한 번에 하나: 이 작업자의 ACTIVE 배정 중 아직 끝나지 않은 작업
    for (AssignmentRow a : store.list(mfcId, null, workerId, "ACTIVE", null)) {
      if (open.containsKey(a.taskId()) || openMoves.contains(a.taskId())) {
        return new Assigned(a, false, List.of());
      }
    }
    // 휴게 중에는 새 작업 없음(들고 있던 작업은 위에서 돌려줌)
    if (workers.onBreak(mfcId, workerId)) {
      throw new PmartException(
          "ON_BREAK",
          org.springframework.http.HttpStatus.CONFLICT,
          "휴게 중에는 새 작업을 받지 않습니다 — 휴게를 끝낸 뒤.",
          false,
          List.of());
    }
    java.util.Set<String> duty =
        com.pmart.mfc.capacity.domain.WorkAreas.narrow(
            workers.taskTypes(mfcId, workerId), workArea);
    if (workArea != null && duty.isEmpty()) {
      throw new PmartException(
          com.pmart.mfc.capacity.domain.WorkAreas.WORK_AREA_NOT_ALLOWED,
          org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT,
          "이 업무(" + workArea + ")를 할 수 있는 자격·업무가 없습니다.",
          false,
          List.of());
    }
    List<Candidate> candidates = new java.util.ArrayList<>();
    tasks.stream()
        .filter(t -> "READY".equals(t.state()) && t.workerId() == null)
        .filter(t -> duty.contains(t.taskType()))
        .forEach(
            t ->
                candidates.add(
                    new Candidate(
                        t.taskId(), t.taskType(), rank(t.taskType()), t.promisedReadyAt())));
    moves.stream()
        .filter(m -> "ACTIVE".equals(m.state()) && m.pickedQty() == 0 && m.assigneeId() == null)
        .filter(m -> duty.contains(m.taskType()))
        .forEach(
            m ->
                candidates.add(
                    new Candidate(
                        m.moveId(),
                        m.taskType(),
                        m.urgent() ? rank("REPLENISH") : rank(m.taskType()),
                        m.createdAt())));
    candidates.sort(
        java.util.Comparator.comparingInt(Candidate::rank)
            .thenComparing(
                Candidate::when,
                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
            .thenComparing(Candidate::taskId));
    for (var t : candidates) {
      AssignmentRow made;
      try {
        made = tryAssignNext(mfcId, workerId, t);
      } catch (org.springframework.dao.DuplicateKeyException race) {
        // 활성 배정이 없을 때는 FOR UPDATE 로 잠글 행이 없다 — 동시에 같은 작업을 잡으면 부분 유일 인덱스가 한쪽을 막는다. 진 쪽은 다음 후보로
        log.info("다음 작업 {} 경합 — 다른 배정이 먼저, 다음 후보로", t.taskId());
        continue;
      }
      if (made != null) {
        log.info("다음 작업 {} {} → {} (시스템 배정)", made.taskType(), made.taskId(), workerId);
        return new Assigned(made, true, List.of());
      }
    }
    throw new PmartException(
        NO_TASK_AVAILABLE,
        org.springframework.http.HttpStatus.CONFLICT,
        "지금 받을 수 있는 작업이 없습니다.",
        true,
        List.of());
  }

  /** 후보 하나를 한 트랜잭션에서 배정. 이미 활성 배정이 있으면 null. 동시 삽입 경합은 DuplicateKeyException(호출자가 다음 후보로). */
  private @Nullable AssignmentRow tryAssignNext(String mfcId, String workerId, Candidate t) {
    return java.util.Objects.requireNonNull(tx)
        .execute(
            s -> {
              if (store.lockActive(t.taskId()).isPresent()) {
                return null; // 다른 작업자가 먼저(또는 센터 관리자 배정)
              }
              Instant at = clock.instant();
              AssignmentRow row =
                  new AssignmentRow(
                      "ASG-" + UUID.randomUUID(),
                      mfcId,
                      t.taskId(),
                      t.taskType(),
                      workerId,
                      store.maxVersion(t.taskId()) + 1,
                      "ACTIVE",
                      null,
                      SYSTEM,
                      "NEXT_TASK",
                      at,
                      null);
              store.insert(row);
              publish(row, SYSTEM);
              return row;
            });
  }

  /**
   * 작업 내놓기(Q-09, fulfillment 가 잔량 확인 뒤 부름): 그 작업의 ACTIVE 배정을 RELEASED. 담당이 아니면 409 NOT_ASSIGNEE, 활성
   * 배정이 없으면 false. 사건 없음(작업 상태 정본은 fulfillment).
   */
  @Transactional
  public boolean releaseTask(String taskId, String workerId) {
    var active = store.lockActive(taskId);
    if (active.isEmpty()) {
      return false;
    }
    if (!active.get().workerId().equals(workerId)) {
      throw new PmartException(
          "NOT_ASSIGNEE",
          org.springframework.http.HttpStatus.CONFLICT,
          "지금 담당(" + active.get().workerId() + ")이 아닙니다.",
          false,
          List.of());
    }
    store.release(active.get().assignmentId(), clock.instant());
    log.info("작업 {} 배정 해제(작업 내놓기) — {}", taskId, workerId);
    return true;
  }

  /** 다음 작업 우선순위: 주문 작업 → 복귀 → 그 밖(이동·보충은 다음 단계). */
  static int rank(String taskType) {
    return switch (taskType) {
      case "PICK", "PACK" -> 0;
      case "REPLENISH" -> 1;
      case "MOVE" -> 2;
      case "RETURN" -> 3;
      default -> 4;
    };
  }

  private static void requireSelf(String mfcId, String workerId) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .filter(x -> x.type() == PmartPrincipal.PrincipalType.OPERATOR)
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "작업자(운영자)만 할 수 있습니다."));
    if (!p.canAccessCenter(mfcId) || !p.subject().equals(workerId)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "작업자 본인만 다음 작업을 받습니다.");
    }
  }

  public Assigned assign(String requestId, String sourceSystem, AssignCommand cmd) {
    String supervisor = requireSupervisor(cmd.mfcId());
    if (!TASK_TYPES.contains(cmd.taskType())) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "알 수 없는 작업 유형: " + cmd.taskType());
    }
    if (cmd.workerId().isBlank()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "workerId 가 비었습니다.");
    }
    // WK-02 강제 제약: 등록·ACTIVE·업무(작업 유형)·온도대 자격. 근무 밖은 경고만(Q-09 미결)
    String reject =
        workers.rejection(cmd.mfcId(), cmd.workerId(), cmd.taskType(), cmd.temperatureZone());
    if (reject != null) {
      throw new PmartException(
          reject,
          org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT,
          "작업자 " + cmd.workerId() + " 에게 이 작업을 배정할 수 없습니다: " + reject,
          false,
          List.of());
    }
    List<String> warnings;
    try {
      warnings =
          workers.offShift(cmd.mfcId(), cmd.workerId(), clock.instant())
              ? List.of(com.pmart.mfc.capacity.domain.WorkerRules.OFF_SHIFT)
              : List.of();
    } catch (PmartException ex) {
      // 수동 배정은 근무 밖이어도 막지 않는다(경고만) — 직원 시간표를 못 읽으면 경고 없이(Q-09)
      log.warn("근무 판단 못 함({}) — 경고 없이 배정: {}", cmd.workerId(), ex.getMessage());
      warnings = List.of();
    }
    try {
      return assignOnce(requestId, sourceSystem, cmd, supervisor, warnings);
    } catch (org.springframework.dao.DuplicateKeyException race) {
      // 같은 작업에 동시에 다른 배정(다음 작업 받기·다른 센터 관리자)이 먼저 들어갔다 — 500 이 아니라 버전 충돌로
      throw PmartException.versionConflict("같은 작업에 동시에 다른 배정이 들어갔습니다 — 다시 조회한 뒤 배정하세요.");
    }
  }

  private Assigned assignOnce(
      String requestId,
      String sourceSystem,
      AssignCommand cmd,
      String supervisor,
      List<String> warnings) {
    return idempotency
        .execute(
            new RequestKey(sourceSystem, requestId, "assignTask", supervisor),
            cmd,
            Assigned.class,
            () -> {
              Optional<AssignmentRow> active = store.lockActive(cmd.taskId());
              int current = active.map(AssignmentRow::assignmentVersion).orElse(0);
              if (cmd.expectedVersion() != null && cmd.expectedVersion() != current) {
                throw PmartException.versionConflict(
                    "배정이 이미 바뀌었습니다(기대 " + cmd.expectedVersion() + ", 현재 " + current + ").");
              }
              if (active.isPresent()) {
                AssignmentRow a = active.get();
                if (!a.mfcId().equals(cmd.mfcId())) {
                  throw new PmartException(ErrorCode.FORBIDDEN, "다른 센터의 작업입니다.");
                }
                if (a.workerId().equals(cmd.workerId())) {
                  return new Assigned(a, false, warnings);
                }
              }
              Instant at = clock.instant();
              active.ifPresent(a -> store.supersede(a.assignmentId(), at));
              AssignmentRow row =
                  new AssignmentRow(
                      "ASG-" + UUID.randomUUID(),
                      cmd.mfcId(),
                      cmd.taskId(),
                      cmd.taskType(),
                      cmd.workerId(),
                      store.maxVersion(cmd.taskId()) + 1,
                      "ACTIVE",
                      null,
                      supervisor,
                      cmd.reason(),
                      at,
                      cmd.temperatureZone());
              store.insert(row);
              publish(row, supervisor);
              log.info(
                  "task {} assigned to {} (v{}) at {} by {}",
                  row.taskId(),
                  row.workerId(),
                  row.assignmentVersion(),
                  row.mfcId(),
                  supervisor);
              return new Assigned(row, true, warnings);
            })
        .value();
  }

  /**
   * 배정 추천 확정(WK-10)의 한 항목. 호출자가 트랜잭션 안에서 그 작업에 활성 배정이 없음을 잠가 확인한 뒤 부른다. 사건 집계 키는 수동과 같은 {@code
   * MANUAL:<taskId>}(작업마다 assignmentVersion 순서를 한 파티션에서 지키려고), payload 에 planId·source PLAN.
   */
  AssignmentRow insertFromPlan(
      String mfcId,
      String taskId,
      String taskType,
      String workerId,
      String planId,
      String by,
      Instant at) {
    AssignmentRow row =
        new AssignmentRow(
            "ASG-" + UUID.randomUUID(),
            mfcId,
            taskId,
            taskType,
            workerId,
            store.maxVersion(taskId) + 1,
            "ACTIVE",
            planId,
            by,
            "PLAN " + planId,
            at,
            null);
    store.insert(row);
    publish(row, by);
    return row;
  }

  /** 배정 작업자가 지금도 적격인가(null = 적격). 조회 때 계산 — 배정 뒤 정지·업무 변경을 시작 판정에 쓴다. */
  public @Nullable String eligibility(AssignmentRow r) {
    return workers.rejection(r.mfcId(), r.workerId(), r.taskType(), r.temperatureZone());
  }

  @Transactional(readOnly = true)
  public List<AssignmentRow> list(
      String mfcId,
      @Nullable String taskId,
      @Nullable String workerId,
      @Nullable String state,
      @Nullable LocalDate date) {
    requireReader(mfcId);
    String s = state == null ? "ACTIVE" : state;
    if (!STATES.contains(s)) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "state 는 ACTIVE/SUPERSEDED/RELEASED.");
    }
    return store.list(mfcId, taskId, workerId, s, date);
  }

  private void publish(AssignmentRow r, String supervisor) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("taskId", r.taskId());
    item.put("workerId", r.workerId());
    item.put("assignmentVersion", r.assignmentVersion());
    item.put("taskType", r.taskType());
    item.put("assignmentId", r.assignmentId());
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("planVersion", r.assignmentVersion());
    payload.put("mfcId", r.mfcId());
    payload.put("assignments", List.of(item));
    if (r.planId() != null) {
      payload.put("planId", r.planId());
    }
    payload.put(
        "source",
        r.planId() != null ? "PLAN" : SYSTEM.equals(r.assignedBy()) ? "SYSTEM" : "MANUAL");
    payload.put("publishedAt", r.assignedAt().toString());
    events.publish(
        codec.create(
            events.sourceSystem(),
            "AssignmentPublished",
            1,
            "AssignmentPlan",
            "MANUAL:" + r.taskId(),
            r.assignmentVersion(),
            new EventEnvelope.Actor(EventEnvelope.ActorType.OPERATOR, supervisor),
            EventEnvelope.EventSource.REAL,
            payload),
        TOPIC);
  }

  static String requireSupervisor(String mfcId) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .filter(x -> x.type() == PmartPrincipal.PrincipalType.OPERATOR)
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "운영자만 할 수 있습니다."));
    if (!p.canAccessCenter(mfcId) || !p.hasRole(SUPERVISOR_ROLE)) {
      throw new PmartException(
          ErrorCode.FORBIDDEN, "이 센터의 " + SUPERVISOR_ROLE + " 만 작업을 배정할 수 있습니다.");
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
