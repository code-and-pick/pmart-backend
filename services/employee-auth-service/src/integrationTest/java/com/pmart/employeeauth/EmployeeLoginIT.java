package com.pmart.employeeauth;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.employeeauth.application.EmployeeTokens;
import com.pmart.employeeauth.infrastructure.EmployeeAuthStore;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 직원 로그인 E2E — 실제 Keycloak 26.8.1(운영과 같은 버전) 컨테이너와 PostgreSQL.
 *
 * <p>브라우저 흉내(쿠키 보관·리디렉션 직접 따라가기)로 로그인 시작 → Keycloak 로그인 화면 → 비밀번호 제출 → 콜백(코드 교환·ID 토큰 검증) → 앱 복귀
 * 주소의 티켓 → 티켓 교환 → 직원 토큰(JWKS 로 검증, 예전 운영자 토큰 모양) → 갱신 회전·재사용 감지·로그아웃까지. Keycloak 세션이 로그인 직후
 * 끝났는지(ADR-0056 결정 14 ①), 티켓을 다른 기기·다른 verifier 로 못 쓰는지, 등록 안 된·정지된 직원, 관리 경로, LAN 시험 교환을 본다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "pmart.employee-auth.issuer=http://employee-auth.test",
      "pmart.employee-auth.keycloak.client-secret=it-employee-auth-secret",
      "pmart.employee-auth.dev-token-exchange.enabled=true",
      "pmart.messaging.outbox.delete-after-insert=false"
    })
class EmployeeLoginIT extends PostgresKafkaContainerSupport {

  static final String REALM = "pmart-staff";

  @SuppressWarnings("resource")
  static final GenericContainer<?> KEYCLOAK =
      new GenericContainer<>("quay.io/keycloak/keycloak:26.8.1")
          .withCommand("start-dev", "--import-realm")
          .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
          .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
          .withCopyFileToContainer(
              MountableFile.forClasspathResource("keycloak/pmart-staff-it-realm.json"),
              "/opt/keycloak/data/import/pmart-staff-realm.json") // 파일 이름 = Realm 이름(26.8)
          .withExposedPorts(8080)
          .waitingFor(
              Wait.forHttp("/realms/" + REALM)
                  .forPort(8080)
                  .forStatusCode(200)
                  .withStartupTimeout(Duration.ofMinutes(4)));

  /** Keycloak 에 보낼 redirect_uri 가 기동 때 정해져야 해서 포트를 미리 고른다. */
  static final int PORT = freePort();

  static {
    KEYCLOAK.start();
  }

  static int freePort() {
    try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
      return s.getLocalPort();
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  static String kcBase() {
    return "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080);
  }

  final int port = PORT;

  @DynamicPropertySource
  static void keycloak(DynamicPropertyRegistry r) {
    String issuer = kcBase() + "/realms/" + REALM;
    r.add("pmart.employee-auth.keycloak.issuer", () -> issuer);
    // 시험은 브라우저·서버가 같은 주소로 Keycloak 을 본다(운영은 token·jwks·logout 이 내부 주소)
    r.add("server.port", () -> PORT);
    r.add(
        "pmart.employee-auth.keycloak.redirect-uri",
        () -> "http://localhost:" + PORT + "/api/v1/employee-auth/logins/callback");
  }

  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired EmployeeAuthStore store;

  RestClient http;

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    account("OP-IT-WORKER1", "ACTIVE", List.of("MFC_WORKER"), List.of("MFC-IT-01"));
    account("OP-IT-ADMIN1", "ACTIVE", List.of("OPERATOR_ADMIN"), List.of());
    account("OP-IT-LEFT1", "LEFT", List.of("MFC_WORKER"), List.of("MFC-IT-01"));
    account("OP-IT-SSO1", "ACTIVE", List.of("MD"), List.of());
  }

