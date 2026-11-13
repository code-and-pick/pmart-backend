package com.pmart.employeeauth.application;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.employeeauth.infrastructure.EmployeeAuthStore;
import com.pmart.employeeauth.infrastructure.EmployeeAuthStore.AccountRow;
import com.pmart.employeeauth.infrastructure.EmployeeAuthStore.AttemptRow;
import com.pmart.employeeauth.infrastructure.EmployeeAuthStore.TicketRow;
import com.pmart.employeeauth.infrastructure.KeycloakClient;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 직원 로그인(ADR-0056 결정 2). Keycloak 의 기밀 클라이언트로 인가 코드 + PKCE 흐름을 하고, 결과를 60초·1회용 로그인 티켓으로 앱에 넘긴다.
 *
 * <ul>
 *   <li>시작: 앱(app)·복귀 주소는 설정 허용 목록에서만. 앱이 준 codeChallenge(S256)를 시도에 묶어 둔다 — 티켓은 그 verifier 를 가진 쪽만
 *       바꾼다(브라우저 쿠키 없이도 시작한 쪽과 받는 쪽을 묶는다. 게이트웨이는 Cookie 헤더를 지운다).
 *   <li>Keycloak 쪽: state·nonce·PKCE(S256), {@code prompt=login}(공용 PDA 에서 앞사람 세션 재사용 금지). SSO 앱의
 *       조용한 확인 ({@code silent}, 2026-11-13)은 {@code prompt=none} — 세션이 없으면 Keycloak 이 {@code
 *       login_required} 로 돌려보내고 앱에는 {@code LOGIN_REQUIRED}.
 *   <li>콜백: 코드 교환 → ID 토큰 검증 → Keycloak 세션 바로 끝냄(결정 14 ①) → Keycloak sub ↔ 운영자 ID 연결 → 직원 상태 확인 →
 *       티켓.
 *   <li>Keycloak 토큰은 여기서 쓰고 버린다(저장·로그·앱 전달 없음).
 * </ul>
 */
@Service
public class EmployeeLoginService {

  public static final String ACTIVE = "ACTIVE";
  private static final java.time.ZoneId SEOUL = java.time.ZoneId.of("Asia/Seoul");
  private static final Pattern VERIFIER = Pattern.compile("^[A-Za-z0-9._~-]{43,128}$");
  private static final Pattern CHALLENGE = Pattern.compile("^[A-Za-z0-9_-]{43,128}$");

  /** prompt=none 일 때 Keycloak 이 "화면이 필요하다"고 돌려보내는 오류(OIDC Core 3.1.2.6). */
  private static final java.util.Set<String> SESSION_NEEDED =
      java.util.Set.of(
          "login_required",
          "interaction_required",
          "consent_required",
          "account_selection_required");

  private static final Logger log = LoggerFactory.getLogger(EmployeeLoginService.class);

  /** 티켓 교환·시험 교환 결과. */
  /** {@code app} = 로그인한 앱(refresh 쿠키 앱인지 컨트롤러가 본다 — ADR-0059). */
  public record LoginResult(
      String operatorId,
      List<String> roles,
      List<String> centers,
      EmployeeTokens.Tokens tokens,
      String app) {}

  private final EmployeeAuthStore store;
  private final EmployeeTokens tokens;
  private final KeycloakClient keycloak;
  private final EmployeeAuthProperties props;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final EmployeeAccess access;

  public EmployeeLoginService(
      EmployeeAuthStore store,
      EmployeeTokens tokens,
      KeycloakClient keycloak,
      EmployeeAuthProperties props,
      TransactionTemplate tx,
      Clock clock,
      EmployeeAccess access) {
    this.store = store;
    this.tokens = tokens;
    this.keycloak = keycloak;
    this.props = props;
    this.tx = tx;
    this.clock = clock;
    this.access = access;
  }

  // ---------------------------------------------------------------- 시작

