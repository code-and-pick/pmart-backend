package com.pmart.mfc.fulfillment.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.fulfillment.domain.HandoverRules;
import com.pmart.mfc.fulfillment.domain.HandoverRules.AuthorizeDecision;
import com.pmart.mfc.fulfillment.domain.HandoverRules.AuthorizeFacts;
import com.pmart.mfc.fulfillment.domain.TaskRules.Content;
import com.pmart.mfc.fulfillment.infrastructure.HandoverStore;
import com.pmart.mfc.fulfillment.infrastructure.HandoverStore.Assignment;
import com.pmart.mfc.fulfillment.infrastructure.HandoverStore.Handover;
import com.pmart.mfc.fulfillment.infrastructure.HandoverStore.PackageFence;
import com.pmart.mfc.fulfillment.infrastructure.InventoryClient;
import com.pmart.mfc.fulfillment.infrastructure.PendingListStore;
import com.pmart.mfc.fulfillment.infrastructure.WorkOrderStore;
import com.pmart.mfc.fulfillment.infrastructure.WorkStopStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 라이더 인계(슬라이스 6c, WK-08·IV-08, ADR-0047). 계약 정본: docs/business/flows/package-handover-contract.md.
 *
 * <ul>
 *   <li>배정: delivery-dispatch 사건 투영(배송 ID 한 행, 더 새 버전만). 라이더가 바뀌거나 배차가 취소되면 확정 요청 전 fence 를 끝낸다.
 *   <li>허가(fence): 주문 잠금(중지와 한 줄) → 포장 잠금 → {@link HandoverRules#authorize}. 유효시간은 센터 설정(없으면
 *       CONFIG_REQUIRED).
 *   <li>확정: 주문 잠금 아래 fence 확인·인계 사실(REQUESTED) 기록 → 트랜잭션 밖 inventory 인계 전기(키 = 인계 ID) → APPLIED 면
 *       포장 HANDED_OVER·출고 갱신·PackageHandedOver. 결과 모름은 sweep 이 같은 ID 로, 거절은 REJECTED(fence 유지 —
 *       대사).
 * </ul>
 */
@Service
public class HandoverService {

  private static final Logger log = LoggerFactory.getLogger(HandoverService.class);
  private static final TypeReference<List<Content>> CONTENTS = new TypeReference<>() {};

  public record AuthorizeCommand(String riderId, String deliveryId, String scannedCode) {}

  public record Authorization(
      String handoverAuthorizationId,
      String packageId,
      int packageVersion,
      int fenceVersion,
      String riderId,
      String deliveryId,
      Instant fenceExpiresAt,
      Instant authorizedAt) {}

  public record CommitCommand(String handoverAuthorizationId, int fenceVersion, String riderId) {}

  /** 확정 결과. status = APPLIED / UNKNOWN_RESULT / REJECTED. */
  public record CommitResult(
      String status,
      String handoverId,
      @Nullable String inventoryCommitId,
      String packageState,
      String shipmentState,
      @Nullable Instant handedOverAt,
      @Nullable String nextAction,
      @Nullable String rejectReason) {}

  /** 조회 판정. */
  public record Resolution(
      PackageFence pkg,
      HandoverRules.Verdict verdict,
      boolean fenceLive,
      @Nullable Handover handover,
      @Nullable String stopRequestId) {}

  private final HandoverStore store;
  private final WorkOrderStore works;
  private final WorkStopStore stops;
  private final InventoryClient inventory;
  private final FulfillmentEvents events;
  private final FulfillmentProperties props;
  private final RequestIdempotency idempotency;
  private final ObjectMapper mapper;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final PendingListStore pending;

  public HandoverService(
      HandoverStore store,
      WorkOrderStore works,
      WorkStopStore stops,
      InventoryClient inventory,
      FulfillmentEvents events,
      FulfillmentProperties props,
      RequestIdempotency idempotency,
      ObjectMapper mapper,
      PlatformTransactionManager txManager,
      Clock clock,
      PendingListStore pending) {
    this.pending = pending;
    this.store = store;
    this.works = works;
    this.stops = stops;
    this.inventory = inventory;
    this.events = events;
    this.props = props;
    this.idempotency = idempotency;
    this.mapper = mapper;
    this.tx = new TransactionTemplate(txManager);
    this.clock = clock;
  }

  // ---------- 허가 ----------

