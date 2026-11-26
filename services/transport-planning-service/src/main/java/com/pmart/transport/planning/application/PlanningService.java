package com.pmart.transport.planning.application;

import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.transport.planning.application.RouteService.RouteView;
import com.pmart.transport.planning.application.RouteService.VehicleView;
import com.pmart.transport.planning.domain.RequestRules;
import com.pmart.transport.planning.domain.Schedule;
import com.pmart.transport.planning.infrastructure.Clients;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * 이관요청서 수신·검증과 운송지시서(배차 계획) — TMS 정본 5·7·8장, 설계 §3·§4. 다른 서비스 호출(cdc-outbound·fleet·execution)은
 * 트랜잭션 밖. 계획완료는 상태(CONFIRMING)로 이어 가므로 같은 요청을 다시 보내면 남은 단계부터 한다(요청 키 재생 대신 상태로 멱등).
 */
@Service
public class PlanningService {

  private static final Logger log = LoggerFactory.getLogger(PlanningService.class);
  static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

  /** 운송지시서에 실린 것으로 보는 요청서 상태. */
  static final Set<String> ON_ORDER = Set.of("ACCEPTED", "ASSIGNED", "DEPARTED", "HANDED_OVER");

  static final Set<String> AFTER_CONFIRM = Set.of("ASSIGNED", "DEPARTED", "HANDED_OVER");

  public record RequestView(
      String trfId,
      @Nullable String trfNumber,
      int trfVersion,
      String cdcId,
      String mfcId,
      LocalDate shipDate,
      Instant dueAt,
      @Nullable String tempClass,
      List<String> tempClasses,
      String allocationState,
      int approvedQty,
      int acceptedQty,
      int boxCount,
      @Nullable Integer rtCount,
      BigDecimal weightKg,
      String state,
      @Nullable String reason,
      boolean lateReceipt,
      @Nullable String orderId,
      Instant receivedAt,
      Instant updatedAt) {}

  public record OrderStop(
      int seq,
      String mfcId,
      int legMinutes,
      Instant plannedArrivalAt,
      Instant plannedDepartureAt,
      List<String> trfIds,
      int boxCount,
      int rtCount) {}

  public record History(
      Instant at, String action, @Nullable String actorId, @Nullable String detail, int version) {}

  public record OrderView(
      String orderId,
      String cdcId,
      LocalDate shipDate,
      String routeId,
      String tempClass,
      String contractType,
      @Nullable String vehicleId,
      @Nullable String driverId,
      @Nullable String plateNo,
      @Nullable String driverName,
      int maxRt,
      int maxWeightKg,
      int loadRt,
      BigDecimal loadWeightKg,
      int returnMinutes,
      Instant plannedLoadingStartAt,
      Instant plannedDepartureAt,
      Instant plannedReturnAt,
      List<OrderStop> stops,
      List<String> trfIds,
      boolean cancelCandidate,
      boolean overCapacity,
      String state,
      @Nullable String reservationId,
      @Nullable String tripState,
      @Nullable String reason,
      int version,
      List<History> history,
      Instant createdAt,
      Instant updatedAt,
      @Nullable String dockCode,
      @Nullable String dockReservationId) {}

  public record Unplanned(String routeId, String tempClass, String reason) {}

  public record Generation(
      LocalDate shipDate,
      List<OrderView> orders,
      Map<String, Integer> requests,
      List<Unplanned> unplanned) {}

  public record Page<T>(List<T> items, int page, int size, boolean hasNext) {}

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final RouteService routes;
  private final Clients clients;
  private final Clock clock;

