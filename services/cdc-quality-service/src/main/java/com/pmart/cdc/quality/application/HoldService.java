package com.pmart.cdc.quality.application;

import com.pmart.cdc.quality.domain.HqApprovalRules;
import com.pmart.cdc.quality.infrastructure.InventoryClient;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 품질 보류·해제(0.2.0, WMS CDC-F-028 — 사용자 2026-11-26 "보류·해제·폐기만"·"MFC 품질과 같게"). 판단·승인 기록은 여기, 상태·수량은
 * cdc-inventory {@code quality-postings}. 재고 호출은 트랜잭션 밖 — 불명이면 같은 키로 주기 재시도.
 */
@Service
public class HoldService {

  private static final Logger log = LoggerFactory.getLogger(HoldService.class);
  static final String OP_HOLD = "cdcQuality:hold";
  static final String OP_RELEASE_REQ = "cdcQuality:releaseRequest";
  static final String OP_RELEASE_DECIDE = "cdcQuality:releaseDecision";

  public record Scope(
      String sku, String lot, @Nullable String locationCode, @Nullable Integer qty) {}

  public record Exact(String sku, String lot, String locationCode, int qty) {}

  public record HoldCmd(
      String reason,
      @Nullable String sourceRef,
      @Nullable String evidenceRef,
      @Nullable String note,
      List<Scope> lines) {}

  public record Ref(String id) {}

  public record HoldLineView(
      int lineNo,
      String sku,
      String lot,
      @Nullable String locationCode,
      @Nullable Integer requestedQty,
      int heldQty,
      int releasedQty,
      int disposedQty,
      @Nullable String commitId,
      String state) {}

  public record ReleaseView(
      String releaseId,
      String holdId,
      String state,
      String requestedBy,
      @Nullable String note,
      @Nullable String decidedBy,
      @Nullable String decisionReason,
      @Nullable String approvalRef,
      @Nullable String commitId,
      @Nullable String rejectReason,
      List<Exact> lines,
      Instant createdAt,
      @Nullable Instant decidedAt) {}

  public record HoldView(
      String holdId,
      String cdcId,
      String reason,
      @Nullable String sourceRef,
      @Nullable String evidenceRef,
      @Nullable String note,
      String state,
      String requestedBy,
      @Nullable String rejectReason,
      List<HoldLineView> lines,
      List<ReleaseView> releases,
      Instant createdAt,
      Instant updatedAt) {}

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final RequestIdempotency idempotency;
  private final InventoryClient inventory;
  private final ApprovalGate gate;
  private final ObjectMapper mapper;
  private final Clock clock;

