package com.pmart.cdc.outbound.application;

import com.pmart.cdc.outbound.infrastructure.InventoryClient;
import com.pmart.cdc.outbound.infrastructure.LpnStore;
import com.pmart.cdc.outbound.infrastructure.LpnStore.Content;
import com.pmart.cdc.outbound.infrastructure.LpnStore.DepartureRow;
import com.pmart.cdc.outbound.infrastructure.LpnStore.DepartureTransferRow;
import com.pmart.cdc.outbound.infrastructure.LpnStore.LpnRow;
import com.pmart.cdc.outbound.infrastructure.LpnStore.ManifestLine;
import com.pmart.cdc.outbound.infrastructure.LpnStore.MismatchRow;
import com.pmart.cdc.outbound.infrastructure.OutboundStore;
import com.pmart.cdc.outbound.infrastructure.OutboundStore.TransferRow;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * 출발 검증(0.4.0, TruckActuallyDeparted 역할 — 칠곡 CDC-9 에선 시뮬레이터가 LAN 내부 경로로 부른다). 출발 구성과 상차 LOADED 를
 * BOX 로 대조해 불일치를 남기고, 일치 TRF 만 cdc-inventory TOUT → 남은 할당 해제(RESIDUAL_CLOSED) → 매니페스트. 대조 기록은 한
 * 트랜잭션, 재고 호출은 트랜잭션 밖 — 불명이면 UNKNOWN_RESULT 로 두고 같은 본문 재전송이 같은 키로 다시 부른다. SIMULATED 출발도 TOUT 한다(재고
 * 기록에 SIMULATED 표시).
 */
@Service
public class DepartureService {

  private static final Logger log = LoggerFactory.getLogger(DepartureService.class);

  public record Composition(
      String trfId, @Nullable String mfcId, List<String> lpns, List<String> sealNos) {}

  public record Validate(
      String departureId,
      String tripId,
      String vehicleId,
      Instant departedAt,
      String source,
      @Nullable String evidenceRef,
      List<Composition> compositions) {}

  public record View(
      DepartureRow departure, List<DepartureTransferRow> transfers, List<MismatchRow> mismatches) {}

  public record Manifest(
      String manifestId,
      TransferRow transfer,
      DepartureRow departure,
      List<LpnRow> lpns,
      Map<String, List<Content>> contents,
      List<ManifestLine> lines) {}

  private final OutboundStore store;
  private final LpnStore lpns;
  private final InventoryClient inventory;
  private final TransactionTemplate tx;
  private final Support support;

  public DepartureService(
      OutboundStore store,
      LpnStore lpns,
      InventoryClient inventory,
      TransactionTemplate tx,
      Support support) {
    this.store = store;
    this.lpns = lpns;
    this.inventory = inventory;
    this.tx = tx;
    this.support = support;
  }

