package com.pmart.cases.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 문의 만족도(V13 {@code inquiry_satisfaction}, 0.15.0). */
@Repository
public class InquirySatisfactionStore {

  public record SatisfactionRow(
      String inquiryId, String customerId, int score, @Nullable String comment, Instant ratedAt) {}

  /** 답변 시각이 기간 안인 고객 문의(상담원 접수 제외) 중 — 분모 answered, 분자 rated, 점수 합·분포. */
  public record Summary(long answered, long rated, long scoreSum, Map<Integer, Long> byScore) {}

  private final JdbcTemplate jdbc;

  public InquirySatisfactionStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(SatisfactionRow r) {
    jdbc.update(
        "INSERT INTO inquiry_satisfaction (inquiry_id, customer_id, score, comment, rated_at)"
            + " VALUES (?, ?, ?, ?, ?)",
        r.inquiryId(),
        r.customerId(),
        r.score(),
        r.comment(),
        Timestamp.from(r.ratedAt()));
  }

  public Optional<SatisfactionRow> find(String inquiryId) {
    return jdbc
        .query(
            "SELECT inquiry_id, customer_id, score, comment, rated_at FROM inquiry_satisfaction"
                + " WHERE inquiry_id = ?",
            (rs, n) ->
                new SatisfactionRow(
                    rs.getString("inquiry_id"),
                    rs.getString("customer_id"),
                    rs.getInt("score"),
                    rs.getString("comment"),
                    rs.getTimestamp("rated_at").toInstant()),
            inquiryId)
        .stream()
        .findFirst();
  }

  public Summary summary(Instant from, Instant to) {
    String base =
        " FROM cs_inquiry q LEFT JOIN inquiry_satisfaction s ON s.inquiry_id = q.inquiry_id"
            + " WHERE q.requester_type = 'CUSTOMER' AND q.origin <> 'STAFF_INTAKE'"
            + " AND q.answered_at >= ? AND q.answered_at < ?";
    Timestamp f = Timestamp.from(from);
    Timestamp t = Timestamp.from(to);
    List<long[]> totals =
        jdbc.query(
            "SELECT count(*), count(s.inquiry_id), coalesce(sum(s.score), 0)" + base,
            (rs, n) -> new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3)},
            f,
            t);
    Map<Integer, Long> byScore = new TreeMap<>();
    for (int i = 1; i <= 5; i++) {
      byScore.put(i, 0L);
    }
    jdbc.query(
        "SELECT s.score, count(*)" + base + " AND s.score IS NOT NULL GROUP BY s.score",
        rs -> {
          byScore.put(rs.getInt(1), rs.getLong(2));
        },
        f,
        t);
    long[] x = totals.get(0);
    return new Summary(x[0], x[1], x[2], byScore);
  }
}
