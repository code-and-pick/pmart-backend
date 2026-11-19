package com.pmart.riderauth.infrastructure;

import java.util.Map;
import java.util.Optional;

/**
 * 제공자별 ID 토큰 검증기(2026-11-19 — 라이더 앱 요청 R1, 고객 ADR-0071 과 같은 흐름). GOOGLE·KAKAO·NAVER, 앱 키(aud)가 없는
 * 제공자는 빠진다 — 로그인 서비스가 CONFIG_REQUIRED 로. 시험은 같은 타입 빈을 넣어 바꾼다.
 */
public class IdTokenVerifiers {

  private final Map<String, GoogleIdTokenVerifier> byProvider;

  public IdTokenVerifiers(Map<String, GoogleIdTokenVerifier> byProvider) {
    this.byProvider = Map.copyOf(byProvider);
  }

  public Optional<GoogleIdTokenVerifier> of(String provider) {
    return Optional.ofNullable(byProvider.get(provider));
  }
}
