package com.pmart.delivery.dispatch.api;

import com.pmart.delivery.dispatch.api.generated.DispatchMetricsApi;
import com.pmart.delivery.dispatch.api.generated.model.ListOnTimeDeliveryMetrics200Response;
import com.pmart.delivery.dispatch.api.generated.model.ListOnTimeDeliveryMetrics200ResponseItemsInner;
import com.pmart.delivery.dispatch.application.OnTimeMetrics;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** DD-4 정시 배송률(2026-11-19) — 규칙은 {@link OnTimeMetrics}. */
@RestController
public class DispatchMetricsController implements DispatchMetricsApi {

  private final OnTimeMetrics metrics;
  private final com.pmart.delivery.dispatch.application.DispatchEfficiency efficiency;
  private final Clock clock;

  public DispatchMetricsController(
      OnTimeMetrics metrics,
      com.pmart.delivery.dispatch.application.DispatchEfficiency efficiency,
      Clock clock) {
    this.metrics = metrics;
    this.efficiency = efficiency;
    this.clock = clock;
  }

  @Override
  public ResponseEntity<ListOnTimeDeliveryMetrics200Response> listOnTimeDeliveryMetrics(
      @Nullable LocalDate from, @Nullable LocalDate to, @Nullable String mfcId) {
    return ResponseEntity.ok(
        new ListOnTimeDeliveryMetrics200Response()
            .items(
                metrics.list(from, to, mfcId).stream()
                    .map(
                        r ->
                            new ListOnTimeDeliveryMetrics200ResponseItemsInner()
                                .date(r.date())
                                .mfcId(r.mfcId())
                                .due(r.due())
                                .onTime(r.onTime())
                                .late(r.late())
                                .unjudged(r.unjudged())
                                .pending(r.pending()))
                    .toList())
            .asOf(clock.instant().atOffset(ZoneOffset.UTC)));
  }

  /** 배차 효율(0.16.0) — 규칙은 {@link com.pmart.delivery.dispatch.application.DispatchEfficiency}. */
  @Override
  public ResponseEntity<
          com.pmart.delivery.dispatch.api.generated.model.GetDispatchMetrics200Response>
      getDispatchMetrics(@Nullable LocalDate from, @Nullable LocalDate to, @Nullable String mfcId) {
    var r = efficiency.get(from, to, mfcId);
    return ResponseEntity.ok(
        new com.pmart.delivery.dispatch.api.generated.model.GetDispatchMetrics200Response()
            .from(r.from())
            .to(r.to())
            .rows(
                r.rows().stream()
                    .map(
                        x ->
                            new com.pmart.delivery.dispatch.api.generated.model
                                    .GetDispatchMetrics200ResponseRowsInner()
                                .date(x.date())
                                .mfcId(x.mfcId())
                                .created(x.created())
                                .assigned(x.assigned())
                                .avgAssignSeconds(x.avgAssignSeconds())
                                .reassigned(x.reassigned())
                                .failedRounds(x.failedRounds())
                                .autoCancelled(x.autoCancelled())
                                .offersSent(x.offersSent())
                                .offersAccepted(x.offersAccepted())
                                .offersRejected(x.offersRejected())
                                .offersExpired(x.offersExpired())
                                .assignedByAi(x.assignedByAi())
                                .assignedByList(x.assignedByList())
                                .assignedByFailedReturn(x.assignedByFailedReturn()))
                    .toList())
            .failureReasons(
                r.reasons().stream()
                    .map(
                        x ->
                            new com.pmart.delivery.dispatch.api.generated.model
                                    .GetDispatchMetrics200ResponseFailureReasonsInner()
                                .reason(x.reason())
                                .rounds(x.rounds()))
                    .toList()));
  }
}
