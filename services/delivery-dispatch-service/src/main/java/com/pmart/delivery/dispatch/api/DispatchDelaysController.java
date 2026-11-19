package com.pmart.delivery.dispatch.api;

import com.pmart.delivery.dispatch.api.generated.DispatchDelaysApi;
import com.pmart.delivery.dispatch.api.generated.model.ListDispatchDelays200Response;
import com.pmart.delivery.dispatch.api.generated.model.ListDispatchDelays200ResponseItemsInner;
import com.pmart.delivery.dispatch.application.DispatchDelays;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 배차 지연 배송(2026-11-19) — 규칙은 {@link DispatchDelays}. */
@RestController
public class DispatchDelaysController implements DispatchDelaysApi {

  private final DispatchDelays delays;

  public DispatchDelaysController(DispatchDelays delays) {
    this.delays = delays;
  }

  @Override
  public ResponseEntity<ListDispatchDelays200Response> listDispatchDelays(@Nullable String mfcId) {
    return ResponseEntity.ok(
        new ListDispatchDelays200Response()
            .items(
                delays.list(mfcId).stream()
                    .map(
                        d ->
                            new ListDispatchDelays200ResponseItemsInner()
                                .deliveryId(d.deliveryId())
                                .orderId(d.orderId())
                                .mfcId(d.mfcId())
                                .deliverySeq(d.deliverySeq())
                                .state(
                                    ListDispatchDelays200ResponseItemsInner.StateEnum.fromValue(
                                        d.state()))
                                .riderId(d.riderId())
                                .promisedAt(t(d.promisedAt()))
                                .delayAlertedAt(t(d.delayAlertedAt()))
                                .createdAt(t(d.createdAt())))
                    .toList()));
  }

  private static @Nullable OffsetDateTime t(@Nullable Instant i) {
    return i == null ? null : i.atOffset(ZoneOffset.UTC);
  }
}
