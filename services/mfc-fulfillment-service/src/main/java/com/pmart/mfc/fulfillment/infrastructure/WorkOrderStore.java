package com.pmart.mfc.fulfillment.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 작업 지시·작업 버전·출고·작업(V3, JDBC). */
@Repository
public class WorkOrderStore {

  public record WorkOrderRow(
      String fulfillmentId,
      String orderId,
      String mfcId,
      String reservationId,
      @Nullable String deliveryMode,
      @Nullable Instant arrivalStart,
      @Nullable Instant arrivalEnd,
      @Nullable String outOfStockPreference,
      int lastWorkVersion,
      String acceptance,
      int version,
      Instant createdAt,
      Instant updatedAt,
      String kind,
      @Nullable String sourceRef) {

    /** 오배송 재발송 작업 지시(2026-11-16). */
    public boolean reshipment() {
      return "RESHIPMENT".equals(kind);
    }
  }

  public record VersionRow(
      String fulfillmentId,
      int workVersion,
      @Nullable String sagaId,
      String decision,
      @Nullable String rejectReason,
      boolean retryable,
      @Nullable String message,
      Instant decidedAt) {}

  public record ShipmentRow(
      String shipmentId,
      String fulfillmentId,
      int workVersion,
      String state,
      @Nullable Integer weightG,
      int version) {}

  public record ShipmentLineRow(
      String shipmentId,
      String orderLineId,
      String sku,
      int qty,
      @Nullable String productName,
      @Nullable Integer unitsPerSku,
      @Nullable Integer weightG) {}

  public record TaskRow(
      String taskId,
      String shipmentId,
      String taskType,
      String state,
      @Nullable Instant readyAt,
      @Nullable String holdReason,
      int version) {}

  public record TaskLineRow(
      String taskId,
      String shipmentId,
      String orderLineId,
      int plannedQty,
      int completedQty,
      int cancelledQty) {

    public int remaining() {
      return plannedQty - completedQty - cancelledQty;
    }
  }

  /** 준비 시각 재계산 대상(설정 없음 대기). */
  public record ConfigWaiting(String taskId, String mfcId, @Nullable Instant arrivalStart) {}

  private final JdbcTemplate jdbc;

  public WorkOrderStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 같은 주문의 접수·중지를 한 줄로 세운다(트랜잭션 끝까지). 작업 지시 행이 아직 없을 때도 막아야 해서 행 잠금 대신 advisory lock. */
  public void lockOrder(String orderId) {
    jdbc.query(
        "SELECT pg_advisory_xact_lock(hashtextextended(?, 7301))",
        rs -> null,
        "fulfillment:" + orderId);
  }

  /** 주문 확정 작업 지시(ORIGINAL — 주문당 하나). 오배송 재발송 작업은 {@link #findBySource}. */
  public Optional<WorkOrderRow> findByOrder(String orderId) {
    return jdbc
        .query(
            "SELECT * FROM work_order WHERE order_id = ? AND kind = 'ORIGINAL'",
            (rs, i) -> order(rs),
            orderId)
        .stream()
        .findFirst();
  }

  /** 오배송 재발송 작업 지시(근거 사건당 하나). */
  public Optional<WorkOrderRow> findBySource(String sourceRef) {
    return jdbc
        .query("SELECT * FROM work_order WHERE source_ref = ?", (rs, i) -> order(rs), sourceRef)
        .stream()
        .findFirst();
  }

  public Optional<WorkOrderRow> find(String fulfillmentId) {
    return jdbc
        .query(
            "SELECT * FROM work_order WHERE fulfillment_id = ?",
            (rs, i) -> order(rs),
            fulfillmentId)
        .stream()
        .findFirst();
  }

