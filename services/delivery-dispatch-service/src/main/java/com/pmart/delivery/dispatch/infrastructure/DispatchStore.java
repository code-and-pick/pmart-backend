package com.pmart.delivery.dispatch.infrastructure;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 배송·배차 시도·제안·연속 거절 저장(JDBC, V2__deliveries_dispatch.sql). */
@Repository
public class DispatchStore {

  public record DeliveryRow(
      String deliveryId,
      int version,
      String type,
      String orderId,
      String shipmentId,
      String mfcId,
      String state,
      @Nullable String deliveryMode,
      @Nullable LocalDate arrivalDate,
      @Nullable String arrivalStart,
      @Nullable String arrivalEnd,
      @Nullable String requestTrigger,
      @Nullable Instant estimatedReadyAt,
      @Nullable Instant readyAt,
      String packagesJson,
      @Nullable Double mfcLat,
      @Nullable Double mfcLng,
      @Nullable Double dropLat,
      @Nullable Double dropLng,
      @Nullable String dropAreaLabel,
      @Nullable Integer dropDistanceM,
      @Nullable Integer dropDurationS,
      @Nullable String geoError,
      @Nullable String riderId,
      @Nullable String assignmentId,
      int assignmentVersion,
      @Nullable Instant assignedAt,
      @Nullable String acceptedOfferId,
      @Nullable Instant pickedUpAt,
      String handedOverJson,
      @Nullable Instant cancelledAt,
      @Nullable String cancelReason,
      Instant createdAt,
      Instant updatedAt,
      @Nullable String bundleId,
      @Nullable Integer stopSeq,
      @Nullable Instant delayAlertedAt,
      @Nullable Instant promisedAt) {

    public boolean geoReady() {
      return mfcLat != null && dropLat != null && dropDistanceM != null;
    }

    /** 출하 대기(실제 → 예상 → 없음). */
    public @Nullable Instant readyOrEstimate() {
      return readyAt != null ? readyAt : estimatedReadyAt;
    }
  }

  public record AttemptRow(
      String attemptId,
      String deliveryId,
      String trigger,
      String state,
      int stage,
      Instant stageStartedAt,
      int retryCount,
      int round,
      Instant nextRunAt,
      Instant startedAt,
      @Nullable Instant finishedAt,
      @Nullable String failureReason,
      boolean hadCandidate,
      boolean hadRejection,
      boolean hadExpiry,
      @Nullable String excludedRiderId) {}

  public record OfferRow(
      String offerId,
      String attemptId,
      String deliveryId,
      String riderId,
      int round,
      String source,
      double radiusKm,
      String state,
      Instant sentAt,
      Instant expiresAt,
      @Nullable Instant viewedAt,
      @Nullable Instant decidedAt,
      @Nullable String rejectReason,
      @Nullable String lateReason,
      @Nullable Integer pickupDistanceM,
      @Nullable Integer pickupDurationS,
      int fee,
      @Nullable String vehicleType,
      @Nullable String bundleId,
      int bundleSize,
      int stopSeq) {}

  private final JdbcTemplate jdbc;

  public DispatchStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  // ---------- 배송 ----------

  public Optional<DeliveryRow> delivery(String deliveryId) {
    return first(
        "SELECT * FROM delivery WHERE delivery_id = ?", DispatchStore::delivery, deliveryId);
  }

  public Optional<DeliveryRow> lockDelivery(String deliveryId) {
    return first(
        "SELECT * FROM delivery WHERE delivery_id = ? FOR UPDATE",
        DispatchStore::delivery,
        deliveryId);
  }

  public Optional<DeliveryRow> lockByShipment(String shipmentId) {
    return first(
        "SELECT * FROM delivery WHERE shipment_id = ? FOR UPDATE",
        DispatchStore::delivery,
        shipmentId);
  }

  public List<DeliveryRow> lockByOrder(String orderId) {
    return jdbc.query(
        "SELECT * FROM delivery WHERE order_id = ? ORDER BY created_at FOR UPDATE",
        (rs, i) -> delivery(rs),
        orderId);
  }

