package com.pmart.cdc.location.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 도크 예약(0.8.0 — DC 백오피스 TR-3, 사용자 "계획완료 때 자동"). DOCK 칸 × [시작, 끝) 겹침 금지. 온도대가 같은 ACTIVE DOCK 을 코드
 * 순으로 고르고(원하는 칸 먼저), 같은 센터의 고르기는 권고 잠금으로 줄 세운다. 운행·목적당 살아 있는 예약 하나.
 */
@Service
public class DockService {

  static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

  public record Reserve(
      String centerId,
      String tripId,
      String purpose,
      @Nullable String temperatureZone,
      @Nullable String vehicleId,
      @Nullable String routeId,
      @Nullable String plateNo,
      Instant start,
      Instant end,
      @Nullable String preferredDockCode) {}

  public record View(
      String dockReservationId,
      String centerId,
      String dockCode,
      String tripId,
      String purpose,
      String state,
      Instant start,
      Instant end,
      int version,
      @Nullable String floor,
      @Nullable String temperatureZone,
      @Nullable String vehicleId,
      @Nullable String routeId,
      @Nullable String plateNo,
      @Nullable String releaseReason,
      Instant createdAt) {}

  public record Dock(String dockCode, String floor, String temperatureZone) {}

  public record Board(List<Dock> docks, List<View> items) {}

  public record Ref(String id) {}

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final RequestIdempotency idempotency;
  private final Clock clock;

