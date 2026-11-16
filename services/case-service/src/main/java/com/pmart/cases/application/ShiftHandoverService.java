package com.pmart.cases.application;

import com.pmart.cases.domain.CaseRules;
import com.pmart.cases.infrastructure.CaseStore;
import com.pmart.cases.infrastructure.CaseStore.CaseRow;
import com.pmart.cases.infrastructure.ObligationStore;
import com.pmart.cases.infrastructure.ShiftHandoverStore;
import com.pmart.cases.infrastructure.ShiftHandoverStore.HandoverRow;
import com.pmart.cases.infrastructure.ShiftHandoverStore.ItemRow;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 교대 인수인계(2026-11-16 사용자 "진행해 줘" — 업무 원문 BO-27 최대한 그대로, 계약 0.9.0).
 *
 * <ul>
 *   <li>작성: 전임 본인(또는 전임 부재면 관리자)이 자기가 맡은 열린 접수 건을 건마다 다음 조치(필수)·확인 기한(선택)과 함께. 담당은 아직 그대로.
 *   <li>수락: 후임 본인만, 건별(빼면 EXCLUDED — 전임 담당 그대로, 미인수). 그사이 바뀐 건·후임이 다룰 수 없는 건은 건너뛰고 표시.
 *   <li>미인수(수락 전·빠진 건)는 관리자 목록(원문 §5 "미인수 상신"). 기한 값은 두지 않는다. 근무 확인은 하지 않는다(본사 근무표 없음).
 * </ul>
 */
@Service
public class ShiftHandoverService {

  public static final String AGGREGATE = "ShiftHandover";
  static final Set<String> GROUPS = Set.of(CaseRules.GROUP_DELIVERY_OPS, CaseRules.GROUP_ORDER_OPS);
  private static final Logger log = LoggerFactory.getLogger(ShiftHandoverService.class);

  public record Item(String caseId, String nextAction, @Nullable Instant checkDueAt) {}

  public record Create(
      String group,
      @Nullable String centerId,
      @Nullable String fromOperatorId,
      String toOperatorId,
      List<Item> items,
      @Nullable String note) {}

  public record View(HandoverRow row, List<ItemRow> items, boolean unreceived) {}

  public record Page(List<View> items, @Nullable String nextCursor) {}

  private final ShiftHandoverStore store;
  private final CaseStore cases;
  private final ObligationStore obligations;
  private final CaseInboxService inbox;
  private final EventPublisher events;
  private final RequestIdempotency idempotency;
  private final Clock clock;

  public ShiftHandoverService(
      ShiftHandoverStore store,
      CaseStore cases,
      ObligationStore obligations,
      CaseInboxService inbox,
      EventPublisher events,
      RequestIdempotency idempotency,
      Clock clock) {
    this.store = store;
    this.cases = cases;
    this.obligations = obligations;
    this.inbox = inbox;
    this.events = events;
    this.idempotency = idempotency;
    this.clock = clock;
  }

