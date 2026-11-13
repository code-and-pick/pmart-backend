package com.pmart.mfc.inbound.api;

import com.pmart.mfc.inbound.api.generated.model.InitialStockLoad;
import com.pmart.mfc.inbound.api.generated.model.InitialStockLoadLinesInner;
import com.pmart.mfc.inbound.infrastructure.InitialStockStore.LoadRow;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/** 반입 기록 → 계약 DTO. */
final class InitialStockLoads {

  private InitialStockLoads() {}

  static InitialStockLoad dto(LoadRow r) {
    return dto(r, java.util.Map.of());
  }

  /** labels: 줄 SKU 의 상품명·바코드(0.8.0). 없으면 null·빈 배열. */
  static InitialStockLoad dto(
      LoadRow r, java.util.Map<String, com.pmart.mfc.inbound.application.SkuLabels.Label> labels) {
    var d = new InitialStockLoad();
    d.setReceiptId(r.receiptId());
    d.setApprovalId(r.approvalId());
    d.setMfcId(r.mfcId());
    d.setState(InitialStockLoad.StateEnum.fromValue(r.state()));
    d.setRejectReason(r.rejectReason());
    d.setInventoryCommitId(r.commitId());
    d.setAttempts(r.attempts());
    d.setLastError(r.lastError());
    List<InitialStockLoadLinesInner> lines = new ArrayList<>();
    r.lines()
        .forEach(
            (sku, qty) -> {
              var l = new InitialStockLoadLinesInner();
              l.setSku(sku);
              var label = com.pmart.mfc.inbound.application.SkuLabels.get(labels, sku);
              l.setProductName(label.productName());
              l.setBarcodes(new ArrayList<>(label.barcodes()));
              l.setQty(qty);
              l.setReceivedQty(r.received().get(sku));
              lines.add(l);
            });
    d.setLines(lines);
    d.setReceivingLocation(r.receivingLocation());
    d.setReceivedAt(r.receivedAt() == null ? null : r.receivedAt().atOffset(ZoneOffset.UTC));
    d.setCreatedAt(r.createdAt().atOffset(ZoneOffset.UTC));
    d.setAppliedAt(r.appliedAt() == null ? null : r.appliedAt().atOffset(ZoneOffset.UTC));
    return d;
  }
}