  /** Keycloak 로그인 화면 주소를 만든다. */
  public URI start(
      String app,
      String deviceId,
      String codeChallenge,
      @Nullable String codeChallengeMethod,
      @Nullable String returnUri,
      boolean silent) {
    EmployeeAuthProperties.Keycloak kc = props.keycloak();
    if (!kc.configured()) {
      throw PmartException.configRequired("pmart.employee-auth.keycloak");
    }
    EmployeeAuthProperties.App a = props.apps().get(app);
    if (a == null || a.returnUris().isEmpty()) {
      throw badRequest("APP_NOT_ALLOWED", "로그인할 수 없는 앱입니다: " + app);
    }
    String target =
        returnUri == null || returnUri.isBlank() ? a.returnUris().getFirst() : returnUri;
    if (!a.returnUris().contains(target)) {
      throw badRequest("RETURN_URI_NOT_ALLOWED", "허용되지 않은 복귀 주소입니다.");
    }
    if (silent && !a.sso()) {
      // 공용 PDA(결정 14 ①)는 남은 세션으로 들어가지 않는다 — 조용한 확인 자체를 받지 않는다
      throw badRequest("SILENT_NOT_ALLOWED", "이 앱은 화면 없는 로그인 확인을 쓸 수 없습니다.");
    }
    if (codeChallengeMethod != null && !"S256".equals(codeChallengeMethod)) {
      throw badRequest("CHALLENGE_METHOD_NOT_SUPPORTED", "codeChallengeMethod 는 S256 만 됩니다.");
    }
    if (!CHALLENGE.matcher(codeChallenge).matches()) {
      throw badRequest(
          "CHALLENGE_INVALID", "codeChallenge 는 BASE64URL(SHA-256(code_verifier)) 이어야 합니다.");
    }
    Instant now = now();
    String state = EmployeeTokens.random();
    String nonce = EmployeeTokens.random();
    String verifier = EmployeeTokens.random();
    store.insertAttempt(
        EmployeeTokens.hash(state),
        app,
        target,
        deviceId,
        codeChallenge,
        verifier,
        nonce,
        now,
        now.plus(props.loginAttemptTtl()));
    UriComponentsBuilder auth =
        UriComponentsBuilder.fromUriString(Objects.requireNonNull(kc.authorizationUri()));
    if (!a.sso()) {
      // 공용 PDA(결정 14 ①): Keycloak 세션이 남아 있어도 매번 비밀번호를 묻는다. SSO 앱(백오피스)은 남은 세션을 쓴다
      auth.queryParam("prompt", "login");
    } else if (silent) {
      // 화면 없이 남은 Keycloak 세션만 확인(OIDC prompt=none) — 없으면 error=login_required 로 콜백
      auth.queryParam("prompt", "none");
    }
    return auth.queryParam("client_id", kc.clientId())
        .queryParam("response_type", "code")
        .queryParam("scope", "openid")
        .queryParam("redirect_uri", kc.redirectUri())
        .queryParam("state", state)
        .queryParam("nonce", nonce)
        .queryParam("code_challenge", EmployeeTokens.s256(verifier))
        .queryParam("code_challenge_method", "S256")
        .encode()
        .build()
        .toUri();
  }

  // ---------------------------------------------------------------- 콜백

