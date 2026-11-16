package com.pmart.delivery.execution.application;

import com.pmart.common.security.PmartPrincipal;
import com.pmart.delivery.execution.domain.EarningRules;
import com.pmart.delivery.execution.infrastructure.ExecutionStore;
import com.pmart.delivery.execution.infrastructure.ExecutionStore.DeliveryRow;
import com.pmart.delivery.execution.infrastructure.ExecutionStore.EarningRow;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 수행료(원문 §7, 사용자 결정 2026-11-13 "수락 당시 금액 + 실패·취소는 산정 불가").
 *
 * <ul>
 *   <li>전달 완료 → 수락 당시 금액(DeliveryAssigned.fee)으로 확정 대기 + RiderEarningRecorded. 금액을 모르면(옛 배정 사건) 산정
 *       불가.
 *   <li>실패 확정·운영자/주문 사유 배차 취소 → 산정 불가(지원센터 조정). 라이더 본인 배차 취소는 수행료 없음(만들지 않는다).
 *   <li>확정 시각(완료 + 5시간)이 지나면 CONFIRMED + RiderEarningRecorded. 지급(PAYING·PAID)은 외부 회계 — 아직 연결 없음.
 *   <li>배정당 하나(배송·라이더·배정 버전) — 완료 중복·사건 재전달에도 한 번만(원문 §6.1 "완료 수익은 중복 클릭 시 한 번만"). 운영자 배차 취소(산정 불가)
 *       뒤 같은 라이더가 다시 배정돼 완료하면 완료 수행료가 따로 생긴다(V4, PC 1 검토 후속).
 * </ul>
 *
 * 만들고 바꾸는 메서드는 호출자 트랜잭션 안에서(완료·실패 확정·사건 처리와 같은 트랜잭션).
 */
@Service
public class EarningService {

  private static final Logger log = LoggerFactory.getLogger(EarningService.class);
  static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

  public record Totals(long estimated, long confirmed, long paid) {}

  private final ExecutionStore store;
  private final ExecutionEvents events;
  private final ExecutionProperties props;
  private final TransactionTemplate tx;
  private final Clock clock;

  public EarningService(
      ExecutionStore store,
      ExecutionEvents events,
      ExecutionProperties props,
      TransactionTemplate tx,
      Clock clock) {
    this.store = store;
    this.events = events;
    this.props = props;
    this.tx = tx;
    this.clock = clock;
  }

  /** 전달 완료 — 수락 당시 금액으로 확정 대기. */
  public void completed(DeliveryRow d, Instant completedAt) {
    if (d.riderId() == null) {
      return;
    }
    LocalDate performedOn = completedAt.atZone(SEOUL).toLocalDate();
    LocalDate payout =
        EarningRules.payoutDate(performedOn, props.payoutBusinessDays(), props.holidays());
    int lastYear =
        props.publicHolidays().stream()
            .mapToInt(LocalDate::getYear)
            .max()
            .orElse(Integer.MIN_VALUE);
    if (payout.getYear() > lastYear) {
      // 지급 예정일 해의 공휴일을 모른다 — 주말만 빼서 계산했다(해마다 법정공휴일 목록을 더한다)
      log.warn(
          "공휴일 목록에 {}년이 없음 — pmart.execution.public-holidays·KoreanPublicHolidays 에 더한다",
          payout.getYear());
    }
    boolean known = d.acceptedFee() != null;
    EarningRow e =
        new EarningRow(
            "ERN-" + UUID.randomUUID(),
            d.deliveryId(),
            d.riderId(),
            known,
            EarningRules.RATE_TABLE_VERSION,
            d.vehicleType(),
            d.distanceM(),
            d.acceptedFee(),
            d.acceptedFee(),
            "PENDING_CONFIRMATION",
            performedOn,
            EarningRules.confirmAt(completedAt, props.confirmAfter()),
            payout,
            known ? null : "수락 당시 금액을 모름 — 지원센터 조정",
            completedAt);
    record(e, d.assignmentVersion(), completedAt);
  }

  /** 실패 확정·취소 — 원문에 금액 기준이 없어 산정 불가(지원센터 조정). */
  public void notCalculable(DeliveryRow d, String stage, Instant at) {
    if (d.riderId() == null) {
      return;
    }
    LocalDate performedOn = at.atZone(SEOUL).toLocalDate();
    EarningRow e =
        new EarningRow(
            "ERN-" + UUID.randomUUID(),
            d.deliveryId(),
            d.riderId(),
            false,
            EarningRules.RATE_TABLE_VERSION,
            d.vehicleType(),
            d.distanceM(),
            null,
            null,
            "ESTIMATED",
            performedOn,
            null,
            null,
            stage + " — 실패·취소 수행료 기준 없음(지원센터 조정)",
            at);
    record(e, d.assignmentVersion(), at); // 배정 해제 사건이면 d 는 바뀌기 전 행 — 옛 배정 버전
  }

