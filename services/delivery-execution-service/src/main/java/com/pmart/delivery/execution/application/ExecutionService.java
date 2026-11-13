package com.pmart.delivery.execution.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.delivery.execution.domain.ExecutionRules;
import com.pmart.delivery.execution.infrastructure.ExecutionStore;
import com.pmart.delivery.execution.infrastructure.ExecutionStore.DeliveryRow;
import com.pmart.delivery.execution.infrastructure.ExecutionStore.HandoverRow;
import com.pmart.delivery.execution.infrastructure.ExecutionStore.PhotoRow;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 라이더 상태 전이(계약 0.2.0): 가게 도착 → 픽업 완료(인계 사실 확인만) → 고객 도착 → 증빙 사진 → 전달 완료. 배정된 라이더 본인만. 같은 키·같은 본문은
 * 같은 결과(RequestIdempotency), X-Expected-Version 은 수행 버전. 거절은 기록하지 않는다(사건이 와서 다시 누르면 되게).
 */
@Service
public class ExecutionService {

  public record Applied(String requestId, String deliveryId, int version, Instant occurredAt) {}

  public record Photo(String photoRef, String contentType, int sizeBytes, Instant uploadedAt) {}

  public record Completion(
      String method,
      List<String> photoRefs,
      Instant takenAt,
      @Nullable String placement,
      @Nullable String placementDetail,
      @Nullable String substituteEvidenceCaseId) {}

  public record View(
      DeliveryRow delivery,
      List<HandoverRow> handovers,
      List<ExecutionStore.HistoryRow> history,
      List<ExecutionStore.HelpRow> helps,
      ExecutionStore.@Nullable EarningRow earning) {}

  /** 전달 실패 등록(2026-11-13) — contactAttempts 는 [{attemptedAt, method, result}]. */
  public record Failure(
      String reason,
      @Nullable String detail,
      List<String> photoRefs,
      @Nullable String goodsLocation,
      List<Map<String, Object>> contactAttempts) {}

  private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

  private final ExecutionStore store;
  private final ExecutionEvents events;
  private final PickupJudgement pickup;
  private final EarningService earnings;
  private final RequestIdempotency idempotency;
  private final ObjectProvider<EvidencePhotoStorage> photos;
  private final ObjectMapper mapper;
  private final Clock clock;

  public ExecutionService(
      ExecutionStore store,
      ExecutionEvents events,
      PickupJudgement pickup,
      EarningService earnings,
      RequestIdempotency idempotency,
      ObjectProvider<EvidencePhotoStorage> photos,
      ObjectMapper mapper,
      Clock clock) {
    this.store = store;
    this.events = events;
    this.pickup = pickup;
    this.earnings = earnings;
    this.idempotency = idempotency;
    this.photos = photos;
    this.mapper = mapper;
    this.clock = clock;
  }

  View viewOf(DeliveryRow d) {
    return new View(
        d,
        store.handovers(d.deliveryId()),
        store.history(d.deliveryId()),
        store.helps(d.deliveryId()),
        d.riderId() == null ? null : store.earningOf(d.deliveryId(), d.riderId()).orElse(null));
  }

  // ---- 조회

  /** 배정 라이더 본인, 그 센터 운영자·OPERATOR_ADMIN·CS_AGENT, 서비스. 볼 수 없으면 있는지도 알리지 않는다(404). */
  public View view(String deliveryId) {
    PmartPrincipal p = principal();
    DeliveryRow d = store.delivery(deliveryId).orElseThrow(ExecutionService::notFound);
    boolean ok =
        switch (p.type()) {
          case SERVICE -> true;
          case OPERATOR ->
              p.hasRole("OPERATOR_ADMIN")
                  || p.hasRole("CS_AGENT")
                  || (d.mfcId() != null && p.canAccessCenter(d.mfcId()));
          case RIDER -> p.subject().equals(d.riderId());
          default -> false;
        };
    if (!ok) {
      throw notFound();
    }
    return viewOf(d);
  }

