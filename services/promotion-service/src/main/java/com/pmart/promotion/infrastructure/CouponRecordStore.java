package com.pmart.promotion.infrastructure;

import com.pmart.promotion.domain.CouponRecordRules;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 쿠폰 발급 기록 조회(0.3.1 — 직원). 쿠폰 행 + 행사 이름·할인. 상태·받은 경로 거르기는 SQL 로({@code domain/CouponRecordRules} 와
 * 같은 뜻).
 */
@Repository
public class CouponRecordStore {

  public record RecordRow(
      String couponId,
      String promotionId,
      String promotionName,
      String accountId,
      String state,
      Instant issuedAt,
      Instant validUntil,
      String issueChannel,
      @Nullable String issuedBy,
      @Nullable String issueReason,
      @Nullable String orderId,
      @Nullable Instant usedAt,
      String discountType,
      int discountValue,
      @Nullable Integer discountMax) {}

  private static final String SYSTEM = CouponRecordRules.SYSTEM;

  private final JdbcTemplate jdbc;

  public CouponRecordStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * 받은 시각 최근 순 한 쪽.
   *
   * @return size+1 개까지 — 호출 쪽이 다음 쪽 여부를 판단
   */
  public List<RecordRow> page(
      @Nullable String customerId,
      @Nullable String promotionId,
      @Nullable String status,
      @Nullable String source,
      @Nullable Instant afterIssuedAt,
      @Nullable String afterCouponId,
      Instant now,
      int size) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT c.*, p.name AS p_name, p.discount_type, p.discount_value, p.discount_max"
                + " FROM coupon c JOIN promotion p ON p.promotion_id = c.promotion_id WHERE 1 = 1");
    List<Object> args = new ArrayList<>();
    if (customerId != null) {
      sql.append(" AND c.account_id = ?");
      args.add(customerId);
    }
    if (promotionId != null) {
      sql.append(" AND c.promotion_id = ?");
      args.add(promotionId);
    }
    if (status != null) {
      switch (status) {
        case "HELD", "USED" -> {
          sql.append(" AND c.state = ?");
          args.add(status);
        }
        case "AVAILABLE" -> {
          sql.append(" AND c.state = 'AVAILABLE' AND c.valid_until >= ?");
          args.add(Timestamp.from(now));
        }
        case "EXPIRED" -> {
          sql.append(" AND c.state = 'AVAILABLE' AND c.valid_until < ?");
          args.add(Timestamp.from(now));
        }
        default -> throw new IllegalArgumentException(status);
      }
    }
    if (source != null) {
      switch (source) {
        case "DOWNLOAD" -> sql.append(" AND c.issue_channel = 'DOWNLOAD'");
        case "SIGNUP" -> sql.append(" AND c.issue_channel = 'SIGNUP'");
        case "STAFF" ->
            sql.append(
                " AND c.issue_channel = 'ADMIN' AND c.issued_by IS DISTINCT FROM '" + SYSTEM + "'");
        case "COMPENSATION" ->
            sql.append(" AND c.issue_channel = 'ADMIN' AND c.issued_by = '" + SYSTEM + "'");
        default -> throw new IllegalArgumentException(source);
      }
    }
    if (afterIssuedAt != null && afterCouponId != null) {
      sql.append(" AND (c.issued_at, c.coupon_id) < (?, ?)");
      args.add(Timestamp.from(afterIssuedAt));
      args.add(afterCouponId);
    }
    sql.append(" ORDER BY c.issued_at DESC, c.coupon_id DESC LIMIT ?");
    args.add(size + 1);
    return jdbc.query(
        sql.toString(),
        (rs, i) -> {
          Timestamp used = rs.getTimestamp("used_at");
          Object max = rs.getObject("discount_max");
          return new RecordRow(
              rs.getString("coupon_id"),
              rs.getString("promotion_id"),
              rs.getString("p_name"),
              rs.getString("account_id"),
              rs.getString("state"),
              rs.getTimestamp("issued_at").toInstant(),
              rs.getTimestamp("valid_until").toInstant(),
              rs.getString("issue_channel"),
              rs.getString("issued_by"),
              rs.getString("issue_reason"),
              rs.getString("order_id"),
              used == null ? null : used.toInstant(),
              rs.getString("discount_type"),
              rs.getInt("discount_value"),
              max == null ? null : ((Number) max).intValue());
        },
        args.toArray());
  }
}
