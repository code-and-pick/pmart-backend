package com.pmart.employeeauth.api;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseCookie;

/**
 * 웹 앱 refresh 쿠키·CSRF 쿠키(ADR-0059). HTTP 모양만 — 값의 의미(소유권·서명)는 application 이 본다.
 *
 * <ul>
 *   <li>{@value #REFRESH} — refresh token. HttpOnly·Secure·SameSite=Strict·Path=갱신
 *       주소/{sessionId}(세션마다 따로 실린다)·Max-Age 없음.
 *   <li>{@value #CSRF_PREFIX}{sessionId} — Signed Double-Submit 값. JS 가 읽는다(HttpOnly
 *       아님)·Secure·SameSite=Strict·Path=/ ·Max-Age 없음. 이름에 sessionId 를 넣어 탭·앱(포트)끼리 덮어쓰지 않는다.
 * </ul>
 *
 * 게이트웨이와 서비스 경로가 같다(재작성 없음) — Path 는 브라우저가 보는 게이트웨이 경로 그대로.
 */
final class RefreshCookies {

  static final String REFRESH = "__Secure-pmart_ea_rt";
  static final String CSRF_PREFIX = "__Secure-pmart_ea_csrf_";
  static final String REFRESH_PATH_PREFIX = "/api/v1/employee-auth/tokens/refresh/";

  private RefreshCookies() {}

  /** 세션을 열 때: refresh + CSRF. */
  static List<String> open(String sessionId, String refreshToken, String csrfValue) {
    return List.of(refresh(sessionId, refreshToken), csrf(sessionId, csrfValue, null));
  }

  /** 갱신 때: 새 refresh 만(CSRF 는 세션 동안 그대로). */
  static String refresh(String sessionId, String refreshToken) {
    return ResponseCookie.from(REFRESH, refreshToken)
        .httpOnly(true)
        .secure(true)
        .sameSite("Strict")
        .path(REFRESH_PATH_PREFIX + sessionId)
        .build()
        .toString();
  }

  /** 로그아웃 때: 둘 다 지운다(같은 이름·Path·속성 + Max-Age=0). */
  static List<String> clear(String sessionId) {
    return List.of(
        ResponseCookie.from(REFRESH, "")
            .httpOnly(true)
            .secure(true)
            .sameSite("Strict")
            .path(REFRESH_PATH_PREFIX + sessionId)
            .maxAge(Duration.ZERO)
            .build()
            .toString(),
        csrf(sessionId, "", Duration.ZERO));
  }

  private static String csrf(String sessionId, String value, @Nullable Duration maxAge) {
    ResponseCookie.ResponseCookieBuilder b =
        ResponseCookie.from(CSRF_PREFIX + sessionId, value)
            .httpOnly(false)
            .secure(true)
            .sameSite("Strict")
            .path("/");
    if (maxAge != null) {
      b.maxAge(maxAge);
    }
    return b.build().toString();
  }

  static @Nullable String read(HttpServletRequest request, String name) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return null;
    }
    for (Cookie c : cookies) {
      if (name.equals(c.getName())) {
        return c.getValue();
      }
    }
    return null;
  }
}
