package com.pmart.hr.infrastructure;

import java.sql.Array;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 직원·사번 순번·계정 발급 진행·요청 권한·변경 기록 저장(JDBC). 비밀번호는 없다. */
@Repository
public class HrStore {

  public record EmployeeRow(
      String operatorId,
      String employeeNo,
      String name,
      String mailId,
      String mailAddress,
      String employmentType,
      String affiliationType,
      String affiliationCode,
      LocalDate startDate,
      @Nullable LocalDate endDate,
      String status,
      @Nullable String statusReason,
      @Nullable String keycloakUserId,
      String iamSync,
      int iamSyncAttempts,
      @Nullable String iamSyncError,
      long version,
      Instant createdAt,
      Instant updatedAt,
      String registeredBy) {}

  public record ProvisioningRow(
      String operatorId,
      String step,
      int attempts,
      @Nullable String lastErrorCode,
      @Nullable Instant nextAttemptAt,
      Instant updatedAt) {}

  public record GrantRow(
      String role,
      List<String> centers,
      @Nullable LocalDate validFrom,
      @Nullable LocalDate validTo,
      @Nullable String reason,
      String submission,
      long grantId,
      @Nullable String approvalRef) {}

  public static final String HQ_DEPARTMENT = "HQ_DEPARTMENT";
  public static final String CENTER = "CENTER";

  private static final String DEPARTMENTS =
      "SELECT d.code, d.name, count(e.operator_id) AS n FROM hq_department d"
          + " LEFT JOIN employee e ON e.affiliation_type = 'HQ_DEPARTMENT'"
          + " AND e.affiliation_code = d.code AND e.status <> 'LEFT'";

  /** 본사 부서(코드·표시 이름·퇴사하지 않은 소속 직원 수). */
  public record DepartmentRow(String code, @Nullable String name, int employeeCount) {}

  private final JdbcTemplate jdbc;

  public HrStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  // ---------- 사번 ----------

  /** 그 해의 다음 순번(원자적). */
  public int nextEmployeeNo(int year) {
    Integer n =
        jdbc.queryForObject(
            "INSERT INTO employee_no_counter (year, last_no) VALUES (?, 1)"
                + " ON CONFLICT (year) DO UPDATE SET last_no = employee_no_counter.last_no + 1"
                + " RETURNING last_no",
            Integer.class,
            year);
    return n == null ? 1 : n;
  }

  // ---------- 직원 ----------

  public boolean mailIdTaken(String mailId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM employee WHERE mail_id = ?", Integer.class, mailId);
    return n != null && n > 0;
  }

  public void insertEmployee(
      String operatorId,
      String employeeNo,
      String name,
      String mailId,
      String mailAddress,
      String employmentType,
      String affiliationType,
      String affiliationCode,
      LocalDate startDate,
      @Nullable LocalDate endDate,
      String registeredBy,
      Instant at) {
    jdbc.update(
        "INSERT INTO employee (operator_id, employee_no, name, mail_id, mail_address, employment_type,"
            + " affiliation_type, affiliation_code, start_date, end_date, status, iam_sync, registered_by,"
            + " row_version, created_at, updated_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,'ACTIVE','NONE',?,1,?,?)",
        operatorId,
        employeeNo,
        name,
        mailId,
        mailAddress,
        employmentType,
        affiliationType,
        affiliationCode,
        Date.valueOf(startDate),
        endDate == null ? null : Date.valueOf(endDate),
        registeredBy,
        Timestamp.from(at),
        Timestamp.from(at));
  }

  public Optional<EmployeeRow> employee(String operatorId, boolean forUpdate) {
    return jdbc
        .query(
            "SELECT * FROM employee WHERE operator_id = ?" + (forUpdate ? " FOR UPDATE" : ""),
            (rs, i) -> employee(rs),
            operatorId)
        .stream()
        .findFirst();
  }