  public void insert(WorkOrderRow w) {
    jdbc.update(
        "INSERT INTO work_order (fulfillment_id, order_id, mfc_id, reservation_id, delivery_mode,"
            + " arrival_start, arrival_end, out_of_stock_preference, last_work_version, acceptance,"
            + " version, created_at, updated_at, kind, source_ref) VALUES"
            + " (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        w.fulfillmentId(),
        w.orderId(),
        w.mfcId(),
        w.reservationId(),
        w.deliveryMode(),
        ts(w.arrivalStart()),
        ts(w.arrivalEnd()),
        w.outOfStockPreference(),
        w.lastWorkVersion(),
        w.acceptance(),
        w.version(),
        ts(w.createdAt()),
        ts(w.updatedAt()),
        w.kind(),
        w.sourceRef());
  }

  /** 새 작업 버전 결정 반영(마지막 버전·접수 여부·버전 +1). */
  public void decide(String fulfillmentId, int workVersion, String acceptance, Instant now) {
    jdbc.update(
        "UPDATE work_order SET last_work_version = ?, acceptance = ?, version = version + 1,"
            + " updated_at = ? WHERE fulfillment_id = ?",
        workVersion,
        acceptance,
        ts(now),
        fulfillmentId);
  }

  /** S4: 행을 잠그고 준비 시작 시각(없으면 empty 안의 null)을 읽는다. 행이 없으면 empty. */
  public Optional<Optional<Instant>> lockForReschedule(String fulfillmentId) {
    return jdbc
        .query(
            "SELECT preparation_started_at FROM work_order WHERE fulfillment_id = ? FOR UPDATE",
            (rs, i) -> Optional.ofNullable(instant(rs, "preparation_started_at")),
            fulfillmentId)
        .stream()
        .findFirst();
  }

  /** S4: 도착 시간대 바꾸기. */
  public void reschedule(String fulfillmentId, Instant start, Instant end, Instant now) {
    jdbc.update(
        "UPDATE work_order SET arrival_start = ?, arrival_end = ?, version = version + 1, updated_at"
            + " = ? WHERE fulfillment_id = ?",
        ts(start),
        ts(end),
        ts(now),
        fulfillmentId);
  }

  /**
   * S4: 시작 전 PICK 의 준비 시각 다시 정하기. waiting 이면 WAITING(미래 준비 시각·또는 설정 없음), 아니면 WAITING 만 READY
   * 로(READY·배정은 그대로). 반환 = 바꾼 PICK 수.
   */
  public int reschedulePicks(
      String fulfillmentId,
      boolean waiting,
      @Nullable Instant readyAt,
      @Nullable String holdReason,
      Instant now) {
    return jdbc.update(
        "UPDATE task t SET state = CASE WHEN ? THEN 'WAITING' WHEN t.state = 'WAITING' THEN 'READY'"
            + " ELSE t.state END, ready_at = ?, hold_reason = ?, version = t.version + 1, updated_at ="
            + " ? FROM shipment s WHERE s.shipment_id = t.shipment_id AND s.fulfillment_id = ? AND"
            + " t.task_type = 'PICK' AND t.state IN ('WAITING', 'READY', 'ASSIGNED') AND t.started_at"
            + " IS NULL",
        waiting,
        ts(readyAt),
        holdReason,
        ts(now),
        fulfillmentId);
  }

  public void touch(String fulfillmentId, Instant now) {
    jdbc.update(
        "UPDATE work_order SET version = version + 1, updated_at = ? WHERE fulfillment_id = ?",
        ts(now),
        fulfillmentId);
  }

  public Optional<VersionRow> version(String fulfillmentId, int workVersion) {
    return jdbc
        .query(
            "SELECT * FROM work_order_version WHERE fulfillment_id = ? AND work_version = ?",
            (rs, i) -> version(rs),
            fulfillmentId,
            workVersion)
        .stream()
        .findFirst();
  }

  public void insertVersion(VersionRow v) {
    jdbc.update(
        "INSERT INTO work_order_version (fulfillment_id, work_version, saga_id, decision,"
            + " reject_reason, retryable, message, decided_at) VALUES (?,?,?,?,?,?,?,?)",
        v.fulfillmentId(),
        v.workVersion(),
        v.sagaId(),
        v.decision(),
        v.rejectReason(),
        v.retryable(),
        v.message(),
        ts(v.decidedAt()));
  }

