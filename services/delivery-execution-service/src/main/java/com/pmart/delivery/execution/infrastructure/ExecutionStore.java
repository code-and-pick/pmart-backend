package com.pmart.delivery.execution.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 배송 수행·포장 목록·인계 사실·이력·증빙 사진 저장(JDBC, V2__execution.sql). */
@Repository
public class ExecutionStore {

  public record DeliveryRow(
      String deliveryId,
      int version,
      String type,
      @Nullable String orderId,
      @Nullable String shipmentId,
      @Nullable String mfcId,
      String state,
      @Nullable String riderId,
      int assignmentVersion,
      @Nullable Instant assignedAt,
      @Nullable Instant pickedUpAt,
      @Nullable Instant completedAt,
      @Nullable String method,
      @Nullable String evidenceJson,
      Instant createdAt,
      @Nullable Integer acceptedFee,
      @Nullable String vehicleType,
      @Nullable Integer distanceM,
      @Nullable String failureJson) {}

  public record HelpRow(
      String requestId,
      String deliveryId,
      String riderId,
      String category,
      @Nullable String detail,
      String evidenceRefsJson,
      String stage,
      String state,
      @Nullable String caseId,
      Instant createdAt) {

    /**
     * 사건 번호(DeliveryIncidentRaised.incidentId) = 배송 ID + 요청 ID. 요청 ID 는 앱이 보낸 Idempotency-Key 라
     * 배송마다만 유일하다(기본 키 (delivery_id, request_id)) — 그대로 쓰면 다른 배송의 도움요청과 겹칠 수 있다(PC 1 검토 후속,
     * 2026-11-14).
     */
    public String incidentId() {
      return deliveryId + ":" + requestId;
    }
  }

  public record EarningRow(
      String earningId,
      String deliveryId,
      String riderId,
      boolean calculable,
      int rateTableVersion,
      @Nullable String vehicleType,
      @Nullable Integer distanceM,
      @Nullable Integer baseFee,
      @Nullable Integer total,
      String settlementState,
      java.time.LocalDate performedOn,
      @Nullable Instant confirmAt,
      java.time.@Nullable LocalDate payoutDate,
      @Nullable String note,
      Instant recordedAt) {}

  public record HandoverRow(
      String handoverId,
      String deliveryId,
      String packageId,
      @Nullable String riderId,
      String linesJson,
      Instant handedAt) {}

  public record HistoryRow(
      String state,
      Instant occurredAt,
      Instant recordedAt,
      String actor,
      @Nullable String sourceRef) {}

  public record PhotoRow(
      String photoRef,
      String deliveryId,
      String riderId,
      String objectKey,
      String contentType,
      int sizeBytes,
      Instant uploadedAt) {}

  private static final String DELIVERY_COLUMNS =
      "delivery_id, version, type, order_id, shipment_id, mfc_id, state, rider_id,"
          + " assignment_version, assigned_at, picked_up_at, completed_at, method, evidence::text,"
          + " created_at, accepted_fee, vehicle_type, distance_m, failure::text";

  private final JdbcTemplate jdbc;

  public ExecutionStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  // ---- 배송

  /** 사건으로 배송을 알게 되면 만든다(이미 있으면 비어 있는 칸만 채운다). */
  public void ensureDelivery(
      String deliveryId,
      @Nullable String type,
      @Nullable String orderId,
      @Nullable String shipmentId,
      @Nullable String mfcId,
      Instant now) {
    jdbc.update(
        "INSERT INTO execution_delivery (delivery_id, type, order_id, shipment_id, mfc_id, state,"
            + " created_at, updated_at) VALUES (?, COALESCE(?, 'FORWARD'), ?, ?, ?, 'UNASSIGNED', ?, ?)"
            + " ON CONFLICT (delivery_id) DO UPDATE SET"
            + " order_id = COALESCE(execution_delivery.order_id, EXCLUDED.order_id),"
            + " shipment_id = COALESCE(execution_delivery.shipment_id, EXCLUDED.shipment_id),"
            + " mfc_id = COALESCE(execution_delivery.mfc_id, EXCLUDED.mfc_id)",
        deliveryId,
        type,
        orderId,
        shipmentId,
        mfcId,
        ts(now),
        ts(now));
  }

