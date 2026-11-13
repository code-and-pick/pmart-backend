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
      Instant createdAt) {}

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
          + " created_at";

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
        rs.getTimestamp(15).toInstant());
  }

  private static @Nullable Instant instant(ResultSet rs, int col) throws SQLException {
    Timestamp t = rs.getTimestamp(col);
    return t == null ? null : t.toInstant();
  }

  private static Timestamp ts(Instant i) {
    return Timestamp.from(i);
  }
}
