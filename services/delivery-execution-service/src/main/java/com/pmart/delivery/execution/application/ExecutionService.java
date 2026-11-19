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
import java.util.Optional;
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

  /**
   * @param origin 오배송 회수 배송(DD-2)이면 원래 배송 — 그 완료 증빙(놓은 곳·사진 참조)을 회수 라이더에게 보여 준다
   */
  public record View(
      DeliveryRow delivery,
      List<HandoverRow> handovers,
      List<ExecutionStore.HistoryRow> history,
      List<ExecutionStore.HelpRow> helps,
      ExecutionStore.@Nullable EarningRow earning,
      @Nullable DeliveryRow origin,
      @Nullable String returnDeliveryId,
      List<String> shipmentPackages) {

    /**
     * 이 배송이 넘겨받을 포장 전부(DE-4, 2026-11-18 — PC 3 요청, 급함): 출고 포장 목록(ShipmentReady·PackingFinished) ∪
     * 인계 기록 포장(재포장으로 바뀐 새 ID 포함). 밀봉 전이라 출고 포장을 모르면 인계 기록만. 예전에는 인계 기록만이라 첫 인계 전에는 늘 비어 라이더 앱이 모든
     * 포장 QR 을 "이 배송의 포장이 아니에요"로 막았다.
     */
    public List<String> packageIds() {
      java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>(shipmentPackages);
      handovers.forEach(h -> all.add(h.packageId()));
      return List.copyOf(all);
    }
  }

  /** 전달 실패 등록(2026-11-13) — contactAttempts 는 [{attemptedAt, method, result}]. */
  public record Failure(
      String reason,
      @Nullable String detail,
      List<String> photoRefs,
      @Nullable String goodsLocation,
      List<Map<String, Object>> contactAttempts) {}

  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(ExecutionService.class);

  private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

  private final ExecutionStore store;
  private final ExecutionEvents events;
  private final PickupJudgement pickup;
  private final EarningService earnings;
  private final RequestIdempotency idempotency;
  private final ObjectProvider<EvidencePhotoStorage> photos;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final com.pmart.delivery.execution.infrastructure.OrderClient orders;

  private final ReturnHandoverCodes handoverCodes;

  public ExecutionService(
      ExecutionStore store,
      ExecutionEvents events,
      PickupJudgement pickup,
      EarningService earnings,
      RequestIdempotency idempotency,
      ObjectProvider<EvidencePhotoStorage> photos,
      ObjectMapper mapper,
      Clock clock,
      com.pmart.delivery.execution.infrastructure.OrderClient orders,
      ReturnHandoverCodes handoverCodes) {
    this.orders = orders;
    this.handoverCodes = handoverCodes;
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
        d.riderId() == null ? null : store.earningOf(d.deliveryId(), d.riderId()).orElse(null),
        d.originDeliveryId() == null ? null : store.delivery(d.originDeliveryId()).orElse(null),
        "FAILED".equals(d.state()) ? store.failedReturnOf(d.deliveryId()).orElse(null) : null,
        shipmentPackagesOf(d));
  }

  /** 출고 포장 목록(배송에 출고가 없거나 아직 모르면 빈 목록 — 회수·반납 배송 등). */
  List<String> shipmentPackagesOf(DeliveryRow d) {
    if (d.shipmentId() == null) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    store
        .shipmentPackages(d.shipmentId())
        .ifPresent(
            json -> {
              for (var n : mapper.readTree(json)) {
                out.add(n.asString());
              }
            });
    return out;
  }

  // ---- 조회

  /** 배정 라이더 본인, 그 센터 운영자·OPERATOR_ADMIN·CS_AGENT·RIDER_OPS, 서비스. 볼 수 없으면 있는지도 알리지 않는다(404). */
  public View view(String deliveryId) {
    PmartPrincipal p = principal();
    DeliveryRow d = store.delivery(deliveryId).orElseThrow(ExecutionService::notFound);
    boolean ok =
        switch (p.type()) {
          case SERVICE -> true;
          case OPERATOR -> Access.operatorCanSee(p, d.mfcId());
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
          forwardOnly(d);
          mfcPickupOnly(d);
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
          forwardOnly(d);
          mfcPickupOnly(d);
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
          forwardOnly(d);
          if (!"PICKED_UP".equals(d.state())) {
            throw PmartException.invalidState("픽업 완료한 배송만 고객 도착할 수 있습니다(지금 " + d.state() + ").");
          }
          store.setState(deliveryId, "ARRIVED_AT_CUSTOMER", now);
          store.history(deliveryId, "ARRIVED_AT_CUSTOMER", now, "RIDER", requestId, now);
          events.arrivedAtCustomer(d, now);
          return d.version() + 1;
        });
  }

  /**
   * 증빙 사진 올리기(원문 §6.2). 형식은 파일 앞부분으로, 10MB 이하. 원본은 비공개 저장소, 여기는 참조만.
   *
   * <p>저장소 올리기는 DB 트랜잭션 밖에서 한다(PC 1 검토 후속, 2026-11-14 — 트랜잭션 안에서 올리면 느린 저장소가 배송 행 잠금을 붙잡고, 롤백되면 객체만
   * 남는다). 순서: 잠금 없이 미리 확인 → 올리는 중 기록 → 올리기 → 트랜잭션(멱등 기록 + 잠금 아래 다시 확인 + 참조 저장 + 올리는 중 기록 지움). 같은
   * 요청(출처·키·라이더·배송·내용)은 같은 사진 참조·객체 키라 재시도가 객체를 새로 만들지 않는다.
   *
   * <p>트랜잭션이 실패해도 올린 객체를 <b>바로 지우지 않는다</b>(PC 1 PR #12 검토 후속, 2026-11-14) — 같은 요청이 동시에 와 다른 쪽이 같은
   * 객체 키로 성공했을 수 있다. 바로 지우면 참조는 있는데 파일이 없는 사진이 됐다. 참조가 끝내 없는 객체는 {@link PhotoSweeper} 가 5시간 뒤
   * 지운다(사용자 "5시간이 지나면 삭제").
   *
   * <p>이 요청의 사진이 이미 저장돼 있으면(중복 요청 기록이 없어진 뒤의 재시도 등) 그 사진을 그대로 돌려준다 — 예전에는 "다시 올려 주세요"(409)가 끝없이 반복될
   * 수 있었다.
   */
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
    String hash = sha256(bytes);
    String photoRef =
        "EVP-"
            + UUID.nameUUIDFromBytes(
                String.join("\n", source, requestId, p.subject(), deliveryId, hash)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    String key = "deliveries/" + deliveryId + "/" + photoRef;
    // 트랜잭션 밖 — 이미 저장된 요청이면(재생) 올리지 않는다. 확인이 안 되면 올리지 않고 아래 트랜잭션이 같은 거절을 낸다.
    boolean stored = store.photos(deliveryId).stream().anyMatch(x -> x.photoRef().equals(photoRef));
    boolean uploaded =
        !stored
            && store
                .delivery(deliveryId)
                .filter(d -> p.subject().equals(d.riderId()) && photoAllowed(d))
                .isPresent();
    if (uploaded) {
      store.notePhotoUpload(key, deliveryId, clock.instant());
      storage.put(key, bytes, contentType);
    }
    return idempotency
        .execute(
            new RequestKey(source, requestId, "uploadEvidencePhoto", p.subject()),
            Map.of("deliveryId", deliveryId, "sha256", hash),
            Photo.class,
            () -> {
              Instant now = clock.instant();
              DeliveryRow d =
                  store.lockDelivery(deliveryId).orElseThrow(ExecutionService::notFound);
              requireRider(p, d);
              // 같은 요청의 사진이 이미 있다 — 그대로 돌려준다(사진 참조가 출처·키·라이더·배송·내용으로 정해져 같은 요청에만 맞는다)
              Optional<PhotoRow> existing = store.photo(photoRef);
              if (existing.isPresent()) {
                PhotoRow x = existing.get();
                return new Photo(x.photoRef(), x.contentType(), x.sizeBytes(), x.uploadedAt());
              }
              if (!photoAllowed(d)) {
                throw PmartException.invalidState(
                    "RETURN".equals(d.type())
                        ? "회수 배송은 배정 뒤 반납 전에만 사진을 올릴 수 있습니다(지금 " + d.state() + ")."
                        : "픽업 뒤 전달 전에만 사진을 올릴 수 있습니다(지금 " + d.state() + ").");
              }
              if (!uploaded) {
                // 미리 확인과 잠금 사이에 상태가 바뀌었다(드묾) — 올린 사진이 없으니 다시 보내 달라고 한다
                throw PmartException.versionConflict("배송 상태가 바뀌었습니다. 사진을 다시 올려 주세요.");
              }
              store.insertPhoto(
                  new PhotoRow(
                      photoRef, deliveryId, p.subject(), key, contentType, bytes.length, now));
              store.forgetPhotoUpload(key);
              return new Photo(photoRef, contentType, bytes.length, now);
            })
        .value();
  }

  /**
   * 직원 근거 사진(2026-11-18 — 라이더 백오피스 요청 #6): 상태 정정 근거. RIDER_OPS·OPERATOR_ADMIN, 상태 무관. 저장 규칙은 라이더
   * 사진과 같다(트랜잭션 밖에서 올리고, 참조는 출처·키·직원·배송·내용으로 정해 재전송은 같은 사진). 올린 사람 = 직원 ID.
   */
  public Photo uploadStaffPhoto(String deliveryId, String requestId, String source, byte[] bytes) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .filter(x -> x.type() == PmartPrincipal.PrincipalType.OPERATOR)
            .orElseThrow(() -> forbidden("직원만 올릴 수 있습니다."));
    if (!(p.hasRole("RIDER_OPS") || p.hasRole("OPERATOR_ADMIN"))) {
      throw forbidden("근거 사진은 라이더 운영·관리자만 올릴 수 있습니다.");
    }
    if (bytes.length == 0 || bytes.length > ExecutionRules.MAX_PHOTO_BYTES) {
      throw unprocessable("PHOTO_INVALID", "사진은 10MB 이하여야 합니다.");
    }
    String contentType =
        ExecutionRules.photoType(bytes)
            .orElseThrow(() -> unprocessable("PHOTO_INVALID", "JPEG·PNG·WEBP 사진만 올릴 수 있습니다."));
    EvidencePhotoStorage storage = photos.getIfAvailable();
    if (storage == null) {
      throw new PmartException(ErrorCode.DEPENDENCY_UNAVAILABLE, "증빙 사진 저장소가 설정되지 않았습니다.");
    }
    store.delivery(deliveryId).orElseThrow(ExecutionService::notFound);
    String hash = sha256(bytes);
    String photoRef =
        "EVP-"
            + UUID.nameUUIDFromBytes(
                String.join("\n", source, requestId, "staff", p.subject(), deliveryId, hash)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    String key = "deliveries/" + deliveryId + "/" + photoRef;
    boolean stored = store.photo(photoRef).isPresent();
    if (!stored) {
      store.notePhotoUpload(key, deliveryId, clock.instant());
      storage.put(key, bytes, contentType);
    }
    return idempotency
        .execute(
            new RequestKey(source, requestId, "uploadStaffEvidencePhoto", p.subject()),
            Map.of("deliveryId", deliveryId, "sha256", hash),
            Photo.class,
            () -> {
              Optional<PhotoRow> existing = store.photo(photoRef);
              if (existing.isPresent()) {
                PhotoRow x = existing.get();
                return new Photo(x.photoRef(), x.contentType(), x.sizeBytes(), x.uploadedAt());
              }
              Instant now = clock.instant();
              store.insertPhoto(
                  new PhotoRow(
                      photoRef, deliveryId, p.subject(), key, contentType, bytes.length, now));
              store.forgetPhotoUpload(key);
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
          forwardOnly(d);
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
          // 다시 배송(ADR-0065)은 MFC 인계가 없다 — 원래 배송이 전달한 줄(같은 상품)
          List<Map<String, Object>> delivered = d.redelivery() ? originLines(d) : deliveredLines(d);
          if (delivered.isEmpty()) {
            // 계약 lines minItems 1 — 인계 수량을 모르면 완료를 내지 않는다(인계 사건에는 줄이 반드시 있어 생기지 않아야 한다)
            throw PmartException.invalidState("인계된 상품 수량을 확인할 수 없습니다. 지원센터에 문의해 주세요.");
          }
          store.completed(deliveryId, c.method(), mapper.writeValueAsString(evidence), now);
          store.history(deliveryId, "COMPLETED", now, "RIDER", evidenceId, now);
          events.completed(d, delivered, c.method(), evidenceId, c.takenAt(), now);
          headingNext(d, now);
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
          if (d.claimReturn()) {
            // 고객 반품 회수(CL-1, 2026-11-17): 고객 집에서 받기 전 — 부재·거부 등. 확정되면 ReturnPickupFailed(다시 시도는 반품
            // 쪽)
            if (!ExecutionRules.CLAIM_RETURN_FAILURE_FROM.contains(d.state())) {
              throw PmartException.invalidState(
                  "반품 회수는 고객에게서 받기 전에만 실패를 등록할 수 있습니다(지금 "
                      + d.state()
                      + "). 받은 뒤 문제는 도움요청을 써 주세요.");
            }
          } else {
            forwardOnly(d);
            if (!ExecutionRules.FAILURE_FROM.contains(d.state())) {
              throw PmartException.invalidState(
                  "픽업 뒤 전달 전에만 실패를 등록할 수 있습니다(지금 " + d.state() + ").");
            }
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
                  now,
                  null,
                  null,
                  null);
          // 전달 불가 = 지원센터 대화(2026-11-17 라이더 앱 요청 §13 — 사용자 "실패 등록이 대화도 열기"): 같은 ID 의 도움요청을 만든다.
          // 앱은 도움요청을 따로 보내지 않고 이 ID(응답 resultId)로 대화한다. 사건함에는 한 건(DeliveryIncidentRaised 하나)
          store.insertHelp(incident);
          events.helpRequested(d, incident, f.photoRefs(), store.bumpVersion(deliveryId, now));
          return store.delivery(deliveryId).map(DeliveryRow::version).orElse(d.version() + 1);
        });
  }

  /**
   * 전달 재개 요청(2026-11-17 라이더 앱 요청 §13-3): 전달 불가를 알린 뒤 고객과 다시 연락돼 전달할 수 있을 때. 라이더가 직접 되돌리지는 않는다 —
   * 지원센터가 반려(failure-decisions REJECT)해야 돌아간다. 요청 시각을 실패 기록에 남기고(운영자 대기 목록·배송 조회에 표시), 연결된 대화에 라이더
   * 메시지를 남긴다. 이미 요청했으면 그대로(처음 시각).
   */
  public Applied requestResume(
      String deliveryId,
      String requestId,
      String source,
      @Nullable Integer expectedVersion,
      @Nullable String note) {
    String text = note == null ? "" : note.strip();
    if (text.length() > 300) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "메모는 300자까지입니다.");
    }
    return transition(
        "requestFailureResume",
        deliveryId,
        requestId,
        source,
        expectedVersion,
        Map.of("note", text),
        (d, rider, now) -> {
          if (!"FAILURE_REPORTED".equals(d.state()) || d.failureJson() == null) {
            throw PmartException.invalidState(
                "전달 불가를 알린 배송만 재개를 요청할 수 있습니다(지금 " + d.state() + ").");
          }
          tools.jackson.databind.JsonNode reported = mapper.readTree(d.failureJson());
          if (reported.path("resumeRequestedAt").isString()) {
            return d.version(); // 이미 요청함
          }
          Map<String, Object> failure = new LinkedHashMap<>();
          reported.properties().forEach(en -> failure.put(en.getKey(), en.getValue()));
          failure.put("resumeRequestedAt", now.toString());
          store.failure(deliveryId, "FAILURE_REPORTED", mapper.writeValueAsString(failure), now);
          String helpId = reported.path("requestId").asString(null);
          if (helpId != null && store.help(deliveryId, helpId).isPresent()) {
            store.insertHelpMessage(
                "HM-" + UUID.randomUUID(),
                deliveryId,
                helpId,
                "RIDER",
                rider,
                "[전달 재개 요청] 고객과 다시 연락이 됐어요. 전달할 수 있어요 — 전달 불가를 반려해 주세요."
                    + (text.isEmpty() ? "" : "\n" + text),
                "[]",
                now);
          }
          return store.delivery(deliveryId).map(DeliveryRow::version).orElse(d.version() + 1);
        });
  }

  /** 사진 원본(보기). */
  public record PhotoFile(String contentType, byte[] bytes) {}

  /**
   * 증빙 사진 보기(사용자 2026-11-16 "사진보기 기능 넣어"): 이 배송에 배정된 라이더, 이 배송을 회수 중인 라이더(오배송 회수 — ASSIGNED·
   * RETURN_PICKED_UP), 그 센터·모든 센터 운영자. 그 밖·없는 사진·다른 배송의 사진은 404(있는지 알리지 않음). 내용은 로그하지 않는다.
   */
  public PhotoFile photoFile(String deliveryId, String photoRef) {
    PmartPrincipal p = principal();
    DeliveryRow d = store.delivery(deliveryId).orElseThrow(ExecutionService::photoNotFound);
    boolean ok =
        switch (p.type()) {
          case OPERATOR -> Access.operatorCanSee(p, d.mfcId());
          case RIDER ->
              p.subject().equals(d.riderId())
                  || store.recoveringRiders(deliveryId).contains(p.subject());
          // 고객(2026-11-17): 본인 배송의 전달 완료 증빙 사진만
          case CUSTOMER ->
              customerOwns(p, d)
                  && "COMPLETED".equals(d.state())
                  && completionPhotos(d).contains(photoRef);
          default -> false;
        };
    if (!ok) {
      throw photoNotFound();
    }
    PhotoRow photo =
        store
            .photo(photoRef)
            .filter(x -> x.deliveryId().equals(deliveryId))
            .orElseThrow(ExecutionService::photoNotFound);
    EvidencePhotoStorage storage = photos.getIfAvailable();
    if (storage == null) {
      throw new PmartException(ErrorCode.DEPENDENCY_UNAVAILABLE, "증빙 사진 저장소가 설정되지 않았습니다.");
    }
    byte[] bytes = storage.get(photo.objectKey()).orElseThrow(ExecutionService::photoNotFound);
    return new PhotoFile(photo.contentType(), bytes);
  }

  /** 고객용 전달 완료 증빙(2026-11-17 사용자 "본인 배송 완료 사진 공개"). */
  public record DeliveryProof(
      String deliveryId,
      String orderId,
      @Nullable Instant completedAt,
      @Nullable String placement,
      @Nullable String placementDetail,
      List<String> photoRefs) {}

  public DeliveryProof deliveryProof(String deliveryId) {
    PmartPrincipal p = principal();
    if (p.type() != PmartPrincipal.PrincipalType.CUSTOMER) {
      throw forbidden("고객만 볼 수 있습니다.");
    }
    DeliveryRow d =
        store
            .delivery(deliveryId)
            .filter(x -> customerOwns(p, x))
            .orElseThrow(() -> PmartException.notFound("DELIVERY_NOT_FOUND", "배송을 찾을 수 없습니다."));
    if (!"COMPLETED".equals(d.state()) || d.evidenceJson() == null) {
      throw new PmartException(
          "DELIVERY_NOT_COMPLETED",
          org.springframework.http.HttpStatus.CONFLICT,
          "아직 전달 완료 전입니다.",
          false,
          List.of());
    }
    tools.jackson.databind.JsonNode e = mapper.readTree(d.evidenceJson());
    return new DeliveryProof(
        d.deliveryId(),
        java.util.Objects.requireNonNull(d.orderId()),
        d.completedAt(),
        e.path("placement").isString() ? e.path("placement").asString() : null,
        e.path("placementDetail").isString() ? e.path("placementDetail").asString() : null,
        completionPhotos(d));
  }

  /** 본인 주문의 배송인가(order 내부 accountId). 주문 없는 배송은 아니다. */
  /** 고객 수령 확인·자동 완료가 받는 상태(픽업 뒤·전달 전 — 실패 등록 중은 아님). */
  private static final Set<String> RECEIPT_FROM = Set.of("PICKED_UP", "ARRIVED_AT_CUSTOMER");

  /**
   * 고객이 "받았어요"(2026-11-17 — 사용자 "도착예정 20분 지나면 고객에게 묻고 고객이 전달완료를 눌러주면 라이더앱에도 반영"): COMPLETED(method
   * CUSTOMER_CONFIRMED, 사진 없음) + DeliveryCompleted + 수행료(수락 당시 금액).
   */
  public Applied confirmReceipt(String deliveryId, String requestId, String source) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .filter(x -> x.type() == PmartPrincipal.PrincipalType.CUSTOMER)
            .orElseThrow(() -> forbidden("주문한 고객만 수령을 확인할 수 있습니다."));
    // 소유 확인은 잠금 밖(order 호출)
    DeliveryRow peek =
        store
            .delivery(deliveryId)
            .filter(x -> customerOwns(p, x))
            .orElseThrow(() -> PmartException.notFound("DELIVERY_NOT_FOUND", "배송을 찾을 수 없습니다."));
    return idempotency
        .execute(
            new RequestKey(source, requestId, "confirmDeliveryReceipt", p.subject()),
            Map.of("deliveryId", peek.deliveryId()),
            Applied.class,
            () -> {
              Instant now = clock.instant();
              DeliveryRow d = store.lockDelivery(deliveryId).orElseThrow();
              if (!RECEIPT_FROM.contains(d.state()) || "RETURN".equals(d.type())) {
                throw PmartException.invalidState("받음 확인을 할 수 없는 배송입니다(지금 " + d.state() + ").");
              }
              int v =
                  finishWithoutPhoto(
                      d,
                      "CUSTOMER_CONFIRMED",
                      "CUSTOMER",
                      new com.pmart.common.messaging.EventEnvelope.Actor(
                          com.pmart.common.messaging.EventEnvelope.ActorType.CUSTOMER, p.subject()),
                      now);
              earnings.completed(d, now);
              log.info("배송 {} 고객 수령 확인 → 완료", deliveryId);
              return new Applied(requestId, deliveryId, v, now);
            })
        .value();
  }

  /**
   * 자동 전달 완료(2026-11-17 — 사용자 "5시간 지나면 자동완료"): 픽업 뒤 5시간 지나도록 완료·실패 등록이 없고 열린 도움요청이 없는 배송. 원문 §6.4
   * "시간 경과만으로 수익까지 자동 확정하지 않음" — 수행료는 산정 불가(지원센터 조정). 다른 처리와 겹치면 아무것도 하지 않는다.
   */
  @org.springframework.transaction.annotation.Transactional
  public boolean autoComplete(String deliveryId, Instant pickedBefore, Instant now) {
    DeliveryRow d = store.lockDelivery(deliveryId).orElse(null);
    if (d == null
        || !RECEIPT_FROM.contains(d.state())
        || "RETURN".equals(d.type())
        || d.pickedUpAt() == null
        || !d.pickedUpAt().isBefore(pickedBefore)) {
      return false;
    }
    boolean openHelp =
        store.helps(deliveryId).stream()
            .anyMatch(h -> "OPEN".equals(h.state()) || "IN_PROGRESS".equals(h.state()));
    if (openHelp) {
      return false;
    }
    try {
      finishWithoutPhoto(
          d,
          "AUTO_COMPLETED",
          "SYSTEM_EVENT",
          new com.pmart.common.messaging.EventEnvelope.Actor(
              com.pmart.common.messaging.EventEnvelope.ActorType.SYSTEM,
              "delivery-execution-service"),
          now);
    } catch (PmartException e) {
      log.warn("배송 {} 자동 완료 못 함({})", deliveryId, e.code());
      return false;
    }
    earnings.notCalculable(d, "AUTO_COMPLETED", now);
    log.info("배송 {} 픽업 뒤 오래 완료되지 않아 자동 완료", deliveryId);
    return true;
  }

  private int finishWithoutPhoto(
      DeliveryRow d,
      String method,
      String historyActor,
      com.pmart.common.messaging.EventEnvelope.Actor actor,
      Instant now) {
    List<Map<String, Object>> delivered = d.redelivery() ? originLines(d) : deliveredLines(d);
    if (delivered.isEmpty()) {
      throw PmartException.invalidState("인계된 상품 수량을 확인할 수 없습니다. 지원센터에 문의해 주세요.");
    }
    String evidenceId = "EVD-" + UUID.randomUUID();
    Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("evidenceRef", evidenceId);
    evidence.put("method", method);
    evidence.put("photoRefs", List.of());
    evidence.put("takenAt", now.toString());
    store.completed(d.deliveryId(), method, mapper.writeValueAsString(evidence), now);
    store.history(d.deliveryId(), "COMPLETED", now, historyActor, evidenceId, now);
    events.completed(d, delivered, method, evidenceId, "DOCUMENT", now, now, actor);
    headingNext(d, now);
    return store.delivery(d.deliveryId()).map(DeliveryRow::version).orElse(d.version() + 1);
  }

  /**
   * DE-6(2026-11-19 사용자 "응 해줘"): 한 라이더가 여러 배송을 들고 있을 때 하나를 끝내면 다음 차례 고객에게 "가고 있어요" 한 번
   * (DeliveryHeadingToCustomer). 단건 배송은 다음이 없어 내지 않는다 — 배송 출발 알림이 그 역할.
   */
  private void headingNext(DeliveryRow done, Instant now) {
    if (done.riderId() == null) {
      return;
    }
    store
        .nextHeading(done.riderId(), done.deliveryId())
        .flatMap(store::delivery)
        .filter(n -> store.markHeading(n.deliveryId(), now))
        .ifPresent(n -> events.headingToCustomer(n, now));
  }

  private boolean customerOwns(PmartPrincipal p, DeliveryRow d) {
    return d.orderId() != null
        && orders.accountId(d.orderId()).map(p.subject()::equals).orElse(false);
  }

  /** 전달 완료 증빙의 사진 참조(회수·실패 사진은 아님). */
  private List<String> completionPhotos(DeliveryRow d) {
    if (d.evidenceJson() == null || "RETURN".equals(d.type())) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    for (tools.jackson.databind.JsonNode n : mapper.readTree(d.evidenceJson()).path("photoRefs")) {
      out.add(n.asString());
    }
    return out;
  }

  private static PmartException photoNotFound() {
    return PmartException.notFound("PHOTO_NOT_FOUND", "사진이 없습니다.");
  }

  // ---- 오배송 회수(DD-2, 2026-11-16 — 사용자 "추천대로")

  /**
   * 회수지에서 회수품을 받음: ASSIGNED → RETURN_PICKED_UP + ReturnPickedUp. 이 배송에 같은 라이더가 올린 사진 1장 이상(아니면 422
   * EVIDENCE_REQUIRED). 사건 lines = 원래 배송이 전달한 줄·수량(회수 대상), 고객 반품 회수(CLAIM_RETURN, 2026-11-17)는
   * claim 이 요청한 줄.
   */
  public Applied returnPickup(
      String deliveryId,
      String requestId,
      String source,
      @Nullable Integer expectedVersion,
      List<String> evidenceRefs) {
    return transition(
        "reportReturnPickup",
        deliveryId,
        requestId,
        source,
        expectedVersion,
        Map.of("evidenceRefs", evidenceRefs),
        (d, rider, now) -> {
          if (d.failedReturn() && !byOtherRider(d)) {
            throw PmartException.invalidState("전달 실패 반납은 상품을 이미 가지고 있어 받음 없이 MFC 반납만 합니다.");
          }
          if (!d.redelivery() && !d.claimReturn() && !d.failedReturn()) {
            recoveryOnly(d);
          }
          if (!"ASSIGNED".equals(d.state())) {
            throw PmartException.invalidState("배정된 회수·다시 배송만 받을 수 있습니다(지금 " + d.state() + ").");
          }
          requireMyPhotos(deliveryId, rider, evidenceRefs, true);
          List<Map<String, Object>> lines = returnLines(d);
          String evidenceId = "EVD-" + UUID.randomUUID();
          Map<String, Object> evidence = new LinkedHashMap<>();
          evidence.put(
              "pickup",
              Map.of("evidenceRef", evidenceId, "photoRefs", evidenceRefs, "at", now.toString()));
          // 다시 배송은 받은 뒤 일반 배송처럼 고객 도착·전달 완료(PICKED_UP), 회수는 MFC 반납(RETURN_PICKED_UP)
          String next = d.redelivery() ? "PICKED_UP" : "RETURN_PICKED_UP";
          store.returnPickedUp(deliveryId, next, mapper.writeValueAsString(evidence), now);
          store.history(deliveryId, next, now, "RIDER", evidenceId, now);
          events.returnPickedUp(d, lines, evidenceId, now);
          return d.version() + 1;
        });
  }

  /**
   * MFC 반납: RETURN_PICKED_UP → RETURNED_TO_STORE(배송 끝) + ReturnDelivered + 수행료(수락 당시 금액 → 확정 대기).
   * mfc-inbound 점원 수령 확인이 생기기 전까지 MFC 도착을 반납으로 본다(사용자 2026-11-16 "추천대로"). 사진·점원 확인 참조는 선택.
   */
  public Applied returnToStore(
      String deliveryId,
      String requestId,
      String source,
      @Nullable Integer expectedVersion,
      List<String> evidenceRefs,
      @Nullable String receivedByStaffRef,
      @Nullable String handoverCode) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("evidenceRefs", evidenceRefs);
    body.put("receivedByStaffRef", String.valueOf(receivedByStaffRef));
    body.put("handoverCodeGiven", handoverCode != null); // 번호 자체는 남기지 않는다
    return transition(
        "reportReturnStoreDelivery",
        deliveryId,
        requestId,
        source,
        expectedVersion,
        body,
        (d, rider, now) -> {
          if (!d.claimReturn() && !d.failedReturn()) {
            recoveryOnly(d);
          }
          // 전달 실패 반납(2026-11-17)은 상품이 이미 라이더 손 — 배정(ASSIGNED)에서 바로 반납
          if (!"RETURN_PICKED_UP".equals(d.state())
              && !(d.failedReturn() && !byOtherRider(d) && "ASSIGNED".equals(d.state()))) {
            throw PmartException.invalidState("회수한 배송만 반납할 수 있습니다(지금 " + d.state() + ").");
          }
          handoverCodes.verify(deliveryId, handoverCode, now); // MFC 반납 인증번호(0.27.0)
          requireMyPhotos(deliveryId, rider, evidenceRefs, false);
          List<Map<String, Object>> lines = returnLines(d);
          String evidenceId = "EVD-" + UUID.randomUUID();
          Map<String, Object> evidence = new LinkedHashMap<>();
          if (d.evidenceJson() != null) {
            for (var e : mapper.readTree(d.evidenceJson()).properties()) {
              evidence.put(e.getKey(), e.getValue());
            }
          }
          Map<String, Object> returned = new LinkedHashMap<>();
          returned.put("evidenceRef", evidenceId);
          returned.put("photoRefs", evidenceRefs);
          if (receivedByStaffRef != null) {
            returned.put("receivedByStaffRef", receivedByStaffRef);
          }
          returned.put("at", now.toString());
          evidence.put("returned", returned);
          store.returnedToStore(deliveryId, mapper.writeValueAsString(evidence), now);
          store.history(deliveryId, "RETURNED_TO_STORE", now, "RIDER", evidenceId, now);
          events.returnDelivered(d, lines, evidenceId, now);
          if (d.failedReturn() && !byOtherRider(d) && !customerCausedFailure(d)) {
            // 전달 실패 반납: 고객 사유가 아닌 실패면 앞 배송처럼 지원센터 조정(사용자 2026-11-17 "기존대로 수행료 조정")
            earnings.notCalculable(d, "RETURNED_TO_STORE", now);
          } else {
            earnings.completed(d, now); // 회수 수행료 = 수락 당시 금액(일반 표, 회수지 → MFC — 사용자 2026-11-16)
          }
          return store.delivery(deliveryId).map(DeliveryRow::version).orElse(d.version() + 1);
        });
  }

  /**
   * 회수 대상 줄·수량: 고객 반품 회수는 claim 회수 요청 lines(CL-1), 오배송 회수·다시 배송은 원래 배송이 전달한 줄. 모르면 회수를 내지 않는다(사건
   * lines 최소 1).
   */
  private List<Map<String, Object>> returnLines(DeliveryRow d) {
    if (!d.claimReturn()) {
      return originLines(d);
    }
    List<Map<String, Object>> lines = new ArrayList<>();
    if (d.returnLinesJson() != null) {
      for (var n : mapper.readTree(d.returnLinesJson())) {
        // claimLineId·sku 등 claim 이 실은 칸은 그대로(claim 이 줄을 잇는다 — DD-3)
        Map<String, Object> l = new LinkedHashMap<>();
        for (var f : n.properties()) {
          tools.jackson.databind.JsonNode v = f.getValue();
          l.put(f.getKey(), v.isIntegralNumber() ? (Object) v.asLong() : v.asString());
        }
        l.put("qty", n.path("qty").asInt());
        lines.add(l);
      }
    }
    if (lines.isEmpty()) {
      throw PmartException.invalidState("반품 회수할 상품을 확인할 수 없습니다. 지원센터에 문의해 주세요.");
    }
    return lines;
  }

  /** 원래 배송이 전달한 줄·수량(회수 대상). 모르면 회수를 내지 않는다(사건 lines 최소 1). */
  private List<Map<String, Object>> originLines(DeliveryRow d) {
    DeliveryRow origin =
        d.originDeliveryId() == null ? null : store.delivery(d.originDeliveryId()).orElse(null);
    List<Map<String, Object>> lines = origin == null ? List.of() : deliveredLines(origin);
    if (lines.isEmpty()) {
      throw PmartException.invalidState("원래 배송의 전달 수량을 확인할 수 없습니다. 지원센터에 문의해 주세요.");
    }
    return lines;
  }

  /**
   * 배송이 전달한 줄·수량 — 포장마다 마지막 인계만(다시 인계한 포장·재배차 전 라이더 인계를 두 번 세지 않는다 — PC 1 재검토 ③). 인계 기록은 인계 시각 순이라
   * 같은 포장의 뒤 기록이 앞 기록을 덮는다. 출고 포장 목록을 알면 그 포장만.
   */
  private List<Map<String, Object>> deliveredLines(DeliveryRow d) {
    Map<String, HandoverRow> lastByPackage = new LinkedHashMap<>();
    for (HandoverRow h : store.handovers(d.deliveryId())) {
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
    return ExecutionRules.deliveredLines(perPackage);
  }

  private void requireMyPhotos(
      String deliveryId, String rider, List<String> refs, boolean atLeastOne) {
    Set<String> mine =
        store.photos(deliveryId).stream()
            .filter(ph -> ph.riderId().equals(rider))
            .map(PhotoRow::photoRef)
            .collect(Collectors.toSet());
    if ((atLeastOne && refs.isEmpty()) || !mine.containsAll(refs)) {
      throw new PmartException(ErrorCode.EVIDENCE_REQUIRED, "이 배송에 올린 사진이 필요합니다. 사진을 먼저 올려 주세요.");
    }
  }

  /** 사진 올리기 상태 — 다시 배송은 잘못 둔 곳(배정)부터 전달 전까지. */
  private static boolean photoAllowed(DeliveryRow d) {
    return d.redelivery()
        ? java.util.Set.of("ASSIGNED", "PICKED_UP", "ARRIVED_AT_CUSTOMER").contains(d.state())
        : ExecutionRules.photoAllowed(d.type(), d.state());
  }

  /** 가게 도착·MFC 인계 픽업은 다시 배송(ADR-0065)에 없다 — 잘못 둔 곳에서 받음(returns/pickups). */
  private static void mfcPickupOnly(DeliveryRow d) {
    if (d.redelivery()) {
      throw PmartException.invalidState("오배송 다시 배송입니다. 잘못 둔 곳에서 받음(returns/pickups)을 써 주세요.");
    }
  }

  /** 일반 배송 단계(가게 도착·픽업·고객 도착·전달 완료·실패 등록)는 회수 배송에 쓰지 않는다. */
  private static void forwardOnly(DeliveryRow d) {
    if ("RETURN".equals(d.type())) {
      throw PmartException.invalidState("회수 배송입니다. 회수·반납 단계를 써 주세요.");
    }
  }

  /** 전달 실패 반납을 원래 라이더가 아닌 다른 라이더가 맡았나(2026-11-17 — 그 라이더는 받음 사진 → 반납, 수행료는 그대로). */
  private boolean byOtherRider(DeliveryRow d) {
    return d.failedReturn()
        && d.riderId() != null
        && Optional.ofNullable(d.originDeliveryId())
            .flatMap(store::delivery)
            .map(DeliveryRow::riderId)
            .filter(r -> !r.equals(d.riderId()))
            .isPresent();
  }

  /** 전달 실패 반납의 앞 배송이 고객 사유(부재·연락 두절·주소 오류·거부)로 확정됐나. */
  private boolean customerCausedFailure(DeliveryRow d) {
    return Optional.ofNullable(d.originDeliveryId())
        .flatMap(store::delivery)
        .map(DeliveryRow::failureJson)
        .map(j -> mapper.readTree(j).path("reason").asString(""))
        .filter(ExecutionRules.CUSTOMER_REASONS::contains)
        .isPresent();
  }

  private static void recoveryOnly(DeliveryRow d) {
    if (!d.recovery()) {
      throw unprocessable("RETURN_NOT_SUPPORTED", "회수 배송(오배송 회수·고객 반품 회수)만 회수·반납할 수 있습니다.");
    }
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
