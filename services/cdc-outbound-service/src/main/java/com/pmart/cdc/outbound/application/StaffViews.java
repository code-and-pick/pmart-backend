package com.pmart.cdc.outbound.application;

import com.pmart.cdc.outbound.infrastructure.StaffViewStore;
import com.pmart.common.web.PmartException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * DC 백오피스 직원 조회(0.11.0, 2026-11-25 — 사용자 "Dc 백오피스 만들어줘", 계획 docs/cdc/cdc-backoffice-plan.md 1단계).
 * 자기 센터 운영자만(토큰 centers). 읽기만 — 상태를 바꾸지 않는다.
 */
@Service
public class StaffViews {

  static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

  public record Page(List<StaffViewStore.TransferRow> items, int page, int size, boolean hasNext) {}

  public record Detail(StaffViewStore.TransferRow transfer, List<StaffViewStore.LineRow> lines) {}

  public record Summary(
      String cdcId,
      LocalDate date,
      StaffViewStore.Totals totals,
      Map<String, Long> transfersByState,
      Map<String, Long> wavesByState,
      Map<String, Long> tasksByKindState,
      @Nullable Double progress,
      Instant asOf,
      List<StaffViewStore.MfcTotals> byMfc) {}

  private final StaffViewStore store;
  private final Clock clock;
  private final WaveService waves;
  private final DepartureService departures;
  private final ReadinessService readiness;

  public StaffViews(
      StaffViewStore store,
      Clock clock,
      WaveService waves,
      DepartureService departures,
      ReadinessService readiness) {
    this.store = store;
    this.clock = clock;
    this.waves = waves;
    this.departures = departures;
    this.readiness = readiness;
  }

  public record ShortagePage(
      List<StaffViewStore.ShortageRow> items, int page, int size, boolean hasNext) {}

  /** 피킹 부족 재할당 목록(0.19.0 결품 관리). */
  public ShortagePage shortages(String cdcId, @Nullable String state, int page, int size) {
    Support.requireCenter(cdcId);
    int p = Math.max(0, page);
    int s = Math.min(200, Math.max(1, size));
    var rows = store.shortages(cdcId, blank(state), p, s);
    boolean next = rows.size() > s;
    return new ShortagePage(next ? rows.subList(0, s) : rows, p, s, next);
  }

  public record LpnPage(
      List<StaffViewStore.LpnRow> items,
      java.util.Map<String, List<StaffViewStore.LpnContent>> contents,
      int page,
      int size,
      boolean hasNext) {}

  /** LPN 목록(0.19.0 LPN 관리) — BOX 내용 포함. */
  public LpnPage lpns(
      String cdcId, @Nullable String trfId, @Nullable String unitType, int page, int size) {
    Support.requireCenter(cdcId);
    int p = Math.max(0, page);
    int s = Math.min(200, Math.max(1, size));
    var rows = store.lpns(cdcId, blank(trfId), blank(unitType), p, s);
    boolean next = rows.size() > s;
    var items = next ? rows.subList(0, s) : rows;
    var contents = new java.util.HashMap<String, List<StaffViewStore.LpnContent>>();
    for (var c : store.lpnContents(items.stream().map(StaffViewStore.LpnRow::lpn).toList())) {
      contents.computeIfAbsent(c.lpn(), k -> new java.util.ArrayList<>()).add(c);
    }
    return new LpnPage(items, contents, p, s, next);
  }

  public record DeparturePage(
      List<DepartureService.View> items, int page, int size, boolean hasNext) {}

  /** 출발 검증 결과 목록(0.16.0 M-3) — 줄은 내부 조회와 같은 내용. */
  public DeparturePage departures(
      String cdcId, @Nullable String result, @Nullable String number, int page, int size) {
    Support.requireCenter(cdcId);
    int p = Math.max(0, page);
    int s = Math.min(200, Math.max(1, size));
    var ids =
        store.departureIds(
            cdcId,
            blank(result),
            com.pmart.cdc.outbound.infrastructure.DisplayNumberStore.normalize(
                com.pmart.cdc.outbound.infrastructure.DisplayNumberStore.Kind.DEPARTURE, number),
            p,
            s);
    boolean next = ids.size() > s;
    var items = (next ? ids.subList(0, s) : ids).stream().map(departures::view).toList();
    return new DeparturePage(items, p, s, next);
  }

