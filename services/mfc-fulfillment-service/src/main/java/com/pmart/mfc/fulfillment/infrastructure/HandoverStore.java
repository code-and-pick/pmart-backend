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

/** 라이더 인계(V6, JDBC): 배송 배정 투영·포장 fence·인계 사실. */
@Repository
public class HandoverStore {

  /** 포장 + 인계 허가 + 출고·주문 머리. */
  public record PackageFence(
      String packageId,
      String shipmentId,
      int packageVersion,
      String state,
      @Nullable String qrCode,
      String contents,
      String packingLocation,
      int version,
      String fenceState,
      @Nullable String authorizationId,
      int fenceVersion,
      @Nullable String fenceRiderId,
      @Nullable String fenceDeliveryId,
      @Nullable Instant fenceExpiresAt,
      @Nullable Instant fenceAuthorizedAt,
      @Nullable Instant handedOverAt,
      String shipmentState,
      String fulfillmentId,
      String orderId,
      String mfcId,
      String reservationId) {}

  public record Assignment(
      String deliveryId,
      @Nullable String shipmentId,
      @Nullable String orderId,
      @Nullable String riderId,
      int assignmentVersion,
      String state) {}

  public record Handover(
      String handoverId,
      String packageId,
      String shipmentId,
      String authorizationId,
      int fenceVersion,
      String riderId,
      String deliveryId,
      String state,
      @Nullable String inventoryCommitId,
      @Nullable String rejectReason,
      Instant requestedAt,
      @Nullable Instant handedOverAt) {}

  private static final String FENCE_SELECT =
      "SELECT p.*, s.state AS shipment_state, s.fulfillment_id, w.order_id, w.mfc_id,"
          + " w.reservation_id FROM package p JOIN shipment s ON s.shipment_id = p.shipment_id"
          + " JOIN work_order w ON w.fulfillment_id = s.fulfillment_id";

  private final JdbcTemplate jdbc;

  public HandoverStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  // ---------- 포장 fence ----------

  public Optional<PackageFence> find(String packageId) {
    return jdbc
        .query(FENCE_SELECT + " WHERE p.package_id = ?", (rs, i) -> fence(rs), packageId)
        .stream()
        .findFirst();
  }

  public Optional<PackageFence> lock(String packageId) {
    return jdbc
        .query(
            FENCE_SELECT + " WHERE p.package_id = ? FOR UPDATE OF p",
            (rs, i) -> fence(rs),
            packageId)
        .stream()
        .findFirst();
  }

  /** 점원 확인 필요(허가 만료·확정 요청 없음) 포장 — 센터 전체, 만료 오래된 순(0.4.9). */
  public List<PackageFence> checkRequired(String mfcId, Instant now) {
    return jdbc.query(
        FENCE_SELECT
            + " WHERE w.mfc_id = ? AND p.fence_state = 'HANDOVER_IN_PROGRESS' AND p.fence_expires_at <= ?"
            + " AND NOT EXISTS (SELECT 1 FROM package_handover h WHERE h.package_id = p.package_id)"
            + " ORDER BY p.fence_expires_at, p.package_id LIMIT 200",
        (rs, i) -> fence(rs),
        mfcId,
        java.sql.Timestamp.from(now));
  }

  /** 출고의 포장 전부(만든 순). */
  public List<PackageFence> byShipment(String shipmentId) {
    return jdbc.query(
        FENCE_SELECT + " WHERE p.shipment_id = ? ORDER BY p.created_at, p.package_id",
        (rs, i) -> fence(rs),
        shipmentId);
  }

  public void authorize(
      String packageId,
      String authorizationId,
      int fenceVersion,
      String riderId,
      String deliveryId,
      Instant expiresAt,
      Instant at) {
    jdbc.update(
        "UPDATE package SET fence_state = 'HANDOVER_IN_PROGRESS', handover_authorization_id = ?,"
            + " fence_version = ?, fence_rider_id = ?, fence_delivery_id = ?, fence_expires_at = ?,"
            + " fence_authorized_at = ?, version = version + 1 WHERE package_id = ?",
        authorizationId,
        fenceVersion,
        riderId,
        deliveryId,
        Timestamp.from(expiresAt),
        Timestamp.from(at),
        packageId);
  }

