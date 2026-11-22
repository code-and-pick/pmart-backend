package com.pmart.delivery.dispatch.application;

import com.pmart.common.security.PmartPrincipal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 라이더 한 명의 배정 이력(2026-11-18 — 라이더 백오피스 요청 #4). 수락된 제안(ACCEPTED)마다 한 줄 + 그 배송의 지금 배정·이 라이더를 푼 배차 취소
 * 기록. RIDER_OPS·OPERATOR_ADMIN·CS_AGENT(보기). 개인정보 없음.
 */
@Service
public class RiderAssignmentHistory {

  public record Cancellation(
      String requestedBy, String reason, @Nullable String reasonDetail, Instant at) {}

  public record Item(
      String deliveryId,
      @Nullable String orderId,
      @Nullable String mfcId,
      @Nullable String type,
      @Nullable String purpose,
      String offerId,
      String source,
      @Nullable Integer fee,
      Instant assignedAt,
      String deliveryState,
      String outcome,
      @Nullable Cancellation cancellation,
      @Nullable String deliveryNumber) {}

  private final JdbcTemplate jdbc;

  public RiderAssignmentHistory(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<Item> of(String riderId, int limit, int offset) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .filter(x -> x.type() == PmartPrincipal.PrincipalType.OPERATOR)
            .orElseThrow(() -> OfferService.forbidden("직원만 볼 수 있습니다."));
    if (!(p.hasRole("RIDER_OPS") || p.hasRole("OPERATOR_ADMIN") || p.hasRole("CS_AGENT"))) {
      throw OfferService.forbidden("라이더 배정 이력은 라이더 운영·관리자·상담원만 볼 수 있습니다.");
    }
    return jdbc.query(
        "SELECT o.offer_id, o.delivery_id, o.source, o.fee, COALESCE(o.decided_at, o.sent_at) AS"
            + " assigned_at, d.order_id, d.mfc_id, d.type, d.purpose, d.state, d.delivery_seq,"
            + " d.order_summary->>'orderNumber' AS order_number, d.rider_id AS"
            + " current_rider, c.requested_by, c.reason, c.reason_detail, c.created_at AS"
            + " cancelled_at FROM dispatch_offer o JOIN delivery d ON d.delivery_id = o.delivery_id"
            + " LEFT JOIN LATERAL (SELECT * FROM assignment_cancellation x WHERE x.delivery_id ="
            + " o.delivery_id AND x.previous_rider_id = o.rider_id AND x.state ="
            + " 'CANCELLED_BEFORE_PICKUP' AND x.created_at >= COALESCE(o.decided_at, o.sent_at)"
            + " ORDER BY x.created_at LIMIT 1) c ON TRUE WHERE o.rider_id = ? AND o.state ="
            + " 'ACCEPTED' ORDER BY assigned_at DESC, o.offer_id DESC LIMIT ? OFFSET ?",
        (rs, i) -> {
          String state = rs.getString("state");
          boolean mine = riderId.equals(rs.getString("current_rider"));
          Timestamp cancelledAt = rs.getTimestamp("cancelled_at");
          Cancellation c =
              cancelledAt == null
                  ? null
                  : new Cancellation(
                      rs.getString("requested_by"),
                      rs.getString("reason"),
                      rs.getString("reason_detail"),
                      cancelledAt.toInstant());
          String outcome;
          if (c != null || !mine) {
            outcome =
                "CANCELLED_BEFORE_PICKUP".equals(state) && c == null ? "CANCELLED" : "RELEASED";
          } else if ("CLOSED".equals(state)) {
            outcome = "DONE";
          } else if ("CANCELLED_BEFORE_PICKUP".equals(state)) {
            outcome = "CANCELLED";
          } else {
            outcome = "CURRENT";
          }
          int fee = rs.getInt("fee");
          return new Item(
              rs.getString("delivery_id"),
              rs.getString("order_id"),
              rs.getString("mfc_id"),
              rs.getString("type"),
              rs.getString("purpose"),
              rs.getString("offer_id"),
              rs.getString("source"),
              rs.wasNull() ? null : fee,
              rs.getTimestamp("assigned_at").toInstant(),
              state,
              outcome,
              c,
              deliveryNumber(
                  rs.getString("order_number"), rs.getObject("delivery_seq", Integer.class)));
        },
        riderId,
        limit,
        offset);
  }

  /** 화면 배송 번호(ADR-0085) = D + 화면용 주문번호, 두 번째 배송부터 -순번(D74939255, D74939255-2). 모르면 null. */
  public static @Nullable String deliveryNumber(
      @Nullable String orderNumber, @Nullable Integer seq) {
    return orderNumber == null || seq == null
        ? null
        : "D" + orderNumber + (seq > 1 ? "-" + seq : "");
  }
}
