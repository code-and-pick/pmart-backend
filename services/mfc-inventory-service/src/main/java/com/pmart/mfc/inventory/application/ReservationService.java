package com.pmart.mfc.inventory.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.web.CommandResponse;
import com.pmart.common.web.CommandStatus;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.inventory.application.Ledger.ReservedChange;
import com.pmart.mfc.inventory.domain.LotAllocator;
import com.pmart.mfc.inventory.domain.LotAllocator.Allocation;
import com.pmart.mfc.inventory.domain.LotAllocator.LineRequest;
import com.pmart.mfc.inventory.domain.LotAllocator.LineShortage;
import com.pmart.mfc.inventory.domain.LotAllocator.LotCandidate;
import com.pmart.mfc.inventory.domain.LotAllocator.ShelfRule;
import com.pmart.mfc.inventory.domain.ReservationState;
import com.pmart.mfc.inventory.domain.ReservationState.Transition;
import com.pmart.mfc.inventory.domain.SkuProjection;
import com.pmart.mfc.inventory.domain.StockRules;
import com.pmart.mfc.inventory.domain.StockRules.Balance;
import com.pmart.mfc.inventory.domain.StockRules.Line;
import com.pmart.mfc.inventory.domain.StockRules.Shortage;
import com.pmart.mfc.inventory.infrastructure.ReservationStore;
import com.pmart.mfc.inventory.infrastructure.ReservationStore.PartRow;
import com.pmart.mfc.inventory.infrastructure.ReservationStore.ReservationRow;
import com.pmart.mfc.inventory.infrastructure.SkuHandlingStore;
import com.pmart.mfc.inventory.infrastructure.SkuHandlingStore.HandlingRow;
import com.pmart.mfc.inventory.infrastructure.SkuProjectionStore;
import com.pmart.mfc.inventory.infrastructure.StockStore;
import com.pmart.mfc.inventory.infrastructure.StockStore.LotBalance;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재고 예약(S1 a, IV-05, ADR-0040 결정 4·5): 예약 → 확정 / 해제 / 만료. 모든 명령은 {@link RequestIdempotency} 안에서 한
 * 트랜잭션이다.
 *
 * <ul>
 *   <li>예약: 같은 SKU 를 합산하고, 취급 OFF 면 SKU_NOT_HANDLED, 센터 요약을 SKU 오름차순으로 잠근 뒤 한 SKU 라도 A 가 모자라면
 *       INSUFFICIENT_STOCK 으로 전체 거절(TC-09). 그다음 LOT 요약을 잠그고 줄마다 적격 LOT 을 FEFO 로 배정한다(줄당 LOT 수 제한
 *       없음, 최소 잔여일 — 배송 약속일 기준). 적격 LOT 이 모자란 줄이 있으면 전체 거절(사유 NO_ELIGIBLE_LOT). 거절은 예외라 멱등 기록에 남지
 *       않는다 — 같은 키로 다시 보내면 그때 잔량으로 다시 판정한다.
 *   <li>잠금 순서는 예약 행 → 센터 요약(SKU 오름차순) → LOT 요약. 만료 작업도 같은 순서라 교착이 없다.
 *   <li>확정은 R 총량 불변, 해제·만료는 조각마다 R−q.
 * </ul>
 */
@Service
public class ReservationService {

  /** 집품이 시작된 예약의 단순 해제(IV-08 전). */
  public static final String RESERVATION_PICKED = "RESERVATION_PICKED";

  public static final String RESERVATION_NOT_FOUND = "RESERVATION_NOT_FOUND";
  public static final String PARTIAL_RELEASE_NOT_SUPPORTED = "PARTIAL_RELEASE_NOT_SUPPORTED";
  public static final String NO_ELIGIBLE_LOT = "NO_ELIGIBLE_LOT";
  public static final String RESERVATION_SHORT = "RESERVATION_SHORT";

  /** 계약 InventoryHoldExpired.v1 의 토픽(집합 이름이 아니라 서비스 토픽). */
  public static final String TOPIC = "mfc.inventory.v1";

  /** 날짜 판정은 Asia/Seoul(ADR-0010). */
  static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");

  static final String OP_RESERVE = "reserveInventory";
  static final String OP_CONFIRM = "confirmReservation";
  static final String OP_RELEASE = "releaseReservation";

  private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

  /** 예약 줄. */
  public record ReserveLine(@Nullable String orderLineId, String sku, int qty) {}

  /** 예약 명령. lotConstraint 는 아직 받지 않는다(api 가 400). promisedDate 가 없으면 예약 날짜(즉시 배송). */
  public record ReserveCommand(
      String orderId,
      String mfcId,
      @Nullable Integer ttlMinutes,
      @Nullable LocalDate promisedDate,
      List<ReserveLine> lines) {}

  /** 멱등 비교용(경로 변수 포함). */
  record ConfirmRequest(
      String reservationId,
      String orderId,
      @Nullable String paymentAttemptId,
      @Nullable Integer expectedVersion) {}

  record ReleaseRequest(
      String reservationId,
      String reason,
      @Nullable String sourceRef,
      @Nullable List<Line> lines,
      @Nullable Integer expectedVersion) {}

