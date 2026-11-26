package com.pmart.cdc.outbound.application;

import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CDC 근무(0.29.0 — DC 백오피스 WF-2, 사용자 "시뮬레이션에서 정한대로 해"). 근무조·층 × 직무 계획 인원은 시뮬레이터 값(Flyway V18 시드 —
 * [시뮬]·[가정]). 배치는 그날 근무조마다 작업자 한 명 한 줄, 출근·퇴근은 시각만 남긴다. 계획 인원은 참고값 — 넘어도 막지 않는다.
 */
@Service
public class Workforce {

  static final String SUPERVISOR = "CDC_SUPERVISOR";
  static final Set<String> FLOORS = Set.of("5", "6", "7", "ALL");
  static final Set<String> ROLES =
      Set.of("FC", "SORT", "JOCKEY", "REPLENISH", "DPS", "INSPECTOR", "NIGHT_JOCKEY", "LOADER");

  public record Shift(
      String shiftCode,
      String name,
      String startTime,
      String endTime,
      List<String> breaks,
      String source) {}

  public record Cell(
      String shiftCode,
      String floor,
      String role,
      int planned,
      @Nullable Integer assigned,
      @Nullable Integer checkedIn) {}

  public record Assignment(
      String assignmentId,
      LocalDate workDate,
      String shiftCode,
      String workerId,
      String floor,
      String role,
      String assignedBy,
      @Nullable Instant checkInAt,
      @Nullable Instant checkOutAt) {}

  public record Shifts(List<Shift> shifts, List<Cell> plan) {}

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final Clock clock;