  /** TRF 출발 준비(0.16.0 M-3) — 이 센터 TRF 만(아니면 404). */
  public ReadinessService.Readiness readiness(String cdcId, String trfId) {
    Support.requireCenter(cdcId);
    store
        .transfer(cdcId, trfId)
        .orElseThrow(() -> PmartException.notFound("TRF_NOT_FOUND", "TRF 가 없습니다."));
    return readiness.of(trfId);
  }

  public record WavePage(List<WaveService.WaveView> items, int page, int size, boolean hasNext) {}

  /** Wave 목록(0.15.0 W-1) — 줄은 GET /waves/{id} 와 같은 내용. */
  public WavePage waves(
      String cdcId, @Nullable String state, @Nullable String number, int page, int size) {
    Support.requireCenter(cdcId);
    int p = Math.max(0, page);
    int s = Math.min(200, Math.max(1, size));
    var ids =
        store.waveIds(
            cdcId,
            blank(state),
            com.pmart.cdc.outbound.infrastructure.DisplayNumberStore.normalize(
                com.pmart.cdc.outbound.infrastructure.DisplayNumberStore.Kind.WAVE, number),
            p,
            s);
    boolean next = ids.size() > s;
    var items = (next ? ids.subList(0, s) : ids).stream().map(waves::get).toList();
    return new WavePage(items, p, s, next);
  }

  public Page transfers(
      String cdcId,
      @Nullable String state,
      @Nullable String mfcId,
      @Nullable String number,
      int page,
      int size) {
    Support.requireCenter(cdcId);
    int p = Math.max(0, page);
    int s = Math.min(200, Math.max(1, size));
    var rows =
        store.transfers(
            cdcId,
            blank(state),
            blank(mfcId),
            com.pmart.cdc.outbound.infrastructure.DisplayNumberStore.normalize(
                com.pmart.cdc.outbound.infrastructure.DisplayNumberStore.Kind.TRF, number),
            p,
            s);
    boolean next = rows.size() > s;
    return new Page(next ? rows.subList(0, s) : rows, p, s, next);
  }

  public Detail transfer(String cdcId, String trfId) {
    Support.requireCenter(cdcId);
    var t =
        store
            .transfer(cdcId, trfId)
            .orElseThrow(() -> PmartException.notFound("TRF_NOT_FOUND", "TRF 가 없습니다."));
    return new Detail(t, store.lines(trfId));
  }

  public Summary summary(String cdcId, @Nullable LocalDate date) {
    Support.requireCenter(cdcId);
    Instant now = Instant.now(clock);
    LocalDate d = date != null ? date : LocalDate.ofInstant(now, SEOUL);
    Instant from = d.atStartOfDay(SEOUL).toInstant();
    Instant to = d.plusDays(1).atStartOfDay(SEOUL).toInstant();
    var totals = store.totals(cdcId, from, to);
    Double progress = totals.approved() == 0 ? null : (double) totals.picked() / totals.approved();
    return new Summary(
        cdcId,
        d,
        totals,
        store.transfersByState(cdcId, from, to),
        store.wavesByState(cdcId, from, to),
        store.tasksByKindState(cdcId, from, to),
        progress,
        now,
        store.byMfc(cdcId, from, to));
  }

  static final int MAX_DAYS = 31;