  public Authorization authorize(
      String packageId, String requestId, String sourceSystem, AuthorizeCommand in) {
    requireRider(in.riderId());
    PackageFence p0 = pkg(packageId);
    Duration ttl = props.handoverFenceTtl(p0.mfcId());
    if (ttl == null) {
      throw new PmartException(
          "CONFIG_REQUIRED",
          HttpStatus.UNPROCESSABLE_CONTENT,
          "센터 " + p0.mfcId() + " 의 인계 허가 유효시간(pmart.fulfillment.handover-fence-ttl)이 없습니다.",
          false,
          List.of());
    }
    return idempotency
        .execute(
            new RequestKey(sourceSystem, requestId, "authorizePackageHandover", in.riderId()),
            Map.of("packageId", packageId, "command", in),
            Authorization.class,
            () -> {
              works.lockOrder(p0.orderId()); // 중지 판정과 한 줄로
              PackageFence p = store.lock(packageId).orElseThrow();
              Instant now = clock.instant();
              Assignment a = store.assignment(in.deliveryId()).orElse(null);
              boolean requested = store.handover(packageId).isPresent();
              boolean live =
                  HandoverRules.fenceLive(p.fenceState(), p.fenceExpiresAt(), requested, now);
              AuthorizeDecision d =
                  HandoverRules.authorize(
                      new AuthorizeFacts(
                          HandoverRules.qrMatches(p.qrCode(), in.scannedCode()),
                          p.state(),
                          p.shipmentState(),
                          stops.orderStopped(p.orderId()),
                          a == null ? null : a.shipmentId(),
                          p.shipmentId(),
                          a == null ? null : a.state(),
                          a == null ? null : a.riderId(),
                          in.riderId(),
                          live,
                          requested,
                          p.fenceRiderId(),
                          p.fenceDeliveryId(),
                          in.deliveryId(),
                          "HANDOVER_IN_PROGRESS".equals(p.fenceState())));
              if ("REJECT".equals(d.outcome())) {
                throw rejection(Objects.requireNonNull(d.code()), packageId);
              }
              if ("REUSE".equals(d.outcome())) {
                return view(p, Objects.requireNonNull(p.fenceExpiresAt()));
              }
              String authId = "HAU-" + UUID.randomUUID();
              Instant expires = now.plus(ttl);
              store.authorize(
                  packageId,
                  authId,
                  p.fenceVersion() + 1,
                  in.riderId(),
                  in.deliveryId(),
                  expires,
                  now);
              log.info(
                  "포장 {} 인계 허가 {} v{} — 라이더 {} 배송 {} (~{})",
                  packageId,
                  authId,
                  p.fenceVersion() + 1,
                  in.riderId(),
                  in.deliveryId(),
                  expires);
              return view(store.find(packageId).orElseThrow(), expires);
            })
        .value();
  }

  private static Authorization view(PackageFence p, Instant expires) {
    return new Authorization(
        Objects.requireNonNull(p.authorizationId()),
        p.packageId(),
        p.packageVersion(),
        p.fenceVersion(),
        Objects.requireNonNull(p.fenceRiderId()),
        Objects.requireNonNull(p.fenceDeliveryId()),
        expires,
        Objects.requireNonNull(p.fenceAuthorizedAt()));
  }

  // ---------- 확정 ----------

