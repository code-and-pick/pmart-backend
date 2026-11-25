package com.pmart.cdc.inventory.application;

import com.pmart.cdc.inventory.domain.HqApprovalRules.Line;
import com.pmart.cdc.inventory.infrastructure.AllocationStore;
import com.pmart.cdc.inventory.infrastructure.CountStore;
import com.pmart.cdc.inventory.infrastructure.CountStore.Key;
import com.pmart.cdc.inventory.infrastructure.CountStore.SessionRow;
import com.pmart.cdc.inventory.infrastructure.LedgerStore.Delta;
import com.pmart.cdc.inventory.infrastructure.RecountHoldStore;
import com.pmart.cdc.inventory.infrastructure.StockStore;
import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.Outcome;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 위치 실사·재계수·CNT 조정(0.8.0 — WMS CDC-F-029·030, 사용자 2026-11-25 "MFC 실사와 같게", 설계
 * docs/cdc/design/2026-11-25-count-adjustment.md). 시작(칸 잠금 = 새 할당 후보에서 뺌, 장부 기준 고정) → 장부 숨긴 첫 계수 →
 * 다르면 다른 작업자 재계수 → 시작·계수자가 아닌 운영자 결정 → APPROVE 면 승인 관문({@link ApprovalGate}) 뒤 차이만 CNT 로 반영·재계수 대기
 * 해소. 계수 보고만으로 현물을 바꾸지 않는다. 실물이 예약보다 적어지는 조정은 아직 막는다(409 COUNT_BELOW_RESERVED — 설계 §4 는 다음 단계).
 */
@Service
public class CountService {

  static final String OP_START = "cdcInventory:countStart";
  static final String OP_FIRST = "cdcInventory:countFirst";
  static final String OP_RECOUNT = "cdcInventory:countRecount";
  static final String OP_DECIDE = "cdcInventory:countDecide";
  static final int MAX_LOCATIONS = 200;
  static final Set<String> CLOSED = Set.of("MATCHED", "APPROVED", "REJECTED");

  public record CountLine(String locationCode, String sku, String lot, int qty) {}

  public record LineView(
      String locationCode,
      String sku,
      String lot,
      int bookQty,
      @Nullable Integer firstQty,
      @Nullable Integer finalQty,
      int deltaQty) {}

  /** lines 는 끝난 세션에서만(결정 전에는 장부 수량을 보이지 않는다). */
  public record View(
      SessionRow session, List<String> locations, List<Key> sheet, List<LineView> lines) {}

  private final CountStore counts;
  private final AllocationStore allocations;
  private final StockStore stock;
  private final RecountHoldStore holds;
  private final RequestIdempotency idempotency;
  private final Ledger ledger;
  private final ApprovalGate gate;
  private final EventPublisher events;
  private final EventEnvelopeCodec codec;

  public CountService(
      CountStore counts,
      StockStore stock,
      RecountHoldStore holds,
      RequestIdempotency idempotency,
      Ledger ledger,
      ApprovalGate gate,
      EventPublisher events,
      EventEnvelopeCodec codec,
      AllocationStore allocations) {
    this.allocations = allocations;
    this.counts = counts;
    this.stock = stock;
    this.holds = holds;
    this.idempotency = idempotency;
    this.ledger = ledger;
    this.gate = gate;
    this.events = events;
    this.codec = codec;
  }

  // ---------- 시작 ----------

