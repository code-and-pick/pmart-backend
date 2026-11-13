package com.pmart.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import reactor.core.publisher.Mono;

/**
 * 게이트웨이 라우팅·보안 검증. 업스트림은 WireMock 서버 하나가 대신한다(아래 서비스들의 pmart.gateway.upstream.* 가 같은 주소). 어느 서비스로
 * 갔는지는 매칭된 라우트 ID 와 업스트림이 받은 경로로 확인한다.
 */
// MOCK: WebTestClient 를 애플리케이션 컨텍스트에 바인딩해야 mockJwt() 가 동작한다. 라우트의 업스트림 호출은 실제 HTTP(WireMock).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
@EnableWireMock(
    @ConfigureWireMock(
        name = "upstream",
        baseUrlProperties = {
          "pmart.gateway.upstream.storefront-service",
          "pmart.gateway.upstream.cart-service",
          "pmart.gateway.upstream.order-service",
          "pmart.gateway.upstream.customer-service",
          "pmart.gateway.upstream.notification-service",
          "pmart.gateway.upstream.search-service",
          "pmart.gateway.upstream.rider-service",
          "pmart.gateway.upstream.delivery-tracking-service",
          "pmart.gateway.upstream.delivery-execution-service",
          "pmart.gateway.upstream.delivery-dispatch-service",
          "pmart.gateway.upstream.backoffice-bff",
          "pmart.gateway.upstream.auth-service",
          "pmart.gateway.upstream.payment-service",
          "pmart.gateway.upstream.employee-auth-service"
        }))
class GatewayRoutingSecurityTest {

  /** 계약이 /internal 만 노출하는 서비스: 외부 라우트가 없는 것이 정상. */
  private static final List<String> INTERNAL_ONLY =
      List.of("order-orchestration-service", "fulfillment-routing-service");

  @Autowired WebTestClient client;
  @Autowired RouteLocator routeLocator;
  @Autowired GatewayProperties properties;

  @InjectWireMock("upstream")
  WireMockServer upstream;