  public CommitResult commit(String packageId, CommitCommand in) {
    requireRider(in.riderId());
    PackageFence p0 = pkg(packageId);
    Handover h =
        Objects.requireNonNull(
            tx.execute(
                s -> {
                  works.lockOrder(p0.orderId());
                  PackageFence p = store.lock(packageId).orElseThrow();
                  Handover existing = store.handover(packageId).orElse(null);
                  if (existing != null) {
                    if (!existing.authorizationId().equals(in.handoverAuthorizationId())
                        || existing.fenceVersion() != in.fenceVersion()) {
                      throw rejection(
                          "APPLIED".equals(existing.state())
                              ? HandoverRules.ALREADY_HANDED_OVER
                              : HandoverRules.FENCE_VERSION_MISMATCH,
                          packageId);
                    }
                    return existing; // 같은 허가로 다시 — 같은 인계
                  }
                  if (!"HANDOVER_IN_PROGRESS".equals(p.fenceState())) {
                    throw rejection(
                        "HANDED_OVER".equals(p.state())
                            ? HandoverRules.ALREADY_HANDED_OVER
                            : HandoverRules.INVALID_STATE,
                        packageId);
                  }
                  if (!in.handoverAuthorizationId().equals(p.authorizationId())
                      || in.fenceVersion() != p.fenceVersion()) {
                    throw rejection(HandoverRules.FENCE_VERSION_MISMATCH, packageId);
                  }
                  if (!in.riderId().equals(p.fenceRiderId())) {
                    throw rejection(HandoverRules.RIDER_MISMATCH, packageId);
                  }
                  // 배정이 바뀌었으면 이전 라이더 허가로 확정하지 않는다(허가는 점원 확인까지 남음, D-1)
                  Assignment cur = store.assignment(p.fenceDeliveryId()).orElse(null);
                  if (cur == null
                      || !"ASSIGNED".equals(cur.state())
                      || !in.riderId().equals(cur.riderId())) {
                    throw rejection(HandoverRules.NOT_ASSIGNED, packageId);
                  }
                  Instant now = clock.instant();
                  if (!HandoverRules.fenceLive(p.fenceState(), p.fenceExpiresAt(), false, now)) {
                    throw rejection(HandoverRules.FENCE_EXPIRED, packageId);
                  }
                  Handover fresh =
                      new Handover(
                          "HOV-" + UUID.randomUUID(),
                          packageId,
                          p.shipmentId(),
                          in.handoverAuthorizationId(),
                          in.fenceVersion(),
                          in.riderId(),
                          Objects.requireNonNull(p.fenceDeliveryId()),
                          "REQUESTED",
                          null,
                          null,
                          now,
                          null);
                  store.insertHandover(fresh);
                  return fresh;
                }));
    if ("REQUESTED".equals(h.state()) || "UNKNOWN_RESULT".equals(h.state())) {
      post(packageId);
    }
    return result(packageId);
  }

  /** 재고 인계 전기(트랜잭션 밖) → 결과 기록. */
  private void post(String packageId) {
    PackageFence p = pkg(packageId);
    Handover h = store.handover(packageId).orElseThrow();
    List<Content> contents = mapper.readValue(p.contents(), CONTENTS);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("packageId", packageId);
    body.put("handoverId", h.handoverId());
    body.put("handoverAuthorizationId", h.authorizationId());
    body.put("fenceVersion", h.fenceVersion());
    body.put("mfcId", p.mfcId());
    body.put("orderId", p.orderId());
    body.put("reservationId", p.reservationId());
    body.put("shipmentId", p.shipmentId());
    body.put("deliveryId", h.deliveryId());
    body.put("riderId", h.riderId());
    body.put("locationCode", p.packingLocation()); // HandoverStore 가 랙에 올린 포장은 랙 위치로 채운다(F-12)
    body.put(
        "lines",
        contents.stream()
            .map(
                c ->
                    Map.of(
                        "orderLineId", c.orderLineId(),
                        "sku", c.sku(),
                        "lotId", c.lotId(),
                        "qty", c.qty()))
            .toList());
    InventoryClient.Commit c = inventory.packageHandover(h.handoverId(), body);
    tx.executeWithoutResult(
        s -> {
          Instant now = clock.instant();
          switch (c.outcome()) {
            case "APPLIED" -> {
              if (store.finishHandover(h.handoverId(), "APPLIED", c.commitId(), null, now, now)
                  == 0) {
                return; // 다른 시도가 먼저 끝냈다
              }
              store.handedOver(packageId, now);
              String shipmentState = store.refreshShipment(p.shipmentId(), now);
              events.packageHandedOver(
                  p,
                  h,
                  Objects.requireNonNull(c.commitId()),
                  contents.stream()
                      .<Map<String, Object>>map(
                          x ->
                              Map.of(
                                  "orderLineId", x.orderLineId(),
                                  "sku", x.sku(),
                                  "lot", x.lotId(),
                                  "qty", x.qty()))
                      .toList(),
                  now);
              log.info(
                  "포장 {} 인계 확정 {} (재고 {}) — 출고 {} {}",
                  packageId,
                  h.handoverId(),
                  c.commitId(),
                  p.shipmentId(),
                  shipmentState);
            }
            case "REJECTED" -> {
              String reason = c.code() + (c.detail() == null ? "" : ": " + c.detail());
              if (store.finishHandover(h.handoverId(), "REJECTED", null, reason, null, now) == 1) {
                log.error(
                    "포장 {} 인계 {} — 재고가 거절, 대사 필요(재인계·취소 막음): {}",
                    packageId,
                    h.handoverId(),
                    reason);
              }
            }
            default ->
                store.finishHandover(h.handoverId(), "UNKNOWN_RESULT", null, c.detail(), null, now);
          }
        });
  }

