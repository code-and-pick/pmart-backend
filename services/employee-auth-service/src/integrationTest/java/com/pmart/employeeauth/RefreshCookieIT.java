package com.pmart.employeeauth;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.employeeauth.application.EmployeeTokens;
import com.pmart.employeeauth.infrastructure.EmployeeAuthStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 웹 앱 refresh 쿠키 + Signed Double-Submit CSRF(ADR-0059). Keycloak 없이 로그인 티켓을 직접 넣고(콜백까지는
 * EmployeeLoginIT) 티켓 교환 → 쿠키 갱신 → 로그아웃을 실제 HTTP 로 본다: 쿠키 속성(HttpOnly·Secure·SameSite·세션별 Path),
 * CSRF(헤더·쿠키·서명·세션 묶음 — 틀려도 세션은 그대로), 소유권(다른 세션 주소·다른 기기·본문 경로는 거절하고 토큰을 쓰지 않음), 회전·재사용, 로그아웃 때 쿠키
 * 삭제, 쿠키를 쓰지 않는 앱(mfc).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "pmart.employee-auth.apps.backoffice.refresh-cookie=true",
      "pmart.employee-auth.apps.mfc.return-uris=pmart-mfc://login",
      "pmart.test.context=refresh-cookie"
    })
class RefreshCookieIT extends PostgresKafkaContainerSupport {

  static final String RT = "__Secure-pmart_ea_rt";
  static final String CSRF = "__Secure-pmart_ea_csrf_";
  static final String BO_RETURN = "http://backoffice.test/auth/callback";

  @LocalServerPort int port;
  @Autowired EmployeeAuthStore store;
  @Autowired JdbcTemplate jdbc;

  final JsonMapper mapper = JsonMapper.builder().build();
  final HttpClient client = HttpClient.newHttpClient();

  record Res(int status, JsonNode body, List<String> setCookies) {
    /** 이름 → Set-Cookie 한 줄 전체. */
    Map<String, String> cookies() {
      Map<String, String> m = new LinkedHashMap<>();
      for (String c : setCookies) {
        m.put(c.substring(0, c.indexOf('=')), c);
      }
      return m;
    }
  }

  /** 탭 하나의 세션(쿠키 값은 브라우저가 들고 있는 것). */
  record Tab(String sessionId, String device, String rt, String csrf, String access) {}

  @BeforeEach
  void setUp() {
    String op = "OP-IT-CK1";
    if (store.account(op, false).isEmpty()) {
      store.insertAccount(op, "ACTIVE", List.of("MD"), List.of(), Instant.now());
    }
  }

  // ------------------------------------------------------------------ 도우미

  Res send(HttpRequest.Builder b) throws Exception {
    HttpResponse<String> r = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    JsonNode body =
        r.body() == null || r.body().isBlank()
            ? mapper.createObjectNode()
            : mapper.readTree(r.body());
    return new Res(r.statusCode(), body, r.headers().allValues("Set-Cookie"));
  }

  HttpRequest.Builder post(String path, String json) {
    return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
        .header("Content-Type", "application/json")
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .header("X-Source-System", "backoffice-web")
        .POST(HttpRequest.BodyPublishers.ofString(json));
  }

  /** 콜백까지 끝난 것처럼 티켓을 넣고 교환한다. */
  Res exchange(String app, String returnUri, String device) throws Exception {
    String ticket = EmployeeTokens.random();
    String verifier = EmployeeTokens.random() + "-v";
    Instant now = Instant.now();
    store.insertTicket(
        EmployeeTokens.hash(ticket),
        "OP-IT-CK1",
        app,
        device,
        EmployeeTokens.s256(verifier),
        now,
        now.plusSeconds(60),
        returnUri,
        null);
    return send(
        post(
                "/employee-auth/tokens",
                "{\"ticket\":\"" + ticket + "\",\"codeVerifier\":\"" + verifier + "\"}")
            .header("X-Device-Id", device));
  }

  Tab login(String device) throws Exception {
    Res r = exchange("backoffice", BO_RETURN, device);
    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    String sid = r.body().path("tokens").path("sessionId").asText();
    Map<String, String> c = r.cookies();
    return new Tab(
        sid,
        device,
        value(c.get(RT)),
        value(c.get(CSRF + sid)),
        r.body().path("tokens").path("accessToken").asText());
  }

  static String value(String setCookie) {
    String pair = setCookie.split(";", 2)[0];
    return pair.substring(pair.indexOf('=') + 1);
  }

