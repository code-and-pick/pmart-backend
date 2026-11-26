package com.pmart.cdc.location.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 공간 예약(0.7.0 — 사가 S7 1단계, WMS CDC-F-010)과 피킹 선반 최소·목표 설정(CDC-F-013). 칸 행을 잠그고 현물(부르는 쪽이 재고에서 읽은 값)
 * + 그 칸 미완료 예약 + 신규 ≤ maxQty. 자동 만료 없음 — 부르는 쪽이 소비·해제한다.
 */
@Service
public class SpaceService {

  static final String OP_RESERVE = "cdcLocation:spaceReserve";
  static final String OP_RELEASE = "cdcLocation:spaceRelease";
  static final String OP_PICK_FACE = "cdcLocation:pickFaceSettings";

  public record ReserveLine(
      String locationCode, String sku, @Nullable String lot, int qty, int occupiedQty) {}

  public record Reserve(
      String centerId, String sourceRef, @Nullable Instant expiresAt, List<ReserveLine> lines) {}

  public record ReleaseLine(String locationCode, int qty) {}

  public record Line(
      String locationCode,
      String sku,
      @Nullable String lot,
      int reservedQty,
      int consumedQty,
      int releasedQty) {}

  public record View(
      String reservationId,
      String centerId,
      String sourceRef,
      String state,
      int version,
      @Nullable Instant expiresAt,
      List<Line> lines) {}

  public record Setting(
      String locationCode, @Nullable Integer minQty, @Nullable Integer targetQty) {}

  public record PickFace(
      String centerId,
      String locationCode,
      String temperatureZone,
      @Nullable Integer maxQty,
      int minQty,
      int targetQty) {}

  public record Ref(String id) {}

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final RequestIdempotency idempotency;
  private final ObjectMapper mapper;
  private final Clock clock;