  /**
   * 재고가 거절한 인계를 운영자가 실물 확인·재고 정정 뒤 같은 인계 ID 로 다시 보낸다(D-8). REJECTED 가 아니면 지금 결과 그대로 — 자동 재개는 하지
   * 않는다(MFC 1551행).
   */
  public CommitResult retry(String handoverId) {
    return retry(handoverId, ManualRetry.NONE);
  }

  /** 운영자 재시도 + 기록(0.9.0, BO-12) — 실제로 다시 보냈을 때(REJECTED 였을 때)만 남긴다. */
  public CommitResult retry(String handoverId, ManualRetry note) {
    Handover h =
        store
            .handoverById(handoverId)
            .orElseThrow(
                () -> PmartException.notFound("HANDOVER_NOT_FOUND", "인계가 없습니다: " + handoverId));
    if ("REJECTED".equals(h.state()) && store.reopenRejected(handoverId, clock.instant()) == 1) {
      log.info("포장 {} 인계 {} — 운영자 재시도", h.packageId(), handoverId);
      pending.recordRetry(
          PendingListStore.HANDOVER,
          handoverId,
          note.operatorId(),
          note.reason(),
          note.sourceSystem(),
          clock.instant());
      post(h.packageId());
    }
    return result(h.packageId());
  }

  /** 재고 결과를 모르는 인계를 같은 인계 ID 로 다시. */
  @Scheduled(
      fixedDelayString = "${pmart.fulfillment.handover-sweep-interval:PT15S}",
      initialDelayString = "PT30S")
  public void sweep() {
    for (String packageId : store.openHandovers(clock.instant().minusSeconds(10), 50)) {
      try {
        post(packageId);
      } catch (RuntimeException e) {
        log.warn("포장 {} 인계 재시도 실패: {}", packageId, e.getMessage());
      }
    }
  }

  private CommitResult result(String packageId) {
    PackageFence p = pkg(packageId);
    Handover h = store.handover(packageId).orElseThrow();
    String status =
        switch (h.state()) {
          case "APPLIED" -> "APPLIED";
          case "REJECTED" -> "REJECTED";
          default -> "UNKNOWN_RESULT";
        };
    String next =
        switch (status) {
          case "APPLIED" -> null;
          case "REJECTED" -> "RECONCILE";
          default -> "WAIT_RESULT";
        };
    return new CommitResult(
        status,
        h.handoverId(),
        h.inventoryCommitId(),
        p.state(),
        p.shipmentState(),
        h.handedOverAt(),
        next,
        h.rejectReason());
  }

  // ---------- 조회 ----------

  /** 점원 확인이 필요한 포장 — 센터 전체(0.4.9). */
  public List<Resolution> toCheck(String mfcId) {
    TaskService.requireWorkerOrSupervisor(mfcId);
    List<Resolution> out = new java.util.ArrayList<>();
    for (PackageFence p : store.checkRequired(mfcId, clock.instant())) {
      out.add(resolution(p.packageId(), null));
    }
    return out;
  }

  public Resolution resolution(String packageId, @Nullable String stopRequestId) {
    PackageFence p = pkg(packageId);
    Handover h = store.handover(packageId).orElse(null);
    boolean live =
        HandoverRules.fenceLive(p.fenceState(), p.fenceExpiresAt(), h != null, clock.instant());
    HandoverRules.Verdict v = HandoverRules.verdict(p.state(), live);
    if (v == HandoverRules.Verdict.READY && stops.orderStopped(p.orderId())) {
      v = HandoverRules.Verdict.STOPPED; // 중지 결정 뒤 — 인계 허가 안 함
    }
    return new Resolution(p, v, live, h, stopRequestId);
  }

  // ---------- 배정 투영(delivery.delivery.v1) ----------

