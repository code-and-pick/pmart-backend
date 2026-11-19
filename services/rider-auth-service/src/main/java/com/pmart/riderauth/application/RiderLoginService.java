package com.pmart.riderauth.application;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.riderauth.infrastructure.GoogleIdTokenVerifier;
import com.pmart.riderauth.infrastructure.RiderAuthStore;
import com.pmart.riderauth.infrastructure.RiderAuthStore.AuthRequestRow;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 라이더 Google 로그인(ADR-0054 F, ADR-0055). 앱이 받은 Google ID 토큰을 Google 공개키로 검증하고(client secret·인가 코드 교환
 * 없음), Google 계정(sub)을 라이더 주체 RD-… 에 연결한 뒤 라이더 토큰을 준다.
 *
 * <ul>
 *   <li>같은 Google 계정은 늘 같은 riderId. 이메일이 같다는 이유로 합치지 않는다.
 *   <li>가입(전화번호·배달 지역·수단·약관 → 즉시 운행 승인, ADR-0054 G)은 rider-service 가 받는다 — 여기는 로그인·토큰만.
 *   <li>기기별 15분 내 5회 명확 실패(PROVIDER_REJECTED) → 10분 AUTH_RESTRICTED. Google 장애는 세지 않고, 제한 중 시도는 기간을
 *       늘리지 않는다.
 *   <li>같은 Idempotency-Key 재전송은 원결과를 따른다: 성공이면 새 세션 토큰(토큰은 저장하지 않음), 실패였으면 같은 실패.
 * </ul>
 */
@Service
public class RiderLoginService {

  public static final String PROVIDER_GOOGLE = "GOOGLE";
  static final String MEMBER = "AUTH_MEMBER";

  private static final Logger log = LoggerFactory.getLogger(RiderLoginService.class);

  public record Result(
      String authRequestId,
      String state,
      String riderId,
      boolean newAccount,
      RiderTokens.@Nullable Tokens tokens,
      Instant occurredAt) {}

  /** AUTH_RESTRICTED(423). */
  public static class RestrictedException extends RuntimeException {
    private final Instant until;
    private final long retryAfterSeconds;

    public RestrictedException(Instant until, long retryAfterSeconds) {
      super("로그인 실패가 많아 잠시 제한되었습니다. " + retryAfterSeconds + "초 뒤 다시 시도하세요.");
      this.until = until;
      this.retryAfterSeconds = retryAfterSeconds;
    }

    public Instant until() {
      return until;
    }

    public long retryAfterSeconds() {
      return retryAfterSeconds;
    }
  }

  private final RiderAuthStore store;
  private final RiderTokens tokens;
  private final com.pmart.riderauth.infrastructure.IdTokenVerifiers verifiers;
  private final RiderAuthProperties props;
  private final TransactionTemplate tx;
  private final RiderSessionCuts cuts;
  private final Clock clock;

  public RiderLoginService(
      RiderAuthStore store,
      RiderTokens tokens,
      com.pmart.riderauth.infrastructure.IdTokenVerifiers verifiers,
      RiderAuthProperties props,
      TransactionTemplate tx,
      RiderSessionCuts cuts,
      Clock clock) {
    this.store = store;
    this.tokens = tokens;
    this.verifiers = verifiers;
    this.props = props;
    this.tx = tx;
    this.cuts = cuts;
    this.clock = clock;
  }

