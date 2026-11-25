package com.pmart.cdc.outbound.api;

import com.pmart.cdc.outbound.api.generated.TransfersApi;
import com.pmart.cdc.outbound.api.generated.model.CancelBeforeDepartureRequest;
import com.pmart.cdc.outbound.api.generated.model.GetPreDepartureCancellation200Response;
import com.pmart.cdc.outbound.api.generated.model.IntakeTransfer202Response;
import com.pmart.cdc.outbound.application.CancellationService;
import com.pmart.cdc.outbound.application.CancellationService.Cancel;
import com.pmart.cdc.outbound.application.CancellationService.Reduce;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 TransfersApi — 미출발 취소·축소(0.7.0, 피킹 시작 전 범위)와 그 결과 조회. */
@RestController
public class TransfersController implements TransfersApi {

  private final CancellationService cancellations;

  public TransfersController(CancellationService cancellations) {
    this.cancellations = cancellations;
  }

  @Override
  public ResponseEntity<IntakeTransfer202Response> cancelBeforeDeparture(
      String cdcId,
      String trfId,
      String idempotencyKey,
      String xSourceSystem,
      CancelBeforeDepartureRequest body,
      @Nullable Integer xExpectedVersion) {
    List<Reduce> lines =
        body.getLines() == null
            ? List.of()
            : body.getLines().stream().map(l -> new Reduce(l.getSku(), l.getReduceQty())).toList();
    var r =
        cancellations.cancel(
            new Cancel(
                cdcId,
                trfId,
                idempotencyKey,
                xSourceSystem,
                xExpectedVersion,
                body.getReason().getValue(),
                body.getScope().getValue(),
                body.getSourceRef(),
                lines));
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(Dto.command(r));
  }

  @Override
  public ResponseEntity<GetPreDepartureCancellation200Response> getPreDepartureCancellation(
      String cdcId, String trfId, String requestId) {
    var r = cancellations.get(cdcId, trfId, requestId);
    var out = new GetPreDepartureCancellation200Response();
    out.setRequestId(r.requestKey());
    out.setCancellationId(r.cancellationId());
    out.setScope(GetPreDepartureCancellation200Response.ScopeEnum.fromValue(r.scope()));
    out.setState(GetPreDepartureCancellation200Response.StateEnum.fromValue(r.state()));
    out.setReleasedQty(r.releasedQty());
    out.setUnloadedLpns(List.of());
    out.setReleaseCommitId(r.releaseCommitId());
    out.setReason(r.reason());
    out.setRejectReason(r.rejectReason());
    return ResponseEntity.ok(out);
  }
}