  public View create(String requestId, String sourceSystem, Create cmd) {
    PmartPrincipal p = operator();
    boolean admin = p.hasRole(CaseRules.ADMIN);
    String requestedFrom = blankToNull(cmd.fromOperatorId());
    String from = requestedFrom == null ? p.subject() : requestedFrom;
    String to = cmd.toOperatorId().strip();
    if (!from.equals(p.subject()) && !admin) {
      throw new PmartException(ErrorCode.FORBIDDEN, "남의 인계서는 관리자만 씁니다(전임 부재).");
    }
    if (!GROUPS.contains(cmd.group())) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "group 은 DELIVERY_OPS·ORDER_OPS 입니다.");
    }
    if (to.isEmpty() || to.equals(from)) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "후임(toOperatorId)은 전임과 달라야 합니다.");
    }
    Set<String> seen = new HashSet<>();
    for (Item i : cmd.items()) {
      if (i.nextAction() == null || i.nextAction().isBlank()) {
        throw new PmartException(
            ErrorCode.VALIDATION_FAILED, "건마다 다음 조치(nextAction)가 필요합니다: " + i.caseId());
      }
      if (!seen.add(i.caseId())) {
        throw new PmartException(ErrorCode.VALIDATION_FAILED, "같은 건이 두 번 있습니다: " + i.caseId());
      }
    }
    String id =
        idempotency
            .execute(
                new RequestKey(sourceSystem, requestId, "createShiftHandover", p.subject()),
                cmd,
                String.class,
                () -> {
                  Instant now = now();
                  String handoverId = "SHO-" + UUID.randomUUID();
                  List<ItemRow> rows = new ArrayList<>();
                  for (Item i : cmd.items()) {
                    CaseRow c =
                        cases
                            .lock(i.caseId())
                            .orElseThrow(() -> notHandoverable(i.caseId(), "없는 건"));
                    if ("CLOSED".equals(c.state())) {
                      throw notHandoverable(i.caseId(), "처리 끝난 건");
                    }
                    if (!from.equals(c.assigneeId())) {
                      throw notHandoverable(i.caseId(), "전임이 맡은 건이 아님");
                    }
                    if (!cmd.group().equals(c.assigneeGroup())) {
                      throw notHandoverable(i.caseId(), "다른 업무(팀)의 건");
                    }
                    rows.add(
                        new ItemRow(
                            c.caseId(),
                            i.nextAction().strip(),
                            i.checkDueAt(),
                            c.state(),
                            c.priority(),
                            obligations.countOpen(c.caseId()),
                            "PENDING",
                            null));
                  }
                  store.insert(
                      new HandoverRow(
                          handoverId,
                          cmd.group(),
                          blankToNull(cmd.centerId()),
                          from,
                          to,
                          p.subject(),
                          "PENDING",
                          blankToNull(cmd.note()),
                          store.teamSnapshot(cmd.group()),
                          1,
                          now,
                          null,
                          null));
                  try {
                    for (ItemRow r : rows) {
                      store.insertItem(handoverId, r);
                    }
                  } catch (DuplicateKeyException e) {
                    throw new PmartException(
                        "CASE_IN_HANDOVER",
                        HttpStatus.CONFLICT,
                        "다른 진행 중 인계서에 든 건이 있습니다.",
                        false,
                        List.of());
                  }
                  for (ItemRow r : rows) {
                    cases.history(
                        r.caseId(),
                        now,
                        p.subject(),
                        "HANDOVER_REQUESTED",
                        null,
                        null,
                        from + " → " + to);
                  }
                  log.info("교대 인계서 {} {}건 by {}", handoverId, rows.size(), p.subject());
                  return handoverId;
                })
            .value();
    return view(store.find(id).orElseThrow());
  }

  public View accept(String requestId, String sourceSystem, String id, List<String> excluded) {
    PmartPrincipal p = operator();
    Set<String> roles = new HashSet<>(p.roles());
    idempotency.execute(
        new RequestKey(sourceSystem, requestId, "acceptShiftHandover:" + id, p.subject()),
        excluded,
        String.class,
        () -> {
          HandoverRow h = visible(p, store.lock(id).orElseThrow(() -> notFound(id)));
          if (!p.subject().equals(h.toOperatorId())) {
            throw new PmartException(ErrorCode.FORBIDDEN, "후임 본인만 수락합니다.");
          }
          if (!"PENDING".equals(h.state())) {
            throw PmartException.invalidState("진행 중인 인계서가 아닙니다(" + h.state() + ").");
          }
          List<ItemRow> items = store.items(id);
          Set<String> ids = new HashSet<>(items.stream().map(ItemRow::caseId).toList());
          for (String x : excluded) {
            if (!ids.contains(x)) {
              throw new PmartException(ErrorCode.VALIDATION_FAILED, "인계서에 없는 건입니다: " + x);
            }
          }
          Instant now = now();
          List<String> accepted = new ArrayList<>();
          List<String> notReceived = new ArrayList<>();
          for (ItemRow i : items) {
            String outcome;
            if (excluded.contains(i.caseId())) {
              outcome = "EXCLUDED";
              notReceived.add(i.caseId());
            } else {
              CaseRow c = cases.lock(i.caseId()).orElse(null);
              if (c == null
                  || "CLOSED".equals(c.state())
                  || !h.fromOperatorId().equals(c.assigneeId())
                  || !h.group().equals(c.assigneeGroup())) {
                outcome = "SKIPPED_CHANGED";
              } else if (!CaseRules.canWork(roles, c.kind(), c.assigneeGroup())) {
                outcome = "SKIPPED_NOT_ALLOWED";
                notReceived.add(i.caseId());
              } else {
                inbox.handOver(
                    c,
                    h.toOperatorId(),
                    p.subject(),
                    h.fromOperatorId() + " → " + h.toOperatorId() + ": " + i.nextAction());
                outcome = "ACCEPTED";
                accepted.add(i.caseId());
              }
            }
            store.resolveItem(id, i.caseId(), outcome, now);
          }
          store.complete(id, now);
          Map<String, Object> m = new LinkedHashMap<>();
          m.put("handoverId", id);
          m.put("fromOperatorId", h.fromOperatorId());
          m.put("toOperatorId", h.toOperatorId());
          m.put("openCases", accepted);
          m.put("notReceivedCases", notReceived);
          m.put("group", h.group());
          m.put("completedAt", now.toString());
          events.publish(
              "ShiftHandoverCompleted",
              1,
              AGGREGATE,
              id,
              h.version() + 1,
              new EventEnvelope.Actor(EventEnvelope.ActorType.OPERATOR, p.subject()),
              m);
          log.info("교대 수락 {} 받음 {} 미인수 {}", id, accepted.size(), notReceived.size());
          return id;
        });
    return view(store.find(id).orElseThrow());
  }

  public View cancel(String requestId, String sourceSystem, String id) {
    PmartPrincipal p = operator();
    idempotency.execute(
        new RequestKey(sourceSystem, requestId, "cancelShiftHandover:" + id, p.subject()),
        id,
        String.class,
        () -> {
          HandoverRow h = visible(p, store.lock(id).orElseThrow(() -> notFound(id)));
          if (!p.subject().equals(h.fromOperatorId()) && !p.hasRole(CaseRules.ADMIN)) {
            throw new PmartException(ErrorCode.FORBIDDEN, "전임·관리자만 취소합니다.");
          }
          if (!"PENDING".equals(h.state())) {
            throw PmartException.invalidState("진행 중인 인계서가 아닙니다(" + h.state() + ").");
          }
          store.cancel(id, p.subject(), now());
          log.info("교대 인계서 취소 {} by {}", id, p.subject());
          return id;
        });
    return view(store.find(id).orElseThrow());
  }

  public View get(String id) {
    PmartPrincipal p = operator();
    return view(visible(p, store.find(id).orElseThrow(() -> notFound(id))));
  }

  public Page list(@Nullable String state, boolean unreceived, int size, @Nullable String cursor) {
    PmartPrincipal p = operator();
    int limit = Math.min(Math.max(size, 1), 100);
    int offset = offset(cursor);
    List<HandoverRow> rows =
        store.page(
            p.hasRole(CaseRules.ADMIN) ? null : p.subject(),
            blankToNull(state),
            unreceived,
            offset,
            limit + 1);
    String next = rows.size() > limit ? String.valueOf(offset + limit) : null;
    return new Page(
        rows.subList(0, Math.min(rows.size(), limit)).stream().map(this::view).toList(), next);
  }

  private View view(HandoverRow h) {
    return new View(h, store.items(h.handoverId()), store.unreceived(h));
  }

  /** 전임·후임·관리자만 — 그 밖은 없는 것처럼. */
  private static HandoverRow visible(PmartPrincipal p, HandoverRow h) {
    if (p.hasRole(CaseRules.ADMIN)
        || p.subject().equals(h.fromOperatorId())
        || p.subject().equals(h.toOperatorId())) {
      return h;
    }
    throw notFound(h.handoverId());
  }

  private static PmartException notHandoverable(String caseId, String why) {
    return new PmartException(
        "NOT_HANDOVERABLE",
        HttpStatus.UNPROCESSABLE_CONTENT,
        "넘길 수 없는 건입니다(" + why + "): " + caseId,
        false,
        List.of());
  }

  private static PmartException notFound(String id) {
    return PmartException.notFound("SHIFT_HANDOVER_NOT_FOUND", "인계서를 찾을 수 없습니다: " + id);
  }

  private static PmartPrincipal operator() {
    return PmartPrincipal.current()
        .filter(x -> x.type() == PmartPrincipal.PrincipalType.OPERATOR)
        .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "직원만 교대 인계를 합니다."));
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