  public void insertShipment(ShipmentRow s, List<ShipmentLineRow> lines, Instant now) {
    jdbc.update(
        "INSERT INTO shipment (shipment_id, fulfillment_id, work_version, state, weight_g, version,"
            + " created_at, updated_at) VALUES (?,?,?,?,?,?,?,?)",
        s.shipmentId(),
        s.fulfillmentId(),
        s.workVersion(),
        s.state(),
        s.weightG(),
        s.version(),
        ts(now),
        ts(now));
    for (ShipmentLineRow l : lines) {
      jdbc.update(
          "INSERT INTO shipment_line (shipment_id, order_line_id, sku, qty, product_name,"
              + " units_per_sku, weight_g) VALUES (?,?,?,?,?,?,?)",
          l.shipmentId(),
          l.orderLineId(),
          l.sku(),
          l.qty(),
          l.productName(),
          l.unitsPerSku(),
          l.weightG());
    }
  }

  public void insertTask(TaskRow t, List<TaskLineRow> lines, Instant now) {
    jdbc.update(
        "INSERT INTO task (task_id, shipment_id, task_type, state, ready_at, hold_reason, version,"
            + " created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?)",
        t.taskId(),
        t.shipmentId(),
        t.taskType(),
        t.state(),
        ts(t.readyAt()),
        t.holdReason(),
        t.version(),
        ts(now),
        ts(now));
    for (TaskLineRow l : lines) {
      jdbc.update(
          "INSERT INTO task_line (task_id, shipment_id, order_line_id, planned_qty, completed_qty,"
              + " cancelled_qty) VALUES (?,?,?,?,?,?)",
          l.taskId(),
          l.shipmentId(),
          l.orderLineId(),
          l.plannedQty(),
          l.completedQty(),
          l.cancelledQty());
    }
  }

  public List<ShipmentRow> shipments(String fulfillmentId) {
    return jdbc.query(
        "SELECT * FROM shipment WHERE fulfillment_id = ? ORDER BY shipment_id",
        (rs, i) ->
            new ShipmentRow(
                rs.getString("shipment_id"),
                rs.getString("fulfillment_id"),
                rs.getInt("work_version"),
                rs.getString("state"),
                (Integer) rs.getObject("weight_g"),
                rs.getInt("version")),
        fulfillmentId);
  }

  public List<TaskRow> tasks(String fulfillmentId) {
    return jdbc.query(
        "SELECT t.* FROM task t JOIN shipment s ON s.shipment_id = t.shipment_id"
            + " WHERE s.fulfillment_id = ? ORDER BY t.shipment_id, t.task_type DESC",
        (rs, i) -> task(rs),
        fulfillmentId);
  }

  public List<TaskLineRow> taskLines(String fulfillmentId) {
    return jdbc.query(
        "SELECT l.* FROM task_line l JOIN shipment s ON s.shipment_id = l.shipment_id"
            + " WHERE s.fulfillment_id = ? ORDER BY l.task_id, l.order_line_id",
        (rs, i) ->
            new TaskLineRow(
                rs.getString("task_id"),
                rs.getString("shipment_id"),
                rs.getString("order_line_id"),
                rs.getInt("planned_qty"),
                rs.getInt("completed_qty"),
                rs.getInt("cancelled_qty")),
        fulfillmentId);
  }

  public void cancelQty(String taskId, String orderLineId, int qty) {
    jdbc.update(
        "UPDATE task_line SET cancelled_qty = cancelled_qty + ? WHERE task_id = ? AND order_line_id = ?",
        qty,
        taskId,
        orderLineId);
  }