  public List<View> mine(
      @Nullable LocalDate date, @Nullable String state, int size, @Nullable String cursor) {
    PmartPrincipal p = principal();
    if (p.type() != PmartPrincipal.PrincipalType.RIDER) {
      throw forbidden("라이더만 볼 수 있습니다.");
    }
    Instant from = date == null ? null : date.atStartOfDay(SEOUL).toInstant();
    Instant to = date == null ? null : date.plusDays(1).atStartOfDay(SEOUL).toInstant();
    return store.riderDeliveries(p.subject(), from, to, state, size, offset(cursor)).stream()
        .map(this::viewOf)
        .toList();
  }

  // ---- 상태 전이

  public Applied storeArrival(
      String deliveryId, String requestId, String source, @Nullable Integer expectedVersion) {
    return transition(
        "reportStoreArrival",
        deliveryId,
        requestId,
        source,
        expectedVersion,
        Map.of(),
        (d, rider, now) -> {
          if (!"ASSIGNED".equals(d.state())) {
            throw PmartException.invalidState("배정된 배송만 가게 도착할 수 있습니다(지금 " + d.state() + ").");
          }
          store.setState(deliveryId, "ARRIVED_AT_STORE", now);
          store.history(deliveryId, "ARRIVED_AT_STORE", now, "RIDER", requestId, now);
          events.arrivedAtStore(d, now);
          return d.version() + 1;
        });
  }

  /**
   * 픽업 완료 버튼: 상태는 인계 사실(PackageHandedOver)로만 바뀐다(DE-1). 보낸 handoverIds 가 이 배송·이 라이더의 인계 사실이고 배송의
   * 포장이 모두 인계됐으면 APPLIED, 아직이면 422 HANDOVER_NOT_CONFIRMED(사건이 오면 다시 누른다).
   */
  public Applied pickup(
      String deliveryId,
      String requestId,
      String source,
      @Nullable Integer expectedVersion,
      List<String> handoverIds) {
    return transition(
        "reportPickup",
        deliveryId,
        requestId,
        source,
        expectedVersion,
        Map.of("handoverIds", handoverIds),
        (d, rider, now) -> {
          Map<String, HandoverRow> recorded =
              store.handovers(deliveryId).stream()
                  .collect(Collectors.toMap(HandoverRow::handoverId, h -> h, (a, b) -> a));
          for (String id : handoverIds) {
            HandoverRow h = recorded.get(id);
            if (h == null) {
              throw unprocessable(
                  "HANDOVER_NOT_CONFIRMED", "인계 확정이 아직 오지 않은 포장이 있습니다. 잠시 뒤 다시 눌러 주세요.");
            }
            if (h.riderId() != null && !h.riderId().equals(rider)) {
              throw unprocessable("HANDOVER_RIDER_MISMATCH", "다른 라이더에게 인계된 포장입니다.");
            }
          }
          if (Set.of("PICKED_UP", "ARRIVED_AT_CUSTOMER", "COMPLETED").contains(d.state())) {
            return d.version(); // 이미 사건으로 픽업 완료
          }
          if (!ExecutionRules.PICKUP_FROM.contains(d.state())) {
            throw PmartException.invalidState("픽업할 수 없는 상태입니다(지금 " + d.state() + ").");
          }
          if (!pickup.judge(deliveryId, now)) {
            throw unprocessable(
                "HANDOVER_NOT_CONFIRMED", "아직 인계되지 않은 포장이 있습니다. 모든 포장을 인계받은 뒤 다시 눌러 주세요.");
          }
          return d.version() + 1;
        });
  }

  public Applied customerArrival(
      String deliveryId, String requestId, String source, @Nullable Integer expectedVersion) {
    return transition(
        "reportCustomerArrival",
        deliveryId,
        requestId,
        source,
        expectedVersion,
        Map.of(),
        (d, rider, now) -> {
          if (!"PICKED_UP".equals(d.state())) {
            throw PmartException.invalidState("픽업 완료한 배송만 고객 도착할 수 있습니다(지금 " + d.state() + ").");
          }
          store.setState(deliveryId, "ARRIVED_AT_CUSTOMER", now);
          store.history(deliveryId, "ARRIVED_AT_CUSTOMER", now, "RIDER", requestId, now);
          events.arrivedAtCustomer(d, now);
          return d.version() + 1;
        });
  }

