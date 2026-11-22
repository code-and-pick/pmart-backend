package com.pmart.cases.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 라이더 컴플레인 저장(JDBC, 0.3.0). note 는 개인정보가 들어갈 수 있다 — 로그 금지. */
@Repository
public class RiderComplaintStore {

  public record ComplaintRow(
      String complaintId,
      @Nullable String caseId,
      String riderId,
      String deliveryId,
      @Nullable String orderId,
      @Nullable String inquiryId,
      String complaintType,
      String source,
      @Nullable String note,
      String recordedBy,
      Instant recordedAt,
      /** 0.33.0 R-12 — 배송 센터(기록 때 dispatch 배정 mfcId). 이전 기록은 null. */
      @Nullable String centerId) {}

  /** 목록 조건. 모두 선택. from 이상 to 미만. */
  public record Filter(
      @Nullable String riderId,
      @Nullable String deliveryId,
      @Nullable String complaintType,
      @Nullable Instant from,
      @Nullable Instant to,
      /** 0.33.0 R-12 — 주면 그 센터만(센터 없는 기록은 빠진다). */
      @Nullable String centerId) {}

  private static final String COLS =
      "complaint_id, case_id, rider_id, delivery_id, order_id, inquiry_id, complaint_type,"
          + " source, note, recorded_by, recorded_at, center_id";

  private final JdbcTemplate jdbc;

  public RiderComplaintStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(ComplaintRow r) {
    jdbc.update(
        "INSERT INTO rider_complaint (" + COLS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
        r.complaintId(),
        r.caseId(),
        r.riderId(),
        r.deliveryId(),
        r.orderId(),
        r.inquiryId(),
        r.complaintType(),
        r.source(),
        r.note(),
        r.recordedBy(),
        Timestamp.from(r.recordedAt()),
        r.centerId());
  }

  public Optional<ComplaintRow> find(String complaintId) {
    return jdbc
        .query(
            "SELECT " + COLS + " FROM rider_complaint WHERE complaint_id = ?",
            this::row,
            complaintId)
        .stream()
        .findFirst();
  }

  /** 문의의 사건 ID(문의에서 넘어온 컴플레인 연결). 없는 문의면 빈 값. */
  public Optional<String> inquiryCaseId(String inquiryId) {
    return jdbc
        .queryForList(
            "SELECT case_id FROM cs_inquiry WHERE inquiry_id = ?", String.class, inquiryId)
        .stream()
        .findFirst();
  }

  /**
   * 최근 기록 순 한 쪽. cursor = 앞 쪽 마지막 컴플레인 ID.
   *
   * @return size+1 개까지 — 호출 쪽이 다음 쪽 여부를 판단
   */
  /** 종류별 수(0.26.0) — 목록과 같은 거르기, 종류 거르기는 무시. 없는 종류는 빠진다(부르는 쪽이 0 으로 채움). */
  public Map<String, Long> countsByType(Filter f) {
    StringBuilder sql =
        new StringBuilder("SELECT complaint_type, count(*) AS n FROM rider_complaint WHERE 1=1");
    List<Object> args = new ArrayList<>();
    if (f.riderId() != null) {
      sql.append(" AND rider_id = ?");
      args.add(f.riderId());
    }
    if (f.deliveryId() != null) {
      sql.append(" AND delivery_id = ?");
      args.add(f.deliveryId());
    }
    if (f.from() != null) {
      sql.append(" AND recorded_at >= ?");
      args.add(Timestamp.from(f.from()));
    }
    if (f.to() != null) {
      sql.append(" AND recorded_at < ?");
      args.add(Timestamp.from(f.to()));
    }
    if (f.centerId() != null) {
      sql.append(" AND center_id = ?");
      args.add(f.centerId());
    }
    sql.append(" GROUP BY complaint_type");
    Map<String, Long> out = new java.util.LinkedHashMap<>();
    jdbc.query(
        sql.toString(),
        rs -> {
          out.put(rs.getString("complaint_type"), rs.getLong("n"));
        },
        args.toArray());
    return out;
  }

  public List<ComplaintRow> page(Filter f, @Nullable String cursor, int size) {
    StringBuilder sql = new StringBuilder("SELECT " + COLS + " FROM rider_complaint WHERE 1=1");
    List<Object> args = new ArrayList<>();
    if (f.riderId() != null) {
      sql.append(" AND rider_id = ?");
      args.add(f.riderId());
    }
    if (f.deliveryId() != null) {
      sql.append(" AND delivery_id = ?");
      args.add(f.deliveryId());
    }
    if (f.complaintType() != null) {
      sql.append(" AND complaint_type = ?");
      args.add(f.complaintType());
    }
    if (f.from() != null) {
      sql.append(" AND recorded_at >= ?");
      args.add(Timestamp.from(f.from()));
    }
    if (f.to() != null) {
      sql.append(" AND recorded_at < ?");
      args.add(Timestamp.from(f.to()));
    }
    if (f.centerId() != null) {
      sql.append(" AND center_id = ?");
      args.add(f.centerId());
    }
    if (cursor != null) {
      sql.append(
          " AND (recorded_at, complaint_id) < (SELECT recorded_at, complaint_id FROM"
              + " rider_complaint WHERE complaint_id = ?)");
      args.add(cursor);
    }
    sql.append(" ORDER BY recorded_at DESC, complaint_id DESC LIMIT ?");
    args.add(size + 1);
    return jdbc.query(sql.toString(), this::row, args.toArray());
  }

  private ComplaintRow row(ResultSet rs, int i) throws SQLException {
    return new ComplaintRow(
        rs.getString("complaint_id"),
        rs.getString("case_id"),
        rs.getString("rider_id"),
        rs.getString("delivery_id"),
        rs.getString("order_id"),
        rs.getString("inquiry_id"),
        rs.getString("complaint_type"),
        rs.getString("source"),
        rs.getString("note"),
        rs.getString("recorded_by"),
        rs.getTimestamp("recorded_at").toInstant(),
        rs.getString("center_id"));
  }
}
