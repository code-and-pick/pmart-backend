package com.pmart.fleet.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 운송사·차량·기사 정본과 점유 예약(TMS 1단계 0.2.0 — 정본 4.4·4.5·7.5). 센터에 묶이지 않는 기준정보라 조회는 운영자 누구나, 등록·변경은
 * CDC_SUPERVISOR. 점유는 [시작, 끝) 겹침을 차량·기사 따로 보고, 같은 자원의 동시 요청은 권고 잠금으로 줄 세운다.
 */
@Service
public class FleetService {

  static final String MANAGER = "CDC_SUPERVISOR";
  static final List<String> TEMPS = List.of("AMBIENT", "CHILLED", "FROZEN");

  public record Carrier(String carrierId, String name, boolean active, Instant createdAt) {}

  public record Vehicle(
      String vehicleId,
      String carrierId,
      @Nullable String plateNo,
      String contractType,
      String tempClass,
      BigDecimal tonnage,
      int maxRt,
      int maxWeightKg,
      boolean active,
      int version,
      Instant createdAt,
      Instant updatedAt) {}

  public record NewVehicle(
      String vehicleId,
      String carrierId,
      @Nullable String plateNo,
      String contractType,
      String tempClass,
      BigDecimal tonnage,
      int maxRt,
      int maxWeightKg) {}

  public record VehicleChange(
      @Nullable String plateNo,
      @Nullable Integer maxRt,
      @Nullable Integer maxWeightKg,
      @Nullable Boolean active) {}

  public record Driver(
      String driverId,
      String carrierId,
      String displayName,
      boolean active,
      int version,
      Instant createdAt) {}

  public record Reservation(
      String reservationId,
      String state,
      String vehicleId,
      String driverId,
      Instant start,
      Instant end,
      String purposeRef,
      @Nullable String releaseReason,
      Instant createdAt,
      @Nullable Instant releasedAt) {}

  public record NewReservation(
      String vehicleId, String driverId, Instant start, Instant end, String purposeRef) {}

  /** 멱등 재생용 결과(JSON 왕복). */
  public record Ref(String id) {}

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final RequestIdempotency idempotency;
  private final Clock clock;

