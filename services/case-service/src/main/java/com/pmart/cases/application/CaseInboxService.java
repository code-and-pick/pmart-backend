package com.pmart.cases.application;

import com.pmart.cases.domain.CaseRules;
import com.pmart.cases.infrastructure.CaseStore;
import com.pmart.cases.infrastructure.CaseStore.CaseRow;
import com.pmart.cases.infrastructure.CaseStore.Ref;
import com.pmart.cases.infrastructure.DispatchClient;
import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 사건함(2026-11-16, 계약 0.4.0 — 사용자 "사건함 공통 결정"). 규칙은 {@link CaseRules}.
 *
 * <ul>
 *   <li>여는 길: delivery-execution {@code DeliveryIncidentRaised}(라이더 도움요청·전달 실패, R-4),
 *       delivery-tracking {@code RiderLocationStale}(R-6 — 같은 라이더·배송으로 열린 사건이 있으면 이력만 더함), 직원의 오배송
 *       확인(R-7 — {@code MisdeliveryConfirmed} 도 냄), 사가·결제 이상(R-3 — order-orchestration·payment 사건 중
 *       이상만, OPERATOR_ADMIN).
 *   <li>팀 공용 목록 → 직원이 집어 간다(assign = 자기 자신, OPERATOR_ADMIN 은 다른 사람에게도). 닫기는 사유·요약 필수.
 *   <li>사건마다 {@code CaseOpened·CaseAssigned·CaseClosed}(platform.case.v1) — delivery-execution 이
 *       도움요청에 사건 번호를 붙이는 데 쓴다(그쪽 구현은 delivery 영역).
 *   <li>요약·이력에 개인정보 원문을 넣지 않는다(참조만). 직원이 쓴 요약·종결 내용은 로그에 남기지 않는다.
 * </ul>
 */
@Service
public class CaseInboxService {

  private static final Logger log = LoggerFactory.getLogger(CaseInboxService.class);
  private static final String AGGREGATE = "Case";
  private static final String SYSTEM = "SYSTEM";

  public record Detail(CaseRow row, List<CaseStore.HistoryRow> history, boolean overdue) {}

  public record Page(List<CaseRow> items, @Nullable String nextCursor) {}

  public record ListQuery(
      @Nullable String kind,
      @Nullable String state,
      @Nullable String centerId,
      @Nullable String assigneeId,
      @Nullable String priority,
      @Nullable String subjectRef,
      @Nullable String assigneeGroup,
      boolean unassigned,
      boolean overdue) {}

  public record MisdeliveryCommand(
      String deliveryId, String summary, @Nullable String centerId, List<String> evidenceRefs) {}

  private final CaseStore store;
  private final EventPublisher events;
  private final RequestIdempotency idempotency;
  private final DispatchClient dispatch;
  private final CaseProperties props;
  private final Clock clock;

  public CaseInboxService(
      CaseStore store,
      EventPublisher events,
      RequestIdempotency idempotency,
      DispatchClient dispatch,
      CaseProperties props,
      Clock clock) {
    this.store = store;
    this.events = events;
    this.idempotency = idempotency;
    this.dispatch = dispatch;
    this.props = props;
    this.clock = clock;
  }

  // ---------------------------------------------------------------- 사건으로 열기(inbox 트랜잭션 안)

  /** R-4: 라이더 도움요청·전달 실패 등록 → RIDER_HELP·DELIVERY_INCIDENT. 같은 incidentId 로 열린 사건이 있으면 그대로. */
  public void onDeliveryIncident(EventEnvelope e) {
    JsonNode p = e.payload();
    String incidentId = text(p, "incidentId");
    String deliveryId = text(p, "deliveryId");
    if (incidentId == null || deliveryId == null) {
      throw new IllegalArgumentException("incidentId·deliveryId 없음"); // 계약 위반 → DLT
    }
    String category = text(p, "category");
    String kind = CaseRules.incidentKind(text(p, "type"), category);
    List<Ref> refs = new ArrayList<>();
    refs.add(new Ref("incidentId", incidentId));
    refs.add(new Ref("deliveryId", deliveryId));
    // 도움요청 단건(GET /deliveries/{id}/help-requests/{requestId})을 바로 열 수 있게 — incidentId = 배송 ID + ":"
    // + 요청 ID
    // (delivery-execution). 클라이언트 질문 2026-11-16. 형식이 다르면 싣지 않는다
    if (incidentId.startsWith(deliveryId + ":") && incidentId.length() > deliveryId.length() + 1) {
      refs.add(new Ref("requestId", incidentId.substring(deliveryId.length() + 1)));
    }
    addRef(refs, "orderId", text(p, "orderId"));
    addRef(refs, "riderId", text(p, "riderId"));
    String summary =
        (CaseRules.DELIVERY_INCIDENT.equals(kind) ? "전달 실패 등록" : "라이더 도움요청")
            + (category == null ? "" : " — " + category)
            + (text(p, "stage") == null ? "" : " (" + text(p, "stage") + ")");
    openFromEvent(
        e,
        kind,
        CaseRules.incidentPriority(category),
        "DELIVERY",
        deliveryId,
        text(p, "mfcId"),
        summary,
        refs,
        "INCIDENT:" + incidentId);
  }

