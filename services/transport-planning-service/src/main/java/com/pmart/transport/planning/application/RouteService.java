package com.pmart.transport.planning.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.transport.planning.domain.Schedule;
import com.pmart.transport.planning.infrastructure.Clients;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 고정 노선과 노선 × 온도대 정규차 계약(TMS 정본 4.3·4.5 — 설계 §2). */
@Service
public class RouteService {

  static final List<String> TEMPS = List.of("AMBIENT", "CHILLED", "FROZEN");

  public record StopView(int seq, String mfcId, int legMinutes, int arrivalOffsetMinutes) {}

  public record VehicleView(
      String tempClass,
      String vehicleId,
      String driverId,
      @Nullable String plateNo,
      @Nullable String driverName,
      int maxRt,
      int maxWeightKg) {}

  public record RouteView(
      String routeId,
      String cdcId,
      String name,
      String departureTime,
      int returnMinutes,
      boolean active,
      int version,
      List<StopView> stops,
      List<VehicleView> vehicles) {

    public List<Schedule.Leg> legs() {
      return stops.stream().map(s -> new Schedule.Leg(s.mfcId(), s.legMinutes())).toList();
    }

    public Optional<VehicleView> vehicle(String temp) {
      return vehicles.stream().filter(v -> v.tempClass().equals(temp)).findFirst();
    }
  }

  public record NewRoute(
      String routeId,
      String name,
      String departureTime,
      List<Schedule.Leg> stops,
      int returnMinutes) {}

  public record Ref(String id) {}

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final RequestIdempotency idempotency;
  private final Clients clients;
  private final Clock clock;

