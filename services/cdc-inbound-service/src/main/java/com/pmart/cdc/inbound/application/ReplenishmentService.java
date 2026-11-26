package com.pmart.cdc.inbound.application;

import com.pmart.cdc.inbound.infrastructure.Clients;
import com.pmart.cdc.inbound.infrastructure.Clients.Loc;
import com.pmart.cdc.inbound.infrastructure.Clients.PickFace;
import com.pmart.cdc.inbound.infrastructure.Clients.Place;
import com.pmart.common.web.PmartException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 최소 이하 감지·자동 보충(0.2.0, WMS CDC-F-013 — 사용자 2026-11-26 "박스 입수"). 최소·목표가 설정된 피킹 선반(cdc-location)마다:
 *
 * <ul>
 *   <li>지금 정상 현물 > 최소면 ABOVE_MIN.
 *   <li>필요량 = max(0, 목표 − 지금 − 진행 중 보충), 0 이면 OPEN_TASK_COVERS.
 *   <li>지시량 = 입수 × floor(min(필요량, 출발 가능량, 칸 여유) / 입수) — 입수 미등록 UNIT_MISSING, 0 이면
 *       SOURCE_SHORT·ROOM_SHORT·BELOW_UNIT.
 *   <li>출발 = 같은 SKU 의 보관 랙(RESERVE) 칸, 소비기한 이른 LOT 먼저(예약 안 된 정상 현물 − 진행 중 작업 몫).
 * </ul>
 *
 * 수동 보충 생성은 없다(문서 "수동 선반 보충 요청/새 작업 생성은 초기 제공하지 않는다"). 칸마다 마지막 판단을 replenishment_check 에 남긴다.
 */
@Service
public class ReplenishmentService {

  private static final Logger log = LoggerFactory.getLogger(ReplenishmentService.class);

  public record Check(
      String locationCode,
      @Nullable String sku,
      @Nullable Integer currentQty,
      int minQty,
      int targetQty,
      @Nullable Integer openQty,
      @Nullable Integer needQty,
      @Nullable Integer orderedQty,
      @Nullable Integer unitQty,
      String reason,
      @Nullable String taskId,
      java.time.Instant checkedAt) {}

  private final JdbcTemplate jdbc;
  private final Clients clients;
  private final TaskService tasks;

  public ReplenishmentService(JdbcTemplate jdbc, Clients clients, TaskService tasks) {
    this.jdbc = jdbc;
    this.clients = clients;
    this.tasks = tasks;
  }

  @Scheduled(
      initialDelayString = "${pmart.inbound.replenishment-interval:PT1M}",
      fixedDelayString = "${pmart.inbound.replenishment-interval:PT1M}")
  public void scheduled() {
    try {
      sweep();
    } catch (RuntimeException e) {
      log.warn("자동 보충 감지 실패 — 다음에 다시: {}", e.toString());
    }
  }

  /** 한 번 훑기(시험에서도 부른다). */
  public void sweep() {
    Map<String, List<PickFace>> byCenter = new LinkedHashMap<>();
    for (PickFace f : clients.pickFaces()) {
      byCenter.computeIfAbsent(f.centerId(), k -> new ArrayList<>()).add(f);
    }
    byCenter.forEach(this::sweepCenter);
  }

  private void sweepCenter(String cdcId, List<PickFace> faces) {
    List<Place> places = clients.places(cdcId, null);
    for (PickFace f : faces) {
      try {
        evaluate(cdcId, f, places);
      } catch (RuntimeException e) {
        log.warn("보충 판단 실패 — {}: {}", f.locationCode(), e.toString());
      }
    }
  }