  /** inbox 트랜잭션 안. 관련 없는 사건은 지나간다. */
  public void onDeliveryEvent(EventEnvelope e) {
    var p = e.payload();
    String deliveryId = p.path("deliveryId").asString(null);
    if (deliveryId == null) {
      return;
    }
    Instant now = clock.instant();
    switch (e.eventType()) {
      case "DeliveryCreated" ->
          store.upsertDelivery(
              deliveryId,
              p.path("shipmentId").asString(null),
              p.path("orderId").asString(null),
              now);
      case "DeliveryAssigned" -> {
        store.upsertDelivery(deliveryId, null, p.path("orderId").asString(null), now);
        if (store.applyAssignment(
                deliveryId,
                p.path("riderId").asString(null),
                p.path("assignmentVersion").asInt(0),
                "ASSIGNED",
                now)
            == 1) {
          revokeOtherRiders(deliveryId, p.path("riderId").asString(null));
        }
      }
      case "DeliveryAssignmentChanged" -> {
        store.upsertDelivery(deliveryId, null, null, now);
        String rider = p.path("newRiderId").asString(null);
        if (store.applyAssignment(
                deliveryId,
                rider,
                p.path("assignmentVersion").asInt(0),
                rider == null ? "UNASSIGNED" : "ASSIGNED",
                now)
            == 1) {
          revokeOtherRiders(deliveryId, rider);
        }
      }
      case "DeliveryCancelledBeforePickup" -> {
        store.upsertDelivery(deliveryId, null, p.path("orderId").asString(null), now);
        if (store.cancelDelivery(deliveryId, now) == 1) {
          revokeOtherRiders(deliveryId, null);
        }
      }
      default -> {
        // 그 밖 배송 사건은 쓰지 않는다
      }
    }
  }

  /**
   * 배정이 바뀌어도 이전 라이더의 열린 허가는 끝내지 않는다(2026-11-08 D-1 안 C) — QR 을 찍은 라이더가 이미 들고 갔을 수 있다. 이전 라이더는 확정할 수
   * 없고(NOT_ASSIGNED), 새 라이더는 허가가 살아 있으면 FENCE_ACTIVE, 만료 뒤에는 점원 확인(handover-checks) 전까지
   * FENCE_EXPIRED_UNCONFIRMED.
   */
  private void revokeOtherRiders(String deliveryId, @Nullable String keepRider) {
    Assignment a = store.assignment(deliveryId).orElse(null);
    if (a == null || a.shipmentId() == null) {
      return;
    }
    for (PackageFence p : store.byShipment(a.shipmentId())) {
      if ("HANDOVER_IN_PROGRESS".equals(p.fenceState())
          && deliveryId.equals(p.fenceDeliveryId())
          && (keepRider == null || !keepRider.equals(p.fenceRiderId()))) {
        log.warn(
            "포장 {} 배송 {} 배정 변경 — 이전 라이더 {} 의 인계 허가는 점원 확인 전까지 유지",
            p.packageId(),
            deliveryId,
            p.fenceRiderId());
      }
    }
  }

  // ---------- 점원 확인(D-1) ----------

  public static final String HANDOVER_CODE_MISMATCH = "HANDOVER_CODE_MISMATCH";
  public static final String HANDOVER_CODE_AMBIGUOUS = "HANDOVER_CODE_AMBIGUOUS";
  public static final String HANDOVER_CODE_LOCKED = "HANDOVER_CODE_LOCKED";

  /** 번호 입력 허용 실패 횟수(0.23.0, R-26 — 반납 인증번호와 같은 5번). */
  static final int MAX_CODE_FAILURES = 5;

