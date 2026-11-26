package com.pmart.cdc.inbound.application;

import com.pmart.cdc.inbound.infrastructure.Clients;
import com.pmart.cdc.inbound.infrastructure.Clients.Loc;
import com.pmart.cdc.inbound.infrastructure.Clients.Place;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 적치·보충 작업(0.2.0, WMS CDC-F-010~012, 사가 S7). 작업 하나 = 출발 칸 → 목적 칸 이동 한 줄. 만들 때 공간 예약(cdc-location) →
 * DISPATCHED → 가상 설비 claim → 결과(증가분) → 재고 이동(cdc-inventory) → 예약 소비. 다른 서비스 호출은 트랜잭션 밖, 키 고정, 불명이면
 * 주기 재시도. 실패·취소는 남은 예약만 해제(이미 옮긴 것은 되돌리지 않는다).
 */
@Service
public class TaskService {

  private static final Logger log = LoggerFactory.getLogger(TaskService.class);
  static final String OP_CANCEL = "cdcInbound:putawayCancel";
  static final String OP_CLAIM = "cdcInbound:claim";
  static final Set<String> OPEN =
      Set.of("CREATED", "RESERVED", "DISPATCHED", "IN_PROGRESS", "PARTIALLY_COMPLETED");
  static final Set<String> DEST_TYPES = Set.of("PICK_FACE", "RESERVE");

  public record NewTask(
      String kind,
      String sku,
      String lot,
      String qualityState,
      String from,
      String to,
      int qty,
      @Nullable String reason) {}

  public record Ref(@Nullable String id) {}

  public record ResultView(
      String eventId,
      String status,
      int completedQty,
      String state,
      @Nullable String commitId,
      @Nullable String rejectReason,
      Instant receivedAt) {}

  public record View(
      String taskId,
      String cdcId,
      String kind,
      String state,
      String sku,
      String lot,
      String qualityState,
      String from,
      String to,
      int qty,
      int completedQty,
      @Nullable String reservationId,
      @Nullable String deviceId,
      String createdBy,
      @Nullable String reason,
      @Nullable String failureReason,
      List<ResultView> results,
      Instant createdAt,
      Instant updatedAt) {}

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final RequestIdempotency idempotency;
  private final Clients clients;
  private final Clock clock;