  public FleetService(
      JdbcTemplate jdbc, TransactionTemplate tx, RequestIdempotency idempotency, Clock clock) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.idempotency = idempotency;
    this.clock = clock;
  }

  // ---------- 호출자 ----------

  static PmartPrincipal operator() {
    return PmartPrincipal.current()
        .filter(x -> x.type() == PmartPrincipal.PrincipalType.OPERATOR)
        .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "운영자만 할 수 있습니다."));
  }

  static PmartPrincipal manager() {
    PmartPrincipal p = operator();
    if (!p.hasRole(MANAGER)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "기준정보 등록·변경은 " + MANAGER + " 만 할 수 있습니다.");
    }
    return p;
  }

  /** 서비스 간 호출의 행위자 — 서비스 토큰이면 주체, 토큰 없이 오면 익명. */
  static String actor() {
    return PmartPrincipal.current()
        .map(PmartPrincipal::subject)
        .orElse(RequestIdempotency.ANONYMOUS);
  }

  // ---------- 운송사 ----------

  public List<Carrier> carriers() {
    operator();
    return jdbc.query(
        "SELECT carrier_id, name, active, created_at FROM carrier ORDER BY carrier_id",
        (rs, i) ->
            new Carrier(
                rs.getString(1),
                rs.getString(2),
                rs.getBoolean(3),
                rs.getTimestamp(4).toInstant()));
  }

  public Carrier createCarrier(String requestId, String source, String carrierId, String name) {
    PmartPrincipal p = manager();
    Ref r =
        idempotency
            .execute(
                new RequestKey(source, requestId, "fleet:createCarrier", p.subject()),
                List.of(carrierId, name),
                Ref.class,
                () -> {
                  insertOnce(
                      "운송사 " + carrierId,
                      () ->
                          jdbc.update(
                              "INSERT INTO carrier (carrier_id, name, active, created_at) VALUES"
                                  + " (?,?,true,?)",
                              carrierId,
                              name.strip(),
                              Timestamp.from(clock.instant())));
                  return new Ref(carrierId);
                })
            .value();
    return carriers().stream().filter(c -> c.carrierId().equals(r.id())).findFirst().orElseThrow();
  }

  boolean carrierExists(String carrierId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM carrier WHERE carrier_id = ?", Integer.class, carrierId);
    return n != null && n > 0;
  }

  // ---------- 차량 ----------

  public List<Vehicle> vehicles(
      @Nullable String carrierId,
      @Nullable String tempClass,
      @Nullable String contractType,
      @Nullable Boolean active) {
    operator();
    StringBuilder sql = new StringBuilder(VEHICLE_SELECT + " WHERE 1 = 1");
    List<Object> args = new ArrayList<>();
    if (carrierId != null) {
      sql.append(" AND carrier_id = ?");
      args.add(carrierId);
    }
    if (tempClass != null) {
      sql.append(" AND temp_class = ?");
      args.add(tempClass);
    }
    if (contractType != null) {
      sql.append(" AND contract_type = ?");
      args.add(contractType);
    }
    if (active != null) {
      sql.append(" AND active = ?");
      args.add(active);
    }
    sql.append(" ORDER BY vehicle_id");
    return jdbc.query(sql.toString(), (rs, i) -> vehicle(rs), args.toArray());
  }

  /** 차량 하나 — 운영자 또는 서비스(planning 의 정규차 확인). */
  public Vehicle vehicle(String vehicleId) {
    return findVehicle(vehicleId)
        .orElseThrow(() -> PmartException.notFound("NOT_FOUND", "차량 " + vehicleId + " 이 없습니다."));
  }

  java.util.Optional<Vehicle> findVehicle(String vehicleId) {
    return jdbc
        .query(VEHICLE_SELECT + " WHERE vehicle_id = ?", (rs, i) -> vehicle(rs), vehicleId)
        .stream()
        .findFirst();
  }

  public Vehicle createVehicle(String requestId, String source, NewVehicle v) {
    PmartPrincipal p = manager();
    if (!TEMPS.contains(v.tempClass())) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "온도대가 바르지 않습니다: " + v.tempClass());
    }
    Ref r =
        idempotency
            .execute(
                new RequestKey(source, requestId, "fleet:createVehicle", p.subject()),
                v,
                Ref.class,
                () -> {
                  if (!carrierExists(v.carrierId())) {
                    throw PmartException.notFound(
                        "CARRIER_NOT_FOUND", "운송사 " + v.carrierId() + " 이 없습니다.");
                  }
                  Timestamp now = Timestamp.from(clock.instant());
                  insertOnce(
                      "차량 " + v.vehicleId(),
                      () ->
                          jdbc.update(
                              "INSERT INTO vehicle (vehicle_id, carrier_id, plate_no, contract_type,"
                                  + " temp_class, tonnage, max_rt, max_weight_kg, active, version,"
                                  + " created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,true,1,?,?)",
                              v.vehicleId(),
                              v.carrierId(),
                              v.plateNo(),
                              v.contractType(),
                              v.tempClass(),
                              v.tonnage(),
                              v.maxRt(),
                              v.maxWeightKg(),
                              now,
                              now));
                  return new Ref(v.vehicleId());
                })
            .value();
    return vehicle(r.id());
  }

  public Vehicle changeVehicle(
      String requestId,
      String source,
      String vehicleId,
      VehicleChange c,
      @Nullable Integer expectedVersion) {
    PmartPrincipal p = manager();
    idempotency.execute(
        new RequestKey(source, requestId, "fleet:changeVehicle", p.subject()),
        List.of(vehicleId, c, expectedVersion == null ? -1 : expectedVersion),
        Ref.class,
        () ->
            tx.execute(
                s -> {
                  Vehicle cur = vehicle(vehicleId);
                  if (expectedVersion != null && expectedVersion != cur.version()) {
                    throw PmartException.versionConflict(
                        "차량 version 이 " + cur.version() + " 입니다(요청 " + expectedVersion + ").");
                  }
                  jdbc.update(
                      "UPDATE vehicle SET plate_no = ?, max_rt = ?, max_weight_kg = ?, active = ?,"
                          + " version = version + 1, updated_at = ? WHERE vehicle_id = ?",
                      c.plateNo() == null ? cur.plateNo() : c.plateNo(),
                      c.maxRt() == null ? cur.maxRt() : c.maxRt(),
                      c.maxWeightKg() == null ? cur.maxWeightKg() : c.maxWeightKg(),
                      c.active() == null ? cur.active() : c.active(),
                      Timestamp.from(clock.instant()),
                      vehicleId);
                  return new Ref(vehicleId);
                }));
    return vehicle(vehicleId);
  }

  private static final String VEHICLE_SELECT =
      "SELECT vehicle_id, carrier_id, plate_no, contract_type, temp_class, tonnage, max_rt,"
          + " max_weight_kg, active, version, created_at, updated_at FROM vehicle";

  private static Vehicle vehicle(ResultSet rs) throws SQLException {
    return new Vehicle(
        rs.getString("vehicle_id"),
        rs.getString("carrier_id"),
        rs.getString("plate_no"),
        rs.getString("contract_type"),
        rs.getString("temp_class"),
        rs.getBigDecimal("tonnage"),
        rs.getInt("max_rt"),
        rs.getInt("max_weight_kg"),
        rs.getBoolean("active"),
        rs.getInt("version"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }

  // ---------- 기사 ----------

  public List<Driver> drivers(@Nullable String carrierId, @Nullable Boolean active) {
    operator();
    StringBuilder sql = new StringBuilder(DRIVER_SELECT + " WHERE 1 = 1");
    List<Object> args = new ArrayList<>();
    if (carrierId != null) {
      sql.append(" AND carrier_id = ?");
      args.add(carrierId);
    }
    if (active != null) {
      sql.append(" AND active = ?");
      args.add(active);
    }
    sql.append(" ORDER BY driver_id");
    return jdbc.query(sql.toString(), (rs, i) -> driver(rs), args.toArray());
  }

  public Driver driver(String driverId) {
    return findDriver(driverId)
        .orElseThrow(() -> PmartException.notFound("NOT_FOUND", "기사 " + driverId + " 이 없습니다."));
  }

  java.util.Optional<Driver> findDriver(String driverId) {
    return jdbc
        .query(DRIVER_SELECT + " WHERE driver_id = ?", (rs, i) -> driver(rs), driverId)
        .stream()
        .findFirst();
  }

  public Driver createDriver(
      String requestId, String source, String driverId, String carrierId, String displayName) {
    PmartPrincipal p = manager();
    Ref r =
        idempotency
            .execute(
                new RequestKey(source, requestId, "fleet:createDriver", p.subject()),
                List.of(driverId, carrierId, displayName),
                Ref.class,
                () -> {
                  if (!carrierExists(carrierId)) {
                    throw PmartException.notFound(
                        "CARRIER_NOT_FOUND", "운송사 " + carrierId + " 이 없습니다.");
                  }
                  insertOnce(
                      "기사 " + driverId,
                      () ->
                          jdbc.update(
                              "INSERT INTO driver (driver_id, carrier_id, display_name, active,"
                                  + " version, created_at) VALUES (?,?,?,true,1,?)",
                              driverId,
                              carrierId,
                              displayName.strip(),
                              Timestamp.from(clock.instant())));
                  return new Ref(driverId);
                })
            .value();
    return driver(r.id());
  }

  private static final String DRIVER_SELECT =
      "SELECT driver_id, carrier_id, display_name, active, version, created_at FROM driver";

  private static Driver driver(ResultSet rs) throws SQLException {
    return new Driver(
        rs.getString("driver_id"),
        rs.getString("carrier_id"),
        rs.getString("display_name"),
        rs.getBoolean("active"),
        rs.getInt("version"),
        rs.getTimestamp("created_at").toInstant());
  }

  // ---------- 점유 ----------

  /**
   * 점유 잡기. 같은 purposeRef 의 살아 있는 점유가 같은 차량·기사·창이면 그대로, 다르면 풀고 새로(REPLANNED). 겹치면 409
   * RESOURCE_OCCUPIED, 사용 중지면 409 RESOURCE_INACTIVE.
   */
  public Reservation reserve(String requestId, String source, NewReservation n) {
    if (!n.start().isBefore(n.end())) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "점유 시작이 끝보다 앞이어야 합니다.");
    }
    Ref r =
        idempotency
            .execute(
                new RequestKey(source, requestId, "fleet:reserve", actor()),
                n,
                Ref.class,
                () -> tx.execute(s -> new Ref(reserveTx(n))))
            .value();
    return reservation(r.id());
  }

  private String reserveTx(NewReservation n) {
    Vehicle v =
        findVehicle(n.vehicleId())
            .orElseThrow(
                () -> PmartException.notFound("NOT_FOUND", "차량 " + n.vehicleId() + " 이 없습니다."));
    Driver d =
        findDriver(n.driverId())
            .orElseThrow(
                () -> PmartException.notFound("NOT_FOUND", "기사 " + n.driverId() + " 이 없습니다."));
    if (!v.active() || !d.active()) {
      throw conflict(
          "RESOURCE_INACTIVE",
          (!v.active() ? "차량 " + v.vehicleId() : "기사 " + d.driverId()) + " 은 사용 중지입니다.");
    }
    // 같은 자원끼리 줄 세우기(차량·기사 ID 순서대로 — 교착 막기)
    List<String> locks = new ArrayList<>(List.of("V:" + n.vehicleId(), "D:" + n.driverId()));
    locks.sort(null);
    for (String k : locks) {
      jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?))", rs -> {}, "fleet:" + k);
    }
    Instant now = clock.instant();
    List<Reservation> same =
        jdbc.query(
            RES_SELECT + " WHERE purpose_ref = ? AND state = 'HELD'",
            (rs, i) -> reservation(rs),
            n.purposeRef());
    if (!same.isEmpty()) {
      Reservation cur = same.get(0);
      if (cur.vehicleId().equals(n.vehicleId())
          && cur.driverId().equals(n.driverId())
          && cur.start().equals(n.start())
          && cur.end().equals(n.end())) {
        return cur.reservationId();
      }
      release(cur.reservationId(), "REPLANNED", now);
    }
    List<Object[]> clash =
        jdbc.query(
            "SELECT reservation_id, CASE WHEN vehicle_id = ? THEN 'VEHICLE' ELSE 'DRIVER' END"
                + " FROM fleet_reservation WHERE state = 'HELD' AND (vehicle_id = ? OR driver_id = ?)"
                + " AND window_start < ? AND ? < window_end ORDER BY window_start LIMIT 1",
            (rs, i) -> new Object[] {rs.getString(1), rs.getString(2)},
            n.vehicleId(),
            n.vehicleId(),
            n.driverId(),
            Timestamp.from(n.end()),
            Timestamp.from(n.start()));
    if (!clash.isEmpty()) {
      throw new PmartException(
          "RESOURCE_OCCUPIED",
          HttpStatus.CONFLICT,
          ("VEHICLE".equals(clash.get(0)[1]) ? "차량" : "기사") + " 이 같은 시간대에 이미 점유되어 있습니다.",
          false,
          List.of(
              new PmartException.Detail(
                  String.valueOf(clash.get(0)[1]), String.valueOf(clash.get(0)[0]))));
    }
    String id = "FRS-" + UUID.randomUUID();
    jdbc.update(
        "INSERT INTO fleet_reservation (reservation_id, vehicle_id, driver_id, window_start,"
            + " window_end, purpose_ref, state, created_at) VALUES (?,?,?,?,?,?,'HELD',?)",
        id,
        n.vehicleId(),
        n.driverId(),
        Timestamp.from(n.start()),
        Timestamp.from(n.end()),
        n.purposeRef(),
        Timestamp.from(now));
    return id;
  }

  public Reservation reservation(String reservationId) {
    return jdbc
        .query(RES_SELECT + " WHERE reservation_id = ?", (rs, i) -> reservation(rs), reservationId)
        .stream()
        .findFirst()
        .orElseThrow(
            () -> PmartException.notFound("NOT_FOUND", "점유 " + reservationId + " 이 없습니다."));
  }

  /** 점유 풀기 — 이미 풀렸으면 그대로. */
  public Reservation release(String requestId, String source, String reservationId, String reason) {
    reservation(reservationId);
    idempotency.execute(
        new RequestKey(source, requestId, "fleet:release", actor()),
        List.of(reservationId, reason),
        Ref.class,
        () -> {
          release(reservationId, reason, clock.instant());
          return new Ref(reservationId);
        });
    return reservation(reservationId);
  }

  private void release(String reservationId, String reason, Instant at) {
    jdbc.update(
        "UPDATE fleet_reservation SET state = 'RELEASED', release_reason = ?, released_at = ?"
            + " WHERE reservation_id = ? AND state = 'HELD'",
        reason,
        Timestamp.from(at),
        reservationId);
  }

  private static final String RES_SELECT =
      "SELECT reservation_id, state, vehicle_id, driver_id, window_start, window_end, purpose_ref,"
          + " release_reason, created_at, released_at FROM fleet_reservation";

  private static Reservation reservation(ResultSet rs) throws SQLException {
    Timestamp rel = rs.getTimestamp("released_at");
    return new Reservation(
        rs.getString("reservation_id"),
        rs.getString("state"),
        rs.getString("vehicle_id"),
        rs.getString("driver_id"),
        rs.getTimestamp("window_start").toInstant(),
        rs.getTimestamp("window_end").toInstant(),
        rs.getString("purpose_ref"),
        rs.getString("release_reason"),
        rs.getTimestamp("created_at").toInstant(),
        rel == null ? null : rel.toInstant());
  }

  // ---------- 공통 ----------

  private static void insertOnce(String what, Runnable insert) {
    try {
      insert.run();
    } catch (DuplicateKeyException e) {
      throw conflict("ALREADY_EXISTS", what + " 은 이미 있습니다.");
    }
  }

  static PmartException conflict(String code, String message) {
    return new PmartException(code, HttpStatus.CONFLICT, message, false, List.of());
  }
}