  /** 살아 있는 fence 를 끝냄(만료·배정 변경). 확정 요청이 있는 포장은 건드리지 않는다. 반환 = 바뀐 행 수. */
  /** 점원 확인 기록(D-1). */
  public void recordCheck(
      String packageId, String result, String by, @Nullable String note, Instant at) {
    jdbc.update(
        "UPDATE package SET fence_check_result = ?, fence_checked_by = ?, fence_checked_at = ?,"
            + " fence_check_note = ? WHERE package_id = ?",
        result,
        by,
        java.sql.Timestamp.from(at),
        note,
        packageId);
  }

  public record Check(String result, String checkedBy, Instant checkedAt) {}

  public Optional<Check> lastCheck(String packageId) {
    return jdbc
        .query(
            "SELECT fence_check_result, fence_checked_by, fence_checked_at FROM package"
                + " WHERE package_id = ? AND fence_check_result IS NOT NULL",
            (rs, i) -> new Check(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant()),
            packageId)
        .stream()
        .findFirst();
  }

  public int expireFence(String packageId) {
    return jdbc.update(
        "UPDATE package p SET fence_state = 'EXPIRED', version = version + 1 WHERE p.package_id = ?"
            + " AND p.fence_state = 'HANDOVER_IN_PROGRESS'"
            + " AND NOT EXISTS (SELECT 1 FROM package_handover h WHERE h.package_id = p.package_id)",
        packageId);
  }

  public void handedOver(String packageId, Instant at) {
    jdbc.update(
        "UPDATE package SET state = 'HANDED_OVER', fence_state = 'COMMITTED', handed_over_at = ?,"
            + " version = version + 1 WHERE package_id = ? AND state = 'SEALED'",
        Timestamp.from(at),
        packageId);
  }

  /** 출고 상태 다시 계산: 유효 포장(SEALED·HANDED_OVER)이 모두 인계면 HANDED_OVER, 일부면 PARTIALLY_HANDED_OVER. */
  public String refreshShipment(String shipmentId, Instant at) {
    jdbc.update(
        "UPDATE shipment s SET state = CASE WHEN NOT EXISTS (SELECT 1 FROM package p WHERE"
            + " p.shipment_id = s.shipment_id AND p.state IN ('OPEN', 'SEALED')) THEN 'HANDED_OVER'"
            + " ELSE 'PARTIALLY_HANDED_OVER' END, version = version + 1, updated_at = ?"
            + " WHERE s.shipment_id = ? AND s.state IN ('READY', 'PARTIALLY_HANDED_OVER')",
        Timestamp.from(at),
        shipmentId);
    return jdbc.queryForObject(
        "SELECT state FROM shipment WHERE shipment_id = ?", String.class, shipmentId);
  }

  // ---------- 배정 투영 ----------

  /** 출고의 밀봉 포장 중 라벨 숫자가 같은 것(F-8). */
  public List<String> sealedPackagesByCode(String shipmentId, String handoverCode) {
    return jdbc.queryForList(
        "SELECT package_id FROM package WHERE shipment_id = ? AND handover_code = ? AND state = 'SEALED'"
            + " ORDER BY package_id",
        String.class,
        shipmentId,
        handoverCode);
  }