  @BeforeEach
  void stubUpstream() {
    upstream.stubFor(
        any(anyUrl())
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"ok\":true}")));
  }

  @Test
  void everyScaffoldedServiceHasAnUpstreamAndAtLeastOneRoute() {
    List<String> routeIds = routeLocator.getRoutes().map(Route::getId).collectList().block();
    assertThat(properties.upstream())
        .hasSize(38); // + ai-assistant-service(ADR-0036), customer-assistant-service(ADR-0051),
    // rider-auth-service(ADR-0054 E), employee-auth-service·keycloak(/iam)·hr-service(ADR-0056)
    for (String service : properties.upstream().keySet()) {
      if (INTERNAL_ONLY.contains(service)) {
        continue;
      }
      assertThat(routeIds).as("route for " + service).anyMatch(id -> id.startsWith(service));
    }
  }

  @Test
  void missingTokenIsRejectedWithProblemDetails() {
    client
        .get()
        .uri("/api/v1/orders/ord-1")
        .exchange()
        .expectStatus()
        .isUnauthorized()
        .expectHeader()
        .contentType("application/problem+json")
        .expectHeader()
        .exists("X-Request-Id")
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo(401)
        .jsonPath("$.code")
        .isEqualTo("UNAUTHENTICATED")
        .jsonPath("$.requestId")
        .isNotEmpty();
    upstream.verify(0, getRequestedFor(anyUrl()));
  }

  @Test
  void internalPathsNeverPassEvenWithValidToken() {
    client
        .mutateWith(mockJwt())
        .post()
        .uri("/api/v1/internal/reservations")
        .exchange()
        .expectStatus()
        .isForbidden()
        .expectHeader()
        .contentType("application/problem+json")
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("FORBIDDEN");
    upstream.verify(0, postRequestedFor(anyUrl()));
  }

  /**
   * Keycloak 중계(/iam)는 로그인 경로만 — 관리 콘솔·Admin API·master Realm 은 유효한 토큰이 있어도 403(ADR-0056 결정 11).
   */
  @Test
  void keycloakAdminPathsNeverPassEvenWithValidToken() {
    for (String path :
        new String[] {
          "/iam/admin/master/console/",
          "/iam/admin/realms/pmart-staff/users",
          "/iam/realms/master/protocol/openid-connect/token",
          "/iam/realms/pmart-staff/account"
        }) {
      client.mutateWith(mockJwt()).get().uri(path).exchange().expectStatus().isForbidden();
    }
  }

  @Test
  void serviceIdentityTokenIsRejectedOnExternalPaths() {
    client
        .mutateWith(mockJwt().jwt(jwt -> jwt.subject("order-service").claim("typ", "SERVICE")))
        .get()
        .uri("/api/v1/orders/o-1")
        .exchange()
        .expectStatus()
        .isForbidden()
        .expectHeader()
        .contentType("application/problem+json")
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("FORBIDDEN");
    upstream.verify(0, getRequestedFor(anyUrl()));
  }

  /** 비회원 토큰(typ=GUEST)은 guest-paths(장바구니 등)에서만. 회원 전용 경로는 403(업스트림에 가지 않음). */
  @Test
  void guestTokenOnlyOnGuestPaths() {
    client
        .mutateWith(mockJwt().jwt(jwt -> jwt.subject("GST-1").claim("typ", "GUEST")))
        .get()
        .uri("/api/v1/me/addresses")
        .exchange()
        .expectStatus()
        .isForbidden();
    upstream.verify(0, getRequestedFor(anyUrl()));
    client
        .mutateWith(mockJwt().jwt(jwt -> jwt.subject("GST-1").claim("typ", "GUEST")))
        .get()
        .uri("/api/v1/carts/C-1")
        .exchange()
        .expectStatus()
        .isOk();
    upstream.verify(getRequestedFor(urlPathEqualTo("/api/v1/carts/C-1")));
  }

  @Test
  void contractPublicOperationPassesWithoutToken() {
    client.get().uri("/api/v1/storefront/home").exchange().expectStatus().isOk();
    upstream.verify(getRequestedFor(urlPathEqualTo("/api/v1/storefront/home")));
    // 같은 리소스라도 계약에 없는 메서드는 공개가 아니다
    client.post().uri("/api/v1/storefront/home").exchange().expectStatus().isUnauthorized();
  }

  @Test
  void authenticatedRequestIsForwardedWithRequestId() {
    client
        .mutateWith(mockJwt())
        .get()
        .uri("/api/v1/orders/ord-1")
        .header("X-Request-Id", "req-123")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals("X-Request-Id", "req-123");
    upstream.verify(
        getRequestedFor(urlPathEqualTo("/api/v1/orders/ord-1"))
            .withHeader("X-Request-Id", equalTo("req-123")));
  }

  @Test
  void sharedSegmentsResolveToTheMostSpecificRoute() {
    assertRoutedTo("/api/v1/me/recent-searches", "search-service-me");
    assertRoutedTo("/api/v1/me/notifications", "notification-service-me");
    assertRoutedTo("/api/v1/me/addresses", "customer-service-me");
    assertRoutedTo("/api/v1/me", "customer-service-me");
    assertRoutedTo("/api/v1/riders/me/locations/latest", "delivery-tracking-service-rider");
    assertRoutedTo("/api/v1/riders/me/deliveries", "delivery-execution-service-rider");
    assertRoutedTo("/api/v1/riders/me", "rider-service-riders");
    assertRoutedTo("/api/v1/deliveries/d-1/eta", "delivery-tracking-service-rider");
    assertRoutedTo(
        "/api/v1/deliveries/d-1/assignment-cancellations", "delivery-dispatch-service-deliveries");
    assertRoutedTo("/api/v1/deliveries/d-1/dispatch", "delivery-dispatch-service-deliveries");
    assertRoutedTo("/api/v1/deliveries/d-1/pickups", "delivery-execution-service-deliveries");
    assertRoutedTo("/api/v1/deliveries/d-1", "delivery-execution-service-deliveries");
    assertRoutedTo("/api/v1/backoffice/views/case-inbox", "backoffice-bff");
    // 결제위젯 설정(BR-34) — payment-service
    assertRoutedTo("/api/v1/payment-widget-config", "payment-service");
  }

  @Test
  void unknownPathIsNotFoundProblem() {
    client
        .mutateWith(mockJwt())
        .get()
        .uri("/api/v1/no-such-resource")
        .exchange()
        .expectStatus()
        .isNotFound()
        .expectHeader()
        .contentType("application/problem+json");
  }

  private void assertRoutedTo(String path, String expectedRouteId) {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(MockServerHttpRequest.get(path).build());
    Route route =
        routeLocator
            .getRoutes()
            .filterWhen(r -> Mono.from(r.getPredicate().apply(exchange)))
            .next()
            .block();
    assertThat(route).as("route for " + path).isNotNull();
    assertThat(route.getId()).as(path).isEqualTo(expectedRouteId);
    client.mutateWith(mockJwt()).get().uri(path).exchange().expectStatus().isOk();
    upstream.verify(getRequestedFor(urlPathEqualTo(path)));
  }

  @Test
  void operatorLoginIsGone() {
    // auth-service 운영자 로그인은 2026-11-13 없앴다(ADR-0056 7단계) — 더 이상 공개 경로가 아니다(직원은
    // employee-auth-service)
    client
        .post()
        .uri("/api/v1/auth/operator-logins")
        .header("Content-Type", "application/json")
        .bodyValue("{}")
        .exchange()
        .expectStatus()
        .isUnauthorized();
    client.get().uri("/api/v1/operator-accounts").exchange().expectStatus().isUnauthorized();
  }

  @Test
  void corsPreflightAllowsOnlyConfiguredWebOrigin() {
    // 실제 요청처럼 호스트를 붙인다(호스트 없는 모의 요청은 Origin 비교가 "malformed" 로 거절된다)
    client
        .options()
        .uri("http://gateway.test/api/v1/products")
        .header("Origin", "http://localhost:5173")
        .header("Access-Control-Request-Method", "POST")
        .header("Access-Control-Request-Headers", "authorization,idempotency-key,x-source-system")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals("Access-Control-Allow-Origin", "http://localhost:5173");
    client
        .options()
        .uri("http://gateway.test/api/v1/products")
        .header("Origin", "http://evil.example")
        .header("Access-Control-Request-Method", "POST")
        .exchange()
        .expectStatus()
        .isForbidden();
    // 실제 요청에도 허용 출처 헤더가 붙는다
    client
        .mutateWith(mockJwt())
        .get()
        .uri("http://gateway.test/api/v1/orders/ord-1")
        .header("Origin", "http://localhost:5173")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals("Access-Control-Allow-Origin", "http://localhost:5173");
  }

  /**
   * 웹 백오피스 refresh 쿠키 갱신(ADR-0059): 토큰 없이 통과(공개 작업), employee-auth-service 쿠키만 업스트림으로, 응답
   * Set-Cookie 는 그대로 브라우저로.
   */
  @Test
  void refreshCookieRouteForwardsOnlyEmployeeAuthCookiesAndSetCookie() {
    String path = "/api/v1/employee-auth/tokens/refresh/ESES-1";
    upstream.stubFor(
        post(urlPathEqualTo(path))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withHeader(
                        "Set-Cookie",
                        "__Secure-pmart_ea_rt=new; Path="
                            + path
                            + "; Secure; HttpOnly; SameSite=Strict")
                    .withBody("{}")));
    client
        .post()
        .uri(path)
        .header(
            "Cookie",
            "AUTH_SESSION_ID=kc; __Secure-pmart_ea_rt=old; __Secure-pmart_ea_csrf_ESES-1=c.s; other=1")
        .header("X-CSRF-Token", "c.s")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueMatches("Set-Cookie", "__Secure-pmart_ea_rt=new; Path=" + path + ".*");
    upstream.verify(
        postRequestedFor(urlPathEqualTo(path))
            .withHeader(
                "Cookie", equalTo("__Secure-pmart_ea_rt=old; __Secure-pmart_ea_csrf_ESES-1=c.s")));
    assertThat(routeId(path)).isEqualTo("employee-auth-refresh-cookie");

    // 다른 employee-auth 경로(티켓 교환)는 쿠키를 넘기지 않는다
    client
        .post()
        .uri("/api/v1/employee-auth/tokens")
        .header("Cookie", "__Secure-pmart_ea_rt=old; other=1")
        .exchange()
        .expectStatus()
        .isOk();
    upstream.verify(
        postRequestedFor(urlPathEqualTo("/api/v1/employee-auth/tokens"))
            .withHeader("Cookie", absent()));
  }

  /** 자격 증명 CORS 는 정한 경로·정한 출처에만(ADR-0059). 그 밖은 지금처럼 자격 증명 없음. */
  @Test
  void credentialedCorsOnlyOnCookiePathsAndOrigins() {
    for (String path :
        List.of(
            "/api/v1/employee-auth/tokens/refresh/ESES-1",
            "/api/v1/employee-auth/tokens",
            "/api/v1/employee-auth/logouts")) {
      client
          .options()
          .uri("http://gateway.test" + path)
          .header("Origin", "http://localhost:5174")
          .header("Access-Control-Request-Method", "POST")
          .header(
              "Access-Control-Request-Headers",
              "content-type,idempotency-key,x-source-system,x-device-id,x-csrf-token,authorization")
          .exchange()
          .expectStatus()
          .isOk()
          .expectHeader()
          .valueEquals("Access-Control-Allow-Origin", "http://localhost:5174")
          .expectHeader()
          .valueEquals("Access-Control-Allow-Credentials", "true")
          .expectHeader()
          .value(
              "Access-Control-Allow-Headers",
              h -> assertThat(h).containsIgnoringCase("x-csrf-token"))
          .expectHeader()
          .value("Vary", v -> assertThat(v).contains("Origin"));
      // 쿠키 경로라도 쿠키 출처가 아니면 거절
      client
          .options()
          .uri("http://gateway.test" + path)
          .header("Origin", "http://localhost:5173")
          .header("Access-Control-Request-Method", "POST")
          .exchange()
          .expectStatus()
          .isForbidden();
    }
    // 그 밖의 경로는 쿠키 출처라도 자격 증명 없음(일반 출처 목록에 없으면 거절)
    client
        .options()
        .uri("http://gateway.test/api/v1/products")
        .header("Origin", "http://localhost:5174")
        .header("Access-Control-Request-Method", "POST")
        .exchange()
        .expectStatus()
        .isForbidden();
    client
        .options()
        .uri("http://gateway.test/api/v1/products")
        .header("Origin", "http://localhost:5173")
        .header("Access-Control-Request-Method", "POST")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .doesNotExist("Access-Control-Allow-Credentials");
  }

  private String routeId(String path) {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(MockServerHttpRequest.post(path).build());
    Route route =
        routeLocator
            .getRoutes()
            .filterWhen(r -> Mono.from(r.getPredicate().apply(exchange)))
            .next()
            .block();
    return route == null ? null : route.getId();
  }
}
