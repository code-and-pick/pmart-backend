package com.pmart.rider.infrastructure;

import com.pmart.rider.domain.ComplaintReviewRules.Complaint;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 컴플레인·제한 검토 대상 저장(JDBC, V4__complaint_reviews.sql — RS-3). */
@Repository
public class ComplaintStore {

  public record ReviewRow(
      String reviewId,
      String riderId,
      String complaintType,
      String state,
      Instant openedAt,
      @Nullable String decidedBy,
      @Nullable Instant decidedAt,
      @Nullable String decisionNote) {}

  public record ComplaintRow(
      String complaintId, String deliveryId, String complaintType, Instant recordedAt) {}

  private final JdbcTemplate jdbc;

  public ComplaintStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 같은 라이더의 컴플레인 판정을 줄 세운다(트랜잭션 끝까지 — 라이더 행이 없어도). */
  public void lockRiderComplaints(String riderId) {
    jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))", "rider-complaint:" + riderId);
  }

  /** 새로 넣었으면 true(같은 complaintId 는 한 번만). */
  public boolean insertComplaint(
      String complaintId,
      String riderId,
      String deliveryId,
      String type,
      @Nullable String caseId,
      Instant recordedAt,
      Instant now) {
    return jdbc.update(
            "INSERT INTO rider_complaint (complaint_id, rider_id, delivery_id, complaint_type, case_id,"
                + " recorded_at, received_at) VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (complaint_id)"
                + " DO NOTHING",
            complaintId,
            riderId,
            deliveryId,
            type,
            caseId,
            Timestamp.from(recordedAt),
            Timestamp.from(now))
        == 1;
  }

  /** 아직 처리된 검토에 묶이지 않은(검토 없음) 같은 종류 컴플레인 — 시각 범위 안. */
  public List<Complaint> unreviewed(String riderId, String type, Instant from, Instant to) {
    return jdbc.query(
        "SELECT complaint_id, delivery_id, recorded_at FROM rider_complaint WHERE rider_id = ? AND"
            + " complaint_type = ? AND review_id IS NULL AND recorded_at BETWEEN ? AND ?",
        (rs, i) -> new Complaint(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant()),
        riderId,
        type,
        Timestamp.from(from),
        Timestamp.from(to));
  }

  public Optional<ReviewRow> openReview(String riderId, String type) {
    return jdbc
        .query(
            "SELECT * FROM rider_restriction_review WHERE rider_id = ? AND complaint_type = ? AND"
                + " state = 'OPEN'",
            (rs, i) -> review(rs),
            riderId,
            type)
        .stream()
        .findFirst();
  }

  public void insertReview(String reviewId, String riderId, String type, Instant now) {
    jdbc.update(
        "INSERT INTO rider_restriction_review (review_id, rider_id, complaint_type, state,"
            + " opened_at) VALUES (?, ?, ?, 'OPEN', ?)",
        reviewId,
        riderId,
        type,
        Timestamp.from(now));
  }

  public void attach(Collection<String> complaintIds, String reviewId) {
    if (complaintIds.isEmpty()) {
      return;
    }
    List<Object> args = new ArrayList<>();
    args.add(reviewId);
    args.addAll(complaintIds);
    jdbc.update(
        "UPDATE rider_complaint SET review_id = ? WHERE complaint_id IN ("
            + String.join(",", Collections.nCopies(complaintIds.size(), "?"))
            + ")",
        args.toArray());
  }

  public Optional<ReviewRow> lockReview(String reviewId) {
    return jdbc
        .query(
            "SELECT * FROM rider_restriction_review WHERE review_id = ? FOR UPDATE",
            (rs, i) -> review(rs),
            reviewId)
        .stream()
        .findFirst();
  }

  public void decide(String reviewId, String state, String by, @Nullable String note, Instant at) {
    jdbc.update(
        "UPDATE rider_restriction_review SET state = ?, decided_by = ?, decided_at = ?,"
            + " decision_note = ? WHERE review_id = ?",
        state,
        by,
        Timestamp.from(at),
        note,
        reviewId);
  }

  /** 검토 목록(최근 순). state·riderId 가 null 이면 거르지 않는다. */
  public List<ReviewRow> reviews(@Nullable String state, @Nullable String riderId, int limit) {
    StringBuilder sql = new StringBuilder("SELECT * FROM rider_restriction_review WHERE 1 = 1");
    List<Object> args = new ArrayList<>();
    if (state != null) {
      sql.append(" AND state = ?");
      args.add(state);
    }
    if (riderId != null) {
      sql.append(" AND rider_id = ?");
      args.add(riderId);
    }
    sql.append(" ORDER BY opened_at DESC LIMIT ?");
    args.add(limit);
    return jdbc.query(sql.toString(), (rs, i) -> review(rs), args.toArray());
  }

  public List<ComplaintRow> complaintsOf(String reviewId) {
    return jdbc.query(
        "SELECT complaint_id, delivery_id, complaint_type, recorded_at FROM rider_complaint WHERE"
            + " review_id = ? ORDER BY recorded_at, complaint_id",
        (rs, i) ->
            new ComplaintRow(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant()),
        reviewId);
  }

  private static ReviewRow review(ResultSet rs) throws SQLException {
    Timestamp decided = rs.getTimestamp("decided_at");
    return new ReviewRow(
        rs.getString("review_id"),
        rs.getString("rider_id"),
        rs.getString("complaint_type"),
        rs.getString("state"),
        rs.getTimestamp("opened_at").toInstant(),
        rs.getString("decided_by"),
        decided == null ? null : decided.toInstant(),
        rs.getString("decision_note"));
  }
}
