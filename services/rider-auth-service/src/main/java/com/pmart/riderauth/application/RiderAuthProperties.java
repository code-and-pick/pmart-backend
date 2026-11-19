package com.pmart.riderauth.application;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pmart.rider-auth.*} — 라이더 로그인·토큰(ADR-0054 E·F, ADR-0055). 수명·제한 수치는 보안 설정값. 값을 바꾸면 재시작한다.
 *
 * @param issuer 발급 토큰의 {@code iss} — 발급자 표(pmart.security.issuers)의 rider 줄 issuer-uri 와 같아야 한다
 * @param signing 서명키
 * @param accessTokenTtl access token 수명
 * @param refreshTokenTtl refresh token 수명(회전)
 * @param sessionMaxAge 세션 유휴 수명 — 마지막 갱신부터 이 기간 안 쓰면 재로그인
 * @param failureWindow 실패 집계 창(15분)
 * @param maxFailures 창 안의 실패 한도(5회)
 * @param restriction 제한 시간(10분)
 * @param google Google ID 토큰 검증
 * @param audience 발급 토큰의 {@code aud}(기본 {@code pmart-api}) — 발급자 표 rider 줄 {@code audiences} 와
 *     맞춘다(ADR-0055 결정 6). Google ID 토큰의 aud(웹 클라이언트 ID)와는 다른 값이다
 */
@ConfigurationProperties(prefix = "pmart.rider-auth")
public record RiderAuthProperties(
    String issuer,
    Signing signing,
    Duration accessTokenTtl,
    Duration refreshTokenTtl,
    Duration sessionMaxAge,
    Duration failureWindow,
    int maxFailures,
    Duration restriction,
    Google google,
    String audience,
    @org.jspecify.annotations.Nullable Google kakao,
    @org.jspecify.annotations.Nullable Google naver) {

  /** PMart API 용 토큰의 기본 {@code aud}. */
  public static final String DEFAULT_AUDIENCE = "pmart-api";

  public RiderAuthProperties {
    if (issuer == null || issuer.isBlank()) {
      throw new IllegalArgumentException("pmart.rider-auth.issuer 를 설정해야 한다");
    }
    signing = signing == null ? new Signing(List.of(), false) : signing;
    accessTokenTtl = accessTokenTtl == null ? Duration.ofMinutes(30) : accessTokenTtl;
    refreshTokenTtl = refreshTokenTtl == null ? Duration.ofDays(30) : refreshTokenTtl;
    sessionMaxAge = sessionMaxAge == null ? Duration.ofDays(30) : sessionMaxAge;
    failureWindow = failureWindow == null ? Duration.ofMinutes(15) : failureWindow;
    maxFailures = maxFailures <= 0 ? 5 : maxFailures;
    restriction = restriction == null ? Duration.ofMinutes(10) : restriction;
    google = google == null ? new Google(null, null, null) : google;
    // 카카오·네이버(2026-11-19 — 라이더 앱 요청, 고객 ADR-0071 과 같은 흐름): 공개키·발급자는 제공자 고정값, 앱 키(aud)만 설정
    kakao =
        new Google(
            kakao == null ? null : kakao.clientIds(),
            "https://kauth.kakao.com/.well-known/jwks.json",
            List.of("https://kauth.kakao.com"));
    naver =
        new Google(
            naver == null ? null : naver.clientIds(),
            "https://nid.naver.com/oauth2/jwks",
            List.of("https://nid.naver.com"));
    audience = audience == null || audience.isBlank() ? DEFAULT_AUDIENCE : audience;
  }

  /**
   * 서명키.
   *
   * @param keys PKCS#8 PEM RSA 개인키 목록. 첫 번째가 활성(서명) 키, 나머지는 교체 중 검증용으로 JWKS 에만 싣는다.
   * @param ephemeral 키가 없을 때 기동 시 임시 키를 만든다. local/test 전용 — 재시작하면 기존 토큰이 모두 무효.
   */
  public record Signing(List<Key> keys, boolean ephemeral) {
    public Signing {
      keys = keys == null ? List.of() : List.copyOf(keys);
    }
  }

  /** {@code kid} + PKCS#8 PEM. */
  public record Key(String kid, String privateKey) {}

  /**
   * @param clientIds 허용할 ID 토큰 {@code aud} = 라이더용 Google 웹 클라이언트 ID(라이더 앱 serverClientId). Android
   *     클라이언트 ID 는 넣지 않는다(토큰 aud 가 아님). 비어 있으면 로그인은 CONFIG_REQUIRED
   * @param jwkSetUri Google 공개키
   * @param issuers 허용 iss
   */
  public record Google(List<String> clientIds, String jwkSetUri, List<String> issuers) {
    public Google {
      clientIds =
          clientIds == null
              ? List.of()
              : clientIds.stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
      jwkSetUri =
          jwkSetUri == null || jwkSetUri.isBlank()
              ? "https://www.googleapis.com/oauth2/v3/certs"
              : jwkSetUri;
      issuers =
          issuers == null || issuers.isEmpty()
              ? List.of("https://accounts.google.com", "accounts.google.com")
              : List.copyOf(issuers);
    }
  }
}
