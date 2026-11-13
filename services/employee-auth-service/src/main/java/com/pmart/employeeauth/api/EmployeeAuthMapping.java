package com.pmart.employeeauth.api;

import com.pmart.employeeauth.api.generated.model.Employee;
import com.pmart.employeeauth.api.generated.model.EmployeeLoginResult;
import com.pmart.employeeauth.api.generated.model.EmployeeState;
import com.pmart.employeeauth.api.generated.model.TokenPair;
import com.pmart.employeeauth.api.generated.model.TokenPair.RefreshTransportEnum;
import com.pmart.employeeauth.application.EmployeeLoginService;
import com.pmart.employeeauth.application.EmployeeTokens;
import com.pmart.employeeauth.infrastructure.EmployeeAuthStore;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** 응답 변환. */
final class EmployeeAuthMapping {

  private EmployeeAuthMapping() {}

  /** 본문 refresh token(PDA 등). */
  static TokenPair tokens(EmployeeTokens.Tokens t) {
    return tokens(t, false);
  }

  /** {@code cookie} 면 refresh token 을 본문에 싣지 않는다(HttpOnly 쿠키 — ADR-0059). */
  static TokenPair tokens(EmployeeTokens.Tokens t, boolean cookie) {
    return new TokenPair()
        .accessToken(t.accessToken())
        .accessTokenExpiresAt(odt(t.accessTokenExpiresAt()))
        .refreshToken(cookie ? null : t.refreshToken())
        .refreshTransport(cookie ? RefreshTransportEnum.COOKIE : RefreshTransportEnum.BODY)
        .refreshTokenExpiresAt(odt(t.refreshTokenExpiresAt()))
        .sessionId(t.sessionId());
  }

  static EmployeeLoginResult login(EmployeeLoginService.LoginResult r) {
    return login(r, false);
  }

  static EmployeeLoginResult login(EmployeeLoginService.LoginResult r, boolean cookie) {
    return new EmployeeLoginResult()
        .operatorId(r.operatorId())
        .roles(r.roles())
        .centers(r.centers())
        .tokens(tokens(r.tokens(), cookie));
  }

  static Employee employee(EmployeeAuthStore.AccountRow a) {
    return new Employee()
        .operatorId(a.operatorId())
        .state(EmployeeState.fromValue(a.state()))
        .roles(a.roles())
        .centers(a.centers())
        .keycloakLinked(a.keycloakLinked())
        .lastLoginAt(a.lastLoginAt() == null ? null : odt(a.lastLoginAt()))
        .version(a.version())
        .updatedAt(odt(a.updatedAt()));
  }

  static OffsetDateTime odt(Instant i) {
    return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
  }
}
