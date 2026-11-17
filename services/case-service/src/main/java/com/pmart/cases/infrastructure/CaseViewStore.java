package com.pmart.cases.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 접수 건 직원별 마지막으로 본 시각(V17 {@code case_view}, 0.20.0). */
@Repository
public class CaseViewStore {

  private final JdbcTemplate jdbc;

  public CaseViewStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 본 시각 남기기 — 더 늦은 시각만 덮어쓴다(늦게 도착한 옛 요청이 되돌리지 않게). */
  public void record(String caseId, String operatorId, Instant at) {
    jdbc.update(
        "INSERT INTO case_view (case_id, operator_id, last_seen_at) VALUES (?, ?, ?)"
            + " ON CONFLICT (case_id, operator_id) DO UPDATE SET last_seen_at ="
            + " GREATEST(case_view.last_seen_at, EXCLUDED.last_seen_at)",
        caseId,
        operatorId,
        Timestamp.from(at));
  }

  /** 그 직원이 이 건들을 마지막으로 본 시각(본 적 없으면 빠짐). */
  public Map<String, Instant> seen(String operatorId, List<String> caseIds) {
    Map<String, Instant> out = new HashMap<>();
    if (caseIds.isEmpty()) {
      return out;
    }
    String in = String.join(",", caseIds.stream().map(x -> "?").toList());
    Object[] args = new Object[caseIds.size() + 1];
    args[0] = operatorId;
    for (int i = 0; i < caseIds.size(); i++) {
      args[i + 1] = caseIds.get(i);
    }
    jdbc.query(
        "SELECT case_id, last_seen_at FROM case_view WHERE operator_id = ? AND case_id IN ("
            + in
            + ")",
        rs -> {
          out.put(rs.getString(1), rs.getTimestamp(2).toInstant());
        },
        args);
    return out;
  }
}