  /** R-6: 배달 중 위치 끊김 → RIDER_LOCATION_STALE. 같은 라이더·배송으로 열린 사건이 있으면 이력(재발)만. */
  public void onLocationStale(EventEnvelope e) {
    JsonNode p = e.payload();
    String riderId = text(p, "riderId");
    if (riderId == null) {
      throw new IllegalArgumentException("riderId 없음");
    }
    String deliveryId = text(p, "deliveryId");
    String key = "STALE:" + riderId + ":" + (deliveryId == null ? "-" : deliveryId);
    Instant now = now();
    var open = store.lockOpenByDedupe(key);
    if (open.isPresent()) {
      store.touch(open.get().caseId(), now);
      store.history(open.get().caseId(), now, SYSTEM, "RECURRED", null, null, "위치 끊김 다시 감지");
      log.info("위치 끊김 재발 — 사건 {} 에 이력만", open.get().caseId());
      return;
    }
    List<Ref> refs = new ArrayList<>();
    refs.add(new Ref("riderId", riderId));
    addRef(refs, "deliveryId", deliveryId);
    openFromEvent(
        e,
        CaseRules.RIDER_LOCATION_STALE,
        "P2",
        deliveryId == null ? "RIDER" : "DELIVERY",
        deliveryId == null ? riderId : deliveryId,
        null,
        "배달 중 라이더 위치 끊김(마지막 " + text(p, "lastSeenAt") + ") — 라이더 연락 필요",
        refs,
        key);
  }

  /**
   * 배차 실패(2026-11-16 사용자 — 중간안·지원센터·10분) → DISPATCH_FAILED. 배송별 실패 횟수를 세어({@code
   * dispatch_failure_watch}) 시스템 장애는 첫 실패에, 그 밖은 두 번째 실패부터 연다. 열린 사건이 있으면 이력(재발)만. 배차가 뒤에 성공해도 자동으로
   * 닫지 않는다(담당자가 확인하고 닫는다).
   */
  public void onDispatchFailed(EventEnvelope e) {
    JsonNode p = e.payload();
    String deliveryId = text(p, "deliveryId");
    if (deliveryId == null) {
      throw new IllegalArgumentException("deliveryId 없음"); // 계약 위반 → DLT
    }
    String reason = text(p, "reason");
    Instant now = now();
    int failures = store.countDispatchFailure(deliveryId, reason == null ? "-" : reason, now);
    String note = "배차 실패 " + failures + "번째 — " + CaseRules.dispatchReasonName(reason);
    String key = "DISPATCH:" + deliveryId;
    var open = store.lockOpenByDedupe(key);
    if (open.isPresent()) {
      store.touch(open.get().caseId(), now);
      store.history(open.get().caseId(), now, SYSTEM, "RECURRED", null, null, note);
      log.info("배차 실패 재발 — 사건 {} 에 이력만", open.get().caseId());
      return;
    }
    if (!CaseRules.dispatchFailureOpens(reason, failures)) {
      log.info("배송 {} 배차 실패 {}번째({}) — 아직 사건 아님", deliveryId, failures, reason);
      return;
    }
    List<Ref> refs = new ArrayList<>();
    refs.add(new Ref("deliveryId", deliveryId));
    addRef(refs, "orderId", text(p, "orderId"));
    openFromEvent(
        e,
        CaseRules.DISPATCH_FAILED,
        "P2",
        "DELIVERY",
        deliveryId,
        text(p, "mfcId"), // 센터 관리자가 자기 센터 것을 본다 — dispatch 가 싣기 전(DD 요청)에는 null
        note + ("SYSTEM_FAILURE".equals(reason) ? " (자동 재배차 없음)" : " (10분마다 자동 재배차 중)"),
        refs,
        key);
  }

