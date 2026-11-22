package com.pmart.riderauth.infrastructure;

import com.pmart.riderauth.application.NaverExchangeProperties;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 네이버 토큰 엔드포인트({@code POST /oauth2/token}, client_secret_post) — 인가 코드 + PKCE verifier 를 ID 토큰으로
 * 바꾼다 (ADR-0078). 공용 {@code RestClient.Builder}(서비스 간 호출 — 로드밸런서·호출 헤더)를 쓰지 않고 이 호출 전용 클라이언트를 둔다:
 * 사용자 토큰·내부 헤더를 네이버로 보내지 않고, 연결·응답 제한을 따로 둔다.
 *
 * <p>응답에서 {@code id_token}·{@code access_token} 을 꺼낸다(2026-11-22 RA-1 — access token 은 프로필 API·연결
 * 끊기에 한 번 쓰고 버린다, 저장·응답 금지). refresh token 은 읽지 않는다. 코드·verifier·secret·토큰은 로그·예외 메시지에 넣지 않는다 — 거절은
 * 네이버 오류 코드(예 invalid_grant)만 싣는다.
 */
@Component
public class NaverTokenClient {

  static final String TOKEN_PATH = "/oauth2/token";
  private static final Pattern ERROR_CODE = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

  /** 네이버가 거절함(4xx 또는 본문 {@code error}). {@link #errorCode()} 만 로그에 남긴다. */
  public static class Rejected extends RuntimeException {
    private final String errorCode;

    public Rejected(String errorCode) {
      super("네이버 토큰 교환 거절: " + errorCode);
      this.errorCode = errorCode;
    }

    public String errorCode() {
      return errorCode;
    }
  }

  /** 네이버 응답 없음·시간 초과·5xx. 재시도 대상. */
  public static class Unavailable extends RuntimeException {
    public Unavailable(String message, @Nullable Throwable cause) {
      super(message, cause);
    }
  }

  /** 교환 요청. secret 은 여기서만 쓰고 남기지 않는다. */
  public record Exchange(
      String clientId,
      String clientSecret,
      String code,
      String codeVerifier,
      String redirectUri,
      @Nullable String state) {
    @Override
    public String toString() {
      return "Exchange[clientId=" + clientId + "]";
    }
  }

  private final RestClient client;
  private final ObjectMapper mapper;
  private final String tokenBaseUrl;
  private final String profileBaseUrl;

  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(NaverTokenClient.class);

  /** 교환 결과. access token 은 프로필·끊기에만 — 로그·저장 금지. */
  public record Tokens(String idToken, @Nullable String accessToken) {
    @Override
    public String toString() {
      return "NaverTokenClient.Tokens[***]";
    }
  }

  /** 프로필 API 결과(이메일 원문 — 개인정보, 로그·toString 금지). */
  public record Profile(@Nullable String email) {
    public static final Profile NONE = new Profile(null);

    @Override
    public String toString() {
      return "NaverTokenClient.Profile[***]";
    }
  }

  public NaverTokenClient(NaverExchangeProperties props, ObjectMapper mapper) {
    HttpClient http =
        HttpClient.newBuilder()
            .connectTimeout(props.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
    factory.setReadTimeout(props.readTimeout());
    this.client =
        RestClient.builder().baseUrl(props.tokenBaseUrl()).requestFactory(factory).build();
    this.mapper = mapper;
    this.tokenBaseUrl = props.tokenBaseUrl();
    this.profileBaseUrl = props.profileBaseUrl();
  }

  /**
   * @return 네이버 OIDC id_token·access token
   * @throws Rejected 네이버 거절(4xx·본문 error·id_token 없음)
   * @throws Unavailable 응답 없음·시간 초과·5xx·해석 불가 응답
   */
  public Tokens exchange(Exchange e) {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "authorization_code");
    form.add("client_id", e.clientId());
    form.add("client_secret", e.clientSecret());
    form.add("code", e.code());
    form.add("code_verifier", e.codeVerifier());
    form.add("redirect_uri", e.redirectUri());
    if (e.state() != null && !e.state().isBlank()) {
      form.add("state", e.state());
    }
    Response r;
    try {
      r =
          client
              .post()
              .uri(TOKEN_PATH)
              .contentType(MediaType.APPLICATION_FORM_URLENCODED)
              .accept(MediaType.APPLICATION_JSON)
              .body(form)
              .exchange(
                  (req, res) -> {
                    byte[] body = res.getBody().readAllBytes();
                    return new Response(
                        res.getStatusCode().value(), new String(body, StandardCharsets.UTF_8));
                  });
    } catch (ResourceAccessException ex) {
      // 원인 메시지에 요청 정보가 없도록 예외 종류만 남긴다
      throw new Unavailable("네이버 토큰 엔드포인트 응답 없음(" + rootName(ex) + ")", null);
    } catch (RestClientException ex) {
      throw new Unavailable("네이버 토큰 호출 실패(" + rootName(ex) + ")", null);
    }
    return interpret(r);
  }

  record Response(int status, String body) {}