  public TaskService(
      JdbcTemplate jdbc,
      TransactionTemplate tx,
      RequestIdempotency idempotency,
      Clients clients,
      Clock clock) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.idempotency = idempotency;
    this.clients = clients;
    this.clock = clock;
  }

  // ---------- 만들기 ----------

  /** 운영자 적치 지시. */
  public String createPutaway(String requestId, String sourceSystem, String cdcId, NewTask t) {
    Operator op = Operator.forCenter(cdcId, sourceSystem);
    if (t.from().equals(t.to())) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "출발 칸과 목적 칸이 같습니다.");
    }
    // 요청 키 한 번 — 이미 만든 작업이면 검사 없이 그것(같은 본문), 다른 본문이면 409
    String key = sourceSystem + "|" + requestId + "|" + op.id();
    String hash = sha(cdcId + "|" + t);
    List<Map<String, Object>> prior =
        jdbc.queryForList(
            "SELECT task_id, request_hash FROM putaway_task WHERE request_key = ?", key);
    if (!prior.isEmpty()) {
      if (!hash.equals(prior.get(0).get("request_hash"))) {
        throw conflict("IDEMPOTENCY_CONFLICT", "같은 요청 키에 다른 본문입니다.");
      }
      String id = (String) prior.get(0).get("task_id");
      dispatch(id, false);
      return id;
    }
    check(cdcId, t); // 트랜잭션 밖
    String id;
    try {
      id = tx.execute(x -> insert(cdcId, t, op.id(), key, hash));
    } catch (org.springframework.dao.DuplicateKeyException race) {
      return createPutaway(requestId, sourceSystem, cdcId, t); // 같은 키 동시 요청 — 먼저 만든 것
    }
    dispatch(id, true);
    return id;
  }

  /** 자동 보충(시스템) — 검사·예약 뒤 작업 ID, 거절이면 예외. */
  String createReplenishment(String cdcId, NewTask t) {
    check(cdcId, t);
    String id = tx.execute(x -> insert(cdcId, t, "AUTO-REPLENISHMENT", null, null));
    dispatch(id, true);
    return id;
  }

  /**
   * 검사(밖에서 읽음): 출발 칸의 예약 안 된 현물(진행 중 작업 몫 뺌) ≥ qty, 목적 칸 ACTIVE·PICK_FACE/RESERVE·온도대 = 상품 보관 온도.
   */
  private void check(String cdcId, NewTask t) {
    List<Place> places = clients.places(cdcId, t.sku());
    int free =
        places.stream()
                .filter(
                    p ->
                        p.locationCode().equals(t.from())
                            && p.lot().equals(t.lot())
                            && p.qualityState().equals(t.qualityState()))
                .mapToInt(p -> p.onHand() - p.reserved())
                .sum()
            - pendingFrom(t.from(), t.sku(), t.lot(), t.qualityState());
    if (free < t.qty()) {
      throw conflict(
          "SOURCE_SHORT",
          t.from()
              + " 의 "
              + t.sku()
              + "/"
              + t.lot()
              + " 옮길 수 있는 현물("
              + Math.max(0, free)
              + ")이 "
              + t.qty()
              + " 보다 적습니다.");
    }
    Map<String, Loc> locs = clients.lookup(cdcId, List.of(t.from(), t.to()));
    Loc to = locs.get(t.to());
    if (to == null || !"ACTIVE".equals(to.state()) || !DEST_TYPES.contains(to.locationType())) {
      throw conflict(
          "DESTINATION_INVALID",
          "목적 칸 "
              + t.to()
              + " 은 ACTIVE 피킹 선반·보관 랙이어야 합니다"
              + (to == null ? "(없음)." : "(" + to.locationType() + "·" + to.state() + ")."));
    }
    String zone = clients.sku(t.sku()).storageType();
    if (zone != null && !zone.equals(to.temperatureZone())) {
      throw conflict(
          "DESTINATION_INVALID",
          "목적 칸 온도대 " + to.temperatureZone() + " 가 상품 보관 온도 " + zone + " 와 다릅니다.");
    }
    if (!locs.containsKey(t.from())) {
      throw conflict("SOURCE_SHORT", "출발 칸 " + t.from() + " 이 없습니다.");
    }
  }

  private String insert(
      String cdcId,
      NewTask t,
      String actor,
      @Nullable String requestKey,
      @Nullable String requestHash) {
    String id =
        "PT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
    Timestamp at = Timestamp.from(clock.instant());
    jdbc.update(
        "INSERT INTO putaway_task (task_id, cdc_id, kind, state, sku, lot, quality_state, from_location, to_location,"
            + " qty, created_by, reason, request_key, request_hash, created_at, updated_at)"
            + " VALUES (?,?,?,'CREATED',?,?,?,?,?,?,?,?,?,?,?,?)",
        id,
        cdcId,
        t.kind(),
        t.sku(),
        t.lot(),
        t.qualityState(),
        t.from(),
        t.to(),
        t.qty(),
        actor,
        t.reason(),
        requestKey,
        requestHash,
        at,
        at);
    return id;
  }

  /**
   * CREATED → 공간 예약(키 = 작업) → DISPATCHED. 칸이 넘치면 FAILED(사유) — throwOnReject 면 그 거절을 다시 던진다. 불명이면
   * CREATED 로 둔다.
   */
  void dispatch(String taskId, boolean throwOnReject) {
    Map<String, Object> t = row(taskId);
    if (!"CREATED".equals(t.get("state"))) {
      return;
    }
    String cdcId = (String) t.get("cdc_id");
    String to = (String) t.get("to_location");
    int occupied =
        clients.places(cdcId, null).stream()
            .filter(p -> p.locationCode().equals(to))
            .mapToInt(Place::onHand)
            .sum();
    String rid;
    try {
      rid =
          clients.reserve(
              cdcId,
              taskId,
              to,
              (String) t.get("sku"),
              (String) t.get("lot"),
              ((Number) t.get("qty")).intValue(),
              occupied);
    } catch (PmartException e) {
      if (Clients.unknown(e)) {
        log.warn("공간 예약 불명 — {} 다음에 다시: {}", taskId, e.getMessage());
        return;
      }
      tx.executeWithoutResult(
          x ->
              jdbc.update(
                  "UPDATE putaway_task SET state = 'FAILED', failure_reason = ?, updated_at = ? WHERE task_id = ?",
                  Clients.reason(e),
                  Timestamp.from(clock.instant()),
                  taskId));
      if (throwOnReject) {
        throw new PmartException(e.code(), e.status(), e.getMessage(), false, List.of());
      }
      return;
    }
    tx.executeWithoutResult(
        x ->
            jdbc.update(
                "UPDATE putaway_task SET state = 'DISPATCHED', reservation_id = ?, updated_at = ? WHERE task_id = ?"
                    + " AND state = 'CREATED'",
                rid,
                Timestamp.from(clock.instant()),
                taskId));
  }

  int pendingFrom(String from, String sku, String lot, String quality) {
    Integer n =
        jdbc.queryForObject(
            "SELECT COALESCE(SUM(qty - completed_qty), 0) FROM putaway_task WHERE from_location = ? AND sku = ?"
                + " AND lot = ? AND quality_state = ? AND state IN ('CREATED','RESERVED','DISPATCHED','IN_PROGRESS','PARTIALLY_COMPLETED')",
            Integer.class,
            from,
            sku,
            lot,
            quality);
    return n == null ? 0 : n;
  }

  int openTo(String to, @Nullable String sku) {
    Integer n =
        jdbc.queryForObject(
            "SELECT COALESCE(SUM(qty - completed_qty), 0) FROM putaway_task WHERE to_location = ?"
                + (sku == null ? "" : " AND sku = ?")
                + " AND state IN ('CREATED','RESERVED','DISPATCHED','IN_PROGRESS','PARTIALLY_COMPLETED')",
            Integer.class,
            sku == null ? new Object[] {to} : new Object[] {to, sku});
    return n == null ? 0 : n;
  }

  // ---------- 다음 작업 받기 ----------

  public @Nullable String claim(
      String requestId, String sourceSystem, String cdcId, String deviceId, List<String> kinds) {
    Map<String, Object> req = new LinkedHashMap<>();
    req.put("cdcId", cdcId);
    req.put("deviceId", deviceId);
    req.put("kinds", kinds);
    return idempotency
        .execute(
            new RequestKey(sourceSystem, requestId, OP_CLAIM, deviceId),
            req,
            Ref.class,
            () -> {
              List<String> held =
                  jdbc.queryForList(
                      "SELECT task_id FROM putaway_task WHERE cdc_id = ? AND device_id = ?"
                          + " AND state IN ('IN_PROGRESS','PARTIALLY_COMPLETED') ORDER BY created_at LIMIT 1",
                      String.class,
                      cdcId,
                      deviceId);
              if (!held.isEmpty()) {
                return new Ref(held.get(0));
              }
              List<Object> args = new ArrayList<>(List.of(cdcId));
              String kindCond = "";
              if (!kinds.isEmpty()) {
                kindCond =
                    " AND kind IN ("
                        + String.join(",", kinds.stream().map(k -> "?").toList())
                        + ")";
                args.addAll(kinds);
              }
              List<String> next =
                  jdbc.queryForList(
                      "SELECT task_id FROM putaway_task WHERE cdc_id = ? AND state = 'DISPATCHED'"
                          + kindCond
                          + " ORDER BY created_at, task_id LIMIT 1 FOR UPDATE SKIP LOCKED",
                      String.class,
                      args.toArray());
              if (next.isEmpty()) {
                return new Ref(null);
              }
              jdbc.update(
                  "UPDATE putaway_task SET state = 'IN_PROGRESS', device_id = ?, updated_at = ? WHERE task_id = ?",
                  deviceId,
                  Timestamp.from(clock.instant()),
                  next.get(0));
              return new Ref(next.get(0));
            })
        .value()
        .id();
  }

  // ---------- 결과 ----------

  public View result(
      String eventId,
      String taskId,
      String deviceId,
      String status,
      int completedQty,
      @Nullable String failureReason) {
    String hash =
        sha(taskId + "|" + deviceId + "|" + status + "|" + completedQty + "|" + failureReason);
    Boolean fresh =
        tx.execute(
            x -> {
              List<Map<String, Object>> prior =
                  jdbc.queryForList(
                      "SELECT body_hash FROM work_result WHERE event_id = ?", eventId);
              if (!prior.isEmpty()) {
                if (!hash.equals(prior.get(0).get("body_hash"))) {
                  throw conflict(
                      "WORK_RESULT_CONFLICT", "eventId " + eventId + " 는 다른 내용으로 이미 받았습니다.");
                }
                return false;
              }
              List<Map<String, Object>> tr =
                  jdbc.queryForList(
                      "SELECT state, device_id, qty, completed_qty FROM putaway_task WHERE task_id = ? FOR UPDATE",
                      taskId);
              if (tr.isEmpty()) {
                throw PmartException.notFound("NOT_FOUND", "작업 " + taskId + " 가 없습니다.");
              }
              Map<String, Object> t = tr.get(0);
              String state = (String) t.get("state");
              if (!deviceId.equals(t.get("device_id"))
                  || (!"IN_PROGRESS".equals(state) && !"PARTIALLY_COMPLETED".equals(state))) {
                throw conflict("NOT_CLAIMED", "이 설비가 받은 진행 중 작업이 아닙니다(상태 " + state + ").");
              }
              Integer inFlight =
                  jdbc.queryForObject(
                      "SELECT count(*) FROM work_result WHERE task_id = ? AND state IN ('PENDING','MOVED')",
                      Integer.class,
                      taskId);
              if (inFlight != null && inFlight > 0) {
                throw conflict("RESULT_IN_FLIGHT", "앞 결과가 아직 반영 중입니다 — 끝난 뒤 보내 주세요.");
              }
              int left =
                  ((Number) t.get("qty")).intValue() - ((Number) t.get("completed_qty")).intValue();
              boolean ok =
                  switch (status) {
                    case "SUCCESS" -> completedQty == left;
                    case "PARTIAL" -> completedQty >= 1 && completedQty <= left;
                    default -> completedQty == 0;
                  };
              if (!ok) {
                throw new PmartException(
                    ErrorCode.VALIDATION_FAILED,
                    "결과 수량이 맞지 않습니다("
                        + status
                        + " "
                        + completedQty
                        + ", 남은 "
                        + left
                        + ") — SUCCESS 는 남은 전량, PARTIAL 은 1~남은 수량, FAILED 는 0.");
              }
              Timestamp at = Timestamp.from(clock.instant());
              jdbc.update(
                  "INSERT INTO work_result (event_id, task_id, device_id, status, completed_qty, failure_reason, body_hash,"
                      + " state, received_at) VALUES (?,?,?,?,?,?,?,?,?)",
                  eventId,
                  taskId,
                  deviceId,
                  status,
                  completedQty,
                  failureReason,
                  hash,
                  "FAILED".equals(status) ? "APPLIED" : "PENDING",
                  at);
              if ("FAILED".equals(status)) {
                jdbc.update(
                    "UPDATE putaway_task SET state = 'FAILED', failure_reason = ?, space_release_pending = true,"
                        + " updated_at = ? WHERE task_id = ?",
                    failureReason == null ? "설비 실패" : failureReason,
                    at,
                    taskId);
              }
              return true;
            });
    if (Boolean.TRUE.equals(fresh)) {
      process(eventId);
      releaseRemaining(taskId);
    }
    return view(taskId);
  }

  /**
   * 결과 반영: PENDING → 재고 이동(키 inb-move-<eventId>) → MOVED → 예약 소비 → APPLIED. 불명이면 그 단계에 남아 주기 재시도.
   */
  void process(String eventId) {
    Map<String, Object> e =
        jdbc.queryForMap("SELECT * FROM work_result WHERE event_id = ?", eventId);
    String taskId = (String) e.get("task_id");
    Map<String, Object> t = row(taskId);
    int delta = ((Number) e.get("completed_qty")).intValue();
    if ("PENDING".equals(e.get("state"))) {
      Map<String, Object> line = new LinkedHashMap<>();
      line.put("sku", t.get("sku"));
      line.put("lot", t.get("lot"));
      line.put("fromLocationCode", t.get("from_location"));
      line.put("toLocationCode", t.get("to_location"));
      line.put("qty", delta);
      line.put("qualityState", t.get("quality_state"));
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("cdcId", t.get("cdc_id"));
      body.put(
          "moveKind", "REPLENISHMENT".equals(t.get("kind")) ? "REPLENISHMENT_MOVE" : "PUTAWAY");
      body.put("sourceRef", taskId);
      body.put("lines", List.of(line));
      String commit;
      try {
        commit = clients.move("inb-move-" + eventId, body);
      } catch (PmartException ex) {
        if (Clients.unknown(ex)) {
          log.warn("재고 이동 불명 — {} 다음에 다시: {}", eventId, ex.getMessage());
          return;
        }
        String r = Clients.reason(ex);
        tx.executeWithoutResult(
            x -> {
              Timestamp at = Timestamp.from(clock.instant());
              jdbc.update(
                  "UPDATE work_result SET state = 'REJECTED', reject_reason = ? WHERE event_id = ?",
                  r,
                  eventId);
              jdbc.update(
                  "UPDATE putaway_task SET state = 'FAILED', failure_reason = ?, space_release_pending = true,"
                      + " updated_at = ? WHERE task_id = ?",
                  "재고 이동 거절 — " + r,
                  at,
                  taskId);
            });
        log.warn("재고 이동 거절 — {}: {}", eventId, r);
        return;
      }
      String c = commit;
      tx.executeWithoutResult(
          x -> {
            jdbc.update(
                "UPDATE work_result SET state = 'MOVED', commit_id = ? WHERE event_id = ? AND state = 'PENDING'",
                c,
                eventId);
            jdbc.update(
                "UPDATE putaway_task SET completed_qty = completed_qty + ?,"
                    + " state = CASE WHEN completed_qty + ? >= qty THEN 'COMPLETED' ELSE 'PARTIALLY_COMPLETED' END,"
                    + " updated_at = ? WHERE task_id = ?",
                delta,
                delta,
                Timestamp.from(clock.instant()),
                taskId);
          });
      e = jdbc.queryForMap("SELECT * FROM work_result WHERE event_id = ?", eventId);
    }
    if ("MOVED".equals(e.get("state")) && t.get("reservation_id") != null) {
      try {
        clients.releaseSpace(
            "inb-consume-" + eventId,
            (String) t.get("reservation_id"),
            "CONSUME",
            (String) t.get("to_location"),
            delta,
            (String) e.get("commit_id"));
      } catch (PmartException ex) {
        log.warn("공간 예약 소비 실패 — {} 다음에 다시: {}", eventId, ex.getMessage());
        return;
      }
      tx.executeWithoutResult(
          x ->
              jdbc.update(
                  "UPDATE work_result SET state = 'APPLIED' WHERE event_id = ? AND state = 'MOVED'",
                  eventId));
    }
  }

  /** 실패·취소된 작업의 남은 공간 예약 해제(키 = 작업, 한 번). */
  void releaseRemaining(String taskId) {
    Map<String, Object> t = row(taskId);
    if (!Boolean.TRUE.equals(t.get("space_release_pending")) || t.get("reservation_id") == null) {
      return;
    }
    try {
      clients.releaseSpace(
          "inb-release-" + taskId,
          (String) t.get("reservation_id"),
          "RELEASE",
          (String) t.get("to_location"),
          0,
          null);
    } catch (PmartException ex) {
      if (!"OVER_RELEASE".equals(ex.code()) && Clients.unknown(ex)) {
        log.warn("공간 예약 해제 불명 — {} 다음에 다시: {}", taskId, ex.getMessage());
        return;
      }
      log.warn("공간 예약 해제 거절 — {}: {}", taskId, ex.getMessage());
    }
    tx.executeWithoutResult(
        x ->
            jdbc.update(
                "UPDATE putaway_task SET space_release_pending = false WHERE task_id = ?", taskId));
  }

  // ---------- 취소 ----------

  public View cancel(
      String requestId, String sourceSystem, String cdcId, String taskId, String reason) {
    Operator op = Operator.forCenter(cdcId, sourceSystem);
    op.requireManager();
    Map<String, Object> req = Map.of("taskId", taskId, "reason", reason);
    idempotency.execute(
        op.key(requestId, OP_CANCEL),
        req,
        Ref.class,
        () -> {
          List<Map<String, Object>> tr =
              jdbc.queryForList(
                  "SELECT state FROM putaway_task WHERE task_id = ? AND cdc_id = ? FOR UPDATE",
                  taskId,
                  cdcId);
          if (tr.isEmpty()) {
            throw PmartException.notFound("NOT_FOUND", "작업 " + taskId + " 가 없습니다.");
          }
          String state = (String) tr.get(0).get("state");
          if (!Set.of("DISPATCHED", "IN_PROGRESS", "PARTIALLY_COMPLETED").contains(state)) {
            throw PmartException.invalidState("작업 상태 " + state + " 에서는 취소할 수 없습니다.");
          }
          Integer inFlight =
              jdbc.queryForObject(
                  "SELECT count(*) FROM work_result WHERE task_id = ? AND state IN ('PENDING','MOVED')",
                  Integer.class,
                  taskId);
          if (inFlight != null && inFlight > 0) {
            throw conflict("RESULT_IN_FLIGHT", "반영 중인 결과가 있습니다 — 끝난 뒤 취소하세요.");
          }
          jdbc.update(
              "UPDATE putaway_task SET state = 'CANCELLED', failure_reason = ?, space_release_pending = true,"
                  + " updated_at = ? WHERE task_id = ?",
              "취소 — " + reason,
              Timestamp.from(clock.instant()),
              taskId);
          return new Ref(taskId);
        });
    releaseRemaining(taskId);
    return view(taskId);
  }

  // ---------- 재시도 ----------

  @Scheduled(
      initialDelayString = "${pmart.inbound.retry-interval:PT30S}",
      fixedDelayString = "${pmart.inbound.retry-interval:PT30S}")
  public void retry() {
    for (String id :
        jdbc.queryForList(
            "SELECT task_id FROM putaway_task WHERE state = 'CREATED' ORDER BY created_at LIMIT 50",
            String.class)) {
      safe(() -> dispatch(id, false), id);
    }
    for (String id :
        jdbc.queryForList(
            "SELECT event_id FROM work_result WHERE state IN ('PENDING','MOVED') ORDER BY received_at LIMIT 50",
            String.class)) {
      safe(() -> process(id), id);
    }
    for (String id :
        jdbc.queryForList(
            "SELECT task_id FROM putaway_task WHERE space_release_pending ORDER BY updated_at LIMIT 50",
            String.class)) {
      safe(() -> releaseRemaining(id), id);
    }
  }

  private static void safe(Runnable r, String id) {
    try {
      r.run();
    } catch (RuntimeException e) {
      log.warn("적치 작업 재시도 실패 — {}: {}", id, e.toString());
    }
  }

  // ---------- 조회 ----------

  public View get(String cdcId, String taskId) {
    Operator.requireCenter(cdcId);
    View v = view(taskId);
    if (!v.cdcId().equals(cdcId)) {
      throw PmartException.notFound("NOT_FOUND", "작업 " + taskId + " 가 없습니다.");
    }
    return v;
  }

  public List<View> list(
      String cdcId,
      @Nullable String state,
      @Nullable String kind,
      @Nullable String cursor,
      int size) {
    Operator.requireCenter(cdcId);
    StringBuilder sql = new StringBuilder("SELECT task_id FROM putaway_task WHERE cdc_id = ?");
    List<Object> args = new ArrayList<>(List.of(cdcId));
    if (state != null) {
      sql.append(" AND state = ?");
      args.add(state);
    }
    if (kind != null) {
      sql.append(" AND kind = ?");
      args.add(kind);
    }
    if (cursor != null) {
      sql.append(
          " AND (created_at, task_id) < (SELECT created_at, task_id FROM putaway_task WHERE task_id = ?)");
      args.add(cursor);
    }
    sql.append(" ORDER BY created_at DESC, task_id DESC LIMIT ?");
    args.add(size);
    return jdbc.queryForList(sql.toString(), String.class, args.toArray()).stream()
        .map(this::view)
        .toList();
  }

  public View view(String taskId) {
    Map<String, Object> t = row(taskId);
    List<ResultView> results =
        jdbc.query(
            "SELECT event_id, status, completed_qty, state, commit_id, reject_reason, received_at FROM work_result"
                + " WHERE task_id = ? ORDER BY received_at, event_id",
            (rs, i) ->
                new ResultView(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getInt(3),
                    "MOVED".equals(rs.getString(4)) ? "PENDING" : rs.getString(4),
                    rs.getString(5),
                    rs.getString(6),
                    rs.getTimestamp(7).toInstant()),
            taskId);
    return new View(
        taskId,
        (String) t.get("cdc_id"),
        (String) t.get("kind"),
        (String) t.get("state"),
        (String) t.get("sku"),
        (String) t.get("lot"),
        (String) t.get("quality_state"),
        (String) t.get("from_location"),
        (String) t.get("to_location"),
        ((Number) t.get("qty")).intValue(),
        ((Number) t.get("completed_qty")).intValue(),
        (String) t.get("reservation_id"),
        (String) t.get("device_id"),
        (String) t.get("created_by"),
        (String) t.get("reason"),
        (String) t.get("failure_reason"),
        results,
        ((Timestamp) t.get("created_at")).toInstant(),
        ((Timestamp) t.get("updated_at")).toInstant());
  }

  private Map<String, Object> row(String taskId) {
    List<Map<String, Object>> r =
        jdbc.queryForList("SELECT * FROM putaway_task WHERE task_id = ?", taskId);
    if (r.isEmpty()) {
      throw PmartException.notFound("NOT_FOUND", "작업 " + taskId + " 가 없습니다.");
    }
    return r.get(0);
  }

  static PmartException conflict(String code, String message) {
    return new PmartException(code, HttpStatus.CONFLICT, message, false, List.of());
  }

  static String sha(String s) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  Instant now() {
    return clock.instant();
  }
}
