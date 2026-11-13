package com.pmart.rider.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.rider.domain.OperatingRules;
import com.pmart.rider.domain.Rider;
import com.pmart.rider.domain.Rider.Area;
import com.pmart.rider.infrastructure.RiderStore;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 라이더 가입·프로필·운행 상태·배차 설정(rider-service.yaml 0.2.0).
 *
 * <ul>
 *   <li>주체 = 라이더 토큰 sub(RD-…, rider-auth-service). 가입은 전화번호 중복만 확인(RS-2, 사용자 결정 2026-11-10), 즉시 운행
 *       승인(원문 §1.2). 본인확인은 아직 없음(사용자 2026-11-13).
 *   <li>운행 상태 변경은 {@link OperatingRules} → 행 버전 조건 갱신 + {@code
 *       RiderStateChanged}(delivery.rider.v1) 한 트랜잭션.
 *   <li>모든 변경은 {@link RequestIdempotency} 안 — 같은 키·같은 본문은 같은 결과.
 * </ul>
 */
@Service
public class RiderService {

  public record Signup(
      @Nullable String name,
      String phone,
      String vehicleType,
      @Nullable String bankCode,
      @Nullable String bankAccountNumber,
      @Nullable String bankHolderName,
      @Nullable String residenceRoad,
      @Nullable String residenceDetail,
      List<Area> areas,
      List<Map<String, String>> consents) {}

  public record ProfileChange(
      @Nullable String vehicleType,
      @Nullable String bankCode,
      @Nullable String bankAccountNumber,
      @Nullable String bankHolderName,
      @Nullable String residenceRoad,
      @Nullable String residenceDetail,
      @Nullable List<Area> areas) {}

  public record StateChange(String status, boolean newDispatchEnabled, @Nullable String mfcId) {}

  public record SettingsChange(@Nullable Boolean oneAtATime, @Nullable String dispatchMode) {}

  private final RiderStore store;
  private final RequestIdempotency idempotency;
  private final EventPublisher events;
  private final Clock clock;

  public RiderService(
      RiderStore store, RequestIdempotency idempotency, EventPublisher events, Clock clock) {
    this.store = store;
    this.idempotency = idempotency;
    this.events = events;
    this.clock = clock;
  }

  // ---------- 가입 ----------

