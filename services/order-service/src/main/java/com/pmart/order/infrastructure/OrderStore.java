package com.pmart.order.infrastructure;

import com.pmart.order.domain.ShippingAddress;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 확정 주문 원장(JDBC). 주문과 주문행은 주문 확정 한 트랜잭션에서 함께 쓴다. */
@Repository
public class OrderStore {

  public record OrderRow(
      String orderId,
      String accountId,
      String draftId,
      String mfcId,
      String status,
      int version,
      String deliveryMode,
      @Nullable LocalDate arrivalDate,
      @Nullable String arrivalStart,
      @Nullable String arrivalEnd,
      String outOfStockPreference,
      @Nullable String deliveryNote,
      String shoppingAddressId,
      int quoteVersion,
      int priceVersion,
      long itemAmount,
      long deliveryTipBase,
      long deliveryTip,
      String deliveryTipReason,
      long bagFee,
      long totalAmount,
      boolean firstPurchaseApplied,
      int weightG,
      int plannedShipments,
      String paymentAttemptId,
      String pgTransactionId,
      long approvedAmount,
      String reservationId,
      @Nullable String capacityHoldId,
      @Nullable String slotHoldId,
      Instant confirmedAt) {}

  public record LineRow(
      String orderLineId,
      String orderId,
      int lineNo,
      @Nullable String cartItemId,
      String sku,
      @Nullable String productId,
      int unitsPerSku,
      int quantity,
      long unitPrice,
      long lineAmount,
      long discountShare,
      int weightG,
      int originalQty,
      int unfulfilledQty,
      int handedOverQty,
      int cancelledBeforeShipQty,
      int deliveredQty,
      int returnedQty,
      @Nullable String productName,
      @Nullable String optionLabel,
      int reshippedQty) {}

  /** 목록 한 줄(주문 요약). */
  public record Summary(
      String orderId, String status, Instant confirmedAt, long totalAmount, int itemCount) {}

  private final JdbcTemplate jdbc;

  public OrderStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 주문 당시 배송지 사본(BR-37). 주문 확정과 같은 트랜잭션에서 쓴다. */
  public void insertShipping(String orderId, ShippingAddress s) {
    jdbc.update(
        "INSERT INTO customer_order_shipping (order_id, road_address, detail_address, postal_code,"
            + " receiver_name, receiver_phone, entrance_method, entrance_note, lat, lng, captured_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?)",
        orderId,
        s.roadAddress(),
        s.detailAddress(),
        s.postalCode(),
        s.receiverName(),
        s.receiverPhone(),
        s.entranceMethod(),
        s.entranceNote(),
        s.lat(),
        s.lng(),
        Timestamp.from(s.capturedAt()));
  }

  /** 주문 당시 배송지 사본. 2026-11-02 이전 주문은 없다. */
  public Optional<ShippingAddress> shipping(String orderId) {
    return jdbc
        .query(
            "SELECT road_address, detail_address, postal_code, receiver_name, receiver_phone,"
                + " entrance_method, entrance_note, lat, lng, captured_at"
                + " FROM customer_order_shipping WHERE order_id = ?",
            (rs, i) ->
                new ShippingAddress(
                    rs.getString("road_address"),
                    rs.getString("detail_address"),
                    rs.getString("postal_code"),
                    rs.getString("receiver_name"),
                    rs.getString("receiver_phone"),
                    rs.getString("entrance_method"),
                    rs.getString("entrance_note"),
                    rs.getObject("lat", Double.class),
                    rs.getObject("lng", Double.class),
                    rs.getTimestamp("captured_at").toInstant()),
            orderId)
        .stream()
        .findFirst();
  }

