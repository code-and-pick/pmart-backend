package com.pmart.employeeauth.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.employeeauth.infrastructure.EmployeeAuthStore;
import com.pmart.employeeauth.infrastructure.EmployeeAuthStore.AccountRow;
import com.pmart.employeeauth.infrastructure.EmployeeAuthStore.RefreshRow;
import com.pmart.employeeauth.infrastructure.EmployeeAuthStore.SessionRow;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 토큰 갱신·로그아웃·현재 세션. refresh token 은 한 번만 쓴다 — 쓴 토큰이 다시 오면 탈취로 보고 세션 폐기(TOKEN_REUSED). 직원 상태·역할은 갱신
 * 때마다 다시 읽는다(권한이 바뀌면 다음 갱신부터 반영). 세션 만료 = min(갱신 시각 + 앱 무활동 한도, 최대 만료).
 */
@Service
public class EmployeeSessionService {

  private static final Logger log = LoggerFactory.getLogger(EmployeeSessionService.class);

  public record Current(
      String sessionId, String operatorId, String app, Instant issuedAt, Instant expiresAt) {}

  private final EmployeeAuthStore store;
  private final EmployeeTokens tokens;
  private final EmployeeAuthProperties props;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final EmployeeAccess access;
  private final SubjectBlocks blocks;
  private final CsrfTokens csrf;

  public EmployeeSessionService(
      EmployeeAuthStore store,
      EmployeeTokens tokens,
      EmployeeAuthProperties props,
      TransactionTemplate tx,
      Clock clock,
      EmployeeAccess access,
      SubjectBlocks blocks,
      CsrfTokens csrf) {
    this.store = store;
    this.tokens = tokens;
    this.props = props;
    this.tx = tx;
    this.clock = clock;
    this.access = access;
    this.blocks = blocks;
    this.csrf = csrf;
  }

  /**
   * 쿠키 갱신(ADR-0059). ① Signed Double-Submit — 헤더 = CSRF 쿠키, 서명이 그 세션 것. 틀리면 403 CSRF_INVALID(세션은 끊지
   * 않는다 — 남이 일부러 틀린 요청으로 세션을 끝내지 못하게). ② 소유권·회전은 {@link #refresh}.
   */
  public EmployeeTokens.Tokens refreshWithCookie(
      String sessionId,
      @Nullable String refreshCookie,
      @Nullable String csrfCookie,
      @Nullable String csrfHeader,
      @Nullable String deviceId) {
    if (!CsrfTokens.same(csrfHeader, csrfCookie) || !csrf.verify(sessionId, csrfCookie)) {
      log.warn("CSRF 확인 실패(쿠키 갱신): sessionId={}", sessionId);
      throw new PmartException(
          "CSRF_INVALID", HttpStatus.FORBIDDEN, "CSRF 확인에 실패했습니다. 다시 로그인하세요.", false, List.of());
    }
    if (refreshCookie == null || refreshCookie.isBlank()) {
      throw new PmartException(
          "TOKEN_INVALID",
          HttpStatus.UNAUTHORIZED,
          "refresh token 이 유효하지 않습니다. 다시 로그인하세요.",
          false,
          List.of());
    }
    return refresh(refreshCookie, deviceId, sessionId);
  }

  /** 세션을 열 때 한 번 — 쿠키 앱의 CSRF 값. */
  public String csrfFor(String sessionId) {
    return csrf.issue(sessionId);
  }

  /** 앱이 refresh 쿠키를 쓰는가. */
  public boolean refreshCookie(String app) {
    return props.refreshCookie(app);
  }

  /** 로그아웃 결과 — Keycloak 로그아웃 주소(SSO 앱), 지울 쿠키의 세션(refresh 쿠키 앱이면, ADR-0059). */
  public record LogoutOutcome(@Nullable URI idpLogoutUri, @Nullable String cookieSessionId) {}

