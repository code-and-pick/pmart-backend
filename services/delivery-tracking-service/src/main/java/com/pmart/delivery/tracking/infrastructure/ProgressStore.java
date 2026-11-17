package com.pmart.delivery.tracking.infrastructure;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 배송 진행 단계 사본(TR-3, V5) + 그 배송의 ETA(rider_active_delivery). */
@Repository
public class ProgressStore {

  /** 진행 + ETA 한 줄. eta 값은 감시 중이거나 닫힌 행에서 마지막 값. */
  public record Progress(
      String deliveryId,
      @Nullable String orderId,
      @Nullable String mfcId,
      String stage,
      @Nullable String outcome,
      @Nullable LocalDate arrivalDate,
      @Nullable String arrivalStart,
      @Nullable String arrivalEnd,
      Instant updatedAt,
      @Nullable String riderId,
      @Nullable Instant etaPickup,
      @Nullable Instant etaDrop,
      @Nullable Instant etaCheckedAt) {}

  private final JdbcTemplate jdbc;

  public ProgressStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 배송 생성 — 이미 있으면(다른 사건이 먼저 와서 만든 행) 주문·센터·도착 시간대만 채운다. */
  public void created(
      String deliveryId,
      @Nullable String orderId,
      @Nullable String mfcId,
      @Nullable LocalDate arrivalDate,
      @Nullable String arrivalStart,
      @Nullable String arrivalEnd,
      Instant now) {
    jdbc.update(
        "INSERT INTO delivery_progress (delivery_id, order_id, mfc_id, stage, arrival_date,"
            + " arrival_start, arrival_end, created_at, updated_at) VALUES (?, ?, ?, 'UNASSIGNED',"
            + " ?, ?, ?, ?, ?) ON CONFLICT (delivery_id) DO UPDATE SET order_id ="
            + " COALESCE(delivery_progress.order_id, EXCLUDED.order_id), mfc_id ="
            + " COALESCE(delivery_progress.mfc_id, EXCLUDED.mfc_id), arrival_date ="
            + " COALESCE(delivery_progress.arrival_date, EXCLUDED.arrival_date), arrival_start ="
            + " COALESCE(delivery_progress.arrival_start, EXCLUDED.arrival_start), arrival_end ="
            + " COALESCE(delivery_progress.arrival_end, EXCLUDED.arrival_end)",
        deliveryId,
        orderId,
        mfcId,
        arrivalDate == null ? null : Date.valueOf(arrivalDate),
        arrivalStart,
        arrivalEnd,
        Timestamp.from(now),
        Timestamp.from(now));
  }

  /** 지금 단계(잠금). 없으면 empty. */
  public Optional<String> lockStage(String deliveryId) {
    return jdbc
        .queryForList(
            "SELECT stage FROM delivery_progress WHERE delivery_id = ? FOR UPDATE",
            String.class,
            deliveryId)
        .stream()
        .findFirst();
  }

  /** 단계를 바꾼다(행이 없으면 만든다 — 주문·센터는 모름). 판단은 호출자({@code ProgressRules}). */
  public void setStage(
      String deliveryId,
      String stage,
      @Nullable String outcome,
      @Nullable String mfcId,
      Instant now) {
    jdbc.update(
        "INSERT INTO delivery_progress (delivery_id, mfc_id, stage, outcome, created_at, updated_at)"
            + " VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (delivery_id) DO UPDATE SET stage ="
            + " EXCLUDED.stage, outcome = EXCLUDED.outcome, mfc_id = COALESCE(delivery_progress.mfc_id,"
            + " EXCLUDED.mfc_id), updated_at = EXCLUDED.updated_at",
        deliveryId,
        mfcId,
        stage,
        outcome,
        Timestamp.from(now),
        Timestamp.from(now));
  }

  public Optional<Progress> find(String deliveryId) {
    return jdbc
        .query(
            "SELECT p.delivery_id, p.order_id, p.mfc_id, p.stage, p.outcome, p.arrival_date,"
                + " p.arrival_start, p.arrival_end, p.updated_at, a.rider_id, a.eta_pickup,"
                + " a.eta_drop, a.eta_checked_at FROM delivery_progress p LEFT JOIN"
                + " rider_active_delivery a ON a.delivery_id = p.delivery_id AND a.rider_id <> ''"
                + " WHERE p.delivery_id = ?",
            this::row,
            deliveryId)
        .stream()
        .findFirst();
  }

  /** 받았는지 아직 묻지 않은, 픽업 뒤·전달 전 배송(2026-11-17). */
  public List<Progress> receiptCandidates(int limit) {
    return jdbc.query(
        "SELECT p.delivery_id, p.order_id, p.mfc_id, p.stage, p.outcome, p.arrival_date,"
            + " p.arrival_start, p.arrival_end, p.updated_at, a.rider_id, a.eta_pickup,"
            + " a.eta_drop, a.eta_checked_at FROM delivery_progress p LEFT JOIN"
            + " rider_active_delivery a ON a.delivery_id = p.delivery_id AND a.state = 'ACTIVE'"
            + " WHERE p.stage IN ('TO_CUSTOMER', 'AT_CUSTOMER') AND p.receipt_asked_at IS NULL"
            + " AND p.order_id IS NOT NULL ORDER BY p.updated_at LIMIT ?",
        this::row,
        limit);
  }

  /** 한 번만(이미 물었으면 0). */
  public int markReceiptAsked(String deliveryId, Instant at) {
    return jdbc.update(
        "UPDATE delivery_progress SET receipt_asked_at = ? WHERE delivery_id = ? AND"
            + " receipt_asked_at IS NULL",
        Timestamp.from(at),
        deliveryId);
  }

  /** 이 서비스의 Delivery 사건 버전(ETA 사건과 같은 줄) — 감시 줄이 없으면 null. */
  public @Nullable Integer bumpEventVersion(String deliveryId) {
    return jdbc
        .query(
            "UPDATE rider_active_delivery SET eta_version = eta_version + 1 WHERE delivery_id = ?"
                + " RETURNING eta_version",
            (rs, i) -> rs.getInt(1),
            deliveryId)
        .stream()
        .findFirst()
        .orElse(null);
  }

  /** 끝난 지 보존 기간이 지난 진행 사본을 지운다. */
  public int purgeDone(Instant before) {
    return jdbc.update(
        "DELETE FROM delivery_progress WHERE stage = 'DONE' AND updated_at < ?",
        Timestamp.from(before));
  }

  private Progress row(ResultSet rs, int n) throws SQLException {
    Date d = rs.getDate("arrival_date");
    return new Progress(
        rs.getString("delivery_id"),
        rs.getString("order_id"),
        rs.getString("mfc_id"),
        rs.getString("stage"),
        rs.getString("outcome"),
        d == null ? null : d.toLocalDate(),
        rs.getString("arrival_start"),
        rs.getString("arrival_end"),
        rs.getTimestamp("updated_at").toInstant(),
        rs.getString("rider_id"),
        instant(rs, "eta_pickup"),
        instant(rs, "eta_drop"),
        instant(rs, "eta_checked_at"));
  }

  private static @Nullable Instant instant(ResultSet rs, String col) throws SQLException {
    Timestamp t = rs.getTimestamp(col);
    return t == null ? null : t.toInstant();
  }

  static List<String> stages() {
    return List.of("UNASSIGNED", "TO_STORE", "AT_STORE", "TO_CUSTOMER", "AT_CUSTOMER", "DONE");
  }
}