  /** 증빙 사진 올리기(원문 §6.2). 형식은 파일 앞부분으로, 10MB 이하. 원본은 비공개 저장소, 여기는 참조만. */
  public Photo uploadPhoto(String deliveryId, String requestId, String source, byte[] bytes) {
    PmartPrincipal p = riderPrincipal();
    if (bytes.length == 0 || bytes.length > ExecutionRules.MAX_PHOTO_BYTES) {
      throw unprocessable("PHOTO_INVALID", "사진은 10MB 이하여야 합니다.");
    }
    String contentType =
        ExecutionRules.photoType(bytes)
            .orElseThrow(() -> unprocessable("PHOTO_INVALID", "JPEG·PNG·WEBP 사진만 올릴 수 있습니다."));
    EvidencePhotoStorage storage = photos.getIfAvailable();
    if (storage == null) {
      throw new PmartException(
          ErrorCode.DEPENDENCY_UNAVAILABLE, "증빙 사진 저장소가 설정되지 않았습니다. 지원센터에 대체 증빙을 요청해 주세요.");
    }
    return idempotency
        .execute(
            new RequestKey(source, requestId, "uploadEvidencePhoto", p.subject()),
            Map.of("deliveryId", deliveryId, "sha256", sha256(bytes)),
            Photo.class,
            () -> {
              Instant now = clock.instant();
              DeliveryRow d =
                  store.lockDelivery(deliveryId).orElseThrow(ExecutionService::notFound);
              requireRider(p, d);
              if (!ExecutionRules.PHOTO_FROM.contains(d.state())) {
                throw PmartException.invalidState(
                    "픽업 뒤 전달 전에만 사진을 올릴 수 있습니다(지금 " + d.state() + ").");
              }
              String photoRef = "EVP-" + UUID.randomUUID();
              String key = "deliveries/" + deliveryId + "/" + photoRef;
              storage.put(key, bytes, contentType);
              store.insertPhoto(
                  new PhotoRow(
                      photoRef, deliveryId, p.subject(), key, contentType, bytes.length, now));
              return new Photo(photoRef, contentType, bytes.length, now);
            })
        .value();
  }