  Res refreshCookie(
      String pathSession,
      String device,
      String rtCookie,
      String csrfCookieName,
      String csrfCookie,
      String csrfHeader)
      throws Exception {
    HttpRequest.Builder b =
        post("/employee-auth/tokens/refresh/" + pathSession, "").header("X-Device-Id", device);
    StringBuilder cookie = new StringBuilder();
    if (rtCookie != null) {
      cookie.append(RT).append('=').append(rtCookie);
    }
    if (csrfCookie != null) {
      cookie
          .append(cookie.isEmpty() ? "" : "; ")
          .append(csrfCookieName)
          .append('=')
          .append(csrfCookie);
    }
    if (!cookie.isEmpty()) {
      b.header("Cookie", cookie.toString());
    }
    if (csrfHeader != null) {
      b.header("X-CSRF-Token", csrfHeader);
    }
    return send(b);
  }

  Res refresh(Tab t) throws Exception {
    return refreshCookie(
        t.sessionId(), t.device(), t.rt(), CSRF + t.sessionId(), t.csrf(), t.csrf());
  }

  // ------------------------------------------------------------------ 시험

  @Test
  void cookieAppGetsHttpOnlyRefreshCookieAndSignedCsrfCookie() throws Exception {
    Res r = exchange("backoffice", BO_RETURN, "tab-a");
    assertThat(r.status()).isEqualTo(200);
    JsonNode tokens = r.body().path("tokens");
    String sid = tokens.path("sessionId").asText();
    assertThat(tokens.path("refreshToken").isNull() || tokens.path("refreshToken").isMissingNode())
        .as("본문에 refresh token 없음")
        .isTrue();
    assertThat(tokens.path("refreshTransport").asText()).isEqualTo("COOKIE");
    assertThat(tokens.path("accessToken").asText()).isNotBlank();

    Map<String, String> c = r.cookies();
    assertThat(c.get(RT))
        .contains("HttpOnly")
        .contains("Secure")
        .contains("SameSite=Strict")
        .contains("Path=/api/v1/employee-auth/tokens/refresh/" + sid)
        .doesNotContain("Max-Age");
    assertThat(c.get(CSRF + sid))
        .doesNotContain("HttpOnly")
        .contains("Secure")
        .contains("SameSite=Strict")
        .contains("Path=/;")
        .doesNotContain("Max-Age");
    assertThat(value(c.get(CSRF + sid))).matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
  }

  @Test
  void refreshRotatesCookieKeepsCsrfAndDetectsReuse() throws Exception {
    Tab t = login("tab-b");
    Res ok = refresh(t);
    assertThat(ok.status()).as(ok.body().toString()).isEqualTo(200);
    assertThat(
            ok.body().path("refreshToken").isNull()
                || ok.body().path("refreshToken").isMissingNode())
        .isTrue();
    assertThat(ok.body().path("refreshTransport").asText()).isEqualTo("COOKIE");
    assertThat(ok.cookies()).containsOnlyKeys(RT); // CSRF 는 세션 동안 그대로
    String newRt = value(ok.cookies().get(RT));
    assertThat(newRt).isNotEqualTo(t.rt());
    assertThat(ok.cookies().get(RT))
        .contains("Path=/api/v1/employee-auth/tokens/refresh/" + t.sessionId());

    // 같은 CSRF 값으로 새 쿠키 갱신이 이어진다
    Tab next = new Tab(t.sessionId(), t.device(), newRt, t.csrf(), t.access());
    assertThat(refresh(next).status()).isEqualTo(200);

    // 옛 쿠키 재사용 → 세션 종료
    Res reused = refresh(t);
    assertThat(reused.status()).isEqualTo(401);
    assertThat(reused.body().path("code").asText()).isEqualTo("TOKEN_REUSED");
    assertThat(sessionState(t.sessionId())).isEqualTo("REVOKED");
  }

  @Test
  void csrfFailuresAre403AndDoNotEndSession() throws Exception {
    Tab a = login("tab-c1");
    Tab b = login("tab-c2");
    String name = CSRF + a.sessionId();

    // 헤더 없음 → 400(필수 헤더)
    Res noHeader = refreshCookie(a.sessionId(), a.device(), a.rt(), name, a.csrf(), null);
    assertThat(noHeader.status()).isEqualTo(400);
    // 쿠키 없음
    Res noCookie = refreshCookie(a.sessionId(), a.device(), a.rt(), name, null, a.csrf());
    assertThat(noCookie.status()).isEqualTo(403);
    assertThat(noCookie.body().path("code").asText()).isEqualTo("CSRF_INVALID");
    // 헤더 ≠ 쿠키
    assertThat(refreshCookie(a.sessionId(), a.device(), a.rt(), name, a.csrf(), b.csrf()).status())
        .isEqualTo(403);
    // 다른 세션의 (서명이 맞는) 값을 쿠키·헤더 둘 다에 — 서명이 세션에 묶여 거절
    assertThat(refreshCookie(a.sessionId(), a.device(), a.rt(), name, b.csrf(), b.csrf()).status())
        .isEqualTo(403);
    // 심어 넣은(서명 없는) 값을 쿠키·헤더 둘 다에
    assertThat(refreshCookie(a.sessionId(), a.device(), a.rt(), name, "x.y", "x.y").status())
        .isEqualTo(403);

    // 세션도 refresh token 도 그대로
    assertThat(sessionState(a.sessionId())).isEqualTo("ACTIVE");
    assertThat(refresh(a).status()).isEqualTo(200);
  }

