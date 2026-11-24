package com.pmart.cases.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * R-16(ADR-0086) — 배송 사본({@code case_delivery_copy})과 오배송 문의 선택({@code inquiry_misdelivery}, V24).
 */
@Repository
public class MisdeliveryInquiryStore {

  public record DeliveryCopy(
      String deliveryId,
      @Nullable String orderId,
      @Nullable String type,
      @Nullable String purpose,
      @Nullable String mfcId,
      @Nullable String riderId,
      @Nullable Instant completedAt) {}

  public record ChoiceRow(
      String inquiryId,
      String caseId,
      String deliveryId,
      String orderId,
      @Nullable String riderId,
      Instant deadlineAt,
      @Nullable String resolution,
      @Nullable String chosenByType,
      @Nullable String chosenBy,
      @Nullable String chosenReason,
      @Nullable Instant chosenAt,
      Instant createdAt) {}

  private final JdbcTemplate jdbc;

  public MisdeliveryInquiryStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  // ---------------------------------------------------------------- 배송 사본

  /** DeliveryCreated — 종류·주문·센터. 완료 칸은 그대로. */
  public void deliveryCreated(
      String deliveryId,
      @Nullable String orderId,
      @Nullable String type,
      @Nullable String purpose,
      @Nullable String mfcId,
      Instant at) {
    jdbc.update(
        "INSERT INTO case_delivery_copy (delivery_id, order_id, delivery_type, purpose, mfc_id,"
            + " updated_at) VALUES (?,?,?,?,?,?) ON CONFLICT (delivery_id) DO UPDATE SET"
            + " order_id = COALESCE(EXCLUDED.order_id, case_delivery_copy.order_id),"
            + " delivery_type = EXCLUDED.delivery_type, purpose = EXCLUDED.purpose,"
            + " mfc_id = COALESCE(EXCLUDED.mfc_id, case_delivery_copy.mfc_id),"
            + " updated_at = EXCLUDED.updated_at",
        deliveryId,
        orderId,
        type,
        purpose,
        mfcId,
        Timestamp.from(at));
  }

  /** DeliveryCompleted — 라이더·전달 시각. 생성 사건보다 먼저 와도 받는다. */
  public void deliveryCompleted(
      String deliveryId,
      @Nullable String orderId,
      @Nullable String riderId,
      Instant deliveredAt,
      Instant at) {
    jdbc.update(
        "INSERT INTO case_delivery_copy (delivery_id, order_id, rider_id, completed_at, updated_at)"
            + " VALUES (?,?,?,?,?) ON CONFLICT (delivery_id) DO UPDATE SET"
            + " order_id = COALESCE(case_delivery_copy.order_id, EXCLUDED.order_id),"
            + " rider_id = COALESCE(EXCLUDED.rider_id, case_delivery_copy.rider_id),"
            + " completed_at = EXCLUDED.completed_at, updated_at = EXCLUDED.updated_at",
        deliveryId,
        orderId,
        riderId,
        Timestamp.from(deliveredAt),
        Timestamp.from(at));
  }

  public Optional<DeliveryCopy> delivery(String deliveryId) {
    return jdbc
        .query("SELECT * FROM case_delivery_copy WHERE delivery_id = ?", this::copy, deliveryId)
        .stream()
        .findFirst();
  }

  /** 그 주문의 가장 최근 전달 완료 고객 배송(FORWARD·RESHIPMENT) — 앱이 배송 ID 없이 주문만 줄 때(R-16 Q2). */
  public Optional<DeliveryCopy> latestCompletedCustomerDelivery(String orderId) {
    return jdbc
        .query(
            "SELECT * FROM case_delivery_copy WHERE order_id = ? AND completed_at IS NOT NULL"
                + " AND delivery_type IN ('FORWARD', 'RESHIPMENT')"
                + " ORDER BY completed_at DESC, delivery_id DESC LIMIT 1",
            this::copy,
            orderId)
        .stream()
        .findFirst();
  }

  private DeliveryCopy copy(ResultSet rs, int i) throws SQLException {
    Timestamp done = rs.getTimestamp("completed_at");
    return new DeliveryCopy(
        rs.getString("delivery_id"),
        rs.getString("order_id"),
        rs.getString("delivery_type"),
        rs.getString("purpose"),
        rs.getString("mfc_id"),
        rs.getString("rider_id"),
        done == null ? null : done.toInstant());
  }

  // ---------------------------------------------------------------- 선택

  public void insert(ChoiceRow r) {
    jdbc.update(
        "INSERT INTO inquiry_misdelivery (inquiry_id, case_id, delivery_id, order_id, rider_id,"
            + " deadline_at, created_at) VALUES (?,?,?,?,?,?,?)",
        r.inquiryId(),
        r.caseId(),
        r.deliveryId(),
        r.orderId(),
        r.riderId(),
        Timestamp.from(r.deadlineAt()),
        Timestamp.from(r.createdAt()));
  }

  public Optional<ChoiceRow> find(String inquiryId) {
    return jdbc
        .query("SELECT * FROM inquiry_misdelivery WHERE inquiry_id = ?", this::choice, inquiryId)
        .stream()
        .findFirst();
  }

  public Optional<ChoiceRow> lock(String inquiryId) {
    return jdbc
        .query(
            "SELECT * FROM inquiry_misdelivery WHERE inquiry_id = ? FOR UPDATE",
            this::choice,
            inquiryId)
        .stream()
        .findFirst();
  }

  public Map<String, ChoiceRow> byInquiries(Collection<String> inquiryIds) {
    Map<String, ChoiceRow> out = new HashMap<>();
    if (inquiryIds.isEmpty()) {
      return out;
    }
    jdbc.query(
        "SELECT * FROM inquiry_misdelivery WHERE inquiry_id = ANY (?::varchar[])",
        rs -> {
          ChoiceRow r = choice(rs, 0);
          out.put(r.inquiryId(), r);
        },
        "{" + String.join(",", inquiryIds) + "}");
    return out;
  }

  /** 기한이 지났는데 안 고른 것(잠금, 다른 인스턴스가 잡은 것은 건너뜀). */
  public List<String> lockDue(Instant now, int limit) {
    return jdbc.queryForList(
        "SELECT inquiry_id FROM inquiry_misdelivery WHERE resolution IS NULL AND deadline_at <= ?"
            + " ORDER BY deadline_at LIMIT ? FOR UPDATE SKIP LOCKED",
        String.class,
        Timestamp.from(now),
        limit);
  }

  public void chosen(
      String inquiryId,
      String resolution,
      String byType,
      String by,
      @Nullable String reason,
      Instant at) {
    jdbc.update(
        "UPDATE inquiry_misdelivery SET resolution = ?, chosen_by_type = ?, chosen_by = ?,"
            + " chosen_reason = ?, chosen_at = ? WHERE inquiry_id = ? AND resolution IS NULL",
        resolution,
        byType,
        by,
        reason,
        Timestamp.from(at),
        inquiryId);
  }

  private ChoiceRow choice(ResultSet rs, int i) throws SQLException {
    Timestamp at = rs.getTimestamp("chosen_at");
    return new ChoiceRow(
        rs.getString("inquiry_id"),
        rs.getString("case_id"),
        rs.getString("delivery_id"),
        rs.getString("order_id"),
        rs.getString("rider_id"),
        rs.getTimestamp("deadline_at").toInstant(),
        rs.getString("resolution"),
        rs.getString("chosen_by_type"),
        rs.getString("chosen_by"),
        rs.getString("chosen_reason"),
        at == null ? null : at.toInstant(),
        rs.getTimestamp("created_at").toInstant());
  }
}