  /** 전달 완료: 이 배송에 같은 라이더가 올린 사진 참조가 있어야 한다(자동 완료 없음) → DeliveryCompleted. */
  public Applied complete(
      String deliveryId,
      String requestId,
      String source,
      @Nullable Integer expectedVersion,
      Completion c) {
    return transition(
        "reportCompletion",
        deliveryId,
        requestId,
        source,
        expectedVersion,
        Map.of("completion", c),
        (d, rider, now) -> {
          if (!"ARRIVED_AT_CUSTOMER".equals(d.state())) {
            throw PmartException.invalidState("고객 도착한 배송만 전달 완료할 수 있습니다(지금 " + d.state() + ").");
          }
          Set<String> mine =
              store.photos(deliveryId).stream()
                  .filter(ph -> ph.riderId().equals(rider))
                  .map(PhotoRow::photoRef)
                  .collect(Collectors.toSet());
          if (c.photoRefs().isEmpty() || !mine.containsAll(c.photoRefs())) {
            throw new PmartException(
                ErrorCode.EVIDENCE_REQUIRED, "이 배송에 올린 증빙 사진이 필요합니다. 사진을 먼저 올려 주세요.");
          }
          String evidenceId = "EVD-" + UUID.randomUUID();
          Map<String, Object> evidence = new LinkedHashMap<>();
          evidence.put("evidenceRef", evidenceId);
          evidence.put("method", c.method());
          evidence.put("photoRefs", c.photoRefs());
          evidence.put("takenAt", c.takenAt().toString());
          if (c.placement() != null) {
            evidence.put("placement", c.placement());
          }
          if (c.placementDetail() != null) {
            evidence.put("placementDetail", c.placementDetail());
          }
          if (c.substituteEvidenceCaseId() != null) {
            evidence.put("substituteEvidenceCaseId", c.substituteEvidenceCaseId());
          }
          // 포장마다 마지막 인계만(다시 인계한 포장·재배차 전 라이더 인계를 두 번 세지 않는다 — PC 1 재검토 ③).
          // 인계 기록은 인계 시각 순이라 같은 포장의 뒤 기록이 앞 기록을 덮는다. 출고 포장 목록을 알면 그 포장만.
          Map<String, HandoverRow> lastByPackage = new LinkedHashMap<>();
          for (HandoverRow h : store.handovers(deliveryId)) {
            lastByPackage.put(h.packageId(), h);
          }
          Set<String> shipmentPackages = new java.util.HashSet<>();
          if (d.shipmentId() != null) {
            store
                .shipmentPackages(d.shipmentId())
                .ifPresent(
                    json -> {
                      for (var n : mapper.readTree(json)) {
                        shipmentPackages.add(n.asString());
                      }
                    });
          }
          List<Map<String, Integer>> perPackage = new ArrayList<>();
          for (HandoverRow h : lastByPackage.values()) {
            if (!shipmentPackages.isEmpty() && !shipmentPackages.contains(h.packageId())) {
              continue;
            }
            Map<String, Integer> lines = new LinkedHashMap<>();
            for (var e : mapper.readTree(h.linesJson()).properties()) {
              lines.put(e.getKey(), e.getValue().asInt());
            }
            perPackage.add(lines);
          }
          List<Map<String, Object>> delivered = ExecutionRules.deliveredLines(perPackage);
          if (delivered.isEmpty()) {
            // 계약 lines minItems 1 — 인계 수량을 모르면 완료를 내지 않는다(인계 사건에는 줄이 반드시 있어 생기지 않아야 한다)
            throw PmartException.invalidState("인계된 상품 수량을 확인할 수 없습니다. 지원센터에 문의해 주세요.");
          }
          store.completed(deliveryId, c.method(), mapper.writeValueAsString(evidence), now);
          store.history(deliveryId, "COMPLETED", now, "RIDER", evidenceId, now);
          events.completed(d, delivered, c.method(), evidenceId, c.takenAt(), now);
          earnings.completed(d, now); // 수락 당시 금액 → 확정 대기(사건 버전은 따로 올린다)
          return store.delivery(deliveryId).map(DeliveryRow::version).orElse(d.version() + 1);
        });
  }

  /**
   * 전달 실패 등록(사용자 결정 2026-11-13): 사유 + 이 배송에 올린 사진 + 고객 사유면 연락 2회 이상 → FAILURE_REPORTED(아직 진행 중 —
   * 운영자 확정 전). 사건은 DeliveryIncidentRaised(지원센터가 확정·반려를 판단하도록 — case-service 구현 전).
   */
  public Applied reportFailure(
      String deliveryId,
      String requestId,
      String source,
      @Nullable Integer expectedVersion,
      Failure f) {
    return transition(
        "reportFailure",
        deliveryId,
        requestId,
        source,
        expectedVersion,
        Map.of("failure", f),
        (d, rider, now) -> {
          if (!ExecutionRules.FAILURE_FROM.contains(d.state())) {
            throw PmartException.invalidState("픽업 뒤 전달 전에만 실패를 등록할 수 있습니다(지금 " + d.state() + ").");
          }
          Set<String> mine =
              store.photos(deliveryId).stream()
                  .filter(ph -> ph.riderId().equals(rider))
                  .map(PhotoRow::photoRef)
                  .collect(Collectors.toSet());
          if (f.photoRefs().isEmpty() || !mine.containsAll(f.photoRefs())) {
            throw new PmartException(
                ErrorCode.EVIDENCE_REQUIRED, "이 배송에 올린 사진이 필요합니다. 사진을 먼저 올려 주세요.");
          }
          if (!ExecutionRules.enoughContacts(f.reason(), f.contactAttempts().size())) {
            throw unprocessable(
                "CONTACT_ATTEMPTS_REQUIRED", "고객 연락을 2회 이상 시도하고 기록해 주세요(고객 부재·연락 두절·주소 오류·수령 거부).");
          }
          Map<String, Object> failure = new LinkedHashMap<>();
          failure.put("reason", f.reason());
          if (f.detail() != null) {
            failure.put("detail", f.detail());
          }
          failure.put("photoRefs", f.photoRefs());
          if (f.goodsLocation() != null) {
            failure.put("goodsLocation", f.goodsLocation());
          }
          failure.put("contactAttempts", f.contactAttempts());
          failure.put("reportedAt", now.toString());
          failure.put("previousState", d.state());
          failure.put("requestId", requestId);
          store.failure(deliveryId, "FAILURE_REPORTED", mapper.writeValueAsString(failure), now);
          store.history(deliveryId, "FAILURE_REPORTED", now, "RIDER", requestId, now);
          ExecutionStore.HelpRow incident =
              new ExecutionStore.HelpRow(
                  requestId,
                  deliveryId,
                  rider,
                  "DELIVERY_FAILURE",
                  f.detail(),
                  mapper.writeValueAsString(f.photoRefs()),
                  "FAILURE_REPORTED",
                  "OPEN",
                  null,
                  now);
          events.helpRequested(d, incident, f.photoRefs(), store.bumpVersion(deliveryId, now));
          return store.delivery(deliveryId).map(DeliveryRow::version).orElse(d.version() + 1);
        });
  }

