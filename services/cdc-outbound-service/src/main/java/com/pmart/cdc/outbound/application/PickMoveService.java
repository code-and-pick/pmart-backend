package com.pmart.cdc.outbound.application;

import com.pmart.cdc.outbound.infrastructure.InventoryClient;
import com.pmart.common.web.PmartException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 피킹 재고를 출고대기 자리로 옮겨 적기(0.14.0 — 사용자 2026-11-25 "피킹재고는 피킹재고로 따로" → "출고대기 자리로 옮겨 적기", 설계
 * docs/cdc/design/2026-11-25-count-adjustment.md §5). 실피킹(TRF 몫·줄)마다 cdc-inventory
 * move-postings(PICK_MOVE, allocationId — 예약도 함께)로 그 칸 → 그 층 출고대기(통로 91)의 목적 MFC 자리. 총현물·예약 수는
 * 그대로(정책 17.4), 칸 장부에는 칸에 실제 남은 물건만 남는다. 재고 호출은 트랜잭션 밖 — 불명이면 주기 재시도(같은 키), 거절·자리 없음이면 FAILED(원위치에
 * 남음, 경고).
 */
@Service
public class PickMoveService {

  private static final Logger log = LoggerFactory.getLogger(PickMoveService.class);

  record Move(
      String moveId,
      String trfId,
      String taskId,
      @Nullable String allocationId,
      String sku,
      String lot,
      String from,
      @Nullable String to,
      int qty,
      String cdcId) {}

  private final JdbcTemplate jdbc;
  private final InventoryClient inventory;
  private final TransactionTemplate tx;
  private final Support support;

  public PickMoveService(
      JdbcTemplate jdbc, InventoryClient inventory, TransactionTemplate tx, Support support) {
    this.jdbc = jdbc;
    this.inventory = inventory;
    this.tx = tx;
    this.support = support;
  }

  /**
   * 출고대기 자리 코드 — 구역 글자는 온도대(냉동 A·냉장 B 는 그대로), 칠곡 상온은 5층 C·7층 G(도면의 91 행, CDC-5). 위치 코드가 7구간이 아니면
   * null.
   */
  static @Nullable String stagingCode(String fromLocation, String bay) {
    String[] p = fromLocation.split("-");
    if (p.length != 7) {
      return null;
    }
    String area = p[1];
    String floor = p[2];
    if ("CDC01".equals(p[0]) && !"A".equals(area) && !"B".equals(area)) {
      area =
          switch (floor) {
            case "5" -> "C";
            case "7" -> "G";
            default -> area;
          };
    }
    return p[0] + "-" + area + "-" + floor + "-91-" + bay + "-0-1";
  }

  /** 피킹 실적 트랜잭션 안 — 옮겨 적기 기록. 자리가 없으면 바로 FAILED(원위치에 남음). */
  void record(
      String cdcId,
      String trfId,
      String taskId,
      @Nullable String allocationId,
      String sku,
      String lot,
      String from,
      @Nullable String stagingBay,
      int qty,
      Instant at) {
    String to = stagingBay == null ? null : stagingCode(from, stagingBay);
    String state = to == null || allocationId == null ? "FAILED" : "PENDING";
    String reason =
        to == null ? "출고대기 자리 없음(지역표 stagingBay)" : allocationId == null ? "할당 없음" : null;
    jdbc.update(
        "INSERT INTO pick_move (move_id, trf_id, task_id, allocation_id, sku, lot, from_location,"
            + " to_location, qty, state, reason, created_at, updated_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
        Support.newId("PMV"),
        trfId,
        taskId,
        allocationId,
        sku,
        lot,
        from,
        to,
        qty,
        state,
        reason,
        Timestamp.from(at),
        Timestamp.from(at));
    if (reason != null) {
      log.warn("피킹 재고 옮겨 적기 안 함 — trfId={} {} {}: {}", trfId, sku, from, reason);
    }
  }

  int openOfTrf(String trfId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM pick_move WHERE trf_id = ? AND state = 'PENDING'",
            Integer.class,
            trfId);
    return n == null ? 0 : n;
  }

  /** 남은 옮겨 적기를 처리한다(피킹 실적 뒤·주기). */
  @Scheduled(
      initialDelayString = "${pmart.outbound.shortage-retry-interval:PT30S}",
      fixedDelayString = "${pmart.outbound.shortage-retry-interval:PT30S}")
  public void processOpen() {
    List<Move> open =
        jdbc.query(
            "SELECT m.move_id, m.trf_id, m.task_id, m.allocation_id, m.sku, m.lot, m.from_location,"
                + " m.to_location, m.qty, t.cdc_id FROM pick_move m JOIN transfer t ON t.trf_id = m.trf_id"
                + " WHERE m.state = 'PENDING' ORDER BY m.created_at, m.move_id LIMIT 100",
            (rs, i) ->
                new Move(
                    rs.getString("move_id"),
                    rs.getString("trf_id"),
                    rs.getString("task_id"),
                    rs.getString("allocation_id"),
                    rs.getString("sku"),
                    rs.getString("lot"),
                    rs.getString("from_location"),
                    rs.getString("to_location"),
                    rs.getInt("qty"),
                    rs.getString("cdc_id")));
    for (Move m : open) {
      try {
        process(m);
      } catch (RuntimeException e) {
        log.warn("피킹 재고 옮겨 적기 실패 — {} 다음에 다시: {}", m.moveId(), e.toString());
      }
    }
  }

  private void process(Move m) {
    Map<String, Object> line = new LinkedHashMap<>();
    line.put("sku", m.sku());
    line.put("lot", m.lot());
    line.put("fromLocationCode", m.from());
    line.put("toLocationCode", m.to());
    line.put("qty", m.qty());
    line.put("allocationId", m.allocationId());
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("cdcId", m.cdcId());
    body.put("moveKind", "PICK_MOVE");
    body.put("sourceRef", m.taskId());
    body.put("lines", List.of(line));
    String state;
    String reason = null;
    try {
      inventory.move("outbound-pickmove-" + m.moveId(), body);
      state = "DONE";
    } catch (PmartException e) {
      if (TransferService.unknown(e)) {
        return; // 같은 키로 다시
      }
      state = "FAILED";
      reason = DepartureService.residualReason(e);
      log.warn("피킹 재고 옮겨 적기 거절 — trfId={} {} {}: {}", m.trfId(), m.sku(), m.from(), reason);
    }
    String s = state;
    String r = reason;
    Instant at = support.now();
    tx.executeWithoutResult(
        x ->
            jdbc.update(
                "UPDATE pick_move SET state = ?, reason = ?, updated_at = ? WHERE move_id = ?"
                    + " AND state = 'PENDING'",
                s,
                r,
                Timestamp.from(at),
                m.moveId()));
  }
}
