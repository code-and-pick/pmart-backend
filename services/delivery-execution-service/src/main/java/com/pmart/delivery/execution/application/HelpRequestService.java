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

  /** 조회 결과 — staff 면 처리한 직원·메모까지 보여 준다(라이더에게는 숨김). */
  public record HelpView(HelpRow help, boolean staff) {}

  /** 처리함 닫기 결과(멱등 기록용). */
  public record Resolved(String deliveryId, String requestId, Instant resolvedAt) {}

  /** 대화 조회 결과 — staff 면 상담원 ID 를 보인다. */
  public record Thread(HelpRow help, List<ExecutionStore.HelpMessageRow> messages, boolean staff) {}

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
                      now,
                      null,
                      null,
                      null);
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

  /** 요청한 라이더 본인, 그 센터 운영자·OPERATOR_ADMIN·CS_AGENT·RIDER_OPS. 볼 수 없으면 404. */
  public HelpView get(String deliveryId, String requestId) {
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
    return new HelpView(h, p.type() != PmartPrincipal.PrincipalType.RIDER);
  }

  /** 배송별 도움요청 목록(0.7.0, 라이더 앱 요청 §10). 라이더는 본인 것만, 직원은 {@link #get} 과 같은 범위. 볼 수 없는 배송이면 404. */
  public List<HelpView> list(String deliveryId) {
    PmartPrincipal p =
        PmartPrincipal.current().orElseThrow(() -> ExecutionService.forbidden("인증이 필요합니다."));
    DeliveryRow d =
        store
            .delivery(deliveryId)
            .orElseThrow(() -> PmartException.notFound("DELIVERY_NOT_FOUND", "배송이 없습니다."));
    boolean rider = p.type() == PmartPrincipal.PrincipalType.RIDER;
    if (!rider
        && p.type() != PmartPrincipal.PrincipalType.SERVICE
        && !Access.operatorCanSee(p, d.mfcId())) {
      throw PmartException.notFound("DELIVERY_NOT_FOUND", "배송이 없습니다.");
    }
    return store.helps(deliveryId).stream()
        .filter(h -> !rider || p.subject().equals(h.riderId()))
        .map(h -> new HelpView(h, !rider))
        .toList();
  }

  /** 도움요청 대화(0.6.0, 라이더 앱 요청 §11-3) — 볼 수 있는 사람은 {@link #get} 과 같다. after 뒤 메시지만(200개까지). */
  public Thread messages(String deliveryId, String requestId, long after) {
    HelpView v = get(deliveryId, requestId);
    return new Thread(
        v.help(), store.helpMessages(deliveryId, requestId, Math.max(0, after)), v.staff());
  }

  /**
   * 대화에 메시지 보내기(0.6.0). 라이더는 요청한 본인(사진은 이 배송에 본인이 올린 것만), 직원은 처리 권한({@link
   * Access#operatorCanDecide}). 처리된 도움요청은 409 HELP_REQUEST_CLOSED. 내용은 로그에 남기지 않는다.
   */
  public Thread postMessage(
      String deliveryId,
      String requestId,
      String idempotencyKey,
      String source,
      String body,
      List<String> photoRefs) {
    PmartPrincipal p =
        PmartPrincipal.current().orElseThrow(() -> ExecutionService.forbidden("인증이 필요합니다."));
    String text = body == null ? "" : body.strip();
    if (text.isEmpty()) {
      throw new PmartException(
          com.pmart.common.web.ErrorCode.VALIDATION_FAILED, "메시지 내용을 입력해 주세요.");
    }
    String messageId =
        idempotency
            .execute(
                new RequestKey(source, idempotencyKey, "postHelpRequestMessage", p.subject()),
                Map.of(
                    "deliveryId", deliveryId,
                    "requestId", requestId,
                    "body", text,
                    "photoRefs", photoRefs),
                String.class,
                () -> {
                  HelpRow h =
                      store
                          .lockHelp(deliveryId, requestId)
                          .orElseThrow(
                              () ->
                                  PmartException.notFound("HELP_REQUEST_NOT_FOUND", "도움요청이 없습니다."));
                  DeliveryRow delivery = store.delivery(deliveryId).orElse(null);
                  String mfcId = delivery == null ? null : delivery.mfcId();
                  String senderType;
                  if (p.type() == PmartPrincipal.PrincipalType.RIDER) {
                    if (!p.subject().equals(h.riderId())) {
                      throw PmartException.notFound("HELP_REQUEST_NOT_FOUND", "도움요청이 없습니다.");
                    }
                    if (store.ownedPhotos(deliveryId, p.subject(), photoRefs)
                        != new java.util.HashSet<>(photoRefs).size()) {
                      throw new PmartException(
                          "PHOTO_NOT_FOUND",
                          org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT,
                          "이 배송에 올린 사진만 붙일 수 있습니다.",
                          false,
                          List.of());
                    }
                    senderType = "RIDER";
                  } else {
                    if (!Access.operatorCanSee(p, mfcId)) {
                      throw PmartException.notFound("HELP_REQUEST_NOT_FOUND", "도움요청이 없습니다.");
                    }
                    if (!Access.operatorCanDecide(p, mfcId)) {
                      throw ExecutionService.forbidden("도움요청 대화에 답할 권한이 없습니다(센터 관리자·고객센터·라이더 운영).");
                    }
                    if (!photoRefs.isEmpty()) {
                      throw new PmartException(
                          com.pmart.common.web.ErrorCode.VALIDATION_FAILED, "직원은 사진을 붙이지 않습니다.");
                    }
                    senderType = "SUPPORT";
                  }
                  if ("RESOLVED".equals(h.state())) {
                    throw new PmartException(
                        "HELP_REQUEST_CLOSED",
                        org.springframework.http.HttpStatus.CONFLICT,
                        "처리가 끝난 도움요청입니다. 새로 도움요청을 보내 주세요.",
                        false,
                        List.of());
                  }
                  String id = "HM-" + java.util.UUID.randomUUID();
                  var saved =
                      store.insertHelpMessage(
                          id,
                          deliveryId,
                          requestId,
                          senderType,
                          p.subject(),
                          text,
                          mapper.writeValueAsString(photoRefs),
                          clock.instant());
                  if ("SUPPORT".equals(senderType)) {
                    // 라이더 알림함(§11-5) — 같은 트랜잭션의 outbox
                    events.helpReplied(
                        deliveryId,
                        requestId,
                        h.riderId(),
                        saved.seq(),
                        saved.createdAt(),
                        delivery == null ? 0 : delivery.version(),
                        p.subject());
                  }
                  log.info("배송 {} 도움요청 {} 대화 — {}", deliveryId, requestId, senderType);
                  return id;
                })
            .value();
    HelpView v = get(deliveryId, requestId);
    return new Thread(
        v.help(),
        store.helpMessage(messageId).map(List::of).orElse(List.of()),
        p.type() != PmartPrincipal.PrincipalType.RIDER);
  }

  /**
   * 도움요청 처리함으로 닫기(0.4.0, BOD-4 — 지원센터 사건함 전까지). 권한은 전달 실패 결정과 같다({@link Access#operatorCanDecide}).
   * 배송 상태·버전은 그대로, 사건도 내지 않는다. 메모는 로그에 남기지 않는다.
   */
  public HelpView resolve(
      String deliveryId,
      String requestId,
      String idempotencyKey,
      String source,
      @Nullable String note) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .filter(x -> x.type() == PmartPrincipal.PrincipalType.OPERATOR)
            .orElseThrow(() -> ExecutionService.forbidden("직원만 도움요청을 닫을 수 있습니다."));
    String memo = note == null || note.isBlank() ? null : note.strip();
    idempotency.execute(
        new RequestKey(source, idempotencyKey, "resolveHelpRequest", p.subject()),
        Map.of("deliveryId", deliveryId, "requestId", requestId, "note", String.valueOf(memo)),
        Resolved.class,
        () -> {
          HelpRow h =
              store
                  .lockHelp(deliveryId, requestId)
                  .orElseThrow(
                      () -> PmartException.notFound("HELP_REQUEST_NOT_FOUND", "도움요청이 없습니다."));
          String mfcId = store.delivery(deliveryId).map(DeliveryRow::mfcId).orElse(null);
          if (!Access.operatorCanSee(p, mfcId)) {
            throw PmartException.notFound("HELP_REQUEST_NOT_FOUND", "도움요청이 없습니다.");
          }
          if (!Access.operatorCanDecide(p, mfcId)) {
            throw ExecutionService.forbidden("도움요청을 닫을 권한이 없습니다(센터 관리자·고객센터·라이더 운영).");
          }
          if (h.caseId() != null && !"RESOLVED".equals(h.state())) {
            // DE-CASE(2026-11-16): 사건함 사건이 붙은 도움요청은 사건함에서 닫는다(사건 닫힘 → 여기도 처리됨)
            throw new PmartException(
                "HELP_REQUEST_HAS_CASE",
                org.springframework.http.HttpStatus.CONFLICT,
                "사건함 사건(" + h.caseId() + ")에서 닫아 주세요.",
                false,
                java.util.List.of());
          }
          Instant now = clock.instant();
          if (!store.resolveHelp(deliveryId, requestId, p.subject(), memo, now)) {
            throw PmartException.invalidState("이미 처리함으로 닫힌 도움요청입니다(지금 " + h.state() + ").");
          }
          log.info("배송 {} 도움요청 {} 처리함", deliveryId, requestId);
          return new Resolved(deliveryId, requestId, now);
        });
    HelpRow closed =
        store
            .help(deliveryId, requestId)
            .orElseThrow(() -> PmartException.notFound("HELP_REQUEST_NOT_FOUND", "도움요청이 없습니다."));
    return new HelpView(closed, true);
  }

  /**
   * 도움요청 대기 목록(0.4.0, BOD-4 — 오래된 것 먼저). OPERATOR_ADMIN·CS_AGENT·RIDER_OPS 는 모든 센터, 그 밖 센터 직원은
   * mfcId 를 주어야 하고 그 센터 권한이 있어야 한다.
   */
  public List<ExecutionStore.StaffHelpRow> staffList(
      ExecutionStore.HelpFilter filter, int limit, int offset) {
    Access.requireStaffScope(filter.mfcId());
    return store.staffHelps(filter, limit, offset);
  }
}
