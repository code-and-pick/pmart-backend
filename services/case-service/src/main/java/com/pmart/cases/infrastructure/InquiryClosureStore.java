package com.pmart.cases.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 문의 닫기(V16 {@code inquiry_closure}, 0.19.0). 문의 행(cs_inquiry)의 상태와 그 접수 건 행(case_record)도 같은
 * 트랜잭션에서 닫힘으로 맞춘다 — 둘 다 이 서비스 표.
 */
@Repository
public class InquiryClosureStore {

  public record ClosureRow(
      String inquiryId,
      String closedBy,
      String closedByType,
      @Nullable String reason,
      Instant closedAt) {}

  private final JdbcTemplate jdbc;

  public InquiryClosureStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 닫기 — 문의 상태 CLOSED(version + 1), 접수 건 CLOSED·닫은 시각·사람, 닫기 기록. 호출 쪽이 문의 행을 잠갔다. */
  public void close(String inquiryId, String caseId, ClosureRow r) {
    Timestamp at = Timestamp.from(r.closedAt());
    jdbc.update(
        "UPDATE cs_inquiry SET status = 'CLOSED', version = version + 1 WHERE inquiry_id = ?",
        inquiryId);
    jdbc.update(
        "UPDATE case_record SET state = 'CLOSED', closed_at = ?, closed_by = ?,"
            + " resolution = 'INQUIRY_CLOSED' WHERE case_id = ?",
        at,
        r.closedBy(),
        caseId);
    jdbc.update(
        "INSERT INTO inquiry_closure (inquiry_id, closed_by, closed_by_type, reason, closed_at)"
            + " VALUES (?, ?, ?, ?, ?)",
        inquiryId,
        r.closedBy(),
        r.closedByType(),
        r.reason(),
        at);
  }

  public Optional<ClosureRow> find(String inquiryId) {
    return jdbc
        .query(
            "SELECT inquiry_id, closed_by, closed_by_type, reason, closed_at FROM inquiry_closure"
                + " WHERE inquiry_id = ?",
            (rs, n) ->
                new ClosureRow(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getTimestamp(5).toInstant()),
            inquiryId)
        .stream()
        .findFirst();
  }
}
