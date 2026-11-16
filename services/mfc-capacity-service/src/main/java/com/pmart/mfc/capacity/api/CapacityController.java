package com.pmart.mfc.capacity.api;

import com.pmart.common.web.CommandResponse;
import com.pmart.mfc.capacity.api.generated.CapacityApi;
import com.pmart.mfc.capacity.api.generated.model.CapacityHold;
import com.pmart.mfc.capacity.api.generated.model.CapacitySnapshot;
import com.pmart.mfc.capacity.api.generated.model.CapacitySnapshotStagesInner;
import com.pmart.mfc.capacity.api.generated.model.ConfirmCapacityHoldRequest;
import com.pmart.mfc.capacity.api.generated.model.GetReceivingCapabilities200ResponseAsOf;
import com.pmart.mfc.capacity.api.generated.model.HoldCapacity201Response;
import com.pmart.mfc.capacity.api.generated.model.HoldCapacityRequest;
import com.pmart.mfc.capacity.api.generated.model.ReleaseCapacityHoldRequest;
import com.pmart.mfc.capacity.application.HoldService;
import com.pmart.mfc.capacity.application.SnapshotService;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 CapacityApi — 여력 스냅샷·CapacityHold(내부 호출, /internal). 업무 규칙은 application/domain 에 있고 여기서는 변환만
 * 한다.
 */
@RestController
public class CapacityController implements CapacityApi {

  private final HoldService holds;
  private final SnapshotService snapshots;

  public CapacityController(HoldService holds, SnapshotService snapshots) {
    this.holds = holds;
    this.snapshots = snapshots;
  }

  @Override
  public ResponseEntity<HoldCapacity201Response> holdCapacity(
      String idempotencyKey,
      String xSourceSystem,
      HoldCapacityRequest body,
      @Nullable String traceparent) {
    CommandResponse r =
        holds.hold(
            idempotencyKey,
            xSourceSystem,
            new HoldService.HoldCommand(
                body.getOrderId(),
                body.getMfcId(),
                body.getWindow(),
                body.getTtlMinutes() == null ? 10 : body.getTtlMinutes(),
                body.getDemand(),
                body.getReplacesHoldId()),
            body);
    return ResponseEntity.status(HttpStatus.CREATED).body(command(r));
  }

  @Override
  public ResponseEntity<CapacityHold> getCapacityHold(String holdId) {
    com.pmart.mfc.capacity.domain.CapacityHold h = holds.get(holdId);
    return ResponseEntity.ok(
        new CapacityHold()
            .holdId(h.holdId())
            .orderId(h.orderId())
            .mfcId(h.mfcId())
            .window(h.window().text())
            .version(h.version())
            .state(CapacityHold.StateEnum.fromValue(h.state().name()))
            .units(h.units())
            .expiresAt(odt(h.expiresAt()))
            .releaseReason(h.releaseReason()));
  }

  @Override
  public ResponseEntity<HoldCapacity201Response> confirmCapacityHold(
      String holdId,
      String idempotencyKey,
      String xSourceSystem,
      ConfirmCapacityHoldRequest body,
      @Nullable Integer xExpectedVersion) {
    return ResponseEntity.ok(
        command(
            holds.confirm(
                idempotencyKey, xSourceSystem, holdId, body.getOrderId(), xExpectedVersion, body)));
  }

  @Override
  public ResponseEntity<HoldCapacity201Response> releaseCapacityHold(
      String holdId,
      String idempotencyKey,
      String xSourceSystem,
      ReleaseCapacityHoldRequest body,
      @Nullable Integer xExpectedVersion) {
    return ResponseEntity.ok(
        command(
            holds.release(
                idempotencyKey,
                xSourceSystem,
                holdId,
                body.getReason() == null ? null : body.getReason().getValue(),
                body.getSourceRef(),
                xExpectedVersion,
                body)));
  }

  @Override
  public ResponseEntity<CapacitySnapshot> getCapacitySnapshot(String mfcId, String window) {
    SnapshotService.Snapshot s = snapshots.snapshot(mfcId, window);
    return ResponseEntity.ok(
        new CapacitySnapshot()
            .mfcId(s.mfcId())
            .window(s.window().text())
            .version(s.version())
            .asOf(
                new GetReceivingCapabilities200ResponseAsOf()
                    .asOf(odt(s.asOf()))
                    .sourceVersion(s.version())
                    .freshness(GetReceivingCapabilities200ResponseAsOf.FreshnessEnum.CURRENT))
            .capacityUnits(s.capacityUnits())
            .heldUnits(s.heldUnits())
            .confirmedUnits(s.confirmedUnits())
            .availableUnits(s.availableUnits())
            .stages(
                List.of(
                    new CapacitySnapshotStagesInner()
                        .stage(CapacitySnapshotStagesInner.StageEnum.WORKFORCE)
                        .rawCapacity(s.capacityUnits())
                        .convertedUnits(s.capacityUnits())))
            .activeRestrictions(s.activeRestrictions()));
  }

  private static HoldCapacity201Response command(CommandResponse r) {
    return new HoldCapacity201Response()
        .status(HoldCapacity201Response.StatusEnum.fromValue(r.status().name()))
        .requestId(r.requestId())
        .resultId(r.resultId())
        .appliedQty(r.appliedQty())
        .version(r.version())
        .retryable(r.retryable())
        .nextAction(r.nextAction())
        .occurredAt(odt(r.occurredAt()));
  }

  private static OffsetDateTime odt(Instant i) {
    return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
  }
}
