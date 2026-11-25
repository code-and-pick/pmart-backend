package com.pmart.rider.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.rider.domain.LicenseRules;
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
import tools.jackson.databind.ObjectMapper;

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

  private final LicenseHasher licenses;

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
      List<Map<String, String>> consents,
      @Nullable String licenseNumber,
      @Nullable String phoneVerificationId) {

    public Signup(
        @Nullable String name,
        String phone,
        String vehicleType,
        @Nullable String bankCode,
        @Nullable String bankAccountNumber,
        @Nullable String bankHolderName,
        @Nullable String residenceRoad,
        @Nullable String residenceDetail,
        List<Area> areas,
        List<Map<String, String>> consents,
        @Nullable String licenseNumber) {
      this(
          name,
          phone,
          vehicleType,
          bankCode,
          bankAccountNumber,
          bankHolderName,
          residenceRoad,
          residenceDetail,
          areas,
          consents,
          licenseNumber,
          null);
    }
  }

  public record ProfileChange(
      @Nullable String vehicleType,
      @Nullable String bankCode,
      @Nullable String bankAccountNumber,
      @Nullable String bankHolderName,
      @Nullable String residenceRoad,
      @Nullable String residenceDetail,
      @Nullable List<Area> areas,
      @Nullable String licenseNumber,
      /** 0.28.0 — 확인된 번호를 붙인다(phoneVerificationId 와 같이). */
      @Nullable String phone,
      @Nullable String phoneVerificationId) {}

  public record StateChange(String status, boolean newDispatchEnabled, @Nullable String mfcId) {}

  public record SettingsChange(@Nullable Boolean oneAtATime, @Nullable String dispatchMode) {}

  /** 계정 제한 바꾸기(RS-1). restriction NORMAL = 풀기. */
  public record RestrictionChange(
      String restriction,
      String reason,
      @Nullable Instant until,
      List<String> evidenceRefs,
      @Nullable String approvalRef) {}

  /** 계정 제한 이력 + 지금 제한. */
  /** 지금 제한·지금 행 버전(X-Expected-Version 기준, 0.4.0 BOD-6)·이력. */
  public record Restrictions(
      Rider.Restriction current, int version, List<RiderStore.RestrictionRow> items) {}

  private final RiderStore store;
  private final org.springframework.beans.factory.ObjectProvider<PhoneVerificationService> phones;
  private final RequestIdempotency idempotency;
  private final EventPublisher events;
  private final ObjectMapper json;
  private final Clock clock;

  private final com.pmart.rider.infrastructure.AddressClient addresses;
  private final com.pmart.rider.infrastructure.CenterClient centers;
  private final com.pmart.rider.infrastructure.ExecutionClient executions;
  private final com.pmart.rider.infrastructure.DispatchClient dispatch;
  private final LocationAreas locationAreas;

  public RiderService(
      RiderStore store,
      RequestIdempotency idempotency,
      EventPublisher events,
      ObjectMapper json,
      Clock clock,
      LicenseHasher licenses,
      com.pmart.rider.infrastructure.AddressClient addresses,
      org.springframework.beans.factory.ObjectProvider<PhoneVerificationService> phones,
      com.pmart.rider.infrastructure.CenterClient centers,
      com.pmart.rider.infrastructure.ExecutionClient executions,
      com.pmart.rider.infrastructure.DispatchClient dispatch,
      LocationAreas locationAreas) {
    this.locationAreas = locationAreas;
    this.dispatch = dispatch;
    this.phones = phones;
    this.centers = centers;
    this.executions = executions;
    this.addresses = addresses;
    this.licenses = licenses;
    this.store = store;
    this.idempotency = idempotency;
    this.events = events;
    this.json = json;
    this.clock = clock;
  }

  // ---------- 가입 ----------

  public Rider signup(String requestId, String sourceSystem, Signup cmd) {
    String riderId = requireRider();
    if (cmd.areas().isEmpty()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "배달지역을 하나 이상 고르세요.");
    }
    requireBankCode(cmd.bankCode());
    requireRealAddress(cmd.residenceRoad()); // 외부 호출 — 트랜잭션 밖
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
                  if (store.withdrawn(riderId)) {
                    // 탈퇴한 주체(2026-11-17) — 다시 로그인하면 새 라이더 주체로 가입한다
                    throw new PmartException(
                        "RIDER_WITHDRAWN",
                        HttpStatus.CONFLICT,
                        "탈퇴한 계정입니다. 다시 로그인한 뒤 가입해 주세요.",
                        false,
                        List.of());
                  }
                  // 같은 번호의 다른 계정·탈퇴 30일(2026-11-19 — R3·R4), 번호 확인(R2 — 설정 required 면 필수)
                  phones.getObject().requireJoinable(riderId, cmd.phone());
                  boolean verified =
                      phones
                          .getObject()
                          .requireForSignup(riderId, cmd.phone(), cmd.phoneVerificationId());
                  if (store.phoneTaken(cmd.phone())) {
                    throw duplicate("같은 전화번호로 가입한 라이더가 있습니다.");
                  }
                  requireAccountFree(cmd.bankCode(), cmd.bankAccountNumber(), riderId);
                  String license = LicenseRules.normalize(cmd.licenseNumber());
                  if (cmd.licenseNumber() != null && license == null) {
                    throw new PmartException(
                        ErrorCode.VALIDATION_FAILED, "운전면허 번호는 12자리 숫자입니다(하이픈 선택).");
                  }
                  if (license == null && LicenseRules.required(cmd.vehicleType())) {
                    throw new PmartException(
                        "LICENSE_REQUIRED",
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        "오토바이·자동차·킥보드는 운전면허 번호가 필요합니다.",
                        false,
                        List.of());
                  }
                  String licenseHash = license == null ? null : licenses.hash(license);
                  if (licenseHash != null && store.licenseTaken(licenseHash)) {
                    throw new PmartException(
                        "DUPLICATE_LICENSE",
                        HttpStatus.CONFLICT,
                        "같은 운전면허 번호로 가입한 라이더가 있습니다.",
                        false,
                        List.of());
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
                            clock.instant(),
                            licenseHash,
                            license == null ? null : LicenseRules.mask(license)));
                  } catch (DuplicateKeyException e) {
                    throw duplicate("이미 가입한 라이더이거나 같은 전화번호가 있습니다.");
                  }
                  if (verified) {
                    store.markPhoneVerified(riderId);
                  }
                  return riderId;
                })
            .value();
    return get(id);
  }

  /** 휴대폰 번호 중복 확인(가입 전, 라이더 앱 요청 §14 — 본인인증 아님). 형식은 계약이 400. 번호는 저장·로그하지 않는다. */
  public boolean phoneAvailable(String phone) {
    requireRider();
    return !store.phoneTaken(phone);
  }

  /** 정산 계좌 중복 확인(가입 전, 라이더 앱 요청 §15 — 사용자 "중복 불가"). 본인 계좌는 사용 가능. 번호는 저장·로그하지 않는다. */
  public boolean accountAvailable(String bankCode, String accountNumber) {
    String riderId = requireRider();
    requireBankCode(bankCode);
    return !store.accountTakenByOther(bankCode, accountNumber, riderId);
  }

  /** 은행 코드(금융결제원 3자리, 사용자 2026-11-17)가 목록에 있어야 한다. 없으면(null) 검사하지 않는다. */
  private static void requireBankCode(@Nullable String bankCode) {
    if (bankCode != null && !com.pmart.rider.domain.BankCodes.known(bankCode)) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED, "은행 코드가 올바르지 않습니다(금융결제원 3자리 — GET /riders/bank-codes).");
    }
  }

  private void requireAccountFree(
      @Nullable String bankCode, @Nullable String accountNumber, String riderId) {
    if (bankCode != null
        && accountNumber != null
        && store.accountTakenByOther(bankCode, accountNumber, riderId)) {
      throw new PmartException(
          "DUPLICATE_ACCOUNT", HttpStatus.CONFLICT, "같은 정산 계좌로 등록한 라이더가 있습니다.", false, List.of());
    }
  }

  /** 거주지 도로명주소가 실제 주소인지(라이더 앱 요청 §12 — 사용자 "서버가 주소 변환으로 확인"). 없으면(null) 검사하지 않는다. */
  /** 본인 진행 중 배송 수(0.25.0 — dispatch ASSIGNED·PICKED_UP). 못 읽으면 null(0 이 아님). */
  public @Nullable Integer activeDeliveryCount(String riderId) {
    try {
      return dispatch.activeDeliveryCounts(List.of(riderId)).getOrDefault(riderId, 0);
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static boolean sameAreas(List<Rider.Area> now, List<Rider.Area> next) {
    return now.stream()
        .map(Rider.Area::mfcId)
        .collect(java.util.stream.Collectors.toSet())
        .equals(next.stream().map(Rider.Area::mfcId).collect(java.util.stream.Collectors.toSet()));
  }

  /**
   * 배달 지역 바꾸기 앞 확인(2026-11-23 라이더 앱 요청): 고른 센터가 운영 중인 MFC 가 아니면 422
   * MFC_NOT_AVAILABLE(center-master), 진행 중 배송이 있으면 409 INVALID_STATE(execution — 쉬는 중이어도 픽업한 배송이 남을
   * 수 있다). 못 부르면 503.
   */
  private void requireAreaChangeAllowed(String riderId, List<Rider.Area> areas) {
    if (areas.isEmpty()) {
      return; // 아래 빈 목록 400 이 먼저
    }
    for (Rider.Area a : areas) {
      if (!centers.activeMfc(a.mfcId())) {
        throw new PmartException(
            "MFC_NOT_AVAILABLE",
            HttpStatus.UNPROCESSABLE_CONTENT,
            "운영 중인 MFC 가 아닙니다. 픽업지 목록에서 고르세요.",
            false,
            List.of());
      }
    }
    if (executions.withdrawalBlockers(riderId).contains("ACTIVE_DELIVERY")) {
      throw PmartException.invalidState("진행 중인 배송이 있어 배달 지역을 바꿀 수 없습니다. 배송을 마친 뒤 바꾸세요.");
    }
    // 위치 검사(0.27.0) 전에 운행 중을 먼저 — 운행 중이면 위치와 무관하게 409 INVALID_STATE(아래 트랜잭션에서 다시 본다)
    Rider now = get(riderId);
    if (now.operatingStatus() == Rider.OperatingStatus.ON_DUTY) {
      throw PmartException.invalidState("운행 중에는 배달 지역을 바꿀 수 없습니다. 운행을 종료한 뒤 바꾸세요.");
    }
    // 2026-11-25 사용자 "배달 가능 지역 내부에 있을 때만 해당 지역으로 권역 변경하게 해줘" — 새로 넣는 센터만
    java.util.Set<String> before =
        now.deliveryAreas().stream()
            .map(Rider.Area::mfcId)
            .collect(java.util.stream.Collectors.toSet());
    locationAreas.requireInside(
        riderId,
        areas.stream().map(Rider.Area::mfcId).filter(m -> !before.contains(m)).distinct().toList());
  }

  private void requireRealAddress(@Nullable String roadAddress) {
    if (roadAddress == null) {
      return;
    }
    if (roadAddress.isBlank() || !addresses.exists(roadAddress.strip())) {
      throw new PmartException(
          "INVALID_ADDRESS",
          HttpStatus.UNPROCESSABLE_CONTENT,
          "주소 검색에서 찾을 수 없는 주소입니다. 주소 검색으로 고른 도로명주소를 보내 주세요.",
          false,
          List.of());
    }
  }

  /** 운전면허 번호 중복 확인(가입 전, 라이더 앱 요청 §7). 형식이 틀리면 400. 번호는 저장·로그하지 않는다. */
  public boolean licenseAvailable(String licenseNumber) {
    requireRider();
    String license = LicenseRules.normalize(licenseNumber);
    if (license == null) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "운전면허 번호는 12자리 숫자입니다(하이픈 선택).");
    }
    return !store.licenseTaken(licenses.hash(license));
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

  /** 지금 위치가 속한 배달 권역(0.27.0 — 배달 지역 바꾸기 안내용, 정본 검사는 updateProfile). 탈퇴·없는 라이더 404. */
  public LocationAreas.Area myLocationArea() {
    String riderId = requireRider();
    get(riderId);
    return locationAreas.current(riderId);
  }

  @Transactional
  public Rider updateProfile(
      String requestId, String sourceSystem, @Nullable Integer expectedVersion, ProfileChange c) {
    String riderId = requireRider();
    requireBankCode(c.bankCode());
    requireRealAddress(c.residenceRoad()); // 외부 호출 — 트랜잭션 밖
    // 0.28.0(2026-11-25 사용자 "이미 가입했는데 전화번호 인증 안 한 계정은 인증하라고 팝업"): 번호와 확인 ID 는 같이
    if ((c.phone() == null)
        != (c.phoneVerificationId() == null || c.phoneVerificationId().isBlank())) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED, "phone 과 phoneVerificationId 는 같이 보내 주세요.");
    }
    boolean areasChange = c.areas() != null && !sameAreas(get(riderId).deliveryAreas(), c.areas());
    if (areasChange) {
      requireAreaChangeAllowed(riderId, c.areas()); // 외부 호출 — 트랜잭션 밖
    }
    idempotency.execute(
        new RequestKey(sourceSystem, requestId, "updateMyRider", riderId),
        Map.of("change", c, "expectedVersion", String.valueOf(expectedVersion)),
        String.class,
        () -> {
          Rider r = locked(riderId, expectedVersion);
          if (c.phone() != null) {
            // 가입과 같은 검사: 다른 라이더 번호·탈퇴 번호 막힘 409, 확인 ID 가 VERIFIED·같은 주체·같은 번호·1시간 안 아니면 422
            phones.getObject().requireJoinable(riderId, c.phone());
            phones.getObject().requireForSignup(riderId, c.phone(), c.phoneVerificationId());
            store.setVerifiedPhone(riderId, c.phone());
          }
          if (c.vehicleType() != null
              && !c.vehicleType().equals(r.vehicleType())
              && r.operatingStatus() == Rider.OperatingStatus.ON_DUTY) {
            // 진행 중 배송 수는 execution 사본이 아직 없어 운행 중이면 막는다(배송 중 수단 변경 불가 — 원문 §8.3 보수적 적용)
            throw PmartException.invalidState("운행 중에는 배달 수단을 바꿀 수 없습니다. 운행을 종료한 뒤 바꾸세요.");
          }
          // 운전면허(2026-11-16 사용자 결정): 면허가 필요한 수단으로 바꾸면 등록된 면허가 있거나 이번에 넣어야 한다
          String license = LicenseRules.normalize(c.licenseNumber());
          if (c.licenseNumber() != null && license == null) {
            throw new PmartException(ErrorCode.VALIDATION_FAILED, "운전면허 번호는 12자리 숫자입니다(하이픈 선택).");
          }
          String vehicle = c.vehicleType() == null ? r.vehicleType() : c.vehicleType();
          if (license == null && r.licenseMasked() == null && LicenseRules.required(vehicle)) {
            throw new PmartException(
                "LICENSE_REQUIRED",
                HttpStatus.UNPROCESSABLE_CONTENT,
                "오토바이·자동차·킥보드는 운전면허 번호가 필요합니다.",
                false,
                List.of());
          }
          if (license != null) {
            String hash = licenses.hash(license);
            if (store.licenseTakenByOther(hash, riderId)) {
              throw new PmartException(
                  "DUPLICATE_LICENSE",
                  HttpStatus.CONFLICT,
                  "같은 운전면허 번호로 가입한 라이더가 있습니다.",
                  false,
                  List.of());
            }
            store.setLicense(riderId, hash, LicenseRules.mask(license));
          }
          if (c.bankAccountNumber() != null) {
            requireAccountFree(
                c.bankCode() == null ? r.bankCode() : c.bankCode(), c.bankAccountNumber(), riderId);
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
            if (areasChange) {
              // 배달 지역 바꾸기(2026-11-23 사용자 "운행을 쉴 때만, 바로 반영"): 운행 중이면 409. 진행 중 배송은 위에서 확인.
              if (r.operatingStatus() == Rider.OperatingStatus.ON_DUTY) {
                throw PmartException.invalidState("운행 중에는 배달 지역을 바꿀 수 없습니다. 운행을 종료한 뒤 바꾸세요.");
              }
              store.replaceAreas(riderId, c.areas());
              // 쉬는 동안 남은 운행 센터가 새 지역 밖이면 새 지역 첫 센터로(옛 앱이 그 값을 보내 MFC_NOT_IN_AREAS 가 나지 않게)
              if (r.operatingMfcId() != null
                  && c.areas().stream().noneMatch(a -> a.mfcId().equals(r.operatingMfcId()))) {
                store.setOperatingMfc(riderId, c.areas().get(0).mfcId());
              }
            }
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
          boolean wasOn = r.operatingStatus() == Rider.OperatingStatus.ON_DUTY;
          boolean on = d.status() == Rider.OperatingStatus.ON_DUTY;
          store.recordDuty( // 접속·대기 시간(2026-11-18)
              riderId,
              wasOn,
              wasOn && r.newDispatchEnabled(),
              on,
              on && d.newDispatchEnabled(),
              now);
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
    Instant now = clock.instant();
    return store
        .eligible(mfcId, vehicleType, Math.max(1, Math.min(size, 200)) + exclude.size(), now)
        .stream()
        .map(r -> r.asOf(now))
        .filter(r -> !exclude.contains(r.riderId()))
        .limit(Math.max(1, Math.min(size, 200)))
        .toList();
  }

  /** 라이더 관제(2026-11-17) — 센터의 운행 중 라이더 200명까지. */
  /** 관제용 운행 시작·신규배차 켠 시각(2026-11-18). */
  public Map<String, RiderStore.DutySince> dutySince(List<String> riderIds) {
    return store.dutySince(riderIds);
  }

  /** 운행 구간(직원 접속·대기 시간 계산 — execution 이 부른다, 2026-11-18). 없는 라이더 404. */
  public List<RiderStore.DutySegment> dutySegments(String riderId, Instant from, Instant to) {
    store.find(riderId).orElseThrow(() -> PmartException.notFound("RIDER_NOT_FOUND", "라이더가 없습니다."));
    return store.dutySegments(riderId, from, to);
  }

  /** 공지 받는 라이더 ID(2026-11-18) — notification 이 부른다. */
  public List<String> recipients(@Nullable String mfcId, @Nullable String after, int size) {
    return store.recipients(mfcId, after, Math.max(1, Math.min(1000, size)));
  }

  public List<Rider> onDuty(String mfcId) {
    Instant now = clock.instant();
    return store.onDuty(mfcId, 200).stream().map(r -> r.asOf(now)).toList();
  }

  public Rider internal(String riderId) {
    return get(riderId);
  }

  // ---------- 운영자: 계정 제한(RS-1) ----------

  /**
   * 계정 제한 걸기·바꾸기·풀기(RS-1, 사용자 2026-11-14 — 승인 화면 BO-20 전에는 운영자 API 로 바로). 행 버전 조건 갱신 + 이력 한 줄 +
   * {@code RiderRestricted}(delivery.rider.v1 — 풀기는 restriction NORMAL) 한 트랜잭션. 제한이면 신규배차도 끈다(운행
   * 상태는 그대로 — 픽업한 배송은 끝까지 전달, 사용자 2026-11-14). dispatch 가 사건을 받아 픽업 전 배정을 풀고 다시 배차한다.
   */
  @Transactional
  public RiderStore.RestrictionRow changeRestriction(
      String riderId,
      String requestId,
      String sourceSystem,
      @Nullable Integer expectedVersion,
      RestrictionChange c) {
    PmartPrincipal p = requireRestrictionAdmin();
    Rider.Restriction next;
    try {
      next = Rider.Restriction.valueOf(c.restriction());
    } catch (IllegalArgumentException e) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "알 수 없는 제한입니다: " + c.restriction());
    }
    if (c.reason().isBlank()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "제한 사유를 적으세요.");
    }
    if (c.until() != null
        && (next == Rider.Restriction.NORMAL || !c.until().isAfter(clock.instant()))) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "제한 끝(until)은 제한을 걸 때만, 지금보다 뒤로 적으세요.");
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("riderId", riderId);
    body.put("change", c);
    body.put("expectedVersion", String.valueOf(expectedVersion));
    return idempotency
        .execute(
            new RequestKey(sourceSystem, requestId, "changeRiderRestriction", riderId),
            body,
            RiderStore.RestrictionRow.class,
            () -> {
              Instant now = clock.instant();
              Rider r = locked(riderId, expectedVersion);
              Rider.Restriction previous = r.restriction();
              if (previous == Rider.Restriction.NORMAL && next == Rider.Restriction.NORMAL) {
                throw PmartException.invalidState("제한이 걸려 있지 않습니다.");
              }
              store.updateRestriction(
                  riderId,
                  r.version(),
                  next.name(),
                  next == Rider.Restriction.NORMAL ? null : c.reason(),
                  c.until());
              RiderStore.RestrictionRow row =
                  new RiderStore.RestrictionRow(
                      requestId,
                      riderId,
                      next.name(),
                      previous.name(),
                      c.reason(),
                      c.until(),
                      json.writeValueAsString(c.evidenceRefs()),
                      c.approvalRef(),
                      p.subject(),
                      now,
                      r.version() + 1);
              store.insertRestriction(row);
              Map<String, Object> e = new LinkedHashMap<>();
              e.put("riderId", riderId);
              e.put("restriction", next.name());
              e.put("previous", previous.name());
              e.put("from", now.toString());
              e.put("to", c.until() == null ? null : c.until().toString());
              e.put("basis", c.reason());
              e.put(
                  "evidenceRefs",
                  c.evidenceRefs().stream().map(id -> Map.of("evidenceId", id)).toList());
              if (c.approvalRef() != null && !c.approvalRef().isBlank()) {
                e.put("approvalRef", Map.of("approvalId", c.approvalRef()));
              }
              events.publish(
                  "RiderRestricted",
                  1,
                  "Rider",
                  riderId,
                  r.version() + 1L,
                  new EventEnvelope.Actor(EventEnvelope.ActorType.OPERATOR, p.subject()),
                  e);
              return row;
            })
        .value();
  }

  public Restrictions restrictions(String riderId) {
    requireRestrictionAdmin();
    Rider r = get(riderId);
    return new Restrictions(r.restriction(), r.version(), store.restrictions(riderId, 100));
  }

  // ---------- 공통 ----------

  Rider get(String riderId) {
    return store
        .find(riderId)
        .map(r -> r.asOf(clock.instant()))
        .orElseThrow(() -> PmartException.notFound("RIDER_NOT_FOUND", "가입한 라이더가 아닙니다."));
  }

  /** 행을 잠그고 기한 지난 제한은 NORMAL 로 본 사본(운행 판정·제한 바꾸기의 이전 값). */
  private Rider locked(String riderId, @Nullable Integer expectedVersion) {
    Rider r =
        store
            .lock(riderId)
            .map(x -> x.asOf(clock.instant()))
            .orElseThrow(() -> PmartException.notFound("RIDER_NOT_FOUND", "가입한 라이더가 아닙니다."));
    if (expectedVersion != null && expectedVersion != r.version()) {
      throw PmartException.versionConflict(
          "라이더 정보가 바뀌었습니다(현재 버전 " + r.version() + "). 다시 조회한 뒤 바꾸세요.");
    }
    return r;
  }

  /** 계정 제한·제한 검토는 OPERATOR_ADMIN·라이더 운영 RIDER_OPS(0.4.0, BOD-1 — 사용자 2026-11-15 "라이더 운영 전용 역할"). */
  static PmartPrincipal requireRestrictionAdmin() {
    PmartPrincipal p =
        PmartPrincipal.current()
            .filter(x -> x.type() == PmartPrincipal.PrincipalType.OPERATOR)
            .orElseThrow(
                () ->
                    new PmartException(
                        ErrorCode.FORBIDDEN.name(),
                        HttpStatus.FORBIDDEN,
                        "운영자만 계정 제한을 다룰 수 있습니다.",
                        false,
                        List.of()));
    if (!p.hasRole("OPERATOR_ADMIN") && !p.hasRole("RIDER_OPS")) {
      throw new PmartException(
          ErrorCode.FORBIDDEN.name(),
          HttpStatus.FORBIDDEN,
          "계정 제한 권한(OPERATOR_ADMIN·RIDER_OPS)이 없습니다.",
          false,
          List.of());
    }
    return p;
  }

  static String requireRider() {
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