  /**
   * 라벨 숫자로 인계 허가(요청 F-8, 2026-11-17 — 라이더 앱 "QR 스캔이 어려우면 라벨 숫자 입력"). 그 배송의 출고에서 숫자가 같은 밀봉 포장을 찾아 QR
   * 을 찍은 것과 같은 {@link #authorize} 로 넘긴다(같은 검사·같은 허가·같은 멱등). 배송이 이 센터 출고가 아니면 409 DELIVERY_MISMATCH,
   * 그 출고에 숫자가 같은 밀봉 포장이 없으면 409 HANDOVER_CODE_MISMATCH(시도 기록 — 경고 로그), 둘 이상이면 409
   * HANDOVER_CODE_AMBIGUOUS(QR 로).
   *
   * <p>0.23.0(R-26): 번호는 4자리라 찍어 맞히기 쉬워, 같은 라이더·배송이 5번 틀리면 409 HANDOVER_CODE_LOCKED — 맞는 번호도 거절,
   * QR·점원 확인만. 틀릴 때 details {@code attemptsLeft}.
   */
  public Authorization authorizeByCode(
      String requestId, String sourceSystem, String riderId, String deliveryId, String code) {
    requireRider(riderId);
    Assignment a = store.assignment(deliveryId).orElse(null);
    if (a == null || a.shipmentId() == null) {
      throw new PmartException(
          "DELIVERY_MISMATCH",
          HttpStatus.CONFLICT,
          "이 센터 출고의 배송이 아닙니다: " + deliveryId,
          false,
          List.of());
    }
    if (store.codeFailures(riderId, deliveryId) >= MAX_CODE_FAILURES) {
      throw locked();
    }
    List<String> found = store.sealedPackagesByCode(a.shipmentId(), code.strip());
    if (found.isEmpty() && store.handedOverByCode(a.shipmentId(), code.strip())) {
      // 2026-11-20(라이더 앱 실서버 — 인계 뒤 같은 번호를 다시 넣어 틀린 횟수가 줄던 것): 이미 인계된 이 배송 포장 번호는 틀린 번호로 세지 않는다
      throw new PmartException(
          HandoverRules.ALREADY_HANDED_OVER,
          HttpStatus.CONFLICT,
          "이미 인계된 포장 번호입니다.",
          false,
          List.of());
    }
    if (found.isEmpty()) {
      int failures = store.recordCodeFailure(riderId, deliveryId, clock.instant());
      log.warn("라벨 숫자 인계 불일치 — 라이더 {} 배송 {} ({}번째)", riderId, deliveryId, failures);
      if (failures >= MAX_CODE_FAILURES) {
        throw locked();
      }
      throw new PmartException(
          HANDOVER_CODE_MISMATCH,
          HttpStatus.CONFLICT,
          "이 배송의 포장 라벨 숫자가 아닙니다 — 라벨을 다시 확인하거나 QR 을 찍어 주세요.",
          false,
          List.of(
              new PmartException.Detail(
                  "attemptsLeft", String.valueOf(MAX_CODE_FAILURES - failures))));
    }
    if (found.size() > 1) {
      throw new PmartException(
          HANDOVER_CODE_AMBIGUOUS,
          HttpStatus.CONFLICT,
          "같은 숫자의 포장이 여럿입니다 — QR 을 찍어 주세요.",
          false,
          List.of());
    }
    String packageId = found.get(0);
    PackageFence p = pkg(packageId);
    return authorize(
        packageId,
        requestId,
        sourceSystem,
        new AuthorizeCommand(riderId, deliveryId, java.util.Objects.requireNonNull(p.qrCode())));
  }

  private static PmartException locked() {
    return new PmartException(
        HANDOVER_CODE_LOCKED,
        HttpStatus.CONFLICT,
        "번호를 " + MAX_CODE_FAILURES + "번 틀려 번호 입력이 막혔습니다 — QR 을 찍거나 센터 직원에게 확인받아 주세요.",
        false,
        List.of());
  }

  public static final String AT_STATION = "AT_STATION";
  public static final String TAKEN_BY_RIDER = "TAKEN_BY_RIDER";