  /** 조회 결과. */
  public record ReservationView(ReservationRow row, List<PartRow> parts) {}

  private final ReservationStore reservations;
  private final StockStore stock;
  private final Ledger ledger;
  private final SkuHandlingStore handlings;
  private final SkuProjectionStore skus;
  private final InventoryProperties properties;
  private final RequestIdempotency idempotency;
  private final EventPublisher events;
  private final EventEnvelopeCodec codec;
  private final Clock clock;

  public ReservationService(
      ReservationStore reservations,
      StockStore stock,
      Ledger ledger,
      SkuHandlingStore handlings,
      SkuProjectionStore skus,
      InventoryProperties properties,
      RequestIdempotency idempotency,
      EventPublisher events,
      EventEnvelopeCodec codec,
      Clock clock) {
    this.reservations = reservations;
    this.stock = stock;
    this.ledger = ledger;
    this.handlings = handlings;
    this.skus = skus;
    this.properties = properties;
    this.idempotency = idempotency;
    this.events = events;
    this.codec = codec;
    this.clock = clock;
  }

  // ---------- 예약 ----------

  public CommandResponse reserve(String requestId, String sourceSystem, ReserveCommand cmd) {
    SortedMap<String, Integer> requested;
    try {
      requested =
          StockRules.sumBySku(cmd.lines().stream().map(l -> new Line(l.sku(), l.qty())).toList());
    } catch (IllegalArgumentException | ArithmeticException e) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, e.getMessage());
    }
    if (requested.isEmpty()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "lines 가 비어 있습니다.");
    }
    int ttl = cmd.ttlMinutes() == null ? defaultTtlMinutes() : cmd.ttlMinutes();
    return idempotency
        .execute(
            Callers.key(sourceSystem, requestId, OP_RESERVE),
            cmd,
            CommandResponse.class,
            () -> {
              requireHandled(cmd.mfcId(), requested);
              Map<String, Balance> locked = stock.lockBalances(cmd.mfcId(), requested.keySet());
              List<Shortage> shortages = StockRules.shortages(requested, locked);
              if (!shortages.isEmpty()) {
                throw insufficient(shortages);
              }
              Instant at = clock.instant();
              LocalDate promised =
                  cmd.promisedDate() != null
                      ? cmd.promisedDate()
                      : LocalDate.ofInstant(at, BUSINESS_ZONE);
              List<LotCandidate> lots = new ArrayList<>();
              for (LotBalance b : stock.lockLotBalances(cmd.mfcId(), requested.keySet())) {
                lots.add(
                    new LotCandidate(
                        b.sku(),
                        b.lotId(),
                        b.expiryDate(),
                        b.manufactureDate(),
                        b.firstNormalAt(),
                        b.available()));
              }
              Map<String, SkuProjection> projections = skus.findAll(requested.keySet());
              LotAllocator.Rules rules = shelfRules(requested.keySet(), projections);
              List<String> missing = LotAllocator.missingPolicy(lots, rules);
              if (!missing.isEmpty()) {
                throw PmartException.configRequired(
                    "pmart.inventory.min-remaining-days(보관 유형 "
                        + storageTypes(missing, projections)
                        + ", SKU "
                        + missing
                        + ")");
              }
              LotAllocator.Result result =
                  LotAllocator.allocate(
                      cmd.lines().stream()
                          .map(l -> new LineRequest(l.orderLineId(), l.sku(), l.qty()))
                          .toList(),
                      lots,
                      promised,
                      rules);
              if (!result.shortages().isEmpty()) {
                throw noEligibleLot(result.shortages());
              }
              String reservationId = "RSV-" + UUID.randomUUID();
              int total = requested.values().stream().mapToInt(Integer::intValue).sum();
              reservations.insert(
                  reservationId,
                  cmd.orderId(),
                  cmd.mfcId(),
                  at.plus(Duration.ofMinutes(ttl)),
                  cmd.promisedDate(),
                  sourceSystem,
                  requestId,
                  at);
              List<ReservedChange> deltas = new ArrayList<>();
              int n = 0;
              for (Allocation al : result.allocations()) {
                n++;
                reservations.insertPart(
                    reservationId,
                    new PartRow(
                        reservationId + ":" + n,
                        al.orderLineId(),
                        al.sku(),
                        al.lotId(),
                        "HAND",
                        al.qty(),
                        ReservationState.HELD,
                        null),
                    at);
                deltas.add(new ReservedChange(al.sku(), al.lotId(), al.qty()));
              }
              ledger.post(
                  "RESERVE",
                  cmd.mfcId(),
                  requestId,
                  sourceSystem,
                  reservationId,
                  total,
                  List.of(),
                  deltas,
                  at);
              log.info(
                  "reservation {} HELD for order {} at {} ({} units in {} parts, ttl {}m)",
                  reservationId,
                  cmd.orderId(),
                  cmd.mfcId(),
                  total,
                  n,
                  ttl);
              return new CommandResponse(
                  CommandStatus.APPLIED, requestId, reservationId, total, 1, false, null, at);
            })
        .value();
  }

  /** 요청에 유효시간이 없을 때(Q-04 결정 2026-11-07: 10분, ADR-0042). 설정이 없으면 CONFIG_REQUIRED. */
  private int defaultTtlMinutes() {
    Duration d = properties.getDefaultHoldTtl();
    if (d == null || d.toMinutes() < 1) {
      throw PmartException.configRequired("pmart.inventory.default-hold-ttl(예약 유효시간)");
    }
    return (int) d.toMinutes();
  }

  /**
   * SKU → 소비기한 기준(ADR-0041). 최소 잔여일은 상품 보장(shelfMinRemainingDays)을 먼저 쓰고, 없으면 보관 유형 기본값 ({@code
   * pmart.inventory.min-remaining-days}). 둘 다 없으면 미설정(null — 기한 있는 LOT 은 CONFIG_REQUIRED). 제조 후
   * 경과일은 상품 보장만.
   */
  /**
   * S4 예약 시간 변경 — 배송 약속일 바꾸기(2026-11-16). 지금 잡힌 LOT 조각이 새 약속일에도 적격일 때만 바꾼다. 미달이면 422
   * LOT_EXPIRY_NOT_MET(예약 그대로 — OMS "새 출고일에 미달 → 시간 변경 실패·기존 유효 예약 유지").
   */
  public CommandResponse changePromisedDate(
      String requestId,
      String sourceSystem,
      String reservationId,
      String orderId,
      LocalDate promisedDate) {
    return idempotency
        .execute(
            new RequestIdempotency.RequestKey(
                sourceSystem, requestId, "changeReservationPromisedDate", "system"),
            Map.of(
                "reservationId", reservationId,
                "orderId", orderId,
                "promisedDate", promisedDate.toString()),
            CommandResponse.class,
            () -> {
              Instant now = clock.instant();
              ReservationRow r =
                  reservations
                      .lock(reservationId)
                      .orElseThrow(
                          () ->
                              PmartException.notFound(
                                  "RESERVATION_NOT_FOUND", "예약이 없습니다: " + reservationId));
              if (!r.orderId().equals(orderId)) {
                throw new PmartException(
                    com.pmart.common.web.ErrorCode.VALIDATION_FAILED, "다른 주문의 예약입니다.");
              }
              if (r.state() != ReservationState.HELD && r.state() != ReservationState.CONFIRMED) {
                throw PmartException.invalidState("활성 예약이 아닙니다(" + r.state() + ").");
              }
              if (promisedDate.equals(r.promisedDate())) {
                return new CommandResponse(
                    com.pmart.common.web.CommandStatus.APPLIED,
                    requestId,
                    reservationId,
                    null,
                    r.version(),
                    false,
                    null,
                    now);
              }
              List<ReservationStore.PartRow> parts =
                  reservations.parts(reservationId).stream()
                      .filter(
                          p ->
                              p.state() == ReservationState.HELD
                                  || p.state() == ReservationState.CONFIRMED)
                      .toList();
              java.util.Set<String> skuIds = new java.util.LinkedHashSet<>();
              parts.forEach(p -> skuIds.add(p.sku()));
              LotAllocator.Rules rules = shelfRules(skuIds, skus.findAll(List.copyOf(skuIds)));
              Map<String, StockStore.LotRow> lots =
                  stock.lots(parts.stream().map(ReservationStore.PartRow::lotId).toList());
              List<PmartException.Detail> bad = new ArrayList<>();
              for (ReservationStore.PartRow p : parts) {
                StockStore.LotRow l = lots.get(p.lotId());
                LotAllocator.LotCandidate c =
                    new LotAllocator.LotCandidate(
                        p.sku(),
                        p.lotId(),
                        l == null ? null : l.expiryDate(),
                        l == null ? null : l.manufactureDate(),
                        null,
                        1);
                if (!LotAllocator.eligible(c, promisedDate, rules)) {
                  bad.add(new PmartException.Detail(p.sku(), p.lotId()));
                }
              }
              if (!bad.isEmpty()) {
                throw new PmartException(
                    "LOT_EXPIRY_NOT_MET",
                    org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT,
                    "잡힌 LOT 이 새 배송 약속일의 소비기한 기준에 미달합니다(" + bad.size() + "건).",
                    false,
                    bad);
              }
              reservations.setPromisedDate(reservationId, promisedDate, now);
              log.info("예약 {} 배송 약속일 {} → {}", reservationId, r.promisedDate(), promisedDate);
              return new CommandResponse(
                  com.pmart.common.web.CommandStatus.APPLIED,
                  requestId,
                  reservationId,
                  null,
                  r.version() + 1,
                  false,
                  null,
                  now);
            })
        .value();
  }

  private LotAllocator.Rules shelfRules(
      Collection<String> skuIds, Map<String, SkuProjection> projections) {
    Map<String, ShelfRule> out = new LinkedHashMap<>();
    for (String sku : skuIds) {
      SkuProjection p = projections.get(sku);
      if (p == null) {
        continue;
      }
      Integer storageDefault =
          p.storageType() == null ? null : properties.getMinRemainingDays().get(p.storageType());
      boolean guaranteed = p.shelfMinRemainingDays() != null;
      out.put(
          sku,
          new ShelfRule(
              guaranteed ? p.shelfMinRemainingDays() : storageDefault,
              guaranteed,
              p.shelfMaxDaysSinceManufacture()));
    }
    return sku -> out.getOrDefault(sku, ShelfRule.storageDefault(null));
  }

  private static String storageTypes(List<String> skuIds, Map<String, SkuProjection> projections) {
    return skuIds.stream()
        .map(s -> projections.get(s) == null ? null : projections.get(s).storageType())
        .map(t -> t == null ? "미상" : t)
        .distinct()
        .sorted()
        .toList()
        .toString();
  }

  private void requireHandled(String mfcId, SortedMap<String, Integer> requested) {
    Map<String, HandlingRow> rows = handlings.findAll(mfcId, requested.keySet());
    List<PmartException.Detail> off = new ArrayList<>();
    for (String sku : requested.keySet()) {
      HandlingRow r = rows.get(sku);
      if (r == null || !r.enabled()) {
        off.add(new PmartException.Detail(sku, "HANDLING_OFF"));
      }
    }
    if (!off.isEmpty()) {
      throw new PmartException(
          StockRules.SKU_NOT_HANDLED,
          HttpStatus.UNPROCESSABLE_CONTENT,
          off.size() + "개 SKU 가 이 센터에서 취급 OFF 입니다.",
          false,
          off);
    }
  }

  private static PmartException insufficient(List<Shortage> shortages) {
    List<PmartException.Detail> details =
        shortages.stream()
            .map(
                s ->
                    new PmartException.Detail(
                        s.sku(),
                        "requestedQty=" + s.requestedQty() + ";availableQty=" + s.availableQty()))
            .toList();
    return new PmartException(
        ErrorCode.INSUFFICIENT_STOCK, shortages.size() + "개 SKU 의 가용 재고가 부족합니다.", details);
  }

  private static PmartException noEligibleLot(List<LineShortage> shortages) {
    List<PmartException.Detail> details =
        shortages.stream()
            .map(
                s ->
                    new PmartException.Detail(
                        s.sku(),
                        NO_ELIGIBLE_LOT
                            + ";orderLineId="
                            + s.orderLineId()
                            + ";requestedQty="
                            + s.requestedQty()
                            + ";eligibleQty="
                            + s.eligibleQty()))
            .toList();
    return new PmartException(
        ErrorCode.INSUFFICIENT_STOCK, shortages.size() + "개 줄에 소비기한 기준을 통과한 재고가 부족합니다.", details);
  }

  // ---------- 확정 ----------

  public CommandResponse confirm(
      String requestId,
      String sourceSystem,
      String reservationId,
      String orderId,
      @Nullable String paymentAttemptId,
      @Nullable Integer expectedVersion) {
    return idempotency
        .execute(
            Callers.key(sourceSystem, requestId, OP_CONFIRM),
            new ConfirmRequest(reservationId, orderId, paymentAttemptId, expectedVersion),
            CommandResponse.class,
            () -> {
              ReservationRow r = lockOrThrow(reservationId);
              if (!r.orderId().equals(orderId)) {
                throw PmartException.invalidState(
                    "예약 " + reservationId + " 는 다른 주문(" + r.orderId() + ")의 예약입니다.");
              }
              Instant at = clock.instant();
              Transition t = r.state().confirm();
              if (t == Transition.INVALID) {
                throw PmartException.invalidState(
                    "예약 " + reservationId + " 는 " + r.state() + " 상태라 확정할 수 없습니다.");
              }
              if (t == Transition.ALREADY) {
                return new CommandResponse(
                    CommandStatus.APPLIED,
                    requestId,
                    reservationId,
                    0,
                    r.version(),
                    false,
                    null,
                    at);
              }
              checkVersion(r, expectedVersion);
              // 격리·실사 손실로 보류된(재확보 못 한) 몫이 있으면 확정하지 않는다 — 결제 보상(사가 RESERVATION_LOST)으로 간다
              int short_ =
                  reservations.parts(reservationId).stream()
                      .filter(p -> p.state() == ReservationState.BLOCKED)
                      .mapToInt(PartRow::qty)
                      .sum();
              if (short_ > 0) {
                throw new PmartException(
                    RESERVATION_SHORT,
                    HttpStatus.CONFLICT,
                    "예약 " + reservationId + " 의 " + short_ + "개가 재고 부족(격리·실사 손실)으로 보류돼 확정할 수 없습니다.",
                    false,
                    List.of());
              }
              int total =
                  reservations.heldBySku(reservationId).values().stream()
                      .mapToInt(Integer::intValue)
                      .sum();
              int version = r.version() + 1;
              reservations.transition(reservationId, ReservationState.CONFIRMED, null, version, at);
              reservations.transitionParts(
                  reservationId, ReservationState.HELD, ReservationState.CONFIRMED, at);
              ledger.record(
                  "CONFIRM", r.mfcId(), requestId, sourceSystem, reservationId, total, at);
              log.info("reservation {} CONFIRMED (order {})", reservationId, orderId);
              return new CommandResponse(
                  CommandStatus.APPLIED, requestId, reservationId, total, version, false, null, at);
            })
        .value();
  }

  // ---------- 해제 ----------

  public CommandResponse release(
      String requestId,
      String sourceSystem,
      String reservationId,
      String reason,
      @Nullable String sourceRef,
      @Nullable List<Line> lines,
      @Nullable Integer expectedVersion) {
    return idempotency
        .execute(
            Callers.key(sourceSystem, requestId, OP_RELEASE),
            new ReleaseRequest(reservationId, reason, sourceRef, lines, expectedVersion),
            CommandResponse.class,
            () -> {
              ReservationRow r = lockOrThrow(reservationId);
              Instant at = clock.instant();
              Transition rt = r.state().release();
              if (rt == Transition.INVALID) {
                throw PmartException.invalidState(
                    "예약 " + reservationId + " 는 " + r.state() + " 상태라 해제할 수 없습니다.");
              }
              if (rt == Transition.ALREADY) {
                return new CommandResponse(
                    CommandStatus.APPLIED,
                    requestId,
                    reservationId,
                    0,
                    r.version(),
                    false,
                    null,
                    at);
              }
              if (reservations.anyPicked(reservationId)) {
                // 집품 뒤 취소는 판매 제외(CANCEL_RETURN_PENDING)·복귀로 처리해야 한다(IV-08) — 단순 해제로 R 만 줄이지 않는다
                throw new PmartException(
                    RESERVATION_PICKED,
                    HttpStatus.CONFLICT,
                    "예약 " + reservationId + " 는 집품이 시작돼 해제할 수 없습니다(집품 후 취소는 IV-08).",
                    false,
                    List.of());
              }
              Map<String, Integer> held = reservations.heldBySku(reservationId);
              Map<String, Integer> releasedLines = new LinkedHashMap<>(held);
              if (!StockRules.isFullRelease(lines, held)) {
                throw new PmartException(
                    PARTIAL_RELEASE_NOT_SUPPORTED,
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "부분 해제는 아직 지원하지 않습니다. lines 를 비우거나 예약 전량을 지정하세요.",
                    false,
                    List.of());
              }
              checkVersion(r, expectedVersion);
              int version = r.version() + 1;
              int total =
                  unreserve(
                      r, ReservationState.RELEASED, reason, version, requestId, sourceSystem, at);
              publishReleased(r, reason, releasedLines, version, at);
              log.info("reservation {} RELEASED ({}, {} units)", reservationId, reason, total);
              return new CommandResponse(
                  CommandStatus.APPLIED, requestId, reservationId, total, version, false, null, at);
            })
        .value();
  }

  // ---------- 만료(주기 작업이 트랜잭션 안에서 호출) ----------

  /**
   * 만료 시각이 지난 HELD 예약 일부를 EXPIRED 로 바꾸고 InventoryHoldExpired 를 Outbox 에 쓴다.
   *
   * @return 처리한 예약 수
   */
  @Transactional
  public int expireDue(int limit) {
    Instant now = clock.instant();
    List<ReservationRow> due = reservations.lockExpired(now, limit);
    for (ReservationRow r : due) {
      int version = r.version() + 1;
      int total =
          unreserve(r, ReservationState.EXPIRED, "TTL", version, null, events.sourceSystem(), now);
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("reservationId", r.reservationId());
      payload.put("orderId", r.orderId());
      payload.put("mfcId", r.mfcId());
      payload.put("expiredAt", now.toString());
      EventEnvelope envelope =
          codec.create(
              events.sourceSystem(),
              "InventoryHoldExpired",
              1,
              "Reservation",
              r.reservationId(),
              version,
              new EventEnvelope.Actor(EventEnvelope.ActorType.SYSTEM, events.sourceSystem()),
              EventEnvelope.EventSource.REAL,
              payload);
      events.publish(envelope, TOPIC);
      log.info(
          "reservation {} EXPIRED (order {}, {} units, expiresAt {})",
          r.reservationId(),
          r.orderId(),
          total,
          r.expiresAt());
    }
    return due.size();
  }

  /** 현물 점유 조각(HELD·CONFIRMED)을 to 로 바꾸고 LOT·센터 요약에서 R−q, 확정 단위 기록. */
  private int unreserve(
      ReservationRow r,
      ReservationState to,
      String reason,
      int version,
      @Nullable String requestId,
      String sourceSystem,
      Instant at) {
    List<ReservedChange> deltas = new ArrayList<>();
    int total = 0;
    for (PartRow p : reservations.parts(r.reservationId())) {
      if (p.state().holdsStock()) {
        deltas.add(new ReservedChange(p.sku(), p.lotId(), -p.qty()));
        total += p.qty();
      }
    }
    ledger.post(
        to == ReservationState.EXPIRED ? "EXPIRE" : "RELEASE",
        r.mfcId(),
        requestId,
        sourceSystem,
        r.reservationId(),
        total,
        List.of(),
        deltas,
        at);
    reservations.transition(r.reservationId(), to, reason, version, at);
    reservations.transitionParts(r.reservationId(), ReservationState.HELD, to, at);
    reservations.transitionParts(r.reservationId(), ReservationState.CONFIRMED, to, at);
    return total;
  }

  // ---------- 집품 뒤 취소(IV-08, ADR-0046) ----------

  /** 취소 복귀 한 줄(fulfillment 가 위치별로 적는다). */
  public record ReturnLine(
      String orderLineId,
      String sku,
      String lotId,
      String locationCode,
      String workState,
      int qty) {}

  public record CancelReturn(String reservationId, String reason, List<ReturnLine> returns) {}

  /**
   * 집품 뒤 취소. returns(위치별 PICKED·PACKED)가 조각의 집품·포장량과 정확히 같아야 한다 — 실물을 빠뜨리지 않게. 단위는 취소 복귀 대기(E−q),
   * 예약은 전부 해제(R−q, BLOCKED 까지 RELEASED). 이미 끝난 예약은 변화 없는 성공.
   */
  public CommandResponse cancelReturn(String requestId, String sourceSystem, CancelReturn in) {
    return idempotency
        .execute(
            Callers.key(sourceSystem, requestId, "cancelReturn"),
            in,
            CommandResponse.class,
            () -> {
              ReservationRow r = lockOrThrow(in.reservationId());
              Instant at = clock.instant();
              if (r.state() == ReservationState.RELEASED || r.state() == ReservationState.EXPIRED) {
                return new CommandResponse(
                    CommandStatus.APPLIED,
                    requestId,
                    r.reservationId(),
                    0,
                    r.version(),
                    false,
                    null,
                    at);
              }
              List<ReservationStore.PickPart> parts =
                  reservations.pickParts(r.reservationId(), true);
              Map<String, Integer> picked = new LinkedHashMap<>();
              Map<String, Integer> packed = new LinkedHashMap<>();
              for (ReservationStore.PickPart p : parts) {
                String k = p.orderLineId() + "|" + p.lotId();
                picked.merge(k, p.pickedQty() - p.packedQty(), Integer::sum);
                packed.merge(k, p.packedQty(), Integer::sum);
              }
              Map<String, Integer> gotPicked = new LinkedHashMap<>();
              Map<String, Integer> gotPacked = new LinkedHashMap<>();
              List<com.pmart.mfc.inventory.domain.StockUnit.Change> units = new ArrayList<>();
              for (ReturnLine l : in.returns()) {
                String k = l.orderLineId() + "|" + l.lotId();
                var work =
                    "PACKED".equals(l.workState())
                        ? com.pmart.mfc.inventory.domain.StockUnit.Work.PACKED
                        : com.pmart.mfc.inventory.domain.StockUnit.Work.PICKED;
                (work == com.pmart.mfc.inventory.domain.StockUnit.Work.PACKED
                        ? gotPacked
                        : gotPicked)
                    .merge(k, l.qty(), Integer::sum);
                var from =
                    new com.pmart.mfc.inventory.domain.StockUnit.Key(
                        l.locationCode(),
                        l.sku(),
                        l.lotId(),
                        com.pmart.mfc.inventory.domain.StockUnit.Quality.NORMAL,
                        work);
                units.add(new com.pmart.mfc.inventory.domain.StockUnit.Change(from, -l.qty()));
                units.add(
                    new com.pmart.mfc.inventory.domain.StockUnit.Change(
                        from.with(
                            com.pmart.mfc.inventory.domain.StockUnit.Quality.NORMAL,
                            com.pmart.mfc.inventory.domain.StockUnit.Work.CANCEL_RETURN_PENDING),
                        l.qty()));
              }
              if (!nonZero(picked).equals(nonZero(gotPicked))
                  || !nonZero(packed).equals(nonZero(gotPacked))) {
                throw new PmartException(
                    "RETURN_MISMATCH",
                    HttpStatus.CONFLICT,
                    "취소 복귀 수량이 집품·포장량과 다릅니다(집품 "
                        + nonZero(picked)
                        + "/보고 "
                        + nonZero(gotPicked)
                        + ", 포장 "
                        + nonZero(packed)
                        + "/보고 "
                        + nonZero(gotPacked)
                        + ").",
                    false,
                    List.of());
              }
              List<ReservedChange> deltas = new ArrayList<>();
              int total = 0;
              for (PartRow p : reservations.parts(r.reservationId())) {
                if (p.state().holdsStock()) {
                  deltas.add(new ReservedChange(p.sku(), p.lotId(), -p.qty()));
                  total += p.qty();
                }
              }
              ledger.post(
                  "CANCEL_RETURN",
                  r.mfcId(),
                  requestId,
                  sourceSystem,
                  r.reservationId(),
                  total,
                  units,
                  deltas,
                  at);
              int version = r.version() + 1;
              reservations.transition(
                  r.reservationId(), ReservationState.RELEASED, in.reason(), version, at);
              for (ReservationState s :
                  List.of(
                      ReservationState.HELD,
                      ReservationState.CONFIRMED,
                      ReservationState.BLOCKED)) {
                reservations.transitionParts(r.reservationId(), s, ReservationState.RELEASED, at);
              }
              log.info(
                  "reservation {} 집품 뒤 취소 — R {} 해제, 복귀 대기 {}줄 ({})",
                  r.reservationId(),
                  total,
                  in.returns().size(),
                  in.reason());
              return new CommandResponse(
                  CommandStatus.APPLIED,
                  requestId,
                  r.reservationId(),
                  total,
                  version,
                  false,
                  null,
                  at);
            })
        .value();
  }

  private static Map<String, Integer> nonZero(Map<String, Integer> m) {
    Map<String, Integer> out = new java.util.TreeMap<>();
    m.forEach(
        (k, v) -> {
          if (v != 0) {
            out.put(k, v);
          }
        });
    return out;
  }

  // ---------- 재확보(IV-09, ADR-0042 결정 1) ----------

  /**
   * 같은 SKU 의 적격 LOT 에서 신규 가용(A=E−R) 안으로 다시 잡는다 — 처음 배정과 같은 기준(소비기한 보장·보관 유형 최소 잔여일·배송 약속일, FEFO).
   * 다른 주문 예약은 건드리지 않는다. 다 못 잡으면 잡을 수 있는 만큼. 확정된 주문이라 조각은 바로 CONFIRMED. 호출자 트랜잭션 안(예약 행을 이미 잠근 뒤)에서
   * 부른다.
   *
   * @return 새 조각(LOT·수량). 기준 미설정·적격 없음이면 빈 목록
   */
  public List<Allocation> resecure(
      ReservationRow r,
      String orderLineId,
      String sku,
      int qty,
      String requestId,
      String sourceSystem,
      Instant at) {
    return resecure(
        r, orderLineId, sku, qty, requestId, sourceSystem, at, ReservationState.CONFIRMED);
  }

  /** 재확보 — 새 조각 상태는 원래 조각과 같게(결제 전 HELD 예약은 HELD). */
  public List<Allocation> resecure(
      ReservationRow r,
      String orderLineId,
      String sku,
      int qty,
      String requestId,
      String sourceSystem,
      Instant at,
      ReservationState partState) {
    LocalDate promised =
        r.promisedDate() != null ? r.promisedDate() : LocalDate.ofInstant(at, BUSINESS_ZONE);
    List<LotCandidate> lots = new ArrayList<>();
    for (LotBalance b : stock.lockLotBalances(r.mfcId(), List.of(sku))) {
      lots.add(
          new LotCandidate(
              b.sku(),
              b.lotId(),
              b.expiryDate(),
              b.manufactureDate(),
              b.firstNormalAt(),
              b.available()));
    }
    LotAllocator.Rules rules = shelfRules(List.of(sku), skus.findAll(List.of(sku)));
    if (!LotAllocator.missingPolicy(lots, rules).isEmpty()) {
      log.warn("재확보 {} {}: 소비기한 기준 미설정 — 재확보 안 함", r.reservationId(), sku);
      return List.of();
    }
    LotAllocator.Result res =
        LotAllocator.allocate(
            List.of(new LineRequest(orderLineId, sku, qty)), lots, promised, rules);
    if (!res.shortages().isEmpty()) {
      int eligible = res.shortages().get(0).eligibleQty();
      if (eligible <= 0) {
        return List.of();
      }
      res =
          LotAllocator.allocate(
              List.of(new LineRequest(orderLineId, sku, Math.min(eligible, qty))),
              lots,
              promised,
              rules);
    }
    List<ReservedChange> deltas = new ArrayList<>();
    int n = 0;
    for (Allocation al : res.allocations()) {
      n++;
      reservations.insertPart(
          r.reservationId(),
          new PartRow(
              r.reservationId() + ":R" + UUID.randomUUID().toString().substring(0, 8) + ":" + n,
              al.orderLineId(),
              al.sku(),
              al.lotId(),
              "HAND",
              al.qty(),
              partState,
              null),
          at);
      deltas.add(new ReservedChange(al.sku(), al.lotId(), al.qty()));
    }
    if (!deltas.isEmpty()) {
      ledger.post(
          "RESECURE",
          r.mfcId(),
          requestId,
          sourceSystem,
          r.reservationId(),
          deltas.stream().mapToInt(ReservedChange::delta).sum(),
          List.of(),
          deltas,
          at);
    }
    return res.allocations();
  }

  /**
   * InventoryReleased(mfc.inventory.v1, 2026-11-08 — 사건 목록에 있던 것, 처음 발행). 해제가 실제로 바뀐 때만(이미 끝난 해제는
   * 사건 없음). 구독: mfc-inbound(가용이 생겨 자동 보충 즉시 점검), 예정 order-orchestration·cart. 같은 트랜잭션 outbox.
   */
  private void publishReleased(
      ReservationRow r, String reason, Map<String, Integer> lines, int version, Instant at) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("reservationId", r.reservationId());
    payload.put("orderId", r.orderId());
    payload.put("mfcId", r.mfcId());
    payload.put("reason", reason);
    payload.put(
        "lines",
        lines.entrySet().stream()
            .map(e -> Map.<String, Object>of("sku", e.getKey(), "qty", e.getValue()))
            .toList());
    payload.put("releasedAt", at.toString());
    events.publish(
        codec.create(
            events.sourceSystem(),
            "InventoryReleased",
            1,
            "Reservation",
            r.reservationId(),
            version,
            new EventEnvelope.Actor(EventEnvelope.ActorType.SYSTEM, events.sourceSystem()),
            EventEnvelope.EventSource.REAL,
            payload),
        TOPIC);
  }

  /** ReservationBlocked(mfc.inventory.v1) — 못 잡은 이행부족 D. 호출자 트랜잭션 안. */
  public void publishBlocked(
      ReservationRow r, String sku, String lotId, int blockedQty, String reason, Instant at) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("reservationId", r.reservationId());
    payload.put("orderId", r.orderId());
    payload.put("mfcId", r.mfcId());
    payload.put("sku", sku);
    payload.put("lot", lotId);
    payload.put("blockedQty", blockedQty);
    payload.put("reason", reason);
    payload.put("blockedAt", at.toString());
    events.publish(
        codec.create(
            events.sourceSystem(),
            "ReservationBlocked",
            1,
            "Reservation",
            r.reservationId(),
            r.version(),
            new EventEnvelope.Actor(EventEnvelope.ActorType.SYSTEM, events.sourceSystem()),
            EventEnvelope.EventSource.REAL,
            payload),
        TOPIC);
  }

  /**
   * 이 LOT 이 오늘(Asia/Seoul) 출하 기준을 맞추는가 — 예약 배정과 같은 적격 기준(상품 소비기한 보장, 없으면 보관 유형 최소 잔여일). 기준이 없으면
   * null(판정 불가).
   */
  public @Nullable Boolean shippableToday(
      String sku,
      String lotId,
      @Nullable LocalDate expiryDate,
      @Nullable LocalDate manufactureDate,
      Instant at) {
    LotAllocator.Rules rules = shelfRules(List.of(sku), skus.findAll(List.of(sku)));
    LotCandidate c = new LotCandidate(sku, lotId, expiryDate, manufactureDate, null, 1);
    if (!LotAllocator.missingPolicy(List.of(c), rules).isEmpty()) {
      return null;
    }
    return LotAllocator.eligible(c, LocalDate.ofInstant(at, BUSINESS_ZONE), rules);
  }

  // ---------- 조회 ----------

  /** 피킹 지시 한 조각: 예약 조각 + LOT 소비기한 + 그 LOT 의 선반 위치별 잔량(코드 순). */
  public record PickPlanPart(
      ReservationStore.PickPart part,
      @Nullable LocalDate expiryDate,
      List<Map.Entry<String, Integer>> locations) {}

  public record PickPlan(ReservationRow reservation, List<PickPlanPart> parts) {}

  @Transactional(readOnly = true)
  public PickPlan pickPlan(String reservationId) {
    ReservationRow r =
        reservations
            .find(reservationId)
            .orElseThrow(
                () -> PmartException.notFound(RESERVATION_NOT_FOUND, "예약이 없습니다: " + reservationId));
    List<ReservationStore.PickPart> parts = reservations.pickParts(reservationId, false);
    var lots =
        stock.lots(
            parts.stream()
                .map(ReservationStore.PickPart::lotId)
                .collect(java.util.stream.Collectors.toSet()));
    Map<String, List<com.pmart.mfc.inventory.infrastructure.StockStore.UnitRow>> unitsBySku =
        new LinkedHashMap<>();
    List<PickPlanPart> out = new ArrayList<>();
    for (ReservationStore.PickPart p : parts) {
      var units = unitsBySku.computeIfAbsent(p.sku(), s -> stock.units(r.mfcId(), s));
      var holds = stock.openHolds(r.mfcId(), p.sku()); // 분실 조사 보류분은 집을 수 없다(ADR-0044)
      Map<String, Integer> byLocation = new java.util.TreeMap<>();
      for (var u : units) {
        if (u.key().lotId().equals(p.lotId())
            && u.key().quality() == com.pmart.mfc.inventory.domain.StockUnit.Quality.NORMAL
            && u.key().work() == com.pmart.mfc.inventory.domain.StockUnit.Work.STORED
            && u.qty() > 0) {
          int usable = u.qty() - holds.getOrDefault(u.key(), 0);
          if (usable > 0) {
            byLocation.merge(u.key().locationCode(), usable, Integer::sum);
          }
        }
      }
      var lot = lots.get(p.lotId());
      out.add(
          new PickPlanPart(
              p, lot == null ? null : lot.expiryDate(), new ArrayList<>(byLocation.entrySet())));
    }
    return new PickPlan(r, out);
  }

  @Transactional(readOnly = true)
  public ReservationView get(String reservationId) {
    ReservationRow r =
        reservations
            .find(reservationId)
            .orElseThrow(
                () -> PmartException.notFound(RESERVATION_NOT_FOUND, "예약이 없습니다: " + reservationId));
    return new ReservationView(r, reservations.parts(reservationId));
  }

  private ReservationRow lockOrThrow(String reservationId) {
    return reservations
        .lock(reservationId)
        .orElseThrow(
            () -> PmartException.notFound(RESERVATION_NOT_FOUND, "예약이 없습니다: " + reservationId));
  }

  private static void checkVersion(ReservationRow r, @Nullable Integer expectedVersion) {
    if (expectedVersion != null && expectedVersion != r.version()) {
      throw PmartException.versionConflict(
          "예약이 이미 바뀌었습니다(기대 버전 " + expectedVersion + ", 현재 " + r.version() + ").");
    }
  }
}