  public RouteService(
      JdbcTemplate jdbc,
      TransactionTemplate tx,
      RequestIdempotency idempotency,
      Clients clients,
      Clock clock) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.idempotency = idempotency;
    this.clients = clients;
    this.clock = clock;
  }

  public List<RouteView> list(String cdcId, @Nullable Boolean active) {
    Operator.requireCenter(cdcId);
    return routes(cdcId, active);
  }

  public RouteView get(String cdcId, String routeId) {
    Operator.requireCenter(cdcId);
    return route(cdcId, routeId);
  }

  List<RouteView> routes(String cdcId, @Nullable Boolean active) {
    List<String> ids =
        active == null
            ? jdbc.queryForList(
                "SELECT route_id FROM route WHERE cdc_id = ? ORDER BY route_id",
                String.class,
                cdcId)
            : jdbc.queryForList(
                "SELECT route_id FROM route WHERE cdc_id = ? AND active = ? ORDER BY route_id",
                String.class,
                cdcId,
                active);
    return ids.stream().map(id -> route(cdcId, id)).toList();
  }

  RouteView route(String cdcId, String routeId) {
    var head =
        jdbc.queryForList(
            "SELECT name, departure_time, return_minutes, active, version FROM route"
                + " WHERE route_id = ? AND cdc_id = ?",
            routeId,
            cdcId);
    if (head.isEmpty()) {
      throw PmartException.notFound("NOT_FOUND", "노선 " + routeId + " 이 없습니다.");
    }
    var h = head.get(0);
    List<Schedule.Leg> legs =
        jdbc.query(
            "SELECT mfc_id, leg_minutes FROM route_stop WHERE route_id = ? ORDER BY seq",
            (rs, i) -> new Schedule.Leg(rs.getString(1), rs.getInt(2)),
            routeId);
    String dep = (String) h.get("departure_time");
    // 출차부터 도착까지 분 — 날짜와 상관없는 값이라 오늘로 계산
    LocalDate any = LocalDate.now(clock.withZone(Schedule.SEOUL));
    Schedule.Plan p = Schedule.plan(any, dep, legs, ((Number) h.get("return_minutes")).intValue());
    List<StopView> stops = new ArrayList<>();
    for (Schedule.StopTime s : p.stops()) {
      stops.add(
          new StopView(
              s.seq(),
              s.mfcId(),
              s.legMinutes(),
              (int) Duration.between(p.departure(), s.arrival()).toMinutes()));
    }
    List<VehicleView> vehicles =
        jdbc.query(
            "SELECT temp_class, vehicle_id, driver_id, plate_no, driver_name, max_rt, max_weight_kg"
                + " FROM route_vehicle WHERE route_id = ? ORDER BY temp_class",
            (rs, i) ->
                new VehicleView(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getString(5),
                    rs.getInt(6),
                    rs.getInt(7)),
            routeId);
    return new RouteView(
        routeId,
        cdcId,
        (String) h.get("name"),
        dep,
        ((Number) h.get("return_minutes")).intValue(),
        (Boolean) h.get("active"),
        ((Number) h.get("version")).intValue(),
        stops,
        vehicles);
  }

  /** 그 MFC 가 든 살아 있는 노선. */
  Optional<String> routeOfMfc(String cdcId, String mfcId) {
    return jdbc
        .queryForList(
            "SELECT r.route_id FROM route r JOIN route_stop s ON s.route_id = r.route_id"
                + " WHERE r.cdc_id = ? AND r.active AND s.mfc_id = ? ORDER BY r.route_id LIMIT 1",
            String.class,
            cdcId,
            mfcId)
        .stream()
        .findFirst();
  }

  public RouteView create(String requestId, String source, String cdcId, NewRoute n) {
    Operator op = Operator.forCenter(cdcId, source).requireSupervisor();
    Set<String> seen = new HashSet<>();
    for (Schedule.Leg l : n.stops()) {
      if (!seen.add(l.mfcId())) {
        throw new PmartException(ErrorCode.VALIDATION_FAILED, "같은 MFC 가 두 번 있습니다: " + l.mfcId());
      }
    }
    Schedule.Plan p =
        Schedule.plan(
            LocalDate.now(clock.withZone(Schedule.SEOUL)),
            n.departureTime(),
            n.stops(),
            n.returnMinutes());
    if (!p.feasible()) {
      throw new PmartException(
          "ROUTE_TIME_INFEASIBLE",
          HttpStatus.BAD_REQUEST,
          "마지막 정차 도착이 07:30 을 넘습니다(정본 3.3).",
          false,
          List.of());
    }
    idempotency.execute(
        op.key(requestId, "planning:createRoute"),
        List.of(cdcId, n),
        Ref.class,
        () ->
            tx.execute(
                s -> {
                  jdbc.query(
                      "SELECT pg_advisory_xact_lock(hashtext(?))", rs -> {}, "route:" + cdcId);
                  for (Schedule.Leg l : n.stops()) {
                    Optional<String> other = routeOfMfc(cdcId, l.mfcId());
                    if (other.isPresent()) {
                      throw conflict(
                          "MFC_ALREADY_ROUTED", l.mfcId() + " 은 이미 노선 " + other.get() + " 에 있습니다.");
                    }
                  }
                  try {
                    jdbc.update(
                        "INSERT INTO route (route_id, cdc_id, name, departure_time, return_minutes,"
                            + " active, version, created_at) VALUES (?,?,?,?,?,true,1,?)",
                        n.routeId(),
                        cdcId,
                        n.name(),
                        n.departureTime(),
                        n.returnMinutes(),
                        Timestamp.from(clock.instant()));
                  } catch (DuplicateKeyException e) {
                    throw conflict("ALREADY_EXISTS", "노선 " + n.routeId() + " 은 이미 있습니다.");
                  }
                  int seq = 1;
                  for (Schedule.Leg l : n.stops()) {
                    jdbc.update(
                        "INSERT INTO route_stop (route_id, seq, mfc_id, leg_minutes) VALUES (?,?,?,?)",
                        n.routeId(),
                        seq++,
                        l.mfcId(),
                        l.legMinutes());
                  }
                  return new Ref(n.routeId());
                }));
    return route(cdcId, n.routeId());
  }

  /** 정규차·기본 기사 지정 — fleet 확인은 트랜잭션 밖. */
  public RouteView setRegularVehicle(
      String requestId,
      String source,
      String cdcId,
      String routeId,
      String temp,
      String vehicleId,
      String driverId) {
    Operator op = Operator.forCenter(cdcId, source).requireSupervisor();
    route(cdcId, routeId);
    Clients.VehicleInfo v = clients.vehicle(vehicleId);
    if (!"REGULAR".equals(v.contractType()) || !v.active() || !temp.equals(v.tempClass())) {
      throw conflict(
          "VEHICLE_NOT_ELIGIBLE",
          "차량 "
              + vehicleId
              + " 은 사용 중인 "
              + temp
              + " 정규차가 아닙니다("
              + v.contractType()
              + "·"
              + v.tempClass()
              + ").");
    }
    Clients.DriverInfo d = clients.driver(driverId);
    if (!d.active()) {
      throw conflict("DRIVER_NOT_ELIGIBLE", "기사 " + driverId + " 은 사용 중지입니다.");
    }
    idempotency.execute(
        op.key(requestId, "planning:setRegularVehicle"),
        List.of(cdcId, routeId, temp, vehicleId, driverId),
        Ref.class,
        () -> {
          jdbc.update(
              "INSERT INTO route_vehicle (route_id, temp_class, vehicle_id, driver_id, plate_no,"
                  + " driver_name, max_rt, max_weight_kg, updated_at) VALUES (?,?,?,?,?,?,?,?,?)"
                  + " ON CONFLICT (route_id, temp_class) DO UPDATE SET vehicle_id = EXCLUDED.vehicle_id,"
                  + " driver_id = EXCLUDED.driver_id, plate_no = EXCLUDED.plate_no,"
                  + " driver_name = EXCLUDED.driver_name, max_rt = EXCLUDED.max_rt,"
                  + " max_weight_kg = EXCLUDED.max_weight_kg, updated_at = EXCLUDED.updated_at",
              routeId,
              temp,
              vehicleId,
              driverId,
              v.plateNo(),
              d.displayName(),
              v.maxRt(),
              v.maxWeightKg(),
              Timestamp.from(clock.instant()));
          jdbc.update("UPDATE route SET version = version + 1 WHERE route_id = ?", routeId);
          return new Ref(routeId);
        });
    return route(cdcId, routeId);
  }

  static PmartException conflict(String code, String message) {
    return new PmartException(code, HttpStatus.CONFLICT, message, false, List.of());
  }

  static Instant now(Clock c) {
    return c.instant();
  }
}
