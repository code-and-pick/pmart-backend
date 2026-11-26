package com.pmart.cdc.outbound.application;

import com.pmart.cdc.outbound.domain.PickPlan;
import com.pmart.cdc.outbound.infrastructure.OutboundStore;
import com.pmart.cdc.outbound.infrastructure.OutboundStore.TaskLineRow;
import com.pmart.cdc.outbound.infrastructure.OutboundStore.TransferRow;
import com.pmart.cdc.outbound.infrastructure.OutboundStore.WaveRow;
import com.pmart.common.messaging.EventEnvelope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * 실사 부족 받기(0.17.0 — cdc-inventory {@code AllocationShortfall}, 설계
 * docs/cdc/design/2026-11-25-count-adjustment.md §4, 사용자 결정 "조정은 하고 나중 예약부터 부족 표시"). 재고가 그 칸 예약을 이미
 * 풀었으므로 그 TRF 의 안 끝난 작업 줄(그 SKU·LOT·위치)에 부족을 적고(사유 COUNT_LOSS), TRF 줄 부족을 더한 뒤 피킹 부족 후속(해제 단계를 건너뛴
 * RELEASED)으로 다시 잡거나 못 찾음으로 처리한다. cdc-outbound 의 첫 사건 구독이다(바인딩 inventoryEvents-in-0 →
 * cdc.inventory.v1).
 */
@Service
public class ShortfallService {

  private static final Logger log = LoggerFactory.getLogger(ShortfallService.class);

  private final OutboundStore store;
  private final JdbcTemplate jdbc;
  private final ShortageService shortages;
  private final WaveService waves;
  private final TransactionTemplate tx;
  private final Support support;

  public ShortfallService(
      OutboundStore store,
      JdbcTemplate jdbc,
      ShortageService shortages,
      WaveService waves,
      TransactionTemplate tx,
      Support support) {
    this.store = store;
    this.jdbc = jdbc;
    this.shortages = shortages;
    this.waves = waves;
    this.tx = tx;
    this.support = support;
  }

