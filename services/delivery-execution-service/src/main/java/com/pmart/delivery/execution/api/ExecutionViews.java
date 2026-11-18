package com.pmart.delivery.execution.api;

import com.pmart.delivery.execution.api.generated.model.DeliveryExecution;
import com.pmart.delivery.execution.api.generated.model.DeliveryExecutionFailure;
import com.pmart.delivery.execution.api.generated.model.DeliveryExecutionIncidentsInner;
import com.pmart.delivery.execution.api.generated.model.DeliveryExecutionRecoveryOrigin;
import com.pmart.delivery.execution.api.generated.model.Earning;
import com.pmart.delivery.execution.api.generated.model.EarningBasis;
import com.pmart.delivery.execution.api.generated.model.Evidence;
import com.pmart.delivery.execution.api.generated.model.ExecutionState;
import com.pmart.delivery.execution.api.generated.model.ReportStoreArrival200Response;
import com.pmart.delivery.execution.api.generated.model.SettlementState;
import com.pmart.delivery.execution.api.generated.model.StatusHistoryEntry;
import com.pmart.delivery.execution.application.ExecutionService;
import com.pmart.delivery.execution.infrastructure.ExecutionStore;
import com.pmart.delivery.execution.infrastructure.ExecutionStore.DeliveryRow;
import com.pmart.delivery.execution.infrastructure.ExecutionStore.HandoverRow;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 저장 행 → 계약 응답. */
@Component
class ExecutionViews {

  private final ObjectMapper mapper;

