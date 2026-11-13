package com.pmart.employeeauth.api;

import com.pmart.employeeauth.api.generated.LoginsApi;
import com.pmart.employeeauth.application.EmployeeLoginService;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 LoginsApi — 브라우저가 여는 로그인 시작·Keycloak 콜백. 둘 다 302 만 돌려준다(본문 없음). */
@RestController
public class LoginsController implements LoginsApi {

  private final EmployeeLoginService logins;

  public LoginsController(EmployeeLoginService logins) {
    this.logins = logins;
  }

  @Override
  public ResponseEntity<Void> startEmployeeLogin(
      String app,
      String deviceId,
      String codeChallenge,
      String codeChallengeMethod,
      @Nullable String returnUri,
      @Nullable Boolean silent) {
    return ResponseEntity.status(HttpStatus.FOUND)
        .location(
            logins.start(
                app,
                deviceId,
                codeChallenge,
                codeChallengeMethod,
                returnUri,
                Boolean.TRUE.equals(silent)))
        .cacheControl(CacheControl.noStore())
        .build();
  }

  @Override
  public ResponseEntity<Void> completeEmployeeLogin(
      @Nullable String state, @Nullable String code, @Nullable String error) {
    return ResponseEntity.status(HttpStatus.FOUND)
        .location(logins.complete(state, code, error))
        .cacheControl(CacheControl.noStore())
        .header("Referrer-Policy", "no-referrer")
        .build();
  }
}
