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

  /** 직원 한 명 몫(0.38.0). agentId 가 null 이면 AGENT 답변 기록이 없는 답변된 문의. */
  public record AgentSummary(@Nullable String agentId, Summary summary) {}

  /** 직원 몫 평가 한 줄(0.38.0) — 고객 ID 는 싣지 않는다. comment 는 원문(가리기는 application). */
  public record RatingRow(
      String inquiryId,
      @Nullable String inquiryNumber,
      int score,
      @Nullable String comment,
      Instant ratedAt,
      Instant answeredAt) {}

  /** 기간·대상 조건 — 전체 요약과 같다(고객 문의, 상담원 접수 제외, 답변 시각 [from, to)). */
  private static final String BASE =
      " FROM cs_inquiry q LEFT JOIN inquiry_satisfaction s ON s.inquiry_id = q.inquiry_id"
          + " WHERE q.requester_type = 'CUSTOMER' AND q.origin <> 'STAFF_INTAKE'"
          + " AND q.answered_at >= ? AND q.answered_at < ?";

  /**
   * 문의 몫 직원(0.38.0, 사용자 2026-11-24 추천안): 평가했으면 평가 시각 전 마지막 AGENT 답변자, 아니면 지금 마지막 AGENT 답변자 — 한 명만.
   */
  private static final String OWNER =
      "(SELECT r.author_id FROM cs_inquiry_reply r WHERE r.inquiry_id = q.inquiry_id"
          + " AND r.author_type = 'AGENT' AND (s.rated_at IS NULL OR r.created_at <= s.rated_at)"
          + " ORDER BY r.created_at DESC, r.seq DESC LIMIT 1)";

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
    String base = BASE;
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

  /** 직원별(0.38.0) — 줄을 모두 더하면 {@link #summary} 와 같다. 답변 수 많은 순. */
  public List<AgentSummary> byAgent(Instant from, Instant to) {
    StringBuilder by = new StringBuilder();
    for (int i = 1; i <= 5; i++) {
      by.append(", count(*) FILTER (WHERE x.score = ").append(i).append(')');
    }
    return jdbc.query(
        "SELECT x.agent_id, count(*), count(x.score), coalesce(sum(x.score), 0)"
            + by
            + " FROM (SELECT s.score, "
            + OWNER
            + " AS agent_id"
            + BASE
            + ") x GROUP BY x.agent_id ORDER BY count(*) DESC, x.agent_id NULLS LAST",
        (rs, n) -> {
          Map<Integer, Long> byScore = new TreeMap<>();
          for (int i = 1; i <= 5; i++) {
            byScore.put(i, rs.getLong(4 + i));
          }
          return new AgentSummary(
              rs.getString(1), new Summary(rs.getLong(2), rs.getLong(3), rs.getLong(4), byScore));
        },
        Timestamp.from(from),
        Timestamp.from(to));
  }

  /**
   * 직원 한 명 몫의 평가(0.38.0) — 남긴 것만, 평가 최근 순. cursor = 앞 쪽 마지막 inquiryId.
   *
   * @return size+1 개까지 — 호출 쪽이 다음 쪽 여부를 판단
   */
  public List<RatingRow> ratings(
      String agentId, Instant from, Instant to, @Nullable String cursor, int size) {
    List<Object> args = new java.util.ArrayList<>();
    args.add(Timestamp.from(from));
    args.add(Timestamp.from(to));
    args.add(agentId);
    StringBuilder sql =
        new StringBuilder(
            "SELECT x.* FROM (SELECT q.inquiry_id, q.inquiry_number, s.score, s.comment,"
                + " s.rated_at, q.answered_at, "
                + OWNER
                + " AS agent_id"
                + BASE
                + " AND s.inquiry_id IS NOT NULL) x WHERE x.agent_id = ?");
    if (cursor != null) {
      sql.append(
          " AND (x.rated_at, x.inquiry_id) < (SELECT rated_at, inquiry_id FROM"
              + " inquiry_satisfaction WHERE inquiry_id = ?)");
      args.add(cursor);
    }
    sql.append(" ORDER BY x.rated_at DESC, x.inquiry_id DESC LIMIT ?");
    args.add(size + 1);
    return jdbc.query(
        sql.toString(),
        (rs, n) ->
            new RatingRow(
                rs.getString("inquiry_id"),
                rs.getString("inquiry_number"),
                rs.getInt("score"),
                rs.getString("comment"),
                rs.getTimestamp("rated_at").toInstant(),
                rs.getTimestamp("answered_at").toInstant()),
        args.toArray());
  }
}
