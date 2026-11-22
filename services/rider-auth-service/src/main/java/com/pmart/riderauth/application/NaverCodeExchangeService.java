package com.pmart.riderauth.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.riderauth.infrastructure.NaverTokenClient;
import com.pmart.riderauth.infrastructure.RiderAuthStore;
import com.pmart.riderauth.infrastructure.RiderClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 네이버 인가 코드 → ID 토큰 서버 교환(ADR-0078, 2026-11-20 BR-69). 네이버 토큰 엔드포인트는 client secret 없는 교환을 거절하므로
 * (client_secret_post) 앱 대신 서버가 secret 으로 교환하고 <b>id_token 만</b> 돌려준다. 계정·세션은 만들지 않는다 — 앱은 받은
 * id_token 으로 지금처럼 로그인({@link RiderLoginService})한다.
 *
 * <ul>
 *   <li>{@code redirectUri} 는 설정의 라이더 앱 콜백 중계 주소와 정확히 같아야 한다(400 VALIDATION_FAILED).
 *   <li>같은 Idempotency-Key·같은 본문 재전송은 처음 받은 id_token 을 다시 준다(코드를 두 번 교환하지 않음). 결과는 {@code
 *       request_record} 에 남는다(ADR-0078 결정 3). 같은 키가 동시에 오면 뒤 요청은 앞 교환이 끝날 때까지 기다린다 — 그래서 교환은 기록
 *       트랜잭션 안에서 하고, 네이버 호출 시간은 연결·응답 제한(기본 2초·5초)으로 묶는다.
 *   <li>네이버 거절 401 PROVIDER_REJECTED(네이버 오류 코드만 로그), 응답 없음·5xx 503 PROVIDER_UNAVAILABLE. 실패는 기록되지
 *       않는다(같은 키로 다시 보내면 다시 교환 — 같은 코드면 네이버가 거절한다).
 *   <li>로그인 실패 횟수(기기별 제한)에는 넣지 않는다 — 이 API 는 기기·계정을 모른다.
 * </ul>
 *
 * <p>2026-11-22 RA-1 — 고객 auth-service 와 같게(사용자 "동의는 처음 가입할 때만"). 교환이 되면 네이버 access token 으로:
 *
 * <ul>
 *   <li>④ 이메일: 프로필 API({@code /v1/nid/me})의 email 을 <b>가려서</b> id_token sub 의 SHA-256 에 10분 묶어 둔다
 *       ({@code naver_email_hold}) — 그 sub 로 로그인할 때 {@code rider_identity.email_masked} 로 옮긴다. 원문은
 *       두지 않는다. 없거나 실패면 이메일 없이 그대로.
 *   <li>② 탈퇴 재인증(purpose WITHDRAWAL): 프로필을 읽은 뒤 네이버 앱 연결을 끊는다 — 다시 가입할 때 동의 화면이 나온다.
 *   <li>③ 가입 전(LOGIN): 그 sub 로 <b>가입을 마친 라이더가 없으면</b>(연결 없음, 또는 연결된 주체가 rider-service 에 가입 전·탈퇴 —
 *       {@code GET /internal/riders/{id}} 404) 끊는다 — 가입을 끝내지 않고 나가면 다음 로그인에서 동의를 다시 받는다. 가입한 라이더는
 *       끊지 않는다. sub 를 못 읽음·조회 실패면 끊지 않는다.
 *   <li>끊기·프로필 실패는 교환을 막지 않는다. 로그인마다 끊는 설정(고객의 unlink-after-exchange)은 두지 않는다.
 * </ul>
 */
@Service
public class NaverCodeExchangeService {

  static final String OPERATION = "exchangeNaverCode";

  /** 교환 때 받은 이메일을 묶어 두는 시간(그 안에 같은 id_token 으로 로그인한다). */
  static final Duration EMAIL_HOLD = Duration.ofMinutes(10);

