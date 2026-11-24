package com.pmart.review.infrastructure;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 리뷰 신고(V6 {@code review_report}, 계약 0.7.0 BR-90). */
@Repository
public class ReviewReportStore {

  public static final List<String> REASONS =
      List.of("ABUSE", "AD", "IRRELEVANT", "PRIVACY", "OTHER");

  /** 리뷰 하나의 신고 묶음(운영자 목록). */
  public record Group(
      String reviewId,
      String productId,
      @Nullable String sku,
      int rating,
      @Nullable String body,
      String authorName,
      Instant reviewCreatedAt,
      String reviewState,
      int count,
      Map<String, Integer> reasonCounts,
      List<String> otherDetails,
      Instant firstReportedAt,
      Instant lastReportedAt,
      @Nullable String resolution,
      @Nullable Instant resolvedAt,
      @Nullable String resolvedBy) {}

  /** 신고 한 건(0.8.0 운영자 상세) — 신고자는 읽지 않는다(사용자 2026-11-24 "안 보여 줌"). */
  public record Item(
      String reportId,
      String reason,
      @Nullable String detail,
      Instant reportedAt,
      String state,
      @Nullable String resolution,
      @Nullable Instant resolvedAt,
      @Nullable String resolvedBy,
      @Nullable String resolutionNote) {}

  private final JdbcTemplate jdbc;

  public ReviewReportStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public boolean exists(String reviewId, String reporter) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM review_report WHERE review_id = ? AND reporter_account_id = ?",
            Integer.class,
            reviewId,
            reporter);
    return n != null && n > 0;
  }

  public void insert(
      String reportId,
      String reviewId,
      String reporter,
      String reason,
      @Nullable String detail,
      Instant now) {
    jdbc.update(
        "INSERT INTO review_report (report_id, review_id, reporter_account_id, reason, detail, state,"
            + " created_at) VALUES (?,?,?,?,?, 'OPEN', ?)",
        reportId,
        reviewId,
        reporter,
        reason,
        detail,
        Timestamp.from(now));
  }

  /** 이 회원이 신고한 리뷰(상품 리뷰 목록 reportedByMe). */
  public Set<String> reportedBy(String reporter, Collection<String> reviewIds) {
    Set<String> out = new HashSet<>();
    if (reviewIds.isEmpty()) {
      return out;
    }
    List<Object> args = new ArrayList<>();
    args.add(reporter);
    args.addAll(reviewIds);
    jdbc.query(
        "SELECT review_id FROM review_report WHERE reporter_account_id = ? AND review_id IN ("
            + String.join(",", java.util.Collections.nCopies(reviewIds.size(), "?"))
            + ")",
        rs -> {
          out.add(rs.getString(1));
        },
        args.toArray());
    return out;
  }

  public int openCount(String reviewId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM review_report WHERE review_id = ? AND state = 'OPEN'",
            Integer.class,
            reviewId);
    return n == null ? 0 : n;
  }

  /** 처리 전 신고를 모두 닫는다. 닫은 수. */
  public int resolve(
      String reviewId, String resolution, String by, @Nullable String note, Instant now) {
    return jdbc.update(
        "UPDATE review_report SET state = 'RESOLVED', resolution = ?, resolved_by = ?,"
            + " resolution_note = ?, resolved_at = ? WHERE review_id = ? AND state = 'OPEN'",
        resolution,
        by,
        note,
        Timestamp.from(now),
        reviewId);
  }

  /** 한 리뷰의 신고 전부(처리 전·끝) — 최근 신고 순. 신고자 열은 읽지 않는다. */
  public List<Item> items(String reviewId) {
    return jdbc.query(
        "SELECT report_id, reason, detail, created_at, state, resolution, resolved_at,"
            + " resolved_by, resolution_note FROM review_report WHERE review_id = ?"
            + " ORDER BY created_at DESC, report_id",
        (rs, i) -> {
          Timestamp resolvedAt = rs.getTimestamp("resolved_at");
          return new Item(
              rs.getString("report_id"),
              rs.getString("reason"),
              rs.getString("detail"),
              rs.getTimestamp("created_at").toInstant(),
              rs.getString("state"),
              rs.getString("resolution"),
              resolvedAt == null ? null : resolvedAt.toInstant(),
              rs.getString("resolved_by"),
              rs.getString("resolution_note"));
        },
        reviewId);
  }

  /** 신고 묶음 — 그 state 의 신고만 센다. 최근 신고 순. */
  public List<Group> groups(String state, int offset, int limit) {
    StringBuilder reasons = new StringBuilder();
    for (String r : REASONS) {
      reasons
          .append(" count(*) FILTER (WHERE p.reason = '")
          .append(r)
          .append("') AS c_")
          .append(r.toLowerCase())
          .append(',');
    }
    String sql =
        "SELECT r.review_id, r.product_id, r.sku, r.rating, r.body, r.author_name, r.created_at AS"
            + " review_created_at, r.state AS review_state, count(*) AS cnt,"
            + reasons
            + " min(p.created_at) AS first_at, max(p.created_at) AS last_at,"
            + " (array_agg(p.detail ORDER BY p.created_at DESC) FILTER (WHERE p.detail IS NOT NULL))"
            + " AS details,"
            + " (array_agg(p.resolution ORDER BY p.resolved_at DESC NULLS LAST))[1] AS resolution,"
            + " max(p.resolved_at) AS resolved_at,"
            + " (array_agg(p.resolved_by ORDER BY p.resolved_at DESC NULLS LAST))[1] AS resolved_by"
            + " FROM review_report p JOIN review r ON r.review_id = p.review_id"
            + " WHERE p.state = ?"
            + " GROUP BY r.review_id, r.product_id, r.sku, r.rating, r.body, r.author_name,"
            + " r.created_at, r.state"
            + " ORDER BY last_at DESC, r.review_id LIMIT ? OFFSET ?";
    return jdbc.query(sql, (rs, i) -> group(rs), state, limit, offset);
  }

  private static Group group(ResultSet rs) throws SQLException {
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (String r : REASONS) {
      int c = rs.getInt("c_" + r.toLowerCase());
      if (c > 0) {
        counts.put(r, c);
      }
    }
    List<String> details = new ArrayList<>();
    Array a = rs.getArray("details");
    if (a != null) {
      details.addAll(Arrays.asList((String[]) a.getArray()));
    }
    Timestamp resolvedAt = rs.getTimestamp("resolved_at");
    return new Group(
        rs.getString("review_id"),
        rs.getString("product_id"),
        rs.getString("sku"),
        rs.getInt("rating"),
        rs.getString("body"),
        rs.getString("author_name"),
        rs.getTimestamp("review_created_at").toInstant(),
        rs.getString("review_state"),
        rs.getInt("cnt"),
        counts,
        details.size() > 10 ? List.copyOf(details.subList(0, 10)) : List.copyOf(details),
        rs.getTimestamp("first_at").toInstant(),
        rs.getTimestamp("last_at").toInstant(),
        rs.getString("resolution"),
        resolvedAt == null ? null : resolvedAt.toInstant(),
        rs.getString("resolved_by"));
  }
}