  /**
   * R-3: 사가·결제 이상 → RECONCILIATION·PAYMENT_UNKNOWN(OPERATOR_ADMIN, 2026-11-16 사용자 "이상만"). 같은 주문으로
   * 열린 같은 종류 사건이 있으면 이력(재발)만 — 결제 결과 불명은 15분마다 다시 오므로 재발 이력도 남기지 않는다. 사건은 알림·기록이고 조치는 처리
   * 대기(BO-12)에서 한다. 금액·상세는 사건에 싣지 않는다(참조만).
   */
  public void onOrderException(EventEnvelope e) {
    JsonNode p = e.payload();
    String type = e.eventType();
    String kind =
        CaseRules.orderExceptionKind(type, text(p, "result"), p.path("escalated").asBoolean(false));
    if (kind == null) {
      return; // 정상 보상·아직 조회 중인 결제 불명 — 사건 아님
    }
    String orderId = text(p, "orderId") != null ? text(p, "orderId") : text(p, "orderRef");
    String sagaId = text(p, "sagaId");
    String paymentAttemptId = text(p, "paymentAttemptId");
    String subjectRef = orderId != null ? orderId : sagaId != null ? sagaId : paymentAttemptId;
    if (subjectRef == null) {
      throw new IllegalArgumentException("orderId·sagaId·paymentAttemptId 없음"); // 계약 위반 → DLT
    }
    String subjectType = orderId != null ? "ORDER" : sagaId != null ? "SAGA" : "PAYMENT";
    String key = kind + ":" + subjectRef;
    Instant now = now();
    var open = store.lockOpenByDedupe(key);
    if (open.isPresent()) {
      if (!"PaymentResultUnknown".equals(type)) {
        store.touch(open.get().caseId(), now);
        store.history(
            open.get().caseId(),
            now,
            SYSTEM,
            "RECURRED",
            null,
            null,
            orderExceptionSummary(type, p));
      }
      log.info("사가·결제 이상 재발 {} — 사건 {} 에 이력만", type, open.get().caseId());
      return;
    }
    List<Ref> refs = new ArrayList<>();
    addRef(refs, "orderId", orderId);
    addRef(refs, "sagaId", sagaId);
    addRef(refs, "paymentAttemptId", paymentAttemptId);
    addRef(refs, "resourceId", text(p, "resourceId"));
    openFromEvent(
        e, kind, "P2", subjectType, subjectRef, null, orderExceptionSummary(type, p), refs, key);
  }

  private static String orderExceptionSummary(String type, JsonNode p) {
    return switch (type) {
      case "ReconciliationRequired" -> "대사 필요 — " + orDash(text(p, "mismatchType"));
      case "PaymentMismatch" -> "결제 승인 불일치 — " + orDash(text(p, "mismatchType"));
      case "CompensationExecuted" ->
          "보상 해제 "
              + ("FAILED".equals(text(p, "result")) ? "실패" : "결과 불명")
              + " — "
              + orDash(text(p, "resourceType"));
      case "PaymentResultUnknown" ->
          "결제 결과 불명 상신 — 원거래 조회 " + p.path("inquiryCount").asInt(0) + "회";
      default -> type;
    };
  }

  private static String orDash(@Nullable String s) {
    return s == null ? "-" : s;
  }

