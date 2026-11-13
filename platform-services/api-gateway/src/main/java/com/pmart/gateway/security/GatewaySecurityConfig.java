package com.pmart.gateway.security;

import com.pmart.common.jwt.IssuerPolicy;
import com.pmart.gateway.GatewayProperties;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.authorization.ReactiveAuthorizationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.AuthorizationContext;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/**
 * 게이트웨이 보안 체인 (security-config.md).
 *
 * <ul>
 *   <li>JWT 검증: 발급자 표({@code pmart.security.issuers}, ADR-0054)가 있으면 {@code iss} 로 발급자를 골라 그
 *       키·규칙(종류 짝·aud)으로, 없으면 auth-service JWKS 하나로. 토큰 없음·무효 → 401 Problem, 권한 없음 → 403 Problem.
 *   <li>{@code /api/{v}/internal/**} 는 서비스 간 전용 — 외부에서 절대 통과하지 않는다(denyAll).
 *   <li>서비스 신원 토큰({@code typ=SERVICE})은 외부 경로에서 403 — 내부 호출 전용(ADR-0011).
 *   <li>비회원 토큰({@code typ=GUEST})은 {@code pmart.gateway.guest-paths} 에서만, 그 밖은 403.
 *   <li>공개 작업은 {@code pmart.gateway.public-paths} 에만(계약의 {@code security: []}).
 *   <li>Actuator 는 health/info/prometheus 만. 나머지 actuator 는 인증 필요.
 *   <li>웹(브라우저) CORS 는 {@code pmart.gateway.cors.allowed-origins} 출처에만. 사전 요청(OPTIONS)은 토큰 없이 통과한다.
 *   <li>세부 인가(역할·센터·행위 시점 검증)는 각 서비스가 한다. 게이트웨이는 "토큰이 유효한가"까지만.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
public class GatewaySecurityConfig {

  /** 인증됐고 서비스 신원·비회원 토큰이 아닐 때만 허용. 내부 호출(토큰 없음)으로 외부 경로를 부르면 403, 비회원은 guest-paths 에서만. */
  static final ReactiveAuthorizationManager<AuthorizationContext> USER_TOKEN_ONLY =
      (authentication, context) ->
          authentication
              .filter(Authentication::isAuthenticated)
              .map(
                  a ->
                      (AuthorizationResult)
                          new AuthorizationDecision(!isServiceToken(a) && !isGuestToken(a)))
              .defaultIfEmpty(new AuthorizationDecision(false));

  /** 비회원 경로: 회원·비회원 토큰 모두(내부 호출(토큰 없음)은 아님). */
  static final ReactiveAuthorizationManager<AuthorizationContext> USER_OR_GUEST_TOKEN =
      (authentication, context) ->
          authentication
              .filter(Authentication::isAuthenticated)
              .map(a -> (AuthorizationResult) new AuthorizationDecision(!isServiceToken(a)))
              .defaultIfEmpty(new AuthorizationDecision(false));

  static boolean isGuestToken(Authentication authentication) {
    return "GUEST".equals(IssuedToken.userTypeOf(authentication));
  }

  static boolean isServiceToken(Authentication authentication) {
    return "SERVICE".equals(IssuedToken.userTypeOf(authentication));
  }

  @Bean
  SecurityWebFilterChain gatewaySecurityFilterChain(
      ServerHttpSecurity http,
      GatewayProperties properties,
      GatewayIssuerProperties issuers,
      ObjectProvider<ReactiveJwtDecoder> jwtDecoder,
      ProblemResponseWriter problems) {
    ServerAuthenticationEntryPoint entryPoint =
        (exchange, ex) -> {
          exchange.getResponse().getHeaders().set("WWW-Authenticate", "Bearer");
          return problems.write(
              exchange, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "유효한 인증 토큰이 필요합니다.");
        };
    ServerAccessDeniedHandler deniedHandler =
        (exchange, ex) ->
            problems.write(exchange, HttpStatus.FORBIDDEN, "FORBIDDEN", "접근이 허용되지 않은 경로입니다.");

    http.cors(cors -> cors.configurationSource(corsSource(properties.cors())))
        .csrf(ServerHttpSecurity.CsrfSpec::disable)
        .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
        .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
        .logout(ServerHttpSecurity.LogoutSpec::disable)
        .requestCache(ServerHttpSecurity.RequestCacheSpec::disable)
        .authorizeExchange(
            exchanges -> {
              exchanges
                  .pathMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus")
                  .permitAll();
              exchanges.pathMatchers("/internal/**", "/api/*/internal/**").denyAll();
              for (GatewayProperties.PublicPath p : properties.publicPaths()) {
                exchanges.pathMatchers(HttpMethod.valueOf(p.method()), p.path()).permitAll();
              }
              // Keycloak 중계(/iam, ADR-0056 결정 11)는 공개 목록의 로그인 경로만. 관리 콘솔·Admin API·master Realm 등 그
              // 밖은
              // 토큰이 있어도 거부 — 라우트가 없다는 것에만 기대지 않는다
              exchanges.pathMatchers("/iam/**").denyAll();
              // 비회원 토큰(typ=GUEST)은 비회원을 허용한 경로(장바구니 등)에서만
              for (String p : properties.guestPaths()) {
                exchanges.pathMatchers(p).access(USER_OR_GUEST_TOKEN);
              }
              // 서비스 신원 토큰(typ=SERVICE)은 /internal 전용(ADR-0011) — 외부 경로에서는 유효해도 거부(방어 심화)
              exchanges.anyExchange().access(USER_TOKEN_ONLY);
            })
        .oauth2ResourceServer(
            oauth2 -> {
              if (issuers.getIssuers().isEmpty()) {
                // 발급자 표 없음: jwk-set-uri 디코더 하나 + typ 클레임(지금까지의 방식)
                oauth2.jwt(Customizer.withDefaults());
              } else {
                // 발급자 표(ADR-0054): iss 로 발급자 선택, 표에 없으면 401, 종류는 발급자 규칙
                oauth2.authenticationManagerResolver(
                    IssuedToken.resolver(
                        IssuerPolicy.table(issuers.getIssuers()), jwtDecoder.getIfAvailable()));
              }
              oauth2.authenticationEntryPoint(entryPoint).accessDeniedHandler(deniedHandler);
            })
        .exceptionHandling(
            handling ->
                handling.authenticationEntryPoint(entryPoint).accessDeniedHandler(deniedHandler));
    return http.build();
  }

  /** 사전 요청에서 허용하는 헤더. */
  static final List<String> ALLOWED_HEADERS =
      List.of(
          "Authorization",
          "Content-Type",
          "Idempotency-Key",
          "X-Source-System",
          "X-Expected-Version",
          "X-Device-Id",
          "X-Request-Id",
          "traceparent");

  /**
   * 허용 출처가 없으면 어떤 경로에도 CORS 설정을 걸지 않는다(브라우저 교차 출처 호출은 거부). 자격 증명 경로(ADR-0059 — 웹 백오피스 refresh 쿠키)는
   * 먼저 등록해 그 경로에서는 그 설정만 쓴다(UrlBasedCorsConfigurationSource 는 등록 순서대로 처음 맞는 것).
   */
  static CorsConfigurationSource corsSource(GatewayProperties.Cors cors) {
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    if (!cors.credentialedOrigins().isEmpty()) {
      CorsConfiguration k = new CorsConfiguration();
      k.setAllowedOrigins(cors.credentialedOrigins());
      k.setAllowedMethods(List.of("POST"));
      List<String> headers = new java.util.ArrayList<>(ALLOWED_HEADERS);
      headers.add("X-CSRF-Token");
      k.setAllowedHeaders(headers);
      k.setExposedHeaders(List.of("X-Request-Id", "Retry-After"));
      k.setAllowCredentials(true);
      k.setMaxAge(Duration.ofMinutes(30));
      for (String path : cors.credentialedPaths()) {
        source.registerCorsConfiguration(path, k);
      }
    }
    if (cors.allowedOrigins().isEmpty()) {
      return source;
    }
    CorsConfiguration c = new CorsConfiguration();
    c.setAllowedOrigins(cors.allowedOrigins());
    c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
    c.setAllowedHeaders(ALLOWED_HEADERS);
    c.setExposedHeaders(List.of("X-Request-Id", "Location", "Retry-After"));
    c.setAllowCredentials(false);
    c.setMaxAge(Duration.ofMinutes(30));
    source.registerCorsConfiguration("/api/**", c);
    return source;
  }
}
