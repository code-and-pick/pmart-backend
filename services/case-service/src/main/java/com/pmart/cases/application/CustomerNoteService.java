package com.pmart.cases.application;

import com.pmart.cases.domain.CaseRules;
import com.pmart.cases.infrastructure.CustomerNoteStore;
import com.pmart.cases.infrastructure.CustomerNoteStore.NoteRow;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 고객 상담 메모(2026-11-23 R-13, 사용자 "상담메모는 상담사가 쓸 수 있겠지. 수정은 적은 사람만 가능하고 삭제도 적은 사람만 가능함").
 *
 * <ul>
 *   <li>쓰기: 상담원(CS_AGENT) 만. 보기: 상담원·관리자.
 *   <li>고치기·지우기: 쓴 사람 본인만(관리자도 안 됨). 지우면 글만 DB 에서 지우고 누가·언제는 남긴다(ADR-0062).
 *   <li>이벤트 없음, 글은 로그에 남기지 않는다. 고객 ID 는 확인하지 않는다(customer 를 부르지 않음).
 * </ul>
 */
@Service
public class CustomerNoteService {

  public record Page(List<NoteRow> items, @Nullable String nextCursor) {}

  private static final Logger log = LoggerFactory.getLogger(CustomerNoteService.class);

  private final CustomerNoteStore store;
  private final RequestIdempotency idempotency;
  private final Clock clock;

  public CustomerNoteService(CustomerNoteStore store, RequestIdempotency idempotency, Clock clock) {
    this.store = store;
    this.idempotency = idempotency;
    this.clock = clock;
  }

  public NoteRow add(String requestId, String sourceSystem, String customerId, String text) {
    PmartPrincipal p = operator();
    if (!p.hasRole(CaseRules.CS_AGENT)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "고객 상담 메모는 상담원(CS_AGENT)만 남깁니다.");
    }
    String customer = required(customerId, "customerId(고객)");
    String body = required(text, "text(메모)");
    String id =
        idempotency
            .execute(
                new RequestKey(sourceSystem, requestId, "createCustomerNote", p.subject()),
                customer + "\n" + body,
                String.class,
                () -> {
                  String noteId = "CNOTE-" + UUID.randomUUID();
                  store.insert(new NoteRow(noteId, customer, body, p.subject(), now(), null, null));
                  log.info("고객 메모 {} by {}", noteId, p.subject());
                  return noteId;
                })
            .value();
    return store.find(id).orElseThrow();
  }

  public Page list(String customerId, @Nullable String cursor, int size) {
    PmartPrincipal p = operator();
    if (!p.hasRole(CaseRules.CS_AGENT) && !p.hasRole(CaseRules.ADMIN)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "고객 상담 메모는 상담원·관리자만 봅니다.");
    }
    int n = Math.min(Math.max(size, 1), 50);
    List<NoteRow> rows = store.page(required(customerId, "customerId(고객)"), blankToNull(cursor), n);
    boolean more = rows.size() > n;
    List<NoteRow> items = more ? rows.subList(0, n) : rows;
    return new Page(List.copyOf(items), more ? items.get(items.size() - 1).noteId() : null);
  }

  @Transactional
  public NoteRow revise(String requestId, String sourceSystem, String noteId, String text) {
    PmartPrincipal p = operator();
    String body = required(text, "text(메모)");
    idempotency.execute(
        new RequestKey(sourceSystem, requestId, "reviseCustomerNote:" + noteId, p.subject()),
        body,
        String.class,
        () -> {
          NoteRow n = ownLiveNote(p, noteId);
          store.revise(n.noteId(), body, now());
          log.info("고객 메모 고침 {} by {}", noteId, p.subject());
          return noteId;
        });
    return store.find(noteId).orElseThrow();
  }

  @Transactional
  public NoteRow delete(String requestId, String sourceSystem, String noteId) {
    PmartPrincipal p = operator();
    idempotency.execute(
        new RequestKey(sourceSystem, requestId, "deleteCustomerNote:" + noteId, p.subject()),
        noteId,
        String.class,
        () -> {
          NoteRow n = store.lock(noteId).orElseThrow(() -> notFound(noteId));
          requireAuthor(p, n);
          if (n.deletedAt() == null) {
            store.delete(noteId, now());
            log.info("고객 메모 지움 {} by {}", noteId, p.subject());
          }
          return noteId;
        });
    return store.find(noteId).orElseThrow();
  }

  private NoteRow ownLiveNote(PmartPrincipal p, String noteId) {
    NoteRow n =
        store.lock(noteId).filter(x -> x.deletedAt() == null).orElseThrow(() -> notFound(noteId));
    requireAuthor(p, n);
    return n;
  }

  private static void requireAuthor(PmartPrincipal p, NoteRow n) {
    if (!n.authorId().equals(p.subject())) {
      throw new PmartException(ErrorCode.FORBIDDEN, "메모는 쓴 사람만 고치거나 지울 수 있습니다.");
    }
  }

  private static PmartException notFound(String noteId) {
    return PmartException.notFound("NOTE_NOT_FOUND", "메모를 찾을 수 없습니다: " + noteId);
  }

  private static String required(@Nullable String v, String what) {
    if (v == null || v.isBlank()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, what + "이(가) 필요합니다.");
    }
    return v.strip();
  }

  private static @Nullable String blankToNull(@Nullable String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }

  private static PmartPrincipal operator() {
    return PmartPrincipal.current()
        .filter(x -> x.type() == PmartPrincipal.PrincipalType.OPERATOR)
        .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "직원만 메모를 다룹니다."));
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }
}