  ExecutionViews(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  DeliveryExecution delivery(ExecutionService.View v) {
    DeliveryRow d = v.delivery();
    DeliveryExecution out =
        new DeliveryExecution()
            .deliveryId(d.deliveryId())
            .version(d.version())
            .type(DeliveryExecution.TypeEnum.fromValue(d.type()))
            .orderId(d.orderId())
            .riderId(d.riderId())
            .mfcId(d.mfcId())
            .state(ExecutionState.fromValue(d.state()))
            .handoverIds(v.handovers().stream().map(HandoverRow::handoverId).toList())
            .packageIds(v.packageIds()) // DE-4: 출고 포장 ∪ 인계 기록 포장
            .history(
                v.history().stream()
                    .map(
                        h ->
                            new StatusHistoryEntry()
                                .state(ExecutionState.fromValue(h.state()))
                                .occurredAt(time(h.occurredAt()))
                                .recordedAt(time(h.recordedAt()))
                                .actor(StatusHistoryEntry.ActorEnum.fromValue(h.actor()))
                                .sourceRef(h.sourceRef()))
                    .toList())
            .assignedAt(time(d.assignedAt()))
            .completedAt(time(d.completedAt()))
            .purpose(
                d.purpose() == null ? null : DeliveryExecution.PurposeEnum.fromValue(d.purpose()))
            .returnPickupRequired(returnPickupRequired(v))
            .caseId(d.caseId())
            .claimId(d.claimId())
            .orderNumber(d.orderNumber())
            .mfcName(d.mfcName())
            .mfcPhone(d.mfcPhone())
            .originDeliveryId(d.originDeliveryId());
    if (d.claimReturn() && d.returnLinesJson() != null) {
      // 고객 반품 회수(DD-3) — 받을 상품·수량(라이더가 고객에게 받을 것). 이름은 orderSummary.items 의 같은 orderLineId
      List<com.pmart.delivery.execution.api.generated.model.ReturnLine> rl = new ArrayList<>();
      for (JsonNode n : mapper.readTree(d.returnLinesJson())) {
        rl.add(
            new com.pmart.delivery.execution.api.generated.model.ReturnLine()
                .orderLineId(n.path("orderLineId").asString())
                .qty(n.path("qty").asInt())
                .claimLineId(n.path("claimLineId").asString(null))
                .sku(n.path("sku").asString(null)));
      }
      out.returnLines(rl);
    }
    if (v.origin() != null && v.origin().evidenceJson() != null) {
      // 오배송 회수(DD-2) — 원래 배송의 완료 증빙(놓은 곳·사진 참조)
      JsonNode e = mapper.readTree(v.origin().evidenceJson());
      out.recoveryOrigin(
          new DeliveryExecutionRecoveryOrigin()
              .placement(e.path("placement").asString(null))
              .placementDetail(e.path("placementDetail").asString(null))
              .photoRefs(strings(e.path("photoRefs")))
              .takenAt(instantOf(e.path("takenAt")))
              .completedAt(time(v.origin().completedAt())));
    }
    if ("RETURN".equals(d.type()) && d.evidenceJson() != null) {
      // 회수 배송 증빙 = 회수지 사진 + 반납 사진
      JsonNode e = mapper.readTree(d.evidenceJson());
      List<String> refs = new ArrayList<>(strings(e.path("pickup").path("photoRefs")));
      refs.addAll(strings(e.path("returned").path("photoRefs")));
      JsonNode last = e.path("returned").isObject() ? e.path("returned") : e.path("pickup");
      out.evidence(
          new Evidence()
              .evidenceRef(last.path("evidenceRef").asString(null))
              .photoRefs(refs)
              .takenAt(instantOf(last.path("at"))));
    } else if (d.evidenceJson() != null) {
      JsonNode e = mapper.readTree(d.evidenceJson());
      List<String> photoRefs = new ArrayList<>();
      for (JsonNode n : e.path("photoRefs")) {
        photoRefs.add(n.asString());
      }
      out.evidence(
          new Evidence()
              .evidenceRef(e.path("evidenceRef").asString(null))
              .method(
                  e.path("method").isString()
                      ? com.pmart.delivery.execution.api.generated.model.Evidence.MethodEnum
                          .fromValue(e.path("method").asString())
                      : null)
              .photoRefs(photoRefs)
              .takenAt(
                  e.path("takenAt").isString()
                      ? time(Instant.parse(e.path("takenAt").asString()))
                      : null)
              .placement(e.path("placement").asString(null))
              .substituteEvidenceCaseId(e.path("substituteEvidenceCaseId").asString(null)));
    }
    if (d.failureJson() != null) {
      JsonNode f = mapper.readTree(d.failureJson());
      List<String> photos = new ArrayList<>();
      for (JsonNode n : f.path("photoRefs")) {
        photos.add(n.asString());
      }
      out.failure(
          new DeliveryExecutionFailure()
              .reason(f.path("reason").asString(null))
              .detail(f.path("detail").asString(null))
              .goodsLocation(f.path("goodsLocation").asString(null))
              .photoRefs(photos)
              .contactAttempts(f.path("contactAttempts").size())
              .reportedAt(instantOf(f.path("reportedAt")))
              .decision(
                  f.path("decision").isString()
                      ? DeliveryExecutionFailure.DecisionEnum.fromValue(
                          f.path("decision").asString())
                      : null)
              .decidedBy(f.path("decidedBy").asString(null))
              .decidedAt(instantOf(f.path("decidedAt")))
              .helpRequestId(f.path("requestId").asString(null))
              .resumeRequestedAt(instantOf(f.path("resumeRequestedAt")))
              .returnDeliveryId(v.returnDeliveryId())
              .custody(
                  f.path("custody").isString()
                      ? DeliveryExecutionFailure.CustodyEnum.fromValue(f.path("custody").asString())
                      : null));
    }
    out.incidents(
        v.helps().stream()
            .map(
                h ->
                    new DeliveryExecutionIncidentsInner()
                        .incidentId(h.incidentId())
                        .category(h.category())
                        .raisedAt(time(h.createdAt())))
            .toList());
    if (v.earning() != null) {
      out.earning(earning(v.earning()));
    }
    return out;
  }

  private static List<String> strings(JsonNode arr) {
    List<String> out = new ArrayList<>();
    for (JsonNode n : arr) {
      out.add(n.asString());
    }
    return out;
  }

  static Earning earning(ExecutionStore.EarningRow e) {
    return new Earning()
        .earningId(e.earningId())
        .deliveryId(e.deliveryId())
        .riderId(e.riderId())
        .calculable(e.calculable())
        .rateTableVersion(e.rateTableVersion())
        .basis(
            new EarningBasis()
                .vehicleType(e.vehicleType())
                .distanceBandKm(
                    e.distanceM() == null
                        ? null
                        : java.math.BigDecimal.valueOf(e.distanceM())
                            .movePointLeft(3)
                            .setScale(2, java.math.RoundingMode.HALF_UP)
                            .toPlainString()))
        .baseFee(e.baseFee())
        .compensation(
            e.baseFee() != null && e.total() != null && e.total() > e.baseFee()
                ? e.total() - e.baseFee()
                : null) // 승인된 추가 배달료(2026-11-17 — 합계 − 기본)
        .total(e.total())
        .settlementState(SettlementState.fromValue(e.settlementState()))
        .recordedAt(time(e.recordedAt()))
        .confirmAt(time(e.confirmAt()))
        .payoutDate(e.payoutDate())
        .note(e.note());
  }

  private static @Nullable OffsetDateTime instantOf(JsonNode n) {
    return n.isString() ? time(Instant.parse(n.asString())) : null;
  }

  static ReportStoreArrival200Response applied(ExecutionService.Applied a) {
    return new ReportStoreArrival200Response()
        .status(ReportStoreArrival200Response.StatusEnum.APPLIED)
        .requestId(a.requestId())
        .resultId(a.deliveryId())
        .version(a.version())
        .retryable(false)
        .occurredAt(time(a.occurredAt()));
  }

  static @Nullable OffsetDateTime time(@Nullable Instant i) {
    return i == null ? null : i.atOffset(ZoneOffset.UTC);
  }

  /** 회수 배송의 "받음" 단계 유무(2026-11-17) — 같은 라이더의 전달 실패 반납만 없다. RETURN 이 아니면 null. */
  static @Nullable Boolean returnPickupRequired(ExecutionService.View v) {
    var d = v.delivery();
    if (!"RETURN".equals(d.type())) {
      return null;
    }
    if (!d.failedReturn()) {
      return true;
    }
    return v.origin() != null
        && v.origin().riderId() != null
        && !v.origin().riderId().equals(d.riderId());
  }

  static com.pmart.delivery.execution.api.generated.model.SurchargeRequest surcharge(
      com.pmart.delivery.execution.application.SurchargeService.Request r, boolean staff) {
    return new com.pmart.delivery.execution.api.generated.model.SurchargeRequest()
        .requestId(r.requestId())
        .deliveryId(r.deliveryId())
        .riderId(r.riderId())
        .reason(
            com.pmart.delivery.execution.api.generated.model.SurchargeRequest.ReasonEnum.fromValue(
                r.reason()))
        .floor(r.floor())
        .amount(r.amount())
        .evidenceRefs(r.evidenceRefs())
        .state(
            com.pmart.delivery.execution.api.generated.model.SurchargeRequest.StateEnum.fromValue(
                r.state()))
        .requestedAt(time(r.requestedAt()))
        .decidedBy(staff ? r.decidedBy() : null)
        .decidedAt(time(r.decidedAt()))
        .decisionNote(r.decisionNote());
  }
}