  void account(String op, String state, List<String> roles, List<String> centers) {
    jdbc.update("DELETE FROM employee_account_change WHERE operator_id = ?", op);
    if (store.account(op, false).isEmpty()) {
      store.insertAccount(op, state, roles, centers, Instant.now());
    } else {
      store.updateAccount(op, state, roles, centers, Instant.now());
    }
  }

  // ------------------------------------------------------------------ 브라우저 흉내

  record Res(int status, JsonNode body) {}

  /**
   * 쿠키를 들고 리디렉션을 하나씩 따라가는 브라우저. Keycloak 쿠키에는 Secure 가 붙는데 java.net.CookieManager 는 http 로 보내지
   * 않는다(브라우저는 localhost 를 안전한 출처로 본다) — 그래서 이름=값만 담아 두고 같은 호스트에 다시 보낸다(시험 전용).
   */
  static final class Browser {
    final HttpClient client =
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    final Map<String, String> cookies = new java.util.LinkedHashMap<>();

    HttpResponse<String> get(String url) throws Exception {
      return send(HttpRequest.newBuilder(URI.create(url)).GET());
    }

    HttpResponse<String> postForm(String url, Map<String, String> form) throws Exception {
      StringBuilder b = new StringBuilder();
      form.forEach(
          (k, v) ->
              b.append(b.isEmpty() ? "" : "&")
                  .append(URLEncoder.encode(k, StandardCharsets.UTF_8))
                  .append('=')
                  .append(URLEncoder.encode(v, StandardCharsets.UTF_8)));
      return send(
          HttpRequest.newBuilder(URI.create(url))
              .header("Content-Type", "application/x-www-form-urlencoded")
              .POST(HttpRequest.BodyPublishers.ofString(b.toString())));
    }

    HttpResponse<String> send(HttpRequest.Builder req) throws Exception {
      if (!cookies.isEmpty()) {
        StringBuilder c = new StringBuilder();
        cookies.forEach(
            (k, v) -> c.append(c.isEmpty() ? "" : "; ").append(k).append('=').append(v));
        req.header("Cookie", c.toString());
      }
      HttpResponse<String> r = client.send(req.build(), HttpResponse.BodyHandlers.ofString());
      for (String h : r.headers().allValues("Set-Cookie")) {
        String pair = h.split(";", 2)[0];
        int eq = pair.indexOf('=');
        if (eq > 0) {
          cookies.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
        }
      }
      return r;
    }
  }

  static final Pattern FORM_ACTION =
      Pattern.compile("<form[^>]*id=\"kc-form-login\"[^>]*action=\"([^\"]+)\"");

  static String verifier() {
    return EmployeeTokens.random() + "-v";
  }

  String startUrl(String app, String device, String verifier) {
    return "http://localhost:"
        + port
        + "/api/v1/employee-auth/logins?app="
        + app
        + "&deviceId="
        + device
        + "&codeChallenge="
        + EmployeeTokens.s256(verifier);
  }

  /** 로그인 시작부터 앱 복귀 주소까지. 돌아온 주소(pmart-mfc://login?ticket=… 또는 ?error=…). */
  URI browserLogin(String user, String app, String device, String verifier) throws Exception {
    Browser b = new Browser();
    HttpResponse<String> start = b.get(startUrl(app, device, verifier));
    assertThat(start.statusCode()).isEqualTo(302);
    String kcUrl = start.headers().firstValue("Location").orElseThrow();
    assertThat(kcUrl)
        .startsWith(kcBase())
        .contains("prompt=login")
        .contains("code_challenge_method=S256");
    HttpResponse<String> page = b.get(kcUrl);
    assertThat(page.statusCode()).as("Keycloak 로그인 화면").isEqualTo(200);
    Matcher m = FORM_ACTION.matcher(page.body());
    assertThat(m.find()).as("로그인 form").isTrue();
    String action = m.group(1).replace("&amp;", "&");
    HttpResponse<String> submitted =
        b.postForm(action, Map.of("username", user, "password", "pw-" + user, "credentialId", ""));
    assertThat(submitted.statusCode()).as("비밀번호 제출 → 콜백으로").isEqualTo(302);
    String callback = submitted.headers().firstValue("Location").orElseThrow();
    assertThat(callback)
        .startsWith("http://localhost:" + port + "/api/v1/employee-auth/logins/callback");
    HttpResponse<String> back = b.get(callback);
    assertThat(back.statusCode()).isEqualTo(302);
    assertThat(back.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
    return URI.create(back.headers().firstValue("Location").orElseThrow());
  }

  static String param(URI uri, String name) {
    return UriComponentsBuilder.fromUri(uri).build().getQueryParams().getFirst(name);
  }

  Res exchange(String ticket, String verifier, String device) {
    return http.post()
        .uri("/employee-auth/tokens")
        .headers(
            h -> {
              h.set("Idempotency-Key", UUID.randomUUID().toString());
              h.set("X-Source-System", "mfc-app");
              h.set("X-Device-Id", device);
            })
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("ticket", ticket, "codeVerifier", verifier))
        .exchange((q, r) -> new Res(r.getStatusCode().value(), read(r.bodyTo(byte[].class))));
  }

