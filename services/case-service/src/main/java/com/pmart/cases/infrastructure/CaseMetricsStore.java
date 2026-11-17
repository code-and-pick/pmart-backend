package com.pmart.cases.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 접수 건 현황 건수(BO-28 1단계 — 0.18.0). 이 서비스 표만 센다(다른 서비스 지표 없음). 모두 지금 시각 기준 스냅샷이고 비율·목표·SLA 달성률은 내지
 * 않는다(승인된 정의 없음).
 */
@Repository
public class CaseMetricsStore {

  /** 사건함 종류별 — 열린 건, 그중 아무도 안 집은 건, 그중 처리 기한이 지난 건. */
  public record KindCount(String kind, long open, long unassigned, long overdue) {}

  /** 후속 처리 담당 팀별 — 열린 것, 그중 기한이 지난 것. */
  public record OwnerCount(String owner, long open, long overdue) {}

  /** 문의 종류별 — 답변을 기다리는 것과 가장 오래 기다린 접수 시각. */
  public record InquiryCount(String requesterType, long awaitingAnswer, @Nullable Instant oldest) {}

  public record Snapshot(
      List<KindCount> kinds,
      List<OwnerCount> obligations,
      long pendingHandovers,
      List<InquiryCount> inquiries) {}

  private final JdbcTemplate jdbc;

  public CaseMetricsStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Snapshot snapshot(List<String> inboxKinds, Instant now) {
    Timestamp t = Timestamp.from(now);
    List<KindCount> kinds = new ArrayList<>();
    if (!inboxKinds.isEmpty()) {
      String in = String.join(",", inboxKinds.stream().map(k -> "?").toList());
      List<Object> args = new ArrayList<>();
      args.add(t);
      args.addAll(inboxKinds);
      kinds.addAll(
          jdbc.query(
              "SELECT case_type, count(*),"
                  + " count(*) FILTER (WHERE assignee_id IS NULL),"
                  + " count(*) FILTER (WHERE sla_due_at IS NOT NULL AND sla_due_at < ?)"
                  + " FROM case_record WHERE state <> 'CLOSED' AND case_type IN ("
                  + in
                  + ") GROUP BY case_type ORDER BY case_type",
              (rs, n) ->
                  new KindCount(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)),
              args.toArray()));
    }
    List<OwnerCount> obligations =
        jdbc.query(
            "SELECT owner, count(*), count(*) FILTER (WHERE due_at IS NOT NULL AND due_at < ?)"
                + " FROM case_obligation WHERE state IN ('OPEN', 'PARTIALLY_RESOLVED')"
                + " GROUP BY owner ORDER BY owner",
            (rs, n) -> new OwnerCount(rs.getString(1), rs.getLong(2), rs.getLong(3)),
            t);
    Long pending =
        jdbc.queryForObject(
            "SELECT count(*) FROM shift_handover WHERE state = 'PENDING'", Long.class);
    List<InquiryCount> inquiries =
        jdbc.query(
            "SELECT requester_type, count(*), min(created_at) FROM cs_inquiry"
                + " WHERE status = 'RECEIVED' AND origin <> 'STAFF_INTAKE'"
                + " GROUP BY requester_type ORDER BY requester_type",
            (rs, n) -> {
              Timestamp oldest = rs.getTimestamp(3);
              return new InquiryCount(
                  rs.getString(1), rs.getLong(2), oldest == null ? null : oldest.toInstant());
            });
    return new Snapshot(kinds, obligations, pending == null ? 0 : pending, inquiries);
  }
}
