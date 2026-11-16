package com.pmart.cases.application;

import com.pmart.cases.domain.CaseRules;
import com.pmart.cases.domain.ObligationRules;
import com.pmart.cases.infrastructure.CaseStore;
import com.pmart.cases.infrastructure.CaseStore.CaseRow;
import com.pmart.cases.infrastructure.ObligationStore;
import com.pmart.cases.infrastructure.ObligationStore.ObligationRow;
import com.pmart.cases.infrastructure.ObligationStore.ResolutionRow;
import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.time.Clock;
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

/**
 * 사건 의무(2026-11-16 사용자 "고친 안으로 진행해 줘" — 계약 0.8.0, 업무 원문 §2 Obligation·§5.3). 규칙은 {@link
 * ObligationRules}.
 *
 * <ul>
 *   <li>등록: 사건을 다룰 수 있는 사람, 열린 사건만, 8종, 담당 팀이 그 사건을 볼 수 있어야.
 *   <li>해소: 한 만큼은 담당 팀·관리자(부분 해소 — 수량·금액 따로), 면제·실패는 관리자만(사유 필수). 근거는 확인하지 않고 기록.
 *   <li>메모·근거는 이력·표에만 — 로그에 남기지 않는다. 금액은 기록만(환불·보상 실행은 기존 클레임·결제 절차).
 * </ul>
 */
@Service
public class ObligationService {

  public static final String AGGREGATE = "Obligation";
  private static final Logger log = LoggerFactory.getLogger(ObligationService.class);

  public record Created(
      String kind,
      @Nullable String owner,
      @Nullable String targetRef,
      @Nullable Integer qty,
      @Nullable Long amount,
      @Nullable Instant dueAt,
      @Nullable String note) {}

  public record Resolve(
      String outcome,
      @Nullable Integer resolvedQty,
      @Nullable Long resolvedAmount,
      String evidenceSystem,
      String evidenceRef,
      @Nullable String evidenceKind,
      @Nullable Instant evidenceAsOf,
      @Nullable String note) {}

  public record View(ObligationRow row, List<ResolutionRow> resolutions) {}

  public record Page(List<View> items, @Nullable String nextCursor) {}

  private final ObligationStore store;
  private final CaseStore cases;
  private final CaseInboxService inbox;
  private final EventPublisher events;
  private final RequestIdempotency idempotency;
  private final Clock clock;

  public ObligationService(
      ObligationStore store,
      CaseStore cases,
      CaseInboxService inbox,
      EventPublisher events,
      RequestIdempotency idempotency,
      Clock clock) {
    this.store = store;
    this.cases = cases;
    this.inbox = inbox;
    this.events = events;
    this.idempotency = idempotency;
    this.clock = clock;
  }