  public PlanningService(
      JdbcTemplate jdbc,
      TransactionTemplate tx,
      RouteService routes,
      Clients clients,
      Clock clock) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.routes = routes;
    this.clients = clients;
    this.clock = clock;
  }

  // ======================= 이관요청서 =======================

  public Page<RequestView> requests(
      String cdcId,
      @Nullable LocalDate shipDate,
      @Nullable String state,
      @Nullable String mfcId,
      int page,
      int size) {
    Operator.requireCenter(cdcId);
    StringBuilder sql = new StringBuilder(REQ_SELECT + " WHERE cdc_id = ?");
    List<Object> args = new ArrayList<>(List.of(cdcId));
    if (shipDate != null) {
      sql.append(" AND ship_date = ?");
      args.add(shipDate);
    }
    if (state != null) {
      sql.append(" AND state = ?");
      args.add(state);
    }
    if (mfcId != null) {
      sql.append(" AND mfc_id = ?");
      args.add(mfcId);
    }
    int n = Math.min(200, Math.max(1, size));
    int p = Math.max(0, page);
    sql.append(" ORDER BY due_at, trf_id LIMIT ? OFFSET ?");
    args.add(n + 1);
    args.add((long) p * n);
    List<RequestView> rows = jdbc.query(sql.toString(), (rs, i) -> request(rs), args.toArray());
    boolean more = rows.size() > n;
    return new Page<>(more ? rows.subList(0, n) : rows, p, n, more);
  }

  public RequestView request(String cdcId, String trfId) {
    Operator.requireCenter(cdcId);
    return findRequest(trfId)
        .filter(r -> r.cdcId().equals(cdcId))
        .orElseThrow(() -> PmartException.notFound("NOT_FOUND", "이관요청서 " + trfId + " 가 없습니다."));
  }

  Optional<RequestView> findRequest(String trfId) {
    return jdbc.query(REQ_SELECT + " WHERE trf_id = ?", (rs, i) -> request(rs), trfId).stream()
        .findFirst();
  }

  // ======================= 생성 =======================

  /** 출고일 배차 생성(정본 7.1) — 가져오기는 트랜잭션 밖, 반영은 출고일 잠금 안 한 트랜잭션. */
  public Generation generate(String requestId, String source, String cdcId, LocalDate shipDate) {
    Operator op = Operator.forCenter(cdcId, source).requireSupervisor();
    Instant[] w = Schedule.dueWindow(shipDate);
    List<Clients.FeedItem> feed = clients.feed(cdcId, w[0], w[1]); // 안 되면 503 — 아무것도 바꾸지 않음
    Map<String, Integer> counts = new TreeMap<>();
    List<Unplanned> unplanned = new ArrayList<>();
    tx.executeWithoutResult(
        s -> {
          lock("plan:" + cdcId + ":" + shipDate);
          List<RouteView> live = routes.routes(cdcId, true);
          // 1) 노선 × 온도대 지시서(정규차가 있는 칸만)
          for (RouteView r : live) {
            for (String temp : RouteService.TEMPS) {
              Optional<VehicleView> v = r.vehicle(temp);
              if (v.isEmpty()) {
                unplanned.add(new Unplanned(r.routeId(), temp, "NO_REGULAR_VEHICLE"));
                continue;
              }
              if (slot(cdcId, shipDate, r.routeId(), temp).isEmpty()) {
                createOrder(cdcId, shipDate, r, temp, v.get(), op.id());
              }
            }
          }
          // 2) 이관요청서 반영·검증·연결
          for (Clients.FeedItem f : feed) {
            String st = upsertRequest(cdcId, shipDate, f, live);
            counts.merge(st, 1, Integer::sum);
          }
        });
    List<OrderView> orders = orders(cdcId, shipDate, null, null, 0, 200).items();
    return new Generation(shipDate, orders, counts, unplanned);
  }

  private String upsertRequest(
      String cdcId, LocalDate shipDate, Clients.FeedItem f, List<RouteView> live) {
    Instant now = clock.instant();
    Optional<RequestView> prior = findRequest(f.trfId());
    if (prior.isPresent()) {
      RequestView p = prior.get();
      if (f.trfVersion() < p.trfVersion()) {
        return p.state();
      }
      if (AFTER_CONFIRM.contains(p.state())) {
        if (f.trfVersion() > p.trfVersion()) {
          String reason =
              "ASSIGNED".equals(p.state()) ? "NEWER_VERSION" : "VERSION_AFTER_DEPARTURE";
          jdbc.update(
              "UPDATE transport_request SET reason = ?, updated_at = ? WHERE trf_id = ?",
              reason,
              Timestamp.from(now),
              f.trfId());
        }
        return p.state();
      }
    }
    Optional<String> routeId =
        f.mfcId() == null ? Optional.empty() : routes.routeOfMfc(cdcId, f.mfcId());
    RouteView route =
        routeId
            .flatMap(id -> live.stream().filter(r -> r.routeId().equals(id)).findFirst())
            .orElse(null);
    String temp1 = f.tempClasses().size() == 1 ? f.tempClasses().get(0) : null;
    Integer maxRt =
        route == null || temp1 == null
            ? null
            : route.vehicle(temp1).map(VehicleView::maxRt).orElse(null);
    RequestRules.Verdict v =
        RequestRules.evaluate(
            new RequestRules.Input(
                f.mfcId(),
                f.state(),
                f.approvedQty(),
                f.allocatedQty(),
                f.tempClasses(),
                f.rtCount()),
            route != null,
            maxRt);
    String state = v.state();
    String reason = v.reason();
    String orderId = null;
    if ("ACCEPTED".equals(state)) {
      // 손으로 옮긴 계획중 지시서가 있으면 그대로, 아니면 노선 × 온도대 지시서
      String kept = prior.map(RequestView::orderId).orElse(null);
      if (kept != null && planningOfTemp(kept, v.tempClass())) {
        orderId = kept;
      } else {
        Optional<Map<String, Object>> o = slot(cdcId, shipDate, route.routeId(), v.tempClass());
        if (o.isEmpty()) {
          state = "HELD";
          reason = "NO_REGULAR_VEHICLE";
        } else if (!"PLANNING".equals(o.get().get("state"))) {
          state = "HELD";
          reason = "ORDER_CLOSED";
        } else {
          orderId = (String) o.get().get("order_id");
        }
      }
    }
    BigDecimal kg =
        BigDecimal.valueOf(f.weightG()).divide(BigDecimal.valueOf(1000), 3, RoundingMode.HALF_UP);
    Instant receivedAt = prior.map(RequestView::receivedAt).orElse(now);
    boolean late = Schedule.lateReceipt(shipDate, receivedAt);
    Object[] args = {
      f.trfNumber(),
      f.trfVersion(),
      cdcId,
      f.mfcId() == null ? "" : f.mfcId(),
      shipDate,
      Timestamp.from(f.dueAt()),
      String.join(",", f.tempClasses()),
      v.tempClass(),
      f.state(),
      f.approvedQty(),
      v.acceptedQty(),
      f.boxCount(),
      f.rtCount(),
      kg,
      state,
      reason,
      late,
      orderId,
      Timestamp.from(now),
      f.trfId()
    };
    if (prior.isPresent()) {
      jdbc.update(
          "UPDATE transport_request SET trf_number = ?, trf_version = ?, cdc_id = ?, mfc_id = ?,"
              + " ship_date = ?, due_at = ?, temp_classes = ?, temp_class = ?, allocation_state = ?,"
              + " approved_qty = ?, accepted_qty = ?, box_count = ?, rt_count = ?, weight_kg = ?,"
              + " state = ?, reason = ?, late_receipt = ?, order_id = ?, updated_at = ?"
              + " WHERE trf_id = ?",
          args);
    } else {
      jdbc.update(
          "INSERT INTO transport_request (trf_number, trf_version, cdc_id, mfc_id, ship_date,"
              + " due_at, temp_classes, temp_class, allocation_state, approved_qty, accepted_qty,"
              + " box_count, rt_count, weight_kg, state, reason, late_receipt, order_id, updated_at,"
              + " trf_id, received_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
          append(args, Timestamp.from(receivedAt)));
    }
    return state;
  }

  private boolean planningOfTemp(String orderId, @Nullable String temp) {
    var rows =
        jdbc.queryForList(
            "SELECT state, temp_class FROM transport_order WHERE order_id = ?", orderId);
    return !rows.isEmpty()
        && "PLANNING".equals(rows.get(0).get("state"))
        && rows.get(0).get("temp_class").equals(temp);
  }

  private Optional<Map<String, Object>> slot(
      String cdcId, LocalDate shipDate, String routeId, @Nullable String temp) {
    if (temp == null) {
      return Optional.empty();
    }
    return jdbc
        .queryForList(
            "SELECT order_id, state FROM transport_order WHERE cdc_id = ? AND ship_date = ?"
                + " AND route_id = ? AND temp_class = ?",
            cdcId,
            shipDate,
            routeId,
            temp)
        .stream()
        .findFirst();
  }

  private void createOrder(
      String cdcId, LocalDate shipDate, RouteView r, String temp, VehicleView v, String actor) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM transport_order WHERE ship_date = ?", Integer.class, shipDate);
    String id = "TO-" + shipDate.format(YMD) + "-" + String.format("%03d", (n == null ? 0 : n) + 1);
    Schedule.Plan p = Schedule.plan(shipDate, r.departureTime(), r.legs(), r.returnMinutes());
    Instant now = clock.instant();
    jdbc.update(
        "INSERT INTO transport_order (order_id, cdc_id, ship_date, route_id, temp_class,"
            + " contract_type, vehicle_id, driver_id, plate_no, driver_name, max_rt, max_weight_kg,"
            + " return_minutes, planned_loading_start_at, planned_departure_at, planned_return_at,"
            + " state, version, created_at, updated_at)"
            + " VALUES (?,?,?,?,?,'REGULAR',?,?,?,?,?,?,?,?,?,?,'PLANNING',1,?,?)",
        id,
        cdcId,
        shipDate,
        r.routeId(),
        temp,
        v.vehicleId(),
        v.driverId(),
        v.plateNo(),
        v.driverName(),
        v.maxRt(),
        v.maxWeightKg(),
        r.returnMinutes(),
        Timestamp.from(p.loadingStart()),
        Timestamp.from(p.departure()),
        Timestamp.from(p.returnAt()),
        Timestamp.from(now),
        Timestamp.from(now));
    for (Schedule.StopTime s : p.stops()) {
      jdbc.update(
          "INSERT INTO transport_order_stop (order_id, seq, mfc_id, leg_minutes, planned_arrival_at,"
              + " planned_departure_at) VALUES (?,?,?,?,?,?)",
          id,
          s.seq(),
          s.mfcId(),
          s.legMinutes(),
          Timestamp.from(s.arrival()),
          Timestamp.from(s.departure()));
    }
    history(id, "CREATED", actor, r.routeId() + " " + temp + " " + v.vehicleId(), 1);
  }

  // ======================= 운송지시서 조회 =======================

  public Page<OrderView> orders(
      String cdcId,
      @Nullable LocalDate shipDate,
      @Nullable String state,
      @Nullable String routeId,
      int page,
      int size) {
    Operator.requireCenter(cdcId);
    StringBuilder sql = new StringBuilder("SELECT order_id FROM transport_order WHERE cdc_id = ?");
    List<Object> args = new ArrayList<>(List.of(cdcId));
    if (shipDate != null) {
      sql.append(" AND ship_date = ?");
      args.add(shipDate);
    }
    if (state != null) {
      sql.append(" AND state = ?");
      args.add(state);
    }
    if (routeId != null) {
      sql.append(" AND route_id = ?");
      args.add(routeId);
    }
    int n = Math.min(200, Math.max(1, size));
    int p = Math.max(0, page);
    sql.append(
        " ORDER BY ship_date, planned_departure_at, route_id,"
            + " CASE temp_class WHEN 'AMBIENT' THEN 1 WHEN 'CHILLED' THEN 2 ELSE 3 END"
            + " LIMIT ? OFFSET ?");
    args.add(n + 1);
    args.add((long) p * n);
    List<String> ids = jdbc.queryForList(sql.toString(), String.class, args.toArray());
    boolean more = ids.size() > n;
    List<OrderView> items = (more ? ids.subList(0, n) : ids).stream().map(this::view).toList();
    return new Page<>(items, p, n, more);
  }

  public OrderView order(String cdcId, String orderId) {
    Operator.requireCenter(cdcId);
    OrderView v = view(orderId);
    if (!v.cdcId().equals(cdcId)) {
      throw PmartException.notFound("NOT_FOUND", "운송지시서 " + orderId + " 가 없습니다.");
    }
    return v;
  }

  OrderView view(String orderId) {
    var rows = jdbc.queryForList("SELECT * FROM transport_order WHERE order_id = ?", orderId);
    if (rows.isEmpty()) {
      throw PmartException.notFound("NOT_FOUND", "운송지시서 " + orderId + " 가 없습니다.");
    }
    Map<String, Object> o = rows.get(0);
    List<RequestView> reqs =
        jdbc.query(
            REQ_SELECT + " WHERE order_id = ? ORDER BY trf_id", (rs, i) -> request(rs), orderId);
    List<RequestView> on = reqs.stream().filter(r -> ON_ORDER.contains(r.state())).toList();
    int loadRt = on.stream().mapToInt(r -> r.rtCount() == null ? 0 : r.rtCount()).sum();
    BigDecimal kg = on.stream().map(RequestView::weightKg).reduce(BigDecimal.ZERO, BigDecimal::add);
    int maxRt = ((Number) o.get("max_rt")).intValue();
    int maxKg = ((Number) o.get("max_weight_kg")).intValue();
    List<OrderStop> stops =
        jdbc.query(
            "SELECT seq, mfc_id, leg_minutes, planned_arrival_at, planned_departure_at"
                + " FROM transport_order_stop WHERE order_id = ? ORDER BY seq",
            (rs, i) -> {
              String mfc = rs.getString(2);
              List<RequestView> at = on.stream().filter(r -> r.mfcId().equals(mfc)).toList();
              return new OrderStop(
                  rs.getInt(1),
                  mfc,
                  rs.getInt(3),
                  rs.getTimestamp(4).toInstant(),
                  rs.getTimestamp(5).toInstant(),
                  at.stream().map(RequestView::trfId).toList(),
                  at.stream().mapToInt(RequestView::boxCount).sum(),
                  at.stream().mapToInt(r -> r.rtCount() == null ? 0 : r.rtCount()).sum());
            },
            orderId);
    List<History> hist =
        jdbc.query(
            "SELECT at, action, actor_id, detail, version FROM transport_order_history"
                + " WHERE order_id = ? ORDER BY seq",
            (rs, i) ->
                new History(
                    rs.getTimestamp(1).toInstant(),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getInt(5)),
            orderId);
    return new OrderView(
        orderId,
        (String) o.get("cdc_id"),
        ((java.sql.Date) o.get("ship_date")).toLocalDate(),
        (String) o.get("route_id"),
        (String) o.get("temp_class"),
        (String) o.get("contract_type"),
        (String) o.get("vehicle_id"),
        (String) o.get("driver_id"),
        (String) o.get("plate_no"),
        (String) o.get("driver_name"),
        maxRt,
        maxKg,
        loadRt,
        kg,
        ((Number) o.get("return_minutes")).intValue(),
        ts(o.get("planned_loading_start_at")),
        ts(o.get("planned_departure_at")),
        ts(o.get("planned_return_at")),
        stops,
        on.stream().map(RequestView::trfId).toList(),
        on.isEmpty(),
        loadRt > maxRt || kg.compareTo(BigDecimal.valueOf(maxKg)) > 0,
        (String) o.get("state"),
        (String) o.get("reservation_id"),
        (String) o.get("trip_state"),
        (String) o.get("reason"),
        ((Number) o.get("version")).intValue(),
        hist,
        ts(o.get("created_at")),
        ts(o.get("updated_at")),
        (String) o.get("dock_code"),
        (String) o.get("dock_reservation_id"));
  }

  // ======================= 요청서 옮기기 =======================

  public OrderView move(
      String source,
      String cdcId,
      String fromOrderId,
      String trfId,
      String toOrderId,
      @Nullable String reason,
      @Nullable Integer expectedVersion) {
    Operator op = Operator.forCenter(cdcId, source).requireSupervisor();
    tx.executeWithoutResult(
        s -> {
          OrderView from = order(cdcId, fromOrderId);
          lock("order:" + from.shipDate());
          from = view(fromOrderId);
          OrderView to = order(cdcId, toOrderId);
          if (expectedVersion != null && expectedVersion != from.version()) {
            throw PmartException.versionConflict("지시서 version 이 " + from.version() + " 입니다.");
          }
          if (!from.trfIds().contains(trfId)) {
            // 같은 요청을 다시 보낸 경우 — 이미 받는 쪽에 있으면 그대로
            if (to.trfIds().contains(trfId)) {
              return;
            }
            throw PmartException.notFound("NOT_FOUND", "이 지시서에 " + trfId + " 가 없습니다.");
          }
          if (!"PLANNING".equals(from.state()) || !"PLANNING".equals(to.state())) {
            throw PmartException.invalidState("두 지시서 모두 계획중이어야 합니다.");
          }
          if (!from.shipDate().equals(to.shipDate()) || !from.tempClass().equals(to.tempClass())) {
            throw RouteService.conflict("TEMP_MISMATCH", "같은 출고일·온도대 지시서로만 옮길 수 있습니다.");
          }
          RequestView r = findRequest(trfId).orElseThrow();
          List<Schedule.Leg> legs =
              new ArrayList<>(
                  to.stops().stream()
                      .map(x -> new Schedule.Leg(x.mfcId(), x.legMinutes()))
                      .toList());
          if (legs.stream().noneMatch(l -> l.mfcId().equals(r.mfcId()))) {
            if (legs.size() >= 3) {
              throw RouteService.conflict("TOO_MANY_STOPS", "정차는 3곳까지입니다(정본 7.4).");
            }
            int leg =
                from.stops().stream()
                    .filter(x -> x.mfcId().equals(r.mfcId()))
                    .mapToInt(OrderStop::legMinutes)
                    .findFirst()
                    .orElse(60);
            legs.add(new Schedule.Leg(r.mfcId(), leg)); // [가정] 구간 분 = 원래 노선의 그 정차 구간
          }
          Schedule.Plan p =
              Schedule.planFrom(to.shipDate(), to.plannedDepartureAt(), legs, to.returnMinutes());
          if (!p.feasible()) {
            throw RouteService.conflict("ROUTE_TIME_INFEASIBLE", "마지막 정차 도착이 07:30 을 넘습니다.");
          }
          jdbc.update(
              "UPDATE transport_request SET order_id = ?, updated_at = ? WHERE trf_id = ?",
              toOrderId,
              Timestamp.from(clock.instant()),
              trfId);
          rewriteStops(to.orderId(), p);
          // 보내는 쪽: 노선 정차가 아니고 남은 요청서가 없으면 정차를 뺀다
          RouteView fromRoute = routes.route(cdcId, from.routeId());
          List<Schedule.Leg> fromLegs = new ArrayList<>();
          for (OrderStop x : from.stops()) {
            boolean master = fromRoute.stops().stream().anyMatch(m -> m.mfcId().equals(x.mfcId()));
            boolean left = x.trfIds().stream().anyMatch(t -> !t.equals(trfId));
            if (master || left) {
              fromLegs.add(new Schedule.Leg(x.mfcId(), x.legMinutes()));
            }
          }
          rewriteStops(
              from.orderId(),
              Schedule.planFrom(
                  from.shipDate(), from.plannedDepartureAt(), fromLegs, from.returnMinutes()));
          String detail =
              trfId
                  + " "
                  + fromOrderId
                  + " → "
                  + toOrderId
                  + (reason == null ? "" : " · " + reason);
          bump(fromOrderId, "REQUEST_MOVED_OUT", op.id(), detail);
          bump(toOrderId, "REQUEST_MOVED_IN", op.id(), detail);
        });
    return view(toOrderId);
  }

  private void rewriteStops(String orderId, Schedule.Plan p) {
    jdbc.update("DELETE FROM transport_order_stop WHERE order_id = ?", orderId);
    for (Schedule.StopTime s : p.stops()) {
      jdbc.update(
          "INSERT INTO transport_order_stop (order_id, seq, mfc_id, leg_minutes, planned_arrival_at,"
              + " planned_departure_at) VALUES (?,?,?,?,?,?)",
          orderId,
          s.seq(),
          s.mfcId(),
          s.legMinutes(),
          Timestamp.from(s.arrival()),
          Timestamp.from(s.departure()));
    }
    jdbc.update(
        "UPDATE transport_order SET planned_return_at = ? WHERE order_id = ?",
        Timestamp.from(p.returnAt()),
        orderId);
  }

  // ======================= 계획완료 =======================

  public OrderView confirm(
      String source,
      String cdcId,
      String orderId,
      @Nullable String vehicleId,
      @Nullable String driverId,
      @Nullable Integer expectedVersion) {
    Operator op = Operator.forCenter(cdcId, source).requireSupervisor();
    OrderView o = order(cdcId, orderId);
    if ("DISPATCHED".equals(o.state())) {
      return o; // 이미 끝남(같은 요청 다시)
    }
    if (!"PLANNING".equals(o.state()) && !"CONFIRMING".equals(o.state())) {
      throw PmartException.invalidState("계획중 지시서만 계획완료할 수 있습니다(지금 " + o.state() + ").");
    }
    if ("PLANNING".equals(o.state())) {
      if (expectedVersion != null && expectedVersion != o.version()) {
        throw PmartException.versionConflict("지시서 version 이 " + o.version() + " 입니다.");
      }
      if (o.trfIds().isEmpty()) {
        throw RouteService.conflict("NO_REQUESTS", "연결된 이관요청서가 없습니다 — 결행 후보입니다.");
      }
      String vid = vehicleId == null ? o.vehicleId() : vehicleId;
      String did = driverId == null ? o.driverId() : driverId;
      Clients.VehicleInfo v = clients.vehicle(vid);
      Clients.DriverInfo d = clients.driver(did);
      if (!v.active() || !o.tempClass().equals(v.tempClass())) {
        throw RouteService.conflict(
            "VEHICLE_NOT_ELIGIBLE", "차량 " + vid + " 은 사용 중인 " + o.tempClass() + " 차량이 아닙니다.");
      }
      if (!d.active()) {
        throw RouteService.conflict("DRIVER_NOT_ELIGIBLE", "기사 " + did + " 은 사용 중지입니다.");
      }
      if (o.loadRt() > v.maxRt()
          || o.loadWeightKg().compareTo(BigDecimal.valueOf(v.maxWeightKg())) > 0) {
        throw RouteService.conflict(
            "OVER_CAPACITY",
            "적재 "
                + o.loadRt()
                + " R/T·"
                + o.loadWeightKg()
                + "kg 이 차량 최대 "
                + v.maxRt()
                + " R/T·"
                + v.maxWeightKg()
                + "kg 을 넘습니다(용차 없음 — 요청서를 옮기세요).");
      }
      tx.executeWithoutResult(
          s -> {
            int n =
                jdbc.update(
                    "UPDATE transport_order SET state = 'CONFIRMING', vehicle_id = ?, driver_id = ?,"
                        + " plate_no = ?, driver_name = ?, max_rt = ?, max_weight_kg = ?,"
                        + " contract_type = ?, version = version + 1, updated_at = ?"
                        + " WHERE order_id = ? AND state = 'PLANNING' AND version = ?",
                    vid,
                    did,
                    v.plateNo(),
                    d.displayName(),
                    v.maxRt(),
                    v.maxWeightKg(),
                    v.contractType(),
                    Timestamp.from(clock.instant()),
                    orderId,
                    o.version());
            if (n == 0) {
              throw PmartException.versionConflict("지시서가 그사이 바뀌었습니다.");
            }
            history(orderId, "CONFIRMING", op.id(), vid + " · " + did, o.version() + 1);
          });
    }
    return continueConfirm(orderId, op.id());
  }

  /** CONFIRMING 이어 가기 — fleet 점유 → execution 운행. 불명이면 CONFIRMING 그대로(주기 재시도). */
  OrderView continueConfirm(String orderId, String actor) {
    OrderView o = view(orderId);
    if (!"CONFIRMING".equals(o.state())) {
      return o;
    }
    String reservationId = o.reservationId();
    try {
      if (reservationId == null) {
        reservationId =
            clients.reserve(
                "plan-res-" + orderId + "-" + o.vehicleId() + "-" + o.driverId(),
                o.vehicleId(),
                o.driverId(),
                o.plannedLoadingStartAt(),
                o.plannedReturnAt(),
                orderId);
        jdbc.update(
            "UPDATE transport_order SET reservation_id = ? WHERE order_id = ?",
            reservationId,
            orderId);
      }
      String[] dock = reserveDock(o, actor);
      clients.createTrip(
          "plan-trip-" + orderId + "-v" + o.version(),
          tripBody(o, reservationId, dock[0], dock[1]));
    } catch (PmartException e) {
      if (Clients.unknown(e)) {
        log.warn("계획완료 이어 가기 보류 {}: {}", orderId, e.getMessage());
        return view(orderId);
      }
      // 업무 거절(점유 겹침·도크 없음·운행 충돌) — 계획중으로 되돌리고 이번에 잡은 점유·도크만 푼다
      releaseDock(orderId, "REPLANNED");
      final String held = reservationId;
      if (held != null) {
        try {
          clients.release("plan-rel-" + orderId + "-" + held, held, "REPLANNED");
        } catch (PmartException ignore) {
          log.warn("점유 해제 실패 {} {}: {}", orderId, held, ignore.getMessage());
        }
      }
      tx.executeWithoutResult(
          s -> {
            jdbc.update(
                "UPDATE transport_order SET state = 'PLANNING', reservation_id = NULL,"
                    + " dock_code = NULL, dock_reservation_id = NULL, reason = ?,"
                    + " version = version + 1, updated_at = ? WHERE order_id = ?",
                trim(e.code() + ": " + e.getMessage()),
                Timestamp.from(clock.instant()),
                orderId);
            history(
                orderId,
                "CONFIRM_REJECTED",
                actor,
                trim(e.code() + ": " + e.getMessage()),
                o.version() + 1);
          });
      throw e;
    }
    tx.executeWithoutResult(
        s -> {
          jdbc.update(
              "UPDATE transport_order SET state = 'DISPATCHED', trip_state = 'SCHEDULED',"
                  + " reason = NULL, version = version + 1, updated_at = ? WHERE order_id = ?",
              Timestamp.from(clock.instant()),
              orderId);
          jdbc.update(
              "UPDATE transport_request SET state = 'ASSIGNED', updated_at = ?"
                  + " WHERE order_id = ? AND state = 'ACCEPTED'",
              Timestamp.from(clock.instant()),
              orderId);
          history(orderId, "DISPATCHED", actor, o.vehicleId(), o.version() + 1);
        });
    return view(orderId);
  }

  /**
   * 도크 자동 배정(0.3.0 — 사용자 "계획완료 때 자동"): 창 = 상차 시작 ~ 계획 출차, 지시서 온도대. 그 온도대 도크 칸이 없으면(NO_DOCK) 도크 없이
   * 이어 간다(이력 한 번). 다 차면(CAPACITY_EXCEEDED) 예외 — 계획완료 거절.
   *
   * @return {dockReservationId, dockCode} — 없으면 null 들
   */
  private String[] reserveDock(OrderView o, String actor) {
    var cur =
        jdbc.queryForList(
            "SELECT dock_code, dock_reservation_id FROM transport_order WHERE order_id = ?",
            o.orderId());
    if (!cur.isEmpty() && cur.get(0).get("dock_reservation_id") != null) {
      return new String[] {
        (String) cur.get(0).get("dock_reservation_id"), (String) cur.get(0).get("dock_code")
      };
    }
    try {
      JsonNode d =
          clients.reserveDock(
              "plan-dock-" + o.orderId() + "-" + o.plannedLoadingStartAt().getEpochSecond(),
              o.cdcId(),
              o.orderId(),
              o.vehicleId(),
              o.tempClass(),
              o.routeId(),
              o.plateNo(),
              o.plannedLoadingStartAt(),
              o.plannedDepartureAt());
      String id = d.path("dockReservationId").asString();
      String code = d.path("dockCode").asString();
      jdbc.update(
          "UPDATE transport_order SET dock_code = ?, dock_reservation_id = ? WHERE order_id = ?",
          code,
          id,
          o.orderId());
      return new String[] {id, code};
    } catch (PmartException e) {
      if (!"NO_DOCK".equals(e.code())) {
        throw e;
      }
      Integer noted =
          jdbc.queryForObject(
              "SELECT count(*) FROM transport_order_history WHERE order_id = ? AND action = 'NO_DOCK'",
              Integer.class,
              o.orderId());
      if (noted == null || noted == 0) {
        history(o.orderId(), "NO_DOCK", actor, trim(e.getMessage()), o.version());
      }
      return new String[] {null, null};
    }
  }

  /** 도크 예약 해제(취소·출차·거절) — 실패해도 경고만(창이 지나면 다른 예약을 막지 않는다). */
  void releaseDock(String orderId, String reason) {
    var cur =
        jdbc.queryForList(
            "SELECT dock_reservation_id FROM transport_order WHERE order_id = ?", orderId);
    if (cur.isEmpty() || cur.get(0).get("dock_reservation_id") == null) {
      return;
    }
    String id = (String) cur.get(0).get("dock_reservation_id");
    try {
      clients.releaseDock("plan-dock-rel-" + orderId + "-" + reason, id, reason);
    } catch (PmartException e) {
      log.warn("도크 해제 보류 {} {}: {}", orderId, id, e.getMessage());
    }
  }

  private Map<String, Object> tripBody(
      OrderView o,
      String reservationId,
      @Nullable String dockReservationId,
      @Nullable String dockCode) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("tripId", o.orderId());
    m.put("orderVersion", o.version());
    m.put("cdcId", o.cdcId());
    m.put("shipDate", o.shipDate().toString());
    m.put("routeId", o.routeId());
    m.put("tempClass", o.tempClass());
    m.put("vehicleId", o.vehicleId());
    m.put("plateNo", o.plateNo());
    m.put("driverId", o.driverId());
    m.put("driverName", o.driverName());
    m.put("reservationId", reservationId);
    m.put("dockCode", dockCode);
    m.put("dockReservationId", dockReservationId);
    m.put("plannedLoadingStartAt", o.plannedLoadingStartAt().toString());
    m.put("plannedDepartureAt", o.plannedDepartureAt().toString());
    m.put("plannedReturnAt", o.plannedReturnAt().toString());
    m.put("returnMinutes", o.returnMinutes());
    List<Map<String, Object>> stops = new ArrayList<>();
    for (OrderStop s : o.stops()) {
      if (s.trfIds().isEmpty()) {
        continue; // 실을 것이 없는 정차는 들르지 않는다
      }
      Map<String, Object> x = new LinkedHashMap<>();
      x.put("seq", stops.size() + 1);
      x.put("mfcId", s.mfcId());
      x.put("plannedArrivalAt", s.plannedArrivalAt().toString());
      x.put("plannedDepartureAt", s.plannedDepartureAt().toString());
      x.put("legMinutes", s.legMinutes());
      x.put("trfIds", s.trfIds());
      stops.add(x);
    }
    m.put("stops", stops);
    return m;
  }

  /** 계획완료 중 응답이 불명이던 지시서 이어 가기. */
  @Scheduled(
      fixedDelayString = "${pmart.planning.retry-interval:PT30S}",
      initialDelayString = "${pmart.planning.retry-interval:PT30S}")
  public void retryConfirming() {
    for (String id :
        jdbc.queryForList(
            "SELECT order_id FROM transport_order WHERE state = 'CONFIRMING' ORDER BY updated_at LIMIT 50",
            String.class)) {
      try {
        continueConfirm(id, "system");
      } catch (RuntimeException e) {
        log.warn("계획완료 재시도 거절 {}: {}", id, e.getMessage());
      }
    }
  }

  // ======================= 결행·취소 =======================

  public OrderView skip(String source, String cdcId, String orderId, String reason) {
    Operator op = Operator.forCenter(cdcId, source).requireSupervisor();
    OrderView o = order(cdcId, orderId);
    if ("SKIPPED".equals(o.state())) {
      return o;
    }
    if (!"PLANNING".equals(o.state())) {
      throw PmartException.invalidState("계획중 지시서만 결행할 수 있습니다.");
    }
    if (!o.trfIds().isEmpty()) {
      throw RouteService.conflict("HAS_REQUESTS", "연결된 이관요청서가 있어 결행할 수 없습니다 — 먼저 옮기세요.");
    }
    tx.executeWithoutResult(
        s -> {
          jdbc.update(
              "UPDATE transport_order SET state = 'SKIPPED', reason = ?, version = version + 1,"
                  + " updated_at = ? WHERE order_id = ?",
              trim(reason),
              Timestamp.from(clock.instant()),
              orderId);
          history(orderId, "SKIPPED", op.id(), trim(reason), o.version() + 1);
        });
    return view(orderId);
  }

  public OrderView cancel(String source, String cdcId, String orderId, String reason) {
    Operator op = Operator.forCenter(cdcId, source).requireSupervisor();
    OrderView o = order(cdcId, orderId);
    if ("CANCELLED".equals(o.state())) {
      return o;
    }
    if ("SKIPPED".equals(o.state())) {
      throw PmartException.invalidState("결행된 지시서입니다.");
    }
    if ("DISPATCHED".equals(o.state()) || "CONFIRMING".equals(o.state())) {
      if ("DISPATCHED".equals(o.state())) {
        clients.cancelTrip("plan-cancel-" + orderId, orderId, trim(reason)); // 출차 뒤 409 그대로
      }
      if (o.reservationId() != null) {
        clients.release(
            "plan-rel-" + orderId + "-" + o.reservationId(), o.reservationId(), "CANCELLED");
      }
      releaseDock(orderId, "CANCELLED");
    }
    tx.executeWithoutResult(
        s -> {
          jdbc.update(
              "UPDATE transport_order SET state = 'CANCELLED', reason = ?, version = version + 1,"
                  + " updated_at = ? WHERE order_id = ?",
              trim(reason),
              Timestamp.from(clock.instant()),
              orderId);
          jdbc.update(
              "UPDATE transport_request SET state = 'ACCEPTED', order_id = NULL, updated_at = ?"
                  + " WHERE order_id = ? AND state IN ('ACCEPTED', 'ASSIGNED')",
              Timestamp.from(clock.instant()),
              orderId);
          history(orderId, "CANCELLED", op.id(), trim(reason), o.version() + 1);
        });
    return view(orderId);
  }

  // ======================= 운행 상태 사본 =======================

  public void tripUpdate(
      String orderId, String tripState, int tripVersion, Instant at, List<String> handedOver) {
    tx.executeWithoutResult(
        s -> {
          var rows =
              jdbc.queryForList(
                  "SELECT trip_version FROM transport_order WHERE order_id = ? FOR UPDATE",
                  orderId);
          if (rows.isEmpty()) {
            throw PmartException.notFound("NOT_FOUND", "운송지시서 " + orderId + " 가 없습니다.");
          }
          Object cur = rows.get(0).get("trip_version");
          if (cur != null && ((Number) cur).intValue() >= tripVersion) {
            return; // 낮은·같은 버전은 무시
          }
          jdbc.update(
              "UPDATE transport_order SET trip_state = ?, trip_version = ?, updated_at = ?"
                  + " WHERE order_id = ?",
              tripState,
              tripVersion,
              Timestamp.from(clock.instant()),
              orderId);
          if ("DEPARTED".equals(tripState) || "COMPLETED".equals(tripState)) {
            jdbc.update(
                "UPDATE transport_request SET state = 'DEPARTED', updated_at = ?"
                    + " WHERE order_id = ? AND state = 'ASSIGNED'",
                Timestamp.from(at),
                orderId);
          }
          for (String trf : handedOver) {
            jdbc.update(
                "UPDATE transport_request SET state = 'HANDED_OVER', updated_at = ?"
                    + " WHERE order_id = ? AND trf_id = ? AND state IN ('ASSIGNED', 'DEPARTED')",
                Timestamp.from(at),
                orderId,
                trf);
          }
        });
    if ("DEPARTED".equals(tripState) || "COMPLETED".equals(tripState)) {
      releaseDock(orderId, "DEPARTED"); // 출차하면 도크가 빈다(트랜잭션 밖)
    }
  }

  // ======================= 공통 =======================

  private void bump(String orderId, String action, String actor, String detail) {
    jdbc.update(
        "UPDATE transport_order SET version = version + 1, updated_at = ? WHERE order_id = ?",
        Timestamp.from(clock.instant()),
        orderId);
    Integer v =
        jdbc.queryForObject(
            "SELECT version FROM transport_order WHERE order_id = ?", Integer.class, orderId);
    history(orderId, action, actor, detail, v == null ? 0 : v);
  }

  private void history(
      String orderId, String action, String actor, @Nullable String detail, int version) {
    jdbc.update(
        "INSERT INTO transport_order_history (order_id, at, action, actor_id, detail, version)"
            + " VALUES (?,?,?,?,?,?)",
        orderId,
        Timestamp.from(clock.instant()),
        action,
        actor,
        detail == null ? null : trim(detail),
        version);
  }

  private void lock(String key) {
    jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?))", rs -> {}, "planning:" + key);
  }

  static String trim(String s) {
    return s.length() > 300 ? s.substring(0, 300) : s;
  }

  static String actor() {
    return PmartPrincipal.current().map(PmartPrincipal::subject).orElse("system");
  }

  private static Instant ts(Object o) {
    return ((Timestamp) o).toInstant();
  }

  private static Object[] append(Object[] a, Object x) {
    Object[] out = java.util.Arrays.copyOf(a, a.length + 1);
    out[a.length] = x;
    return out;
  }

  private static final String REQ_SELECT =
      "SELECT trf_id, trf_number, trf_version, cdc_id, mfc_id, ship_date, due_at, temp_classes,"
          + " temp_class, allocation_state, approved_qty, accepted_qty, box_count, rt_count, weight_kg,"
          + " state, reason, late_receipt, order_id, received_at, updated_at FROM transport_request";

  private static RequestView request(ResultSet rs) throws SQLException {
    String temps = rs.getString("temp_classes");
    Object rt = rs.getObject("rt_count");
    return new RequestView(
        rs.getString("trf_id"),
        rs.getString("trf_number"),
        rs.getInt("trf_version"),
        rs.getString("cdc_id"),
        rs.getString("mfc_id"),
        rs.getDate("ship_date").toLocalDate(),
        rs.getTimestamp("due_at").toInstant(),
        rs.getString("temp_class"),
        temps == null || temps.isEmpty() ? List.of() : List.of(temps.split(",")),
        rs.getString("allocation_state"),
        rs.getInt("approved_qty"),
        rs.getInt("accepted_qty"),
        rs.getInt("box_count"),
        rt == null ? null : ((Number) rt).intValue(),
        rs.getBigDecimal("weight_kg"),
        rs.getString("state"),
        rs.getString("reason"),
        rs.getBoolean("late_receipt"),
        rs.getString("order_id"),
        rs.getTimestamp("received_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }

  static PmartException badRequest(String message) {
    return new PmartException(ErrorCode.VALIDATION_FAILED, message);
  }

  static PmartException conflict(String code, String message) {
    return new PmartException(code, HttpStatus.CONFLICT, message, false, List.of());
  }
}