  /** 앱 복귀 주소(?ticket= 또는 ?error=). state 를 모르면 400(돌려보낼 곳을 모른다). */
  public URI complete(@Nullable String state, @Nullable String code, @Nullable String error) {
    if (state == null || state.isBlank()) {
      throw badRequest("LOGIN_STATE_INVALID", "로그인 상태값이 없습니다. 앱에서 다시 로그인하세요.");
    }
    Instant now = now();
    AttemptRow attempt =
        store
            .consumeAttempt(EmployeeTokens.hash(state), now)
            .orElseThrow(
                () -> badRequest("LOGIN_STATE_INVALID", "로그인 시간이 지났거나 이미 처리되었습니다. 앱에서 다시 로그인하세요."));
    if (error != null && SESSION_NEEDED.contains(error)) {
      // silent(prompt=none)인데 Keycloak 세션이 없다 — 오류가 아니라 "로그인 버튼을 보여라"
      return back(attempt, "error", "LOGIN_REQUIRED");
    }
    if (error != null || code == null || code.isBlank()) {
      log.info("직원 로그인 중단(Keycloak 오류 응답) app={}", attempt.app());
      return back(attempt, "error", "IDP_ERROR");
    }
    boolean sso = props.sso(attempt.app());
    KeycloakClient.Identity identity;
    KeycloakClient.Tokens kcTokens;
    try {
      kcTokens = keycloak.exchange(code, attempt.codeVerifier());
      try {
        identity = keycloak.verifyIdToken(kcTokens.idToken(), attempt.nonce());
      } catch (RuntimeException e) {
        keycloak.endSession(kcTokens.refreshToken());
        throw e;
      }
      if (!sso) {
        // 공용 PDA(결정 14 ①): Keycloak 세션을 바로 끝낸다 — 직원 세션은 PMart 세션 하나
        keycloak.endSession(kcTokens.refreshToken());
      }
    } catch (KeycloakClient.Unavailable e) {
      log.warn("직원 로그인: Keycloak 응답 없음 — {}", e.getMessage());
      return back(attempt, "error", "IDP_UNAVAILABLE");
    } catch (KeycloakClient.Rejected e) {
      log.warn("직원 로그인: Keycloak 결과 거절 — {}", e.getMessage());
      return back(attempt, "error", "IDP_REJECTED");
    }
    String operatorId;
    try {
      operatorId = tx.execute(s -> resolve(identity, now));
    } catch (PmartException e) {
      if (sso) {
        // 거절된 로그인의 Keycloak 세션은 남기지 않는다(같은 브라우저가 계속 그 계정으로 SSO 되지 않게)
        keycloak.endSession(kcTokens.refreshToken());
      }
      return back(attempt, "error", e.code());
    }
    String ticket = EmployeeTokens.random();
    store.insertTicket(
        EmployeeTokens.hash(ticket),
        Objects.requireNonNull(operatorId),
        attempt.app(),
        attempt.deviceId(),
        attempt.ticketChallenge(),
        now,
        now.plus(props.ticketTtl()),
        attempt.returnUri(),
        // SSO 앱만: 로그아웃 때 Keycloak 세션을 확인 화면 없이 끝내는 id_token_hint(이름·이메일 없음, PMart API 에 못 씀)
        sso ? kcTokens.idToken() : null);
    log.info("직원 로그인 확인: operatorId={} app={}", operatorId, attempt.app());
    return back(attempt, "ticket", ticket);
  }

  /** Keycloak sub → 운영자 ID(처음이면 pmart_operator_id 로 연결) → ACTIVE 확인. 트랜잭션 안. */
  private String resolve(KeycloakClient.Identity identity, Instant now) {
    String operatorId = store.linkedOperator(identity.subject()).orElse(null);
    if (operatorId == null) {
      String claimed = identity.operatorId();
      if (claimed == null || store.account(claimed, false).isEmpty()) {
        log.warn("직원 로그인: PMart 직원으로 등록되지 않은 Keycloak 사용자");
        throw notRegistered();
      }
      Optional<String> other = store.linkedSubject(claimed);
      if (other.isPresent() && !other.get().equals(identity.subject())) {
        // 다른 Keycloak 사용자가 이미 이 운영자 ID 로 연결됨 — 속성을 잘못 넣었거나 계정을 새로 만든 경우. 관리자가 확인한다
        log.warn("직원 로그인: 운영자 ID {} 는 다른 Keycloak 사용자와 연결되어 있음", claimed);
        throw notRegistered();
      }
      store.link(identity.subject(), claimed, now);
      operatorId = claimed;
    } else if (identity.operatorId() != null && !identity.operatorId().equals(operatorId)) {
      log.warn(
          "직원 로그인: Keycloak 속성 pmart_operator_id 가 연결된 운영자 ID 와 다름 — 연결을 따른다(operatorId={})",
          operatorId);
    }
    AccountRow account =
        store.account(operatorId, false).orElseThrow(EmployeeLoginService::notRegistered);
    requireActive(account);
    store.recordLogin(identity.subject(), operatorId, now);
    return operatorId;
  }

