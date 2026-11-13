package com.pmart.employeeauth.application;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Signed Double-Submit CSRF 값(ADR-0059 결정 4). 값 = {@code 무작위.HMAC-SHA256(키, sessionId + "!" + 무작위)}
 * (둘 다 base64url). 세션에 묶인 서명이라 다른 세션의 값이나 심어 넣은 쿠키로는 통과하지 못한다. 서버에 저장하지 않는다(상태 없음). 값은 세션 동안 바뀌지
 * 않는다 — 발급은 세션을 열 때 한 번.
 */
@Component
public class CsrfTokens {

  private static final Logger log = LoggerFactory.getLogger(CsrfTokens.class);
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String ALG = "HmacSHA256";
  private static final int MIN_KEY_BYTES = 32;

  private final byte[] key;

  public CsrfTokens(EmployeeAuthProperties props) {
    String configured = props.refreshCookie().csrfKey();
    if (configured != null && !configured.isBlank()) {
      byte[] k = configured.getBytes(StandardCharsets.UTF_8);
      if (k.length < MIN_KEY_BYTES) {
        throw new IllegalStateException(
            "pmart.employee-auth.refresh-cookie.csrf-key 는 32바이트 이상이어야 한다");
      }
      key = k;
    } else if (props.signing().ephemeral()) {
      key = new byte[MIN_KEY_BYTES];
      RANDOM.nextBytes(key);
      if (props.anyRefreshCookie()) {
        log.warn("CSRF 키가 없어 임시 키를 쓴다(local·시험 전용) — 재시작하면 쿠키 앱 세션은 다시 로그인해야 한다");
      }
    } else if (props.anyRefreshCookie()) {
      throw new IllegalStateException(
          "refresh 쿠키 앱이 있으면 pmart.employee-auth.refresh-cookie.csrf-key 를 설정해야 한다(ADR-0059)");
    } else {
      key = new byte[0]; // 쿠키 앱이 없으면 쓰지 않는다
    }
  }

  /** 세션을 열 때 한 번. */
  public String issue(String sessionId) {
    byte[] raw = new byte[32];
    RANDOM.nextBytes(raw);
    String nonce = b64(raw);
    return nonce + "." + b64(mac(sessionId, nonce));
  }

  /** 서명이 맞고 그 세션에 묶인 값인가. 형식이 틀려도 false. */
  public boolean verify(String sessionId, @Nullable String value) {
    if (key.length == 0 || value == null || value.length() > 256) {
      return false;
    }
    int dot = value.indexOf('.');
    if (dot <= 0 || dot != value.lastIndexOf('.')) {
      return false;
    }
    byte[] given;
    try {
      given = Base64.getUrlDecoder().decode(value.substring(dot + 1));
    } catch (IllegalArgumentException e) {
      return false;
    }
    return MessageDigest.isEqual(mac(sessionId, value.substring(0, dot)), given);
  }

  /** 두 값이 같은가(시간 일정). */
  public static boolean same(@Nullable String a, @Nullable String b) {
    return a != null
        && b != null
        && MessageDigest.isEqual(
            a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
  }

  private byte[] mac(String sessionId, String nonce) {
    try {
      Mac m = Mac.getInstance(ALG);
      m.init(new SecretKeySpec(key, ALG));
      return m.doFinal((sessionId + "!" + nonce).getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String b64(byte[] b) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }
}
