package com.pmart.cdc.quality.application;

import com.pmart.cdc.quality.infrastructure.InventoryClient;
import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * 리콜(0.3.0, WMS CDC-F-028·정책 16.13 — 사용자 2026-11-26 "본사 승인 번호 + 모든 CDC"·"리콜은 출고대기분도 막음"). 발령 = 본사
 * 승인 번호 필수 + {@code RecallIssued}(같은 트랜잭션) → (밖) cdc-inventory LOT 추적으로 현물이 있는 CDC 마다 보류(reason
 * RECALL — 재고 HOLD recall). 추적이 안 되면 TRACING 에 두고 주기 재시도. 리콜 발령만으로 재고·주문을 지우지 않는다(정책 16.13 주의).
 */
@Service
public class RecallService {

  private static final Logger log = LoggerFactory.getLogger(RecallService.class);
  static final String OP_ISSUE = "cdcQuality:recall";
  static final String TOPIC = "cdc.quality.v1";

  public record IssueCmd(
      String sku,
      List<String> lots,
      String reason,
      @Nullable String severity,
      @Nullable String approvalRef,
      @Nullable String externalRef,
      @Nullable String note) {}

  public record CenterView(
      String cdcId, String lot, String holdId, String holdState, int heldQty) {}

  public record DepartureView(
      String cdcId,
      String lot,
      String trfId,
      String departureId,
      @Nullable String mfcId,
      @Nullable Instant departedAt,
      int q,
      int received,
      int unreconciled) {}

  public record RecallView(
      String recallId,
      String sku,
      List<String> lots,
      String reason,
      @Nullable String severity,
      String approvalRef,
      @Nullable String externalRef,
      @Nullable String note,
      String state,
      String issuedBy,
      List<CenterView> centers,
      List<DepartureView> departures,
      boolean traceCurrent,
      Instant createdAt) {}

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final RequestIdempotency idempotency;
  private final InventoryClient inventory;
  private final HoldService holds;
  private final EventPublisher events;
  private final EventEnvelopeCodec codec;
  private final Clock clock;

