package com.pmart.promotion.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 할인가 표시(ADR-0082 4단계) — 가격 사본(sku_price)과 마지막으로 알린 표시 할인(sku_display_discount). */
@Repository
public class DisplayDiscountStore {

  public record SkuPrice(String sku, long price, int priceVersion) {}

  /** 알린 표시 할인. 할인 없음 = unitDiscount 0·promotionId null. */
  public record Shown(
      String sku,
      int seq,
      int priceVersion,
      long basePrice,
      long unitDiscount,
      long salePrice,
      int ratePercent,
      @Nullable String promotionId,
      @Nullable String promotionName,
      @Nullable Instant endsAt) {}

  private final JdbcTemplate jdbc;

  public DisplayDiscountStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 가격 사본 바꾸기 — 더 큰 버전만. 바뀌었으면 true. */
  public boolean projectPrice(String sku, long price, int priceVersion, Instant now) {
    return jdbc.update(
            "INSERT INTO sku_price (sku, price, price_version, updated_at) VALUES (?,?,?,?)"
                + " ON CONFLICT (sku) DO UPDATE SET price = EXCLUDED.price,"
                + " price_version = EXCLUDED.price_version, updated_at = EXCLUDED.updated_at"
                + " WHERE EXCLUDED.price_version > sku_price.price_version",
            sku,
            price,
            priceVersion,
            Timestamp.from(now))
        == 1;
  }

  /** 지금 진행 중인 기간 할인이 걸린(SKU 직접 또는 카테고리) 가격 있는 SKU. */
  public List<String> targetedSkus(Instant at) {
    Timestamp t = Timestamp.from(at);
    return jdbc.queryForList(
        "SELECT DISTINCT sp.sku FROM promotion p"
            + " JOIN promotion_target pt ON pt.promotion_id = p.promotion_id"
            + " JOIN sku_price sp ON (pt.target_type = 'SKU' AND sp.sku = pt.target_id)"
            + " OR (pt.target_type = 'CATEGORY' AND sp.sku IN"
            + " (SELECT sc.sku FROM sku_category sc WHERE sc.category_id = pt.target_id))"
            + " OR (pt.target_type = 'THEME' AND sp.sku IN (SELECT s.sku FROM sku_product s"
            + " JOIN theme_copy_product tp ON tp.product_id = s.product_id"
            + " WHERE tp.section_id = pt.target_id))"
            + " WHERE p.kind = 'PRICE_DISCOUNT' AND p.state = 'APPROVED' AND p.starts_at <= ?"
            + " AND p.ends_at > ? AND (p.ended_at IS NULL OR p.ended_at > ?)",
        String.class,
        t,
        t,
        t);
  }

  /** 지금 할인으로 보이고 있는 SKU(끝났거나 가격이 바뀌었을 수 있다). */
  public List<String> shownDiscountedSkus() {
    return jdbc.queryForList(
        "SELECT sku FROM sku_display_discount WHERE unit_discount > 0", String.class);
  }

  public Map<String, SkuPrice> prices(Collection<String> skus) {
    Map<String, SkuPrice> out = new HashMap<>();
    if (skus.isEmpty()) {
      return out;
    }
    jdbc.query(
        "SELECT sku, price, price_version FROM sku_price WHERE sku = ANY (?::varchar[])",
        rs -> {
          out.put(rs.getString(1), new SkuPrice(rs.getString(1), rs.getLong(2), rs.getInt(3)));
        },
        array(skus));
    return out;
  }

  /** 알린 표시 할인(잠금 — 같은 SKU 를 두 작업이 동시에 알리지 않게). */
  public Map<String, Shown> shownForUpdate(Collection<String> skus) {
    Map<String, Shown> out = new HashMap<>();
    if (skus.isEmpty()) {
      return out;
    }
    jdbc.query(
        "SELECT * FROM sku_display_discount WHERE sku = ANY (?::varchar[]) FOR UPDATE",
        rs -> {
          Timestamp ends = rs.getTimestamp("ends_at");
          out.put(
              rs.getString("sku"),
              new Shown(
                  rs.getString("sku"),
                  rs.getInt("seq"),
                  rs.getInt("price_version"),
                  rs.getLong("base_price"),
                  rs.getLong("unit_discount"),
                  rs.getLong("sale_price"),
                  rs.getInt("rate_percent"),
                  rs.getString("promotion_id"),
                  rs.getString("promotion_name"),
                  ends == null ? null : ends.toInstant()));
        },
        array(skus));
    return out;
  }

  public void save(Shown s, Instant now) {
    jdbc.update(
        "INSERT INTO sku_display_discount (sku, seq, price_version, base_price, unit_discount, sale_price,"
            + " rate_percent, promotion_id, promotion_name, ends_at, updated_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?)"
            + " ON CONFLICT (sku) DO UPDATE SET seq = EXCLUDED.seq, price_version = EXCLUDED.price_version,"
            + " base_price = EXCLUDED.base_price, unit_discount = EXCLUDED.unit_discount,"
            + " sale_price = EXCLUDED.sale_price, rate_percent = EXCLUDED.rate_percent,"
            + " promotion_id = EXCLUDED.promotion_id, promotion_name = EXCLUDED.promotion_name,"
            + " ends_at = EXCLUDED.ends_at, updated_at = EXCLUDED.updated_at",
        s.sku(),
        s.seq(),
        s.priceVersion(),
        s.basePrice(),
        s.unitDiscount(),
        s.salePrice(),
        s.ratePercent(),
        s.promotionId(),
        s.promotionName(),
        s.endsAt() == null ? null : Timestamp.from(s.endsAt()),
        Timestamp.from(now));
  }

  /** 여러 인스턴스가 같은 주기에 겹치지 않게(트랜잭션 끝에 풀림). */
  public boolean tryLock() {
    Boolean ok =
        jdbc.queryForObject(
            "SELECT pg_try_advisory_xact_lock(hashtext('promotion-display-discount-sync'))",
            Boolean.class);
    return Boolean.TRUE.equals(ok);
  }

  private static String array(Collection<String> skus) {
    return "{" + String.join(",", skus) + "}";
  }
}
