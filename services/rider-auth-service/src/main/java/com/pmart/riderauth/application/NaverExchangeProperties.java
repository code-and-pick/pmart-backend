package com.pmart.riderauth.application;

import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pmart.rider-auth.naver.*} — 네이버 인가 코드 서버 교환(ADR-0078, 2026-11-20 BR-69). 같은 접두사의 {@code
 * client-ids} 는 ID 토큰 aud 검증({@link RiderAuthProperties#naver()})에도 쓰인다. 값을 바꾸면 재시작한다.
 *
 * @param clientIds 네이버 Client ID — 첫 번째를 토큰 요청 {@code client_id} 로 쓴다
 * @param clientSecret 네이버 Client Secret. LAN 은 pmart-config {@code {cipher}}. 없으면 교환은
 *     CONFIG_REQUIRED. 로그 금지
 * @param callbackUrl 허용 {@code redirect_uri} = 라이더 앱 콜백 중계 주소. 앱이 보낸 redirectUri 와 정확히 같아야 한다
 * @param tokenBaseUrl 네이버 토큰 엔드포인트 기준 주소(기본 {@code https://nid.naver.com}, 경로 {@code
 *     /oauth2/token})
 * @param connectTimeout 연결 제한(기본 2초)
 * @param readTimeout 응답 제한(기본 5초)
 * @param profileBaseUrl 네이버 프로필 API 기준 주소(기본 {@code https://openapi.naver.com}, 경로 {@code
 *     /v1/nid/me} — 2026-11-22 RA-1 이메일)
 */
@ConfigurationProperties(prefix = "pmart.rider-auth.naver")
public record NaverExchangeProperties(
    @Nullable List<String> clientIds,
    @Nullable String clientSecret,
    @Nullable String callbackUrl,
    @Nullable String tokenBaseUrl,
    @Nullable Duration connectTimeout,
    @Nullable Duration readTimeout,
    @Nullable String profileBaseUrl) {

  public static final String DEFAULT_TOKEN_BASE_URL = "https://nid.naver.com";
  public static final String DEFAULT_PROFILE_BASE_URL = "https://openapi.naver.com";

  public NaverExchangeProperties {
    clientIds =
        clientIds == null
            ? List.of()
            : clientIds.stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
    clientSecret = blankToNull(clientSecret);
    callbackUrl = blankToNull(callbackUrl);
    tokenBaseUrl =
        tokenBaseUrl == null || tokenBaseUrl.isBlank() ? DEFAULT_TOKEN_BASE_URL : tokenBaseUrl;
    connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
    readTimeout = readTimeout == null ? Duration.ofSeconds(5) : readTimeout;
    profileBaseUrl =
        profileBaseUrl == null || profileBaseUrl.isBlank()
            ? DEFAULT_PROFILE_BASE_URL
            : profileBaseUrl.strip().replaceAll("/+$", "");
  }

  /** 토큰 요청 {@code client_id}(첫 번째). 없으면 null. */
  public @Nullable String clientId() {
    List<String> ids = clientIds;
    return ids == null || ids.isEmpty() ? null : ids.get(0);
  }

  /** secret 이 로그·오류 메시지에 찍히지 않게 한다. */
  @Override
  public String toString() {
    return "NaverExchangeProperties[clientIds="
        + clientIds
        + ", clientSecret="
        + (clientSecret == null ? "null" : "****")
        + ", callbackUrl="
        + callbackUrl
        + ", tokenBaseUrl="
        + tokenBaseUrl
        + "]";
  }

  private static @Nullable String blankToNull(@Nullable String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }
}