  private static final Logger log = LoggerFactory.getLogger(NaverCodeExchangeService.class);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** 기록·재생되는 결과. */
  public record Exchanged(String idToken) {
    @Override
    public String toString() {
      return "Exchanged[idToken=****]";
    }
  }

  private final NaverExchangeProperties props;
  private final NaverTokenClient naver;
  private final RequestIdempotency idempotency;
  private final @Nullable RiderAuthStore store;
  private final @Nullable RiderClient riders;
  private final Clock clock;

  /** 단위 시험용 — 이메일 보관·가입 판단 없음. */
  public NaverCodeExchangeService(
      NaverExchangeProperties props, NaverTokenClient naver, RequestIdempotency idempotency) {
    this(props, naver, idempotency, null, null, Clock.systemUTC());
  }

  @Autowired
  public NaverCodeExchangeService(
      NaverExchangeProperties props,
      NaverTokenClient naver,
      RequestIdempotency idempotency,
      @Nullable RiderAuthStore store,
      @Nullable RiderClient riders,
      Clock clock) {
    this.props = props;
    this.naver = naver;
    this.idempotency = idempotency;
    this.store = store;
    this.riders = riders;
    this.clock = clock;
  }

  public String exchange(
      String idempotencyKey,
      String sourceSystem,
      String authorizationCode,
      String codeVerifier,
      String redirectUri,
      @Nullable String state) {
    return exchange(
        idempotencyKey, sourceSystem, authorizationCode, codeVerifier, redirectUri, state, false);
  }