  /** 소비 — inbox 기록과 같은 트랜잭션 안에서 부른다(EventConsumers). 재고 호출은 커밋 뒤 주기 작업이. */
  public void apply(EventEnvelope e) {
    JsonNode p = e.payload();
    String trfId = p.path("trfId").asString();
    String allocationId = p.path("allocationId").asString();
    String sku = p.path("sku").asString();
    String lot = p.path("lot").asString();
    String loc = p.path("locationCode").asString();
    int qty = p.path("qty").asInt(0);
    // 사유 그대로(COUNT_LOSS 실사·QUALITY_HOLD 품질 보류 — cdc-inventory 0.15.0)
    String reason = p.path("reason").asString("COUNT_LOSS");
    if (qty <= 0) {
      return;
    }
    TransferRow t = store.transfer(trfId, true).orElse(null);
    if (t == null
        || !allocationId.equals(t.allocationId())
        || ShortageService.GONE.contains(t.state())) {
      log.warn("실사 부족 무시 — trfId={} allocationId={}: TRF 없음·다른 할당·출발/취소", trfId, allocationId);
      return;
    }
    Instant at = support.now();
    if ("RECALL".equals(reason) && staging(loc)) {
      // 0.23.0 리콜 — 이미 집어 출고대기에 옮겨 적은 물건: 재할당하지 않고 막는다(출발 준비 RECALL_HOLD, 출발 전기는 재고가 거절)
      jdbc.update(
          "INSERT INTO recall_hold (trf_id, sku, lot, location_code, source_ref, allocation_id, qty, created_at)"
              + " VALUES (?,?,?,?,?,?,?,?) ON CONFLICT (trf_id, sku, lot, location_code, source_ref)"
              + " DO UPDATE SET qty = recall_hold.qty + EXCLUDED.qty",
          trfId,
          sku,
          lot,
          loc,
          p.path("sourceRef").asString("-"),
          allocationId,
          qty,
          java.sql.Timestamp.from(at));
      log.warn("리콜로 출고대기 물량 막힘 — trfId={} {}/{} {} x{}", trfId, sku, lot, loc, qty);
      return;
    }
    int left = qty;
    String firstTask = null;
    List<String> touched = new ArrayList<>();
    // 그 TRF 몫이 남은 안 끝난 작업 줄 — 먼저 만든 작업부터
    List<Object[]> open =
        jdbc.query(
            "SELECT s.task_id, s.line_no, s.planned_qty - s.picked_qty - s.short_qty AS left_qty"
                + " FROM task_line_share s JOIN task_line l ON l.task_id = s.task_id AND l.line_no = s.line_no"
                + " JOIN task t ON t.task_id = s.task_id WHERE s.trf_id = ? AND l.sku = ? AND l.lot = ?"
                + " AND l.location_code = ? AND t.state NOT IN ('DONE', 'PARTIAL', 'CANCELLED')"
                + " AND s.planned_qty - s.picked_qty - s.short_qty > 0 ORDER BY t.seq, s.line_no FOR UPDATE OF s",
            (rs, i) -> new Object[] {rs.getString(1), rs.getInt(2), rs.getInt(3)},
            trfId,
            sku,
            lot,
            loc);
    for (Object[] o : open) {
      if (left <= 0) {
        break;
      }
      String taskId = (String) o[0];
      int lineNo = (Integer) o[1];
      int take = Math.min(left, (Integer) o[2]);
      store.addLineResult(taskId, lineNo, 0, take, reason);
      store.addShareResult(taskId, lineNo, trfId, 0, take);
      if (firstTask == null) {
        firstTask = taskId;
      }
      touched.add(taskId);
      left -= take;
    }
    store.addLinePicked(trfId, sku, 0, qty);
    for (String taskId : touched) {
      settle(taskId, trfId, at);
    }
    shortages.recordReleased(
        trfId,
        t.waveId() == null ? ShortageService.NO_WAVE : t.waveId(),
        firstTask == null ? "-" : firstTask,
        sku,
        lot,
        loc,
        qty,
        at);
  }

  /** 출고대기 자리(통로 91) — 7구간 코드의 넷째. */
  static boolean staging(String locationCode) {
    String[] p = locationCode.split("-");
    return p.length == 7 && "91".equals(p[3]);
  }

  /** 리콜로 막힌 물량이 있는 TRF 인지(출발 준비 RECALL_HOLD). */
  int recallHeldOfTrf(String trfId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM recall_hold WHERE trf_id = ?", Integer.class, trfId);
    return n == null ? 0 : n;
  }

  /** 줄이 다 정리된 작업은 끝낸다(피킹 실적과 같은 규칙) — TRF PICKED·Wave WORK_DONE 도. */
  private void settle(String taskId, String trfId, Instant at) {
    var task = store.task(taskId, true).orElseThrow();
    List<int[]> lines = new ArrayList<>();
    for (TaskLineRow r : store.taskLines(taskId)) {
      lines.add(new int[] {r.plannedQty(), r.pickedQty(), r.shortQty()});
    }
    String state = PickPlan.afterReport(task.state(), lines);
    if (state.equals(task.state())) {
      return;
    }
    store.setTaskState(taskId, state, at);
    if (store.openTasks("trf_id", trfId) == 0) {
      store.setTransferState(trfId, "PICKED", null, at);
    }
    if (store.openTasks("wave_id", task.waveId()) == 0) {
      WaveRow w = store.wave(task.waveId(), true).orElseThrow();
      if (store.setWaveState(w.waveId(), "WORK_DONE", w.version(), null)) {
        waves.publishState(
            w,
            "WORK_DONE",
            store.transfersOfWave(w.waveId()).stream().map(TransferRow::trfId).toList(),
            "cdc-outbound",
            w.version() + 1,
            at);
      }
    }
  }
}