  /**
   * 운영자 ID → 이름(0.5.0 내부 이름 조회 — 퇴사자 포함). 모르는 ID 는 빠진다. ID 는 부르는 쪽이 형식을 확인한 값(쉼표·중괄호·따옴표 없음 — 배열
   * 문자열로 넘긴다). 결과는 로그에 남기지 않는다(개인정보).
   */
  public Map<String, String> names(Collection<String> operatorIds) {
    Map<String, String> out = new LinkedHashMap<>();
    if (operatorIds.isEmpty()) {
      return out;
    }
    jdbc.query(
        "SELECT operator_id, name FROM employee WHERE operator_id = ANY (?::varchar[])",
        rs -> {
          out.put(rs.getString("operator_id"), rs.getString("name"));
        },
        "{" + String.join(",", operatorIds) + "}");
    return out;
  }

  /**
   * 표시 조회(0.8.0): 운영자 ID → (이름, 사번). 퇴사자 포함, 모르는 ID 는 빠진다. status(0.12.0 HR-6)는 사번으로 부를 때만 — 운영자 ID
   * 로 부르면 null.
   */
  public record Label(String operatorId, String name, String employeeNo, @Nullable String status) {}

  public List<Label> labels(Collection<String> operatorIds) {
    return labels(operatorIds, false);
  }

  /** activeOnly(0.11.0) = 퇴사자(LEFT) 뺌. */
  public List<Label> labels(Collection<String> operatorIds, boolean activeOnly) {
    if (operatorIds.isEmpty()) {
      return List.of();
    }
    return jdbc.query(
        "SELECT operator_id, name, employee_no FROM employee WHERE operator_id = ANY (?::varchar[])"
            + (activeOnly ? " AND status <> 'LEFT'" : ""),
        (rs, i) ->
            new Label(
                rs.getString("operator_id"),
                rs.getString("name"),
                rs.getString("employee_no"),
                null),
        "{" + String.join(",", operatorIds) + "}");
  }

  /**
   * 0.10.0 HR-5: 사번 → (운영자 ID, 이름, 사번). 퇴사자 포함, 모르는 사번은 빠진다. 0.12.0 HR-6: 상태도 — 사람 고르기에서 정지 직원 경고.
   */
  public List<Label> labelsByEmployeeNo(Collection<String> employeeNos, boolean activeOnly) {
    if (employeeNos.isEmpty()) {
      return List.of();
    }
    return jdbc.query(
        "SELECT operator_id, name, employee_no, status FROM employee"
            + " WHERE employee_no = ANY (?::varchar[])"
            + (activeOnly ? " AND status <> 'LEFT'" : ""),
        (rs, i) ->
            new Label(
                rs.getString("operator_id"),
                rs.getString("name"),
                rs.getString("employee_no"),
                rs.getString("status")),
        "{" + String.join(",", employeeNos) + "}");
  }

  /** 사번 내림차순(최근 등록 먼저). cursor = 이전 쪽 마지막 사번. */
  /** 직원 목록 거르기(0.9.0 H-D1). 값이 null 이면 거르지 않는다. */
  public record ListFilter(
      @Nullable String status,
      @Nullable String affiliationCode,
      @Nullable String q,
      List<String> provisioningSteps,
      @Nullable String grantSubmission,
      @Nullable LocalDate endDateFrom,
      @Nullable LocalDate endDateTo,
      @Nullable Instant createdFrom,
      @Nullable Instant createdTo,
      boolean endDateAsc) {}

  public List<EmployeeRow> list(
      @Nullable String status,
      @Nullable String affiliationCode,
      @Nullable String q,
      @Nullable String cursor,
      int size) {
    return list(
        new ListFilter(status, affiliationCode, q, List.of(), null, null, null, null, null, false),
        cursor,
        null,
        size);
  }

