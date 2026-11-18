package com.pmart.rider.api;

import com.pmart.rider.api.generated.InternalApi;
import com.pmart.rider.api.generated.model.EligibleRider;
import com.pmart.rider.api.generated.model.ListEligibleRiders200Response;
import com.pmart.rider.api.generated.model.ListEligibleRiders200ResponseAsOf;
import com.pmart.rider.api.generated.model.VehicleType;
import com.pmart.rider.application.RiderService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 InternalApi — dispatch 가 부르는 적격 라이더·라이더 상태 조회(토큰 없는 내부 호출). 반경은 여기서 거르지 않는다(위치 정본은 tracking).
 */
@RestController
public class InternalController implements InternalApi {

  private final RiderService riders;
  private final Clock clock;

  public InternalController(RiderService riders, Clock clock) {
    this.riders = riders;
    this.clock = clock;
  }

  @Override
  public ResponseEntity<ListEligibleRiders200Response> listEligibleRiders(
      String mfcId,
      BigDecimal radiusKm,
      @Nullable VehicleType vehicleType,
      @Nullable Boolean requiresColdChain,
      @Nullable Boolean requiresAlcoholEligible,
      @Nullable List<String> excludeRiderIds,
      @Nullable Integer size) {
    var items =
        riders
            .eligible(
                mfcId,
                vehicleType == null ? null : vehicleType.getValue(),
                excludeRiderIds == null ? List.of() : excludeRiderIds,
                size == null ? 100 : size)
            .stream()
            .map(RiderViews::eligible)
            .toList();
    return ResponseEntity.ok(
        new ListEligibleRiders200Response()
            .items(items)
            .asOf(
                new ListEligibleRiders200ResponseAsOf()
                    .asOf(OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                    .freshness(ListEligibleRiders200ResponseAsOf.FreshnessEnum.CURRENT)));
  }

  @Override
  public ResponseEntity<com.pmart.rider.api.generated.model.ListOnDutyRidersInternal200Response>
      listOnDutyRidersInternal(String mfcId) {
    var list = riders.onDuty(mfcId);
    var since = riders.dutySince(list.stream().map(r -> r.riderId()).toList());
    return ResponseEntity.ok(
        new com.pmart.rider.api.generated.model.ListOnDutyRidersInternal200Response()
            .items(
                list.stream()
                    .map(
                        r -> {
                          var s = since.get(r.riderId());
                          return RiderViews.onDuty(r)
                              .onDutySince(s == null ? null : RiderViews.time(s.onDutySince()))
                              .availableSince(
                                  s == null ? null : RiderViews.time(s.availableSince()));
                        })
                    .toList()));
  }

  /** 운행 구간(0.11.0, 2026-11-18) — execution 직원 접속·대기 시간. */
  @Override
  public ResponseEntity<
          com.pmart.rider.api.generated.model.ListRiderDutySegmentsInternal200Response>
      listRiderDutySegmentsInternal(String riderId, OffsetDateTime from, OffsetDateTime to) {
    return ResponseEntity.ok(
        new com.pmart.rider.api.generated.model.ListRiderDutySegmentsInternal200Response()
            .riderId(riderId)
            .items(
                riders.dutySegments(riderId, from.toInstant(), to.toInstant()).stream()
                    .map(
                        s ->
                            new com.pmart.rider.api.generated.model
                                    .ListRiderDutySegmentsInternal200ResponseItemsInner()
                                .startedAt(RiderViews.time(s.startedAt()))
                                .endedAt(RiderViews.time(s.endedAt()))
                                .available(s.available()))
                    .toList()));
  }

  @Override
  public ResponseEntity<EligibleRider> getRiderInternal(String riderId) {
    return ResponseEntity.ok(RiderViews.eligible(riders.internal(riderId)));
  }
}