  private void record(EarningRow e, int assignmentVersion, Instant at) {
    if (store.insertEarning(e, assignmentVersion)) {
      events.earningRecorded(e, store.bumpVersion(e.deliveryId(), at), at);
      log.info(
          "배송 {} 수행료 기록 — {} {}", e.deliveryId(), e.calculable() ? "확정 대기" : "산정 불가", e.total());
    }
  }

  /**
   * 라이더 귀책 오배송(ADR-0065, 2026-11-16 — 업무 원문 §9.3 "라이더 귀책이면 배달료 미지급"): 원래 배송의 수행료를 지급 전이면
   * ADJUSTED(산정 제외), 이미 지급 중·지급됐으면 상태는 그대로 두고 "회수 필요 — 지원센터 조정" 메모만(사용자 "지원센터 조정 기록만"). 사건
   * RiderEarningRecorded.
   */
  public void withholdForMisdelivery(String deliveryId, String riderId, String caseId, Instant at) {
    store
        .earningOf(deliveryId, riderId)
        .ifPresentOrElse(
            e -> {
              boolean paid =
                  "PAYING".equals(e.settlementState()) || "PAID".equals(e.settlementState());
              if ("ADJUSTED".equals(e.settlementState())) {
                return;
              }
              String note =
                  paid
                      ? "라이더 귀책 오배송(사건 " + caseId + ") — 이미 지급, 회수 필요(지원센터 조정)"
                      : "라이더 귀책 오배송(사건 " + caseId + ") — 배달료 미지급";
              if (store.withholdEarning(e.earningId(), !paid, note, at) == 0) {
                return;
              }
              EarningRow after =
                  new EarningRow(
                      e.earningId(),
                      e.deliveryId(),
                      e.riderId(),
                      paid && e.calculable(),
                      e.rateTableVersion(),
                      e.vehicleType(),
                      e.distanceM(),
                      e.baseFee(),
                      e.total(),
                      paid ? e.settlementState() : "ADJUSTED",
                      e.performedOn(),
                      e.confirmAt(),
                      e.payoutDate(),
                      note,
                      e.recordedAt());
              events.earningRecorded(after, store.bumpVersion(deliveryId, at), at);
              log.info("배송 {} 라이더 귀책 오배송 — 수행료 {}", deliveryId, paid ? "지급됨(조정 메모)" : "미지급");
            },
            () -> log.warn("배송 {} 라이더 귀책 오배송 — 수행료 기록이 없음", deliveryId));
  }

  /** 확정 시각이 지난 확정 대기 → CONFIRMED(원문 §7.2 "배달 완료 5시간 이후 수익 확정"). */
  @Scheduled(
      initialDelayString = "${pmart.execution.confirm-interval:PT1M}",
      fixedDelayString = "${pmart.execution.confirm-interval:PT1M}")
  public void confirmDue() {
    tx.executeWithoutResult(
        s -> {
          Instant now = clock.instant();
          for (EarningRow e : store.lockDueEarnings(now, 200)) {
            store.settlementState(e.earningId(), "CONFIRMED", now);
            EarningRow confirmed =
                new EarningRow(
                    e.earningId(),
                    e.deliveryId(),
                    e.riderId(),
                    e.calculable(),
                    e.rateTableVersion(),
                    e.vehicleType(),
                    e.distanceM(),
                    e.baseFee(),
                    e.total(),
                    "CONFIRMED",
                    e.performedOn(),
                    e.confirmAt(),
                    e.payoutDate(),
                    e.note(),
                    e.recordedAt());
            events.earningRecorded(confirmed, store.bumpVersion(e.deliveryId(), now), now);
          }
        });
  }

  // ---- 조회(라이더 본인)

  public List<EarningRow> mine(
      @Nullable LocalDate from,
      @Nullable LocalDate to,
      @Nullable String state,
      int size,
      int offset) {
    return store.riderEarnings(riderId(), from, to, state, size, offset);
  }

  /** 예상 = 확정 전(ESTIMATED·PENDING_CONFIRMATION), 확정 = CONFIRMED·PAYING, 지급 = PAID. 산정 불가는 빼고. */
  public Totals totals(@Nullable LocalDate from, @Nullable LocalDate to) {
    Map<String, Long> t = store.riderTotals(riderId(), from, to);
    return new Totals(
        t.getOrDefault("ESTIMATED", 0L) + t.getOrDefault("PENDING_CONFIRMATION", 0L),
        t.getOrDefault("CONFIRMED", 0L) + t.getOrDefault("PAYING", 0L),
        t.getOrDefault("PAID", 0L));
  }

  public Instant now() {
    return clock.instant();
  }

  private static String riderId() {
    return PmartPrincipal.current()
        .filter(p -> p.type() == PmartPrincipal.PrincipalType.RIDER)
        .map(PmartPrincipal::subject)
        .orElseThrow(() -> ExecutionService.forbidden("라이더만 볼 수 있습니다."));
  }
}