  public DockService(
      JdbcTemplate jdbc, TransactionTemplate tx, RequestIdempotency idempotency, Clock clock) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.idempotency = idempotency;
    this.clock = clock;
  }

  public View reserve(String requestId, String source, Reserve r) {
    if (!r.start().isBefore(r.end())) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "창 시작이 끝보다 앞이어야 합니다.");
    }
    Ref ref =
        idempotency
            .execute(
                new RequestKey(
                    source, requestId, "cdcLocation:dockReserve", RequestIdempotency.ANONYMOUS),
                r,
                Ref.class,
                () -> tx.execute(s -> new Ref(reserveTx(r))))
            .value();
    return get(ref.id());
  }

  private String reserveTx(Reserve r) {
    jdbc.query(
        "SELECT pg_advisory_xact_lock(hashtext(?))", rs -> {}, "cdc-location:dock:" + r.centerId());
    Instant now = clock.instant();
    Optional<View> live =
        jdbc
            .query(
                SELECT + " WHERE r.trip_id = ? AND r.purpose = ? AND r.state = 'RESERVED'",
                (rs, i) -> view(rs),
                r.tripId(),
                r.purpose())
            .stream()
            .findFirst();
    if (live.isPresent()) {
      View v = live.get();
      if (v.start().equals(r.start()) && v.end().equals(r.end())) {
        return v.dockReservationId();
      }
      release(v.dockReservationId(), "REPLANNED", now);
    }
    StringBuilder sql =
        new StringBuilder(
            "SELECT location_code, floor, temperature_zone FROM location WHERE center_id = ?"
                + " AND location_type = 'DOCK' AND state = 'ACTIVE'");
    List<Object> args = new ArrayList<>(List.of(r.centerId()));
    if (r.temperatureZone() != null) {
      sql.append(" AND temperature_zone = ?");
      args.add(r.temperatureZone());
    }
    sql.append(" ORDER BY location_code");
    List<Dock> docks =
        jdbc.query(
            sql.toString(),
            (rs, i) -> new Dock(rs.getString(1), rs.getString(2), rs.getString(3)),
            args.toArray());
    if (docks.isEmpty()) {
      throw conflict("NO_DOCK", "이 센터에 " + r.temperatureZone() + " 도크 칸이 없습니다.");
    }
    List<Dock> order = new ArrayList<>();
    if (r.preferredDockCode() != null) {
      docks.stream().filter(d -> d.dockCode().equals(r.preferredDockCode())).forEach(order::add);
    }
    docks.stream().filter(d -> !order.contains(d)).forEach(order::add);
    for (Dock d : order) {
      Integer busy =
          jdbc.queryForObject(
              "SELECT count(*) FROM dock_reservation WHERE dock_code = ? AND state = 'RESERVED'"
                  + " AND window_start < ? AND ? < window_end",
              Integer.class,
              d.dockCode(),
              Timestamp.from(r.end()),
              Timestamp.from(r.start()));
      if (busy != null && busy == 0) {
        String id = "DKR-" + UUID.randomUUID();
        jdbc.update(
            "INSERT INTO dock_reservation (dock_reservation_id, center_id, dock_code, trip_id, purpose,"
                + " temperature_zone, vehicle_id, route_id, plate_no, window_start, window_end, state,"
                + " version, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,'RESERVED',1,?)",
            id,
            r.centerId(),
            d.dockCode(),
            r.tripId(),
            r.purpose(),
            d.temperatureZone(),
            r.vehicleId(),
            r.routeId(),
            r.plateNo(),
            Timestamp.from(r.start()),
            Timestamp.from(r.end()),
            Timestamp.from(now));
        return id;
      }
    }
    throw new PmartException(
        ErrorCode.CAPACITY_EXCEEDED.name(),
        HttpStatus.CONFLICT,
        "그 시간대에 빈 " + r.temperatureZone() + " 도크가 없습니다(" + docks.size() + "칸 모두 예약됨).",
        false,
        List.of());
  }

  public View release(String requestId, String source, String reservationId, String reason) {
    get(reservationId);
    idempotency.execute(
        new RequestKey(source, requestId, "cdcLocation:dockRelease", RequestIdempotency.ANONYMOUS),
        List.of(reservationId, reason),
        Ref.class,
        () -> {
          release(reservationId, reason, clock.instant());
          return new Ref(reservationId);
        });
    return get(reservationId);
  }

  private void release(String id, String reason, Instant at) {
    jdbc.update(
        "UPDATE dock_reservation SET state = 'RELEASED', release_reason = ?, released_at = ?,"
            + " version = version + 1 WHERE dock_reservation_id = ? AND state = 'RESERVED'",
        reason,
        Timestamp.from(at),
        id);
  }

  public View get(String reservationId) {
    return jdbc
        .query(SELECT + " WHERE r.dock_reservation_id = ?", (rs, i) -> view(rs), reservationId)
        .stream()
        .findFirst()
        .orElseThrow(
            () -> PmartException.notFound("NOT_FOUND", "도크 예약 " + reservationId + " 이 없습니다."));
  }

  /** 운영자 — 그 서울 날짜에 창이 걸친 예약과 센터의 ACTIVE 도크 칸. */
  public Board board(
      String centerId,
      @Nullable LocalDate date,
      @Nullable String dockCode,
      @Nullable String state) {
    Operators.requireCenter(centerId);
    LocalDate day = date == null ? LocalDate.now(clock.withZone(SEOUL)) : date;
    Instant from = day.atStartOfDay(SEOUL).toInstant();
    Instant to = day.plusDays(1).atStartOfDay(SEOUL).toInstant();
    List<Dock> docks =
        jdbc.query(
            "SELECT location_code, floor, temperature_zone FROM location WHERE center_id = ?"
                + " AND location_type = 'DOCK' AND state = 'ACTIVE' ORDER BY location_code",
            (rs, i) -> new Dock(rs.getString(1), rs.getString(2), rs.getString(3)),
            centerId);
    StringBuilder sql =
        new StringBuilder(
            SELECT + " WHERE r.center_id = ? AND r.window_start < ? AND ? < r.window_end");
    List<Object> args =
        new ArrayList<>(List.of(centerId, Timestamp.from(to), Timestamp.from(from)));
    if (dockCode != null) {
      sql.append(" AND r.dock_code = ?");
      args.add(dockCode);
    }
    if (state != null) {
      sql.append(" AND r.state = ?");
      args.add(state);
    }
    sql.append(" ORDER BY r.dock_code, r.window_start");
    return new Board(docks, jdbc.query(sql.toString(), (rs, i) -> view(rs), args.toArray()));
  }

  private static final String SELECT =
      "SELECT r.dock_reservation_id, r.center_id, r.dock_code, r.trip_id, r.purpose, r.state,"
          + " r.window_start, r.window_end, r.version, l.floor, r.temperature_zone, r.vehicle_id,"
          + " r.route_id, r.plate_no, r.release_reason, r.created_at FROM dock_reservation r"
          + " LEFT JOIN location l ON l.location_code = r.dock_code";

  private static View view(ResultSet rs) throws SQLException {
    return new View(
        rs.getString(1),
        rs.getString(2),
        rs.getString(3),
        rs.getString(4),
        rs.getString(5),
        rs.getString(6),
        rs.getTimestamp(7).toInstant(),
        rs.getTimestamp(8).toInstant(),
        rs.getInt(9),
        rs.getString(10),
        rs.getString(11),
        rs.getString(12),
        rs.getString(13),
        rs.getString(14),
        rs.getString(15),
        rs.getTimestamp(16).toInstant());
  }

  static PmartException conflict(String code, String message) {
    return new PmartException(code, HttpStatus.CONFLICT, message, false, List.of());
  }
}
