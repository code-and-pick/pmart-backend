package com.pmart.delivery.execution.api;

import com.pmart.delivery.execution.api.generated.model.DeliveryExecution;
import com.pmart.delivery.execution.api.generated.model.Evidence;
import com.pmart.delivery.execution.api.generated.model.ExecutionState;
import com.pmart.delivery.execution.api.generated.model.ReportStoreArrival200Response;
import com.pmart.delivery.execution.api.generated.model.StatusHistoryEntry;
import com.pmart.delivery.execution.application.ExecutionService;
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
    return out;
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