  private void evaluate(String cdcId, PickFace f, List<Place> places) {
    List<Place> here =
        places.stream().filter(p -> p.locationCode().equals(f.locationCode())).toList();
    TreeSet<String> skus = new TreeSet<>();
    here.forEach(p -> skus.add(p.sku()));
    if (skus.isEmpty()) {
      save(cdcId, f, null, null, null, null, null, null, "NO_SKU", null);
      return;
    }
    String sku = skus.first(); // 칸 하나 = SKU 하나(CDC-7) — 여럿이면 코드 순 첫 SKU
    int current =
        here.stream()
            .filter(p -> p.sku().equals(sku) && "RELEASED".equals(p.qualityState()))
            .mapToInt(Place::onHand)
            .sum();
    if (current > f.minQty()) {
      save(cdcId, f, sku, current, null, null, null, null, "ABOVE_MIN", null);
      return;
    }
    int open = tasks.openTo(f.locationCode(), sku);
    int need = Math.max(0, f.targetQty() - current - open);
    if (need == 0) {
      save(cdcId, f, sku, current, open, 0, null, null, "OPEN_TASK_COVERS", null);
      return;
    }
    Integer unit = clients.sku(sku).unitsPerBox();
    if (unit == null) {
      save(cdcId, f, sku, current, open, need, null, null, "UNIT_MISSING", null);
      return;
    }
    // 출발 후보 — 같은 SKU 의 다른 칸, 예약 안 된 정상 현물 − 진행 중 작업 몫, 보관 랙만, 소비기한 이른 LOT 먼저
    List<Place> cands =
        places.stream()
            .filter(
                p ->
                    p.sku().equals(sku)
                        && !p.locationCode().equals(f.locationCode())
                        && "RELEASED".equals(p.qualityState())
                        && p.onHand() - p.reserved() > 0)
            .toList();
    Map<String, Loc> locs =
        cands.isEmpty()
            ? Map.of()
            : clients.lookup(cdcId, cands.stream().map(Place::locationCode).distinct().toList());
    Place src =
        cands.stream()
            .filter(
                p -> {
                  Loc l = locs.get(p.locationCode());
                  return l != null
                      && "RESERVE".equals(l.locationType())
                      && "ACTIVE".equals(l.state());
                })
            .filter(p -> free(p) > 0)
            .sorted(
                Comparator.comparing((Place p) -> p.expiresAt() == null ? "9999" : p.expiresAt())
                    .thenComparing(Place::lot)
                    .thenComparing(Place::locationCode))
            .findFirst()
            .orElse(null);
    int srcFree = src == null ? 0 : free(src);
    int occupied = here.stream().mapToInt(Place::onHand).sum();
    int room =
        f.maxQty() == null ? 0 : f.maxQty() - occupied - tasks.openTo(f.locationCode(), null);
    int qty = unit * (Math.min(need, Math.min(srcFree, Math.max(0, room))) / unit);
    if (qty <= 0) {
      String reason = srcFree < unit ? "SOURCE_SHORT" : room < unit ? "ROOM_SHORT" : "BELOW_UNIT";
      save(cdcId, f, sku, current, open, need, 0, unit, reason, null);
      return;
    }
    String taskId;
    try {
      taskId =
          tasks.createReplenishment(
              cdcId,
              new TaskService.NewTask(
                  "REPLENISHMENT",
                  sku,
                  src.lot(),
                  "RELEASED",
                  src.locationCode(),
                  f.locationCode(),
                  qty,
                  "최소 이하 자동 보충(현재 " + current + " ≤ 최소 " + f.minQty() + ")"));
    } catch (PmartException e) {
      save(cdcId, f, sku, current, open, need, 0, unit, "RESERVE_FAILED", null);
      log.warn("자동 보충 작업 못 만듦 — {}: {}", f.locationCode(), e.getMessage());
      return;
    }
    save(cdcId, f, sku, current, open, need, qty, unit, "CREATED", taskId);
  }

  private int free(Place p) {
    return p.onHand()
        - p.reserved()
        - tasks.pendingFrom(p.locationCode(), p.sku(), p.lot(), p.qualityState());
  }

  private void save(
      String cdcId,
      PickFace f,
      @Nullable String sku,
      @Nullable Integer current,
      @Nullable Integer open,
      @Nullable Integer need,
      @Nullable Integer ordered,
      @Nullable Integer unit,
      String reason,
      @Nullable String taskId) {
    jdbc.update(
        "INSERT INTO replenishment_check (location_code, cdc_id, sku, current_qty, min_qty, target_qty, open_qty,"
            + " need_qty, ordered_qty, unit_qty, reason, task_id, checked_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)"
            + " ON CONFLICT (location_code) DO UPDATE SET cdc_id = EXCLUDED.cdc_id, sku = EXCLUDED.sku,"
            + " current_qty = EXCLUDED.current_qty, min_qty = EXCLUDED.min_qty, target_qty = EXCLUDED.target_qty,"
            + " open_qty = EXCLUDED.open_qty, need_qty = EXCLUDED.need_qty, ordered_qty = EXCLUDED.ordered_qty,"
            + " unit_qty = EXCLUDED.unit_qty, reason = EXCLUDED.reason, task_id = EXCLUDED.task_id,"
            + " checked_at = EXCLUDED.checked_at",
        f.locationCode(),
        cdcId,
        sku,
        current,
        f.minQty(),
        f.targetQty(),
        open,
        need,
        ordered,
        unit,
        reason,
        taskId,
        Timestamp.from(tasks.now()));
  }

  public List<Check> checks(String cdcId, @Nullable String reason, int limit) {
    Operator.requireCenter(cdcId);
    return jdbc.query(
        "SELECT * FROM replenishment_check WHERE cdc_id = ?"
            + (reason == null ? "" : " AND reason = ?")
            + " ORDER BY location_code LIMIT ?",
        (rs, i) ->
            new Check(
                rs.getString("location_code"),
                rs.getString("sku"),
                (Integer) rs.getObject("current_qty"),
                rs.getInt("min_qty"),
                rs.getInt("target_qty"),
                (Integer) rs.getObject("open_qty"),
                (Integer) rs.getObject("need_qty"),
                (Integer) rs.getObject("ordered_qty"),
                (Integer) rs.getObject("unit_qty"),
                rs.getString("reason"),
                rs.getString("task_id"),
                rs.getTimestamp("checked_at").toInstant()),
        reason == null ? new Object[] {cdcId, limit} : new Object[] {cdcId, reason, limit});
  }
}
