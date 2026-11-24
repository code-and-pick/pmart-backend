package com.pmart.promotion.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 행사·쿠폰·점유·상품 사본 저장(ADR-0082). 명시 SQL(JDBC). */
@Repository
public class PromotionStore {

  public record PromotionRow(
      String promotionId,
      String kind,
      String name,
      String state,
      Instant startsAt,
      Instant endsAt,
      @Nullable Instant endedAt,
      String discountType,
      int discountValue,
      @Nullable Integer discountMax,
      @Nullable String issueMode,
      @Nullable Long minOrderAmount,
      @Nullable Integer totalQuantity,
      @Nullable Integer perCustomerLimit,
      @Nullable Integer useDays,
      @Nullable Instant useUntil,
      int issuedCount,
      int usedCount,
      String input,
      @Nullable String draftHash,
      @Nullable String approvalId,
      @Nullable String endHash,
      @Nullable String endReason,
      int version,
      String createdBy,
      Instant createdAt,
      Instant updatedAt) {}

  public record CouponRow(
      String couponId,
      String promotionId,
      String accountId,
      String state,
      Instant issuedAt,
      Instant validUntil,
      String issueChannel,
      @Nullable String issuedBy,
      @Nullable String issueReason,
      @Nullable String orderId,
      @Nullable Instant usedAt) {}

  public record HoldRow(
      String holdId,
      String couponId,
      String orderId,
      String accountId,
      String state,
      long itemAmount,
      long discountAmount,
      @Nullable Boolean couponUsable) {}

  /** 기간 할인 후보 한 줄: 행사 + 걸린 대상. */
  public record Candidate(PromotionRow promotion, String targetType, String targetId) {}

  private final JdbcTemplate jdbc;

  public PromotionStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  // ------------------------------------------------------------------ 행사

  /** 새 초안 또는 초안 고치기(같은 ID 면 덮어쓰고 버전 +1). */
  public void saveDraft(PromotionRow r) {
    int n =
        jdbc.update(
            "UPDATE promotion SET kind = ?, name = ?, state = 'DRAFT', starts_at = ?, ends_at = ?,"
                + " discount_type = ?, discount_value = ?, discount_max = ?, issue_mode = ?,"
                + " min_order_amount = ?, total_quantity = ?, per_customer_limit = ?, use_days = ?,"
                + " use_until = ?, input = ?::jsonb, draft_hash = NULL, version = version + 1,"
                + " updated_at = ? WHERE promotion_id = ?",
            r.kind(),
            r.name(),
            ts(r.startsAt()),
            ts(r.endsAt()),
            r.discountType(),
            r.discountValue(),
            r.discountMax(),
            r.issueMode(),
            r.minOrderAmount(),
            r.totalQuantity(),
            r.perCustomerLimit(),
            r.useDays(),
            ts(r.useUntil()),
            r.input(),
            ts(r.updatedAt()),
            r.promotionId());
    if (n == 0) {
      jdbc.update(
          "INSERT INTO promotion (promotion_id, kind, name, state, starts_at, ends_at, discount_type,"
              + " discount_value, discount_max, issue_mode, min_order_amount, total_quantity,"
              + " per_customer_limit, use_days, use_until, input, version, created_by, created_at,"
              + " updated_at) VALUES (?,?,?,'DRAFT',?,?,?,?,?,?,?,?,?,?,?,?::jsonb,0,?,?,?)",
          r.promotionId(),
          r.kind(),
          r.name(),
          ts(r.startsAt()),
          ts(r.endsAt()),
          r.discountType(),
          r.discountValue(),
          r.discountMax(),
          r.issueMode(),
          r.minOrderAmount(),
          r.totalQuantity(),
          r.perCustomerLimit(),
          r.useDays(),
          ts(r.useUntil()),
          r.input(),
          r.createdBy(),
          ts(r.createdAt()),
          ts(r.updatedAt()));
    }
  }

  public void replaceTargets(String promotionId, List<String[]> targets) {
    jdbc.update("DELETE FROM promotion_target WHERE promotion_id = ?", promotionId);
    for (String[] t : targets) {
      jdbc.update(
          "INSERT INTO promotion_target (promotion_id, target_type, target_id) VALUES (?,?,?)"
              + " ON CONFLICT DO NOTHING",
          promotionId,
          t[0],
          t[1]);
    }
  }

  public List<String[]> targets(String promotionId) {
    return jdbc.query(
        "SELECT target_type, target_id FROM promotion_target WHERE promotion_id = ?"
            + " ORDER BY target_type DESC, target_id",
        (rs, i) -> new String[] {rs.getString(1), rs.getString(2)},
        promotionId);
  }

