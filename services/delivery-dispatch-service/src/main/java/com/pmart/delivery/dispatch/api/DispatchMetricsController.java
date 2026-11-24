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
  private final com.pmart.delivery.dispatch.application.AutoCancellations autoCancellations;
  private final Clock clock;

  public DispatchMetricsController(
      OnTimeMetrics metrics,
      com.pmart.delivery.dispatch.application.DispatchEfficiency efficiency,
      com.pmart.delivery.dispatch.application.AutoCancellations autoCancellations,
      Clock clock) {
    this.autoCancellations = autoCancellations;
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

  /**
   * 배차 불가 자동 취소 내역(0.17.0) — 규칙은 {@link com.pmart.delivery.dispatch.application.AutoCancellations}.
   */
  @Override
  public ResponseEntity<
          com.pmart.delivery.dispatch.api.generated.model.ListAutoCancellations200Response>
      listAutoCancellations(
          @Nullable LocalDate from, @Nullable LocalDate to, @Nullable String mfcId, String filter) {
    com.pmart.delivery.dispatch.application.AutoCancellations.Filter which;
    try {
      which = com.pmart.delivery.dispatch.application.AutoCancellations.Filter.valueOf(filter);
    } catch (IllegalArgumentException e) {
      throw new com.pmart.common.web.PmartException(
          com.pmart.common.web.ErrorCode.VALIDATION_FAILED,
          "filter 는 ALL·CANCELLED·BLOCKED·BLOCKED_OPEN 입니다.");
    }
    var r = autoCancellations.list(from, to, mfcId, which);
    return ResponseEntity.ok(
        new com.pmart.delivery.dispatch.api.generated.model.ListAutoCancellations200Response()
            .from(r.from())
            .to(r.to())
            .items(
                r.items().stream()
                    .map(
                        x ->
                            new com.pmart.delivery.dispatch.api.generated.model
                                    .ListAutoCancellations200ResponseItemsInner()
                                .deliveryId(x.deliveryId())
                                .orderId(x.orderId())
                                .mfcId(x.mfcId())
                                .deliveryNumber(x.deliveryNumber())
                                .promisedAt(
                                    x.promisedAt() == null
                                        ? null
                                        : x.promisedAt().atOffset(ZoneOffset.UTC))
                                .attemptedAt(x.attemptedAt().atOffset(ZoneOffset.UTC))
                                .result(
                                    com.pmart.delivery.dispatch.api.generated.model
                                        .ListAutoCancellations200ResponseItemsInner.ResultEnum
                                        .fromValue(x.result()))
                                .rejectCode(x.rejectCode())
                                .deliveryState(x.deliveryState()))
                    .toList()));
  }
}