  public Outcome<View> start(
      String requestId,
      String sourceSystem,
      String cdcId,
      List<String> locations,
      @Nullable String reason) {
    Operator op = Operator.forCenter(cdcId, sourceSystem);
    Set<String> locs = new TreeSet<>(locations);
    if (locs.isEmpty() || locs.size() > MAX_LOCATIONS || locs.size() != locations.size()) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED, "locationCodes 는 1~" + MAX_LOCATIONS + "칸, 같은 칸 두 번 없이.");
    }
    List<PmartException.Detail> bad = new ArrayList<>();
    for (String l : locs) {
      String[] p = l.split("-");
      if (p.length != 7 || !p[0].equals(cdcId)) {
        bad.add(new PmartException.Detail(l, "LOCATION_CODE"));
      } else if ("91".equals(p[3])) {
        bad.add(new PmartException.Detail(l, "STAGING_NOT_COUNTED"));
      }
    }
    if (!bad.isEmpty()) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED, "실사할 수 없는 칸이 있습니다(출고대기 91 은 실사 대상 아님).", bad);
    }
    Map<String, Object> req = new LinkedHashMap<>();
    req.put("cdcId", cdcId);
    req.put("locations", List.copyOf(locs));
    req.put("reason", reason);
    return idempotency.execute(
        op.key(requestId, OP_START),
        req,
        View.class,
        () -> {
          Instant at = ledger.now();
          if (!counts.openLocations(cdcId, locs).isEmpty()) {
            throw inProgress(counts.openLocations(cdcId, locs));
          }
          Map<Key, Integer> book = new TreeMap<>(KEY_ORDER);
          List<String> quality = new ArrayList<>();
          for (Object[] r : stock.lockAtLocations(cdcId, locs)) {
            int onHand = (Integer) r[4];
            if (onHand <= 0) {
              continue;
            }
            if (!"RELEASED".equals(r[3])) {
              quality.add(r[0] + " " + r[1] + "/" + r[2] + " " + r[3]);
              continue;
            }
            book.merge(new Key((String) r[0], (String) r[1], (String) r[2]), onHand, Integer::sum);
          }
          if (!quality.isEmpty()) {
            throw new PmartException(
                "QUALITY_STOCK_PRESENT",
                HttpStatus.CONFLICT,
                "품질 보류·격리 현물이 있는 칸은 품질 처리 뒤 실사합니다: " + String.join(", ", quality),
                false,
                List.of());
          }
          SessionRow s =
              new SessionRow(
                  Ledger.newId("CNT"),
                  cdcId,
                  "COUNTING",
                  reason,
                  op.id(),
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  at,
                  null);
          if (!counts.insert(s, locs, at)) {
            throw inProgress(locs);
          }
          counts.putLines(s.sessionId(), 0, book);
          return view(s.sessionId());
        });
  }

  @Transactional(readOnly = true)
  public View get(String cdcId, String sessionId) {
    Operator.requireCenter(cdcId);
    SessionRow s = find(cdcId, sessionId, false);
    return view(s.sessionId());
  }

  // ---------- 계수 ----------

  public Outcome<View> first(
      String requestId,
      String sourceSystem,
      String cdcId,
      String sessionId,
      List<CountLine> lines) {
    return count(requestId, sourceSystem, cdcId, sessionId, lines, false);
  }

  public Outcome<View> recount(
      String requestId,
      String sourceSystem,
      String cdcId,
      String sessionId,
      List<CountLine> lines) {
    return count(requestId, sourceSystem, cdcId, sessionId, lines, true);
  }

  private Outcome<View> count(
      String requestId,
      String sourceSystem,
      String cdcId,
      String sessionId,
      List<CountLine> lines,
      boolean second) {
    Operator op = Operator.forCenter(cdcId, sourceSystem);
    return idempotency.execute(
        op.key(requestId, second ? OP_RECOUNT : OP_FIRST),
        Map.of("cdcId", cdcId, "sessionId", sessionId, "lines", lines),
        View.class,
        () -> {
          Instant at = ledger.now();
          SessionRow s = find(cdcId, sessionId, true);
          String want = second ? "RECOUNT_REQUIRED" : "COUNTING";
          if (!want.equals(s.state())) {
            throw PmartException.invalidState("실사 상태 " + s.state() + " 에서는 이 계수를 받을 수 없습니다.");
          }
          if (second && op.id().equals(s.firstCounterId())) {
            throw new PmartException(
                "SAME_COUNTER", HttpStatus.CONFLICT, "재계수는 첫 계수자와 다른 사람이 합니다.", false, List.of());
          }
          Map<Key, Integer> book = counts.lines(sessionId, 0);
          Map<Key, Integer> counted = counted(cdcId, sessionId, book, lines);
          counts.putLines(sessionId, second ? 2 : 1, counted);
          boolean same = sameQty(book, counted);
          String next = same ? "MATCHED" : second ? "PENDING_APPROVAL" : "RECOUNT_REQUIRED";
          counts.counted(
              sessionId, next, second ? "recounter_id" : "first_counter_id", op.id(), at);
          if (same) {
            closeOut(s, op.id(), "실사 일치", at);
          }
          return view(sessionId);
        });
  }

  /** 계수 줄 → (장부 키는 0 으로 채움) 수량. 실사 칸 밖·처음 보는 LOT·같은 줄 두 번은 400. */
  private Map<Key, Integer> counted(
      String cdcId, String sessionId, Map<Key, Integer> book, List<CountLine> lines) {
    Set<String> locs = new LinkedHashSet<>(counts.locations(sessionId));
    Set<String> skus = new TreeSet<>();
    lines.forEach(l -> skus.add(l.sku()));
    Set<String> known = stock.existingLots(cdcId, skus);
    List<PmartException.Detail> bad = new ArrayList<>();
    Map<Key, Integer> out = new TreeMap<>(KEY_ORDER);
    book.keySet().forEach(k -> out.put(k, 0));
    Set<Key> seen = new java.util.HashSet<>();
    for (int i = 0; i < lines.size(); i++) {
      CountLine l = lines.get(i);
      Key k = new Key(l.locationCode(), l.sku(), l.lot());
      if (!locs.contains(l.locationCode())) {
        bad.add(new PmartException.Detail("lines[" + i + "]", "NOT_IN_SESSION"));
      } else if (!seen.add(k)) {
        bad.add(new PmartException.Detail("lines[" + i + "]", "DUPLICATE_LINE"));
      } else if (!book.containsKey(k) && !known.contains(l.sku() + "|" + l.lot())) {
        bad.add(new PmartException.Detail("lines[" + i + "]", "LOT_UNKNOWN"));
      } else if (l.qty() < 0) {
        bad.add(new PmartException.Detail("lines[" + i + "].qty", "NEGATIVE"));
      } else {
        out.put(k, l.qty());
      }
    }
    if (!bad.isEmpty()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "계수 줄을 받을 수 없습니다.", bad);
    }
    return out;
  }

  // ---------- 결정 ----------

  public Outcome<View> decide(
      String requestId,
      String sourceSystem,
      String cdcId,
      String sessionId,
      String decision,
      String reason,
      @Nullable String approvalRef) {
    Operator op = Operator.forCenter(cdcId, sourceSystem);
    SessionRow pre = find(cdcId, sessionId, false);
    String ref = null;
    if ("APPROVE".equals(decision) && "PENDING_APPROVAL".equals(pre.state())) {
      selfCheck(pre, op.id());
      // 승인 관문은 트랜잭션 밖에서 — pricing·approval-service 호출(같은 세션이면 같은 useRef 라 다시 불러도 한 번)
      ref =
          gate.require(
              cdcId,
              "COUNT_ADJUSTMENT",
              sessionId,
              op.id(),
              "CDC 실사 조정 " + sessionId,
              gateLines(deltas(sessionId)),
              approvalRef);
    }
    String recorded = ref;
    Map<String, Object> req = new LinkedHashMap<>();
    req.put("cdcId", cdcId);
    req.put("sessionId", sessionId);
    req.put("decision", decision);
    req.put("reason", reason);
    req.put("approvalRef", String.valueOf(approvalRef));
    return idempotency.execute(
        op.key(requestId, OP_DECIDE),
        req,
        View.class,
        () -> {
          Instant at = ledger.now();
          SessionRow s = find(cdcId, sessionId, true);
          if (!"PENDING_APPROVAL".equals(s.state())) {
            throw PmartException.invalidState("실사 상태 " + s.state() + " 에서는 결정할 수 없습니다.");
          }
          selfCheck(s, op.id());
          if ("REJECT".equals(decision)) {
            counts.decided(sessionId, "REJECTED", op.id(), reason, null, null, at);
            counts.close(sessionId); // 재계수 대기는 남긴다 — 다시 실사
            return view(sessionId);
          }
          List<String> locs = counts.locations(sessionId);
          Map<Key, Integer> book = counts.lines(sessionId, 0);
          Map<Key, Integer> now = new TreeMap<>(KEY_ORDER);
          boolean otherQuality = false;
          for (Object[] r : stock.lockAtLocations(cdcId, locs)) {
            int onHand = (Integer) r[4];
            if (onHand <= 0) {
              continue;
            }
            if (!"RELEASED".equals(r[3])) {
              otherQuality = true;
              continue;
            }
            now.merge(new Key((String) r[0], (String) r[1], (String) r[2]), onHand, Integer::sum);
          }
          if (otherQuality || !sameQty(book, now)) {
            throw new PmartException(
                "BOOK_CHANGED",
                HttpStatus.CONFLICT,
                "실사 시작 뒤 이 칸들의 장부가 바뀌었습니다 — 반려하고 다시 실사하세요.",
                false,
                List.of());
          }
          Map<Key, Integer> delta = deltas(sessionId);
          List<Delta> ledgerDeltas = new ArrayList<>();
          List<Map<String, Object>> evLines = new ArrayList<>();
          int executed = 0;
          for (Map.Entry<Key, Integer> e : delta.entrySet()) {
            Key k = e.getKey();
            int d = e.getValue();
            if (d == 0) {
              continue;
            }
            if (d < 0) {
              // 실물이 예약보다 적어지면 모자란 만큼 그 칸의 늦게 잡힌 할당부터 푼다(설계 §4 — 사용자 결정 3)
              shortfall(cdcId, sessionId, k, d, at, ledgerDeltas);
            }
            if (!stock.adjustOnHand(cdcId, k.sku(), k.lot(), k.locationCode(), d, at)) {
              throw PmartException.versionConflict("재고가 방금 바뀌었습니다 — 다시 결정하세요.");
            }
            ledgerDeltas.add(new Delta(k.sku(), k.lot(), k.locationCode(), "ON_HAND", d));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sku", k.sku());
            m.put("lot", k.lot());
            m.put("locCode", k.locationCode());
            m.put("approvedDeltaQty", d);
            m.put("executedDeltaQty", d);
            m.put("reason", "CNT");
            evLines.add(m);
            executed += Math.abs(d);
          }
          String commitId =
              ledger.record(
                  requestId, "CNT", sourceSystem, "COUNT:" + sessionId, cdcId, at, ledgerDeltas);
          counts.decided(sessionId, "APPROVED", op.id(), reason, recorded, commitId, at);
          closeOut(s, op.id(), "실사 조정 " + sessionId, at);
          if (!evLines.isEmpty()) {
            publish(cdcId, sessionId, recorded, commitId, evLines, executed, at);
          }
          return view(sessionId);
        });
  }

  /**
   * 조정 뒤 현물(지금 + d)이 예약보다 작으면 모자란 만큼 그 칸에 늦게 잡힌 할당부터 예약을 푼다(0.10.0, 설계 §4). 할당마다
   * AllocationShortfall(reason COUNT_LOSS) — cdc-outbound 가 받아 다른 LOT 으로 다시 잡거나 못 찾음으로 처리한다.
   */
  private void shortfall(
      String cdcId, String sessionId, Key k, int d, Instant at, List<Delta> ledgerDeltas) {
    int onHand = 0;
    int reserved = 0;
    for (Object[] r : stock.lockAtLocations(cdcId, List.of(k.locationCode()))) {
      if (k.sku().equals(r[1]) && k.lot().equals(r[2]) && "RELEASED".equals(r[3])) {
        onHand += (Integer) r[4];
        reserved += (Integer) r[5];
      }
    }
    int need = reserved - (onHand + d);
    if (need <= 0) {
      return;
    }
    for (AllocationStore.Holder h :
        allocations.holdersAt(cdcId, k.sku(), k.lot(), k.locationCode())) {
      if (need <= 0) {
        break;
      }
      int take = Math.min(need, h.lot().remaining());
      if (take <= 0) {
        continue;
      }
      if (!stock.addReserved(cdcId, k.sku(), k.lot(), k.locationCode(), -take, at)) {
        throw PmartException.versionConflict("예약이 방금 바뀌었습니다 — 다시 결정하세요.");
      }
      allocations.addReleased(h.allocationId(), h.lot(), take);
      allocations.updateState(
          h.allocationId(),
          AllocationService.nextState(h.state(), allocations.lots(h.allocationId())));
      ledgerDeltas.add(new Delta(k.sku(), k.lot(), k.locationCode(), "RESERVED", -take));
      Map<String, Object> p = new LinkedHashMap<>();
      p.put("cdcId", cdcId);
      p.put("trfId", h.trfId());
      p.put("allocationId", h.allocationId());
      p.put("sku", k.sku());
      p.put("lot", k.lot());
      p.put("locationCode", k.locationCode());
      p.put("qty", take);
      p.put("reason", "COUNT_LOSS");
      p.put("sourceRef", sessionId);
      p.put("occurredAt", at.toString());
      events.publish(
          codec.create(
              events.sourceSystem(),
              "AllocationShortfall",
              1,
              "Allocation",
              h.allocationId(),
              1,
              new EventEnvelope.Actor(EventEnvelope.ActorType.SYSTEM, "cdc-inventory"),
              EventEnvelope.EventSource.REAL,
              p),
          Ledger.TOPIC);
      need -= take;
    }
    if (need > 0) {
      throw new PmartException(
          "COUNT_BELOW_RESERVED",
          HttpStatus.CONFLICT,
          k.locationCode() + " " + k.sku() + "/" + k.lot() + " 예약을 풀 할당을 찾지 못했습니다(조사 필요).",
          false,
          List.of(new PmartException.Detail(k.locationCode(), "COUNT_BELOW_RESERVED")));
    }
  }

  /** 시작자·계수자는 결정할 수 없다(요청자·승인자 분리, CDC-F-029). */
  private static void selfCheck(SessionRow s, String me) {
    if (me.equals(s.startedBy()) || me.equals(s.firstCounterId()) || me.equals(s.recounterId())) {
      throw new PmartException(
          "SELF_APPROVAL",
          HttpStatus.UNPROCESSABLE_CONTENT,
          "실사를 시작하거나 센 사람은 결정할 수 없습니다.",
          false,
          List.of());
    }
  }

  /** 최종(재계수, 없으면 첫 계수) − 장부. */
  private Map<Key, Integer> deltas(String sessionId) {
    Map<Key, Integer> book = counts.lines(sessionId, 0);
    Map<Key, Integer> fin = counts.lines(sessionId, 2);
    if (fin.isEmpty()) {
      fin = counts.lines(sessionId, 1);
    }
    Map<Key, Integer> out = new TreeMap<>(KEY_ORDER);
    Set<Key> keys = new java.util.HashSet<>(book.keySet());
    keys.addAll(fin.keySet());
    for (Key k : keys) {
      out.put(k, fin.getOrDefault(k, 0) - book.getOrDefault(k, 0));
    }
    return out;
  }

  private static List<Line> gateLines(Map<Key, Integer> delta) {
    List<Line> out = new ArrayList<>();
    delta.forEach(
        (k, d) -> {
          if (d != 0) {
            out.add(new Line(k.sku(), Math.abs(d), d < 0 ? -d : 0));
          }
        });
    return out;
  }

  private void closeOut(SessionRow s, String by, String reason, Instant at) {
    List<String> locs = counts.locations(s.sessionId());
    counts.close(s.sessionId());
    holds.releaseAt(s.cdcId(), locs, by, reason, at);
  }

  private View view(String sessionId) {
    SessionRow s = counts.find(sessionId, false).orElseThrow();
    Map<Key, Integer> book = counts.lines(sessionId, 0);
    List<LineView> lines = new ArrayList<>();
    if (CLOSED.contains(s.state())) {
      Map<Key, Integer> first = counts.lines(sessionId, 1);
      Map<Key, Integer> second = counts.lines(sessionId, 2);
      Map<Key, Integer> fin = second.isEmpty() ? first : second;
      Set<Key> keys = new java.util.TreeSet<>(KEY_ORDER);
      keys.addAll(book.keySet());
      keys.addAll(first.keySet());
      keys.addAll(second.keySet());
      for (Key k : keys) {
        int b = book.getOrDefault(k, 0);
        Integer f = fin.isEmpty() ? null : fin.getOrDefault(k, 0);
        lines.add(
            new LineView(
                k.locationCode(),
                k.sku(),
                k.lot(),
                b,
                first.isEmpty() ? null : first.getOrDefault(k, 0),
                f,
                "APPROVED".equals(s.state()) && f != null ? f - b : 0));
      }
    }
    return new View(s, counts.locations(sessionId), List.copyOf(book.keySet()), lines);
  }

  private SessionRow find(String cdcId, String sessionId, boolean lock) {
    return counts
        .find(sessionId, lock)
        .filter(x -> x.cdcId().equals(cdcId))
        .orElseThrow(() -> PmartException.notFound("NOT_FOUND", "실사 " + sessionId + " 가 없습니다."));
  }

  private static boolean sameQty(Map<Key, Integer> a, Map<Key, Integer> b) {
    Set<Key> keys = new java.util.HashSet<>(a.keySet());
    keys.addAll(b.keySet());
    for (Key k : keys) {
      if (!Objects.equals(a.getOrDefault(k, 0), b.getOrDefault(k, 0))) {
        return false;
      }
    }
    return true;
  }

  private static PmartException inProgress(Set<String> locs) {
    return new PmartException(
        "COUNT_IN_PROGRESS",
        HttpStatus.CONFLICT,
        "열린 실사가 있는 칸입니다: " + String.join(", ", locs),
        false,
        List.of());
  }

  private void publish(
      String cdcId,
      String sessionId,
      @Nullable String approvalRef,
      String commitId,
      List<Map<String, Object>> lines,
      int executed,
      Instant at) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("centerType", "CDC");
    p.put("centerId", cdcId);
    p.put("adjId", sessionId);
    // 본사 번호가 없으면(기준 아래 — 센터 관리자 결정) 실사 세션을 근거로 남긴다
    p.put(
        "approvalRef",
        Map.of("approvalId", approvalRef == null ? "CENTER:" + sessionId : approvalRef));
    p.put("commitId", commitId);
    p.put("postingId", commitId);
    p.put("lines", lines);
    p.put("executedQty", executed);
    p.put("executedAt", at.toString());
    events.publish(
        codec.create(
            events.sourceSystem(),
            "InventoryAdjustmentPosted",
            1,
            "InventoryAdjustment",
            sessionId,
            1,
            new EventEnvelope.Actor(EventEnvelope.ActorType.OPERATOR, cdcId),
            EventEnvelope.EventSource.REAL,
            p),
        Ledger.TOPIC);
  }

  static final java.util.Comparator<Key> KEY_ORDER =
      java.util.Comparator.comparing(Key::locationCode)
          .thenComparing(Key::sku)
          .thenComparing(Key::lot);
}
