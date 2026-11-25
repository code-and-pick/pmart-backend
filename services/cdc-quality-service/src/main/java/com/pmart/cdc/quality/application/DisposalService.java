package com.pmart.cdc.quality.application;

import com.pmart.cdc.quality.application.HoldService.Exact;
import com.pmart.cdc.quality.application.HoldService.Ref;
import com.pmart.cdc.quality.infrastructure.InventoryClient;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.web.PmartException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * 폐기 요청·결정·실행(0.2.0, WMS CDC-F-028). 보류된 수량만 요청할 수 있고, 승인만으로는 장부를 바꾸지 않는다 — 실제 폐기한 수량·증빙으로 실행할 때
 * cdc-inventory DISPOSE(보류 −q). 승인 5·실행 3 이면 3 만 빠지고 2 는 보류 현물로 남는다.
 */
@Service
public class DisposalService {

  private static final Logger log = LoggerFactory.getLogger(DisposalService.class);
  static final String OP_REQ = "cdcQuality:disposalRequest";
  static final String OP_DECIDE = "cdcQuality:disposalDecision";
  static final String OP_EXEC = "cdcQuality:disposalExecution";

  public record DLine(
      String sku, String lot, String locationCode, int approvedQty, int executedQty) {}

  public record ExecutionView(
      String executionId,
      String state,
      String evidenceRef,
      String executedBy,
      @Nullable String commitId,
      @Nullable String rejectReason,
      List<Exact> lines,
      Instant createdAt) {}

  public record DisposalView(
      String disposalApprovalId,
      String cdcId,
      String holdId,
      String reason,
      @Nullable String note,
      String state,
      String requestedBy,
      @Nullable String decidedBy,
      @Nullable String decisionReason,
      @Nullable String approvalRef,
      List<DLine> lines,
      List<ExecutionView> executions,
      Instant createdAt,
      @Nullable Instant decidedAt) {}

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final RequestIdempotency idempotency;
  private final InventoryClient inventory;
  private final ApprovalGate gate;
  private final HoldService holds;
  private final ObjectMapper mapper;
  private final Clock clock;