  private void openFromEvent(
      EventEnvelope e,
      String kind,
      String priority,
      String subjectType,
      String subjectRef,
      @Nullable String centerId,
      String summary,
      List<Ref> refs,
      String dedupeKey) {
    Instant now = now();
    if (store.lockOpenByDedupe(dedupeKey).isPresent()) {
      log.info("이미 열린 사건 — {} 무시", dedupeKey);
      return;
    }
    refs = withPrevious(kind, subjectRef, refs);
    CaseRow c =
        new CaseRow(
            "CASE-" + UUID.randomUUID(),
            kind,
            "OPEN",
            priority,
            subjectType,
            subjectRef,
            centerId,
            null,
            CaseRules.group(kind),
            now.plus(sla(kind)),
            summary,
            refs,
            e.eventId().toString(),
            1,
            now,
            now,
            null,
            null,
            null,
            null);
    if (!store.insert(c, dedupeKey, e.eventType())) {
      log.info("사건 중복 — {} 무시", dedupeKey);
      return;
    }
    store.history(c.caseId(), now, SYSTEM, "OPENED", null, "OPEN", e.eventType());
    publishOpened(c, new EventEnvelope.Actor(EventEnvelope.ActorType.SYSTEM, "case-service"));
    log.info("사건 열림 {} {} {}", c.caseId(), kind, subjectRef);
  }

  // ---------------------------------------------------------------- 직원 명령

  /** R-7: 오배송 확인 기록 → MISDELIVERY 사건 + MisdeliveryConfirmed(회수 배차 DD-2). */
  public Detail createMisdelivery(String requestId, String sourceSystem, MisdeliveryCommand cmd) {
    PmartPrincipal p = requireAny(CaseRules.CREATE_MISDELIVERY_ROLES, "오배송 확인 기록");
    String deliveryId = cmd.deliveryId().strip();
    // 배정 라이더(있으면 사건에 싣는다 — 회수 배차가 쓴다). 못 읽어도 기록은 막지 않는다
    String riderId = null;
    if (props.deliveryDispatchBaseUrl() != null) {
      try {
        riderId = dispatch.assignment(deliveryId).riderId();
      } catch (RuntimeException ex) {
        log.warn("오배송 확인 — 배정 라이더를 읽지 못함({}): {}", deliveryId, ex.toString());
      }
    }
    String rider = riderId;
    String caseId =
        idempotency
            .execute(
                new RequestKey(sourceSystem, requestId, "createMisdelivery", p.subject()),
                cmd,
                String.class,
                () -> {
                  Instant now = now();
                  String key = "MISDELIVERY:" + deliveryId;
                  if (store.lockOpenByDedupe(key).isPresent()) {
                    throw new PmartException(
                        "CASE_ALREADY_OPEN",
                        HttpStatus.CONFLICT,
                        "이 배송의 오배송 사건이 이미 열려 있습니다.",
                        false,
                        List.of());
                  }
                  List<Ref> refs = new ArrayList<>();
                  refs.add(new Ref("deliveryId", deliveryId));
                  addRef(refs, "riderId", rider);
                  refs = withPrevious(CaseRules.MISDELIVERY, deliveryId, refs);
                  CaseRow c =
                      new CaseRow(
                          "CASE-" + UUID.randomUUID(),
                          CaseRules.MISDELIVERY,
                          "OPEN",
                          "P2",
                          "DELIVERY",
                          deliveryId,
                          blankToNull(cmd.centerId()),
                          null,
                          CaseRules.GROUP_DELIVERY_OPS,
                          now.plus(sla(CaseRules.MISDELIVERY)),
                          cmd.summary().strip(),
                          refs,
                          null,
                          1,
                          now,
                          now,
                          null,
                          null,
                          null,
                          null);
                  store.insert(c, key, null);
                  store.history(c.caseId(), now, p.subject(), "OPENED", null, "OPEN", "오배송 확인");
                  var actor =
                      new EventEnvelope.Actor(EventEnvelope.ActorType.OPERATOR, p.subject());
                  publishOpened(c, actor);
                  Map<String, Object> m = new LinkedHashMap<>();
                  m.put("caseId", c.caseId());
                  m.put("deliveryId", deliveryId);
                  if (rider != null) {
                    m.put("riderId", rider);
                  }
                  m.put("confirmedBy", p.subject());
                  m.put("confirmedAt", now.toString());
                  if (!cmd.evidenceRefs().isEmpty()) {
                    m.put("evidenceRefs", cmd.evidenceRefs());
                  }
                  events.publish("MisdeliveryConfirmed", 1, AGGREGATE, c.caseId(), 1, actor, m);
                  log.info("오배송 확인 사건 {} 배송 {} by {}", c.caseId(), deliveryId, p.subject());
                  return c.caseId();
                })
            .value();
    return detail(store.find(caseId).orElseThrow());
  }