  // ---- 공통

  @FunctionalInterface
  private interface Step {
    int apply(DeliveryRow before, String riderId, Instant now);
  }

  private Applied transition(
      String operation,
      String deliveryId,
      String requestId,
      String source,
      @Nullable Integer expectedVersion,
      Map<String, Object> body,
      Step step) {
    PmartPrincipal p = riderPrincipal();
    Map<String, Object> request = new LinkedHashMap<>(body);
    request.put("deliveryId", deliveryId);
    request.put("expectedVersion", String.valueOf(expectedVersion));
    return idempotency
        .execute(
            new RequestKey(source, requestId, operation, p.subject()),
            request,
            Applied.class,
            () -> {
              Instant now = clock.instant();
              DeliveryRow d =
                  store.lockDelivery(deliveryId).orElseThrow(ExecutionService::notFound);
              requireRider(p, d);
              if (expectedVersion != null && expectedVersion != d.version()) {
                throw PmartException.versionConflict("배송이 바뀌었습니다(현재 버전 " + d.version() + ").");
              }
              int version = step.apply(d, p.subject(), now);
              return new Applied(requestId, deliveryId, version, now);
            })
        .value();
  }

  private static PmartPrincipal principal() {
    return PmartPrincipal.current().orElseThrow(() -> forbidden("인증이 필요합니다."));
  }

  private static PmartPrincipal riderPrincipal() {
    PmartPrincipal p = principal();
    if (p.type() != PmartPrincipal.PrincipalType.RIDER) {
      throw forbidden("라이더만 할 수 있습니다.");
    }
    return p;
  }

  private static void requireRider(PmartPrincipal p, DeliveryRow d) {
    if (!p.subject().equals(d.riderId())) {
      throw forbidden("본인에게 배정된 배송만 처리할 수 있습니다.");
    }
  }

  static PmartException forbidden(String message) {
    return new PmartException(
        ErrorCode.FORBIDDEN.name(), HttpStatus.FORBIDDEN, message, false, List.of());
  }

  private static PmartException unprocessable(String code, String message) {
    return new PmartException(code, HttpStatus.UNPROCESSABLE_CONTENT, message, false, List.of());
  }

  private static PmartException notFound() {
    return PmartException.notFound("DELIVERY_NOT_FOUND", "배송이 없습니다.");
  }

  private static int offset(@Nullable String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return 0;
    }
    try {
      return Math.max(0, Integer.parseInt(cursor));
    } catch (NumberFormatException e) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "cursor 가 올바르지 않습니다.");
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