  public void insert(OrderRow o, List<LineRow> lines) {
    jdbc.update(
        "INSERT INTO customer_order (order_id, account_id, draft_id, mfc_id, status, version,"
            + " delivery_mode, arrival_date, arrival_start, arrival_end, out_of_stock_preference,"
            + " delivery_note, shopping_address_id, quote_version, price_version, item_amount,"
            + " delivery_tip_base, delivery_tip, delivery_tip_reason, bag_fee, total_amount,"
            + " first_purchase_applied, weight_g, planned_shipments, payment_attempt_id,"
            + " pg_transaction_id, approved_amount, reservation_id, capacity_hold_id, slot_hold_id,"
            + " confirmed_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        o.orderId(),
        o.accountId(),
        o.draftId(),
        o.mfcId(),
        o.status(),
        o.version(),
        o.deliveryMode(),
        o.arrivalDate() == null ? null : Date.valueOf(o.arrivalDate()),
        o.arrivalStart(),
        o.arrivalEnd(),
        o.outOfStockPreference(),
        o.deliveryNote(),
        o.shoppingAddressId(),
        o.quoteVersion(),
        o.priceVersion(),
        o.itemAmount(),
        o.deliveryTipBase(),
        o.deliveryTip(),
        o.deliveryTipReason(),
        o.bagFee(),
        o.totalAmount(),
        o.firstPurchaseApplied(),
        o.weightG(),
        o.plannedShipments(),
        o.paymentAttemptId(),
        o.pgTransactionId(),
        o.approvedAmount(),
        o.reservationId(),
        o.capacityHoldId(),
        o.slotHoldId(),
        Timestamp.from(o.confirmedAt()));
    for (LineRow l : lines) {
      jdbc.update(
          "INSERT INTO customer_order_line (order_line_id, order_id, line_no, cart_item_id, sku,"
              + " product_id, units_per_sku, quantity, unit_price, line_amount, discount_share,"
              + " weight_g, original_qty, unfulfilled_qty, handed_over_qty,"
              + " cancelled_before_ship_qty, delivered_qty, returned_qty, product_name, option_label)"
              + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
          l.orderLineId(),
          l.orderId(),
          l.lineNo(),
          l.cartItemId(),
          l.sku(),
          l.productId(),
          l.unitsPerSku(),
          l.quantity(),
          l.unitPrice(),
          l.lineAmount(),
          l.discountShare(),
          l.weightG(),
          l.originalQty(),
          l.unfulfilledQty(),
          l.handedOverQty(),
          l.cancelledBeforeShipQty(),
          l.deliveredQty(),
          l.returnedQty(),
          l.productName(),
          l.optionLabel());
    }
  }

  public Optional<OrderRow> order(String orderId) {
    return jdbc
        .query("SELECT * FROM customer_order WHERE order_id = ?", (rs, i) -> row(rs), orderId)
        .stream()
        .findFirst();
  }

  /** 수량 변경 전 주문 행 잠금(같은 주문의 변경을 줄 세운다). */
  public Optional<OrderRow> lock(String orderId) {
    return jdbc
        .query(
            "SELECT * FROM customer_order WHERE order_id = ? FOR UPDATE",
            (rs, i) -> row(rs),
            orderId)
        .stream()
        .findFirst();
  }

  /** 주문행 수량 누계 갱신(불변식은 호출자가 확인 — 원수량 = 미이행 + 인계 + 출고 전 취소). */
  public void updateLineQuantities(
      String orderLineId, int unfulfilledQty, int cancelledBeforeShipQty) {
    jdbc.update(
        "UPDATE customer_order_line SET unfulfilled_qty = ?, cancelled_before_ship_qty = ?"
            + " WHERE order_line_id = ?",
        unfulfilledQty,
        cancelledBeforeShipQty,
        orderLineId);
  }

  /** 이행 진행(인계·전달, O-1) 반영 — 미이행·인계·전달 누계. */
  /** 오배송 재발송(RESHIPMENT) — 미이행·재발송 누계. */
  public void updateLineReshipped(String orderLineId, int unfulfilledQty, int reshippedQty) {
    jdbc.update(
        "UPDATE customer_order_line SET unfulfilled_qty = ?, reshipped_qty = ? WHERE order_line_id = ?",
        unfulfilledQty,
        reshippedQty,
        orderLineId);
  }

  public void updateLineProgress(
      String orderLineId, int unfulfilledQty, int handedOverQty, int deliveredQty) {
    jdbc.update(
        "UPDATE customer_order_line SET unfulfilled_qty = ?, handed_over_qty = ?, delivered_qty = ?"
            + " WHERE order_line_id = ?",
        unfulfilledQty,
        handedOverQty,
        deliveredQty,
        orderLineId);
  }

  /** 이행 사실(인계·전달)을 업무 키로 한 번만 기록. 이미 있으면 false(반영하지 않는다). */
  public boolean recordFact(
      String kind,
      String sourceRef,
      String orderId,
      @Nullable String deliveryId,
      String sourceEventId,
      String state,
      @Nullable String detail,
      Instant occurredAt,
      Instant appliedAt) {
    return jdbc.update(
            "INSERT INTO order_fulfillment_fact (kind, source_ref, order_id, delivery_id,"
                + " source_event_id, state, detail, occurred_at, applied_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT (kind, source_ref) DO NOTHING",
            kind,
            sourceRef,
            orderId,
            deliveryId,
            sourceEventId,
            state,
            detail,
            java.sql.Timestamp.from(occurredAt),
            java.sql.Timestamp.from(appliedAt))
        > 0;
  }

