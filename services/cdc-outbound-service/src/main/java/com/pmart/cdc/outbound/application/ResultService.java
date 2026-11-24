package com.pmart.cdc.outbound.application;

import com.pmart.cdc.outbound.application.Support.CommandResult;
import com.pmart.cdc.outbound.infrastructure.LpnStore;
import com.pmart.cdc.outbound.infrastructure.LpnStore.Content;
import com.pmart.cdc.outbound.infrastructure.LpnStore.LpnRow;
import com.pmart.cdc.outbound.infrastructure.OutboundStore;
import com.pmart.cdc.outbound.infrastructure.OutboundStore.TransferRow;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.Outcome;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 분류·검품·상차 결과(0.4.0). 수량은 BOX 의 contents 만 — RT(롤테이너)·PLT 는 그릇이고 검품·상차는 안의 BOX 로 펼친다(TMS D-07 적재 =
 * R/T, 검증 = 박스). 불변식 상차 ≤ 검품합격 ≤ 분류 ≤ 실피킹.
 */
@Service
public class ResultService {

  static final String OP_SORT = "cdcOutbound:sorting";
  static final String OP_INSPECT = "cdcOutbound:inspection";
  static final String OP_LOAD = "cdcOutbound:loading";
  static final List<String> FORWARD =
      List.of("IN_WAVE", "PICKED", "SORTED", "INSPECTED", "LOADED", "DEPARTED");

  public record SortLpn(
      String lpn,
      String unitType,
      String state,
      @Nullable String parentLpn,
      @Nullable String sealNo,
      @Nullable String temperatureZone,
      @Nullable Integer weightG,
      List<Content> contents) {}

  public record Sort(String waveId, String trfId, String workerId, List<SortLpn> lpns) {}

  public record InspectItem(String lpn, String result, @Nullable String failReason) {}

  public record Inspect(String trfId, String inspectorId, List<InspectItem> items) {}

  public record LoadLpn(String lpn, @Nullable String compartment, @Nullable String sealNo) {}

  public record Load(
      String trfId,
      String tripId,
      String vehicleId,
      @Nullable String dockCode,
      String workerId,
      String action,
      List<LoadLpn> lpns) {}

  private final OutboundStore store;
  private final LpnStore lpns;
  private final RequestIdempotency idempotency;
  private final Support support;

  public ResultService(
      OutboundStore store, LpnStore lpns, RequestIdempotency idempotency, Support support) {
    this.store = store;
    this.lpns = lpns;
    this.idempotency = idempotency;
    this.support = support;
  }

  // ---------- 분류 ----------

  public Outcome<CommandResult> sort(String requestId, String sourceSystem, Sort cmd) {
    TransferRow pre = transfer(cmd.trfId());
    String by = Support.requireCenter(pre.cdcId());
    validateSort(cmd);
    return idempotency.execute(
        Support.key(sourceSystem, requestId, OP_SORT, by),
        cmd,
        CommandResult.class,
        () -> {
          Instant at = support.now();
          TransferRow t = store.transfer(cmd.trfId(), true).orElseThrow();
          if (!cmd.waveId().equals(t.waveId()) || "DEPARTED".equals(t.state())) {
            throw PmartException.invalidState(
                "TRF "
                    + t.trfId()
                    + " 는 Wave "
                    + cmd.waveId()
                    + " 에서 분류할 수 없습니다("
                    + t.state()
                    + ").");
          }
          List<LpnRow> existing = lpns.lockOfTrf(t.trfId());
          Map<String, LpnRow> known = lpns.find(cmd.lpns().stream().map(SortLpn::lpn).toList());
          if (!known.isEmpty()) {
            throw new PmartException(
                "LPN_ALREADY_SEALED",
                HttpStatus.CONFLICT,
                "이미 분류된 LPN 이 있습니다: " + known.keySet(),
                false,
                List.of());
          }
          Map<String, String> containers = new HashMap<>();
          existing.stream()
              .filter(l -> !"BOX".equals(l.unitType()))
              .forEach(l -> containers.put(l.lpn(), l.trfId()));
          cmd.lpns().stream()
              .filter(l -> !"BOX".equals(l.unitType()))
              .forEach(l -> containers.put(l.lpn(), t.trfId()));
          List<PmartException.Detail> bad = new ArrayList<>();
          for (int i = 0; i < cmd.lpns().size(); i++) {
            SortLpn l = cmd.lpns().get(i);
            if (l.parentLpn() != null && !t.trfId().equals(containers.get(l.parentLpn()))) {
              bad.add(
                  new PmartException.Detail(
                      "lpns[" + i + "].parentLpn", "PARENT_NOT_CONTAINER_OF_TRF"));
            }
          }
          if (!bad.isEmpty()) {
            throw new PmartException(
                ErrorCode.VALIDATION_FAILED, "상위 LPN 이 이 TRF 의 RT·PLT 가 아닙니다.", bad);
          }
          // 분류 ≤ 실피킹(SKU·LOT)
          Map<String, Integer> sorted = new HashMap<>(lpns.boxQty(t.trfId(), "ALL"));
          for (SortLpn l : cmd.lpns()) {
            for (Content c : l.contents()) {
              sorted.merge(c.sku() + "|" + c.lot(), c.qty(), Integer::sum);
            }
          }
          Map<String, Integer> picked = store.pickedByLot(t.trfId());
          for (Map.Entry<String, Integer> e : sorted.entrySet()) {
            int p = picked.getOrDefault(e.getKey(), 0);
            if (e.getValue() > p) {
              throw new PmartException(
                  "SORT_EXCEEDS_PICKED",
                  HttpStatus.CONFLICT,
                  e.getKey() + " 분류 " + e.getValue() + " 가 실피킹 " + p + " 보다 많습니다.",
                  false,
                  List.of());
            }
          }
          // 그릇 먼저, 그다음 BOX
          List<SortLpn> ordered = new ArrayList<>();
          cmd.lpns().stream().filter(l -> !"BOX".equals(l.unitType())).forEach(ordered::add);
          cmd.lpns().stream().filter(l -> "BOX".equals(l.unitType())).forEach(ordered::add);
          for (SortLpn l : ordered) {
            lpns.insert(
                new LpnRow(
                    l.lpn(),
                    t.trfId(),
                    t.waveId(),
                    l.unitType(),
                    l.parentLpn(),
                    l.state(),
                    l.sealNo(),
                    l.temperatureZone(),
                    l.weightG(),
                    null,
                    false,
                    null,
                    null,
                    null,
                    null),
                cmd.workerId(),
                at,
                "BOX".equals(l.unitType()) ? l.contents() : List.of());
          }
          advance(t, "SORTED", at);
          String id = Support.newId("WR");
          lpns.insertWorkResult(id, "SORTING", t.trfId(), at);
          return new CommandResult("APPLIED", requestId, id, t.version(), at);
        });
  }

