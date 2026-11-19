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
  private final Clock clock;

  public DispatchMetricsController(OnTimeMetrics metrics, Clock clock) {
    this.metrics = metrics;
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
}
