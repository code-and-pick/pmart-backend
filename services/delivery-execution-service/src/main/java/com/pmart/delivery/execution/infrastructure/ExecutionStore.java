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
      @Nullable String failureJson,
      @Nullable String purpose,
      @Nullable String caseId,
      @Nullable String originDeliveryId,
      @Nullable String redeliveryDestinationId,
      @Nullable String claimId,
      @Nullable String returnLinesJson,
      @Nullable String orderNumber,
      @Nullable String mfcName,
      @Nullable String mfcPhone) {

    /** 고객 반품 회수(CL-1, 2026-11-17) — 고객 집에서 받아 MFC 로. */
    public boolean claimReturn() {
      return "RETURN".equals(type) && "CLAIM_RETURN".equals(purpose);
    }

    /** 오배송 고객 잘못 다시 배송(ADR-0065) — 잘못 둔 곳에서 받아 실제 집으로. */
    public boolean redelivery() {
      return "MISDELIVERY_REDELIVERY".equals(purpose);
    }

    /** 확인된 오배송 회수 배송(DD-2). */
    public boolean recovery() {
      return "RETURN".equals(type) && "MISDELIVERY_RECOVERY".equals(purpose);
    }

    /** 전달 실패 반납(2026-11-17) — 확정된 전달 실패 뒤 상품을 든 원래 라이더가 MFC 로. 받음 단계 없음. */
    public boolean failedReturn() {
      return "RETURN".equals(type) && "FAILED_DELIVERY_RETURN".equals(purpose);
    }
  }

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
      Instant createdAt,
      @Nullable Instant resolvedAt,
      @Nullable String resolvedBy,
      @Nullable String resolutionNote) {

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
          + " created_at, accepted_fee, vehicle_type, distance_m, failure::text, purpose, case_id,"
          + " origin_delivery_id, redelivery_destination_id, claim_id, return_lines::text,"
          + " order_number, mfc_name, mfc_phone";

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

  /** DeliveryCreated 의 유형·목적(회수 배송 — DD-2). 배정 사건이 먼저 와 FORWARD 로 만들어졌어도 바로잡는다. */
  /** 주문번호를 아직 모를 때만 채운다(2026-11-18 — 배송 생성 때 order 에서). */
  public void fillOrderNumber(String deliveryId, String orderNumber) {
    jdbc.update(
        "UPDATE execution_delivery SET order_number = ? WHERE delivery_id = ? AND order_number IS"
            + " NULL",
        orderNumber,
        deliveryId);
  }

  public void describe(
      String deliveryId,
      @Nullable String type,
      @Nullable String purpose,
      @Nullable String caseId,
      @Nullable String originDeliveryId,
      @Nullable String redeliveryDestinationId,
      @Nullable String claimId,
      @Nullable String returnLinesJson) {
    jdbc.update(
        "UPDATE execution_delivery SET type = COALESCE(?, type), purpose = COALESCE(?, purpose),"
            + " case_id = COALESCE(?, case_id), origin_delivery_id = COALESCE(?, origin_delivery_id),"
            + " redelivery_destination_id = COALESCE(?, redelivery_destination_id), claim_id ="
            + " COALESCE(?, claim_id), return_lines = COALESCE(?::jsonb, return_lines) WHERE"
            + " delivery_id = ?",
        type,
        purpose,
        caseId,
        originDeliveryId,
        redeliveryDestinationId,
        claimId,
        returnLinesJson,
        deliveryId);
  }

  /** 이 배송(원래 배송)을 지금 회수 중인 라이더들(오배송 회수 — ASSIGNED·RETURN_PICKED_UP). 사진 보기 권한. */
  public List<String> recoveringRiders(String originDeliveryId) {
    return jdbc.queryForList(
        "SELECT rider_id FROM execution_delivery WHERE origin_delivery_id = ? AND ((purpose ="
            + " 'MISDELIVERY_RECOVERY' AND state IN ('ASSIGNED', 'RETURN_PICKED_UP')) OR (purpose ="
            + " 'MISDELIVERY_REDELIVERY' AND state IN ('ASSIGNED', 'PICKED_UP',"
            + " 'ARRIVED_AT_CUSTOMER'))) AND rider_id IS NOT NULL",
        String.class,
        originDeliveryId);
  }

  /** 회수지에서 받음(DD-2) — RETURN_PICKED_UP. 증빙은 evidence 의 pickup 에. */
  public void returnPickedUp(String deliveryId, String evidenceJson, Instant at) {
    returnPickedUp(deliveryId, "RETURN_PICKED_UP", evidenceJson, at);
  }

  /** 잘못 둔 곳에서 받음 — 회수는 RETURN_PICKED_UP, 다시 배송은 PICKED_UP(그 뒤 고객 도착·전달 완료). */
  public void returnPickedUp(String deliveryId, String state, String evidenceJson, Instant at) {
    jdbc.update(
        "UPDATE execution_delivery SET state = ?, picked_up_at = ?, evidence ="
            + " ?::jsonb, version = version + 1, updated_at = ? WHERE delivery_id = ?",
        state,
        ts(at),
        evidenceJson,
        ts(at),
        deliveryId);
  }

  /** 회수 반납(DD-2) — RETURNED_TO_STORE, 배송 끝. 증빙(있으면 사진 참조·점원 확인 참조)은 evidence 에. */
  public void returnedToStore(String deliveryId, String evidenceJson, Instant at) {
    jdbc.update(
        "UPDATE execution_delivery SET state = 'RETURNED_TO_STORE', evidence = ?::jsonb,"
            + " completed_at = ?, version = version + 1, updated_at = ? WHERE delivery_id = ?",
        evidenceJson,
        ts(at),
        ts(at),
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

  /** 자동 전달 완료 후보(2026-11-17) — 픽업 뒤 그 시각 전, 아직 전달 전(실패 등록 중 아님). */
  public List<String> autoCompleteCandidates(Instant pickedBefore, int limit) {
    return jdbc.query(
        "SELECT delivery_id FROM execution_delivery WHERE state IN ('PICKED_UP',"
            + " 'ARRIVED_AT_CUSTOMER') AND type <> 'RETURN' AND picked_up_at < ? ORDER BY"
            + " picked_up_at LIMIT ?",
        (rs, i) -> rs.getString(1),
        ts(pickedBefore),
        limit);
  }

  /** 운영자 상태 정정 기록(V16, 2026-11-17). */
  public void insertCorrection(
      String correctionId,
      String deliveryId,
      String from,
      String to,
      String reason,
      String evidenceJson,
      String by,
      Instant at) {
    jdbc.update(
        "INSERT INTO delivery_status_correction (correction_id, delivery_id, from_state, to_state,"
            + " reason, evidence_refs, corrected_by, corrected_at) VALUES (?,?,?,?,?,?::jsonb,?,?)",
        correctionId,
        deliveryId,
        from,
        to,
        reason,
        evidenceJson,
        by,
        ts(at));
  }

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

  public Optional<PhotoRow> photo(String photoRef) {
    return jdbc
        .query(
            "SELECT photo_ref, delivery_id, rider_id, object_key, content_type, size_bytes,"
                + " uploaded_at FROM evidence_photo WHERE photo_ref = ?",
            (rs, i) ->
                new PhotoRow(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getString(5),
                    rs.getInt(6),
                    rs.getTimestamp(7).toInstant()),
            photoRef)
        .stream()
        .findFirst();
  }

  // ---- 올리는 중 사진(V5 — 실패한 요청의 객체는 orphan-photo-after 뒤 PhotoSweeper 가 지운다)

  /** 저장소에 올리기 전에(트랜잭션 밖, 바로 커밋). 같은 키 재시도면 시작 시각을 늦춘다. */
  public void notePhotoUpload(String objectKey, String deliveryId, Instant now) {
    jdbc.update(
        "INSERT INTO photo_upload (object_key, delivery_id, started_at) VALUES (?, ?, ?) ON"
            + " CONFLICT (object_key) DO UPDATE SET started_at = EXCLUDED.started_at",
        objectKey,
        deliveryId,
        ts(now));
  }

  /** 참조를 저장했거나(같은 트랜잭션) 객체를 지웠으면 기록을 지운다. */
  public void forgetPhotoUpload(String objectKey) {
    jdbc.update("DELETE FROM photo_upload WHERE object_key = ?", objectKey);
  }

  public List<String> stalePhotoUploads(Instant before, int limit) {
    return jdbc.queryForList(
        "SELECT object_key FROM photo_upload WHERE started_at < ? ORDER BY started_at LIMIT ?",
        String.class,
        ts(before),
        limit);
  }

  /** 아직 before 전에 시작한 기록이면 잠근다 — 그 사이 같은 요청이 다시 올리기 시작했으면(시작 시각 갱신) false. */
  public boolean lockStalePhotoUpload(String objectKey, Instant before) {
    return !jdbc.queryForList(
            "SELECT object_key FROM photo_upload WHERE object_key = ? AND started_at < ? FOR UPDATE"
                + " SKIP LOCKED",
            String.class,
            objectKey,
            ts(before))
        .isEmpty();
  }

  public boolean photoObjectReferenced(String objectKey) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM evidence_photo WHERE object_key = ?", Integer.class, objectKey);
    return n != null && n > 0;
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
      @Nullable Integer distanceM,
      @Nullable String orderNumber,
      @Nullable String mfcName,
      @Nullable String mfcPhone) {
    jdbc.update(
        "UPDATE execution_delivery SET accepted_fee = ?, vehicle_type = ?, distance_m = ?,"
            + " order_number = COALESCE(?, order_number), mfc_name = COALESCE(?, mfc_name),"
            + " mfc_phone = COALESCE(?, mfc_phone) WHERE delivery_id = ?",
        fee,
        vehicleType,
        distanceM,
        orderNumber,
        mfcName,
        mfcPhone,
        deliveryId);
  }

  /**
   * 운행시간 구간(2026-11-17 사용자 "배차 수락하고 전달완료까지"): 이 라이더가 끝낸 배송(전달 완료·회수 반납)의 [수락, 완료] 중 [from, to) 에
   * 걸치는 것.
   */
  public List<com.pmart.delivery.execution.domain.OperatingTime.Span> riderOperatingSpans(
      String riderId, Instant from, Instant to) {
    return jdbc.query(
        "SELECT assigned_at, completed_at FROM execution_delivery WHERE rider_id = ? AND state IN"
            + " ('COMPLETED', 'RETURNED_TO_STORE') AND assigned_at IS NOT NULL AND completed_at IS NOT"
            + " NULL AND completed_at > ? AND assigned_at < ?",
        (rs, i) ->
            new com.pmart.delivery.execution.domain.OperatingTime.Span(
                rs.getTimestamp(1).toInstant(), rs.getTimestamp(2).toInstant()),
        riderId,
        ts(from),
        ts(to));
  }

  /** 실패 등록·결정 기록과 상태를 함께 바꾼다. */
  /** 이 배송의 전달 실패 반납 배송(dispatch 가 만든 뒤 DeliveryCreated 가 와야 있다). */
  public Optional<String> failedReturnOf(String originDeliveryId) {
    return jdbc
        .query(
            "SELECT delivery_id FROM execution_delivery WHERE origin_delivery_id = ? AND purpose ="
                + " 'FAILED_DELIVERY_RETURN' LIMIT 1",
            (rs, i) -> rs.getString(1),
            originDeliveryId)
        .stream()
        .findFirst();
  }

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

  public Optional<HelpRow> lockHelp(String deliveryId, String requestId) {
    return jdbc
        .query(
            HELP_SELECT + " WHERE delivery_id = ? AND request_id = ? FOR UPDATE",
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

  /** 사건함이 이 도움요청으로 사건을 열었다(DE-CASE) — 사건 번호, 열림이면 처리 중. 다른 사건이 이미 붙어 있으면 바꾸지 않는다. */
  public int linkHelpCase(String deliveryId, String requestId, String caseId) {
    // DE-2(2026-11-16): 열릴 때는 사건 번호만 — 처리 중은 상담원이 집을 때(CaseAssigned)
    return jdbc.update(
        "UPDATE help_request SET case_id = ? WHERE delivery_id = ? AND request_id = ? AND (case_id"
            + " IS NULL OR case_id = ?)",
        caseId,
        deliveryId,
        requestId,
        caseId);
  }

  /** 사건함이 사건을 닫았다(DE-CASE) — 그 사건의 도움요청을 닫는다. 처리자 = {@code case:<caseId>}, 메모 = 결과. */
  public int closeHelpByCase(String caseId, @Nullable String outcome, Instant closedAt) {
    return jdbc.update(
        "UPDATE help_request SET state = 'RESOLVED', resolved_at = ?, resolved_by = ?,"
            + " resolution_note = ? WHERE case_id = ? AND state <> 'RESOLVED'",
        ts(closedAt),
        "case:" + caseId,
        outcome == null ? "사건함에서 닫음" : "사건함에서 닫음(" + outcome + ")",
        caseId);
  }

  /** 사건함이 닫았던 사건을 다시 열었다(DE-CASE) — 사건함이 닫은 도움요청만 다시 접수됨(담당 없음 — DE-2: 집으면 처리 중). */
  public int reopenHelpByCase(String caseId) {
    return jdbc.update(
        "UPDATE help_request SET state = 'OPEN', resolved_at = NULL, resolved_by = NULL,"
            + " resolution_note = NULL WHERE case_id = ? AND state = 'RESOLVED' AND resolved_by = ?",
        caseId,
        "case:" + caseId);
  }

  /** 상담원이 접수 건을 집었다(DE-2) — 접수됨 → 처리 중. */
  public int assignHelpByCase(String caseId) {
    return jdbc.update(
        "UPDATE help_request SET state = 'IN_PROGRESS' WHERE case_id = ? AND state = 'OPEN'",
        caseId);
  }

  /** 접수 건이 다른 팀으로 넘어갔다(DE-2) — 지금 팀 기록, 담당이 비므로 처리 중 → 접수됨. */
  public int transferHelpByCase(String caseId, String toGroup) {
    return jdbc.update(
        "UPDATE help_request SET case_group = ?, state = CASE WHEN state = 'IN_PROGRESS' THEN 'OPEN'"
            + " ELSE state END WHERE case_id = ?",
        toGroup,
        caseId);
  }

  /** 도움요청의 지금 팀(DE-2). 없으면 null(= 지원센터). */
  public @Nullable String helpGroup(String deliveryId, String requestId) {
    List<String> r =
        jdbc.queryForList(
            "SELECT case_group FROM help_request WHERE delivery_id = ? AND request_id = ?",
            String.class,
            deliveryId,
            requestId);
    return r.isEmpty() ? null : r.get(0);
  }

  public boolean helpMessageBelongs(String messageId, String deliveryId, String requestId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM help_message WHERE message_id = ? AND delivery_id = ? AND"
                + " request_id = ?",
            Integer.class,
            messageId,
            deliveryId,
            requestId);
    return n != null && n > 0;
  }

  /** 대화의 마지막 메시지 시각(DE-2) — 라이더·지원센터 따로. */
  public record MessageTimes(@Nullable Instant lastRider, @Nullable Instant lastSupport) {}

  public MessageTimes messageTimes(String deliveryId, String requestId) {
    return jdbc.query(
        "SELECT max(created_at) FILTER (WHERE sender_type = 'RIDER'), max(created_at) FILTER (WHERE"
            + " sender_type = 'SUPPORT') FROM help_message WHERE delivery_id = ? AND request_id = ?",
        rs -> {
          rs.next();
          Timestamp a = rs.getTimestamp(1);
          Timestamp b = rs.getTimestamp(2);
          return new MessageTimes(
              a == null ? null : a.toInstant(), b == null ? null : b.toInstant());
        },
        deliveryId,
        requestId);
  }

  /** 메시지 가림(DE-2) — 글을 지우고 누가·언제·왜를 남긴다. 이미 가렸으면 그대로(0). */
  public int maskHelpMessage(String messageId, String by, String reason, Instant at) {
    return jdbc.update(
        "UPDATE help_message SET body = NULL, masked_at = ?, masked_by = ?, mask_reason = ? WHERE"
            + " message_id = ? AND masked_at IS NULL",
        ts(at),
        by,
        reason,
        messageId);
  }

  /** 도움요청 대화 한 줄(0.6.0). */
  public record HelpMessageRow(
      long seq,
      String messageId,
      String senderType,
      String senderId,
      @Nullable String body,
      String photoRefsJson,
      Instant createdAt,
      @Nullable Instant maskedAt,
      @Nullable String maskedBy,
      @Nullable String maskReason) {}

  public HelpMessageRow insertHelpMessage(
      String messageId,
      String deliveryId,
      String requestId,
      String senderType,
      String senderId,
      String body,
      String photoRefsJson,
      Instant now) {
    Long seq =
        jdbc.queryForObject(
            "INSERT INTO help_message (message_id, delivery_id, request_id, sender_type, sender_id,"
                + " body, photo_refs, created_at) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?) RETURNING seq",
            Long.class,
            messageId,
            deliveryId,
            requestId,
            senderType,
            senderId,
            body,
            photoRefsJson,
            ts(now));
    return new HelpMessageRow(
        seq == null ? 0 : seq,
        messageId,
        senderType,
        senderId,
        body,
        photoRefsJson,
        now,
        null,
        null,
        null);
  }

  public List<HelpMessageRow> helpMessages(String deliveryId, String requestId, long after) {
    return jdbc.query(
        "SELECT seq, message_id, sender_type, sender_id, body, photo_refs::text, created_at,"
            + " masked_at, masked_by, mask_reason FROM"
            + " help_message WHERE delivery_id = ? AND request_id = ? AND seq > ? ORDER BY seq LIMIT"
            + " 200",
        (rs, n) ->
            new HelpMessageRow(
                rs.getLong(1),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getString(5),
                rs.getString(6),
                rs.getTimestamp(7).toInstant(),
                rs.getTimestamp(8) == null ? null : rs.getTimestamp(8).toInstant(),
                rs.getString(9),
                rs.getString(10)),
        deliveryId,
        requestId,
        after);
  }

  public Optional<HelpMessageRow> helpMessage(String messageId) {
    return jdbc
        .query(
            "SELECT seq, message_id, sender_type, sender_id, body, photo_refs::text, created_at,"
                + " masked_at, masked_by, mask_reason FROM"
                + " help_message WHERE message_id = ?",
            (rs, n) ->
                new HelpMessageRow(
                    rs.getLong(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getString(5),
                    rs.getString(6),
                    rs.getTimestamp(7).toInstant(),
                    rs.getTimestamp(8) == null ? null : rs.getTimestamp(8).toInstant(),
                    rs.getString(9),
                    rs.getString(10)),
            messageId)
        .stream()
        .findFirst();
  }

  /** 이 배송에 이 라이더가 올린 사진 참조 수(대화에 붙일 수 있는지). */
  public int ownedPhotos(String deliveryId, String riderId, List<String> refs) {
    if (refs.isEmpty()) {
      return 0;
    }
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM evidence_photo WHERE delivery_id = ? AND rider_id = ? AND photo_ref"
                + " = ANY (?)",
            Integer.class,
            deliveryId,
            riderId,
            refs.toArray(String[]::new));
    return n == null ? 0 : n;
  }

  /** 도움요청 처리함으로 닫기(0.4.0 — 사건함 전까지). 이미 닫혔으면 false. */
  public boolean resolveHelp(
      String deliveryId, String requestId, String by, @Nullable String note, Instant now) {
    return jdbc.update(
            "UPDATE help_request SET state = 'RESOLVED', resolved_at = ?, resolved_by = ?,"
                + " resolution_note = ? WHERE delivery_id = ? AND request_id = ?"
                + " AND state <> 'RESOLVED'",
            ts(now),
            by,
            note,
            deliveryId,
            requestId)
        > 0;
  }

  private static String helpColumns(String p) {
    return p
        + "request_id, "
        + p
        + "delivery_id, "
        + p
        + "rider_id, "
        + p
        + "category, "
        + p
        + "detail, "
        + p
        + "evidence_refs::text, "
        + p
        + "stage, "
        + p
        + "state, "
        + p
        + "case_id, "
        + p
        + "created_at, "
        + p
        + "resolved_at, "
        + p
        + "resolved_by, "
        + p
        + "resolution_note";
  }

  private static final String HELP_SELECT = "SELECT " + helpColumns("") + " FROM help_request";

  private HelpRow mapHelp(ResultSet rs, int i) throws SQLException {
    Timestamp resolved = rs.getTimestamp(11);
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
        rs.getTimestamp(10).toInstant(),
        resolved == null ? null : resolved.toInstant(),
        rs.getString(12),
        rs.getString(13));
  }

  // ---- 직원 목록(0.4.0 — 백오피스 고객센터·라이더 운영, BOD-4·5). 개인정보 없음(ID·상태·시각)

  /** 직원 배송 찾기 조건. 비어 있는 값은 거르지 않는다. */
  public record DeliveryFilter(
      @Nullable String orderId,
      @Nullable String orderNumber,
      @Nullable String riderId,
      @Nullable String mfcId,
      @Nullable String state,
      @Nullable String type,
      boolean oldestFirst) {}

  public List<DeliveryRow> staffDeliveries(DeliveryFilter f, int limit, int offset) {
    StringBuilder sql =
        new StringBuilder("SELECT " + DELIVERY_COLUMNS + " FROM execution_delivery WHERE TRUE");
    List<Object> args = new java.util.ArrayList<>();
    eq(sql, args, "order_id", f.orderId());
    eq(sql, args, "order_number", f.orderNumber());
    eq(sql, args, "rider_id", f.riderId());
    eq(sql, args, "mfc_id", f.mfcId());
    eq(sql, args, "state", f.state());
    eq(sql, args, "type", f.type());
    sql.append(
        f.oldestFirst()
            ? " ORDER BY created_at, delivery_id"
            : " ORDER BY created_at DESC, delivery_id DESC");
    sql.append(" LIMIT ? OFFSET ?");
    args.add(limit);
    args.add(offset);
    return jdbc.query(sql.toString(), this::mapDelivery, args.toArray());
  }

  /** 도움요청 대기 목록 조건(접수 시각 from 포함·to 미포함). */
  public record HelpFilter(
      String state,
      @Nullable String category,
      @Nullable String mfcId,
      @Nullable String riderId,
      @Nullable Instant from,
      @Nullable Instant to) {}

  /** 대기 목록 한 줄 — 배송의 주문·센터를 붙인다. */
  public record StaffHelpRow(
      HelpRow help, @Nullable String orderId, @Nullable String mfcId, int evidenceCount) {}

  /** 도움요청 대기 목록(오래된 것 먼저). */
  public List<StaffHelpRow> staffHelps(HelpFilter f, int limit, int offset) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT "
                + helpColumns("h.")
                + ", d.order_id, d.mfc_id, jsonb_array_length(h.evidence_refs)"
                + " FROM help_request h LEFT JOIN execution_delivery d"
                + " ON d.delivery_id = h.delivery_id WHERE h.state = ?");
    List<Object> args = new java.util.ArrayList<>();
    args.add(f.state());
    eq(sql, args, "h.category", f.category());
    eq(sql, args, "d.mfc_id", f.mfcId());
    eq(sql, args, "h.rider_id", f.riderId());
    if (f.from() != null) {
      sql.append(" AND h.created_at >= ?");
      args.add(ts(f.from()));
    }
    if (f.to() != null) {
      sql.append(" AND h.created_at < ?");
      args.add(ts(f.to()));
    }
    sql.append(" ORDER BY h.created_at, h.delivery_id, h.request_id LIMIT ? OFFSET ?");
    args.add(limit);
    args.add(offset);
    return jdbc.query(
        sql.toString(),
        (rs, i) ->
            new StaffHelpRow(mapHelp(rs, i), rs.getString(14), rs.getString(15), rs.getInt(16)),
        args.toArray());
  }

  private static void eq(
      StringBuilder sql, List<Object> args, String column, @Nullable String value) {
    if (value != null) {
      sql.append(" AND ").append(column).append(" = ?");
      args.add(value);
    }
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

  /**
   * 라이더 귀책 오배송(ADR-0065) — 지급 전이면 ADJUSTED·산정 제외(calculable false)로, 지급 중·지급됐으면 상태는 그대로 두고 메모만(지원센터
   * 조정). 바꾼 행 수.
   */
  public int withholdEarning(String earningId, boolean beforePayout, String note, Instant now) {
    return beforePayout
        ? jdbc.update(
            "UPDATE rider_earning SET settlement_state = 'ADJUSTED', calculable = false, note = ?,"
                + " updated_at = ? WHERE earning_id = ? AND settlement_state NOT IN ('PAYING',"
                + " 'PAID', 'ADJUSTED')",
            note,
            ts(now),
            earningId)
        : jdbc.update(
            "UPDATE rider_earning SET note = ?, updated_at = ? WHERE earning_id = ?",
            note,
            ts(now),
            earningId);
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
        rs.getString(19),
        rs.getString(20),
        rs.getString(21),
        rs.getString(22),
        rs.getString(23),
        rs.getString(24),
        rs.getString(25),
        rs.getString(26),
        rs.getString(27),
        rs.getString(28));
  }

  private static @Nullable Instant instant(ResultSet rs, int col) throws SQLException {
    Timestamp t = rs.getTimestamp(col);
    return t == null ? null : t.toInstant();
  }

  private static Timestamp ts(Instant i) {
    return Timestamp.from(i);
  }

  /** 라이더 탈퇴를 막는 것의 수(2026-11-17). */
  public record OpenItems(
      int activeDeliveries, int unconfirmedEarnings, int adjustmentPending, int openHelpRequests) {}

  public OpenItems riderOpenItems(String riderId) {
    return jdbc.queryForObject(
        "SELECT (SELECT count(*) FROM execution_delivery WHERE rider_id = ? AND state NOT IN"
            + " ('COMPLETED', 'FAILED', 'CANCELLED', 'RETURNED_TO_STORE', 'UNASSIGNED')),"
            + " (SELECT count(*) FROM rider_earning WHERE rider_id = ? AND calculable AND"
            + " settlement_state IN ('ESTIMATED', 'PENDING_CONFIRMATION')),"
            + " (SELECT count(*) FROM rider_earning WHERE rider_id = ? AND NOT calculable AND"
            + " settlement_state NOT IN ('ADJUSTED', 'PAYING', 'PAID')),"
            + " (SELECT count(*) FROM help_request WHERE rider_id = ? AND state IN ('OPEN',"
            + " 'IN_PROGRESS'))",
        (rs, i) -> new OpenItems(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)),
        riderId,
        riderId,
        riderId,
        riderId);
  }

  // ---------- 산정 불가 수행료 조정(2026-11-17) ----------

  /** 조정할 수 있는 산정 불가 수행료 — 조정 전(ADJUSTED·지급 중·지급 아님). */
  static final String NEEDS_ADJUSTMENT =
      " NOT calculable AND settlement_state NOT IN ('ADJUSTED', 'PAYING', 'PAID')";

  public List<EarningRow> earningsNeedingAdjustment(
      @Nullable String riderId, int limit, int offset) {
    List<Object> args = new java.util.ArrayList<>();
    StringBuilder sql = new StringBuilder(EARNING_SELECT + " WHERE" + NEEDS_ADJUSTMENT);
    if (riderId != null) {
      sql.append(" AND rider_id = ?");
      args.add(riderId);
    }
    sql.append(" ORDER BY recorded_at, earning_id LIMIT ? OFFSET ?");
    args.add(limit);
    args.add(offset);
    return jdbc.query(sql.toString(), this::mapEarning, args.toArray());
  }

  public Optional<EarningRow> lockEarning(String earningId) {
    return jdbc
        .query(EARNING_SELECT + " WHERE earning_id = ? FOR UPDATE", this::mapEarning, earningId)
        .stream()
        .findFirst();
  }

  /** 조정 반영(조건 — 아직 조정 전일 때만). 바뀐 줄 수. */
  public int applyAdjustment(
      String earningId,
      boolean calculable,
      @Nullable Integer fee,
      String state,
      @Nullable Instant confirmAt,
      java.time.@Nullable LocalDate payoutDate,
      String note,
      Instant at) {
    return jdbc.update(
        "UPDATE rider_earning SET calculable = ?, base_fee = ?, total = ?, settlement_state = ?,"
            + " confirm_at = ?, payout_date = ?, note = ?, updated_at = ? WHERE earning_id = ? AND"
            + NEEDS_ADJUSTMENT,
        calculable,
        fee,
        fee,
        state,
        confirmAt == null ? null : ts(confirmAt),
        payoutDate == null ? null : java.sql.Date.valueOf(payoutDate),
        note,
        ts(at),
        earningId);
  }

  public void insertAdjustment(
      String adjustmentId,
      EarningRow e,
      String fault,
      String decision,
      int amount,
      String reason,
      String adjustedBy,
      Instant at) {
    jdbc.update(
        "INSERT INTO earning_adjustment (adjustment_id, earning_id, delivery_id, rider_id, fault,"
            + " decision, amount, reason, adjusted_by, adjusted_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
        adjustmentId,
        e.earningId(),
        e.deliveryId(),
        e.riderId(),
        fault,
        decision,
        amount,
        reason,
        adjustedBy,
        ts(at));
  }
}