  /** 행 잔량이 모두 0 인 작업은 CANCELLED(아직 시작 전 상태에서만). */
  public void closeEmptyTasks(String fulfillmentId, Instant now) {
    jdbc.update(
        "UPDATE task t SET state = 'CANCELLED', hold_reason = NULL, version = t.version + 1,"
            + " updated_at = ? FROM shipment s WHERE s.shipment_id = t.shipment_id"
            + " AND s.fulfillment_id = ? AND t.state IN ('WAITING', 'READY')"
            + " AND NOT EXISTS (SELECT 1 FROM task_line l WHERE l.task_id = t.task_id"
            + " AND l.planned_qty - l.completed_qty - l.cancelled_qty > 0)",
        ts(now),
        fulfillmentId);
    jdbc.update(
        "UPDATE shipment s SET state = 'CANCELLED', version = s.version + 1, updated_at = ?"
            + " WHERE s.fulfillment_id = ? AND s.state = 'PREPARING'"
            + " AND NOT EXISTS (SELECT 1 FROM task t WHERE t.shipment_id = s.shipment_id"
            + " AND t.state <> 'CANCELLED')",
        ts(now),
        fulfillmentId);
  }

  /** 준비 시각이 지난 PICK 대기 → READY. 반환 = 바뀐 작업 수. */
  public int promoteDue(Instant now) {
    return jdbc.update(
        "UPDATE task SET state = 'READY', version = version + 1, updated_at = ?"
            + " WHERE state = 'WAITING' AND task_type = 'PICK' AND hold_reason IS NULL"
            + " AND ready_at IS NOT NULL AND ready_at <= ?",
        ts(now),
        ts(now));
  }

  public List<ConfigWaiting> configWaiting() {
    return jdbc.query(
        "SELECT t.task_id, w.mfc_id, w.arrival_start FROM task t"
            + " JOIN shipment s ON s.shipment_id = t.shipment_id"
            + " JOIN work_order w ON w.fulfillment_id = s.fulfillment_id"
            + " WHERE t.state = 'WAITING' AND t.task_type = 'PICK' AND t.hold_reason = 'CONFIG_REQUIRED'",
        (rs, i) ->
            new ConfigWaiting(
                rs.getString("task_id"), rs.getString("mfc_id"), instant(rs, "arrival_start")));
  }

  /** 설정이 들어온 대기 작업에 준비 시각을 넣는다(CONFIG_REQUIRED 일 때만 — 경합 시 한 번). */
  public void schedule(String taskId, String state, Instant readyAt, Instant now) {
    jdbc.update(
        "UPDATE task SET state = ?, ready_at = ?, hold_reason = NULL, version = version + 1,"
            + " updated_at = ? WHERE task_id = ? AND state = 'WAITING' AND hold_reason = 'CONFIG_REQUIRED'",
        state,
        ts(readyAt),
        ts(now),
        taskId);
  }

  // ---------- 변환 ----------

  private static WorkOrderRow order(ResultSet rs) throws SQLException {
    return new WorkOrderRow(
        rs.getString("fulfillment_id"),
        rs.getString("order_id"),
        rs.getString("mfc_id"),
        rs.getString("reservation_id"),
        rs.getString("delivery_mode"),
        instant(rs, "arrival_start"),
        instant(rs, "arrival_end"),
        rs.getString("out_of_stock_preference"),
        rs.getInt("last_work_version"),
        rs.getString("acceptance"),
        rs.getInt("version"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant(),
        rs.getString("kind"),
        rs.getString("source_ref"));
  }

  private static VersionRow version(ResultSet rs) throws SQLException {
    return new VersionRow(
        rs.getString("fulfillment_id"),
        rs.getInt("work_version"),
        rs.getString("saga_id"),
        rs.getString("decision"),
        rs.getString("reject_reason"),
        rs.getBoolean("retryable"),
        rs.getString("message"),
        rs.getTimestamp("decided_at").toInstant());
  }

  private static TaskRow task(ResultSet rs) throws SQLException {
    return new TaskRow(
        rs.getString("task_id"),
        rs.getString("shipment_id"),
        rs.getString("task_type"),
        rs.getString("state"),
        instant(rs, "ready_at"),
        rs.getString("hold_reason"),
        rs.getInt("version"));
  }

  private static @Nullable Instant instant(ResultSet rs, String col) throws SQLException {
    Timestamp t = rs.getTimestamp(col);
    return t == null ? null : t.toInstant();
  }

  private static @Nullable Timestamp ts(@Nullable Instant i) {
    return i == null ? null : Timestamp.from(i);
  }
}