  public Page list(ListQuery q, @Nullable String cursor, int size) {
    PmartPrincipal p = operator();
    Set<String> kinds = CaseRules.visibleKinds(roles(p));
    if (kinds.isEmpty()) {
      throw new PmartException(ErrorCode.FORBIDDEN, "사건함을 볼 권한이 없습니다.");
    }
    int offset = offset(cursor);
    int n = Math.min(Math.max(size, 1), 200);
    List<CaseRow> rows =
        store.page(
            new CaseStore.Filter(
                kinds,
                q.kind(),
                q.state(),
                q.centerId(),
                q.assigneeId(),
                q.priority(),
                q.subjectRef(),
                q.assigneeGroup(),
                q.unassigned(),
                q.overdue(),
                now(),
                CaseRules.centerScoped(roles(p)) ? p.centerScope() : null),
            offset,
            n + 1);
    boolean more = rows.size() > n;
    return new Page(more ? rows.subList(0, n) : rows, more ? String.valueOf(offset + n) : null);
  }

  public Detail get(String caseId) {
    PmartPrincipal p = operator();
    CaseRow c = visible(p, caseId);
    return detail(c);
  }

  /** 집기(자기 자신) 또는 OPERATOR_ADMIN 의 배정·다시 배정. */
  public Detail assign(
      String requestId,
      String sourceSystem,
      String caseId,
      String assigneeId,
      @Nullable Integer expectedVersion) {
    PmartPrincipal p = operator();
    Set<String> roles = roles(p);
    boolean admin = roles.contains(CaseRules.ADMIN);
    idempotency.execute(
        new RequestKey(sourceSystem, requestId, "assignCase:" + caseId, p.subject()),
        assigneeId,
        String.class,
        () -> {
          CaseRow c = lockVisible(p, caseId);
          checkVersion(c, expectedVersion);
          if (!CaseRules.canWork(roles, c.kind())) {
            throw new PmartException(ErrorCode.FORBIDDEN, "이 사건을 맡을 권한이 없습니다.");
          }
          if ("CLOSED".equals(c.state())) {
            throw PmartException.invalidState("닫힌 사건입니다.");
          }
          if (!admin && !assigneeId.equals(p.subject())) {
            throw new PmartException(ErrorCode.FORBIDDEN, "자기 자신에게만 집을 수 있습니다(다른 사람 배정은 관리자).");
          }
          if (!admin && c.assigneeId() != null && !c.assigneeId().equals(p.subject())) {
            throw PmartException.invalidState("이미 다른 직원이 맡은 사건입니다.");
          }
          if (assigneeId.equals(c.assigneeId())) {
            return caseId; // 같은 사람 — 그대로
          }
          Instant now = now();
          store.assign(caseId, assigneeId, "ASSIGNED", now);
          store.history(
              caseId, now, p.subject(), "ASSIGNED", c.state(), "ASSIGNED", "담당 " + assigneeId);
          CaseRow after = store.find(caseId).orElseThrow();
          Map<String, Object> m = basePayload(after);
          m.put("assignee", assigneeId);
          m.put("previousAssignee", c.assigneeId());
          m.put("assignedAt", now.toString());
          events.publish(
              "CaseAssigned",
              1,
              AGGREGATE,
              caseId,
              after.version(),
              new EventEnvelope.Actor(EventEnvelope.ActorType.OPERATOR, p.subject()),
              m);
          return caseId;
        });
    return detail(store.find(caseId).orElseThrow());
  }

