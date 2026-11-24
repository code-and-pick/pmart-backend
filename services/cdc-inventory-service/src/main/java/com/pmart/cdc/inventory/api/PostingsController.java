package com.pmart.cdc.inventory.api;

import com.pmart.cdc.inventory.api.generated.PostingsApi;
import com.pmart.cdc.inventory.api.generated.model.PostDepartureRequest;
import com.pmart.cdc.inventory.api.generated.model.PostInitialBalanceRequest;
import com.pmart.cdc.inventory.api.generated.model.ReleaseAllocation200Response;
import com.pmart.cdc.inventory.api.generated.model.SetSafetyStocksRequest;
import com.pmart.cdc.inventory.application.DepartureService;
import com.pmart.cdc.inventory.application.DepartureService.Depart;
import com.pmart.cdc.inventory.application.DepartureService.DepartLine;
import com.pmart.cdc.inventory.application.StockSetupService;
import com.pmart.cdc.inventory.application.StockSetupService.InitialBalance;
import com.pmart.cdc.inventory.application.StockSetupService.InitialLine;
import com.pmart.cdc.inventory.application.StockSetupService.SafetyLine;
import com.pmart.cdc.inventory.application.StockSetupService.SafetyStocks;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 PostingsApi — 초기 보유·안전량·TOUT 구현(0.2.0). 수령 확인·이동·품질·손실·조정은 아직 인터페이스 default(501). 업무 규칙은
 * application/domain 에 있다.
 */
@RestController
public class PostingsController implements PostingsApi {

  private final StockSetupService setup;
  private final DepartureService departures;

  public PostingsController(StockSetupService setup, DepartureService departures) {
    this.setup = setup;
    this.departures = departures;
  }

  @Override
  public ResponseEntity<ReleaseAllocation200Response> postInitialBalance(
      String idempotencyKey,
      String xSourceSystem,
      PostInitialBalanceRequest body,
      @Nullable String traceparent) {
    List<InitialLine> lines =
        body.getLines().stream()
            .map(
                l ->
                    new InitialLine(
                        l.getSku(),
                        l.getLot(),
                        l.getLotIdentification() == null
                            ? null
                            : l.getLotIdentification().getValue(),
                        l.getExpiresAt(),
                        l.getLocationCode(),
                        l.getQty(),
                        l.getQualityState().getValue()))
            .toList();
    return ResponseEntity.ok(
        Dto.command(
            setup.initialBalance(
                idempotencyKey,
                xSourceSystem,
                new InitialBalance(body.getCdcId(), body.getApprovalRef(), lines))));
  }

  @Override
  public ResponseEntity<ReleaseAllocation200Response> setSafetyStocks(
      String idempotencyKey,
      String xSourceSystem,
      SetSafetyStocksRequest body,
      @Nullable String traceparent) {
    List<SafetyLine> lines =
        body.getLines().stream().map(l -> new SafetyLine(l.getSku(), l.getSafetyQty())).toList();
    return ResponseEntity.ok(
        Dto.command(
            setup.safetyStocks(
                idempotencyKey,
                xSourceSystem,
                new SafetyStocks(body.getCdcId(), body.getApprovalRef(), lines))));
  }

  @Override
  public ResponseEntity<ReleaseAllocation200Response> postDeparture(
      String idempotencyKey,
      String xSourceSystem,
      PostDepartureRequest body,
      @Nullable String traceparent) {
    List<DepartLine> lines =
        body.getLines().stream()
            .map(
                l ->
                    new DepartLine(
                        l.getDepartureLineId(),
                        l.getSku(),
                        l.getLot(),
                        l.getLocationCode(),
                        l.getQty(),
                        l.getLpns() == null ? List.of() : List.copyOf(l.getLpns())))
            .toList();
    var cmd =
        new Depart(
            body.getCdcId(),
            body.getDepartureId(),
            body.getDepartureSource() == null ? "REAL" : body.getDepartureSource().getValue(),
            body.getToMfcId(),
            body.getTrfId(),
            body.getTrfVersion(),
            body.getAllocationId(),
            body.getShipmentManifestId(),
            body.getDepartedAt().toInstant(),
            lines);
    return ResponseEntity.ok(Dto.command(departures.depart(idempotencyKey, xSourceSystem, cmd)));
  }
}
