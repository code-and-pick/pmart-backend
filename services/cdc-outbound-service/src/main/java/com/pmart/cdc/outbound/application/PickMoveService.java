package com.pmart.cdc.outbound.application;

import com.pmart.cdc.outbound.infrastructure.InventoryClient;
import com.pmart.cdc.outbound.infrastructure.LocationClient;
import com.pmart.common.web.PmartException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
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
 * 그대로(정책 17.4), 칸 장부에는 칸에 실제 남은 물건만 남는다. 자리는 cdc-location 에서 같은 층·같은 온도대의 91 STAGING 을 고른다(R-22 —
 * 구역 글자를 코드에 박지 않아 옛·새 배치 모두 맞다). 재고 호출은 트랜잭션 밖 — 불명이면 주기 재시도(같은 키), 거절·자리 없음이면 FAILED(원위치에 남음,
 * 경고).
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
  private final LocationClient locations;
  private final TransactionTemplate tx;
  private final Support support;

  public PickMoveService(
      JdbcTemplate jdbc,
      InventoryClient inventory,
      LocationClient locations,
      TransactionTemplate tx,
      Support support) {
    this.jdbc = jdbc;
    this.inventory = inventory;
    this.locations = locations;
    this.tx = tx;
    this.support = support;
  }

  /**
   * 출고대기 자리 후보 — 원위치와 같은 센터·층의 통로 91, 자리 번호 = 지역표 stagingBay, 구역 글자 A~Z 전부(R-22: 글자 뜻이 배치마다 바뀌어 코드에
   * 박지 않는다 — 실제 고르기는 cdc-location 의 유형 STAGING·온도대 = 원위치 온도대). 위치 코드가 7구간이 아니면 빈 목록.
   */
  static List<String> stagingCandidates(String fromLocation, String bay) {
    String[] p = fromLocation.split("-");
    if (p.length != 7) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    for (char a = 'A'; a <= 'Z'; a++) {
      out.add(p[0] + "-" + a + "-" + p[2] + "-91-" + bay + "-0-1");
    }
    return out;
  }

  /** 후보 중 원위치와 온도대가 같은 사용 중(ACTIVE) 출고대기 자리. 없으면 null. */
  static @Nullable String pickStaging(
      String from, List<String> candidates, Map<String, LocationClient.Place> places) {
    LocationClient.Place src = places.get(from);
    if (src == null || src.temperatureZone().isBlank()) {
      return null;
    }
    return candidates.stream()
        .map(places::get)
        .filter(
            x ->
                x != null
                    && "STAGING".equals(x.locationType())
                    && "ACTIVE".equals(x.state())
                    && src.temperatureZone().equals(x.temperatureZone()))
        .map(LocationClient.Place::locationCode)
        .sorted()
        .findFirst()
        .orElse(null);
  }

  /**
   * 피킹 실적 트랜잭션 안 — 옮겨 적기 기록(자리는 처리 때 cdc-location 으로 고른다 — 트랜잭션 밖). 지역표 자리 번호·할당이 없으면 바로
   * FAILED(원위치에 남음).
   */
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
    String to = null; // 처리 때 고름(R-22)
    String state = stagingBay == null || allocationId == null ? "FAILED" : "PENDING";
    String reason =
        stagingBay == null ? "출고대기 자리 없음(지역표 stagingBay)" : allocationId == null ? "할당 없음" : null;
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
    String to = m.to();
    if (to == null) {
      String bay =
          jdbc
              .query(
                  "SELECT r.staging_bay FROM transfer t JOIN region_mfc r ON r.cdc_id = t.cdc_id"
                      + " AND r.mfc_id = t.mfc_id WHERE t.trf_id = ?",
                  (rs, i) -> rs.getString(1),
                  m.trfId())
              .stream()
              .filter(java.util.Objects::nonNull)
              .findFirst()
              .orElse(null);
      List<String> cands = bay == null ? List.of() : stagingCandidates(m.from(), bay);
      String chosen = null;
      if (!cands.isEmpty()) {
        List<String> codes = new ArrayList<>(cands);
        codes.add(m.from());
        Map<String, LocationClient.Place> places;
        try {
          places = locations.places(m.cdcId(), codes);
        } catch (PmartException e) {
          log.warn("출고대기 자리 조회 안 됨 — {} 다음에 다시: {}", m.moveId(), e.getMessage());
          return; // 위치 서비스 불명·거절 — 다음 주기에 다시
        }
        chosen = pickStaging(m.from(), cands, places);
      }
      if (chosen == null) {
        String reason = "출고대기 자리 없음(같은 층·온도대 91-" + bay + ")";
        log.warn("피킹 재고 옮겨 적기 안 함 — trfId={} {} {}: {}", m.trfId(), m.sku(), m.from(), reason);
        finish(m.moveId(), "FAILED", reason);
        return;
      }
      to = chosen;
      String t = to;
      tx.executeWithoutResult(
          x ->
              jdbc.update(
                  "UPDATE pick_move SET to_location = ? WHERE move_id = ? AND to_location IS NULL",
                  t,
                  m.moveId()));
    }
    Map<String, Object> line = new LinkedHashMap<>();
    line.put("sku", m.sku());
    line.put("lot", m.lot());
    line.put("fromLocationCode", m.from());
    line.put("toLocationCode", to);
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
    finish(m.moveId(), state, reason);
  }

  private void finish(String moveId, String state, @Nullable String reason) {
    Instant at = support.now();
    tx.executeWithoutResult(
        x ->
            jdbc.update(
                "UPDATE pick_move SET state = ?, reason = ?, updated_at = ? WHERE move_id = ?"
                    + " AND state = 'PENDING'",
                state,
                reason,
                Timestamp.from(at),
                moveId));
  }
}