  public View create(
      String requestId,
      String sourceSystem,
      String caseId,
      Created cmd,
      @Nullable Integer expectedCaseVersion) {
    PmartPrincipal p = operator();
    Set<String> roles = new HashSet<>(p.roles());
    if (!ObligationRules.KINDS.contains(cmd.kind())) {
      throw unprocessable("UNSUPPORTED_KIND", "이 종류의 의무는 담당 팀이 없어 지금 등록하지 않습니다(재고 회수·폐기·공급사 청구).");
    }
    if (cmd.owner() != null && !ObligationRules.OWNERS.contains(cmd.owner())) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "owner 가 올바르지 않습니다.");
    }
    String id =
        idempotency
            .execute(
                new RequestKey(sourceSystem, requestId, "createObligation:" + caseId, p.subject()),
                cmd,
                String.class,
                () -> {
                  CaseRow c = inbox.lockVisibleCase(caseId);
                  if (expectedCaseVersion != null && expectedCaseVersion != c.version()) {
                    throw PmartException.versionConflict(
                        "다른 변경이 먼저 반영되었습니다(현재 " + c.version() + ").");
                  }
                  if (!CaseRules.canWork(roles, c.kind(), c.assigneeGroup())) {
                    throw new PmartException(ErrorCode.FORBIDDEN, "이 사건에 의무를 등록할 권한이 없습니다.");
                  }
                  if ("CLOSED".equals(c.state())) {
                    throw PmartException.invalidState("닫힌 사건입니다 — 다시 연 뒤 등록하세요.");
                  }
                  String owner =
                      cmd.owner() != null
                          ? cmd.owner()
                          : ObligationRules.defaultOwner(c.assigneeGroup());
                  if (!ObligationRules.ownerCanSee(owner, c.kind())) {
                    throw unprocessable(
                        "OWNER_CANNOT_SEE_CASE", "그 팀은 이 사건을 볼 수 없어 의무를 맡길 수 없습니다(관리자 팀만).");
                  }
                  Instant now = now();
                  ObligationRow o =
                      new ObligationRow(
                          "OBL-" + UUID.randomUUID(),
                          caseId,
                          cmd.kind(),
                          owner,
                          blankToNull(cmd.targetRef()),
                          cmd.qty(),
                          cmd.amount(),
                          cmd.dueAt(),
                          blankToNull(cmd.note()),
                          "OPEN",
                          0,
                          0,
                          1,
                          p.subject(),
                          now,
                          now);
                  store.insert(o);
                  cases.touch(caseId, now);
                  cases.history(
                      caseId,
                      now,
                      p.subject(),
                      "OBLIGATION_CREATED",
                      null,
                      null,
                      o.obligationId() + " " + o.kind() + " → " + owner);
                  Map<String, Object> m = new LinkedHashMap<>();
                  m.put("obligationId", o.obligationId());
                  m.put("caseId", caseId);
                  m.put("kind", o.kind());
                  putIfNotNull(m, "qty", o.qty());
                  putIfNotNull(m, "amount", o.amount());
                  m.put("ownerService", owner);
                  putIfNotNull(m, "dueAt", o.dueAt() == null ? null : o.dueAt().toString());
                  m.put("createdAt", now.toString());
                  events.publish(
                      "ObligationCreated",
                      1,
                      AGGREGATE,
                      o.obligationId(),
                      1,
                      new EventEnvelope.Actor(EventEnvelope.ActorType.OPERATOR, p.subject()),
                      m);
                  log.info(
                      "의무 등록 {} 사건 {} {} by {}", o.obligationId(), caseId, o.kind(), p.subject());
                  return o.obligationId();
                })
            .value();
    return view(store.find(id).orElseThrow());
  }

  public View resolve(
      String requestId,
      String sourceSystem,
      String obligationId,
      Resolve cmd,
      @Nullable Integer expectedVersion) {
    PmartPrincipal p = operator();
    Set<String> roles = new HashSet<>(p.roles());
    String outcome = cmd.outcome();
    boolean fulfilled = ObligationRules.FULFILLED.equals(outcome);
    boolean attempted = ObligationRules.ATTEMPTED.equals(outcome);
    if (!fulfilled && (cmd.note() == null || cmd.note().isBlank())) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "시도 기록·면제·실패는 note(사유)가 필요합니다.");
    }
    if (cmd.evidenceSystem().isBlank() || cmd.evidenceRef().isBlank()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "evidence(system·ref)가 필요합니다.");
    }
    idempotency.execute(
        new RequestKey(sourceSystem, requestId, "resolveObligation:" + obligationId, p.subject()),
        cmd,
        String.class,
        () -> {
          ObligationRow o = store.lock(obligationId).orElseThrow(() -> notFound(obligationId));
          try {
            inbox.visibleCase(o.caseId());
          } catch (PmartException e) {
            throw notFound(obligationId); // 사건을 못 보면 의무도 없는 것처럼
          }
          if (expectedVersion != null && expectedVersion != o.version()) {
            throw PmartException.versionConflict("다른 변경이 먼저 반영되었습니다(현재 " + o.version() + ").");
          }
          if (!ObligationRules.OPEN_STATES.contains(o.state())) {
            throw PmartException.invalidState("이미 끝난 의무입니다(" + o.state() + ").");
          }
          if (!ObligationRules.canResolve(roles, o.owner(), outcome)) {
            throw new PmartException(
                ErrorCode.FORBIDDEN,
                fulfilled ? "이 의무를 해소할 권한이 없습니다(담당 팀·관리자)." : "면제·실패는 관리자만 합니다.");
          }
          int addQty = 0;
          long addAmount = 0;
          String state;
          if (fulfilled) {
            if (o.qty() != null && cmd.resolvedQty() == null) {
              throw new PmartException(
                  ErrorCode.VALIDATION_FAILED, "수량 의무입니다 — resolvedQty 가 필요합니다.");
            }
            if (o.amount() != null && cmd.resolvedAmount() == null) {
              throw new PmartException(
                  ErrorCode.VALIDATION_FAILED, "금액 의무입니다 — resolvedAmount 가 필요합니다.");
            }
            if ((o.qty() == null && cmd.resolvedQty() != null)
                || (o.amount() == null && cmd.resolvedAmount() != null)) {
              throw new PmartException(
                  ErrorCode.VALIDATION_FAILED, "이 의무에 없는 수량·금액입니다(종류가 다른 값은 합치지 않음).");
            }
            addQty = cmd.resolvedQty() == null ? 0 : cmd.resolvedQty();
            addAmount = cmd.resolvedAmount() == null ? 0 : cmd.resolvedAmount();
            if ((o.qty() != null && o.resolvedQty() + addQty > o.qty())
                || (o.amount() != null && o.resolvedAmount() + addAmount > o.amount())) {
              throw new PmartException(
                  "QTY_EXCEEDS_OBLIGATION",
                  HttpStatus.CONFLICT,
                  "남은 수량·금액보다 많습니다.",
                  false,
                  List.of());
            }
            state =
                ObligationRules.stateAfter(
                    o.qty(), o.amount(), o.resolvedQty() + addQty, o.resolvedAmount() + addAmount);
          } else if (attempted) {
            state = o.state(); // 시도 기록 — 상태 그대로
          } else {
            state = outcome; // WAIVED·FAILED — 일을 하지 않고 끝냄
          }
          Instant now = now();
          store.addResolution(
              obligationId,
              new ResolutionRow(
                  outcome,
                  fulfilled ? cmd.resolvedQty() : null,
                  fulfilled ? cmd.resolvedAmount() : null,
                  cmd.evidenceSystem().strip(),
                  cmd.evidenceRef().strip(),
                  cmd.evidenceKind(),
                  cmd.evidenceAsOf(),
                  blankToNull(cmd.note()),
                  p.subject(),
                  now));
          store.applyResolution(
              obligationId, state, o.resolvedQty() + addQty, o.resolvedAmount() + addAmount, now);
          cases.touch(o.caseId(), now);
          cases.history(
              o.caseId(),
              now,
              p.subject(),
              attempted ? "OBLIGATION_ATTEMPTED" : "OBLIGATION_RESOLVED",
              null,
              null,
              obligationId + " " + outcome + " → " + state);
          if (attempted) {
            log.info("의무 시도 기록 {} by {}", obligationId, p.subject());
            return obligationId; // 끝난 게 아니라 ObligationResolved 는 내지 않는다
          }
          Map<String, Object> m = new LinkedHashMap<>();
          m.put("obligationId", obligationId);
          m.put("caseId", o.caseId());
          m.put("kind", o.kind());
          if (fulfilled) {
            putIfNotNull(m, "qty", cmd.resolvedQty());
            putIfNotNull(m, "amount", cmd.resolvedAmount());
          }
          m.put("ownerService", o.owner());
          m.put("resolution", outcome);
          m.put("state", state);
          m.put("resolvedAt", now.toString());
          events.publish(
              "ObligationResolved",
              1,
              AGGREGATE,
              obligationId,
              o.version() + 1,
              new EventEnvelope.Actor(EventEnvelope.ActorType.OPERATOR, p.subject()),
              m);
          log.info("의무 해소 {} {} → {} by {}", obligationId, outcome, state, p.subject());
          return obligationId;
        });
    return view(store.find(obligationId).orElseThrow());
  }

  public View get(String obligationId) {
    ObligationRow o = store.find(obligationId).orElseThrow(() -> notFound(obligationId));
    try {
      inbox.visibleCase(o.caseId());
    } catch (PmartException e) {
      throw notFound(obligationId);
    }
    return view(o);
  }

  public Page list(
      @Nullable String owner,
      @Nullable String state,
      @Nullable String caseId,
      int size,
      @Nullable String cursor) {
    PmartPrincipal p = operator();
    Set<String> roles = new HashSet<>(p.roles());
    int limit = Math.min(Math.max(size, 1), 100);
    int offset = offset(cursor);
    ObligationStore.Filter f =
        new ObligationStore.Filter(
            CaseRules.visibleKinds(roles),
            CaseRules.centerScoped(roles) ? p.centerScope() : null,
            blankToNull(owner),
            state == null || state.isBlank() ? ObligationRules.OPEN_STATES : Set.of(state.strip()),
            blankToNull(caseId));
    List<ObligationRow> rows = store.page(f, offset, limit + 1);
    String next = rows.size() > limit ? String.valueOf(offset + limit) : null;
    List<View> out = new ArrayList<>();
    for (ObligationRow r : rows.subList(0, Math.min(rows.size(), limit))) {
      out.add(view(r));
    }
    return new Page(out, next);
  }

  /** 사건 상세의 의무(사건을 이미 볼 수 있다고 확인한 뒤). */
  public List<View> forCase(String caseId) {
    return store.forCase(caseId).stream().map(this::view).toList();
  }

  public Map<String, Integer> openCounts(List<String> caseIds) {
    return store.countOpen(caseIds);
  }

  private View view(ObligationRow o) {
    return new View(o, store.resolutions(o.obligationId()));
  }

  private static void putIfNotNull(Map<String, Object> m, String k, @Nullable Object v) {
    if (v != null) {
      m.put(k, v);
    }
  }

  private static PmartPrincipal operator() {
    return PmartPrincipal.current()
        .filter(x -> x.type() == PmartPrincipal.PrincipalType.OPERATOR)
        .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "직원만 의무를 다룹니다."));
  }

  private static PmartException unprocessable(String code, String detail) {
    return new PmartException(code, HttpStatus.UNPROCESSABLE_CONTENT, detail, false, List.of());
  }

  private static PmartException notFound(String id) {
    return PmartException.notFound("OBLIGATION_NOT_FOUND", "의무를 찾을 수 없습니다: " + id);
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

  private static @Nullable String blankToNull(@Nullable String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }
}