  public Optional<PromotionRow> find(String promotionId) {
    return jdbc
        .query(
            "SELECT * FROM promotion WHERE promotion_id = ?", (rs, i) -> promotion(rs), promotionId)
        .stream()
        .findFirst();
  }

  public Optional<PromotionRow> lock(String promotionId) {
    return jdbc
        .query(
            "SELECT * FROM promotion WHERE promotion_id = ? FOR UPDATE",
            (rs, i) -> promotion(rs),
            promotionId)
        .stream()
        .findFirst();
  }

  public List<PromotionRow> list(
      @Nullable String kind,
      @Nullable String state,
      @Nullable Instant afterUpdatedAt,
      @Nullable String afterId,
      int limit) {
    StringBuilder sql = new StringBuilder("SELECT * FROM promotion WHERE 1 = 1");
    List<Object> args = new ArrayList<>();
    if (kind != null) {
      sql.append(" AND kind = ?");
      args.add(kind);
    }
    if (state != null) {
      sql.append(" AND state = ?");
      args.add(state);
    }
    if (afterUpdatedAt != null && afterId != null) {
      sql.append(" AND (updated_at, promotion_id) < (?, ?)");
      args.add(ts(afterUpdatedAt));
      args.add(afterId);
    }
    sql.append(" ORDER BY updated_at DESC, promotion_id DESC LIMIT ?");
    args.add(limit);
    return jdbc.query(sql.toString(), (rs, i) -> promotion(rs), args.toArray());
  }

  public void submitted(String promotionId, String hash, Instant at) {
    jdbc.update(
        "UPDATE promotion SET state = 'SUBMITTED', draft_hash = ?, version = version + 1,"
            + " updated_at = ? WHERE promotion_id = ?",
        hash,
        ts(at),
        promotionId);
  }

  public void decided(String promotionId, String state, String approvalId, Instant at) {
    jdbc.update(
        "UPDATE promotion SET state = ?, approval_id = ?, version = version + 1, updated_at = ?"
            + " WHERE promotion_id = ?",
        state,
        approvalId,
        ts(at),
        promotionId);
  }

  public void endSubmitted(
      String promotionId, @Nullable String hash, @Nullable String reason, Instant at) {
    jdbc.update(
        "UPDATE promotion SET end_hash = ?, end_reason = ?, version = version + 1, updated_at = ?"
            + " WHERE promotion_id = ?",
        hash,
        reason,
        ts(at),
        promotionId);
  }

  public void ended(String promotionId, Instant at) {
    jdbc.update(
        "UPDATE promotion SET state = 'ENDED', ended_at = ?, version = version + 1, updated_at = ?"
            + " WHERE promotion_id = ?",
        ts(at),
        ts(at),
        promotionId);
  }

  /** 발급 수 하나 올리기 — 총 수량을 넘으면 false(행은 호출자가 잠근다). */
  public boolean incrementIssued(String promotionId) {
    return jdbc.update(
            "UPDATE promotion SET issued_count = issued_count + 1 WHERE promotion_id = ?"
                + " AND (total_quantity IS NULL OR issued_count < total_quantity)",
            promotionId)
        == 1;
  }

  public void addUsed(String promotionId, int delta) {
    jdbc.update(
        "UPDATE promotion SET used_count = GREATEST(used_count + ?, 0) WHERE promotion_id = ?",
        delta,
        promotionId);
  }

  /** 진행 중(at 기준)인 쿠폰 행사(발급 방식). */
  public List<PromotionRow> runningCoupons(String issueMode, Instant at) {
    return jdbc.query(
        "SELECT * FROM promotion WHERE kind = 'COUPON' AND state = 'APPROVED' AND issue_mode = ?"
            + " AND starts_at <= ? AND ends_at > ? ORDER BY ends_at, promotion_id",
        (rs, i) -> promotion(rs),
        issueMode,
        ts(at),
        ts(at));
  }

  /**
   * 이 SKU 들에 at 시각 걸린 기간 할인 후보(SKU 지정 + 카테고리 — 카테고리는 sku_category 사본으로, 0.3.0 테마 — 받아 둔 진열 테마 사본의
   * 상품(sku_product)으로).
   */
  public List<Candidate> priceCandidates(Collection<String> skus, Instant at) {
    if (skus.isEmpty()) {
      return List.of();
    }
    String arr = "{" + String.join(",", skus) + "}";
    return jdbc.query(
        "SELECT p.*, t.target_type AS t_type, t.target_id AS t_id FROM promotion p"
            + " JOIN promotion_target t ON t.promotion_id = p.promotion_id"
            + " WHERE p.kind = 'PRICE_DISCOUNT' AND p.state = 'APPROVED' AND p.starts_at <= ?"
            + " AND p.ends_at > ? AND (p.ended_at IS NULL OR p.ended_at > ?)"
            + " AND ((t.target_type = 'SKU' AND t.target_id = ANY (?::varchar[]))"
            + " OR (t.target_type = 'CATEGORY' AND t.target_id IN"
            + " (SELECT category_id FROM sku_category WHERE sku = ANY (?::varchar[])))"
            + " OR (t.target_type = 'THEME' AND t.target_id IN"
            + " (SELECT tp.section_id FROM theme_copy_product tp"
            + " JOIN sku_product sp ON sp.product_id = tp.product_id"
            + " WHERE sp.sku = ANY (?::varchar[]))))",
        (rs, i) -> new Candidate(promotion(rs), rs.getString("t_type"), rs.getString("t_id")),
        ts(at),
        ts(at),
        ts(at),
        arr,
        arr,
        arr);
  }