  /**
   * @param withdrawal 탈퇴 재인증(purpose WITHDRAWAL, RA-1 ②) — 교환 뒤 네이버 앱 연결을 끊는다
   */
  public String exchange(
      String idempotencyKey,
      String sourceSystem,
      String authorizationCode,
      String codeVerifier,
      String redirectUri,
      @Nullable String state,
      boolean withdrawal) {
    String clientId = props.clientId();
    if (clientId == null) {
      throw PmartException.configRequired("pmart.rider-auth.naver.client-ids");
    }
    String secret = props.clientSecret();
    if (secret == null) {
      throw PmartException.configRequired("pmart.rider-auth.naver.client-secret");
    }
    String callback = props.callbackUrl();
    if (callback == null) {
      throw PmartException.configRequired("pmart.rider-auth.naver.callback-url");
    }
    if (!callback.equals(redirectUri)) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED,
          "redirectUri 가 라이더 앱 네이버 콜백 주소와 다릅니다.",
          List.of(new PmartException.Detail("redirectUri", "설정된 콜백 주소와 같아야 합니다")));
    }
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("authorizationCode", authorizationCode);
    request.put("codeVerifier", codeVerifier);
    request.put("redirectUri", redirectUri);
    request.put("state", state);
    if (withdrawal) {
      // LOGIN 은 넣지 않는다 — 0.3.0 때 기록과 본문 비교가 그대로 맞게
      request.put("purpose", "WITHDRAWAL");
    }
    RequestIdempotency.Outcome<Exchanged> outcome =
        idempotency.execute(
            new RequestKey(sourceSystem, idempotencyKey, OPERATION, RequestIdempotency.ANONYMOUS),
            request,
            Exchanged.class,
            () -> {
              NaverTokenClient.Tokens tokens =
                  call(
                      new NaverTokenClient.Exchange(
                          clientId, secret, authorizationCode, codeVerifier, redirectUri, state));
              afterExchange(tokens, clientId, secret, withdrawal);
              return new Exchanged(tokens.idToken());
            });
    if (outcome.replayed()) {
      log.info("네이버 코드 교환 재전송 — 처음 결과를 돌려줌");
    }
    return outcome.value().idToken();
  }

  /** 프로필(이메일) → 끊기. 어떤 실패도 교환을 막지 않는다. */
  private void afterExchange(
      NaverTokenClient.Tokens tokens, String clientId, String secret, boolean withdrawal) {
    String accessToken = tokens.accessToken();
    if (accessToken == null) {
      log.info("네이버 프로필·끊기 생략 reason=missing_access_token");
      return;
    }
    String sub = subjectOf(tokens.idToken());
    holdEmail(sub, naver.profile(accessToken).email());
    // 프로필을 읽은 뒤 끊는다(끊으면 access token 이 무효)
    String reason = withdrawal ? "WITHDRAWAL" : signupPending(sub) ? "SIGNUP_PENDING" : null;
    if (reason != null) {
      boolean ok = naver.unlink(clientId, secret, accessToken);
      log.info("네이버 연결 끊기 purpose={} ok={}", reason, ok);
    }
  }

  /** 이 네이버 계정(sub)으로 가입을 마친 라이더가 없는가(RA-1 ③). sub 를 못 읽거나 조회가 실패하면 false(끊지 않음). sub 는 로그 금지. */
  boolean signupPending(@Nullable String sub) {
    if (sub == null || store == null || riders == null) {
      return false;
    }
    try {
      return store.linkedRider("NAVER", sub).map(id -> !riders.signedUp(id)).orElse(true);
    } catch (RuntimeException e) {
      log.warn("네이버 가입 전 끊기 판단 생략 reason={}", e.getClass().getSimpleName());
      return false;
    }
  }

  /** 가린 이메일을 sub 해시에 묶어 둔다. 실패는 경고만(로그인은 이메일 없이). 이메일·sub 는 로그 금지. */
  private void holdEmail(@Nullable String sub, @Nullable String email) {
    if (store == null || email == null) {
      return;
    }
    if (sub == null) {
      log.info("네이버 프로필 이메일 생략 reason=missing_sub");
      return;
    }
    String masked = RiderLoginService.maskEmail(email);
    if (masked == null) {
      log.info("네이버 프로필 이메일 생략 reason=invalid_email");
      return;
    }
    try {
      store.holdNaverEmail(
          subjectHash(sub), masked, clock.instant().plus(EMAIL_HOLD), clock.instant());
    } catch (RuntimeException ex) {
      log.warn("네이버 프로필 이메일 보관 실패 reason={}", ex.getClass().getSimpleName());
    }
  }

  /** 보관 키 — 원 sub 대신 SHA-256(hex). */
  public static String subjectHash(String sub) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(sub.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** id_token 의 sub(서명 검증 없이 키로만 쓴다 — 방금 네이버 토큰 엔드포인트가 직접 준 토큰이고, 이메일은 로그인 때 검증된 같은 sub 로만 읽힌다). */
  static @Nullable String subjectOf(String idToken) {
    String[] parts = idToken.split("[.]");
    if (parts.length < 2) {
      return null;
    }
    try {
      JsonNode claims =
          JSON.readTree(
              new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
      String sub = claims.path("sub").asString("");
      return sub.isBlank() ? null : sub;
    } catch (RuntimeException e) {
      return null;
    }
  }

  private NaverTokenClient.Tokens call(NaverTokenClient.Exchange e) {
    try {
      NaverTokenClient.Tokens tokens = naver.exchange(e);
      log.info("네이버 코드 교환 성공");
      return tokens;
    } catch (NaverTokenClient.Rejected ex) {
      log.warn("네이버 코드 교환 거절(PROVIDER_REJECTED) error={}", ex.errorCode());
      throw new PmartException(
          "PROVIDER_REJECTED",
          HttpStatus.UNAUTHORIZED,
          "네이버 인가 코드를 확인할 수 없습니다. 다시 로그인하세요.",
          false,
          List.of());
    } catch (NaverTokenClient.Unavailable ex) {
      log.warn("네이버 코드 교환 불가(PROVIDER_UNAVAILABLE): {}", ex.getMessage());
      throw new PmartException(
          "PROVIDER_UNAVAILABLE",
          HttpStatus.SERVICE_UNAVAILABLE,
          "네이버 인증 확인이 잠시 안 됩니다. 잠시 뒤 다시 시도하세요.",
          true,
          List.of());
    }
  }
}