  static void validateSort(Sort cmd) {
    List<PmartException.Detail> bad = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    for (int i = 0; i < cmd.lpns().size(); i++) {
      SortLpn l = cmd.lpns().get(i);
      String f = "lpns[" + i + "]";
      if (!ids.add(l.lpn())) {
        bad.add(new PmartException.Detail(f + ".lpn", "DUPLICATE"));
      }
      if ("BOX".equals(l.unitType()) && l.contents().isEmpty()) {
        bad.add(new PmartException.Detail(f + ".contents", "BOX_NEEDS_CONTENTS"));
      }
      if (!"BOX".equals(l.unitType()) && !l.contents().isEmpty()) {
        bad.add(new PmartException.Detail(f + ".contents", "CONTAINER_HAS_NO_CONTENTS"));
      }
      if (l.lpn().equals(l.parentLpn())) {
        bad.add(new PmartException.Detail(f + ".parentLpn", "SELF"));
      }
      if (!"BOX".equals(l.unitType()) && l.parentLpn() != null) {
        bad.add(new PmartException.Detail(f + ".parentLpn", "CONTAINER_IN_CONTAINER"));
      }
    }
    if (cmd.lpns().isEmpty() || !bad.isEmpty()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "분류 LPN 이 올바르지 않습니다.", bad);
    }
  }

  // ---------- 검품 ----------

  public Outcome<CommandResult> inspect(String requestId, String sourceSystem, Inspect cmd) {
    TransferRow pre = transfer(cmd.trfId());
    String by = Support.requireCenter(pre.cdcId());
    if (cmd.items().isEmpty()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "검품 줄이 비었습니다.");
    }
    return idempotency.execute(
        Support.key(sourceSystem, requestId, OP_INSPECT, by),
        cmd,
        CommandResult.class,
        () -> {
          Instant at = support.now();
          TransferRow t = store.transfer(cmd.trfId(), true).orElseThrow();
          Map<String, LpnRow> mine = byLpn(lpns.lockOfTrf(t.trfId()));
          for (InspectItem it : cmd.items()) {
            LpnRow l = mine.get(it.lpn());
            if (l == null) {
              throw PmartException.notFound(
                  "NOT_FOUND", "TRF " + t.trfId() + " 의 LPN " + it.lpn() + " 이 없습니다.");
            }
            List<LpnRow> targets = new ArrayList<>(boxes(l, mine));
            if (!"BOX".equals(l.unitType())) {
              targets.add(l);
            }
            for (LpnRow b : targets) {
              if (b.loaded()) {
                throw PmartException.invalidState("상차된 LPN " + b.lpn() + " 은 다시 검품할 수 없습니다.");
              }
              lpns.inspect(b.lpn(), it.result(), it.failReason(), at);
            }
          }
          advance(t, "INSPECTED", at);
          String id = Support.newId("WR");
          lpns.insertWorkResult(id, "INSPECTION", t.trfId(), at);
          return new CommandResult("APPLIED", requestId, id, t.version(), at);
        });
  }

  // ---------- 상차 ----------

  public Outcome<CommandResult> load(String requestId, String sourceSystem, Load cmd) {
    TransferRow pre = transfer(cmd.trfId());
    String by = Support.requireCenter(pre.cdcId());
    if (cmd.lpns().isEmpty()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "상차 LPN 이 비었습니다.");
    }
    return idempotency.execute(
        Support.key(sourceSystem, requestId, OP_LOAD, by),
        cmd,
        CommandResult.class,
        () -> {
          Instant at = support.now();
          TransferRow t = store.transfer(cmd.trfId(), true).orElseThrow();
          if ("DEPARTED".equals(t.state())) {
            throw PmartException.invalidState("TRF " + t.trfId() + " 는 이미 출발했습니다.");
          }
          Map<String, LpnRow> mine = byLpn(lpns.lockOfTrf(t.trfId()));
          boolean loading = "LOADED".equals(cmd.action());
          for (LoadLpn item : cmd.lpns()) {
            LpnRow l = mine.get(item.lpn());
            if (l == null) {
              throw PmartException.notFound(
                  "NOT_FOUND", "TRF " + t.trfId() + " 의 LPN " + item.lpn() + " 이 없습니다.");
            }
            List<LpnRow> targets = new ArrayList<>(boxes(l, mine));
            for (LpnRow b : targets) {
              if (loading) {
                if (!"PASSED".equals(b.inspection())) {
                  throw conflict("LPN_NOT_PASSED", "검품 합격이 아닌 BOX " + b.lpn() + " 은 실을 수 없습니다.");
                }
                if (b.loaded() && !cmd.tripId().equals(b.tripId())) {
                  throw conflict(
                      "TRIP_MISMATCH", "BOX " + b.lpn() + " 은 다른 운행 " + b.tripId() + " 에 실려 있습니다.");
                }
              } else if (!b.loaded() || !cmd.tripId().equals(b.tripId())) {
                throw conflict(
                    "TRIP_MISMATCH", "BOX " + b.lpn() + " 은 운행 " + cmd.tripId() + " 에 실려 있지 않습니다.");
              }
            }
            if (!"BOX".equals(l.unitType())) {
              targets.add(l);
            }
            for (LpnRow b : targets) {
              if (loading) {
                lpns.load(
                    b.lpn(),
                    cmd.tripId(),
                    cmd.vehicleId(),
                    cmd.dockCode(),
                    item.compartment(),
                    item.sealNo(),
                    at);
              } else {
                lpns.unload(b.lpn());
              }
            }
          }
          if (loading) {
            advance(t, "LOADED", at);
          }
          String id = Support.newId("WR");
          lpns.insertWorkResult(id, "LOADING", t.trfId(), at);
          return new CommandResult("APPLIED", requestId, id, t.version(), at);
        });
  }

  @Transactional(readOnly = true)
  public Map<String, Object> workResult(String id) {
    Map<String, Object> r =
        lpns.workResult(id)
            .orElseThrow(() -> PmartException.notFound("NOT_FOUND", "작업 결과 " + id + " 가 없습니다."));
    TransferRow t = transfer(String.valueOf(r.get("trf_id")));
    Support.requireCenter(t.cdcId());
    return r;
  }

  /** LPN 을 BOX 로 펼친다(BOX 면 자기, RT·PLT 면 안의 BOX). */
  static List<LpnRow> boxes(LpnRow l, Map<String, LpnRow> mine) {
    if ("BOX".equals(l.unitType())) {
      return List.of(l);
    }
    List<LpnRow> out = new ArrayList<>();
    for (LpnRow c : mine.values()) {
      if (l.lpn().equals(c.parentLpn()) && "BOX".equals(c.unitType())) {
        out.add(c);
      }
    }
    return out;
  }

  static Map<String, LpnRow> byLpn(List<LpnRow> rows) {
    Map<String, LpnRow> m = new LinkedHashMap<>();
    rows.forEach(r -> m.put(r.lpn(), r));
    return m;
  }

  /** TRF 상태를 앞으로만 옮긴다(IN_WAVE → PICKED → SORTED → INSPECTED → LOADED → DEPARTED). */
  void advance(TransferRow t, String to, Instant at) {
    if (FORWARD.indexOf(to) > FORWARD.indexOf(t.state())) {
      store.setTransferState(t.trfId(), to, null, at);
    }
  }

  private TransferRow transfer(String trfId) {
    return store
        .transfer(trfId, false)
        .orElseThrow(() -> PmartException.notFound("NOT_FOUND", "TRF " + trfId + " 가 없습니다."));
  }

  private static PmartException conflict(String code, String message) {
    return new PmartException(code, HttpStatus.CONFLICT, message, false, List.of());
  }
}
