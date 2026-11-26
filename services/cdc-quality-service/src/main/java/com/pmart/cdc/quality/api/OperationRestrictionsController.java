package com.pmart.cdc.quality.api;

import com.pmart.cdc.quality.api.generated.OperationRestrictionsApi;
import com.pmart.cdc.quality.api.generated.model.CheckOperationRestrictions200Response;
import com.pmart.cdc.quality.api.generated.model.CheckOperationRestrictions200ResponseMatchesInner;
import com.pmart.cdc.quality.api.generated.model.CheckOperationRestrictionsRequest;
import com.pmart.cdc.quality.api.generated.model.LiftOperationRestrictionRequest;
import com.pmart.cdc.quality.api.generated.model.ListOperationRestrictions200Response;
import com.pmart.cdc.quality.api.generated.model.OperationRestriction;
import com.pmart.cdc.quality.api.generated.model.OperationRestrictionInput;
import com.pmart.cdc.quality.api.generated.model.RestrictedActivity;
import com.pmart.cdc.quality.application.RestrictionService;
import com.pmart.cdc.quality.application.RestrictionService.View;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 OperationRestrictionsApi — 운영 제한 걸기·풀기·조회·내부 확인(0.4.0). 업무 규칙은 {@link RestrictionService}. */
@RestController
public class OperationRestrictionsController implements OperationRestrictionsApi {

  private final RestrictionService restrictions;

  public OperationRestrictionsController(RestrictionService restrictions) {
    this.restrictions = restrictions;
  }

  @Override
  public ResponseEntity<OperationRestriction> applyOperationRestriction(
      String cdcId, String idempotencyKey, String xSourceSystem, OperationRestrictionInput body) {
    String id =
        restrictions.apply(
            idempotencyKey,
            xSourceSystem,
            cdcId,
            new RestrictionService.ApplyCmd(
                body.getActivities().stream().map(RestrictedActivity::getValue).toList(),
                body.getTemperatureZone() == null ? null : body.getTemperatureZone().getValue(),
                body.getSkus() == null ? List.of() : body.getSkus(),
                body.getReason().getValue(),
                body.getApprovalRef(),
                body.getPlannedUntil() == null ? null : body.getPlannedUntil().toInstant(),
                body.getNote()));
    return ResponseEntity.status(HttpStatus.CREATED).body(dto(restrictions.get(cdcId, id)));
  }

  @Override
  public ResponseEntity<ListOperationRestrictions200Response> listOperationRestrictions(
      String cdcId, @Nullable String state, @Nullable Integer size, @Nullable String cursor) {
    int n = size == null ? 50 : size;
    List<View> items = restrictions.list(cdcId, state, cursor, n);
    var out = new ListOperationRestrictions200Response();
    out.setItems(items.stream().map(OperationRestrictionsController::dto).toList());
    out.setNextCursor(items.size() < n ? null : items.get(items.size() - 1).restrictionId());
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<OperationRestriction> getOperationRestriction(
      String cdcId, String restrictionId) {
    return ResponseEntity.ok(dto(restrictions.get(cdcId, restrictionId)));
  }

  @Override
  public ResponseEntity<OperationRestriction> liftOperationRestriction(
      String cdcId,
      String restrictionId,
      String idempotencyKey,
      String xSourceSystem,
      LiftOperationRestrictionRequest body) {
    restrictions.lift(
        idempotencyKey,
        xSourceSystem,
        cdcId,
        restrictionId,
        body == null ? null : body.getApprovalRef(),
        body == null ? null : body.getNote());
    return ResponseEntity.ok(dto(restrictions.get(cdcId, restrictionId)));
  }

  @Override
  public ResponseEntity<CheckOperationRestrictions200Response> checkOperationRestrictions(
      String idempotencyKey, String xSourceSystem, CheckOperationRestrictionsRequest body) {
    var matches =
        restrictions.check(
            body.getCdcId(),
            body.getActivity().getValue(),
            body.getItems().stream()
                .map(i -> new RestrictionService.Item(i.getSku(), i.getTemperatureZone()))
                .toList());
    var out = new CheckOperationRestrictions200Response();
    out.setRestricted(!matches.isEmpty());
    out.setMatches(
        matches.stream()
            .map(
                m ->
                    new CheckOperationRestrictions200ResponseMatchesInner()
                        .restrictionId(m.restrictionId())
                        .reason(m.reason())
                        .sku(m.sku()))
            .toList());
    return ResponseEntity.ok(out);
  }

  static OperationRestriction dto(View v) {
    var r = new OperationRestriction();
    r.setRestrictionId(v.restrictionId());
    r.setCdcId(v.cdcId());
    r.setActivities(v.activities().stream().map(RestrictedActivity::fromValue).toList());
    r.setTemperatureZone(v.temperatureZone());
    r.setSkus(v.skus());
    r.setReason(v.reason());
    r.setApprovalRef(v.approvalRef());
    r.setPlannedUntil(HoldsController.time(v.plannedUntil()));
    r.setNote(v.note());
    r.setState(OperationRestriction.StateEnum.fromValue(v.state()));
    r.setAppliedBy(v.appliedBy());
    r.setAppliedAt(HoldsController.time(v.appliedAt()));
    r.setLiftedBy(v.liftedBy());
    r.setLiftApprovalRef(v.liftApprovalRef());
    r.setLiftNote(v.liftNote());
    r.setLiftedAt(HoldsController.time(v.liftedAt()));
    return r;
  }
}