  /**
   * 갱신. {@code cookieSessionId} 가 있으면 쿠키 갱신 경로(ADR-0059) — refresh token 이 그 세션 것이고 그 세션이 refresh
   * 쿠키 앱이어야 한다. 없으면 본문 경로 — refresh 쿠키 앱 세션은 거절한다. 어느 쪽이든 거절은 토큰을 쓰지 않은 채로 둔다(재사용 감지는 이미 쓴 토큰만).
   */
  public EmployeeTokens.Tokens refresh(
      String refreshToken, @Nullable String deviceId, @Nullable String cookieSessionId) {
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    String[] reason = new String[1];
    EmployeeTokens.Tokens result =
        tx.execute(
            status -> {
              RefreshRow r = store.lockRefresh(EmployeeTokens.hash(refreshToken)).orElse(null);
              if (r == null) {
                reason[0] = "TOKEN_INVALID";
                return null;
              }
              SessionRow s = store.session(r.sessionId(), true).orElseThrow();
              if (r.usedAt() != null) {
                store.revokeSession(s.sessionId(), "REFRESH_REUSED", now);
                // 탈취 의심 — 그 세션의 access token 도 바로 막는다(2026-11-13)
                blocks.blockSession(
                    s.operatorId(),
                    s.sessionId(),
                    version(s.operatorId()),
                    now,
                    "TOKEN_REUSED",
                    new EventEnvelope.Actor(
                        EventEnvelope.ActorType.SYSTEM, "employee-auth-service"));
                log.warn("refresh token 재사용 감지: sessionId={}", s.sessionId());
                reason[0] = "TOKEN_REUSED";
                return null;
              }
              boolean cookieApp = props.refreshCookie(s.app());
              boolean ownerOk =
                  cookieSessionId == null
                      ? !cookieApp
                      : cookieApp && s.sessionId().equals(cookieSessionId);
              if (!ownerOk
                  || !"ACTIVE".equals(s.state())
                  || !r.expiresAt().isAfter(now)
                  || !s.expiresAt().isAfter(now)
                  || !s.deviceId().equals(deviceId)) {
                reason[0] = "TOKEN_INVALID";
                return null;
              }
              AccountRow account = store.account(s.operatorId(), false).orElse(null);
              if (account == null || !EmployeeLoginService.ACTIVE.equals(account.state())) {
                store.revokeSession(s.sessionId(), "ACCOUNT_DISABLED", now);
                reason[0] = "TOKEN_INVALID";
                return null;
              }
              Instant idle = now.plus(props.sessionIdle(s.app()));
              Instant extended = idle.isBefore(s.maxExpiresAt()) ? idle : s.maxExpiresAt();
              store.markRefreshUsed(r.tokenHash(), now);
              store.refreshed(s.sessionId(), now, extended);
              EmployeeAccess.Access access = this.access.of(account, now);
              return tokens.issue(
                  account.operatorId(),
                  access.roles(),
                  access.centers(),
                  s.sessionId(),
                  extended,
                  now);
            });
    if (result == null) {
      throw new PmartException(
          reason[0],
          HttpStatus.UNAUTHORIZED,
          "TOKEN_REUSED".equals(reason[0])
              ? "이미 사용한 refresh token 입니다. 세션을 종료했으니 다시 로그인하세요."
              : "refresh token 이 유효하지 않습니다. 다시 로그인하세요.",
          false,
          List.of());
    }
    return result;
  }