  public DisposalService(
      JdbcTemplate jdbc,
      TransactionTemplate tx,
      RequestIdempotency idempotency,
      InventoryClient inventory,
      ApprovalGate gate,
      HoldService holds,
      ObjectMapper mapper,
      Clock clock) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.idempotency = idempotency;
    this.inventory = inventory;
    this.gate = gate;
    this.holds = holds;
    this.mapper = mapper;
    this.clock = clock;
  }

  public String request(
      String requestId,
      String sourceSystem,
      String cdcId,
      String holdId,
      String reason,
      @Nullable String note,
      List<Exact> lines) {
    Operator op = Operator.forCenter(cdcId, sourceSystem);
    Map<String, Object> req = new LinkedHashMap<>();
    req.put("holdId", holdId);
    req.put("reason", reason);
    req.put("note", String.valueOf(note));
    req.put("lines", lines);
    return idempotency
        .execute(
            op.key(requestId, OP_REQ),
            req,
            Ref.class,
            () -> {
              holds.lockOpenHold(cdcId, holdId);
              holds.checkAgainstHold(holdId, lines);
              String id = HoldService.newId("QD-");
              List<DLine> dl =
                  lines.stream()
                      .map(e -> new DLine(e.sku(), e.lot(), e.locationCode(), e.qty(), 0))
                      .toList();
              jdbc.update(
                  "INSERT INTO disposal (disposal_id, cdc_id, hold_id, reason, note, state, requested_by, lines,"
                      + " created_at) VALUES (?,?,?,?,?,'REQUESTED',?,?,?)",
                  id,
                  cdcId,
                  holdId,
                  reason,
                  note,
                  op.id(),
                  mapper.writeValueAsString(dl),
                  Timestamp.from(clock.instant()));
              return new Ref(id);
            })
        .value()
        .id();
  }

  public void decide(
      String requestId,
      String sourceSystem,
      String cdcId,
      String disposalId,
      String decision,
      @Nullable String reason,
      @Nullable String approvalRef) {
    Operator op = Operator.forCenter(cdcId, sourceSystem);
    op.requireManager();
    DisposalView pre = get(cdcId, disposalId);
    if (op.id().equals(pre.requestedBy())) {
      throw HoldService.selfApproval();
    }
    String ref = null;
    if ("APPROVE".equals(decision) && "REQUESTED".equals(pre.state())) {
      ref =
          gate.require(
              cdcId,
              "DISPOSAL",
              disposalId,
              op.id(),
              "CDC 폐기 " + disposalId,
              HoldService.gateLines(
                  pre.lines().stream()
                      .map(l -> new Exact(l.sku(), l.lot(), l.locationCode(), l.approvedQty()))
                      .toList()),
              approvalRef);
    }
    String recorded = ref;
    Map<String, Object> req = new LinkedHashMap<>();
    req.put("disposalId", disposalId);
    req.put("decision", decision);
    req.put("reason", String.valueOf(reason));
    req.put("approvalRef", String.valueOf(approvalRef));
    idempotency.execute(
        op.key(requestId, OP_DECIDE),
        req,
        Ref.class,
        () -> {
          String state =
              jdbc.queryForObject(
                  "SELECT state FROM disposal WHERE disposal_id = ? FOR UPDATE",
                  String.class,
                  disposalId);
          if (!"REQUESTED".equals(state)) {
            throw PmartException.invalidState("폐기 상태 " + state + " 에서는 결정할 수 없습니다.");
          }
          jdbc.update(
              "UPDATE disposal SET state = ?, decided_by = ?, decision_reason = ?, approval_ref = ?, decided_at = ?"
                  + " WHERE disposal_id = ?",
              "APPROVE".equals(decision) ? "APPROVED" : "DECLINED",
              op.id(),
              reason,
              recorded,
              Timestamp.from(clock.instant()),
              disposalId);
          return new Ref(disposalId);
        });
  }

  public void execute(
      String requestId,
      String sourceSystem,
      String cdcId,
      String disposalId,
      String evidenceRef,
      @Nullable String note,
      List<Exact> lines) {
    Operator op = Operator.forCenter(cdcId, sourceSystem);
    if (evidenceRef == null || evidenceRef.isBlank()) {
      throw new PmartException(
          "EVIDENCE_REQUIRED",
          HttpStatus.UNPROCESSABLE_CONTENT,
          "폐기 실행에는 실제 실행 증빙(evidenceRef)이 필요합니다.",
          false,
          List.of());
    }
    Map<String, Object> req = new LinkedHashMap<>();
    req.put("disposalId", disposalId);
    req.put("evidenceRef", evidenceRef);
    req.put("note", String.valueOf(note));
    req.put("lines", lines);
    String execId =
        idempotency
            .execute(
                op.key(requestId, OP_EXEC),
                req,
                Ref.class,
                () -> {
                  List<Map<String, Object>> d =
                      jdbc.queryForList(
                          "SELECT state, lines FROM disposal WHERE disposal_id = ? AND cdc_id = ? FOR UPDATE",
                          disposalId,
                          cdcId);
                  if (d.isEmpty()) {
                    throw PmartException.notFound("NOT_FOUND", "폐기 " + disposalId + " 가 없습니다.");
                  }
                  String state = (String) d.get(0).get("state");
                  if (!"APPROVED".equals(state) && !"PARTIALLY_EXECUTED".equals(state)) {
                    throw PmartException.invalidState("폐기 상태 " + state + " 에서는 실행할 수 없습니다.");
                  }
                  List<DLine> dl = dlines((String) d.get(0).get("lines"));
                  List<Exact> inFlight = new ArrayList<>();
                  for (String raw :
                      jdbc.queryForList(
                          "SELECT lines FROM disposal_execution WHERE disposal_id = ?"
                              + " AND state IN ('APPLYING', 'UNKNOWN_RESULT')",
                          String.class,
                          disposalId)) {
                    inFlight.addAll(holds.exacts(raw));
                  }
                  for (int i = 0; i < lines.size(); i++) {
                    Exact e = lines.get(i);
                    DLine m =
                        dl.stream()
                            .filter(
                                x ->
                                    x.sku().equals(e.sku())
                                        && x.lot().equals(e.lot())
                                        && x.locationCode().equals(e.locationCode()))
                            .findFirst()
                            .orElse(null);
                    int pending =
                        inFlight.stream()
                            .filter(
                                x ->
                                    x.sku().equals(e.sku())
                                        && x.lot().equals(e.lot())
                                        && x.locationCode().equals(e.locationCode()))
                            .mapToInt(Exact::qty)
                            .sum();
                    int left = m == null ? 0 : m.approvedQty() - m.executedQty() - pending;
                    if (e.qty() > left) {
                      throw new PmartException(
                          "QTY_EXCEEDS_APPROVED",
                          HttpStatus.UNPROCESSABLE_CONTENT,
                          "lines[" + i + "] 승인 잔량(" + left + ")보다 많이 폐기할 수 없습니다.",
                          false,
                          List.of(
                              new PmartException.Detail(
                                  "lines[" + i + "].qty", "QTY_EXCEEDS_APPROVED")));
                    }
                  }
                  String id = HoldService.newId("QX-");
                  jdbc.update(
                      "INSERT INTO disposal_execution (execution_id, disposal_id, state, evidence_ref, note,"
                          + " executed_by, lines, created_at) VALUES (?,?,'APPLYING',?,?,?,?,?)",
                      id,
                      disposalId,
                      evidenceRef,
                      note,
                      op.id(),
                      mapper.writeValueAsString(lines),
                      Timestamp.from(clock.instant()));
                  return new Ref(id);
                })
            .value()
            .id();
    processExecution(execId);
  }

  public void processExecution(String executionId) {
    Map<String, Object> x =
        jdbc.queryForMap(
            "SELECT e.state, e.evidence_ref, e.lines, d.disposal_id, d.cdc_id, d.hold_id, d.reason, d.approval_ref"
                + " FROM disposal_execution e JOIN disposal d ON d.disposal_id = e.disposal_id WHERE e.execution_id = ?",
            executionId);
    if (!"APPLYING".equals(x.get("state")) && !"UNKNOWN_RESULT".equals(x.get("state"))) {
      return;
    }
    String disposalId = (String) x.get("disposal_id");
    List<Exact> lines = holds.exacts((String) x.get("lines"));
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("cdcId", x.get("cdc_id"));
    body.put("action", "DISPOSE");
    body.put("sourceRef", disposalId);
    body.put(
        "approvalRef",
        x.get("approval_ref") == null ? "CENTER:" + disposalId : x.get("approval_ref"));
    body.put("evidenceRef", x.get("evidence_ref"));
    body.put("reason", x.get("reason"));
    body.put("lines", lines.stream().map(HoldService::line).toList());
    String state;
    String commit = null;
    String reject = null;
    try {
      commit = inventory.qualityPosting("cdcq-dex-" + executionId, body).commitId();
      state = "APPLIED";
    } catch (PmartException e) {
      if (InventoryClient.unknown(e)) {
        state = "UNKNOWN_RESULT";
      } else {
        state = "REJECTED";
        reject = InventoryClient.reason(e);
        log.warn("폐기 실행 거절 — {}: {}", executionId, reject);
      }
    }
    String s = state;
    String c = commit;
    String r = reject;
    String holdId = (String) x.get("hold_id");
    tx.executeWithoutResult(
        t -> {
          int n =
              jdbc.update(
                  "UPDATE disposal_execution SET state = ?, commit_id = ?, reject_reason = ? WHERE execution_id = ?"
                      + " AND state IN ('APPLYING', 'UNKNOWN_RESULT')",
                  s,
                  c,
                  r,
                  executionId);
          if (n != 1 || !"APPLIED".equals(s)) {
            return;
          }
          String raw =
              jdbc.queryForObject(
                  "SELECT lines FROM disposal WHERE disposal_id = ? FOR UPDATE",
                  String.class,
                  disposalId);
          List<DLine> dl = new ArrayList<>(dlines(raw));
          for (Exact e : lines) {
            for (int i = 0; i < dl.size(); i++) {
              DLine l = dl.get(i);
              if (l.sku().equals(e.sku())
                  && l.lot().equals(e.lot())
                  && l.locationCode().equals(e.locationCode())) {
                dl.set(
                    i,
                    new DLine(
                        l.sku(),
                        l.lot(),
                        l.locationCode(),
                        l.approvedQty(),
                        l.executedQty() + e.qty()));
                break;
              }
            }
            holds.attribute(holdId, e, "disposed_qty");
          }
          boolean all = dl.stream().allMatch(l -> l.executedQty() >= l.approvedQty());
          jdbc.update(
              "UPDATE disposal SET lines = ?, state = ? WHERE disposal_id = ?",
              mapper.writeValueAsString(dl),
              all ? "COMPLETED" : "PARTIALLY_EXECUTED",
              disposalId);
          holds.refreshState(holdId);
        });
  }

  @Scheduled(
      initialDelayString = "${pmart.quality.retry-interval:PT30S}",
      fixedDelayString = "${pmart.quality.retry-interval:PT30S}")
  public void retry() {
    for (String id :
        jdbc.queryForList(
            "SELECT execution_id FROM disposal_execution WHERE state IN ('APPLYING', 'UNKNOWN_RESULT')"
                + " ORDER BY execution_id LIMIT 50",
            String.class)) {
      try {
        processExecution(id);
      } catch (RuntimeException e) {
        log.warn("폐기 실행 다시 반영 실패 — {}: {}", id, e.toString());
      }
    }
  }

  // ---------- 조회 ----------

  public DisposalView get(String cdcId, String disposalId) {
    Operator.requireCenter(cdcId);
    List<DisposalView> v = disposals(cdcId, disposalId, null, null, 1);
    if (v.isEmpty()) {
      throw PmartException.notFound("NOT_FOUND", "폐기 " + disposalId + " 가 없습니다.");
    }
    return v.get(0);
  }

  public List<DisposalView> list(
      String cdcId, @Nullable String state, @Nullable String cursor, int size) {
    Operator.requireCenter(cdcId);
    return disposals(cdcId, null, state, cursor, size);
  }

  private List<DisposalView> disposals(
      String cdcId,
      @Nullable String id,
      @Nullable String state,
      @Nullable String cursor,
      int size) {
    StringBuilder sql = new StringBuilder("SELECT * FROM disposal WHERE cdc_id = ?");
    List<Object> args = new ArrayList<>(List.of(cdcId));
    if (id != null) {
      sql.append(" AND disposal_id = ?");
      args.add(id);
    }
    if (state != null) {
      sql.append(" AND state = ?");
      args.add(state);
    }
    if (cursor != null) {
      sql.append(
          " AND (created_at, disposal_id) < (SELECT created_at, disposal_id FROM disposal WHERE disposal_id = ?)");
      args.add(cursor);
    }
    sql.append(" ORDER BY created_at DESC, disposal_id DESC LIMIT ?");
    args.add(size);
    return jdbc.query(
        sql.toString(),
        (rs, i) -> {
          String did = rs.getString("disposal_id");
          Timestamp dec = rs.getTimestamp("decided_at");
          return new DisposalView(
              did,
              rs.getString("cdc_id"),
              rs.getString("hold_id"),
              rs.getString("reason"),
              rs.getString("note"),
              rs.getString("state"),
              rs.getString("requested_by"),
              rs.getString("decided_by"),
              rs.getString("decision_reason"),
              rs.getString("approval_ref"),
              dlines(rs.getString("lines")),
              executions(did),
              rs.getTimestamp("created_at").toInstant(),
              dec == null ? null : dec.toInstant());
        },
        args.toArray());
  }

  private List<ExecutionView> executions(String disposalId) {
    return jdbc.query(
        "SELECT * FROM disposal_execution WHERE disposal_id = ? ORDER BY created_at, execution_id",
        (rs, i) ->
            new ExecutionView(
                rs.getString("execution_id"),
                rs.getString("state"),
                rs.getString("evidence_ref"),
                rs.getString("executed_by"),
                rs.getString("commit_id"),
                rs.getString("reject_reason"),
                holds.exacts(rs.getString("lines")),
                rs.getTimestamp("created_at").toInstant()),
        disposalId);
  }

  private List<DLine> dlines(String raw) {
    List<DLine> out = new ArrayList<>();
    for (JsonNode n : mapper.readTree(raw)) {
      out.add(
          new DLine(
              n.path("sku").asString(),
              n.path("lot").asString(),
              n.path("locationCode").asString(),
              n.path("approvedQty").asInt(),
              n.path("executedQty").asInt()));
    }
    return out;
  }
}