  public Rider signup(String requestId, String sourceSystem, Signup cmd) {
    String riderId = requireRider();
    if (cmd.areas().isEmpty()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "배달지역을 하나 이상 고르세요.");
    }
    String id =
        idempotency
            .execute(
                new RequestKey(sourceSystem, requestId, "createRiderSignup", riderId),
                cmd,
                String.class,
                () -> {
                  if (store.find(riderId).isPresent()) {
                    throw duplicate("이미 가입한 라이더입니다.");
                  }
                  if (store.phoneTaken(cmd.phone())) {
                    throw duplicate("같은 전화번호로 가입한 라이더가 있습니다.");
                  }
                  try {
                    store.insert(
                        new RiderStore.NewRider(
                            riderId,
                            requestId,
                            cmd.name(),
                            cmd.phone(),
                            cmd.vehicleType(),
                            cmd.bankCode(),
                            cmd.bankAccountNumber(),
                            cmd.bankHolderName(),
                            cmd.residenceRoad(),
                            cmd.residenceDetail(),
                            cmd.areas(),
                            cmd.consents().stream()
                                .map(c -> new String[] {c.get("consentType"), c.get("version")})
                                .filter(c -> c[0] != null && c[1] != null)
                                .toList(),
                            clock.instant()));
                  } catch (DuplicateKeyException e) {
                    throw duplicate("이미 가입한 라이더이거나 같은 전화번호가 있습니다.");
                  }
                  return riderId;
                })
            .value();
    return get(id);
  }

  public Rider signupResult(String requestId) {
    String riderId = requireRider();
    return store
        .findBySignupRequest(riderId, requestId)
        .orElseThrow(() -> PmartException.notFound("SIGNUP_NOT_FOUND", "가입 요청이 없습니다."));
  }

  // ---------- 본인 ----------

  public Rider me() {
    return get(requireRider());
  }

  @Transactional
  public Rider updateProfile(
      String requestId, String sourceSystem, @Nullable Integer expectedVersion, ProfileChange c) {
    String riderId = requireRider();
    idempotency.execute(
        new RequestKey(sourceSystem, requestId, "updateMyRider", riderId),
        Map.of("change", c, "expectedVersion", String.valueOf(expectedVersion)),
        String.class,
        () -> {
          Rider r = locked(riderId, expectedVersion);
          if (c.vehicleType() != null
              && !c.vehicleType().equals(r.vehicleType())
              && r.operatingStatus() == Rider.OperatingStatus.ON_DUTY) {
            // 진행 중 배송 수는 execution 사본이 아직 없어 운행 중이면 막는다(배송 중 수단 변경 불가 — 원문 §8.3 보수적 적용)
            throw PmartException.invalidState("운행 중에는 배달 수단을 바꿀 수 없습니다. 운행을 종료한 뒤 바꾸세요.");
          }
          store.updateProfile(
              riderId,
              r.version(),
              c.vehicleType() == null ? r.vehicleType() : c.vehicleType(),
              c.bankCode() == null ? r.bankCode() : c.bankCode(),
              c.bankAccountNumber() == null ? r.bankAccountNumber() : c.bankAccountNumber(),
              c.bankHolderName() == null ? r.bankHolderName() : c.bankHolderName(),
              c.residenceRoad() == null ? r.residenceRoad() : c.residenceRoad(),
              c.residenceDetail() == null ? r.residenceDetail() : c.residenceDetail());
          if (c.areas() != null) {
            if (c.areas().isEmpty()) {
              throw new PmartException(ErrorCode.VALIDATION_FAILED, "배달지역을 하나 이상 두세요.");
            }
            store.replaceAreas(riderId, c.areas());
          }
          return riderId;
        });
    return get(riderId);
  }

  @Transactional
  public Rider setOperating(
      String requestId, String sourceSystem, @Nullable Integer expectedVersion, StateChange c) {
    String riderId = requireRider();
    idempotency.execute(
        new RequestKey(sourceSystem, requestId, "setMyOperatingState", riderId),
        Map.of("change", c, "expectedVersion", String.valueOf(expectedVersion)),
        String.class,
        () -> {
          Rider r = locked(riderId, expectedVersion);
          OperatingRules.Decision d =
              OperatingRules.decide(
                  r, Rider.OperatingStatus.valueOf(c.status()), c.newDispatchEnabled(), c.mfcId());
          if (d.rejection() == OperatingRules.Rejection.RESTRICTED) {
            throw new PmartException(
                ErrorCode.FORBIDDEN.name(),
                HttpStatus.FORBIDDEN,
                "계정 제한(" + r.restriction() + ")으로 운행 시작·신규배차를 켤 수 없습니다.",
                false,
                List.of());
          }
          if (d.rejection() == OperatingRules.Rejection.MFC_NOT_IN_AREAS) {
            throw new PmartException(ErrorCode.VALIDATION_FAILED, "배달지역에 없는 MFC 입니다.");
          }
          Instant now = clock.instant();
          store.updateOperating(
              riderId, r.version(), d.status().name(), d.newDispatchEnabled(), d.mfcId(), now);
          Map<String, Object> p = new LinkedHashMap<>();
          p.put("riderId", riderId);
          p.put("operatingState", d.status().name());
          p.put("newDispatchEnabled", d.newDispatchEnabled());
          p.put("vehicleType", r.vehicleType());
          if (d.mfcId() != null) {
            p.put("mfcId", d.mfcId());
          }
          p.put("changedAt", now.toString());
          events.publish(
              "RiderStateChanged",
              1,
              "Rider",
              riderId,
              r.version() + 1L,
              new EventEnvelope.Actor(EventEnvelope.ActorType.RIDER, riderId),
              p);
          return riderId;
        });
    return get(riderId);
  }

  @Transactional
  public Rider setSettings(
      String requestId, String sourceSystem, @Nullable Integer expectedVersion, SettingsChange c) {
    String riderId = requireRider();
    idempotency.execute(
        new RequestKey(sourceSystem, requestId, "setMyDispatchSettings", riderId),
        Map.of("change", c, "expectedVersion", String.valueOf(expectedVersion)),
        String.class,
        () -> {
          Rider r = locked(riderId, expectedVersion);
          store.updateSettings(
              riderId,
              r.version(),
              c.oneAtATime() == null ? r.oneAtATime() : c.oneAtATime(),
              c.dispatchMode() == null ? r.dispatchMode().name() : c.dispatchMode());
          return riderId;
        });
    return get(riderId);
  }

  // ---------- 내부(dispatch) ----------

  public List<Rider> eligible(
      String mfcId, @Nullable String vehicleType, List<String> exclude, int size) {
    return store
        .eligible(mfcId, vehicleType, Math.max(1, Math.min(size, 200)) + exclude.size())
        .stream()
        .filter(r -> !exclude.contains(r.riderId()))
        .limit(Math.max(1, Math.min(size, 200)))
        .toList();
  }

  public Rider internal(String riderId) {
    return get(riderId);
  }

  // ---------- 공통 ----------

  private Rider get(String riderId) {
    return store
        .find(riderId)
        .orElseThrow(() -> PmartException.notFound("RIDER_NOT_FOUND", "가입한 라이더가 아닙니다."));
  }

  private Rider locked(String riderId, @Nullable Integer expectedVersion) {
    Rider r =
        store
            .lock(riderId)
            .orElseThrow(() -> PmartException.notFound("RIDER_NOT_FOUND", "가입한 라이더가 아닙니다."));
    if (expectedVersion != null && expectedVersion != r.version()) {
      throw PmartException.versionConflict(
          "라이더 정보가 바뀌었습니다(현재 버전 " + r.version() + "). 다시 조회한 뒤 바꾸세요.");
    }
    return r;
  }

  private static String requireRider() {
    PmartPrincipal p =
        PmartPrincipal.current()
            .orElseThrow(
                () ->
                    new PmartException(
                        ErrorCode.FORBIDDEN.name(),
                        HttpStatus.FORBIDDEN,
                        "라이더 토큰이 필요합니다.",
                        false,
                        List.of()));
    if (p.type() != PmartPrincipal.PrincipalType.RIDER) {
      throw new PmartException(
          ErrorCode.FORBIDDEN.name(), HttpStatus.FORBIDDEN, "라이더만 쓸 수 있습니다.", false, List.of());
    }
    return p.subject();
  }

  private static PmartException duplicate(String message) {
    return new PmartException("DUPLICATE_RIDER", HttpStatus.CONFLICT, message, false, List.of());
  }
}