  public Result login(
      String idempotencyKey,
      String sourceSystem,
      String deviceId,
      String provider,
      String providerToken) {
    if (!java.util.Set.of("GOOGLE", "KAKAO", "NAVER").contains(provider)) {
      throw new PmartException(
          ErrorCode.UNSUPPORTED_ACTION, provider + " 로그인은 지원하지 않습니다(GOOGLE·KAKAO·NAVER).");
    }
    if (PROVIDER_GOOGLE.equals(provider) && props.google().clientIds().isEmpty()) {
      throw PmartException.configRequired("pmart.rider-auth.google.client-ids");
    }
    GoogleIdTokenVerifier verifier =
        verifiers
            .of(provider)
            .orElseThrow(
                () ->
                    PmartException.configRequired(
                        "pmart.rider-auth."
                            + provider.toLowerCase(java.util.Locale.ROOT)
                            + ".client-ids"));
    Optional<AuthRequestRow> earlier = store.requestByKey(sourceSystem, idempotencyKey);
    if (earlier.isPresent()) {
      return replay(earlier.get(), deviceId, sourceSystem);
    }
    Instant now = now();
    Optional<Instant> restricted = store.restrictedUntil(deviceId).filter(u -> u.isAfter(now));
    if (restricted.isPresent()) {
      throw restrictedUntil(restricted.get(), now);
    }

    // Google 검증은 트랜잭션 밖(네트워크)
    GoogleIdTokenVerifier.Identity identity;
    try {
      identity = verifier.verify(providerToken);
    } catch (GoogleIdTokenVerifier.Unavailable e) {
      log.warn("{} 검증 불가(실패 횟수 제외): {}", provider, e.getMessage());
      throw new PmartException(
          "PROVIDER_UNAVAILABLE",
          HttpStatus.SERVICE_UNAVAILABLE,
          "Google 인증 확인이 잠시 안 됩니다. 잠시 뒤 다시 시도하세요.",
          true,
          List.of());
    } catch (GoogleIdTokenVerifier.Rejected e) {
      return rejected(idempotencyKey, sourceSystem, deviceId, provider, now);
    }

    Result r =
        tx.execute(
            status -> {
              String riderId = store.linkedRider(provider, identity.subject()).orElse(null);
              boolean created = false;
              if (riderId == null) {
                String candidate = "RD-" + UUID.randomUUID();
                store.insertAccount(candidate, now);
                if (store.insertIdentity(provider, identity.subject(), candidate, now) == 1) {
                  // 가린 이메일만(2026-11-19 — 같은 번호 다른 제공자 안내 "카카오로 가입한 계정이 있어요 · a***@k***")
                  store.setEmailMasked(provider, identity.subject(), maskEmail(identity.email()));
                  riderId = candidate;
                  created = true;
                } else {
                  // 같은 Google 계정이 동시에 들어옴 — 먼저 만든 쪽을 쓴다(만든 빈 주체는 롤백으로 지운다)
                  status.setRollbackOnly();
                  return null;
                }
              }
              String state = store.accountState(riderId).orElse("ACTIVE");
              if (!"ACTIVE".equals(state)) {
                throw new PmartException(
                    "ACCOUNT_NOT_AVAILABLE",
                    HttpStatus.FORBIDDEN,
                    "이용할 수 없는 라이더 계정입니다(" + state + ").",
                    false,
                    List.of());
              }
              if (cuts.loginBlocked(riderId)) {
                throw restrictedRider();
              }
              String authRequestId = "RAR-" + UUID.randomUUID();
              store.insertRequest(
                  authRequestId,
                  sourceSystem,
                  idempotencyKey,
                  deviceId,
                  provider,
                  MEMBER,
                  null,
                  riderId,
                  created,
                  now);
              store.recordLogin(provider, identity.subject(), riderId, now);
              RiderTokens.Tokens t = openSession(riderId, deviceId, sourceSystem, now);
              return new Result(authRequestId, MEMBER, riderId, created, t, now);
            });
    if (r == null) {
      return login(idempotencyKey, sourceSystem, deviceId, provider, providerToken);
    }
    log.info("라이더 로그인: provider={} riderId={} new={}", provider, r.riderId(), r.newAccount());
    return r;
  }