  public View validate(Validate cmd) {
    if (cmd.compositions().isEmpty()
        || new TreeSet<>(cmd.compositions().stream().map(Composition::trfId).toList()).size()
            != cmd.compositions().size()) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED, "trfCompositions 가 비었거나 같은 TRF 가 두 번 있습니다.");
    }
    String hash = hash(cmd);
    // 1) 대조·기록(이미 있으면 같은 본문인지만)
    tx.executeWithoutResult(s -> record(cmd, hash));
    // 2) 일치 TRF 중 아직 반영 안 된 것 TOUT (트랜잭션 밖)
    for (DepartureTransferRow t : lpns.departureTransfers(cmd.departureId())) {
      if (!"MATCHED".equals(t.result()) || "REJECTED".equals(t.postingStatus())) {
        continue;
      }
      if (!"APPLIED".equals(t.postingStatus())) {
        post(cmd, t);
      } else if (!"DONE".equals(t.residualStatus())) {
        releaseResidual(cmd, store.transfer(t.trfId(), false).orElseThrow());
      }
    }
    return view(cmd.departureId());
  }

  private void record(Validate cmd, String hash) {
    Optional<DepartureRow> prior = lpns.departure(cmd.departureId(), true);
    if (prior.isPresent()) {
      if (!prior.get().bodyHash().equals(hash)) {
        throw new PmartException(
            "DEPARTURE_ALREADY_VALIDATED",
            HttpStatus.CONFLICT,
            "출발 " + cmd.departureId() + " 은 이미 다른 구성으로 검증했습니다.",
            false,
            List.of());
      }
      return;
    }
    Instant at = support.now();
    List<MismatchRow> mismatches = new ArrayList<>();
    List<DepartureTransferRow> rows = new ArrayList<>();
    Set<String> departing = new LinkedHashSet<>();
    for (int i = 0; i < cmd.compositions().size(); i++) {
      Composition c = cmd.compositions().get(i);
      TransferRow t =
          store
              .transfer(c.trfId(), true)
              .orElseThrow(() -> PmartException.invalidState("TRF " + c.trfId() + " 가 없습니다."));
      if (lpns.departureIdOfTrf(t.trfId()).isPresent() || "DEPARTED".equals(t.state())) {
        throw PmartException.invalidState("TRF " + t.trfId() + " 는 이미 다른 출발로 나갔습니다.");
      }
      Map<String, LpnRow> mine = ResultService.byLpn(lpns.lockOfTrf(t.trfId()));
      List<MismatchRow> m = new ArrayList<>();
      if (c.mfcId() != null && !c.mfcId().equals(t.mfcId())) {
        m.add(
            new MismatchRow(
                "WRONG_MFC", null, t.trfId(), "목적지 " + t.mfcId() + " ≠ 출발 " + c.mfcId()));
      }
      // 출발 구성 → BOX
      Set<String> declared = new LinkedHashSet<>();
      Map<String, LpnRow> others = lpns.find(c.lpns());
      for (String id : c.lpns()) {
        LpnRow l = mine.get(id);
        if (l == null) {
          LpnRow o = others.get(id);
          m.add(
              new MismatchRow(
                  o == null ? "LPN_NOT_LOADED" : "WRONG_TRF",
                  id,
                  t.trfId(),
                  o == null ? "등록되지 않은 LPN" : "TRF " + o.trfId() + " 의 LPN"));
          continue;
        }
        ResultService.boxes(l, mine).forEach(b -> declared.add(b.lpn()));
      }
      // 이 운행에 실린 BOX
      Set<String> loaded = new LinkedHashSet<>();
      for (LpnRow b : mine.values()) {
        if ("BOX".equals(b.unitType()) && b.loaded() && cmd.tripId().equals(b.tripId())) {
          loaded.add(b.lpn());
        }
      }
      for (String b : declared) {
        if (!loaded.contains(b)) {
          m.add(new MismatchRow("LPN_NOT_LOADED", b, t.trfId(), "출발 구성에 있으나 이 운행 상차 기록 없음"));
        }
      }
      for (String b : loaded) {
        if (!declared.contains(b)) {
          m.add(new MismatchRow("LPN_NOT_IN_DEPARTURE", b, t.trfId(), "상차됐으나 출발 구성에 없음"));
        }
      }
      if (!c.sealNos().isEmpty()) {
        for (String b : loaded) {
          String seal = mine.get(b).loadSealNo();
          if (seal != null && !c.sealNos().contains(seal)) {
            m.add(new MismatchRow("SEAL_MISMATCH", b, t.trfId(), "봉인 " + seal + " 이 출발 봉인 목록에 없음"));
          }
        }
      }
      if (declared.isEmpty() && m.isEmpty()) {
        m.add(new MismatchRow("LPN_NOT_LOADED", null, t.trfId(), "출발 구성에 BOX 가 없음"));
      }
      boolean ok = m.isEmpty();
      int qty = 0;
      if (ok) {
        for (String b : declared) {
          qty += lpns.contents(b).stream().mapToInt(Content::qty).sum();
        }
        departing.addAll(declared);
        lpns.markDeparted(declared, cmd.departureId());
      }
      rows.add(
          new DepartureTransferRow(
              t.trfId(),
              i,
              c.mfcId() == null ? t.mfcId() : c.mfcId(),
              ok ? "MATCHED" : "MISMATCHED",
              qty,
              ok ? "PENDING" : null,
              null,
              null,
              null,
              null,
              null));
      mismatches.addAll(m);
      publishValidated(cmd, t, ok, m, declared, at);
      if (!ok) {
        publishDiscrepancy(cmd, t, m, at);
      }
    }
    long matched = rows.stream().filter(r -> "MATCHED".equals(r.result())).count();
    String overall =
        matched == rows.size() ? "MATCHED" : matched == 0 ? "MISMATCHED" : "PARTIALLY_MATCHED";
    lpns.insertDeparture(
        new DepartureRow(
            cmd.departureId(),
            cmd.tripId(),
            cmd.vehicleId(),
            cmd.source(),
            cmd.departedAt(),
            cmd.evidenceRef(),
            hash,
            overall,
            at),
        rows,
        mismatches);
  }

  /** 일치 TRF 하나의 TOUT → 잔여 해제 → 매니페스트. */
  private void post(Validate cmd, DepartureTransferRow row) {
    TransferRow t = store.transfer(row.trfId(), false).orElseThrow();
    List<LpnRow> boxes =
        lpns.departedOfTrf(t.trfId(), cmd.departureId()).stream()
            .filter(b -> "BOX".equals(b.unitType()))
            .toList();
    Map<String, LocalDate> expiry = expiry(t);
    Map<String, int[]> qty = new LinkedHashMap<>();
    Map<String, List<String>> lpnsOf = new HashMap<>();
    List<ManifestLine> manifest = new ArrayList<>();
    for (LpnRow b : boxes) {
      for (Content c : lpns.contents(b.lpn())) {
        String k = c.sku() + "|" + c.lot();
        qty.computeIfAbsent(k, x -> new int[1])[0] += c.qty();
        lpnsOf.computeIfAbsent(k, x -> new ArrayList<>()).add(b.lpn());
        manifest.add(new ManifestLine(b.lpn(), c.sku(), c.lot(), expiry.get(c.lot()), c.qty()));
      }
    }
    String manifestId = "MAN-" + t.trfId();
    List<Map<String, Object>> lines = new ArrayList<>();
    for (Map.Entry<String, int[]> e : qty.entrySet()) {
      String[] k = e.getKey().split("\\|", 2);
      Map<String, Object> l = new LinkedHashMap<>();
      l.put(
          "departureLineId",
          "DL-" + sha(cmd.departureId() + "|" + t.trfId() + "|" + e.getKey()).substring(0, 32));
      l.put("sku", k[0]);
      l.put("lot", k[1]);
      l.put("qty", e.getValue()[0]);
      l.put("lpns", lpnsOf.get(e.getKey()));
      lines.add(l);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("cdcId", t.cdcId());
    body.put("departureId", cmd.departureId());
    body.put("departureSource", cmd.source());
    body.put("toMfcId", t.mfcId());
    body.put("trfId", t.trfId());
    body.put("trfVersion", t.trfVersion());
    body.put("allocationId", t.allocationId());
    body.put("shipmentManifestId", manifestId);
    body.put("departedAt", cmd.departedAt().toString());
    body.put("lines", lines);
    JsonNode res;
    try {
      res = inventory.depart("outbound-tout-" + cmd.departureId() + "-" + t.trfId(), body);
    } catch (PmartException e) {
      String status = TransferService.unknown(e) ? "UNKNOWN_RESULT" : "REJECTED";
      tx.executeWithoutResult(
          s ->
              lpns.setPosting(
                  cmd.departureId(),
                  t.trfId(),
                  status,
                  null,
                  e.code() + ": " + e.getMessage(),
                  null));
      return;
    }
    String commitId = res.path("resultId").asString(null);
    Instant at = support.now();
    tx.executeWithoutResult(
        s -> {
          lpns.setPosting(cmd.departureId(), t.trfId(), "APPLIED", commitId, null, manifestId);
          if (lpns.manifestLines(manifestId).isEmpty()) {
            lpns.insertManifest(manifestId, t.trfId(), manifest);
          }
          store.setTransferState(t.trfId(), "DEPARTED", null, at);
          publishManifest(cmd, t, manifestId, manifest, at);
        });
    releaseResidual(cmd, t);
  }

  /**
   * 출발 뒤 남은 할당 해제(RESIDUAL_CLOSED). 이미 다 나갔거나 풀렸으면 재고가 409 INVALID_STATE — 끝난 것으로 본다. 불명·미전달은
   * PENDING, 그 밖의 거절(없는 할당·검증 실패 등)은 FAILED 와 사유(HTTP 상태·코드·설명)를 남긴다. PENDING·FAILED 는 같은 출발 재전송이
   * 같은 키로 다시 부른다.
   */
  private void releaseResidual(Validate cmd, TransferRow t) {
    if (t.allocationId() == null) {
      tx.executeWithoutResult(s -> lpns.setResidual(cmd.departureId(), t.trfId(), "DONE", null));
      return;
    }
    String status;
    String reason = null;
    try {
      inventory.release(
          t.allocationId(),
          "outbound-residual-" + t.trfId(),
          Map.of("reason", "RESIDUAL_CLOSED", "sourceRef", cmd.departureId()));
      status = "DONE";
    } catch (PmartException e) {
      if (TransferService.unknown(e)) {
        status = "PENDING";
      } else if (e.status().value() == 409 && ErrorCode.INVALID_STATE.name().equals(e.code())) {
        status = "DONE";
      } else {
        status = "FAILED";
        reason = residualReason(e);
        log.warn(
            "잔여 할당 해제 거절 — departureId={} trfId={} allocationId={}: {}",
            cmd.departureId(),
            t.trfId(),
            t.allocationId(),
            reason);
      }
    }
    String st = status;
    String why = reason;
    tx.executeWithoutResult(s -> lpns.setResidual(cmd.departureId(), t.trfId(), st, why));
  }

  static String residualReason(PmartException e) {
    String r = "HTTP " + e.status().value() + " " + e.code() + ": " + e.getMessage();
    return r.length() > 300 ? r.substring(0, 300) : r;
  }

  /** 할당 LOT → 소비기한(매니페스트·사건용). 조회가 안 되면 비운다. */
  private Map<String, LocalDate> expiry(TransferRow t) {
    Map<String, LocalDate> out = new HashMap<>();
    if (t.allocationId() == null) {
      return out;
    }
    try {
      JsonNode a = inventory.allocation(t.allocationId());
      for (JsonNode line : a.path("lines")) {
        for (JsonNode lot : line.path("lots")) {
          String exp = lot.path("expiresAt").asString(null);
          if (exp != null && !exp.isBlank()) {
            out.put(lot.path("lot").asString(), LocalDate.parse(exp));
          }
        }
      }
    } catch (PmartException | java.time.format.DateTimeParseException e) {
      // 소비기한 없이 진행
    }
    return out;
  }

  public View view(String departureId) {
    DepartureRow d =
        lpns.departure(departureId, false)
            .orElseThrow(
                () -> PmartException.notFound("NOT_FOUND", "출발 " + departureId + " 검증이 없습니다."));
    return new View(d, lpns.departureTransfers(departureId), lpns.mismatches(departureId));
  }

  public Manifest manifest(String trfId) {
    TransferRow t =
        store
            .transfer(trfId, false)
            .orElseThrow(() -> PmartException.notFound("NOT_FOUND", "TRF " + trfId + " 가 없습니다."));
    DepartureTransferRow row =
        lpns.departureOfTrf(trfId)
            .filter(r -> r.manifestId() != null)
            .orElseThrow(
                () ->
                    PmartException.notFound(
                        "NOT_DEPARTED", "TRF " + trfId + " 는 아직 출발 전기되지 않았습니다."));
    String depId = lpns.departureIdOfTrf(trfId).orElseThrow();
    DepartureRow d = lpns.departure(depId, false).orElseThrow();
    List<LpnRow> boxes = lpns.departedOfTrf(trfId, depId);
    List<LpnRow> all = new ArrayList<>(lpns.parentsOf(boxes.stream().map(LpnRow::lpn).toList()));
    all.addAll(boxes);
    Map<String, List<Content>> contents = new HashMap<>();
    for (LpnRow b : boxes) {
      contents.put(b.lpn(), lpns.contents(b.lpn()));
    }
    return new Manifest(
        row.manifestId(), t, d, all, contents, lpns.manifestLines(row.manifestId()));
  }

  private void publishValidated(
      Validate cmd, TransferRow t, boolean ok, List<MismatchRow> m, Set<String> boxes, Instant at) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("departureId", cmd.departureId());
    p.put("trfId", t.trfId());
    p.put("tripId", cmd.tripId());
    p.put("vehicleId", cmd.vehicleId());
    List<Map<String, Object>> lines = new ArrayList<>();
    if (ok) {
      for (String b : boxes) {
        for (Content c : lpns.contents(b)) {
          lines.add(Map.of("sku", c.sku(), "lot", c.lot(), "lpn", b, "qty", c.qty()));
        }
      }
    }
    p.put("validatedLines", lines);
    p.put(
        "mismatches",
        m.stream()
            .map(
                x -> {
                  Map<String, Object> mm = new LinkedHashMap<>();
                  mm.put("type", eventType(x.kind()));
                  if (x.lpn() != null) {
                    mm.put("lpn", x.lpn());
                  }
                  mm.put("detail", x.kind() + ": " + x.detail());
                  return mm;
                })
            .toList());
    p.put("result", ok ? "SUCCESS" : "FAILED");
    p.put("validatedAt", at.toString());
    p.put("source", cmd.source());
    support.publish("TruckDepartureValidated", "Departure", cmd.departureId(), 1, "transport", p);
  }

  private void publishDiscrepancy(Validate cmd, TransferRow t, List<MismatchRow> m, Instant at) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("discrepancyId", "DSC-" + sha(cmd.departureId() + "|" + t.trfId()).substring(0, 24));
    p.put("trfId", t.trfId());
    p.put("departureId", cmd.departureId());
    p.put("type", eventType(m.get(0).kind()));
    p.put(
        "details",
        String.join(
            "; ", m.stream().map(x -> x.kind() + " " + (x.lpn() == null ? "" : x.lpn())).toList()));
    p.put(
        "lines",
        m.stream()
            .filter(x -> x.lpn() != null)
            .map(x -> Map.<String, Object>of("lpn", x.lpn()))
            .toList());
    p.put("raisedAt", at.toString());
    support.publish(
        "OutboundDiscrepancyRaised", "OutboundTransfer", t.trfId(), t.version(), "transport", p);
  }

  private void publishManifest(
      Validate cmd, TransferRow t, String manifestId, List<ManifestLine> lines, Instant at) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("shipmentManifestId", manifestId);
    p.put("trfId", t.trfId());
    p.put("trfVersion", t.trfVersion());
    p.put("departureId", cmd.departureId());
    p.put("tripId", cmd.tripId());
    p.put("fromCdc", t.cdcId());
    p.put("toMfc", t.mfcId());
    p.put(
        "lines",
        lines.stream()
            .map(
                l -> {
                  Map<String, Object> m = new LinkedHashMap<>();
                  m.put("sku", l.sku());
                  m.put("lot", l.lot());
                  m.put("lpn", l.lpn());
                  m.put("qty", l.qty());
                  if (l.expiresAt() != null) {
                    m.put("expiryDate", l.expiresAt().toString());
                  }
                  return m;
                })
            .toList());
    p.put("totalQty", lines.stream().mapToInt(ManifestLine::qty).sum());
    p.put("issuedAt", at.toString());
    support.publish(
        "TransferShipmentManifestIssued", "ShipmentManifest", manifestId, 1, "transport", p);
  }

  /** 이벤트 불일치 유형(MIXED·MISSING·EXCESS·SEAL)으로. */
  static String eventType(String kind) {
    return switch (kind) {
      case "WRONG_TRF", "WRONG_MFC" -> "MIXED";
      case "LPN_NOT_IN_DEPARTURE" -> "MISSING";
      case "SEAL_MISMATCH", "COMPARTMENT_MISMATCH" -> "SEAL";
      default -> "EXCESS";
    };
  }

  static String hash(Validate cmd) {
    StringBuilder sb =
        new StringBuilder()
            .append(cmd.departureId())
            .append('|')
            .append(cmd.tripId())
            .append('|')
            .append(cmd.vehicleId())
            .append('|')
            .append(cmd.departedAt())
            .append('|')
            .append(cmd.source());
    for (Composition c : cmd.compositions()) {
      sb.append('|')
          .append(c.trfId())
          .append(':')
          .append(c.mfcId())
          .append(':')
          .append(c.lpns())
          .append(':')
          .append(c.sealNos());
    }
    return sha(sb.toString());
  }

  static String sha(String s) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