  /** 반영된 전달 완료의 배송 ID(OrderCompleted.deliveryIds). */
  public List<String> deliveredDeliveryIds(String orderId) {
    return jdbc.queryForList(
        "SELECT source_ref FROM order_fulfillment_fact WHERE order_id = ? AND kind = 'DELIVERY'"
            + " AND state = 'APPLIED' ORDER BY occurred_at, source_ref",
        String.class,
        orderId);
  }

  /** 주문 상태·버전 갱신. */
  public void updateStatus(String orderId, String status, int version) {
    jdbc.update(
        "UPDATE customer_order SET status = ?, version = ? WHERE order_id = ?",
        status,
        version,
        orderId);
  }

  public Optional<OrderRow> byDraft(String draftId) {
    return jdbc
        .query("SELECT * FROM customer_order WHERE draft_id = ?", (rs, i) -> row(rs), draftId)
        .stream()
        .findFirst();
  }

  public boolean exists(String orderId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM customer_order WHERE order_id = ?", Integer.class, orderId);
    return n != null && n > 0;
  }

  public List<LineRow> lines(String orderId) {
    return jdbc.query(
        "SELECT * FROM customer_order_line WHERE order_id = ? ORDER BY line_no",
        (rs, i) ->
            new LineRow(
                rs.getString("order_line_id"),
                rs.getString("order_id"),
                rs.getInt("line_no"),
                rs.getString("cart_item_id"),
                rs.getString("sku"),
                rs.getString("product_id"),
                rs.getInt("units_per_sku"),
                rs.getInt("quantity"),
                rs.getLong("unit_price"),
                rs.getLong("line_amount"),
                rs.getLong("discount_share"),
                rs.getInt("weight_g"),
                rs.getInt("original_qty"),
                rs.getInt("unfulfilled_qty"),
                rs.getInt("handed_over_qty"),
                rs.getInt("cancelled_before_ship_qty"),
                rs.getInt("delivered_qty"),
                rs.getInt("returned_qty"),
                rs.getString("product_name"),
                rs.getString("option_label"),
                rs.getInt("reshipped_qty")),
        orderId);
  }

