package com.pmart.employeeauth.application;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pmart.employee-auth.*} — 직원 로그인·토큰(ADR-0056). 수명 수치는 보안 설정값. 값을 바꾸면 재시작한다.
 *
 * @param issuer 발급 토큰의 {@code iss} — 발급자 표의 직원 줄 issuer-uri 와 같아야 한다
 * @param audience 발급 토큰의 {@code aud}(기본 {@code pmart-api})
 * @param signing 서명키
 * @param accessTokenTtl access token 수명(15분)
 * @param sessionMaxAge 세션 최대 길이(근무 교대 — 12시간). 갱신해도 넘지 않는다
 * @param ticketTtl 로그인 티켓 수명(60초)
 * @param loginAttemptTtl 로그인 시작 → 콜백까지 허용 시간(10분)
 * @param apps 로그인하는 앱(mfc, backoffice …) — 허용 복귀 주소·무활동 한도
 * @param roles 직원 역할 목록(이 밖의 역할은 저장하지 않는다)
 * @param keycloak Keycloak(인가 서버) 연결
 * @param devTokenExchange LAN 시험 전용 토큰 교환(ADR-0056 D5)
 * @param refreshCookie 웹 앱 refresh 쿠키·Signed Double-Submit CSRF(ADR-0059)
 */
