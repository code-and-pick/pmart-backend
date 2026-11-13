package com.pmart.employeeauth.api;

import com.pmart.employeeauth.api.generated.TokensApi;
import com.pmart.employeeauth.api.generated.model.EmployeeLoginResult;
import com.pmart.employeeauth.api.generated.model.EmployeeLogoutRequest;
import com.pmart.employeeauth.api.generated.model.EmployeeLogoutResult;
import com.pmart.employeeauth.api.generated.model.EmployeeSession;
import com.pmart.employeeauth.api.generated.model.ExchangeLoginTicketRequest;
import com.pmart.employeeauth.api.generated.model.RefreshEmployeeTokensRequest;
import com.pmart.employeeauth.api.generated.model.TokenPair;
import com.pmart.employeeauth.application.EmployeeLoginService;
import com.pmart.employeeauth.application.EmployeeSessionService;
import com.pmart.employeeauth.application.EmployeeTokens;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 TokensApi — 티켓 교환·갱신·로그아웃·현재 세션. 티켓·refresh token 은 한 번만 쓰므로 같은 Idempotency-Key 재전송도 결과를 재생하지
 * 않는다(토큰을 저장하지 않으려고).
 */
@RestController
public class TokensController implements TokensApi {

  private final EmployeeLoginService logins;
  private final EmployeeSessionService sessions;
  private final HttpServletRequest request;

  public TokensController(
      EmployeeLoginService logins, EmployeeSessionService sessions, HttpServletRequest request) {
    this.logins = logins;
    this.sessions = sessions;
    this.request = request; // 요청 범위 프록시 — 쿠키 읽기(ADR-0059)
  }

  @Override
  public ResponseEntity<EmployeeLoginResult> exchangeLoginTicket(
      String idempotencyKey,
      String xSourceSystem,
      String xDeviceId,
      ExchangeLoginTicketRequest body) {
    EmployeeLoginService.LoginResult r =
        logins.exchangeTicket(body.getTicket(), body.getCodeVerifier(), xDeviceId, xSourceSystem);
    boolean cookie = sessions.refreshCookie(r.app());
    ResponseEntity.BodyBuilder res = ResponseEntity.ok().cacheControl(CacheControl.noStore());
    if (cookie) {
      String sid = r.tokens().sessionId();
      res.header(
          HttpHeaders.SET_COOKIE,
          RefreshCookies.open(sid, r.tokens().refreshToken(), sessions.csrfFor(sid))
              .toArray(String[]::new));
    }
    return res.body(EmployeeAuthMapping.login(r, cookie));
  }

  @Override
  public ResponseEntity<TokenPair> refreshEmployeeTokens(
      String idempotencyKey,
      String xSourceSystem,
      String xDeviceId,
      RefreshEmployeeTokensRequest body) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            EmployeeAuthMapping.tokens(sessions.refresh(body.getRefreshToken(), xDeviceId, null)));
  }

  @Override
  public ResponseEntity<TokenPair> refreshEmployeeTokensWithCookie(
      String idempotencyKey,
      String xSourceSystem,
      String sessionId,
      String xDeviceId,
      String xCSRFToken) {
    EmployeeTokens.Tokens t =
        sessions.refreshWithCookie(
            sessionId,
            RefreshCookies.read(request, RefreshCookies.REFRESH),
            RefreshCookies.read(request, RefreshCookies.CSRF_PREFIX + sessionId),
            xCSRFToken,
            xDeviceId);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.SET_COOKIE, RefreshCookies.refresh(sessionId, t.refreshToken()))
        .body(EmployeeAuthMapping.tokens(t, true));
  }

  @Override
  public ResponseEntity<EmployeeLogoutResult> employeeLogout(
      String idempotencyKey, String xSourceSystem, @Nullable EmployeeLogoutRequest body) {
    EmployeeSessionService.LogoutOutcome out =
        sessions.logout(body != null && Boolean.TRUE.equals(body.getAllDevices()));
    ResponseEntity.BodyBuilder res = ResponseEntity.ok().cacheControl(CacheControl.noStore());
    if (out.cookieSessionId() != null) {
      // 서버 세션 폐기와 함께 이 탭 세션의 쿠키 둘을 지운다(ADR-0059 결정 7)
      res.header(
          HttpHeaders.SET_COOKIE,
          RefreshCookies.clear(out.cookieSessionId()).toArray(String[]::new));
    }
    java.net.URI idp = out.idpLogoutUri();
    return res.body(new EmployeeLogoutResult().idpLogoutUri(idp == null ? null : idp.toString()));
  }

  @Override
  public ResponseEntity<EmployeeSession> getCurrentEmployeeSession() {
    EmployeeSessionService.Current c = sessions.current();
    return ResponseEntity.ok(
        new EmployeeSession()
            .sessionId(c.sessionId())
            .operatorId(c.operatorId())
            .app(c.app())
            .issuedAt(EmployeeAuthMapping.odt(c.issuedAt()))
            .expiresAt(EmployeeAuthMapping.odt(c.expiresAt())));
  }
}
