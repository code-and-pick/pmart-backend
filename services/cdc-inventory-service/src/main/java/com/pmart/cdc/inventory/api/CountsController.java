package com.pmart.cdc.inventory.api;

import com.pmart.cdc.inventory.api.generated.CountsApi;
import com.pmart.cdc.inventory.api.generated.model.CountLines;
import com.pmart.cdc.inventory.api.generated.model.CountSession;
import com.pmart.cdc.inventory.api.generated.model.CountSessionLinesInner;
import com.pmart.cdc.inventory.api.generated.model.CountSessionSheetInner;
import com.pmart.cdc.inventory.api.generated.model.DecideCountSessionRequest;
import com.pmart.cdc.inventory.api.generated.model.StartCountSessionRequest;
import com.pmart.cdc.inventory.application.CountService;
import com.pmart.cdc.inventory.application.CountService.CountLine;
import com.pmart.cdc.inventory.application.CountService.View;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 CountsApi — 위치 실사·재계수·CNT 조정(0.8.0). 업무 규칙은 {@link CountService}. */
@RestController
public class CountsController implements CountsApi {

  private final CountService counts;
  private final com.pmart.cdc.inventory.infrastructure.ProductNames names;

  public CountsController(
      CountService counts, com.pmart.cdc.inventory.infrastructure.ProductNames names) {
    this.counts = counts;
    this.names = names;
  }

  @Override
  public ResponseEntity<CountSession> startCountSession(
      String cdcId, String idempotencyKey, String xSourceSystem, StartCountSessionRequest body) {
    var v =
        counts
            .start(idempotencyKey, xSourceSystem, cdcId, body.getLocationCodes(), body.getReason())
            .value();
    return ResponseEntity.status(HttpStatus.CREATED).body(one(dto(v)));
  }

  @Override
  public ResponseEntity<com.pmart.cdc.inventory.api.generated.model.ListCountSessions200Response>
      listCountSessions(
          String cdcId,
          @Nullable String state,
          @Nullable String number,
          Integer page,
          Integer size) {
    var p = counts.list(cdcId, state, number, page == null ? 0 : page, size == null ? 50 : size);
    var out = new com.pmart.cdc.inventory.api.generated.model.ListCountSessions200Response();
    out.setItems(
        new java.util.ArrayList<>(named(p.items().stream().map(CountsController::dto).toList())));
    out.setPage(p.page());
    out.setSize(p.size());
    out.setHasNext(p.hasNext());
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<CountSession> getCountSession(String cdcId, String sessionId) {
    return ResponseEntity.ok(one(dto(counts.get(cdcId, sessionId))));
  }

  @Override
  public ResponseEntity<CountSession> submitFirstCount(
      String cdcId,
      String sessionId,
      String idempotencyKey,
      String xSourceSystem,
      CountLines body) {
    return ResponseEntity.ok(
        one(
            dto(
                counts
                    .first(idempotencyKey, xSourceSystem, cdcId, sessionId, lines(body))
                    .value())));
  }

  @Override
  public ResponseEntity<CountSession> submitRecount(
      String cdcId,
      String sessionId,
      String idempotencyKey,
      String xSourceSystem,
      CountLines body) {
    return ResponseEntity.ok(
        one(
            dto(
                counts
                    .recount(idempotencyKey, xSourceSystem, cdcId, sessionId, lines(body))
                    .value())));
  }

  @Override
  public ResponseEntity<CountSession> decideCountSession(
      String cdcId,
      String sessionId,
      String idempotencyKey,
      String xSourceSystem,
      DecideCountSessionRequest body) {
    var v =
        counts
            .decide(
                idempotencyKey,
                xSourceSystem,
                cdcId,
                sessionId,
                body.getDecision().getValue(),
                body.getReason(),
                body.getApprovalRef())
            .value();
    return ResponseEntity.ok(one(dto(v)));
  }

  /** 0.17.0 CI-1: 계수표·줄에 상품 이름(product internalName — 모르면 null). */
  private List<CountSession> named(List<CountSession> items) {
    java.util.Set<String> skus = new java.util.TreeSet<>();
    for (CountSession c : items) {
      if (c.getSheet() != null) {
        c.getSheet().forEach(x -> skus.add(x.getSku()));
      }
      if (c.getLines() != null) {
        c.getLines().forEach(x -> skus.add(x.getSku()));
      }
    }
    var n = names.of(skus);
    for (CountSession c : items) {
      if (c.getSheet() != null) {
        c.getSheet().forEach(x -> x.setProductName(n.get(x.getSku())));
      }
      if (c.getLines() != null) {
        c.getLines().forEach(x -> x.setProductName(n.get(x.getSku())));
      }
    }
    return items;
  }

  private CountSession one(CountSession c) {
    named(List.of(c));
    return c;
  }

  private static List<CountLine> lines(CountLines body) {
    return body.getLines().stream()
        .map(l -> new CountLine(l.getLocationCode(), l.getSku(), l.getLot(), l.getQty()))
        .toList();
  }

  static CountSession dto(View v) {
    var s = v.session();
    var out = new CountSession();
    out.setSessionId(s.sessionId());
    out.setSessionNumber(s.sessionNumber());
    out.setCdcId(s.cdcId());
    out.setState(CountSession.StateEnum.fromValue(s.state()));
    out.setLocationCodes(v.locations());
    out.setSheet(
        v.sheet().stream()
            .map(
                k -> {
                  var x = new CountSessionSheetInner();
                  x.setLocationCode(k.locationCode());
                  x.setSku(k.sku());
                  x.setLot(k.lot());
                  return x;
                })
            .toList());
    out.setLines(
        v.lines().stream()
            .map(
                l -> {
                  var x = new CountSessionLinesInner();
                  x.setLocationCode(l.locationCode());
                  x.setSku(l.sku());
                  x.setLot(l.lot());
                  x.setBookQty(l.bookQty());
                  x.setFirstQty(l.firstQty());
                  x.setFinalQty(l.finalQty());
                  x.setDeltaQty(l.deltaQty());
                  return x;
                })
            .toList());
    out.setStartedBy(s.startedBy());
    out.setFirstCounterId(s.firstCounterId());
    out.setRecounterId(s.recounterId());
    out.setDecidedBy(s.decidedBy());
    out.setApprovalRef(s.approvalRef());
    out.setCommitId(s.commitId());
    out.setReason(s.reason());
    out.setCreatedAt(Dto.time(s.createdAt()));
    out.setDecidedAt(Dto.time(s.decidedAt()));
    return out;
  }
}