  /**
   * 거른 목록. 기본은 사번 내림차순(cursor = 사번). endDateAsc 면 (종료일, 사번) 오름차순 — 종료일 없는 직원은 빠지고 커서는 afterEndDate
   * + cursor(사번).
   */
  public List<EmployeeRow> list(
      ListFilter f, @Nullable String cursor, @Nullable LocalDate afterEndDate, int size) {
    @Nullable String status = f.status();
    @Nullable String affiliationCode = f.affiliationCode();
    @Nullable String q = f.q();
    StringBuilder sql = new StringBuilder("SELECT * FROM employee e WHERE true");
    List<Object> args = new ArrayList<>();
    if (!f.provisioningSteps().isEmpty()) {
      sql.append(
              " AND EXISTS (SELECT 1 FROM provisioning p WHERE p.operator_id = e.operator_id AND p.step IN (")
          .append(
              String.join(",", java.util.Collections.nCopies(f.provisioningSteps().size(), "?")))
          .append("))");
      args.addAll(f.provisioningSteps());
    }
    if (f.grantSubmission() != null) {
      sql.append(
          " AND EXISTS (SELECT 1 FROM requested_grant g WHERE g.operator_id = e.operator_id AND g.submission = ?)");
      args.add(f.grantSubmission());
    }
    if (f.endDateFrom() != null) {
      sql.append(" AND e.end_date >= ?");
      args.add(f.endDateFrom());
    }
    if (f.endDateTo() != null) {
      sql.append(" AND e.end_date <= ?");
      args.add(f.endDateTo());
    }
    if (f.createdFrom() != null) {
      sql.append(" AND e.created_at >= ?");
      args.add(Timestamp.from(f.createdFrom()));
    }
    if (f.createdTo() != null) {
      sql.append(" AND e.created_at <= ?");
      args.add(Timestamp.from(f.createdTo()));
    }
    if (f.endDateAsc()) {
      sql.append(" AND e.end_date IS NOT NULL");
    }
    if (status != null) {
      sql.append(" AND status = ?");
      args.add(status);
    }
    if (affiliationCode != null) {
      sql.append(" AND affiliation_code = ?");
      args.add(affiliationCode);
    }
    if (q != null && !q.isBlank()) {
      String like = q.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
      sql.append(" AND (name LIKE ? OR mail_id LIKE ? OR employee_no LIKE ?)");
      args.add(like);
      args.add(like.toLowerCase());
      args.add(like.toUpperCase());
    }
    if (f.endDateAsc()) {
      if (cursor != null && afterEndDate != null) {
        sql.append(" AND (e.end_date, e.employee_no) > (?, ?)");
        args.add(afterEndDate);
        args.add(cursor);
      }
      sql.append(" ORDER BY e.end_date, e.employee_no LIMIT ?");
      args.add(size);
      return jdbc.query(sql.toString(), (rs, i) -> employee(rs), args.toArray());
    }
    if (cursor != null) {
      sql.append(" AND employee_no < ?");
      args.add(cursor);
    }
    sql.append(" ORDER BY employee_no DESC LIMIT ?");
    args.add(size);
    return jdbc.query(sql.toString(), (rs, i) -> employee(rs), args.toArray());
  }

  /** 인원 세기(0.9.0 H-D2) — 상태별. 없는 상태는 0. */
  public java.util.Map<String, Long> countByStatus() {
    java.util.Map<String, Long> out = new java.util.HashMap<>();
    jdbc.query(
        "SELECT status, count(*) AS n FROM employee GROUP BY status",
        rs -> {
          out.put(rs.getString("status"), rs.getLong("n"));
        });
    return out;
  }

  /** 계정 발급 실패(FAILED·COMPENSATED) 직원 수. */
  public long countProvisioningFailed() {
    Long n =
        jdbc.queryForObject(
            "SELECT count(*) FROM provisioning WHERE step IN ('FAILED', 'COMPENSATED')",
            Long.class);
    return n == null ? 0 : n;
  }

  /** 요청 권한 중 SUBMITTED(승인 대기)가 있는 직원 수 — 퇴사자 제외. */
  public long countGrantPendingApproval() {
    Long n =
        jdbc.queryForObject(
            "SELECT count(DISTINCT g.operator_id) FROM requested_grant g JOIN employee e"
                + " ON e.operator_id = g.operator_id WHERE g.submission = 'SUBMITTED' AND e.status <> 'LEFT'",
            Long.class);
    return n == null ? 0 : n;
  }

  /** 재직 중 계약 종료일이 from~to(포함)인 직원 수. */
  public long countEndingBetween(LocalDate from, LocalDate to) {
    Long n =
        jdbc.queryForObject(
            "SELECT count(*) FROM employee WHERE status = 'ACTIVE' AND end_date BETWEEN ? AND ?",
            Long.class,
            from,
            to);
    return n == null ? 0 : n;
  }

