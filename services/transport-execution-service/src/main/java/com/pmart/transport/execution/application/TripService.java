package com.pmart.transport.execution.application;

import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.transport.execution.domain.TripRules;
import com.pmart.transport.execution.infrastructure.Clients;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 운행 실행(TMS 정본 9·10·11장 — 설계 §5). 운행 = planning 계획완료 지시서 사본(tripId = orderId). 명령은 상태로 멱등(같은 명령을 다시
 * 보내면 그대로). 다른 서비스 호출(cdc-outbound 출발 검증·planning 상태 사본·fleet 점유 해제)은 트랜잭션 밖, 실패·불명은 주기 재시도 — 출차·인계
 * 사실은 되돌리지 않는다(정본 17장).
 */
@Service
public class TripService {

  private static final Logger log = LoggerFactory.getLogger(TripService.class);
  static final Duration CLOCK_SKEW = Duration.ofMinutes(2);
  static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

  // ---------- 입력 ----------

  public record EventIn(
      @Nullable Instant at, @Nullable String location, @Nullable BigDecimal temperatureC) {
    public static EventIn none() {
      return new EventIn(null, null, null);
    }
  }

  public record LoadTrf(
      String trfId, List<String> lpns, @Nullable Integer rtCount, List<String> sealNos) {}

  public record HandTrf(String trfId, List<String> received, List<String> damaged) {}

  public record StopInput(
      int seq,
      String mfcId,
      Instant plannedArrivalAt,
      @Nullable Instant plannedDepartureAt,
      int legMinutes,
      List<String> trfIds) {}

  public record TripInput(
      String tripId,
      int orderVersion,
      String cdcId,
      LocalDate shipDate,
      String routeId,
      String tempClass,
      String vehicleId,
      @Nullable String plateNo,
      String driverId,
      @Nullable String driverName,
      @Nullable String reservationId,
      Instant plannedLoadingStartAt,
      Instant plannedDepartureAt,
      Instant plannedReturnAt,
      int returnMinutes,
      List<StopInput> stops,
      @Nullable String dockCode,
      @Nullable String dockReservationId) {}

  /** 호출자 — 직원(센터 권한) 또는 시뮬레이터(내부 경로, 검사 없음). */
  public record Caller(String actorId, String source, @Nullable PmartPrincipal principal) {

