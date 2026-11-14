package com.pmart.mfc.inbound.infrastructure;

import com.pmart.mfc.inbound.infrastructure.InventoryReceiptClient.ReceiptLine;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 입고 작업·작업 실행 저장(JDBC). 작업 변경은 {@link #lock} 으로 잠근 행에. */
@Repository
public class InboundTaskStore {

  /** 작업. */
  public record TaskRow(
      String taskId,
      String taskType,
      String mfcId,
      String receiptId,
      @Nullable String parentTaskId,
      String sku,
      String lotId,
      String locationCode,
      int plannedQty,
      int doneQty,
      String state,
      int version,
      @Nullable String assigneeId,
      Instant createdAt) {

    public int remaining() {
      return plannedQty - doneQty;
    }
  }

  /** 작업 실행. */
  public record ExecutionRow(
      String executionId,
      String taskId,
      String requestId,
      String body,
      String applyStatus,
      @Nullable String commitId,
      @Nullable String rejectReason) {}

  private static final String COLUMNS =
      "task_id, task_type, mfc_id, receipt_id, parent_task_id, sku, lot_id, location_code,"
          + " planned_qty, done_qty, state, row_version, worker_id, created_at";

  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate named;

  public InboundTaskStore(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
    this.jdbc = jdbc;
    this.named = named;
  }

  /** 수령 줄마다 품질검사 작업(정상 수령분). 이미 있으면 만들지 않는다(재시도 안전). */
  public void inspectionsFor(String receiptId, String mfcId, List<ReceiptLine> lines, Instant at) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM inbound_task WHERE receipt_id = ? AND task_type = 'INSPECT'",
            Integer.class,
            receiptId);
    if (n != null && n > 0) {
      return;
    }
    for (ReceiptLine l : lines) {
      if (l.normalQty() > 0) {
        insert(
            "INSPECT",
            mfcId,
            receiptId,
            null,
            l.sku(),
            l.lotId(),
            l.locationCode(),
            l.normalQty(),
            at);
      }
    }
  }

  public String insert(
      String type,
      String mfcId,
      String receiptId,
      @Nullable String parentTaskId,
      String sku,
      String lotId,
      String locationCode,
      int planned,
      Instant at) {
    String id = (type.equals("INSPECT") ? "TSK-INS-" : "TSK-PUT-") + UUID.randomUUID();
    jdbc.update(
        "INSERT INTO inbound_task (task_id, task_type, mfc_id, receipt_id, parent_task_id, sku,"
            + " lot_id, location_code, planned_qty, done_qty, state, row_version, created_at,"
            + " updated_at) VALUES (?,?,?,?,?,?,?,?,?,0,'READY',1,?,?)",
        id,
        type,
        mfcId,
        receiptId,
        parentTaskId,
        sku,
        lotId,
        locationCode,
        planned,
        Timestamp.from(at),
        Timestamp.from(at));
    return id;
  }

  public Optional<TaskRow> find(String taskId) {
    return jdbc
        .query(
            "SELECT " + COLUMNS + " FROM inbound_task WHERE task_id = ?",
            (rs, i) -> row(rs),
            taskId)
        .stream()
        .findFirst();
  }

  public Optional<TaskRow> lock(String taskId) {
    return jdbc
        .query(
            "SELECT " + COLUMNS + " FROM inbound_task WHERE task_id = ? FOR UPDATE",
            (rs, i) -> row(rs),
            taskId)
        .stream()
        .findFirst();
  }

  /** 담당 투영(capacity AssignmentPublished) — 더 새 배정 버전만. 바뀌었으면 1. */
  public int assign(String taskId, @Nullable String workerId, int assignmentVersion, Instant at) {
    return jdbc.update(
        "UPDATE inbound_task SET worker_id = ?, assignment_version = ?, updated_at = ?"
            + " WHERE task_id = ? AND assignment_version < ?",
        workerId,
        assignmentVersion,
        Timestamp.from(at),
        taskId,
        assignmentVersion);
  }

  /** 실행 반영분만큼 완료 수량을 늘린다(잔량 0 이면 COMPLETED). */
  public void addDone(String taskId, int qty, Instant at) {
    jdbc.update(
        "UPDATE inbound_task SET done_qty = done_qty + ?, state = CASE WHEN done_qty + ? >="
            + " planned_qty THEN 'COMPLETED' ELSE state END, row_version = row_version + 1,"
            + " updated_at = ? WHERE task_id = ?",
        qty,
        qty,
        Timestamp.from(at),
        taskId);
  }

  public List<TaskRow> list(
      String mfcId, @Nullable String state, @Nullable String type, @Nullable String receiptId) {
    StringBuilder sql =
        new StringBuilder("SELECT " + COLUMNS + " FROM inbound_task WHERE mfc_id = :mfc");
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("mfc", mfcId);
    if (state != null) {
      sql.append(" AND state = :state");
      p.put("state", state);
    }
    if (type != null) {
      sql.append(" AND task_type = :type");
      p.put("type", type);
    }
    if (receiptId != null) {
      sql.append(" AND receipt_id = :receipt");
      p.put("receipt", receiptId);
    }
    sql.append(" ORDER BY created_at, task_id LIMIT 500");
    return named.query(sql.toString(), p, (rs, i) -> row(rs));
  }

  /** 같은 작업의 실행 중 아직 반영 결과를 모르는(CAPTURED·UNKNOWN_RESULT) 수량 합 — 잔량 검사에 넣는다. */
  public List<ExecutionRow> openExecutions(String taskId) {
    return jdbc.query(
        "SELECT execution_id, task_id, request_id, body::text, apply_status, inventory_commit_id,"
            + " reject_reason FROM inbound_task_execution WHERE task_id = ?"
            + " AND apply_status IN ('CAPTURED', 'UNKNOWN_RESULT')",
        (rs, i) -> exec(rs),
        taskId);
  }

  public Optional<ExecutionRow> execution(String taskId, String requestId) {
    return jdbc
        .query(
            "SELECT execution_id, task_id, request_id, body::text, apply_status, inventory_commit_id,"
                + " reject_reason FROM inbound_task_execution WHERE task_id = ? AND request_id = ?",
            (rs, i) -> exec(rs),
            taskId,
            requestId)
        .stream()
        .findFirst();
  }

  public void insertExecution(
      String executionId, String taskId, String requestId, String bodyJson, String by, Instant at) {
    jdbc.update(
        "INSERT INTO inbound_task_execution (execution_id, task_id, request_id, body, apply_status,"
            + " executed_by, created_at, updated_at) VALUES (?,?,?,?::jsonb,'CAPTURED',?,?,?)",
        executionId,
        taskId,
        requestId,
        bodyJson,
        by,
        Timestamp.from(at),
        Timestamp.from(at));
  }

  public void markExecution(
      String executionId,
      String status,
      @Nullable String commitId,
      @Nullable String reason,
      Instant at) {
    jdbc.update(
        "UPDATE inbound_task_execution SET apply_status = ?, inventory_commit_id = ?,"
            + " reject_reason = ?, updated_at = ? WHERE execution_id = ?",
        status,
        commitId,
        reason == null ? null : reason.length() > 500 ? reason.substring(0, 500) : reason,
        Timestamp.from(at),
        executionId);
  }

  /** 품질검사 실행이 만든 적치 작업. */
  public List<String> childTasks(String parentTaskId, String receiptId) {
    List<String> out = new ArrayList<>();
    jdbc.query(
        "SELECT task_id FROM inbound_task WHERE parent_task_id = ? AND receipt_id = ? ORDER BY task_id",
        rs -> {
          out.add(rs.getString(1));
        },
        parentTaskId,
        receiptId);
    return out;
  }

  private static ExecutionRow exec(ResultSet rs) throws SQLException {
    return new ExecutionRow(
        rs.getString(1),
        rs.getString(2),
        rs.getString(3),
        rs.getString(4),
        rs.getString(5),
        rs.getString(6),
        rs.getString(7));
  }

  private static TaskRow row(ResultSet rs) throws SQLException {
    return new TaskRow(
        rs.getString("task_id"),
        rs.getString("task_type"),
        rs.getString("mfc_id"),
        rs.getString("receipt_id"),
        rs.getString("parent_task_id"),
        rs.getString("sku"),
        rs.getString("lot_id"),
        rs.getString("location_code"),
        rs.getInt("planned_qty"),
        rs.getInt("done_qty"),
        rs.getString("state"),
        rs.getInt("row_version"),
        rs.getString("worker_id"),
        rs.getTimestamp("created_at").toInstant());
  }
}