  public Workforce(JdbcTemplate jdbc, TransactionTemplate tx, Clock clock) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.clock = clock;
  }

  public Shifts shifts(String cdcId) {
    Support.requireCenter(cdcId);
    List<Shift> shifts =
        jdbc.query(
            "SELECT shift_code, name, start_time, end_time, breaks, source FROM shift_template"
                + " WHERE cdc_id = ? ORDER BY start_time",
            (rs, i) ->
                new Shift(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getString(5).isEmpty() ? List.of() : List.of(rs.getString(5).split(",")),
                    rs.getString(6)),
            cdcId);
    return new Shifts(shifts, plan(cdcId));
  }

  private List<Cell> plan(String cdcId) {
    return jdbc.query(
        "SELECT shift_code, floor, role, headcount FROM staffing_plan WHERE cdc_id = ?"
            + " ORDER BY shift_code, floor, role",
        (rs, i) ->
            new Cell(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), null, null),
        cdcId);
  }

  public List<Assignment> assignments(String cdcId, LocalDate date, @Nullable String shiftCode) {
    Support.requireCenter(cdcId);
    return shiftCode == null
        ? jdbc.query(
            SELECT + " WHERE cdc_id = ? AND work_date = ?" + ORDER, (rs, i) -> row(rs), cdcId, date)
        : jdbc.query(
            SELECT + " WHERE cdc_id = ? AND work_date = ? AND shift_code = ?" + ORDER,
            (rs, i) -> row(rs),
            cdcId,
            date,
            shiftCode);
  }

  public Assignment assign(
      String cdcId, LocalDate date, String shiftCode, String workerId, String floor, String role) {
    String me = supervisor(cdcId);
    if (!FLOORS.contains(floor) || !ROLES.contains(role)) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "층(5·6·7·ALL)·직무가 바르지 않습니다.");
    }
    Integer known =
        jdbc.queryForObject(
            "SELECT count(*) FROM shift_template WHERE cdc_id = ? AND shift_code = ?",
            Integer.class,
            cdcId,
            shiftCode);
    if (known == null || known == 0) {
      throw new PmartException(
          "UNKNOWN_SHIFT", HttpStatus.BAD_REQUEST, "없는 근무조입니다: " + shiftCode, false, List.of());
    }
    String id =
        tx.execute(
            s -> {
              jdbc.query(
                  "SELECT pg_advisory_xact_lock(hashtext(?))",
                  rs -> {},
                  "cdc-outbound:shift:" + cdcId + ":" + date + ":" + shiftCode + ":" + workerId);
              Instant now = clock.instant();
              var prior =
                  jdbc.query(
                      SELECT
                          + " WHERE cdc_id = ? AND work_date = ? AND shift_code = ? AND worker_id = ?",
                      (rs, i) -> row(rs),
                      cdcId,
                      date,
                      shiftCode,
                      workerId);
              if (!prior.isEmpty()) {
                Assignment a = prior.get(0);
                if (a.checkInAt() != null) {
                  if (a.floor().equals(floor) && a.role().equals(role)) {
                    return a.assignmentId();
                  }
                  throw conflict("CHECKED_IN", "이미 출근해 배치를 바꿀 수 없습니다.");
                }
                jdbc.update(
                    "UPDATE shift_assignment SET floor = ?, role = ?, assigned_by = ?, updated_at = ?"
                        + " WHERE assignment_id = ?",
                    floor,
                    role,
                    me,
                    Timestamp.from(now),
                    a.assignmentId());
                return a.assignmentId();
              }
              String nid = "SA-" + UUID.randomUUID();
              jdbc.update(
                  "INSERT INTO shift_assignment (assignment_id, cdc_id, work_date, shift_code, worker_id,"
                      + " floor, role, assigned_by, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                  nid,
                  cdcId,
                  date,
                  shiftCode,
                  workerId,
                  floor,
                  role,
                  me,
                  Timestamp.from(now),
                  Timestamp.from(now));
              return nid;
            });
    return get(cdcId, id);
  }

  public Assignment checkIn(String cdcId, String assignmentId) {
    Assignment a = get(cdcId, assignmentId);
    selfOrSupervisor(cdcId, a.workerId());
    jdbc.update(
        "UPDATE shift_assignment SET check_in_at = ?, updated_at = ? WHERE assignment_id = ?"
            + " AND check_in_at IS NULL",
        Timestamp.from(clock.instant()),
        Timestamp.from(clock.instant()),
        assignmentId);
    return get(cdcId, assignmentId);
  }

  public Assignment checkOut(String cdcId, String assignmentId) {
    Assignment a = get(cdcId, assignmentId);
    selfOrSupervisor(cdcId, a.workerId());
    if (a.checkInAt() == null) {
      throw conflict("NOT_CHECKED_IN", "출근 기록이 없습니다.");
    }
    jdbc.update(
        "UPDATE shift_assignment SET check_out_at = ?, updated_at = ? WHERE assignment_id = ?"
            + " AND check_out_at IS NULL",
        Timestamp.from(clock.instant()),
        Timestamp.from(clock.instant()),
        assignmentId);
    return get(cdcId, assignmentId);
  }

  /** 계획 대비 현황 — 계획 칸 + 배치만 있는 칸(계획 0). */
  public List<Cell> staffing(String cdcId, LocalDate date) {
    Support.requireCenter(cdcId);
    Map<String, int[]> counts = new LinkedHashMap<>();
    for (Assignment a : assignments(cdcId, date, null)) {
      int[] c =
          counts.computeIfAbsent(a.shiftCode() + "|" + a.floor() + "|" + a.role(), k -> new int[2]);
      c[0]++;
      if (a.checkInAt() != null && a.checkOutAt() == null) {
        c[1]++;
      }
    }
    List<Cell> out = new ArrayList<>();
    for (Cell p : plan(cdcId)) {
      int[] c = counts.remove(p.shiftCode() + "|" + p.floor() + "|" + p.role());
      out.add(
          new Cell(
              p.shiftCode(),
              p.floor(),
              p.role(),
              p.planned(),
              c == null ? 0 : c[0],
              c == null ? 0 : c[1]));
    }
    for (var e : counts.entrySet()) {
      String[] k = e.getKey().split("\\|");
      out.add(new Cell(k[0], k[1], k[2], 0, e.getValue()[0], e.getValue()[1]));
    }
    return out;
  }

  Assignment get(String cdcId, String assignmentId) {
    Support.requireCenter(cdcId);
    return jdbc
        .query(
            SELECT + " WHERE assignment_id = ? AND cdc_id = ?",
            (rs, i) -> row(rs),
            assignmentId,
            cdcId)
        .stream()
        .findFirst()
        .orElseThrow(() -> PmartException.notFound("NOT_FOUND", "배치 " + assignmentId + " 가 없습니다."));
  }

  private static String supervisor(String cdcId) {
    String me = Support.requireCenter(cdcId);
    boolean sup = PmartPrincipal.current().map(p -> p.hasRole(SUPERVISOR)).orElse(false);
    if (!sup) {
      throw new PmartException(ErrorCode.FORBIDDEN, "근무 배치는 " + SUPERVISOR + " 만 할 수 있습니다.");
    }
    return me;
  }

  private static void selfOrSupervisor(String cdcId, String workerId) {
    String me = Support.requireCenter(cdcId);
    boolean sup = PmartPrincipal.current().map(p -> p.hasRole(SUPERVISOR)).orElse(false);
    if (!me.equals(workerId) && !sup) {
      throw new PmartException(ErrorCode.FORBIDDEN, "작업자 본인이나 " + SUPERVISOR + " 만 할 수 있습니다.");
    }
  }

  private static final String SELECT =
      "SELECT assignment_id, work_date, shift_code, worker_id, floor, role, assigned_by, check_in_at,"
          + " check_out_at FROM shift_assignment";
  private static final String ORDER = " ORDER BY shift_code, floor, role, worker_id";

  private static Assignment row(ResultSet rs) throws SQLException {
    Timestamp in = rs.getTimestamp(8);
    Timestamp out = rs.getTimestamp(9);
    return new Assignment(
        rs.getString(1),
        rs.getDate(2).toLocalDate(),
        rs.getString(3),
        rs.getString(4),
        rs.getString(5),
        rs.getString(6),
        rs.getString(7),
        in == null ? null : in.toInstant(),
        out == null ? null : out.toInstant());
  }

  static PmartException conflict(String code, String message) {
    return new PmartException(code, HttpStatus.CONFLICT, message, false, List.of());
  }
}
