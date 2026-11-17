package com.pmart.cases.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 문의 사진 메타(V12 {@code inquiry_photo}, 0.14.0). 본문은 {@code InquiryPhotoStorage}. */
@Repository
public class InquiryPhotoStore {

  public record PhotoRow(
      String photoId,
      String ownerId,
      String objectKey,
      String contentType,
      long sizeBytes,
      String sha256,
      Instant uploadedAt,
      @Nullable String inquiryId) {}

  private static final String COLS =
      "photo_id, owner_id, object_key, content_type, size_bytes, sha256, uploaded_at, inquiry_id";

  private final JdbcTemplate jdbc;

  public InquiryPhotoStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(PhotoRow r) {
    jdbc.update(
        "INSERT INTO inquiry_photo ("
            + COLS
            + ", sort_order) VALUES (?, ?, ?, ?, ?, ?, ?, NULL, NULL)",
        r.photoId(),
        r.ownerId(),
        r.objectKey(),
        r.contentType(),
        r.sizeBytes(),
        r.sha256(),
        Timestamp.from(r.uploadedAt()));
  }

  public Optional<PhotoRow> find(String photoId) {
    return jdbc
        .query("SELECT " + COLS + " FROM inquiry_photo WHERE photo_id = ?", this::row, photoId)
        .stream()
        .findFirst();
  }

  /** 문의에 붙은 사진(붙인 순서). */
  public List<PhotoRow> ofInquiry(String inquiryId) {
    return jdbc.query(
        "SELECT " + COLS + " FROM inquiry_photo WHERE inquiry_id = ? ORDER BY sort_order",
        this::row,
        inquiryId);
  }

  /**
   * 내가 올리고 아직 어디에도 붙지 않은 사진만 붙인다. 붙인 장수를 돌려준다(요청 수와 다르면 호출 쪽이 트랜잭션째 되돌린다). 같은 문의에 붙이는 요청이 겹치면 문의 행
   * 잠금으로 순서를 맞춘다(호출 쪽).
   */
  public int attach(String inquiryId, String ownerId, List<String> photoIds, int startOrder) {
    int n = 0;
    for (int i = 0; i < photoIds.size(); i++) {
      n +=
          jdbc.update(
              "UPDATE inquiry_photo SET inquiry_id = ?, sort_order = ?"
                  + " WHERE photo_id = ? AND owner_id = ? AND inquiry_id IS NULL",
              inquiryId,
              startOrder + i,
              photoIds.get(i),
              ownerId);
    }
    return n;
  }

  /** 붙지 않은 채 before 보다 먼저 올린 사진. */
  public List<PhotoRow> orphans(Instant before, int limit) {
    return jdbc.query(
        "SELECT "
            + COLS
            + " FROM inquiry_photo WHERE inquiry_id IS NULL AND uploaded_at < ?"
            + " ORDER BY uploaded_at LIMIT ?",
        this::row,
        Timestamp.from(before),
        limit);
  }

  public void delete(String photoId) {
    jdbc.update("DELETE FROM inquiry_photo WHERE photo_id = ? AND inquiry_id IS NULL", photoId);
  }

  private PhotoRow row(ResultSet rs, int n) throws SQLException {
    return new PhotoRow(
        rs.getString("photo_id"),
        rs.getString("owner_id"),
        rs.getString("object_key"),
        rs.getString("content_type"),
        rs.getLong("size_bytes"),
        rs.getString("sha256"),
        rs.getTimestamp("uploaded_at").toInstant(),
        rs.getString("inquiry_id"));
  }
}
