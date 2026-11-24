package com.pmart.promotion.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 지정 발급 쿠폰의 받을 고객(0.3.0 V5 — 만들 때 정함). result NULL = 아직, 시작 시각이 되면 {@code RecipientGrants} 가 채운다.
 */
@Repository
public class RecipientStore {

  public record RecipientRow(
      String accountId,
      int seq,
      @Nullable String result,
      @Nullable String couponId,
      @Nullable Instant processedAt) {}

  private final JdbcTemplate jdbc;

  public RecipientStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 초안 저장 때 통째로 바꾼다(recipients 가 없으면 비움). */
  public void replace(
      String promotionId, @Nullable List<String> accountIds, @Nullable String reason) {
    jdbc.update("DELETE FROM promotion_recipient WHERE promotion_id = ?", promotionId);
    jdbc.update(
        "UPDATE promotion SET recipient_reason = ? WHERE promotion_id = ?",
        accountIds == null ? null : reason,
        promotionId);
    if (accountIds == null) {
      return;
    }
    int seq = 0;
    for (String a : accountIds) {
      jdbc.update(
          "INSERT INTO promotion_recipient (promotion_id, account_id, seq) VALUES (?,?,?)"
              + " ON CONFLICT DO NOTHING",
          promotionId,
          a,
          seq++);
    }
  }

  public List<RecipientRow> of(String promotionId) {
    return jdbc.query(
        "SELECT account_id, seq, result, coupon_id, processed_at FROM promotion_recipient"
            + " WHERE promotion_id = ? ORDER BY seq",
        (rs, i) -> {
          Timestamp t = rs.getTimestamp("processed_at");
          return new RecipientRow(
              rs.getString("account_id"),
              rs.getInt("seq"),
              rs.getString("result"),
              rs.getString("coupon_id"),
              t == null ? null : t.toInstant());
        },
        promotionId);
  }

  public Optional<String> reason(String promotionId) {
    return jdbc
        .query(
            "SELECT recipient_reason FROM promotion WHERE promotion_id = ?",
            (rs, i) -> rs.getString(1),
            promotionId)
        .stream()
        .filter(java.util.Objects::nonNull)
        .findFirst();
  }

  /** 지금 진행 중인 지정 발급 행사 중 아직 안 준 고객이 있는 것. */
  public List<String> duePromotions(Instant at) {
    Timestamp t = Timestamp.from(at);
    return jdbc.queryForList(
        "SELECT p.promotion_id FROM promotion p WHERE p.kind = 'COUPON' AND p.issue_mode = 'ADMIN'"
            + " AND p.state = 'APPROVED' AND p.starts_at <= ? AND p.ends_at > ?"
            + " AND (p.ended_at IS NULL OR p.ended_at > ?)"
            + " AND EXISTS (SELECT 1 FROM promotion_recipient r WHERE r.promotion_id = p.promotion_id"
            + " AND r.result IS NULL) ORDER BY p.starts_at, p.promotion_id",
        String.class,
        t,
        t,
        t);
  }

  public List<String> pending(String promotionId) {
    return jdbc.queryForList(
        "SELECT account_id FROM promotion_recipient WHERE promotion_id = ? AND result IS NULL"
            + " ORDER BY seq",
        String.class,
        promotionId);
  }

  public void done(
      String promotionId, String accountId, String result, @Nullable String couponId, Instant at) {
    jdbc.update(
        "UPDATE promotion_recipient SET result = ?, coupon_id = ?, processed_at = ?"
            + " WHERE promotion_id = ? AND account_id = ? AND result IS NULL",
        result,
        couponId,
        Timestamp.from(at),
        promotionId,
        accountId);
  }
}
