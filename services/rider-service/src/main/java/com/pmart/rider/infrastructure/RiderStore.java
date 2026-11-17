package com.pmart.rider.infrastructure;

import com.pmart.rider.domain.Rider;
import com.pmart.rider.domain.Rider.Area;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 라이더 저장(JDBC, V2__riders.sql). 버전은 바꿀 때마다 +1(낙관적 잠금 X-Expected-Version 의 기준). */
@Repository
public class RiderStore {

  /** 가입 입력(검증 뒤). */
  public record NewRider(
      String riderId,
      String signupRequestId,
      @Nullable String name,
      String phone,
      String vehicleType,
      @Nullable String bankCode,
      @Nullable String bankAccountNumber,
      @Nullable String bankHolderName,
      @Nullable String residenceRoad,
      @Nullable String residenceDetail,
      List<Area> areas,
      List<String[]> consents,
      Instant at,
      @Nullable String licenseHash,
      @Nullable String licenseMasked) {}

  private static final String COLUMNS =
      "rider_id, version, signup_request_id, name, phone, vehicle_type, bank_code,"
          + " bank_account_number, bank_holder_name, residence_road, residence_detail,"
          + " operating_status, new_dispatch_enabled, operating_mfc_id, state_changed_at,"
          + " one_at_a_time, dispatch_mode, restriction, restriction_reason, restriction_until,"
          + " last_heartbeat_at, joined_at, license_masked";

  private final JdbcTemplate jdbc;

