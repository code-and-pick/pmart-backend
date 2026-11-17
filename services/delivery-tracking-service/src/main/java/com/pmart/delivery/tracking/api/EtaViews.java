package com.pmart.delivery.tracking.api;

import com.pmart.delivery.tracking.api.generated.model.DeliveryEta;
import com.pmart.delivery.tracking.api.generated.model.DeliveryEtaArrivalWindow;
import com.pmart.delivery.tracking.api.generated.model.DeliveryEtaAsOf;
import com.pmart.delivery.tracking.api.generated.model.EtaLeg;
import com.pmart.delivery.tracking.api.generated.model.EtaStatus;
import com.pmart.delivery.tracking.application.ProgressService;
import com.pmart.delivery.tracking.domain.ProgressRules;
import com.pmart.delivery.tracking.infrastructure.ProgressStore.Progress;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** 진행 사본 → 계약 DeliveryEta(TR-3). 라이더 좌표는 싣지 않는다. 고객에게 pickupEta 없음. */
final class EtaViews {

  private static final ZoneId KST = ZoneId.of("Asia/Seoul");
  private static final Set<String> BEFORE_PICKUP =
      Set.of(ProgressRules.UNASSIGNED, ProgressRules.TO_STORE, ProgressRules.AT_STORE);

  private EtaViews() {}

  static DeliveryEta eta(ProgressService.View v, Instant now) {
    Progress r = v.progress();
    DeliveryEta out =
        new DeliveryEta()
            .deliveryId(r.deliveryId())
            .stage(DeliveryEta.StageEnum.fromValue(r.stage()))
            .dropEta(leg(v, r.etaDrop(), now, windowEnd(r)))
            .asOf(
                new DeliveryEtaAsOf()
                    .asOf(time(now))
                    .freshness(DeliveryEtaAsOf.FreshnessEnum.CURRENT));
    if (ProgressRules.DONE.equals(r.stage()) && r.outcome() != null) {
      out.outcome(DeliveryEta.OutcomeEnum.fromValue(r.outcome()));
    }
    if (v.staff() && BEFORE_PICKUP.contains(r.stage())) {
      out.pickupEta(leg(v, r.etaPickup(), now, null));
    }
    if (v.riderLocation() != null) {
      var l = v.riderLocation();
      out.riderLocation(
              new com.pmart.delivery.tracking.api.generated.model.DeliveryEtaRiderLocation()
                  .lat(java.math.BigDecimal.valueOf(l.lat()))
                  .lng(java.math.BigDecimal.valueOf(l.lng()))
                  .recordedAt(time(l.recordedAt()))
                  .staleSeconds(
                      (int) Math.max(0, now.getEpochSecond() - l.receivedAt().getEpochSecond())))
          .riderLocationQuality(
              com.pmart.delivery.tracking.api.generated.model.GpsQuality.fromValue(l.quality()));
    }
    if (r.arrivalDate() != null) {
      out.arrivalWindow(
          new DeliveryEtaArrivalWindow()
              .date(r.arrivalDate())
              .start(r.arrivalStart())
              .end(r.arrivalEnd()));
    }
    return out;
  }

  /** 상태 지문 — 단계·도착 예정이 바뀌면 달라진다(SSE id, Last-Event-ID). */
  static String fingerprint(ProgressService.View v) {
    Progress r = v.progress();
    return r.stage()
        + ":"
        + epoch(r.etaDrop())
        + (v.staff() ? ":" + epoch(r.etaPickup()) : "")
        + (v.locating() ? ":L" : "")
        + (v.riderLocation() == null
            ? ""
            : ":" + v.riderLocation().recordedAt().getEpochSecond()); // 위치가 바뀌면 ETA_UPDATED
  }

  private static String epoch(@Nullable Instant i) {
    return i == null ? "-" : String.valueOf(i.getEpochSecond());
  }

  private static EtaLeg leg(
      ProgressService.View v, @Nullable Instant value, Instant now, @Nullable Instant windowEnd) {
    Progress r = v.progress();
    if (ProgressRules.DONE.equals(r.stage())) {
      return new EtaLeg().status(EtaStatus.FINAL);
    }
    if (ProgressRules.UNASSIGNED.equals(r.stage()) || !v.etaEnabled()) {
      return new EtaLeg().status(EtaStatus.ETA_PENDING);
    }
    if (value == null) {
      return new EtaLeg().status(v.locating() ? EtaStatus.LOCATING : EtaStatus.ETA_PENDING);
    }
    EtaLeg leg =
        new EtaLeg()
            .status(EtaStatus.AVAILABLE)
            .etaAt(time(value))
            .remainingSeconds((int) Math.max(0, value.getEpochSecond() - now.getEpochSecond()))
            .calculatedAt(time(r.etaCheckedAt()));
    if (windowEnd != null) {
      leg.delayedBySeconds((int) (value.getEpochSecond() - windowEnd.getEpochSecond()));
    }
    return leg;
  }

  /** 예약 도착 시간대 끝(KST) — 지연 판단 기준. 없거나 형식이 다르면 null. */
  private static @Nullable Instant windowEnd(Progress r) {
    if (r.arrivalDate() == null || r.arrivalEnd() == null) {
      return null;
    }
    try {
      return r.arrivalDate().atTime(LocalTime.parse(r.arrivalEnd())).atZone(KST).toInstant();
    } catch (RuntimeException e) {
      return null;
    }
  }

  static @Nullable OffsetDateTime time(@Nullable Instant i) {
    return i == null ? null : OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
  }
}