  public Optional<DeliveryRow> delivery(String deliveryId) {
    return jdbc
        .query(
            "SELECT " + DELIVERY_COLUMNS + " FROM execution_delivery WHERE delivery_id = ?",
            this::mapDelivery,
            deliveryId)
        .stream()
        .findFirst();
  }

  public Optional<DeliveryRow> lockDelivery(String deliveryId) {
    return jdbc
        .query(
            "SELECT "
                + DELIVERY_COLUMNS
                + " FROM execution_delivery WHERE delivery_id = ? FOR UPDATE",
            this::mapDelivery,
            deliveryId)
        .stream()
        .findFirst();
  }

  public List<String> deliveriesOfShipment(String shipmentId) {
    return jdbc.queryForList(
        "SELECT delivery_id FROM execution_delivery WHERE shipment_id = ?",
        String.class,
        shipmentId);
  }

  public void assign(
      String deliveryId, String riderId, int assignmentVersion, Instant assignedAt, Instant now) {
    jdbc.update(
        "UPDATE execution_delivery SET state = 'ASSIGNED', rider_id = ?, assignment_version = ?,"
            + " assigned_at = ?, version = version + 1, updated_at = ? WHERE delivery_id = ?",
        riderId,
        assignmentVersion,
        ts(assignedAt),
        ts(now),
        deliveryId);
  }

  /** 픽업 전 배정 해제(다시 배차 대기). */
  public void unassign(String deliveryId, int assignmentVersion, Instant now) {
    jdbc.update(
        "UPDATE execution_delivery SET state = 'UNASSIGNED', rider_id = NULL, assignment_version = ?,"
            + " version = version + 1, updated_at = ? WHERE delivery_id = ?",
        assignmentVersion,
        ts(now),
        deliveryId);
  }

  public void setState(String deliveryId, String state, Instant now) {
    jdbc.update(
        "UPDATE execution_delivery SET state = ?, version = version + 1, updated_at = ?"
            + " WHERE delivery_id = ?",
        state,
        ts(now),
        deliveryId);
  }

  public void pickedUp(String deliveryId, Instant pickedUpAt, Instant now) {
    jdbc.update(
        "UPDATE execution_delivery SET state = 'PICKED_UP', picked_up_at = ?, version = version + 1,"
            + " updated_at = ? WHERE delivery_id = ?",
        ts(pickedUpAt),
        ts(now),
        deliveryId);
  }

  public void completed(
      String deliveryId, String method, String evidenceJson, Instant completedAt) {
    jdbc.update(
        "UPDATE execution_delivery SET state = 'COMPLETED', method = ?, evidence = ?::jsonb,"
            + " completed_at = ?, version = version + 1, updated_at = ? WHERE delivery_id = ?",
        method,
        evidenceJson,
        ts(completedAt),
        ts(completedAt),
        deliveryId);
  }