  public RecallService(
      JdbcTemplate jdbc,
      TransactionTemplate tx,
      RequestIdempotency idempotency,
      InventoryClient inventory,
      HoldService holds,
      EventPublisher events,
      EventEnvelopeCodec codec,
      Clock clock) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.idempotency = idempotency;
    this.inventory = inventory;
    this.holds = holds;
    this.events = events;
    this.codec = codec;
    this.clock = clock;
  }

  public String issue(String requestId, String sourceSystem, IssueCmd c) {
    Operator op = Operator.any(sourceSystem);
    if (c.approvalRef() == null || c.approvalRef().isBlank()) {
      throw new PmartException(
          ErrorCode.APPROVAL_REQUIRED, "리콜 발령에는 본사 승인 번호(approvalRef)가 필요합니다.");
    }
    List<String> lots = new ArrayList<>(new LinkedHashSet<>(c.lots()));
    Map<String, Object> req = new LinkedHashMap<>();
    req.put("cmd", c);
    String id =
        idempotency
            .execute(
                op.key(requestId, OP_ISSUE),
                req,
                HoldService.Ref.class,
                () -> {
                  String recallId = HoldService.newId("RC-");
                  Instant now = clock.instant();
                  Timestamp at = Timestamp.from(now);
                  jdbc.update(
                      "INSERT INTO recall (recall_id, sku, reason, severity, approval_ref, external_ref, note, state,"
                          + " issued_by, created_at, updated_at) VALUES (?,?,?,?,?,?,?,'TRACING',?,?,?)",
                      recallId,
                      c.sku(),
                      c.reason(),
                      c.severity(),
                      c.approvalRef(),
                      c.externalRef(),
                      c.note(),
                      op.id(),
                      at,
                      at);
                  for (String lot : lots) {
                    jdbc.update(
                        "INSERT INTO recall_lot (recall_id, lot) VALUES (?,?)", recallId, lot);
                  }
                  Map<String, Object> p = new LinkedHashMap<>();
                  p.put("recallId", recallId);
                  p.put("sku", c.sku());
                  p.put("lots", lots);
                  p.put("scope", "ALL_CDC");
                  p.put("reason", c.reason());
                  p.put("issuerRef", op.id());
                  p.put("issuedAt", now.toString());
                  events.publish(
                      codec.create(
                          events.sourceSystem(),
                          "RecallIssued",
                          1,
                          "Recall",
                          recallId,
                          1,
                          new EventEnvelope.Actor(EventEnvelope.ActorType.OPERATOR, op.id()),
                          EventEnvelope.EventSource.REAL,
                          p),
                      TOPIC);
                  return new HoldService.Ref(recallId);
                })
            .value()
            .id();
    trace(id);
    return id;
  }

  /** LOT 마다 현물이 있는 CDC 를 찾아 센터·LOT 마다 보류(한 번). 다 되면 ISSUED. */
  public void trace(String recallId) {
    Map<String, Object> r =
        jdbc.queryForMap("SELECT sku, state, issued_by FROM recall WHERE recall_id = ?", recallId);
    if (!"TRACING".equals(r.get("state"))) {
      return;
    }
    String sku = (String) r.get("sku");
    for (String lot :
        jdbc.queryForList(
            "SELECT lot FROM recall_lot WHERE recall_id = ? ORDER BY lot",
            String.class,
            recallId)) {
      JsonNode t;
      try {
        t = inventory.lotTrace(sku, lot);
      } catch (PmartException e) {
        log.warn("리콜 {} LOT {} 추적 안 됨 — 다음에 다시: {}", recallId, lot, e.getMessage());
        return;
      }
      Set<String> centers = new LinkedHashSet<>();
      for (JsonNode s : t.path("stock")) {
        if ("RELEASED".equals(s.path("qualityState").asString())) {
          centers.add(s.path("cdcId").asString());
        }
      }
      for (String cdc : centers) {
        String holdId =
            tx.execute(
                x -> {
                  List<String> had =
                      jdbc.queryForList(
                          "SELECT hold_id FROM recall_center WHERE recall_id = ? AND cdc_id = ? AND lot = ?",
                          String.class,
                          recallId,
                          cdc,
                          lot);
                  if (!had.isEmpty()) {
                    return had.get(0);
                  }
                  String h =
                      holds.insertSystemHold(
                          cdc, "RECALL:" + recallId, recallId, "리콜 " + recallId, sku, lot);
                  jdbc.update(
                      "INSERT INTO recall_center (recall_id, cdc_id, lot, hold_id, created_at) VALUES (?,?,?,?,?)",
                      recallId,
                      cdc,
                      lot,
                      h,
                      Timestamp.from(clock.instant()));
                  return h;
                });
        holds.processHold(holdId);
      }
    }
    tx.executeWithoutResult(
        x ->
            jdbc.update(
                "UPDATE recall SET state = 'ISSUED', updated_at = ? WHERE recall_id = ? AND state = 'TRACING'",
                Timestamp.from(clock.instant()),
                recallId));
  }

  @Scheduled(
      initialDelayString = "${pmart.quality.retry-interval:PT30S}",
      fixedDelayString = "${pmart.quality.retry-interval:PT30S}")
  public void retry() {
    for (String id :
        jdbc.queryForList(
            "SELECT recall_id FROM recall WHERE state = 'TRACING' ORDER BY recall_id LIMIT 20",
            String.class)) {
      try {
        trace(id);
      } catch (RuntimeException e) {
        log.warn("리콜 다시 추적 실패 — {}: {}", id, e.toString());
      }
    }
  }

  // ---------- 조회 ----------

  public RecallView get(String sourceSystem, String recallId) {
    Operator.any(sourceSystem);
    List<RecallView> v = recalls(recallId, null, null, 1, true);
    if (v.isEmpty()) {
      throw PmartException.notFound("NOT_FOUND", "리콜 " + recallId + " 가 없습니다.");
    }
    return v.get(0);
  }

  public List<RecallView> list(
      String sourceSystem, @Nullable String sku, @Nullable String cursor, int size) {
    Operator.any(sourceSystem);
    return recalls(null, sku, cursor, size, false);
  }

  private List<RecallView> recalls(
      @Nullable String id,
      @Nullable String sku,
      @Nullable String cursor,
      int size,
      boolean withTrace) {
    StringBuilder sql = new StringBuilder("SELECT * FROM recall WHERE 1 = 1");
    List<Object> args = new ArrayList<>();
    if (id != null) {
      sql.append(" AND recall_id = ?");
      args.add(id);
    }
    if (sku != null) {
      sql.append(" AND sku = ?");
      args.add(sku);
    }
    if (cursor != null) {
      sql.append(
          " AND (created_at, recall_id) < (SELECT created_at, recall_id FROM recall WHERE recall_id = ?)");
      args.add(cursor);
    }
    sql.append(" ORDER BY created_at DESC, recall_id DESC LIMIT ?");
    args.add(size);
    List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
    List<RecallView> out = new ArrayList<>();
    for (Map<String, Object> r : rows) {
      String rid = (String) r.get("recall_id");
      List<String> lots =
          jdbc.queryForList(
              "SELECT lot FROM recall_lot WHERE recall_id = ? ORDER BY lot", String.class, rid);
      List<CenterView> centers =
          jdbc.query(
              "SELECT c.cdc_id, c.lot, c.hold_id, h.state,"
                  + " (SELECT COALESCE(SUM(l.held_qty), 0) FROM quality_hold_line l WHERE l.hold_id = c.hold_id)"
                  + " FROM recall_center c JOIN quality_hold h ON h.hold_id = c.hold_id WHERE c.recall_id = ?"
                  + " ORDER BY c.cdc_id, c.lot",
              (rs, i) ->
                  new CenterView(
                      rs.getString(1),
                      rs.getString(2),
                      rs.getString(3),
                      rs.getString(4),
                      rs.getInt(5)),
              rid);
      List<DepartureView> deps = new ArrayList<>();
      boolean current = withTrace;
      if (withTrace) {
        for (String lot : lots) {
          try {
            for (JsonNode d : inventory.lotTrace((String) r.get("sku"), lot).path("transits")) {
              String at = d.path("departedAt").asString(null);
              deps.add(
                  new DepartureView(
                      d.path("cdcId").asString(),
                      lot,
                      d.path("trfId").asString(),
                      d.path("departureId").asString(),
                      d.path("mfcId").asString(null),
                      at == null ? null : Instant.parse(at),
                      d.path("q").asInt(),
                      d.path("received").asInt(),
                      d.path("unreconciled").asInt()));
            }
          } catch (PmartException e) {
            current = false;
            deps.clear();
            break;
          }
        }
      }
      out.add(
          new RecallView(
              rid,
              (String) r.get("sku"),
              lots,
              (String) r.get("reason"),
              (String) r.get("severity"),
              (String) r.get("approval_ref"),
              (String) r.get("external_ref"),
              (String) r.get("note"),
              (String) r.get("state"),
              (String) r.get("issued_by"),
              centers,
              deps,
              current,
              ((Timestamp) r.get("created_at")).toInstant()));
    }
    return out;
  }
}