  /** 이 배송에 이미 인계된 같은 번호의 포장(2026-11-20 — 다시 넣어도 틀린 번호로 세지 않게). */
  public boolean handedOverByCode(String shipmentId, String handoverCode) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM package p WHERE p.shipment_id = ? AND p.handover_code = ? AND"
                + " p.state = 'HANDED_OVER'",
            Integer.class,
            shipmentId,
            handoverCode);
    return n != null && n > 0;
  }

  /** 번호 입력 실패 횟수(0.23.0, R-26) — 없으면 0. */
  public int codeFailures(String riderId, String deliveryId) {
    List<Integer> r =
        jdbc.queryForList(
            "SELECT failures FROM handover_code_attempt WHERE rider_id = ? AND delivery_id = ?",
            Integer.class,
            riderId,
            deliveryId);
    return r.isEmpty() ? 0 : r.get(0);
  }

  /** 번호 입력 실패 한 번 더 — 반환 = 누적 횟수. */
  public int recordCodeFailure(String riderId, String deliveryId, Instant at) {
    return java.util.Objects.requireNonNull(
        jdbc.queryForObject(
            "INSERT INTO handover_code_attempt (rider_id, delivery_id, failures, last_failed_at)"
                + " VALUES (?,?,1,?) ON CONFLICT (rider_id, delivery_id) DO UPDATE"
                + " SET failures = handover_code_attempt.failures + 1, last_failed_at = EXCLUDED.last_failed_at"
                + " RETURNING failures",
            Integer.class,
            riderId,
            deliveryId,
            Timestamp.from(at)));
  }

  public Optional<Assignment> assignment(String deliveryId) {
    return jdbc
        .query(
            "SELECT * FROM delivery_assignment WHERE delivery_id = ?",
            (rs, i) -> assignment(rs),
            deliveryId)
        .stream()
        .findFirst();
  }

  public Optional<Assignment> lockAssignment(String deliveryId) {
    return jdbc
        .query(
            "SELECT * FROM delivery_assignment WHERE delivery_id = ? FOR UPDATE",
            (rs, i) -> assignment(rs),
            deliveryId)
        .stream()
        .findFirst();
  }

  /** 출고의 배송(가장 최근 갱신). */
  public Optional<Assignment> byShipmentAssignment(String shipmentId) {
    return jdbc
        .query(
            "SELECT * FROM delivery_assignment WHERE shipment_id = ? ORDER BY updated_at DESC LIMIT 1",
            (rs, i) -> assignment(rs),
            shipmentId)
        .stream()
        .findFirst();
  }

  /** 없으면 만든다(CREATED). 있으면 비어 있는 출고·주문만 채운다. */
  public void upsertDelivery(
      String deliveryId, @Nullable String shipmentId, @Nullable String orderId, Instant at) {
    jdbc.update(
        "INSERT INTO delivery_assignment (delivery_id, shipment_id, order_id, state, updated_at)"
            + " VALUES (?,?,?, 'CREATED', ?) ON CONFLICT (delivery_id) DO UPDATE SET"
            + " shipment_id = COALESCE(delivery_assignment.shipment_id, EXCLUDED.shipment_id),"
            + " order_id = COALESCE(delivery_assignment.order_id, EXCLUDED.order_id)",
        deliveryId,
        shipmentId,
        orderId,
        Timestamp.from(at));
  }

  /** 더 새 배정 버전만 반영. 반환 = 바뀐 행 수. */
  public int applyAssignment(
      String deliveryId, @Nullable String riderId, int version, String state, Instant at) {
    return jdbc.update(
        "UPDATE delivery_assignment SET rider_id = ?, assignment_version = ?, state = ?,"
            + " updated_at = ? WHERE delivery_id = ? AND assignment_version < ?"
            + " AND state <> 'CANCELLED'",
        riderId,
        version,
        state,
        Timestamp.from(at),
        deliveryId,
        version);
  }

  /** 픽업 전 배차 취소 — 버전과 상관없이 끝(새 배송은 새 배송 ID). */
  public int cancelDelivery(String deliveryId, Instant at) {
    return jdbc.update(
        "UPDATE delivery_assignment SET state = 'CANCELLED', rider_id = NULL, updated_at = ?"
            + " WHERE delivery_id = ? AND state <> 'CANCELLED'",
        Timestamp.from(at),
        deliveryId);
  }

  // ---------- 인계 사실 ----------

  public Optional<Handover> handover(String packageId) {
    return jdbc
        .query(
            "SELECT * FROM package_handover WHERE package_id = ?",
            (rs, i) -> handover(rs),
            packageId)
        .stream()
        .findFirst();
  }

  public Optional<Handover> handoverById(String handoverId) {
    return jdbc
        .query(
            "SELECT * FROM package_handover WHERE handover_id = ?",
            (rs, i) -> handover(rs),
            handoverId)
        .stream()
        .findFirst();
  }

  /** 재고가 거절한 인계를 운영자가 다시 보낼 때 — REJECTED 일 때만 REQUESTED 로(사유 지움). */
  public int reopenRejected(String handoverId, Instant at) {
    return jdbc.update(
        "UPDATE package_handover SET state = 'REQUESTED', reject_reason = NULL, updated_at = ?"
            + " WHERE handover_id = ? AND state = 'REJECTED'",
        Timestamp.from(at),
        handoverId);
  }

  public void insertHandover(Handover h) {
    jdbc.update(
        "INSERT INTO package_handover (handover_id, package_id, shipment_id,"
            + " handover_authorization_id, fence_version, rider_id, delivery_id, state,"
            + " requested_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
        h.handoverId(),
        h.packageId(),
        h.shipmentId(),
        h.authorizationId(),
        h.fenceVersion(),
        h.riderId(),
        h.deliveryId(),
        h.state(),
        Timestamp.from(h.requestedAt()),
        Timestamp.from(h.requestedAt()));
  }

  /** 열린 인계(REQUESTED·UNKNOWN_RESULT)만 바꾼다. 반환 = 바뀐 행 수. */
  public int finishHandover(
      String handoverId,
      String state,
      @Nullable String commitId,
      @Nullable String reason,
      @Nullable Instant handedOverAt,
      Instant at) {
    return jdbc.update(
        "UPDATE package_handover SET state = ?, inventory_commit_id = ?, reject_reason = ?,"
            + " handed_over_at = ?, updated_at = ? WHERE handover_id = ?"
            + " AND state IN ('REQUESTED', 'UNKNOWN_RESULT')",
        state,
        commitId,
        reason,
        handedOverAt == null ? null : Timestamp.from(handedOverAt),
        Timestamp.from(at),
        handoverId);
  }

  /** 재고 결과를 아직 모르는 인계(오래된 순) — sweep. */
  public List<String> openHandovers(Instant before, int limit) {
    return jdbc.queryForList(
        "SELECT package_id FROM package_handover WHERE state IN ('REQUESTED', 'UNKNOWN_RESULT')"
            + " AND updated_at < ? ORDER BY updated_at LIMIT ?",
        String.class,
        Timestamp.from(before),
        limit);
  }

  // ---------- 변환 ----------

  private static PackageFence fence(ResultSet rs) throws SQLException {
    return new PackageFence(
        rs.getString("package_id"),
        rs.getString("shipment_id"),
        rs.getInt("package_version"),
        rs.getString("state"),
        rs.getString("qr_code"),
        rs.getString("contents"),
        // 포장분이 지금 있는 곳(F-12) — 출하대기 랙에 올렸으면 랙(인계 전기 위치)
        rs.getString("staging_location") != null
            ? rs.getString("staging_location")
            : rs.getString("packing_location"),
        rs.getInt("version"),
        rs.getString("fence_state"),
        rs.getString("handover_authorization_id"),
        rs.getInt("fence_version"),
        rs.getString("fence_rider_id"),
        rs.getString("fence_delivery_id"),
        instant(rs, "fence_expires_at"),
        instant(rs, "fence_authorized_at"),
        instant(rs, "handed_over_at"),
        rs.getString("shipment_state"),
        rs.getString("fulfillment_id"),
        rs.getString("order_id"),
        rs.getString("mfc_id"),
        rs.getString("reservation_id"));
  }

  private static Assignment assignment(ResultSet rs) throws SQLException {
    return new Assignment(
        rs.getString("delivery_id"),
        rs.getString("shipment_id"),
        rs.getString("order_id"),
        rs.getString("rider_id"),
        rs.getInt("assignment_version"),
        rs.getString("state"));
  }

  private static Handover handover(ResultSet rs) throws SQLException {
    return new Handover(
        rs.getString("handover_id"),
        rs.getString("package_id"),
        rs.getString("shipment_id"),
        rs.getString("handover_authorization_id"),
        rs.getInt("fence_version"),
        rs.getString("rider_id"),
        rs.getString("delivery_id"),
        rs.getString("state"),
        rs.getString("inventory_commit_id"),
        rs.getString("reject_reason"),
        rs.getTimestamp("requested_at").toInstant(),
        instant(rs, "handed_over_at"));
  }

  private static @Nullable Instant instant(ResultSet rs, String col) throws SQLException {
    Timestamp t = rs.getTimestamp(col);
    return t == null ? null : t.toInstant();
  }
}