  public RiderStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public boolean licenseTakenByOther(String licenseHash, String riderId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM rider WHERE license_hash = ? AND rider_id <> ?",
            Integer.class,
            licenseHash,
            riderId);
    return n != null && n > 0;
  }

  public void setLicense(String riderId, String licenseHash, String licenseMasked) {
    jdbc.update(
        "UPDATE rider SET license_hash = ?, license_masked = ? WHERE rider_id = ?",
        licenseHash,
        licenseMasked,
        riderId);
  }

  /** 이 면허 해시로 가입한 라이더가 있는가(라이더 앱 요청 §7). */
  public boolean licenseTaken(String licenseHash) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM rider WHERE license_hash = ?", Integer.class, licenseHash);
    return n != null && n > 0;
  }

  public boolean phoneTaken(String phone) {
    Integer n =
        jdbc.queryForObject("SELECT count(*) FROM rider WHERE phone = ?", Integer.class, phone);
    return n != null && n > 0;
  }

  public void insert(NewRider r) {
    Timestamp at = Timestamp.from(r.at());
    jdbc.update(
        "INSERT INTO rider (rider_id, signup_request_id, name, phone, vehicle_type, bank_code,"
            + " bank_account_number, bank_holder_name, residence_road, residence_detail,"
            + " state_changed_at, joined_at, license_hash, license_masked) VALUES (?, ?, ?, ?, ?, ?, ?,"
            + " ?, ?, ?, ?, ?, ?, ?)",
        r.riderId(),
        r.signupRequestId(),
        r.name(),
        r.phone(),
        r.vehicleType(),
        r.bankCode(),
        r.bankAccountNumber(),
        r.bankHolderName(),
        r.residenceRoad(),
        r.residenceDetail(),
        at,
        at,
        r.licenseHash(),
        r.licenseMasked());
    replaceAreas(r.riderId(), r.areas());
    for (String[] c : r.consents()) {
      jdbc.update(
          "INSERT INTO rider_consent (rider_id, consent_type, version, agreed_at) VALUES (?, ?, ?, ?)"
              + " ON CONFLICT DO NOTHING",
          r.riderId(),
          c[0],
          c[1],
          at);
    }
  }

  public void replaceAreas(String riderId, List<Area> areas) {
    jdbc.update("DELETE FROM rider_delivery_area WHERE rider_id = ?", riderId);
    for (Area a : areas) {
      jdbc.update(
          "INSERT INTO rider_delivery_area (rider_id, mfc_id, zone_id) VALUES (?, ?, ?)"
              + " ON CONFLICT DO NOTHING",
          riderId,
          a.mfcId(),
          a.zoneId());
    }
  }

  public Optional<Rider> find(String riderId) {
    return one(
        "SELECT " + COLUMNS + " FROM rider WHERE rider_id = ? AND withdrawn_at IS NULL", riderId);
  }

  /** 트랜잭션 끝까지 그 라이더 행을 잡는다. */
  public Optional<Rider> lock(String riderId) {
    return one(
        "SELECT " + COLUMNS + " FROM rider WHERE rider_id = ? AND withdrawn_at IS NULL FOR UPDATE",
        riderId);
  }

  public Optional<Rider> findBySignupRequest(String riderId, String requestId) {
    return one(
        "SELECT "
            + COLUMNS
            + " FROM rider WHERE rider_id = ? AND signup_request_id = ? AND withdrawn_at IS NULL",
        riderId,
        requestId);
  }

  /** 직원 라이더 찾기 조건(0.4.0, BOD-3). 이름 검색은 없다(사용자 2026-11-15 — 동명이인). 비어 있는 값은 거르지 않는다. */
  public record Search(
      @Nullable String riderId,
      @Nullable String phone,
      @Nullable String restriction,
      @Nullable String operatingStatus,
      @Nullable String operatingMfcId,
      @Nullable String vehicleType,
      boolean byStateChange) {}

  /** 직원 라이더 찾기 — restriction 은 지금 유효한 제한(기한 지난 제한은 NORMAL)으로 거른다. */
  public List<Rider> search(Search s, Instant now, int limit, int offset) {
    List<Object> args = new ArrayList<>();
    StringBuilder sql =
        new StringBuilder("SELECT " + COLUMNS + " FROM rider WHERE withdrawn_at IS NULL");
    if (s.riderId() != null) {
      sql.append(" AND rider_id = ?");
      args.add(s.riderId());
    }
    if (s.phone() != null) {
      sql.append(" AND phone = ?");
      args.add(s.phone());
    }
    if (s.restriction() != null) {
      if ("NORMAL".equals(s.restriction())) {
        sql.append(" AND (restriction = 'NORMAL' OR restriction_until <= ?)");
      } else {
        sql.append(" AND restriction = ? AND (restriction_until IS NULL OR restriction_until > ?)");
        args.add(s.restriction());
      }
      args.add(Timestamp.from(now));
    }
    if (s.operatingStatus() != null) {
      sql.append(" AND operating_status = ?");
      args.add(s.operatingStatus());
    }
    if (s.operatingMfcId() != null) {
      sql.append(" AND operating_mfc_id = ?");
      args.add(s.operatingMfcId());
    }
    if (s.vehicleType() != null) {
      sql.append(" AND vehicle_type = ?");
      args.add(s.vehicleType());
    }
    sql.append(
        s.byStateChange()
            ? " ORDER BY state_changed_at DESC, rider_id"
            : " ORDER BY joined_at DESC, rider_id");
    sql.append(" LIMIT ? OFFSET ?");
    args.add(limit);
    args.add(offset);
    List<Row> rows = jdbc.query(sql.toString(), (rs, i) -> row(rs), args.toArray());
    return withAreas(rows);
  }

  /** 운행 중·신규배차 ON·제한 없음(기한 지난 제한 포함)·배달지역에 mfcId(적격 라이더 조회). */
  public List<Rider> eligible(String mfcId, @Nullable String vehicleType, int limit, Instant now) {
    List<Object> args = new ArrayList<>();
    StringBuilder sql =
        new StringBuilder(
            "SELECT "
                + COLUMNS
                + " FROM rider r WHERE r.withdrawn_at IS NULL AND r.operating_status = 'ON_DUTY' AND"
                + " r.new_dispatch_enabled"
                + " AND (r.restriction = 'NORMAL' OR r.restriction_until <= ?) AND EXISTS (SELECT 1"
                + " FROM rider_delivery_area a WHERE a.rider_id = r.rider_id AND a.mfc_id = ?)");
    args.add(Timestamp.from(now));
    args.add(mfcId);
    if (vehicleType != null) {
      sql.append(" AND r.vehicle_type = ?");
      args.add(vehicleType);
    }
    sql.append(" ORDER BY r.rider_id LIMIT ?");
    args.add(limit);
    List<Row> rows = jdbc.query(sql.toString(), (rs, i) -> row(rs), args.toArray());
    return withAreas(rows);
  }

  public int updateProfile(
      String riderId,
      int expectedVersion,
      String vehicleType,
      @Nullable String bankCode,
      @Nullable String bankAccountNumber,
      @Nullable String bankHolderName,
      @Nullable String residenceRoad,
      @Nullable String residenceDetail) {
    return jdbc.update(
        "UPDATE rider SET version = version + 1, vehicle_type = ?, bank_code = ?,"
            + " bank_account_number = ?, bank_holder_name = ?, residence_road = ?,"
            + " residence_detail = ? WHERE rider_id = ? AND version = ?",
        vehicleType,
        bankCode,
        bankAccountNumber,
        bankHolderName,
        residenceRoad,
        residenceDetail,
        riderId,
        expectedVersion);
  }

  public int updateOperating(
      String riderId,
      int expectedVersion,
      String status,
      boolean newDispatch,
      @Nullable String mfcId,
      Instant at) {
    Timestamp t = Timestamp.from(at);
    return jdbc.update(
        "UPDATE rider SET version = version + 1, operating_status = ?, new_dispatch_enabled = ?,"
            + " operating_mfc_id = ?, state_changed_at = ?, last_heartbeat_at = ?"
            + " WHERE rider_id = ? AND version = ?",
        status,
        newDispatch,
        mfcId,
        t,
        t,
        riderId,
        expectedVersion);
  }

  public int updateSettings(String riderId, int expectedVersion, boolean oneAtATime, String mode) {
    return jdbc.update(
        "UPDATE rider SET version = version + 1, one_at_a_time = ?, dispatch_mode = ?"
            + " WHERE rider_id = ? AND version = ?",
        oneAtATime,
        mode,
        riderId,
        expectedVersion);
  }

  // ---------- 계정 제한(RS-1) ----------

  /** 제한 이력 한 줄(V3). evidenceRefs 는 JSON 배열 문자열. */
  public record RestrictionRow(
      String requestId,
      String riderId,
      String restriction,
      String previous,
      String reason,
      @Nullable Instant until,
      String evidenceRefsJson,
      @Nullable String approvalRef,
      String changedBy,
      Instant changedAt,
      int riderVersion) {}

  /** 제한을 바꾼다. NORMAL 이 아니면 신규배차도 끈다(운행 상태는 그대로 — 픽업한 배송 전달 중). */
  public int updateRestriction(
      String riderId,
      int expectedVersion,
      String restriction,
      @Nullable String reason,
      @Nullable Instant until) {
    return jdbc.update(
        "UPDATE rider SET version = version + 1, restriction = ?, restriction_reason = ?,"
            + " restriction_until = ?, new_dispatch_enabled = CASE WHEN ? = 'NORMAL' THEN"
            + " new_dispatch_enabled ELSE false END WHERE rider_id = ? AND version = ?",
        restriction,
        reason,
        until == null ? null : Timestamp.from(until),
        restriction,
        riderId,
        expectedVersion);
  }

  public void insertRestriction(RestrictionRow r) {
    jdbc.update(
        "INSERT INTO rider_restriction_history (request_id, rider_id, restriction, previous, reason,"
            + " until_at, evidence_refs, approval_ref, changed_by, changed_at, rider_version)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        r.requestId(),
        r.riderId(),
        r.restriction(),
        r.previous(),
        r.reason(),
        r.until() == null ? null : Timestamp.from(r.until()),
        r.evidenceRefsJson(),
        r.approvalRef(),
        r.changedBy(),
        Timestamp.from(r.changedAt()),
        r.riderVersion());
  }

  public List<RestrictionRow> restrictions(String riderId, int limit) {
    return jdbc.query(
        "SELECT * FROM rider_restriction_history WHERE rider_id = ? ORDER BY changed_at DESC,"
            + " rider_version DESC LIMIT ?",
        (rs, i) ->
            new RestrictionRow(
                rs.getString("request_id"),
                rs.getString("rider_id"),
                rs.getString("restriction"),
                rs.getString("previous"),
                rs.getString("reason"),
                instantOrNull(rs, "until_at"),
                rs.getString("evidence_refs"),
                rs.getString("approval_ref"),
                rs.getString("changed_by"),
                instant(rs, "changed_at"),
                rs.getInt("rider_version")),
        riderId,
        limit);
  }

  // ---------- 읽기 ----------

  private record Row(Rider withoutAreas) {}

  private Optional<Rider> one(String sql, Object... args) {
    List<Row> rows = jdbc.query(sql, (rs, i) -> row(rs), args);
    List<Rider> riders = withAreas(rows);
    return riders.isEmpty() ? Optional.empty() : Optional.of(riders.get(0));
  }

  private List<Rider> withAreas(List<Row> rows) {
    if (rows.isEmpty()) {
      return List.of();
    }
    Map<String, List<Area>> areas = new LinkedHashMap<>();
    List<String> ids = rows.stream().map(r -> r.withoutAreas().riderId()).toList();
    String in = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
    jdbc.query(
        "SELECT rider_id, mfc_id, zone_id FROM rider_delivery_area WHERE rider_id IN ("
            + in
            + ") ORDER BY rider_id, mfc_id, zone_id",
        rs -> {
          areas
              .computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
              .add(new Area(rs.getString(2), rs.getString(3)));
        },
        ids.toArray());
    List<Rider> out = new ArrayList<>();
    for (Row r : rows) {
      Rider x = r.withoutAreas();
      out.add(
          new Rider(
              x.riderId(),
              x.version(),
              x.signupRequestId(),
              x.name(),
              x.phone(),
              x.vehicleType(),
              x.bankCode(),
              x.bankAccountNumber(),
              x.bankHolderName(),
              x.residenceRoad(),
              x.residenceDetail(),
              x.operatingStatus(),
              x.newDispatchEnabled(),
              x.operatingMfcId(),
              x.stateChangedAt(),
              x.oneAtATime(),
              x.dispatchMode(),
              x.restriction(),
              x.restrictionReason(),
              x.restrictionUntil(),
              x.lastHeartbeatAt(),
              x.joinedAt(),
              List.copyOf(areas.getOrDefault(x.riderId(), List.of())),
              x.licenseMasked()));
    }
    return out;
  }

  private static Row row(ResultSet rs) throws SQLException {
    return new Row(
        new Rider(
            rs.getString("rider_id"),
            rs.getInt("version"),
            rs.getString("signup_request_id"),
            rs.getString("name"),
            rs.getString("phone"),
            rs.getString("vehicle_type"),
            rs.getString("bank_code"),
            rs.getString("bank_account_number"),
            rs.getString("bank_holder_name"),
            rs.getString("residence_road"),
            rs.getString("residence_detail"),
            Rider.OperatingStatus.valueOf(rs.getString("operating_status")),
            rs.getBoolean("new_dispatch_enabled"),
            rs.getString("operating_mfc_id"),
            instant(rs, "state_changed_at"),
            rs.getBoolean("one_at_a_time"),
            Rider.DispatchMode.valueOf(rs.getString("dispatch_mode")),
            Rider.Restriction.valueOf(rs.getString("restriction")),
            rs.getString("restriction_reason"),
            instantOrNull(rs, "restriction_until"),
            instantOrNull(rs, "last_heartbeat_at"),
            instant(rs, "joined_at"),
            List.of(),
            rs.getString("license_masked")));
  }

  private static Instant instant(ResultSet rs, String col) throws SQLException {
    return rs.getTimestamp(col).toInstant();
  }

  private static @Nullable Instant instantOrNull(ResultSet rs, String col) throws SQLException {
    Timestamp t = rs.getTimestamp(col);
    return t == null ? null : t.toInstant();
  }

  // ---------- 탈퇴(2026-11-17) ----------

  /** 이 라이더가 탈퇴한 행이 있는가(가입 거절 — 같은 주체로 다시 가입하지 않는다, 다시 로그인하면 새 주체). */
  public boolean withdrawn(String riderId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM rider WHERE rider_id = ? AND withdrawn_at IS NOT NULL",
            Integer.class,
            riderId);
    return n != null && n > 0;
  }

  /** 열린 제한 검토(RS-3) 수. */
  public int openRestrictionReviews(String riderId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM rider_restriction_review WHERE rider_id = ? AND state = 'OPEN'",
            Integer.class,
            riderId);
    return n == null ? 0 : n;
  }

  /** 탈퇴: 개인정보를 지우고(사용자 "즉시 가림·재가입 허용") 운행·신규배차를 끈다. 행은 이력의 주체로 남는다. */
  public void withdraw(String riderId, Instant at) {
    jdbc.update(
        "UPDATE rider SET name = NULL, phone = NULL, bank_code = NULL, bank_account_number = NULL,"
            + " bank_holder_name = NULL, residence_road = NULL, residence_detail = NULL, license_hash ="
            + " NULL, license_masked = NULL, operating_status = 'OFF_DUTY', new_dispatch_enabled ="
            + " false, operating_mfc_id = NULL, withdrawn_at = ?, state_changed_at = ?, version ="
            + " version + 1 WHERE rider_id = ?",
        java.sql.Timestamp.from(at),
        java.sql.Timestamp.from(at),
        riderId);
  }

  public void upsertWithdrawal(
      String riderId,
      String requestId,
      String state,
      List<String> blockers,
      @Nullable String reason,
      Instant at,
      @Nullable Instant completedAt) {
    jdbc.update(
        "INSERT INTO rider_withdrawal (rider_id, request_id, state, blockers, reason, requested_at,"
            + " completed_at) VALUES (?, ?, ?, ?::jsonb, ?, ?, ?) ON CONFLICT (rider_id, request_id)"
            + " DO UPDATE SET state = EXCLUDED.state, blockers = EXCLUDED.blockers, completed_at ="
            + " EXCLUDED.completed_at",
        riderId,
        requestId,
        state,
        blockers.stream()
            .map(b -> "\"" + b + "\"")
            .collect(java.util.stream.Collectors.joining(",", "[", "]")),
        reason,
        java.sql.Timestamp.from(at),
        completedAt == null ? null : java.sql.Timestamp.from(completedAt));
  }

  public Optional<com.pmart.rider.application.WithdrawalService.Withdrawal> withdrawal(
      String riderId, String requestId) {
    return jdbc
        .query(
            "SELECT request_id, state, blockers::text, requested_at, completed_at FROM"
                + " rider_withdrawal WHERE rider_id = ? AND request_id = ?",
            (rs, i) -> {
              String raw = rs.getString(3);
              List<String> b = new java.util.ArrayList<>();
              java.util.regex.Matcher m =
                  java.util.regex.Pattern.compile("\"([A-Z_]+)\"").matcher(raw);
              while (m.find()) {
                b.add(m.group(1));
              }
              java.sql.Timestamp done = rs.getTimestamp(5);
              return new com.pmart.rider.application.WithdrawalService.Withdrawal(
                  rs.getString(1),
                  rs.getString(2),
                  b,
                  rs.getTimestamp(4).toInstant(),
                  done == null ? null : done.toInstant());
            },
            riderId,
            requestId)
        .stream()
        .findFirst();
  }
}
