package com.pmart.delivery.execution.api;

import com.pmart.delivery.execution.api.generated.model.DeliveryExecution;
import com.pmart.delivery.execution.api.generated.model.DeliveryExecutionFailure;
import com.pmart.delivery.execution.api.generated.model.DeliveryExecutionIncidentsInner;
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
            .packageIds(v.handovers().stream().map(HandoverRow::packageId).distinct().toList())
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
            .completedAt(time(d.completedAt()));
    if (d.evidenceJson() != null) {
      JsonNode e = mapper.readTree(d.evidenceJson());
      List<String> photoRefs = new ArrayList<>();
      for (JsonNode n : e.path("photoRefs")) {
        photoRefs.add(n.asString());
      }
      out.evidence(
          new Evidence()
              .evidenceRef(e.path("evidenceRef").asString(null))
              .method(e.path("method").asString(null))
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
                        .incidentId(h.requestId())
                        .category(h.category())
                        .raisedAt(time(h.createdAt())))
            .toList());
    if (v.earning() != null) {
      out.earning(earning(v.earning()));
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
}