  // ---------------------------------------------------------------- 티켓 교환

  public LoginResult exchangeTicket(
      String ticket, String codeVerifier, String deviceId, String sourceSystem) {
    Instant now = now();
    boolean[] invalid = new boolean[1];
    LoginResult r =
        tx.execute(
            status -> {
              TicketRow t = store.lockTicket(EmployeeTokens.hash(ticket)).orElse(null);
              if (t == null) {
                invalid[0] = true;
                return null;
              }
              boolean ok =
                  t.usedAt() == null
                      && t.expiresAt().isAfter(now)
                      && t.deviceId().equals(deviceId)
                      && VERIFIER.matcher(codeVerifier).matches()
                      && constantEquals(EmployeeTokens.s256(codeVerifier), t.ticketChallenge());
              // 틀린 시도도 티켓을 태운다(한 번만 쓴다 — 추측 시도 불가)
              store.markTicketUsed(t.ticketHash(), now);
              if (!ok) {
                invalid[0] = true;
                return null;
              }
              AccountRow account =
                  store
                      .account(t.operatorId(), false)
                      .orElseThrow(EmployeeLoginService::notRegistered);
              requireActive(account);
              return openSession(
                  account,
                  deviceId,
                  t.app(),
                  sourceSystem,
                  now,
                  t.returnUri(),
                  t.keycloakIdToken());
            });
    if (invalid[0] || r == null) {
      log.warn("로그인 티켓 교환 거절");
      throw new PmartException(
          "TICKET_INVALID",
          HttpStatus.UNAUTHORIZED,
          "로그인 티켓이 유효하지 않습니다. 다시 로그인하세요.",
          false,
          List.of());
    }
    return r;
  }

  // ---------------------------------------------------------------- LAN 시험 교환(D5)

  public LoginResult exchangeDevToken(String keycloakAccessToken, String deviceId) {
    KeycloakClient.Identity identity;
    try {
      identity = keycloak.verifyDevAccessToken(keycloakAccessToken);
    } catch (KeycloakClient.Unavailable e) {
      throw new PmartException(
          "IDP_UNAVAILABLE",
          HttpStatus.SERVICE_UNAVAILABLE,
          "Keycloak 공개키를 확인할 수 없습니다.",
          true,
          List.of());
    } catch (KeycloakClient.Rejected e) {
      throw new PmartException(
          "IDP_REJECTED", HttpStatus.UNAUTHORIZED, "Keycloak 토큰을 확인할 수 없습니다.", false, List.of());
    }
    Instant now = now();
    LoginResult r =
        tx.execute(
            s -> {
              String operatorId = resolve(identity, now);
              AccountRow account = store.account(operatorId, false).orElseThrow();
              return openSession(
                  account, deviceId, "dev-cli", "dev-token-exchange", now, null, null);
            });
    log.info("LAN 시험 토큰 교환: operatorId={}", Objects.requireNonNull(r).operatorId());
    return r;
  }

  // ---------------------------------------------------------------- 로컬 개발 발급(2026-11-13)