  public void setKeycloakUserId(String operatorId, String keycloakUserId, Instant at) {
    jdbc.update(
        "UPDATE employee SET keycloak_user_id = ?, updated_at = ? WHERE operator_id = ?",
        keycloakUserId,
        Timestamp.from(at),
        operatorId);
  }

  /** 재직 상태 변경 + Keycloak·사서함 맞추기 대기. */
  public void updateStatus(
      String operatorId,
      String status,
      @Nullable String reason,
      @Nullable LocalDate startDate,
      @Nullable LocalDate endDate,
      boolean changeDates,
      Instant at) {
    if (changeDates) {
      jdbc.update(
          "UPDATE employee SET status = ?, status_reason = ?, start_date = COALESCE(?, start_date), end_date = ?,"
              + " iam_sync = 'PENDING', iam_sync_attempts = 0, iam_sync_error = NULL, iam_sync_next_at = ?,"
              + " row_version = row_version + 1, updated_at = ? WHERE operator_id = ?",
          status,
          reason,
          startDate == null ? null : Date.valueOf(startDate),
          endDate == null ? null : Date.valueOf(endDate),
          Timestamp.from(at),
          Timestamp.from(at),
          operatorId);
    } else {
      jdbc.update(
          "UPDATE employee SET status = ?, status_reason = ?,"
              + " iam_sync = 'PENDING', iam_sync_attempts = 0, iam_sync_error = NULL, iam_sync_next_at = ?,"
              + " row_version = row_version + 1, updated_at = ? WHERE operator_id = ?",
          status,
          reason,
          Timestamp.from(at),
          Timestamp.from(at),
          operatorId);
    }
  }

  public void updateAffiliation(String operatorId, String type, String code, Instant at) {
    jdbc.update(
        "UPDATE employee SET affiliation_type = ?, affiliation_code = ?, row_version = row_version + 1,"
            + " updated_at = ? WHERE operator_id = ?",
        type,
        code,
        Timestamp.from(at),
        operatorId);
  }

  public void bumpVersion(String operatorId, Instant at) {
    jdbc.update(
        "UPDATE employee SET row_version = row_version + 1, updated_at = ? WHERE operator_id = ?",
        Timestamp.from(at),
        operatorId);
  }

  /** Keycloak·사서함을 지금 재직 상태에 맞추는 일을 남긴다. */
  public void markIamSync(String operatorId, Instant at) {
    jdbc.update(
        "UPDATE employee SET iam_sync = 'PENDING', iam_sync_attempts = 0, iam_sync_error = NULL, iam_sync_next_at = ?"
            + " WHERE operator_id = ?",
        Timestamp.from(at),
        operatorId);
  }

  /** 계약 종료일이 오늘보다 앞인 재직 중 직원(자동 정지 대상). */
  public List<String> contractEnded(LocalDate today) {
    return jdbc.queryForList(
        "SELECT operator_id FROM employee WHERE status = 'ACTIVE' AND end_date IS NOT NULL AND end_date < ?",
        String.class,
        Date.valueOf(today));
  }

  /** Keycloak·사서함 맞추기가 남은 직원(잠금 — 여러 대여도 한 대만). */
  public List<EmployeeRow> lockIamSyncDue(Instant now, int limit) {
    return jdbc.query(
        "SELECT * FROM employee WHERE iam_sync = 'PENDING' AND (iam_sync_next_at IS NULL OR iam_sync_next_at <= ?)"
            + " ORDER BY iam_sync_next_at LIMIT ? FOR UPDATE SKIP LOCKED",
        (rs, i) -> employee(rs),
        Timestamp.from(now),
        limit);
  }

  public void iamSyncDone(String operatorId, Instant at) {
    jdbc.update(
        "UPDATE employee SET iam_sync = 'NONE', iam_sync_error = NULL, iam_sync_next_at = NULL, updated_at = ?"
            + " WHERE operator_id = ?",
        Timestamp.from(at),
        operatorId);
  }

  public void iamSyncFailed(String operatorId, String error, Instant next) {
    jdbc.update(
        "UPDATE employee SET iam_sync_attempts = iam_sync_attempts + 1, iam_sync_error = ?, iam_sync_next_at = ?"
            + " WHERE operator_id = ?",
        error,
        Timestamp.from(next),
        operatorId);
  }

