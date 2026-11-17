package com.pmart.delivery.execution.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.delivery.execution.domain.EarningRules;
import com.pmart.delivery.execution.infrastructure.ExecutionStore;
import com.pmart.delivery.execution.infrastructure.ExecutionStore.DeliveryRow;
import com.pmart.delivery.execution.infrastructure.ExecutionStore.EarningRow;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 산정 불가 수행료 조정(2026-11-17 — 실패 확정·운영자 배차 취소 등 "지원센터 조정"으로 남은 수행료). 사용자 결정:
 *
 * <ul>
 *   <li>누가: 라이더 운영(RIDER_OPS)·관리자(OPERATOR_ADMIN). 상담원(CS_AGENT)은 목록 보기만.
 *   <li>무엇을: <b>귀책</b>(RIDER·CUSTOMER·STORE·PMART)과 사유만 고른다 — 라이더 귀책이면 미지급, 그 밖이면 <b>거리별로 측정된
 *       금액</b> = 수락 당시 금액(dispatch 가 수락 때 일반 표로 매긴 값, DeliveryAssigned.fee). 수락 금액을 모르면 409
 *       FEE_UNKNOWN.
 *   <li>언제: 바로 확정(CONFIRMED), 지급 예정일 = 조정한 날(Asia/Seoul) + 3영업일. 미지급은 ADJUSTED(지급 없음).
 * </ul>
 *
 * 조정은 한 번(같은 수행료를 다시 조정하면 409 NOT_ADJUSTABLE). 사유는 직원만 본다 — 라이더에게는 결과(지급 금액·날짜 또는 미지급)만
 * RiderEarningRecorded(adjustment) → 알림함.
 */
@Service
public class EarningAdjustments {

  private static final Logger log = LoggerFactory.getLogger(EarningAdjustments.class);

  static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
  public static final Set<String> FAULTS = Set.of("RIDER", "CUSTOMER", "STORE", "PMART");

  public record Adjusted(EarningRow earning, String adjustmentId, String decision, String fault) {}

  private final ExecutionStore store;
  private final ExecutionEvents events;
  private final ExecutionProperties props;
  private final RequestIdempotency idempotency;
  private final Clock clock;

  public EarningAdjustments(
      ExecutionStore store,
      ExecutionEvents events,
      ExecutionProperties props,
      RequestIdempotency idempotency,
      Clock clock) {
    this.store = store;
    this.events = events;
    this.props = props;
    this.idempotency = idempotency;
    this.clock = clock;
  }

  /** 조정 대기 목록(오래된 것 먼저). */
  public List<EarningRow> queue(String riderId, int limit, int offset) {
    PmartPrincipal p = staff();
    if (!(p.hasRole("OPERATOR_ADMIN") || p.hasRole("RIDER_OPS") || p.hasRole("CS_AGENT"))) {
      throw ExecutionService.forbidden("수행료 조정 대기는 라이더 운영·관리자·상담원만 볼 수 있습니다.");
    }
    return store.earningsNeedingAdjustment(riderId, limit, offset);
  }

  @Transactional
  public Adjusted adjust(
      String earningId, String requestId, String source, String fault, String reason) {
    PmartPrincipal p = staff();
    if (!(p.hasRole("OPERATOR_ADMIN") || p.hasRole("RIDER_OPS"))) {
      throw ExecutionService.forbidden("수행료 조정은 라이더 운영·관리자만 할 수 있습니다.");
    }
    if (!FAULTS.contains(fault)) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED, "귀책은 RIDER·CUSTOMER·STORE·PMART 중 하나입니다.");
    }
    String why = reason == null ? "" : reason.strip();
    if (why.length() < 2 || why.length() > 300) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "조정 사유를 2~300자로 적어 주세요.");
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("earningId", earningId);
    body.put("fault", fault);
    body.put("reason", why);
    String adjustmentId =
        idempotency
            .execute(
                new RequestKey(source, requestId, "adjustRiderEarning", earningId),
                body,
                String.class,
                () -> apply(earningId, fault, why, p.subject()))
            .value();
    EarningRow after =
        store
            .lockEarning(earningId)
            .orElseThrow(() -> PmartException.notFound("EARNING_NOT_FOUND", "수행료가 없습니다."));
    return new Adjusted(after, adjustmentId, "RIDER".equals(fault) ? "NO_PAY" : "PAY", fault);
  }

  private String apply(String earningId, String fault, String reason, String operator) {
    Instant now = clock.instant();
    EarningRow e =
        store
            .lockEarning(earningId)
            .orElseThrow(() -> PmartException.notFound("EARNING_NOT_FOUND", "수행료가 없습니다."));
    if (e.calculable() || Set.of("ADJUSTED", "PAYING", "PAID").contains(e.settlementState())) {
      throw new PmartException(
          "NOT_ADJUSTABLE",
          HttpStatus.CONFLICT,
          "조정할 산정 불가 수행료가 아닙니다(이미 조정·산정됨).",
          false,
          List.of());
    }
    boolean pay = !"RIDER".equals(fault);
    Integer fee = null;
    if (pay) {
      DeliveryRow d = store.delivery(e.deliveryId()).orElse(null);
      fee = d == null ? null : d.acceptedFee();
      if (fee == null) {
        throw new PmartException(
            "FEE_UNKNOWN",
            HttpStatus.CONFLICT,
            "수락 당시 금액(거리별 금액)을 몰라 지급으로 조정할 수 없습니다.",
            false,
            List.of());
      }
    }
    LocalDate today = now.atZone(SEOUL).toLocalDate();
    LocalDate payout =
        pay ? EarningRules.payoutDate(today, props.payoutBusinessDays(), props.holidays()) : null;
    String note = pay ? "지원센터 조정 — 지급(" + faultLabel(fault) + " 귀책)" : "지원센터 조정 — 미지급(라이더 귀책)";
    int n =
        store.applyAdjustment(
            earningId,
            pay,
            fee,
            pay ? "CONFIRMED" : "ADJUSTED",
            pay ? now : null,
            payout,
            note,
            now);
    if (n == 0) {
      throw new PmartException(
          "NOT_ADJUSTABLE", HttpStatus.CONFLICT, "조정할 산정 불가 수행료가 아닙니다.", false, List.of());
    }
    String adjustmentId = "ADJ-" + UUID.randomUUID();
    store.insertAdjustment(
        adjustmentId,
        e,
        fault,
        pay ? "PAY" : "NO_PAY",
        fee == null ? 0 : fee,
        reason,
        operator,
        now);
    EarningRow after = store.lockEarning(earningId).orElseThrow();
    Map<String, Object> adj = new LinkedHashMap<>();
    adj.put("adjustmentId", adjustmentId);
    adj.put("decision", pay ? "PAY" : "NO_PAY");
    adj.put("fault", fault);
    events.earningRecorded(after, store.bumpVersion(e.deliveryId(), now), now, adj);
    log.info("수행료 {} 조정 — {} ({} 귀책)", earningId, pay ? "지급 " + fee : "미지급", fault);
    return adjustmentId;
  }

  private static String faultLabel(String fault) {
    return switch (fault) {
      case "CUSTOMER" -> "고객";
      case "STORE" -> "점포";
      case "PMART" -> "PMart";
      default -> "라이더";
    };
  }

  private static PmartPrincipal staff() {
    PmartPrincipal p =
        PmartPrincipal.current().orElseThrow(() -> ExecutionService.forbidden("직원 토큰이 필요합니다."));
    if (p.type() != PmartPrincipal.PrincipalType.OPERATOR) {
      throw ExecutionService.forbidden("직원만 쓸 수 있습니다.");
    }
    return p;
  }
}
