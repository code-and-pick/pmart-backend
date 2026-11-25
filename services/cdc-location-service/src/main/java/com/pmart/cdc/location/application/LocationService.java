package com.pmart.cdc.location.application;

import com.pmart.cdc.location.domain.LocationCode;
import com.pmart.cdc.location.domain.LocationCode.Parts;
import com.pmart.cdc.location.domain.LocationCode.Violation;
import com.pmart.cdc.location.domain.LocationStates;
import com.pmart.cdc.location.infrastructure.LocationStore;
import com.pmart.cdc.location.infrastructure.LocationStore.Filter;
import com.pmart.cdc.location.infrastructure.LocationStore.LocationRow;
import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.Outcome;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 위치 마스터(cdc-location 0.2.0): 등록·일괄 등록·조회·상태 전환. 코드 문법은 {@link LocationCode}, 상태 전환은 {@link
 * LocationStates}. 상태가 바뀔 때만 LocStateChanged(cdc.location.v1)를 낸다 — 만들 때는 내지 않는다.
 */
@Service
public class LocationService {

  public static final String TOPIC = "cdc.location.v1";
  public static final int MAX_BATCH = 2000;
  static final String OP_CREATE = "cdcLocation:create";
  static final String OP_BATCH = "cdcLocation:batch";
  static final String OP_STATE = "cdcLocation:state";

  /** 등록 입력(계약 LocationInput). */
  public record Input(
      String locationCode,
      String centerId,
      String temperatureZone,
      String locationType,
      @Nullable String capacityUnit,
      @Nullable Integer maxQty,
      @Nullable Integer maxWeightG,
      List<String> restrictions,
      @Nullable Integer pickFaceMinQty,
      @Nullable Integer pickFaceTargetQty) {}

  public record Batch(String centerId, String approvalRef, boolean activate, List<Input> items) {}

  public record BatchResult(String requestId, String approvalRef, int created, Instant at) {}

  public record StateChange(
      String locationCode,
      String action,
      String reason,
      @Nullable String approvalRef,
      @Nullable Integer expectedVersion) {}

  public record StateResult(
      String requestId, String locationCode, String state, int version, Instant at) {}

  public record Page(List<LocationRow> items, @Nullable String nextCursor) {}

  private final LocationStore store;
  private final RequestIdempotency idempotency;
  private final EventPublisher events;
  private final EventEnvelopeCodec codec;
  private final Clock clock;

  public LocationService(
      LocationStore store,
      RequestIdempotency idempotency,
      EventPublisher events,
      EventEnvelopeCodec codec,
      Clock clock) {
    this.store = store;
    this.idempotency = idempotency;
    this.events = events;
    this.codec = codec;
    this.clock = clock;
  }

  // ---------- 조회 ----------

  @Transactional(readOnly = true)
  public LocationRow get(String cdcId, String code) {
    Operators.requireCenter(cdcId);
    LocationCode.parse(code)
        .filter(p -> p.center().equals(cdcId))
        .orElseThrow(() -> PmartException.notFound("NOT_FOUND", "위치 " + code + " 가 없습니다."));
    return store
        .find(code)
        .orElseThrow(() -> PmartException.notFound("NOT_FOUND", "위치 " + code + " 가 없습니다."));
  }