  /** SKU → 그 상품이 든 진열 테마 sectionId(받아 둔 사본, 0.3.0). */
  public Map<String, List<String>> themesOf(Collection<String> skus) {
    Map<String, List<String>> out = new HashMap<>();
    if (skus.isEmpty()) {
      return out;
    }
    jdbc.query(
        "SELECT sp.sku, tp.section_id FROM sku_product sp"
            + " JOIN theme_copy_product tp ON tp.product_id = sp.product_id"
            + " WHERE sp.sku = ANY (?::varchar[])",
        rs -> {
          out.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2));
        },
        "{" + String.join(",", skus) + "}");
    return out;
  }

  // ------------------------------------------------------------------ 상품 사본

  /** SKU → 카테고리 사본 바꾸기. 더 오래된 버전이면 무시. */
  public void projectSku(String sku, String productId, int version, Collection<String> categories) {
    Integer current =
        jdbc
            .query(
                "SELECT product_version FROM sku_product WHERE sku = ? FOR UPDATE",
                (rs, i) -> rs.getInt(1),
                sku)
            .stream()
            .findFirst()
            .orElse(null);
    if (current != null && current > version) {
      return;
    }
    jdbc.update(
        "INSERT INTO sku_product (sku, product_id, product_version) VALUES (?,?,?)"
            + " ON CONFLICT (sku) DO UPDATE SET product_id = EXCLUDED.product_id,"
            + " product_version = EXCLUDED.product_version",
        sku,
        productId,
        version);
    jdbc.update("DELETE FROM sku_category WHERE sku = ?", sku);
    for (String c : categories) {
      jdbc.update(
          "INSERT INTO sku_category (sku, category_id) VALUES (?,?) ON CONFLICT DO NOTHING",
          sku,
          c);
    }
  }

  public Map<String, List<String>> categoriesOf(Collection<String> skus) {
    Map<String, List<String>> out = new HashMap<>();
    if (skus.isEmpty()) {
      return out;
    }
    jdbc.query(
        "SELECT sku, category_id FROM sku_category WHERE sku = ANY (?::varchar[])",
        rs -> {
          out.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2));
        },
        "{" + String.join(",", skus) + "}");
    return out;
  }

  // ------------------------------------------------------------------ 쿠폰

  public void insertCoupon(CouponRow c, Instant at) {
    jdbc.update(
        "INSERT INTO coupon (coupon_id, promotion_id, account_id, state, issued_at, valid_until,"
            + " issue_channel, issued_by, issue_reason, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
        c.couponId(),
        c.promotionId(),
        c.accountId(),
        c.state(),
        ts(c.issuedAt()),
        ts(c.validUntil()),
        c.issueChannel(),
        c.issuedBy(),
        c.issueReason(),
        ts(at));
  }

  public int issuedTo(String promotionId, String accountId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM coupon WHERE promotion_id = ? AND account_id = ?",
            Integer.class,
            promotionId,
            accountId);
    return n == null ? 0 : n;
  }

  public Map<String, Integer> issuedToByPromotion(String accountId) {
    Map<String, Integer> out = new HashMap<>();
    jdbc.query(
        "SELECT promotion_id, count(*) FROM coupon WHERE account_id = ? GROUP BY promotion_id",
        rs -> {
          out.put(rs.getString(1), rs.getInt(2));
        },
        accountId);
    return out;
  }

  public List<CouponRow> couponsOf(String accountId) {
    return jdbc.query(
        "SELECT * FROM coupon WHERE account_id = ? ORDER BY issued_at DESC, coupon_id DESC",
        (rs, i) -> coupon(rs),
        accountId);
  }

  public Optional<CouponRow> lockCoupon(String couponId) {
    return jdbc
        .query(
            "SELECT * FROM coupon WHERE coupon_id = ? FOR UPDATE", (rs, i) -> coupon(rs), couponId)
        .stream()
        .findFirst();
  }

  public Optional<CouponRow> findCoupon(String couponId) {
    return jdbc
        .query("SELECT * FROM coupon WHERE coupon_id = ?", (rs, i) -> coupon(rs), couponId)
        .stream()
        .findFirst();
  }

  public void couponState(
      String couponId,
      String state,
      @Nullable String orderId,
      @Nullable Instant usedAt,
      Instant at) {
    jdbc.update(
        "UPDATE coupon SET state = ?, order_id = ?, used_at = ?, updated_at = ? WHERE coupon_id = ?",
        state,
        orderId,
        ts(usedAt),
        ts(at),
        couponId);
  }

  // ------------------------------------------------------------------ 점유

  public void insertHold(HoldRow h, Instant at) {
    jdbc.update(
        "INSERT INTO coupon_hold (hold_id, coupon_id, order_id, account_id, state, item_amount,"
            + " discount_amount, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?)",
        h.holdId(),
        h.couponId(),
        h.orderId(),
        h.accountId(),
        h.state(),
        h.itemAmount(),
        h.discountAmount(),
        ts(at),
        ts(at));
  }

  public Optional<HoldRow> findHold(String holdId) {
    return jdbc
        .query("SELECT * FROM coupon_hold WHERE hold_id = ?", (rs, i) -> hold(rs), holdId)
        .stream()
        .findFirst();
  }

  public Optional<HoldRow> lockHold(String holdId) {
    return jdbc
        .query(
            "SELECT * FROM coupon_hold WHERE hold_id = ? FOR UPDATE", (rs, i) -> hold(rs), holdId)
        .stream()
        .findFirst();
  }

  public Optional<HoldRow> liveHoldFor(String couponId, String orderId) {
    return jdbc
        .query(
            "SELECT * FROM coupon_hold WHERE coupon_id = ? AND order_id = ?"
                + " AND state IN ('HELD', 'CONFIRMED')",
            (rs, i) -> hold(rs),
            couponId,
            orderId)
        .stream()
        .findFirst();
  }

  public void holdState(String holdId, String state, @Nullable Boolean couponUsable, Instant at) {
    jdbc.update(
        "UPDATE coupon_hold SET state = ?, coupon_usable = ?, updated_at = ? WHERE hold_id = ?",
        state,
        couponUsable,
        ts(at),
        holdId);
  }

  // ------------------------------------------------------------------ 행 읽기

  private static PromotionRow promotion(ResultSet rs) throws SQLException {
    return new PromotionRow(
        rs.getString("promotion_id"),
        rs.getString("kind"),
        rs.getString("name"),
        rs.getString("state"),
        rs.getTimestamp("starts_at").toInstant(),
        rs.getTimestamp("ends_at").toInstant(),
        instant(rs.getTimestamp("ended_at")),
        rs.getString("discount_type"),
        rs.getInt("discount_value"),
        (Integer) rs.getObject("discount_max"),
        rs.getString("issue_mode"),
        (Long) rs.getObject("min_order_amount"),
        (Integer) rs.getObject("total_quantity"),
        (Integer) rs.getObject("per_customer_limit"),
        (Integer) rs.getObject("use_days"),
        instant(rs.getTimestamp("use_until")),
        rs.getInt("issued_count"),
        rs.getInt("used_count"),
        rs.getString("input"),
        rs.getString("draft_hash"),
        rs.getString("approval_id"),
        rs.getString("end_hash"),
        rs.getString("end_reason"),
        rs.getInt("version"),
        rs.getString("created_by"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }

  private static CouponRow coupon(ResultSet rs) throws SQLException {
    return new CouponRow(
        rs.getString("coupon_id"),
        rs.getString("promotion_id"),
        rs.getString("account_id"),
        rs.getString("state"),
        rs.getTimestamp("issued_at").toInstant(),
        rs.getTimestamp("valid_until").toInstant(),
        rs.getString("issue_channel"),
        rs.getString("issued_by"),
        rs.getString("issue_reason"),
        rs.getString("order_id"),
        instant(rs.getTimestamp("used_at")));
  }

  private static HoldRow hold(ResultSet rs) throws SQLException {
    return new HoldRow(
        rs.getString("hold_id"),
        rs.getString("coupon_id"),
        rs.getString("order_id"),
        rs.getString("account_id"),
        rs.getString("state"),
        rs.getLong("item_amount"),
        rs.getLong("discount_amount"),
        (Boolean) rs.getObject("coupon_usable"));
  }

  private static @Nullable Instant instant(@Nullable Timestamp t) {
    return t == null ? null : t.toInstant();
  }

  private static @Nullable Timestamp ts(@Nullable Instant i) {
    return i == null ? null : Timestamp.from(i);
  }
}
