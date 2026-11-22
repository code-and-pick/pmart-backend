package com.pmart.cases.api;

import com.pmart.cases.api.generated.ObligationsApi;
import com.pmart.cases.api.generated.model.EvidenceReference;
import com.pmart.cases.api.generated.model.ListCases200ResponsePage;
import com.pmart.cases.api.generated.model.ListObligations200Response;
import com.pmart.cases.api.generated.model.Obligation;
import com.pmart.cases.api.generated.model.ObligationAllOfResolutions;
import com.pmart.cases.api.generated.model.ObligationBody;
import com.pmart.cases.api.generated.model.ResolveObligationRequest;
import com.pmart.cases.application.ObligationService;
import com.pmart.cases.infrastructure.ObligationStore.ObligationRow;
import com.pmart.cases.infrastructure.ObligationStore.ResolutionRow;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 ObligationsApi — 사건 의무(0.8.0, 2026-11-16). 규칙은 {@link ObligationService}·{@code
 * domain/ObligationRules}, 여기서는 요청·응답 변환만.
 */
@RestController
public class ObligationsController implements ObligationsApi {

  private final ObligationService obligations;

  public ObligationsController(ObligationService obligations) {
    this.obligations = obligations;
  }

  @Override
  public ResponseEntity<Obligation> createObligation(
      String caseId,
      String idempotencyKey,
      String xSourceSystem,
      ObligationBody body,
      @Nullable Integer xExpectedVersion) {
    ObligationService.View v =
        obligations.create(
            idempotencyKey,
            xSourceSystem,
            caseId,
            new ObligationService.Created(
                body.getKind().getValue(),
                body.getOwner() == null ? null : body.getOwner().getValue(),
                body.getTargetRef(),
                body.getQty(),
                body.getAmount() == null ? null : body.getAmount().longValue(),
                body.getDueAt() == null ? null : body.getDueAt().toInstant(),
                body.getNote()),
            xExpectedVersion);
    return ResponseEntity.created(URI.create("/api/v1/obligations/" + v.row().obligationId()))
        .body(toModel(v));
  }

  @Override
  public ResponseEntity<Obligation> getObligation(String obligationId) {
    return ResponseEntity.ok(toModel(obligations.get(obligationId)));
  }

  @Override
  public ResponseEntity<ListObligations200Response> listObligations(
      @Nullable String owner,
      @Nullable String state,
      @Nullable String caseId,
      @Nullable String centerId,
      Integer size,
      @Nullable String cursor) {
    ObligationService.Page page =
        obligations.list(owner, state, caseId, centerId, size == null ? 20 : size, cursor);
    return ResponseEntity.ok(
        new ListObligations200Response()
            .items(page.items().stream().map(ObligationsController::toModel).toList())
            .page(new ListCases200ResponsePage().nextCursor(page.nextCursor())));
  }

  @Override
  public ResponseEntity<Obligation> resolveObligation(
      String obligationId,
      String idempotencyKey,
      String xSourceSystem,
      ResolveObligationRequest body,
      @Nullable Integer xExpectedVersion) {
    EvidenceReference ev = body.getEvidence();
    return ResponseEntity.ok(
        toModel(
            obligations.resolve(
                idempotencyKey,
                xSourceSystem,
                obligationId,
                new ObligationService.Resolve(
                    body.getOutcome().getValue(),
                    body.getResolvedQty(),
                    body.getResolvedAmount() == null ? null : body.getResolvedAmount().longValue(),
                    ev.getSystem(),
                    ev.getRef(),
                    ev.getKind() == null ? null : ev.getKind().getValue(),
                    ev.getAsOf() == null ? null : ev.getAsOf().toInstant(),
                    body.getNote()),
                xExpectedVersion)));
  }

  /** 사건 상세도 쓴다. */
  static Obligation toModel(ObligationService.View v) {
    ObligationRow o = v.row();
    Obligation m =
        new Obligation()
            .obligationId(o.obligationId())
            .caseId(o.caseId())
            .kind(Obligation.KindEnum.fromValue(o.kind()))
            .owner(Obligation.OwnerEnum.fromValue(o.owner()))
            .targetRef(o.targetRef())
            .qty(o.qty())
            .amount(o.amount() == null ? null : Math.toIntExact(o.amount()))
            .dueAt(odt(o.dueAt()))
            .note(o.note())
            .state(Obligation.StateEnum.fromValue(o.state()))
            .resolvedQty(o.resolvedQty())
            .resolvedAmount(Math.toIntExact(o.resolvedAmount()))
            .version(o.version())
            .createdAt(odt(o.createdAt()));
    for (ResolutionRow r : v.resolutions()) {
      EvidenceReference ev =
          new EvidenceReference()
              .system(r.evidenceSystem())
              .ref(r.evidenceRef())
              .kind(
                  r.evidenceKind() == null
                      ? null
                      : EvidenceReference.KindEnum.fromValue(r.evidenceKind()))
              .asOf(odt(r.evidenceAsOf()));
      m.addResolutionsItem(
          new ObligationAllOfResolutions()
              .outcome(r.outcome())
              .qty(r.qty())
              .amount(r.amount() == null ? null : Math.toIntExact(r.amount()))
              .evidence(ev)
              .note(r.note())
              .resolvedBy(r.resolvedBy())
              .resolvedAt(odt(r.resolvedAt())));
    }
    return m;
  }

  private static @Nullable OffsetDateTime odt(@Nullable Instant i) {
    return i == null ? null : i.atOffset(ZoneOffset.UTC);
  }
}