  public SpaceService(
      JdbcTemplate jdbc,
      TransactionTemplate tx,
      RequestIdempotency idempotency,
      ObjectMapper mapper,
      Clock clock) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.idempotency = idempotency;
    this.mapper = mapper;
    this.clock = clock;
  }

  // ---------- 공간 예약 ----------

  public View reserve(String requestId, String sourceSystem, Reserve r) {
    String hash = sha(mapper.writeValueAsString(r));
    String id =
        idempotency
            .execute(
                new RequestKey(sourceSystem, requestId, OP_RESERVE, sourceSystem),
                r,
                Ref.class,
                () -> {
                  List<Map<String, Object>> prior =
                      jdbc.queryForList(
                          "SELECT reservation_id, body_hash FROM space_reservation WHERE center_id = ? AND source_ref = ?",
                          r.centerId(),
                          r.sourceRef());
                  if (!prior.isEmpty()) {
                    if (!hash.equals(prior.get(0).get("body_hash"))) {
                      throw conflict(
                          "SPACE_RESERVATION_CONFLICT",
                          "sourceRef " + r.sourceRef() + " 는 다른 내용으로 이미 예약돼 있습니다.",
                          List.of());
                    }
                    return new Ref((String) prior.get(0).get("reservation_id"));
                  }
                  // 칸 코드 순으로 잠근다(교착 방지)
                  Map<String, ReserveLine> byLoc = new TreeMap<>();
                  for (ReserveLine l : r.lines()) {
                    if (byLoc.put(l.locationCode(), l) != null) {
                      throw new PmartException(
                          ErrorCode.VALIDATION_FAILED, "같은 칸이 두 번 있습니다: " + l.locationCode());
                    }
                  }
                  List<PmartException.Detail> over = new ArrayList<>();
                  for (ReserveLine l : byLoc.values()) {
                    List<Map<String, Object>> loc =
                        jdbc.queryForList(
                            "SELECT state, max_qty FROM location WHERE location_code = ? AND center_id = ? FOR UPDATE",
                            l.locationCode(),
                            r.centerId());
                    if (loc.isEmpty()) {
                      throw PmartException.notFound(
                          "NOT_FOUND", "칸 " + l.locationCode() + " 이 없습니다.");
                    }
                    if (!"ACTIVE".equals(loc.get(0).get("state"))) {
                      throw conflict(
                          "LOCATION_NOT_ACTIVE",
                          "칸 " + l.locationCode() + " 은 " + loc.get(0).get("state") + " 입니다.",
                          List.of());
                    }
                    Object max = loc.get(0).get("max_qty");
                    if (max == null) {
                      throw PmartException.configRequired(
                          "location.max_qty (" + l.locationCode() + ")");
                    }
                    int open = openReserved(l.locationCode());
                    int room = ((Number) max).intValue() - l.occupiedQty() - open;
                    if (l.qty() > room) {
                      over.add(
                          new PmartException.Detail(l.locationCode(), "ROOM=" + Math.max(0, room)));
                    }
                  }
                  if (!over.isEmpty()) {
                    throw conflict("CAPACITY_EXCEEDED", "칸 여유보다 많이 예약할 수 없습니다.", over);
                  }
                  String rid =
                      "SR-"
                          + UUID.randomUUID()
                              .toString()
                              .replace("-", "")
                              .substring(0, 16)
                              .toUpperCase();
                  Timestamp at = Timestamp.from(clock.instant());
                  jdbc.update(
                      "INSERT INTO space_reservation (reservation_id, center_id, source_ref, body_hash, state, version,"
                          + " expires_at, created_at, updated_at) VALUES (?,?,?,?,'RESERVED',1,?,?,?)",
                      rid,
                      r.centerId(),
                      r.sourceRef(),
                      hash,
                      r.expiresAt() == null ? null : Timestamp.from(r.expiresAt()),
                      at,
                      at);
                  for (ReserveLine l : byLoc.values()) {
                    jdbc.update(
                        "INSERT INTO space_reservation_line (reservation_id, location_code, sku, lot, reserved_qty)"
                            + " VALUES (?,?,?,?,?)",
                        rid,
                        l.locationCode(),
                        l.sku(),
                        l.lot(),
                        l.qty());
                  }
                  return new Ref(rid);
                })
            .value()
            .id();
    return get(id);
  }

  /** 그 칸의 미완료 예약 합(예약 − 소비 − 해제). */
  int openReserved(String locationCode) {
    Integer n =
        jdbc.queryForObject(
            "SELECT COALESCE(SUM(reserved_qty - consumed_qty - released_qty), 0) FROM space_reservation_line"
                + " WHERE location_code = ?",
            Integer.class,
            locationCode);
    return n == null ? 0 : n;
  }

  public View release(
      String requestId,
      String sourceSystem,
      String reservationId,
      String action,
      @Nullable String commitId,
      List<ReleaseLine> lines) {
    Map<String, Object> req = new LinkedHashMap<>();
    req.put("reservationId", reservationId);
    req.put("action", action);
    req.put("commitId", String.valueOf(commitId));
    req.put("lines", lines);
    idempotency.execute(
        new RequestKey(sourceSystem, requestId, OP_RELEASE, sourceSystem),
        req,
        Ref.class,
        () -> {
          List<Map<String, Object>> h =
              jdbc.queryForList(
                  "SELECT reservation_id FROM space_reservation WHERE reservation_id = ? FOR UPDATE",
                  reservationId);
          if (h.isEmpty()) {
            throw PmartException.notFound("NOT_FOUND", "공간 예약 " + reservationId + " 가 없습니다.");
          }
          String col = "CONSUME".equals(action) ? "consumed_qty" : "released_qty";
          List<Line> cur = lines(reservationId);
          List<ReleaseLine> todo =
              lines.isEmpty()
                  ? cur.stream()
                      .map(
                          l ->
                              new ReleaseLine(
                                  l.locationCode(),
                                  l.reservedQty() - l.consumedQty() - l.releasedQty()))
                      .filter(l -> l.qty() > 0)
                      .toList()
                  : lines;
          for (ReleaseLine l : todo) {
            Line c =
                cur.stream()
                    .filter(x -> x.locationCode().equals(l.locationCode()))
                    .findFirst()
                    .orElseThrow(
                        () ->
                            PmartException.notFound(
                                "NOT_FOUND", "예약에 칸 " + l.locationCode() + " 이 없습니다."));
            int left = c.reservedQty() - c.consumedQty() - c.releasedQty();
            if (l.qty() > left) {
              throw conflict(
                  "OVER_RELEASE",
                  l.locationCode() + " 남은 예약(" + left + ")보다 많이 " + action + " 할 수 없습니다.",
                  List.of());
            }
            jdbc.update(
                "UPDATE space_reservation_line SET "
                    + col
                    + " = "
                    + col
                    + " + ? WHERE reservation_id = ? AND location_code = ?",
                l.qty(),
                reservationId,
                l.locationCode());
          }
          List<Line> after = lines(reservationId);
          int open =
              after.stream()
                  .mapToInt(l -> l.reservedQty() - l.consumedQty() - l.releasedQty())
                  .sum();
          int consumed = after.stream().mapToInt(Line::consumedQty).sum();
          String state =
              open > 0
                  ? (consumed > 0 ? "PARTIALLY_CONSUMED" : "RESERVED")
                  : consumed > 0 ? "CONSUMED" : "RELEASED";
          jdbc.update(
              "UPDATE space_reservation SET state = ?, version = version + 1, updated_at = ? WHERE reservation_id = ?",
              state,
              Timestamp.from(clock.instant()),
              reservationId);
          return new Ref(reservationId);
        });
    return get(reservationId);
  }

  public View get(String reservationId) {
    List<Map<String, Object>> h =
        jdbc.queryForList(
            "SELECT * FROM space_reservation WHERE reservation_id = ?", reservationId);
    if (h.isEmpty()) {
      throw PmartException.notFound("NOT_FOUND", "공간 예약 " + reservationId + " 가 없습니다.");
    }
    Map<String, Object> r = h.get(0);
    Timestamp ex = (Timestamp) r.get("expires_at");
    return new View(
        reservationId,
        (String) r.get("center_id"),
        (String) r.get("source_ref"),
        (String) r.get("state"),
        ((Number) r.get("version")).intValue(),
        ex == null ? null : ex.toInstant(),
        lines(reservationId));
  }

  private List<Line> lines(String reservationId) {
    return jdbc.query(
        "SELECT location_code, sku, lot, reserved_qty, consumed_qty, released_qty FROM space_reservation_line"
            + " WHERE reservation_id = ? ORDER BY location_code",
        (rs, i) ->
            new Line(
                rs.getString(1),
                rs.getString(2),
                rs.getString(3),
                rs.getInt(4),
                rs.getInt(5),
                rs.getInt(6)),
        reservationId);
  }

  // ---------- 피킹 선반 최소·목표 ----------

  public int setPickFaces(
      String requestId, String sourceSystem, String centerId, List<Setting> items) {
    Map<String, Object> req = new LinkedHashMap<>();
    req.put("centerId", centerId);
    req.put("items", items);
    return idempotency
        .execute(
            new RequestKey(sourceSystem, requestId, OP_PICK_FACE, sourceSystem),
            req,
            Integer.class,
            () -> {
              List<PmartException.Detail> bad = new ArrayList<>();
              for (int i = 0; i < items.size(); i++) {
                Setting s = items.get(i);
                if (s.minQty() != null && s.targetQty() != null && s.targetQty() < s.minQty()) {
                  bad.add(new PmartException.Detail("items[" + i + "]", "TARGET_BELOW_MIN"));
                }
                if ((s.minQty() == null) != (s.targetQty() == null)) {
                  bad.add(new PmartException.Detail("items[" + i + "]", "MIN_AND_TARGET_TOGETHER"));
                }
              }
              if (!bad.isEmpty()) {
                throw new PmartException(ErrorCode.VALIDATION_FAILED, "최소·목표가 올바르지 않습니다.", bad);
              }
              int n = 0;
              Timestamp at = Timestamp.from(clock.instant());
              for (int i = 0; i < items.size(); i++) {
                Setting s = items.get(i);
                List<String> type =
                    jdbc.queryForList(
                        "SELECT location_type FROM location WHERE location_code = ? AND center_id = ? FOR UPDATE",
                        String.class,
                        s.locationCode(),
                        centerId);
                if (type.isEmpty()) {
                  throw PmartException.notFound("NOT_FOUND", "칸 " + s.locationCode() + " 이 없습니다.");
                }
                if (!"PICK_FACE".equals(type.get(0))) {
                  throw new PmartException(
                      ErrorCode.VALIDATION_FAILED,
                      "피킹 선반만 최소·목표를 둡니다: " + s.locationCode(),
                      List.of(new PmartException.Detail("items[" + i + "]", "PICK_FACE_ONLY")));
                }
                n +=
                    jdbc.update(
                        "UPDATE location SET pick_face_min_qty = ?, pick_face_target_qty = ?, updated_at = ?"
                            + " WHERE location_code = ?",
                        s.minQty(),
                        s.targetQty(),
                        at,
                        s.locationCode());
              }
              return n;
            })
        .value();
  }

  public List<PickFace> pickFaces(@Nullable String centerId, int limit) {
    String sql =
        "SELECT center_id, location_code, temperature_zone, max_qty, pick_face_min_qty, pick_face_target_qty"
            + " FROM location WHERE location_type = 'PICK_FACE' AND state = 'ACTIVE'"
            + " AND pick_face_min_qty IS NOT NULL AND pick_face_target_qty IS NOT NULL"
            + (centerId == null ? "" : " AND center_id = ?")
            + " ORDER BY location_code LIMIT ?";
    Object[] args = centerId == null ? new Object[] {limit} : new Object[] {centerId, limit};
    return jdbc.query(
        sql,
        (rs, i) ->
            new PickFace(
                rs.getString(1),
                rs.getString(2),
                rs.getString(3),
                (Integer) rs.getObject(4),
                rs.getInt(5),
                rs.getInt(6)),
        args);
  }

  private static PmartException conflict(
      String code, String message, List<PmartException.Detail> d) {
    return new PmartException(code, HttpStatus.CONFLICT, message, false, d);
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
}
