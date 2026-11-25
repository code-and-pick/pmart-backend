package com.pmart.cdc.outbound.api;

import com.pmart.cdc.outbound.api.generated.WavesApi;
import com.pmart.cdc.outbound.api.generated.model.CreateWaveRequest;
import com.pmart.cdc.outbound.api.generated.model.CreateWaveRequestPlannedDepartureWindow;
import com.pmart.cdc.outbound.api.generated.model.IntakeTransfer202Response;
import com.pmart.cdc.outbound.api.generated.model.ListRegions200Response;
import com.pmart.cdc.outbound.api.generated.model.Region;
import com.pmart.cdc.outbound.api.generated.model.SetRegionsRequest;
import com.pmart.cdc.outbound.api.generated.model.Wave;
import com.pmart.cdc.outbound.api.generated.model.WaveState;
import com.pmart.cdc.outbound.api.generated.model.WaveTaskSummary;
import com.pmart.cdc.outbound.application.RegionService;
import com.pmart.cdc.outbound.application.RegionService.Regions;
import com.pmart.cdc.outbound.application.WaveService;
import com.pmart.cdc.outbound.application.WaveService.Create;
import com.pmart.cdc.outbound.application.WaveService.WaveView;
import com.pmart.cdc.outbound.infrastructure.OutboundStore.RegionRow;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 WavesApi — Wave 생성·조회·릴리스(0.3.0). */
@RestController
public class WavesController implements WavesApi {

  private final WaveService waves;
  private final RegionService regions;

  public WavesController(WaveService waves, RegionService regions) {
    this.waves = waves;
    this.regions = regions;
  }

  @Override
  public ResponseEntity<ListRegions200Response> listRegions(String cdcId) {
    return ResponseEntity.ok(regions(regions.list(cdcId)));
  }

  @Override
  public ResponseEntity<ListRegions200Response> setRegions(
      String cdcId, String idempotencyKey, String xSourceSystem, SetRegionsRequest body) {
    var items =
        body.getItems().stream()
            .map(i -> new RegionRow(i.getMfcId(), i.getRegionCode(), i.getRegionName()))
            .toList();
    return ResponseEntity.ok(
        regions(regions.set(idempotencyKey, xSourceSystem, cdcId, items).value()));
  }

  private static ListRegions200Response regions(Regions r) {
    var out = new ListRegions200Response();
    out.setItems(
        r.items().stream()
            .map(
                x -> {
                  var y = new Region();
                  y.setRegionCode(x.regionCode());
                  y.setRegionName(x.regionName());
                  y.setMfcIds(x.mfcIds());
                  return y;
                })
            .toList());
    return out;
  }

  @Override
  public ResponseEntity<Wave> createWave(
      String idempotencyKey,
      String xSourceSystem,
      CreateWaveRequest body,
      @Nullable String traceparent) {
    var w = body.getPlannedDepartureWindow();
    var v =
        waves
            .create(
                idempotencyKey,
                xSourceSystem,
                new Create(
                    body.getCdcId(),
                    body.getTrfIds(),
                    w == null || w.getStart() == null ? null : w.getStart().toInstant(),
                    w == null || w.getEnd() == null ? null : w.getEnd().toInstant(),
                    body.getTemperatureZone() == null
                        ? null
                        : body.getTemperatureZone().getValue()))
            .value();
    return ResponseEntity.status(HttpStatus.CREATED).body(dto(v));
  }

  @Override
  public ResponseEntity<Wave> getWave(String waveId) {
    return ResponseEntity.ok(dto(waves.get(waveId)));
  }

  @Override
  public ResponseEntity<IntakeTransfer202Response> releaseWave(
      String waveId,
      String idempotencyKey,
      String xSourceSystem,
      @Nullable Integer xExpectedVersion) {
    var r = waves.release(idempotencyKey, xSourceSystem, waveId, xExpectedVersion).value();
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(Dto.command(r));
  }

  static Wave dto(WaveView v) {
    var w = v.wave();
    var out = new Wave();
    out.setWaveId(w.waveId());
    out.setCdcId(w.cdcId());
    out.setState(WaveState.fromValue(w.state()));
    out.setVersion(w.version());
    out.setTrfIds(v.trfIds());
    if (w.windowStart() != null || w.windowEnd() != null) {
      var win = new CreateWaveRequestPlannedDepartureWindow();
      win.setStart(Dto.time(w.windowStart()));
      win.setEnd(Dto.time(w.windowEnd()));
      out.setPlannedDepartureWindow(win);
    }
    var s = new WaveTaskSummary();
    s.setTotal(v.total());
    s.setDone(v.done());
    s.setException(v.exception());
    out.setTaskSummary(s);
    out.setCreatedAt(Dto.time(w.createdAt()));
    out.setReleasedAt(Dto.time(w.releasedAt()));
    return out;
  }
}