  Tokens interpret(Response r) {
    if (r.status() >= 500) {
      throw new Unavailable("네이버 토큰 엔드포인트 " + r.status(), null);
    }
    JsonNode n = parse(r.body());
    String error = n == null ? null : text(n, "error");
    if (r.status() >= 400 || error != null) {
      // 네이버는 오류를 200 + {"error":…} 로도 준다
      throw new Rejected(error == null ? "http_" + r.status() : safeCode(error));
    }
    if (r.status() >= 300 || n == null) {
      throw new Unavailable("네이버 토큰 응답을 해석할 수 없음(" + r.status() + ")", null);
    }
    String idToken = text(n, "id_token");
    if (idToken == null) {
      // scope 에 openid 가 없던 인가 등 — 재시도해도 같으므로 거절로 본다
      throw new Rejected("missing_id_token");
    }
    return new Tokens(idToken, text(n, "access_token"));
  }

  /**
   * 네이버 프로필 API({@code GET {profileBaseUrl}/v1/nid/me}, Bearer access token) 한 번 — {@code
   * response.email}(라이더가 이메일 제공에 동의했을 때만, 2026-11-22 RA-1 ④ — 고객 BR-77 과 같음). 어떤 실패도 예외 없이 {@link
   * Profile#NONE}. 로그는 짧은 까닭 코드만.
   */
  public Profile profile(String accessToken) {
    Response r;
    try {
      r =
          client
              .get()
              .uri(profileBaseUrl + "/v1/nid/me")
              .header("Authorization", "Bearer " + accessToken)
              .accept(MediaType.APPLICATION_JSON)
              .exchange(
                  (req, res) ->
                      new Response(
                          res.getStatusCode().value(),
                          new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8)));
    } catch (RestClientException ex) {
      log.info("네이버 프로필 생략 reason={}", rootName(ex));
      return Profile.NONE;
    }
    if (r.status() != 200) {
      log.info("네이버 프로필 생략 reason=http_{}", r.status());
      return Profile.NONE;
    }
    JsonNode n = parse(r.body());
    if (n == null) {
      log.info("네이버 프로필 생략 reason=empty_response");
      return Profile.NONE;
    }
    // 네이버는 실패도 200 + resultcode ≠ "00" 으로 줄 수 있다
    String resultCode = n.path("resultcode").asString("");
    if (!resultCode.isEmpty() && !"00".equals(resultCode)) {
      log.info("네이버 프로필 생략 reason=resultcode");
      return Profile.NONE;
    }
    String email = n.path("response").path("email").asString("");
    if (email.isBlank()) {
      log.info("네이버 프로필 이메일 생략 reason=missing_email");
      return Profile.NONE;
    }
    return new Profile(email.strip());
  }

  /**
   * 네이버 앱 연결 끊기(연동 해제, {@code grant_type=delete} — 고객 BR-74 와 같은 호출, 2026-11-22 RA-1 ②③). 끊으면 다음
   * 네이버 로그인에서 정보 제공 동의를 다시 받는다. 어떤 실패도 예외 없이 false. access token·secret 은 로그 금지 — 결과 코드만.
   */
  public boolean unlink(String clientId, String clientSecret, String accessToken) {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "delete");
    form.add("client_id", clientId);
    form.add("client_secret", clientSecret);
    form.add("access_token", accessToken);
    form.add("service_provider", "NAVER");
    Response r;
    try {
      r =
          client
              .post()
              .uri(tokenBaseUrl + "/oauth2.0/token")
              .contentType(MediaType.APPLICATION_FORM_URLENCODED)
              .accept(MediaType.APPLICATION_JSON)
              .body(form)
              .exchange(
                  (req, res) ->
                      new Response(
                          res.getStatusCode().value(),
                          new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8)));
    } catch (RestClientException ex) {
      log.info("네이버 연결 끊기 실패 reason={}", rootName(ex));
      return false;
    }
    JsonNode n = parse(r.body());
    if (r.status() != 200 || n == null || !"success".equals(n.path("result").asString(""))) {
      String error = n == null ? null : text(n, "error");
      log.info(
          "네이버 연결 끊기 실패 reason={}",
          r.status() != 200 ? "http_" + r.status() : error == null ? "no_result" : safeCode(error));
      return false;
    }
    return true;
  }

  private @Nullable JsonNode parse(String body) {
    if (body == null || body.isBlank()) {
      return null;
    }
    try {
      JsonNode n = mapper.readTree(body);
      return n != null && n.isObject() ? n : null;
    } catch (JacksonException ex) {
      return null;
    }
  }

  private static @Nullable String text(JsonNode n, String field) {
    JsonNode v = n.get(field);
    if (v == null || v.isNull()) {
      return null;
    }
    String s = v.asString();
    return s == null || s.isBlank() ? null : s;
  }

  private static String safeCode(String error) {
    return ERROR_CODE.matcher(error).matches() ? error : "unknown_error";
  }

  /** 가장 안쪽 원인의 종류만(메시지에는 주소·요청 정보가 섞일 수 있어 쓰지 않는다). */
  private static String rootName(Throwable t) {
    return org.springframework.core.NestedExceptionUtils.getMostSpecificCause(t)
        .getClass()
        .getSimpleName();
  }
}