  /**
   * 만료됐지만 확정 요청이 없는 허가의 실물 확인. AT_STATION → 허가 해제, TAKEN_BY_RIDER → 허가 라이더에게 인계된 것으로(인계 사실 → 재고
   * 전기).
   */
  public Resolution check(
      String packageId,
      String requestId,
      String sourceSystem,
      String result,
      @Nullable String note) {
    PackageFence p0 = pkg(packageId);
    String me = TaskService.requireWorkerOrSupervisor(p0.mfcId());
    if (!AT_STATION.equals(result) && !TAKEN_BY_RIDER.equals(result)) {
      throw TaskService.invalid("result 는 AT_STATION / TAKEN_BY_RIDER 입니다.");
    }
    idempotency.execute(
        new RequestKey(sourceSystem, requestId, "checkPackageHandover", me),
        Map.of("packageId", packageId, "result", result),
        String.class,
        () -> {
          works.lockOrder(p0.orderId());
          PackageFence p = store.lock(packageId).orElseThrow();
          boolean requested = store.handover(packageId).isPresent();
          Instant now = clock.instant();
          if (!HandoverRules.checkRequired(p.fenceState(), p.fenceExpiresAt(), requested, now)) {
            boolean live =
                HandoverRules.fenceLive(p.fenceState(), p.fenceExpiresAt(), requested, now);
            throw new PmartException(
                live && !requested ? HandoverRules.FENCE_ACTIVE : HandoverRules.INVALID_STATE,
                HttpStatus.CONFLICT,
                live && !requested
                    ? "인계 허가 유효시간이 아직 남았습니다 — 만료 뒤 확인합니다 (" + packageId + ")"
                    : "확인할 인계 허가가 없습니다(허가 없음·확정 요청 있음·이미 확인) (" + packageId + ")",
                false,
                List.of());
          }
          store.recordCheck(packageId, result, me, note, now);
          if (AT_STATION.equals(result)) {
            store.expireFence(packageId);
            log.info("포장 {} 점원 확인 — 포장대에 있음, 인계 허가 해제 by {}", packageId, me);
            return "RELEASED";
          }
          store.insertHandover(
              new Handover(
                  "HOV-" + UUID.randomUUID(),
                  packageId,
                  p.shipmentId(),
                  Objects.requireNonNull(p.authorizationId()),
                  p.fenceVersion(),
                  Objects.requireNonNull(p.fenceRiderId()),
                  Objects.requireNonNull(p.fenceDeliveryId()),
                  "REQUESTED",
                  null,
                  null,
                  now,
                  null));
          log.warn(
              "포장 {} 점원 확인 — 포장대에 없음, 라이더 {} 에게 인계된 것으로 확정 by {}", packageId, p.fenceRiderId(), me);
          return "TAKEN";
        });
    store
        .handover(packageId)
        .filter(h -> "REQUESTED".equals(h.state()) || "UNKNOWN_RESULT".equals(h.state()))
        .ifPresent(h -> post(packageId));
    return resolution(packageId, null);
  }

  public java.util.Optional<HandoverStore.Check> lastCheck(String packageId) {
    return store.lastCheck(packageId);
  }

  // ---------- 내부 ----------

  private PackageFence pkg(String packageId) {
    return store
        .find(packageId)
        .orElseThrow(() -> PmartException.notFound("PACKAGE_NOT_FOUND", "포장이 없습니다: " + packageId));
  }

  private static void requireRider(String riderId) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "토큰이 필요합니다."));
    if (p.type() != PmartPrincipal.PrincipalType.RIDER || !p.subject().equals(riderId)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "그 라이더 본인만 인계할 수 있습니다.");
    }
  }

  private static PmartException rejection(String code, String packageId) {
    if (HandoverRules.RIDER_MISMATCH.equals(code)) {
      return new PmartException(
          code, HttpStatus.FORBIDDEN, "배정 라이더가 아닙니다: " + packageId, false, List.of());
    }
    String msg =
        switch (code) {
          case HandoverRules.ALREADY_HANDED_OVER -> "이미 인계된 포장입니다.";
          case HandoverRules.PACKAGE_INVALIDATED -> "무효 라벨입니다(재포장·취소된 포장이거나 다른 QR).";
          case HandoverRules.DELIVERY_MISMATCH -> "이 포장의 배송이 아닙니다.";
          case HandoverRules.NOT_ASSIGNED -> "배정된 라이더가 없습니다(배정 해제·취소).";
          case HandoverRules.FENCE_ACTIVE -> "다른 라이더의 인계 허가가 진행 중입니다.";
          case HandoverRules.FENCE_EXPIRED_UNCONFIRMED ->
              "앞선 인계 허가가 만료됐지만 포장 실물 확인이 없습니다 — 점원 확인 뒤 다시 찍어 주세요.";
          case HandoverRules.HANDOVER_RESULT_PENDING -> "인계 확정 결과를 확인 중입니다.";
          case HandoverRules.FENCE_EXPIRED -> "인계 허가가 만료됐습니다. 다시 찍어 주세요.";
          case HandoverRules.FENCE_VERSION_MISMATCH -> "인계 허가가 바뀌었습니다. 다시 찍어 주세요.";
          default -> "지금 인계할 수 없는 포장입니다(포장·출고 상태 또는 주문 취소 진행).";
        };
    return new PmartException(
        code, HttpStatus.CONFLICT, msg + " (" + packageId + ")", false, List.of());
  }
}