  // ---------- 계정 발급 진행 ----------

  public void insertProvisioning(String operatorId, Instant at) {
    jdbc.update(
        "INSERT INTO provisioning (operator_id, step, attempts, next_attempt_at, updated_at)"
            + " VALUES (?, 'REQUESTED', 0, ?, ?)",
        operatorId,
        Timestamp.from(at),
        Timestamp.from(at));
  }

  public Optional<ProvisioningRow> provisioning(String operatorId, boolean forUpdate) {
    return jdbc
        .query(
            "SELECT * FROM provisioning WHERE operator_id = ?" + (forUpdate ? " FOR UPDATE" : ""),
            (rs, i) -> provisioning(rs),
            operatorId)
        .stream()
        .findFirst();
  }

  /** 진행할 차례인 것(잠금 — 여러 대여도 한 대만). */
  public List<ProvisioningRow> lockDue(Instant now, int limit) {
    return jdbc.query(
        "SELECT * FROM provisioning WHERE step IN ('REQUESTED','MAILBOX_CREATED','IAM_CREATED','INVITE_SENT')"
            + " AND (next_attempt_at IS NULL OR next_attempt_at <= ?)"
            + " ORDER BY next_attempt_at LIMIT ? FOR UPDATE SKIP LOCKED",
        (rs, i) -> provisioning(rs),
        Timestamp.from(now),
        limit);
  }

  public void advance(String operatorId, String step, Instant at) {
    jdbc.update(
        "UPDATE provisioning SET step = ?, attempts = 0, last_error_code = NULL, next_attempt_at = ?, updated_at = ?"
            + " WHERE operator_id = ?",
        step,
        Timestamp.from(at),
        Timestamp.from(at),
        operatorId);
  }

  public void failed(
      String operatorId,
      String step,
      int attempts,
      String error,
      @Nullable Instant next,
      Instant at) {
    jdbc.update(
        "UPDATE provisioning SET step = ?, attempts = ?, last_error_code = ?, next_attempt_at = ?, updated_at = ?"
            + " WHERE operator_id = ?",
        step,
        attempts,
        error,
        next == null ? null : Timestamp.from(next),
        Timestamp.from(at),
        operatorId);
  }

  // ---------- 요청 권한 ----------

  public void insertGrant(
      String operatorId,
      String role,
      List<String> centers,
      @Nullable LocalDate validFrom,
      @Nullable LocalDate validTo,
      @Nullable String reason,
      Instant at) {
    jdbc.update(
        "INSERT INTO requested_grant (operator_id, role, centers, valid_from, valid_to, reason, submission, created_at)"
            + " VALUES (?, ?, ?::varchar[], ?, ?, ?, 'PENDING', ?)",
        operatorId,
        role,
        "{" + String.join(",", centers) + "}",
        validFrom == null ? null : Date.valueOf(validFrom),
        validTo == null ? null : Date.valueOf(validTo),
        reason,
        Timestamp.from(at));
  }

  public List<GrantRow> grants(String operatorId) {
    return jdbc.query(
        "SELECT * FROM requested_grant WHERE operator_id = ? ORDER BY grant_id",
        (rs, i) ->
            new GrantRow(
                rs.getString("role"),
                list(rs.getArray("centers")),
                date(rs, "valid_from"),
                date(rs, "valid_to"),
                rs.getString("reason"),
                rs.getString("submission"),
                rs.getLong("grant_id"),
                rs.getString("approval_ref")),
        operatorId);
  }

  /** 아직 올리지 않은 요청 권한을 제출됨으로(같은 요청 ID). */
  public int markGrantsSubmitted(String operatorId, String requestId) {
    return jdbc.update(
        "UPDATE requested_grant SET submission = 'SUBMITTED', request_id = ?"
            + " WHERE operator_id = ? AND submission = 'PENDING'",
        requestId,
        operatorId);
  }

