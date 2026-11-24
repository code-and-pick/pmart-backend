package com.pmart.cdc.outbound.api;

import com.pmart.cdc.outbound.api.generated.ResultsApi;
import com.pmart.cdc.outbound.api.generated.model.GetWorkResult200Response;
import com.pmart.cdc.outbound.api.generated.model.IntakeTransfer202Response;
import com.pmart.cdc.outbound.api.generated.model.ReportInspectionResultRequest;
import com.pmart.cdc.outbound.api.generated.model.ReportLoadingResultRequest;
import com.pmart.cdc.outbound.api.generated.model.ReportSortingResultRequest;
import com.pmart.cdc.outbound.application.ResultService;
import com.pmart.cdc.outbound.application.ResultService.Inspect;
import com.pmart.cdc.outbound.application.ResultService.InspectItem;
import com.pmart.cdc.outbound.application.ResultService.Load;
import com.pmart.cdc.outbound.application.ResultService.LoadLpn;
import com.pmart.cdc.outbound.application.ResultService.Sort;
import com.pmart.cdc.outbound.application.ResultService.SortLpn;
import com.pmart.cdc.outbound.infrastructure.LpnStore.Content;
import java.sql.Timestamp;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 ResultsApi — 분류·검품·상차 결과와 결과 조회(0.4.0). 업무 규칙은 application 에 있다. */
@RestController
public class ResultsController implements ResultsApi {

  private final ResultService results;

  public ResultsController(ResultService results) {
    this.results = results;
  }

  @Override
  public ResponseEntity<IntakeTransfer202Response> reportSortingResult(
      String idempotencyKey,
      String xSourceSystem,
      ReportSortingResultRequest body,
      @Nullable String traceparent) {
    List<SortLpn> lpns =
        body.getLpns().stream()
            .map(
                l ->
                    new SortLpn(
                        l.getLpn(),
                        l.getUnitType().getValue(),
                        l.getState().getValue(),
                        l.getParentLpn(),
                        l.getSealNo(),
                        l.getTemperatureZone() == null ? null : l.getTemperatureZone().getValue(),
                        l.getWeightG(),
                        l.getContents() == null
                            ? List.of()
                            : l.getContents().stream()
                                .map(c -> new Content(c.getSku(), c.getLot(), c.getQty()))
                                .toList()))
            .toList();
    var r =
        results
            .sort(
                idempotencyKey,
                xSourceSystem,
                new Sort(body.getWaveId(), body.getTrfId(), body.getWorkerId(), lpns))
            .value();
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(Dto.command(r));
  }

  @Override
  public ResponseEntity<IntakeTransfer202Response> reportInspectionResult(
      String idempotencyKey,
      String xSourceSystem,
      ReportInspectionResultRequest body,
      @Nullable String traceparent) {
    var items =
        body.getItems().stream()
            .map(
                i ->
                    new InspectItem(
                        i.getLpn(),
                        i.getResult().getValue(),
                        i.getFailReason() == null ? null : i.getFailReason().getValue()))
            .toList();
    var r =
        results
            .inspect(
                idempotencyKey,
                xSourceSystem,
                new Inspect(body.getTrfId(), body.getInspectorId(), items))
            .value();
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(Dto.command(r));
  }

  @Override
  public ResponseEntity<IntakeTransfer202Response> reportLoadingResult(
      String idempotencyKey,
      String xSourceSystem,
      ReportLoadingResultRequest body,
      @Nullable String traceparent) {
    var lpns =
        body.getLpns().stream()
            .map(l -> new LoadLpn(l.getLpn(), l.getCompartment(), l.getSealNo()))
            .toList();
    var r =
        results
            .load(
                idempotencyKey,
                xSourceSystem,
                new Load(
                    body.getTrfId(),
                    body.getTripId(),
                    body.getVehicleId(),
                    body.getDockCode(),
                    body.getWorkerId(),
                    body.getAction().getValue(),
                    lpns))
            .value();
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(Dto.command(r));
  }

  @Override
  public ResponseEntity<GetWorkResult200Response> getWorkResult(String workResultId) {
    var r = results.workResult(workResultId);
    var out = new GetWorkResult200Response();
    out.setWorkResultId(String.valueOf(r.get("work_result_id")));
    out.setKind(GetWorkResult200Response.KindEnum.fromValue(String.valueOf(r.get("kind"))));
    out.setTrfId(String.valueOf(r.get("trf_id")));
    out.setStatus(GetWorkResult200Response.StatusEnum.fromValue(String.valueOf(r.get("status"))));
    out.setRejectReason((String) r.get("reject_reason"));
    Object at = r.get("recorded_at");
    if (at instanceof Timestamp ts) {
      out.setRecordedAt(Dto.time(ts.toInstant()));
    } else if (at instanceof java.time.OffsetDateTime odt) {
      out.setRecordedAt(odt);
    }
    return ResponseEntity.ok(out);
  }
}
