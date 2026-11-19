package com.pmart.riderauth.infrastructure;

import com.pmart.riderauth.application.RiderAuthProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Google ID 토큰 검증기. 시험은 같은 타입 빈을 넣어 바꾼다. */
@Configuration(proxyBeanMethods = false)
class GoogleVerifierConfig {

  @Bean
  @ConditionalOnMissingBean
  GoogleIdTokenVerifier googleIdTokenVerifier(RiderAuthProperties props) {
    return GoogleIdTokenVerifier.forGoogle(props.google());
  }

  /** 제공자별(2026-11-19). 구글은 위 빈(시험이 바꿀 수 있게), 카카오·네이버는 앱 키가 있을 때만. */
  @Bean
  @ConditionalOnMissingBean
  IdTokenVerifiers idTokenVerifiers(RiderAuthProperties props, GoogleIdTokenVerifier google) {
    java.util.Map<String, GoogleIdTokenVerifier> m = new java.util.HashMap<>();
    m.put("GOOGLE", google);
    if (props.kakao() != null && !props.kakao().clientIds().isEmpty()) {
      m.put("KAKAO", GoogleIdTokenVerifier.forOidc(props.kakao()));
    }
    if (props.naver() != null && !props.naver().clientIds().isEmpty()) {
      m.put("NAVER", GoogleIdTokenVerifier.forOidc(props.naver()));
    }
    return new IdTokenVerifiers(m);
  }
}