  Res refresh(String refreshToken, String device) {
    return http.post()
        .uri("/employee-auth/tokens/refresh")
        .headers(
            h -> {
              h.set("Idempotency-Key", UUID.randomUUID().toString());
              h.set("X-Source-System", "mfc-app");
              h.set("X-Device-Id", device);
            })
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("refreshToken", refreshToken))
        .exchange((q, r) -> new Res(r.getStatusCode().value(), read(r.bodyTo(byte[].class))));
  }

  Res withToken(String method, String path, String token, Object body) {
    var spec =
        http.method(org.springframework.http.HttpMethod.valueOf(method))
            .uri(path)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", UUID.randomUUID().toString());
                  h.set("X-Source-System", "backoffice");
                });
    if (body != null) {
      spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
    }
    return spec.exchange(
        (q, r) -> new Res(r.getStatusCode().value(), read(r.bodyTo(byte[].class))));
  }

  JsonNode read(byte[] b) {
    return b == null || b.length == 0 ? mapper.createObjectNode() : mapper.readTree(b);
  }

  /** 로그인 → 티켓 교환 → 응답 본문. */
  JsonNode loginTokens(String user, String device) throws Exception {
    String v = verifier();
    URI back = browserLogin(user, "mfc", device, v);
    Res r = exchange(param(back, "ticket"), v, device);
    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    return r.body();
  }

  Jwt decode(String accessToken) throws Exception {
    String jwks = http.get().uri("/internal/jwks").retrieve().body(String.class);
    RSAKey key = (RSAKey) JWKSet.parse(jwks).getKeys().getFirst();
    return NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build().decode(accessToken);
  }

  // ------------------------------------------------------------------ 시험

  @Test
  void workerLogsInThroughKeycloakAndGetsOperatorToken() throws Exception {
    String v = verifier();
    URI back = browserLogin("worker1", "mfc", "pda-1", v);
    assertThat(back.getScheme()).isEqualTo("pmart-mfc");
    assertThat(back.toString()).startsWith("pmart-mfc://login?ticket=");
    String ticket = param(back, "ticket");

    // Keycloak 세션은 로그인 확인 직후 끝났다(공용 PDA — 결정 14 ①)
    assertThat(keycloakSessions("worker1")).isZero();

    Res r = exchange(ticket, v, "pda-1");
    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    assertThat(r.body().path("operatorId").asText()).isEqualTo("OP-IT-WORKER1");
    String access = r.body().path("tokens").path("accessToken").asText();
    Jwt jwt = decode(access);
    assertThat(jwt.getIssuer().toString()).isEqualTo("http://employee-auth.test");
    assertThat(jwt.getSubject()).isEqualTo("OP-IT-WORKER1");
    assertThat(jwt.getClaimAsString("typ")).isEqualTo("OPERATOR");
    assertThat(jwt.getAudience()).containsExactly("pmart-api");
    assertThat(jwt.getClaimAsStringList("roles")).containsExactly("MFC_WORKER");
    assertThat(jwt.getClaimAsStringList("centers")).containsExactly("MFC-IT-01");
    assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()))
        .isEqualTo(Duration.ofMinutes(15));
    // mfc 앱 세션은 무활동 30분
    Instant refreshExp =
        Instant.parse(r.body().path("tokens").path("refreshTokenExpiresAt").asText());
    assertThat(Duration.between(Instant.now(), refreshExp))
        .isBetween(Duration.ofMinutes(29), Duration.ofMinutes(31));

    // 티켓은 한 번만
    assertThat(exchange(ticket, v, "pda-1").status()).isEqualTo(401);

    // 현재 세션
    Res cur = withToken("GET", "/employee-auth/sessions/current", access, null);
    assertThat(cur.status()).isEqualTo(200);
    assertThat(cur.body().path("app").asText()).isEqualTo("mfc");

    // 갱신 회전 + 재사용 감지
    String refresh1 = r.body().path("tokens").path("refreshToken").asText();
    assertThat(refresh(refresh1, "pda-other").status()).as("다른 기기").isEqualTo(401);
    Res r2 = refresh(refresh1, "pda-1");
    assertThat(r2.status()).isEqualTo(200);
    Res reused = refresh(refresh1, "pda-1");
    assertThat(reused.status()).isEqualTo(401);
    assertThat(reused.body().path("code").asText()).isEqualTo("TOKEN_REUSED");
    assertThat(refresh(r2.body().path("refreshToken").asText(), "pda-1").status())
        .as("재사용 감지로 세션이 끝났다")
        .isEqualTo(401);
    // 그 세션의 access token 도 게이트웨이에서 바로 막히게 — 세션 단위 SubjectBlocked(2026-11-13)
    assertThat(sessionBlock(r.body().path("tokens").path("sessionId").asText()))
        .contains("\"reason\": \"TOKEN_REUSED\"");

    // 다시 로그인 → 로그아웃 → 현재 세션 401
    JsonNode again = loginTokens("worker1", "pda-1");
    String access2 = again.path("tokens").path("accessToken").asText();
    Res loggedOut = withToken("POST", "/employee-auth/logouts", access2, Map.of());
    assertThat(loggedOut.status()).isEqualTo(200);
    assertThat(
            loggedOut.body().path("idpLogoutUri").isMissingNode()
                || loggedOut.body().path("idpLogoutUri").isNull())
        .as("PDA 는 Keycloak 세션이 이미 없다")
        .isTrue();
    assertThat(withToken("GET", "/employee-auth/sessions/current", access2, null).status())
        .isEqualTo(401);
    assertThat(refresh(again.path("tokens").path("refreshToken").asText(), "pda-1").status())
        .isEqualTo(401);
    // 로그아웃한 세션의 access token(15분)도 바로 막는다 — 세션 단위 SubjectBlocked
    assertThat(sessionBlock(again.path("tokens").path("sessionId").asText()))
        .contains("\"reason\": \"LOGOUT\"")
        .contains("\"userType\": \"OPERATOR\"");
  }

  /** 그 세션의 SubjectBlocked 사건(outbox payload), 없으면 빈 문자열. */
  String sessionBlock(String sessionId) {
    return String.join(
        " | ",
        jdbc.queryForList(
            "SELECT payload::text FROM outbox_event WHERE topic = 'platform.subject-block.v1'"
                + " AND payload -> 'payload' ->> 'sessionId' = ?",
            String.class,
            sessionId));
  }

  @Test
  void ticketOnlyForStartingDeviceAndVerifier() throws Exception {
    String v = verifier();
    String ticket = param(browserLogin("worker1", "mfc", "pda-2", v), "ticket");
    assertThat(exchange(ticket, v, "pda-other").status()).as("다른 기기").isEqualTo(401);
    // 틀린 시도로 티켓이 탔다 — 맞는 기기·verifier 라도 다시 못 쓴다
    assertThat(exchange(ticket, v, "pda-2").status()).isEqualTo(401);

    String v2 = verifier();
    String ticket2 = param(browserLogin("worker1", "mfc", "pda-2", v2), "ticket");
    Res wrong = exchange(ticket2, verifier(), "pda-2");
    assertThat(wrong.status()).as("가로챈 딥링크 — verifier 없음").isEqualTo(401);
    assertThat(wrong.body().path("code").asText()).isEqualTo("TICKET_INVALID");
  }

  @Test
  void notRegisteredOrLeftEmployeesGetErrorRedirect() throws Exception {
    assertThat(param(browserLogin("ghost1", "mfc", "pda-3", verifier()), "error"))
        .as("Keycloak 에는 있지만 PMart 직원이 아님")
        .isEqualTo("EMPLOYEE_NOT_REGISTERED");
    assertThat(param(browserLogin("noattr1", "mfc", "pda-3", verifier()), "error"))
        .isEqualTo("EMPLOYEE_NOT_REGISTERED");
    URI left = browserLogin("left1", "mfc", "pda-3", verifier());
    assertThat(param(left, "error")).isEqualTo("ACCOUNT_NOT_AVAILABLE");
    assertThat(param(left, "ticket")).isNull();
    assertThat(keycloakSessions("left1")).as("거절돼도 Keycloak 세션은 끝냈다").isZero();
  }

  @Test
  void startAndCallbackRejectUnknownInput() throws Exception {
    Browser b = new Browser();
    String v = verifier();
    assertThat(b.get(startUrl("unknown", "d", v)).statusCode()).isEqualTo(400);
    assertThat(
            b.get(startUrl("backoffice", "d", v) + "&returnUri=https%3A%2F%2Fevil.example%2F")
                .statusCode())
        .as("허용 목록 밖 복귀 주소")
        .isEqualTo(400);
    HttpResponse<String> bo =
        b.get(
            startUrl("backoffice", "d", v)
                + "&returnUri=http%3A%2F%2Fbackoffice.test%2Fauth%2Fcallback");
    assertThat(bo.statusCode()).isEqualTo(302);
    assertThat(
            b.get(
                    startUrl("mfc", "d", v)
                        .replaceAll("codeChallenge=[^&]*", "codeChallenge=" + "*".repeat(43)))
                .statusCode())
        .as("challenge 형식")
        .isEqualTo(400);

    String base = "http://localhost:" + port + "/api/v1/employee-auth/logins/callback";
    assertThat(b.get(base + "?state=nope&code=x").statusCode()).isEqualTo(400);

    // 사용자가 Keycloak 에서 취소 → 앱으로 IDP_ERROR. 같은 state 는 두 번 못 쓴다
    String kcUrl = bo.headers().firstValue("Location").orElseThrow();
    String state = param(URI.create(kcUrl), "state");
    HttpResponse<String> cancelled = b.get(base + "?state=" + state + "&error=access_denied");
    assertThat(cancelled.statusCode()).isEqualTo(302);
    assertThat(cancelled.headers().firstValue("Location").orElseThrow())
        .isEqualTo("http://backoffice.test/auth/callback?error=IDP_ERROR");
    assertThat(b.get(base + "?state=" + state + "&code=x").statusCode()).isEqualTo(400);

    // 화면 없는 확인(silent)은 SSO 앱만 — 공용 PDA(mfc)는 400
    assertThat(b.get(startUrl("mfc", "d", v) + "&silent=true").statusCode()).isEqualTo(400);
  }

  @Test
  void adminManagesEmployeesButNotSelf() throws Exception {
    String admin = loginTokens("admin1", "bo-1").path("tokens").path("accessToken").asText();
    String worker = loginTokens("worker1", "pda-4").path("tokens").path("accessToken").asText();
    String newOp = "OP-IT-NEW-" + UUID.randomUUID().toString().substring(0, 8);
    Map<String, Object> change =
        Map.of(
            "state",
            "ACTIVE",
            "roles",
            List.of("MFC_WORKER"),
            "centers",
            List.of("MFC-IT-02"),
            "reason",
            "이전");

    assertThat(withToken("PUT", "/employee-auth/employees/" + newOp, worker, change).status())
        .as("관리자 아님")
        .isEqualTo(403);
    assertThat(
            withToken(
                    "PUT",
                    "/employee-auth/employees/" + newOp,
                    admin,
                    Map.of(
                        "state",
                        "ACTIVE",
                        "roles",
                        List.of("SUPER_USER"),
                        "centers",
                        List.of(),
                        "reason",
                        "x"))
                .status())
        .as("모르는 역할")
        .isEqualTo(400);
    Res created = withToken("PUT", "/employee-auth/employees/" + newOp, admin, change);
    assertThat(created.status()).as(created.body().toString()).isEqualTo(200);
    assertThat(created.body().path("version").asLong()).isEqualTo(1);
    Res same = withToken("PUT", "/employee-auth/employees/" + newOp, admin, change);
    assertThat(same.body().path("version").asLong()).as("같은 값은 멱등").isEqualTo(1);
    assertThat(withToken("PUT", "/employee-auth/employees/OP-IT-ADMIN1", admin, change).status())
        .as("자기 계정")
        .isEqualTo(403);
    assertThat(
            withToken("GET", "/employee-auth/employees/" + newOp, admin, null)
                .body()
                .path("keycloakLinked")
                .asBoolean())
        .isFalse();

    // 작업자를 정지 → 그 작업자의 갱신이 막힌다
    JsonNode w = loginTokens("worker1", "pda-5");
    Res disabled =
        withToken(
            "PUT",
            "/employee-auth/employees/OP-IT-WORKER1",
            admin,
            Map.of(
                "state",
                "DISABLED",
                "roles",
                List.of("MFC_WORKER"),
                "centers",
                List.of("MFC-IT-01"),
                "reason",
                "정지 시험"));
    assertThat(disabled.status()).isEqualTo(200);
    assertThat(refresh(w.path("tokens").path("refreshToken").asText(), "pda-5").status())
        .isEqualTo(401);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM employee_account_change WHERE operator_id = 'OP-IT-WORKER1' AND changed_by = 'OP-IT-ADMIN1'",
                Integer.class))
        .isEqualTo(1);
  }

  @Test
  void devTokenExchangeAcceptsOnlyDevClientTokens() throws Exception {
    Res ok = devExchange(directLogin("pmart-dev-cli", "worker1"));
    assertThat(ok.status()).as(ok.body().toString()).isEqualTo(200);
    assertThat(decode(ok.body().path("tokens").path("accessToken").asText()).getSubject())
        .isEqualTo("OP-IT-WORKER1");

    assertThat(devExchange(directLogin("other-client", "worker1")).status())
        .as("azp 가 개발 클라이언트 아님")
        .isEqualTo(401);
    assertThat(devExchange("not-a-token").status()).isEqualTo(401);
    Res ghost = devExchange(directLogin("pmart-dev-cli", "ghost1"));
    assertThat(ghost.status()).isEqualTo(403);
    assertThat(ghost.body().path("code").asText()).isEqualTo("EMPLOYEE_NOT_REGISTERED");
  }

  @Test
  void backofficeUsesSsoAndLogoutEndsKeycloakSession() throws Exception {
    String bo = "http://backoffice.test/auth/callback";
    Browser b = new Browser();

    // 1) 첫 로그인 — 비밀번호. 백오피스는 prompt=login 이 없다
    String v1 = verifier();
    HttpResponse<String> start = b.get(startUrl("backoffice", "tab-1", v1) + "&returnUri=" + bo);
    String kcUrl = start.headers().firstValue("Location").orElseThrow();
    assertThat(kcUrl).doesNotContain("prompt=");
    Matcher m = FORM_ACTION.matcher(b.get(kcUrl).body());
    assertThat(m.find()).isTrue();
    HttpResponse<String> submitted =
        b.postForm(
            m.group(1).replace("&amp;", "&"),
            Map.of("username", "sso1", "password", "pw-sso1", "credentialId", ""));
    URI back1 =
        URI.create(
            b.get(submitted.headers().firstValue("Location").orElseThrow())
                .headers()
                .firstValue("Location")
                .orElseThrow());
    assertThat(back1.toString()).startsWith(bo + "?ticket=");
    Res first = exchange(param(back1, "ticket"), v1, "tab-1");
    assertThat(first.status()).isEqualTo(200);
    assertThat(keycloakSessions("sso1")).as("SSO — Keycloak 세션이 남는다").isEqualTo(1);

    // 저장한 Keycloak ID 토큰에는 이름·이메일이 없다(클라이언트 기본 범위 basic 만)
    String idToken =
        jdbc.queryForObject(
            "SELECT keycloak_id_token FROM employee_session WHERE session_id = ?",
            String.class,
            first.body().path("tokens").path("sessionId").asText());
    String payload =
        new String(
            java.util.Base64.getUrlDecoder().decode(idToken.split("[.]")[1]),
            java.nio.charset.StandardCharsets.UTF_8);
    assertThat(payload).doesNotContain("\"email\"").doesNotContain("\"name\"").contains("\"sid\"");

    // 2) 새 탭(같은 브라우저) — 비밀번호 없이 바로 티켓
    String v2 = verifier();
    String kc2 =
        b.get(startUrl("backoffice", "tab-2", v2) + "&returnUri=" + bo)
            .headers()
            .firstValue("Location")
            .orElseThrow();
    HttpResponse<String> sso = b.get(kc2);
    assertThat(sso.statusCode()).as("로그인 화면 없이 콜백으로").isEqualTo(302);
    URI back2 =
        URI.create(
            b.get(sso.headers().firstValue("Location").orElseThrow())
                .headers()
                .firstValue("Location")
                .orElseThrow());
    Res second = exchange(param(back2, "ticket"), v2, "tab-2");
    assertThat(second.status()).isEqualTo(200);
    assertThat(second.body().path("operatorId").asText()).isEqualTo("OP-IT-SSO1");

    // 2-1) 앱이 열릴 때 조용히 확인(silent, 2026-11-13) — prompt=none, 화면 없이 바로 티켓
    String v2s = verifier();
    String kcSilent =
        b.get(startUrl("backoffice", "tab-2s", v2s) + "&returnUri=" + bo + "&silent=true")
            .headers()
            .firstValue("Location")
            .orElseThrow();
    assertThat(kcSilent).contains("prompt=none");
    HttpResponse<String> silentKc = b.get(kcSilent);
    assertThat(silentKc.statusCode()).as("화면 없이 콜백으로").isEqualTo(302);
    URI back2s =
        URI.create(
            b.get(silentKc.headers().firstValue("Location").orElseThrow())
                .headers()
                .firstValue("Location")
                .orElseThrow());
    assertThat(back2s.toString()).startsWith(bo + "?ticket=");
    assertThat(exchange(param(back2s, "ticket"), v2s, "tab-2s").status()).isEqualTo(200);

    // 3) 로그아웃 → Keycloak 로그아웃 주소 → 브라우저가 가면 확인 화면 없이 백오피스 로그인 화면으로
    Res out =
        withToken(
            "POST",
            "/employee-auth/logouts",
            second.body().path("tokens").path("accessToken").asText(),
            Map.of());
    assertThat(out.status()).isEqualTo(200);
    String idp = out.body().path("idpLogoutUri").asText();
    assertThat(idp)
        .startsWith(kcBase() + "/realms/pmart-staff/protocol/openid-connect/logout?")
        .contains("id_token_hint=")
        .contains("post_logout_redirect_uri=http://backoffice.test/login");
    HttpResponse<String> kcLogout = b.get(idp);
    assertThat(kcLogout.statusCode()).as(kcLogout.body()).isEqualTo(302);
    assertThat(kcLogout.headers().firstValue("Location").orElseThrow())
        .startsWith("http://backoffice.test/login");
    assertThat(keycloakSessions("sso1")).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM employee_session WHERE operator_id = 'OP-IT-SSO1'"
                    + " AND state = 'REVOKED' AND keycloak_id_token IS NOT NULL",
                Integer.class))
        .as("끝난 세션의 ID 토큰은 지운다")
        .isZero();

    // 3-1) 로그아웃 뒤 조용히 확인 → Keycloak 세션 없음 → 앱에는 LOGIN_REQUIRED(오류 화면이 아니라 로그인 버튼)
    String kcSilentOut =
        b.get(startUrl("backoffice", "tab-3s", verifier()) + "&returnUri=" + bo + "&silent=true")
            .headers()
            .firstValue("Location")
            .orElseThrow();
    HttpResponse<String> noSession = b.get(kcSilentOut);
    assertThat(noSession.statusCode()).as("화면 없이 콜백으로(login_required)").isEqualTo(302);
    HttpResponse<String> backOut = b.get(noSession.headers().firstValue("Location").orElseThrow());
    assertThat(backOut.headers().firstValue("Location").orElseThrow())
        .isEqualTo(bo + "?error=LOGIN_REQUIRED");

    // 4) 그 뒤 새 로그인은 다시 비밀번호
    String kc3 =
        b.get(startUrl("backoffice", "tab-3", verifier()) + "&returnUri=" + bo)
            .headers()
            .firstValue("Location")
            .orElseThrow();
    HttpResponse<String> page = b.get(kc3);
    assertThat(page.statusCode()).isEqualTo(200);
    assertThat(FORM_ACTION.matcher(page.body()).find()).isTrue();
  }

  // ------------------------------------------------------------------ Keycloak 도우미

  Res devExchange(String kcToken) {
    return http.post()
        .uri("/internal/dev-token-exchange")
        .headers(
            h -> {
              h.set("Idempotency-Key", UUID.randomUUID().toString());
              h.set("X-Source-System", "e2e-script");
              h.set("X-Device-Id", "e2e");
            })
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("keycloakAccessToken", kcToken))
        .exchange((q, r) -> new Res(r.getStatusCode().value(), read(r.bodyTo(byte[].class))));
  }

  String directLogin(String clientId, String user) throws Exception {
    HttpResponse<String> r =
        new Browser()
            .postForm(
                kcBase() + "/realms/" + REALM + "/protocol/openid-connect/token",
                Map.of(
                    "grant_type",
                    "password",
                    "client_id",
                    clientId,
                    "username",
                    user,
                    "password",
                    "pw-" + user));
    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    return mapper.readTree(r.body()).path("access_token").asText();
  }

  int keycloakSessions(String user) throws Exception {
    Browser b = new Browser();
    HttpResponse<String> t =
        b.postForm(
            kcBase() + "/realms/master/protocol/openid-connect/token",
            Map.of(
                "grant_type",
                "password",
                "client_id",
                "admin-cli",
                "username",
                "admin",
                "password",
                "admin"));
    String adminToken = mapper.readTree(t.body()).path("access_token").asText();
    HttpResponse<String> users =
        b.client.send(
            HttpRequest.newBuilder(
                    URI.create(
                        kcBase() + "/admin/realms/" + REALM + "/users?exact=true&username=" + user))
                .header("Authorization", "Bearer " + adminToken)
                .build(),
            HttpResponse.BodyHandlers.ofString());
    String id = mapper.readTree(users.body()).get(0).path("id").asText();
    HttpResponse<String> sessions =
        b.client.send(
            HttpRequest.newBuilder(
                    URI.create(kcBase() + "/admin/realms/" + REALM + "/users/" + id + "/sessions"))
                .header("Authorization", "Bearer " + adminToken)
                .build(),
            HttpResponse.BodyHandlers.ofString());
    return mapper.readTree(sessions.body()).size();
  }
}
