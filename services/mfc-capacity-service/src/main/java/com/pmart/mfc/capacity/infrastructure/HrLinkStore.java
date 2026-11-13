package com.pmart.mfc.capacity.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 인사 사본(hr_employee)과 그 센터 MFC_WORKER 권한(mfc_worker_grant) — V15. */
@Repository
public class HrLinkStore {

  /** 인사 사본. employeeNo·employmentType 은 등록 사건 전이면 null(상태 사건만 받은 경우). */
  public record Employee(
      String operatorId,
      @Nullable String employeeNo,
      @Nullable String employmentType,
      String status,
      long hrVersion) {}

  public record Grant(String grantId, String mfcId, Instant validFrom, @Nullable Instant validTo) {
    public boolean validAt(Instant now) {
      return !now.isBefore(validFrom) && (validTo == null || now.isBefore(validTo));
    }

    public boolean endedAt(Instant now) {
      return validTo != null && !now.isBefore(validTo);
    }
  }

  private final JdbcTemplate jdbc;

  public HrLinkStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Employee> lockEmployee(String operatorId) {
    return jdbc
        .query(
            "SELECT * FROM hr_employee WHERE operator_id = ? FOR UPDATE",
            (rs, i) ->
                new Employee(
                    rs.getString("operator_id"),
                    rs.getString("employee_no"),
                    rs.getString("employment_type"),
                    rs.getString("status"),
                    rs.getLong("hr_version")),
            operatorId)
        .stream()
        .findFirst();
  }

  /** 등록 사건: 사번·고용 형태·상태를 모두 쓴다. */
  public void upsertRegistered(
      String operatorId,
      String employeeNo,
      String employmentType,
      String status,
      long version,
      Instant at) {
    jdbc.update(
        "INSERT INTO hr_employee (operator_id, employee_no, employment_type, status, hr_version,"
            + " updated_at) VALUES (?,?,?,?,?,?) ON CONFLICT (operator_id) DO UPDATE SET"
            + " employee_no = EXCLUDED.employee_no, employment_type = EXCLUDED.employment_type,"
            + " status = EXCLUDED.status, hr_version = EXCLUDED.hr_version, updated_at ="
            + " EXCLUDED.updated_at",
        operatorId,
        employeeNo,
        employmentType,
        status,
        version,
        Timestamp.from(at));
  }

  /** 상태 사건: 상태·버전만(사번·고용 형태는 그대로, 없으면 null 로 새 행). */
  public void upsertStatus(String operatorId, String status, long version, Instant at) {
    jdbc.update(
        "INSERT INTO hr_employee (operator_id, status, hr_version, updated_at) VALUES (?,?,?,?)"
            + " ON CONFLICT (operator_id) DO UPDATE SET status = EXCLUDED.status, hr_version ="
            + " EXCLUDED.hr_version, updated_at = EXCLUDED.updated_at",
        operatorId,
        status,
        version,
        Timestamp.from(at));
  }

  /** 새로 넣었으면 true(같은 권한 행이 이미 있으면 false). */
  public boolean insertGrant(
      String grantId,
      String mfcId,
      String operatorId,
      Instant from,
      @Nullable Instant to,
      Instant at) {
    return jdbc.update(
            "INSERT INTO mfc_worker_grant (grant_id, mfc_id, operator_id, valid_from, valid_to,"
                + " applied_at) VALUES (?,?,?,?,?,?) ON CONFLICT DO NOTHING",
            grantId,
            mfcId,
            operatorId,
            Timestamp.from(from),
            to == null ? null : Timestamp.from(to),
            Timestamp.from(at))
        == 1;
  }

  public List<Grant> grants(String mfcId, String operatorId) {
    return jdbc.query(
        "SELECT grant_id, mfc_id, valid_from, valid_to FROM mfc_worker_grant"
            + " WHERE mfc_id = ? AND operator_id = ? ORDER BY valid_from",
        (rs, i) -> grant(rs),
        mfcId,
        operatorId);
  }

  /** 이 운영자에게 MFC_WORKER 권한이 있는 센터들. */
  public List<String> grantCenters(String operatorId) {
    return jdbc.queryForList(
        "SELECT DISTINCT mfc_id FROM mfc_worker_grant WHERE operator_id = ? ORDER BY mfc_id",
        String.class,
        operatorId);
  }

  /** 권한이 있는 (센터, 운영자) 쌍 전부 — 기간 경계를 맞추는 주기 작업용(작은 표). */
  public List<String[]> grantPairs() {
    return jdbc.query(
        "SELECT DISTINCT mfc_id, operator_id FROM mfc_worker_grant ORDER BY mfc_id, operator_id",
        (rs, i) -> new String[] {rs.getString(1), rs.getString(2)});
  }

  private static Grant grant(java.sql.ResultSet rs) throws java.sql.SQLException {
    Timestamp to = rs.getTimestamp("valid_to");
    return new Grant(
        rs.getString("grant_id"),
        rs.getString("mfc_id"),
        rs.getTimestamp("valid_from").toInstant(),
        to == null ? null : to.toInstant());
  }
}