  /**
   * local 프로필 전용 — Keycloak 없이 직원 토큰(컨트롤러가 {@code pmart.employee-auth.dev-token-mint.enabled=true}
   * 일 때만 있다). 계정이 없으면 ACTIVE 로 만들고, 있으면 역할·센터를 이 값으로 바꾼다. 역할은 설정 목록 안에서만.
   */
  public LoginResult mintDevToken(
      String operatorId, List<String> roles, List<String> centers, String deviceId) {
    List<String> unknown = roles.stream().filter(r -> !props.roles().contains(r)).toList();
    if (!unknown.isEmpty()) {
      throw new PmartException(
          com.pmart.common.web.ErrorCode.VALIDATION_FAILED, "모르는 역할: " + unknown);
    }
    List<String> sortedRoles = roles.stream().distinct().sorted().toList();
    List<String> sortedCenters = centers.stream().distinct().sorted().toList();
    Instant now = now();
    LoginResult r =
        tx.execute(
            s -> {
              if (store.account(operatorId, true).isEmpty()) {
                store.insertAccount(operatorId, ACTIVE, sortedRoles, sortedCenters, now);
              } else {
                store.updateAccount(operatorId, ACTIVE, sortedRoles, sortedCenters, now);
              }
              AccountRow account = store.account(operatorId, false).orElseThrow();
              return openSession(account, deviceId, "dev-cli", "dev-token-mint", now, null, null);
            });
    log.info("로컬 개발 토큰 발급: operatorId={}", Objects.requireNonNull(r).operatorId());
    return r;
  }

  // ---------------------------------------------------------------- 공통

  private LoginResult openSession(
      AccountRow account,
      String deviceId,
      String app,
      String sourceSystem,
      Instant now,
      @Nullable String returnUri,
      @Nullable String keycloakIdToken) {
    String sessionId = "ESES-" + UUID.randomUUID();
    Instant max = now.plus(props.sessionMaxAge());
    Instant expires = min(now.plus(props.sessionIdle(app)), max);
    store.insertSession(
        sessionId,
        account.operatorId(),
        deviceId,
        app,
        sourceSystem,
        now,
        max,
        expires,
        returnUri,
        keycloakIdToken);
    EmployeeAccess.Access access = this.access.of(account, now);
    EmployeeTokens.Tokens t =
        tokens.issue(
            account.operatorId(), access.roles(), access.centers(), sessionId, expires, now);
    return new LoginResult(account.operatorId(), access.roles(), access.centers(), t, app);
  }

  /** 재직 중(ACTIVE)이고 근무 시작일(Asia/Seoul)이 지났는가. */
  private void requireActive(AccountRow account) {
    if (!ACTIVE.equals(account.state())) {
      throw new PmartException(
          "ACCOUNT_NOT_AVAILABLE",
          HttpStatus.FORBIDDEN,
          "이용할 수 없는 직원 계정입니다(" + account.state() + ").",
          false,
          List.of());
    }
    if (account.startDate() != null
        && account.startDate().isAfter(java.time.LocalDate.now(clock.withZone(SEOUL)))) {
      throw new PmartException(
          "ACCOUNT_NOT_AVAILABLE",
          HttpStatus.FORBIDDEN,
          "근무 시작일(" + account.startDate() + ") 전에는 로그인할 수 없습니다.",
          false,
          List.of());
    }
  }

  static PmartException notRegistered() {
    return new PmartException(
        "EMPLOYEE_NOT_REGISTERED",
        HttpStatus.FORBIDDEN,
        "PMart 직원으로 등록되지 않은 계정입니다.",
        false,
        List.of());
  }

  private static URI back(AttemptRow attempt, String name, String value) {
    return UriComponentsBuilder.fromUriString(attempt.returnUri())
        .queryParam(name, value)
        .encode()
        .build()
        .toUri();
  }

  private static PmartException badRequest(String code, String message) {
    return new PmartException(
        code, ErrorCode.VALIDATION_FAILED.status(), message, false, List.of());
  }

  private static boolean constantEquals(String a, String b) {
    return MessageDigest.isEqual(
        a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
  }

  private static Instant min(Instant a, Instant b) {
    return a.isBefore(b) ? a : b;
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }
}