    public static Caller staff(String source) {
      PmartPrincipal p =
          PmartPrincipal.current()
              .filter(x -> x.type() == PmartPrincipal.PrincipalType.OPERATOR)
              .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "운영자만 할 수 있습니다."));
      return new Caller(p.subject(), source, p);
    }

    public static Caller internal(String source) {
      return new Caller(source, source, null);
    }

    boolean simulated() {
      return principal == null;
    }

    boolean canAccess(String centerId) {
      return principal == null || principal.canAccessCenter(centerId);
    }
  }

  // ---------- 보기 ----------

  public record Handover(
      String handoverId,
      String result,
      int shippedBoxes,
      int receivedBoxes,
      List<String> missing,
      List<String> extra,
      List<String> damaged,
      @Nullable String receiverId,
      @Nullable String receiverName) {}

  public record StopView(
      int seq,
      String mfcId,
      String state,
      List<String> trfIds,
      Instant plannedArrivalAt,
      @Nullable Instant estimatedArrivalAt,
      @Nullable Instant arrivedAt,
      @Nullable Instant handedOverAt,
      @Nullable Instant departedAt,
      boolean delayed,
      @Nullable Handover handover) {}

  public record TransferView(
      String trfId, int stopSeq, boolean loaded, List<String> lpns, @Nullable Integer rtCount) {}

  public record EventView(
      Instant at,
      String type,
      @Nullable Integer stopSeq,
      @Nullable String location,
      @Nullable BigDecimal temperatureC,
      @Nullable String actorId,
      @Nullable String source) {}

  public record IncidentView(
      String incidentId,
      String type,
      @Nullable Integer stopSeq,
      String state,
      String description,
      @Nullable String resolution,
      Instant raisedAt,
      @Nullable Instant resolvedAt) {}

  public record TripView(
      String tripId,
      String cdcId,
      LocalDate shipDate,
      String routeId,
      String tempClass,
      String vehicleId,
      @Nullable String plateNo,
      String driverId,
      @Nullable String driverName,
      String state,
      boolean delayed,
      Instant plannedLoadingStartAt,
      Instant plannedDepartureAt,
      Instant plannedReturnAt,
      @Nullable Instant checkedInAt,
      @Nullable Instant loadedAt,
      @Nullable Instant departedAt,
      @Nullable Instant endedAt,
      @Nullable String departureId,
      @Nullable String departureSource,
      @Nullable String outboundLinkage,
      @Nullable String outboundResult,
      int boxCount,
      int rtCount,
      List<StopView> stops,
      List<TransferView> transfers,
      List<EventView> events,
      List<IncidentView> incidents,
      int version,
      @Nullable String dockCode) {}

  public record Page(List<TripView> items, int page, int size, boolean hasNext) {}

  public record DepartureFact(
      String departureId,
      String tripId,
      String vehicleId,
      String driverId,
      String cdcId,
      Instant departedAt,
      String source,
      List<Map<String, Object>> compositions) {}

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final Clients clients;
  private final ObjectMapper mapper;
  private final Clock clock;

  public TripService(
      JdbcTemplate jdbc,
      TransactionTemplate tx,
      Clients clients,
      ObjectMapper mapper,
      Clock clock) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.clients = clients;
    this.mapper = mapper;
    this.clock = clock;
  }

  // ======================= 만들기·취소(planning) =======================

  public TripView create(TripInput in) {
    String hash = sha(mapper.writeValueAsString(in));
    tx.executeWithoutResult(
        s -> {
          lock(in.tripId());
          var prior =
              jdbc.queryForList(
                  "SELECT state, order_version, input_hash FROM trip WHERE trip_id = ?",
                  in.tripId());
          if (!prior.isEmpty()) {
            var p = prior.get(0);
            if (hash.equals(p.get("input_hash"))) {
              return;
            }
            if (!"SCHEDULED".equals(p.get("state"))
                || ((Number) p.get("order_version")).intValue() >= in.orderVersion()) {
              throw conflict("TRIP_CONFLICT", "같은 운행에 다른 내용입니다(계획완료 상태가 아니거나 지시서 버전이 낮음).");
            }
            jdbc.update("DELETE FROM trip_stop WHERE trip_id = ?", in.tripId());
            jdbc.update("DELETE FROM trip_transfer WHERE trip_id = ?", in.tripId());
            jdbc.update("DELETE FROM trip WHERE trip_id = ?", in.tripId());
          }
          Instant now = clock.instant();
          jdbc.update(
              "INSERT INTO trip (trip_id, order_version, cdc_id, ship_date, route_id, temp_class,"
                  + " vehicle_id, plate_no, driver_id, driver_name, reservation_id, dock_code, dock_reservation_id,"
                  + " planned_loading_start_at, planned_departure_at, planned_return_at, return_minutes,"
                  + " state, input_hash, version, created_at, updated_at)"
                  + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'SCHEDULED',?,1,?,?)",
              in.tripId(),
              in.orderVersion(),
              in.cdcId(),
              in.shipDate(),
              in.routeId(),
              in.tempClass(),
              in.vehicleId(),
              in.plateNo(),
              in.driverId(),
              in.driverName(),
              in.reservationId(),
              in.dockCode(),
              in.dockReservationId(),
              ts(in.plannedLoadingStartAt()),
              ts(in.plannedDepartureAt()),
              ts(in.plannedReturnAt()),
              in.returnMinutes(),
              hash,
              ts(now),
              ts(now));
          for (StopInput st : in.stops()) {
            jdbc.update(
                "INSERT INTO trip_stop (trip_id, seq, mfc_id, leg_minutes, planned_arrival_at,"
                    + " planned_departure_at, state) VALUES (?,?,?,?,?,?,'PLANNED')",
                in.tripId(),
                st.seq(),
                st.mfcId(),
                st.legMinutes(),
                ts(st.plannedArrivalAt()),
                st.plannedDepartureAt() == null ? null : ts(st.plannedDepartureAt()));
            for (String trf : st.trfIds()) {
              jdbc.update(
                  "INSERT INTO trip_transfer (trip_id, trf_id, stop_seq) VALUES (?,?,?)",
                  in.tripId(),
                  trf,
                  st.seq());
            }
          }
          event(
              in.tripId(), now, "SCHEDULED", null, EventIn.none(), Clients.SOURCE, Clients.SOURCE);
        });
    return view(in.tripId());
  }

  public TripView cancel(String tripId, String reason, String source) {
    tx.executeWithoutResult(
        s -> {
          String state = lockState(tripId);
          if ("CANCELLED".equals(state)) {
            return;
          }
          if ("DEPARTED".equals(state) || "COMPLETED".equals(state)) {
            throw conflict("ALREADY_DEPARTED", "이미 출차한 운행은 취소하지 않습니다(정본 11장 — 원래 목적지로 인계).");
          }
          Instant now = clock.instant();
          setState(tripId, "CANCELLED", null, now);
          event(
              tripId,
              now,
              "CANCELLED",
              null,
              new EventIn(null, trim(reason), null),
              source,
              source);
        });
    notifyPlanning(tripId);
    return view(tripId);
  }

  // ======================= DC 쪽 명령 =======================

  public TripView checkIn(Caller c, String tripId, EventIn e) {
    tx.executeWithoutResult(
        s -> {
          String state = lockState(tripId);
          requireCdc(c, tripId);
          if (!"SCHEDULED".equals(state)) {
            if ("CANCELLED".equals(state)) {
              throw PmartException.invalidState("취소된 운행입니다.");
            }
            return; // 이미 입차
          }
          Instant at = when(e);
          jdbc.update("UPDATE trip SET checked_in_at = ? WHERE trip_id = ?", ts(at), tripId);
          setState(tripId, "CHECKED_IN", null, at);
          event(tripId, at, "CHECKED_IN", null, e, c.actorId(), c.source());
        });
    notifyPlanning(tripId);
    return view(tripId);
  }

  public TripView completeLoading(Caller c, String tripId, EventIn e, List<LoadTrf> load) {
    tx.executeWithoutResult(
        s -> {
          String state = lockState(tripId);
          requireCdc(c, tripId);
          if (!"CHECKED_IN".equals(state) && !"LOADED".equals(state)) {
            throw PmartException.invalidState("입차한 뒤 출차 전에만 상차를 확정합니다(지금 " + state + ").");
          }
          List<String> planned =
              jdbc.queryForList(
                  "SELECT trf_id FROM trip_transfer WHERE trip_id = ? ORDER BY trf_id",
                  String.class,
                  tripId);
          Map<String, LoadTrf> by = new LinkedHashMap<>();
          for (LoadTrf l : load) {
            if (!planned.contains(l.trfId())) {
              throw new PmartException(
                  "UNKNOWN_TRF",
                  HttpStatus.BAD_REQUEST,
                  "이 운행에 없는 이관요청서입니다: " + l.trfId(),
                  false,
                  List.of());
            }
            by.put(l.trfId(), l);
          }
          boolean any = by.values().stream().anyMatch(l -> !l.lpns().isEmpty());
          if (!any) {
            throw conflict("NOTHING_LOADED", "실은 박스가 없습니다.");
          }
          Instant at = when(e);
          jdbc.update(
              "DELETE FROM trip_incident WHERE trip_id = ? AND type = 'NOT_LOADED' AND state = 'OPEN'",
              tripId);
          for (String trf : planned) {
            LoadTrf l = by.get(trf);
            boolean loaded = l != null && !l.lpns().isEmpty();
            jdbc.update(
                "UPDATE trip_transfer SET loaded = ?, lpns = ?, rt_count = ?, seal_nos = ?"
                    + " WHERE trip_id = ? AND trf_id = ?",
                loaded,
                loaded ? join(l.lpns()) : "",
                loaded ? l.rtCount() : null,
                loaded ? join(l.sealNos()) : "",
                tripId,
                trf);
            if (!loaded) {
              incident(
                  tripId,
                  "NOT_LOADED",
                  null,
                  "미상차 — " + trf + " (배차 담당이 다른 차량·미배차 처리, 정본 11장)",
                  at);
            }
          }
          jdbc.update("UPDATE trip SET loaded_at = ? WHERE trip_id = ?", ts(at), tripId);
          setState(tripId, "LOADED", null, at);
          event(tripId, at, "LOADED", null, e, c.actorId(), c.source());
        });
    notifyPlanning(tripId);
    return view(tripId);
  }

  public TripView depart(
      Caller c, String tripId, EventIn e, @Nullable String source, @Nullable String evidenceRef) {
    tx.executeWithoutResult(
        s -> {
          String state = lockState(tripId);
          requireCdc(c, tripId);
          if ("DEPARTED".equals(state) || "COMPLETED".equals(state)) {
            return; // 같은 출차 다시
          }
          if (!"LOADED".equals(state)) {
            throw PmartException.invalidState(
                "상차완료 뒤에만 출차합니다(지금 " + state + " — 상차완료로 자동 출차하지 않음).");
          }
          Instant at = when(e);
          String src = source != null ? source : c.simulated() ? "SIMULATED" : "REAL";
          jdbc.update(
              "UPDATE trip SET departed_at = ?, departure_id = ?, departure_source = ?, evidence_ref = ?,"
                  + " outbound_linkage = 'PENDING' WHERE trip_id = ?",
              ts(at),
              "DEP-" + UUID.randomUUID(),
              src,
              evidenceRef,
              tripId);
          setState(tripId, "DEPARTED", null, at);
          event(tripId, at, "DEPARTED", null, e, c.actorId(), c.source());
        });
    sendOutbound(tripId);
    notifyPlanning(tripId);
    return view(tripId);
  }

  public TripView end(Caller c, String tripId, EventIn e) {
    tx.executeWithoutResult(
        s -> {
          String state = lockState(tripId);
          requireCdc(c, tripId);
          if ("COMPLETED".equals(state)) {
            return;
          }
          if (!"DEPARTED".equals(state)) {
            throw PmartException.invalidState("운행 중에만 복귀합니다(지금 " + state + ").");
          }
          Integer open =
              jdbc.queryForObject(
                  "SELECT count(*) FROM trip_stop WHERE trip_id = ? AND state NOT IN ('HANDED_OVER', 'DEPARTED')",
                  Integer.class,
                  tripId);
          if (open != null && open > 0) {
            throw conflict("STOPS_OPEN", "인계하지 않은 정차가 " + open + "곳 있습니다.");
          }
          Instant at = when(e);
          jdbc.update("UPDATE trip SET ended_at = ? WHERE trip_id = ?", ts(at), tripId);
          setState(tripId, "COMPLETED", null, at);
          event(tripId, at, "PHYSICAL_END", null, e, c.actorId(), c.source());
        });
    releaseReservation(tripId);
    notifyPlanning(tripId);
    return view(tripId);
  }

  // ======================= 정차 =======================

  public TripView arrive(Caller c, String tripId, int seq, EventIn e) {
    tx.executeWithoutResult(
        s -> {
          String state = lockState(tripId);
          String mfc = stopMfc(tripId, seq);
          requireCdcOrMfc(c, tripId, mfc);
          String st = stopState(tripId, seq);
          if (!"PLANNED".equals(st)) {
            return;
          }
          if (!"DEPARTED".equals(state)) {
            throw PmartException.invalidState("출차한 운행만 도착합니다(지금 " + state + ").");
          }
          Integer before =
              jdbc.queryForObject(
                  "SELECT count(*) FROM trip_stop WHERE trip_id = ? AND seq < ? AND state <> 'DEPARTED'",
                  Integer.class,
                  tripId,
                  seq);
          if (before != null && before > 0) {
            throw conflict("PREVIOUS_STOP_OPEN", "앞 정차를 아직 출발하지 않았습니다.");
          }
          Instant at = when(e);
          jdbc.update(
              "UPDATE trip_stop SET state = 'ARRIVED', arrived_at = ? WHERE trip_id = ? AND seq = ?",
              ts(at),
              tripId,
              seq);
          bump(tripId, at);
          event(tripId, at, "STOP_ARRIVED", seq, e, c.actorId(), c.source());
        });
    notifyPlanning(tripId);
    return view(tripId);
  }

  public TripView handover(
      Caller c,
      String tripId,
      int seq,
      EventIn e,
      @Nullable String receiverName,
      List<HandTrf> in) {
    String hash =
        sha(mapper.writeValueAsString(List.of(seq, in, receiverName == null ? "" : receiverName)));
    tx.executeWithoutResult(
        s -> {
          lockState(tripId);
          String mfc = stopMfc(tripId, seq);
          if (!c.canAccess(mfc)) {
            throw new PmartException(ErrorCode.FORBIDDEN, "그 MFC 운영자만 인계를 기록합니다.");
          }
          var prior =
              jdbc.queryForList(
                  "SELECT input_hash FROM handover WHERE trip_id = ? AND stop_seq = ?",
                  tripId,
                  seq);
          if (!prior.isEmpty()) {
            if (hash.equals(prior.get(0).get("input_hash"))) {
              return;
            }
            throw conflict("HANDOVER_RECORDED", "이미 인계 증빙이 있습니다(정정은 뒤로).");
          }
          if (!"ARRIVED".equals(stopState(tripId, seq))) {
            throw PmartException.invalidState("도착한 정차에서만 인계합니다.");
          }
          List<Map<String, Object>> mine =
              jdbc.queryForList(
                  "SELECT trf_id, loaded, lpns FROM trip_transfer WHERE trip_id = ? AND stop_seq = ?",
                  tripId,
                  seq);
          List<String> trfs = mine.stream().map(m -> (String) m.get("trf_id")).toList();
          List<String> shipped = new ArrayList<>();
          for (Map<String, Object> m : mine) {
            if (Boolean.TRUE.equals(m.get("loaded"))) {
              shipped.addAll(split((String) m.get("lpns")));
            }
          }
          List<String> received = new ArrayList<>();
          List<String> damaged = new ArrayList<>();
          for (HandTrf h : in) {
            if (!trfs.contains(h.trfId())) {
              throw new PmartException(
                  "UNKNOWN_TRF",
                  HttpStatus.BAD_REQUEST,
                  "이 정차에 없는 이관요청서입니다: " + h.trfId(),
                  false,
                  List.of());
            }
            received.addAll(h.received());
            damaged.addAll(h.damaged());
          }
          TripRules.Comparison cmp = TripRules.compare(shipped, received);
          String result = damaged.isEmpty() ? cmp.result() : "MISMATCHED";
          Instant at = when(e);
          LocalDate day = at.atZone(TripRules.SEOUL).toLocalDate();
          String id = "HO-" + day.format(YMD) + "-" + UUID.randomUUID().toString().substring(0, 8);
          jdbc.update(
              "INSERT INTO handover (handover_id, trip_id, stop_seq, result, shipped_boxes, received_boxes,"
                  + " missing_lpns, extra_lpns, damaged_lpns, receiver_id, receiver_name, input_hash, at)"
                  + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
              id,
              tripId,
              seq,
              result,
              new LinkedHashSet<>(shipped).size(),
              new LinkedHashSet<>(received).size(),
              join(cmp.missing()),
              join(cmp.extra()),
              join(damaged),
              c.actorId(),
              receiverName,
              hash,
              ts(at));
          jdbc.update(
              "UPDATE trip_stop SET state = 'HANDED_OVER', handed_over_at = ? WHERE trip_id = ? AND seq = ?",
              ts(at),
              tripId,
              seq);
          if ("MISMATCHED".equals(result)) {
            incident(
                tripId,
                "QTY_MISMATCH",
                seq,
                "인계 차이 — 부족 "
                    + cmp.missing().size()
                    + "·초과 "
                    + cmp.extra().size()
                    + "·파손 "
                    + damaged.size()
                    + " (자동 손실 처리 없음 — WMS·MFC 가 판단)",
                at);
          }
          bump(tripId, at);
          event(tripId, at, "HANDED_OVER", seq, e, c.actorId(), c.source());
        });
    notifyPlanning(tripId);
    return view(tripId);
  }

  public TripView departStop(Caller c, String tripId, int seq, EventIn e) {
    tx.executeWithoutResult(
        s -> {
          lockState(tripId);
          String mfc = stopMfc(tripId, seq);
          requireCdcOrMfc(c, tripId, mfc);
          String st = stopState(tripId, seq);
          if ("DEPARTED".equals(st)) {
            return;
          }
          if (!"HANDED_OVER".equals(st)) {
            throw conflict("HANDOVER_REQUIRED", "인계 증빙 없이 정차를 떠날 수 없습니다(정본 10.3).");
          }
          Instant at = when(e);
          jdbc.update(
              "UPDATE trip_stop SET state = 'DEPARTED', departed_at = ? WHERE trip_id = ? AND seq = ?",
              ts(at),
              tripId,
              seq);
          bump(tripId, at);
          event(tripId, at, "STOP_DEPARTED", seq, e, c.actorId(), c.source());
        });
    return view(tripId);
  }

  // ======================= 예외 =======================

  public TripView raiseIncident(
      Caller c, String tripId, String type, @Nullable Integer stopSeq, String description) {
    tx.executeWithoutResult(
        s -> {
          lockState(tripId);
          requireAny(c, tripId);
          incident(tripId, type, stopSeq, trim(description), clock.instant());
        });
    return view(tripId);
  }

  public TripView resolveIncident(Caller c, String tripId, String incidentId, String resolution) {
    tx.executeWithoutResult(
        s -> {
          lockState(tripId);
          requireAny(c, tripId);
          int n =
              jdbc.update(
                  "UPDATE trip_incident SET state = 'RESOLVED', resolution = ?, resolved_at = ?"
                      + " WHERE incident_id = ? AND trip_id = ? AND state = 'OPEN'",
                  trim(resolution),
                  ts(clock.instant()),
                  incidentId,
                  tripId);
          if (n == 0) {
            Integer exists =
                jdbc.queryForObject(
                    "SELECT count(*) FROM trip_incident WHERE incident_id = ? AND trip_id = ?",
                    Integer.class,
                    incidentId,
                    tripId);
            if (exists == null || exists == 0) {
              throw PmartException.notFound("NOT_FOUND", "예외 " + incidentId + " 가 없습니다.");
            }
          }
        });
    return view(tripId);
  }

  // ======================= 조회 =======================

  public TripView get(Caller c, String tripId) {
    requireAny(c, tripId);
    return view(tripId);
  }

  public Page centerTrips(
      Caller c,
      String cdcId,
      @Nullable LocalDate shipDate,
      @Nullable String state,
      @Nullable Boolean delayed,
      int page,
      int size) {
    if (!c.canAccess(cdcId)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "이 센터에 대한 권한이 없습니다.");
    }
    StringBuilder sql = new StringBuilder("SELECT trip_id FROM trip WHERE cdc_id = ?");
    List<Object> args = new ArrayList<>(List.of(cdcId));
    if (shipDate != null) {
      sql.append(" AND ship_date = ?");
      args.add(shipDate);
    }
    if (state != null) {
      sql.append(" AND state = ?");
      args.add(state);
    }
    sql.append(" ORDER BY planned_departure_at, route_id, temp_class");
    return page(
        jdbc.queryForList(sql.toString(), String.class, args.toArray()), delayed, page, size);
  }

  public Page mfcTrips(
      Caller c, String mfcId, @Nullable LocalDate arrivalDate, int page, int size) {
    if (!c.canAccess(mfcId)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "이 센터에 대한 권한이 없습니다.");
    }
    StringBuilder sql =
        new StringBuilder(
            "SELECT DISTINCT t.trip_id, t.planned_departure_at FROM trip t JOIN trip_stop s ON s.trip_id = t.trip_id"
                + " WHERE s.mfc_id = ? AND t.state <> 'CANCELLED'");
    List<Object> args = new ArrayList<>(List.of(mfcId));
    if (arrivalDate != null) {
      sql.append(" AND s.planned_arrival_at >= ? AND s.planned_arrival_at < ?");
      args.add(ts(arrivalDate.atStartOfDay(TripRules.SEOUL).toInstant()));
      args.add(ts(arrivalDate.plusDays(1).atStartOfDay(TripRules.SEOUL).toInstant()));
    }
    sql.append(" ORDER BY t.planned_departure_at");
    List<String> ids = jdbc.query(sql.toString(), (rs, i) -> rs.getString(1), args.toArray());
    return page(ids, null, page, size);
  }

  private Page page(List<String> ids, @Nullable Boolean delayed, int page, int size) {
    int n = Math.min(200, Math.max(1, size));
    int p = Math.max(0, page);
    List<TripView> all = new ArrayList<>();
    for (String id : ids) {
      TripView v = view(id);
      if (delayed == null || v.delayed() == delayed) {
        all.add(v);
      }
    }
    int from = Math.min(all.size(), p * n);
    int to = Math.min(all.size(), from + n);
    return new Page(all.subList(from, to), p, n, to < all.size());
  }

  public DepartureFact departure(String departureId) {
    var rows =
        jdbc.queryForList(
            "SELECT trip_id, vehicle_id, driver_id, cdc_id, departed_at, departure_source FROM trip WHERE departure_id = ?",
            departureId);
    if (rows.isEmpty()) {
      throw PmartException.notFound("NOT_FOUND", "출발 사실 " + departureId + " 가 없습니다.");
    }
    var r = rows.get(0);
    String tripId = (String) r.get("trip_id");
    return new DepartureFact(
        departureId,
        tripId,
        (String) r.get("vehicle_id"),
        (String) r.get("driver_id"),
        (String) r.get("cdc_id"),
        ((Timestamp) r.get("departed_at")).toInstant(),
        (String) r.get("departure_source"),
        compositions(tripId));
  }

  /** 출발 구성 — 실린 이관요청서마다 MFC·박스·봉인. */
  List<Map<String, Object>> compositions(String tripId) {
    return jdbc.query(
        "SELECT t.trf_id, s.mfc_id, t.lpns, t.seal_nos FROM trip_transfer t JOIN trip_stop s"
            + " ON s.trip_id = t.trip_id AND s.seq = t.stop_seq WHERE t.trip_id = ? AND t.loaded ORDER BY t.trf_id",
        (rs, i) -> {
          Map<String, Object> m = new LinkedHashMap<>();
          m.put("trfId", rs.getString(1));
          m.put("mfcId", rs.getString(2));
          m.put("lpns", split(rs.getString(3)));
          List<String> seals = split(rs.getString(4));
          if (!seals.isEmpty()) {
            m.put("sealNos", seals);
          }
          return m;
        },
        tripId);
  }

  TripView view(String tripId) {
    var rows = jdbc.queryForList("SELECT * FROM trip WHERE trip_id = ?", tripId);
    if (rows.isEmpty()) {
      throw PmartException.notFound("NOT_FOUND", "운행 " + tripId + " 이 없습니다.");
    }
    Map<String, Object> t = rows.get(0);
    LocalDate shipDate = ((java.sql.Date) t.get("ship_date")).toLocalDate();
    List<TransferView> transfers =
        jdbc.query(
            "SELECT trf_id, stop_seq, loaded, lpns, rt_count FROM trip_transfer WHERE trip_id = ? ORDER BY stop_seq, trf_id",
            (rs, i) ->
                new TransferView(
                    rs.getString(1),
                    rs.getInt(2),
                    rs.getBoolean(3),
                    split(rs.getString(4)),
                    (Integer) rs.getObject(5)),
            tripId);
    Map<Integer, Handover> hos = new LinkedHashMap<>();
    jdbc.query(
        "SELECT stop_seq, handover_id, result, shipped_boxes, received_boxes, missing_lpns, extra_lpns,"
            + " damaged_lpns, receiver_id, receiver_name FROM handover WHERE trip_id = ?",
        (org.springframework.jdbc.core.RowCallbackHandler)
            rs -> {
              hos.put(
                  rs.getInt(1),
                  new Handover(
                      rs.getString(2),
                      rs.getString(3),
                      rs.getInt(4),
                      rs.getInt(5),
                      split(rs.getString(6)),
                      split(rs.getString(7)),
                      split(rs.getString(8)),
                      rs.getString(9),
                      rs.getString(10)));
            },
        tripId);
    List<Map<String, Object>> stopRows =
        jdbc.queryForList("SELECT * FROM trip_stop WHERE trip_id = ? ORDER BY seq", tripId);
    List<TripRules.StopIn> ins = new ArrayList<>();
    for (Map<String, Object> s : stopRows) {
      ins.add(
          new TripRules.StopIn(
              ((Number) s.get("seq")).intValue(),
              ((Number) s.get("leg_minutes")).intValue(),
              tsOf(s.get("planned_arrival_at")),
              tsOrNull(s.get("arrived_at")),
              tsOrNull(s.get("departed_at"))));
    }
    String state = (String) t.get("state");
    boolean ended = "COMPLETED".equals(state) || "CANCELLED".equals(state);
    List<TripRules.StopEta> etas =
        TripRules.eta(
            ins,
            tsOf(t.get("planned_departure_at")),
            tsOrNull(t.get("departed_at")),
            clock.instant(),
            TripRules.deadline(shipDate),
            ended);
    List<StopView> stops = new ArrayList<>();
    boolean anyDelay = false;
    for (int i = 0; i < stopRows.size(); i++) {
      Map<String, Object> s = stopRows.get(i);
      int seq = ((Number) s.get("seq")).intValue();
      boolean delayed = !"CANCELLED".equals(state) && etas.get(i).delayed();
      anyDelay |= delayed;
      stops.add(
          new StopView(
              seq,
              (String) s.get("mfc_id"),
              (String) s.get("state"),
              transfers.stream().filter(x -> x.stopSeq() == seq).map(TransferView::trfId).toList(),
              tsOf(s.get("planned_arrival_at")),
              "CANCELLED".equals(state) ? null : etas.get(i).estimatedArrival(),
              tsOrNull(s.get("arrived_at")),
              tsOrNull(s.get("handed_over_at")),
              tsOrNull(s.get("departed_at")),
              delayed,
              hos.get(seq)));
    }
    List<EventView> events =
        jdbc.query(
            "SELECT at, type, stop_seq, location, temperature_c, actor_id, source FROM trip_event WHERE trip_id = ? ORDER BY seq",
            (rs, i) ->
                new EventView(
                    rs.getTimestamp(1).toInstant(),
                    rs.getString(2),
                    (Integer) rs.getObject(3),
                    rs.getString(4),
                    rs.getBigDecimal(5),
                    rs.getString(6),
                    rs.getString(7)),
            tripId);
    List<IncidentView> incidents =
        jdbc.query(
            "SELECT incident_id, type, stop_seq, state, description, resolution, raised_at, resolved_at"
                + " FROM trip_incident WHERE trip_id = ? ORDER BY raised_at, incident_id",
            (rs, i) ->
                new IncidentView(
                    rs.getString(1),
                    rs.getString(2),
                    (Integer) rs.getObject(3),
                    rs.getString(4),
                    rs.getString(5),
                    rs.getString(6),
                    rs.getTimestamp(7).toInstant(),
                    rs.getTimestamp(8) == null ? null : rs.getTimestamp(8).toInstant()),
            tripId);
    int boxes =
        transfers.stream().filter(TransferView::loaded).mapToInt(x -> x.lpns().size()).sum();
    int rts =
        transfers.stream()
            .filter(TransferView::loaded)
            .mapToInt(x -> x.rtCount() == null ? 0 : x.rtCount())
            .sum();
    return new TripView(
        tripId,
        (String) t.get("cdc_id"),
        shipDate,
        (String) t.get("route_id"),
        (String) t.get("temp_class"),
        (String) t.get("vehicle_id"),
        (String) t.get("plate_no"),
        (String) t.get("driver_id"),
        (String) t.get("driver_name"),
        state,
        anyDelay,
        tsOf(t.get("planned_loading_start_at")),
        tsOf(t.get("planned_departure_at")),
        tsOf(t.get("planned_return_at")),
        tsOrNull(t.get("checked_in_at")),
        tsOrNull(t.get("loaded_at")),
        tsOrNull(t.get("departed_at")),
        tsOrNull(t.get("ended_at")),
        (String) t.get("departure_id"),
        (String) t.get("departure_source"),
        (String) t.get("outbound_linkage"),
        (String) t.get("outbound_result"),
        boxes,
        rts,
        stops,
        transfers,
        events,
        incidents,
        ((Number) t.get("version")).intValue(),
        (String) t.get("dock_code"));
  }

  // ======================= 다른 서비스 알림(트랜잭션 밖) =======================

  /** cdc-outbound 출발 검증 — 키 = departureId. 불명·미전달은 UNKNOWN_RESULT(재시도), 업무 거절은 FAILED(사유). */
  void sendOutbound(String tripId) {
    var rows =
        jdbc.queryForList(
            "SELECT departure_id, vehicle_id, departed_at, departure_source, evidence_ref, outbound_linkage FROM trip WHERE trip_id = ?",
            tripId);
    if (rows.isEmpty() || rows.get(0).get("departure_id") == null) {
      return;
    }
    var r = rows.get(0);
    String linkage = (String) r.get("outbound_linkage");
    if ("ACKNOWLEDGED".equals(linkage) || "FAILED".equals(linkage)) {
      return;
    }
    String depId = (String) r.get("departure_id");
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("departureId", depId);
    body.put("tripId", tripId);
    body.put("vehicleId", r.get("vehicle_id"));
    body.put("departedAt", ((Timestamp) r.get("departed_at")).toInstant().toString());
    body.put("source", r.get("departure_source"));
    if (r.get("evidence_ref") != null) {
      body.put("evidenceRef", r.get("evidence_ref"));
    }
    body.put("trfCompositions", compositions(tripId));
    String next;
    String result;
    try {
      JsonNode res = clients.validateDeparture(depId, body);
      next = "ACKNOWLEDGED";
      result = res.path("overallResult").asString("ACKNOWLEDGED");
    } catch (PmartException e) {
      next = Clients.unknown(e) ? "UNKNOWN_RESULT" : "FAILED";
      result = trim(e.code() + ": " + e.getMessage());
      log.warn("출발 검증 {} {}: {}", tripId, next, result);
    }
    jdbc.update(
        "UPDATE trip SET outbound_linkage = ?, outbound_result = ? WHERE trip_id = ?",
        next,
        result,
        tripId);
  }

  /** planning 운행 상태 사본 — 실패하면 다음 주기에(planning_synced_version 으로 판단). */
  void notifyPlanning(String tripId) {
    var rows =
        jdbc.queryForList(
            "SELECT state, version, planning_synced_version, updated_at FROM trip WHERE trip_id = ?",
            tripId);
    if (rows.isEmpty()) {
      return;
    }
    var r = rows.get(0);
    int version = ((Number) r.get("version")).intValue();
    if (((Number) r.get("planning_synced_version")).intValue() >= version) {
      return;
    }
    List<String> handed =
        jdbc.queryForList(
            "SELECT t.trf_id FROM trip_transfer t JOIN handover h ON h.trip_id = t.trip_id AND h.stop_seq = t.stop_seq"
                + " WHERE t.trip_id = ? AND t.loaded ORDER BY t.trf_id",
            String.class,
            tripId);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("tripState", r.get("state"));
    body.put("tripVersion", version);
    body.put("at", ((Timestamp) r.get("updated_at")).toInstant().toString());
    body.put("handedOverTrfIds", handed);
    try {
      clients.tripUpdate(tripId, version, body);
      jdbc.update(
          "UPDATE trip SET planning_synced_version = ? WHERE trip_id = ? AND planning_synced_version < ?",
          version,
          tripId,
          version);
    } catch (PmartException e) {
      log.warn("planning 알림 보류 {} v{}: {}", tripId, version, e.getMessage());
    }
  }

  void releaseReservation(String tripId) {
    var rows =
        jdbc.queryForList(
            "SELECT reservation_id FROM trip WHERE trip_id = ? AND state = 'COMPLETED' AND NOT reservation_released",
            tripId);
    if (rows.isEmpty()) {
      return;
    }
    String rid = (String) rows.get(0).get("reservation_id");
    try {
      if (rid != null) {
        clients.releaseReservation(rid);
      }
      jdbc.update("UPDATE trip SET reservation_released = true WHERE trip_id = ?", tripId);
    } catch (PmartException e) {
      log.warn("점유 해제 보류 {} {}: {}", tripId, rid, e.getMessage()); // 운행은 COMPLETED 그대로
    }
  }

  @Scheduled(
      fixedDelayString = "${pmart.execution.retry-interval:PT30S}",
      initialDelayString = "${pmart.execution.retry-interval:PT30S}")
  public void retry() {
    for (String id :
        jdbc.queryForList(
            "SELECT trip_id FROM trip WHERE outbound_linkage IN ('PENDING', 'UNKNOWN_RESULT') ORDER BY updated_at LIMIT 50",
            String.class)) {
      sendOutbound(id);
    }
    for (String id :
        jdbc.queryForList(
            "SELECT trip_id FROM trip WHERE planning_synced_version < version ORDER BY updated_at LIMIT 50",
            String.class)) {
      notifyPlanning(id);
    }
    for (String id :
        jdbc.queryForList(
            "SELECT trip_id FROM trip WHERE state = 'COMPLETED' AND NOT reservation_released LIMIT 50",
            String.class)) {
      releaseReservation(id);
    }
  }

  // ======================= 공통 =======================

  private String lockState(String tripId) {
    lock(tripId);
    var rows = jdbc.queryForList("SELECT state FROM trip WHERE trip_id = ?", tripId);
    if (rows.isEmpty()) {
      throw PmartException.notFound("NOT_FOUND", "운행 " + tripId + " 이 없습니다.");
    }
    return (String) rows.get(0).get("state");
  }

  private void lock(String tripId) {
    jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?))", rs -> {}, "execution:trip:" + tripId);
  }

  private String stopMfc(String tripId, int seq) {
    var r =
        jdbc.queryForList(
            "SELECT mfc_id FROM trip_stop WHERE trip_id = ? AND seq = ?", tripId, seq);
    if (r.isEmpty()) {
      throw PmartException.notFound("NOT_FOUND", "정차 " + seq + " 이 없습니다.");
    }
    return (String) r.get(0).get("mfc_id");
  }

  private String stopState(String tripId, int seq) {
    return jdbc.queryForObject(
        "SELECT state FROM trip_stop WHERE trip_id = ? AND seq = ?", String.class, tripId, seq);
  }

  private String cdcOf(String tripId) {
    var r = jdbc.queryForList("SELECT cdc_id FROM trip WHERE trip_id = ?", tripId);
    if (r.isEmpty()) {
      throw PmartException.notFound("NOT_FOUND", "운행 " + tripId + " 이 없습니다.");
    }
    return (String) r.get(0).get("cdc_id");
  }

  private void requireCdc(Caller c, String tripId) {
    if (!c.canAccess(cdcOf(tripId))) {
      throw new PmartException(ErrorCode.FORBIDDEN, "출발 센터 운영자만 할 수 있습니다.");
    }
  }

  private void requireCdcOrMfc(Caller c, String tripId, String mfcId) {
    if (!c.canAccess(cdcOf(tripId)) && !c.canAccess(mfcId)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "출발 센터나 그 정차 MFC 운영자만 할 수 있습니다.");
    }
  }

  private void requireAny(Caller c, String tripId) {
    if (c.canAccess(cdcOf(tripId))) {
      return;
    }
    for (String mfc :
        jdbc.queryForList("SELECT mfc_id FROM trip_stop WHERE trip_id = ?", String.class, tripId)) {
      if (c.canAccess(mfc)) {
        return;
      }
    }
    throw new PmartException(ErrorCode.FORBIDDEN, "이 운행에 대한 권한이 없습니다.");
  }

  private Instant when(EventIn e) {
    Instant now = clock.instant();
    if (e.at() == null) {
      return now;
    }
    if (e.at().isAfter(now.plus(CLOCK_SKEW))) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "일어난 시각이 미래입니다.");
    }
    return e.at();
  }

  private void setState(String tripId, String state, @Nullable String unused, Instant at) {
    jdbc.update(
        "UPDATE trip SET state = ?, version = version + 1, updated_at = ? WHERE trip_id = ?",
        state,
        ts(clock.instant()),
        tripId);
  }

  private void bump(String tripId, Instant at) {
    jdbc.update(
        "UPDATE trip SET version = version + 1, updated_at = ? WHERE trip_id = ?",
        ts(clock.instant()),
        tripId);
  }

  private void event(
      String tripId,
      Instant at,
      String type,
      @Nullable Integer stopSeq,
      EventIn e,
      String actor,
      String source) {
    jdbc.update(
        "INSERT INTO trip_event (trip_id, at, type, stop_seq, location, temperature_c, actor_id, source)"
            + " VALUES (?,?,?,?,?,?,?,?)",
        tripId,
        ts(at),
        type,
        stopSeq,
        e.location(),
        e.temperatureC(),
        actor,
        source);
  }

  private void incident(
      String tripId, String type, @Nullable Integer stopSeq, String description, Instant at) {
    jdbc.update(
        "INSERT INTO trip_incident (incident_id, trip_id, type, stop_seq, state, description, raised_at)"
            + " VALUES (?,?,?,?,'OPEN',?,?)",
        "INC-" + UUID.randomUUID(),
        tripId,
        type,
        stopSeq,
        trim(description),
        ts(at));
  }

  static String join(List<String> xs) {
    return String.join(",", xs);
  }

  static List<String> split(@Nullable String s) {
    return s == null || s.isEmpty() ? List.of() : Arrays.asList(s.split(","));
  }

  static String trim(String s) {
    return s.length() > 300 ? s.substring(0, 300) : s;
  }

  static Timestamp ts(Instant i) {
    return Timestamp.from(i);
  }

  static Instant tsOf(Object o) {
    return ((Timestamp) o).toInstant();
  }

  static @Nullable Instant tsOrNull(@Nullable Object o) {
    return o == null ? null : ((Timestamp) o).toInstant();
  }

  static String sha(String s) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static PmartException conflict(String code, String message) {
    return new PmartException(code, HttpStatus.CONFLICT, message, false, List.of());
  }
}