  /** 날짜 범위 출고 집계(0.27.0 KP-2) — 날짜마다 {@link #summary}. */
  public List<Summary> dailySummary(String cdcId, LocalDate from, LocalDate to) {
    Support.requireCenter(cdcId);
    range(from, to);
    List<Summary> out = new java.util.ArrayList<>();
    for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
      out.add(summary(cdcId, d));
    }
    return out;
  }

  public record WorkerThroughput(
      String workerId,
      int tasks,
      Map<String, Long> tasksByKind,
      int pickedLines,
      int pickedQty,
      int shortLines,
      int shortQty,
      @Nullable Instant firstStartedAt,
      @Nullable Instant lastCompletedAt,
      @Nullable String mainFloor,
      @Nullable String mainZone) {}

  public record Throughput(LocalDate from, LocalDate to, List<WorkerThroughput> items) {}

  /** 작업자 실적(0.27.0 WF-1) — 끝난 작업(DONE·PARTIAL)의 담당자별, 작업 기록에서 계산. */
  public Throughput throughput(String cdcId, @Nullable LocalDate from, @Nullable LocalDate to) {
    Support.requireCenter(cdcId);
    LocalDate today = LocalDate.ofInstant(Instant.now(clock), SEOUL);
    LocalDate f = from != null ? from : (to != null ? to : today);
    LocalDate t = to != null ? to : (from != null ? from : today);
    range(f, t);
    var rows =
        store.finishedTasks(
            cdcId,
            f.atStartOfDay(SEOUL).toInstant(),
            t.plusDays(1).atStartOfDay(SEOUL).toInstant());
    Map<String, List<StaffViewStore.FinishedTask>> by = new java.util.TreeMap<>();
    for (var r : rows) {
      by.computeIfAbsent(r.assigneeId(), k -> new java.util.ArrayList<>()).add(r);
    }
    List<WorkerThroughput> items = new java.util.ArrayList<>();
    for (var e : by.entrySet()) {
      List<StaffViewStore.FinishedTask> ts = e.getValue();
      Map<String, Long> kinds = new java.util.TreeMap<>();
      Map<String, Integer> floors = new java.util.HashMap<>();
      Map<String, Integer> zones = new java.util.HashMap<>();
      int pl = 0;
      int pq = 0;
      int sl = 0;
      int sq = 0;
      Instant first = null;
      Instant last = null;
      for (var x : ts) {
        kinds.merge(x.kind(), 1L, Long::sum);
        if (x.floor() != null) {
          floors.merge(x.floor(), 1, Integer::sum);
        }
        if (x.zone() != null) {
          zones.merge(x.zone(), 1, Integer::sum);
        }
        pl += x.pickedLines();
        pq += x.pickedQty();
        sl += x.shortLines();
        sq += x.shortQty();
        if (x.startedAt() != null && (first == null || x.startedAt().isBefore(first))) {
          first = x.startedAt();
        }
        if (x.completedAt() != null && (last == null || x.completedAt().isAfter(last))) {
          last = x.completedAt();
        }
      }
      items.add(
          new WorkerThroughput(
              e.getKey(),
              ts.size(),
              kinds,
              pl,
              pq,
              sl,
              sq,
              first,
              last,
              most(floors),
              most(zones)));
    }
    return new Throughput(f, t, items);
  }

  private static @Nullable String most(Map<String, Integer> m) {
    String best = null;
    int n = -1;
    for (var e : new java.util.TreeMap<>(m).entrySet()) {
      if (e.getValue() > n) {
        best = e.getKey();
        n = e.getValue();
      }
    }
    return best;
  }

  private static void range(LocalDate from, LocalDate to) {
    if (to.isBefore(from)) {
      throw new com.pmart.common.web.PmartException(
          com.pmart.common.web.ErrorCode.VALIDATION_FAILED, "from 은 to 와 같거나 앞이어야 합니다.");
    }
    if (java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
      throw new com.pmart.common.web.PmartException(
          com.pmart.common.web.ErrorCode.VALIDATION_FAILED, "기간은 31일까지입니다.");
    }
  }

  private static @Nullable String blank(@Nullable String v) {
    return v == null || v.isBlank() ? null : v.strip();
  }
}