  public Detail close(
      String requestId,
      String sourceSystem,
      String caseId,
      String resolution,
      String summary,
      @Nullable Integer expectedVersion) {
    PmartPrincipal p = operator();
    Set<String> roles = roles(p);
    if (!CaseRules.RESOLUTIONS.contains(resolution)) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "resolution 이 올바르지 않습니다.");
    }
    if (summary == null || summary.isBlank()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "summary(처리 내용)가 필요합니다.");
    }
    idempotency.execute(
        new RequestKey(sourceSystem, requestId, "closeCase:" + caseId, p.subject()),
        List.of(resolution, summary),
        String.class,
        () -> {
          CaseRow c = lockVisible(p, caseId);
          checkVersion(c, expectedVersion);
          if (!CaseRules.canWork(roles, c.kind())) {
            throw new PmartException(ErrorCode.FORBIDDEN, "이 사건을 닫을 권한이 없습니다.");
          }
          if ("CLOSED".equals(c.state())) {
            return caseId; // 이미 닫힘
          }
          if (c.assigneeId() != null
              && !c.assigneeId().equals(p.subject())
              && !roles.contains(CaseRules.ADMIN)) {
            throw new PmartException(ErrorCode.FORBIDDEN, "다른 직원이 맡은 사건은 관리자만 닫을 수 있습니다.");
          }
          Instant now = now();
          store.close(caseId, p.subject(), resolution, summary.strip(), now);
          store.history(caseId, now, p.subject(), "CLOSED", c.state(), "CLOSED", resolution);
          CaseRow after = store.find(caseId).orElseThrow();
          Map<String, Object> m = basePayload(after);
          m.put("assignee", after.assigneeId());
          m.put("outcome", resolution);
          m.put("closedAt", now.toString());
          events.publish(
              "CaseClosed",
              1,
              AGGREGATE,
              caseId,
              after.version(),
              new EventEnvelope.Actor(EventEnvelope.ActorType.OPERATOR, p.subject()),
              m);
          log.info("사건 닫힘 {} {} by {}", caseId, resolution, p.subject());
          return caseId;
        });
    return detail(store.find(caseId).orElseThrow());
  }

  /**
   * 재개(0.6.0 — 2026-11-16 사용자 "1번 + 이전 사건 연결", 업무 원문 §5.3·BO-27): 사람이 사유를 적고 연다. 닫기와 같은 사람(그 종류
   * 팀·관리자), 기간 제한 없음. 담당을 비우고(팀 공용 목록), 처리 기한은 지금부터 그 종류 기본값. 같은 일로 열린 사건이 있으면 409
   * CASE_ALREADY_OPEN.
   */
  public Detail reopen(
      String requestId,
      String sourceSystem,
      String caseId,
      String reason,
      @Nullable Integer expectedVersion) {
    PmartPrincipal p = operator();
    Set<String> roles = roles(p);
    if (reason == null || reason.isBlank()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "reason(재개 사유)이 필요합니다.");
    }
    idempotency.execute(
        new RequestKey(sourceSystem, requestId, "reopenCase:" + caseId, p.subject()),
        reason,
        String.class,
        () -> {
          CaseRow c = lockVisible(p, caseId);
          checkVersion(c, expectedVersion);
          if (!CaseRules.canWork(roles, c.kind())) {
            throw new PmartException(ErrorCode.FORBIDDEN, "이 사건을 다시 열 권한이 없습니다.");
          }
          if (!"CLOSED".equals(c.state())) {
            throw PmartException.invalidState("닫힌 사건만 다시 열 수 있습니다.");
          }
          Instant now = now();
          try {
            store.reopen(caseId, now.plus(sla(c.kind())), now);
          } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new PmartException(
                "CASE_ALREADY_OPEN",
                HttpStatus.CONFLICT,
                "같은 일로 열린 사건이 이미 있습니다 — 그 사건에서 처리하세요.",
                false,
                List.of());
          }
          store.history(caseId, now, p.subject(), "REOPENED", "CLOSED", "REOPENED", reason.strip());
          CaseRow after = store.find(caseId).orElseThrow();
          Map<String, Object> m = basePayload(after);
          m.put("assignee", null);
          m.put("reason", reason.strip());
          m.put("reopenedAt", now.toString());
          events.publish(
              "CaseReopened",
              1,
              AGGREGATE,
              caseId,
              after.version(),
              new EventEnvelope.Actor(EventEnvelope.ActorType.OPERATOR, p.subject()),
              m);
          log.info("사건 재개 {} by {}", caseId, p.subject());
          return caseId;
        });
    return detail(store.find(caseId).orElseThrow());
  }

  // ---------------------------------------------------------------- 공통

  /** 같은 종류·같은 대상의 가장 최근 닫힌 사건을 previousCaseId 로 잇는다(0.6.0 — 근거가 뒤집혔으면 사람이 그 사건을 재개). */
  private List<Ref> withPrevious(String kind, @Nullable String subjectRef, List<Ref> refs) {
    if (subjectRef == null) {
      return refs;
    }
    var prev = store.latestClosed(kind, subjectRef);
    if (prev.isEmpty()) {
      return refs;
    }
    List<Ref> out = new ArrayList<>(refs);
    out.add(new Ref("previousCaseId", prev.get()));
    return out;
  }

  private void publishOpened(CaseRow c, EventEnvelope.Actor actor) {
    Map<String, Object> m = basePayload(c);
    m.put("assignee", null);
    m.put("openedAt", c.openedAt().toString());
    events.publish("CaseOpened", 1, AGGREGATE, c.caseId(), c.version(), actor, m);
  }

  private static Map<String, Object> basePayload(CaseRow c) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("caseId", c.caseId());
    m.put("type", c.kind());
    m.put("refs", CaseStore.refsPayload(c.refs()));
    if (c.priority() != null) {
      m.put("priority", c.priority());
    }
    return m;
  }

  private Detail detail(CaseRow c) {
    return new Detail(c, store.historyOf(c.caseId()), overdue(c));
  }

  public boolean overdue(CaseRow c) {
    return !"CLOSED".equals(c.state()) && c.slaDueAt() != null && c.slaDueAt().isBefore(now());
  }

  private Duration sla(String kind) {
    Map<String, Duration> configured = props.sla();
    if (configured != null && configured.get(kind) != null) {
      return configured.get(kind);
    }
    return CaseRules.DEFAULT_SLA.getOrDefault(kind, Duration.ofHours(4));
  }

  private CaseRow visible(PmartPrincipal p, String caseId) {
    CaseRow c = store.find(caseId).orElseThrow(() -> notFound(caseId));
    if (!CaseRules.visibleKinds(roles(p)).contains(c.kind()) || !inCenterScope(p, c)) {
      throw notFound(caseId); // 볼 수 없으면 있는지도 알리지 않는다
    }
    return c;
  }

  private CaseRow lockVisible(PmartPrincipal p, String caseId) {
    CaseRow c = store.lock(caseId).orElseThrow(() -> notFound(caseId));
    if (!CaseRules.visibleKinds(roles(p)).contains(c.kind()) || !inCenterScope(p, c)) {
      throw notFound(caseId);
    }
    return c;
  }

  /** 센터 관리자는 자기 센터(토큰 centers) 사건만 — 센터가 없는 사건은 보이지 않는다. */
  private static boolean inCenterScope(PmartPrincipal p, CaseRow c) {
    return !CaseRules.centerScoped(roles(p))
        || (c.centerId() != null && p.centerScope().contains(c.centerId()));
  }

  private static void checkVersion(CaseRow c, @Nullable Integer expected) {
    if (expected != null && expected != c.version()) {
      throw PmartException.versionConflict("다른 변경이 먼저 반영되었습니다(현재 " + c.version() + ").");
    }
  }

  private static Set<String> roles(PmartPrincipal p) {
    return new HashSet<>(p.roles());
  }

  private static PmartPrincipal operator() {
    return PmartPrincipal.current()
        .filter(x -> x.type() == PmartPrincipal.PrincipalType.OPERATOR)
        .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "직원만 사건함을 볼 수 있습니다."));
  }

  private static PmartPrincipal requireAny(List<String> roles, String what) {
    PmartPrincipal p = operator();
    if (roles.stream().noneMatch(p::hasRole)) {
      throw new PmartException(
          ErrorCode.FORBIDDEN, what + " 권한이 없습니다(" + String.join("·", roles) + ").");
    }
    return p;
  }

  private static int offset(@Nullable String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return 0;
    }
    try {
      int n = Integer.parseInt(cursor);
      if (n < 0 || n > 100_000) {
        throw new NumberFormatException();
      }
      return n;
    } catch (NumberFormatException e) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "cursor 가 올바르지 않습니다.");
    }
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  private static void addRef(List<Ref> refs, String type, @Nullable String id) {
    if (id != null && !id.isBlank()) {
      refs.add(new Ref(type, id));
    }
  }

  private static @Nullable String text(JsonNode p, String field) {
    String v = p.path(field).asString(null);
    return v == null || v.isBlank() ? null : v;
  }

  private static @Nullable String blankToNull(@Nullable String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }

  private static PmartException notFound(String caseId) {
    return PmartException.notFound("CASE_NOT_FOUND", "사건을 찾을 수 없습니다: " + caseId);
  }
}
