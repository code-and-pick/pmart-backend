package com.pmart.cases.application;

import com.pmart.cases.domain.CaseRules;
import com.pmart.cases.infrastructure.CaseStore;
import com.pmart.cases.infrastructure.CaseStore.CaseRow;
import com.pmart.cases.infrastructure.CaseStore.Ref;
import com.pmart.cases.infrastructure.DispatchClient;
import com.pmart.cases.infrastructure.ObligationStore;
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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
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
      @Nullable String caseNumber,
      boolean unassigned,
      boolean overdue) {}

  /**
   * @param fault 귀책 CUSTOMER·RIDER·SYSTEM(ADR-0065, 2026-11-16) — 없으면 예전 처리(회수 + 재발송)
   * @param resolution 고객 선택 RESHIP·REFUND·REDELIVER — 없으면 귀책별 기본
   * @param redeliveryDestinationId CUSTOMER·REDELIVER 의 실제 주소(order 기록 ID)
   */
  public record MisdeliveryCommand(
      String deliveryId,
      String summary,
      @Nullable String centerId,
      List<String> evidenceRefs,
      @Nullable String fault,
      @Nullable String resolution,
      @Nullable String redeliveryDestinationId) {}

  private final CaseStore store;
  private final EventPublisher events;
  private final RequestIdempotency idempotency;
  private final DispatchClient dispatch;
  private final ObligationStore obligations;
  private final CaseProperties props;
  private final Clock clock;

  public CaseInboxService(
      CaseStore store,
      EventPublisher events,
      RequestIdempotency idempotency,
      DispatchClient dispatch,
      ObligationStore obligations,
      CaseProperties props,
      Clock clock) {
    this.store = store;
    this.events = events;
    this.idempotency = idempotency;
    this.dispatch = dispatch;
    this.obligations = obligations;
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

  /**
   * R-9(2026-11-19 — 원문 Delivery §3.6 "배차지연시간이 일정기준을 넘으면 MFC관리자·고객센터에 안내", 사용자 "1,2,3 전부"):
   * dispatch {@code DispatchDelayed}(배송마다 한 번) → 상담원 팀 사건 DISPATCH_DELAYED. 센터 = mfcId(센터 관리자도 자기
   * 센터만 보기), 주문을 refs 로(고객 전화 때 주문으로 찾기). 같은 배송에 열린 배차 실패(DISPATCH_FAILED — 지원센터)가 있어도 따로 연다 — 팀이
   * 다르다(지원센터는 배차, 상담원은 고객 안내·취소). 같은 배송으로 열린 지연 사건이 있으면 그대로(dedupe). 고객 안내는 notification, 취소는 claim
   * "배차 불가 취소"(사건은 부르지 않음).
   */
  public void onDispatchDelayed(EventEnvelope e) {
    JsonNode p = e.payload();
    String deliveryId = text(p, "deliveryId");
    if (deliveryId == null) {
      throw new IllegalArgumentException("deliveryId 없음"); // 계약 위반 → DLT
    }
    List<Ref> refs = new ArrayList<>();
    refs.add(new Ref("deliveryId", deliveryId));
    addRef(refs, "orderId", text(p, "orderId"));
    int minutes = p.path("delayedMinutes").asInt(0);
    String summary =
        "배차 지연 — 약속보다 "
            + minutes
            + "분 늦음, 고객 안내·취소 문의 대비(기준 "
            + p.path("threshold").asInt(0)
            + "분)";
    openFromEvent(
        e,
        CaseRules.DISPATCH_DELAYED,
        "P2",
        "DELIVERY",
        deliveryId,
        text(p, "mfcId"),
        summary,
        refs,
        "DISPATCH_DELAYED:" + deliveryId);
  }

  /**
   * CS-MFC-Q(2026-11-20 사용자 "추천대로" — PC 3 요청, MB-2 2차 R6): mfc-quality {@code
   * QualityIncidentImpactAssessed}(영향 LOT 이 고객에게 나간 주문, 판단이 바뀔 때마다 전체 목록) → 영향 주문마다 상담원 팀 사건
   * MFC_QUALITY_IMPACT(subject ORDER, 센터 = mfcId — 센터 관리자는 보기만). dedupe {@code
   * MFC_QUALITY:<사고>:<주문>} 로 <b>닫힌 것까지</b> 한 번만 연다 — 다음 판단에서 늘어난 주문만 새로, 이미 연(또는 닫은) 주문은 그대로. 0건이면
   * 아무것도 안 한다. 사건 ID 는 {@code CaseOpened}(type MFC_QUALITY_IMPACT, refs incidentId·orderId)로
   * mfc-quality 가 받는다. 고객 ID 는 이벤트에 없어 싣지 않는다(상담원은 주문으로 찾음). 연락·환불은 상담원이 각 메뉴에서 — case 는 부르지 않고 자동
   * 종결 없음.
   */
  public void onQualityImpactAssessed(EventEnvelope e) {
    JsonNode p = e.payload();
    String incidentId = text(p, "incidentId");
    if (incidentId == null) {
      throw new IllegalArgumentException("incidentId 없음"); // 계약 위반 → DLT
    }
    if (p.path("affectedOrderCount").asInt(0) <= 0) {
      return;
    }
    String mfcId = text(p, "mfcId");
    String what = CaseRules.qualityKindName(text(p, "kind"));
    for (JsonNode order : p.path("affectedOrders")) {
      String orderId = text(order, "orderId");
      if (orderId == null) {
        continue;
      }
      String key = "MFC_QUALITY:" + incidentId + ":" + orderId;
      if (store.lockLatestByDedupe(key).isPresent()) {
        continue; // 이미 연 주문(닫힌 것 포함) — 그대로
      }
      List<Ref> refs = new ArrayList<>();
      refs.add(new Ref("incidentId", incidentId));
      refs.add(new Ref("orderId", orderId));
      List<String> lines = new ArrayList<>();
      for (JsonNode line : order.path("lines")) {
        String lotId = text(line, "lotId");
        if (lotId != null && refs.stream().noneMatch(r -> r.refId().equals(lotId))) {
          refs.add(new Ref("lotId", lotId));
        }
        if (lines.size() < 10) {
          lines.add(
              (text(line, "sku") == null ? "-" : text(line, "sku"))
                  + " "
                  + line.path("qty").asInt(0)
                  + "개");
        }
      }
      String summary =
          "품질 사고(" + what + ") 상품이 이 주문으로 나감 — " + String.join(", ", lines) + ". 고객 연락·환불 판단";
      openFromEvent(
          e,
          CaseRules.MFC_QUALITY_IMPACT,
          "P2",
          "ORDER",
          orderId,
          mfcId,
          summary,
          refs,
          key,
          UUID.nameUUIDFromBytes(
                  (e.eventId() + ":" + orderId).getBytes(java.nio.charset.StandardCharsets.UTF_8))
              .toString());
    }
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

  /**
   * R-8(2026-11-17 사용자 — 상담원 팀·1시간·사람이 닫기): 반품 회수 실패(claim ReturnRecoveryFailed — 두 번 실패 또는 상담원 재요청
   * 뒤 실패) → RETURN_RECOVERY_FAILED. 클레임마다 열린 사건 하나, 열려 있으면 이력(재발)만. 닫은 뒤 또 실패하면 새 사건(이전 사건 연결).
   * 조치(다시 회수·반품 취소)는 상담원이 claim {@code POST /claims/{id}/recovery-actions} 로 하고, 클레임이 바뀌어도 자동으로 닫지
   * 않는다. 주소·연락처는 이벤트에도 사건에도 없다.
   */
  public void onReturnRecoveryFailed(EventEnvelope e) {
    JsonNode p = e.payload();
    String claimId = text(p, "claimId");
    if (claimId == null) {
      throw new IllegalArgumentException("claimId 없음"); // 계약 위반 → DLT
    }
    String note = returnRecoveryNote(p);
    String key = "RETURN_RECOVERY:" + claimId;
    var open = store.lockOpenByDedupe(key);
    if (open.isPresent()) {
      store.touch(open.get().caseId(), now());
      store.history(open.get().caseId(), now(), SYSTEM, "RECURRED", null, null, note);
      log.info("반품 회수 실패 재발 — 사건 {} 에 이력만", open.get().caseId());
      return;
    }
    List<Ref> refs = new ArrayList<>();
    refs.add(new Ref("claimId", claimId));
    addRef(refs, "orderId", text(p, "orderId"));
    addRef(refs, "customerId", text(p, "accountId"));
    addRef(refs, "lastDeliveryId", text(p, "lastDeliveryId"));
    openFromEvent(
        e,
        CaseRules.RETURN_RECOVERY_FAILED,
        "P2",
        "CLAIM",
        claimId,
        text(p, "mfcId"),
        note,
        refs,
        key);
  }

  /**
   * 반품이 끝남(ClaimClosed, 2026-11-18 사용자 "1, 2, 3 다 진행해줘"): 그 클레임의 반품 회수 실패 사건이 열려 있으면 이력
   * CLAIM_CLOSED 만 남긴다 — 상담원이 이미 끝난 반품을 붙들지 않게. **사건은 닫지 않는다**(R-8 "사람이 닫기"). 열린 사건이 없으면 아무것도 안
   * 한다(새 사건 없음).
   */
  public void onClaimClosed(EventEnvelope e) {
    JsonNode p = e.payload();
    String claimId = text(p, "claimId");
    if (claimId == null) {
      throw new IllegalArgumentException("claimId 없음"); // 계약 위반 → DLT
    }
    var open = store.lockOpenByDedupe("RETURN_RECOVERY:" + claimId);
    if (open.isEmpty()) {
      return;
    }
    store.touch(open.get().caseId(), now());
    store.history(
        open.get().caseId(), now(), SYSTEM, "CLAIM_CLOSED", null, null, claimClosedNote(p));
    log.info("반품 끝남 — 사건 {} 에 이력만(닫지 않음)", open.get().caseId());
  }

  static String claimClosedNote(JsonNode p) {
    String outcome = text(p, "outcome");
    String what =
        switch (outcome == null ? "" : outcome) {
          case "RETURN_REFUNDED" -> "검수·환불 완료";
          case "RETURN_SHORT_CLOSED" -> "받은 만큼만 환불하고 끝냄";
          case "RETURN_CANCELLED" -> "상담원이 반품 취소";
          case "RETURN_FEE_UNPAID" -> "회수비 미결제로 취소";
          case "RETURN_EXPIRED" -> "반품 기한이 지나 자동 취소";
          default -> orDash(outcome);
        };
    return "반품이 끝났어요 — " + what + ". 고객 연락이 끝났으면 완료 처리하세요";
  }

  /**
   * 반품 회수 실패 메모. claim 0.3.0(CL-1 추가 결정, 2026-11-17)의 countedAttempts·retryAllowed·returnDeadlineAt
   * 이 있으면 쓴다 — 우리 잘못(RIDER_INCIDENT)은 횟수에 들지 않으므로 attempts(요청 수)보다 countedAttempts 가 맞다. 없으면(이전 발행)
   * 예전 문구. 상담원이 "다시 회수 요청"을 할 수 있는지 메모만 보고 알게 한다(판정은 claim 이 하고 여기선 옮기기만).
   */
  static String returnRecoveryNote(JsonNode p) {
    boolean counted = p.path("countedAttempts").isNumber();
    StringBuilder b = new StringBuilder("반품 회수 ");
    if (counted) {
      b.append(p.path("countedAttempts").asInt()).append("번 실패");
    } else {
      b.append(p.path("attempts").asInt(0)).append("번 요청 뒤 실패");
    }
    b.append(" — 마지막 사유 ").append(orDash(text(p, "lastFailureReason")));
    JsonNode retry = p.path("retryAllowed");
    if (retry.isBoolean()) {
      if (retry.asBoolean()) {
        b.append(", 다시 회수 요청 가능");
        String deadline = text(p, "returnDeadlineAt");
        if (deadline != null) {
          try {
            b.append("(반품 기한 ").append(KST_SHORT.format(Instant.parse(deadline))).append(')');
          } catch (DateTimeParseException ignored) {
            // 형식이 틀리면 기한만 뺀다
          }
        }
      } else {
        b.append(", 다시 회수 불가 — 반품 취소만");
      }
    }
    return b.append(", 고객에게 다시 연락").toString();
  }

  /** 메모에 쓰는 한국 시각(ADR-0010 — 표시만). */
  private static final DateTimeFormatter KST_SHORT =
      DateTimeFormatter.ofPattern("M월 d일 HH:mm").withZone(ZoneId.of("Asia/Seoul"));

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
    openFromEvent(
        e,
        kind,
        priority,
        subjectType,
        subjectRef,
        centerId,
        summary,
        refs,
        dedupeKey,
        e.eventId().toString());
  }

  /**
   * {@code sourceEventId} — 사건 하나에 사건이 하나면 이벤트 ID, 한 이벤트로 여럿을 열면(품질 사고 영향 주문) 주문마다 다른 값(원천 이벤트 유일
   * 색인 V4 를 지키려고 — 같은 이벤트를 다시 받아도 같은 값).
   */
  private void openFromEvent(
      EventEnvelope e,
      String kind,
      String priority,
      String subjectType,
      String subjectRef,
      @Nullable String centerId,
      String summary,
      List<Ref> refs,
      String dedupeKey,
      String sourceEventId) {
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
            sourceEventId,
            1,
            now,
            now,
            null,
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

  /** 사고·파손 재발송의 근거 ID 머리(ADR-0069 — order-orchestration 이 `DF-<실패한 배송 ID>` 로 싣는다). */
  static final String FAILURE_RESHIPMENT_PREFIX = "DF-";

  /**
   * 오배송 재발송(2026-11-16 사용자 "진행해 줘" — PC 5·PC 1 의 재발송 #39~#41 과 잇기): 재발송 배송 생성(DeliveryCreated,
   * purpose MISDELIVERY_RESHIPMENT, caseId)을 그 오배송 사건에 — refs {@code reshipmentDeliveryId}(한 번만)와
   * 이력 RESHIPMENT_CREATED. 상태는 그대로, 자동 종결 없음. 사고·파손 재발송(ADR-0069, 2026-11-18)은 caseId 자리에 {@code
   * DF-<실패한 배송 ID>} 가 오므로 그 배송의 전달 실패 사건(없으면 도움요청 사건)에 같은 방식으로 붙인다 — 닫힌 사건이어도 이력만 남기고 상태는 그대로. 재발송
   * 작업(MFC — mfc.fulfillment.v1)은 구독하지 않는다(바인딩 변경 필요 — 지금은 배송 상태로 본다).
   */
  private void onReshipmentCreated(EventEnvelope e, JsonNode p) {
    String caseId = text(p, "caseId");
    String reshipment = text(p, "deliveryId");
    if (caseId == null || reshipment == null) {
      log.warn("재발송 {} — caseId·deliveryId 없음, 무시", e.eventType());
      return;
    }
    boolean fromFailure = caseId.startsWith(FAILURE_RESHIPMENT_PREFIX);
    var found =
        fromFailure
            ? store.lockIncidentOfDelivery(caseId.substring(FAILURE_RESHIPMENT_PREFIX.length()))
            : store.lock(caseId);
    if (found.isEmpty()) {
      log.warn("재발송 — 사건 {} 없음, 무시", caseId);
      return;
    }
    CaseRow c = found.get();
    caseId = c.caseId();
    Instant now = now();
    if (c.refs().stream()
        .noneMatch(
            r -> "reshipmentDeliveryId".equals(r.refType()) && reshipment.equals(r.refId()))) {
      List<Ref> refs = new ArrayList<>(c.refs());
      refs.add(new Ref("reshipmentDeliveryId", reshipment));
      store.replaceRefs(caseId, refs, now);
    } else {
      store.touch(caseId, now);
    }
    store.history(
        caseId,
        now,
        SYSTEM,
        "RESHIPMENT_CREATED",
        null,
        null,
        (fromFailure ? "사고·파손으로 새 상품 재발송 — 배송 " : "재발송 배송 ") + reshipment);
    log.info("재발송 배송 — 사건 {}", caseId);
  }

  /**
   * 오배송 회수 진행(2026-11-16 사용자 "진행해" — DD-2 와 잇기): delivery-dispatch·execution 의 회수 배송 사건(purpose
   * MISDELIVERY_RECOVERY, caseId 실림)을 그 오배송 사건에 — 회수 배송 번호를 refs {@code recoveryDeliveryId} 로,
   * 생성·회수함·매장 반납을 이력으로. 사건 상태는 바꾸지 않고 자동으로 닫지 않는다(사건은 알림·기록). 후속 처리(의무)도 자동으로 해소하지 않는다. 다른 회수(고객
   * 반품)·일반 배송은 버린다. 그 사건이 없으면 무시(로그만).
   */
  public void onRecoveryProgress(EventEnvelope e) {
    JsonNode p = e.payload();
    if ("MISDELIVERY_RESHIPMENT".equals(text(p, "purpose"))
        && "DeliveryCreated".equals(e.eventType())) {
      onReshipmentCreated(e, p);
      return;
    }
    if (!"MISDELIVERY_RECOVERY".equals(text(p, "purpose"))) {
      return;
    }
    String caseId = text(p, "caseId");
    if (caseId == null) {
      log.warn("회수 진행 {} — caseId 없음, 무시", e.eventType());
      return;
    }
    var found = store.lock(caseId);
    if (found.isEmpty()) {
      log.warn("회수 진행 {} — 사건 {} 없음, 무시", e.eventType(), caseId);
      return;
    }
    CaseRow c = found.get();
    Instant now = now();
    String recovery = text(p, "deliveryId");
    if (recovery != null
        && c.refs().stream()
            .noneMatch(
                r -> "recoveryDeliveryId".equals(r.refType()) && recovery.equals(r.refId()))) {
      List<Ref> refs = new ArrayList<>(c.refs());
      refs.add(new Ref("recoveryDeliveryId", recovery));
      store.replaceRefs(caseId, refs, now);
    } else {
      store.touch(caseId, now);
    }
    String action;
    String note;
    switch (e.eventType()) {
      case "DeliveryCreated" -> {
        action = "RECOVERY_CREATED";
        note = "회수 배송 " + orDash(recovery);
      }
      case "ReturnPickedUp" -> {
        action = "RECOVERY_PICKED_UP";
        note = "회수함 " + orDash(recovery);
      }
      default -> {
        action = "RECOVERY_RETURNED";
        note = "매장 반납 " + orDash(text(p, "mfcId"));
      }
    }
    store.history(caseId, now, SYSTEM, action, null, null, note);
    log.info("회수 진행 {} — 사건 {}", action, caseId);
  }

  // ---------------------------------------------------------------- 직원 명령

  /** R-7: 오배송 확인 기록 → MISDELIVERY 사건 + MisdeliveryConfirmed(회수 배차 DD-2). */
  public Detail createMisdelivery(String requestId, String sourceSystem, MisdeliveryCommand cmd) {
    PmartPrincipal p = requireAny(CaseRules.CREATE_MISDELIVERY_ROLES, "오배송 확인 기록");
    String deliveryId = cmd.deliveryId().strip();
    MisdeliveryDecision decision =
        misdeliveryDecision(
            cmd.fault(), cmd.resolution(), blankToNull(cmd.redeliveryDestinationId()));
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
                  if (decision != null) {
                    refs.add(new Ref("fault", decision.fault()));
                    refs.add(new Ref("resolution", decision.resolution()));
                    addRef(refs, "redeliveryDestinationId", decision.redeliveryDestinationId());
                  }
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
                  if (decision != null) {
                    m.put("fault", decision.fault());
                    m.put("resolution", decision.resolution());
                    if (decision.redeliveryDestinationId() != null) {
                      m.put("redeliveryDestinationId", decision.redeliveryDestinationId());
                    }
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
                q.caseNumber(),
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
          if (!CaseRules.canWork(roles, c.kind(), c.assigneeGroup())) {
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
          if (!CaseRules.canWork(roles, c.kind(), c.assigneeGroup())) {
            throw new PmartException(ErrorCode.FORBIDDEN, "이 사건을 닫을 권한이 없습니다.");
          }
          if ("CLOSED".equals(c.state())) {
            return caseId; // 이미 닫힘
          }
          if (obligations.countOpen(caseId) > 0) {
            throw new PmartException(
                "OPEN_OBLIGATIONS",
                HttpStatus.CONFLICT,
                "열린 의무가 있어 닫을 수 없습니다 — 의무를 해소하거나 관리자가 면제·실패로 끝낸 뒤 닫으세요.",
                false,
                List.of());
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
          if (!CaseRules.canWork(roles, c.kind(), c.assigneeGroup())) {
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

  /**
   * 이관(0.7.0 — 2026-11-16 사용자 "A안", 업무 원문 §5.3): 배송 쪽 사건만 지원센터 팀 ↔ 관리자 팀. 지금 그 사건을 다룰 수 있는 사람이, 남이
   * 맡은 사건은 관리자만. 담당 비움, 기한·보이는 범위 그대로. 센터 이관·병합·그 밖 팀은 422.
   */
  public Detail transfer(
      String requestId,
      String sourceSystem,
      String caseId,
      String toGroup,
      @Nullable String toCenterId,
      @Nullable String mergeIntoCaseId,
      String reason,
      @Nullable Integer expectedVersion) {
    PmartPrincipal p = operator();
    Set<String> roles = roles(p);
    if (reason == null || reason.isBlank()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "reason(이관 사유)이 필요합니다.");
    }
    if (blankToNull(toCenterId) != null
        || blankToNull(mergeIntoCaseId) != null
        || !CaseRules.TRANSFER_GROUPS.contains(toGroup)) {
      throw unsupported("지금은 배송 쪽 사건을 지원센터(DELIVERY_OPS)·관리자(ORDER_OPS) 팀 사이로만 넘깁니다(센터 이관·병합 없음).");
    }
    idempotency.execute(
        new RequestKey(sourceSystem, requestId, "transferCase:" + caseId, p.subject()),
        List.of(toGroup, reason),
        String.class,
        () -> {
          CaseRow c = lockVisible(p, caseId);
          checkVersion(c, expectedVersion);
          if (!CaseRules.transferable(c.kind())) {
            throw unsupported("이 종류의 사건은 넘기지 않습니다(사가·결제 사건은 관리자 팀).");
          }
          if (!CaseRules.canWork(roles, c.kind(), c.assigneeGroup())) {
            throw new PmartException(ErrorCode.FORBIDDEN, "이 사건을 넘길 권한이 없습니다.");
          }
          if ("CLOSED".equals(c.state())) {
            throw PmartException.invalidState("닫힌 사건입니다.");
          }
          if (toGroup.equals(c.assigneeGroup())) {
            throw PmartException.invalidState("이미 그 팀의 사건입니다.");
          }
          if (c.assigneeId() != null
              && !c.assigneeId().equals(p.subject())
              && !roles.contains(CaseRules.ADMIN)) {
            throw new PmartException(ErrorCode.FORBIDDEN, "다른 직원이 맡은 사건은 관리자만 넘길 수 있습니다.");
          }
          Instant now = now();
          store.transfer(caseId, toGroup, now);
          CaseRow after = store.find(caseId).orElseThrow();
          store.history(
              caseId,
              now,
              p.subject(),
              "TRANSFERRED",
              c.state(),
              after.state(),
              c.assigneeGroup() + " → " + toGroup + ": " + reason.strip());
          Map<String, Object> m = basePayload(after);
          m.put("assignee", null);
          m.put("fromAssignee", c.assigneeId());
          m.put("toAssignee", null);
          m.put("fromGroup", c.assigneeGroup());
          m.put("toGroup", toGroup);
          m.put("reason", reason.strip());
          m.put("transferredAt", now.toString());
          events.publish(
              "CaseTransferred",
              1,
              AGGREGATE,
              caseId,
              after.version(),
              new EventEnvelope.Actor(EventEnvelope.ActorType.OPERATOR, p.subject()),
              m);
          log.info("사건 이관 {} {} → {} by {}", caseId, c.assigneeGroup(), toGroup, p.subject());
          return caseId;
        });
    return detail(store.find(caseId).orElseThrow());
  }

  private static PmartException unsupported(String detail) {
    return new PmartException(
        "UNSUPPORTED_ACTION", HttpStatus.UNPROCESSABLE_CONTENT, detail, false, List.of());
  }

  /**
   * 교대 수락(0.9.0 — BO-27): 담당을 후임으로. 이력 HANDED_OVER(note = 전임 → 후임: 다음 조치), CaseAssigned. 잠금·권한·상태
   * 검사는 호출자 (ShiftHandoverService)가 한다.
   */
  public void handOver(CaseRow c, String toOperatorId, String actorId, String note) {
    Instant now = now();
    store.assign(c.caseId(), toOperatorId, "ASSIGNED", now);
    store.history(c.caseId(), now, actorId, "HANDED_OVER", c.state(), "ASSIGNED", note);
    CaseRow after = store.find(c.caseId()).orElseThrow();
    Map<String, Object> m = basePayload(after);
    m.put("assignee", toOperatorId);
    m.put("previousAssignee", c.assigneeId());
    m.put("assignedAt", now.toString());
    events.publish(
        "CaseAssigned",
        1,
        AGGREGATE,
        c.caseId(),
        after.version(),
        new EventEnvelope.Actor(EventEnvelope.ActorType.OPERATOR, actorId),
        m);
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

  /**
   * R-10 (나)(2026-11-19 사용자 "추천대로" — 자동 재개는 하지 않는다, 2026-11-16 "재개는 사람이 손으로만" 유지): 라이더가 닫힌 도움요청을 다시
   * 열면(execution {@code HelpRequestReopened}) 그 도움요청의 접수 건에 이력 RIDER_REOPENED 만 남기고 updated_at 을
   * 올린다(목록에서 바뀐 건으로 보이게). 접수 건이 닫혀 있어도 상태는 그대로 — 지원센터가 이력을 보고 직접 다시 처리하기. 찾는 순서: payload caseId,
   * 없으면 incidentId 의 가장 최근 사건. 없으면 로그만.
   */
  public void onHelpRequestReopened(EventEnvelope e) {
    JsonNode p = e.payload();
    String caseId = text(p, "caseId");
    String incidentId = text(p, "incidentId");
    var found =
        caseId != null
            ? store.lock(caseId)
            : incidentId == null
                ? java.util.Optional.<CaseRow>empty()
                : store.lockLatestByDedupe("INCIDENT:" + incidentId);
    if (found.isEmpty()) {
      log.warn("도움요청 다시 열림 — 접수 건 없음, 무시");
      return;
    }
    CaseRow c = found.get();
    Instant now = now();
    store.touch(c.caseId(), now);
    store.history(
        c.caseId(),
        now,
        SYSTEM,
        "RIDER_REOPENED",
        null,
        null,
        "CLOSED".equals(c.state())
            ? "라이더가 도움요청을 다시 열었어요 — 처리 끝난 건이라 확인 뒤 필요하면 다시 처리하기"
            : "라이더가 도움요청을 다시 열었어요");
    log.info("도움요청 다시 열림 — 사건 {} ({})", c.caseId(), c.state());
  }

  /** 의무(0.8.0)가 쓴다 — 지금 직원이 볼 수 있는 사건(없거나 못 보면 404). */
  public CaseRow visibleCase(String caseId) {
    return visible(operator(), caseId);
  }

  /** 의무(0.8.0)가 쓴다 — 지금 직원이 볼 수 있는 사건을 잠근다(닫기와 순서를 맞춘다). */
  public CaseRow lockVisibleCase(String caseId) {
    return lockVisible(operator(), caseId);
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

  // ---------- 오배송 귀책(ADR-0065, 2026-11-16) ----------

  /** 귀책·고객 선택·실제 주소 ID(CUSTOMER·REDELIVER). */
  public record MisdeliveryDecision(
      String fault,
      String resolution,
      @org.jspecify.annotations.Nullable String redeliveryDestinationId) {}

  /**
   * 오배송 귀책·선택 검사. fault 가 없으면 null(예전 처리). RIDER·SYSTEM 은 RESHIP(기본)·REFUND, CUSTOMER 는
   * REDELIVER(기본)·REFUND — 다른 조합은 422 INVALID_MISDELIVERY_RESOLUTION. CUSTOMER·REDELIVER 는 실제 주소 ID
   * 필수(422 REDELIVERY_DESTINATION_REQUIRED).
   */
  static @org.jspecify.annotations.Nullable MisdeliveryDecision misdeliveryDecision(
      @org.jspecify.annotations.Nullable String fault,
      @org.jspecify.annotations.Nullable String resolution,
      @org.jspecify.annotations.Nullable String redeliveryDestinationId) {
    if (fault == null) {
      if (resolution != null || redeliveryDestinationId != null) {
        throw unprocessable("INVALID_MISDELIVERY_RESOLUTION", "귀책(fault)을 함께 보내야 합니다.");
      }
      return null;
    }
    boolean customer = "CUSTOMER".equals(fault);
    String r = resolution != null ? resolution : customer ? "REDELIVER" : "RESHIP";
    boolean ok =
        customer
            ? "REDELIVER".equals(r) || "REFUND".equals(r)
            : "RESHIP".equals(r) || "REFUND".equals(r);
    if (!ok) {
      throw unprocessable(
          "INVALID_MISDELIVERY_RESOLUTION",
          customer
              ? "고객 잘못이면 다시 배송(REDELIVER) 또는 환불(REFUND)입니다."
              : "라이더·시스템 잘못이면 재발송(RESHIP) 또는 환불(REFUND)입니다.");
    }
    if (customer && "REDELIVER".equals(r) && redeliveryDestinationId == null) {
      throw unprocessable("REDELIVERY_DESTINATION_REQUIRED", "다시 배송할 실제 주소를 먼저 기록해 주세요.");
    }
    return new MisdeliveryDecision(
        fault, r, "REDELIVER".equals(r) ? redeliveryDestinationId : null);
  }

  private static com.pmart.common.web.PmartException unprocessable(String code, String message) {
    return new com.pmart.common.web.PmartException(
        code,
        org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT,
        message,
        false,
        java.util.List.of());
  }
}
