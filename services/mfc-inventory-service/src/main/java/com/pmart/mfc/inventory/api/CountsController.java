package com.pmart.mfc.inventory.api;

import com.pmart.mfc.inventory.api.generated.CountsApi;
import com.pmart.mfc.inventory.api.generated.model.CountSession;
import com.pmart.mfc.inventory.api.generated.model.CountSessionDifferencesInner;
import com.pmart.mfc.inventory.api.generated.model.CountSessionSheetInner;
import com.pmart.mfc.inventory.api.generated.model.CreateCountSessionRequest;
import com.pmart.mfc.inventory.api.generated.model.DecideCountSessionRequest;
import com.pmart.mfc.inventory.api.generated.model.SubmitCountRequest;
import com.pmart.mfc.inventory.application.CountService;
import com.pmart.mfc.inventory.application.SkuProjector;
import com.pmart.mfc.inventory.domain.CountRules.CountLine;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 CountsApi — 순환 실사(IV-11, 0.10.0). 업무 규칙은 {@link CountService}·{@code CountRules}. */
@RestController
public class CountsController implements CountsApi {

  private final CountService service;
  private final SkuProjector skus;

  public CountsController(CountService service, SkuProjector skus) {
    this.service = service;
    this.skus = skus;
  }

  @Override
  public ResponseEntity<CountSession> createCountSession(
      String mfcId, String idempotencyKey, String xSourceSystem, CreateCountSessionRequest body) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            dto(
                service.create(
                    mfcId,
                    idempotencyKey,
                    xSourceSystem,
                    body.getLocationCodes(),
                    body.getReason())));
  }

  @Override
  public ResponseEntity<CountSession> getCountSession(String mfcId, String sessionId) {
    return ResponseEntity.ok(dto(service.view(mfcId, sessionId)));
  }

  @Override
  public ResponseEntity<CountSession> submitCount(
      String mfcId,
      String sessionId,
      String idempotencyKey,
      String xSourceSystem,
      SubmitCountRequest body) {
    var lines =
        body.getLines().stream()
            .map(
                l ->
                    new CountLine(l.getLocationCode(), l.getSku(), l.getLotId(), l.getCountedQty()))
            .toList();
    return ResponseEntity.ok(dto(service.count(mfcId, sessionId, lines)));
  }

  @Override
  public ResponseEntity<CountSession> decideCountSession(
      String mfcId,
      String sessionId,
      String idempotencyKey,
      String xSourceSystem,
      DecideCountSessionRequest body) {
    return ResponseEntity.ok(
        dto(
            service.decide(
                mfcId,
                sessionId,
                body.getAction().getValue(),
                body.getApprovalRef(),
                body.getNote())));
  }

  private CountSession dto(CountService.View v) {
    var s = v.row();
    // (0.16.0, BR-52 R19-1) 계수표 줄의 상품명·바코드 — 이 서비스의 SKU 사본(상품 사건 투영). 사본이 없으면 null·빈 배열
    var names = skus.projections(v.book().stream().map(b -> b.sku()).distinct().toList());
    var out = new CountSession();
    out.setSessionId(s.sessionId());
    out.setMfcId(s.mfcId());
    out.setState(CountSession.StateEnum.fromValue(s.state()));
    out.setLocationCodes(v.locations());
    out.setReason(s.reason());
    out.setCreatedBy(s.createdBy());
    out.setFirstCounter(s.firstCounter());
    out.setRecounter(s.recounter());
    out.setApproverId(s.approverId());
    out.setApprovalRef(s.approvalRef());
    out.setBaselineAt(SkuHandlingsController.time(s.baselineAt()));
    out.setSheet(
        v.book().stream()
            .map(
                b -> {
                  var x = new CountSessionSheetInner();
                  x.setLocationCode(b.locationCode());
                  x.setSku(b.sku());
                  var p = names.get(b.sku());
                  x.setProductName(p == null ? null : p.productName());
                  x.setBarcodes(
                      p == null
                          ? new java.util.ArrayList<>()
                          : new java.util.ArrayList<>(p.barcodes()));
                  x.setLotId(b.lotId());
                  var lot = v.lots().get(b.lotId());
                  x.setExpiryDate(lot == null ? null : lot.expiryDate());
                  return x;
                })
            .toList());
    out.setDifferences(
        v.differences() == null
            ? null
            : v.differences().stream()
                .map(
                    d -> {
                      var x = new CountSessionDifferencesInner();
                      x.setLocationCode(d.locationCode());
                      x.setSku(d.sku());
                      x.setLotId(d.lotId());
                      x.setBookQty(d.bookQty());
                      x.setHeldQty(d.heldQty());
                      x.setCountedQty(d.countedQty());
                      x.setDeltaQty(d.delta());
                      return x;
                    })
                .toList());
    out.setCommitId(s.commitId());
    out.setVersion(s.version());
    out.setUpdatedAt(SkuHandlingsController.time(s.updatedAt()));
    return out;
  }
}