  /**
   * 내 주문 목록(최근 확정 순). keyset — after 가 있으면 그 (확정 시각, 주문 ID) 다음부터.
   *
   * @param limit 가져올 최대 건수(다음 쪽 판정을 위해 호출자가 +1 해서 준다)
   */
  public List<Summary> list(
      String accountId,
      @Nullable String status,
      @Nullable Instant afterConfirmedAt,
      @Nullable String afterOrderId,
      int limit) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT o.order_id, o.status, o.confirmed_at, o.total_amount,"
                + " (SELECT COALESCE(SUM(l.quantity), 0) FROM customer_order_line l"
                + " WHERE l.order_id = o.order_id) AS item_count"
                + " FROM customer_order o WHERE o.account_id = ?");
    List<Object> args = new ArrayList<>();
    args.add(accountId);
    if (status != null) {
      sql.append(" AND o.status = ?");
      args.add(status);
    }
    if (afterConfirmedAt != null && afterOrderId != null) {
      sql.append(" AND (o.confirmed_at, o.order_id) < (?, ?)");
      args.add(Timestamp.from(afterConfirmedAt));
      args.add(afterOrderId);
    }
    sql.append(" ORDER BY o.confirmed_at DESC, o.order_id DESC LIMIT ?");
    args.add(limit);
    return jdbc.query(
        sql.toString(),
        (rs, i) ->
            new Summary(
                rs.getString("order_id"),
                rs.getString("status"),
                rs.getTimestamp("confirmed_at").toInstant(),
                rs.getLong("total_amount"),
                rs.getInt("item_count")),
        args.toArray());
  }

  /** 직원용 검색 조건(O-6). null 은 거르지 않음. phoneDigits 는 숫자만(완전 일치). */
  public record StaffCriteria(
      @Nullable String orderId,
      @Nullable String accountId,
      @Nullable String status,
      @Nullable String mfcId,
      @Nullable String phoneDigits,
      @Nullable Instant from,
      @Nullable Instant to) {}

  /** 직원용 검색 한 줄. receiverPhone 은 원문(가림은 호출자) — 로그 금지. */
  public record StaffSummary(
      String orderId,
      String accountId,
      String status,
      int version,
      String mfcId,
      String deliveryMode,
      @Nullable LocalDate arrivalDate,
      @Nullable String arrivalStart,
      @Nullable String arrivalEnd,
      Instant confirmedAt,
      long totalAmount,
      long approvedAmount,
      int itemCount,
      int lineCount,
      int plannedShipments,
      int openFulfillmentIssues,
      @Nullable String receiverPhone) {}

  /**
   * 직원용 주문 검색(O-6, 읽기 전용). 최근 확정 순, offset 쪽 나눔. 기간 상한은 호출자가 확인한다. 연락처는 사본의 숫자만 비교(V9 식 색인).
   *
   * @param limit 가져올 최대 건수(다음 쪽 판정을 위해 호출자가 +1 해서 준다)
   */
  public List<StaffSummary> staffSearch(StaffCriteria c, int offset, int limit) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT o.order_id, o.account_id, o.status, o.version, o.mfc_id, o.delivery_mode,"
                + " o.arrival_date, o.arrival_start, o.arrival_end, o.confirmed_at, o.total_amount,"
                + " o.approved_amount, o.planned_shipments,"
                + " (SELECT COALESCE(SUM(l.quantity), 0) FROM customer_order_line l"
                + " WHERE l.order_id = o.order_id) AS item_count,"
                + " (SELECT COUNT(*) FROM customer_order_line l WHERE l.order_id = o.order_id)"
                + " AS line_count,"
                + " (SELECT COUNT(*) FROM order_fulfillment_issue f WHERE f.order_id = o.order_id"
                + " AND f.state = 'OPEN') AS open_issues,"
                + " s.receiver_phone"
                + " FROM customer_order o"
                + " LEFT JOIN customer_order_shipping s ON s.order_id = o.order_id WHERE TRUE");
    List<Object> args = new ArrayList<>();
    if (c.orderId() != null) {
      sql.append(" AND o.order_id = ?");
      args.add(c.orderId());
    }
    if (c.accountId() != null) {
      sql.append(" AND o.account_id = ?");
      args.add(c.accountId());
    }
    if (c.status() != null) {
      sql.append(" AND o.status = ?");
      args.add(c.status());
    }
    if (c.mfcId() != null) {
      sql.append(" AND o.mfc_id = ?");
      args.add(c.mfcId());
    }
    if (c.phoneDigits() != null) {
      sql.append(" AND regexp_replace(s.receiver_phone, '[^0-9]', '', 'g') = ?");
      args.add(c.phoneDigits());
    }
    if (c.from() != null) {
      sql.append(" AND o.confirmed_at >= ?");
      args.add(Timestamp.from(c.from()));
    }
    if (c.to() != null) {
      sql.append(" AND o.confirmed_at < ?");
      args.add(Timestamp.from(c.to()));
    }
    sql.append(" ORDER BY o.confirmed_at DESC, o.order_id DESC LIMIT ? OFFSET ?");
    args.add(limit);
    args.add(offset);
    return jdbc.query(
        sql.toString(),
        (rs, i) -> {
          Date arrival = rs.getDate("arrival_date");
          return new StaffSummary(
              rs.getString("order_id"),
              rs.getString("account_id"),
              rs.getString("status"),
              rs.getInt("version"),
              rs.getString("mfc_id"),
              rs.getString("delivery_mode"),
              arrival == null ? null : arrival.toLocalDate(),
              rs.getString("arrival_start"),
              rs.getString("arrival_end"),
              rs.getTimestamp("confirmed_at").toInstant(),
              rs.getLong("total_amount"),
              rs.getLong("approved_amount"),
              rs.getInt("item_count"),
              rs.getInt("line_count"),
              rs.getInt("planned_shipments"),
              rs.getInt("open_issues"),
              rs.getString("receiver_phone"));
        },
        args.toArray());
  }

  private static OrderRow row(ResultSet rs) throws SQLException {
    Date arrival = rs.getDate("arrival_date");
    return new OrderRow(
        rs.getString("order_id"),
        rs.getString("account_id"),
        rs.getString("draft_id"),
        rs.getString("mfc_id"),
        rs.getString("status"),
        rs.getInt("version"),
        rs.getString("delivery_mode"),
        arrival == null ? null : arrival.toLocalDate(),
        rs.getString("arrival_start"),
        rs.getString("arrival_end"),
        rs.getString("out_of_stock_preference"),
        rs.getString("delivery_note"),
        rs.getString("shopping_address_id"),
        rs.getInt("quote_version"),
        rs.getInt("price_version"),
        rs.getLong("item_amount"),
        rs.getLong("delivery_tip_base"),
        rs.getLong("delivery_tip"),
        rs.getString("delivery_tip_reason"),
        rs.getLong("bag_fee"),
        rs.getLong("total_amount"),
        rs.getBoolean("first_purchase_applied"),
        rs.getInt("weight_g"),
        rs.getInt("planned_shipments"),
        rs.getString("payment_attempt_id"),
        rs.getString("pg_transaction_id"),
        rs.getLong("approved_amount"),
        rs.getString("reservation_id"),
        rs.getString("capacity_hold_id"),
        rs.getString("slot_hold_id"),
        rs.getTimestamp("confirmed_at").toInstant());
  }
}