  @Test
  void ownershipIsCheckedAndRejectedTokenIsNotSpent() throws Exception {
    Tab a = login("tab-d1");
    Tab b = login("tab-d2");

    // a 의 refresh 쿠키를 b 의 주소로(b 의 CSRF 는 맞게) → 소유권 거절
    Res wrongSession =
        refreshCookie(b.sessionId(), b.device(), a.rt(), CSRF + b.sessionId(), b.csrf(), b.csrf());
    assertThat(wrongSession.status()).isEqualTo(401);
    assertThat(wrongSession.body().path("code").asText()).isEqualTo("TOKEN_INVALID");
    // 다른 기기
    assertThat(
            refreshCookie(
                    a.sessionId(), "other-device", a.rt(), CSRF + a.sessionId(), a.csrf(), a.csrf())
                .status())
        .isEqualTo(401);
    // refresh 쿠키 없음
    assertThat(
            refreshCookie(a.sessionId(), a.device(), null, CSRF + a.sessionId(), a.csrf(), a.csrf())
                .status())
        .isEqualTo(401);
    // 쿠키 앱 세션을 본문 경로로
    Res body =
        send(
            post("/employee-auth/tokens/refresh", "{\"refreshToken\":\"" + a.rt() + "\"}")
                .header("X-Device-Id", a.device()));
    assertThat(body.status()).isEqualTo(401);
    assertThat(body.body().path("code").asText()).isEqualTo("TOKEN_INVALID");

    // 거절된 시도들은 토큰을 쓰지 않았다 — a·b 둘 다 그대로 갱신된다
    assertThat(refresh(a).status()).isEqualTo(200);
    assertThat(refresh(b).status()).isEqualTo(200);
  }

  @Test
  void logoutEndsSessionAndClearsBothCookies() throws Exception {
    Tab t = login("tab-e");
    Res out =
        send(post("/employee-auth/logouts", "{}").header("Authorization", "Bearer " + t.access()));
    assertThat(out.status()).as(out.body().toString()).isEqualTo(200);
    Map<String, String> c = out.cookies();
    assertThat(c.get(RT))
        .contains("Max-Age=0")
        .contains("Path=/api/v1/employee-auth/tokens/refresh/" + t.sessionId())
        .contains("HttpOnly");
    assertThat(c.get(CSRF + t.sessionId())).contains("Max-Age=0").contains("Path=/;");
    assertThat(sessionState(t.sessionId())).isEqualTo("REVOKED");
    assertThat(refresh(t).status()).isEqualTo(401);
  }

  @Test
  void bodyAppKeepsBodyRefreshAndCannotUseCookiePath() throws Exception {
    Res r = exchange("mfc", "pmart-mfc://login", "pda-1");
    assertThat(r.status()).isEqualTo(200);
    JsonNode tokens = r.body().path("tokens");
    assertThat(tokens.path("refreshTransport").asText()).isEqualTo("BODY");
    assertThat(tokens.path("refreshToken").asText()).isNotBlank();
    assertThat(r.setCookies()).isEmpty();

    String sid = tokens.path("sessionId").asText();
    String rt = tokens.path("refreshToken").asText();
    // 쿠키 경로로는 안 된다(CSRF 값이 없으니 403 — 서명 값을 알아도 소유권에서 401)
    assertThat(refreshCookie(sid, "pda-1", rt, CSRF + sid, "x.y", "x.y").status()).isEqualTo(403);
    // 본문 갱신은 그대로
    Res body =
        send(
            post("/employee-auth/tokens/refresh", "{\"refreshToken\":\"" + rt + "\"}")
                .header("X-Device-Id", "pda-1"));
    assertThat(body.status()).isEqualTo(200);
    assertThat(body.body().path("refreshTransport").asText()).isEqualTo("BODY");
    assertThat(body.setCookies()).isEmpty();
  }

  String sessionState(String sessionId) {
    return jdbc.queryForObject(
        "SELECT state FROM employee_session WHERE session_id = ?", String.class, sessionId);
  }
}