  /** 라이더 본인 배송(배정 최신순). 날짜는 호출자가 Asia/Seoul 하루를 UTC 구간으로 바꿔 준다. */
  public List<DeliveryRow> riderDeliveries(
      String riderId,
      @Nullable Instant from,
      @Nullable Instant to,
      @Nullable String state,
      int limit,
      int offset) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT " + DELIVERY_COLUMNS + " FROM execution_delivery WHERE rider_id = ?");
    List<Object> args = new java.util.ArrayList<>();
    args.add(riderId);
    if (from != null && to != null) {
      sql.append(" AND assigned_at >= ? AND assigned_at < ?");
      args.add(ts(from));
      args.add(ts(to));
    }
    if (state != null) {
      sql.append(" AND state = ?");
      args.add(state);
    }
    sql.append(" ORDER BY assigned_at DESC NULLS LAST, delivery_id LIMIT ? OFFSET ?");
    args.add(limit);
    args.add(offset);
    return jdbc.query(sql.toString(), this::mapDelivery, args.toArray());
  }

  // ---- 포장 목록·인계

  public void putShipmentPackages(String shipmentId, String packageIdsJson, Instant now) {
    jdbc.update(
        "INSERT INTO shipment_package (shipment_id, package_ids, updated_at) VALUES (?, ?::jsonb, ?)"
            + " ON CONFLICT (shipment_id) DO UPDATE SET package_ids = EXCLUDED.package_ids,"
            + " updated_at = EXCLUDED.updated_at",
        shipmentId,
        packageIdsJson,
        ts(now));
  }

  public Optional<String> shipmentPackages(String shipmentId) {
    return jdbc
        .queryForList(
            "SELECT package_ids::text FROM shipment_package WHERE shipment_id = ?",
            String.class,
            shipmentId)
        .stream()
        .findFirst();
  }

  /** 같은 handoverId 는 한 번. 새로 남겼으면 true. */
  public boolean recordHandover(
      String handoverId,
      String deliveryId,
      String packageId,
      @Nullable String riderId,
      String linesJson,
      Instant handedAt,
      Instant now) {
    return jdbc.update(
            "INSERT INTO package_handover (handover_id, delivery_id, package_id, rider_id, lines,"
                + " handed_at, recorded_at) VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)"
                + " ON CONFLICT (handover_id) DO NOTHING",
            handoverId,
            deliveryId,
            packageId,
            riderId,
            linesJson,
            ts(handedAt),
            ts(now))
        > 0;
  }

  public List<HandoverRow> handovers(String deliveryId) {
    return jdbc.query(
        "SELECT handover_id, delivery_id, package_id, rider_id, lines::text, handed_at"
            + " FROM package_handover WHERE delivery_id = ? ORDER BY handed_at, handover_id",
        (rs, i) ->
            new HandoverRow(
                rs.getString(1),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getString(5),
                rs.getTimestamp(6).toInstant()),
        deliveryId);
  }

  // ---- 이력

  public void history(
      String deliveryId,
      String state,
      Instant occurredAt,
      String actor,
      @Nullable String sourceRef,
      Instant now) {
    jdbc.update(
        "INSERT INTO execution_history (delivery_id, state, occurred_at, recorded_at, actor,"
            + " source_ref) VALUES (?, ?, ?, ?, ?, ?)",
        deliveryId,
        state,
        ts(occurredAt),
        ts(now),
        actor,
        sourceRef);
  }

  public List<HistoryRow> history(String deliveryId) {
    return jdbc.query(
        "SELECT state, occurred_at, recorded_at, actor, source_ref FROM execution_history"
            + " WHERE delivery_id = ? ORDER BY id",
        (rs, i) ->
            new HistoryRow(
                rs.getString(1),
                rs.getTimestamp(2).toInstant(),
                rs.getTimestamp(3).toInstant(),
                rs.getString(4),
                rs.getString(5)),
        deliveryId);
  }

  // ---- 증빙 사진

  public void insertPhoto(PhotoRow p) {
    jdbc.update(
        "INSERT INTO evidence_photo (photo_ref, delivery_id, rider_id, object_key, content_type,"
            + " size_bytes, uploaded_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
        p.photoRef(),
        p.deliveryId(),
        p.riderId(),
        p.objectKey(),
        p.contentType(),
        p.sizeBytes(),
        ts(p.uploadedAt()));
  }

  public List<PhotoRow> photos(String deliveryId) {
    return jdbc.query(
        "SELECT photo_ref, delivery_id, rider_id, object_key, content_type, size_bytes, uploaded_at"
            + " FROM evidence_photo WHERE delivery_id = ? ORDER BY uploaded_at",
        (rs, i) ->
            new PhotoRow(
                rs.getString(1),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getString(5),
                rs.getInt(6),
                rs.getTimestamp(7).toInstant()),
        deliveryId);
  }

  /**
   * 상태는 그대로, 수행 버전만 올린다 — 상태를 바꾸지 않는 사건(도움요청·수행료)도 aggregateVersion 이 겹치지 않게(dispatch L3 와 같은 규칙).
   *
   * @return 올린 뒤 버전
   */
  public int bumpVersion(String deliveryId, Instant now) {
    Integer v =
        jdbc.queryForObject(
            "UPDATE execution_delivery SET version = version + 1, updated_at = ? WHERE delivery_id = ?"
                + " RETURNING version",
            Integer.class,
            ts(now),
            deliveryId);
    return v == null ? 0 : v;
  }

  // ---- 수락 당시 금액·전달 실패

  public void accepted(
      String deliveryId,
      @Nullable Integer fee,
      @Nullable String vehicleType,
      @Nullable Integer distanceM) {
    jdbc.update(
        "UPDATE execution_delivery SET accepted_fee = ?, vehicle_type = ?, distance_m = ?"
            + " WHERE delivery_id = ?",
        fee,
        vehicleType,
        distanceM,
        deliveryId);
  }

  /** 실패 등록·결정 기록과 상태를 함께 바꾼다. */
  public void failure(String deliveryId, String state, String failureJson, Instant now) {
    jdbc.update(
        "UPDATE execution_delivery SET state = ?, failure = ?::jsonb, version = version + 1,"
            + " updated_at = ? WHERE delivery_id = ?",
        state,
        failureJson,
        ts(now),
        deliveryId);
  }

  // ---- 도움요청

  /**
   * @return 새로 남겼으면 true(같은 배송·같은 요청 ID 는 한 번)
   */
  public boolean insertHelp(HelpRow h) {
    return jdbc.update(
            "INSERT INTO help_request (request_id, delivery_id, rider_id, category, detail,"
                + " evidence_refs, stage, state, case_id, created_at) VALUES (?, ?, ?, ?, ?, ?::jsonb,"
                + " ?, ?, ?, ?) ON CONFLICT (delivery_id, request_id) DO NOTHING",
            h.requestId(),
            h.deliveryId(),
            h.riderId(),
            h.category(),
            h.detail(),
            h.evidenceRefsJson(),
            h.stage(),
            h.state(),
            h.caseId(),
            ts(h.createdAt()))
        > 0;
  }

  public Optional<HelpRow> help(String deliveryId, String requestId) {
    return jdbc
        .query(
            HELP_SELECT + " WHERE delivery_id = ? AND request_id = ?",
            this::mapHelp,
            deliveryId,
            requestId)
        .stream()
        .findFirst();
  }

  public List<HelpRow> helps(String deliveryId) {
    return jdbc.query(
        HELP_SELECT + " WHERE delivery_id = ? ORDER BY created_at", this::mapHelp, deliveryId);
  }

  private static final String HELP_SELECT =
      "SELECT request_id, delivery_id, rider_id, category, detail, evidence_refs::text, stage,"
          + " state, case_id, created_at FROM help_request";

  private HelpRow mapHelp(ResultSet rs, int i) throws SQLException {
    return new HelpRow(
        rs.getString(1),
        rs.getString(2),
        rs.getString(3),
        rs.getString(4),
        rs.getString(5),
        rs.getString(6),
        rs.getString(7),
        rs.getString(8),
        rs.getString(9),
        rs.getTimestamp(10).toInstant());
  }

  // ---- 수행료

  /**
   * @param assignmentVersion 이 수행료가 속한 배정(dispatch assignmentVersion) — 같은 라이더가 다시 배정돼도 따로(V4)
   * @return 새로 만들었으면 true(배정당 하나 — 중복 완료·사건 재전달은 한 번만, 원문 §6.1·§9.7)
   */
  public boolean insertEarning(EarningRow e, int assignmentVersion) {
    return jdbc.update(
            "INSERT INTO rider_earning (earning_id, delivery_id, rider_id, calculable,"
                + " rate_table_version, vehicle_type, distance_m, base_fee, total, settlement_state,"
                + " performed_on, confirm_at, payout_date, note, recorded_at, updated_at,"
                + " assignment_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                + " ON CONFLICT (delivery_id, rider_id, assignment_version) DO NOTHING",
            e.earningId(),
            e.deliveryId(),
            e.riderId(),
            e.calculable(),
            e.rateTableVersion(),
            e.vehicleType(),
            e.distanceM(),
            e.baseFee(),
            e.total(),
            e.settlementState(),
            java.sql.Date.valueOf(e.performedOn()),
            e.confirmAt() == null ? null : ts(e.confirmAt()),
            e.payoutDate() == null ? null : java.sql.Date.valueOf(e.payoutDate()),
            e.note(),
            ts(e.recordedAt()),
            ts(e.recordedAt()),
            assignmentVersion)
        > 0;
  }

  public Optional<EarningRow> earningOf(String deliveryId, String riderId) {
    return jdbc
        .query(
            EARNING_SELECT
                + " WHERE delivery_id = ? AND rider_id = ? ORDER BY recorded_at DESC LIMIT 1",
            this::mapEarning,
            deliveryId,
            riderId)
        .stream()
        .findFirst();
  }

  /** 확정 시각이 지난 확정 대기 수행료(다른 인스턴스와 겹치지 않게 잠근다). */
  public List<EarningRow> lockDueEarnings(Instant now, int limit) {
    return jdbc.query(
        EARNING_SELECT
            + " WHERE settlement_state = 'PENDING_CONFIRMATION' AND confirm_at <= ?"
            + " ORDER BY confirm_at LIMIT ? FOR UPDATE SKIP LOCKED",
        this::mapEarning,
        ts(now),
        limit);
  }

  public void settlementState(String earningId, String state, Instant now) {
    jdbc.update(
        "UPDATE rider_earning SET settlement_state = ?, updated_at = ? WHERE earning_id = ?",
        state,
        ts(now),
        earningId);
  }

  public List<EarningRow> riderEarnings(
      String riderId,
      java.time.@Nullable LocalDate from,
      java.time.@Nullable LocalDate to,
      @Nullable String state,
      int limit,
      int offset) {
    StringBuilder sql = new StringBuilder(EARNING_SELECT + " WHERE rider_id = ?");
    List<Object> args = new java.util.ArrayList<>();
    args.add(riderId);
    if (from != null) {
      sql.append(" AND performed_on >= ?");
      args.add(java.sql.Date.valueOf(from));
    }
    if (to != null) {
      sql.append(" AND performed_on <= ?");
      args.add(java.sql.Date.valueOf(to));
    }
    if (state != null) {
      sql.append(" AND settlement_state = ?");
      args.add(state);
    }
    sql.append(" ORDER BY performed_on DESC, recorded_at DESC, earning_id LIMIT ? OFFSET ?");
    args.add(limit);
    args.add(offset);
    return jdbc.query(sql.toString(), this::mapEarning, args.toArray());
  }

  /** 상태별 합계(산정 가능한 것만) — 기간 조건은 목록과 같다. */
  public java.util.Map<String, Long> riderTotals(
      String riderId, java.time.@Nullable LocalDate from, java.time.@Nullable LocalDate to) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT settlement_state, COALESCE(SUM(total), 0) FROM rider_earning WHERE rider_id = ?"
                + " AND calculable");
    List<Object> args = new java.util.ArrayList<>();
    args.add(riderId);
    if (from != null) {
      sql.append(" AND performed_on >= ?");
      args.add(java.sql.Date.valueOf(from));
    }
    if (to != null) {
      sql.append(" AND performed_on <= ?");
      args.add(java.sql.Date.valueOf(to));
    }
    sql.append(" GROUP BY settlement_state");
    java.util.Map<String, Long> out = new java.util.HashMap<>();
    jdbc.query(
        sql.toString(),
        rs -> {
          out.put(rs.getString(1), rs.getLong(2));
        },
        args.toArray());
    return out;
  }

  private static final String EARNING_SELECT =
      "SELECT earning_id, delivery_id, rider_id, calculable, rate_table_version, vehicle_type,"
          + " distance_m, base_fee, total, settlement_state, performed_on, confirm_at, payout_date,"
          + " note, recorded_at FROM rider_earning";

  private EarningRow mapEarning(ResultSet rs, int i) throws SQLException {
    java.sql.Date payout = rs.getDate(13);
    return new EarningRow(
        rs.getString(1),
        rs.getString(2),
        rs.getString(3),
        rs.getBoolean(4),
        rs.getInt(5),
        rs.getString(6),
        (Integer) rs.getObject(7),
        (Integer) rs.getObject(8),
        (Integer) rs.getObject(9),
        rs.getString(10),
        rs.getDate(11).toLocalDate(),
        instant(rs, 12),
        payout == null ? null : payout.toLocalDate(),
        rs.getString(14),
        rs.getTimestamp(15).toInstant());
  }

  private DeliveryRow mapDelivery(ResultSet rs, int i) throws SQLException {
    return new DeliveryRow(
        rs.getString(1),
        rs.getInt(2),
        rs.getString(3),
        rs.getString(4),
        rs.getString(5),
        rs.getString(6),
        rs.getString(7),
        rs.getString(8),
        rs.getInt(9),
        instant(rs, 10),
        instant(rs, 11),
        instant(rs, 12),
        rs.getString(13),
        rs.getString(14),
        rs.getTimestamp(15).toInstant(),
        (Integer) rs.getObject(16),
        rs.getString(17),
        (Integer) rs.getObject(18),
        rs.getString(19));
  }

  private static @Nullable Instant instant(ResultSet rs, int col) throws SQLException {
    Timestamp t = rs.getTimestamp(col);
    return t == null ? null : t.toInstant();
  }

  private static Timestamp ts(Instant i) {
    return Timestamp.from(i);
  }
}