  /** 현재 토큰의 세션(또는 그 직원의 모든 세션) 종료. 이미 종료여도 성공. */
  /**
   * 현재 토큰의 세션(또는 그 직원의 모든 세션)을 끝낸다. 이미 끝났어도 성공. SSO 앱(백오피스) 세션이면 브라우저가 이동할 Keycloak 로그아웃 주소를 돌려준다(이
   * 브라우저의 Keycloak 세션까지 끝내려고 — 2026-11-12 사용자 결정). 아니면 null.
   */
  public LogoutOutcome logout(boolean allDevices) {
    Jwt jwt = ownJwt();
    Instant now = clock.instant();
    String sid = jwt.getClaimAsString("sid");
    return tx.execute(
        status -> {
          SessionRow current = sid == null ? null : store.session(sid, true).orElse(null);
          String cookieSession =
              current != null
                      && current.operatorId().equals(jwt.getSubject())
                      && props.refreshCookie(current.app())
                  ? current.sessionId()
                  : null;
          URI idpLogout =
              current != null
                      && "ACTIVE".equals(current.state())
                      && current.operatorId().equals(jwt.getSubject())
                  ? idpLogoutUri(current)
                  : null;
          EventEnvelope.Actor me =
              new EventEnvelope.Actor(EventEnvelope.ActorType.OPERATOR, jwt.getSubject());
          // 이미 나간 access token(15분)도 바로 401 — 게이트웨이 Redis 차단 목록(2026-11-13)
          if (allDevices) {
            store.revokeAllSessions(jwt.getSubject(), "LOGOUT_ALL", now);
            blocks.blockAll(jwt.getSubject(), version(jwt.getSubject()), now, "LOGOUT_ALL", me);
          } else if (sid != null) {
            store.revokeSession(sid, "LOGOUT", now);
            if (current != null && current.operatorId().equals(jwt.getSubject())) {
              blocks.blockSession(
                  jwt.getSubject(), sid, version(jwt.getSubject()), now, "LOGOUT", me);
            }
          }
          return new LogoutOutcome(idpLogout, cookieSession);
        });
  }

  /** 사건 aggregateVersion — 직원 계정 버전(없으면 1). */
  private long version(String operatorId) {
    return store.account(operatorId, false).map(a -> a.version()).orElse(1L);
  }

  /** Keycloak end_session(브라우저용). id_token_hint 로 확인 화면 없이 끝내고 그 앱의 로그인 화면으로 돌아온다. */
  private @Nullable URI idpLogoutUri(SessionRow s) {
    if (!props.sso(s.app()) || s.keycloakIdToken() == null || s.returnUri() == null) {
      return null;
    }
    EmployeeAuthProperties.App app = props.apps().get(s.app());
    URI back = URI.create(s.returnUri());
    String postLogout =
        back.getScheme()
            + "://"
            + back.getRawAuthority()
            + Objects.requireNonNull(app.postLogoutPath());
    return UriComponentsBuilder.fromUriString(
            Objects.requireNonNull(props.keycloak().endSessionUri()))
        .queryParam("client_id", props.keycloak().clientId())
        .queryParam("id_token_hint", s.keycloakIdToken())
        .queryParam("post_logout_redirect_uri", postLogout)
        .encode()
        .build()
        .toUri();
  }

  public Current current() {
    Jwt jwt = ownJwt();
    String sid = jwt.getClaimAsString("sid");
    SessionRow s = sid == null ? null : store.session(sid, false).orElse(null);
    if (s == null
        || !"ACTIVE".equals(s.state())
        || !s.expiresAt().isAfter(clock.instant())
        || !s.operatorId().equals(jwt.getSubject())) {
      throw new PmartException(
          "SESSION_EXPIRED", HttpStatus.UNAUTHORIZED, "세션이 종료되었습니다. 다시 로그인하세요.", false, List.of());
    }
    return new Current(
        s.sessionId(),
        s.operatorId(),
        s.app(),
        Objects.requireNonNull(jwt.getIssuedAt()),
        Objects.requireNonNull(jwt.getExpiresAt()));
  }

  /** 이 서비스가 발급한 직원 토큰만(Keycloak 토큰·다른 발급자는 403). */
  private Jwt ownJwt() {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null
        && auth.getPrincipal() instanceof Jwt jwt
        && EmployeeTokens.TYPE.equals(jwt.getClaimAsString("typ"))
        && jwt.getIssuer() != null
        && props.issuer().equals(jwt.getIssuer().toString())) {
      return jwt;
    }
    throw new PmartException(ErrorCode.FORBIDDEN, "employee-auth-service 가 발급한 직원 토큰이 필요합니다.");
  }
}