  /** 승인 결과(ApprovalDecided 행) 반영. 이미 결정된 행은 그대로. */
  public int markGrantDecided(
      String operatorId, long grantId, String submission, String approvalRef, Instant at) {
    return jdbc.update(
        "UPDATE requested_grant SET submission = ?, approval_ref = ?, decided_at = ?"
            + " WHERE operator_id = ? AND grant_id = ? AND submission IN ('PENDING', 'SUBMITTED')",
        submission,
        approvalRef,
        Timestamp.from(at),
        operatorId,
        grantId);
  }

  // ---------- 본사 부서 ----------

  /** 처음 보는 본사 부서 코드면 더한다(이름 없음). 이미 있으면 그대로. */
  public void ensureDepartment(String code, String actor, Instant at) {
    jdbc.update(
        "INSERT INTO hq_department (code, name, updated_by, created_at, updated_at)"
            + " VALUES (?, NULL, ?, ?, ?) ON CONFLICT (code) DO NOTHING",
        code,
        actor,
        Timestamp.from(at),
        Timestamp.from(at));
  }

  /** 표시 이름을 정한다(없으면 만든다). */
  public void upsertDepartment(String code, @Nullable String name, String actor, Instant at) {
    jdbc.update(
        "INSERT INTO hq_department (code, name, updated_by, created_at, updated_at)"
            + " VALUES (?, ?, ?, ?, ?) ON CONFLICT (code) DO UPDATE"
            + " SET name = EXCLUDED.name, updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at",
        code,
        name,
        actor,
        Timestamp.from(at),
        Timestamp.from(at));
  }

  /** 코드 오름차순. 직원 수는 소속이 본사 부서이고 퇴사하지 않은 직원. */
  public List<DepartmentRow> departments() {
    return jdbc.query(
        DEPARTMENTS + " GROUP BY d.code, d.name ORDER BY d.code", HrStore::department);
  }

  public Optional<DepartmentRow> department(String code) {
    return jdbc
        .query(DEPARTMENTS + " WHERE d.code = ? GROUP BY d.code, d.name", HrStore::department, code)
        .stream()
        .findFirst();
  }

  private static DepartmentRow department(ResultSet rs, int i) throws SQLException {
    return new DepartmentRow(rs.getString("code"), rs.getString("name"), rs.getInt("n"));
  }

  // ---------- 변경 기록 ----------

  public void insertChange(
      String operatorId,
      String type,
      String changedBy,
      String sourceSystem,
      @Nullable String reason,
      @Nullable String detailJson,
      Instant at) {
    jdbc.update(
        "INSERT INTO employee_change (operator_id, change_type, changed_by, source_system, reason, detail, changed_at)"
            + " VALUES (?,?,?,?,?,?::jsonb,?)",
        operatorId,
        type,
        changedBy,
        sourceSystem,
        reason,
        detailJson,
        Timestamp.from(at));
  }

  // ---------- 변환 ----------

  private static EmployeeRow employee(ResultSet rs) throws SQLException {
    return new EmployeeRow(
        rs.getString("operator_id"),
        rs.getString("employee_no"),
        rs.getString("name"),
        rs.getString("mail_id"),
        rs.getString("mail_address"),
        rs.getString("employment_type"),
        rs.getString("affiliation_type"),
        rs.getString("affiliation_code"),
        rs.getDate("start_date").toLocalDate(),
        date(rs, "end_date"),
        rs.getString("status"),
        rs.getString("status_reason"),
        rs.getString("keycloak_user_id"),
        rs.getString("iam_sync"),
        rs.getInt("iam_sync_attempts"),
        rs.getString("iam_sync_error"),
        rs.getLong("row_version"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant(),
        rs.getString("registered_by"));
  }

  private static ProvisioningRow provisioning(ResultSet rs) throws SQLException {
    Timestamp next = rs.getTimestamp("next_attempt_at");
    return new ProvisioningRow(
        rs.getString("operator_id"),
        rs.getString("step"),
        rs.getInt("attempts"),
        rs.getString("last_error_code"),
        next == null ? null : next.toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }

  private static @Nullable LocalDate date(ResultSet rs, String col) throws SQLException {
    Date d = rs.getDate(col);
    return d == null ? null : d.toLocalDate();
  }

  private static List<String> list(@Nullable Array a) throws SQLException {
    return a == null
        ? List.of()
        : Arrays.stream((Object[]) a.getArray()).map(String::valueOf).toList();
  }
}