  public HoldService(
      JdbcTemplate jdbc,
      TransactionTemplate tx,
      RequestIdempotency idempotency,
      InventoryClient inventory,
      ApprovalGate gate,
      ObjectMapper mapper,
      Clock clock) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.idempotency = idempotency;
    this.inventory = inventory;
    this.gate = gate;
    this.mapper = mapper;
    this.clock = clock;
  }

  static String newId(String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
  }

  // ---------- 보류 ----------

  public String create(String requestId, String sourceSystem, String cdcId, HoldCmd c) {
    Operator op = Operator.forCenter(cdcId, sourceSystem);
    List<PmartException.Detail> bad = new ArrayList<>();
    for (int i = 0; i < c.lines().size(); i++) {
      Scope l = c.lines().get(i);
      if (l.qty() != null && l.locationCode() == null) {
        bad.add(new PmartException.Detail("lines[" + i + "].qty", "LOCATION_REQUIRED_WITH_QTY"));
      }
    }
    if (!bad.isEmpty()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "보류 줄이 올바르지 않습니다.", bad);
    }
    Map<String, Object> req = new LinkedHashMap<>();
    req.put("cdcId", cdcId);
    req.put("cmd", c);
    String id =
        idempotency
            .execute(
                op.key(requestId, OP_HOLD),
                req,
                Ref.class,
                () -> {
                  String holdId = newId("QH-");
                  Timestamp at = Timestamp.from(clock.instant());
                  jdbc.update(
                      "INSERT INTO quality_hold (hold_id, cdc_id, reason, source_ref, evidence_ref, note, state,"
                          + " requested_by, created_at, updated_at) VALUES (?,?,?,?,?,?,'APPLYING',?,?,?)",
                      holdId,
                      cdcId,
                      c.reason(),
                      c.sourceRef(),
                      c.evidenceRef(),
                      c.note(),
                      op.id(),
                      at,
                      at);
                  for (int i = 0; i < c.lines().size(); i++) {
                    Scope l = c.lines().get(i);
                    jdbc.update(
                        "INSERT INTO quality_hold_line (hold_id, line_no, sku, lot, location_code, requested_qty,"
                            + " state) VALUES (?,?,?,?,?,?,'PENDING')",
                        holdId,
                        i + 1,
                        l.sku(),
                        l.lot(),
                        l.locationCode(),
                        l.qty());
                  }
                  return new Ref(holdId);
                })
            .value()
            .id();
    processHold(id);
    return id;
  }

  /** 반영 안 된 줄마다 재고 HOLD(키 고정 — 다시 불러도 한 번). */
  public void processHold(String holdId) {
    Map<String, Object> h =
        jdbc.queryForMap(
            "SELECT cdc_id, reason, state FROM quality_hold WHERE hold_id = ?", holdId);
    List<Map<String, Object>> lines =
        jdbc.queryForList(
            "SELECT line_no, sku, lot, location_code, requested_qty FROM quality_hold_line"
                + " WHERE hold_id = ? AND state IN ('PENDING', 'UNKNOWN_RESULT') ORDER BY line_no",
            holdId);
    for (Map<String, Object> l : lines) {
      int lineNo = ((Number) l.get("line_no")).intValue();
      Map<String, Object> line = new LinkedHashMap<>();
      line.put("sku", l.get("sku"));
      line.put("lot", l.get("lot"));
      if (l.get("location_code") != null) {
        line.put("locationCode", l.get("location_code"));
      }
      if (l.get("requested_qty") != null) {
        line.put("qty", l.get("requested_qty"));
      }
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("cdcId", h.get("cdc_id"));
      body.put("action", "HOLD");
      body.put("sourceRef", holdId);
      body.put("reason", h.get("reason"));
      body.put("lines", List.of(line));
      String state;
      int held = 0;
      String commit = null;
      String reject = null;
      try {
        InventoryClient.Posted p =
            inventory.qualityPosting("cdcq-hold-" + holdId + "-" + lineNo, body);
        state = "APPLIED";
        held = p.appliedQty();
        commit = p.commitId();
      } catch (PmartException e) {
        if (InventoryClient.unknown(e)) {
          state = "UNKNOWN_RESULT";
        } else {
          state = "REJECTED";
          reject = InventoryClient.reason(e);
          log.warn("품질 보류 줄 거절 — {}#{}: {}", holdId, lineNo, reject);
        }
      }
      String s = state;
      int q = held;
      String c = commit;
      String r = reject;
      tx.executeWithoutResult(
          x ->
              jdbc.update(
                  "UPDATE quality_hold_line SET state = ?, held_qty = ?, commit_id = ?, reject_reason = ?"
                      + " WHERE hold_id = ? AND line_no = ? AND state IN ('PENDING', 'UNKNOWN_RESULT')",
                  s,
                  q,
                  c,
                  r,
                  holdId,
                  lineNo));
    }
    tx.executeWithoutResult(x -> refreshState(holdId));
  }

  /** 줄 상태·수량으로 보류 상태를 다시 정한다(트랜잭션 안). */
  void refreshState(String holdId) {
    List<Map<String, Object>> ls =
        jdbc.queryForList(
            "SELECT state, held_qty, released_qty, disposed_qty, reject_reason FROM quality_hold_line"
                + " WHERE hold_id = ? FOR UPDATE",
            holdId);
    boolean open = ls.stream().anyMatch(l -> "PENDING".equals(l.get("state")));
    boolean unknown = ls.stream().anyMatch(l -> "UNKNOWN_RESULT".equals(l.get("state")));
    boolean allRejected = ls.stream().allMatch(l -> "REJECTED".equals(l.get("state")));
    int remaining = 0;
    int done = 0;
    String reject = null;
    for (Map<String, Object> l : ls) {
      int held = ((Number) l.get("held_qty")).intValue();
      int rel = ((Number) l.get("released_qty")).intValue();
      int dis = ((Number) l.get("disposed_qty")).intValue();
      remaining += held - rel - dis;
      done += rel + dis;
      if (reject == null && l.get("reject_reason") != null) {
        reject = (String) l.get("reject_reason");
      }
    }
    String state =
        open
            ? "APPLYING"
            : unknown
                ? "UNKNOWN_RESULT"
                : allRejected
                    ? "REJECTED"
                    : remaining == 0 ? "CLOSED" : done > 0 ? "PARTIALLY_RELEASED" : "APPLIED";
    jdbc.update(
        "UPDATE quality_hold SET state = ?, reject_reason = ?, updated_at = ? WHERE hold_id = ?",
        state,
        reject,
        Timestamp.from(clock.instant()),
        holdId);
  }

  /** 그 보류에서 sku·lot 의 남은 수량 = 보류 − 해제 − 폐기 − 진행 중 해제 요청 − 진행 중 폐기(승인 − 실행). */
  int remaining(String holdId, String sku, String lot) {
    Integer held =
        jdbc.queryForObject(
            "SELECT COALESCE(SUM(held_qty - released_qty - disposed_qty), 0) FROM quality_hold_line"
                + " WHERE hold_id = ? AND sku = ? AND lot = ? AND state = 'APPLIED'",
            Integer.class,
            holdId,
            sku,
            lot);
    int left = held == null ? 0 : held;
    for (String raw :
        jdbc.queryForList(
            "SELECT lines FROM hold_release WHERE hold_id = ? AND state IN ('REQUESTED', 'APPLYING', 'UNKNOWN_RESULT')",
            String.class,
            holdId)) {
      for (Exact e : exacts(raw)) {
        if (e.sku().equals(sku) && e.lot().equals(lot)) {
          left -= e.qty();
        }
      }
    }
    for (String raw :
        jdbc.queryForList(
            "SELECT lines FROM disposal WHERE hold_id = ? AND state IN ('REQUESTED', 'APPROVED', 'PARTIALLY_EXECUTED')",
            String.class,
            holdId)) {
      for (JsonNode n : mapper.readTree(raw)) {
        if (n.path("sku").asString().equals(sku) && n.path("lot").asString().equals(lot)) {
          left -= n.path("approvedQty").asInt() - n.path("executedQty").asInt();
        }
      }
    }
    return left;
  }

  /** 해제·폐기 요청 줄 검사 — 보류 줄에 있는 sku·lot(칸을 지정한 줄이면 같은 칸), 남은 수량 이하. 트랜잭션 안·보류 잠금 뒤. */
  void checkAgainstHold(String holdId, List<Exact> lines) {
    Map<List<String>, Integer> want = new LinkedHashMap<>();
    for (int i = 0; i < lines.size(); i++) {
      Exact e = lines.get(i);
      Integer match =
          jdbc.queryForObject(
              "SELECT count(*) FROM quality_hold_line WHERE hold_id = ? AND sku = ? AND lot = ?"
                  + " AND state = 'APPLIED' AND (location_code IS NULL OR location_code = ?)",
              Integer.class,
              holdId,
              e.sku(),
              e.lot(),
              e.locationCode());
      if (match == null || match == 0) {
        throw new PmartException(
            "LINE_NOT_IN_HOLD",
            HttpStatus.UNPROCESSABLE_CONTENT,
            "lines["
                + i
                + "] "
                + e.sku()
                + "/"
                + e.lot()
                + " "
                + e.locationCode()
                + " 는 이 보류에 없습니다.",
            false,
            List.of(new PmartException.Detail("lines[" + i + "]", "LINE_NOT_IN_HOLD")));
      }
      want.merge(List.of(e.sku(), e.lot()), e.qty(), Integer::sum);
    }
    for (Map.Entry<List<String>, Integer> w : want.entrySet()) {
      int left = remaining(holdId, w.getKey().get(0), w.getKey().get(1));
      if (w.getValue() > left) {
        throw new PmartException(
            "QTY_EXCEEDS_HELD",
            HttpStatus.UNPROCESSABLE_CONTENT,
            w.getKey().get(0)
                + "/"
                + w.getKey().get(1)
                + " 남은 보류 "
                + left
                + " 보다 많습니다("
                + w.getValue()
                + ").",
            false,
            List.of(new PmartException.Detail("lines", "QTY_EXCEEDS_HELD")));
      }
    }
  }

  /** 보류를 잠그고 해제·폐기를 받을 수 있는 상태인지. */
  Map<String, Object> lockOpenHold(String cdcId, String holdId) {
    List<Map<String, Object>> h =
        jdbc.queryForList(
            "SELECT hold_id, state FROM quality_hold WHERE hold_id = ? AND cdc_id = ? FOR UPDATE",
            holdId,
            cdcId);
    if (h.isEmpty()) {
      throw PmartException.notFound("NOT_FOUND", "보류 " + holdId + " 가 없습니다.");
    }
    String state = (String) h.get(0).get("state");
    if (!"APPLIED".equals(state) && !"PARTIALLY_RELEASED".equals(state)) {
      throw PmartException.invalidState("보류 상태 " + state + " 에서는 해제·폐기를 요청할 수 없습니다.");
    }
    return h.get(0);
  }

  // ---------- 해제 ----------

  public String requestRelease(
      String requestId,
      String sourceSystem,
      String cdcId,
      String holdId,
      @Nullable String note,
      List<Exact> lines) {
    Operator op = Operator.forCenter(cdcId, sourceSystem);
    Map<String, Object> req = new LinkedHashMap<>();
    req.put("holdId", holdId);
    req.put("note", String.valueOf(note));
    req.put("lines", lines);
    return idempotency
        .execute(
            op.key(requestId, OP_RELEASE_REQ),
            req,
            Ref.class,
            () -> {
              lockOpenHold(cdcId, holdId);
              checkAgainstHold(holdId, lines);
              String id = newId("QR-");
              jdbc.update(
                  "INSERT INTO hold_release (release_id, hold_id, cdc_id, state, requested_by, note, lines,"
                      + " created_at) VALUES (?,?,?,'REQUESTED',?,?,?,?)",
                  id,
                  holdId,
                  cdcId,
                  op.id(),
                  note,
                  mapper.writeValueAsString(lines),
                  Timestamp.from(clock.instant()));
              return new Ref(id);
            })
        .value()
        .id();
  }

  public void decideRelease(
      String requestId,
      String sourceSystem,
      String cdcId,
      String holdId,
      String releaseId,
      String decision,
      @Nullable String reason,
      @Nullable String approvalRef) {
    Operator op = Operator.forCenter(cdcId, sourceSystem);
    op.requireManager();
    ReleaseView pre = release(cdcId, holdId, releaseId);
    if (op.id().equals(pre.requestedBy())) {
      throw selfApproval();
    }
    String ref = null;
    if ("APPROVE".equals(decision) && "REQUESTED".equals(pre.state())) {
      // 승인 관문은 트랜잭션 밖(pricing·approval-service — 같은 요청이면 같은 useRef)
      ref =
          gate.require(
              cdcId,
              "QUALITY_RELEASE",
              releaseId,
              op.id(),
              "CDC 품질 해제 " + releaseId,
              gateLines(pre.lines()),
              approvalRef);
    }
    String recorded = ref;
    Map<String, Object> req = new LinkedHashMap<>();
    req.put("releaseId", releaseId);
    req.put("decision", decision);
    req.put("reason", String.valueOf(reason));
    req.put("approvalRef", String.valueOf(approvalRef));
    idempotency.execute(
        op.key(requestId, OP_RELEASE_DECIDE),
        req,
        Ref.class,
        () -> {
          String state =
              jdbc.queryForObject(
                  "SELECT state FROM hold_release WHERE release_id = ? FOR UPDATE",
                  String.class,
                  releaseId);
          if (!"REQUESTED".equals(state)) {
            throw PmartException.invalidState("해제 요청 상태 " + state + " 에서는 결정할 수 없습니다.");
          }
          jdbc.update(
              "UPDATE hold_release SET state = ?, decided_by = ?, decision_reason = ?, approval_ref = ?,"
                  + " decided_at = ? WHERE release_id = ?",
              "APPROVE".equals(decision) ? "APPLYING" : "DECLINED",
              op.id(),
              reason,
              recorded,
              Timestamp.from(clock.instant()),
              releaseId);
          return new Ref(releaseId);
        });
    processRelease(releaseId);
  }

  /** 승인된 해제를 재고에 반영(키 고정). */
  public void processRelease(String releaseId) {
    Map<String, Object> r =
        jdbc.queryForMap(
            "SELECT hold_id, cdc_id, state, approval_ref, lines FROM hold_release WHERE release_id = ?",
            releaseId);
    if (!"APPLYING".equals(r.get("state")) && !"UNKNOWN_RESULT".equals(r.get("state"))) {
      return;
    }
    List<Exact> lines = exacts((String) r.get("lines"));
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("cdcId", r.get("cdc_id"));
    body.put("action", "RELEASE");
    body.put("sourceRef", releaseId);
    body.put(
        "approvalRef",
        r.get("approval_ref") == null ? "CENTER:" + releaseId : r.get("approval_ref"));
    body.put("lines", lines.stream().map(HoldService::line).toList());
    String state;
    String commit = null;
    String reject = null;
    try {
      commit = inventory.qualityPosting("cdcq-rel-" + releaseId, body).commitId();
      state = "APPLIED";
    } catch (PmartException e) {
      if (InventoryClient.unknown(e)) {
        state = "UNKNOWN_RESULT";
      } else {
        state = "REJECTED";
        reject = InventoryClient.reason(e);
        log.warn("품질 해제 거절 — {}: {}", releaseId, reject);
      }
    }
    String s = state;
    String c = commit;
    String rj = reject;
    String holdId = (String) r.get("hold_id");
    tx.executeWithoutResult(
        x -> {
          int n =
              jdbc.update(
                  "UPDATE hold_release SET state = ?, commit_id = ?, reject_reason = ? WHERE release_id = ?"
                      + " AND state IN ('APPLYING', 'UNKNOWN_RESULT')",
                  s,
                  c,
                  rj,
                  releaseId);
          if (n == 1 && "APPLIED".equals(s)) {
            for (Exact e : lines) {
              attribute(holdId, e, "released_qty");
            }
            refreshState(holdId);
          }
        });
  }

  /** 반영된 해제·폐기 수량을 보류 줄에 나눠 적는다(같은 sku·lot, 칸이 맞는 줄부터). */
  void attribute(String holdId, Exact e, String column) {
    int left = e.qty();
    List<Map<String, Object>> ls =
        jdbc.queryForList(
            "SELECT line_no, held_qty - released_qty - disposed_qty AS room FROM quality_hold_line"
                + " WHERE hold_id = ? AND sku = ? AND lot = ? AND state = 'APPLIED'"
                + " AND (location_code IS NULL OR location_code = ?)"
                + " ORDER BY (location_code IS NULL), line_no FOR UPDATE",
            holdId,
            e.sku(),
            e.lot(),
            e.locationCode());
    for (Map<String, Object> l : ls) {
      if (left <= 0) {
        break;
      }
      int take = Math.min(left, ((Number) l.get("room")).intValue());
      if (take > 0) {
        jdbc.update(
            "UPDATE quality_hold_line SET "
                + column
                + " = "
                + column
                + " + ? WHERE hold_id = ? AND line_no = ?",
            take,
            holdId,
            l.get("line_no"));
        left -= take;
      }
    }
    if (left > 0) {
      log.warn(
          "보류 {} 줄에 {} {}/{} {} 를 다 나누지 못함(남음 {})",
          holdId,
          column,
          e.sku(),
          e.lot(),
          e.qty(),
          left);
    }
  }

  /** 불명·중단된 반영을 같은 키로 다시. */
  @Scheduled(
      initialDelayString = "${pmart.quality.retry-interval:PT30S}",
      fixedDelayString = "${pmart.quality.retry-interval:PT30S}")
  public void retry() {
    for (String id :
        jdbc.queryForList(
            "SELECT hold_id FROM quality_hold WHERE state IN ('APPLYING', 'UNKNOWN_RESULT') ORDER BY hold_id LIMIT 50",
            String.class)) {
      try {
        processHold(id);
      } catch (RuntimeException e) {
        log.warn("품질 보류 다시 반영 실패 — {}: {}", id, e.toString());
      }
    }
    for (String id :
        jdbc.queryForList(
            "SELECT release_id FROM hold_release WHERE state IN ('APPLYING', 'UNKNOWN_RESULT') ORDER BY release_id LIMIT 50",
            String.class)) {
      try {
        processRelease(id);
      } catch (RuntimeException e) {
        log.warn("품질 해제 다시 반영 실패 — {}: {}", id, e.toString());
      }
    }
  }

  // ---------- 조회 ----------

  public HoldView get(String cdcId, String holdId) {
    Operator.requireCenter(cdcId);
    List<HoldView> v = holds(cdcId, holdId, null, null, 1);
    if (v.isEmpty()) {
      throw PmartException.notFound("NOT_FOUND", "보류 " + holdId + " 가 없습니다.");
    }
    return v.get(0);
  }

  public List<HoldView> list(
      String cdcId, @Nullable String state, @Nullable String cursor, int size) {
    Operator.requireCenter(cdcId);
    return holds(cdcId, null, state, cursor, size);
  }

  private List<HoldView> holds(
      String cdcId,
      @Nullable String holdId,
      @Nullable String state,
      @Nullable String cursor,
      int size) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT hold_id, cdc_id, reason, source_ref, evidence_ref, note, state, requested_by, reject_reason,"
                + " created_at, updated_at FROM quality_hold WHERE cdc_id = ?");
    List<Object> args = new ArrayList<>(List.of(cdcId));
    if (holdId != null) {
      sql.append(" AND hold_id = ?");
      args.add(holdId);
    }
    if (state != null) {
      sql.append(" AND state = ?");
      args.add(state);
    }
    if (cursor != null) {
      sql.append(
          " AND (created_at, hold_id) < (SELECT created_at, hold_id FROM quality_hold WHERE hold_id = ?)");
      args.add(cursor);
    }
    sql.append(" ORDER BY created_at DESC, hold_id DESC LIMIT ?");
    args.add(size);
    return jdbc.query(
        sql.toString(),
        (rs, i) -> {
          String id = rs.getString("hold_id");
          return new HoldView(
              id,
              rs.getString("cdc_id"),
              rs.getString("reason"),
              rs.getString("source_ref"),
              rs.getString("evidence_ref"),
              rs.getString("note"),
              rs.getString("state"),
              rs.getString("requested_by"),
              rs.getString("reject_reason"),
              holdLines(id),
              releases(id),
              rs.getTimestamp("created_at").toInstant(),
              rs.getTimestamp("updated_at").toInstant());
        },
        args.toArray());
  }

  private List<HoldLineView> holdLines(String holdId) {
    return jdbc.query(
        "SELECT line_no, sku, lot, location_code, requested_qty, held_qty, released_qty, disposed_qty,"
            + " commit_id, state FROM quality_hold_line WHERE hold_id = ? ORDER BY line_no",
        (rs, i) ->
            new HoldLineView(
                rs.getInt("line_no"),
                rs.getString("sku"),
                rs.getString("lot"),
                rs.getString("location_code"),
                (Integer) rs.getObject("requested_qty"),
                rs.getInt("held_qty"),
                rs.getInt("released_qty"),
                rs.getInt("disposed_qty"),
                rs.getString("commit_id"),
                rs.getString("state")),
        holdId);
  }

  private List<ReleaseView> releases(String holdId) {
    return jdbc.query(
        "SELECT * FROM hold_release WHERE hold_id = ? ORDER BY created_at, release_id",
        (rs, i) -> releaseView(rs),
        holdId);
  }

  public ReleaseView release(String cdcId, String holdId, String releaseId) {
    Operator.requireCenter(cdcId);
    List<ReleaseView> r =
        jdbc.query(
            "SELECT * FROM hold_release WHERE release_id = ? AND hold_id = ? AND cdc_id = ?",
            (rs, i) -> releaseView(rs),
            releaseId,
            holdId,
            cdcId);
    if (r.isEmpty()) {
      throw PmartException.notFound("NOT_FOUND", "해제 요청 " + releaseId + " 가 없습니다.");
    }
    return r.get(0);
  }

  private ReleaseView releaseView(java.sql.ResultSet rs) throws java.sql.SQLException {
    Timestamp d = rs.getTimestamp("decided_at");
    return new ReleaseView(
        rs.getString("release_id"),
        rs.getString("hold_id"),
        rs.getString("state"),
        rs.getString("requested_by"),
        rs.getString("note"),
        rs.getString("decided_by"),
        rs.getString("decision_reason"),
        rs.getString("approval_ref"),
        rs.getString("commit_id"),
        rs.getString("reject_reason"),
        exacts(rs.getString("lines")),
        rs.getTimestamp("created_at").toInstant(),
        d == null ? null : d.toInstant());
  }

  // ---------- 도우미 ----------

  List<Exact> exacts(String raw) {
    List<Exact> out = new ArrayList<>();
    for (JsonNode n : mapper.readTree(raw)) {
      out.add(
          new Exact(
              n.path("sku").asString(),
              n.path("lot").asString(),
              n.path("locationCode").asString(),
              n.path("qty").asInt()));
    }
    return out;
  }

  static Map<String, Object> line(Exact e) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("sku", e.sku());
    m.put("lot", e.lot());
    m.put("locationCode", e.locationCode());
    m.put("qty", e.qty());
    return m;
  }

  static List<HqApprovalRules.Line> gateLines(List<Exact> lines) {
    Map<String, Integer> bySku = new LinkedHashMap<>();
    lines.forEach(e -> bySku.merge(e.sku(), e.qty(), Integer::sum));
    List<HqApprovalRules.Line> out = new ArrayList<>();
    bySku.forEach((sku, q) -> out.add(new HqApprovalRules.Line(sku, q, q)));
    return out;
  }

  static PmartException selfApproval() {
    return new PmartException(
        "SELF_APPROVAL",
        HttpStatus.UNPROCESSABLE_CONTENT,
        "요청한 사람은 결정할 수 없습니다 — 다른 센터 관리자가 결정하세요.",
        false,
        List.of());
  }
}