  /**
   * 같은 주문의 배차 요청(배송 만들기)과 작업 중지·주문 취소를 줄 세운다(PC 1 검토 후속, 2026-11-14). 없으면 배차 요청이 "멈춘 주문 아님"을 본 뒤 아직
   * 커밋 안 된 배송을 중지 처리가 못 보고 지나가, 둘 다 커밋된 뒤 멈춘 주문이 배차된다. 잡는 순서는 늘 주문 → 출고.
   */
  public void lockOrderKey(String orderId) {
    jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))", "dispatch-order:" + orderId);
  }

  /** 출고 하나에 배송 하나 — 같은 출고는 줄을 세운 뒤 다시 본다. */
  public void lockShipmentKey(String shipmentId) {
    jdbc.queryForList(
        "SELECT pg_advisory_xact_lock(hashtext(?))", "dispatch-shipment:" + shipmentId);
  }

  public void insertDelivery(
      String deliveryId,
      String orderId,
      String shipmentId,
      String mfcId,
      @Nullable String trigger,
      @Nullable Instant estimatedReadyAt,
      @Nullable Instant readyAt,
      String packagesJson,
      @Nullable String deliveryMode,
      String state,
      Instant now) {
    jdbc.update(
        "INSERT INTO delivery (delivery_id, version, type, order_id, shipment_id, mfc_id, state,"
            + " request_trigger, estimated_ready_at, ready_at, packages, delivery_mode, created_at,"
            + " updated_at) VALUES (?, 1, 'FORWARD', ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)",
        deliveryId,
        orderId,
        shipmentId,
        mfcId,
        state,
        trigger,
        ts(estimatedReadyAt),
        ts(readyAt),
        packagesJson,
        deliveryMode,
        ts(now),
        ts(now));
  }

  public void updateRequest(
      String deliveryId,
      @Nullable String trigger,
      @Nullable Instant estimatedReadyAt,
      Instant now) {
    jdbc.update(
        "UPDATE delivery SET request_trigger = COALESCE(request_trigger, ?),"
            + " estimated_ready_at = COALESCE(estimated_ready_at, ?), updated_at = ? WHERE delivery_id = ?",
        trigger,
        ts(estimatedReadyAt),
        ts(now),
        deliveryId);
  }

  public void updateReady(
      String deliveryId,
      @Nullable Instant readyAt,
      String packagesJson,
      @Nullable String deliveryMode,
      Instant now) {
    jdbc.update(
        "UPDATE delivery SET ready_at = COALESCE(?, ready_at), packages = ?::jsonb,"
            + " delivery_mode = COALESCE(?, delivery_mode), updated_at = ? WHERE delivery_id = ?",
        ts(readyAt),
        packagesJson,
        deliveryMode,
        ts(now),
        deliveryId);
  }

  public void updatePackages(String deliveryId, String packagesJson, Instant now) {
    jdbc.update(
        "UPDATE delivery SET packages = ?::jsonb, updated_at = ? WHERE delivery_id = ?",
        packagesJson,
        ts(now),
        deliveryId);
  }

  public void saveGeo(
      String deliveryId,
      double mfcLat,
      double mfcLng,
      double dropLat,
      double dropLng,
      @Nullable String label,
      int dropDistanceM,
      int dropDurationS,
      @Nullable String deliveryMode,
      @Nullable LocalDate arrivalDate,
      @Nullable String arrivalStart,
      @Nullable String arrivalEnd,
      Instant now) {
    jdbc.update(
        "UPDATE delivery SET mfc_lat = ?, mfc_lng = ?, drop_lat = ?, drop_lng = ?, drop_area_label = ?,"
            + " drop_distance_m = ?, drop_duration_s = ?, delivery_mode = COALESCE(?, delivery_mode),"
            + " arrival_date = ?, arrival_start = ?, arrival_end = ?, geo_error = NULL, updated_at = ?"
            + " WHERE delivery_id = ?",
        mfcLat,
        mfcLng,
        dropLat,
        dropLng,
        label,
        dropDistanceM,
        dropDurationS,
        deliveryMode,
        arrivalDate == null ? null : Date.valueOf(arrivalDate),
        arrivalStart,
        arrivalEnd,
        ts(now),
        deliveryId);
  }

  public void setPromise(String deliveryId, @Nullable Instant promisedAt) {
    jdbc.update(
        "UPDATE delivery SET promised_at = ? WHERE delivery_id = ?", ts(promisedAt), deliveryId);
  }

  public void saveGeoError(String deliveryId, String error, Instant now) {
    jdbc.update(
        "UPDATE delivery SET geo_error = ?, updated_at = ? WHERE delivery_id = ?",
        error,
        ts(now),
        deliveryId);
  }

  public void assign(
      String deliveryId,
      String riderId,
      String assignmentId,
      int assignmentVersion,
      String offerId,
      Instant now) {
    jdbc.update(
        "UPDATE delivery SET state = 'ASSIGNED', rider_id = ?, assignment_id = ?, assignment_version = ?,"
            + " assigned_at = ?, accepted_offer_id = ?, version = version + 1, updated_at = ?"
            + " WHERE delivery_id = ?",
        riderId,
        assignmentId,
        assignmentVersion,
        ts(now),
        offerId,
        ts(now),
        deliveryId);
  }

  public void setState(
      String deliveryId, String state, @Nullable String cancelReason, Instant now) {
    jdbc.update(
        "UPDATE delivery SET state = ?, cancel_reason = COALESCE(?, cancel_reason),"
            + " cancelled_at = CASE WHEN ? IN ('CANCELLED_BEFORE_PICKUP', 'BLOCKED') THEN ? ELSE"
            + " cancelled_at END, version = version + 1, updated_at = ? WHERE delivery_id = ?",
        state,
        cancelReason,
        state,
        ts(now),
        ts(now),
        deliveryId);
  }

  public void recordHandover(
      String deliveryId, String handedOverJson, boolean pickedUp, Instant now) {
    jdbc.update(
        "UPDATE delivery SET handed_over_packages = ?::jsonb,"
            + " state = CASE WHEN ? THEN 'PICKED_UP' ELSE state END,"
            + " picked_up_at = CASE WHEN ? THEN ? ELSE picked_up_at END,"
            + " version = version + CASE WHEN ? THEN 1 ELSE 0 END, updated_at = ? WHERE delivery_id = ?",
        handedOverJson,
        pickedUp,
        pickedUp,
        ts(now),
        pickedUp,
        ts(now),
        deliveryId);
  }

  /** 라이더가 지금 맡고 있는 배송(배정 ~ 픽업 전). 픽업 뒤 수행 상태는 execution 이 정본이라 여기서는 픽업까지만 센다. */
  public int activeDeliveries(String riderId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM delivery WHERE rider_id = ? AND state IN ('ASSIGNED', 'PICKED_UP')",
            Integer.class,
            riderId);
    return n == null ? 0 : n;
  }

  public Set<String> ridersAtOrAbove(Collection<String> riderIds, int limit) {
    if (riderIds.isEmpty()) {
      return Set.of();
    }
    return new TreeSet<>(
        jdbc.queryForList(
            "SELECT rider_id FROM delivery WHERE state IN ('ASSIGNED', 'PICKED_UP') AND rider_id IN ("
                + in(riderIds.size())
                + ") GROUP BY rider_id HAVING count(*) >= ?",
            String.class,
            concat(riderIds, limit)));
  }

  /** 배차 대기 배송과 진행 중 시도의 반경 단계(배달리스트). */
  /**
   * @param excludedRiderId 이 시도에서 빼는 라이더(방금 배차취소한 라이더, 원문 §5.4)
   */
  public record ListedDelivery(
      DeliveryRow delivery, int stage, Instant waitingSince, @Nullable String excludedRiderId) {}

  public List<ListedDelivery> dispatchingWithStage() {
    return jdbc.query(
        "SELECT d.*, a.stage AS attempt_stage, a.started_at AS attempt_started,"
            + " a.excluded_rider_id AS attempt_excluded FROM delivery d JOIN"
            + " dispatch_attempt a ON a.delivery_id ="
            + " d.delivery_id AND a.state IN ('SEARCHING', 'OFFERING') WHERE d.state = 'DISPATCHING'"
            + " AND d.drop_distance_m IS NOT NULL ORDER BY d.created_at LIMIT 500",
        (rs, i) ->
            new ListedDelivery(
                delivery(rs),
                rs.getInt("attempt_stage"),
                rs.getTimestamp("attempt_started").toInstant(),
                rs.getString("attempt_excluded")));
  }

  // ---------- 시도 ----------

  public void insertAttempt(String attemptId, String deliveryId, String trigger, Instant now) {
    jdbc.update(
        "INSERT INTO dispatch_attempt (attempt_id, delivery_id, trigger, state, stage, stage_started_at,"
            + " next_run_at, started_at) VALUES (?, ?, ?, 'SEARCHING', 1, ?, ?, ?)",
        attemptId,
        deliveryId,
        trigger,
        ts(now),
        ts(now),
        ts(now));
  }

  /** 재배차 시도 — 방금 취소한 라이더는 뺀다. */
  public void insertAttempt(
      String attemptId,
      String deliveryId,
      String trigger,
      @Nullable String excludedRiderId,
      Instant now) {
    insertAttempt(attemptId, deliveryId, trigger, now);
    jdbc.update(
        "UPDATE dispatch_attempt SET excluded_rider_id = ? WHERE attempt_id = ?",
        excludedRiderId,
        attemptId);
  }

  /** 픽업 전 배정 해제 → 다시 배차 대기(원문 §5.4). */
  public void unassign(String deliveryId, int assignmentVersion, Instant now) {
    jdbc.update(
        "UPDATE delivery SET state = 'DISPATCHING', rider_id = NULL, assignment_id = NULL,"
            + " accepted_offer_id = NULL, assigned_at = NULL, bundle_id = NULL, stop_seq = NULL,"
            + " assignment_version = ?, version = version + 1, updated_at = ? WHERE delivery_id = ?",
        assignmentVersion,
        ts(now),
        deliveryId);
  }

  // ---------- 라이더 잠금·멈춘 주문 ----------

  /** 라이더가 맡은(ASSIGNED·PICKED_UP) 배송과 그 라이더에게 간 SENT 제안의 배송 — 잠글 순서대로(배송 ID 순). RS-1. */
  public List<String> deliveryIdsTouchingRider(String riderId) {
    return jdbc.queryForList(
        "SELECT delivery_id FROM delivery WHERE rider_id = ? AND state IN ('ASSIGNED', 'PICKED_UP')"
            + " UNION SELECT delivery_id FROM dispatch_offer WHERE rider_id = ? AND state = 'SENT'"
            + " ORDER BY 1",
        String.class,
        riderId,
        riderId);
  }

  /** 같은 라이더의 수락을 줄 세운다(트랜잭션 끝까지) — 진행 중 수를 센 뒤 배정하기까지 다른 수락이 끼지 못하게(PC 1 검토 M1). */
  public void lockRider(String riderId) {
    jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))", "dispatch-rider:" + riderId);
  }

  public void recordStoppedOrder(String orderId, String stopRequestId, Instant now) {
    jdbc.update(
        "INSERT INTO stopped_order (order_id, stop_request_id, stopped_at) VALUES (?, ?, ?)"
            + " ON CONFLICT (order_id) DO NOTHING",
        orderId,
        stopRequestId,
        ts(now));
  }

  public boolean orderStopped(String orderId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM stopped_order WHERE order_id = ?", Integer.class, orderId);
    return n != null && n > 0;
  }

  /** 라이더별 최근 배차취소 수(라이더 본인이 한 것) — 배차 알림 감소에 쓴다(제재 아님). */
  public java.util.Map<String, Integer> recentRiderCancellations(
      Collection<String> riderIds, Instant since) {
    java.util.Map<String, Integer> out = new java.util.HashMap<>();
    if (riderIds.isEmpty()) {
      return out;
    }
    Object[] args = new Object[riderIds.size() + 1];
    int i = 0;
    for (String id : riderIds) {
      args[i++] = id;
    }
    args[i] = ts(since);
    jdbc.query(
        "SELECT actor, count(*) FROM assignment_cancellation WHERE requested_by = 'RIDER' AND"
            + " state = 'CANCELLED_BEFORE_PICKUP' AND actor IN ("
            + in(riderIds.size())
            + ") AND created_at >= ? GROUP BY actor",
        rs -> {
          out.put(rs.getString(1), rs.getInt(2));
        },
        args);
    return out;
  }

  // ---------- 배차 취소 기록 ----------

  public record CancellationRow(
      String requestId,
      String deliveryId,
      String requestedBy,
      String actor,
      String reason,
      String state,
      @Nullable String previousRiderId,
      @Nullable String reassignAttemptId,
      Instant createdAt) {}

  /**
   * @return 새로 남겼으면 true — 같은 (요청, 배송)이 이미 있으면 false(다른 X-Source-System 으로 같은 키, PC 1 검토 L2)
   */
  public boolean insertCancellation(
      String requestId,
      String deliveryId,
      String requestedBy,
      String actor,
      String reason,
      @Nullable String detail,
      boolean reassign,
      @Nullable String approvalRef,
      String state,
      @Nullable String previousRiderId,
      @Nullable String reassignAttemptId,
      Instant now) {
    return jdbc.update(
            "INSERT INTO assignment_cancellation (request_id, delivery_id, requested_by, actor, reason,"
                + " reason_detail, reassign, approval_ref, state, previous_rider_id, reassign_attempt_id,"
                + " created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                + " ON CONFLICT (request_id, delivery_id) DO NOTHING",
            requestId,
            deliveryId,
            requestedBy,
            actor,
            reason,
            detail,
            reassign,
            approvalRef,
            state,
            previousRiderId,
            reassignAttemptId,
            ts(now))
        > 0;
  }

  public Optional<CancellationRow> cancellation(String deliveryId, String requestId) {
    return first(
        "SELECT * FROM assignment_cancellation WHERE delivery_id = ? AND request_id = ?",
        rs ->
            new CancellationRow(
                rs.getString("request_id"),
                rs.getString("delivery_id"),
                rs.getString("requested_by"),
                rs.getString("actor"),
                rs.getString("reason"),
                rs.getString("state"),
                rs.getString("previous_rider_id"),
                rs.getString("reassign_attempt_id"),
                rs.getTimestamp("created_at").toInstant()),
        deliveryId,
        requestId);
  }

  // ---------- 배차 지연 안내 ----------

  /** 지연 안내 후보 — 배차 대기·아직 안내 안 함·약속 시각 있음(잠금). 늦었는지는 호출자가 예상 전달 시각으로 판정. */
  public List<DeliveryRow> lockDelayCandidates(Instant promisedBefore, int limit) {
    return jdbc.query(
        "SELECT * FROM delivery WHERE state = 'DISPATCHING' AND delay_alerted_at IS NULL AND promised_at"
            + " IS NOT NULL AND promised_at < ? ORDER BY promised_at LIMIT ? FOR UPDATE SKIP LOCKED",
        (rs, i) -> delivery(rs),
        ts(promisedBefore),
        limit);
  }

  /**
   * 상태는 그대로 두고 배송 버전만 올린다 — 사건(DispatchOfferSent·DispatchDelayed)마다 aggregateVersion 이 달라지게(PC 1 검토
   * L3).
   *
   * @return 올린 뒤 버전
   */
  public int bumpVersion(String deliveryId, Instant now) {
    Integer v =
        jdbc.queryForObject(
            "UPDATE delivery SET version = version + 1, updated_at = ? WHERE delivery_id = ? RETURNING version",
            Integer.class,
            ts(now),
            deliveryId);
    return v == null ? 0 : v;
  }

  public void markDelayAlerted(String deliveryId, Instant now) {
    jdbc.update(
        "UPDATE delivery SET delay_alerted_at = ? WHERE delivery_id = ?", ts(now), deliveryId);
  }

  public Optional<AttemptRow> openAttempt(String deliveryId) {
    return first(
        "SELECT * FROM dispatch_attempt WHERE delivery_id = ? AND state IN ('SEARCHING', 'OFFERING')",
        DispatchStore::attempt,
        deliveryId);
  }

  public Optional<AttemptRow> lockAttempt(String attemptId) {
    return first(
        "SELECT * FROM dispatch_attempt WHERE attempt_id = ? FOR UPDATE",
        DispatchStore::attempt,
        attemptId);
  }

  public List<AttemptRow> attempts(String deliveryId) {
    return jdbc.query(
        "SELECT * FROM dispatch_attempt WHERE delivery_id = ? ORDER BY started_at",
        (rs, i) -> attempt(rs),
        deliveryId);
  }

  /** 판단할 차례가 된 시도(MFC 와 함께). */
  public record Due(String attemptId, String deliveryId, String mfcId) {}

  public List<Due> dueAttempts(Instant now, int limit) {
    return jdbc.query(
        "SELECT a.attempt_id, a.delivery_id, d.mfc_id FROM dispatch_attempt a JOIN delivery d ON"
            + " d.delivery_id = a.delivery_id WHERE a.state = 'SEARCHING' AND a.next_run_at <= ? AND"
            + " d.state = 'DISPATCHING' ORDER BY a.next_run_at LIMIT ?",
        (rs, i) -> new Due(rs.getString(1), rs.getString(2), rs.getString(3)),
        ts(now),
        limit);
  }

  public void updateAttempt(
      String attemptId,
      String state,
      int stage,
      Instant stageStartedAt,
      int retryCount,
      int round,
      Instant nextRunAt,
      @Nullable Instant finishedAt,
      @Nullable String failureReason) {
    jdbc.update(
        "UPDATE dispatch_attempt SET state = ?, stage = ?, stage_started_at = ?, retry_count = ?,"
            + " round = ?, next_run_at = ?, finished_at = ?, failure_reason = ? WHERE attempt_id = ?",
        state,
        stage,
        ts(stageStartedAt),
        retryCount,
        round,
        ts(nextRunAt),
        ts(finishedAt),
        failureReason,
        attemptId);
  }

  public void postpone(String attemptId, Instant nextRunAt) {
    jdbc.update(
        "UPDATE dispatch_attempt SET next_run_at = ? WHERE attempt_id = ? AND state = 'SEARCHING'",
        ts(nextRunAt),
        attemptId);
  }

  public void markAttempt(String attemptId, boolean candidate, boolean rejection, boolean expiry) {
    jdbc.update(
        "UPDATE dispatch_attempt SET had_candidate = had_candidate OR ?, had_rejection = had_rejection"
            + " OR ?, had_expiry = had_expiry OR ? WHERE attempt_id = ?",
        candidate,
        rejection,
        expiry,
        attemptId);
  }

  public void searchAgain(String attemptId, Instant now) {
    jdbc.update(
        "UPDATE dispatch_attempt SET state = 'SEARCHING', next_run_at = ? WHERE attempt_id = ? AND"
            + " state = 'OFFERING'",
        ts(now),
        attemptId);
  }

  public void finishAttempt(String attemptId, String state, @Nullable String reason, Instant now) {
    jdbc.update(
        "UPDATE dispatch_attempt SET state = ?, finished_at = ?, failure_reason = ? WHERE attempt_id ="
            + " ? AND state IN ('SEARCHING', 'OFFERING')",
        state,
        ts(now),
        reason,
        attemptId);
  }

  // ---------- 제안 ----------

  public void insertOffer(OfferRow o) {
    jdbc.update(
        "INSERT INTO dispatch_offer (offer_id, attempt_id, delivery_id, rider_id, round, source,"
            + " radius_km, state, sent_at, expires_at, decided_at, pickup_distance_m,"
            + " pickup_duration_s, fee, vehicle_type, bundle_id, bundle_size, stop_seq) VALUES (?, ?, ?, ?, ?,"
            + " ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        o.offerId(),
        o.attemptId(),
        o.deliveryId(),
        o.riderId(),
        o.round(),
        o.source(),
        BigDecimal.valueOf(o.radiusKm()),
        o.state(),
        ts(o.sentAt()),
        ts(o.expiresAt()),
        ts(o.decidedAt()),
        o.pickupDistanceM(),
        o.pickupDurationS(),
        o.fee(),
        o.vehicleType(),
        o.bundleId(),
        o.bundleSize(),
        o.stopSeq());
  }

  /** 같은 묶음의 제안(방문 순서). */
  public List<OfferRow> bundleOffers(String bundleId) {
    return jdbc.query(
        "SELECT * FROM dispatch_offer WHERE bundle_id = ? ORDER BY stop_seq",
        (rs, i) -> offer(rs),
        bundleId);
  }

  public void setBundle(String deliveryId, @Nullable String bundleId, int stopSeq) {
    jdbc.update(
        "UPDATE delivery SET bundle_id = ?, stop_seq = ? WHERE delivery_id = ?",
        bundleId,
        stopSeq,
        deliveryId);
  }

  /** 라이더별 맡고 있는 배송 수(배정 ~ 픽업). */
  public java.util.Map<String, Integer> activeCounts(Collection<String> riderIds) {
    java.util.Map<String, Integer> out = new java.util.HashMap<>();
    if (riderIds.isEmpty()) {
      return out;
    }
    jdbc.query(
        "SELECT rider_id, count(*) FROM delivery WHERE state IN ('ASSIGNED', 'PICKED_UP') AND rider_id IN ("
            + in(riderIds.size())
            + ") GROUP BY rider_id",
        rs -> {
          out.put(rs.getString(1), rs.getInt(2));
        },
        riderIds.toArray());
    return out;
  }

  public Optional<OfferRow> offer(String offerId) {
    return first("SELECT * FROM dispatch_offer WHERE offer_id = ?", DispatchStore::offer, offerId);
  }

  public Optional<OfferRow> lockOffer(String offerId) {
    return first(
        "SELECT * FROM dispatch_offer WHERE offer_id = ? FOR UPDATE",
        DispatchStore::offer,
        offerId);
  }

  public List<OfferRow> offersOfDelivery(String deliveryId) {
    return jdbc.query(
        "SELECT * FROM dispatch_offer WHERE delivery_id = ? ORDER BY sent_at",
        (rs, i) -> offer(rs),
        deliveryId);
  }

  public List<OfferRow> offersOfAttempt(String attemptId) {
    return jdbc.query(
        "SELECT * FROM dispatch_offer WHERE attempt_id = ? ORDER BY sent_at",
        (rs, i) -> offer(rs),
        attemptId);
  }

  /** 라이더 본인 제안(최근 순). state 가 null 이면 만료 전 SENT 만. */
  public List<OfferRow> riderOffers(
      String riderId, @Nullable String state, Instant now, int limit, int offset) {
    if (state == null) {
      return jdbc.query(
          "SELECT * FROM dispatch_offer WHERE rider_id = ? AND state = 'SENT' AND expires_at > ? ORDER"
              + " BY sent_at DESC LIMIT ? OFFSET ?",
          (rs, i) -> offer(rs),
          riderId,
          ts(now),
          limit,
          offset);
    }
    return jdbc.query(
        "SELECT * FROM dispatch_offer WHERE rider_id = ? AND state = ? ORDER BY sent_at DESC LIMIT ?"
            + " OFFSET ?",
        (rs, i) -> offer(rs),
        riderId,
        state,
        limit,
        offset);
  }

  public Set<String> offeredInRound(String attemptId, int round) {
    return new TreeSet<>(
        jdbc.queryForList(
            "SELECT DISTINCT rider_id FROM dispatch_offer WHERE attempt_id = ? AND round = ?",
            String.class,
            attemptId,
            round));
  }

  public Set<String> ridersWithLiveOffer(Instant now) {
    return new TreeSet<>(
        jdbc.queryForList(
            "SELECT DISTINCT rider_id FROM dispatch_offer WHERE state = 'SENT' AND expires_at > ?",
            String.class,
            ts(now)));
  }

  /** 응답 없이 만료된 제안(잠금 — 다른 인스턴스와 겹치지 않게). */
  public List<OfferRow> lockExpired(Instant now, int limit) {
    return jdbc.query(
        "SELECT * FROM dispatch_offer WHERE state = 'SENT' AND expires_at <= ? ORDER BY expires_at"
            + " LIMIT ? FOR UPDATE SKIP LOCKED",
        (rs, i) -> offer(rs),
        ts(now),
        limit);
  }

  public void decideOffer(
      String offerId,
      String state,
      Instant now,
      @Nullable String rejectReason,
      @Nullable String lateReason) {
    jdbc.update(
        "UPDATE dispatch_offer SET state = ?, decided_at = ?, reject_reason = ?, late_reason = ?"
            + " WHERE offer_id = ?",
        state,
        ts(now),
        rejectReason,
        lateReason,
        offerId);
  }

  /** 배정이 정해지면 그 배송의 남은 제안은 SUPERSEDED. */
  public void supersedeOpenOffers(String deliveryId, Instant now, String lateReason) {
    jdbc.update(
        "UPDATE dispatch_offer SET state = 'SUPERSEDED', decided_at = ?, late_reason = ? WHERE"
            + " delivery_id = ? AND state = 'SENT'",
        ts(now),
        lateReason,
        deliveryId);
  }

  public int liveOffersInAttempt(String attemptId, Instant now) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM dispatch_offer WHERE attempt_id = ? AND state = 'SENT' AND expires_at"
                + " > ?",
            Integer.class,
            attemptId,
            ts(now));
    return n == null ? 0 : n;
  }

  public void markViewed(String offerId, Instant now) {
    jdbc.update(
        "UPDATE dispatch_offer SET viewed_at = ? WHERE offer_id = ? AND viewed_at IS NULL",
        ts(now),
        offerId);
  }

  // ---------- 연속 거절 ----------

  public int addRejection(String riderId, Instant now) {
    Integer n =
        jdbc.queryForObject(
            "INSERT INTO rider_rejection (rider_id, consecutive, updated_at) VALUES (?, 1, ?) ON"
                + " CONFLICT (rider_id) DO UPDATE SET consecutive = rider_rejection.consecutive + 1,"
                + " updated_at = EXCLUDED.updated_at RETURNING consecutive",
            Integer.class,
            riderId,
            ts(now));
    return n == null ? 0 : n;
  }

  public void resetRejections(String riderId, Instant now) {
    jdbc.update(
        "INSERT INTO rider_rejection (rider_id, consecutive, updated_at) VALUES (?, 0, ?) ON CONFLICT"
            + " (rider_id) DO UPDATE SET consecutive = 0, updated_at = EXCLUDED.updated_at",
        riderId,
        ts(now));
  }

  public int rejections(String riderId) {
    List<Integer> n =
        jdbc.queryForList(
            "SELECT consecutive FROM rider_rejection WHERE rider_id = ?", Integer.class, riderId);
    return n.isEmpty() ? 0 : n.get(0);
  }

  // ---------- 매핑 ----------

  private interface Mapper<T> {
    T map(ResultSet rs) throws SQLException;
  }

  private <T> Optional<T> first(String sql, Mapper<T> m, Object... args) {
    List<T> rows = jdbc.query(sql, (rs, i) -> m.map(rs), args);
    return rows.stream().findFirst();
  }

  static DeliveryRow delivery(ResultSet rs) throws SQLException {
    Date ad = rs.getDate("arrival_date");
    return new DeliveryRow(
        rs.getString("delivery_id"),
        rs.getInt("version"),
        rs.getString("type"),
        rs.getString("order_id"),
        rs.getString("shipment_id"),
        rs.getString("mfc_id"),
        rs.getString("state"),
        rs.getString("delivery_mode"),
        ad == null ? null : ad.toLocalDate(),
        rs.getString("arrival_start"),
        rs.getString("arrival_end"),
        rs.getString("request_trigger"),
        instant(rs, "estimated_ready_at"),
        instant(rs, "ready_at"),
        rs.getString("packages"),
        rs.getObject("mfc_lat", Double.class),
        rs.getObject("mfc_lng", Double.class),
        rs.getObject("drop_lat", Double.class),
        rs.getObject("drop_lng", Double.class),
        rs.getString("drop_area_label"),
        rs.getObject("drop_distance_m", Integer.class),
        rs.getObject("drop_duration_s", Integer.class),
        rs.getString("geo_error"),
        rs.getString("rider_id"),
        rs.getString("assignment_id"),
        rs.getInt("assignment_version"),
        instant(rs, "assigned_at"),
        rs.getString("accepted_offer_id"),
        instant(rs, "picked_up_at"),
        rs.getString("handed_over_packages"),
        instant(rs, "cancelled_at"),
        rs.getString("cancel_reason"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant(),
        rs.getString("bundle_id"),
        rs.getObject("stop_seq", Integer.class),
        instant(rs, "delay_alerted_at"),
        instant(rs, "promised_at"));
  }

  static AttemptRow attempt(ResultSet rs) throws SQLException {
    return new AttemptRow(
        rs.getString("attempt_id"),
        rs.getString("delivery_id"),
        rs.getString("trigger"),
        rs.getString("state"),
        rs.getInt("stage"),
        rs.getTimestamp("stage_started_at").toInstant(),
        rs.getInt("retry_count"),
        rs.getInt("round"),
        rs.getTimestamp("next_run_at").toInstant(),
        rs.getTimestamp("started_at").toInstant(),
        instant(rs, "finished_at"),
        rs.getString("failure_reason"),
        rs.getBoolean("had_candidate"),
        rs.getBoolean("had_rejection"),
        rs.getBoolean("had_expiry"),
        rs.getString("excluded_rider_id"));
  }

  static OfferRow offer(ResultSet rs) throws SQLException {
    return new OfferRow(
        rs.getString("offer_id"),
        rs.getString("attempt_id"),
        rs.getString("delivery_id"),
        rs.getString("rider_id"),
        rs.getInt("round"),
        rs.getString("source"),
        rs.getBigDecimal("radius_km").doubleValue(),
        rs.getString("state"),
        rs.getTimestamp("sent_at").toInstant(),
        rs.getTimestamp("expires_at").toInstant(),
        instant(rs, "viewed_at"),
        instant(rs, "decided_at"),
        rs.getString("reject_reason"),
        rs.getString("late_reason"),
        rs.getObject("pickup_distance_m", Integer.class),
        rs.getObject("pickup_duration_s", Integer.class),
        rs.getInt("fee"),
        rs.getString("vehicle_type"),
        rs.getString("bundle_id"),
        rs.getInt("bundle_size"),
        rs.getInt("stop_seq"));
  }

  private static @Nullable Instant instant(ResultSet rs, String col) throws SQLException {
    Timestamp t = rs.getTimestamp(col);
    return t == null ? null : t.toInstant();
  }

  private static @Nullable Timestamp ts(@Nullable Instant i) {
    return i == null ? null : Timestamp.from(i);
  }

  private static String in(int n) {
    return String.join(",", Collections.nCopies(n, "?"));
  }

  private static Object[] concat(Collection<String> ids, Object last) {
    Object[] a = new Object[ids.size() + 1];
    int i = 0;
    for (String id : ids) {
      a[i++] = id;
    }
    a[i] = last;
    return a;
  }
}