@ConfigurationProperties(prefix = "pmart.employee-auth")
public record EmployeeAuthProperties(
    String issuer,
    String audience,
    Signing signing,
    Duration accessTokenTtl,
    Duration sessionMaxAge,
    Duration ticketTtl,
    Duration loginAttemptTtl,
    Map<String, App> apps,
    List<String> roles,
    Keycloak keycloak,
    DevTokenExchange devTokenExchange,
    RefreshCookie refreshCookie) {

  public static final String DEFAULT_AUDIENCE = "pmart-api";

  public EmployeeAuthProperties {
    if (issuer == null || issuer.isBlank()) {
      throw new IllegalArgumentException("pmart.employee-auth.issuer 를 설정해야 한다");
    }
    audience = audience == null || audience.isBlank() ? DEFAULT_AUDIENCE : audience;
    signing = signing == null ? new Signing(List.of(), false) : signing;
    accessTokenTtl = accessTokenTtl == null ? Duration.ofMinutes(15) : accessTokenTtl;
    sessionMaxAge = sessionMaxAge == null ? Duration.ofHours(12) : sessionMaxAge;
    ticketTtl = ticketTtl == null ? Duration.ofSeconds(60) : ticketTtl;
    loginAttemptTtl = loginAttemptTtl == null ? Duration.ofMinutes(10) : loginAttemptTtl;
    apps = apps == null ? Map.of() : Map.copyOf(apps);
    roles = roles == null ? List.of() : List.copyOf(roles);
    keycloak =
        keycloak == null
            ? new Keycloak(null, null, null, null, null, null, null, null, null, null)
            : keycloak;
    devTokenExchange =
        devTokenExchange == null ? new DevTokenExchange(false, null) : devTokenExchange;
    refreshCookie = refreshCookie == null ? new RefreshCookie(null) : refreshCookie;
  }

  /** 앱별 세션 무활동 한도. 모르는 앱(시험 교환 등)은 세션 최대 길이. */
  public Duration sessionIdle(String app) {
    App a = apps.get(app);
    Duration idle = a == null || a.sessionIdle() == null ? sessionMaxAge : a.sessionIdle();
    return idle.compareTo(sessionMaxAge) > 0 ? sessionMaxAge : idle;
  }

  /**
   * @param keys PKCS#8 PEM RSA 개인키 목록. 첫 번째가 활성 키, 나머지는 교체 중 검증용으로 JWKS 에만
   * @param ephemeral 키가 없을 때 임시 키(local/test 전용)
   */
  public record Signing(List<Key> keys, boolean ephemeral) {
    public Signing {
      keys = keys == null ? List.of() : List.copyOf(keys);
    }
  }

  /** {@code kid} + PKCS#8 PEM. */
  public record Key(String kid, String privateKey) {}

  /**
   * @param returnUris 허용 복귀 주소(첫 번째가 기본). 로그인 시작의 returnUri 는 이 중 하나와 정확히 같아야 한다
   * @param sessionIdle 무활동 한도 — 이 시간 동안 갱신이 없으면 다시 로그인(mfc 30분, ADR-0056 결정 14 ③)
   * @param sso Keycloak 세션을 남겨 SSO 를 쓴다(백오피스, 2026-11-12 사용자 결정). 끄면(기본 — PDA) 로그인 직후 Keycloak 세션을
   *     끝내고 매번 비밀번호를 묻는다(결정 14 ①). 켠 앱은 로그아웃 때 Keycloak 로그아웃 주소를 돌려준다
   * @param postLogoutPath SSO 앱이 Keycloak 로그아웃 뒤 돌아갈 경로(로그인한 복귀 주소의 출처 + 이 경로, 기본 /login). Keycloak
   *     클라이언트의 post.logout.redirect.uris 에 있어야 한다
   * @param refreshCookie refresh token 을 본문 대신 세션별 HttpOnly 쿠키로(웹 앱 — ADR-0059). 켠 앱의 세션은 쿠키 갱신
   *     경로로만 갱신된다
   */
  public record App(
      List<String> returnUris,
      @Nullable Duration sessionIdle,
      boolean sso,
      @Nullable String postLogoutPath,
      boolean refreshCookie) {
    public App {
      returnUris = returnUris == null ? List.of() : List.copyOf(returnUris);
      postLogoutPath =
          postLogoutPath == null || postLogoutPath.isBlank() ? "/login" : postLogoutPath;
    }
  }

  /** 앱이 refresh 쿠키를 쓰는가(모르는 앱은 아니다 — ADR-0059). */
  public boolean refreshCookie(String app) {
    App a = apps.get(app);
    return a != null && a.refreshCookie();
  }

  /** 어느 앱이든 refresh 쿠키를 쓰는가. */
  public boolean anyRefreshCookie() {
    return apps.values().stream().anyMatch(App::refreshCookie);
  }

  /**
   * @param csrfKey Signed Double-Submit CSRF 값의 HMAC-SHA256 키(32바이트 이상 문자열, 설정 {cipher}, 로그 금지). 쿠키
   *     앱이 있는데 비어 있으면 기동 실패 — 단 {@code signing.ephemeral}(local·시험)이면 기동마다 임시 키
   */
  public record RefreshCookie(@Nullable String csrfKey) {}

  /** 앱이 SSO 를 쓰는가(모르는 앱은 아니다). */
  public boolean sso(String app) {
    App a = apps.get(app);
    return a != null && a.sso();
  }

  /**
   * Keycloak 연결. 브라우저가 여는 주소(authorizationUri)와 서버끼리 부르는 주소(token·jwks·logout — 같은 Docker 네트워크 안)를
   * 나눈다.
   *
   * @param issuer ID 토큰 {@code iss}(= KC_HOSTNAME 기준 Realm 주소)
   * @param authorizationUri 브라우저용 로그인 주소(기본 issuer + /protocol/openid-connect/auth)
   * @param tokenUri 코드 교환(서버끼리)
   * @param jwkSetUri Keycloak 공개키(서버끼리)
   * @param logoutUri Keycloak 세션 끝내기(서버끼리)
   * @param clientId 이 서비스의 Keycloak 클라이언트 ID(기밀)
   * @param clientSecret 클라이언트 비밀(설정 {cipher}, 로그 금지)
   * @param redirectUri Keycloak 에 등록한 콜백(게이트웨이 경유 공개 주소)
   * @param timeout Keycloak 호출 연결·응답 한도
   * @param endSessionUri 브라우저용 로그아웃 주소(SSO 앱 로그아웃, 기본 issuer + /protocol/openid-connect/logout)
   */
  public record Keycloak(
      @Nullable String issuer,
      @Nullable String authorizationUri,
      @Nullable String tokenUri,
      @Nullable String jwkSetUri,
      @Nullable String logoutUri,
      @Nullable String clientId,
      @Nullable String clientSecret,
      @Nullable String redirectUri,
      @Nullable Duration timeout,
      @Nullable String endSessionUri) {

    public Keycloak {
      String base = issuer == null ? "" : issuer.replaceAll("/+$", "");
      authorizationUri =
          blank(authorizationUri) ? base + "/protocol/openid-connect/auth" : authorizationUri;
      tokenUri = blank(tokenUri) ? base + "/protocol/openid-connect/token" : tokenUri;
      jwkSetUri = blank(jwkSetUri) ? base + "/protocol/openid-connect/certs" : jwkSetUri;
      logoutUri = blank(logoutUri) ? base + "/protocol/openid-connect/logout" : logoutUri;
      timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
      endSessionUri =
          blank(endSessionUri) ? base + "/protocol/openid-connect/logout" : endSessionUri;
    }

    /** 로그인에 필요한 값이 모두 있는가. */
    public boolean configured() {
      return !blank(issuer) && !blank(clientId) && !blank(clientSecret) && !blank(redirectUri);
    }

    private static boolean blank(@Nullable String s) {
      return s == null || s.isBlank();
    }
  }

  /**
   * @param enabled 켜야만 경로가 생긴다(운영 설정에는 두지 않는다)
   * @param clientIds 받아 줄 Keycloak 토큰의 azp(기본 pmart-dev-cli)
   */
  public record DevTokenExchange(boolean enabled, @Nullable List<String> clientIds) {
    public DevTokenExchange {
      clientIds =
          clientIds == null || clientIds.isEmpty()
              ? List.of("pmart-dev-cli")
              : List.copyOf(clientIds);
    }
  }
}