  /** 내부 일괄 조회(0.4.0, cdc-inventory 할당의 선반 먼저) — 바꾸는 것 없음. */
  @Transactional(readOnly = true)
  public List<LocationRow> lookup(String centerId, List<String> codes) {
    if (codes.isEmpty() || codes.size() > MAX_BATCH) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED, "locationCodes 는 1~" + MAX_BATCH + "개여야 합니다.");
    }
    return store.findAll(centerId, new java.util.LinkedHashSet<>(codes));
  }

  @Transactional(readOnly = true)
  public Page list(Filter f, @Nullable String cursor, int size) {
    Operators.requireCenter(f.centerId());
    List<LocationRow> rows = store.list(f, cursor, size + 1);
    boolean more = rows.size() > size;
    List<LocationRow> items = more ? rows.subList(0, size) : rows;
    return new Page(items, more ? items.get(items.size() - 1).locationCode() : null);
  }

  // ---------- 등록 ----------

  public Outcome<LocationRow> create(
      String requestId, String sourceSystem, String cdcId, Input in) {
    String by = Operators.requireCenter(cdcId);
    if (!cdcId.equals(in.centerId())) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED,
          "본문 centerId 가 경로의 센터와 다릅니다.",
          List.of(new PmartException.Detail("centerId", "CENTER_MISMATCH")));
    }
    reject(validate(List.of(in), in.centerId(), false));
    return idempotency.execute(
        new RequestKey(sourceSystem, requestId, OP_CREATE, by),
        in,
        LocationRow.class,
        () -> {
          Instant at = Instant.now(clock);
          if (!store.existing(List.of(in.locationCode())).isEmpty()) {
            throw exists(
                List.of(new PmartException.Detail("locationCode", "LOCATION_CODE_EXISTS")));
          }
          LocationRow row = row(in, "DRAFT", null, at);
          store.insertAll(List.of(row));
          store.insertHistory(
              List.of(row.locationCode()),
              "CREATE",
              null,
              "DRAFT",
              null,
              null,
              by,
              requestId,
              1,
              at);
          return row;
        });
  }

  public Outcome<BatchResult> createBatch(String requestId, String sourceSystem, Batch b) {
    if (b.approvalRef() == null || b.approvalRef().isBlank()) {
      throw new PmartException(
          "APPROVAL_REQUIRED",
          HttpStatus.UNPROCESSABLE_CONTENT,
          "센터 기준정보 승인 근거(approvalRef)가 필요합니다.",
          false,
          List.of());
    }
    if (b.items().isEmpty() || b.items().size() > MAX_BATCH) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "items 는 1~" + MAX_BATCH + "행이어야 합니다.");
    }
    reject(validate(b.items(), b.centerId(), true));
    String by = PmartPrincipal.current().map(PmartPrincipal::subject).orElse(sourceSystem);
    return idempotency.execute(
        new RequestKey(sourceSystem, requestId, OP_BATCH, by),
        b,
        BatchResult.class,
        () -> {
          Instant at = Instant.now(clock);
          List<String> codes = b.items().stream().map(Input::locationCode).toList();
          List<String> taken = store.existing(codes);
          if (!taken.isEmpty()) {
            Set<String> t = new HashSet<>(taken);
            List<PmartException.Detail> d = new ArrayList<>();
            for (int i = 0; i < codes.size(); i++) {
              if (t.contains(codes.get(i))) {
                d.add(new PmartException.Detail("items[" + i + "]", "LOCATION_CODE_EXISTS"));
              }
            }
            throw exists(d);
          }
          String state = b.activate() ? "ACTIVE" : "DRAFT";
          int version = b.activate() ? 2 : 1;
          List<LocationRow> rows = new ArrayList<>();
          for (Input in : b.items()) {
            LocationRow r = row(in, "DRAFT", b.approvalRef(), at);
            rows.add(b.activate() ? withState(r, state, version) : r);
          }
          store.insertAll(rows);
          store.insertHistory(
              codes, "CREATE", null, "DRAFT", null, b.approvalRef(), by, requestId, 1, at);
          if (b.activate()) {
            store.insertHistory(
                codes,
                "ACTIVATE",
                "DRAFT",
                "ACTIVE",
                "일괄 등록",
                b.approvalRef(),
                by,
                requestId,
                2,
                at);
          }
          return new BatchResult(requestId, b.approvalRef(), rows.size(), at);
        });
  }

  // ---------- 상태 전환 ----------

  public Outcome<StateResult> changeState(
      String requestId, String sourceSystem, String cdcId, StateChange c) {
    String by = Operators.requireCenter(cdcId);
    LocationCode.parse(c.locationCode())
        .filter(p -> p.center().equals(cdcId))
        .orElseThrow(
            () -> PmartException.notFound("NOT_FOUND", "위치 " + c.locationCode() + " 가 없습니다."));
    if ("RETIRE".equals(c.action()) && (c.approvalRef() == null || c.approvalRef().isBlank())) {
      throw new PmartException(
          "APPROVAL_REQUIRED",
          HttpStatus.UNPROCESSABLE_CONTENT,
          "폐기(RETIRE)에는 승인 근거가 필요합니다.",
          false,
          List.of());
    }
    return idempotency.execute(
        new RequestKey(sourceSystem, requestId, OP_STATE, by),
        c,
        StateResult.class,
        () -> {
          Instant at = Instant.now(clock);
          LocationRow cur =
              store
                  .find(c.locationCode())
                  .orElseThrow(
                      () ->
                          PmartException.notFound(
                              "NOT_FOUND", "위치 " + c.locationCode() + " 가 없습니다."));
          if (c.expectedVersion() != null && c.expectedVersion() != cur.version()) {
            throw PmartException.versionConflict(
                "위치가 이미 바뀌었습니다(기대 버전 " + c.expectedVersion() + ", 현재 " + cur.version() + ").");
          }
          String to =
              LocationStates.next(cur.state(), c.action())
                  .orElseThrow(
                      () ->
                          PmartException.invalidState(
                              "상태 "
                                  + cur.state()
                                  + " 에서는 "
                                  + c.action()
                                  + " 할 수 없습니다"
                                  + ("RETIRE".equals(c.action())
                                      ? "(폐기는 DRAFT 만 — 점유를 확인할 길이 아직 없음)."
                                      : ".")));
          if (!store.changeState(c.locationCode(), to, cur.version(), at)) {
            throw PmartException.versionConflict("위치가 방금 다른 요청으로 바뀌었습니다. 다시 조회하세요.");
          }
          int version = cur.version() + 1;
          store.insertHistory(
              List.of(c.locationCode()),
              c.action(),
              cur.state(),
              to,
              c.reason(),
              c.approvalRef(),
              by,
              requestId,
              version,
              at);
          publish(cur, to, c.reason(), version, by, at);
          return new StateResult(requestId, c.locationCode(), to, version, at);
        });
  }

  private void publish(
      LocationRow cur, String to, String reason, int version, String by, Instant at) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("cdcId", cur.centerId());
    p.put("locCode", cur.locationCode());
    p.put("state", to);
    p.put("previousState", cur.state());
    p.put("reason", reason);
    p.put("temperatureZone", cur.temperatureZone());
    p.put("locationType", cur.locationType());
    p.put("changedAt", at.toString());
    events.publish(
        codec.create(
            events.sourceSystem(),
            "LocStateChanged",
            1,
            "Location",
            cur.locationCode(),
            version,
            new EventEnvelope.Actor(EventEnvelope.ActorType.OPERATOR, by),
            EventEnvelope.EventSource.REAL,
            p),
        TOPIC);
  }

  // ---------- 검사 ----------

  /** 행 검사(코드 문법·같은 코드 두 번·센터 일치). */
  static List<PmartException.Detail> validate(List<Input> items, String centerId, boolean batch) {
    List<PmartException.Detail> bad = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < items.size(); i++) {
      Input in = items.get(i);
      String prefix = batch ? "items[" + i + "]." : "";
      if (!in.centerId().equals(centerId)) {
        bad.add(new PmartException.Detail(prefix + "centerId", "CENTER_MISMATCH"));
      }
      if (!seen.add(in.locationCode())) {
        bad.add(new PmartException.Detail(prefix + "locationCode", "DUPLICATE"));
      }
      for (Violation v :
          LocationCode.check(
              in.locationCode(),
              in.centerId(),
              in.temperatureZone(),
              in.locationType(),
              in.pickFaceMinQty(),
              in.pickFaceTargetQty())) {
        bad.add(new PmartException.Detail(prefix + v.field(), v.rule()));
      }
    }
    return bad;
  }

  private static void reject(List<PmartException.Detail> bad) {
    if (!bad.isEmpty()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "위치 규칙에 맞지 않습니다.", bad);
    }
  }

  private static PmartException exists(List<PmartException.Detail> d) {
    return new PmartException(
        "LOCATION_CODE_EXISTS", HttpStatus.CONFLICT, "이미 있는 위치 코드가 있습니다.", false, d);
  }

  private static LocationRow row(Input in, String state, @Nullable String approvalRef, Instant at) {
    Parts p = LocationCode.parse(in.locationCode()).orElseThrow();
    return new LocationRow(
        in.locationCode(),
        in.centerId(),
        p.area(),
        p.floor(),
        p.aisle(),
        p.bay(),
        p.level(),
        p.slot(),
        in.temperatureZone(),
        in.locationType(),
        in.capacityUnit(),
        in.maxQty(),
        in.maxWeightG() == null ? null : in.maxWeightG().longValue(),
        in.restrictions(),
        in.pickFaceMinQty(),
        in.pickFaceTargetQty(),
        state,
        1,
        approvalRef,
        at,
        at);
  }

  private static LocationRow withState(LocationRow r, String state, int version) {
    return new LocationRow(
        r.locationCode(),
        r.centerId(),
        r.area(),
        r.floor(),
        r.aisle(),
        r.bay(),
        r.level(),
        r.slot(),
        r.temperatureZone(),
        r.locationType(),
        r.capacityUnit(),
        r.maxQty(),
        r.maxWeightG(),
        r.restrictions(),
        r.pickFaceMinQty(),
        r.pickFaceTargetQty(),
        state,
        version,
        r.approvalRef(),
        r.createdAt(),
        r.updatedAt());
  }
}