  /** 원인증 결과 조회(같은 기기만). 토큰은 다시 주지 않는다. 실패 기록은 결과가 아니라 404 로 본다. */
  public Result result(String authRequestId, String deviceId) {
    AuthRequestRow row =
        store
            .request(authRequestId)
            .filter(x -> x.riderId() != null)
            .orElseThrow(() -> PmartException.notFound("AUTH_REQUEST_NOT_FOUND", "원인증 요청이 없습니다."));
    if (!row.deviceId().equals(deviceId)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "로그인을 시작한 기기에서만 조회할 수 있습니다.");
    }
    return new Result(
        row.authRequestId(),
        row.state(),
        Objects.requireNonNull(row.riderId()),
        row.newAccount(),
        null,
        row.createdAt());
  }

  private Result replay(AuthRequestRow row, String deviceId, String sourceSystem) {
    if (!row.deviceId().equals(deviceId)) {
      throw PmartException.idempotencyConflict("같은 Idempotency-Key 로 다른 기기의 요청이 이미 처리되었습니다.");
    }
    if (row.failureCode() != null || row.riderId() == null) {
      throw providerRejected();
    }
    String riderId = row.riderId();
    if (!"ACTIVE".equals(store.accountState(riderId).orElse(null))) {
      throw new PmartException(
          "ACCOUNT_NOT_AVAILABLE", HttpStatus.FORBIDDEN, "이용할 수 없는 라이더 계정입니다.", false, List.of());
    }
    if (cuts.loginBlocked(riderId)) {
      throw restrictedRider();
    }
    Instant now = now();
    RiderTokens.Tokens t = tx.execute(status -> openSession(riderId, deviceId, sourceSystem, now));
    return new Result(
        row.authRequestId(), row.state(), riderId, row.newAccount(), t, row.createdAt());
  }

  private Result rejected(
      String idempotencyKey, String sourceSystem, String deviceId, String provider, Instant now) {
    Instant[] until = new Instant[1];
    tx.executeWithoutResult(
        status -> {
          store.lockDevice(deviceId);
          store.insertRequest(
              "RAR-" + UUID.randomUUID(),
              sourceSystem,
              idempotencyKey,
              deviceId,
              provider,
              "REJECTED",
              "PROVIDER_REJECTED",
              null,
              false,
              now);
          if (store.failuresSince(deviceId, now.minus(props.failureWindow()))
              >= props.maxFailures()) {
            until[0] = now.plus(props.restriction());
            store.restrict(deviceId, until[0]);
          }
        });
    log.warn("라이더 로그인 거절(PROVIDER_REJECTED) provider={}", provider);
    if (until[0] != null) {
      throw restrictedUntil(until[0], now);
    }
    throw providerRejected();
  }

  private RiderTokens.Tokens openSession(
      String riderId, String deviceId, String sourceSystem, Instant now) {
    String sessionId = "RSES-" + UUID.randomUUID();
    Instant sessionExpires = now.plus(props.sessionMaxAge());
    store.insertSession(sessionId, riderId, deviceId, sourceSystem, now, sessionExpires);
    return tokens.issue(riderId, sessionId, sessionExpires, now);
  }

  /** DB(timestamptz, 마이크로초)에 저장한 값과 응답 값이 같도록 마이크로초로 자른다. */
  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  /** 이메일 가리기 — 앞 한 글자 + *** @ 도메인 첫 글자 + ***(예 a***@k***). 없으면 null. */
  static @org.jspecify.annotations.Nullable String maskEmail(
      @org.jspecify.annotations.Nullable String email) {
    if (email == null || !email.contains("@")) {
      return null;
    }
    String[] parts = email.split("@", 2);
    String local = parts[0].isEmpty() ? "*" : parts[0].substring(0, 1);
    String domain = parts[1].isEmpty() ? "*" : parts[1].substring(0, 1);
    return local + "***@" + domain + "***";
  }

  static PmartException providerRejected() {
    return new PmartException(
        "PROVIDER_REJECTED",
        HttpStatus.UNAUTHORIZED,
        "Google 인증 결과를 확인할 수 없습니다. 다시 로그인하세요.",
        false,
        List.of());
  }

  static RestrictedException restrictedUntil(Instant until, Instant now) {
    return new RestrictedException(until, Math.max(1, Duration.between(now, until).toSeconds()));
  }

  /** 정지·계약 종료로 로그인이 끊긴 라이더(진행 중 배송이 끝난 뒤 — 2026-11-15). 제한이 풀리거나 기한이 지나면 다시 된다. */
  private static PmartException restrictedRider() {
    return new PmartException(
        "ACCOUNT_NOT_AVAILABLE",
        HttpStatus.FORBIDDEN,
        "정지되었거나 계약이 끝난 라이더 계정입니다. 지원센터에 문의하세요.",
        false,
        List.of());
  }
}
